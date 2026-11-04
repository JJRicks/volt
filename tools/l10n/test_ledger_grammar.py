"""G1 for the wave-1 grammar (gates round 3b): honest conversions the batches B3-B10 could not record.

Each form is allowed only in its exact shape and listed for the reviewer; the careless variants fail.
"""

import os
import sys
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ledger_check  # noqa: E402
import gen_catalog  # noqa: E402
from repo_fixture import TempRepo, java_class  # noqa: E402

HEADER = "\t".join(ledger_check.HEADER) + "\n"
APP_NAME = "<resources><string name=\"app_name\">Volt</string></resources>\n"
PKG = "src/com/jjricks/volt/"


class GrammarCase(unittest.TestCase):
    FILES = {}
    STRINGS = None

    def setUp(self):
        self.repo = TempRepo()
        for path, text in self.FILES.items():
            self.repo.write(path, text)
        self.repo.write("res/values/strings.xml", APP_NAME)
        self.base = self.repo.commit("base")
        if self.STRINGS:
            self.repo.write("res/values/strings_test.xml", self.STRINGS)

    def tearDown(self):
        self.repo.close()

    def text(self, path):
        with open(self.repo.path(path), encoding="utf-8") as handle:
            return handle.read()

    def edit(self, path, old, new):
        text = self.text(path)
        self.assertIn(old, text)
        self.repo.write(path, text.replace(old, new))

    def check(self, rows, ledger="b9"):
        self.repo.write("l10n/ledger/%s.tsv" % ledger, HEADER + "\n".join(rows) + "\n")
        head = self.repo.commit("head")
        return ledger_check.run(self.base, head, cwd=self.repo.root)

    def assertFails(self, rows, needle):
        problems, warnings, _ = self.check(rows)
        self.assertTrue(any(needle in p for p in problems), "%r not in %s" % (needle, problems))
        return problems, warnings


# ---------------------------------------------------------------------------
# 1. A throw switching to a subclass that carries catalog text
# ---------------------------------------------------------------------------

TX = PKG + "Tx.java"
BACKUP_EXCEPTION = PKG + "BackupException.java"
SETUP_EXCEPTION = PKG + "SetupException.java"
USER_FACING = PKG + "UserFacingException.java"

BACKUP_EXCEPTION_TEXT = """package com.jjricks.volt;

final class BackupException extends java.io.IOException {
    enum Code { CHANGED, NO_SPACE, OTHER }
    static final Code DEFAULT = Code.OTHER;
    private final Code code;
    private final Msg msg;
    BackupException(Code code, Msg msg) { super(msg.english()); this.code = code; this.msg = msg; }
    BackupException(Msg msg) { this(Code.OTHER, msg); }
    BackupException(Code code, Msg msg, Throwable cause) { super(msg.english(), cause); this.code = code; this.msg = msg; }
    Code code() { return code; }
    Msg msg() { return msg; }
}
"""

USER_FACING_TEXT = """package com.jjricks.volt;

public class UserFacingException extends RuntimeException {
    private final Msg msg;
    public UserFacingException(Msg msg) { super(english(msg)); this.msg = msg; }
    private static String english(Msg msg) {
        if (msg == null) throw new NullPointerException("msg");
        return msg.english();
    }
    public Msg msg() { return msg; }
}
"""

SETUP_EXCEPTION_TEXT = """package com.jjricks.volt;

final class SetupException extends IllegalArgumentException {
    private final Msg msg;
    SetupException(Msg msg) { super(msg.english()); this.msg = msg; }
    Msg msg() { return msg; }
}
"""


