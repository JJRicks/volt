package com.jjricks.volt;

import java.io.*;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Replays docs/validation/data/fast-charge-taper-v7/sessions.csv through the production store and
 * estimator. Each charge learns only from the charges before it. Reflection keeps this compiling
 * against earlier releases too, which regenerate their baselines: ut5-stock-pack-v6 (b21d3f6)
 * expected-v6.csv and ut5-fast-taper-v7 (f529f43) expected-v7.csv, as
 * java -cp CLASSES com.jjricks.volt.FastChargeTaperReplay sessions.csv > expected-vN.csv
 */
final class FastChargeTaperReplay {
    static final double RATED_AH = 15.3;
    static final long BASE_MONO = 1_000_000_000L, BASE_WALL = 1_700_000_000_000L, SESSION_SPACING = 100_000_000L;

    static final class Sample {
        int session; String charger, stop; long elapsed; double soc, volts, amps; boolean gap;
        String recordedVersion, recordedSource; Double recordedFull; int recordedLearned;
    }
    static final class Row {
        Sample sample; double minutes; Double actual; Double full; String source; int learned;
        /** The charge's own taper was established (FastChargeTaper.ownTaper) at this sample's live reading. */
        boolean ownTaper;
        /** The time to the replayed charge limit, its source and Charge done by's minutes before the buffer. */
        Double limit, planningLimit; String limitSource, label;
        /** Past charges' time to that limit (ChargingEstimate.withObservedCurve) at this sample, or null without one. */
        Double personalLimit;
    }

    static List<List<Sample>> read(Path csv) throws IOException {
        List<List<Sample>> sessions = new ArrayList<>();
        boolean header = true;
        for (String line : Files.readAllLines(csv, StandardCharsets.UTF_8)) {
            if (line.startsWith("#")) continue;
            if (header) { header = false; continue; }
            String[] p = line.split(",", -1);
            Sample s = new Sample();
            s.session = Integer.parseInt(p[0]); s.charger = p[1]; s.stop = p[2]; s.elapsed = Long.parseLong(p[3]);
            s.soc = Double.parseDouble(p[4]); s.volts = Double.parseDouble(p[5]); s.amps = Double.parseDouble(p[6]);
            s.gap = p[7].equals("1"); s.recordedVersion = p[8]; s.recordedFull = p[9].isEmpty() ? null : Double.valueOf(p[9]);
            s.recordedSource = p[10]; s.recordedLearned = Integer.parseInt(p[11]);
            while (sessions.size() <= s.session) sessions.add(new ArrayList<>());
            sessions.get(s.session).add(s);
        }
        return sessions;
    }

    static long mono(Sample s) { return BASE_MONO + s.session * SESSION_SPACING + s.elapsed; }

