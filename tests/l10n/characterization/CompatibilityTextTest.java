package com.jjricks.volt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.TreeSet;

/**
 * G5 characterization for B9 (compatibility, guided tests): the English that GuidedTest, TestJournal and
 * CompatibilityReport give the Compatibility panel and the notices, byte for byte, before the batch.
 *
 *  - GuidedTest: a seeded walk drives every testable setting and every try Volt offers on the UT5 MAX, an
 *    OEM-group model and a shared-layer scooter through random scooter replies, owner answers, timeouts,
 *    disconnects, refused writes and failing journal saves, then reviews and restores what it left
 *    unfinished. Every note, review text, outcome text and restoration status it shows is collected: the
 *    distinct texts are pinned one by one, and the whole sequence by its SHA-256.
 *  - GuidedTest.Outcome: text(code) and describe(token) for every outcome and code.
 *  - CompatibilityReport.fieldLabel for every offset and layer (the panel's field rows).
 *  - TestJournal.UNAVAILABLE_NOTE.
 * Every case also runs with the phone set to tr-TR and de-DE.
 */
public final class CompatibilityTextTest {
    private static int checks;
    private static final String PROFILE = "owner-1";
    private static final long SEEN = 100000, NOW = SEEN + 1000, WALL = 1700000000000L;

    public static void main(String[] args) throws Exception {
        Locale saved = Locale.getDefault();
        try {
            for (Locale phone : new Locale[]{Locale.US, new Locale("tr", "TR"), Locale.GERMANY}) {
                Locale.setDefault(phone);
                outcomes();
                fieldLabels();
                check(TestJournal.UNAVAILABLE_NOTE.equals("Test records unavailable. A previous test may not have been set back; check the scooter's settings."),
                        "the unreadable-journal note");
                walk();
            }
        } finally {
            Locale.setDefault(saved);
        }
        System.out.println("CompatibilityTextTest: " + checks + " checks passed (guided test notes, reviews, outcomes and field labels under 3 phone locales)");
    }

    // ---- Outcomes ------------------------------------------------------------------------------

    private static void outcomes() {
        String[][] base = {
            {"READ_SUPPORTED", "Read supported"}, {"REJECTED", "Rejected, code N"}, {"ACK_UNCONFIRMED", "Acknowledged, unconfirmed"},
            {"READBACK_UNCHANGED", "Readback unchanged"}, {"READBACK_DIFFERENT", "Readback different"},
            {"SETTING_CONFIRMED", "Setting confirmed"}, {"EFFECT_OBSERVED", "Effect observed"},
            {"RESTORATION_CONFIRMED", "Restoration confirmed"}, {"INTERRUPTED", "Interrupted/no response"}, {"NOT_SENT", "Not sent"}};
        check(GuidedTest.Outcome.values().length == base.length, "ten outcomes");
        for (String[] row : base) {
            GuidedTest.Outcome o = GuidedTest.Outcome.valueOf(row[0]);
            equal(row[1], o.text, o + " text");
            for (int code = -1; code <= 256; code++) {
                String expected = o == GuidedTest.Outcome.REJECTED ? "Rejected, code " + code
                        : o.carries() ? row[1] + ", value " + code : row[1];
                equal(expected, o.text(code), o + " text(" + code + ")");
                if (code >= 0 && code <= 255) equal(expected, GuidedTest.Outcome.describe(o.token(code)), o + " describe(" + o.token(code) + ")");
            }
        }
        for (String token : new String[]{null, "", "ok", "REJECTED", "rejected_", "rejected_256", "readback_different", "setting_confirmed_1"})
            equal("", GuidedTest.Outcome.describe(token), "describe(" + token + ")");
    }

    // ---- Field labels --------------------------------------------------------------------------

    private static void fieldLabels() {
        String[] shared = {"Binding status", "Driving mode", "Lock status", "Cruise control", "Tail light", "Energy recovery", "Range algorithm",
            "Display units", "Auto sensor", "Tyre reminder", "Lighting byte 10 (raw)", "Traction control", "Turn sound", "Proximity key", "Night mode",
            "Range algorithm mode", "Lighting byte 16 (raw)", "Lighting byte 17 (raw)", "Lighting byte 18 (raw)", "Start speed", "Speed limit", "Volume",
            "Voice language", "Logo light", "Daytime light", "Maximum speed setting", "Drive mode setting", "", "", "", "", "Charge limit percent",
            "Low power", "Timed charging", "Auto lock time", "Speed limit eligibility", "Weather", "Slope assist", "Long range mode",
            "Overspeed control", "OSA flag (unmapped)", "Byte 41 (unread)", "Drive speed limit"};
        for (ModelRegistry.Layer layer : ModelRegistry.Layer.values()) {
            boolean ut5 = layer == ModelRegistry.Layer.UT5_MAX;
            for (int offset = -3; offset <= 70; offset++) {
                String expected = offset < 0 || offset >= shared.length ? "" : shared[offset];
                if (ut5 && offset == 10) expected = "Ambient light";
                if (ut5 && offset == 16) expected = "Eco light";
                if (ut5 && offset == 17) expected = "Drive light";
                if (ut5 && offset == 18) expected = "Sport light";
                equal(expected, CompatibilityReport.fieldLabel(offset, layer), "fieldLabel(" + offset + ", " + layer + ")");
            }
        }
    }

    // ---- Guided tests and tries ----------------------------------------------------------------

