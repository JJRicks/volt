package com.jjricks.volt;

import java.io.IOException;
import java.util.*;

/**
 * One scooter's maintenance document, as JSON in app_settings under {@code maintenance_v1_<profileId>}
 * (maintenance plan, section 5). It travels with Scooter preferences backups (BackupRegistry).
 * Device-local "already notified" bookkeeping uses {@code maintenance_seen_<profileId>}, which is
 * never backed up; the two prefixes share no common start, so no key matches both rules.
 *
 * <pre>{"v":1,"on":true,
 *  "distance":{"lastRawKm":2009.4,"lastWall":1762300000000,"bits":32,"accKm":2009.4,"resetWall":0,"floorRawKm":0.0},
 *  "reminders":[{"id":"r1","task":"brakes","name":"","km":482.8032,"days":0,"months":0,"notify":true,
 *    "baseAccKm":1661.8,"baseWall":1759400000000,"snoozeUntil":0,
 *    "events":[{"t":"done","wall":1759400000000,"accKm":1661.8}]}]}</pre>
 *
 * Distances are km at full precision, so an interval typed in miles stays exact. The decoder is
 * tolerant: unreadable text is "off" with no reminders, a malformed reminder or event is dropped,
 * and fields a newer version added are kept. Pure Java; nothing in the app reads or writes it yet.
 */
final class MaintenanceStore {
    static final String PREFIX = "maintenance_v1_", SEEN_PREFIX = "maintenance_seen_";
    static final int VERSION = 1, MAX_DEPTH = 8, MAX_LENGTH = 256 * 1024;

    static String key(String profileId) { return PREFIX + profileId; }
    static String seenKey(String profileId) { return SEEN_PREFIX + profileId; }

    /** A scooter's whole maintenance state. Off by default. */
    static final class Settings {
        boolean on;
        MaintenanceDistance distance = new MaintenanceDistance();
        final List<MaintenancePolicy.Reminder> reminders = new ArrayList<>();
        /** Fields a newer version wrote, at the top level and in "distance"; kept as they are. */
        final Map<String, Object> extra = new LinkedHashMap<>(), distanceExtra = new LinkedHashMap<>();
        MaintenancePolicy.Reminder find(String id) { for (MaintenancePolicy.Reminder r : reminders) if (r.id.equals(id)) return r; return null; }
    }

    private MaintenanceStore() { }

    // ---- Decode -----------------------------------------------------------------------------

    /** Never throws. Absent or unreadable text gives off with no reminders. */
    static Settings decode(String text) {
        Settings s = new Settings();
        if (text == null || text.length() > MAX_LENGTH) return s;
        Map<String, Object> o;
        try { o = BackupJson.object(BackupJson.parse(text, MAX_DEPTH)); } catch (IOException | RuntimeException unreadable) { return s; }
        try {
            s.on = Boolean.TRUE.equals(o.get("on"));
            Object distance = o.get("distance");
            if (distance instanceof Map) {
                s.distance = distance(cast(distance));
                for (Map.Entry<String, Object> e : cast(distance).entrySet()) if (!DISTANCE.contains(e.getKey())) s.distanceExtra.put(e.getKey(), e.getValue());
            }
            Object list = o.get("reminders");
            if (list instanceof List) for (Object item : (List<?>) list) {
                if (s.reminders.size() >= MaintenancePolicy.MAX_REMINDERS) break;
                MaintenancePolicy.Reminder r = item instanceof Map ? reminder(cast(item)) : null;
                if (r != null && s.find(r.id) == null) s.reminders.add(r);
            }
            for (Map.Entry<String, Object> e : o.entrySet()) if (!TOP.contains(e.getKey())) s.extra.put(e.getKey(), e.getValue());
        } catch (RuntimeException unreadable) {
            return new Settings();
        }
        return s;
    }

    private static final List<String> TOP = Arrays.asList("v", "on", "distance", "reminders");
    private static final List<String> DISTANCE = Arrays.asList("lastRawKm", "lastWall", "bits", "accKm", "resetWall", "floorRawKm");
    private static final List<String> REMINDER = Arrays.asList("id", "task", "name", "km", "days", "months", "notify", "baseAccKm", "baseWall", "snoozeUntil", "events");
    private static final List<String> EVENT = Arrays.asList("t", "wall", "accKm");

