import contextlib
import hashlib
import io
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import council_fixture as cf  # noqa: E402
import draft_apk  # noqa: E402
import gen_catalog  # noqa: E402
import translib  # noqa: E402

REAL_BUILD = os.path.join(translib.REPO_ROOT, "build.sh")
MANIFEST = """<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.jjricks.volt"
    android:versionCode="1"
    android:versionName="1.2.3">
    <application
        android:label="Volt">
    </application>
</manifest>
"""
LOCALES = """<?xml version="1.0" encoding="utf-8"?>
<locale-config xmlns:android="http://schemas.android.com/apk/res/android">
    <locale android:name="en" />
</locale-config>
"""
WRITE_EVENTS = ("os.mkdir", "os.remove", "os.rename", "os.rmdir", "os.symlink", "os.link", "os.truncate",
                "os.chmod", "os.utime", "shutil.copyfile", "shutil.move", "shutil.rmtree")


class WriteWatch(object):
    """Every file this process writes while active, from Python's audit hook (which cannot be removed)."""
    active = None
    installed = False

    def __init__(self):
        self.paths = []
        self.commands = []

    def __enter__(self):
        if not WriteWatch.installed:
            sys.addaudithook(WriteWatch.hook)
            WriteWatch.installed = True
        WriteWatch.active = self
        return self

    def __exit__(self, *exc):
        WriteWatch.active = None
        return False

    @staticmethod
    def hook(event, args):
        watch = WriteWatch.active
        if watch is None:
            return
        if event == "open":
            flags = args[2] if isinstance(args[2], int) else 0
            if flags & (os.O_WRONLY | os.O_RDWR | os.O_CREAT) and isinstance(args[0], (str, bytes)):
                watch.paths.append(os.fsdecode(args[0]))
        elif event in ("os.remove", "os.rmdir") and args[-1] not in (None, -1):
            return  # inside shutil.rmtree, named relative to a folder its own event already gave
        elif event in WRITE_EVENTS and args and isinstance(args[0], (str, bytes, os.PathLike)):
            watch.paths.append(os.fsdecode(args[0]))
            if event in ("os.rename", "shutil.copyfile", "shutil.move") and len(args) > 1:
                watch.paths.append(os.fsdecode(args[1]))
        elif event == "subprocess.Popen":
            watch.commands.append(list(args[1]))


def tree_state(root):
    """{relative path: content hash}, .git included, to show a run left a tree exactly as it was."""
    state = {}
    for directory, folders, files in os.walk(root):
        for name in folders:
            state[os.path.relpath(os.path.join(directory, name), root)] = "dir"
        for name in files:
            path = os.path.join(directory, name)
            with open(path, "rb") as handle:
                state[os.path.relpath(path, root)] = hashlib.sha1(handle.read()).hexdigest()
    return state


def read_file(path):
    with open(path, encoding="utf-8") as handle:
        return handle.read()


def run_tool(*args):
    out, err = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        code = draft_apk.main(list(args))
    return code, out.getvalue() + err.getvalue()


def read_report(path):
    with open(path, encoding="utf-8") as handle:
        lines = [line.rstrip("\n").split("\t") for line in handle]
    return dict(((r[0], r[1]), dict(zip(lines[0], r))) for r in lines[1:])