class ExceptionRetypeTest(GrammarCase):
    FILES = {TX: java_class("Tx", """    static void check(long free) throws java.io.IOException {
        if (free < 10) throw new IOException("Not enough free storage");
    }
    static void copy(java.io.File f) throws java.io.IOException {
        try { f.length(); } catch (RuntimeException e) { throw new IOException("Copy failed", e); }
    }
    static void parse(String hex) { if (hex.length() != 12) throw new IllegalArgumentException("Need 12 hex characters"); }
    static void require(boolean ok) { if (!ok) throw new RuntimeException("Unlock first"); }"""),
             USER_FACING: USER_FACING_TEXT}
    STRINGS = ('<resources><string name="backup_no_space">Not enough free storage</string>'
               '<string name="backup_copy_failed">Copy failed</string>'
               '<string name="setup_need_hex">Need 12 hex characters</string>'
               '<string name="controls_unlock_first">Unlock first</string></resources>\n')
    NO_SPACE = TX + ':5\t["Not enough free storage"]\tbackup_no_space\texact\t'
    COPY = TX + ':8\t["Copy failed"]\tbackup_copy_failed\texact\t'
    HEX = TX + ':10\t["Need 12 hex characters"]\tsetup_need_hex\texact\t'
    UNLOCK = TX + ':11\t["Unlock first"]\tcontrols_unlock_first\texact\t'

    def convert(self, call='new BackupException(BackupException.Code.NO_SPACE, M.backupNoSpace())',
                exception=BACKUP_EXCEPTION_TEXT):
        self.repo.write(BACKUP_EXCEPTION, exception)
        self.edit(TX, 'new IOException("Not enough free storage")', call)

    def test_honest_switches_pass_and_are_listed(self):
        for call in ('new BackupException(BackupException.Code.NO_SPACE, M.backupNoSpace())',
                     'new BackupException(Code.NO_SPACE, M.backupNoSpace())',
                     'new BackupException(M.backupNoSpace())',  # this(Code.OTHER, msg)
                     'new BackupException(BackupException.DEFAULT, M.backupNoSpace())'):
            with self.subTest(call=call):
                self.repo.write(TX, self.FILES[TX])
                self.convert(call)
                problems, warnings, _ = self.check([self.NO_SPACE])
                self.assertEqual(problems, [])
                shown = [w for w in warnings if "BackupException extends IOException" in w]
                self.assertEqual(len(shown), 1, warnings)
                self.assertIn('`new IOException ( "Not enough free storage" )` becomes `%s`' % " ".join(
                    t.raw for t in ledger_check._Lexed(call).tokens), shown[0])
                self.assertIn("super ( msg . english ( ) )", shown[0])
                self.assertIn("print the new class name BackupException", shown[0])
                self.base = self.repo.commit("reset")
                self.repo.write("l10n/ledger/b9.tsv", HEADER)
                self.repo.write(TX, self.FILES[TX])
                self.base = self.repo.commit("reset to the original")

    def test_cause_is_kept_and_other_jdk_types(self):
        self.repo.write(BACKUP_EXCEPTION, BACKUP_EXCEPTION_TEXT)
        self.repo.write(SETUP_EXCEPTION, SETUP_EXCEPTION_TEXT)
        self.edit(TX, 'new IOException("Copy failed", e)',
                  'new BackupException(BackupException.Code.OTHER, M.backupCopyFailed(), e)')
        self.edit(TX, 'new IllegalArgumentException("Need 12 hex characters")', 'new SetupException(M.setupNeedHex())')
        self.edit(TX, 'new RuntimeException("Unlock first")', 'new UserFacingException(M.controlsUnlockFirst())')
        problems, warnings, _ = self.check([self.COPY, self.HEX, self.UNLOCK])
        self.assertEqual(problems, [])
        self.assertTrue(any("SetupException extends IllegalArgumentException" in w for w in warnings), warnings)
        self.assertTrue(any("UserFacingException extends RuntimeException" in w and "super ( english ( msg ) )" in w
                            for w in warnings), warnings)

    def test_careless_variants_fail(self):
        cases = [
            # not a subclass: catch (IOException) would miss it
            (BACKUP_EXCEPTION_TEXT.replace("extends java.io.IOException", "extends Exception"), None,
             "does not extend IOException"),
            # getMessage() would be the rendered (current-language) text
            (BACKUP_EXCEPTION_TEXT.replace("super(msg.english()); this.code", "super(msg.render()); this.code"), None,
             "passes `msg . render ( )` as the message"),
            (BACKUP_EXCEPTION_TEXT.replace("super(msg.english()); this.code", "super(String.valueOf(msg)); this.code"),
             None, "getMessage() stays the English text only when"),
            # no message at all
            (BACKUP_EXCEPTION_TEXT.replace("{ super(msg.english()); this.code = code; this.msg = msg; }\n    BackupException(Msg",
                                           "{ this.code = code; this.msg = msg; }\n    BackupException(Msg"), None,
             "does not start with super(...) or this(...)"),
            # an override prints something else
            (BACKUP_EXCEPTION_TEXT.replace("    Msg msg() {", "    @Override public String getMessage() { return msg.render(); }\n"
                                           "    Msg msg() {"), None, "declares getMessage"),
            # rendered String instead of a Msg: no Msg constructor
            (None, 'new BackupException(BackupException.Code.NO_SPACE, T.backupNoSpace())', "a deferred M.<key>(...)"),
            # an argument that is not a constant of the class
            (None, 'new BackupException(Tx.code(), M.backupNoSpace())', "is not a constant of BackupException"),
            (None, 'new BackupException(Other.Code.NO_SPACE, M.backupNoSpace())', "is not a constant of BackupException"),
            (None, 'new BackupException(BackupException.Code.FULL, M.backupNoSpace())',
             "is not a constant of BackupException"),
            # a class src does not declare
            (None, 'new java.nio.file.FileSystemException(M.backupNoSpace())', "must be one class declared in src"),
        ]
        for exception, call, needle in cases:
            with self.subTest(needle=needle, call=call):
                self.convert(call or 'new BackupException(BackupException.Code.NO_SPACE, M.backupNoSpace())',
                             exception or BACKUP_EXCEPTION_TEXT)
                self.assertFails([self.NO_SPACE], needle)
                self.repo.write("l10n/ledger/b9.tsv", HEADER)
                self.repo.write(TX, self.FILES[TX])
                self.repo.remove(BACKUP_EXCEPTION)
                self.base = self.repo.commit("reset to the original")

    def test_a_dropped_cause_or_a_non_exception_fails(self):
        self.repo.write(BACKUP_EXCEPTION, BACKUP_EXCEPTION_TEXT)
        self.edit(TX, 'new IOException("Copy failed", e)', 'new BackupException(BackupException.Code.OTHER, M.backupCopyFailed())')
        self.assertFails([self.COPY], "the other arguments must stay as they were")

    def test_same_type_needs_no_listing(self):
        # A conversion inside an unchanged new X(...) is an ordinary exact row: nothing extra listed.
        self.edit(TX, 'new RuntimeException("Unlock first")', 'new RuntimeException(T.controlsUnlockFirst())')
        problems, warnings, _ = self.check([self.UNLOCK])
        self.assertEqual(problems, [])
        self.assertFalse(any("extends" in w for w in warnings), warnings)


# ---------------------------------------------------------------------------
# 4. Locale.US becoming Locale.ROOT in a locale-independent String.format
# ---------------------------------------------------------------------------

TELEMETRY = PKG + "Telemetry.java"


