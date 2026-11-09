import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import l10nlib  # noqa: E402

B = chr(92)  # a backslash, spelled out so no tool or editor reinterprets an escape in this file
ELLIPSIS = chr(0x2026)


class LexerTest(unittest.TestCase):
    def strings(self, source):
        return [value for _, value in l10nlib.string_literals(source)]

    def test_plain_literals_comments_and_chars(self):
        source = ('class A {\n'
                  '  // "not a literal"\n'
                  '  /* "nor this" */ String a = "Lock" + \'x\' + "scooter";\n'
                  '  char q = \'"\';\n'
                  '}\n')
        self.assertEqual(self.strings(source), ["Lock", "scooter"])
        tokens = l10nlib.lex_java(source)
        self.assertIn(("char", "x"), [(t.kind, t.value) for t in tokens])
        self.assertIn(("char", '"'), [(t.kind, t.value) for t in tokens])

    def test_line_numbers_count_block_comments(self):
        source = 'class A {\n/* one\ntwo */\nString s = "here";\n}\n'
        self.assertEqual(l10nlib.string_literals(source), [(4, "here")])

    def test_escapes(self):
        body = ('Tab' + B + 't' + B + '"q' + B + '"' + B + B + 'end' + B + 'n' + B + '101' + B + '0')
        source = 'String s = "' + body + '";'
        self.assertEqual(self.strings(source), ['Tab\t"q"' + B + 'end\nA' + chr(0)])

    def test_unicode_escapes_are_translated_first(self):
        source = 'String s = "Wait' + B + 'u2026";'
        self.assertEqual(self.strings(source), ["Wait" + ELLIPSIS])
        # An escaped backslash followed by u is not a unicode escape.
        source = 'String s = "' + B + B + 'u2026";'
        self.assertEqual(self.strings(source), [B + "u2026"])
        # Surrogate pairs become one character.
        source = 'String s = "' + B + 'ud83d' + B + 'udd0b";'
        self.assertEqual(self.strings(source), [chr(0x1F50B)])

    def test_operators(self):
        tokens = [t.value for t in l10nlib.lex_java("a += b++ + c -> d :: e >>>= 1.5e+3f;")]
        self.assertEqual(tokens, ["a", "+=", "b", "++", "+", "c", "->", "d", "::", "e", ">>>=", "1.5e+3f", ";"])

    def test_text_block(self):
        source = 'String s = """\n    Hello\n      world\n    """;'
        self.assertEqual(self.strings(source), ["Hello\n  world\n"])

    def test_unterminated(self):
        with self.assertRaises(l10nlib.LexError):
            l10nlib.lex_java('String s = "open\n";')

    def test_lexes_every_java_file_in_the_repository(self):
        root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
        count = 0
        for folder in ("src", "tests", "android-tests"):
            for directory, _, names in os.walk(os.path.join(root, folder)):
                for name in names:
                    if name.endswith(".java"):
                        l10nlib.lex_java(l10nlib.read_text(os.path.join(directory, name)))
                        count += 1
        self.assertGreater(count, 100)


