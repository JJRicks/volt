package com.jjricks.volt;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * G5 characterization for F4 (the B18, B17 and B11 follow-ups), committed before F4 changes anything.
 *
 * MoreScreen, OnboardingScreen and BackupActivity need Android, so a plain JVM test cannot build them.
 * What this pins is the English those screens show today where F4 changes how they build it:
 *   - Base.SECTION_TITLES: the submenu titles, checked against MoreScreen's SECTION_TITLES table (F4 adds
 *     SECTION_TITLE_IDS beside it; FollowupsTest checks those render these titles);
 *   - the default scooter name the setup sentences, the import summary, the My scooters card and the clear
 *     history confirmation show today ("Scooter", the stored ScooterProfiles.DEFAULT_NAME) is what B6's
 *     owner_profile_default_name prints, and the sentences around it;
 *   - the Restore page's destination name: what the spoken labels pass today is the engine's name as it
 *     stands (Base.destinationName), BackupArchive's "Layout file" for the layout file, a backup's own
 *     name otherwise, even for a scooter whose id is the layout file's source id; the catalog's
 *     backup_file_layout prints the same "Layout file";
 *   - the two count sentences F4 turns into plurals, at every LedgerTest quantity: the lab's "Cleared N
 *     tries" toast (Base.clearedTries) and its "other models" caution (Base.labWhyOffered). Their one and
 *     many keys print this today; FollowupsTest checks the plurals print it after.
 * Every case runs with the phone set to en-US, de-DE, en-GB, en-IN, tr-TR and ar-EG.
 */
