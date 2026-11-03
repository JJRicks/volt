"""G1: ledger_check.py and extract.py against throwaway git repositories."""

import contextlib
import io
import json
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import expressions  # noqa: E402
import extract  # noqa: E402
import ledger_check  # noqa: E402
from repo_fixture import TempRepo, java_class  # noqa: E402

UI = "src/com/jjricks/volt/Ui.java"
UNITS = "src/com/jjricks/volt/DisplayUnits.java"
HEADER = "\t".join(ledger_check.HEADER) + "\n"
APP_NAME = "<resources><string name=\"app_name\">Volt</string></resources>\n"

# java_class puts the body's first line on line 4.
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
         '"other": [{"hole": "n", "arg": 1, "type": "d", "quantity": true}, " rides"]}\trides_count\tplural\t'
         'manual: ternary on n == 1',
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
            (UI + ":3\t[{\"hole\": \"a\", \"arg\": 1, \"type\": \"s\"}, {\"hole\": \"b\", \"arg\": 1, \"type\": \"s\"}]"
                  "\tk\ttemplate\t", "two holes claim arg 1"),
            ("tests/UiTest.java:3\t[\"a\"]\tk\texact\t", "must be kind removed"),
            # Re-review finding 4: an "as" other than an Fmt wrapper of the hole is a hand-checked row.
            (UI + ":3\t[{\"hole\": \"x\", \"arg\": 1, \"type\": \"s\", \"as\": \"x.toUpperCase()\"}]\tk\ttemplate\t",
             "needs a note starting \"manual:\""),
            (UI + ":3\t[{\"hole\": \"x\", \"arg\": 1, \"type\": \"s\", \"as\": \"Fmt.text(y)\"}]\tk\ttemplate\t",
             "only Fmt.<method>(..., x, ...) is checked"),
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


class RepoCase(unittest.TestCase):
    """A repository whose base commit holds the given src files and app_name only."""
    FILES = {UI: BASE_UI}

    def setUp(self):
        self.repo = TempRepo()
        for path, text in self.FILES.items():
            self.repo.write(path, text)
        self.repo.write("res/values/strings.xml", APP_NAME)
        self.base = self.repo.commit("base")

    def tearDown(self):
        self.repo.close()

    def check(self, ledger_rows=None, ledger="b2"):
        if ledger_rows is not None:
            self.repo.write("l10n/ledger/%s.tsv" % ledger, HEADER + "\n".join(ledger_rows) + "\n")
        head = self.repo.commit("head")
        return ledger_check.run(self.base, head, cwd=self.repo.root)

    def text(self, path):
        with open(self.repo.path(path), encoding="utf-8") as handle:
            return handle.read()


class LedgerRepoTest(RepoCase):
    def setUp(self):
        super().setUp()
        self.repo.write(UI, HEAD_UI)
        self.repo.write("res/values/strings_common.xml", STRINGS)

    def test_missing_rows_fail(self):
        problems, _, summary = self.check()
        text = "\n".join(problems)
        for literal in ('"Move "', '" to the top"', '"Cancel"', '" ride"', '" rides"'):
            self.assertIn(literal, text)
        self.assertIn("5 literal(s) left 1 src file(s)", summary)

    def test_complete_ledger_passes(self):
        problems, warnings, summary = self.check(ROWS)
        self.assertEqual(problems, [])
        self.assertEqual(len(warnings), 1)
        self.assertIn("manual row", warnings[0])
        self.assertIn("3 new ledger row(s) (1 manual)", summary)
        self.assertIn("3 ledger row(s) agree with the catalog", summary)

    def test_wrong_line_and_wrong_text_fail(self):
        rows = [ROWS[0].replace(":4\t", ":5\t"), ROWS[1].replace('["Cancel"]', '["Cancel."]'), ROWS[2]]
        problems, _, _ = self.check(rows)
        text = "\n".join(problems)
        self.assertIn("do not match the expression on line 5", text)
        self.assertIn("exact row: common_cancel is 'Cancel' but the literal was 'Cancel.'", text)

    def test_template_mismatch_and_unknown_key_fail(self):
        rows = [ROWS[0], ROWS[1].replace("common_cancel", "common_cancle"), ROWS[2]]
        self.repo.write("res/values/strings_common.xml", STRINGS.replace("to the top", "to top"))
        problems, _, _ = self.check(rows)
        text = "\n".join(problems)
        self.assertIn("template dash_move_top renders", text)
        self.assertIn("common_cancle is not in res/values/strings*.xml", text)

    def test_claiming_a_literal_that_stayed_fails(self):
        rows = ROWS + [UI + ':7\t["Cancel"]\tcommon_cancel\texact\t']
        problems, _, _ = self.check(rows)
        self.assertTrue(any("line 7) left, but it is still there at head" in p for p in problems), problems)

    def test_a_hand_written_row_needs_the_manual_note(self):
        problems, _, _ = self.check(ROWS[:2] + [ROWS[2].replace("manual: ternary on n == 1", "")])
        self.assertTrue(any("needs a note starting \"manual:\"" in p for p in problems), problems)

    def test_the_key_must_be_used_at_head(self):
        self.repo.write(UI, HEAD_UI.replace("T.commonCancel()", "\"\""))
        problems, _, _ = self.check(ROWS)
        self.assertTrue(any("key common_cancel is not used in src at head" in p for p in problems), problems)

    def test_extract_drafts_one_row_per_expression(self):
        head = self.repo.commit("head without ledger")
        lines = extract.draft_rows(self.base, head, [], cwd=self.repo.root)
        self.assertEqual(len(lines), 4, lines)  # the template, Cancel, and the two ternary literals
        first = lines[0].split("\t")
        self.assertEqual(first[0], UI + ":4")
        self.assertEqual(json.loads(first[1]), ["Move ", {"hole": "title", "arg": 1, "type": "s"}, " to the top"])
        self.assertEqual(first[2:4], ["TODO", "TODO"])
        rows = ledger_check.parse_ledger(HEADER + "\n".join(ROWS) + "\n")
        self.assertEqual(extract.draft_rows(self.base, head, rows, cwd=self.repo.root), [])


class TemplateOrderTest(RepoCase):
    """Finding 1: holes swapped in the ledger, or arguments swapped at the call site."""
    FILES = {UNITS: java_class("DisplayUnits",
                               '    static String dual(String metric, String imperial) { return imperial + " / " + metric; }')}
    STRINGS = '<resources><string name="units_dual">%s</string></resources>\n'

    def convert(self, call, template="%1$s / %2$s"):
        self.repo.write(UNITS, java_class("DisplayUnits",
                                          "    static String dual(String metric, String imperial) { return %s; }" % call))
        self.repo.write("res/values/strings_units.xml", self.STRINGS % template)

    def row(self, pieces):
        return UNITS + ":4\t%s\tunits_dual\ttemplate\t" % json.dumps(pieces)

    IMPERIAL_FIRST = [{"hole": "imperial", "arg": 1, "type": "s"}, " / ", {"hole": "metric", "arg": 2, "type": "s"}]

    def test_honest_template_passes(self):
        self.convert("T.unitsDual(imperial, metric)")
        problems, _, _ = self.check([self.row(self.IMPERIAL_FIRST)])
        self.assertEqual(problems, [])

    def test_reordered_placeholders_with_matching_call_pass(self):
        self.convert("T.unitsDual(metric, imperial)", "%2$s / %1$s")
        pieces = [{"hole": "imperial", "arg": 2, "type": "s"}, " / ", {"hole": "metric", "arg": 1, "type": "s"}]
        problems, _, _ = self.check([self.row(pieces)])
        self.assertEqual(problems, [])

    def test_swapped_holes_in_the_ledger_fail(self):
        self.convert("T.unitsDual(metric, imperial)")
        pieces = [{"hole": "metric", "arg": 1, "type": "s"}, " / ", {"hole": "imperial", "arg": 2, "type": "s"}]
        problems, _, _ = self.check([self.row(pieces)])
        self.assertTrue(any("do not match the expression on line 4" in p and "imperial" in p for p in problems), problems)

    def test_swapped_arguments_at_the_call_fail(self):
        self.convert("T.unitsDual(metric, imperial)")
        problems, _, _ = self.check([self.row(self.IMPERIAL_FIRST)])
        self.assertTrue(any("no call of unitsDual passes the holes in placeholder order (imperial, metric)" in p
                            for p in problems), problems)

    def test_msg_of_id_counts_as_a_call(self):
        self.convert("Msg.of(T.UNITS_DUAL, imperial, metric).render()")
        problems, _, _ = self.check([self.row(self.IMPERIAL_FIRST)])
        self.assertEqual(problems, [])

    def test_as_names_a_transformed_argument(self):
        self.convert("T.unitsDual(Fmt.text(imperial), metric)")
        pieces = [{"hole": "imperial", "arg": 1, "type": "s", "as": "Fmt.text(imperial)"}, " / ",
                  {"hole": "metric", "arg": 2, "type": "s"}]
        problems, warnings, _ = self.check([self.row(pieces)])
        self.assertEqual(problems, [])
        self.assertTrue(any("the hole imperial is passed as Fmt.text(imperial)" in w for w in warnings), warnings)

    def test_a_transformed_hole_needs_the_manual_note_and_is_listed(self):
        """Re-review finding 4: "as" accepted any expression silently."""
        self.convert("T.unitsDual(imperial.toUpperCase(), metric)")
        pieces = [{"hole": "imperial", "arg": 1, "type": "s", "as": "imperial.toUpperCase()"}, " / ",
                  {"hole": "metric", "arg": 2, "type": "s"}]
        problems, _, _ = self.check([self.row(pieces)])
        self.assertTrue(any('any other "as" needs a note starting "manual:"' in p for p in problems), problems)
        problems, warnings, _ = self.check([self.row(pieces) + "manual: shouted"], ledger="b3")
        self.assertTrue(any("passed as imperial.toUpperCase()" in w for w in warnings), warnings)

    def test_a_manual_note_does_not_excuse_a_parsed_expression(self):
        """Re-review finding 6: the swapped-hole row with a manual note."""
        self.convert("T.unitsDual(metric, imperial)")
        pieces = [{"hole": "metric", "arg": 1, "type": "s"}, " / ", {"hole": "imperial", "arg": 2, "type": "s"}]
        problems, _, _ = self.check([self.row(pieces) + "manual: ternary"])
        self.assertTrue(any("manual row, but line 4" in p and '"hole": "imperial"' in p for p in problems), problems)