    private static MaintenanceDistance distance(Map<String, Object> o) {
        MaintenanceDistance d = new MaintenanceDistance();
        double acc = number(o.get("accKm"), 0);
        d.accumulatedKm = acc >= 0 && !Double.isInfinite(acc) ? acc : 0;
        double raw = number(o.get("lastRawKm"), Double.NaN);
        d.lastRawKm = raw >= 0 && !Double.isInfinite(raw) ? raw : Double.NaN;
        d.lastWall = Math.max(0, whole(o.get("lastWall"), 0));
        long bits = whole(o.get("bits"), 0);
        d.bits = bits == 8 || bits == 16 || bits == 32 ? (int) bits : MaintenanceDistance.BITS_UNKNOWN;
        d.resetWall = Math.max(0, whole(o.get("resetWall"), 0));
        double floor = number(o.get("floorRawKm"), 0);
        d.floorRawKm = floor >= 0 && !Double.isInfinite(floor) ? floor : 0;
        return d;
    }

    private static MaintenancePolicy.Reminder reminder(Map<String, Object> o) {
        MaintenancePolicy.Reminder r = new MaintenancePolicy.Reminder();
        r.id = text(o.get("id")); r.task = text(o.get("task")); r.name = text(o.get("name"));
        if (r.id == null || r.task == null) return null;
        if (r.name == null) r.name = "";
        Object km = o.get("km");
        if (km instanceof Number) r.km = ((Number) km).doubleValue();
        else if (km != null && km != BackupJson.NULL) return null;
        long days = whole(o.get("days"), 0), months = whole(o.get("months"), 0);
        if (days < 0 || days > MaintenancePolicy.MAX_DAYS || months < 0 || months > MaintenancePolicy.MAX_MONTHS) return null;
        r.days = (int) days; r.months = (int) months;
        r.notify = Boolean.TRUE.equals(o.get("notify"));
        r.baseAccKm = number(o.get("baseAccKm"), Double.NaN);
        r.baseWall = whole(o.get("baseWall"), 0);
        r.snoozeUntil = whole(o.get("snoozeUntil"), 0);
        if (!MaintenancePolicy.valid(r)) return null;
        Object events = o.get("events");
        if (events instanceof List) for (Object item : (List<?>) events) {
            MaintenancePolicy.Event e = item instanceof Map ? event(cast(item)) : null;
            if (e != null) MaintenancePolicy.record(r, e);
        }
        for (Map.Entry<String, Object> e : o.entrySet()) if (!REMINDER.contains(e.getKey())) r.extra.put(e.getKey(), e.getValue());
        return r;
    }

