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

    def test_a_table_with_a_call_in_it_keeps_its_semicolon(self):
        self.repo.write(CHARTS, java_class("Charts", """    static final String[] LABELS = {compute(), "All"};
    static String compute() { return ""; }"""))
        self.base = self.repo.commit("base with a call")
        self.edit(CHARTS, '    static final String[] LABELS = {compute(), "All"};\n', "")
        problems = self.check([removed(CHARTS, 4, "All")])[0]
        self.assertTrue(problems)

    def test_an_anonymous_class_keeps_its_semicolon(self):
        self.repo.write(CHARTS, java_class("Charts", """    static final Runnable R = new Runnable() { public void run() { lock("Engage now"); } };
    static void lock(String w) { }"""))
        self.base = self.repo.commit("base with a class")
        self.edit(CHARTS, '    static final Runnable R = new Runnable() { public void run() { lock("Engage now"); } };\n', "")
        problems = self.check([removed(CHARTS, 4, "Engage now")])[0]
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

    def test_a_quantity_with_an_effect_is_refused(self):
        with self.assertRaises(ledger_check.LedgerError):
            ledger_check.parse_ledger(HEADER + self.row(split=[dict(self.ITEM, value="it.next()", **{"as": "Fmt.integer(it.next())"}), " rides"]) + "\n")

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


HEADER_BASE = """    static void refresh(boolean ready, boolean connecting, long seen, String recent, Reading drive) {
        String mode = null;
        if (ready && drive != null && drive.live) {
            String value = format(drive);
            if (value.matches("[A-Z]+")) mode = value;
        }
        String word = ready ? "Connected" : connecting ? "Connecting" : "Disconnected";
        if (connecting) status.setText(label());
        else status.setText(word + (mode != null ? " · " + mode : "") + (!ready && seen > 0 ? " · last seen " + recent : ""));
        status.setContentDescription(word + (mode != null ? ", " + mode + " mode" : "")
                + (!ready && !connecting && seen > 0 ? ", last seen " + recent : "") + ". Details");
    }"""
SENTENCE = """word + (mode != null ? " · " + mode : "") + (!ready && seen > 0 ? " · last seen " + recent : "")"""
SPOKEN = ('word + (mode != null ? ", " + mode + " mode" : "")\n'
          '                + (!ready && !connecting && seen > 0 ? ", last seen " + recent : "") + ". Details"')
WORD = """        String word = ready ? "Connected" : connecting ? "Connecting" : "Disconnected";\n"""
STATUS_HEAD = "ready ? (mode != null ? T.sMode(mode) : T.sOn()) : seen > 0 ? T.sSeen(recent) : T.sOff()"
SPOKEN_HEAD = ("ready ? (mode != null ? T.pMode(mode) : T.pOn()) : connecting ? T.pGoing() : "
               "seen > 0 ? T.pSeen(recent) : T.pOff()")
HEADER_STRINGS = """<resources>
<string name="s_mode">Connected · %1$s</string>
<string name="s_on">Connected</string>
<string name="s_seen">Disconnected · last seen %1$s</string>
<string name="s_off">Disconnected</string>
<string name="p_mode">Connected, %1$s mode. Details</string>
<string name="p_on">Connected. Details</string>
<string name="p_going">Connecting. Details</string>
<string name="p_seen">Disconnected, last seen %1$s. Details</string>
<string name="p_off">Disconnected. Details</string>
<string name="s_linked">Linked</string>
</resources>
"""
STATUS_KEYS = ("s_mode", "s_on", "s_seen", "s_off")
SPOKEN_KEYS = ("p_mode", "p_on", "p_going", "p_seen", "p_off")
STATUS_PIECES = {"if": "ready", "then": {"if": "mode != null", "then": ["Connected", " · ", hole("mode", 1)], "else": ["Connected"]},
                 "else": {"if": "seen > 0", "then": ["Disconnected", " · last seen ", hole("recent", 1)], "else": ["Disconnected"]}}
SPOKEN_PIECES = {"if": "ready", "then": {"if": "mode != null", "then": ["Connected", ", ", hole("mode", 1), " mode", ". Details"],
                                         "else": ["Connected", ". Details"]},
                 "else": {"if": "connecting", "then": ["Connecting", ". Details"],
                          "else": {"if": "seen > 0", "then": ["Disconnected", ", last seen ", hole("recent", 1), ". Details"],
                                   "else": ["Disconnected", ". Details"]}}}