class RemovedAndMovedTest(RepoCase):
    """Findings 2a, 8, 10 and 11: removed rows, swaps, stays-english moves and char literals."""
    SCALE = "src/com/jjricks/volt/RouteSpeedScale.java"
    FILES = {
        SCALE: java_class("RouteSpeedScale", '''    static final String SCOOTER = "scooter", GPS = "GPS";
    static String label(double v) { return String.format(java.util.Locale.US, "%.0f km/h", v); }
    static String dead() { return "Unused"; }
    static String backup() { return "Choose what to back up."; }
    static String report(StringBuilder b, String k) { return b.append(' ').append(k).toString(); }
    static String restore() { return "Choose what to restore."; }'''),
    }

    def edit(self, old, new):
        self.repo.write(self.SCALE, self.text(self.SCALE).replace(old, new))

    def test_removed_for_deleted_code_passes(self):
        self.edit('    static String dead() { return "Unused"; }\n', "")
        problems, _, _ = self.check([self.SCALE + ':6\t["Unused"]\t-\tremoved\tdead code'])
        self.assertEqual(problems, [])

    def test_removed_for_a_changed_literal_fails(self):
        self.edit('"%.0f km/h"', '"%.0f kph"')
        problems, warnings, summary = self.check([self.SCALE + ':5\t["%.0f km/h"]\t-\tremoved\tdead code'])
        self.assertTrue(any("removed is for deleted code" in p and "%.0f kph" in p for p in problems), problems)

    def test_moving_the_changed_literal_elsewhere_fails(self):
        self.edit('    static String label(double v) { return String.format(java.util.Locale.US, "%.0f km/h", v); }\n', "")
        self.edit('    static String restore()', '    static String label(double v) { return String.format('
                  'java.util.Locale.US, "%.0f kph", v); }\n    static String restore()')
        problems, warnings, summary = self.check([self.SCALE + ':5\t["%.0f km/h"]\t-\tremoved\tdead code'])
        self.assertTrue(any('new literal "%.0f kph"; an extraction batch adds no text' in p for p in problems), problems)
        self.assertIn("1 new literal(s)", summary)

    def test_removed_with_the_new_text_in_a_constant_fails(self):
        """Re-review finding 1: the changed literal moves into a constant declared elsewhere."""
        self.edit('    static String label(double v) { return String.format(java.util.Locale.US, "%.0f km/h", v); }',
                  '    static String label(double v) { return String.format(java.util.Locale.US, METRIC_FORMAT, v); }')
        self.edit('    static final String SCOOTER', '    private static final String METRIC_FORMAT = "%.0f kph";\n'
                                                     '    static final String SCOOTER')
        problems, _, _ = self.check([self.SCALE + ':5\t["%.0f km/h"]\t-\tremoved\tdead code'])
        self.assertTrue(any("removed is for deleted code" in p and "METRIC_FORMAT in its place" in p
                            for p in problems), problems)
        self.assertTrue(any('new literal "%.0f kph"' in p for p in problems), problems)

    def test_removed_with_an_existing_constant_in_its_place_fails(self):
        self.edit('return "Unused";', "return SCOOTER;")
        problems, _, _ = self.check([self.SCALE + ':6\t["Unused"]\t-\tremoved\tdead code'])
        self.assertTrue(any("removed is for deleted code" in p and "SCOOTER" in p for p in problems), problems)

    def test_stays_english_decoy_fails(self):
        """Re-review finding 1: the old value re-added elsewhere while its site shows other text."""
        self.edit('return "Choose what to back up.";', "return restore();")
        self.edit('    static String dead()', '    static final String DECOY = "Choose what to back up.";\n\n'
                                              '    static String dead()')
        problems, _, _ = self.check([self.SCALE + ':7\t["Choose what to back up."]\t-\tstays-english\t'])
        self.assertTrue(any("is not added anywhere else in src where the code it left points" in p
                            for p in problems), problems)

    def test_stays_english_into_a_named_constant_passes(self):
        self.edit('return "Unused";', "return UNUSED;")
        self.edit('    static String restore()', '    static String restore2() { return null; }\n\n'
                                                 '    static final String UNUSED = "Unused";\n    static String restore()')
        problems, _, _ = self.check([self.SCALE + ':6\t["Unused"]\t-\tstays-english\tsource id'])
        self.assertEqual(problems, [])

    def test_swapping_two_literals_fails(self):
        self.edit("Choose what to back up.", "PLACEHOLDER")
        self.edit("Choose what to restore.", "Choose what to back up.")
        self.edit("PLACEHOLDER", "Choose what to restore.")
        problems, _, _ = self.check()
        self.assertTrue(any('"Choose what to back up." left with no ledger row' in p for p in problems), problems)
        self.assertTrue(any('"Choose what to restore." left with no ledger row' in p for p in problems), problems)

    def test_changed_char_literal_fails(self):
        self.edit("b.append(' ')", "b.append(':')")
        problems, _, _ = self.check()
        self.assertTrue(any('char " " left with no ledger row' in p for p in problems), problems)

    def test_stays_english_move_to_another_class_passes(self):
        self.edit('    static final String SCOOTER = "scooter", GPS = "GPS";\n', "")
        self.repo.write("src/com/jjricks/volt/SpeedSources.java",
                        java_class("SpeedSources", '    static final String SCOOTER = "scooter", GPS = "GPS";'))
        rows = [self.SCALE + ':4\t["scooter"]\t-\tstays-english\tsource ids',
                self.SCALE + ':4\t["GPS"]\t-\tstays-english\tsource ids']
        problems, warnings, _ = self.check(rows)
        self.assertEqual(problems, [])
        self.assertFalse(any("new English-looking" in w for w in warnings), warnings)

    def test_stays_english_needs_the_literal_to_arrive_elsewhere(self):
        self.edit('    static final String SCOOTER = "scooter", GPS = "GPS";\n', "")
        rows = [self.SCALE + ':4\t["scooter"]\t-\tstays-english\t', self.SCALE + ':4\t["GPS"]\t-\tstays-english\t']
        problems, _, _ = self.check(rows)
        self.assertTrue(any('"GPS" left' in p and "not added anywhere else" in p for p in problems), problems)

    def test_a_swap_is_not_a_stays_english_move(self):
        self.edit('"scooter", GPS = "GPS"', '"GPS", GPS = "scooter"')
        rows = [self.SCALE + ':4\t["scooter"]\t-\tstays-english\t', self.SCALE + ':4\t["GPS"]\t-\tstays-english\t']
        problems, _, _ = self.check(rows)
        self.assertTrue(any("not added anywhere else" in p for p in problems), problems)

    def test_decoy_keeping_the_old_literal_fails(self):
        self.repo.write("res/values/strings_backup.xml",
                        '<resources><string name="backup_choose">Choose what to back up.</string></resources>\n')
        self.edit('return "Choose what to back up.";', 'String unused = "Choose what to back up."; return T.backupChoose();')
        problems, _, _ = self.check([self.SCALE + ':7\t["Choose what to back up."]\tbackup_choose\texact\t'])
        self.assertTrue(any("still there at head" in p for p in problems), problems)


class ChangesAroundTextTest(RepoCase):
    """Re-review 3, findings 2 and 3: a removed row deletes a whole statement, and a batch changes
    nothing around text besides replacing a row's expression with its call."""
    PICKER = "src/com/jjricks/volt/StatsWindowPicker.java"
    FILES = {PICKER: java_class("StatsWindowPicker", '''    static void describe(Button button, String label) { button.setContentDescription("Chart time window: " + label + ". Choose time window"); }
    static String title() { return "Chart window"; }
    static String skip() { return "Skip"; }
    static int size() { return 20; }''')}
    DESCRIBE_ROW = PICKER + ':4\t["Chart time window: ", {"hole": "label", "arg": 1, "type": "s"}, ". Choose time window"]' \
        '\t-\tremoved\tdead text'
    TITLE_ROW = PICKER + ':5\t["Chart window"]\tstats_window_title\texact\t'
    STRINGS = '<resources><string name="stats_window_title">Chart window</string></resources>\n'

    def edit(self, old, new):
        self.repo.write(self.PICKER, self.text(self.PICKER).replace(old, new))

    def test_removing_part_of_a_statement_fails(self):
        self.edit('"Chart time window: " + label + ". Choose time window"', "label")
        problems, warnings, _ = self.check([self.DESCRIBE_ROW])
        self.assertTrue(any("only part of the statement" in p and "setContentDescription ( label )" in p
                            for p in problems), problems)

    def test_removing_the_whole_statement_passes_and_is_listed(self):
        self.edit('button.setContentDescription("Chart time window: " + label + ". Choose time window"); ', "")
        problems, warnings, _ = self.check([self.DESCRIBE_ROW])
        self.assertEqual(problems, [])
        self.assertTrue(any("removed row at %s:4 deletes `button . setContentDescription" % self.PICKER in w
                            for w in warnings), warnings)

    def test_removing_one_of_two_statements_on_a_line_passes(self):
        self.edit('{ button.setContentDescription(', '{ button.setEnabled(true); button.setContentDescription(')
        self.repo.commit("two statements")
        self.base = self.repo.git("rev-parse", "HEAD")
        self.edit(' button.setContentDescription("Chart time window: " + label + ". Choose time window");', "")
        problems, _, _ = self.check([self.DESCRIBE_ROW])
        self.assertEqual(problems, [])

    def test_wrapping_a_kept_literal_fails(self):
        self.edit('return "Skip";', 'return "Skip".toUpperCase();')
        problems, _, _ = self.check([])
        self.assertTrue(any("%s:6: this change adds `. toUpperCase ( )`" % self.PICKER in p for p in problems), problems)

    def test_wrapping_the_new_call_fails(self):
        self.repo.write("res/values/strings_stats.xml", self.STRINGS)
        self.edit('return "Chart window";', "return T.statsWindowTitle().toUpperCase();")
        problems, _, _ = self.check([self.TITLE_ROW])
        self.assertTrue(any("%s:5: this change adds `. toUpperCase ( )`" % self.PICKER in p for p in problems), problems)

    def test_dropping_code_beside_text_fails(self):
        self.edit('"Chart time window: " + label + ". Choose time window"', '"Chart time window: " + label')
        problems, _, _ = self.check([])
        self.assertTrue(any("drops `+`" in p or "drops `+ \". Choose" in p or ". Choose time window" in p
                            for p in problems), problems)

    def test_honest_conversion_passes_and_other_code_is_listed(self):
        self.repo.write("res/values/strings_stats.xml", self.STRINGS)
        self.edit('return "Chart window";', "return T.statsWindowTitle();")
        self.edit("return 20;", "return 21;")
        problems, warnings, _ = self.check([self.TITLE_ROW])
        self.assertEqual(problems, [])
        self.assertTrue(any("%s:7: this change adds `21` and drops `20`, away from any text" % self.PICKER in w
                            for w in warnings), warnings)

    def test_new_preference_keys_pass_and_are_listed(self):
        """Re-review 5: B0b's planned preference keys and requireNonNull messages are not text."""
        self.edit("    static int size()", '    static final String CHOICE = "app_language";\n'
                  '    static void check(Object msg) { if (msg == null) throw new NullPointerException("msg"); }\n'
                  '    static int size()')
        problems, warnings, summary = self.check([])
        self.assertEqual(problems, [])
        self.assertTrue(any('new key or id literal "app_language" in `static final String CHOICE = "app_language"`'
                            in w for w in warnings), warnings)
        self.assertTrue(any('new key or id literal "msg" in `if ( msg == null ) throw new NullPointerException ( "msg" )`'
                            in w for w in warnings), warnings)
        self.assertTrue(any("away from any text" in w for w in warnings), warnings)
        self.assertIn("0 new literal(s); 2 new key or id literal(s), listed", summary)

    def test_a_long_statement_shows_the_code_around_its_key(self):
        """Re-review 6: the excerpt used to stop after 40 tokens, before a key late in a long statement."""
        args = ", ".join("a%d" % i for i in range(30))
        self.edit("    static int size()", '    static void store(Object %s) { put(%s, "late_key"); }\n'
                  '    static int size()' % (", Object ".join("a%d" % i for i in range(30)), args))
        problems, warnings, _ = self.check([])
        self.assertEqual(problems, [])
        shown = [w for w in warnings if 'new key or id literal "late_key"' in w]
        self.assertEqual(len(shown), 1, warnings)
        self.assertIn('a29 , "late_key" )', shown[0])
        self.assertIn("`... ", shown[0])

    def test_new_key_beside_kept_text_still_fails(self):
        self.edit('return "Skip";', 'skipped("stats_skip"); return "Skip";')
        problems, warnings, _ = self.check([])
        self.assertTrue(any("%s:6: this change adds `skipped" % self.PICKER in p and "around text" in p
                            for p in problems), problems)
        self.assertTrue(any('new key or id literal "stats_skip"' in w for w in warnings), warnings)

    def test_new_text_that_is_not_a_key_still_fails(self):
        for literal in ("Language", "app language", "%d km", " / ", "km/h", "x", "12", "50% off"):
            with self.subTest(literal=literal):
                self.repo.write(self.PICKER, self.FILES[self.PICKER])
                self.edit("    static int size()", '    static final String LABEL = "%s";\n    static int size()' % literal)
                problems, _, _ = self.check([])
                self.assertTrue(any("new literal %s" % json.dumps(literal) in p for p in problems), problems)

    def test_lowering_a_literal_to_key_case_still_fails(self):
        self.edit('return "Skip";', 'return "skip";')
        problems, _, _ = self.check([])
        self.assertTrue(any('literal "Skip" left with no ledger row' in p for p in problems), problems)
        self.edit('return "skip";', 'return T.statsSkip();')
        self.repo.write("res/values/strings_stats.xml",
                        '<resources><string name="stats_skip">skip</string></resources>\n')
        problems, _, _ = self.check([self.PICKER + ':6\t["Skip"]\tstats_skip\texact\t'])
        self.assertTrue(any("stats_skip is 'skip' but the literal was 'Skip'" in p for p in problems), problems)


