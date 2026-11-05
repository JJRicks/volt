"""G1 for the shapes wave 2 and 3 recorded as gate blocks (l10n round T1).

Each relaxation here keeps its proof: the honest conversion passes and is listed for the reviewer, and the near
misses fail, above all an English change smuggled through the new shape.
"""

import json
import os
import sys
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gen_catalog  # noqa: E402
import ledger_check  # noqa: E402
from repo_fixture import java_class  # noqa: E402
from test_ledger_grammar import GrammarCase, HEADER, HELPER_BODY, PKG, choice_row, hole  # noqa: E402

CHARTS = PKG + "Charts.java"
GRID = PKG + "Grid.java"
ARCHIVE = PKG + "Archive.java"
TAG = "manual: folds the second statement into one template"


def removed(path, line, literal, note="the table is unused"):
    return "%s:%d\t%s\t-\tremoved\t%s" % (path, line, json.dumps([literal], ensure_ascii=False), note)


def count(source="n", arg=1):
    return {"hole": source, "arg": arg, "type": "d", "quantity": True}


class PluralFlagCase(GrammarCase):
    def setUp(self):
        super().setUp()
        patcher = mock.patch.object(gen_catalog, ledger_check.PLURAL_RULES_FLAG, True, create=True)
        patcher.start()
        self.addCleanup(patcher.stop)


class ArrayTableTest(GrammarCase):
    """Item 3: `String[] NAMES = {...};` could never leave: its ; was outside every row's scope."""
    FILES = {CHARTS: java_class("Charts", """    static final String[] LABELS = {"2 min", "All"};
    static final String[] KEPT = {"Keep"};
    static int size() { return KEPT.length; }""")}

    def rows(self):
        return [removed(CHARTS, 4, "2 min"), removed(CHARTS, 4, "All")]

    def test_the_whole_table_leaves_with_its_semicolon(self):
        self.edit(CHARTS, '    static final String[] LABELS = {"2 min", "All"};\n', "")
        problems, warnings, _ = self.check(self.rows())
        self.assertEqual(problems, [])
        self.assertTrue(any("LABELS" in w and "whole" in w for w in warnings), warnings)

    def test_a_table_with_a_label_no_row_covers_fails(self):
        self.edit(CHARTS, '    static final String[] LABELS = {"2 min", "All"};\n', "")
        problems, _, _ = self.check(self.rows()[:1])
        self.assertTrue(problems)

    def test_another_table_cannot_go_with_it(self):
        self.edit(CHARTS, '    static final String[] LABELS = {"2 min", "All"};\n'
                  '    static final String[] KEPT = {"Keep"};\n', "    static final String[] KEPT = null;\n")
        problems, _, _ = self.check(self.rows())
        self.assertTrue(problems)

    def test_a_following_statement_cannot_go_with_it(self):
        self.edit(CHARTS, '    static final String[] LABELS = {"2 min", "All"};\n'
                  '    static final String[] KEPT = {"Keep"};\n', '    static final String[] KEPT;\n')
        problems, _, _ = self.check(self.rows() + [removed(CHARTS, 5, "Keep")])
        self.assertTrue(problems)

    def test_a_table_left_half_there_fails(self):
        self.edit(CHARTS, '{"2 min", "All"};', '{"2 min"};')
        problems, _, _ = self.check(self.rows())
        self.assertTrue(problems)


class ManualSecondStatementTest(GrammarCase):
    """Item 3: a manual row folds `state += " • " + t;` into the one template of the sentence."""
    FILES = {GRID: java_class("Grid", """    static String tile(String t) {
        String state = "On";
        state += " • " + t;
        refresh();
        return state;
    }
    static void refresh() { }""")}
    STRINGS = """<resources>
<string name="dashboard_on_at">On • %1$s</string>
<string name="dashboard_on_late">On later %1$s</string>
</resources>
"""

    def row(self, pieces=None):
        pieces = pieces or ["On", " • ", hole("t", 1)]
        return "%s:5\t%s\tdashboard_on_at\ttemplate\t%s" % (GRID, json.dumps(pieces, ensure_ascii=False), TAG)

    def fold(self, call="T.dashboardOnAt(t)"):
        self.edit(GRID, 'String state = "On";\n        state += " • " + t;',
                  "String state = %s;" % call)

    def test_the_fold_passes_and_is_listed(self):
        self.fold()
        problems, warnings, _ = self.check([self.row()])
        self.assertEqual(problems, [])
        self.assertTrue(any('deletes the whole statement `state += " • " + t ;`' in w for w in warnings), warnings)

    def test_a_statement_with_no_literal_cannot_go_along(self):
        self.fold()
        self.edit(GRID, "        refresh();\n", "")
        problems, _, _ = self.check([self.row()])
        self.assertTrue(problems)

    def test_a_statement_left_partly_there_fails(self):
        self.edit(GRID, 'state += " • " + t;', "state += t;")
        problems, _, _ = self.check([self.row()])
        self.assertTrue(problems)

    def test_a_different_sentence_in_the_new_key_fails(self):
        self.fold("T.dashboardOnLate(t)")
        problems, _, _ = self.check([self.row().replace("dashboard_on_at", "dashboard_on_late")])
        self.assertTrue(any("dashboard_on_late" in p for p in problems), problems)