class HeaderStatusTest(GrammarCase):
    """Item 6: the Dashboard header status: a word picked on the line above, an else around the sentence, and a
    mode that stays null unless ready held."""
    FILES = {GRID: java_class("Grid", HEADER_BASE)}
    STRINGS = HEADER_STRINGS

    def rows(self, status=STATUS_PIECES, spoken=SPOKEN_PIECES, keys=STATUS_KEYS):
        return [choice_row(12, status, keys, GRID), choice_row(13, spoken, SPOKEN_KEYS, GRID)]

    def convert(self, status=STATUS_HEAD, spoken=SPOKEN_HEAD, word_gone=True):
        self.edit(GRID, SENTENCE, status)
        self.edit(GRID, SPOKEN, spoken)
        if word_gone:
            self.edit(GRID, WORD, "")

    def test_both_sentences_pass_with_the_word_gone(self):
        self.convert()
        problems, warnings, _ = self.check(self.rows())
        self.assertEqual(problems, [])
        self.assertTrue(any("the local word is" in w and "`connecting` is false around it" in w for w in warnings), warnings)
        self.assertTrue(any("mode stays null unless drive != null and ready" in w for w in warnings), warnings)

    def test_a_word_the_local_did_not_hold_fails(self):
        self.edit(GRID, SENTENCE, STATUS_HEAD.replace("T.sOn()", "T.sLinked()"))
        self.edit(GRID, SPOKEN, SPOKEN_HEAD)
        self.edit(GRID, WORD, "")
        status = json.loads(json.dumps(STATUS_PIECES))
        status["then"]["else"] = ["Linked"]
        problems = self.check(self.rows(status, keys=("s_mode", "s_linked", "s_seen", "s_off")))[0]
        self.assertTrue(any("choice row at" in p for p in problems), problems)

    def test_the_local_kept_when_the_other_sentence_still_reads_it_is_fine(self):
        self.edit(GRID, SENTENCE, STATUS_HEAD)
        problems = self.check(self.rows()[:1])[0]
        self.assertEqual(problems, [])

    def test_the_local_half_gone_leaves_its_strings_with_no_row(self):
        self.edit(GRID, SENTENCE, STATUS_HEAD)
        self.edit(GRID, SPOKEN, SPOKEN_HEAD)
        self.edit(GRID, '"Connecting" : "Disconnected"', '"Going" : "Disconnected"')
        problems = self.check(self.rows())[0]
        self.assertTrue(problems)

    def test_a_status_change_on_the_connecting_path_fails(self):
        # Without the else around it the status prints "Connecting", which no branch here holds.
        self.edit(GRID, 'if (connecting) status.setText(label());\n        else status.setText', "status.setText")
        self.edit(GRID, SENTENCE, STATUS_HEAD)
        self.assertTrue(self.check(self.rows()[:1])[0])

    def test_a_dropped_mode_fails(self):
        self.convert()
        status = json.loads(json.dumps(STATUS_PIECES))
        status["then"]["then"] = ["Connected"]
        problems = self.check(self.rows(status))[0]
        self.assertTrue(problems)

    def test_a_branch_merged_away_fails(self):
        status = {"if": "ready", "then": {"if": "mode != null", "then": ["Connected", " · ", hole("mode", 1)], "else": ["Connected"]},
                  "else": ["Disconnected"]}
        self.convert(status="ready ? (mode != null ? T.sMode(mode) : T.sOn()) : T.sOff()")
        problems = self.check(self.rows(status, keys=("s_mode", "s_on", "s_off")))[0]
        self.assertTrue(any("still holds the choice" in p or "choice row at" in p for p in problems), problems)

    def test_a_flipped_head_condition_fails(self):
        self.convert(status=STATUS_HEAD.replace("ready ?", "!ready ?"))
        self.assertTrue(self.check(self.rows())[0])


def header_variant(old, new):
    base = HEADER_BASE
    assert old in base, old
    return {GRID: java_class("Grid", base.replace(old, new))}


class HeaderNearMissCase(GrammarCase):
    STRINGS = HEADER_STRINGS

    def assertRefused(self):
        self.edit(GRID, SENTENCE, STATUS_HEAD)
        problems = self.check([choice_row(self.LINE, STATUS_PIECES, STATUS_KEYS, GRID)])[0]
        self.assertTrue(any("choice row at" in p for p in problems), problems)


class LocalReassignedTest(HeaderNearMissCase):
    FILES = header_variant('        if (connecting) status', '        word = "Other";\n        if (connecting) status')
    LINE = 13

    def test_refused(self):
        self.assertRefused()


class LocalConditionChangesTest(HeaderNearMissCase):
    FILES = header_variant('        if (connecting) status', '        ready = !ready;\n        if (connecting) status')
    LINE = 13

    def test_refused(self):
        self.assertRefused()


class LocalConditionOnACallTest(HeaderNearMissCase):
    FILES = header_variant('String word = ready ?', 'String word = isReady() ?')
    LINE = 12

    def test_refused(self):
        self.assertRefused()


class GuardVariableChangesTest(HeaderNearMissCase):
    FILES = {GRID: java_class("Grid", HEADER_BASE.replace(
        '        else status.setText(' + SENTENCE + ');',
        '        else { connecting = probe(); status.setText(' + SENTENCE + '); }'))}
    LINE = 12

    def test_refused(self):
        self.assertRefused()


class ModeAssignedOutsideTheGuardTest(HeaderNearMissCase):
    FILES = header_variant('        String word =', '        mode = "Sport";\n        String word =')
    LINE = 13

    def test_refused(self):
        self.assertRefused()


