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
