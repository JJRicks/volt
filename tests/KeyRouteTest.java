package com.jjricks.volt;

import java.util.*;

/**
 * How a saved scooter's owner key reached Volt (KeyRoute) and the diagnostics file's scooter_setup section: the route
 * words, a picked file's route, a made key's state through its record's whole life, an old profile with no route, and a
 * profile full of recognisable private values that gets none of them into the file. Synthetic values only.
 */
public final class KeyRouteTest {
    private static int checks;
    private static final String PROFILE_ID = "4f1c2b7e-9d3a-4e65-b0c1-2d3e4f5a6b7c", PROFILE_NAME = "Rocket of Testville",
            SERIAL = "N2546ZZ9US1234567", MAC = "C0:FF:EE:12:34:56", OTHER_MAC = "C0:FF:EE:65:43:21", OWNER_KEY = "88A1B2C3D4E5";

    public static void main(String[] args) {
        words();
        files();
        madeKeyLife();
        oldProfiles();
        section();
        noPrivateValues();
        backups();
        System.out.println("KeyRoute tests passed: " + checks + " assertions");
    }

    /** Every route word is known as it is; anything else reads as unknown, and a profile keeps only route-shaped words. */
    private static void words() {
        for (String route : KeyRoute.ROUTES) {
            require(KeyRoute.known(route).equals(route), "a route word is known: " + route);
            require(route.matches(KeyRoute.SHAPE), "a route word has the saved shape: " + route);
        }
        require(new HashSet<>(KeyRoute.ROUTES).size() == KeyRoute.ROUTES.size(), "route words are distinct");
        for (String other : new String[]{null, "", "Typed", "typed ", "made;1", SERIAL, MAC, OWNER_KEY, PROFILE_NAME, "a_newer_route"})
            require(KeyRoute.known(other).equals(KeyRoute.UNKNOWN), "not a route word reads as unknown: " + other);
        require(KeyRoute.saved(KeyRoute.UNKNOWN).isEmpty() && KeyRoute.saved(null).isEmpty() && KeyRoute.saved(PROFILE_NAME).isEmpty(),
                "unknown is saved as nothing");
        require(KeyRoute.saved(KeyRoute.TYPED).equals(KeyRoute.TYPED) && KeyRoute.saved(KeyRoute.MADE).equals(KeyRoute.MADE), "a known route is saved as it is");
        // Read back, a newer Volt's word stays (so saving the profile again keeps it); free text never does.
        require(KeyRoute.kept("a_newer_route").equals("a_newer_route") && KeyRoute.kept(KeyRoute.SHIZUKU).equals(KeyRoute.SHIZUKU), "route-shaped words are kept");
        for (String other : new String[]{null, "", "Typed", PROFILE_NAME, MAC, OWNER_KEY, SERIAL.toLowerCase(Locale.ROOT) + "x_long_enough_to_pass_32_chars"})
            require(KeyRoute.kept(other).isEmpty(), "free text is not kept: " + other);
    }

