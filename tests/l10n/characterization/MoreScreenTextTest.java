package com.jjricks.volt;

import java.util.Locale;

/**
 * G5 characterization for B18 (the More screen), committed before the batch changes anything.
 *
 * MoreScreen needs Android, so a plain JVM test cannot build it; G2 (LedgerTest) proves each new
 * template renders the original concatenation, and G7's census covers the screen. What this pins is
 * the English the screen builds by joining and formatting text today:
 *   - the format sites the batch moves onto Fmt: the history file size ("%.1f MiB"), local storage
 *     ("%.1f of %.0f MiB"), the text size percent and the telemetry refresh seconds (whole numbers
 *     joined to "%" and " s"), byte for byte against Fmt.decimal and Fmt.integer;
 *   - Base: copies of the base's concatenations (the joins, choices and counts that become
 *     templates, choices and plurals), so B18's own suite can check the catalog against them.
 * Every case runs with the phone set to en-US, de-DE, en-GB, en-IN and tr-TR.
 */
public final class MoreScreenTextTest {
    private static int checks;

    public static void main(String[] args) {
        Locale saved = Locale.getDefault();
        try {
            for (Locale phone : new Locale[]{Locale.US, Locale.GERMANY, Locale.UK, new Locale("en", "IN"),
                    new Locale("tr", "TR")}) {
                Locale.setDefault(phone);
                formats(phone);
                joins(phone);
            }
        } finally {
            Locale.setDefault(saved);
        }
        System.out.println("MoreScreenTextTest: " + checks + " checks passed (More screen sizes, percents, "
                + "joins, choices and counts under 5 phone locales)");
    }

    private static void formats(Locale phone) {
        long[] sizes = {1, 1023, 52428, 52429, 1048576, 1572864, 10485760, 104857600, 1073741824L, 5368709120L,
                Long.MAX_VALUE / 2};
        for (long bytes : sizes) {
            equal(Base.mib(bytes), Fmt.decimal(Locale.US, bytes / 1048576.0, 1) + " MiB", phone, "mib " + bytes);
            for (long limit : new long[]{1048576, 268435456, 524288000, 1073741824L, 1610612736}) {
                equal(Base.storageUsed(bytes, limit), Fmt.decimal(Locale.US, bytes / 1048576.0, 1) + " of "
                        + Fmt.decimal(Locale.US, limit / 1048576.0, 0) + " MiB", phone, "storage " + bytes + "/" + limit);
            }
        }
        equal("", Base.mib(0), phone, "no file has no size");
        equal("", Base.mib(-1), phone, "a missing file has no size");
        equal("1.0 MiB", Base.mib(1048576), phone, "one MiB");
        equal("0.5 of 256 MiB", Base.storageUsed(524288, 268435456), phone, "half a MiB of 256");
        for (float scale = 0.8f; scale <= 1.5f; scale += 0.05f) {
            equal(Base.percent(scale), Fmt.integer(Math.round(scale * 100)) + "%", phone, "percent " + scale);
        }
        equal("100%", Base.percent(1f), phone, "the default text size");
        for (int ms : new int[]{0, 999, 1000, 2000, 5000, 10000, 2500}) {
            equal(Base.seconds(ms), Fmt.integer(ms / 1000) + " s", phone, "seconds " + ms);
        }
        equal("2 s", Base.seconds(2000), phone, "the default refresh");
    }

