package com.jjricks.volt;

import java.io.*;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Replays docs/validation/data/fast-charge-taper-v7/sessions.csv through the production store and
 * estimator. Each charge learns only from the charges before it. Reflection keeps this compiling
 * against the ut5-stock-pack-v6 baseline (b21d3f6) too, which regenerates expected-v6.csv:
 * java -cp CLASSES com.jjricks.volt.FastChargeTaperReplay sessions.csv > expected-v6.csv
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

    static List<Row> replay(Path csv) throws Exception {
        List<List<Sample>> sessions = read(csv);
        Class<?> taperClass = null;
        try { taperClass = Class.forName("com.jjricks.volt.FastChargeTaper"); } catch (ClassNotFoundException baseline) {}
        Method withTaper = null, observe = null, tracking = null;
        if (taperClass != null) {
            withTaper = ChargingEstimate.class.getMethod("forScooter", ChargingEstimate.Result.class, ChargingEstimate.Value.class,
                    ChargingEstimate.Value.class, int.class, ChargeSessionStore.Charger.class, ChargeSessionStore.Curve.class,
                    ChargeReferenceGate.class, String.class, long.class, long.class, ChargeProgressTracker.Progress.class, taperClass);
            observe = taperClass.getMethod("observe", String.class, Boolean.class, Double.class, Double.class, long.class);
            tracking = taperClass.getMethod("tracking", String.class);
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
                                live(s.soc, t), live(RATED_AH, t), live(100, t));
                        ChargingEstimate.Result r = taper == null
                                ? ChargingEstimate.forScooter(base, live(s.soc, t), live(RATED_AH, t), 2538, charger, curve, gate, "fixture:serial", t, t, progress.progress("fixture", t))
                                : (ChargingEstimate.Result) withTaper.invoke(null, base, live(s.soc, t), live(RATED_AH, t), 2538, charger, curve, gate, "fixture:serial", t, t,
                                        progress.progress("fixture", t), (Boolean) tracking.invoke(taper, "fixture") ? taper : null);
                        Row row = new Row();
                        row.sample = s; row.minutes = (t - start) / 60000d; row.actual = complete == null ? null : (complete - t) / 60000d;
                        row.full = r.remainingMinutesToFull; row.source = source(r); row.learned = r.learnedSessions;
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

    public static void main(String[] args) throws Exception {
        PrintStream out = System.out;
        out.println("session,minutes,full,source,learned");
        for (Row row : replay(Paths.get(args[0])))
            out.println(row.sample.session + "," + String.format(Locale.ROOT, "%.4f", row.minutes) + ","
                    + (row.full == null ? "" : String.format(Locale.ROOT, "%.6f", row.full)) + "," + row.source + "," + row.learned);
    }
}