class Base(unittest.TestCase):
    def setUp(self):
        self.repo = cf.FixtureRepo()
        self.repo.write("AndroidManifest.xml", MANIFEST)
        self.repo.write("res/xml/locales_config.xml", LOCALES)
        with open(REAL_BUILD, encoding="utf-8") as handle:
            self.repo.write("build.sh", handle.read())
        self.git("init", "-q")
        self.git("add", "-A")
        self.git("-c", "user.name=T", "-c", "user.email=t@example.invalid", "-c", "commit.gpgsign=false",
                 "-c", "core.hooksPath=" + os.devnull, "commit", "-q", "-m", "fixture")
        self.scratch = os.path.realpath(tempfile.mkdtemp(prefix="l10n-draft-apk-"))
        self.workdir = os.path.join(self.scratch, "work")
        self.out = os.path.join(self.scratch, "out")
        self.root = os.path.realpath(self.repo.root)
        self.paths = translib.Paths(self.root)
        self.sources, _ = translib.load_source(self.paths)

    def tearDown(self):
        self.repo.close()
        shutil.rmtree(self.scratch, ignore_errors=True)

    def git(self, *args):
        subprocess.run(["git"] + list(args), cwd=self.repo.root, check=True, stdout=subprocess.PIPE)

    def draft(self, tag="es", area="common", name="claude-opus", texts=None, stale=()):
        texts = cf.SPANISH if texts is None else texts
        rows = []
        for s in self.sources[area]:
            if s.key not in texts:
                continue
            items = translib.items_of(s.kind, texts[s.key])
            src = "1" * 40 if s.key in stale else translib.source_sha1(s.kind, s.items)
            rows.append((s.key, s.kind, items, 1, src))
        translib.write_draft(self.paths.drafts(tag, area) + "/%s.xml" % name, name, "claude-opus-5-5", rows)

    def run_tool(self, *args):
        return run_tool("--root", self.root, "--workdir", self.workdir, "--out", self.out, "--no-java", "--aapt", "off",
                        "--no-build", *args)

    def kept_copy(self, output):
        line = next(l for l in output.splitlines() if l.startswith("kept the copy: "))
        return translib.Paths(line[len("kept the copy: "):])