class TextClassTest(unittest.TestCase):
    def test_english_looking(self):
        for value in ("Cancel", "OK", "Lock scooter", "Odo.", "SPORT", "Not reported", "Über"):
            self.assertTrue(l10nlib.is_english_looking(value), value)
        for value in ("app_settings", "com.jjricks.volt", "%1$s", "", " · ", "12", "x", "headlight",
                      "scooter/state", "--output"):
            self.assertFalse(l10nlib.is_english_looking(value), value)

    def test_identifier_like(self):
        for value in ("app_settings", "app_language_migrated", "msg", "text", "com.jjricks.volt", "v2"):
            self.assertTrue(l10nlib.is_identifier_like(value), value)
        for value in ("", "x", "Cancel", "app language", "%1$s", " · ", "12", "--", "km/h", "km/h:", "Über", "über",
                      "scooter/state", "--output", "a__b", "_x", "app_"):
            self.assertFalse(l10nlib.is_identifier_like(value), value)

    def test_multiword(self):
        self.assertTrue(l10nlib.is_multiword("Turn off"))
        self.assertTrue(l10nlib.is_multiword("Don't clear storage."))
        self.assertTrue(l10nlib.is_multiword("%1$s rides left"))
        self.assertFalse(l10nlib.is_multiword("Cancel"))
        self.assertFalse(l10nlib.is_multiword("%d %s"))
        self.assertFalse(l10nlib.is_multiword("12 34"))

    def test_is_multiword_counts_a_word_and_a_symbol(self):
        for value in ("Unrestricted ✓", "Optimized ⚠", "Optimized ⚠\ufe0e", "☆ Star", "✓ Save", "PIN ✓", "★ %1$s pinned"):
            self.assertTrue(l10nlib.is_multiword(value), value)
        # A symbol alone, joined to a word, or with a number is not a word and a mark; ASCII symbols are code.
        for value in ("✓", "⚠\ufe0e", "✓ ✓", "✓Save", "2 ✓", "x = ", "a +", "%d ✓", "Temp -", "km · "):
            self.assertFalse(l10nlib.is_multiword(value), value)

    def test_has_letters_ignores_format_specifiers(self):
        self.assertFalse(l10nlib.has_letters("%1$d%%"))
        self.assertFalse(l10nlib.has_letters("%.1f"))
        self.assertTrue(l10nlib.has_letters("%d km"))


class TsvTest(unittest.TestCase):
    def test_escape_round_trip(self):
        for value in ("plain", " edge ", "tab\there", "line\nbreak", "back" + B + "slash", "cr\r", chr(7) + "bell",
                      "Connecting" + ELLIPSIS + "  " + chr(0xFFFC)):
            escaped = l10nlib.escape_field(value)
            self.assertNotIn("\t", escaped)
            self.assertNotIn("\n", escaped)
            self.assertEqual(l10nlib.unescape_field(escaped), value)

    def test_unknown_escape_is_an_error(self):
        with self.assertRaises(ValueError):
            l10nlib.unescape_field(B + "q")

    def test_read_tsv(self):
        header, rows = l10nlib.read_tsv("# comment\na\tb\n\n1\t2\n3\t4\n", ("a", "b"))
        self.assertEqual(header, ["a", "b"])
        self.assertEqual([r[1] for r in rows], [["1", "2"], ["3", "4"]])
        with self.assertRaises(ValueError):
            l10nlib.read_tsv("a\tb\n1\n", ("a", "b"))
        with self.assertRaises(ValueError):
            l10nlib.read_tsv("x\ty\n", ("a", "b"))