class RootSwapTest(GrammarCase):
    FILES = {TELEMETRY: java_class("Telemetry", """    private static String statusKey(int cmd) { return String.format(Locale.US, "response_%02x", cmd); }
    static String id(int pid, String name) { return String.format(java.util.Locale.US, "%d-%s:%-4X", pid, name, pid); }
    static String temp(double c) { return String.format(Locale.US, "%.1f C", c); }
    static String count(int n) { return String.format(Locale.US, "%,d items", n); }
    static String day(java.util.Date d) { return String.format(Locale.US, "%tB", d); }
    static String upper(String s) { return s.toUpperCase(Locale.US) + "_raw"; }""")}

    def test_identical_output_swaps_pass_and_are_listed(self):
        self.edit(TELEMETRY, 'String.format(Locale.US, "response_%02x"', 'String.format(Locale.ROOT, "response_%02x"')
        self.edit(TELEMETRY, 'String.format(java.util.Locale.US, "%d-%s', 'String.format(java.util.Locale.ROOT, "%d-%s')
        problems, warnings, _ = self.check([])
        self.assertEqual(problems, [])
        shown = [w for w in warnings if "moves from Locale.US to Locale.ROOT" in w]
        self.assertEqual(len(shown), 2, warnings)
        self.assertIn('%s:4: `String . format ( Locale . ROOT , "response_%%02x" , cmd )`' % TELEMETRY, shown[0])

    def test_locale_dependent_patterns_and_other_swaps_fail(self):
        for old, new, needle in (
                ('Locale.US, "%.1f C"', 'Locale.ROOT, "%.1f C"', 'has %.1f'),
                ('Locale.US, "%,d items"', 'Locale.ROOT, "%,d items"', 'has %,d'),
                ('Locale.US, "%tB"', 'Locale.ROOT, "%tB"', 'has %tB'),
                # another locale, another call, or another change in the same call
                ('Locale.US, "response_%02x"', 'Locale.ENGLISH, "response_%02x"', 'adds `ENGLISH` and drops `US`'),
                ('toUpperCase(Locale.US)', 'toUpperCase(Locale.ROOT)', 'adds `ROOT` and drops `US`'),
                ('Locale.US, "response_%02x", cmd)', 'Locale.ROOT, "response_%02x", cmd & 0xff)', 'adds `ROOT & 0xff` and drops `US`')):
            with self.subTest(new=new):
                self.edit(TELEMETRY, old, new)
                self.assertFails([], needle)
                self.repo.write(TELEMETRY, self.FILES[TELEMETRY])
                self.base = self.repo.commit("reset to the original")


# ---------------------------------------------------------------------------
# 3. A private method's String parameter retyped for the catalog calls its callers pass
# ---------------------------------------------------------------------------

BLE = PKG + "BleController.java"


class ParameterRetypeTest(GrammarCase):
    FILES = {BLE: java_class("BleController", """    private void simulationUnavailable(String action){notice(action+" is unavailable in simulation. No scooter command was sent.");}
    public void bond(boolean sim){if(sim){simulationUnavailable("Bluetooth pairing");return;}notice("Pair now");}
    public void identify(boolean sim){if(sim){simulationUnavailable("Identify model");return;}}
    void notice(CharSequence text){}""")}
    STRINGS = ('<resources><string name="ble_simulation_unavailable">%1$s is unavailable in simulation. No scooter '
               'command was sent.</string><string name="ble_sim_pairing">Bluetooth pairing</string>'
               '<string name="ble_sim_identify">Identify model</string></resources>\n')
    ROWS = [BLE + ':4\t[{"hole": "action", "arg": 1, "type": "s"}, " is unavailable in simulation. No scooter command '
                  'was sent."]\tble_simulation_unavailable\ttemplate\t',
            BLE + ':5\t["Bluetooth pairing"]\tble_sim_pairing\texact\t',
            BLE + ':6\t["Identify model"]\tble_sim_identify\texact\t']
    METHOD = ('private void simulationUnavailable(String action){notice(action+" is unavailable in simulation. No scooter '
              'command was sent.");}')

    def convert(self, kind="Msg", modifiers="private ", pairing="M.bleSimPairing()", identify="M.bleSimIdentify()"):
        original = self.FILES[BLE].replace("private void simulationUnavailable", modifiers + "void simulationUnavailable")
        self.repo.write(BLE, original)
        self.base = self.repo.commit("modifiers")
        text = original.replace(self.METHOD.replace("private ", modifiers), "%svoid simulationUnavailable(%s action)"
                                "{notice(M.bleSimulationUnavailable(action));}" % (modifiers, kind))
        text = text.replace('simulationUnavailable("Bluetooth pairing")', "simulationUnavailable(%s)" % pairing)
        if identify is not None:
            text = text.replace('simulationUnavailable("Identify model")', "simulationUnavailable(%s)" % identify)
        self.repo.write(BLE, text)

    def test_retype_with_catalog_callers_passes_and_is_listed(self):
        for kind, pairing in (("Msg", "M.bleSimPairing()"), ("CharSequence", "T.bleSimPairing()")):
            with self.subTest(kind=kind):
                self.convert(kind, pairing=pairing)
                problems, warnings, _ = self.check(self.ROWS)
                self.assertEqual(problems, [])
                shown = [w for w in warnings if "changes from String to %s" % kind in w]
                self.assertEqual(len(shown), 1, warnings)
                self.assertIn("every call passes a catalog call: line 5 `", shown[0])
                self.assertIn("simulationUnavailable ( %s )" % " ".join(t.raw for t in ledger_check._Lexed(pairing).tokens),
                              shown[0])
                self.assertIn("line 6 `", shown[0])
                self.assertIn("notice ( M . bleSimulationUnavailable ( action ) )", shown[0])
                self.repo.write("l10n/ledger/b9.tsv", HEADER)
                self.repo.write(BLE, self.FILES[BLE])
                self.base = self.repo.commit("reset")

    def test_careless_variants_fail(self):
        other = PKG + "Screen.java"
        cases = [
            (dict(identify=None), self.ROWS[:2], "its argument is not an M catalog call"),
            (dict(identify="T.bleSimIdentify()"), self.ROWS, "its argument is not an M catalog call"),
            (dict(modifiers="public "), self.ROWS, "the method is public"),
            (dict(modifiers=""), self.ROWS, "Screen.java name simulationUnavailable(...)"),
            (dict(kind="Object"), self.ROWS, "adds `Object` and drops `String`"),
        ]
        for options, rows, needle in cases:
            with self.subTest(needle=needle):
                self.repo.write(other, java_class("Screen", "    void show(BleController b) { b.simulationUnavailable(\"x\"); }"))
                self.convert(**options)
                self.assertFails(rows, needle)
                self.repo.write("l10n/ledger/b9.tsv", HEADER)
                self.repo.write(BLE, self.FILES[BLE])
                self.base = self.repo.commit("reset")

    def test_an_overload_of_the_same_arity_fails(self):
        self.repo.write(BLE, self.FILES[BLE].replace("    void notice(CharSequence text){}",
                                                     "    void notice(CharSequence text){}\n"
                                                     "    private void simulationUnavailable(int code){}"))
        self.base = self.repo.commit("an overload")
        self.edit(BLE, "String action){notice(action+\" is unavailable in simulation. No scooter command was sent.\");}",
                  "Msg action){notice(M.bleSimulationUnavailable(action));}")
        self.edit(BLE, 'simulationUnavailable("Bluetooth pairing")', "simulationUnavailable(M.bleSimPairing())")
        self.edit(BLE, 'simulationUnavailable("Identify model")', "simulationUnavailable(M.bleSimIdentify())")
        self.assertFails(self.ROWS, "is declared more than once in the file")