class BackupNounTest(PluralFlagCase):
    """Item 6 with item 4: the noun of plural(n, noun), distributed into each sentence's branches."""
    FILES = {ARCHIVE: java_class("Archive", """    static void plan(java.util.List<String> lines, boolean charges, int full, int lost) {
        String noun = charges ? "charge" : "ride";
        if (full > 0) lines.add(plural(full, noun) + " skipped");
        if (lost > 0) lines.add(plural(lost, noun) + " lost");
    }
    """ + HELPER_BODY)}
    STRINGS = """<resources>
<plurals name="c_full"><item quantity="one">%1$d charge skipped</item><item quantity="other">%1$d charges skipped</item></plurals>
<plurals name="r_full"><item quantity="one">%1$d ride skipped</item><item quantity="other">%1$d rides skipped</item></plurals>
<plurals name="c_lost"><item quantity="one">%1$d charge lost</item><item quantity="other">%1$d charges lost</item></plurals>
<plurals name="r_lost"><item quantity="one">%1$d ride lost</item><item quantity="other">%1$d rides lost</item></plurals>
</resources>
"""

    @staticmethod
    def form(source, noun, tail):
        piece = [{"helper": "plural", "model": "count_noun", "args": [count(source), noun]}, tail]
        return {"one": piece, "other": piece}

    def row(self, line, source, tail, keys, then="charge"):
        return choice_row(line, {"if": "charges", "then": self.form(source, then, tail),
                                 "else": self.form(source, "ride", tail)}, keys, ARCHIVE)

    def rows(self):
        return [self.row(6, "full", " skipped", ("c_full", "r_full")), self.row(7, "lost", " lost", ("c_lost", "r_lost"))]

    def convert(self):
        self.edit(ARCHIVE, 'plural(full, noun) + " skipped"', "charges ? T.cFull(full, full) : T.rFull(full, full)")
        self.edit(ARCHIVE, 'plural(lost, noun) + " lost"', "charges ? T.cLost(lost, lost) : T.rLost(lost, lost)")

    def test_both_sentences_pass_with_the_noun_gone(self):
        self.convert()
        self.edit(ARCHIVE, '        String noun = charges ? "charge" : "ride";\n', "")
        self.assertEqual(self.check(self.rows())[0], [])

    def test_one_sentence_converts_while_the_other_keeps_the_noun(self):
        self.edit(ARCHIVE, 'plural(full, noun) + " skipped"', "charges ? T.cFull(full, full) : T.rFull(full, full)")
        self.assertEqual(self.check(self.rows()[:1])[0], [])

    def test_the_wrong_noun_in_a_branch_fails(self):
        self.convert()
        self.edit(ARCHIVE, '        String noun = charges ? "charge" : "ride";\n', "")
        problems = self.check([self.row(6, "full", " skipped", ("c_full", "r_full"), then="ride"), self.rows()[1]])[0]
        self.assertTrue(any("choice row at" in p for p in problems), problems)


class ReplacesWhatTest(GrammarCase):
    """Item 6: BackupArchive's what, a local read inside a branch of the sentence."""
    FILES = {ARCHIVE: java_class("Archive", """    static String line(String category, String label, boolean empty, String names) {
        String what = category.equals(BackupRegistry.DASHBOARDS) ? "layouts" : "settings";
        return label + ": " + (empty ? "nothing to replace" : "replaces " + what + " for " + names);
    }""")}
    STRINGS = """<resources>
<string name="s_nothing">%1$s: nothing to replace</string>
<string name="s_layouts">%1$s: replaces layouts for %2$s</string>
<string name="s_settings">%1$s: replaces settings for %2$s</string>
</resources>
"""
    HEAD = ("empty ? T.sNothing(label) : category.equals(BackupRegistry.DASHBOARDS) ? T.sLayouts(label, names) "
            ": T.sSettings(label, names)")

    def row(self, then="layouts"):
        return choice_row(6, {"if": "empty", "then": [hole("label", 1), ": ", "nothing to replace"],
                              "else": {"if": "category.equals(BackupRegistry.DASHBOARDS)",
                                       "then": [hole("label", 1), ": ", "replaces ", then, " for ", hole("names", 2)],
                                       "else": [hole("label", 1), ": ", "replaces ", "settings", " for ", hole("names", 2)]}},
                          ("s_nothing", "s_layouts", "s_settings"), ARCHIVE)

    def convert(self):
        self.edit(ARCHIVE, 'label + ": " + (empty ? "nothing to replace" : "replaces " + what + " for " + names)', self.HEAD)
        self.edit(ARCHIVE, '        String what = category.equals(BackupRegistry.DASHBOARDS) ? "layouts" : "settings";\n', "")

    def test_each_sentence_passes(self):
        self.convert()
        self.assertEqual(self.check([self.row()])[0], [])

    def test_the_words_swapped_fail(self):
        self.convert()
        problems = self.check([self.row("settings")])[0]
        self.assertTrue(any("choice row at" in p for p in problems), problems)


def strings_xml(**entries):
    return "<resources>\n%s</resources>\n" % "".join('<string name="%s">%s</string>\n' % e for e in entries.items())


class ShapeCase(PluralFlagCase):
    """Each test builds its own base: body, with the head's edits and rows."""

    def attempt(self, body, edits, rows, strings):
        self.repo.write(GRID, java_class("Grid", body))
        self.base = self.repo.commit("base")
        self.repo.write("res/values/strings_test.xml", strings)
        for old, new in edits:
            self.edit(GRID, old, new)
        return self.check(rows)

    @staticmethod
    def line_of(body, text):
        return body[:body.index(text)].count("\n") + 4


