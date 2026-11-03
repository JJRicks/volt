"""G7 coverage: every android-tests method that writes a PNG file censuses it (TextCensus.write for the
windows, TextCensus.writeView for a render of one view) or says why it has no census (TextCensus.skip),
so run-release-sweep.py --census finds a census beside every screenshot. Skip is only for renders that
hold no text, so each method that calls it is listed in SKIPS with what it draws. A runner script that
saves its own screencap (run-ride-auto-stop.py's notification shade) moves a device census beside it."""

import os
import re
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
TESTS = os.path.join(ROOT, "android-tests")

# A method declaration's start, on its own line or after a ; { or } (some helpers are one line long).
DECLARATION = re.compile(
    r"(?:^\s*|[;{}]\s*)(?:@\w+\s+)*(?:(?:public|private|protected|static|final|synchronized|abstract)\s+)*"
    r"(?:<[^>]*>\s*)?[\w.]+(?:<[^()]*?>)?(?:\[\])*\s+(\w+)\s*\([^()]*(?:\([^()]*\)[^()]*)*\)\s*(?:throws\s+[\w.,\s]+)?\{")
NOT_NAMES = {"if", "for", "while", "switch", "catch", "synchronized", "return", "new", "try", "else"}
CENSUS = re.compile(r"\bTextCensus\.(?:write|writeView|skip)\(")
SKIP = re.compile(r"\bTextCensus\.skip\(")
# (file, method) -> what it renders. A render with any text must be censused instead (writeView).
SKIPS = {
    ("DeveloperSettingsValidation.java", "verifySwitchGeometry"): "a detached Switch's track and thumb, no text",
}


def method_name(line):
    """The name of the method a line declares, or None."""
    for match in DECLARATION.finditer(line):
        before = line[:match.start(1)].split()
        if match.group(1) in NOT_NAMES or (before and before[-1] in NOT_NAMES | {"=", "->"}) or "new" in before[-2:]:
            continue
        return match.group(1)
    return None


def declares(line):
    return method_name(line) is not None


def skip_sites(text):
    """(line, enclosing method name) for each TextCensus.skip call."""
    lines = text.split("\n")
    current, found = None, []
    for i, line in enumerate(lines):
        name = method_name(line)
        if name:
            current = name
        if SKIP.search(line):
            found.append((i + 1, current))
    return found


def png_writers(text):
    """(line, has census) for each PNG compressed into a file, judged over its enclosing method."""
    lines = text.split("\n")
    starts = [i for i, line in enumerate(lines) if declares(line)]
    found = []
    for i, line in enumerate(lines):
        if "CompressFormat.PNG" not in line:
            continue
        start = max([s for s in starts if s <= i], default=0)
        end = min([s for s in starts if s > i], default=len(lines))
        body = "\n".join(lines[start:end])
        if "FileOutputStream" in body:  # an in-memory PNG (MapTilesValidation's synthetic tile) is no screenshot
            found.append((i + 1, bool(CENSUS.search(body))))
    return found


# A runner script that runs screencap itself, on the host ('exec-out', 'screencap'); a mention in prose is not one.
SCREENCAP = re.compile(r"""['"]screencap['"]""")
PNG_NAME = re.compile(r"""-([\w-]+)\.png['"]""")


def host_png_writers(text, java):
    """For a runner script that runs screencap itself: (PNG name, has census) for each '...-<name>.png' it
    names, or [(None, False)] when it names none (the check cannot see what it writes). The census comes
    from the device: a validation calls TextCensus.writeNotifications for '-<name>.png', and the runner
    moves the .census.tsv / .census-error.txt that arrive with its pull beside the PNG."""
    if not SCREENCAP.search(text):
        return []
    names = sorted(set(PNG_NAME.findall(text)))
    moves = ".census.tsv" in text and ".census-error.txt" in text
    taken = lambda name: re.search(r"TextCensus\.writeNotifications\([^;]*\"-%s\.png\"" % re.escape(name), java) is not None
    return [(name, moves and taken(name)) for name in names] or [(None, False)]


def android_tests_java():
    return "".join(open(os.path.join(TESTS, n), encoding="utf-8").read() for n in sorted(os.listdir(TESTS)) if n.endswith(".java"))