    /** The distinct texts the walk shows, as the base printed them. */
    private static final String[] TEXTS = {
        "A related setting changed during the test.",
        "A related setting changed since the test. Volt won't set the tested setting back.",
        "A setting changed while the test was waiting. Review it before setting it back.",
        "Accepted, but no conclusive reading arrived.",
        "Actual riding isn't offered now.",
        "Already back to 10 km/h.",
        "Already back to 13 km/h.",
        "Already back to 14 km/h.",
        "Already back to 15 km/h.",
        "Already back to 16 km/h.",
        "Already back to 17 km/h.",
        "Already back to 18 km/h.",
        "Already back to 19 km/h.",
        "Already back to 21 km/h.",
        "Already back to 22 km/h.",
        "Already back to 23 km/h.",
        "Already back to 25 km/h.",
        "Already back to 26 km/h.",
        "Already back to 27 km/h.",
        "Already back to 28 km/h.",
        "Already back to 29 km/h.",
        "Already back to 30 km/h.",
        "Already back to 31 km/h.",
        "Already back to 32 km/h.",
        "Already back to 6 km/h.",
        "Already back to 8 km/h.",
        "Already back to 9 km/h.",
        "Already back to D-mode estimate.",
        "Already back to Eco.",
        "Already back to Kilometers / km/h.",
        "Already back to Miles / mph.",
        "Already back to Off.",
        "Already back to On.",
        "Already back to Sound 1.",
        "Already back to Sound 2.",
        "Already back to Sound 3.",
        "Already back to Sound 4.",
        "Already back to Sound 5.",
        "Already back to Sport estimate.",
        "Another scooter profile was selected during the test.",
        "Cancelled. Nothing was changed.",
        "Changed outside the test (now 1 km/h).",
        "Changed outside the test (now 10 km/h).",
        "Changed outside the test (now 10).",
        "Changed outside the test (now 100 km/h).",
        "Changed outside the test (now 100).",
        "Changed outside the test (now 101 km/h).",
        "Changed outside the test (now 101).",
        "Changed outside the test (now 102 km/h).",
        "Changed outside the test (now 102).",
        "Changed outside the test (now 103 km/h).",
        "Changed outside the test (now 103).",
        "Changed outside the test (now 104 km/h).",
        "Changed outside the test (now 104).",
        "Changed outside the test (now 105 km/h).",
        "Changed outside the test (now 105).",
        "Changed outside the test (now 106 km/h).",
        "Changed outside the test (now 106).",
        "Changed outside the test (now 107 km/h)",
        "Changed outside the test (now 107 km/h).",
        "Changed outside the test (now 107).",
        "Changed outside the test (now 108 km/h).",
        "Changed outside the test (now 109 km/h).",
        "Changed outside the test (now 11 km/h).",
        "Changed outside the test (now 11).",
        "Changed outside the test (now 110 km/h).",
        "Changed outside the test (now 110).",
        "Changed outside the test (now 111 km/h).",
        "Changed outside the test (now 111).",
        "Changed outside the test (now 112 km/h).",
        "Changed outside the test (now 113 km/h).",
        "Changed outside the test (now 113).",
        "Changed outside the test (now 114 km/h).",
        "Changed outside the test (now 115 km/h).",
        "Changed outside the test (now 115).",
        "Changed outside the test (now 116 km/h).",
        "Changed outside the test (now 116).",
        "Changed outside the test (now 117 km/h).",
        "Changed outside the test (now 117).",
        "Changed outside the test (now 118 km/h).",
        "Changed outside the test (now 118).",
        "Changed outside the test (now 119 km/h).",
        "Changed outside the test (now 119).",
        "Changed outside the test (now 12 km/h).",
        "Changed outside the test (now 12).",
        "Changed outside the test (now 120 km/h).",
        "Changed outside the test (now 120).",
        "Changed outside the test (now 121 km/h).",
        "Changed outside the test (now 122 km/h).",
        "Changed outside the test (now 122).",
        "Changed outside the test (now 123 km/h).",
        "Changed outside the test (now 123).",
        "Changed outside the test (now 124 km/h).",
        "Changed outside the test (now 124).",
        "Changed outside the test (now 125 km/h).",
        "Changed outside the test (now 125).",
        "Changed outside the test (now 126 km/h).",
        "Changed outside the test (now 126).",
        "Changed outside the test (now 127 km/h).",
        "Changed outside the test (now 127).",
        "Changed outside the test (now 128).",
        "Changed outside the test (now 129).",
        "Changed outside the test (now 13 km/h).",
        "Changed outside the test (now 13).",
        "Changed outside the test (now 130).",
        "Changed outside the test (now 131).",
        "Changed outside the test (now 132).",
        "Changed outside the test (now 133).",
        "Changed outside the test (now 134).",
        "Changed outside the test (now 135).",
        "Changed outside the test (now 138).",
        "Changed outside the test (now 139).",
        "Changed outside the test (now 14 km/h)",
        "Changed outside the test (now 14 km/h).",
        "Changed outside the test (now 14).",
        "Changed outside the test (now 140).",
        "Changed outside the test (now 142).",
        "Changed outside the test (now 143).",
        "Changed outside the test (now 144).",
        "Changed outside the test (now 145).",
        "Changed outside the test (now 146).",
        "Changed outside the test (now 147).",
        "Changed outside the test (now 15 km/h).",
        "Changed outside the test (now 150).",
        "Changed outside the test (now 151).",
        "Changed outside the test (now 153).",
        "Changed outside the test (now 154).",
        "Changed outside the test (now 156).",
        "Changed outside the test (now 157).",
        "Changed outside the test (now 158).",
        "Changed outside the test (now 159).",
        "Changed outside the test (now 16 km/h).",
        "Changed outside the test (now 16).",
        "Changed outside the test (now 160).",
        "Changed outside the test (now 161).",
        "Changed outside the test (now 163).",
        "Changed outside the test (now 164).",
        "Changed outside the test (now 165).",
        "Changed outside the test (now 167).",
        "Changed outside the test (now 168).",
        "Changed outside the test (now 169).",
        "Changed outside the test (now 17 km/h).",
        "Changed outside the test (now 17).",
        "Changed outside the test (now 170).",
        "Changed outside the test (now 171).",
        "Changed outside the test (now 173).",
        "Changed outside the test (now 175).",
        "Changed outside the test (now 177).",
        "Changed outside the test (now 178).",
        "Changed outside the test (now 179).",
        "Changed outside the test (now 18 km/h).",
        "Changed outside the test (now 18).",
        "Changed outside the test (now 180).",
        "Changed outside the test (now 181).",
        "Changed outside the test (now 182).",
        "Changed outside the test (now 183).",
        "Changed outside the test (now 184).",
        "Changed outside the test (now 185).",
        "Changed outside the test (now 186).",
        "Changed outside the test (now 187).",
        "Changed outside the test (now 189).",
        "Changed outside the test (now 19 km/h).",
        "Changed outside the test (now 19).",
        "Changed outside the test (now 190).",
        "Changed outside the test (now 191).",
        "Changed outside the test (now 192).",
        "Changed outside the test (now 194).",
        "Changed outside the test (now 195).",
        "Changed outside the test (now 196).",
        "Changed outside the test (now 197).",
        "Changed outside the test (now 198).",
        "Changed outside the test (now 199).",
        "Changed outside the test (now 2 km/h).",
        "Changed outside the test (now 20 km/h)",
        "Changed outside the test (now 20 km/h).",
        "Changed outside the test (now 201).",
        "Changed outside the test (now 204).",
        "Changed outside the test (now 205).",
        "Changed outside the test (now 206).",
        "Changed outside the test (now 207).",
        "Changed outside the test (now 208).",
        "Changed outside the test (now 209).",
        "Changed outside the test (now 21 km/h).",
        "Changed outside the test (now 21).",
        "Changed outside the test (now 210).",
        "Changed outside the test (now 211).",
        "Changed outside the test (now 212).",
        "Changed outside the test (now 213).",
        "Changed outside the test (now 214).",
        "Changed outside the test (now 215).",
        "Changed outside the test (now 216).",
        "Changed outside the test (now 217).",
        "Changed outside the test (now 218).",
        "Changed outside the test (now 219).",
        "Changed outside the test (now 22 km/h).",
        "Changed outside the test (now 22).",
        "Changed outside the test (now 221).",
        "Changed outside the test (now 222).",
        "Changed outside the test (now 224).",
        "Changed outside the test (now 225).",
        "Changed outside the test (now 227).",
        "Changed outside the test (now 228).",
        "Changed outside the test (now 229).",
        "Changed outside the test (now 23 km/h).",
        "Changed outside the test (now 23).",
        "Changed outside the test (now 230).",
        "Changed outside the test (now 232).",
        "Changed outside the test (now 233).",
        "Changed outside the test (now 234).",
        "Changed outside the test (now 235).",
        "Changed outside the test (now 236).",
        "Changed outside the test (now 237).",
        "Changed outside the test (now 238).",
        "Changed outside the test (now 239).",
        "Changed outside the test (now 24 km/h).",
        "Changed outside the test (now 24).",
        "Changed outside the test (now 242).",
        "Changed outside the test (now 243).",
        "Changed outside the test (now 244).",
        "Changed outside the test (now 246).",
        "Changed outside the test (now 247).",
        "Changed outside the test (now 248).",
        "Changed outside the test (now 249).",
        "Changed outside the test (now 25 km/h).",
        "Changed outside the test (now 25).",
        "Changed outside the test (now 250).",
        "Changed outside the test (now 253).",
        "Changed outside the test (now 254).",
        "Changed outside the test (now 255).",
        "Changed outside the test (now 26 km/h).",
        "Changed outside the test (now 26).",
        "Changed outside the test (now 27 km/h)",
        "Changed outside the test (now 27 km/h).",
        "Changed outside the test (now 27).",
        "Changed outside the test (now 28 km/h).",
        "Changed outside the test (now 28).",
        "Changed outside the test (now 29 km/h).",
        "Changed outside the test (now 3 km/h).",
        "Changed outside the test (now 30 km/h).",
        "Changed outside the test (now 31 km/h).",
        "Changed outside the test (now 31).",
        "Changed outside the test (now 32 km/h).",
        "Changed outside the test (now 32).",
        "Changed outside the test (now 33 km/h).",
        "Changed outside the test (now 33).",
        "Changed outside the test (now 34 km/h).",
        "Changed outside the test (now 34).",
        "Changed outside the test (now 35 km/h).",
        "Changed outside the test (now 35).",
        "Changed outside the test (now 36 km/h).",
        "Changed outside the test (now 36).",
        "Changed outside the test (now 37 km/h).",
        "Changed outside the test (now 37).",
        "Changed outside the test (now 38 km/h).",
        "Changed outside the test (now 38).",
        "Changed outside the test (now 39 km/h).",
        "Changed outside the test (now 39).",
        "Changed outside the test (now 4 km/h).",
        "Changed outside the test (now 4).",
        "Changed outside the test (now 40 km/h).",
        "Changed outside the test (now 41 km/h).",
        "Changed outside the test (now 42 km/h).",
        "Changed outside the test (now 43 km/h).",
        "Changed outside the test (now 43).",
        "Changed outside the test (now 44 km/h).",
        "Changed outside the test (now 44).",
        "Changed outside the test (now 45 km/h).",
        "Changed outside the test (now 46 km/h).",
        "Changed outside the test (now 46).",
        "Changed outside the test (now 47 km/h).",
        "Changed outside the test (now 47).",
        "Changed outside the test (now 48 km/h).",
        "Changed outside the test (now 48).",
        "Changed outside the test (now 49 km/h).",
        "Changed outside the test (now 49).",
        "Changed outside the test (now 5 km/h).",
        "Changed outside the test (now 5).",
        "Changed outside the test (now 50 km/h).",
        "Changed outside the test (now 50).",
        "Changed outside the test (now 51 km/h).",
        "Changed outside the test (now 52 km/h).",
        "Changed outside the test (now 52).",
        "Changed outside the test (now 53 km/h).",
        "Changed outside the test (now 53).",
        "Changed outside the test (now 54 km/h).",
        "Changed outside the test (now 54).",
        "Changed outside the test (now 55 km/h).",
        "Changed outside the test (now 55).",
        "Changed outside the test (now 56 km/h).",
        "Changed outside the test (now 56).",
        "Changed outside the test (now 57 km/h).",
        "Changed outside the test (now 57).",
        "Changed outside the test (now 58 km/h).",
        "Changed outside the test (now 58).",
        "Changed outside the test (now 59 km/h).",
        "Changed outside the test (now 59).",
        "Changed outside the test (now 6 km/h).",
        "Changed outside the test (now 6).",
        "Changed outside the test (now 60 km/h).",
        "Changed outside the test (now 60).",
        "Changed outside the test (now 61 km/h).",
        "Changed outside the test (now 61).",
        "Changed outside the test (now 62 km/h).",
        "Changed outside the test (now 62).",
        "Changed outside the test (now 63 km/h).",
        "Changed outside the test (now 63).",
        "Changed outside the test (now 64 km/h).",
        "Changed outside the test (now 65 km/h).",
        "Changed outside the test (now 66 km/h).",
        "Changed outside the test (now 66).",
        "Changed outside the test (now 67 km/h).",
        "Changed outside the test (now 68 km/h).",
        "Changed outside the test (now 68).",
        "Changed outside the test (now 69 km/h).",
        "Changed outside the test (now 69).",
        "Changed outside the test (now 7 km/h).",
        "Changed outside the test (now 7).",
        "Changed outside the test (now 70 km/h).",
        "Changed outside the test (now 71 km/h).",
        "Changed outside the test (now 72 km/h).",
        "Changed outside the test (now 72).",
        "Changed outside the test (now 73 km/h).",
        "Changed outside the test (now 73).",
        "Changed outside the test (now 74 km/h).",
        "Changed outside the test (now 74).",
        "Changed outside the test (now 75 km/h).",
        "Changed outside the test (now 76 km/h).",
        "Changed outside the test (now 77 km/h).",
        "Changed outside the test (now 77).",
        "Changed outside the test (now 78 km/h).",
        "Changed outside the test (now 78).",
        "Changed outside the test (now 79 km/h).",
        "Changed outside the test (now 79).",
        "Changed outside the test (now 8 km/h).",
        "Changed outside the test (now 8).",
        "Changed outside the test (now 80 km/h).",
        "Changed outside the test (now 80).",
        "Changed outside the test (now 81 km/h).",
        "Changed outside the test (now 81)",
        "Changed outside the test (now 81).",
        "Changed outside the test (now 82 km/h).",
        "Changed outside the test (now 82)",
        "Changed outside the test (now 82).",
        "Changed outside the test (now 83 km/h).",
        "Changed outside the test (now 83).",
        "Changed outside the test (now 84 km/h).",
        "Changed outside the test (now 85 km/h).",
        "Changed outside the test (now 85).",
        "Changed outside the test (now 86 km/h).",
        "Changed outside the test (now 86).",
        "Changed outside the test (now 87 km/h).",
        "Changed outside the test (now 87).",
        "Changed outside the test (now 88 km/h).",
        "Changed outside the test (now 88).",
        "Changed outside the test (now 89 km/h).",
        "Changed outside the test (now 89).",
        "Changed outside the test (now 9 km/h).",
        "Changed outside the test (now 9).",
        "Changed outside the test (now 90 km/h).",
        "Changed outside the test (now 90).",
        "Changed outside the test (now 91 km/h).",
        "Changed outside the test (now 91).",
        "Changed outside the test (now 92 km/h).",
        "Changed outside the test (now 92).",
        "Changed outside the test (now 93 km/h).",
        "Changed outside the test (now 93).",
        "Changed outside the test (now 94 km/h).",
        "Changed outside the test (now 94).",
        "Changed outside the test (now 95 km/h).",
        "Changed outside the test (now 95).",
        "Changed outside the test (now 96 km/h).",
        "Changed outside the test (now 96).",
        "Changed outside the test (now 97 km/h).",
        "Changed outside the test (now 97).",
        "Changed outside the test (now 98 km/h).",
        "Changed outside the test (now 98).",
        "Changed outside the test (now 99 km/h).",
        "Changed outside the test (now 99).",
        "Changed outside the test (now Off).",
        "Changed outside the test (now Sound 5).",
        "Connect to this scooter to review.",
        "Couldn't get a fresh baseline. Reconnect and try again. Nothing was changed.",
        "Couldn't save the test record, so Volt did not set it back.",
        "Couldn't save the test record. Nothing was changed.",
        "Couldn't verify the setting. Reconnect and review the unfinished test.",
        "D-mode estimate isn't offered now.",
        "Eco estimate isn't offered now.",
        "Firmware changed since the test.",
        "Firmware not read since the test.",
        "It shows the test value, 10 km/h.",
        "It shows the test value, 20 km/h.",
        "It shows the test value, 32 km/h.",
        "It shows the test value, 8 km/h.",
        "It shows the test value, 9 km/h.",
        "It shows the test value, D-mode estimate.",
        "It shows the test value, Miles / mph.",
        "It shows the test value, Off.",
        "It shows the test value, On.",
        "It shows the test value, Sound 1.",
        "It shows the test value, Sound 3.",
        "It shows the test value, Sound 4.",
        "It shows the test value, Sport estimate.",
        "It shows the test value, Theoretical.",
        "It still shows 101 km/h.",
        "It still shows 105.",
        "It still shows 11.",
        "It still shows 117.",
        "It still shows 119 km/h.",
        "It still shows 120 km/h.",
        "It still shows 14 km/h.",
        "It still shows 154.",
        "It still shows 17 km/h.",
        "It still shows 187.",
        "It still shows 238.",
        "It still shows 27 km/h.",
        "It still shows 31 km/h.",
        "It still shows 32 km/h.",
        "It still shows 36 km/h.",
        "It still shows 5 km/h.",
        "It still shows 53 km/h.",
        "It still shows 54.",
        "It still shows 57.",
        "It still shows 59.",
        "It still shows 61 km/h.",
        "It still shows 63 km/h.",
        "It still shows 66 km/h.",
        "It still shows 68.",
        "It still shows 76 km/h.",
        "It still shows 8 km/h.",
        "It still shows 8.",
        "It still shows 85 km/h.",
        "It still shows 89.",
        "It still shows Kilometers / km/h.",
        "It still shows Miles / mph.",
        "It still shows Off.",
        "It still shows Single motor.",
        "It still shows Sport estimate.",
        "It still shows Theoretical.",
        "It's already set to 10 km/h.",
        "It's already set to 20 km/h.",
        "It's already set to 32 km/h.",
        "It's already set to 8 km/h.",
        "It's already set to 9 km/h.",
        "It's already set to Actual riding.",
        "It's already set to D-mode estimate.",
        "It's already set to Dual motor.",
        "It's already set to Eco estimate.",
        "It's already set to Eco.",
        "It's already set to Kilometers / km/h.",
        "It's already set to Miles / mph.",
        "It's already set to Off.",
        "It's already set to On.",
        "It's already set to Single motor.",
        "It's already set to Sound 1.",
        "It's already set to Sound 2.",
        "It's already set to Sound 3.",
        "It's already set to Sound 4.",
        "It's already set to Sport estimate.",
        "It's already set to Theoretical.",
        "It's already set to Turbo.",
        "Kept, but the record couldn't be saved. It will show as unfinished.",
        "Model ID differs from the test. Volt won't change it.",
        "No fresh reading arrived.",
        "No reply from the scooter.",
        "Set back and confirmed, but the record couldn't be saved.",
        "Sport estimate isn't offered now.",
        "Still on the test value, 10 km/h.",
        "Still on the test value, 20 km/h.",
        "Still on the test value, 32 km/h.",
        "Still on the test value, 8 km/h.",
        "Still on the test value, 9 km/h.",
        "Still on the test value, Dual motor.",
        "Still on the test value, Eco.",
        "Still on the test value, Kilometers / km/h.",
        "Still on the test value, Miles / mph.",
        "Still on the test value, Off.",
        "Still on the test value, On.",
        "Still on the test value, Single motor.",
        "Still on the test value, Sound 1.",
        "Still on the test value, Sound 2.",
        "Still on the test value, Sound 3.",
        "Still on the test value, Sound 4.",
        "Still on the test value, Sport estimate.",
        "Stopped before any change. Nothing was changed.",
        "The connection ended during the test.",
        "The current value can't be set back, so Volt won't test it.",
        "The current value is unknown, so Volt won't test it.",
        "The scooter accepted it but still shows 10 km/h.",
        "The scooter accepted it but still shows 11 km/h.",
        "The scooter accepted it but still shows 12 km/h.",
        "The scooter accepted it but still shows 13 km/h.",
        "The scooter accepted it but still shows 14 km/h.",
        "The scooter accepted it but still shows 15 km/h.",
        "The scooter accepted it but still shows 17 km/h.",
        "The scooter accepted it but still shows 18 km/h.",
        "The scooter accepted it but still shows 19 km/h.",
        "The scooter accepted it but still shows 20 km/h.",
        "The scooter accepted it but still shows 21 km/h.",
        "The scooter accepted it but still shows 23 km/h.",
        "The scooter accepted it but still shows 24 km/h.",
        "The scooter accepted it but still shows 25 km/h.",
        "The scooter accepted it but still shows 26 km/h.",
        "The scooter accepted it but still shows 27 km/h.",
        "The scooter accepted it but still shows 28 km/h.",
        "The scooter accepted it but still shows 29 km/h.",
        "The scooter accepted it but still shows 30 km/h.",
        "The scooter accepted it but still shows 31 km/h.",
        "The scooter accepted it but still shows 32 km/h.",
        "The scooter accepted it but still shows 8 km/h.",
        "The scooter accepted it but still shows 9 km/h.",
        "The scooter accepted it but still shows D-mode estimate.",
        "The scooter accepted it but still shows Eco.",
        "The scooter accepted it but still shows Kilometers / km/h.",
        "The scooter accepted it but still shows Miles / mph.",
        "The scooter accepted it but still shows Off.",
        "The scooter accepted it but still shows On.",
        "The scooter accepted it but still shows Single motor.",
        "The scooter accepted it but still shows Sound 2.",
        "The scooter accepted it but still shows Sound 3.",
        "The scooter accepted it but still shows Sound 4.",
        "The scooter accepted it but still shows Sound 5.",
        "The scooter accepted it but still shows Theoretical.",
        "The scooter isn't reporting a related setting now.",
        "The scooter isn't reporting this setting now.",
        "The scooter now shows 1 km/h.",
        "The scooter now shows 10 km/h. Nothing was changed.",
        "The scooter now shows 106 km/h.",
        "The scooter now shows 108.",
        "The scooter now shows 109 km/h.",
        "The scooter now shows 112 km/h.",
        "The scooter now shows 117 km/h.",
        "The scooter now shows 12 km/h. Nothing was changed.",
        "The scooter now shows 121 km/h.",
        "The scooter now shows 122 km/h.",
        "The scooter now shows 124 km/h.",
        "The scooter now shows 126 km/h.",
        "The scooter now shows 14 km/h.",
        "The scooter now shows 14 km/h. Nothing was changed.",
        "The scooter now shows 144.",
        "The scooter now shows 15 km/h. Nothing was changed.",
        "The scooter now shows 16 km/h. Nothing was changed.",
        "The scooter now shows 17 km/h. Nothing was changed.",
        "The scooter now shows 171.",
        "The scooter now shows 175.",
        "The scooter now shows 18 km/h. Nothing was changed.",
        "The scooter now shows 20 km/h. Nothing was changed.",
        "The scooter now shows 203.",
        "The scooter now shows 209.",
        "The scooter now shows 21 km/h. Nothing was changed.",
        "The scooter now shows 22 km/h. Nothing was changed.",
        "The scooter now shows 23 km/h. Nothing was changed.",
        "The scooter now shows 24 km/h. Nothing was changed.",
        "The scooter now shows 246.",
        "The scooter now shows 25 km/h. Nothing was changed.",
        "The scooter now shows 26 km/h. Nothing was changed.",
        "The scooter now shows 28 km/h. Nothing was changed.",
        "The scooter now shows 29 km/h. Nothing was changed.",
        "The scooter now shows 30 km/h.",
        "The scooter now shows 30 km/h. Nothing was changed.",
        "The scooter now shows 31 km/h. Nothing was changed.",
        "The scooter now shows 41 km/h.",
        "The scooter now shows 42.",
        "The scooter now shows 5 km/h. Nothing was changed.",
        "The scooter now shows 55 km/h.",
        "The scooter now shows 57 km/h.",
        "The scooter now shows 59 km/h.",
        "The scooter now shows 60.",
        "The scooter now shows 63 km/h.",
        "The scooter now shows 64.",
        "The scooter now shows 65 km/h.",
        "The scooter now shows 70 km/h.",
        "The scooter now shows 78 km/h.",
        "The scooter now shows 8 km/h. Nothing was changed.",
        "The scooter now shows 81 km/h.",
        "The scooter now shows 81.",
        "The scooter now shows 85 km/h.",
        "The scooter now shows 93 km/h.",
        "The scooter now shows 95 km/h.",
        "The scooter now shows D-mode estimate. Nothing was changed.",
        "The scooter now shows Eco. Nothing was changed.",
        "The scooter now shows Kilometers / km/h. Nothing was changed.",
        "The scooter now shows Off. Nothing was changed.",
        "The scooter now shows On. Nothing was changed.",
        "The scooter now shows Single motor. Nothing was changed.",
        "The scooter now shows Sound 1. Nothing was changed.",
        "The scooter now shows Sound 3. Nothing was changed.",
        "The scooter now shows Sound 4. Nothing was changed.",
        "The scooter now shows Sound 5. Nothing was changed.",
        "The scooter now shows Theoretical. Nothing was changed.",
        "The scooter rejected setting it back (code 1).",
        "The scooter rejected setting it back (code 2).",
        "The scooter rejected setting it back (code 3).",
        "The scooter rejected setting it back (code 4).",
        "Theoretical isn't offered now.",
        "This scooter isn't reporting this setting.",
        "Volt could not send the command to set it back.",
        "Volt restarted during the test.",
        "Volt's rules for this setting changed. Volt won't change it.",
        "Waiting for a fresh reading."
    };
    /** SHA-256 of the whole walk's record (209177042 bytes of UTF-8) at the base. */
    private static final String DIGEST = "0933469d7feb44fa4c35b47437bcc8e49aab769e83e7099628ff99e1734dc846";

