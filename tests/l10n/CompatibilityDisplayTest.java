package com.jjricks.volt;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * B9's display accessors beside the English the guided tests, the journal and the report keep (plan 1.5):
 *  - GuidedTest.Outcome.label and outcomeLabels() print exactly text(code), describe(token) and outcomeTexts()
 *    in English, and follow the installed text source, while text, describe and outcomeTexts stay English;
 *  - GuidedTest.noteMsg() and Review.message render note() and Review.text in English, and follow the source,
 *    while note() and Review.text (notices, the event log) stay English;
 *  - CompatibilityLabels.field prints CompatibilityReport.fieldLabel for every offset and layer, and follows the
 *    source, while fieldLabel stays English;
 *  - the panel's journal note is TestJournal.UNAVAILABLE_NOTE in English;
 *  - the "in English" lines stay empty while Volt is English (so English screens are unchanged);
 *  - the counts and the plural print what the source printed at every quantity.
 * "Follows the source" is checked with a key-echo source that answers every id with its marked key.
 */
public final class CompatibilityDisplayTest {
    private static int checks;
    private static final String PROFILE = "owner-1";
    private static final long SEEN = 100000, NOW = SEEN + 1000, WALL = 1700000000000L;

    /** Answers every id with its key between marks, and says the text is Spanish. */
    private static final T.Source ECHO = new T.Source() {
        @Override public String string(int id) { return "⟦" + T.key(id) + "⟧"; }
        @Override public String plural(int id, long quantity) { return "⟦" + T.key(id) + "⟧"; }
        @Override public String language() { return "es"; }
    };

    public static void main(String[] args) throws Exception {
        T.install(null);
        try {
            outcomes();
            fields();
            check(T.compatTestRecordsUnavailable().equals(TestJournal.UNAVAILABLE_NOTE), "the panel's journal note is the journal's English");
            check(UpdateCheck.notesLanguageNote().isEmpty(), "no release-notes language line in English");
            check(T.compatTestElsewhereOne().equals("1 unfinished test on another scooter. Select it to review.") && T.compatAgeOneMinute().equals("1 minute ago"),
                    "the exact-one texts");
            check(T.compatReportInEnglish().equals("The report is in English.") && T.compatUpdateNotesInEnglish().equals("Release notes are in English."),
                    "the in-English lines' English");
            plurals();
            walks();
            T.install(ECHO);
            // An unshipped language (Spanish is not beta or stable yet) keeps every screen English, these lines too.
            check(UpdateCheck.notesLanguageNote().isEmpty() && Fmt.legacy(), "no release-notes language line while no language ships");
        } finally {
            T.install(null);
        }
        System.out.println("CompatibilityDisplayTest: " + checks + " checks passed (outcome, note, review and field labels against the English they show)");
    }

    private static void outcomes() {
        for (GuidedTest.Outcome o : GuidedTest.Outcome.values()) {
            for (int code = 0; code <= 255; code++) {
                T.install(null);
                String english = o.text(code), token = o.token(code);
                equal(english, o.label(code), o + " label(" + code + ")");
                equal(GuidedTest.Outcome.describe(token), GuidedTest.Outcome.label(token), "label(" + token + ")");
                T.install(ECHO);
                equal(english, o.text(code), o + " text(" + code + ") stays English");
                equal(english, GuidedTest.Outcome.describe(token), "describe(" + token + ") stays English");
                check(o.label(code).startsWith("⟦compat_outcome_") && GuidedTest.Outcome.label(token).equals(o.label(code)), o + " label follows the source");
            }
        }
        T.install(null);
        for (String token : new String[]{null, "", "ok", "REJECTED", "rejected_256", "readback_different"}) equal("", GuidedTest.Outcome.label(token), "label(" + token + ")");
    }

    private static void fields() {
        for (ModelRegistry.Layer layer : ModelRegistry.Layer.values()) {
            for (int offset = -3; offset <= 70; offset++) {
                T.install(null);
                String english = CompatibilityReport.fieldLabel(offset, layer);
                equal(english, CompatibilityLabels.field(offset, layer), "field(" + offset + ", " + layer + ")");
                T.install(ECHO);
                equal(english, CompatibilityReport.fieldLabel(offset, layer), "fieldLabel(" + offset + ", " + layer + ") stays English");
                String shown = CompatibilityLabels.field(offset, layer);
                check(english.isEmpty() ? shown.isEmpty() : shown.startsWith("⟦compat_field_"), "field(" + offset + ", " + layer + ") follows the source: " + shown);
            }
        }
        T.install(null);
    }

