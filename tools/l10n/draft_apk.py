#!/usr/bin/env python3
"""Build a throwaway APK that shows draft-status languages, without touching the tree or any release.

  draft_apk.py --tags es,de --workdir <scratch dir> --out <dir> [--name <config>] [--keep]
  draft_apk.py --tags es --workdir <scratch dir> --out <dir> --no-build     the report and checks only

It exports HEAD with git archive into a fresh directory under --workdir, and everything else happens
in that copy, which is deleted at the end unless --keep:

- res/values-<qual>/strings_<area>.xml come from the Claude draft l10n/work/<tag>/<area>/drafts/
  claude-<config>.xml (--name, default the only one present). Only keys whose volt:src is the current
  English's hash and that pass check.py are written, through merge.py's render_file; every other key
  is left out and Android shows English. A ladder goes whole or not at all.
- The languages' status becomes beta, res/xml/locales_config.xml lists them and the manifest names it.
- build.sh in the copy gets a versionName ending in -draft-languages and refuses VOLT_RELEASE.
- merge.py's validation, the catalog generator, CatalogCheckTest and GlyphCoverageTest run on the copy;
  any failure means no APK.

The APK goes to <out>/volt-draft-languages.apk, signed with a key made for that copy and deleted with
it, so it cannot update an installed Volt: uninstall Volt first. Nothing is ever written to res/ or
dist/ of the repository, or anywhere else outside --workdir and --out.

<out>/report.tsv has one row per language and area, and a TOTAL row per language; english_fallback is
every key shown in English, of which failing and stale are the drafted keys that were refused, and
unknown counts draft keys the English no longer has. <out>/report-keys.tsv says why for each refused
key. The drafts stay exactly as they are: this tool never edits or relabels them.
"""

import sys

sys.dont_write_bytecode = True  # the tools' own __pycache__ would be a write outside --workdir and --out

import argparse  # noqa: E402
import collections  # noqa: E402
import contextlib  # noqa: E402
import os  # noqa: E402
import re  # noqa: E402
import shutil  # noqa: E402
import subprocess  # noqa: E402
import tempfile  # noqa: E402

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import check as checklib  # noqa: E402
import fontmetrics  # noqa: E402
import gen_catalog  # noqa: E402
import l10nlib  # noqa: E402
import merge  # noqa: E402
import pack as packlib  # noqa: E402
import translib  # noqa: E402

APK_NAME = "volt-draft-languages.apk"
REPORT_NAME = "report.tsv"
KEYS_NAME = "report-keys.tsv"
VERSION_SUFFIX = "-draft-languages"
REFUSED_NAMES = ("res", "dist")
REPORT_COLUMNS = ("language", "area", "total", "translated", "english_fallback", "failing", "stale", "unknown",
                  "safety_total", "safety_translated", "safety_fallback",
                  "lock_alarm_total", "lock_alarm_translated", "lock_alarm_fallback")
JAVA_CHECKS = ("CatalogCheckTest", "GlyphCoverageTest")
# build.sh ends with these lines; the copy's override goes right before them.
BUILD_ENTRY = '\nnavee_load_config\nif [[ "${BASH_SOURCE[0]}" == "$0" ]]; then\n'
RELEASE_REFUSAL = "This is a draft-languages copy (tools/l10n/draft_apk.py); it cannot be built as a release."


class DraftApkError(Exception):
    """A refusal or a usage problem: nothing was built."""


# ---------------------------------------------------------------------------
# Where the tool may write
# ---------------------------------------------------------------------------

def _git_env():
    return dict((k, v) for k, v in os.environ.items() if not k.startswith("GIT_"))