class FoldShapesTest(ShapeCase):
    """Item 3: a manual row folds only an unconditional append to the local its sentence just set, with operands
    that are the row's own. Whatever else it would delete is refused, whichever tokens the aligner kept."""
    STRINGS_AT = strings_xml(s_on_at="On • %1$s", s_on_late="On later %1$s", s_on_chain="On later • %1$s")

    def fold(self, body, old, call, pieces, key="s_on_at"):
        row = "%s:%d\t%s\t%s\ttemplate\t%s" % (GRID, self.line_of(body, '"On"'), json.dumps(pieces, ensure_ascii=False), key, TAG)
        return self.attempt(body, [(old, call)], [row], self.STRINGS_AT)

    def test_a_call_in_the_append_passes(self):
        body = """    static String tile(String t) {
        String state = "On";
        state += " • " + fmt(t);
        return state;
    }
    static String fmt(String t) { return t; }"""
        problems, warnings, _ = self.fold(body, 'String state = "On";\n        state += " • " + fmt(t);', "String state = T.sOnAt(fmt(t));",
                                          ["On", " • ", hole("fmt(t)", 1)])
        self.assertEqual(problems, [])
        self.assertTrue(any("deletes the whole statement" in w for w in warnings), warnings)

    def test_the_b12_argument_passes(self):
        clock = "DisplayTime.clock(host.activity(), preferences, charge.startWallMs)"
        body = """    static String tile(Host host, Prefs preferences, Charge charge) {
        String state = "On";
        state += " • " + %s;
        return state;
    }""" % clock
        problems = self.fold(body, 'String state = "On";\n        state += " • " + %s;' % clock, "String state = T.sOnAt(%s);" % clock,
                             ["On", " • ", hole(clock, 1)])[0]
        self.assertEqual(problems, [])

    def assertRefused(self, body, old, call, pieces, key="s_on_at"):
        problems = self.fold(body, old, call, pieces, key)[0]
        self.assertTrue(problems)

    def test_a_guarded_append_is_refused(self):
        self.assertRefused("""    static String tile(boolean charging, String t) {
        String state = "On";
        if (charging) state += " • " + t;
        return state;
    }""", 'String state = "On";\n        if (charging) state += " • " + t;', "String state = T.sOnAt(t);",
                           ["On", " • ", hole("t", 1)])

    def test_an_early_return_is_refused(self):
        self.assertRefused("""    static String tile(boolean bad) {
        String state = "On";
        if (bad) return " stop";
        return state;
    }""", 'String state = "On";\n        if (bad) return " stop";', "String state = T.sOnStop();", ["On", " stop"], "s_on_stop")

    def test_another_locals_append_is_refused(self):
        self.assertRefused("""    static String tile(String t) {
        String other = "";
        String state = "On";
        other += " • " + t;
        return state + other;
    }""", 'String state = "On";\n        other += " • " + t;', "String state = T.sOnAt(t);", ["On", " • ", hole("t", 1)])

    def test_an_append_after_another_statement_is_refused(self):
        self.assertRefused("""    static String tile(String t) {
        String state = "On";
        log();
        state += " • " + t;
        return state;
    }
    static void log() { }""", 'String state = "On";\n        log();\n        state += " • " + t;',
                           "String state = T.sOnAt(t);\n        log();", ["On", " • ", hole("t", 1)])

    def test_a_set_in_place_of_an_append_is_refused(self):
        # The base prints only " • t": "On" is overwritten.
        self.assertRefused("""    static String tile(String t) {
        String state = "On";
        state = " • " + t;
        return state;
    }""", 'String state = "On";\n        state = " • " + t;', "String state = T.sOnAt(t);", ["On", " • ", hole("t", 1)])

    def test_an_append_still_there_at_head_is_refused(self):
        self.assertRefused("""    static String tile(String t) {
        String state = "On";
        state += " • " + t;
        return state;
    }""", 'String state = "On";\n        state += " • " + t;', "String state = T.sOnAt(t);\n        state += t;",
                           ["On", " • ", hole("t", 1)])

    def test_a_choice_in_the_first_statement_is_refused(self):
        # What the first statement sets is part of the sentence, so the row must hold all of it.
        self.assertRefused("""    static String tile(boolean c, String other, String t) {
        String state = c ? "On" : other;
        state += " • " + t;
        return state;
    }""", 'String state = c ? "On" : other;\n        state += " • " + t;', "String state = T.sOnAt(t);", ["On", " • ", hole("t", 1)])

    def test_a_piece_the_statements_do_not_print_is_refused(self):
        self.assertRefused("""    static String tile(String t) {
        String state = "On";
        state += " • " + t;
        return state;
    }""", 'String state = "On";\n        state += " • " + t;', "String state = T.sOnLate(t);", ["On", " later ", " • ", hole("t", 1)], "s_on_late")

    def test_a_second_append_is_refused(self):
        self.assertRefused("""    static String tile(String t) {
        String state = "On";
        state += " later";
        state += " • " + t;
        return state;
    }""", 'String state = "On";\n        state += " later";\n        state += " • " + t;', "String state = T.sOnChain(t);",
                           ["On", " later", " • ", hole("t", 1)], "s_on_chain")

    def test_an_append_after_a_conditional_set_is_refused(self):
        self.assertRefused("""    static String tile(boolean c, String t) {
        String state = t;
        if (c) state = "On";
        state += " • " + t;
        return state;
    }""", 'if (c) state = "On";\n        state += " • " + t;', "if (c) state = T.sOnAt(t);", ["On", " • ", hole("t", 1)])

    def test_a_call_the_row_does_not_hold_is_refused(self):
        self.assertRefused("""    static String tile(String t) {
        String state = "On";
        state += " • " + t + bump();
        return state;
    }
    static String bump() { return ""; }""", 'String state = "On";\n        state += " • " + t + bump();', "String state = T.sOnAt(t);",
                           ["On", " • ", hole("t", 1)])

    def test_operands_out_of_the_rows_order_are_refused(self):
        self.assertRefused("""    static String tile(String t) {
        String state = "On";
        state += t + " • ";
        return state;
    }""", 'String state = "On";\n        state += t + " • ";', "String state = T.sOnAt(t);", ["On", " • ", hole("t", 1)])

    def test_a_field_is_refused(self):
        self.assertRefused("""    static String state;
    static String tile(String t) {
        state = "On";
        state += " • " + t;
        return state;
    }""", 'state = "On";\n        state += " • " + t;', "state = T.sOnAt(t);", ["On", " • ", hole("t", 1)])