class OneFormTest(PluralFlagCase):
    """Item 5: n == 1 ? "Cleared 1 try" : "Cleared " + n + " tries" may print its 1 through a placeholder."""
    FILES = {GRID: java_class("Grid", """    static String tries(int n) {
        return n == 1 ? "Cleared 1 try" : "Cleared " + n + " tries";
    }""")}
    STRINGS = """<resources>
<plurals name="s_cleared"><item quantity="one">Cleared %1$d try</item><item quantity="other">Cleared %1$d tries</item></plurals>
</resources>
"""
    FORMS = {"one": ["Cleared 1 try"], "other": ["Cleared ", count(), " tries"]}

    def rows(self, forms=None):
        return ["%s:5\t%s\ts_cleared\tplural\tmanual: the base has its own branch for 1" % (
            GRID, json.dumps(forms or self.FORMS, ensure_ascii=False))]

    def convert(self):
        self.edit(GRID, 'n == 1 ? "Cleared 1 try" : "Cleared " + n + " tries"', "T.sCleared(n, n)")

    def test_an_item_printing_the_spelled_count_passes(self):
        self.convert()
        self.assertEqual(self.check(self.rows())[0], [])

    def test_other_words_in_the_one_item_fail(self):
        self.convert()
        self.repo.write("res/values/strings_test.xml", self.STRINGS.replace("Cleared %1$d try", "Cleared %1$d attempt"))
        problems = self.check(self.rows())[0]
        self.assertTrue(any("s_cleared[one]" in p and "Cleared 1 attempt" in p for p in problems), problems)

    def test_a_placeholder_that_is_not_the_count_still_shows(self):
        self.convert()
        self.repo.write("res/values/strings_test.xml", self.STRINGS.replace("Cleared %1$d try", "Cleared %2$s try"))
        problems = self.check(self.rows())[0]
        self.assertTrue(any("s_cleared[one]" in p for p in problems), problems)

    def test_the_other_item_gets_no_such_fill(self):
        self.assertEqual(ledger_check._fill_literal_count("x %1$d", self.FORMS["other"], self.FORMS), "x %1$d")