class GenerateTest(Base):
    def test_writes_nothing_outside_workdir_and_out(self):
        self.draft()
        before = tree_state(self.root)
        temporary = tempfile.tempdir
        with WriteWatch() as watch:
            code, output = self.run_tool("--tags", "es")
        self.assertEqual(code, 0, output)
        self.assertEqual(tree_state(self.root), before)
        allowed = (self.workdir, self.workdir + os.sep, self.out, self.out + os.sep, os.devnull)
        outside = [p for p in watch.paths if os.path.realpath(p) not in allowed
                   and not os.path.realpath(p).startswith(allowed[1::2])]
        self.assertEqual(outside, [])
        self.assertTrue(watch.paths, "the watch saw no writes at all")
        # Only read-only git, and only an extraction into the copy.
        for command in watch.commands:
            self.assertIn(command[0], ("git", "tar"), command)
            if command[0] == "git":
                self.assertTrue({"rev-parse", "status", "archive"} & set(command), command)
        # The copy is gone, and the report is what stays.
        self.assertEqual(os.listdir(self.workdir), [])
        self.assertEqual(sorted(os.listdir(self.out)), ["report-keys.tsv", "report.tsv"])
        self.assertFalse(os.path.exists(self.paths.res("values-es")))
        self.assertEqual(tempfile.tempdir, temporary)

    def test_the_copy_ships_the_language(self):
        self.draft()
        code, output = self.run_tool("--tags", "es", "--keep")
        self.assertEqual(code, 0, output)
        copy = self.kept_copy(output)
        text = read_file(copy.res("values-es", "strings_common.xml"))
        sha = translib.source_sha1("string", {"other": "Cancel"})
        self.assertIn('<!-- src:%s -->\n    <string name="common_cancel">Cancelar</string>' % sha, text)
        self.assertIn('<string name="l10n_tag">es</string>', text)
        self.assertIn("status\tbeta\n", read_file(os.path.join(copy.lang_dir(), "es.tsv")))
        self.assertIn('<locale android:name="es" />', read_file(copy.res("xml", "locales_config.xml")))
        manifest = read_file(os.path.join(copy.root, "AndroidManifest.xml"))
        self.assertIn('<application\n        android:localeConfig="@xml/locales_config"\n', manifest)
        languages = dict((l.tag, l) for l in gen_catalog.load_languages(copy.lang_dir()))
        self.assertEqual(languages["de"].status, "draft")
        # None of that reached the tree.
        self.assertIn("status\tdraft\n", read_file(os.path.join(self.paths.lang_dir(), "es.tsv")))
        self.assertNotIn("es", read_file(self.paths.res("xml", "locales_config.xml")).split('"'))

    def test_stale_and_failing_keys_fall_back(self):
        texts = dict(cf.SPANISH, common_move_widget="Mover a la posición")
        self.draft(texts=texts, stale=("common_cancel", "common_lock_waiting_short"))
        code, output = self.run_tool("--tags", "es", "--keep")
        self.assertEqual(code, 0, output)
        text = read_file(self.kept_copy(output).res("values-es", "strings_common.xml"))
        self.assertIn("common_not_reported", text)
        # Stale: English. Failing: English. A rung's full key goes with it.
        for key in ("common_cancel", "common_lock_waiting_short", "common_lock_waiting", "common_move_widget"):
            self.assertNotIn('name="%s"' % key, text)
        report = read_report(os.path.join(self.out, "report.tsv"))
        common = report[("es", "common")]
        translatable = len([s for s in self.sources["common"] if s.translatable])
        self.assertEqual(common["total"], str(translatable))
        self.assertEqual(common["translated"], str(len(cf.SPANISH) - 4))
        self.assertEqual(common["stale"], "2")
        self.assertEqual(common["failing"], "2")
        self.assertEqual(common["english_fallback"], str(translatable - (len(cf.SPANISH) - 4)))
        self.assertEqual(common["lock_alarm_total"], "3")
        self.assertEqual(common["lock_alarm_translated"], "1")
        self.assertEqual(common["lock_alarm_fallback"], "2")
        self.assertEqual(common["safety_total"], "4")
        self.assertEqual(common["safety_translated"], "2")
        keys = read_file(os.path.join(self.out, "report-keys.tsv"))
        self.assertIn("common_lock_waiting\tfailing\tladder: the ladder is incomplete without common_lock_waiting_short", keys)
        self.assertIn("common_cancel\tstale\t", keys)

    def test_a_failing_rung_takes_its_ladder_with_it(self):
        self.draft(texts=dict(cf.SPANISH, common_lock_waiting_short="Esperando %1$s"))
        code, output = self.run_tool("--tags", "es", "--keep")
        self.assertEqual(code, 0, output)
        text = read_file(self.kept_copy(output).res("values-es", "strings_common.xml"))
        self.assertNotIn("common_lock_waiting", text)
        keys = read_file(os.path.join(self.out, "report-keys.tsv"))
        self.assertIn("common_lock_waiting\tfailing\tladder: common_lock_waiting_short is failing", keys)

    def test_an_area_without_a_draft_is_all_english_and_counted(self):
        self.draft(area="ble", texts={"ble_unlock": "Desbloquear patinete", "ble_searching": "Buscando 2 patinetes"})
        code, output = self.run_tool("--tags", "es", "--keep")
        self.assertEqual(code, 0, output)
        report = read_report(os.path.join(self.out, "report.tsv"))
        self.assertEqual(report[("es", "common")]["translated"], "0")
        self.assertEqual(report[("es", "ble")]["translated"], "2")
        self.assertEqual(report[("es", "TOTAL")]["translated"], "2")
        # The tag's file exists anyway: without l10n_tag the app would treat the folder as English.
        copy = self.kept_copy(output)
        self.assertIn('name="l10n_tag"', read_file(copy.res("values-es", "strings_common.xml")))

    def test_a_key_the_english_lost_is_counted_not_written(self):
        self.draft()
        path = self.paths.drafts("es", "common") + "/claude-opus.xml"
        text = read_file(path).replace(
            "</resources>", '    <string name="common_gone" volt:src="%s">Ya no</string>\n</resources>' % ("2" * 40))
        self.repo.write(self.paths.rel(path), text)
        code, output = self.run_tool("--tags", "es", "--keep")
        self.assertEqual(code, 0, output)
        self.assertEqual(read_report(os.path.join(self.out, "report.tsv"))[("es", "common")]["unknown"], "1")
        self.assertNotIn("common_gone", read_file(self.kept_copy(output).res("values-es", "strings_common.xml")))

    def test_two_languages_and_drafts_from_another_root(self):
        self.draft(tag="de", texts={"common_cancel": "Abbrechen"})
        self.draft(tag="es", texts={"common_cancel": "Cancelar"})
        elsewhere = os.path.join(self.scratch, "drafts")
        os.makedirs(elsewhere + "/l10n")
        shutil.move(self.paths.root + "/l10n/work", elsewhere + "/l10n/work")
        code, output = self.run_tool("--tags", "es,de", "--drafts", elsewhere, "--keep")
        self.assertEqual(code, 0, output)
        copy = self.kept_copy(output)
        locales = read_file(copy.res("xml", "locales_config.xml"))
        self.assertIn('"es"', locales)
        self.assertIn('"de"', locales)
        self.assertTrue(os.path.exists(copy.res("values-de", "strings_common.xml")))
        report = read_report(os.path.join(self.out, "report.tsv"))
        self.assertEqual(report[("de", "TOTAL")]["translated"], "1")

    def test_name_selects_a_draft(self):
        self.draft()
        self.draft(name="claude-sonnet", texts={"common_cancel": "Anular"})
        code, output = self.run_tool("--tags", "es")
        self.assertEqual(code, 2)
        self.assertIn("several Claude drafts (claude-opus, claude-sonnet)", output)
        for name in ("sonnet", "claude-sonnet"):
            code, output = self.run_tool("--tags", "es", "--name", name, "--keep")
            self.assertEqual(code, 0, output)
            text = read_file(self.kept_copy(output).res("values-es", "strings_common.xml"))
            self.assertIn(">Anular<", text)
        code, output = self.run_tool("--tags", "es", "--name", "haiku")
        self.assertEqual(code, 2)
        self.assertIn("has no draft claude-haiku", output)

    def test_other_providers_drafts_do_not_count(self):
        os.makedirs(self.paths.drafts("es", "common"))
        translib.write_draft(self.paths.drafts("es", "common") + "/openai.xml", "openai", "gpt", [])
        code, output = self.run_tool("--tags", "es")
        self.assertEqual(code, 2)
        self.assertIn("es has no drafts", output)
        self.assertFalse(os.path.exists(self.workdir), "nothing is exported for a language with no drafts")

    def test_a_language_with_no_drafts_fails_even_beside_one_with_drafts(self):
        self.draft()
        code, output = self.run_tool("--tags", "es,de")
        self.assertEqual(code, 2)
        self.assertIn("de has no drafts", output)

    def test_only_draft_languages(self):
        self.repo.write("l10n/lang/es.tsv", self.repo.read("l10n/lang/es.tsv").replace("status\tdraft", "status\tbeta"))
        self.draft()
        code, output = self.run_tool("--tags", "es")
        self.assertEqual(code, 2)
        self.assertIn("es has status beta", output)
        self.assertEqual(os.listdir(self.workdir), [])


