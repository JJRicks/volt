package com.jjricks.volt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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
        equal("Preview of the Clean Home", HubText.homeStylePreview(true), "clean preview");
        equal("Preview of the Widgets Home", HubText.homeStylePreview(false), "widgets preview");
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
        equal("Ride", HubText.quickActionLabel(HomeQuickActions.RIDING), "riding");
        equal("Record", HubText.quickActionLabel(HomeQuickActions.RECORD), "record");
        equal("Ride", HubText.quickActionShortLabel(HomeQuickActions.RIDING), "riding short");
        equal("Record", HubText.quickActionShortLabel(HomeQuickActions.RECORD), "record short");
        for (String id : new String[]{HomeQuickActions.RIDING, HomeQuickActions.RECORD}) {
            Msg full = HubText.quickActionLabel(id), shortRung = HubText.quickActionShortLabel(id);
            check(T.key(shortRung.id()).equals(T.key(full.id()) + "_short"), "the short rung sits beside its full key: " + id);
            check(HomeQuickActions.cleanOnly(id), "a labelled id is one of Clean's own: " + id);
        }
        // Catalog ids keep their Quick access names (QuickAccessActions); HubText names only Clean's own two.
        for (String id : QuickAccessActions.defaultIds()) {
            check(HubText.quickActionLabel(id) == null && HubText.quickActionShortLabel(id) == null, "no Home name for catalog id " + id);
        }
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
        // Every key of strings_home.xml is reached through HubText, each by its own method.
        check(homeKeys.size() == 36, "thirty-six Home keys, each reached: " + homeKeys.size());
        // The English of a message stays English whatever language is installed.
        equal("Charging to your 90% limit", HubText.chargingToLimit("90%").english(), "english() under another source");
    }
}