    /** A helper's capture file takes the helper route setup is on; any other file is a setup file. */
    private static void files() {
        String browser = "{\n  \"format\": \"navee-offline-owner-v1\",\n  \"owner_identity\": \"" + OWNER_KEY + "\",\n  \"role\": 0,\n"
                + "  \"model_name\": \"NAVEE scooter\",\n  \"captured_at_utc\": \"2026-11-01T10:00:00.000Z\",\n  \"note\": \"Private\"\n}\n";
        String computer = browser.replace("NAVEE scooter", "NAVEE UT5 MAX").replace(".000Z", "Z");
        for (String helperFile : new String[]{browser, computer}) {
            require(KeyRoute.ofFile(KeyRoute.CHROME_USB, helperFile).equals(KeyRoute.CHROME_USB), "a helper file on the Chrome and USB route");
            require(KeyRoute.ofFile(KeyRoute.DESKTOP_HELPER, helperFile).equals(KeyRoute.DESKTOP_HELPER), "a helper file on the computer route");
            require(KeyRoute.ofFile(null, helperFile).equals(KeyRoute.HELPER_FILE), "a helper file outside the helper routes");
            require(KeyRoute.ofFile(KeyRoute.SHIZUKU, helperFile).equals(KeyRoute.HELPER_FILE), "only a helper route names a helper");
        }
        // Volt's older private backup of one profile (no capture time), a bare key, a text capture and a hand-made file.
        String backup = "{\n  \"format\": \"navee-offline-owner-v1\",\n  \"name\": \"" + PROFILE_NAME + "\",\n  \"owner_identity\": \"" + OWNER_KEY
                + "\",\n  \"role\": 0,\n  \"scooter_address\": \"" + MAC + "\",\n  \"scooter_serial\": \"" + SERIAL + "\"\n}";
        String capture = "11-01 10:00:00.000 D NaveeBle: Send authData: [55 aa 00 30 09 00 00 88 a1 b2 c3 d4 e5 00 00 fe fd]";
        String handMade = "{\"owner_identity\": \"" + OWNER_KEY + "\", \"role\": 1, \"captured_at_utc\": \"2026-11-01T10:00:00Z\"}";
        for (String other : new String[]{backup, OWNER_KEY, capture, handMade, "", null})
            for (String helper : new String[]{KeyRoute.CHROME_USB, KeyRoute.DESKTOP_HELPER, null})
                require(KeyRoute.ofFile(helper, other).equals(KeyRoute.SETUP_FILE), "not a helper's capture file: a setup file");
        // The identity step: twelve characters, however they're spaced, are typed; pasted text reads as that text picked as a file.
        for (String typed : new String[]{OWNER_KEY, OWNER_KEY.toLowerCase(Locale.ROOT), spaced(OWNER_KEY, " "), spaced(OWNER_KEY, ":"), spaced(OWNER_KEY, "-"), " " + OWNER_KEY + "\n"})
            require(KeyRoute.ofIdentityInput(typed).equals(KeyRoute.TYPED), "twelve characters on the identity step: typed");
        for (String helperFile : new String[]{browser, computer})
            require(KeyRoute.ofIdentityInput(helperFile).equals(KeyRoute.HELPER_FILE), "a helper's file pasted on the identity step");
        for (String other : new String[]{backup, capture, handMade, "", null})
            require(KeyRoute.ofIdentityInput(other).equals(KeyRoute.SETUP_FILE), "other text pasted on the identity step: a setup file");
    }