class RefusalTest(Base):
    def setUp(self):
        super().setUp()
        self.draft()

    def tool(self, workdir, out):
        return run_tool("--root", self.root, "--workdir", workdir, "--out", out, "--tags", "es", "--no-build", "--no-java")

    def test_inside_the_worktree(self):
        for workdir, out in ((self.root + "/scratch", self.out), (self.workdir, self.root + "/build/out"),
                             (self.root, self.out), (self.workdir, self.root)):
            code, output = self.tool(workdir, out)
            self.assertEqual(code, 2, output)
            self.assertIn("inside the worktree", output)
        self.assertFalse(os.path.exists(self.root + "/scratch"))

    def test_through_a_symlink(self):
        link = os.path.join(self.scratch, "link")
        os.symlink(self.root, link)
        code, output = self.tool(link + "/scratch", self.out)
        self.assertEqual(code, 2, output)
        self.assertIn("inside the worktree", output)

    def test_any_res_or_dist_path(self):
        for name in ("res", "dist"):
            code, output = self.tool(self.workdir, os.path.join(self.scratch, name, "x"))
            self.assertEqual(code, 2, output)
            self.assertIn("res/ or dist/ path", output)
            code, output = self.tool(os.path.join(self.scratch, name), self.out)
            self.assertEqual(code, 2, output)

    def test_another_checkout(self):
        other = os.path.join(self.scratch, "other")
        os.makedirs(other)
        subprocess.run(["git", "init", "-q", other], check=True)
        code, output = self.tool(other + "/scratch", self.out)
        self.assertEqual(code, 2, output)
        self.assertIn("inside the git checkout", output)

    def test_dirty_res(self):
        self.repo.write("res/values/strings_common.xml", self.repo.read("res/values/strings_common.xml") + "\n")
        code, output = self.tool(self.workdir, self.out)
        self.assertEqual(code, 2, output)
        self.assertIn("uncommitted changes", output)
        self.assertIn("res/values/strings_common.xml", output)

    def test_a_subfolder_is_no_root(self):
        code, output = run_tool("--root", self.root + "/res", "--workdir", self.workdir, "--out", self.out,
                                "--tags", "es", "--no-build")
        self.assertEqual(code, 2, output)
        self.assertIn("not the top of a git checkout", output)

    def test_bad_tag(self):
        code, output = run_tool("--root", self.root, "--workdir", self.workdir, "--out", self.out, "--tags", "../es",
                                "--no-build")
        self.assertEqual(code, 2, output)
        self.assertIn("is not a BCP 47 tag", output)