# ---------------------------------------------------------------------------
# 2. A choice between whole templates
# ---------------------------------------------------------------------------

SERVICE = PKG + "Service.java"


def hole(source, arg, kind="s"):
    return {"hole": source, "arg": arg, "type": kind}


def choice_row(line, pieces, keys, path=SERVICE):
    import json
    return "%s:%d\t%s\t%s\tchoice\t" % (path, line, json.dumps(pieces, ensure_ascii=False), ",".join(keys))


MEASURED = ["Measured this charge. ", "Charging resumes at ", hole("t1", 1), " to finish by ", hole("t2", 2), "."]
PLANNED = ["Planned for a fresh charge. ", "Charging resumes at ", hole("t1", 1), " to finish by ", hole("t2", 2), "."]


class ChoiceRowTest(GrammarCase):
    FILES = {SERVICE: java_class("Service", """    static String done(boolean armed, boolean measured, String t1, String t2, String result) {
        return armed ? (measured ? "Measured this charge. " : "Planned for a fresh charge. ") + "Charging resumes at " + t1 + " to finish by " + t2 + "."
            : result == null ? "Charge done by is off." : result + ".";
    }
    static String card(boolean keeps, String label) { return ui((keeps ? "Try · " : "Test · ") + label, 16); }
    static String state(String name, boolean open) { return name + (open ? ", expanded" : ", collapsed"); }
    static String question(String value) { return "Is the tail light " + value.toLowerCase(java.util.Locale.ROOT) + "?"; }
    static String units(String value) { return "Does the dashboard show " + (value.contains("mph") ? "mph" : "km/h") + "?"; }
    static String ui(CharSequence s, int size) { return s.toString(); }
    static final String ON = "On", OFF = "Off";""")}
    STRINGS = """<resources>
    <string name="svc_measured">Measured this charge. Charging resumes at %1$s to finish by %2$s.</string>
    <string name="svc_planned">Planned for a fresh charge. Charging resumes at %1$s to finish by %2$s.</string>
    <string name="compat_try">Try · %1$s</string>
    <string name="compat_test">Test · %1$s</string>
    <string name="state_expanded">%1$s, expanded</string>
    <string name="state_collapsed">%1$s, collapsed</string>
    <string name="q_on">Is the tail light on?</string>
    <string name="q_off">Is the tail light off?</string>
    <string name="q_any">Is the tail light %1$s?</string>
    <string name="units_mph_q">Does the dashboard show mph?</string>
    <string name="units_kmh_q">Does the dashboard show km/h?</string>
</resources>
"""
    DONE = choice_row(5, {"if": "measured", "then": MEASURED, "else": PLANNED}, ["svc_measured", "svc_planned"])
    CARD = choice_row(8, {"if": "keeps", "then": ["Try · ", hole("label", 1)], "else": ["Test · ", hole("label", 1)]},
                      ["compat_try", "compat_test"])
    STATE = choice_row(9, {"if": "open", "then": [hole("name", 1), ", expanded"],
                           "else": [hole("name", 1), ", collapsed"]}, ["state_expanded", "state_collapsed"])
    QUESTION = choice_row(10, {"if": '"On".equals(value)', "then": ["Is the tail light on?"],
                               "else": {"if": '"Off".equals(value)', "then": ["Is the tail light ", "off", "?"],
                                        "else": ["Is the tail light ", hole("value.toLowerCase(java.util.Locale.ROOT)", 1),
                                                 "?"]}}, ["q_on", "q_off", "q_any"])
    UNITS = choice_row(11, {"if": 'value.contains("mph")', "then": ["Does the dashboard show mph?"],
                            "else": ["Does the dashboard show ", "km/h", "?"]}, ["units_mph_q", "units_kmh_q"])

    def convert_all(self):
        self.edit(SERVICE, '(measured ? "Measured this charge. " : "Planned for a fresh charge. ") + "Charging resumes at " + '
                           't1 + " to finish by " + t2 + "."', "(measured ? T.svcMeasured(t1, t2) : T.svcPlanned(t1, t2))")
        self.edit(SERVICE, 'ui((keeps ? "Try · " : "Test · ") + label, 16)',
                  "ui(keeps ? T.compatTry(label) : T.compatTest(label), 16)")
        self.edit(SERVICE, 'name + (open ? ", expanded" : ", collapsed")', "open ? M.stateExpanded(name) : M.stateCollapsed(name)")
        self.edit(SERVICE, '"Is the tail light " + value.toLowerCase(java.util.Locale.ROOT) + "?"',
                  '"On".equals(value) ? T.qOn() : "Off".equals(value) ? T.qOff() : '
                  'T.qAny(value.toLowerCase(java.util.Locale.ROOT))')
        self.edit(SERVICE, '"Does the dashboard show " + (value.contains("mph") ? "mph" : "km/h") + "?"',
                  'value.contains("mph") ? T.unitsMphQ() : T.unitsKmhQ()')

    def test_whole_template_choices_pass_and_are_listed(self):
        self.convert_all()
        problems, warnings, _ = self.check([self.DONE, self.CARD, self.STATE, self.QUESTION, self.UNITS])
        self.assertEqual(problems, [])
        shown = [w for w in warnings if "choice row at" in w]
        self.assertEqual(len(shown), 5, warnings)
        self.assertIn("becomes `( measured ? T . svcMeasured ( t1 , t2 ) : T . svcPlanned ( t1 , t2 ) )`", shown[0])
        self.assertIn('data id(s) tested: "Off" (%s:13 at the merge base), "On" (%s:13 at the merge base)' % (
            SERVICE, SERVICE), shown[3])
        # The other branch of the outer ternary is untouched English, still in the source.
        self.assertIn('"Charge done by is off."', self.text(SERVICE))

    def test_careless_variants_fail(self):
        swapped_keys = choice_row(5, {"if": "measured", "then": MEASURED, "else": PLANNED}, ["svc_planned", "svc_measured"])
        dropped = choice_row(5, {"if": "measured", "then": MEASURED, "else": PLANNED[:1] + PLANNED[2:]},
                             ["svc_measured", "svc_planned"])
        negated = choice_row(5, {"if": "!measured", "then": PLANNED, "else": MEASURED}, ["svc_planned", "svc_measured"])
        cases = [
            # keys swapped in the row: each key's English is the other branch
            (None, swapped_keys, "choice branch svc_planned renders"),
            # calls swapped in head
            ("(measured ? T.svcPlanned(t1, t2) : T.svcMeasured(t1, t2))", self.DONE, "must become a ternary"),
            # holes swapped in a call
            ("(measured ? T.svcMeasured(t2, t1) : T.svcPlanned(t1, t2))", self.DONE, "must become a ternary"),
            # a shared piece dropped from one branch
            (None, dropped, "but the base expression with !(measured) is"),
            # a condition the base does not have
            ("(!measured ? T.svcPlanned(t1, t2) : T.svcMeasured(t1, t2))", negated,
             "is neither a choice of the base expression"),
            # a shared piece kept outside the choice
            ('(measured ? T.svcMeasured(t1, t2) : T.svcPlanned(t1, t2)) + "."', self.DONE, "still there at head"),
        ]
        for head, row, needle in cases:
            with self.subTest(needle=needle, head=head):
                self.edit(SERVICE, '(measured ? "Measured this charge. " : "Planned for a fresh charge. ") + "Charging '
                                   'resumes at " + t1 + " to finish by " + t2 + "."',
                          head or "(measured ? T.svcMeasured(t1, t2) : T.svcPlanned(t1, t2))")
                self.assertFails([row], needle)
                self.repo.write("l10n/ledger/b9.tsv", HEADER)
                self.repo.write(SERVICE, self.FILES[SERVICE])
                self.base = self.repo.commit("reset")

    def test_careless_id_tests_fail(self):
        unknown = choice_row(10, {"if": '"Dim".equals(value)', "then": ["Is the tail light dim?"],
                                  "else": ["Is the tail light ", hole("value.toLowerCase(java.util.Locale.ROOT)", 1), "?"]},
                             ["q_on", "q_any"])
        wrong_case = choice_row(10, {"if": '"On".equals(value)', "then": ["Is the tail light On?"],
                                     "else": ["Is the tail light ", hole("value.toLowerCase(java.util.Locale.ROOT)", 1),
                                              "?"]}, ["q_on", "q_any"])
        unresolved = choice_row(11, {"if": '"On".equals(value)', "then": ["Does the dashboard show mph?"],
                                     "else": ["Does the dashboard show km/h?"]}, ["units_mph_q", "units_kmh_q"])
        for head, row, needle in (
                ('"Dim".equals(value) ? T.qOn() : T.qAny(value.toLowerCase(java.util.Locale.ROOT))', unknown,
                 'but "Dim" is no literal in src at the merge base'),
                ('"On".equals(value) ? T.qOn() : T.qAny(value.toLowerCase(java.util.Locale.ROOT))', wrong_case,
                 "but the base expression with"),
                ):
            with self.subTest(needle=needle):
                self.edit(SERVICE, '"Is the tail light " + value.toLowerCase(java.util.Locale.ROOT) + "?"', head)
                self.assertFails([row], needle)
                self.repo.write("l10n/ledger/b9.tsv", HEADER)
                self.repo.write(SERVICE, self.FILES[SERVICE])
                self.base = self.repo.commit("reset")
        self.edit(SERVICE, '"Does the dashboard show " + (value.contains("mph") ? "mph" : "km/h") + "?"',
                  '"On".equals(value) ? T.unitsMphQ() : T.unitsKmhQ()')
        self.assertFails([unresolved], "still holds the choice")

    def test_choice_rows_parse(self):
        rows = ledger_check.parse_ledger(HEADER + self.QUESTION + "\n")
        self.assertEqual(rows[0].kind, "choice")
        self.assertEqual(len(ledger_check.choice_leaves(rows[0].pieces)), 3)
        for bad, needle in ((self.QUESTION.replace("q_on,q_off,q_any", "q_on,q_any"), "3 branch(es) and 2 key(s)"),
                            (self.QUESTION.replace('"then"', '"than"', 1), "a choice row's pieces are"),
                            (self.QUESTION + "manual: hand", "takes no \"manual:\" note")):
            with self.assertRaises(ledger_check.LedgerError) as caught:
                ledger_check.parse_ledger(HEADER + bad + "\n")
            self.assertIn(needle, str(caught.exception))


