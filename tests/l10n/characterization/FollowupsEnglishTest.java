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
 *   - Base.unitsSummary: a copy of MoreScreen.unitsSummary's concatenation (the Units row value), for
 *     every stored unit id, temperature id and clock, with the exact English each one prints;
 *   - Base.SECTION_TITLES: the submenu titles, checked against MoreScreen's SECTION_TITLES table while
 *     the source still has it (F4 replaces it with SECTION_TITLE_IDS; FollowupsTest checks those
 *     render these titles);
 *   - the default scooter name the setup sentences and the My scooters card show today ("Scooter",
 *     the stored ScooterProfiles.DEFAULT_NAME) is what B6's owner_profile_default_name prints, and
 *     the connecting and done sentences around it;
 *   - the Restore page's layout destination name: BackupArchive's English "Layout file", which the
 *     destination's spoken labels pass today, and the catalog's backup_file_layout prints the same.
 * Every case runs with the phone set to en-US, de-DE, en-GB, en-IN and tr-TR.
 */
public final class FollowupsEnglishTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        Locale saved = Locale.getDefault();
        try {
            for (Locale phone : new Locale[]{Locale.US, Locale.GERMANY, Locale.UK, new Locale("en", "IN"),
                    new Locale("tr", "TR")}) {
                Locale.setDefault(phone);
                T.install(null);
                units(phone);
                names(phone);
            }
            Locale.setDefault(Locale.US);
            sources();
        } finally {
            T.install(null);
            Locale.setDefault(saved);
        }
        System.out.println("FollowupsEnglishTest: " + checks + " checks passed (Units row value, submenu titles, "
                + "default scooter name sentences and the layout destination under 5 phone locales)");
    }

    private static void units(Locale phone) {
        String[][] expected = {
                {"mi", "c", "true", "mi · °C · 24h"}, {"mi", "c", "false", "mi · °C · 12h"},
                {"mi", "f", "true", "mi · °F · 24h"}, {"mi", "f", "false", "mi · °F · 12h"},
                {"km", "c", "true", "km · °C · 24h"}, {"km", "c", "false", "km · °C · 12h"},
                {"km", "f", "true", "km · °F · 24h"}, {"km", "f", "false", "km · °F · 12h"},
                {"dual", "c", "true", "mi + km · °C · 24h"}, {"dual", "c", "false", "mi + km · °C · 12h"},
                {"dual", "f", "true", "mi + km · °F · 24h"}, {"dual", "f", "false", "mi + km · °F · 12h"},
                // Anything else stored reads as miles and Celsius, as the screen's defaults do.
                {"", "", "true", "mi · °C · 24h"}, {"KM", "F", "false", "mi · °C · 12h"},
                {"miles", "x", "true", "mi · °C · 24h"},
        };
        for (String[] row : expected) {
            equal(row[3], Base.unitsSummary(row[0], row[1], Boolean.parseBoolean(row[2])), phone,
                    "units " + Arrays.toString(row));
        }
    }

    private static void names(Locale phone) {
        equal(Base.DEFAULT_NAME, T.ownerProfileDefaultName(), phone, "the default scooter name");
        equal("Connecting to Scooter", T.onboardingConnectConnecting(Base.DEFAULT_NAME), phone, "connecting sentence");
        equal("Scooter is ready.", T.onboardingDoneReady(Base.DEFAULT_NAME), phone, "done sentence");
        equal("Connecting to Blue", T.onboardingConnectConnecting("Blue"), phone, "connecting sentence, renamed");
        equal("Blue is ready.", T.onboardingDoneReady("Blue"), phone, "done sentence, renamed");
        equal(Base.LAYOUT_NAME, T.backupFileLayout(), phone, "the layout file's display name");
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

        static String unitsSummary(String units, String temperature, boolean hours24) {
            return ("km".equals(units) ? "km" : "dual".equals(units) ? "mi + km" : "mi") + " · "
                    + ("f".equals(temperature) ? "°F" : "°C") + " · " + (hours24 ? "24h" : "12h");
        }
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
