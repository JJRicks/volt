package com.jjricks.volt;

/**
 * When a failed automatic ride copy posts its notice (RideAutoExport.notifies): never while Volt is visible, once
 * per failure kind while hidden, and still once when the same failure was first stored while Volt was open, since
 * that one was never posted. Listed in tests/l10n/ride-auto-export.sources; tests/run.sh runs it.
 */
public final class RideAutoExportNoticeTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }

    public static void main(String[] args) {
        RideAutoExport.Problem none = RideAutoExport.Problem.NONE;
        RideAutoExport.Problem folder = RideAutoExport.Problem.FOLDER;
        RideAutoExport.Problem save = RideAutoExport.Problem.SAVE;
        check(RideAutoExport.notifies(none, folder, none, false), "A new failure while hidden posts");
        check(!RideAutoExport.notifies(none, folder, none, true), "No notice while Volt is visible");
        check(!RideAutoExport.notifies(folder, folder, folder, false), "Hidden, then the same failure hidden again: no repeat");
        check(RideAutoExport.notifies(folder, folder, none, false), "Visible failure, then the same failure while hidden: one notice");
        check(!RideAutoExport.notifies(folder, folder, none, true), "Still visible: the row shows it, no notice");
        check(RideAutoExport.notifies(folder, save, folder, false), "A different failure kind posts again");
        check(!RideAutoExport.notifies(save, save, save, false), "A repeated write failure posts once");
        check(RideAutoExport.notifies(none, folder, folder, false), "Nothing stored before the pass: an old posted kind is stale");
        check(!RideAutoExport.notifies(folder, none, folder, false), "A success never posts");
        check(!RideAutoExport.notifies(none, none, none, false), "Nothing failed: no notice");
        System.out.println("RideAutoExportNoticeTest: " + checks + " checks passed");
    }
}