public final class FollowupsEnglishTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        Locale saved = Locale.getDefault();
        try {
            for (Locale phone : new Locale[]{Locale.US, Locale.GERMANY, Locale.UK, new Locale("en", "IN"),
                    new Locale("tr", "TR"), new Locale("ar", "EG")}) {
                Locale.setDefault(phone);
                T.install(null);
                counts(phone);
                names(phone);
            }
            Locale.setDefault(Locale.US);
            sources();
        } finally {
            T.install(null);
            Locale.setDefault(saved);
        }
        System.out.println("FollowupsEnglishTest: " + checks + " checks passed (submenu titles, count sentences, "
                + "default scooter name sentences and the layout destination under 6 phone locales)");
    }

    private static void counts(Locale phone) {
        equal("Cleared 1 try", Base.clearedTries(1), phone, "cleared 1");
        equal("Cleared 2 tries", Base.clearedTries(2), phone, "cleared 2");
        equal("Cleared 21 tries", Base.clearedTries(21), phone, "cleared 21");
        equal("The official app offers this on 1 other model, but not on yours. It may do nothing here, or work differently.",
                Base.labWhyOffered(1), phone, "offered 1");
        equal("The official app offers this on 2 other models, but not on yours. It may do nothing here, or work differently.",
                Base.labWhyOffered(2), phone, "offered 2");
        equal("The official app offers this on 21 other models, but not on yours. It may do nothing here, or work differently.",
                Base.labWhyOffered(21), phone, "offered 21");
        for (int n : Base.COUNTS) {
            equal(Base.clearedTries(n), n == 1 ? T.moreLabClearedOne() : T.moreLabClearedMany(n), phone, "cleared " + n);
            equal(Base.labWhyOffered(n), n == 1 ? T.moreLabWhyOne(n) : T.moreLabWhyMany(n), phone, "offered " + n);
        }
    }

    private static void names(Locale phone) {
        equal(Base.DEFAULT_NAME, T.ownerProfileDefaultName(), phone, "the default scooter name");
        equal("Connecting to Scooter", T.onboardingConnectConnecting(Base.DEFAULT_NAME), phone, "connecting sentence");
        equal("Scooter is ready.", T.onboardingDoneReady(Base.DEFAULT_NAME), phone, "done sentence");
        equal("Connecting to Blue", T.onboardingConnectConnecting("Blue"), phone, "connecting sentence, renamed");
        equal("Blue is ready.", T.onboardingDoneReady("Blue"), phone, "done sentence, renamed");
        equal("Removes Scooter's history. Rides stay.", T.moreHistoryClearOneBody(Base.DEFAULT_NAME), phone, "clear history");
        equal("Removes Blue's history. Rides stay.", T.moreHistoryClearOneBody("Blue"), phone, "clear history, renamed");
        equal(Base.LAYOUT_NAME, T.backupFileLayout(), phone, "the layout file's display name");
        equal(Base.LAYOUT_NAME, Base.destinationName(Base.LAYOUT_SOURCE, Base.LAYOUT_NAME), phone, "the layout destination");
        equal("Blue", Base.destinationName(Base.LAYOUT_SOURCE, "Blue"), phone, "a backup scooter with the layout file's id");
        equal("Blue", Base.destinationName("scooter-1", "Blue"), phone, "a scooter");
        equal("Destination for Layout file", T.backupDestinationFor(Base.LAYOUT_NAME), phone, "layout spoken label");
        equal("Destination for Layout file: Don't restore",
                T.backupDestinationForChoice(Base.LAYOUT_NAME, T.backupDontRestore()), phone, "layout spoken choice");
    }

    private static void sources() throws Exception {
        String more = read("src/com/jjricks/volt/MoreScreen.java");
        if (more.contains("String[] SECTION_TITLES = {")) {
            List<String> titles = items(more, "String[] SECTION_TITLES = {", "\"([^\"]*)\"");
            check(titles.equals(Arrays.asList(Base.SECTION_TITLES)), "SECTION_TITLES " + titles);
        }
        check(Base.SECTION_TITLES.length == 7, "seven submenus");
        String archive = read("src/com/jjricks/volt/BackupArchive.java");
        check(archive.contains("LAYOUT_SOURCE = \"" + Base.LAYOUT_SOURCE + "\""), "BackupArchive's layout source id");
        check(archive.contains("new Source(LAYOUT_SOURCE, \"" + Base.LAYOUT_NAME + "\", \"\")"),
                "BackupArchive names the layout file's destination " + Base.LAYOUT_NAME);
        String profiles = read("src/com/jjricks/volt/ScooterProfiles.java");
        check(profiles.contains("static final String DEFAULT_NAME=\"" + Base.DEFAULT_NAME + "\";"),
                "ScooterProfiles stores " + Base.DEFAULT_NAME + " as the default name");
    }

    /** The base's English, copied from the screens before F4. */
    static final class Base {
        static final String DEFAULT_NAME = "Scooter";
        static final String LAYOUT_NAME = "Layout file";
        static final String[] SECTION_TITLES = {"App preferences", "My scooters", "PIN & proximity",
                "Experimental controls", "Data & history", "About & permissions", "Developer options"};

        static final String LAYOUT_SOURCE = "layout-file";
        /** Above 0: MoreScreen shows more_lab_cleared_none and more_lab_why_none for 0. */
        static final int[] COUNTS = {1, 2, 3, 4, 5, 11, 12, 21, 22, 25, 100, 101, 111, 1000000};

        static String clearedTries(int n) { return n == 1 ? "Cleared 1 try" : "Cleared " + n + " tries"; }

        static String labWhyOffered(int n) {
            return "The official app offers this on " + n + (n == 1 ? " other model" : " other models")
                    + ", but not on yours. It may do nothing here, or work differently.";
        }

        /** What the spoken labels pass at base, whatever the source id. */
        static String destinationName(String source, String name) { return name; }
    }

    static String read(String path) throws java.io.IOException {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    static List<String> items(String source, String start, String item) {
        int from = source.indexOf(start);
        check(from >= 0, "no " + start);
        String body = source.substring(from + start.length(), source.indexOf("};", from));
        List<String> found = new ArrayList<>();
        Matcher m = Pattern.compile(item).matcher(body);
        while (m.find()) found.add(m.group(1));
        return found;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }

    private static void equal(String expected, String actual, Locale phone, String what) {
        if (!expected.equals(actual)) {
            throw new AssertionError(what + " on a " + phone + " phone: expected <" + expected + "> but was <" + actual + ">");
        }
        checks++;
    }
}