    /** The English plurals print what the code before B9 printed, at every quantity English and the tests use. */
    private static void plurals() {
        for (long n : new long[]{0, 1, 2, 3, 4, 5, 11, 12, 21, 22, 25, 100, 101, 111, 1000000}) {
            equal(n == 1 ? "1 unfinished test on another scooter. Select it to review." : n + " unfinished tests on other scooters. Select one to review.", T.compatTestsElsewhere(n, n), "tests elsewhere at " + n);
            equal(n + (n == 1 ? " minute ago" : " minutes ago"), T.compatAgeMinutes(n, n), "minutes ago at " + n);
            equal("Too short (" + n + (n == 1 ? " byte)" : " bytes)"), T.compatReadTooShort(n, n), "too short at " + n);
        }
    }

    // ---- Guided tests: the same walk under English and under the key-echo source ----------------

    private static void walks() throws Exception {
        for (int seed = 0; seed < 60; seed++) {
            List<String[]> english = walk(seed, null), echoed = walk(seed, ECHO);
            check(english.size() == echoed.size(), "the walk is the same under both sources");
            for (int i = 0; i < english.size(); i++) {
                String[] e = english.get(i), x = echoed.get(i);
                equal(e[0], e[1], "seed " + seed + " step " + i + ": the shown note is note() in English");
                equal(e[0], x[0], "seed " + seed + " step " + i + ": note() stays English under another source");
                check(e[0].isEmpty() ? x[1].isEmpty() : x[1].contains("⟦compat_"), "seed " + seed + " step " + i + ": the shown note follows the source: " + x[1]);
                equal(e[2], e[3], "seed " + seed + " step " + i + ": outcome labels are outcomeTexts() in English");
                equal(e[2], x[2], "seed " + seed + " step " + i + ": outcomeTexts() stays English under another source");
                equal(e[4], e[5], "seed " + seed + " step " + i + ": the shown review is Review.text in English");
                equal(e[4], x[4], "seed " + seed + " step " + i + ": Review.text stays English under another source");
                check(x[5].contains("⟦compat_review_"), "seed " + seed + " step " + i + ": the shown review follows the source: " + x[5]);
            }
        }
    }

    static final class Memory implements TestJournal.Storage {
        String text; boolean failWrites;
        @Override public String read() { return text; }
        @Override public void writeAtomic(String t) throws java.io.IOException { if (failWrites) throw new java.io.IOException("disk full"); text = t; }
    }