    static void journal(List<Sample> session, File file) throws IOException {
        Sample first = session.get(0);
        StringBuilder out = new StringBuilder("charge-v2,fixture," + first.charger + "," + (BASE_WALL + mono(first)) + "," + mono(first) + "\n");
        for (Sample s : session) out.append("s,").append(BASE_WALL + mono(s)).append(',').append(mono(s)).append(',').append(s.soc).append(',')
                .append(s.volts).append(',').append(s.amps).append(',').append(s.volts * s.amps).append(",,").append(s.gap ? 1 : 0).append(",fixture,,,,,,0\n");
        if (!first.stop.isEmpty()) out.append("end,").append(first.stop).append('\n');
        Files.write(file.toPath(), out.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void clear(Path dir) throws IOException {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) { for (Path f : files) Files.delete(f); }
        Files.delete(dir);
    }

    static ChargingEstimate.Value live(double n, long t) { return new ChargingEstimate.Value(n, true, t); }
    static String source(ChargingEstimate.Result r) {
        return r.learnedToFull ? "PERSONAL" : r.referenceToFull
                ? (r.referenceCharger == ChargeSessionStore.Charger.STOCK ? "STOCK_REFERENCE" : "FAST_REFERENCE") : "CURRENT_RATE";
    }

    static String limitSource(ChargingEstimate.Result r) {
        return r.remainingMinutesToLimit == null ? "" : r.learnedToLimit ? "PERSONAL" : r.referenceToLimit
                ? (r.referenceCharger == ChargeSessionStore.Charger.STOCK ? "STOCK_REFERENCE" : "FAST_REFERENCE") : "CURRENT_RATE";
    }

    static List<Row> replay(Path csv) throws Exception { return replay(csv, 100); }

    /** Replays with the given charge limit (100 for the committed baselines). */
    static List<Row> replay(Path csv, double target) throws Exception {
        List<List<Sample>> sessions = read(csv);
        Class<?> taperClass = null;
        try { taperClass = Class.forName("com.jjricks.volt.FastChargeTaper"); } catch (ClassNotFoundException baseline) {}
        Method withTaper = null, observe = null, tracking = null, ownTaper = null;
        if (taperClass != null) {
            withTaper = ChargingEstimate.class.getMethod("forScooter", ChargingEstimate.Result.class, ChargingEstimate.Value.class,
                    ChargingEstimate.Value.class, int.class, ChargeSessionStore.Charger.class, ChargeSessionStore.Curve.class,
                    ChargeReferenceGate.class, String.class, long.class, long.class, ChargeProgressTracker.Progress.class, taperClass);
            observe = taperClass.getMethod("observe", String.class, Boolean.class, Double.class, Double.class, long.class);
            tracking = taperClass.getMethod("tracking", String.class);
            try { ownTaper = taperClass.getDeclaredMethod("ownTaper", double.class, double.class); } catch (NoSuchMethodException v7) {}
        }
        Path tmp = Files.createTempDirectory("fast-taper-replay-");
        List<Row> rows = new ArrayList<>();
        try {
            for (int index = 0; index < sessions.size(); index++) {
                List<Sample> session = sessions.get(index);
                Path history = Files.createTempDirectory(tmp, "history-"), alone = Files.createTempDirectory(tmp, "alone-");
                try {
                    for (int prior = 0; prior < index; prior++) {
                        File file = history.resolve(String.format("00000000-0000-0000-0000-%012d.csv", prior)).toFile();
                        journal(sessions.get(prior), file);
                        file.setLastModified(BASE_WALL + prior * 1000L);
                    }
                    ChargeSessionStore.Charger charger = ChargeSessionStore.Charger.valueOf(session.get(0).charger);
                    ChargeSessionStore.Curve curve = new ChargeSessionStore(history.toFile()).snapshot().curves.get(ChargeSessionStore.key("fixture", charger));
                    journal(session, alone.resolve(String.format("00000000-0000-0000-0000-%012d.csv", index)).toFile());
                    Long complete = new ChargeSessionStore(alone.toFile()).recordings("fixture").get(0).observedCompleteMono;
                    ChargeReferenceGate gate = new ChargeReferenceGate();
                    ChargeProgressTracker progress = new ChargeProgressTracker();
                    Object taper = taperClass == null ? null : taperClass.getConstructor().newInstance();
                    long start = mono(session.get(0));
                    progress.observe("fixture", false, null, null, start - 1000);
                    if (taper != null) observe.invoke(taper, "fixture", false, null, null, start - 1000);
                    for (Sample s : session) {
                        long t = mono(s);
                        progress.observe("fixture", true, s.soc, s.amps, t);
                        if (taper != null) observe.invoke(taper, "fixture", true, s.volts, s.amps, t);
                        ChargingEstimate.Result base = ChargingEstimate.calculate(live(1, t), live(s.volts, t), live(s.amps, t),
                                live(s.soc, t), live(RATED_AH, t), live(target, t));
                        ChargingEstimate.Result r = taper == null
                                ? ChargingEstimate.forScooter(base, live(s.soc, t), live(RATED_AH, t), 2538, charger, curve, gate, "fixture:serial", t, t, progress.progress("fixture", t))
                                : (ChargingEstimate.Result) withTaper.invoke(null, base, live(s.soc, t), live(RATED_AH, t), 2538, charger, curve, gate, "fixture:serial", t, t,
                                        progress.progress("fixture", t), (Boolean) tracking.invoke(taper, "fixture") ? taper : null);
                        Row row = new Row();
                        row.sample = s; row.minutes = (t - start) / 60000d; row.actual = complete == null ? null : (complete - t) / 60000d;
                        row.full = r.remainingMinutesToFull; row.source = source(r); row.learned = r.learnedSessions;
                        row.ownTaper = ownTaper != null && base.inputWatts != null && base.inputAmps != null && base.inputAmps > 0
                                && (Boolean) ownTaper.invoke(taper, base.inputWatts / base.inputAmps, base.inputAmps);
                        row.limit = r.remainingMinutesToLimit; row.planningLimit = r.planningMinutesToLimit;
                        row.limitSource = limitSource(r); row.label = r.label;
                        ChargingEstimate.Result learned = curve == null ? null : ChargingEstimate.withObservedCurve(base, live(s.soc, t), curve);
                        row.personalLimit = learned != null && learned.learnedToLimit ? learned.remainingMinutesToLimit : null;
                        rows.add(row);
                    }
                } finally { clear(history); clear(alone); }
            }
        } finally { Files.delete(tmp); }
        return rows;
    }

    /** First replayed row of a session at or after the given minute. */
    static Row at(List<Row> rows, int session, double minutes) {
        for (Row row : rows) if (row.sample.session == session && row.minutes >= minutes) return row;
        return null;
    }

    /** The lower charge limits pinned by expected-v7-limits.csv. */
    static final double[] LIMITS = {95, 80};

    /**
     * java -cp CLASSES com.jjricks.volt.FastChargeTaperReplay sessions.csv [--limits]. With --limits,
     * each sample's time to each of LIMITS, its source, Charge done by's minutes before the buffer and
     * the past charges counted (regen-expected-v7-limits.sh writes expected-v7-limits.csv this way).
     */
    public static void main(String[] args) throws Exception {
        PrintStream out = System.out;
        if (args.length > 1 && args[1].equals("--limits")) {
            List<List<Row>> byLimit = new ArrayList<>();
            StringBuilder header = new StringBuilder("session,minutes");
            for (double limit : LIMITS) {
                byLimit.add(replay(Paths.get(args[0]), limit));
                int l = (int) limit;
                header.append(",limit").append(l).append(",source").append(l).append(",planning").append(l).append(",learned").append(l);
            }
            out.println(header);
            for (int i = 0; i < byLimit.get(0).size(); i++) {
                Row first = byLimit.get(0).get(i);
                StringBuilder line = new StringBuilder(first.sample.session + "," + String.format(Locale.ROOT, "%.4f", first.minutes));
                for (List<Row> rows : byLimit) {
                    Row row = rows.get(i);
                    line.append(',').append(minutes(row.limit)).append(',').append(row.limitSource).append(',')
                            .append(minutes(row.planningLimit)).append(',').append(row.learned);
                }
                out.println(line);
            }
            return;
        }
        out.println("session,minutes,full,source,learned");
        for (Row row : replay(Paths.get(args[0])))
            out.println(row.sample.session + "," + String.format(Locale.ROOT, "%.4f", row.minutes) + ","
                    + minutes(row.full) + "," + row.source + "," + row.learned);
    }

    private static String minutes(Double value) { return value == null ? "" : String.format(Locale.ROOT, "%.6f", value); }
}