class ContextFactsTest(ShapeCase):
    """Item 6: what a fact rests on. In each base the second choice holds a word the row can only get from a fact."""
    SETTLED = strings_xml(s_pa="pA", s_qa="qA", s_pb="pB", s_qb="qB")

    def settle(self, body, condition, word="A"):
        sentence = '(x ? "p" : "q") + (%s ? "A" : "B")' % condition
        body = body.replace("@", sentence)
        low = word.lower()
        row = choice_row(self.line_of(body, sentence), {"if": "x", "then": ["p", word], "else": ["q", word]}, ("s_p" + low, "s_q" + low), GRID)
        return self.attempt(body, [(sentence, "x ? T.sP%s() : T.sQ%s()" % (low, low))], [row], self.SETTLED)[0]

    def assertRefused(self, body, condition, word="A"):
        problems = self.settle(body, condition, word)
        self.assertTrue(any("still holds the choice" in p or "choice row at" in p for p in problems), problems)

    GUARDED = """    static String f(boolean x, %s) {
        if (%s) {
            %s
            return @;
        }
        return "";
    }
%s"""

    def guarded(self, params, condition, between="", members=""):
        return self.GUARDED % (params, condition, between, members)

    def test_an_all_caps_local_changed_after_the_guard(self):
        self.assertRefused(self.guarded("boolean N", "N", "N = false;"), "N")

    def test_an_all_caps_field_not_final(self):
        self.assertRefused(self.guarded("int u", "LIVE", "flip();", "    static boolean LIVE;\n    static void flip() { LIVE = !LIVE; }"), "LIVE")

    def test_a_constant_of_an_object_is_not_a_constant(self):
        self.assertRefused(self.guarded("Cfg c", "c.ON", "c.ON = !c.ON;"), "c.ON")

    def test_a_constant_of_a_field_is_not_a_constant(self):
        self.assertRefused(self.guarded("int u", "c.ON", "flip();", "    static Cfg c;\n    static void flip() { }"), "c.ON")

    def test_a_class_field_this_file_assigns(self):
        self.assertRefused(self.guarded("int u", "Cfg.ON", "Cfg.ON = false;"), "Cfg.ON")

    def test_an_all_caps_method(self):
        self.assertRefused(self.guarded("int u", "Cfg.ON()", "flip();", "    static void flip() { }"), "Cfg.ON()")

    def test_equals_on_a_list_that_changes(self):
        self.assertRefused(self.guarded("java.util.List<String> l, java.util.List<String> m", "l.equals(m)", 'l.add("z");'), "l.equals(m)")

    def test_equals_on_a_builder_that_changes(self):
        self.assertRefused(self.guarded("StringBuilder l, String m", "l.equals(m)", 'l.append("z");'), "l.equals(m)")

    def test_equals_on_a_string_reassigned(self):
        self.assertRefused(self.guarded("String l, String m", "l.equals(m)", "l = m + m;"), "l.equals(m)")

    def test_a_field_with_a_local_of_its_name_in_a_closed_block(self):
        self.assertRefused("""    static boolean live;
    static String f(boolean x) {
        { boolean live = true; }
        if (live) {
            refresh();
            return @;
        }
        return "";
    }
    static void refresh() { live = false; }""", "live")

    def test_a_field_with_a_loop_variable_of_its_name(self):
        self.assertRefused("""    static boolean live;
    static String f(boolean x, boolean[] all) {
        for (boolean live : all) use(live);
        if (live) {
            refresh();
            return @;
        }
        return "";
    }
    static void use(boolean b) { }
    static void refresh() { live = false; }""", "live")

    def test_an_else_through_a_while(self):
        self.assertRefused("""    static String f(boolean x, boolean a, boolean b, boolean c) {
        String out = "";
        if (a) while (b) if (c) out = "z"; else out = @;
        return out;
    }""", "a", "B")

    def test_an_else_through_a_foreach(self):
        self.assertRefused("""    static String f(boolean x, boolean a, boolean c, int[] all) {
        String out = "";
        if (a) for (int n : all) if (c) out = "z"; else out = @;
        return out;
    }""", "a", "B")

    def test_a_block_else_through_a_while(self):
        self.assertRefused("""    static String f(boolean x, boolean a, boolean b, boolean c) {
        String out = "";
        if (a) while (b) if (c) { out = "z"; } else { out = @; }
        return out;
    }""", "a", "B")

    def test_a_dangling_else_belongs_to_the_inner_if(self):
        self.assertRefused("""    static String f(boolean x, boolean a, boolean c) {
        String out = "";
        if (a) if (c) out = "z"; else out = @;
        return out;
    }""", "a", "B")

    def test_an_else_settles_its_if_only_as_a_whole(self):
        self.assertRefused("""    static String f(boolean x, boolean a, boolean b) {
        String out = "";
        if (a && b) out = "z"; else out = @;
        return out;
    }""", "a", "B")

    def test_a_true_or_settles_neither_side(self):
        self.assertRefused("""    static String f(boolean x, boolean a, boolean b) {
        if (a || b) return @;
        return "";
    }""", "a", "B")

    def test_a_guard_says_the_opposite_of_its_negation(self):
        self.assertRefused(self.guarded("int a, int b", "a != b"), "a == b")
        self.assertEqual(self.settle_more(self.guarded("int a, int b", "a != b"), "a == b", "B"), [])

    def settle_more(self, body, condition, word):
        self.repo.close()
        self.setUp()
        return self.settle(body, condition, word)

    def test_a_guard_outside_a_loop_is_not_read_through_it(self):
        self.assertRefused("""    static String f(boolean a, boolean c, boolean x) {
        String r = "";
        if (a) {
            while (c) { r = @; c = false; }
        }
        return r;
    }""", "a")

    def test_a_constant_settles(self):
        self.assertEqual(self.settle(self.guarded("int u", "LIVE", "flip();", "    static final boolean LIVE = true;\n    static void flip() { }"), "LIVE"), [])

    def test_a_class_constant_settles(self):
        self.assertEqual(self.settle(self.guarded("int u", "Cfg.ON", "u = 1;"), "Cfg.ON"), [])

    def test_equals_on_a_string_local_settles(self):
        self.assertEqual(self.settle(self.guarded("String l, String m", "l.equals(m)", "use();", "    static void use() { }"), "l.equals(m)"), [])

    def test_a_plain_statement_before_an_else_settles(self):
        self.assertEqual(self.settle("""    static String f(boolean x, boolean a) {
        String out = "";
        if (a) use(); else out = @;
        return out;
    }
    static void use() { }""", "a", "B"), [])

    def test_a_block_else_settles_each_if_of_its_chain(self):
        body = """    static String f(boolean x, boolean a, boolean b) {
        String out = "";
        if (a) { use(); } else if (b) { use(); } else { out = @; }
        return out;
    }
    static void use() { }"""
        self.assertEqual(self.settle(body, "a", "B"), [])
        self.assertEqual(self.settle_more(body, "b", "B"), [])

    def test_a_loop_variable_settles_in_its_own_body(self):
        self.assertEqual(self.settle("""    static String f(boolean x, boolean[] all) {
        String out = "";
        for (boolean live : all) {
            if (live) { use(); out = @; }
        }
        return out;
    }
    static void use() { }""", "live"), [])