    /** One walk of a guided test of the tail light: [note(), noteMsg(), outcomeTexts, outcomeLabels, review text, review message] per step. */
    private static List<String[]> walk(int seed, T.Source source) throws Exception {
        T.install(null);
        Random r = new Random(seed);
        DeviceDescriptor d = DeviceDescriptor.of(seed % 2 == 0 ? "Q2538ZZ9US7777777" : "Q2443ZZ9US7777777", seed % 2 == 0 ? "" : "24431", seed % 2,
                new DeviceDescriptor.Firmware("2.0.3.1", null, null, null, null, SEEN), true, DeviceDescriptor.Transport.STANDARD, 0);
        String[] features = {"headlight", "turn_sound", "auto_sensor", "display_units", "range_algorithm"};
        String feature = features[seed % features.length];
        byte[] base = full();
        CapabilityResolver.Decision decision = CapabilityResolver.decideTest(feature, d, status(base, SEEN), parked());
        List<String[]> out = new ArrayList<>();
        if (!decision.available()) return out;
        FeatureVariant v = decision.variant;
        List<byte[]> data = new ArrayList<>();
        data.add(base);
        for (int raw = 0; raw < 256 && v.offset >= 0; raw++) { byte[] c = base.clone(); c[v.offset] = (byte) raw; data.add(c); }
        data.add(java.util.Arrays.copyOf(base, 4));
        Memory m = new Memory();
        T.install(source);
        GuidedTest t = new GuidedTest(new TestJournal(m), PROFILE, d, v, v.values[r.nextInt(v.values.length)], NOW, WALL, new Random(seed));
        long now = NOW;
        byte[] scooter = data.get(r.nextBoolean() ? 0 : r.nextInt(data.size()));
        for (int step = 0; step < 50; step++) {
            now += 50 + r.nextInt(r.nextInt(10) == 0 ? 12000 : 900);
            int event = r.nextInt(12);
            if (event == 0) t.onDisconnect(now);
            else if (event == 1) m.failWrites = r.nextInt(3) == 0;
            else if (event == 2) scooter = data.get(r.nextInt(data.size()));
            else if (event == 3) t.onMutationTimeout(r.nextBoolean(), now);
            else if (t.wantsRead()) { t.onReadRequested(0x70, now); t.onStatus(status(scooter, now), now); }
            else if (t.phase() == GuidedTest.Phase.BASELINE) t.approve(now);
            else if (t.phase() == GuidedTest.Phase.OBSERVE) { int a = r.nextInt(3); t.ownerEffect(a == 0 ? null : a == 1, now); }
            else {
                Integer w = t.writeNow();
                if (w != null) { int fate = r.nextInt(4); if (fate == 0) t.onAck(v.cmd, 2, now); else if (fate == 1) t.onWriteRefused(now); else t.onAck(v.cmd, 0, now); }
                else t.tick(now);
            }
            TestJournal.Entry e = t.entry();
            GuidedTest.Review review = e == null ? GuidedTest.review(new TestJournal.Entry(), PROFILE, false, d, null, now, v, false)
                    : GuidedTest.review(e, PROFILE, r.nextInt(5) != 0, d, status(r.nextBoolean() ? scooter : data.get(r.nextInt(data.size())), now), now, v, false);
            out.add(new String[]{t.note(), t.noteMsg().render(), t.outcomeTexts().toString(), t.outcomeLabels().toString(), review.text, review.message.render()});
        }
        T.install(null);
        return out;
    }

    private static byte[] full() {
        byte[] d = new byte[44];
        d[1]=3;d[2]=0;d[3]=1;d[4]=1;d[5]=60;d[6]=2;d[7]=1;d[8]=1;d[10]=(byte)0x81;d[11]=1;d[12]=0;d[13]=0;d[15]=1;d[16]=0x01;d[17]=(byte)0x82;d[18]=0x10;
        d[19]=3;d[20]=(byte)0x99;d[21]=2;d[25]=40;d[26]=0;d[27]=0x01;d[28]=0x02;d[29]=0x03;d[30]=0x04;d[31]=90;d[32]=0;d[33]=1;d[34]=2;d[37]=5;d[38]=0;d[39]=0;d[42]=(byte)0x8f;
        return d;
    }
    private static StatusFields status(byte[] payload, long seen) {
        byte[] d = new byte[payload.length + 1]; System.arraycopy(payload, 0, d, 1, payload.length);
        byte[] p = new byte[d.length + 8]; p[0] = 0x55; p[1] = (byte) 0xaa; p[3] = 0x70; p[4] = (byte) d.length;
        System.arraycopy(d, 0, p, 5, d.length); int sum = 0; for (int i = 0; i < p.length - 3; i++) sum += p[i] & 255;
        p[p.length - 3] = (byte) sum; p[p.length - 2] = (byte) 0xfe; p[p.length - 1] = (byte) 0xfd;
        return StatusFields.of(Protocol.parse(p), seen);
    }
    private static CapabilityResolver.Runtime parked() {
        CapabilityResolver.Runtime rt = new CapabilityResolver.Runtime();
        rt.ready = true; rt.stationary = true; rt.motionSeen = true; rt.owner = true; rt.bonded = true; rt.pinEnabledFresh = true; rt.nowMs = NOW;
        return rt;
    }

    private static void equal(String expected, String actual, String what) {
        if (!expected.equals(actual)) throw new AssertionError(what + ": expected \"" + expected + "\", got \"" + actual + "\"");
        checks++;
    }
    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        checks++;
    }
}
