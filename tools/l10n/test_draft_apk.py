import contextlib
import hashlib
import io
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
import unittest.mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import council_fixture as cf  # noqa: E402
import draft_apk  # noqa: E402
import fontmetrics  # noqa: E402
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
        self.assertIn("common_gone\tunknown\t", read_file(os.path.join(self.out, "report-keys.tsv")))
        self.assertNotIn("common_gone", read_file(self.kept_copy(output).res("values-es", "strings_common.xml")))

    def test_a_draft_area_the_english_lost_is_counted(self):
        self.draft()
        os.makedirs(self.paths.drafts("es", "renamed"))
        shutil.copy(self.paths.drafts("es", "common") + "/claude-opus.xml", self.paths.drafts("es", "renamed"))
        code, output = self.run_tool("--tags", "es")
        self.assertEqual(code, 0, output)
        self.assertIn("its draft is ignored", output)
        report = read_report(os.path.join(self.out, "report.tsv"))
        self.assertEqual(report[("es", "renamed")]["unknown"], str(len(cf.SPANISH)))
        self.assertEqual(report[("es", "renamed")]["total"], "0")
        self.assertEqual(report[("es", "TOTAL")]["unknown"], str(len(cf.SPANISH)))
        self.assertIn("es\trenamed\tcommon_cancel\tunknown\t", read_file(os.path.join(self.out, "report-keys.tsv")))

    def assert_nothing_to_show(self):
        code, output = self.run_tool("--tags", "es")
        self.assertEqual(code, 1, output)
        self.assertIn("es: none of its draft text can be shown", output)
        self.assertIn("FAILED", output)
        self.assertTrue(os.path.exists(os.path.join(self.out, "report.tsv")))

    def test_a_language_whose_only_draft_area_is_lost_fails(self):
        self.draft(texts={"common_cancel": "Cancelar"})
        os.rename(self.paths.work("es", "common"), self.paths.work("es", "renamed"))
        self.assert_nothing_to_show()

    def test_a_language_whose_only_key_fails_fails(self):
        self.draft(texts={"common_move_widget": "Mover a la posición"})
        self.assert_nothing_to_show()

    def test_no_glossary_keeps_safety_keys_english(self):
        self.draft()
        self.git("rm", "-q", "l10n/glossary/es.tsv")
        self.git("-c", "user.name=T", "-c", "user.email=t@example.invalid", "-c", "commit.gpgsign=false",
                 "-c", "core.hooksPath=" + os.devnull, "commit", "-q", "-m", "no glossary")
        code, output = self.run_tool("--tags", "es", "--keep")
        self.assertEqual(code, 0, output)
        self.assertIn("es has no l10n/glossary/es.tsv", output)
        text = read_file(self.kept_copy(output).res("values-es", "strings_common.xml"))
        self.assertNotIn('name="common_disclaimer"', text)
        self.assertIn('name="common_cancel"', text)
        report = read_report(os.path.join(self.out, "report.tsv"))
        self.assertEqual(report[("es", "common")]["unchecked"], "1")
        self.assertIn("1 drafted safety keys show English", output)
        keys = read_file(os.path.join(self.out, "report-keys.tsv"))
        self.assertIn("common_disclaimer\tunchecked\tglossary: no approved target for 'not affiliated with NAVEE'", keys)

    def test_a_glossary_missing_one_safety_term_keeps_that_key_english(self):
        self.draft()
        path = self.paths.glossary_dir() + "/es.tsv"
        self.repo.write(self.paths.rel(path), "".join(
            line + "\n" for line in read_file(path).splitlines() if not line.startswith("not-affiliated")))
        code, output = self.run_tool("--tags", "es", "--keep")
        self.assertEqual(code, 0, output)
        self.assertNotIn("has no l10n/glossary", output)
        self.assertEqual(read_report(os.path.join(self.out, "report.tsv"))[("es", "common")]["unchecked"], "1")
        self.assertNotIn('name="common_disclaimer"',
                         read_file(self.kept_copy(output).res("values-es", "strings_common.xml")))

    def test_with_the_target_the_safety_key_is_written(self):
        self.draft()
        code, output = self.run_tool("--tags", "es", "--keep")
        self.assertEqual(code, 0, output)
        self.assertEqual(read_report(os.path.join(self.out, "report.tsv"))[("es", "common")]["unchecked"], "0")
        self.assertIn('name="common_disclaimer"', read_file(self.kept_copy(output).res("values-es", "strings_common.xml")))

    def test_warnings_reach_the_report(self):
        self.draft(texts=dict(cf.SPANISH, common_not_reported="Not reported"))
        code, output = self.run_tool("--tags", "es", "--keep")
        self.assertEqual(code, 0, output)
        self.assertIn('name="common_not_reported"', read_file(self.kept_copy(output).res("values-es", "strings_common.xml")))
        keys = read_file(os.path.join(self.out, "report-keys.tsv"))
        self.assertRegex(keys, r"common_not_reported\twarned\t.*untranslated: the target is the English text")
        warned = keys.count("\twarned\t")
        self.assertEqual(read_report(os.path.join(self.out, "report.tsv"))[("es", "TOTAL")]["warned"], str(warned))
        self.assertIn("es: %d written keys have check.py warnings" % warned, output)

    def test_a_silent_width_fallback_is_printed(self):
        self.draft()
        with unittest.mock.patch.object(fontmetrics, "java_command", return_value=None):
            code, output = run_tool("--root", self.root, "--workdir", self.workdir, "--out", self.out, "--aapt", "off",
                                    "--no-build", "--tags", "es")
        self.assertEqual(code, 0, output)
        self.assertIn("note: widths use the hmtx approximation: no Java runtime", output)

    def test_the_report_names_the_commit(self):
        self.draft()
        code, output = self.run_tool("--tags", "es")
        self.assertEqual(code, 0, output)
        commit = subprocess.run(["git", "rev-parse", "--short=7", "HEAD"], cwd=self.root, stdout=subprocess.PIPE,
                                universal_newlines=True).stdout.strip()
        report = read_report(os.path.join(self.out, "report.tsv"))
        self.assertEqual(report[("es", "common")]["commit"], commit)
        self.assertEqual(report[("es", "TOTAL")]["commit"], commit)

    def earlier_run(self):
        os.makedirs(self.out, exist_ok=True)
        for name in (draft_apk.APK_NAME, draft_apk.IDSIG_NAME, "report.tsv", "report-keys.tsv"):
            with open(os.path.join(self.out, name), "w", encoding="utf-8") as handle:
                handle.write("from an earlier run\n")
        with open(os.path.join(self.out, "owner-notes.txt"), "w", encoding="utf-8") as handle:
            handle.write("not ours\n")

    def test_no_build_removes_an_earlier_apk(self):
        self.draft()
        self.earlier_run()
        code, output = self.run_tool("--tags", "es")
        self.assertEqual(code, 0, output)
        self.assertEqual(sorted(os.listdir(self.out)), ["owner-notes.txt", "report-keys.tsv", "report.tsv"])
        self.assertNotIn("from an earlier run", read_file(os.path.join(self.out, "report.tsv")))

    def test_a_malformed_draft_leaves_nothing_of_an_earlier_run(self):
        self.draft()
        self.repo.write(self.paths.rel(self.paths.drafts("es", "common") + "/claude-opus.xml"), "<string")
        self.earlier_run()
        code, output = self.run_tool("--tags", "es")
        self.assertEqual(code, 2, output)
        self.assertEqual(os.listdir(self.out), ["owner-notes.txt"])

    def test_a_failure_after_the_build_started_removes_the_apk(self):
        self.draft()
        self.earlier_run()

        def fails_after_a_build(*_args):
            with open(os.path.join(self.out, draft_apk.APK_NAME), "w", encoding="utf-8") as handle:
                handle.write("a release build that should have refused\n")
            with open(os.path.join(self.out, draft_apk.IDSIG_NAME), "w", encoding="utf-8") as handle:
                handle.write("its signature\n")
            raise draft_apk.DraftApkError("a VOLT_RELEASE build of the copy did not refuse")

        with unittest.mock.patch.object(draft_apk, "write_report", side_effect=fails_after_a_build):
            code, output = self.run_tool("--tags", "es")
        self.assertEqual(code, 2, output)
        self.assertEqual(os.listdir(self.out), ["owner-notes.txt"])

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

    def test_inside_a_git_directory(self):
        other = os.path.join(self.scratch, "other")
        subprocess.run(["git", "init", "-q", other], check=True)
        code, output = self.tool(other + "/.git/x", self.out)
        self.assertEqual(code, 2, output)
        self.assertIn("inside the git checkout", output)
        self.assertFalse(os.path.exists(other + "/.git/x"))

    def test_inside_the_main_git_directory_from_a_linked_worktree(self):
        linked = os.path.join(self.scratch, "linked")
        self.git("worktree", "add", "-q", "--detach", linked)
        linked = os.path.realpath(linked)
        for path in (self.root + "/.git/zz", self.root + "/.git"):
            with self.assertRaises(draft_apk.DraftApkError) as caught:
                draft_apk.check_target("--workdir", path, linked)
            self.assertIn("inside the git checkout", str(caught.exception))

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
        draft_apk.patch_build_script(self.root, draft_apk.draft_version_name(self.root))

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
        self.assertEqual(result.stdout.strip(), "1.2.3-dev-draft-languages")
        self.assertEqual(self.version(VOLT_RELEASE="0").stdout.strip(), "1.2.3-dev-draft-languages")

    def test_version_survives_the_apps_own_pattern(self):
        """Diagnostics and the journal turn a versionName outside this pattern into "unknown"."""
        source = read_file(os.path.join(translib.REPO_ROOT, "src", "com", "jjricks", "volt", "CommandLog.java"))
        pattern = re.search(r'String APP_VERSION = "(.*)";', source).group(1).replace("\\\\", "\\")
        self.assertRegex(self.version().stdout.strip(), "^(%s)$" % pattern)
        self.assertNotRegex("1.2.3-dev+abc1234-draft-languages", "^(%s)$" % pattern)
        version_name = read_file(os.path.join(translib.REPO_ROOT, "src", "com", "jjricks", "volt", "VersionName.java"))
        self.assertIn('rest.startsWith("-dev-")', version_name)  # so the journal stores it as a dev build

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