class NullImplicationShapesTest(ShapeCase):
    """Item 6: `T name = null;`, assigned only under a guard, stays null where the guard is false."""
    STRINGS_NULL = strings_xml(s_a="On · %1$s", s_b="On", s_c="Off")
    PIECES = {"if": "ready", "then": {"if": "mode != null", "then": ["On", " · ", hole("mode", 1)], "else": ["On"]}, "else": ["Off"]}
    SENTENCE = '(ready ? "On" : "Off") + (mode != null ? " · " + mode : "")'
    HEAD = "ready ? (mode != null ? T.sA(mode) : T.sB()) : T.sC()"

    def problems(self, body):
        row = choice_row(self.line_of(body, self.SENTENCE), self.PIECES, ("s_a", "s_b", "s_c"), GRID)
        return self.attempt(body, [(self.SENTENCE, self.HEAD)], [row], self.STRINGS_NULL)[0]

    def test_it_settles(self):
        self.assertEqual(self.problems("""    static String s(boolean ready, String m) {
        String mode = null;
        if (ready) { mode = m; }
        return %s;
    }""" % self.SENTENCE), [])

    def test_a_guard_set_earlier_in_the_loop_does_not_hold_later(self):
        # An earlier pass sets mode, the last one clears ready: the base prints "Off · m".
        self.assertTrue(self.problems("""    static String s(java.util.Iterator<Integer> it) {
        boolean ready = false;
        String mode = null;
        while (it.hasNext()) {
            ready = it.next() > 0;
            if (ready) { mode = "m"; }
        }
        return %s;
    }""" % self.SENTENCE))

    def test_an_assignment_under_the_opposite_of_the_guard_does_not_count(self):
        # Where ready is false mode is set, so the row's Off branch would drop it.
        self.assertTrue(self.problems("""    static String s(boolean ready, String m) {
        String mode = null;
        if (ready) { } else { mode = m; }
        return %s;
    }""" % self.SENTENCE))

    def test_a_local_null_in_a_closed_block_says_nothing_of_a_field(self):
        self.assertTrue(self.problems("""    static String mode = "z";
    static String s(boolean ready) {
        { String mode = null; if (ready) mode = "m"; use(mode); }
        return %s;
    }
    static void use(String u) { }""" % self.SENTENCE))