    static final class Memory implements TestJournal.Storage {
        String text; boolean failWrites;
        @Override public String read() { return text; }
        @Override public void writeAtomic(String t) throws java.io.IOException { if (failWrites) throw new java.io.IOException("disk full"); text = t; }
    }

    private static final DeviceDescriptor.Firmware FW = new DeviceDescriptor.Firmware("2.0.3.1", null, null, null, null, SEEN);
    private static final DeviceDescriptor.Firmware OTHER_FW = new DeviceDescriptor.Firmware("2.0.4.0", null, null, null, null, SEEN);
    private static DeviceDescriptor device(String serial, String pid, int source, DeviceDescriptor.Firmware fw, boolean thisSession) {
        return DeviceDescriptor.of(serial, pid, source, fw, thisSession, DeviceDescriptor.Transport.STANDARD, 0);
    }
    private static DeviceDescriptor[] devices() {
        return new DeviceDescriptor[]{
            device("Q2538ZZ9US7777777", "", 0, FW, true), device("Q2443ZZ9US7777777", "24431", 1, FW, true),
            device("Q9999ZZ9US7777777", "", 0, FW, true)};
    }
    /** The same scooter with other meter firmware, read this session or not. */
    private static DeviceDescriptor changedFirmware(int index, boolean thisSession) {
        return index == 0 ? device("Q2538ZZ9US7777777", "", 0, OTHER_FW, thisSession) : index == 1 ? device("Q2443ZZ9US7777777", "24431", 1, OTHER_FW, thisSession)
                : device("Q9999ZZ9US7777777", "", 0, OTHER_FW, thisSession);
    }
    /** CapabilityResolverTest's full status fixture: units km, range set 1, tail light and automatic lights on, turn sound off. */
    private static byte[] full() {
        byte[] d = new byte[44];
        d[1]=3;d[2]=0;d[3]=1;d[4]=1;d[5]=60;d[6]=2;d[7]=1;d[8]=1;d[10]=(byte)0x81;d[11]=1;d[12]=0;d[13]=0;d[15]=1;d[16]=0x01;d[17]=(byte)0x82;d[18]=0x10;
        d[19]=3;d[20]=(byte)0x99;d[21]=2;d[25]=40;d[26]=0;d[27]=0x01;d[28]=0x02;d[29]=0x03;d[30]=0x04;d[31]=90;d[32]=0;d[33]=1;d[34]=2;d[37]=5;d[38]=0;d[39]=0;d[42]=(byte)0x8f;
        return d;
    }
    /** A 0x70 notification frame with status 0 around the data, as the scooter sends it. */
    private static StatusFields status(byte[] payload, long seen) {
        byte[] d = new byte[payload.length + 1]; System.arraycopy(payload, 0, d, 1, payload.length);
        byte[] p = new byte[d.length + 8]; p[0] = 0x55; p[1] = (byte) 0xaa; p[3] = 0x70; p[4] = (byte) d.length;
        System.arraycopy(d, 0, p, 5, d.length); int sum = 0; for (int i = 0; i < p.length - 3; i++) sum += p[i] & 255;
        p[p.length - 3] = (byte) sum; p[p.length - 2] = (byte) 0xfe; p[p.length - 1] = (byte) 0xfd;
        return StatusFields.of(Protocol.parse(p), seen);
    }
    /** Parked, owner, bonded and ready, as CapabilityResolverTest.parked(). */
    private static CapabilityResolver.Runtime parked(long now) {
        CapabilityResolver.Runtime rt = new CapabilityResolver.Runtime();
        rt.ready = true; rt.stationary = true; rt.motionSeen = true; rt.owner = true; rt.bonded = true; rt.pinEnabledFresh = true; rt.nowMs = now;
        return rt;
    }