class RemovedBlockTest(RepoCase):
    """Re-review 4, finding 1: a removed row's statement deleted with its enclosing block must not
    take the block's other statements (a setText with no literal) along unseen."""
    CARD = "src/com/jjricks/volt/ChargingCard.java"
    FILES = {CARD: java_class("ChargingCard", '''    void update(TextView chargingTemperature, double temperature) {
        if (chargingTemperature != null) {
            String unit = units("temperature_c");
            chargingTemperature.setText(format(temperature));
            chargingTemperature.setTextColor(0xffffffff);
            describeCharging(chargingTemperature, "Battery temperature", "");
        }
        if (temperature > 60) {
            describeCharging(chargingTemperature, "Battery hot", "");
        }
        refresh();
    }''')}
    TEMP_ROWS = [CARD + ':9\t["Battery temperature"]\t-\tremoved\tdead label',
                 CARD + ':6\t["temperature_c"]\t-\tremoved\tdead unit',
                 CARD + ':9\t[""]\t-\tremoved\tdead hint']

    def delete_lines(self, first, last):
        lines = self.text(self.CARD).split("\n")
        self.repo.write(self.CARD, "\n".join(lines[:first - 1] + lines[last:]))

    def test_a_block_with_an_unrecorded_statement_fails(self):
        self.delete_lines(5, 10)
        problems, _, _ = self.check(self.TEMP_ROWS)
        self.assertTrue(any("deletes the whole block" in p and "chargingTemperature . setText ( format ( temperature ) )"
                            in p for p in problems), problems)

    def test_a_whole_block_note_passes_and_lists_the_entire_block(self):
        self.delete_lines(5, 10)
        rows = list(self.TEMP_ROWS)
        rows[0] = rows[0].replace("\tdead label", "\twhole block: the charging card loses its temperature")
        problems, warnings, _ = self.check(rows)
        self.assertEqual(problems, [])
        self.assertTrue(any("deletes the whole block (base lines 5-10)" in w and "as a whole block: note asks" in w and
                            "chargingTemperature . setText ( format ( temperature ) ) ;" in w and
                            "setTextColor" in w for w in warnings), warnings)

    def test_a_block_holding_only_recorded_statements_passes_and_is_listed(self):
        self.delete_lines(11, 13)
        problems, warnings, _ = self.check([self.CARD + ':12\t["Battery hot"]\t-\tremoved\tdead warning',
                                            self.CARD + ':12\t[""]\t-\tremoved\tdead hint'])
        self.assertEqual(problems, [])
        self.assertTrue(any("deletes the whole block (base lines 11-13)" in w and "if ( temperature > 60 )" in w
                            for w in warnings), warnings)

    def test_a_row_spanning_a_deleted_block_and_a_lone_statement_lists_both(self):
        """Re-review 5: a manual removed row covering a literal in a deleted block and one in a lone
        deleted statement must still print the lone statement (the temperature readout)."""
        card = "src/com/jjricks/volt/Card.java"
        self.repo.write(card, java_class("Card", '''    void update(TextView t, double temp) {
        t.setText(format(temp) + " C");
        if (t != null) {
            describe(t, "Battery temperature");
        }
        refresh();
    }'''))
        self.base = self.repo.commit("card")
        lines = self.text(card).split("\n")
        self.repo.write(card, "\n".join(lines[:4] + lines[8:]))
        problems, warnings, _ = self.check([card + ':5\t[" C", "Battery temperature"]\t-\tremoved\tmanual: two dead labels'])
        self.assertEqual(problems, [])
        self.assertTrue(any("deletes `t . setText ( format ( temp ) + \" C\" )`" in w for w in warnings), warnings)
        self.assertTrue(any("deletes the whole block (base lines 6-8)" in w for w in warnings), warnings)
        self.assertFalse(any("deletes `describe" in w for w in warnings), warnings)

    def test_a_deleted_statement_alone_still_passes(self):
        self.delete_lines(12, 12)
        problems, warnings, _ = self.check([self.CARD + ':12\t["Battery hot"]\t-\tremoved\tdead warning',
                                            self.CARD + ':12\t[""]\t-\tremoved\tdead hint'])
        self.assertEqual(problems, [])
        self.assertTrue(any("deletes `describeCharging ( chargingTemperature , \"Battery hot\" , \"\" )`" in w
                            for w in warnings), warnings)


class CallSiteTest(RepoCase):
    """Re-review finding 3: a row's call must sit where its literal was, so swapped keys fail."""
    PICKER = "src/com/jjricks/volt/StatsWindowPicker.java"
    FILES = {PICKER: java_class("StatsWindowPicker", '''    String title() { return "Chart window"; }
    int unchanged() { return 1; }
    String close() { return "Close chart window choices"; }
    String a() { return "Choose"; }
    String b() { return "Choose again"; }''')}
    STRINGS = """<resources>
    <string name="stats_window_title">Chart window</string>
    <string name="stats_window_close">Close chart window choices</string>
    <string name="stats_choose">Choose</string>
    <string name="stats_choose_again">Choose again</string>
</resources>
"""
    ROWS = [PICKER + ':4\t["Chart window"]\tstats_window_title\texact\t',
            PICKER + ':6\t["Close chart window choices"]\tstats_window_close\texact\t']

    def convert(self, title, close):
        text = self.text(self.PICKER).replace('return "Chart window";', "return %s;" % title)
        self.repo.write(self.PICKER, text.replace('return "Close chart window choices";', "return %s;" % close))
        self.repo.write("res/values/strings_stats.xml", self.STRINGS)

    def test_honest_conversion_passes(self):
        self.convert("T.statsWindowTitle()", "T.statsWindowClose()")
        self.assertEqual(self.check(self.ROWS)[0], [])

    def test_keys_swapped_between_sites_fail(self):
        self.convert("T.statsWindowClose()", "T.statsWindowTitle()")
        problems, _, _ = self.check(self.ROWS)
        self.assertEqual(len([p for p in problems if "not in the head lines that replaced the literal" in p]), 2,
                         problems)

    def test_keys_swapped_within_one_region_fail(self):
        text = self.text(self.PICKER).replace('return "Choose";', "return T.statsChooseAgain();")
        self.repo.write(self.PICKER, text.replace('return "Choose again";', "return T.statsChoose();"))
        self.repo.write("res/values/strings_stats.xml", self.STRINGS)
        rows = [self.PICKER + ':7\t["Choose"]\tstats_choose\texact\t',
                self.PICKER + ':8\t["Choose again"]\tstats_choose_again\texact\t']
        problems, _, _ = self.check(rows)
        self.assertTrue(any("comes before the call of an earlier row" in p for p in problems), problems)

    def test_one_call_cannot_serve_two_rows(self):
        text = self.text(self.PICKER).replace('return "Choose";', "return T.statsChoose();")
        self.repo.write(self.PICKER, text.replace('    String b() { return "Choose again"; }', "    String b() { return null; }"))
        self.repo.write("res/values/strings_stats.xml", self.STRINGS)
        rows = [self.PICKER + ':7\t["Choose"]\tstats_choose\texact\t',
                self.PICKER + ':8\t["Choose again"]\tstats_choose\texact\t']
        problems, _, _ = self.check(rows)
        self.assertTrue(any("already used by another row" in p for p in problems), problems)


class CatalogHistoryTest(RepoCase):
    """Finding 14: a later pull request rewording an extracted string; ledgers are append-only."""

    def setUp(self):
        super().setUp()
        self.repo.write(UI, HEAD_UI)
        self.repo.write("res/values/strings_common.xml", STRINGS)
        self.repo.write("l10n/ledger/b2.tsv", HEADER + "\n".join(ROWS) + "\n")
        self.base = self.repo.commit("batch merged")

    def test_rewording_an_extracted_string_fails(self):
        self.repo.write("res/values/strings_common.xml", STRINGS.replace(">Cancel<", ">Cancel now<"))
        problems, _, _ = self.check()
        self.assertTrue(any("exact row: common_cancel is 'Cancel now' but the literal was 'Cancel'" in p
                            for p in problems), problems)
        head = self.repo.git("rev-parse", "HEAD")
        self.assertTrue(ledger_check.catalog_consistency(head, self.repo.root)[0])

    def test_a_recorded_english_change_passes(self):
        self.repo.write("res/values/strings_common.xml", STRINGS.replace(">Cancel<", ">Cancel now<"))
        self.repo.write("l10n/english-changes.tsv", "key\tquantity\told\tnew\tnote\n"
                                                    "common_cancel\t-\tCancel\tCancel now\towner approved in D1\n")
        problems, _, _ = self.check()
        self.assertEqual(problems, [])

    def test_an_english_change_for_an_unknown_key_fails(self):
        """Re-review finding 9: english-changes rows are checked against the catalog."""
        self.repo.write("l10n/english-changes.tsv", "key\tquantity\told\tnew\tnote\n"
                                                    "zz_nothing\t-\ta\tb\towner approved in #999\n")
        problems, _, _ = self.check()
        self.assertTrue(any("names key zz_nothing, which is not in res/values" in p for p in problems), problems)

    def test_ledgers_are_append_only(self):
        self.repo.write("l10n/ledger/b2.tsv", HEADER + "\n".join(ROWS[:2]) + "\n")
        problems, _, _ = self.check()
        self.assertTrue(any("append-only" in p for p in problems), problems)


class DraftAndRelocateTest(RepoCase):
    """Findings 24 and 25: drafts name the converted expression; rows follow a rebase."""
    PICKER = "src/com/jjricks/volt/StatsWindowPicker.java"
    FILES = {PICKER: java_class("StatsWindowPicker", '''    String a(String[] labels, int selected) { return "Chart time window: " + labels[selected] + ". Choose time window"; }
    String b(String[] labels, int index) { return "Chart time window: " + labels[index]; }''')}

    def convert_b(self):
        self.repo.write(self.PICKER, self.text(self.PICKER).replace(
            'return "Chart time window: " + labels[index];', "return T.statsWindowCurrent(labels[index]);"))
        self.repo.write("res/values/strings_stats.xml",
                        '<resources><string name="stats_window_current">Chart time window: %1$s</string></resources>\n')

    def test_draft_names_the_converted_expression(self):
        self.convert_b()
        head = self.repo.commit("convert b")
        lines = extract.draft_rows(self.base, head, [], cwd=self.repo.root)
        self.assertEqual(len(lines), 1, lines)
        location, pieces = lines[0].split("\t")[:2]
        self.assertEqual(location, self.PICKER + ":5")
        self.assertEqual(json.loads(pieces), ["Chart time window: ", {"hole": "labels[index]", "arg": 1, "type": "s"}])

    def test_relocate_after_main_moved_lines(self):
        self.repo.git("checkout", "-q", "-b", "batch")
        self.convert_b()
        row = self.PICKER + ':5\t["Chart time window: ", {"hole": "labels[index]", "arg": 1, "type": "s"}]' \
                            '\tstats_window_current\ttemplate\t'
        self.repo.write("l10n/ledger/b2.tsv", HEADER + row + "\n")
        self.repo.commit("batch")
        self.repo.git("checkout", "-q", "main")
        self.repo.write(self.PICKER, "// moved\n// down\n" + self.text(self.PICKER))
        main_tip = self.repo.commit("main moved lines")
        self.repo.git("checkout", "-q", "batch")
        self.repo.git("rebase", "-q", "main")
        head = self.repo.git("rev-parse", "HEAD")
        self.assertTrue(any("line 5" in p for p in ledger_check.run(main_tip, head, cwd=self.repo.root)[0]))
        text, moved, problems = extract.relocate(self.text("l10n/ledger/b2.tsv"), main_tip, cwd=self.repo.root)
        self.assertEqual((moved, problems), (1, []))
        self.assertIn(self.PICKER + ":7\t", text)
        self.repo.write("l10n/ledger/b2.tsv", text)
        head = self.repo.commit("relocated")
        self.assertEqual(ledger_check.run(main_tip, head, cwd=self.repo.root)[0], [])


FMT_STRINGS = """<resources>
    <string name="fmt_quantity">%1$s %2$s</string>
    <string name="fmt_tight">%1$s%2$s</string>
    <string name="fmt_range">%1$s–%2$s %3$s</string>
    <string name="units_kmh">km/h</string>
    <string name="units_mph">mph</string>
    <string name="units_watt">W</string>
    <string name="units_mi">mi</string>
</resources>
"""


def spec(text, arg, value, call):
    return {"spec": text, "arg": arg, "value": value, "as": call}


def unit(text, key, arg):
    return {"text": text, "key": key, "arg": arg}