class PluralBranchTest(PluralFlagCase):
    """Item 4: a choice branch may be a plural, when its forms are the base at the counts English serves them."""
    FILES = {ARCHIVE: java_class("Archive", """    static String tally(boolean charges, int n) {
        return (charges ? plural(n, "charge") : plural(n, "ride")) + " skipped";
    }
    static String own(boolean hard, int n) {
        return (hard ? (n == 1 ? "Cleared 1 try" : "Cleared " + n + " tries") : "None");
    }
    static String flat(boolean a, int n) {
        return (a ? n + " rides" : "None");
    }
    """ + HELPER_BODY)}
    STRINGS = """<resources>
<plurals name="s_charges"><item quantity="one">%1$d charge skipped</item><item quantity="other">%1$d charges skipped</item></plurals>
<plurals name="s_rides"><item quantity="one">%1$d ride skipped</item><item quantity="other">%1$d rides skipped</item></plurals>
<plurals name="s_tries"><item quantity="one">Cleared %1$d try</item><item quantity="other">Cleared %1$d tries</item></plurals>
<plurals name="s_flat"><item quantity="one">%1$d ride</item><item quantity="other">%1$d rides</item></plurals>
<plurals name="s_flat_same"><item quantity="one">%1$d rides</item><item quantity="other">%1$d rides</item></plurals>
<string name="s_none">None</string>
</resources>
"""

    @staticmethod
    def helped(noun):
        form = [{"helper": "plural", "model": "count_noun", "args": [count(), noun]}, " skipped"]
        return {"one": form, "other": form}

    def tally_row(self, then=None, other=None, keys=("s_charges", "s_rides")):
        return choice_row(5, {"if": "charges", "then": then or self.helped("charge"), "else": other or self.helped("ride")},
                          keys, ARCHIVE)

    def convert_tally(self, call='charges ? T.sCharges(n, n) : T.sRides(n, n)'):
        self.edit(ARCHIVE, '(charges ? plural(n, "charge") : plural(n, "ride")) + " skipped"', call)

    def test_helper_branches_pass_and_are_listed(self):
        self.convert_tally()
        problems, warnings, _ = self.check([self.tally_row()])
        self.assertEqual(problems, [])
        self.assertTrue(any("choice row" in w and "s_charges" in w for w in warnings), warnings)

    def test_branches_swapped_fail(self):
        self.convert_tally()
        problems = self.check([self.tally_row(self.helped("ride"), self.helped("charge"))])[0]
        self.assertTrue(any("choice row at" in p for p in problems), problems)

    def test_a_noun_the_base_never_had_fails(self):
        self.convert_tally()
        problems = self.check([self.tally_row(self.helped("trip"))])[0]
        self.assertTrue(problems)

    def test_an_item_with_other_words_fails(self):
        self.convert_tally()
        self.repo.write("res/values/strings_test.xml", self.STRINGS.replace("%1$d charges skipped", "%1$d charges dropped"))
        problems = self.check([self.tally_row()])[0]
        self.assertTrue(any("s_charges[other]" in p for p in problems), problems)

    def test_head_calling_a_string_key_fails(self):
        self.convert_tally()
        problems = self.check([self.tally_row(keys=("s_none", "s_rides"))])[0]
        self.assertTrue(problems)

    def test_head_with_the_wrong_quantity_fails(self):
        self.convert_tally('charges ? T.sCharges(1, n) : T.sRides(n, n)')
        problems = self.check([self.tally_row()])[0]
        self.assertTrue(any("must become a ternary" in p for p in problems), problems)

    def test_a_helper_in_a_string_branch_is_refused(self):
        form = [{"helper": "plural", "model": "count_noun", "args": [count(), "ride"]}]
        with self.assertRaises(ledger_check.LedgerError) as caught:
            ledger_check.parse_ledger(HEADER + choice_row(4, {"if": "a", "then": form, "else": ["x"]}, ("a", "b"), ARCHIVE) + "\n")
        self.assertIn("belongs only in a plural branch", str(caught.exception))

    def test_a_branch_with_no_quantity_is_refused(self):
        with self.assertRaises(ledger_check.LedgerError):
            ledger_check.parse_ledger(HEADER + choice_row(
                4, {"if": "a", "then": {"one": ["x"], "other": ["y"]}, "else": ["x"]}, ("a", "b"), ARCHIVE) + "\n")

    def own_row(self, one=None):
        other = ["Cleared ", count(), " tries"]
        return choice_row(8, {"if": "hard", "then": {"one": one or ["Cleared 1 try"], "other": other}, "else": ["None"]},
                          ("s_tries", "s_none"), ARCHIVE)

    def test_the_bases_own_branch_for_one_passes(self):
        self.edit(ARCHIVE, '(hard ? (n == 1 ? "Cleared 1 try" : "Cleared " + n + " tries") : "None")',
                  "hard ? T.sTries(n, n) : T.sNone()")
        self.assertEqual(self.check([self.own_row()])[0], [])

    def test_a_one_form_the_bases_branch_did_not_print_fails(self):
        self.edit(ARCHIVE, '(hard ? (n == 1 ? "Cleared 1 try" : "Cleared " + n + " tries") : "None")',
                  "hard ? T.sTries(n, n) : T.sNone()")
        problems = self.check([self.own_row(["Cleared 1 attempt"])])[0]
        self.assertTrue(any("[one]" in p for p in problems), problems)

    def flat_row(self, key, forms):
        return choice_row(11, {"if": "a", "then": forms, "else": ["None"]}, (key, "s_none"), ARCHIVE)

    def test_a_plural_grammar_fix_smuggled_through_a_branch_fails(self):
        # The base prints "1 rides"; correcting it needs english-changes.tsv, not a branch.
        self.edit(ARCHIVE, '(a ? n + " rides" : "None")', "a ? T.sFlat(n, n) : T.sNone()")
        forms = {"one": [count(), " ride"], "other": [count(), " rides"]}
        problems = self.check([self.flat_row("s_flat", forms)])[0]
        self.assertTrue(any("[one]" in p and "the base expression" in p for p in problems), problems)

    def test_other_only_is_checked_at_one_too(self):
        self.edit(ARCHIVE, '(a ? n + " rides" : "None")', "a ? T.sFlatSame(n, n) : T.sNone()")
        self.assertEqual(self.check([self.flat_row("s_flat_same", {"other": [count(), " rides"]})])[0], [])


class PluralBranchHelperTest(PluralFlagCase):
    """A plural branch's helper is held to its model as a plural row's is."""
    FILES = {ARCHIVE: java_class("Archive", """    static String tally(boolean charges, int n) {
        return (charges ? plural(n, "charge") : plural(n, "ride")) + " skipped";
    }
    """ + HELPER_BODY.replace('"s"', '"es"'))}
    STRINGS = PluralBranchTest.STRINGS

    def test_a_helper_that_is_not_the_model_fails(self):
        self.edit(ARCHIVE, '(charges ? plural(n, "charge") : plural(n, "ride")) + " skipped"',
                  "charges ? T.sCharges(n, n) : T.sRides(n, n)")
        row = choice_row(5, {"if": "charges", "then": PluralBranchTest.helped("charge"),
                             "else": PluralBranchTest.helped("ride")}, ("s_charges", "s_rides"), ARCHIVE)
        problems = self.check([row])[0]
        self.assertTrue(any("helper plural's body" in p for p in problems), problems)