class LocalChoiceNearMissTest(ShapeCase):
    """Item 6: the local's own limits; each base here prints something the row does not."""
    WORDS = strings_xml(s_py="pYes", s_qy="qYes", s_pn="pNo", s_qn="qNo")
    ROW_PIECES = {"if": "a", "then": {"if": "x", "then": ["p", "Yes"], "else": ["q", "Yes"]},
                  "else": {"if": "x", "then": ["p", "No"], "else": ["q", "No"]}}
    SENTENCE = '(x ? "p" : "q") + w'
    HEAD = "a ? (x ? T.sPy() : T.sQy()) : x ? T.sPn() : T.sQn()"

    def problems(self, body, edits=()):
        row = choice_row(self.line_of(body, self.SENTENCE), self.ROW_PIECES, ("s_py", "s_qy", "s_pn", "s_qn"), GRID)
        return self.attempt(body, [(self.SENTENCE, self.HEAD)] + list(edits), [row], self.WORDS)[0]

    def test_a_data_id_test_with_a_literal_receiver_passes(self):
        condition = '"eco".equals(id)'
        body = """    static String f(String id, boolean x) {
        String w = %s ? "Yes" : "No";
        return %s;
    }""" % (condition, self.SENTENCE)
        pieces = dict(self.ROW_PIECES, **{"if": condition})
        row = choice_row(self.line_of(body, self.SENTENCE), pieces, ("s_py", "s_qy", "s_pn", "s_qn"), GRID)
        problems = self.attempt(body, [(self.SENTENCE, self.HEAD.replace("a ?", condition + " ?", 1)),
                                       ('String w = %s ? "Yes" : "No";\n' % condition, "")], [row], self.WORDS)[0]
        self.assertEqual(problems, [])

    def test_a_condition_changed_after_the_local_is_refused(self):
        self.assertTrue(self.problems("""    static String f(boolean a, boolean x) {
        String w = a ? "Yes" : "No";
        a = !a;
        return %s;
    }""" % self.SENTENCE))

    def test_a_local_in_a_closed_block_is_not_the_field_the_sentence_reads(self):
        self.assertTrue(self.problems("""    static String w = "Field";
    static String f(boolean a, boolean x) {
        { String w = a ? "Yes" : "No"; use(w); }
        return %s;
    }
    static void use(String u) { }""" % self.SENTENCE))

    def test_a_local_with_a_second_declarator_is_refused(self):
        self.assertTrue(self.problems("""    static String f(boolean a, boolean x, Lock l) {
        String w = a ? "Yes" : "No", z = l.engage();
        return %s;
    }""" % self.SENTENCE, [('String w = a ? "Yes" : "No", z = l.engage();\n', "")]))

    def test_a_local_holding_more_than_strings_is_refused(self):
        body = """    static String f(boolean a, boolean x, String name) {
        String w = a ? name : "No";
        return %s;
    }""" % self.SENTENCE
        row = choice_row(self.line_of(body, self.SENTENCE), {"if": "a", "then": {"if": "x", "then": ["p", hole("name", 1)], "else": ["q", hole("name", 1)]},
                                                             "else": {"if": "x", "then": ["p", "No"], "else": ["q", "No"]}},
                         ("s_py", "s_qy", "s_pn", "s_qn"), GRID)
        problems = self.attempt(body, [(self.SENTENCE, "a ? (x ? T.sPy(name) : T.sQy(name)) : x ? T.sPn() : T.sQn()"),
                                       ('String w = a ? name : "No";\n', "")], [row],
                                strings_xml(s_py="p%1$s", s_qy="q%1$s", s_pn="pNo", s_qn="qNo"))[0]
        self.assertTrue(problems)

    def test_a_string_half_gone_leaves_the_local_with_no_row(self):
        self.assertTrue(self.problems("""    static String f(boolean a, boolean x) {
        String w = a ? "Yes" : "No";
        return %s;
    }""" % self.SENTENCE, [('a ? "Yes" : "No"', 'a ? "Oui" : "No"')]))


class PluralBranchNearMissTest(ShapeCase):
    def test_an_other_only_branch_does_not_print_the_bases_one(self):
        body = """    static String f(boolean h, int n) {
        return (h ? (n == 1 ? "1 try" : n + " tries") : "None");
    }"""
        strings = """<resources><plurals name="s_tr"><item quantity="other">%1$d tries</item></plurals><string name="s_none">None</string></resources>\n"""
        row = choice_row(self.line_of(body, "(h ?"), {"if": "h", "then": {"other": [count(), " tries"]}, "else": ["None"]}, ("s_tr", "s_none"), GRID)
        self.assertTrue(self.attempt(body, [('(h ? (n == 1 ? "1 try" : n + " tries") : "None")', "h ? T.sTr(n, n) : T.sNone()")], [row], strings)[0])

    def test_a_count_filled_into_an_other_form_is_not_a_literal(self):
        # The base's other branch spells "1 tries"; the key's placeholder prints the real count there. (A %1$d is
        # LedgerTest's: Python renders only %s templates.)
        body = """    static String f(boolean h, int n) {
        return (h ? (n == 1 ? n + " try" : "1 tries") : "None");
    }"""
        strings = """<resources><plurals name="s_tr"><item quantity="one">%1$d try</item><item quantity="other">%1$s tries</item></plurals><string name="s_none">None</string></resources>\n"""
        row = choice_row(self.line_of(body, "(h ?"), {"if": "h", "then": {"one": [count(), " try"], "other": ["1 tries"]}, "else": ["None"]}, ("s_tr", "s_none"), GRID)
        self.assertTrue(self.attempt(body, [('(h ? (n == 1 ? n + " try" : "1 tries") : "None")', "h ? T.sTr(n, n) : T.sNone()")], [row], strings)[0])

    def test_two_counts_in_a_branch_are_refused(self):
        row = choice_row(4, {"if": "h", "then": {"other": [count("n", 1), " of ", count("m", 2)]}, "else": ["None"]}, ("s_tr", "s_none"), GRID)
        with self.assertRaises(ledger_check.LedgerError):
            ledger_check.parse_ledger(HEADER + row + "\n")

    def test_a_count_read_twice_with_an_effect_is_refused(self):
        row = choice_row(4, {"if": "h", "then": {"other": [count("it.next()"), " tries"]}, "else": ["None"]}, ("s_tr", "s_none"), GRID)
        with self.assertRaises(ledger_check.LedgerError):
            ledger_check.parse_ledger(HEADER + row + "\n")