    /** The walk's record, hashed as it grows (it is far too long to keep). */
    private static final StringBuilder trace = new StringBuilder();
    private static MessageDigest hash;
    private static long traced;
    private static void flush() {
        byte[] bytes = trace.toString().getBytes(StandardCharsets.UTF_8);
        hash.update(bytes); traced += bytes.length; trace.setLength(0);
    }
    private static final TreeSet<String> shown = new TreeSet<>();

    private static void walk() throws Exception {
        trace.setLength(0); shown.clear(); hash = MessageDigest.getInstance("SHA-256"); traced = 0;
        String[] keys = keys();
        for (int index = 0; index < 3; index++) {
            DeviceDescriptor d = devices()[index];
            StatusFields s = status(full(), SEEN);
            for (String feature : keys) {
                for (int kind = 0; kind < 3; kind++) {
                    CapabilityResolver.Decision decision = kind == 0 ? CapabilityResolver.decideTest(feature, d, s, parked(NOW))
                            : kind == 1 ? CapabilityResolver.decideExperimental(feature, d, s, parked(NOW)) : CapabilityResolver.decideVerify(feature, d, s, parked(NOW));
                    if (decision == null || !decision.available() || decision.variant == null || !decision.variant.readable) continue;
                    FeatureVariant v = decision.variant;
                    String journalKind = kind == 0 ? TestJournal.TEST : kind == 1 ? TestJournal.EXPERIMENTAL : TestJournal.VERIFY;
                    for (int target : targets(v)) {
                        for (int seed = 0; seed < 16; seed++) run(index, d, v, journalKind, target, seed, -1);
                        // Every status the walk knows as the scooter's value when the test starts.
                        for (int start = 0; start < statuses(v).size(); start++) run(index, d, v, journalKind, target, 100 + start, start);
                    }
                }
            }
        }
        flush();
        StringBuilder hex = new StringBuilder();
        for (byte b : hash.digest()) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
        String digest = hex.toString();
        List<String> texts = new ArrayList<>(shown);
        if (!texts.equals(Arrays.asList(TEXTS)) || !digest.equals(DIGEST)) {
            StringBuilder b = new StringBuilder();
            for (String t : texts) b.append("        \"").append(t.replace("\\", "\\\\").replace("\"", "\\\"")).append("\",\n");
            throw new AssertionError("guided test texts differ from the base (" + Locale.getDefault() + "):\n" + b + "digest " + digest
                    + " (" + traced + " bytes)");
        }
        checks += texts.size() + 1;
    }

