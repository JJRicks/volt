"""G1: ledger_check.py and extract.py against throwaway git repositories."""

import contextlib
import io
import json
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import extract  # noqa: E402
import ledger_check  # noqa: E402
from repo_fixture import TempRepo, java_class  # noqa: E402

UI = "src/com/jjricks/volt/Ui.java"
HEADER = "\t".join(ledger_check.HEADER) + "\n"

BASE_UI = java_class("Ui", """    static String move(String title) { return "Move " + title + " to the top"; }
    static String cancel() { return "Cancel"; }
    static String rides(int n) { return n + (n == 1 ? " ride" : " rides"); }
    static String log() { return "Cancel"; }""")

HEAD_UI = java_class("Ui", """    static String move(String title) { return T.dashMoveTop(title); }
    static String cancel() { return T.commonCancel(); }
    static String rides(int n) { return T.ridesCount(n, n); }
    static String log() { return "Cancel"; }""")

STRINGS = """<resources>
    <string name="common_cancel">Cancel</string>
    <string name="dash_move_top">Move %1$s to the top</string>
    <plurals name="rides_count"><item quantity="one">%1$d ride</item><item quantity="other">%1$d rides</item></plurals>
</resources>
"""

ROWS = [
    UI + ':4\t["Move ", {"hole": "title", "arg": 1, "type": "s"}, " to the top"]\tdash_move_top\ttemplate\t',
    UI + ':5\t["Cancel"]\tcommon_cancel\texact\t',
    UI + ':6\t{"one": [{"hole": "n", "arg": 1, "type": "d", "quantity": true}, " ride"], '
         '"other": [{"hole": "n", "arg": 1, "type": "d", "quantity": true}, " rides"]}\trides_count\tplural\t',
]


class LedgerFormatTest(unittest.TestCase):
    def test_valid_rows(self):
        rows = ledger_check.parse_ledger(HEADER + "\n".join(ROWS) + "\n")
        self.assertEqual([r.kind for r in rows], ["template", "exact", "plural"])
        self.assertEqual(rows[0].literals, {"Move ": 1, " to the top": 1})
        self.assertEqual(rows[2].literals, {" ride": 1, " rides": 1})

    def test_shared_plural_pieces_count_once(self):
        pieces = {"one": ["Skipped ", {"hole": "n", "arg": 1, "type": "d"}, " scooter"],
                  "other": ["Skipped ", {"hole": "n", "arg": 1, "type": "d"}, " scooters"]}
        self.assertEqual(ledger_check.literal_counts(pieces), {"Skipped ": 1, " scooter": 1, " scooters": 1})

    def test_invalid_rows(self):
        cases = [
            ("src/X.java\t[\"a\"]\tk\texact\t", "location"),
            (UI + ":0\t[\"a\"]\tk\texact\t", "location"),
            (UI + ":3\t[\"a\"]\tTODO\tTODO\t", "unfinished draft"),
            (UI + ":3\t[\"a\"]\tk\tmoved\t", "kind must be"),
            (UI + ":3\t[\"a\"]\t-\texact\t", "needs a resource key"),
            (UI + ":3\t[\"a\"]\tk\tremoved\t", "takes no key"),
            (UI + ":3\t[\"a\"\tk\texact\t", "not valid JSON"),
            (UI + ":3\t[\"a\", \"b\"]\tk\texact\t", "exactly one string piece"),
            (UI + ":3\t[{\"hole\": \"x\", \"type\": \"s\"}]\tk\ttemplate\t", "arg"),
            (UI + ":3\t[{\"hole\": \"x\", \"arg\": 1, \"type\": \"q\"}]\tk\ttemplate\t", "type"),
            (UI + ":3\t[\"a\"]\tk\tplural\t", "plural pieces"),
            (UI + ":3\t[{\"char\": \"ab\"}]\tk\ttemplate\t", "char piece"),
        ]
        for row, message in cases:
            with self.assertRaises(ledger_check.LedgerError) as caught:
                ledger_check.parse_ledger(HEADER + row + "\n")
            self.assertIn(message, str(caught.exception), row)

    def test_render_simple(self):
        pieces = ["Move ", {"hole": "title", "arg": 1, "type": "s"}, " to the top"]
        rendered, original = ledger_check.render_simple("Move %1$s to the top", pieces)
        self.assertEqual(rendered, original)
        self.assertIsNone(ledger_check.render_simple("%1$d rides", [{"hole": "n", "arg": 1, "type": "d"}, " rides"]))