    private static void joins(Locale phone) {
        equal("Volt 1.11.1 · not affiliated with NAVEE", Base.footer("1.11.1"), phone, "footer");
        equal("Back to App preferences", Base.backTo("App preferences"), phone, "back");
        equal("Haptic feedback, state not reported", Base.toggleDescription("Haptic feedback", null), phone, "toggle");
        equal("Haptic feedback", Base.toggleDescription("Haptic feedback", true), phone, "toggle known");
        equal("Step 3", Base.checklistStep(false, 3), phone, "checklist");
        equal("Done", Base.checklistStep(true, 3), phone, "checklist done");
        equal("Your answer: N65i", Base.ownerAnswer("N65i"), phone, "answer");
        equal("Forget Blue", Base.forget("Blue"), phone, "forget");
        equal("Forget Blue?", Base.forgetTitle("Blue"), phone, "forget title");
        equal("mi · °F · 12h", Base.unitsSummary("mi", "f", false), phone, "units");
        equal("mi + km · °C · 24h", Base.unitsSummary("dual", "c", true), phone, "units dual");
        equal("km · °C · 24h", Base.unitsSummary("km", "c", true), phone, "units km");
        equal("up to 7 days", Base.upTo("7 days"), phone, "retention");
        equal("Removes history for all scooters. Rides stay.", Base.clearHistory(true, "Blue"), phone, "clear all");
        equal("Removes Blue's history. Rides stay.", Base.clearHistory(false, "Blue"), phone, "clear one");
        equal("PIN change did not start. Not connected", Base.pinNotStarted("Not connected"), phone, "pin");
        equal("Step 2, Hold the button", Base.guideStep(1, "Hold the button"), phone, "guide step");
        equal("No finished tries to clear", Base.clearTries(0), phone, "tries 0");
        equal("Cleared 1 try", Base.clearTries(1), phone, "tries 1");
        equal("Cleared 2 tries", Base.clearTries(2), phone, "tries 2");
        equal("Cleared 21 tries", Base.clearTries(21), phone, "tries 21");
        equal("the N65i", Base.labModel("N65i", "Model 22131"), phone, "lab model name");
        equal("Model 24431", Base.labModel("", "Model 24431"), phone, "lab model line");
        equal("your model", Base.labModel("", ""), phone, "lab model unknown");
        equal("The official app doesn't offer these for the N65i. Try one while parked; Volt reads it back.",
                Base.labIntro(true, true, "the N65i"), phone, "lab intro");
        equal("The official app offers these for your model, but Volt hasn't tested them there. Try one while parked; Volt reads it back.",
                Base.labIntro(false, true, "your model"), phone, "lab verify intro");
        equal("Nothing to try on Model 24431: the official app already offers the controls Volt could try. They're in Scooter settings.",
                Base.labNothingOem("Model 24431"), phone, "lab empty");
        equal("Volt never changes firmware. Try results stay on this phone until a compatibility report you send includes them, or until you clear them.",
                Base.labFoot("Volt never changes firmware.", true), phone, "lab foot");
        equal("Volt never changes firmware.", Base.labFoot("Volt never changes firmware.", false), phone, "lab foot");
        equal("Now 20 km/h · was 25 km/h", Base.labNow("20 km/h", "25 km/h", true), phone, "lab now");
        equal("Now 20 km/h", Base.labNow("20 km/h", "25 km/h", false), phone, "lab now");
        equal("", Base.labNow(null, "25 km/h", true), phone, "lab no value");
        equal("Now 20 km/h · Not connected", Base.labReason("Now 20 km/h", "Not connected"), phone, "lab reason");
        equal("Not connected", Base.labReason("", "Not connected"), phone, "lab reason alone");
        equal("Review the unfinished cruise control try", Base.labAction(true, false, "Cruise control", "On"), phone, "review");
        equal("Set cruise control back to On", Base.labAction(false, true, "Cruise control", "On"), phone, "set back");
        equal("Try cruise control", Base.labAction(false, false, "Cruise control", "On"), phone, "try");
        equal("Cruise control, Unverified, Now On", Base.labLine("Cruise control", "Unverified", "Now On"), phone, "line");
        equal("Cruise control, Unverified", Base.labLine("Cruise control", "Unverified", ""), phone, "line");
        equal("Cruise control, not reported by this scooter", Base.labAbsent("Cruise control"), phone, "absent");
        equal("Setting it back to Off…", Base.settingBack("Off"), phone, "restoring");
        equal("Sending On and reading it back…", Base.sending("On"), phone, "sending");
        equal("Already On", Base.go(false, true, "On"), phone, "already");
        equal("Try On", Base.go(false, false, "On"), phone, "try value");
        equal("Try again: On", Base.go(true, false, "On"), phone, "try again value");
        equal("Volt can't set it back to Off afterwards: the official app doesn't offer that here.", Base.caution("Off"), phone, "caution");
        equal("Set back to Off", Base.setBack("Off"), phone, "set back");
        equal("The official app offers this on 1 other model, but not on yours. It may do nothing here, or work differently.",
                Base.labWhy(1), phone, "why 1");
        equal("The official app offers this on 3 other models, but not on yours. It may do nothing here, or work differently.",
                Base.labWhy(3), phone, "why 3");
        equal("The official app doesn't offer this on your model. It may do nothing here, or work differently.",
                Base.labWhy(0), phone, "why 0");
        equal("Local clock sync\nok", Base.clockEvidence("ok"), phone, "clock evidence");
        equal("Cruise control\nok", Base.evidence("Cruise control", "ok"), phone, "evidence");
        equal("Volt 1.11.1", Base.version("1.11.1"), phone, "version");
        equal("Volt 1.12.0 is available", Base.updateTitle("1.12.0"), phone, "update");
        equal("You have 1.11.1. Get it from the release page and install it over this version; your data stays.",
                Base.updateBody("1.11.1"), phone, "update body");
        equal("Volt 1.12.0-dev is a development build. The latest release is 1.11.1.",
                Base.updateCurrent(true, "1.12.0-dev", "1.11.1"), phone, "dev build");
        equal("Volt 1.11.1 is the latest release.", Base.updateCurrent(false, "1.11.1", "1.11.1"), phone, "latest");
        equal("1 more tap to enable developer options.", Base.taps(1), phone, "tap");
        equal("3 more taps to enable developer options.", Base.taps(3), phone, "taps");
        equal("Record with Stock charger?", Base.recordWith(true), phone, "stock");
        equal("Record with High-speed charger?", Base.recordWith(false), phone, "fast");
        equal("4 qualified stock charge recordings", Base.qualified(4, true), phone, "qualified");
        equal("1 qualified high-speed charge recordings", Base.qualified(1, false), phone, "qualified, kept English bug");
        equal("Owner · connected", Base.profileStatus("Owner", "connected"), phone, "profile");
        equal("Blue, Owner · connected. Open My scooters", Base.profileDescription("Blue", "Owner · connected"), phone, "profile card");
        equal("N65i · Shared rider", Base.sharedModel("N65i"), phone, "shared");
        equal("Last connected 5 minutes ago", Base.lastConnected("5 minutes ago"), phone, "last connected");
        equal("Edit Blue", Base.edit("Blue"), phone, "edit");
        equal("Connect Blue", Base.action("Connect", "Blue"), phone, "action");
        equal("Use Blue. View saved data without connecting", Base.use("Blue"), phone, "use");
        equal("Proximity unlock, Owner only", Base.proximity("Proximity unlock", "Owner only"), phone, "proximity");
    }