class ReleaseRefusalTest(unittest.TestCase):
    """The patch to the real build.sh, in a temporary folder that is no checkout (as a copy is not)."""

    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="l10n-draft-build-")
        shutil.copy(REAL_BUILD, self.root)
        with open(os.path.join(self.root, "AndroidManifest.xml"), "w", encoding="utf-8") as handle:
            handle.write(MANIFEST)
        draft_apk.patch_build_script(self.root, draft_apk.draft_version_name(self.root, "abc1234"))

    def tearDown(self):
        shutil.rmtree(self.root, ignore_errors=True)

    def version(self, **env):
        clean = dict((k, v) for k, v in os.environ.items() if not k.startswith(("VOLT_", "NAVEE_")))
        clean.update(env)
        return subprocess.run(["bash", "-c", "source build.sh && navee_version_name"], cwd=self.root, env=clean,
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True)

    def test_version_has_the_suffix(self):
        result = self.version()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), "1.2.3-dev+abc1234-draft-languages")
        self.assertEqual(self.version(VOLT_RELEASE="0").stdout.strip(), "1.2.3-dev+abc1234-draft-languages")

    def test_a_release_build_refuses(self):
        for value in ("1", "yes"):
            result = self.version(VOLT_RELEASE=value)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn(draft_apk.RELEASE_REFUSAL, result.stderr)
            self.assertEqual(result.stdout, "")

    def test_build_stops_at_the_version(self):
        """The whole script refuses at navee_version_name, which build.sh calls before it builds anything."""
        script = read_file(os.path.join(self.root, "build.sh"))
        body = script[script.index("navee_build_app() {"):]
        self.assertLess(body.index('version_name="$(navee_version_name)"'), body.index("mkdir -p"))
        self.assertIn('    local version_name\n    version_name="$(navee_version_name)"', body)

    def test_a_changed_build_script_is_noticed(self):
        path = os.path.join(self.root, "build.sh")
        with open(path, "w", encoding="utf-8") as handle:
            handle.write("#!/bin/bash\nnavee_build_app\n")
        with self.assertRaises(draft_apk.DraftApkError):
            draft_apk.patch_build_script(self.root, "1-draft-languages")

    def test_the_build_gets_no_signing_identity_or_release_flag(self):
        saved = dict(os.environ)
        os.environ.update(VOLT_RELEASE="1", VOLT_PSEUDO_LOCALES="1", NAVEE_SIGNING_KEYSTORE="/release.p12",
                          NAVEE_SIGNING_PASSWORD="x", NAVEE_SIGNING_ALIAS="y", NAVEE_KEY_DIR="/keys")
        try:
            env = draft_apk.build_env("/run", "/run/tree", "/out/a.apk")
        finally:
            os.environ.clear()
            os.environ.update(saved)
        for name in ("VOLT_RELEASE", "VOLT_PSEUDO_LOCALES", "NAVEE_SIGNING_KEYSTORE", "NAVEE_SIGNING_PASSWORD",
                     "NAVEE_SIGNING_ALIAS"):
            self.assertNotIn(name, env)
        self.assertEqual(env["NAVEE_KEY_DIR"], "/run/tree/.local/keys")
        self.assertEqual(env["NAVEE_APK_PATH"], "/out/a.apk")
        self.assertEqual(env["NAVEE_BUILD_DIR"], "/run/tree/.local/build")