class LedgerRepoTest(unittest.TestCase):
    def setUp(self):
        self.repo = TempRepo()
        self.repo.write(UI, BASE_UI)
        self.repo.write("res/values/strings.xml", "<resources><string name=\"app_name\">Volt</string></resources>\n")
        self.base = self.repo.commit("base")
        self.repo.write(UI, HEAD_UI)
        self.repo.write("res/values/strings_common.xml", STRINGS)

    def tearDown(self):
        self.repo.close()

    def check(self, ledger_rows=None):
        if ledger_rows is not None:
            self.repo.write("l10n/ledger/b2.tsv", HEADER + "\n".join(ledger_rows) + "\n")
        head = self.repo.commit("head")
        return ledger_check.run(self.base, head, cwd=self.repo.root)

    def test_missing_rows_fail(self):
        problems, _, summary = self.check()
        text = "\n".join(problems)
        for literal in ('"Move "', '" to the top"', '"Cancel"', '" ride"', '" rides"'):
            self.assertIn(literal, text)
        self.assertIn("5 literal(s) left 1 src file(s)", summary)

    def test_complete_ledger_passes(self):
        problems, warnings, summary = self.check(ROWS)
        self.assertEqual(problems, [])
        self.assertEqual(warnings, [])
        self.assertIn("3 ledger row(s)", summary)

    def test_wrong_line_and_wrong_text_fail(self):
        rows = [ROWS[0].replace(":4\t", ":5\t"), ROWS[1].replace('["Cancel"]', '["Cancel."]'), ROWS[2]]
        problems, _, _ = self.check(rows)
        text = "\n".join(problems)
        self.assertIn("line 5", text)
        self.assertIn("exact row: common_cancel is 'Cancel' but the literal was 'Cancel.'", text)

    def test_template_mismatch_and_unknown_key_fail(self):
        rows = [ROWS[0].replace('" to the top"', '" to top"'), ROWS[1].replace("common_cancel", "common_cancle"), ROWS[2]]
        problems, _, _ = self.check(rows)
        text = "\n".join(problems)
        self.assertIn("template dash_move_top renders", text)
        self.assertIn("common_cancle is not in res/values/strings*.xml", text)

    def test_over_claiming_warns(self):
        rows = ROWS + [UI + ':7\t["Cancel"]\tcommon_cancel\texact\t']
        problems, warnings, _ = self.check(rows)
        self.assertEqual(problems, [])
        self.assertTrue(any("1 more time" in w for w in warnings), warnings)

    def test_extract_drafts_one_row_per_expression(self):
        head = self.repo.commit("head without ledger")
        lines = extract.draft_rows(self.base, head, [], cwd=self.repo.root)
        self.assertEqual(len(lines), 4, lines)  # the template, Cancel, and the two ternary literals
        first = lines[0].split("\t")
        self.assertEqual(first[0], UI + ":4")
        self.assertEqual(json.loads(first[1]), ["Move ", {"hole": "title", "arg": 1, "type": "s"}, " to the top"])
        self.assertEqual(first[2:4], ["TODO", "TODO"])
        # Once rows cover the literals, nothing is drafted.
        rows = ledger_check.parse_ledger(HEADER + "\n".join(ROWS) + "\n")
        self.assertEqual(extract.draft_rows(self.base, head, rows, cwd=self.repo.root), [])


class ConcatenationTest(unittest.TestCase):
    def pieces(self, expression):
        source = "class A { Object f() { return %s; } }" % expression
        return [p for _, value, p, _, _ in extract.literal_expressions(source)][0]

    def test_shapes(self):
        self.assertEqual(self.pieces('"Cancel"'), ["Cancel"])
        self.assertEqual(self.pieces('"Move " + w.title() + " to slot " + (i + 1)'),
                         ["Move ", {"hole": "w.title()", "arg": 1, "type": "s"}, " to slot ",
                          {"hole": "(i + 1)", "arg": 2, "type": "s"}])
        self.assertEqual(self.pieces('count * 2 + " km"'), [{"hole": "count * 2", "arg": 1, "type": "s"}, " km"])
        self.assertEqual(self.pieces("\"Odo\" + '.'"), ["Odo", {"char": "."}])
        self.assertEqual(self.pieces('label(x)[0] + ": " + values.get(k).trim()'),
                         [{"hole": "label(x)[0]", "arg": 1, "type": "s"}, ": ",
                          {"hole": "values.get(k).trim()", "arg": 2, "type": "s"}])

    def test_scan_cli(self):
        path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "extract.py")
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            self.assertEqual(extract.main(["scan", path]), 0)
        self.assertTrue(out.getvalue().startswith("location\tliteral\tenglish\tsink\tpieces\n"))


if __name__ == "__main__":
    unittest.main()