    /** A twelve-character key with a separator between each pair, as people write it down. */
    private static String spaced(String key, String separator) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < key.length(); i += 2) { if (i > 0) out.append(separator); out.append(key, i, i + 2); }
        return out.toString();
    }

    /** A made key's state through untied, tied, refused and untied, tied again and accepted; the route says made throughout. */
    private static void madeKeyLife() {
        Map<String, String> store = new HashMap<>();
        NewOwnerKey.Records records = new NewOwnerKey.Records() {
            @Override public String get(String key) { return store.get(key); }
            @Override public boolean write(String key, String value) { if (value == null) store.remove(key); else store.put(key, value); return true; }
        };
        String key = NewOwnerKey.key(PROFILE_ID);
        require(KeyRoute.madeKey(KeyRoute.MADE, store.get(key), false).equals(KeyRoute.MADE_NO_RECORD), "a key saved as made without its record");
        require(NewOwnerKey.recordNew(records, PROFILE_ID), "fixture: the made key's record");
        for (String saved : new String[]{KeyRoute.MADE, ""}) {
            // A made key saved before routes were has a record but no route: still made.
            require(KeyRoute.reported(saved, store.get(key)).equals(KeyRoute.MADE), "a made key reports made");
        }
        require(KeyRoute.madeKey(KeyRoute.MADE, store.get(key), false).equals(KeyRoute.MADE_NOT_OFFERED), "made, not offered yet");
        require(NewOwnerKey.offerTo(records, PROFILE_ID, "", MAC) == NewOwnerKey.Offer.SEND, "fixture: offered to the first scooter");
        require(KeyRoute.madeKey(KeyRoute.MADE, store.get(key), false).equals(KeyRoute.MADE_TIED), "offered and tied");
        require(KeyRoute.reported(KeyRoute.MADE, store.get(key)).equals(KeyRoute.MADE), "tied: made");
        require(NewOwnerKey.refusedBy(records, PROFILE_ID, MAC) && NewOwnerKey.OFFERED.equals(store.get(key)), "fixture: that scooter refused it");
        require(KeyRoute.madeKey(KeyRoute.MADE, store.get(key), false).equals(KeyRoute.MADE_REFUSED), "refused, untied");
        require(KeyRoute.madeKey("", store.get(key), false).equals(KeyRoute.MADE_REFUSED), "refused, untied, saved before routes were");
        require(NewOwnerKey.offerTo(records, PROFILE_ID, "", OTHER_MAC) == NewOwnerKey.Offer.SEND, "fixture: offered to another scooter");
        require(KeyRoute.madeKey(KeyRoute.MADE, store.get(key), false).equals(KeyRoute.MADE_TIED), "tied again");
        // Accepted: the scooter took it and its address is saved; the record stays.
        require(KeyRoute.madeKey(KeyRoute.MADE, store.get(key), true).equals(KeyRoute.MADE_ACCEPTED), "accepted once an address is saved");
        require(KeyRoute.reported(KeyRoute.MADE, store.get(key)).equals(KeyRoute.MADE), "accepted: made");
        // Any other key has no record.
        for (String route : KeyRoute.ROUTES) if (!route.equals(KeyRoute.MADE)) {
            require(KeyRoute.madeKey(route, null, true).equals(KeyRoute.MADE_NONE) && KeyRoute.madeKey(route, null, false).equals(KeyRoute.MADE_NONE), "no made key: " + route);
            require(KeyRoute.reported(route, null).equals(route), "another key reports its route: " + route);
        }
    }

    /** A profile saved before routes were: unknown, no made key. */
    private static void oldProfiles() {
        DiagnosticsBundle.ScooterSetup s = setup(null, null);
        String json = new DiagnosticsBundle("1.7.0", 1792026000000L, false).scooterSetup(s).json(identities(), names());
        require(json != null && json.contains("\"key_route\": \"unknown\"") && json.contains("\"made_key\": \"none\""), "an old profile reports unknown, no made key");
        s.keyRoute = "";
        json = new DiagnosticsBundle("1.7.0", 1792026000000L, false).scooterSetup(s).json(identities(), names());
        require(json != null && json.contains("\"key_route\": \"unknown\""), "an empty route reports unknown");
    }

    /** The section's fields, each as the file writes it; no scooter selected; an unreadable source. */
    private static void section() {
        DiagnosticsBundle.ScooterSetup s = setup(KeyRoute.TYPED, null);
        s.advertisedPid = "25462";
        String json = new DiagnosticsBundle("1.7.0", 1792026000000L, false).scooterSetup(s).json(identities(), names());
        require(json != null, "the file is made");
        for (String expected : new String[]{"\"scooter_setup\": {", "\"saved_scooters\": 3", "\"selected\": {", "\"key_route\": \"typed\"", "\"made_key\": \"none\"",
                "\"role\": \"owner\"", "\"key_saved\": true", "\"address_saved\": true", "\"serial_saved\": true", "\"advertised_model_id\": \"25462\""})
            require(json.contains(expected), "scooter_setup holds " + expected);
        s = setup(KeyRoute.SHIZUKU, null); s.role = 1; s.addressSaved = false; s.serialSaved = false; s.advertisedPid = "";
        json = new DiagnosticsBundle("1.7.0", 1792026000000L, false).scooterSetup(s).json(identities(), names());
        for (String expected : new String[]{"\"key_route\": \"shizuku\"", "\"role\": \"shared\"", "\"address_saved\": false", "\"serial_saved\": false", "\"advertised_model_id\": null"})
            require(json.contains(expected), "a shared, unsaved scooter: " + expected);
        s = setup(KeyRoute.MADE, MAC); s.addressSaved = false; s.serialSaved = false;
        json = new DiagnosticsBundle("1.7.0", 1792026000000L, false).scooterSetup(s).json(identities(), names());
        require(json != null && json.contains("\"key_route\": \"made\"") && json.contains("\"made_key\": \"offered_tied\""), "a tied made key: " + json);
        DiagnosticsBundle.ScooterSetup none = new DiagnosticsBundle.ScooterSetup(); none.saved = 0;
        json = new DiagnosticsBundle("1.7.0", 1792026000000L, false).scooterSetup(none).json(Collections.<String>emptyList());
        require(json.contains("\"saved_scooters\": 0") && json.contains("\"selected\": null") && !json.contains("key_route"), "no scooter selected");
        json = new DiagnosticsBundle("1.7.0", 1792026000000L, false).scooterSetup(null).json(Collections.<String>emptyList());
        require(json.contains("\"scooter_setup\": null"), "an unreadable source leaves the section null");
        require(json.contains("\"format\": \"volt-diagnostics-v1\""), "the format stays v1: the section only adds fields");
    }

    /**
     * A profile whose every stored text is a private value: the route word, the record (an address) and the model ID.
     * None of them, nor the key, address, serial, name or id, reaches the file, and the file is still made: json()'s final
     * check would otherwise withhold it.
     */
    private static void noPrivateValues() {
        String[][] stuffed = {{PROFILE_NAME, MAC, SERIAL}, {SERIAL, OWNER_KEY, MAC}, {MAC, OTHER_MAC, OWNER_KEY}, {OWNER_KEY, PROFILE_NAME, PROFILE_ID},
                {PROFILE_ID, SERIAL, PROFILE_NAME}, {KeyRoute.TYPED, MAC.replace(":", ""), "1234567"}};
        for (String[] values : stuffed) {
            DiagnosticsBundle.ScooterSetup s = setup(values[0], values[1]);
            s.advertisedPid = values[2];
            String json = new DiagnosticsBundle("1.7.0", 1792026000000L, false).scooterSetup(s).json(identities(), names());
            require(json != null, "the file is made: " + Arrays.toString(values));
            for (String secret : new String[]{PROFILE_NAME, SERIAL, MAC, MAC.replace(":", ""), OTHER_MAC, OWNER_KEY, PROFILE_ID, "1234567", "Testville"})
                require(!json.toUpperCase(Locale.ROOT).contains(secret.toUpperCase(Locale.ROOT)), "no private value in the file: " + secret);
            require(json.contains("\"advertised_model_id\": null"), "a model ID that isn't one shows as null");
            String route = between(json, "\"key_route\": \"");
            require(KeyRoute.ROUTES.contains(route), "the route is one of KeyRoute's words: " + route);
            String made = between(json, "\"made_key\": \"");
            require(Arrays.asList(KeyRoute.MADE_NONE, KeyRoute.MADE_NOT_OFFERED, KeyRoute.MADE_TIED, KeyRoute.MADE_REFUSED, KeyRoute.MADE_ACCEPTED, KeyRoute.MADE_NO_RECORD)
                    .contains(made), "the made key's state is one of its words: " + made);
        }
        // A file that did carry one would be withheld whole: the check behind the section's own sanitising.
        DiagnosticsBundle leaky = new DiagnosticsBundle("1.7.0", 1792026000000L, false).phone("16", 36, PROFILE_NAME);
        require(leaky.json(identities(), names()) == null, "fixture: a name in a value withholds the file");
    }

    /** A backup's route word is checked by shape only, so a newer Volt's word still restores. */
    private static void backups() {
        Map<String, Object> p = new HashMap<>();
        require(BackupProfiles.validModelEvidence(p), "no route: valid");
        for (String ok : new String[]{KeyRoute.TYPED, KeyRoute.MADE, "a_newer_route"}) { p.put("key_route", ok); require(BackupProfiles.validModelEvidence(p), "a route word restores: " + ok); }
        for (Object bad : new Object[]{"", PROFILE_NAME, MAC, 3, Boolean.TRUE, null}) { p.put("key_route", bad); require(!BackupProfiles.validModelEvidence(p), "not a route word: " + bad); }
    }

    /** The selected scooter: owner, key, address and serial saved, three saved scooters. */
    private static DiagnosticsBundle.ScooterSetup setup(String route, String record) {
        DiagnosticsBundle.ScooterSetup s = new DiagnosticsBundle.ScooterSetup();
        s.saved = 3; s.selected = true; s.keyRoute = route; s.madeRecord = record; s.role = 0;
        s.keySaved = true; s.addressSaved = true; s.serialSaved = true; s.advertisedPid = "";
        return s;
    }
    /** What ScooterService passes json() for this profile: its id, serial, key and address with and without separators. */
    private static List<String> identities() { return Arrays.asList(PROFILE_ID, SERIAL, OWNER_KEY, MAC, MAC.replace(":", ""), OTHER_MAC); }
    private static List<String> names() { return Collections.singletonList(PROFILE_NAME); }
    private static String between(String json, String start) {
        int at = json.indexOf(start);
        require(at >= 0, "the file has " + start);
        return json.substring(at + start.length(), json.indexOf('"', at + start.length()));
    }
    private static void require(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
}
