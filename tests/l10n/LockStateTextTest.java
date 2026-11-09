package com.jjricks.volt;

import java.io.File;
import java.util.Locale;

/**
 * The electronic lock's Setting tile and Quick access button. Turkish and Polish show DisplayUnits.lockStateText's state
 * words, live or not, ahead of the shared variant's and the UT5 route's generic On/Off; every other language keeps On/Off.
 * In every language a Quick access button's padlock follows the last known state. A reading that is not live gets no
 * "Last known" words: the tile's dimming marks it. Other on/off features keep On/Off, and the English labels stay
 * Off/On for logs and reports. QuickAccessGrid and Ui are Android views, so their wiring is checked by reading the
 * source; DisplayUnits runs for real. Fixtures are synthesized.
 */
public final class LockStateTextTest {
    private static int checks;

    private static T.Source echo(String language) {
        return new T.Source() {
            @Override public String string(int id) { return "⟦" + T.key(id) + "⟧"; }
            @Override public String plural(int id, long quantity) { return "⟦" + T.key(id) + "⟧"; }
            @Override public String language() { return language; }
        };
    }

    public static void main(String[] args) {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(Locale.US);
            tileChain();
            T.install(null);
            keepsOnOff("On", "Off");
            T.install(echo("es"));
            keepsOnOff("⟦common_on⟧", "⟦common_off⟧");
            onlyTurkishAndPolish();
            shownLanguageGate();
            quickAccessIcons();
        } finally {
            T.install(null);
            Locale.setDefault(saved);
        }
        System.out.println("LockStateTextTest: " + checks + " checks passed (state words only in Turkish and Polish; the padlock follows the last known state everywhere)");
    }

    /** With no state word the lock takes the same On/Off route as main, and every other feature does too. */
    private static void keepsOnOff(String on, String off) {
        ScooterCommands.Feature lock = ScooterCommands.find("lock");
        check(DisplayUnits.lockStateText(lock, 1) == null, "lock 1 keeps the shared word");
        check(DisplayUnits.lockStateText(lock, 0) == null, "lock 0 keeps the shared word");

        CapabilityResolver.Decision shared = decide("lock", ModelRegistryTest.advertised("39991"));
        check(shared.variant == ModelRegistry.SHARED_LOCK, "another model's lock uses the shared variant");
        equal(on, shared.oemText(1).render(), "the shared variant's own label, 1");
        equal(off, shared.oemText(0).render(), "the shared variant's own label, 0");
        CapabilityResolver.Decision ut5 = decide("lock", ModelRegistryTest.serial("Q2538ZZ9US7777777"));
        check(ut5.variant != null && ut5.variant.decode == FeatureVariant.Decode.UT5_FEATURE && ut5.oemText(1) == null,
                "the UT5 MAX lock has no variant label of its own");
        equal(on, DisplayUnits.featureValueText(lock, 1, "km"), "the UT5 route's generic label, 1");
        equal(off, DisplayUnits.featureValueText(lock, 0, "km"), "the UT5 route's generic label, 0");

        // The English ids stay data.
        equal("On", DisplayUnits.featureValue(lock, 1, "km"), "featureValue keeps the English label, 1");
        equal("Off", DisplayUnits.featureValue(lock, 0, "km"), "featureValue keeps the English label, 0");

        for (String key : new String[]{"cruise_control", "headlight", "proximity_key"}) {
            ScooterCommands.Feature feature = ScooterCommands.find(key);
            equal(on, DisplayUnits.featureValueText(feature, 1, "km"), key + " keeps On");
            equal(off, DisplayUnits.featureValueText(feature, 0, "km"), key + " keeps Off");
        }
        FeatureVariant cruise = decide("cruise_control", ModelRegistryTest.serial("Q2538ZZ9US7777777")).variant;
        equal(on, cruise.text(1).render(), "cruise_control's variant keeps On");
        equal(off, cruise.text(0).render(), "cruise_control's variant keeps Off");
    }

    /** The word itself, given the resolved language: only tr and pl, only the lock, only its two values. */
    private static void onlyTurkishAndPolish() {
        T.install(echo("tr"));
        ScooterCommands.Feature lock = ScooterCommands.find("lock");
        for (String language : new String[]{"tr", "pl"}) {
            equal("⟦main_lock_locked⟧", DisplayUnits.lockStateText(lock, 1, language), language + " lock 1");
            equal("⟦main_lock_unlocked⟧", DisplayUnits.lockStateText(lock, 0, language), language + " lock 0");
            check(DisplayUnits.lockStateText(lock, 2, language) == null, language + ": a value the lock does not define is not a lock state");
            for (String key : new String[]{"cruise_control", "headlight", "proximity_key", "pin"}) {
                ScooterCommands.Feature feature = ScooterCommands.find(key);
                check(DisplayUnits.lockStateText(feature, 1, language) == null && DisplayUnits.lockStateText(feature, 0, language) == null, language + ": " + key + " is not a lock state");
            }
        }
        for (String language : new String[]{"en", "de", "es", "fr", "it", "nl", "pt-BR", "pt-PT", "sv", "tr-TR", "pl-PL", "xx"})
            check(DisplayUnits.lockStateText(lock, 1, language) == null && DisplayUnits.lockStateText(lock, 0, language) == null, language + " keeps On/Off");
    }

    /**
     * The gate reads the shown language through AppLanguages.resolve, so Turkish and Polish take effect when they ship
     * and a draft or unknown tag reads as English. Written against the table so it holds before and after they merge.
     */
    private static void shownLanguageGate() {
        ScooterCommands.Feature lock = ScooterCommands.find("lock");
        for (int i = 0; i < AppLanguages.size(); i++) {
            String tag = AppLanguages.tag(i);
            boolean words = AppLanguages.shipped(i) && (tag.equals("tr") || tag.equals("pl"));
            T.install(echo(tag));
            equal(words ? "⟦main_lock_locked⟧" : null, DisplayUnits.lockStateText(lock, 1), tag + " lock 1");
            equal(words ? "⟦main_lock_unlocked⟧" : null, DisplayUnits.lockStateText(lock, 0), tag + " lock 0");
        }
        T.install(echo("xx"));
        check(DisplayUnits.lockStateText(lock, 1) == null, "an unknown language reads as English");
    }

    /** The padlock follows the last known value in every language: it takes no language, liveness or pending input. */
    private static void quickAccessIcons() {
        ScooterCommands.Feature lock = ScooterCommands.find("lock");
        for (String language : new String[]{"en", "es", "tr", "pl"}) {
            T.install(echo(language));
            equal("unlock", DisplayUnits.quickAccessIcon(lock, 0, "lock"), language + ": unlocked is the open padlock");
            equal("lock", DisplayUnits.quickAccessIcon(lock, 1, "lock"), language + ": locked is the closed padlock");
            equal("lock", DisplayUnits.quickAccessIcon(lock, null, "lock"), language + ": no reading keeps the shortcut's padlock");
            equal("lock", DisplayUnits.quickAccessIcon(lock, 2, "lock"), language + ": a value the lock does not define keeps the shortcut's padlock");
        }
        equal("lights", DisplayUnits.quickAccessIcon(ScooterCommands.find("headlight"), 0, "lights"), "another feature keeps its icon");
        equal("home", DisplayUnits.quickAccessIcon(null, 0, "home"), "a shortcut with no feature keeps its icon");
    }

    /** QuickAccessGrid is an Android view, so its chain is read: the lock's line has to come before the variant's label. */
    private static void tileChain() {
        String grid = L10nFiles.read(new File("src/com/jjricks/volt/QuickAccessGrid.java"));
        int lock = grid.indexOf(": lockState != null ? lockState");
        int variant = grid.indexOf(": decision != null && decision.oemText(value) != null ? decision.oemText(value).render()");
        int pending = grid.indexOf(": unmapped ? NO_VALUE : pending ? T.dashboardModelTileUpdating() : value == null ? NO_VALUE");
        check(pending >= 0 && lock > pending && variant > lock, "pending and no-value come first, then the lock's state, then the variant's label");
        check(grid.contains("String lockState = feature == null || value == null ? null : DisplayUnits.lockStateText(feature, value);"), "the word does not depend on whether the reading is live");
        check(!L10nFiles.read(new File("src/com/jjricks/volt/DisplayUnits.java")).contains("mainLockLastKnownWord"), "these tiles have no Last known text");
        check(grid.contains("cell.value.setTextColor(highlight ? ui.onAccent : muted ? ui.muted : ui.text);") && grid.contains("boolean muted = (feature != null || chargeControl) && !featureLive;"), "a stale Setting tile dims");
        check(grid.indexOf("if (!state.contentEquals(cell.value.getText())) cell.value.setText(state);") < grid.indexOf("ui.setIcon(cell.icon, DisplayUnits.quickAccessIcon(feature, value, cell.entry.icon));"), "a Quick access button's padlock follows the reading");
        check(grid.contains("cell.setAlpha((feature != null || chargeControl) && !featureLive ? .65f : 1f);"), "a stale button stays muted");
        check(grid.contains("T.dashboardModelTileDescPart(state)") && grid.contains("cell.setStateDescription(state);"), "TalkBack keeps the full state");
        check(grid.contains("cell.lockState = !pending && lockState != null;"), "Updating is not a lock state");
        check(L10nFiles.read(new File("src/com/jjricks/volt/Ui.java")).contains("key.equals(\"unlock\") ? 210 : 180"), "the open padlock is the shared icon set's");
        check(grid.contains("if (lockState && !StatusRungs.wholeLines(value.getText().toString(), value, room, 2)) value.setVisibility(GONE);"), "a Quick access lock state never breaks mid-word");
    }

    private static CapabilityResolver.Decision decide(String key, DeviceDescriptor descriptor) {
        return CapabilityResolver.decide(key, descriptor, StatusFields.NONE, new CapabilityResolver.Runtime());
    }

    private static void equal(String expected, String actual, String what) {
        check(java.util.Objects.equals(expected, actual), what + ": expected [" + expected + "], got [" + actual + "]");
    }

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }
}