class InspectTest(unittest.TestCase):
    def test_configurations_compare_whole_names(self):
        dumped = "\nanydpi-v26\nanydpi-v33\nnodpi-v4\nes\npt-rBR\n"
        folders = {"no": "values-no", "an": "values-an", "es": "values-es", "pt-BR": "values-pt-rBR", "pt": "values-pt"}
        self.assertEqual(draft_apk.missing_configurations(dumped, ["es", "pt-BR"], folders), [])
        self.assertEqual(draft_apk.missing_configurations(dumped, ["no", "an", "pt"], folders), ["no", "an", "pt"])


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

    def setUp(self):
        paths = translib.Paths()
        inside = subprocess.run(["git", "-C", paths.root, "rev-parse", "--show-toplevel"], stdout=subprocess.PIPE,
                                stderr=subprocess.DEVNULL)
        if inside.returncode != 0 or os.path.realpath(inside.stdout.decode().strip()) != os.path.realpath(paths.root):
            self.skipTest("not in a git checkout")
        dirty = subprocess.run(["git", "-C", paths.root, "--no-optional-locks", "status", "--porcelain", "--", "res"],
                               stdout=subprocess.PIPE).stdout
        if dirty.strip():
            self.skipTest("res/ has uncommitted changes, which the tool rightly refuses")
        self.scratch = os.path.realpath(tempfile.mkdtemp(prefix="l10n-draft-apk-real-"))
        self.addCleanup(shutil.rmtree, self.scratch, ignore_errors=True)
        # A language the tree does not ship yet: its own pilot will add res/values-es, and the tool refuses that.
        self.tag = next(t for t in ("es", "de", "fr", "it", "nl", "pl", "sv", "da", "fi", "cs", "hu", "ro")
                        if not os.path.exists(paths.res("values-" + t)))
        self.drafts = translib.Paths(os.path.join(self.scratch, "drafts"))
        text = read_file(os.path.join(cf.FIXTURE, "l10n", "lang", "es.tsv"))
        text = text.replace("tag\tes\n", "tag\t%s\n" % self.tag).replace("folder\tvalues-es\n", "folder\tvalues-%s\n" % self.tag)
        os.makedirs(self.drafts.lang_dir())
        with open(os.path.join(self.drafts.lang_dir(), self.tag + ".tsv"), "w", encoding="utf-8") as target:
            target.write(text)

    def tool(self):
        return run_tool("--tags", self.tag, "--workdir", os.path.join(self.scratch, "w"),
                        "--out", os.path.join(self.scratch, "o"), "--drafts", self.drafts.root,
                        "--no-build", "--no-java", "--aapt", "off")

    def test_the_tree_holds_no_draft_language_after_a_run(self):
        paths = translib.Paths()
        before = self.language_folders()
        self.assertLessEqual(before[0], before[1], "a res/values-xx folder of a language that does not ship")
        sources, tag_area = translib.load_source(paths)
        source = next(s for s in sources[tag_area] if s.kind == "string" and s.translatable
                      and not s.meta.get("ladder") and "%" not in s.items["other"]
                      and s.key + "_short" not in [o.key for o in sources[tag_area]]
                      and not s.meta.get("safety") and not s.meta.get("max"))
        translib.write_draft(os.path.join(self.drafts.drafts(self.tag, tag_area), "claude-test.xml"), "claude-test", "test",
                             [(source.key, "string", {"other": source.items["other"]}, 1,
                               translib.source_sha1(source.kind, source.items))])
        lang_listing = sorted(os.listdir(paths.lang_dir()))
        code, output = self.tool()
        self.assertEqual(code, 0, output)
        self.assertEqual(self.language_folders(), before)
        self.assertFalse(os.path.exists(paths.res("values-" + self.tag)))
        self.assertEqual(sorted(os.listdir(paths.lang_dir())), lang_listing)
        report = read_report(os.path.join(self.scratch, "o", "report.tsv"))
        self.assertEqual(report[(self.tag, "TOTAL")]["translated"], "1")
        self.assertEqual(os.listdir(os.path.join(self.scratch, "w")), [])

    def test_an_inverted_lock_text_needs_the_glossary_to_be_caught(self):
        """The real English's "Locked", drafted as "Unlocked": never written, with or without the glossary."""
        paths = translib.Paths()
        sources, _ = translib.load_source(paths)
        area = next(a for a, items in sources.items() if any(s.key == "readings_value_locked" for s in items))
        source = next(s for s in sources[area] if s.key == "readings_value_locked")
        harmless = next(s for s in sources[area] if s.kind == "string" and s.translatable and not s.meta.get("ladder")
                        and "%" not in s.items["other"] and s.key + "_short" not in [o.key for o in sources[area]]
                        and not s.meta.get("safety") and not s.meta.get("max"))
        rows = [(s.key, "string", {"other": text}, 1, translib.source_sha1(s.kind, s.items))
                for s, text in ((source, "Desbloqueado"), (harmless, harmless.items["other"]))]
        translib.write_draft(os.path.join(self.drafts.drafts(self.tag, area), "claude-test.xml"), "claude-test", "test", rows)
        code, output = self.tool()
        self.assertEqual(code, 0, output)
        keys = read_file(os.path.join(self.scratch, "o", "report-keys.tsv"))
        self.assertIn("readings_value_locked\tunchecked\tglossary: no approved target for 'Locked'", keys)
        report = read_report(os.path.join(self.scratch, "o", "report.tsv"))
        self.assertEqual(report[(self.tag, area)]["translated"], "1")
        # With the target, the same text is plainly wrong, and the check says so.
        os.makedirs(self.drafts.glossary_dir())
        with open(os.path.join(self.drafts.glossary_dir(), self.tag + ".tsv"), "w", encoding="utf-8") as handle:
            handle.write("id\tterm\tdefinition\tpos\tdnt\tsafety\tsource\n"
                         "locked\tBloqueado\tLocked.\tadjective\tno\tlock\ttest\n")
        code, output = self.tool()
        self.assertEqual(code, 0, output)
        keys = read_file(os.path.join(self.scratch, "o", "report-keys.tsv"))
        self.assertIn("readings_value_locked\tfailing\t", keys)
        self.assertIn("'Locked' should be 'Bloqueado'", keys)


if __name__ == "__main__":
    unittest.main()
