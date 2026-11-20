package com.jjricks.volt;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;

/**
 * The scooter's percent jump is display only: every estimate from a fixed set of synthesized recordings (two with a jump,
 * a reconnect, a plain stock charge to full and a high-speed one) is byte for byte what it was before the jump was
 * detected. The digest below was taken with the same recordings on main before this change (the class uses only calls
 * main has), so any change to minutes, counts, estimate, estimateProvisional, estimateJoined, the finishing curves,
 * powerMatch or familyWatts fails here. Summary coverage is compared with a jump percent read as what it was before
 * (no data, or disagreeing for two or more recordings), since only the card words it differently.
 */
public final class ChargeJumpEstimatesTest {
    /** SHA-256 of the dump on main before the change (0792aae2). */
    static final String BEFORE = "6e53a4df272523723aaf5f50f3a031915500b21255fa40d61f4318d22adae0b6";

    public static void main(String[] args) throws Exception {
        String dump = dump();
        String digest = sha256(dump);
        if (args.length > 0 && args[0].equals("--print")) { System.out.println(digest + " " + dump.length()); return; }
        if (!digest.equals(BEFORE)) throw new AssertionError("Estimates changed: " + digest + " (was " + BEFORE + ")");
        System.out.println("Charge jump estimates unchanged: " + digest.substring(0, 12) + ", " + dump.length() + " characters");
    }

    static String dump() throws Exception {
        ChargeSessionStore store = new ChargeSessionStore(Files.createTempDirectory("charge-jump-estimates-").toFile());
        ChargeJumpRecordings.record(store, ChargeSessionStore.Charger.STOCK, 0, ChargeJumpRecordings.jump83(), true);
        ChargeJumpRecordings.record(store, ChargeSessionStore.Charger.STOCK, 1, ChargeJumpRecordings.jump86(), true);
        ChargeJumpRecordings.record(store, ChargeSessionStore.Charger.STOCK, 2, ChargeJumpRecordings.finish(new ChargeJumpRecordings.Plan()
                .range(40, 61, 290, 1.9).gap(180_000).range(63, 95, 290, 1.9)), true);
        ChargeJumpRecordings.record(store, ChargeSessionStore.Charger.STOCK, 3, new ChargeJumpRecordings.Plan().range(20, 70, 300, 1.95), false);
        ChargeJumpRecordings.record(store, ChargeSessionStore.Charger.HIGH_SPEED, 4, new ChargeJumpRecordings.Plan().range(30, 80, 70, 8)
                .range(80, 90, 120, 5).range(90, 100, 300, 2), true);
        StringBuilder out = new StringBuilder();
        Map<String, ChargeSessionStore.Curve> curves = new TreeMap<>(store.snapshot().curves);
        for (Map.Entry<String, ChargeSessionStore.Curve> item : curves.entrySet()) {
            ChargeSessionStore.Curve curve = item.getValue();
            out.append("curve ").append(item.getKey()).append(' ').append(curve.charger).append(' ').append(curve.qualifiedSessions).append('\n');
            for (int soc = 0; soc < 100; soc++) {
                out.append(soc).append(':');
                for (double target : new double[]{50, 80, 90, 95, 100}) {
                    out.append(' ').append(d(curve.estimate(soc, target))).append('/').append(d(curve.estimateProvisional(soc, target)))
                            .append('/').append(d(curve.estimateJoined(soc, target, true))).append('/').append(d(curve.estimateJoined(soc, target, false)));
                }
                for (double watts : new double[]{40, 80, 105, 300, 400}) {
                    out.append(" f").append(d(curve.estimateFinishing(soc, watts))).append('|').append(d(curve.estimateFinishing(soc, watts, 52.0)))
                            .append(" p").append(curve.powerMatch(soc, watts)).append(curve.powerMatch(soc, watts, true)).append(curve.familyPowerMatch(soc, watts));
                }
                out.append(" w").append(curve.familyWatts(soc)).append(" c").append(curve.hasFinishingCoverage(soc));
                ChargeSessionStore.Summary.Bin bin = curve.summary().bins.get(soc);
                String kind = bin.coverage.name().equals("JUMP") ? bin.sessions >= 2 ? "DISAGREEING" : "NONE" : bin.coverage.name();
                out.append(" s").append(kind).append(',').append(bin.sessions).append(',').append(bin.minutes).append(',').append(bin.watts)
                        .append(',').append(bin.finishCovered).append(',').append(bin.outsideTemperature).append('\n');
            }
            out.append("finish ").append(curve.summary().finishStarts).append('\n');
        }
        return out.toString();
    }
    private static String d(ChargeSessionStore.Duration duration) {
        return duration == null ? "-" : Math.round(duration.minutes * 1e6) + "," + duration.sessions + "," + duration.provisional + "," + duration.single + "," + duration.joinedAt;
    }
    private static String sha256(String text) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))) hex.append(String.format("%02x", b));
        return hex.toString();
    }
}