def _git(root, *args):
    """stdout of a read-only git command, or None. --no-optional-locks keeps git from refreshing the index."""
    result = subprocess.run(["git", "--no-optional-locks", "-C", root] + list(args), env=_git_env(),
                            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    return result.stdout.decode("utf-8", "replace") if result.returncode == 0 else None


def _inside(path, parent):
    return path == parent or path.startswith(parent.rstrip(os.sep) + os.sep)


def _existing_ancestor(path):
    while not os.path.isdir(path):
        path = os.path.dirname(path)
    return path


def check_target(option, value, root):
    """The real path of a --workdir or --out, refusing the worktree, any res/ or dist/, and other checkouts."""
    path = os.path.realpath(value)
    if _inside(path, root):
        raise DraftApkError("%s %s is inside the worktree %s; use a scratch directory outside it" % (option, value, root))
    if any(part in REFUSED_NAMES for part in path.split(os.sep)):
        raise DraftApkError("%s %s is a res/ or dist/ path; a draft build never writes there" % (option, value))
    top = _git(_existing_ancestor(path), "rev-parse", "--show-toplevel")
    if top is not None:
        raise DraftApkError("%s %s is inside the git checkout %s; use a scratch directory outside it" % (
            option, value, top.strip()))
    return path


def check_root(root):
    top = _git(root, "rev-parse", "--show-toplevel")
    if top is None or os.path.realpath(top.strip()) != root:
        raise DraftApkError("%s is not the top of a git checkout" % root)
    dirty = _git(root, "status", "--porcelain", "--", "res", "dist")
    if dirty is None:
        raise DraftApkError("git status failed in %s" % root)
    if dirty.strip():
        raise DraftApkError("res/ or dist/ has uncommitted changes, and the build uses HEAD only; "
                            "commit or stash them first:\n%s" % dirty.rstrip())


@contextlib.contextmanager
def temporary_files_in(directory):
    """Send this process's and its children's temporary files (merge.py's aapt run) to directory."""
    saved_dir, saved_env = tempfile.tempdir, os.environ.get("TMPDIR")
    tempfile.tempdir = directory
    os.environ["TMPDIR"] = directory
    try:
        yield
    finally:
        tempfile.tempdir = saved_dir
        if saved_env is None:
            os.environ.pop("TMPDIR", None)
        else:
            os.environ["TMPDIR"] = saved_env


def export_head(root, copy):
    os.makedirs(copy)
    archive = subprocess.Popen(["git", "-C", root, "archive", "--format=tar", "HEAD"], env=_git_env(),
                               stdout=subprocess.PIPE)
    extract = subprocess.run(["tar", "-xf", "-", "-C", copy], stdin=archive.stdout)
    archive.stdout.close()
    if archive.wait() != 0 or extract.returncode != 0:
        raise DraftApkError("could not export HEAD of %s" % root)


# ---------------------------------------------------------------------------
# Choosing draft text
# ---------------------------------------------------------------------------

def draft_files(drafts_paths, tag):
    """{config name: {area: path}} of the language's Claude drafts."""
    found = collections.OrderedDict()
    top = os.path.join(drafts_paths.root, "l10n", "work", tag)
    for area in sorted(os.listdir(top)) if os.path.isdir(top) else []:
        directory = drafts_paths.drafts(tag, area)
        for filename in sorted(os.listdir(directory)) if os.path.isdir(directory) else []:
            if filename.startswith("claude-") and filename.endswith(".xml"):
                found.setdefault(filename[len("claude-"):-len(".xml")], collections.OrderedDict())[area] = \
                    os.path.join(directory, filename)
    return found


def choose_drafts(drafts_paths, tag, name):
    """{area: path} of the draft to use; a language with no draft at all is an error."""
    found = draft_files(drafts_paths, tag)
    if not found:
        raise DraftApkError("%s has no drafts: nothing at l10n/work/%s/<area>/drafts/claude-*.xml under %s" % (
            tag, tag, drafts_paths.root))
    if name:
        name = name[len("claude-"):] if name.startswith("claude-") else name
        if name not in found:
            raise DraftApkError("%s has no draft claude-%s; it has %s" % (
                tag, name, ", ".join("claude-" + n for n in found)))
        return found[name]
    if len(found) > 1:
        raise DraftApkError("%s has several Claude drafts (%s); choose one with --name" % (
            tag, ", ".join("claude-" + n for n in found)))
    return next(iter(found.values()))


def bring_language_files(drafts_paths, copy_paths, tag):
    """The language's settings, glossary and style guide, from where the drafts live into the copy."""
    for source, target in ((os.path.join(drafts_paths.lang_dir(), tag + ".tsv"),
                            os.path.join(copy_paths.lang_dir(), tag + ".tsv")),
                           (os.path.join(drafts_paths.glossary_dir(), tag + ".tsv"),
                            os.path.join(copy_paths.glossary_dir(), tag + ".tsv")),
                           (drafts_paths.style(tag), copy_paths.style(tag))):
        if os.path.exists(source):
            l10nlib.write_text(target, l10nlib.read_text(source))


def classify_area(pack, draft, font, measurer):
    """{key: (state, reason)} for every key of the pack, state being translated, missing, failing or stale.

    Also returns the draft's keys the English does not have. A ladder that is not whole falls whole,
    since merge.py and CatalogCheckTest refuse a partly translated one.
    """
    entries = collections.OrderedDict((e["key"], e) for e in pack["entries"])
    states = collections.OrderedDict((k, ("missing", "no draft")) for k in entries)
    if draft is None:
        return states, []
    unknown = [k for k in draft.entries if k not in entries]
    results = checklib.check_targets(pack, checklib.draft_targets(draft, pack), font, measurer)
    for key, entry in entries.items():
        drafted = draft.entries.get(key)
        if drafted is None:
            continue
        stale = checklib.stale_source(drafted.src, entry["source_sha1"])
        if stale:
            states[key] = ("stale", stale)
            continue
        errors = [f for f in results.get(key, []) if f["severity"] == checklib.ERROR]
        if errors:
            states[key] = ("failing", "; ".join("%s: %s" % (f["check"], f["message"]) for f in errors))
        else:
            states[key] = ("translated", "")
    for entry in entries.values():
        group = entry.get("ladder_group") or []
        broken = [k for k in group if states[k][0] != "translated"]
        if broken:
            for key in group:
                if states[key][0] == "translated":
                    states[key] = ("failing", "ladder: %s is %s" % (broken[0], states[broken[0]][0]))
    return states, unknown


def write_language_file(paths, tag, area, lang, sources, tag_area, draft, states):
    """Write res/<folder>/strings_<area>.xml with merge.py's writer; (written keys, skipped keys)."""
    decisions = {}
    for s in sources:
        if states.get(s.key, ("missing",))[0] == "translated":
            # Only for merge.render_file, in this copy: no decision file is written, and nothing was agreed.
            decisions[s.key] = {"outcome": merge.TRANSLATED[0], "source_sha1": translib.source_sha1(s.kind, s.items),
                                "text": translib.text_value(s.kind, draft.entries[s.key].items)}
    text, written, skipped = merge.render_file(tag, area, lang, sources, decisions, tag_area)
    skipped = [(k, why) for k, why in skipped if k in decisions]
    if written or area == tag_area:
        head, rest = text.split("\n", 1)
        note = "<!-- Claude-only draft text (tools/l10n/draft_apk.py), in a throwaway copy: never committed or released. -->"
        l10nlib.write_text(paths.res(lang.folder, "strings_%s.xml" % area), "\n".join((head, note, rest)))
    return written, skipped


# ---------------------------------------------------------------------------
# The report
# ---------------------------------------------------------------------------

def area_row(tag, area, pack, states, unknown):
    row = collections.OrderedDict((c, 0) for c in REPORT_COLUMNS)
    row["language"], row["area"] = tag, area
    row["unknown"] = len(unknown)
    for entry in pack["entries"]:
        state = states[entry["key"]][0]
        translated = state == "translated"
        row["total"] += 1
        row["translated"] += translated
        row["english_fallback"] += not translated
        row["failing"] += state == "failing"
        row["stale"] += state == "stale"
        if entry["safety"]:
            row["safety_total"] += 1
            row["safety_translated"] += translated
            row["safety_fallback"] += not translated
        if any(v in translib.SAFETY_BLOCKING for v in entry["safety"]):
            row["lock_alarm_total"] += 1
            row["lock_alarm_translated"] += translated
            row["lock_alarm_fallback"] += not translated
    return row


def total_row(tag, rows):
    total = collections.OrderedDict((c, sum(r[c] for r in rows)) for c in REPORT_COLUMNS[2:])
    return collections.OrderedDict([("language", tag), ("area", "TOTAL")] + list(total.items()))


def tsv_lines(columns, rows):
    return "\n".join(["\t".join(columns)] + ["\t".join(str(r[c]) for c in columns) for r in rows]) + "\n"


def write_report(out, rows, key_rows):
    l10nlib.write_text(os.path.join(out, REPORT_NAME), tsv_lines(REPORT_COLUMNS, rows))
    l10nlib.write_text(os.path.join(out, KEYS_NAME), tsv_lines(("language", "area", "key", "state", "reason"), key_rows))
    sys.stdout.write(tsv_lines(REPORT_COLUMNS, rows))


# ---------------------------------------------------------------------------
# Making the copy ship the languages
# ---------------------------------------------------------------------------

def set_beta(paths, tag):
    path = os.path.join(paths.lang_dir(), tag + ".tsv")
    text, count = re.subn(r"^status\tdraft$", "status\tbeta", l10nlib.read_text(path), flags=re.MULTILINE)
    if count != 1:
        raise DraftApkError("l10n/lang/%s.tsv has no status draft line; only draft languages go through this tool" % tag)
    l10nlib.write_text(path, text)


def add_locales(paths, tags):
    path = paths.res("xml", "locales_config.xml")
    text = l10nlib.read_text(path)
    lines = "".join('    <locale android:name="%s" />\n' % translib.xml_attr(t) for t in tags)
    if text.count("</locale-config>") != 1:
        raise DraftApkError("res/xml/locales_config.xml has no single </locale-config>")
    l10nlib.write_text(path, text.replace("</locale-config>", lines + "</locale-config>"))


def add_locale_config(root):
    path = os.path.join(root, "AndroidManifest.xml")
    text = l10nlib.read_text(path)
    if "android:localeConfig" in text:
        return
    if text.count("<application\n") != 1:
        raise DraftApkError("AndroidManifest.xml has no single <application line")
    l10nlib.write_text(path, text.replace("<application\n", '<application\n        android:localeConfig="@xml/locales_config"\n'))


def draft_version_name(root, commit):
    match = re.search(r'android:versionName="([0-9.]+)"', l10nlib.read_text(os.path.join(root, "AndroidManifest.xml")))
    if not match:
        raise DraftApkError("AndroidManifest.xml has no numeric versionName")
    return "%s-dev+%s%s" % (match.group(1), commit, VERSION_SUFFIX)


def patch_build_script(root, version_name):
    """In the copy only: this versionName always, and VOLT_RELEASE refused.

    A copy is no git checkout, so build.sh's own dirty-tree check could not stop a release build there.
    """
    path = os.path.join(root, "build.sh")
    text = l10nlib.read_text(path)
    if text.count(BUILD_ENTRY) != 1:
        raise DraftApkError("build.sh no longer ends the way tools/l10n/draft_apk.py expects; update BUILD_ENTRY")
    override = "\n".join([
        "",
        "# Added by tools/l10n/draft_apk.py in this throwaway copy only.",
        "navee_version_name() {",
        '    if [[ -n "${VOLT_RELEASE:-}" && "${VOLT_RELEASE:-}" != 0 ]]; then',
        '        echo "%s" >&2' % RELEASE_REFUSAL,
        "        return 1",
        "    fi",
        "    printf '%%s\\n' '%s'" % version_name,
        "}",
    ]) + "\n"
    l10nlib.write_text(path, text.replace(BUILD_ENTRY, override + BUILD_ENTRY))


def build_env(run_dir, copy, apk_path):
    """Nothing of the caller's build setup reaches the copy: no release flag, no signing identity."""
    env = dict((k, v) for k, v in os.environ.items() if not k.startswith(("VOLT_", "NAVEE_", "GIT_")))
    env.update(NAVEE_BUILD_DIR=os.path.join(copy, ".local", "build"), NAVEE_KEY_DIR=os.path.join(copy, ".local", "keys"),
               NAVEE_APK_PATH=apk_path, PYTHONDONTWRITEBYTECODE="1", TMPDIR=os.path.join(run_dir, "tmp"))
    return env


def bring_dependencies(root, copy):
    """build.sh verifies the pinned jars in .local/shizuku-dependencies, which git archive leaves out."""
    source = os.path.join(root, ".local", "shizuku-dependencies")
    if not os.path.isdir(source):
        raise DraftApkError("%s is missing; run python3 setup/shizuku-dependencies.py --fetch there first" % source)
    shutil.copytree(source, os.path.join(copy, ".local", "shizuku-dependencies"))


# ---------------------------------------------------------------------------
# Checks on the copy, and the build
# ---------------------------------------------------------------------------

def check_copy(paths, tags, aapt, catalog_dir):
    """merge.py's validation and the catalog generator; problems, notes."""
    problems, notes = [], []
    for tag in tags:
        tag_problems, tag_notes = merge.validate_folder(paths, tag, aapt)
        problems.extend("%s: %s" % (tag, p) for p in tag_problems)
        notes.extend("%s: %s" % (tag, n) for n in tag_notes)
    try:
        gen_catalog.generate(paths.res(), paths.lang_dir(), catalog_dir, None, False, gen_catalog.PACKAGE, paths.root)
    except gen_catalog.CatalogError as error:
        problems.extend("gen_catalog: %s" % p for p in error.problems)
    return problems, notes


def run_java_checks(run_dir, copy, catalog_dir, env):
    """CatalogCheckTest and GlyphCoverageTest against the copy, compiled the way tests/run.sh does."""
    package = os.path.join(catalog_dir, *gen_catalog.PACKAGE.split("."))
    classes = os.path.join(run_dir, "check-classes")
    os.makedirs(classes)
    sources = [os.path.join(package, n + ".java") for n in ("T", "M", "EnglishText", "AppLanguages")]
    sources.extend(os.path.join(copy, "tests", "l10n", n + ".java") for n in ("L10nFiles",) + JAVA_CHECKS)
    # -sourcepath: the plain classes these use (Msg, UserFacingException), without listing them here.
    script = 'source build.sh && navee_find_java && javac --release 8 -encoding UTF-8 -sourcepath "$1" -d "$2" "${@:3}"'
    result = subprocess.run(["bash", "-c", script, "bash", os.path.join(copy, "src"), classes] + sources, cwd=copy, env=env,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if result.returncode != 0:
        return ["javac: %s" % result.stdout.decode("utf-8", "replace").strip()]
    problems = []
    for name in JAVA_CHECKS:
        script = 'source build.sh && navee_find_java && java -cp "$1" "com.jjricks.volt.$2"'
        result = subprocess.run(["bash", "-c", script, "bash", classes, name], cwd=copy, env=env,
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        output = result.stdout.decode("utf-8", "replace").strip()
        if result.returncode != 0:
            problems.append("%s: %s" % (name, output))
        else:
            print("%s: %s" % (name, output.splitlines()[-1] if output else "passed"))
    return problems


def prove_release_refused(copy, env):
    """A VOLT_RELEASE build of the copy has to refuse, before it compiles anything."""
    result = subprocess.run(["bash", "build.sh"], cwd=copy, env=dict(env, VOLT_RELEASE="1"),
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    output = result.stdout.decode("utf-8", "replace")
    if result.returncode == 0 or RELEASE_REFUSAL not in output:
        raise DraftApkError("a VOLT_RELEASE build of the copy did not refuse:\n%s" % output)
    print("release: a VOLT_RELEASE build of the copy refuses")


def build_apk(copy, env, apk_path):
    if os.path.exists(apk_path):
        os.remove(apk_path)
    result = subprocess.run(["bash", "build.sh"], cwd=copy, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    output = result.stdout.decode("utf-8", "replace")
    if result.returncode != 0 or not os.path.exists(apk_path):
        return ["build.sh failed:\n%s" % output]
    return []


def inspect_apk(apk_path, tags, lang_folders):
    """What the APK really holds, from aapt; problems."""
    aapt, _jar = merge.find_aapt()
    if not aapt:
        return ["aapt %s is not installed, so the APK could not be inspected" % merge.AAPT_VERSION]

    def dump(*args):
        return subprocess.run([aapt, "dump"] + list(args) + [apk_path], stdout=subprocess.PIPE,
                              stderr=subprocess.STDOUT).stdout.decode("utf-8", "replace")

    problems = []
    match = re.search(r"versionName='([^']*)'", dump("badging"))
    version = match.group(1) if match else ""
    print("apk versionName: %s" % version)
    if not version.endswith(VERSION_SUFFIX):
        problems.append("the APK's versionName %r does not end in %s" % (version, VERSION_SUFFIX))
    configurations = dump("configurations").split()
    for tag in tags:
        wanted = lang_folders[tag][len("values-"):]
        if not any(wanted in c for c in configurations):
            problems.append("the APK has no %s configuration" % wanted)
    tree = subprocess.run([aapt, "dump", "xmltree", apk_path, "AndroidManifest.xml"],
                          stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    kept = "localeConfig" in tree.stdout.decode("utf-8", "replace")
    print("apk manifest android:localeConfig: %s" % ("kept" if kept else "NOT kept by aapt"))
    return problems


# ---------------------------------------------------------------------------
# The run
# ---------------------------------------------------------------------------

def generate_language(copy_paths, drafts_paths, tag, name, sources, tag_area, font, measurer):
    """Write the language's folder in the copy; (report rows, key rows)."""
    lang = translib.load_language(copy_paths, tag)
    if lang.status != "draft":
        raise DraftApkError("%s has status %s; this tool is only for draft languages" % (tag, lang.status))
    if os.path.isdir(copy_paths.res(lang.folder)):
        raise DraftApkError("HEAD already has res/%s" % lang.folder)
    chosen = choose_drafts(drafts_paths, tag, name)
    rows, key_rows = [], []
    for area in chosen:
        if area not in sources:
            print("warning: %s: %s has no res/values/strings_%s.xml; its draft is ignored" % (tag, area, area))
    for area, area_sources in sources.items():
        pack = packlib.build_pack(copy_paths, tag, area, measurer, sources)
        draft = translib.read_draft(chosen[area]) if area in chosen else None
        states, unknown = classify_area(pack, draft, font, measurer)
        _written, skipped = write_language_file(copy_paths, tag, area, lang, area_sources, tag_area, draft, states)
        for key, why in skipped:
            states[key] = ("failing", why)
        rows.append(area_row(tag, area, pack, states, unknown))
        for key, (state, reason) in states.items():
            if state in ("failing", "stale"):
                key_rows.append({"language": tag, "area": area, "key": key, "state": state, "reason": reason})
        for key in unknown:
            key_rows.append({"language": tag, "area": area, "key": key, "state": "unknown",
                             "reason": "not a translatable key of the English"})
    rows.append(total_row(tag, rows))
    return rows, key_rows


def run(args):
    root = os.path.realpath(args.root or translib.REPO_ROOT)
    check_root(root)
    workdir = check_target("--workdir", args.workdir, root)
    out = check_target("--out", args.out, root)
    tags = list(collections.OrderedDict((t.strip(), None) for t in args.tags.split(",") if t.strip()))
    if not tags:
        raise DraftApkError("--tags names no language")
    for tag in tags:
        translib.check_tag_area(tag, None)
    drafts_paths = translib.Paths(os.path.realpath(args.drafts) if args.drafts else root)
    for tag in tags:
        choose_drafts(drafts_paths, tag, args.name)  # before anything is exported
    os.makedirs(workdir, exist_ok=True)
    os.makedirs(out, exist_ok=True)
    run_dir = tempfile.mkdtemp(prefix="volt-draft-", dir=workdir)
    copy = os.path.join(run_dir, "tree")
    os.makedirs(os.path.join(run_dir, "tmp"))
    try:
        with temporary_files_in(os.path.join(run_dir, "tmp")):
            return run_in_copy(args, root, out, tags, drafts_paths, run_dir, copy)
    finally:
        if args.keep:
            print("kept the copy: %s" % copy)
        else:
            shutil.rmtree(run_dir, ignore_errors=True)


def run_in_copy(args, root, out, tags, drafts_paths, run_dir, copy):
    export_head(root, copy)
    paths = translib.Paths(copy)
    for tag in tags:
        bring_language_files(drafts_paths, paths, tag)
    sources, tag_area = translib.load_source(paths)
    font = fontmetrics.Font(paths.font())
    measurer = fontmetrics.Measurer(paths.font(), use_java=not args.no_java)
    rows, key_rows = [], []
    for tag in tags:
        tag_rows, tag_keys = generate_language(paths, drafts_paths, tag, args.name, sources, tag_area, font, measurer)
        rows.extend(tag_rows)
        key_rows.extend(tag_keys)
    write_report(out, rows, key_rows)
    for row in rows:
        if row["area"] == "TOTAL" and row["lock_alarm_fallback"]:
            print("%s: %d lock or alarm keys show English" % (row["language"], row["lock_alarm_fallback"]))

    for tag in tags:
        set_beta(paths, tag)
    add_locales(paths, tags)
    add_locale_config(copy)
    commit = (_git(root, "rev-parse", "--short=7", "HEAD") or "").strip()
    patch_build_script(copy, draft_version_name(copy, commit))

    catalog_dir = os.path.join(run_dir, "catalog")
    problems, notes = check_copy(paths, tags, args.aapt, catalog_dir)
    for note in notes:
        print("note: %s" % note)
    apk_path = os.path.join(out, APK_NAME)
    env = build_env(run_dir, copy, apk_path)
    if not problems and not args.no_build:
        problems.extend(run_java_checks(run_dir, copy, catalog_dir, env))
    if not problems and not args.no_build:
        bring_dependencies(root, copy)
        prove_release_refused(copy, env)
        problems.extend(build_apk(copy, env, apk_path))
        if not problems:
            folders = dict((t, translib.load_language(paths, t).folder) for t in tags)
            problems.extend(inspect_apk(apk_path, tags, folders))
    for problem in problems:
        print("problem: %s" % problem)
    if problems:
        print("draft_apk: FAILED; no usable APK")
        if os.path.exists(apk_path):
            os.remove(apk_path)
        return 1
    if args.no_build:
        print("draft_apk: checks passed; --no-build, so no APK. Report: %s" % os.path.join(out, REPORT_NAME))
    else:
        print("draft_apk: %s (draft text, never release it). Report: %s" % (apk_path, os.path.join(out, REPORT_NAME)))
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", help="the checkout to export (default: this one)")
    parser.add_argument("--tags", required=True, help="comma-separated language tags, such as es,de")
    parser.add_argument("--workdir", required=True, help="scratch directory outside the checkout")
    parser.add_argument("--out", required=True, help="where the APK and report go, outside the checkout")
    parser.add_argument("--name", help="the draft's config name: drafts/claude-<name>.xml (default: the only one)")
    parser.add_argument("--drafts", help="another root holding l10n/work and the languages' l10n files (default: --root)")
    parser.add_argument("--keep", action="store_true", help="keep the copy under --workdir")
    parser.add_argument("--no-build", action="store_true", help="stop after the report and the Python checks")
    parser.add_argument("--no-java", action="store_true", help="measure widths with the hmtx approximation only")
    parser.add_argument("--aapt", choices=("auto", "off", "required"), default="auto", help="merge.py's aapt check")
    args = parser.parse_args(argv)
    try:
        return run(args)
    except (DraftApkError, translib.L10nError, fontmetrics.FontError) as error:
        print("draft_apk: %s" % error, file=sys.stderr)
        return 2
    except gen_catalog.CatalogError as error:
        for problem in error.problems:
            print("draft_apk: %s" % problem, file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