class FormatRowTest(RepoCase):
    """B1: a String.format literal mixing specifiers and unit text, or a + chain with unit text, splits
    into a template, text keys and Fmt calls; every careless variant fails."""
    FILES = {UNITS: java_class("DisplayUnits", '''    static String speed(double kmh) { return String.format(Locale.US, "%.1f mph", kmh / KM_PER_MILE); }
    static String count(int n) { return n + " km/h"; }
    static String watts(double w) { return String.format(Locale.US, "%,.0f W", w); }
    static String range(double a, double b) { return String.format(Locale.US, "%.1f–%.1f mph", a, b); }
    static String plain(int n) { return String.format("%d mph", n); }
    static String either(double x, String u) { return String.format(Locale.US, "%.2f %s", x,
        u.equals("km/h") ? "mph" : "mi"); }''')}
    FMT_TEST = "tests/l10n/FmtLegacyTest.java"
    SOURCES = "tests/l10n/b1.sources"
    SPEED = [spec("%.1f", 1, "kmh / KM_PER_MILE", "Fmt.decimal(Locale.US, kmh / KM_PER_MILE, 1)"), " ",
             unit("mph", "units_mph", 2)]
    SPEED_CALL = "T.fmtQuantity(Fmt.decimal(Locale.US, kmh / KM_PER_MILE, 1), T.unitsMph())"

    def setUp(self):
        super().setUp()
        self.repo.write("res/values/strings_fmt.xml", FMT_STRINGS)
        self.repo.write(self.FMT_TEST, java_class("FmtLegacyTest", "    static void run() { DisplayUnits.speed(1); "
                                                  "DisplayUnits.count(1); DisplayUnits.watts(1); DisplayUnits.range(1, 2); "
                                                  "DisplayUnits.plain(1); Fmt.decimal(Locale.US, 1, 1); "
                                                  "Fmt.grouped(Locale.US, 1, 0); Fmt.integer(1); }"))
        self.repo.write(self.SOURCES, "# G10\n%s  # parity\n" % self.FMT_TEST)

    def convert(self, old, new):
        self.repo.write(UNITS, self.text(UNITS).replace(old, new))

    def row(self, line, split, key="fmt_quantity", note=""):
        return "%s:%d\t%s\t%s\tformat\t%s" % (UNITS, line, json.dumps(split, ensure_ascii=False), key, note)

    def speed(self, call=SPEED_CALL, split=SPEED, key="fmt_quantity"):
        self.convert('String.format(Locale.US, "%.1f mph", kmh / KM_PER_MILE)', call)
        return self.check([self.row(4, split, key)])

    def test_honest_string_format_passes_and_is_listed(self):
        problems, warnings, _ = self.speed()
        self.assertEqual(problems, [])
        shown = [w for w in warnings if "format row at %s:4" % UNITS in w]
        self.assertEqual(len(shown), 1, warnings)
        self.assertIn('`String . format ( Locale . US , "%.1f mph" , kmh / KM_PER_MILE )` becomes '
                      '`T . fmtQuantity ( Fmt . decimal ( Locale . US , kmh / KM_PER_MILE , 1 ) , T . unitsMph ( ) )`',
                      shown[0])
        self.assertIn('text "mph" -> units_mph', shown[0])
        self.assertIn("%.1f -> Fmt.decimal(Locale.US, kmh / KM_PER_MILE, 1)", shown[0])
        self.assertIn("a method named DisplayUnits.speed is called by %s" % self.FMT_TEST, shown[0])

    def test_honest_plus_chain_passes(self):
        self.convert('n + " km/h"', "T.fmtQuantity(Fmt.integer(n), T.unitsKmh())")
        split = [{"hole": "n", "arg": 1, "type": "s", "as": "Fmt.integer(n)"}, " ", unit("km/h", "units_kmh", 2)]
        problems, warnings, _ = self.check([self.row(5, split)])
        self.assertEqual(problems, [])
        self.assertTrue(any("`n + \" km/h\"` becomes" in w and "n -> Fmt.integer(n)" in w for w in warnings), warnings)

    def test_grouping_and_two_specifiers_pass(self):
        self.convert('String.format(Locale.US, "%,.0f W", w)', "T.fmtQuantity(Fmt.grouped(Locale.US, w, 0), T.unitsWatt())")
        self.convert('String.format(Locale.US, "%.1f–%.1f mph", a, b)',
                     "T.fmtRange(Fmt.decimal(Locale.US, a, 1), Fmt.decimal(Locale.US, b, 1), T.unitsMph())")
        rows = [self.row(6, [spec("%,.0f", 1, "w", "Fmt.grouped(Locale.US, w, 0)"), " ", unit("W", "units_watt", 2)]),
                self.row(7, [spec("%.1f", 1, "a", "Fmt.decimal(Locale.US, a, 1)"), "–",
                             spec("%.1f", 2, "b", "Fmt.decimal(Locale.US, b, 1)"), " ", unit("mph", "units_mph", 3)],
                         "fmt_range")]
        problems, _, _ = self.check(rows)
        self.assertEqual(problems, [])

    def test_wrong_unit_key_in_the_code_fails(self):
        problems, _, _ = self.speed(call=self.SPEED_CALL.replace("unitsMph", "unitsKmh"))
        self.assertTrue(any("no call of fmtQuantity passes the holes in placeholder order" in p and "T . unitsMph ( )" in p
                            for p in problems), problems)

    def test_wrong_unit_key_in_the_row_fails(self):
        split = [dict(p, key="units_kmh") if isinstance(p, dict) and "key" in p else p for p in self.SPEED]
        problems, _, _ = self.speed(call=self.SPEED_CALL.replace("unitsMph", "unitsKmh"), split=split)
        self.assertTrue(any("text key units_kmh is 'km/h' but that part of the literal was 'mph'" in p
                            for p in problems), problems)

    def test_swapped_pieces_fail(self):
        swapped_call = "T.fmtQuantity(T.unitsMph(), Fmt.decimal(Locale.US, kmh / KM_PER_MILE, 1))"
        problems, _, _ = self.speed(call=swapped_call)
        self.assertTrue(any("no call of fmtQuantity passes" in p for p in problems), problems)
        split = [unit("mph", "units_mph", 1), " ", dict(self.SPEED[0], arg=2)]
        problems, _, _ = self.speed(call=swapped_call, split=split)
        self.assertTrue(any('its split reassembles to ["mph %.1f"]' in p for p in problems), problems)

    def test_swapped_placeholder_numbers_fail(self):
        """The split says the unit is arg 1 in the right order; the template puts arg 1 first."""
        split = [dict(self.SPEED[0], arg=2), " ", unit("mph", "units_mph", 1)]
        problems, _, _ = self.speed(call="T.fmtQuantity(T.unitsMph(), Fmt.decimal(Locale.US, kmh / KM_PER_MILE, 1))",
                                    split=split)
        self.assertTrue(any("template fmt_quantity renders 'mph \\x01B'" in p for p in problems), problems)

    def test_changed_specifier_fails(self):
        call = self.SPEED_CALL.replace(", 1)", ", 0)")
        zero = dict(self.SPEED[0], **{"as": "Fmt.decimal(Locale.US, kmh / KM_PER_MILE, 0)"})
        problems, _, _ = self.speed(call=call, split=[zero] + self.SPEED[1:])
        self.assertTrue(any("the specifier %.1f of kmh / KM_PER_MILE prints as Fmt.decimal(Locale.US, kmh / KM_PER_MILE, 1)"
                            in p for p in problems), problems)
        problems, _, _ = self.speed(call=call, split=[spec("%.0f", 1, "kmh / KM_PER_MILE",
                                                          "Fmt.decimal(Locale.US, kmh / KM_PER_MILE, 0)")] + self.SPEED[1:])
        self.assertTrue(any('its split reassembles to ["%.0f mph"]' in p for p in problems), problems)

    def test_dropped_space_fails(self):
        problems, _, _ = self.speed(split=[self.SPEED[0], self.SPEED[2]])
        self.assertTrue(any('its split reassembles to ["%.1fmph"]' in p for p in problems), problems)

    def test_a_template_without_the_space_fails(self):
        problems, _, _ = self.speed(call=self.SPEED_CALL.replace("fmtQuantity", "fmtTight"), key="fmt_tight")
        self.assertTrue(any("template fmt_tight renders '\\x01Amph' with its text keys and sentinels, the original "
                            "'\\x01A mph'" in p for p in problems), problems)

    def test_text_piece_left_out_fails(self):
        problems, _, _ = self.speed(call="T.fmtQuantity(Fmt.decimal(Locale.US, kmh / KM_PER_MILE, 1), \"\")",
                                    split=self.SPEED[:2])
        self.assertTrue(any('its split reassembles to ["%.1f "]' in p for p in problems), problems)

    def test_another_value_for_the_specifier_fails(self):
        split = [spec("%.1f", 1, "kmh", "Fmt.decimal(Locale.US, kmh, 1)")] + self.SPEED[1:]
        problems, _, _ = self.speed(call="T.fmtQuantity(Fmt.decimal(Locale.US, kmh, 1), T.unitsMph())", split=split)
        self.assertTrue(any("the specifier %.1f formats kmh / KM_PER_MILE at the merge base, not kmh" in p
                            for p in problems), problems)

    def test_another_locale_fails(self):
        call = self.SPEED_CALL.replace("Locale.US", "Locale.ROOT")
        split = [dict(self.SPEED[0], **{"as": "Fmt.decimal(Locale.ROOT, kmh / KM_PER_MILE, 1)"})] + self.SPEED[1:]
        problems, _, _ = self.speed(call=call, split=split)
        self.assertTrue(any("prints as Fmt.decimal(Locale.US, kmh / KM_PER_MILE, 1)" in p for p in problems), problems)

    def test_integer_needs_a_latin_digit_locale(self):
        self.convert('String.format("%d mph", n)', "T.fmtQuantity(Fmt.integer(n), T.unitsMph())")
        problems, _, _ = self.check([self.row(8, [spec("%d", 1, "n", "Fmt.integer(n)"), " ", unit("mph", "units_mph", 2)])])
        self.assertTrue(any("%d prints the default locale's digits" in p for p in problems), problems)

    def test_unknown_specifier_pairs_fail(self):
        self.repo.write(UNITS, self.text(UNITS).replace('"%.1f mph"', '"%5.1f mph"'))
        self.base = self.repo.commit("padded")
        self.convert('String.format(Locale.US, "%5.1f mph", kmh / KM_PER_MILE)',
                     "T.fmtQuantity(Fmt.decimal(Locale.US, kmh / KM_PER_MILE, 1), T.unitsMph())")
        split = [spec("%5.1f", 1, "kmh / KM_PER_MILE", "Fmt.decimal(Locale.US, kmh / KM_PER_MILE, 1)")] + self.SPEED[1:]
        problems, _, _ = self.check([self.row(4, split)])
        self.assertTrue(any("G1 does not know which Fmt call prints like %5.1f" in p for p in problems), problems)

    def test_each_fmt_method_needs_a_compiled_g10_test(self):
        """Fmt.decimal must be called by a test tests/run.sh compiles; a call in a file no .sources list
        names does not count."""
        self.repo.write(self.FMT_TEST, java_class("FmtLegacyTest", "    static void run() { Fmt.integer(1); }"))
        self.repo.write("tests/l10n/Notes.java", java_class("Notes", "    static void run() { Fmt.decimal(Locale.US, 1, 1); "
                                                            "DisplayUnits.speed(1); }"))
        problems, warnings, _ = self.speed()
        self.assertTrue(any("passes Fmt.decimal, and no test that tests/run.sh compiles under tests/l10n/ calls it" in p
                            for p in problems), problems)
        self.assertFalse(any("Notes.java" in w for w in warnings), warnings)

    def test_a_method_no_test_calls_is_listed_not_failed(self):
        """A private or instance method (RidesScreen.speed) cannot be called from a plain JVM test; Fmt's
        parity is G10's, so the site is named for the reviewer instead."""
        self.repo.write(self.FMT_TEST, java_class("FmtLegacyTest", "    static void run() { Fmt.decimal(Locale.US, 1, 1); }"))
        problems, warnings, _ = self.speed()
        self.assertEqual(problems, [])
        self.assertTrue(any("format row at %s:4" % UNITS in w and "no compiled test under tests/l10n/ calls "
                            "DisplayUnits.speed(...), so check by hand that its values are ones G10 covers" in w
                            for w in warnings), warnings)

    def test_specifiers_must_be_split_out_and_only_from_string_format(self):
        problems, _, _ = self.speed(split=["%.1f ", unit("mph", "units_mph", 1)])
        self.assertTrue(any("is String.format's format string; split its specifiers out" in p for p in problems), problems)
        self.repo.write(UNITS, self.FILES[UNITS])
        self.convert('n + " km/h"', '"%.1f" + n + " km/h"')
        self.base = self.repo.commit("odd")
        self.convert('"%.1f" + n + " km/h"', "T.fmtQuantity(Fmt.integer(n), T.unitsKmh())")
        problems, _, _ = self.check([self.row(5, [spec("%.1f", 1, "n", "Fmt.decimal(Locale.US, n, 1)"),
                                                  {"hole": "n", "arg": 2, "type": "s"}, " km/h"])], ledger="b3")
        self.assertTrue(any("spec items split a String.format literal, which has no holes" in p for p in problems),
                        problems)

    EITHER = [spec("%.2f", 1, "x", "Fmt.decimal(Locale.US, x, 2)"), " ",
              spec("%s", 2, 'u.equals("km/h") ? "mph" : "mi"', 'u.equals("km/h") ? T.unitsMph() : T.unitsMi()')]
    EITHER_ROWS = [UNITS + ':10\t["mph"]\tunits_mph\texact\tmanual: ternary unit argument of String.format',
                   UNITS + ':10\t["mi"]\tunits_mi\texact\tmanual: ternary unit argument of String.format']

    def either(self, unit_arg='u.equals("km/h") ? T.unitsMph() : T.unitsMi()', split=None, rows=None):
        old = 'String.format(Locale.US, "%.2f %s", x,\n        u.equals("km/h") ? "mph" : "mi")'
        self.assertIn(old, self.text(UNITS))
        self.convert(old, "T.fmtQuantity(Fmt.decimal(Locale.US, x, 2),\n        %s)" % unit_arg)
        if split is None:
            split = self.EITHER[:2] + [dict(self.EITHER[2], **{"as": unit_arg})]
        return self.check([self.row(9, split)] + (self.EITHER_ROWS if rows is None else rows))

    def test_a_plain_s_specifier_passes_its_argument_through(self):
        """DisplayUnits.metricText: "%.2f %s" with a ternary unit; each unit literal gets an exact row."""
        problems, warnings, _ = self.either()
        self.assertEqual(problems, [])
        self.assertTrue(any("format row at %s:9" % UNITS in w and
                            '%s -> u.equals("km/h") ? T.unitsMph() : T.unitsMi()' in w for w in warnings), warnings)

    def test_a_plain_s_specifier_with_swapped_units_fails(self):
        problems, _, _ = self.either('u.equals("km/h") ? T.unitsMi() : T.unitsMph()')
        self.assertTrue(any("already used by another row, or comes before the call of an earlier row" in p
                            for p in problems), problems)

    def test_a_plain_s_specifier_with_a_wrong_unit_key_fails(self):
        rows = [self.EITHER_ROWS[0].replace("units_mph", "units_kmh"), self.EITHER_ROWS[1]]
        problems, _, _ = self.either('u.equals("km/h") ? T.unitsKmh() : T.unitsMi()', rows=rows)
        self.assertTrue(any("units_kmh" in p and "km/h" in p and "mph" in p for p in problems), problems)

    def test_a_plain_s_specifier_with_other_code_fails(self):
        for unit_arg in ('u.equals("km/h") ? T.unitsMph().toUpperCase() : T.unitsMi()',
                         'u.equals("mph") ? T.unitsMph() : T.unitsMi()', 'T.unitsMph()',
                         'u.equals("km/h") ? T.unitsMph() : T.render(T.UNITS_MI)'):
            problems, _, _ = self.either(unit_arg)
            self.assertTrue(any("prints u.equals(\"km/h\") ? \"mph\" : \"mi\" as it is" in p for p in problems),
                            (unit_arg, problems))
            self.repo.write(UNITS, self.FILES[UNITS])

    def test_a_plain_s_specifier_without_rows_for_its_literals_fails(self):
        problems, _, _ = self.either(rows=[])
        self.assertTrue(any('literal "mph" left with no ledger row' in p for p in problems), problems)
        self.assertTrue(any('literal "mi" left with no ledger row' in p for p in problems), problems)

    def test_only_a_plain_s_passes_through(self):
        for text in ("%S", "%-5s", "%.3s"):
            self.assertIsNotNone(ledger_check.spec_problem(spec(text, 1, "u", "u"), ("Locale", ".", "US")), text)
        self.assertIsNone(ledger_check.spec_problem(spec("%2$s", 1, "u", "u"), ("Locale", ".", "US")))

    def test_invalid_format_rows(self):
        cases = [
            ("[]", "its split"),
            ('["%.1f mph"]', "splits a specifier out to Fmt or text out to a key"),
            ('[{"spec": "%%", "arg": 1, "value": "x", "as": "Fmt.x(x)"}]', "takes an argument"),
            ('[{"spec": "%.1f", "arg": 2, "value": "x", "as": "Fmt.decimal(Locale.US, x, 1)"}]', "1 to 1 with no gaps"),
            ('[{"spec": "%.1f", "arg": 1, "value": "x", "as": "Fmt.decimal(Locale.US, x, 1)"}, " %s ", '
             '{"text": "mph", "key": "units_mph", "arg": 2}]', "holds a format specifier"),
            ('[{"text": "mph", "key": "units_mph", "arg": 1}, {"text": "mph", "key": "units_mph", "arg": 1}]',
             "claim arg 1"),
            ('[{"text": "mph", "key": "Units", "arg": 1}]', "needs a resource key"),
            ('[{"hole": "x", "arg": 1, "type": "s", "as": "x.trim()"}, {"text": "mph", "key": "units_mph", "arg": 2}]',
             "a format row's \\\"as\\\" must be Fmt"),
            ('[{"hole": "x", "arg": 1, "type": "d"}, {"text": "mph", "key": "units_mph", "arg": 2}]', "type s"),
        ]
        for pieces, message in cases:
            with self.assertRaises(ledger_check.LedgerError, msg=pieces) as caught:
                ledger_check.parse_ledger(HEADER + UNITS + ":4\t" + pieces + "\tfmt_quantity\tformat\t\n")
            self.assertIn(message.replace('\\"', '"'), str(caught.exception), pieces)
        with self.assertRaises(ledger_check.LedgerError) as caught:
            ledger_check.parse_ledger(HEADER + self.row(4, self.SPEED, note="manual: shape") + "\n")
        self.assertIn("takes no \"manual:\" note", str(caught.exception))

    def test_rows_stay_checked_against_the_catalog(self):
        self.speed()
        self.base = self.repo.git("rev-parse", "HEAD")
        self.repo.write("res/values/strings_fmt.xml", FMT_STRINGS.replace(">mph<", ">mi/h<"))
        problems, _, _ = self.check()
        self.assertTrue(any("text key units_mph is 'mi/h' but that part of the literal was 'mph'" in p for p in problems),
                        problems)

    def test_format_only_literals_are_listed(self):
        self.assertTrue(all(ledger_check.is_format_only(v) for v in ("%.1f", "%d", "%,.0f", "%.1f–%.1f", "%1$.2f%%", "%x")))
        # Conversions that can print words are not format-only: %s, %S, %b, %c and the %t dates.
        self.assertFalse(any(ledger_check.is_format_only(v) for v in (
            "%.1f mph", "%%", "50% off", "km/h", "%.", "%d%", "%2$s/%1$s", "%s", "%S", "%b", "%c", "%tB", "%TA",
            "%.1f %s")))
        self.convert("    static String plain(int n)", '    static final String[] PATTERNS = {"%.1f", "%,.0f"};\n'
                     "    static String plain(int n)")
        problems, warnings, summary = self.check([])
        self.assertEqual(problems, [])
        self.assertTrue(any('new format-only literal "%,.0f" in `"%.1f" , "%,.0f"`'
                            in w for w in warnings), warnings)
        self.assertIn("2 new format-only literal(s), listed", summary)