class FormatPluralTest(PluralFlagCase):
    """Item 4: a String.format line whose %d picks a plural's item, with every English item printing the base."""
    FILES = {GRID: java_class("Grid", """    static String rides(int n) { return String.format(Locale.US, "%d rides", n); }
    static String charged(int n, double a) { return String.format(Locale.US, "%.1f Ah %d", a, n); }""")}
    STRINGS = """<resources>
<plurals name="s_rides"><item quantity="one">%1$s rides</item><item quantity="other">%1$s rides</item></plurals>
<plurals name="s_fixed"><item quantity="one">%1$s ride</item><item quantity="other">%1$s rides</item></plurals>
<plurals name="s_two"><item quantity="one">%1$s Ah %2$s</item><item quantity="other">%1$s Ah %2$s</item></plurals>
<string name="s_flat">%1$s rides</string>
</resources>
"""
    ITEM = {"spec": "%d", "arg": 1, "value": "n", "as": "Fmt.integer(n)", "quantity": True}

    def setUp(self):
        super().setUp()
        self.repo.write("tests/l10n/FmtLegacyTest.java", java_class(
            "FmtLegacyTest", "    static void run() { Grid.rides(1); Grid.charged(1, 1); Fmt.integer(1); Fmt.decimal(Locale.US, 1, 1); }"))
        self.repo.write("tests/l10n/b1.sources", "# G10\ntests/l10n/FmtLegacyTest.java  # parity\n")

    def row(self, key="s_rides", split=None, line=4):
        split = split or [self.ITEM, " rides"]
        return "%s:%d\t%s\t%s\tformat\t" % (GRID, line, json.dumps(split, ensure_ascii=False), key)

    def convert(self, call="T.sRides(n, Fmt.integer(n))"):
        self.edit(GRID, 'String.format(Locale.US, "%d rides", n)', call)

    def test_the_plural_line_passes_and_says_so(self):
        self.convert()
        problems, warnings, _ = self.check([self.row()])
        self.assertEqual(problems, [])
        self.assertTrue(any("format row" in w and "plural on n" in w for w in warnings), warnings)

    def test_a_corrected_one_item_fails(self):
        self.convert("T.sFixed(n, Fmt.integer(n))")
        problems = self.check([self.row("s_fixed")])[0]
        self.assertTrue(any("s_fixed[one]" in p and "1234567 ride" in p.replace("\\x01A", "1234567") or "s_fixed[one]" in p
                            for p in problems), problems)

    def test_a_string_key_is_refused_for_a_quantity(self):
        self.convert("T.sFlat(Fmt.integer(n))")
        problems = self.check([self.row("s_flat")])[0]
        self.assertTrue(any("is a string, but" in p for p in problems), problems)

    def test_a_plural_key_without_quantity_is_refused(self):
        self.convert()
        problems = self.check([self.row(split=[dict(spec="%d", arg=1, value="n", **{"as": "Fmt.integer(n)"}), " rides"])])[0]
        self.assertTrue(any("is a plural, but" in p for p in problems), problems)

    def test_a_constant_quantity_fails(self):
        self.convert("T.sRides(1, Fmt.integer(n))")
        self.assertTrue(self.check([self.row()])[0])

    def test_a_float_cannot_be_the_quantity(self):
        with self.assertRaises(ledger_check.LedgerError):
            ledger_check.parse_ledger(HEADER + self.row(split=[dict(self.ITEM, spec="%.1f"), " rides"]) + "\n")

    def test_two_quantities_are_refused(self):
        with self.assertRaises(ledger_check.LedgerError):
            ledger_check.parse_ledger(HEADER + self.row(split=[self.ITEM, " ", dict(self.ITEM, arg=2)]) + "\n")

    def test_the_quantity_can_be_any_specifier_of_a_longer_line(self):
        self.edit(GRID, 'String.format(Locale.US, "%.1f Ah %d", a, n)',
                  "T.sTwo(n, Fmt.decimal(Locale.US, a, 1), Fmt.integer(n))")
        split = [{"spec": "%.1f", "arg": 1, "value": "a", "as": "Fmt.decimal(Locale.US, a, 1)"}, " Ah ",
                 dict(self.ITEM, arg=2)]
        self.assertEqual(self.check([self.row("s_two", split, 5)])[0], [])


if __name__ == "__main__":
    unittest.main()