class ChoiceTypeTest(GrammarCase):
    """A ternary's Java result type: char and number choices promote (5 + '*' is 47, 2 : 1.5 prints 2.0), so a
    choice row refuses them; a ternary with no text in its branches is one hole, passed as it is."""
    FILES = {SERVICE: java_class("Service", """    static String a(int n, boolean c) { return n + (c ? 1 : 2) + " items"; }
    static String b(int n, boolean c) { return n + (c ? '*' : '-') + " items"; }
    static String r(boolean fast) { return "Rate " + (fast ? 2 : 1.5) + "x"; }
    static String x(boolean c) { return "Mark " + (c ? 'a' : 'b'); }
    static String s(boolean fast, Integer v) { return (fast ? "Fast: " : "Slow: ") + (v == null ? 0 : v) + " sessions"; }
    static String i(boolean fast, String v) { return (fast ? "Fast " : "Slow ") + ("On".equals(v) ? 1 : 2); }""")}
    STRINGS = """<resources>
    <string name="k_one">%1$s%2$s items</string>
    <string name="k_two">%1$s%2$s items</string>
    <string name="k_star">%1$s* items</string>
    <string name="k_dash">%1$s- items</string>
    <string name="k_fast">Rate %1$sx</string>
    <string name="k_slow">Rate %1$sx</string>
    <string name="k_ma">Mark a</string>
    <string name="k_mb">Mark b</string>
    <string name="s_fast">Fast: %1$s sessions</string>
    <string name="s_slow">Slow: %1$s sessions</string>
    <string name="i_fast">Fast %1$s</string>
    <string name="i_slow">Slow %1$s</string>
</resources>
"""
    SESSIONS = choice_row(8, {"if": "fast", "then": ["Fast: ", hole("v == null ? 0 : v", 1), " sessions"],
                              "else": ["Slow: ", hole("v == null ? 0 : v", 1), " sessions"]}, ["s_fast", "s_slow"])

    def test_a_value_ternary_is_one_hole(self):
        self.edit(SERVICE, '(fast ? "Fast: " : "Slow: ") + (v == null ? 0 : v) + " sessions"',
                  "fast ? T.sFast(v == null ? 0 : v) : T.sSlow((v == null ? 0 : v))")
        problems, warnings, _ = self.check([self.SESSIONS])
        self.assertEqual(problems, [])
        self.assertEqual(len([w for w in warnings if "choice row at" in w]), 1, warnings)

    def test_a_value_ternary_with_an_id_literal_is_not_copied(self):
        # A literal in a value ternary's condition stays one hole, but passing it to both calls copies the
        # literal, which G1 still refuses.
        self.edit(SERVICE, '(fast ? "Fast " : "Slow ") + ("On".equals(v) ? 1 : 2)',
                  'fast ? T.iFast("On".equals(v) ? 1 : 2) : T.iSlow("On".equals(v) ? 1 : 2)')
        ids = choice_row(9, {"if": "fast", "then": ["Fast ", hole('"On".equals(v) ? 1 : 2', 1)],
                             "else": ["Slow ", hole('"On".equals(v) ? 1 : 2', 1)]}, ["i_fast", "i_slow"])
        self.assertFails([ids], 'new literal "On"')

    def test_splitting_a_value_ternary_still_fails(self):
        self.edit(SERVICE, '(fast ? "Fast: " : "Slow: ") + (v == null ? 0 : v) + " sessions"',
                  "fast ? (v == null ? T.sFast(0) : T.sFast(v)) : T.sSlow(v == null ? 0 : v)")
        split = choice_row(8, {"if": "fast", "then": {"if": "v == null", "then": ["Fast: ", hole("0", 1), " sessions"],
                                                      "else": ["Fast: ", hole("v", 1), " sessions"]},
                               "else": ["Slow: ", hole("v == null ? 0 : v", 1), " sessions"]},
                           ["s_fast", "s_fast", "s_slow"])
        self.assertFails([split], "is neither a choice of the base expression")

    def test_char_and_number_choices_fail(self):
        cases = [
            ('n + (c ? 1 : 2) + " items"', "c ? T.kOne(n, 1) : T.kTwo(n, 2)",
             choice_row(4, {"if": "c", "then": [hole("n", 1), hole("1", 2), " items"],
                            "else": [hole("n", 1), hole("2", 2), " items"]}, ["k_one", "k_two"]),
             "adds numbers before its first string"),
            ("n + (c ? '*' : '-') + \" items\"", "c ? T.kStar(n) : T.kDash(n)",
             choice_row(5, {"if": "c", "then": [hole("n", 1), {"char": "*"}, " items"],
                            "else": [hole("n", 1), {"char": "-"}, " items"]}, ["k_star", "k_dash"]),
             "adds numbers before its first string"),
            ('"Rate " + (fast ? 2 : 1.5) + "x"', "fast ? T.kFast(2) : T.kSlow(1.5)",
             choice_row(6, {"if": "fast", "then": ["Rate ", hole("2", 1), "x"],
                            "else": ["Rate ", hole("1.5", 1), "x"]}, ["k_fast", "k_slow"]),
             "is neither a choice of the base expression"),
            ("\"Mark \" + (c ? 'a' : 'b')", "c ? T.kMa() : T.kMb()",
             choice_row(7, {"if": "c", "then": ["Mark ", {"char": "a"}], "else": ["Mark ", {"char": "b"}]},
                        ["k_ma", "k_mb"]),
             "chooses between char or number values"),
        ]
        for old, head, row, needle in cases:
            with self.subTest(old=old):
                self.edit(SERVICE, old, head)
                self.assertFails([row], needle)
                self.repo.write("l10n/ledger/b9.tsv", HEADER)
                self.repo.write(SERVICE, self.FILES[SERVICE])
                self.base = self.repo.commit("reset")


