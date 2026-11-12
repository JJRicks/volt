package com.jjricks.volt;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hub layout plan, PR 7: Home's text through HubText (Home style, Clean's top area, its Edit sheet and quick
 * actions, the header, setup's last step and the one-time What's new sheet), in English and under a key-echo
 * text source (each line is one whole catalog string, never glued from pieces).
 */
public final class HomeTextTest {
    private static int checks;

    private static final T.Source ECHO = new T.Source() {
        @Override public String string(int id) { return "⟦" + T.key(id) + "⟧"; }
        @Override public String plural(int id, long quantity) { return "⟦" + T.key(id) + "⟧"; }
        @Override public String language() { return "es"; }
    };

    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    private static void equal(String expected, CharSequence actual, String message) {
        check(actual != null && expected.equals(actual.toString()), message + ": expected <" + expected + "> but was <" + actual + ">");
    }

    /** Every name of a string or plurals entry in res/values/strings_home.xml (the suite runs from the repository root). */
    private static Set<String> homeCatalogKeys() {
        String xml;
        try {
            xml = new String(Files.readAllBytes(Paths.get("res/values/strings_home.xml")), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Set<String> keys = new HashSet<>();
        Matcher m = Pattern.compile("<(?:string|plurals)\\s+name=\"([^\"]+)\"").matcher(xml);
        while (m.find()) keys.add(m.group(1));
        return keys;
    }

    public static void main(String[] args) {
        Locale saved = Locale.getDefault();
        try {
            for (Locale phone : new Locale[]{Locale.US, Locale.GERMANY, new Locale("tr", "TR")}) {
                Locale.setDefault(phone);
                T.install(null);
                style();
                topArea();
                quickActions();
                headerAndSheets();
            }
            Locale.setDefault(Locale.US);
            T.install(ECHO);
            echo();
        } finally {
            T.install(null);
            Locale.setDefault(saved);
        }
        System.out.println("HomeTextTest: " + checks + " checks passed (Home style, top area, quick actions, header, "
                + "setup and What's new, in English and under the key-echo source)");
    }

    private static void style() {
        equal("Home style", HubText.title(HubPages.SETTINGS_HOME), "settings.home title");
        check(HubText.rowTitle(HubPages.SETTINGS_HOME).equals(HubText.title(HubPages.SETTINGS_HOME)), "settings.home row is named as its page");
        equal("Clean", HubText.homeStyleName(true), "clean");
        equal("Widgets", HubText.homeStyleName(false), "widgets");
        equal("A fixed Home: battery, lock, four quick actions and your scooter's subjects.", HubText.homeStyleDescription(true), "clean sub");
        equal("The dashboard you arrange yourself, with saved layouts.", HubText.homeStyleDescription(false), "widgets sub");
        // Owner's answers 9, 10 and 12: setup shows detailed pictures, and each is spoken as what it shows.
        equal("Picture of the Clean Home: the battery card, the lock, a row of four quick actions starting with RIDE, "
                + "and your scooter's subjects below.", HubText.homeStylePreview(true), "clean preview");
        equal("Picture of the Widgets Home: the RIDE button at the top, then the scooter overview, the lock bar, "
                + "reading and setting tiles and the ride recorder, in a grid you arrange.",
                HubText.homeStylePreview(false), "widgets preview");
        equal("Switching keeps your widget layout.", HubText.homeStyleKeepsLayout(), "keeps layout");
        equal("Show charging while plugged in", HubText.momentCharging(), "moment charging");
        equal("Home shows the charge by itself when charging starts.", HubText.momentChargingDescription(), "moment charging sub");
        equal("Show the last ride after riding", HubText.momentRide(), "moment ride");
        equal("For about an hour after a ride, until you close it.", HubText.momentRideDescription(), "moment ride sub");
    }

    private static void topArea() {
        equal("Charging to your 90% limit", HubText.chargingToLimit("90%"), "charging to limit");
        equal("Charging to your 80% limit", HubText.chargingToLimit(Msg.verbatim("80%")), "a Msg argument");
        equal("Charging", HubText.charging(), "charging without a limit");
        equal("Charging power", HubText.chargingPower(), "charging power");
        equal("Ride saved just now", HubText.justBackSaved(0), "saved now");
        equal("Ride saved just now", HubText.justBackSaved(-3), "a ride that ends in the future reads as now");
        equal("Ride saved 1 minute ago", HubText.justBackSaved(1), "one minute");
        equal("Ride saved 12 minutes ago", HubText.justBackSaved(12), "minutes");
        equal("Ride saved 59 minutes ago", HubText.justBackSaved(59), "last minute of the window");
        check(HubText.justBackSaved(12).isPlural() && !HubText.justBackSaved(0).isPlural(), "minutes are a count, now is not");
        equal("Close the last ride", HubText.justBackClose(), "close");
        equal("Last ride, 6.2 mi. Opens the ride.", HubText.justBackDescription("6.2 mi"), "card description");
        equal("Ride time", HubText.justBackRideTime(), "ride time tile");
        equal("Avg speed", HubText.justBackAvgSpeed(), "avg speed tile");
        equal("Battery used", HubText.justBackBatteryUsed(), "battery used tile");
    }

    private static void quickActions() {
        equal("Edit Home", HubText.editTitle(), "edit title");
        equal("Quick actions", HubText.editQuickActions(), "edit section");
        equal("Quick action 3: Charge done by. Change", HubText.editQuickSlot(3, "Charge done by"), "slot");
        // Owner's answer 6: Clean's way into riding mode reads as the Widgets RIDE button does, with its own keys.
        equal("RIDE", HubText.quickActionLabel(HomeQuickActions.RIDING), "riding");
        equal("RIDE", HubText.quickActionShortLabel(HomeQuickActions.RIDING), "riding short");
        check(T.key(HubText.quickActionLabel(HomeQuickActions.RIDING).id()).equals("riding_ride_button"), "RIDE reuses the RIDE button's key");
        equal("Open riding mode", HubText.quickActionDescription(HomeQuickActions.RIDING), "riding description");
        check(T.key(HubText.quickActionDescription(HomeQuickActions.RIDING).id()).equals("riding_ride_description"),
                "RIDE is spoken as the RIDE button is");
        equal("Record ride", HubText.quickActionLabel(HomeQuickActions.RECORD), "record");
        equal("Record", HubText.quickActionShortLabel(HomeQuickActions.RECORD), "record short");
        // Owner's answer 11: charge alerts take Charge done by's place on a scooter without scheduled charging.
        equal("Battery alerts", HubText.quickActionLabel(HomeQuickActions.CHARGE_ALERTS), "charge alerts");
        equal("Alerts", HubText.quickActionShortLabel(HomeQuickActions.CHARGE_ALERTS), "charge alerts short");
        for (String id : new String[]{HomeQuickActions.RECORD, HomeQuickActions.CHARGE_ALERTS}) {
            Msg full = HubText.quickActionLabel(id), shortRung = HubText.quickActionShortLabel(id);
            check(T.key(shortRung.id()).equals(T.key(full.id()) + "_short"), "the short rung sits beside its full key: " + id);
            check(HubText.quickActionDescription(id) == null, "no separate description for " + id);
        }
        for (String id : new String[]{HomeQuickActions.RIDING, HomeQuickActions.RECORD, HomeQuickActions.CHARGE_ALERTS}) {
            check(HomeQuickActions.cleanOnly(id), "a labelled id is one of Clean's own: " + id);
            check(HubText.quickActionLabel(id) != null && HubText.quickActionShortLabel(id) != null, "every Clean-only id has a name: " + id);
        }
        // Catalog ids keep their Quick access names (QuickAccessActions); HubText names only Clean's own three.
        for (String id : QuickAccessActions.defaultIds()) {
            check(HubText.quickActionLabel(id) == null && HubText.quickActionShortLabel(id) == null
                    && HubText.quickActionDescription(id) == null, "no Home name for catalog id " + id);
        }
        check(HubText.quickActionDescription(null) == null, "no description for null");
        check(HubText.quickActionLabel(null) == null && HubText.quickActionShortLabel(null) == null, "no name for null");
        check(HubText.quickActionLabel("nowhere") == null, "no name for an unknown id");
        equal("Recording", HubText.qaRecording(), "recording value");
    }

    private static void headerAndSheets() {
        equal("Search settings, readings and rides", HubText.searchDescription(), "search");
        equal("Settings", HubText.settingsDescription(), "settings");
        equal("Ninebot Max. Switch scooter", HubText.switchScooterDescription("Ninebot Max"), "switch scooter");
        equal("Choose your Home", HubText.setupStyleTitle(), "setup title");
        equal("You can change this any time in Settings › Home style.", HubText.setupStyleBody(), "setup body");
        equal("Home has a new look", HubText.whatsNewTitle(), "what's new title");
        equal("Try Clean: battery, lock and your most-used actions on one fixed screen. Your Widgets dashboard stays as it is.",
                HubText.whatsNewBody(), "what's new body");
        equal("Switch to Clean", HubText.whatsNewSwitch(), "switch");
        equal("Keep Widgets", HubText.whatsNewKeep(), "keep");
        equal("You can change this any time in Settings › Home style.", HubText.whatsNewLater(), "later");
        // Owner's answers 9, 10 and 12: the sheet carries the release's notes, with the update check's own words
        // for the English-notes line and the release page link.
        equal("What's new in Volt 1.14.0", HubText.whatsNewChanges("1.14.0"), "changes heading");
        equal("Release notes are in English.", HubText.whatsNewNotesInEnglish(), "notes in English");
        check(T.key(HubText.whatsNewNotesInEnglish().id()).equals("compat_update_notes_in_english"), "the update check's line");
        equal("Open release page", HubText.whatsNewReleasePage(), "release page link");
        check(T.key(HubText.whatsNewReleasePage().id()).equals("more_update_open"), "the update check's button");
        equal("No browser could open the release page", HubText.whatsNewNoBrowser(), "no browser");
        check(T.key(HubText.whatsNewNoBrowser().id()).equals("more_update_no_browser"), "the update check's message");
    }

    /** Under the key-echo source every line is exactly one key: nothing is glued around a catalog string. */
    private static void echo() {
        List<Msg> all = new ArrayList<>(Arrays.asList(HubText.title(HubPages.SETTINGS_HOME),
                HubText.homeStyleName(true), HubText.homeStyleName(false), HubText.homeStyleDescription(true),
                HubText.homeStyleDescription(false), HubText.homeStylePreview(true), HubText.homeStylePreview(false),
                HubText.homeStyleKeepsLayout(), HubText.momentCharging(), HubText.momentChargingDescription(),
                HubText.momentRide(), HubText.momentRideDescription(), HubText.chargingToLimit("x"), HubText.charging(),
                HubText.chargingPower(), HubText.justBackSaved(0), HubText.justBackSaved(5), HubText.justBackClose(),
                HubText.justBackDescription("x"), HubText.justBackRideTime(), HubText.justBackAvgSpeed(),
                HubText.justBackBatteryUsed(), HubText.editTitle(), HubText.editQuickActions(), HubText.editQuickSlot(1, "x"),
                HubText.quickActionLabel(HomeQuickActions.RIDING), HubText.quickActionLabel(HomeQuickActions.RECORD),
                HubText.quickActionShortLabel(HomeQuickActions.RIDING), HubText.quickActionShortLabel(HomeQuickActions.RECORD),
                HubText.quickActionLabel(HomeQuickActions.CHARGE_ALERTS), HubText.quickActionShortLabel(HomeQuickActions.CHARGE_ALERTS),
                HubText.quickActionDescription(HomeQuickActions.RIDING), HubText.whatsNewChanges("x"),
                HubText.whatsNewNotesInEnglish(), HubText.whatsNewReleasePage(), HubText.whatsNewNoBrowser(),
                HubText.qaRecording(), HubText.searchDescription(), HubText.settingsDescription(),
                HubText.switchScooterDescription("x"), HubText.setupStyleTitle(), HubText.setupStyleBody(),
                HubText.whatsNewTitle(), HubText.whatsNewBody(), HubText.whatsNewSwitch(), HubText.whatsNewKeep(),
                HubText.whatsNewLater()));
        Set<String> homeKeys = new HashSet<>();
        for (Msg msg : all) {
            check(msg != null && msg.id() >= 0, "a catalog message, not verbatim text");
            equal("⟦" + T.key(msg.id()) + "⟧", msg.render(), "one whole key");
            if (T.key(msg.id()).startsWith("home_")) homeKeys.add(T.key(msg.id()));
        }
        // Every key of strings_home.xml is reached through HubText, each by its own method: the set read from
        // the catalog file equals the set reached, so a key added later without a HubText method fails here.
        Set<String> catalogKeys = homeCatalogKeys();
        check(!catalogKeys.isEmpty(), "strings_home.xml has keys");
        check(catalogKeys.equals(homeKeys), "every Home key reached: catalog " + catalogKeys + " but reached " + homeKeys);
        // The English of a message stays English whatever language is installed.
        equal("Charging to your 90% limit", HubText.chargingToLimit("90%").english(), "english() under another source");
    }
}