class CensusCoverageTest(unittest.TestCase):
    def test_every_runner_screencap_has_a_census(self):
        java, missing, writers = android_tests_java(), [], 0
        for name in sorted(os.listdir(TESTS)):
            if name.endswith(".py"):
                for png, ok in host_png_writers(open(os.path.join(TESTS, name), encoding="utf-8").read(), java):
                    writers += 1
                    if not ok:
                        missing.append("android-tests/%s: %s" % (name, "-%s.png" % png if png else "a screencap PNG with no literal name"))
        self.assertGreaterEqual(writers, 1)  # run-ride-auto-stop.py's notification shade
        self.assertEqual(missing, [], "these runner screencaps have no device census (TextCensus.writeNotifications) moved beside them")

    def test_the_runner_check_sees_a_missing_census(self):
        java = 'TextCensus.writeNotifications(this, new File(dir, variant + "-notification-shade.png"));'
        bare = "shade = run('exec-out', 'screencap', '-p')\n(d / f'{c}-notification-shade.png').write_bytes(shade)\n"
        moved = bare + "for suffix in ('.census.tsv', '.census-error.txt'): move(shot.stem + suffix)\n"
        self.assertEqual(host_png_writers(bare, java), [("notification-shade", False)])
        self.assertEqual(host_png_writers(moved, java), [("notification-shade", True)])
        self.assertEqual(host_png_writers(moved, java.replace("shade.png", "other.png")), [("notification-shade", False)])
        self.assertEqual(host_png_writers("shot = d / name\nshot.write_bytes(run('exec-out', 'screencap', '-p'))\n", java), [(None, False)])
        self.assertEqual(host_png_writers("# frames come from the real screencap tool\n(d / 'x-a.png').write_bytes(b)\n", java), [])


    def test_every_png_writer_takes_or_skips_a_census(self):
        missing, writers = [], 0
        for name in sorted(os.listdir(TESTS)):
            if name.endswith(".java"):
                for line, ok in png_writers(open(os.path.join(TESTS, name), encoding="utf-8").read()):
                    writers += 1
                    if not ok:
                        missing.append("android-tests/%s:%d" % (name, line))
        self.assertGreater(writers, 30)
        self.assertEqual(missing, [], "these write a PNG without TextCensus.write, writeView or skip")

    def test_skip_is_only_for_listed_text_free_renders(self):
        sites = []
        for name in sorted(os.listdir(TESTS)):
            if name.endswith(".java") and name != "TextCensus.java":
                for line, method in skip_sites(open(os.path.join(TESTS, name), encoding="utf-8").read()):
                    sites.append((name, method, line))
        unlisted = ["android-tests/%s:%d (%s)" % (name, line, method) for name, method, line in sites if (name, method) not in SKIPS]
        self.assertEqual(unlisted, [], "TextCensus.skip outside SKIPS; census the render with TextCensus.writeView instead")
        self.assertEqual(sorted(SKIPS), sorted({(name, method) for name, method, _ in sites}), "a SKIPS entry no longer skips")

    def test_skip_sites_name_their_method(self):
        text = ("    private void thumbs() {\n        TextCensus.skip(file, \"no text\");\n    }\n"
                "    void card(View v) { TextCensus.skip(f, \"x\"); }\n")
        self.assertEqual(skip_sites(text), [(2, "thumbs"), (4, "card")])

    def test_the_check_sees_a_missing_census(self):
        helper = ("    private void shot(String label) throws Exception {\n"
                  "        try (FileOutputStream out = new FileOutputStream(file)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); }\n"
                  "    }\n")
        other = "    private void other() {\n        TextCensus.write(this, file);\n    }\n"
        one_line = "    void shot(String l)throws Exception{try(FileOutputStream o=new FileOutputStream(f)){b.compress(Bitmap.CompressFormat.PNG,100,o);}}\n"
        self.assertEqual(png_writers(helper + other), [(2, False)])
        self.assertEqual(png_writers(helper.replace("}\n    }", "} TextCensus.skip(file, \"pixels\");\n    }") + other), [(2, True)])
        self.assertEqual(png_writers(one_line + other), [(1, False)])
        self.assertEqual(png_writers("    void tile() {\n        bitmap.compress(Bitmap.CompressFormat.PNG, 100, new ByteArrayOutputStream());\n    }\n"), [])


if __name__ == "__main__":
    unittest.main()