# ---------------------------------------------------------------------------
# 5. A plural row through a modelled helper: plural(n, "scooter")
# ---------------------------------------------------------------------------

ARCHIVE = PKG + "BackupArchive.java"
HELPER_BODY = 'static String plural(int count, String noun) { return count + " " + noun + (count == 1 ? "" : "s"); }'


def readings(noun="scooter", kind="d", model="count_noun", quantity=True, row_kind="plural", forms=("one", "other")):
    import json
    count = {"hole": "skipped", "arg": 1, "type": kind}
    if quantity:
        count["quantity"] = True
    helper = {"helper": "plural", "model": model, "args": [count, noun]}
    form = ["Readings for ", helper, " skipped · not restored"]
    pieces = form if row_kind != "plural" else {f: form for f in forms}
    return ARCHIVE + ":5\t%s\tbackup_readings_skipped\t%s\t" % (json.dumps(pieces, ensure_ascii=False), row_kind)


class HelperPluralTest(GrammarCase):
    FILES = {ARCHIVE: java_class("BackupArchive", """    static void plan(java.util.List<String> lines, int skipped) {
        if (skipped > 0) lines.add("Readings for " + plural(skipped, "scooter") + " skipped · not restored");
    }
    """ + HELPER_BODY)}
    STRINGS = """<resources><plurals name="backup_readings_skipped">
    <item quantity="one">Readings for %1$d scooter skipped · not restored</item>
    <item quantity="other">Readings for %1$d scooters skipped · not restored</item>
</plurals></resources>
"""

    def setUp(self):
        super().setUp()
        # These tests model the helper as if AndroidText already picked default English items by English rules
        # (the runtime track); DeviceRulesTest below covers this branch, where it does not.
        patcher = mock.patch.object(gen_catalog, ledger_check.PLURAL_RULES_FLAG, True, create=True)
        patcher.start()
        self.addCleanup(patcher.stop)

    def convert(self):
        self.edit(ARCHIVE, '"Readings for " + plural(skipped, "scooter") + " skipped · not restored"',
                  "T.backupReadingsSkipped(skipped, skipped)")

    def test_a_modelled_helper_passes_and_is_listed(self):
        self.convert()
        problems, warnings, summary = self.check([readings()])
        self.assertEqual(problems, [])
        shown = [w for w in warnings if '`plural(skipped, "scooter")` is modelled as count_noun' in w]
        self.assertEqual(len(shown), 1, warnings)
        self.assertIn('with the quantity skipped', shown[0])
        self.assertIn('one at 1: "Readings for 1 scooter skipped · not restored"', shown[0])
        self.assertIn('other at 2: "Readings for 2 scooters skipped · not restored"', shown[0])
        self.assertIn("3 literal(s) left", summary)

    def test_careless_variants_fail(self):
        self.convert()
        self.assertFails([readings(noun="ride")], "the pieces do not match the expression on line 5")

    def test_a_helper_that_is_not_the_model_fails(self):
        for body in (HELPER_BODY.replace('count + " " + noun', "count + noun"),
                     HELPER_BODY.replace("count == 1", "count <= 1"),
                     HELPER_BODY.replace("int count", "double count"),
                     HELPER_BODY + "\n    static String plural(long count, String noun) { return null; }"):
            with self.subTest(body=body):
                self.repo.write(ARCHIVE, self.FILES[ARCHIVE].replace(HELPER_BODY, body))
                self.repo.write("l10n/ledger/b9.tsv", HEADER)
                self.base = self.repo.commit("another helper")
                self.convert()
                self.assertFails([readings()], "helper plural")

    def test_a_wrong_form_fails_in_g1(self):
        # Review round 1: "1 scooters" for "1 scooter" passed when the helper sat in a template row, or its count
        # was no quantity; now the helper row is a plural whose forms G1 renders at 1, 0, 2 and 21.
        self.repo.write("res/values/strings_test.xml", self.STRINGS.replace("%1$d scooter skipped", "%1$d scooters skipped"))
        self.convert()
        self.assertFails([readings()], "backup_readings_skipped[one] at quantity 1 renders 'Readings for 1 scooters")
        self.repo.write("res/values/strings_test.xml", self.STRINGS.replace("%1$d scooters skipped", "%1$d scooter skipped"))
        self.assertFails([readings()], "[other] at quantity 0 renders 'Readings for 0 scooter")

    def test_a_form_the_catalog_lacks_fails_in_g1(self):
        # Review round 3: a helper row's one form passed G1 when the catalog plural had no one item (only G2 saw it).
        self.repo.write("res/values/strings_test.xml", self.STRINGS.replace(
            '    <item quantity="one">Readings for %1$d scooter skipped · not restored</item>\n', ""))
        self.convert()
        self.assertFails([readings()], "plural backup_readings_skipped has no one item")

    def test_a_template_row_with_a_helper_fails(self):
        self.repo.write("res/values/strings_test.xml", """<resources>
    <string name="backup_readings_skipped">Readings for %1$d scooters skipped · not restored</string>
</resources>
""")
        self.edit(ARCHIVE, '"Readings for " + plural(skipped, "scooter") + " skipped · not restored"',
                  "T.backupReadingsSkipped(skipped)")
        for row, needle in ((readings(row_kind="template"), "a helper piece belongs only in a plural row"),
                            (readings(row_kind="template", quantity=False), "must be the plural's quantity")):
            with self.subTest(row=row):
                self.assertFails([row], needle)

    def test_helper_pieces_parse(self):
        for row, needle in ((readings(kind="s"), "whole-number (type d)"), (readings(model="plural_s"), "unknown helper model"),
                            (readings(quantity=False), "must be the plural's quantity"),
                            (readings(forms=("other",)), "has one and other forms"),
                            (readings(row_kind="template"), "belongs only in a plural row")):
            with self.assertRaises(ledger_check.LedgerError) as caught:
                ledger_check.parse_ledger(HEADER + row + "\n")
            self.assertIn(needle, str(caught.exception))


