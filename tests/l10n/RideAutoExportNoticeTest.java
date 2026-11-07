package com.jjricks.volt;

/**
 * When a failed automatic ride copy posts its notice (RideAutoExport.notifies and notifiedAfter): never while Volt
 * is visible, once per run of one failure kind while hidden, still once when the same failure was first stored while
 * Volt was open (that one was never posted), and again when the kind changed while Volt was open. Listed in
 * tests/l10n/ride-auto-export.sources; tests/run.sh runs it.
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

        check(RideAutoExport.notifiedAfter(none, folder, none, false) == folder, "A posted kind is kept");
        check(RideAutoExport.notifiedAfter(folder, none, folder, false) == none, "A success forgets it");
        check(RideAutoExport.notifiedAfter(folder, save, folder, true) == none, "A kind change while visible forgets it");
        check(RideAutoExport.notifiedAfter(folder, folder, folder, true) == folder, "The same failure carrying on while visible keeps it");
        check(RideAutoExport.notifiedAfter(folder, folder, folder, false) == folder, "A suppressed repeat keeps it");

        // Whole sequences, as ScooterService runs them: each pass reads the failure the last one stored.
        check(posts("hF hF hF") == 1, "Hidden failures of one kind post once");
        check(posts("vF hF hF") == 1, "Visible failure, then the same one hidden: one notice, then none");
        check(posts("hF vS hF") == 2, "Folder hidden, write failure visible, folder hidden again: posts again");
        check(posts("hF vS vF hF") == 2, "The kind changed and came back while visible: posts again");
        check(posts("hF vF hF") == 1, "The same failure carrying on while visible: no repeat");
        check(posts("hF hS hF") == 3, "Each hidden change of kind posts");
        check(posts("hF h- hF") == 2, "A success in between: the next failure posts");
        check(posts("hF off hF") == 2, "Both switches off cleared the stored failure: the next one posts");
        check(posts("vF vF vS v-") == 0, "Nothing posts while Volt stays open");
        System.out.println("RideAutoExportNoticeTest: " + checks + " checks passed");
    }

    /**
     * How many notices a sequence of passes posts. Each step is h (hidden) or v (visible) plus the pass's failure:
     * F folder, S save, - success. "off" clears the stored failure without a pass, as turning auto-save off does.
     */
    private static int posts(String steps) {
        RideAutoExport.Problem stored = RideAutoExport.Problem.NONE, notified = RideAutoExport.Problem.NONE;
        int posted = 0;
        for (String step : steps.split(" ")) {
            if (step.equals("off")) { stored = RideAutoExport.Problem.NONE; continue; }
            boolean visible = step.charAt(0) == 'v';
            char kind = step.charAt(1);
            RideAutoExport.Problem after = kind == 'F' ? RideAutoExport.Problem.FOLDER : kind == 'S' ? RideAutoExport.Problem.SAVE : RideAutoExport.Problem.NONE;
            if (RideAutoExport.notifies(stored, after, notified, visible)) posted++;
            notified = RideAutoExport.notifiedAfter(stored, after, notified, visible);
            stored = after;
        }
        return posted;
    }
}