class ConcurrentWriteTest(unittest.TestCase):
    """Several processes on one area: council.log_run keeps every row, and write_text never leaves a torn file."""

    PROCESSES = 6
    ROWS = 100

    def setUp(self):
        import tempfile
        self.root = tempfile.mkdtemp()
        self.addCleanup(__import__("shutil").rmtree, self.root)

    def run_processes(self, script, *args):
        import subprocess
        here = os.path.dirname(os.path.abspath(__file__))
        children = [subprocess.Popen([sys.executable, "-c", script, here, self.root, str(n)] + list(args))
                    for n in range(self.PROCESSES)]
        for child in children:
            self.assertEqual(child.wait(timeout=120), 0)

    def test_log_run_from_several_processes_keeps_every_row(self):
        script = ("import sys, collections\n"
                  "sys.path.insert(0, sys.argv[1])\n"
                  "import council, translib\n"
                  "paths = translib.Paths(sys.argv[2])\n"
                  "for i in range(int(sys.argv[4])):\n"
                  "    council.log_run(paths, 'xx', 'core', collections.OrderedDict([('stage', 'api-call'),\n"
                  "        ('writer', int(sys.argv[3])), ('n', i), ('usage', {'input_tokens': i})]))\n")
        self.run_processes(script, str(self.ROWS))
        import json
        import translib
        path = translib.Paths(self.root).work("xx", "core", "runs.jsonl")
        lines = l10nlib.read_text(path).split("\n")
        self.assertEqual(lines[-1], "")
        rows = [json.loads(line) for line in lines[:-1]]  # each line on its own is valid JSON
        self.assertEqual(len(rows), self.PROCESSES * self.ROWS)
        self.assertEqual(sorted((r["writer"], r["n"]) for r in rows),
                         [(w, n) for w in range(self.PROCESSES) for n in range(self.ROWS)])
        for writer in range(self.PROCESSES):  # each process's rows stay in its own order
            self.assertEqual([r["n"] for r in rows if r["writer"] == writer], list(range(self.ROWS)))

    def test_append_line_after_a_last_line_without_newline(self):
        path = os.path.join(self.root, "a", "runs.jsonl")
        os.makedirs(os.path.dirname(path))
        with open(path, "wb") as handle:
            handle.write(b'{"n":0}')
        l10nlib.append_line(path, '{"n":1}')
        self.assertEqual(l10nlib.read_text(path), '{"n":0}\n{"n":1}\n')

    def test_write_text_is_whole_under_concurrent_writers(self):
        script = ("import sys, os\n"
                  "sys.path.insert(0, sys.argv[1])\n"
                  "import l10nlib\n"
                  "path = os.path.join(sys.argv[2], 'pack.json')\n"
                  "body = ('writer %s\\n' % sys.argv[3]) * 20000\n"
                  "for _ in range(int(sys.argv[4])):\n"
                  "    l10nlib.write_text(path, body)\n"
                  "    text = l10nlib.read_text(path)\n"
                  "    assert len(set(text.splitlines())) == 1 and len(text) == len(body), 'torn file'\n")
        self.run_processes(script, "30")
        self.assertEqual([n for n in os.listdir(self.root) if n != "pack.json"], [])  # no temporary file left

    def test_new_files_from_threads_take_the_umask_and_leave_it(self):
        # In a child process, so the umask set here touches no other test.
        script = ("import sys, os, threading\n"
                  "sys.path.insert(0, sys.argv[1])\n"
                  "import l10nlib\n"
                  "os.umask(0o077)\n"
                  "sys.setswitchinterval(1e-6)  # switch threads often, so a umask race would show\n"
                  "def write(n):\n"
                  "    for i in range(200):\n"
                  "        l10nlib.write_text(os.path.join(sys.argv[2], 't%d-%d.txt' % (n, i)), 'x')\n"
                  "threads = [threading.Thread(target=write, args=(n,)) for n in range(8)]\n"
                  "[t.start() for t in threads]\n"
                  "[t.join() for t in threads]\n"
                  "print(oct(os.umask(0o077)))\n"
                  "print(sorted(set(oct(os.stat(os.path.join(sys.argv[2], f)).st_mode & 0o777)\n"
                  "                 for f in os.listdir(sys.argv[2]))))\n"
                  "print(len(os.listdir(sys.argv[2])))\n")
        import subprocess
        out = subprocess.run([sys.executable, "-c", script, os.path.dirname(os.path.abspath(__file__)), self.root],
                             check=True, capture_output=True, text=True).stdout.split("\n")
        self.assertEqual(out[:3], ["0o77", "['0o600']", "1600"])

    def test_write_text_keeps_mode_and_symlink(self):
        target = os.path.join(self.root, "target.txt")
        l10nlib.write_text(target, "one\n")
        os.chmod(target, 0o640)
        link = os.path.join(self.root, "link.txt")
        os.symlink(target, link)
        l10nlib.write_text(link, "two\n")
        self.assertTrue(os.path.islink(link))
        self.assertEqual(l10nlib.read_text(target), "two\n")
        self.assertEqual(os.stat(target).st_mode & 0o777, 0o640)


if __name__ == "__main__":
    unittest.main()