class RealTreeTest(unittest.TestCase):
    """The tool against this checkout's own HEAD and English, with drafts from a temporary folder."""

    @classmethod
    def language_folders(cls):
        shipped = set(l.folder for l in gen_catalog.load_languages(translib.Paths().lang_dir())
                      if l.status in ("beta", "stable"))
        res = translib.Paths().res()
        found = set()
        for name in os.listdir(res):
            folder = os.path.join(res, name)
            if name.startswith("values-") and any(f.startswith("strings") for f in os.listdir(folder)):
                found.add(name)
        return found, shipped

    def test_the_tree_holds_no_draft_language_after_a_run(self):
        paths = translib.Paths()
        inside = subprocess.run(["git", "-C", paths.root, "rev-parse", "--show-toplevel"], stdout=subprocess.PIPE,
                                stderr=subprocess.DEVNULL)
        if inside.returncode != 0 or os.path.realpath(inside.stdout.decode().strip()) != os.path.realpath(paths.root):
            self.skipTest("not in a git checkout")
        dirty = subprocess.run(["git", "-C", paths.root, "--no-optional-locks", "status", "--porcelain", "--", "res"],
                               stdout=subprocess.PIPE).stdout
        if dirty.strip():
            self.skipTest("res/ has uncommitted changes, which the tool rightly refuses")
        scratch = os.path.realpath(tempfile.mkdtemp(prefix="l10n-draft-apk-real-"))
        self.addCleanup(shutil.rmtree, scratch, ignore_errors=True)
        before = self.language_folders()
        self.assertLessEqual(before[0], before[1], "a res/values-xx folder of a language that does not ship")
        drafts = translib.Paths(os.path.join(scratch, "drafts"))
        with open(os.path.join(cf.FIXTURE, "l10n", "lang", "es.tsv"), encoding="utf-8") as handle:
            os.makedirs(drafts.lang_dir())
            with open(os.path.join(drafts.lang_dir(), "es.tsv"), "w", encoding="utf-8") as target:
                target.write(handle.read())
        sources, tag_area = translib.load_source(paths)
        source = next(s for s in sources[tag_area] if s.kind == "string" and s.translatable
                      and not s.meta.get("ladder") and "%" not in s.items["other"]
                      and s.key + "_short" not in [o.key for o in sources[tag_area]]
                      and not s.meta.get("safety") and not s.meta.get("max"))
        translib.write_draft(os.path.join(drafts.drafts("es", tag_area), "claude-test.xml"), "claude-test", "test",
                             [(source.key, "string", {"other": source.items["other"]}, 1,
                               translib.source_sha1(source.kind, source.items))])
        lang_listing = sorted(os.listdir(paths.lang_dir()))
        code, output = run_tool("--tags", "es", "--workdir", os.path.join(scratch, "w"), "--out", os.path.join(scratch, "o"),
                                "--drafts", drafts.root, "--no-build", "--no-java", "--aapt", "off")
        self.assertEqual(code, 0, output)
        self.assertEqual(self.language_folders(), before)
        self.assertFalse(os.path.exists(paths.res("values-es")) and "es" not in lang_listing)
        self.assertEqual(sorted(os.listdir(paths.lang_dir())), lang_listing)
        report = read_report(os.path.join(scratch, "o", "report.tsv"))
        self.assertEqual(report[("es", "TOTAL")]["translated"], "1")
        self.assertEqual(os.listdir(os.path.join(scratch, "w")), [])


if __name__ == "__main__":
    unittest.main()