class NounLocalTest(GrammarCase):
    """Review round 2: a noun picked by a ternary on an earlier line (BackupArchive 710-714) has no row yet; the
    whole-sentence conversion fails, both as a choice row and as a helper with a hole for the noun."""
    FILES = {ARCHIVE: java_class("BackupArchive", """    static void plan(java.util.List<String> lines, boolean charges, int notRestored) {
        String noun = charges ? "charge" : "ride";
        if (notRestored > 0) lines.add(plural(notRestored, noun) + " skipped · scooter not restored");
    }
    """ + HELPER_BODY)}
    STRINGS = """<resources>
<plurals name="charges_not_restored"><item quantity="one">%1$d charge skipped · scooter not restored</item>
<item quantity="other">%1$d charges skipped · scooter not restored</item></plurals>
<plurals name="rides_not_restored"><item quantity="one">%1$d ride skipped · scooter not restored</item>
<item quantity="other">%1$d rides skipped · scooter not restored</item></plurals>
</resources>
"""

    def setUp(self):
        super().setUp()
        patcher = mock.patch.object(gen_catalog, ledger_check.PLURAL_RULES_FLAG, True, create=True)
        patcher.start()
        self.addCleanup(patcher.stop)

    def test_the_whole_sentence_conversion_has_no_row(self):
        import json
        self.edit(ARCHIVE, 'lines.add(plural(notRestored, noun) + " skipped · scooter not restored")',
                  "lines.add(charges ? T.chargesNotRestored(notRestored, notRestored) : "
                  "T.ridesNotRestored(notRestored, notRestored))")
        count = {"hole": "notRestored", "arg": 1, "type": "d", "quantity": True}
        form = [{"helper": "plural", "model": "count_noun", "args": [count, {"hole": "noun", "arg": 2, "type": "s"}]},
                " skipped · scooter not restored"]
        row = ARCHIVE + ":6\t%s\tcharges_not_restored\tplural\t" % json.dumps({"one": form, "other": form},
                                                                                ensure_ascii=False)
        problems = self.check([row])[0]
        self.assertTrue(any("charges_not_restored[one] at quantity 1 renders '1 charge skipped" in p and
                            "but the base expression prints '1 \\x01B skipped" in p for p in problems), problems)
        branch = {"one": [count, " charge skipped"], "other": [count, " charges skipped"]}
        choice = ARCHIVE + ":6\t%s\tcharges_not_restored,rides_not_restored\tchoice\t" % json.dumps(
            {"if": "charges", "then": branch, "else": branch})
        with self.assertRaises(ledger_check.LedgerError):
            ledger_check.parse_ledger(HEADER + choice + "\n")