    private static String[] keys() {
        List<String> out = new ArrayList<>(Arrays.asList(ModelRegistry.TESTABLE));
        for (ScooterCommands.Feature f : ScooterCommands.catalog()) if (!out.contains(f.key)) out.add(f.key);
        for (ScooterCommands.Feature f : ScooterCommands.oemOnly()) if (!out.contains(f.key)) out.add(f.key);
        return out.toArray(new String[0]);
    }

    private static int[] targets(FeatureVariant v) {
        if (v.values.length > 0) return v.values.length > 4 ? Arrays.copyOf(v.values, 4) : v.values;
        return new int[]{v.min, v.max, v.min + v.step};
    }

    /** One raw byte for each value the variant reads at its offset, with the base fixture's byte first. */
    private static List<byte[]> statuses(FeatureVariant v) {
        List<byte[]> out = new ArrayList<>();
        byte[] base = full();
        out.add(base);
        List<Integer> seen = new ArrayList<>();
        seen.add(v.read(status(base, SEEN)));
        if (v.offset >= 0 && v.offset < base.length) {
            for (int raw = 0; raw < 256; raw++) {
                byte[] d = base.clone(); d[v.offset] = (byte) raw;
                Integer value = v.read(status(d, SEEN));
                if (!seen.contains(value)) { seen.add(value); out.add(d); }
            }
        }
        // The range choice set (offset 15) decides which range algorithms are offered.
        for (byte[] d : new ArrayList<>(out)) for (int set = 0; set < 4; set++) if (d[15] != set) { byte[] c = d.clone(); c[15] = (byte) set; out.add(c); }
        // A short status that lacks the setting, or lacks the related range choice set.
        for (int length : new int[]{4, 9, 14}) out.add(Arrays.copyOf(base, length));
        // A related setting changed: the range choice set, or the other bits of a masked byte.
        byte[] related = base.clone(); related[15] = (byte) (related[15] == 1 ? 2 : 1); out.add(related);
        if (v.offset >= 0 && v.offset < base.length) { byte[] bits = base.clone(); bits[v.offset] = (byte) (bits[v.offset] ^ 0x40); out.add(bits); }
        return out;
    }

