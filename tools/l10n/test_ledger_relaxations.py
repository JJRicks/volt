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
