package com.jjricks.volt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the current connection's readings showed, for the report diagnostics file: each drive mode code the scooter
 * reported, with the highest live speed seen while it was in that mode (how the ST3 Pro's mode names were worked out),
 * and whether its current carries a direction (PowerSign's rule, the one riding mode and recordings use). Telemetry
 * feeds it from every accepted reading and clears it with its readings, so it covers one connection (or one
 * simulation) and is never stored. Memory only: no I/O, no background work.
 */
final class DiagnosticsTally {
    /** Drive mode codes kept; a scooter has a handful, so more than this is noise. */
    static final int MAX_MODES = 16;

    private static final class Mode { long reports, speedReadings; double topKmh = -1; }
    private final LinkedHashMap<Long, Mode> modes = new LinkedHashMap<>();
    private Long mode;
    private long modesLeftOut;
    private PowerSign sign = new PowerSign();
    private long signStates, currents;

    /** One accepted reading (Telemetry.put): only drive mode, speed, current sign state and current are looked at. */
    synchronized void note(String key, Double numeric) {
        if (key == null || numeric == null || numeric.isNaN() || numeric.isInfinite()) return;
        double v = numeric;
        switch (key) {
            case "driving_mode": {
                if (v < 0 || v > 255 || v != Math.rint(v)) return;
                long code = (long) v;
                Mode m = modes.get(code);
                if (m == null) {
                    if (modes.size() >= MAX_MODES) { modesLeftOut++; mode = null; return; }
                    modes.put(code, m = new Mode());
                }
                m.reports++; mode = code;
                break;
            }
            case "speed_kmh": {
                Mode m = mode == null ? null : modes.get(mode);
                if (m == null || v < 0 || v > 200) return;
                m.speedReadings++; if (v > m.topKmh) m.topKmh = v;
                break;
            }
            case "current_sign_state": sign.signState(v); signStates++; break;
            case "current_a": sign.current(v); currents++; break;
            default: break;
        }
    }

    /** A new connection, scooter or simulation: nothing seen yet. */
    synchronized void clear() { modes.clear(); mode = null; modesLeftOut = 0; sign = new PowerSign(); signStates = currents = 0; }

    /** drive_modes: one row per code in the order first seen; the current one marked. */
    synchronized Map<String, Object> driveModes() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Object> rows = new ArrayList<>();
        for (Map.Entry<Long, Mode> e : modes.entrySet()) {
            Map<String, Object> r = new LinkedHashMap<>();
            Mode m = e.getValue();
            r.put("code", e.getKey());
            r.put("reports", m.reports);
            r.put("speed_readings", m.speedReadings);
            r.put("top_speed_kmh", m.topKmh < 0 ? null : String.format(Locale.ROOT, "%.1f", m.topKmh));
            r.put("current", e.getKey().equals(mode));
            rows.add(r);
        }
        out.put("modes", rows);
        if (modesLeftOut > 0) out.put("codes_left_out", modesLeftOut);
        return out;
    }

    /**
     * power_sign: whether the scooter's current has a known direction now (PowerSign.signedNow) and for a recording
     * (signedRide), the basis for it, and the raw facts behind it.
     */
    synchronized Map<String, Object> powerSign() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("current_direction_known", currents > 0 || signStates > 0 ? sign.signedNow() : null);
        out.put("basis", currents == 0 && signStates == 0 ? "no_readings" : basis(sign));
        out.put("recording_direction_known", currents > 0 || signStates > 0 ? sign.signedRide() : null);
        out.put("sign_state_reports", signStates);
        out.put("sign_state_ever_0_or_1", sign.everKnown());
        out.put("latest_sign_state_unknown", sign.latestUnknown());
        out.put("current_readings", currents);
        out.put("negative_current_before_sign_state", sign.sawNegative());
        return out;
    }

    /** Which PowerSign rule decided signedNow(). */
    static String basis(PowerSign s) {
        if (s.everKnown() && !s.latestUnknown()) return "sign_state";
        if (s.everKnown()) return "sign_state_lost";
        if (!s.present()) return "no_sign_state";
        return s.sawNegative() ? "negative_current" : "unsigned";
    }
}