    /** Copies of MoreScreen's concatenations at f529f43, the English B18's templates must render. */
    static final class Base {
        private Base() {}
        static String mib(long bytes) { return bytes <= 0 ? "" : String.format(Locale.US, "%.1f MiB", bytes / 1048576.0); }
        static String storageUsed(long bytes, long limitBytes) {
            return String.format(Locale.US, "%.1f of %.0f MiB", bytes / 1048576.0, limitBytes / 1048576.0);
        }
        static String percent(float scale) { return Math.round(scale * 100) + "%"; }
        static String seconds(int ms) { return ms / 1000 + " s"; }
        static String footer(String version) { return "Volt " + version + " · not affiliated with NAVEE"; }
        static String backTo(String parent) { return "Back to " + parent; }
        static String toggleDescription(String title, Boolean state) { return title + (state == null ? ", state not reported" : ""); }
        static String checklistStep(boolean done, int number) { return done ? "Done" : "Step " + number; }
        static String ownerAnswer(String model) { return "Your answer: " + model; }
        static String forget(String name) { return "Forget " + name; }
        static String forgetTitle(String name) { return "Forget " + name + "?"; }
        static String unitsSummary(String units, String temperature, boolean hours24) {
            return ("km".equals(units) ? "km" : "dual".equals(units) ? "mi + km" : "mi") + " · "
                    + ("f".equals(temperature) ? "°F" : "°C") + " · " + (hours24 ? "24h" : "12h");
        }
        static String upTo(String retention) { return "up to " + retention; }
        static String clearHistory(boolean all, String name) {
            return (all ? "Removes history for all scooters" : "Removes " + name + "'s history") + ". Rides stay.";
        }
        static String pinNotStarted(String message) { return "PIN change did not start. " + message; }
        static String guideStep(int i, String step) { return "Step " + (i + 1) + ", " + step; }
        static String clearTries(int n) { return n == 0 ? "No finished tries to clear" : n == 1 ? "Cleared 1 try" : "Cleared " + n + " tries"; }
        static String labModel(String name, String line) {
            if (!name.isEmpty()) return "the " + name;
            return line.isEmpty() ? "your model" : line;
        }
        static String labIntro(boolean experimental, boolean rows, String model) {
            return experimental ? "The official app doesn't offer these for " + model + ". Try one while parked; Volt reads it back."
                    : rows ? "The official app offers these for " + model + ", but Volt hasn't tested them there. Try one while parked; Volt reads it back." : "";
        }
        static String labNothingOem(String model) {
            return "Nothing to try on " + model + ": the official app already offers the controls Volt could try. They're in Scooter settings.";
        }
        static String labFoot(String foot, boolean rows) {
            String kept = rows ? " Try results stay on this phone until a compatibility report you send includes them, or until you clear them." : "";
            return foot + kept;
        }
        static String labNow(String current, String before, boolean back) {
            return current == null ? "" : "Now " + current + (back ? " · was " + before : "");
        }
        static String labReason(String text, String reason) { return (text.isEmpty() ? "" : text + " · ") + reason; }
        static String labAction(boolean review, boolean back, String label, String before) {
            return review ? "Review the unfinished " + label.toLowerCase(Locale.ROOT) + " try"
                    : back ? "Set " + label.toLowerCase(Locale.ROOT) + " back to " + before
                    : "Try " + label.toLowerCase(Locale.ROOT);
        }
        static String labLine(String label, String status, String text) { return label + ", " + status + (text.isEmpty() ? "" : ", " + text); }
        static String labAbsent(String label) { return label + ", not reported by this scooter"; }
        static String settingBack(String value) { return "Setting it back to " + value + "…"; }
        static String sending(String value) { return "Sending " + value + " and reading it back…"; }
        static String go(boolean refused, boolean already, String value) {
            return already ? "Already " + value : (refused ? "Try again: " : "Try ") + value;
        }
        static String caution(String value) { return "Volt can't set it back to " + value + " afterwards: the official app doesn't offer that here."; }
        static String setBack(String value) { return "Set back to " + value; }
        static String labWhy(int others) {
            return (others > 0 ? "The official app offers this on " + others + (others == 1 ? " other model" : " other models") + ", but not on yours."
                    : "The official app doesn't offer this on your model.") + " It may do nothing here, or work differently.";
        }
        static String clockEvidence(String value) { return "Local clock sync\n" + value; }
        static String evidence(String label, String value) { return label + "\n" + value; }
        static String version(String version) { return "Volt " + version; }
        static String updateTitle(String latest) { return "Volt " + latest + " is available"; }
        static String updateBody(String installed) { return "You have " + installed + ". Get it from the release page and install it over this version; your data stays."; }
        static String updateCurrent(boolean development, String installed, String latest) {
            return development ? "Volt " + installed + " is a development build. The latest release is " + latest + "."
                    : "Volt " + installed + " is the latest release.";
        }
        static String taps(int remaining) { return remaining + (remaining == 1 ? " more tap" : " more taps") + " to enable developer options."; }
        static String recordWith(boolean stock) { return "Record with " + (stock ? "Stock" : "High-speed") + " charger?"; }
        static String qualified(int qualified, boolean stock) { return qualified + " qualified " + (stock ? "stock" : "high-speed") + " charge recordings"; }
        static String profileStatus(String role, String link) { return role + " · " + link; }
        static String profileDescription(String name, String status) { return name + ", " + status + ". Open My scooters"; }
        static String sharedModel(String model) { return model + " · Shared rider"; }
        static String lastConnected(CharSequence relative) { return "Last connected " + relative; }
        static String edit(String name) { return "Edit " + name; }
        static String action(String action, String name) { return action + " " + name; }
        static String use(String name) { return "Use " + name + ". View saved data without connecting"; }
        static String proximity(CharSequence title, String reason) { return title + ", " + reason; }
    }

    private static void equal(String expected, String actual, Locale phone, String what) {
        if (!expected.equals(actual)) {
            throw new AssertionError(what + " on a " + phone + " phone: expected <" + expected + "> but was <" + actual + ">");
        }
        checks++;
    }
}