    private static MaintenancePolicy.Event event(Map<String, Object> o) {
        String type = text(o.get("t"));
        long wall = whole(o.get("wall"), 0);
        if (type == null || type.isEmpty() || type.length() > 16 || wall <= 0) return null;
        double acc = number(o.get("accKm"), Double.NaN);
        Map<String, Object> extra = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : o.entrySet()) if (!EVENT.contains(e.getKey())) extra.put(e.getKey(), e.getValue());
        return new MaintenancePolicy.Event(type, wall, Math.abs(acc) <= MaintenancePolicy.MAX_ODOMETER_KM ? acc : Double.NaN, extra);
    }

    // ---- Encode -----------------------------------------------------------------------------

    static String encode(Settings s) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("v", (long) VERSION);
        o.put("on", s.on);
        MaintenanceDistance d = s.distance;
        Map<String, Object> distance = new LinkedHashMap<>();
        if (d.hasBaseline()) distance.put("lastRawKm", d.lastRawKm);
        distance.put("lastWall", d.lastWall); distance.put("bits", (long) d.bits); distance.put("accKm", d.accumulatedKm); distance.put("resetWall", d.resetWall); distance.put("floorRawKm", d.floorRawKm);
        for (Map.Entry<String, Object> x : s.distanceExtra.entrySet()) if (!distance.containsKey(x.getKey())) distance.put(x.getKey(), x.getValue());
        o.put("distance", distance);
        List<Object> reminders = new ArrayList<>();
        for (MaintenancePolicy.Reminder r : s.reminders) {
            if (reminders.size() >= MaintenancePolicy.MAX_REMINDERS) break;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.id); m.put("task", r.task); m.put("name", r.name);
            m.put("km", r.km == null ? BackupJson.NULL : r.km);
            m.put("days", (long) r.days); m.put("months", (long) r.months); m.put("notify", r.notify);
            if (!Double.isNaN(r.baseAccKm)) m.put("baseAccKm", r.baseAccKm);
            m.put("baseWall", r.baseWall); m.put("snoozeUntil", r.snoozeUntil);
            List<Object> events = new ArrayList<>();
            List<MaintenancePolicy.Event> kept = r.events.subList(Math.max(0, r.events.size() - MaintenancePolicy.MAX_EVENTS), r.events.size());
            for (MaintenancePolicy.Event e : kept) {
                Map<String, Object> em = new LinkedHashMap<>();
                em.put("t", e.type); em.put("wall", e.wall);
                if (!Double.isNaN(e.accKm)) em.put("accKm", e.accKm);
                for (Map.Entry<String, Object> x : e.extra.entrySet()) if (!em.containsKey(x.getKey())) em.put(x.getKey(), x.getValue());
                events.add(em);
            }
            m.put("events", events);
            for (Map.Entry<String, Object> x : r.extra.entrySet()) if (!m.containsKey(x.getKey())) m.put(x.getKey(), x.getValue());
            reminders.add(m);
        }
        o.put("reminders", reminders);
        for (Map.Entry<String, Object> x : s.extra.entrySet()) if (!o.containsKey(x.getKey())) o.put(x.getKey(), x.getValue());
        StringBuilder out = new StringBuilder();
        write(out, o);
        return out.toString();
    }

    // ---- Backup merge -----------------------------------------------------------------------

    /**
     * A backup merge is moving this preference onto another scooter profile. For a maintenance
     * document, the raw odometer baseline belongs to the other physical scooter, so it is removed:
     * the next reading re-baselines without adding a bogus distance. Everything else, unknown
     * fields included, is kept. Other keys, and text that can't be read, are returned unchanged.
     */
    static Object movedToAnotherScooter(String key, Object value) {
        if (key == null || !key.startsWith(PREFIX) || !(value instanceof String) || ((String) value).length() > MAX_LENGTH) return value;
        try {
            Map<String, Object> o = BackupJson.object(BackupJson.parse((String) value, MAX_DEPTH));
            Object distance = o.get("distance");
            if (!(distance instanceof Map) || !cast(distance).containsKey("lastRawKm")) return value;
            cast(distance).remove("lastRawKm");
            StringBuilder out = new StringBuilder();
            write(out, o);
            return out.toString();
        } catch (IOException | RuntimeException unreadable) {
            return value;
        }
    }

    // ---- Small JSON helpers -----------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object map) { return (Map<String, Object>) map; }
    private static String text(Object v) { return v instanceof String ? (String) v : null; }
    private static double number(Object v, double fallback) {
        if (!(v instanceof Number)) return fallback;
        double d = ((Number) v).doubleValue();
        return Double.isNaN(d) ? fallback : d;
    }
    /** A whole number (Long, or a Double with no fraction in the long range), else the fallback. */
    private static long whole(Object v, long fallback) {
        if (v instanceof Long) return (Long) v;
        if (v instanceof Double) { double d = (Double) v; if (d == Math.rint(d) && Math.abs(d) < 9e15) return (long) d; }
        return fallback;
    }

    /** Writes the BackupJson value model back as JSON. Non-finite numbers become null. */
    private static void write(StringBuilder out, Object v) {
        if (v == null || v == BackupJson.NULL) out.append("null");
        else if (v instanceof String) out.append(BackupJson.quote((String) v));
        else if (v instanceof Boolean) out.append(v.toString());
        else if (v instanceof Long || v instanceof Integer) out.append(v.toString());
        else if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) out.append("null");
            else if (d == Math.rint(d) && Math.abs(d) < 1e15) out.append((long) d).append(".0");
            else out.append(Double.toString(d));
        } else if (v instanceof Map) {
            out.append('{'); boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (!first) out.append(','); first = false;
                out.append(BackupJson.quote(String.valueOf(e.getKey()))).append(':'); write(out, e.getValue());
            }
            out.append('}');
        } else if (v instanceof List) {
            out.append('['); boolean first = true;
            for (Object item : (List<?>) v) { if (!first) out.append(','); first = false; write(out, item); }
            out.append(']');
        } else throw new IllegalArgumentException("json");
    }
}