class RepeatableQuantityTest(unittest.TestCase):
    def test_plain_reads_repeat(self):
        for source in ("n", "rides.size()", "stats.count", "n + 1", "Math.round(x)", "a[i]", "a == b ? 1 : 2"):
            self.assertTrue(ledger_check.repeatable_quantity(source), source)

    def test_anything_with_an_effect_or_an_unknown_call_does_not(self):
        for source in ("it.next()", "n++", "--n", "n += 1", "n = 2", "bump()", "a.b().size()"):
            self.assertFalse(ledger_check.repeatable_quantity(source), source)


class ScopeTest(unittest.TestCase):
    def scope(self, source, name, mark="MARK"):
        tokens = ledger_check.l10nlib.lex_java(source)
        before = [t.value for t in tokens].index(mark)
        return ledger_check._declaration(tokens, name, 0, before) is not None

    def test_a_declaration_in_scope(self):
        self.assertTrue(self.scope("class C { void m(boolean a) { MARK(); } }", "a"))
        self.assertTrue(self.scope("class C { void m(boolean a) throws E, F { x(); MARK(); } }", "a"))
        self.assertTrue(self.scope("class C { void m() { boolean a = true; { MARK(); } } }", "a"))
        self.assertTrue(self.scope("class C { void m(int[] all) { for (int a : all) { MARK(); } } }", "a"))
        self.assertTrue(self.scope("class C { void m(int[] all) { for (int a : all) MARK(); } }", "a"))
        self.assertTrue(self.scope("class C { void m() { run((String a) -> { MARK(); }); } }", "a"))
        self.assertTrue(self.scope("class C { void m() { try { x(); } catch (E a) { MARK(); } } }", "a"))

    def test_a_declaration_out_of_scope(self):
        self.assertFalse(self.scope("class C { void m() { { boolean a = true; } MARK(); } }", "a"))
        self.assertFalse(self.scope("class C { void m(int[] all) { for (int a : all) { x(); } MARK(); } }", "a"))
        self.assertFalse(self.scope("class C { void m(int[] all) { for (int a : all) x(); MARK(); } }", "a"))
        self.assertFalse(self.scope("class C { void m() { run((String a) -> { x(); }); MARK(); } }", "a"))
        self.assertFalse(self.scope("class C { void m() { try { x(); } catch (E a) { y(); } MARK(); } }", "a"))
        self.assertFalse(self.scope("class C { void m(boolean a) throws E { } void n() { MARK(); } }", "a"))
        self.assertFalse(self.scope("class C { void m(boolean a) { } void n() { MARK(); } }", "a"))

    def test_no_declaration_at_all(self):
        self.assertFalse(self.scope("class C { void m() { a = true; MARK(); } }", "a"))
        self.assertFalse(self.scope("class C { void m() { outer: while (x) { continue a; MARK(); } } }", "a"))
        self.assertFalse(self.scope("class C { void m() { else_(); if (y) z(); else a = true; MARK(); } }", "a"))


class NoFactFromAFieldTest(unittest.TestCase):
    def test_a_field_is_not_stable(self):
        tokens = ledger_check.l10nlib.lex_java("class C { void m(boolean a) { if (f && a) { x(); } } }")
        first = [t.value for t in tokens].index("f")
        region = ledger_check._method_region(tokens, first)
        self.assertFalse(ledger_check._stable_condition(tokens, first, first, region, first))
        self.assertTrue(ledger_check._stable_condition(tokens, first + 2, first + 2, region, first + 2))

    def test_the_facts_combine_as_java_does(self):
        lex = ledger_check.l10nlib.lex_java
        facts = {}
        ledger_check.assert_fact(lex("!a || b"), False, facts)
        self.assertIs(ledger_check.evaluate_condition(lex("a"), facts, {}), True)
        self.assertIs(ledger_check.evaluate_condition(lex("b"), facts, {}), False)
        self.assertIs(ledger_check.evaluate_condition(lex("b && c"), facts, {}), False)
        self.assertIs(ledger_check.evaluate_condition(lex("b || c"), facts, {}), None)
        self.assertIs(ledger_check.evaluate_condition(lex("c != null"), {("c", "==", "null"): True}, {}), False)
        # a != b && z being false says nothing about a == b && z.
        facts = {}
        ledger_check.assert_fact(lex("a != b && z"), False, facts)
        self.assertIs(ledger_check.evaluate_condition(lex("a == b && z"), facts, {}), None)
        self.assertIs(ledger_check.evaluate_condition(lex("a != b && z"), facts, {}), False)
        self.assertIs(ledger_check.evaluate_condition(lex("c != null"), {("a",): False}, {"c": ["a"]}), False)
        self.assertIs(ledger_check.evaluate_condition(lex("c != null"), {("a",): True}, {"c": ["a"]}), None)


if __name__ == "__main__":
    unittest.main()
