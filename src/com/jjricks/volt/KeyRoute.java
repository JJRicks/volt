package com.jjricks.volt;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * How a saved scooter's owner key reached Volt: a fixed word per setup route, saved with the profile (ScooterProfiles,
 * "key_route") when setup saves its key, and reported in the diagnostics file (DiagnosticsBundle.scooterSetup) so a
 * report full of refused owner checks says whether the key was made by Volt, captured or typed. Never the key itself.
 * The word travels with its profile in backups, as the rest of the profile does: it says where the key came from, which a
 * restore doesn't change. Pure Java.
 */
final class KeyRoute {
    /** Make a new owner key (NewOwnerKey). */
    static final String MADE = "made";
    /** Twelve characters typed on setup's identity step. */
    static final String TYPED = "typed";
    /** Phone-only capture through Shizuku. */
    static final String SHIZUKU = "shizuku";
    /** A helper's capture file picked on setup's Chrome and USB route (the browser helper). */
    static final String CHROME_USB = "chrome_usb";
    /** A helper's capture file picked on setup's computer route (setup/capture-owner.py). */
    static final String DESKTOP_HELPER = "desktop_helper";
    /** A helper's capture file picked anywhere else (setup's owner file route, Settings' import): which helper is unknown. */
    static final String HELPER_FILE = "helper_file";
    /** Any other owner setup file: one without a helper's capture time, or a text capture. */
    static final String SETUP_FILE = "setup_file";
    /** No route saved: a profile from before routes were saved, the first version's migration, or a word Volt doesn't know. */
    static final String UNKNOWN = "unknown";
    static final List<String> ROUTES = Collections.unmodifiableList(Arrays.asList(
            MADE, TYPED, SHIZUKU, CHROME_USB, DESKTOP_HELPER, HELPER_FILE, SETUP_FILE, UNKNOWN));

    /** A made key's state, from its record (NewOwnerKey) and whether the profile has a saved scooter address. */
    static final String MADE_NONE = "none", MADE_NOT_OFFERED = "not_offered", MADE_TIED = "offered_tied",
            MADE_REFUSED = "refused_untied", MADE_ACCEPTED = "accepted", MADE_NO_RECORD = "no_record";

    /** What the browser and computer helpers write (setup/web, setup/capture-owner.py): this format and a capture time. */
    private static final Pattern OWNER_FORMAT = Pattern.compile("\"format\"\\s*:\\s*\"navee-offline-owner-v1\""),
            CAPTURED = Pattern.compile("\"captured_at_utc\"\\s*:\\s*\"");
    /** The shape of a route word, this version's or a newer one's (BackupProfiles and AppDataBackup check a backup's by it). */
    static final String SHAPE = "[a-z_]{1,32}";
    private static final Pattern WORD = Pattern.compile(SHAPE);

    private KeyRoute() { }

    /** A saved word as it is when Volt knows it, else unknown (null, empty or any other text). */
    static String known(String stored) { return stored != null && ROUTES.contains(stored) ? stored : UNKNOWN; }

    /**
     * A saved word as a profile keeps it when read back: any word of KeyRoute's shape (a newer Volt's too, so an older one
     * that saves the profile again doesn't drop it), else nothing. Reports read it through {@link #known}.
     */
    static String kept(String stored) { return stored != null && WORD.matcher(stored).matches() ? stored : ""; }

    /** The word a profile saves: nothing for unknown, so an unknown route is the same as none. */
    static String saved(String route) { String r = known(route); return UNKNOWN.equals(r) ? "" : r; }

    /**
     * The route of a picked owner file. A helper's capture file (the owner-file format with its capture time) takes the
     * helper setup's route names ({@code helper}: CHROME_USB or DESKTOP_HELPER, or null outside those routes), else
     * HELPER_FILE; any other file is SETUP_FILE. Both helpers write the same file, so a file from the other helper picked
     * on one helper's route reads as that route's helper.
     */
    static String ofFile(String helper, String text) {
        boolean captured = text != null && OWNER_FORMAT.matcher(text).find() && CAPTURED.matcher(text).find();
        if (!captured) return SETUP_FILE;
        return CHROME_USB.equals(helper) || DESKTOP_HELPER.equals(helper) ? helper : HELPER_FILE;
    }

    /** The route reported: MADE for any key with a made key's record (one saved before routes were), else the saved word. */
    static String reported(String stored, String madeRecord) { return madeRecord != null ? MADE : known(stored); }

    /**
     * A made key's state: none for any other key; no_record for a key saved as made whose record is gone; else
     * accepted once a scooter address is saved (Volt saves one only after the owner check), not_offered while it was
     * offered to no scooter, offered_tied while it is tied to the scooter it was offered to, and refused_untied once that
     * scooter refused it (NewOwnerKey.afterRefusal).
     */
    static String madeKey(String stored, String madeRecord, boolean addressSaved) {
        if (madeRecord == null) return MADE.equals(known(stored)) ? MADE_NO_RECORD : MADE_NONE;
        if (addressSaved) return MADE_ACCEPTED;
        if (!NewOwnerKey.offered(madeRecord)) return MADE_NOT_OFFERED;
        return NewOwnerKey.tie(madeRecord).isEmpty() ? MADE_REFUSED : MADE_TIED;
    }
}