class SharedConstantTest(RepoCase):
    """B1: repeated data unit literals move into one constant (an enum constant's argument or a static
    final field); each site must name the constant its own literal moved to."""
    SCALE = "src/com/jjricks/volt/ChartScale.java"
    CODE = "src/com/jjricks/volt/UnitCode.java"
    FILES = {SCALE: java_class("ChartScale", '''    static double span(String unit) { return unit.equals("V") ? .1 : unit.equals("W") ? 2 : 1; }
    static int unused() { return 0; }
    static double floor(String unit) { return unit.equals("V") ? .1 : 1; }''')}
    ROWS = [SCALE + ':4\t["V"]\t-\tstays-english\tdata unit', SCALE + ':4\t["W"]\t-\tstays-english\tdata unit',
            SCALE + ':6\t["V"]\t-\tstays-english\tdata unit']
    ENUM = java_class("UnitCode", '''    VOLT(1, "V"),
    WATT(2, "W");
    final String code;
    UnitCode(int id, String code) { this.code = code; }''').replace("final class UnitCode", "enum UnitCode")

    def convert(self, span="UnitCode.VOLT.matches(unit) ? .1 : UnitCode.WATT.matches(unit) ? 2 : 1",
                floor="UnitCode.VOLT.matches(unit) ? .1 : 1", code=None):
        text = self.text(self.SCALE).replace('unit.equals("V") ? .1 : unit.equals("W") ? 2 : 1', span)
        self.repo.write(self.SCALE, text.replace('unit.equals("V") ? .1 : 1', floor))
        self.repo.write(self.CODE, code or self.ENUM)

    def test_enum_constant_shared_by_two_sites_passes_and_lists_each(self):
        self.convert()
        problems, warnings, _ = self.check(self.ROWS)
        self.assertEqual(problems, [])
        self.assertTrue(any('2 occurrence(s) of "V" moved into the one constant UnitCode.VOLT (`VOLT ( 1 , "V" )`): '
                            "%s:4 (l10n/ledger/b2.tsv:2), %s:6 (l10n/ledger/b2.tsv:4)" % (self.SCALE, self.SCALE) in w
                            for w in warnings), warnings)
        self.assertTrue(any("%s:4: this change adds `UnitCode . VOLT" % self.SCALE in w and "beside a stays-english" in w
                            for w in warnings), warnings)

    def test_static_final_field_shared_by_two_sites_passes(self):
        self.convert("VOLT.equals(unit) ? .1 : WATT.equals(unit) ? 2 : 1", "VOLT.equals(unit) ? .1 : 1",
                     java_class("Units", '    static final String VOLT = "V";\n    static final String WATT = "W";'))
        text = self.text(self.SCALE).replace("VOLT.equals", "Units.VOLT.equals").replace("WATT.equals", "Units.WATT.equals")
        self.repo.write(self.SCALE, text)
        self.repo.write("src/com/jjricks/volt/Units.java", self.text(self.CODE).replace("class UnitCode", "class Units"))
        self.repo.remove(self.CODE)
        problems, _, _ = self.check(self.ROWS)
        self.assertEqual(problems, [])

    def test_an_arrival_holding_another_value_fails(self):
        self.convert(code=self.ENUM.replace('VOLT(1, "V")', 'VOLT(1, "v")'))
        problems, _, _ = self.check(self.ROWS)
        self.assertEqual(len([p for p in problems if '"V" left' in p and "not added anywhere else" in p]), 2, problems)
        self.assertTrue(any('new literal "v"' in p for p in problems), problems)

    def test_a_site_naming_another_constant_fails(self):
        self.convert(floor="UnitCode.WATT.matches(unit) ? .1 : 1")
        problems, _, _ = self.check(self.ROWS)
        self.assertTrue(any('"V" left %s:6 and is not added anywhere else' % self.SCALE in p for p in problems), problems)

    def test_constants_swapped_within_a_line_fail(self):
        self.convert(span="UnitCode.WATT.matches(unit) ? .1 : UnitCode.VOLT.matches(unit) ? 2 : 1")
        problems, _, _ = self.check(self.ROWS)
        self.assertTrue(any('are, in order, "V" (UnitCode.VOLT), "W" (UnitCode.WATT), but the head lines name '
                            "UnitCode.WATT, UnitCode.VOLT" in p for p in problems), problems)

    def test_two_sites_in_one_region_need_two_references(self):
        self.repo.write(self.SCALE, self.text(self.SCALE).replace('unit.equals("W") ? 2', 'unit.equals("V") ? 2'))
        self.base = self.repo.commit("two volts")
        self.convert(span="UnitCode.VOLT.matches(unit) ? .1 : unit.isEmpty() ? 2 : 1")
        self.repo.write(self.SCALE, self.text(self.SCALE).replace('unit.equals("V") ? .1 : unit.equals("V") ? 2 : 1',
                                                                  "UnitCode.VOLT.matches(unit) ? .1 : unit.isEmpty() ? 2 : 1"))
        self.repo.write(self.CODE, self.ENUM.replace('VOLT(1, "V"),\n    WATT(2, "W");', 'VOLT(1, "V");'))
        rows = [self.ROWS[0], self.ROWS[0], self.ROWS[2]]
        problems, _, _ = self.check(rows)
        self.assertTrue(any('are, in order, "V" (UnitCode.VOLT), "V" (UnitCode.VOLT), but the head lines name '
                            "UnitCode.VOLT;" in p for p in problems), problems)

    def existing(self, edit, rows):
        """A later batch: UnitCode is already in src at the merge base."""
        self.repo.write(self.CODE, self.ENUM)
        self.base = self.repo.commit("UnitCode exists")
        self.repo.write(self.SCALE, self.text(self.SCALE).replace('unit.equals("V") ? .1 : 1', edit))
        return self.check(rows)

    def test_a_later_batch_moves_a_literal_into_an_existing_constant(self):
        problems, warnings, _ = self.existing("UnitCode.VOLT.matches(unit) ? .1 : 1", [self.ROWS[2]])
        self.assertEqual(problems, [])
        self.assertTrue(any('1 occurrence(s) of "V" moved into the existing constant UnitCode.VOLT (`VOLT ( 1 , "V" )`): '
                            "%s:6 (l10n/ledger/b2.tsv:2)" % self.SCALE in w for w in warnings), warnings)

    def test_an_existing_constant_must_hold_the_value_and_be_named(self):
        for edit in ("UnitCode.WATT.matches(unit) ? .1 : 1", "VOLT.matches(unit) ? .1 : 1", "unit.isEmpty() ? .1 : 1"):
            problems, _, _ = self.existing(edit, [self.ROWS[2]])
            self.assertTrue(any('"V" left %s:6 and is not added anywhere else' % self.SCALE in p for p in problems),
                            (edit, problems))
            self.repo.git("reset", "-q", "--hard", self.base + "~1")

    def test_a_local_variable_or_plain_field_is_not_an_existing_constant(self):
        # review repro: PhoneRideStore's local `source = "GPS";` let a dropped "GPS" check pass
        for code in ('static void unused(String unit) { unit = "V"; }',
                     'static void unused() { String unit = "V"; }',
                     'static void unused() { final String unit = "V"; }',
                     'static String unit = "V";'):
            self.repo.write(self.SCALE, self.text(self.SCALE).replace("static int unused() { return 0; }", code))
            self.base = self.repo.commit("a V that is not a constant")
            self.repo.write(self.SCALE, self.text(self.SCALE).replace('unit.equals("V") ? .1 : 1',
                                                                      "unit.isEmpty() ? .1 : 1"))
            problems, warnings, _ = self.check([self.ROWS[2]])
            self.assertTrue(any('"V" left %s:6 and is not added anywhere else' % self.SCALE in p for p in problems),
                            (code, problems))
            self.assertFalse(any("existing constant" in w for w in warnings), (code, warnings))
            self.repo.git("reset", "-q", "--hard", self.base + "~1")

    def test_constant_name_forms(self):
        def name(source, literal):
            tokens = expressions.l10nlib.lex_java(source)
            return ledger_check.constant_name(tokens, next(i for i, t in enumerate(tokens) if t.value == literal))
        self.assertEqual(name('enum U { A(1, "a"), B(2, "b"); }', "b"), "B")
        self.assertEqual(name('enum U { A("a") }', "a"), "A")
        self.assertEqual(name('class C { static final String X = "x"; }', "x"), "X")
        self.assertIsNone(name('class C { static final String X = "x" + y; }', "x"))
        self.assertIsNone(name('class C { void f() { g(1, "x"); } }', "x"))
        self.assertIsNone(name('enum U { A; void f() { B("x"); } }', "x"))
        self.assertIsNone(name('enum U { A(f("x")) }', "x"))
        # only a final field is a constant (review: PhoneRideStore's local `source = "GPS";`)
        self.assertEqual(name('class C { private static final String A = "a", X = "x"; }', "x"), "X")
        self.assertEqual(name('class C { @Deprecated(since = "1") final Map<K, V> X = "x"; }', "x"), "X")
        self.assertEqual(name('interface I { String X = "x"; }', "x"), "X")
        self.assertEqual(name('enum U { A; static final String X = "x"; }', "x"), "X")
        self.assertIsNone(name('class C { void f() { source = "GPS"; } }', "GPS"))
        self.assertIsNone(name('class C { void f() { String s = "x"; } }', "x"))
        self.assertIsNone(name('class C { void f() { final String s = "x"; } }', "x"))
        self.assertIsNone(name('class C { static { s = "x"; } }', "x"))
        self.assertIsNone(name('class C { String title = "x"; }', "x"))
        self.assertIsNone(name('class C { C() { this.title = "x"; } }', "x"))
        self.assertIsNone(name('class C { void f() { g(t = "x"); } }', "x"))
        self.assertIsNone(name('class C { Runnable r = () -> { final String s = "x"; }; }', "x"))
        self.assertIsNone(name('class C { Object o = new Object() { final String s = "x"; }; }', "x"))
        self.assertIsNone(name('class C { void f() { if (a == C.class) { final String s = "x"; } } }', "x"))