    private static void run(int index, DeviceDescriptor d, FeatureVariant v, String kind, int target, int seed, int start) throws Exception {
        Random r = new Random(seed * 7919L + target * 31L + kind.hashCode() + v.id.hashCode());
        Memory m = new Memory(); TestJournal j = new TestJournal(m);
        List<byte[]> data = statuses(v);
        long now = NOW;
        GuidedTest t;
        try {
            if (TestJournal.TEST.equals(kind)) t = new GuidedTest(j, PROFILE, d, v, target, now, WALL, new Random(seed));
            else {
                Integer expected = v.read(status(data.get(start >= 0 ? start : r.nextInt(3) == 0 ? r.nextInt(data.size()) : 0), SEEN));
                t = GuidedTest.trial(j, PROFILE, d, v, kind, target, expected == null ? 0 : expected, now, WALL, new Random(seed));
            }
        } catch (IllegalArgumentException refused) {
            trace.append("refused ").append(v.id).append(' ').append(kind).append(' ').append(target).append('\n');
            return;
        }
        trace.append("run ").append(v.id).append(' ').append(kind).append(' ').append(target).append(' ').append(seed).append('\n');
        byte[][] scooter = {data.get(start >= 0 ? start : seed % 2 == 0 ? 0 : r.nextInt(data.size()))};
        now = drive(t, v, data, scooter, r, m, now, 40);
        // What the walk left unfinished: reviewed, then restored where the review allows it.
        TestJournal reopened = new TestJournal(m);
        if (r.nextBoolean()) trace.append("recovered ").append(GuidedTest.recover(reopened, WALL + now)).append('\n');
        for (TestJournal.Entry e : reopened.all(PROFILE)) {
            // A fresh status that lacks the setting or the related range choice set.
            for (int length : new int[]{4, 9, 14}) {
                GuidedTest.Review review = GuidedTest.review(e, PROFILE, true, d, status(Arrays.copyOf(full(), length), now), now, v, false);
                record("short review " + length + " " + review.state + " " + review.current + " " + review.restorable, review.text);
            }
            for (int i = 0; i < 8; i++) {
                DeviceDescriptor reviewed = i == 0 ? devices()[(index + 1) % 3] : i == 1 ? changedFirmware(index, r.nextBoolean()) : d;
                byte[] chosen = r.nextBoolean() ? scooter[0] : data.get(r.nextInt(data.size()));
                StatusFields s = r.nextInt(6) == 0 ? null : status(chosen, r.nextInt(5) == 0 ? 1 : now);
                FeatureVariant variant = r.nextInt(8) == 0 ? null : v;
                GuidedTest.Review review = GuidedTest.review(e, i == 2 ? "other" : PROFILE, i != 3, reviewed, s, now, variant, r.nextInt(4) == 0);
                record("review " + review.state + " " + review.current + " " + review.restorable, review.text);
                if ((review.state == GuidedTest.ReviewState.RESTORE_NEEDED || review.state == GuidedTest.ReviewState.CHANGED_OUTSIDE) && review.restorable && r.nextBoolean()) {
                    try {
                        GuidedTest back = GuidedTest.restoreFrom(reopened, e, v, d, s, now, WALL + now);
                        trace.append("restore\n");
                        scooter[0] = chosen; now = drive(back, v, data, scooter, r, m, now, 24);
                    } catch (java.io.IOException | IllegalArgumentException refused) {
                        trace.append("restore refused\n");
                    }
                    break;
                }
            }
        }
    }