class DeviceRulesTest(GrammarCase):
    """Review round 2: AndroidText.plural is resources.getQuantityString, which picks the item by the phone's first
    locale. A ru phone at 21 picks "one" ("Readings for 21 scooter"), a fr or pt phone at 0 picks "one" ("0
    setting"), so a plural key whose English items differ changes English there until the runtime fix lands."""
    FILES = HelperPluralTest.FILES
    STRINGS = HelperPluralTest.STRINGS
    convert = HelperPluralTest.convert

    def test_gen_catalog_declares_the_runtime_fix(self):
        # Gates round 3A made AndroidText pick default English items by English rules, so gen_catalog declares it.
        self.assertIs(getattr(gen_catalog, ledger_check.PLURAL_RULES_FLAG, None), True)
        self.convert()
        self.assertEqual(self.check([readings()])[0], [])

    def test_the_honest_helper_row_fails_without_the_runtime_fix(self):
        self.convert()
        with mock.patch.object(gen_catalog, ledger_check.PLURAL_RULES_FLAG, False, create=True):
            problems, _ = self.assertFails([readings()], "English one and other items differ")
        self.assertTrue(any("Russian phone at 21" in p and ledger_check.PLURAL_RULES_FLAG in p for p in problems), problems)

    def test_only_true_lifts_it(self):
        self.convert()
        for value in (False, None, 1, "yes"):
            with self.subTest(value=value), mock.patch.object(gen_catalog, ledger_check.PLURAL_RULES_FLAG, value,
                                                              create=True):
                self.assertFails([readings()], "English one and other items differ")
        with mock.patch.object(gen_catalog, ledger_check.PLURAL_RULES_FLAG, True, create=True):
            self.assertEqual(self.check([readings()])[0], [])

    def test_a_plain_plural_whose_items_differ_fails_in_g1(self):
        # Without a helper the forms equal the one base expression; a %d template G1 cannot render was G2's alone.
        self.repo.write(ARCHIVE, java_class("BackupArchive", """    static String plan(int skipped) {
        return skipped + " skipped";
    }
"""))
        self.repo.write("res/values/strings_test.xml", "")
        self.base = self.repo.commit("plain")
        self.repo.write("res/values/strings_test.xml", """<resources><plurals name="backup_skipped">
    <item quantity="one">%1$d skipped</item>
    <item quantity="other">%1$d skipped</item>
</plurals></resources>
""")
        self.edit(ARCHIVE, 'skipped + " skipped"', "T.backupSkipped(skipped, skipped)")
        form = [{"hole": "skipped", "arg": 1, "type": "d", "quantity": True}, " skipped"]
        import json
        row = ARCHIVE + ":5\t%s\tbackup_skipped\tplural\t" % json.dumps({"one": form, "other": form})
        self.assertEqual(self.check([row])[0], [])
        self.repo.write("res/values/strings_test.xml", self.text("res/values/strings_test.xml").replace(
            "%1$d skipped</item>\n    <item quantity=\"other\">", "%1$d skip</item>\n    <item quantity=\"other\">"))
        with mock.patch.object(gen_catalog, ledger_check.PLURAL_RULES_FLAG, False, create=True):
            self.assertFails([row], "English one and other items differ")


if __name__ == "__main__":
    unittest.main()