class InsertedMemberTest(RepoCase):
    """B1: a converted method with a new method added right after it (the diff may show the insertion
    as "} void f() { ... ;")."""
    SCALE = "src/com/jjricks/volt/RouteSpeedScale.java"
    FILES = {SCALE: java_class("RouteSpeedScale", '''    static String label(String a, String b) {
        return a + " / " + b;
    }

    static int blend(int a) {
        return a;
    }''')}

    def test_a_new_member_beside_converted_text_passes_and_is_listed(self):
        self.repo.write("res/values/strings_fmt.xml", FMT_STRINGS.replace("</resources>",
                                                                         '<string name="fmt_dual">%1$s / %2$s</string></resources>'))
        self.repo.write(self.SCALE, self.text(self.SCALE).replace('''        return a + " / " + b;
    }
''', '''        return T.fmtDual(a, b);
    }

    static Formatted labelQuantity(String a, String b) {
        return Fmt.dual(a, b);
    }
'''))
        problems, warnings, _ = self.check([self.SCALE + ':5\t[{"hole": "a", "arg": 1, "type": "s"}, " / ", '
                                            '{"hole": "b", "arg": 2, "type": "s"}]\tfmt_dual\ttemplate\t'])
        self.assertEqual(problems, [])
        self.assertTrue(any("adds the whole member(s) `static Formatted labelQuantity ( String a , String b ) { return "
                            "Fmt . dual ( a , b ) ; }`" in w for w in warnings), warnings)

    def test_a_new_statement_beside_text_still_fails(self):
        self.repo.write(self.SCALE, self.text(self.SCALE).replace('        return a + " / " + b;',
                                                                  '        log(a); return a + " / " + b;'))
        problems, _, _ = self.check([])
        self.assertTrue(any("adds `log ( a ) ;`" in p and "around text" in p for p in problems), problems)