    /**
     * A simulated scooter that mostly follows the protocol (reads answered, a written value applied) with random
     * faults: lost or empty replies, rejections, no answer, Volt refusing the write, a value changed outside the
     * test, interruptions, failing journal saves and time jumps. What the test shows is recorded after each step.
     */
    private static long drive(GuidedTest t, FeatureVariant v, List<byte[]> data, byte[][] scooter, Random r, Memory m, long now, int steps) {
        for (int step = 0; step < steps; step++) {
            now += r.nextInt(12) == 0 ? 11000 : 50 + r.nextInt(900);
            double x = r.nextDouble();
            String event;
            if (x < 0.03) { int which = r.nextInt(3); event = "interrupt " + which; if (which == 0) t.onDisconnect(now); else if (which == 1) t.onProfileSwitch(now); else t.onProcessStart(now); }
            else if (x < 0.07) { m.failWrites = r.nextInt(3) == 0; event = "saves " + !m.failWrites; }
            else if (x < 0.10) { scooter[0] = data.get(r.nextInt(data.size())); event = "changed outside"; }
            else if (x < 0.12) { now += 11000; t.tick(now); event = "tick"; }
            else if (x < 0.14) { event = "admits " + t.admitsQueuedWrite(status(r.nextBoolean() ? scooter[0] : data.get(r.nextInt(data.size())), now), now); }
            else if (x < 0.15) { event = "cancel " + t.cancel(); }
            else if (x < 0.17) { if (r.nextBoolean()) t.onStatus(null, now); else t.onReadRequested(0x71, now); event = "noise"; }
            else if (x < 0.19) { t.onMutationTimeout(r.nextBoolean(), now); event = "timeout"; }
            else if (t.wantsRead()) {
                t.onReadRequested(0x70, now);
                int fate = r.nextInt(20);
                if (fate < 16) t.onStatus(status(scooter[0], now), now); else if (fate < 18) t.onStatus(null, now);
                event = "read " + fate;
            } else if (t.phase() == GuidedTest.Phase.BASELINE) {
                event = "approve " + t.approve(now);
            } else if (t.phase() == GuidedTest.Phase.OBSERVE) {
                if (r.nextInt(4) == 0) { t.onReadRequested(0x70, now); t.onStatus(status(scooter[0], now), now); event = "observe read"; }
                else { if (r.nextInt(6) == 0) m.failWrites = true; int answer = r.nextInt(3); t.ownerEffect(answer == 0 ? null : answer == 1, now); m.failWrites = false; event = "answer " + answer; }
            } else {
                Integer w = t.writeNow();
                if (w == null) { if (r.nextBoolean()) { t.onReadRequested(0x70, now); t.onStatus(status(scooter[0], now), now); } else t.tick(now); event = "wait"; }
                else {
                    int fate = r.nextInt(10);
                    byte[] after = valueBytes(v, data, w);
                    if (fate < 5) { if (after != null) scooter[0] = after; t.onAck(v.cmd, 0, now); }
                    else if (fate == 5) t.onAck(v.cmd, 0, now);
                    else if (fate == 6) { if (after != null && r.nextInt(3) == 0) scooter[0] = after; t.onAck(v.cmd, 1 + r.nextInt(4), now); }
                    else if (fate == 7) { if (after != null && r.nextInt(3) == 0) scooter[0] = after; t.onWriteRefused(now); }
                    else if (fate == 8) t.onMutationTimeout(false, now);
                    else { t.onAck(v.cmd, 0, now); t.onMutationTimeout(true, now); }
                    event = "write " + w + " " + fate;
                }
            }
            record(event + " " + t.phase() + " " + t.restorationStatus() + " " + t.nothingWritten() + " " + t.outcomeTexts(), t.note());
        }
        return now;
    }

    /** The status bytes that read as value, or null when the walk found none. */
    private static byte[] valueBytes(FeatureVariant v, List<byte[]> data, int value) {
        for (byte[] d : data) { Integer read = v.read(status(d, SEEN)); if (read != null && read == value) return d; }
        return null;
    }

    private static void record(String state, String text) {
        trace.append(state).append(" | ").append(text).append('\n');
        if (!text.isEmpty()) shown.add(text);
        if (trace.length() > 65536) flush();
    }

    // ---- Helpers -------------------------------------------------------------------------------

    private static void equal(String expected, String actual, String what) {
        if (!expected.equals(actual)) throw new AssertionError(what + " (" + Locale.getDefault() + "): expected \"" + expected + "\", got \"" + actual + "\"");
        checks++;
    }
    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        checks++;
    }
}
