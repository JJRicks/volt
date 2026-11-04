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


if __name__ == "__main__":
    unittest.main()