class DeclarationTypeTest(RepoCase):
    """B2: a String field whose default becomes a deferred M call must be a CharSequence. Exactly that
    type change, on the declaration whose initializer the call is, is allowed and listed."""
    FILES = {UI: java_class("Ui", '''    static final class Ring {
        private String subtitle = "Not reported";
        private String unit = "", empty = "No measured data yet", axis = "";
        void set(String value) { subtitle = value; show(subtitle); }
        String shown(boolean on) { return on ? "" : subtitle; }
        String other(boolean on) { return on ? "" : unit; }
    }
    static String label() { String shown = "Not reported"; return shown; }''')}
    STRINGS = ('<resources><string name="common_not_reported">Not reported</string>'
               '<string name="ui_chart_empty">No measured data yet</string></resources>\n')
    SUBTITLE = UI + ':5\t["Not reported"]\tcommon_not_reported\texact\t'
    EMPTY = UI + ':6\t["No measured data yet"]\tui_chart_empty\texact\t'

    def setUp(self):
        super().setUp()
        self.repo.write("res/values/strings_ui.xml", self.STRINGS)

    def edit(self, old, new):
        self.repo.write(UI, self.text(UI).replace(old, new))

    def test_string_to_char_sequence_for_an_m_call_passes_and_is_listed(self):
        self.edit('private String subtitle = "Not reported";', "private CharSequence subtitle = M.commonNotReported();")
        problems, warnings, _ = self.check([self.SUBTITLE])
        self.assertEqual(problems, [])
        shown = [w for w in warnings if "changes from String to CharSequence" in w]
        self.assertEqual(len(shown), 1, warnings)
        self.assertIn("%s:5: the declaration `private CharSequence subtitle = M . commonNotReported ( )`" % UI, shown[0])
        self.assertIn("every use of subtitle behaves the same with a CharSequence", shown[0])
        # Each use is shown as its code, so the reviewer sees the overload or equals without the file.
        self.assertIn("only this file can name it (private or local), at: line 7 `subtitle = value`; line 7 "
                      "`show ( subtitle )`; line 8 `return on ? \"\" : subtitle`", shown[0])

    def test_a_field_other_files_can_name_fails(self):
        # Review round 1: r.subtitle.equals("Not reported") in another file compiles on and turns false
        # (Msg.equals(String)), and G1 lists uses from the declaring file only.
        screen = "src/com/jjricks/volt/Screen.java"
        self.repo.write(screen, java_class("Screen", '    static boolean missing(Ui.Ring r) { return '
                                           'r.subtitle.equals("Not reported"); }'))
        for modifiers in ("", "protected ", "public ", "static "):
            with self.subTest(modifiers=modifiers):
                self.repo.write(UI, self.FILES[UI].replace('private String subtitle', '%sString subtitle' % modifiers))
                self.repo.write("l10n/ledger/b2.tsv", HEADER)  # the last subtest's row is not in this base
                self.base = self.repo.commit("a %sfield used in another file" % modifiers)
                self.edit('%sString subtitle = "Not reported";' % modifiers,
                          '%sCharSequence subtitle = M.commonNotReported();' % modifiers)
                problems, warnings, _ = self.check([self.SUBTITLE])
                self.assertTrue(any("%s:5: `%sCharSequence subtitle = M . commonNotReported ( )` changes from String to "
                                    "CharSequence on a field that is not private" % (UI, modifiers) in p
                                    for p in problems), problems)
                self.assertFalse(any("only this file can name it" in w
                                     for w in warnings), warnings)

    def test_every_use_is_listed_even_several_in_one_long_statement(self):
        # Gates round 2 review: one window per statement hid a later use in the same statement, such as
        # an equals that turns false for a Msg.
        self.edit("void set(String value) { subtitle = value; show(subtitle); }",
                  "void set(String value) { show(subtitle, value, value, value, value, value, value, value, value, "
                  "value, value, value, subtitle.equals(\"Not reported\") ? 1 : 0); }")
        self.repo.write(UI, self.text(UI))
        self.base = self.repo.commit("a long statement")
        self.edit('private String subtitle = "Not reported";', "private CharSequence subtitle = M.commonNotReported();")
        problems, warnings, _ = self.check([self.SUBTITLE])
        self.assertEqual(problems, [])
        shown = [w for w in warnings if "changes from String to CharSequence" in w]
        self.assertEqual(len(shown), 1, warnings)
        self.assertIn("line 7 `show ( subtitle , value", shown[0])
        self.assertIn("subtitle . equals ( \"Not reported\" ) ? 1 : 0", shown[0])
        self.assertEqual(shown[0].count("line 7 `"), 2, shown[0])
        # Many uses are all listed, never cut to a count.
        self.repo.write(UI, self.FILES[UI].replace("show(subtitle); }", "show(subtitle); }\n" + "".join(
            "        void use%d() { show(subtitle); }\n" % i for i in range(30))))
        self.repo.write("l10n/ledger/b2.tsv", HEADER)
        self.base = self.repo.commit("thirty uses")
        self.edit('private String subtitle = "Not reported";', "private CharSequence subtitle = M.commonNotReported();")
        problems, warnings, _ = self.check([self.SUBTITLE])
        self.assertEqual(problems, [])
        shown = [w for w in warnings if "changes from String to CharSequence" in w]
        self.assertEqual(shown[0].count("`show ( subtitle )`"), 31, shown[0])
        self.assertNotIn("more)", shown[0])

    def test_a_field_of_a_private_or_local_class_passes(self):
        # A package-private field of a private nested class, or a field of a class declared in a method,
        # can be named only in its own file, like a private field.
        for old, new in (("    static final class Ring {", "    private static final class Ring {"),
                         ("    static final class Ring {", "    private static final class Outer {\n    static final class Ring {"),
                         ("    static final class Ring {", "    static void local() {\n    final class Ring {")):
            with self.subTest(new=new):
                text = self.FILES[UI].replace(old, new).replace('private String subtitle', 'String subtitle')
                if new.count("{") == 2:
                    text = text.replace("    static String label()", "    }\n    static String label()")
                self.repo.write(UI, text)
                self.repo.write("l10n/ledger/b2.tsv", HEADER)
                self.base = self.repo.commit("a nested field")
                line = 1 + text.splitlines().index('        String subtitle = "Not reported";')
                self.edit('String subtitle = "Not reported";', "CharSequence subtitle = M.commonNotReported();")
                problems, warnings, _ = self.check([UI + ':%d\t["Not reported"]\tcommon_not_reported\texact\t' % line])
                self.assertEqual(problems, [])
                self.assertTrue(any("only this file can name it" in w for w in warnings), warnings)

    def test_a_local_in_a_method_with_a_record_parameter_passes(self):
        # Gates round 2 review: record is a contextual keyword, so label(Object record) { is a method.
        self.repo.write(UI, self.FILES[UI].replace("static String label() { String shown = \"Not reported\"; return shown; }",
                                                   "static String label(Object record) { String shown = \"Not reported\"; "
                                                   "return shown.toString(); }"))
        self.base = self.repo.commit("a record parameter")
        self.edit('String shown = "Not reported";', "CharSequence shown = M.commonNotReported();")
        problems, warnings, _ = self.check([UI + ':11\t["Not reported"]\tcommon_not_reported\texact\t'])
        self.assertEqual(problems, [])
        self.assertTrue(any("`CharSequence shown = M . commonNotReported ( )` changes" in w for w in warnings), warnings)

    def test_a_non_private_split_or_interface_field_fails(self):
        self.repo.write(UI, self.FILES[UI].replace('private String unit = "", empty', 'String unit = "", empty'))
        self.base = self.repo.commit("package-private")
        self.edit('String unit = "", empty = "No measured data yet", axis = "";',
                  'String unit = "", axis = "";\n        CharSequence empty = M.uiChartEmpty();')
        problems, _, _ = self.check([self.EMPTY])
        self.assertTrue(any("`CharSequence empty = M . uiChartEmpty ( )` changes from String to CharSequence on a field "
                            "that is not private" in p for p in problems), problems)
        self.repo.write(UI, java_class("Ui", '    interface Labels {\n        String NONE = "Not reported";\n    }'))
        self.base = self.repo.commit("an interface field")
        self.edit('String NONE = "Not reported";', "CharSequence NONE = M.commonNotReported();")
        problems, _, _ = self.check([self.SUBTITLE])
        self.assertTrue(any("on a field that is not private" in p for p in problems), problems)

    def test_body_kind(self):
        def kind(source, literal):
            tokens = expressions.l10nlib.lex_java(source)
            return ledger_check.body_kind(tokens, next(i for i, t in enumerate(tokens) if t.value == literal))
        self.assertEqual(kind('class C { String a = "x"; }', "x"), "class")
        self.assertEqual(kind('class C { void f() {} String a = "x"; }', "x"), "class")
        self.assertEqual(kind('interface I { String a = "x"; }', "x"), "interface")
        self.assertIsNone(kind('class C { void f() { String a = "x"; } }', "x"))
        self.assertIsNone(kind('class C { void f() { if (o == C.class) { String a = "x"; } } }', "x"))
        self.assertIsNone(kind('class C { Runnable r = new Runnable() { String a = "x"; }; }', "x"))
        self.assertIsNone(kind('class C { static { String a = "x"; } }', "x"))
        self.assertIsNone(kind('class C { void save(Ride record) { String a = "x"; } }', "x"))
        self.assertIsNone(kind('class C { void f() { for (Ride record : rides) { String a = "x"; } } }', "x"))
        self.assertIsNone(kind('class C { Ride record(int id) { String a = "x"; return null; } }', "x"))
        self.assertEqual(kind('class C { record Point(int y) { static String a = "x"; } }', "x"), "record")
        self.assertEqual(kind('class C { @Tag(record = 1) static class D { String a = "x"; } }', "x"), "class")

    def test_one_declarator_of_several_and_a_local_pass(self):
        self.edit('private String unit = "", empty = "No measured data yet", axis = "";',
                  'private CharSequence unit = "", empty = M.uiChartEmpty(), axis = "";')
        self.edit('String shown = "Not reported";', "CharSequence shown = T.commonNotReported();")
        problems, warnings, _ = self.check([self.EMPTY, UI + ':11\t["Not reported"]\tcommon_not_reported\texact\t'])
        self.assertEqual(problems, [])
        self.assertTrue(any("`private CharSequence unit = \"\" , empty = M . uiChartEmpty ( ) , axis = \"\"` changes "
                            "from String to CharSequence" in w for w in warnings), warnings)
        self.assertTrue(any("`CharSequence shown = T . commonNotReported ( )` changes" in w for w in warnings), warnings)

    def test_to_string_at_a_use_of_the_retyped_variable_passes_and_is_listed(self):
        self.edit('private String subtitle = "Not reported";', "private CharSequence subtitle = M.commonNotReported();")
        self.edit('return on ? "" : subtitle;', 'return on ? "" : subtitle.toString();')
        problems, warnings, _ = self.check([self.SUBTITLE])
        self.assertEqual(problems, [])
        self.assertTrue(any("%s:8: `subtitle` becomes `subtitle . toString ( )` in `return on ? \"\" : subtitle . toString "
                            "( )`" % UI in w for w in warnings), warnings)
        # A whole multi-declarator retype: each of its names may take .toString().
        self.edit('private String unit = "", empty = "No measured data yet", axis = "";',
                  'private CharSequence unit = "", empty = M.uiChartEmpty(), axis = "";')
        self.edit('return on ? "" : unit;', 'return on ? "" : unit.toString();')
        problems, _, _ = self.check([self.SUBTITLE, self.EMPTY])
        self.assertEqual(problems, [])

    def test_other_changes_at_a_use_fail(self):
        # Only name.toString() on the retyped variable; unit is still a String declaration here.
        for old, new in (('"" : subtitle;', '"" : subtitle.toString().trim();'), ('"" : subtitle;', '"" : subtitle.trim();'),
                         ('"" : subtitle;', '"" : String.valueOf(subtitle);'), ('"" : unit;', '"" : unit.toString();')):
            with self.subTest(new=new):
                self.repo.write(UI, self.FILES[UI])
                self.edit('private String subtitle = "Not reported";', "private CharSequence subtitle = M.commonNotReported();")
                self.edit(old, new)
                problems, _, _ = self.check([self.SUBTITLE])
                self.assertTrue(any("around text" in p for p in problems), problems)

    def test_one_declarator_moved_into_its_own_declaration_passes(self):
        for code in ('private String unit = "", axis = "";\n        private CharSequence empty = M.uiChartEmpty();',
                     'private CharSequence empty = M.uiChartEmpty();\n        private String unit = "", axis = "";'):
            with self.subTest(code=code):
                self.repo.write(UI, self.FILES[UI])
                self.edit('private String unit = "", empty = "No measured data yet", axis = "";', code)
                problems, warnings, _ = self.check([self.EMPTY])
                self.assertEqual(problems, [])
                self.assertTrue(any("`private CharSequence empty = M . uiChartEmpty ( )` (moved out of `private String unit "
                                    "= \"\" , empty = \"No measured data yet\" , axis = \"\" ;`, the rest kept as it was) "
                                    "changes from String to CharSequence" in w for w in warnings), warnings)

    def test_a_careless_split_fails(self):
        cases = [
            # a declarator dropped, reordered or changed with the move
            'private String unit = "";\n        private CharSequence empty = M.uiChartEmpty();',
            'private String axis = "", unit = "";\n        private CharSequence empty = M.uiChartEmpty();',
            'private String unit = null, axis = "";\n        private CharSequence empty = M.uiChartEmpty();',
            # other modifiers, or the new declaration not beside the rest
            'private String unit = "", axis = "";\n        private final CharSequence empty = M.uiChartEmpty();',
            'private String unit = "", axis = "";\n        private int gap;\n        private CharSequence empty = M.uiChartEmpty();',
            # two declarators in the new declaration
            'private String unit = "";\n        private CharSequence empty = M.uiChartEmpty(), axis = "";',
        ]
        for code in cases:
            with self.subTest(code=code):
                self.repo.write(UI, self.FILES[UI])
                self.edit('private String unit = "", empty = "No measured data yet", axis = "";', code)
                problems, _, _ = self.check([self.EMPTY])
                self.assertTrue(any("around text" in p for p in problems), problems)

    def test_other_type_changes_fail(self):
        cases = [
            # another type than CharSequence
            ("private Object subtitle = M.commonNotReported();", "adds `Object` and drops `String`"),
            ("private Msg subtitle = M.commonNotReported();", "adds `Msg` and drops `String`"),
            ("private java.lang.CharSequence subtitle = M.commonNotReported();", "adds `java . lang . CharSequence`"),
            # a modifier changed with it
            ("private final CharSequence subtitle = M.commonNotReported();", "adds `final CharSequence` and drops `String`"),
            # the call is not the whole initializer
            ('private CharSequence subtitle = M.commonNotReported() + "";', "around text"),
            # another declaration retyped beside the converted one
        ]
        for code, message in cases:
            with self.subTest(code=code):
                self.repo.write(UI, self.FILES[UI])
                self.edit('private String subtitle = "Not reported";', code)
                problems, _, _ = self.check([self.SUBTITLE])
                self.assertTrue(any(message in p and "around text" in p for p in problems), problems)

    def test_retyping_a_neighbour_or_a_kept_literal_fails(self):
        self.edit('private String subtitle = "Not reported";', "private CharSequence subtitle = M.commonNotReported();")
        self.edit('private String unit = "", empty', 'private CharSequence unit = "", empty')
        problems, _, _ = self.check([self.SUBTITLE])
        self.assertTrue(any("%s:6: this change adds `CharSequence` and drops `String`" % UI in p and "around text" in p
                            for p in problems), problems)

    def test_char_sequence_to_string_fails(self):
        self.edit('private String subtitle = "Not reported";', 'private CharSequence subtitle = "Not reported";')
        self.base = self.repo.commit("a CharSequence field")
        self.edit('private CharSequence subtitle = "Not reported";', "private String subtitle = T.commonNotReported();")
        problems, _, _ = self.check([self.SUBTITLE])
        self.assertTrue(any("adds `String` and drops `CharSequence`" in p for p in problems), problems)

    def test_declaration_type_forms(self):
        def found(source, literal):
            tokens = expressions.l10nlib.lex_java(source)
            k = next(i for i, t in enumerate(tokens) if t.value == literal)
            result = ledger_check.declaration_type(tokens, k, k)
            return None if result is None else (tokens[result[0]].value, result[1])
        self.assertEqual(found('class C { private static String a = "x"; }', "x"), ("String", "a"))
        self.assertEqual(found('class C { String a = "", b = f(1, 2), c = "x", d; }', "x"), ("String", "c"))
        self.assertEqual(found('class C { void f() { final String a = "x"; } }', "x"), ("String", "a"))
        self.assertIsNone(found('class C { void f() { a = "x"; } }', "x"))
        self.assertIsNone(found('class C { void f() { this.a = "x"; } }', "x"))
        self.assertIsNone(found('class C { String a = "x" + b; }', "x"))
        self.assertIsNone(found('class C { void f() { g(a = "x"); } }', "x"))
        self.assertIsNone(found('class C { List<String> a = "x"; }', "x"))
        self.assertIsNone(found('class C { @Keep String a = "x"; }', "x"))


