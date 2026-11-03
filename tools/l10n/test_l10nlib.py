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

    def test_multiword(self):
        self.assertTrue(l10nlib.is_multiword("Turn off"))
        self.assertTrue(l10nlib.is_multiword("Don't clear storage."))
        self.assertTrue(l10nlib.is_multiword("%1$s rides left"))
        self.assertFalse(l10nlib.is_multiword("Cancel"))
        self.assertFalse(l10nlib.is_multiword("%d %s"))
        self.assertFalse(l10nlib.is_multiword("12 34"))

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


if __name__ == "__main__":
    unittest.main()
