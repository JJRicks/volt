"""G1 for the wave-1 grammar (gates round 3b): honest conversions the batches B3-B10 could not record.

Each form is allowed only in its exact shape and listed for the reviewer; the careless variants fail.
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ledger_check  # noqa: E402
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


if __name__ == "__main__":
    unittest.main()