class LocaleWrapTest(RepoCase):
    """B2: a display call site's legacy locale wrapped for Fmt, Locale.US becoming Fmt.locale(Locale.US),
    next to a format-only or stays-english literal. Exactly that wrap is allowed, listed, and held to G10."""
    FILES = {UI: java_class("Ui", '''    static String axis(double v) { return String.format(Locale.US, "%.1f", v); }
    static String label(String s) { return s == null ? "" : s.toUpperCase(Locale.ROOT); }
    static String when(long t) { return new SimpleDateFormat("MMM d", Locale.getDefault()).format(t); }
    static String full(double v) { return String.format(java.util.Locale.US, "%.2f", v); }
    static String kept(double v) { return String.format(Fmt.locale(Locale.US), "%.0f", v); }''')}
    FMT_TEST = "tests/l10n/FmtLegacyTest.java"

    def setUp(self):
        super().setUp()
        self.repo.write(self.FMT_TEST, java_class("FmtLegacyTest", "    static void run() { Fmt.locale(Locale.US); "
                                                  "Ui.axis(1); }"))
        self.repo.write("tests/l10n/b1.sources", self.FMT_TEST + "\n")

    def edit(self, old, new):
        self.repo.write(UI, self.text(UI).replace(old, new))

    def wrap_all(self):
        self.edit("String.format(Locale.US, \"%.1f\"", "String.format(Fmt.locale(Locale.US), \"%.1f\"")
        self.edit("toUpperCase(Locale.ROOT)", "toUpperCase(Fmt.locale(Locale.ROOT))")
        self.edit("Locale.getDefault()).format", "Fmt.locale(Locale.getDefault())).format")
        self.edit("String.format(java.util.Locale.US", "String.format(Fmt.locale(java.util.Locale.US)")

    def test_wrapping_legacy_locales_passes_and_is_listed(self):
        self.wrap_all()
        problems, warnings, summary = self.check([])
        self.assertEqual(problems, [])
        shown = [w for w in warnings if "Fmt.locale keeps that locale" in w]
        self.assertEqual(len(shown), 4, warnings)
        self.assertIn("%s:4: `Locale . US` becomes `Fmt . locale ( Locale . US )` in `return String . format ( Fmt . "
                      "locale ( Locale . US ) , \"%%.1f\" , v )`" % UI, shown[0])
        self.assertIn("a method named Ui.axis is called by %s" % self.FMT_TEST, shown[0])
        self.assertIn("`Locale . ROOT` becomes `Fmt . locale ( Locale . ROOT )`", shown[1])
        self.assertIn("no compiled test under tests/l10n/ calls Ui.label(...)", shown[1])
        self.assertIn("`Locale . getDefault ( )` becomes", shown[2])
        self.assertIn("`java . util . Locale . US` becomes", shown[3])
        self.assertIn("4 legacy locale(s) wrapped in Fmt.locale, listed", summary)
        self.assertFalse(any("around text" in p for p in problems))

    def test_a_wrap_needs_a_compiled_g10_test(self):
        self.repo.write("tests/l10n/b1.sources", "# nothing compiled\n")
        self.wrap_all()
        problems, _, _ = self.check([])
        self.assertEqual(len([p for p in problems if "calls Fmt.locale; G10" in p]), 1, problems)

    def test_careless_wraps_fail(self):
        cases = [
            ("String.format(Fmt.locale(Locale.ROOT), \"%.1f\"", "adds `Fmt . locale ( ROOT )` and drops `US`"),
            ("String.format(Fmt.decimalLocale(Locale.US), \"%.1f\"", "adds `Fmt . decimalLocale ( )`"),
            ("String.format(Fmt.locale(Locale.US).stripExtensions(), \"%.1f\"", "adds `. stripExtensions ( )`"),
            ("String.format(Fmt.locale(Locale.US, true), \"%.1f\"", "around text"),
            ("String.format(Fmt.displayLocale(), \"%.1f\"", "around text"),
        ]
        for code, message in cases:
            with self.subTest(code=code):
                self.repo.write(UI, self.FILES[UI])
                self.edit("String.format(Locale.US, \"%.1f\"", code)
                problems, _, _ = self.check([])
                self.assertTrue(any(message in p and "around text" in p for p in problems), problems)

    def test_a_wrap_in_an_english_only_file_fails(self):
        # Review round 1: reports, logs and the shell process stay English in every language
        # (CatalogUsageTest.ENGLISH_ONLY), so their legacy locales are never wrapped.
        report = "src/com/jjricks/volt/IssueReport.java"
        self.repo.write(report, java_class("IssueReport", '    static String code(Enum<?> e) { return "" + '
                                           'e.name().toLowerCase(Locale.ROOT); }'))
        self.repo.write(ledger_check.CATALOG_USAGE_TEST, java_class("CatalogUsageTest", '    static final List<String> '
                        'ENGLISH_ONLY = Arrays.asList(\n            "CommandLog.java", "IssueReport.java");'))
        self.base = self.repo.commit("an English-only file")
        self.repo.write(report, self.text(report).replace("toLowerCase(Locale.ROOT)", "toLowerCase(Fmt.locale(Locale.ROOT))"))
        problems, _, _ = self.check([])
        self.assertTrue(any("%s:4: `Locale . ROOT` is wrapped into Fmt.locale(...) in an English-only file" % report in p
                            for p in problems), problems)
        # The same wrap in a display file passes, and a ROOT wrap tells the reviewer to rule out ids.
        self.repo.write(report, java_class("IssueReport", '    static String code(Enum<?> e) { return "" + '
                                           'e.name().toLowerCase(Locale.ROOT); }'))
        self.wrap_all()
        problems, warnings, _ = self.check([])
        self.assertEqual(problems, [])
        shown = [w for w in warnings if "Fmt.locale keeps that locale" in w]
        self.assertIn("Locale.ROOT is the data tier", shown[1])
        self.assertIn("not an id or a value that is stored, sent or compared", shown[1])
        self.assertFalse(any("data tier" in w for w in shown[:1] + shown[2:]), shown)

    def test_a_wrap_in_a_data_tier_file_fails(self):
        # Gates round 2 review: plan section 4.1's data tier (Telemetry label and value, stored records,
        # JSON) never localizes, whatever the legacy locale; a US wrap there would write a decimal comma.
        for name, code, wrapped in (
                ("Telemetry", 'static String value(double v, int d) { return String.format(Locale.US, "%." + d + "f", v); }',
                 "Locale.US, \"%.\""),
                ("RideStore", 'static java.text.SimpleDateFormat utc() { return new java.text.SimpleDateFormat('
                 '"yyyy-MM-dd\'T\'HH:mm:ss", Locale.US); }', "Locale.US)"),
                ("BackupJson", 'static String hex(char c) { return String.format(Locale.ROOT, "\\\\u%04x", (int) c); }',
                 "Locale.ROOT, ")):
            with self.subTest(name=name):
                path = "src/com/jjricks/volt/%s.java" % name
                self.repo.write(path, java_class(name, "    " + code))
                self.base = self.repo.commit("a data-tier file")
                locale = wrapped.split(",")[0].rstrip(")")
                self.repo.write(path, self.text(path).replace(locale, "Fmt.locale(%s)" % locale, 1))
                problems, _, _ = self.check([])
                self.assertTrue(any("%s:4: `%s` is wrapped into Fmt.locale(...) in a data-tier file" % (
                    path, locale.replace(".", " . ")) in p for p in problems), problems)

    def test_an_unreadable_english_only_list_fails(self):
        self.repo.write(ledger_check.CATALOG_USAGE_TEST, java_class("CatalogUsageTest", '    static final List<String> '
                        'ENGLISH_FILES = Arrays.asList("IssueReport.java");'))
        self.base = self.repo.commit("a renamed list")
        self.wrap_all()
        problems, _, _ = self.check([])
        self.assertTrue(any("cannot read its ENGLISH_ONLY" in p for p in problems), problems)

    def test_unwrapping_a_locale_fails(self):
        self.edit("String.format(Fmt.locale(Locale.US), \"%.0f\"", "String.format(Locale.US, \"%.0f\"")
        problems, _, _ = self.check([])
        self.assertTrue(any("%s:8: this change drops `Fmt . locale ( )`" % UI in p and "around text" in p
                            for p in problems), problems)


class ArrivalChoiceTest(RepoCase):
    """B1 fix round: a stays-english literal moved out whole while a new literal of the same value
    arrives in a file that sorts first; G1 must take the copy that arrived as the same expression."""
    TEMP = "src/com/jjricks/volt/DisplayTemperature.java"
    FMT = "src/com/jjricks/volt/Fmt.java"
    FILES = {TEMP: java_class("DisplayTemperature", '''    static String unit(String p) { return p.isEmpty() ? "C" : "F"; }
    private static String number(double value, int precision) {
        return String.format(Locale.US, "%." + precision + "f", value);
    }
    static String format(double v) { return number(v, 1); }'''),
             FMT: java_class("Fmt", "    static String decimal(Locale l, double v, int p) { return null; }")}
    ROW = TEMP + ':6\t["%.", {"hole": "precision", "arg": 1, "type": "d"}, "f"]\t-\tstays-english\tJava format syntax'

    def move(self, extra=""):
        self.repo.write(self.TEMP, java_class("DisplayTemperature", '''    static String unit(String p) { return p.isEmpty() ? "C" : "F"; }
    static String format(double v) { return number(v, 1); }
%s    private static String number(double value, int precision) { return Fmt.decimal(Locale.US, value, precision); }''' % extra))
        self.repo.write(self.FMT, java_class("Fmt", '    static String decimal(Locale l, double v, int p) { '
                                                    'return String.format(l, "%." + p + "f", v); }'))

    def test_the_moved_expression_passes(self):
        self.move()
        problems, _, _ = self.check([self.ROW])
        self.assertEqual(problems, [])

    def test_a_new_literal_of_the_same_value_fails_where_it_is(self):
        self.move('    static boolean fahrenheit(String p) { return "f".equals(p); }\n')
        problems, _, _ = self.check([self.ROW])
        self.assertTrue(any('%s:6: new literal "f"' % self.TEMP in p for p in problems), problems)
        self.assertFalse(any(self.FMT in p and "new literal" in p for p in problems), problems)

    def test_an_ambiguous_copy_is_reported_as_such(self):
        # Base: a lone "f" (line 4). Head: that method is deleted, and two unrelated new methods each
        # return "f"; neither is named where the literal left, so G1 cannot tell which one moved.
        self.repo.write(self.TEMP, java_class("DisplayTemperature", '    static String suffix() { return "f"; }\n'
                                              '    static String format(double v) { return null; }'))
        self.base = self.repo.commit("a lone f")
        self.repo.write(self.TEMP, java_class("DisplayTemperature", '    static String format(double v) { return null; }\n'
                                              '    static String a() { return "f"; }'))
        self.repo.write(self.FMT, self.text(self.FMT).replace("    static String decimal",
                                                             '    static String b() { return "f"; }\n    static String decimal'))
        problems, _, _ = self.check([self.TEMP + ':4\t["f"]\t-\tstays-english\tsuffix'])
        failed = [p for p in problems if 'new literal "f"' in p]
        self.assertEqual(len(failed), 1, problems)
        self.assertIn('%s:4: new literal "f"' % self.FMT, failed[0])
        self.assertIn('G1 could not tell which copy of "f" the stays-english row at l10n/ledger/b2.tsv:2 (%s:4) moved '
                      'to, and took the one at %s:5' % (self.TEMP, self.TEMP), failed[0])


class ExpressionTest(unittest.TestCase):
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

    def test_source_key_ignores_spacing_and_outer_parentheses(self):
        self.assertEqual(expressions.source_key("( i+1 )"), expressions.source_key("i + 1"))
        self.assertNotEqual(expressions.source_key("(a) + (b)"), expressions.source_key("a + b"))

    def test_file_diff_is_positional(self):
        before = 'class A {\n  String a = "one";\n  String b = "two";\n}\n'
        after = 'class A {\n  String a = "two";\n  String b = "one";\n}\n'
        diff = expressions.FileDiff(before, after)
        # Neighbours swapped in one changed region: at least one of them is gone, so G1 needs a row.
        self.assertEqual(len(diff.gone), 1)
        far = expressions.FileDiff('class A {\n  String a = "one";\n  int x;\n  String b = "two";\n}\n',
                                   'class A {\n  String a = "two";\n  int x;\n  String b = "one";\n}\n')
        self.assertEqual(sorted(far.base_token(k).value for k in far.gone), ["one", "two"])
        unchanged = expressions.FileDiff(before, before.replace("  String", "    String"))
        self.assertEqual(unchanged.gone, {})

    def test_scan_cli(self):
        path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "extract.py")
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            self.assertEqual(extract.main(["scan", path]), 0)
        self.assertTrue(out.getvalue().startswith("location\tliteral\tenglish\tsink\tpieces\n"))


if __name__ == "__main__":
    unittest.main()
