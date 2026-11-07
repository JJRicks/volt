package com.jjricks.volt;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * When riding mode's night vision is on (ride-display plan, PR 3): Off, On, or Scheduled, from sunset to
 * sunrise (owner Q21 A) or between custom times. The moon button overrides it until the next scheduled
 * switch, as phones do with a manual dark-mode change; in Off or On it lasts while riding mode stays
 * open. Pure Java and clock-free: every call takes the wall time and zone, so the JVM tests drive DST
 * days, overnight windows and polar days.
 */
final class NightSchedule {
    private NightSchedule() { }

    static final String MODE_KEY = "riding_night_vision", OFF = "off", ON = "on", SCHEDULED = "scheduled";
    static final String SCHEDULE_KEY = "riding_night_schedule", SUN = "sun", CUSTOM = "custom";
    /** Custom times, minutes after local midnight. */
    static final String START_KEY = "riding_night_start_min", END_KEY = "riding_night_end_min";
    static final int START_DEFAULT = 20 * 60, END_DEFAULT = 7 * 60;
    /**
     * The rider's answer to using the last ride's area for sunset (owner: brief informed consent first).
     * Absent until asked; kept on this phone only (excluded from backups).
     */
    static final String AREA_KEY = "riding_night_ride_area", AREA_ALLOW = "allow", AREA_DECLINE = "decline";

    /** Where the sun times come from, for the settings caption. */
    enum Source { RIDE, ZONE, CUSTOM, NONE }

    /** A place for the sun, already rounded to 0.1° (about 11 km). */
    static final class Place {
        final double latitude, longitude;
        final Source source;
        Place(double latitude, double longitude, Source source) {
            this.latitude = round(latitude); this.longitude = round(longitude); this.source = source;
        }
    }

    /** 0.1°: the most of a ride's location the schedule ever keeps. */
    static double round(double degrees) { return Math.round(degrees * 10) / 10.0; }

    static String mode(String stored) { return ON.equals(stored) || SCHEDULED.equals(stored) ? stored : OFF; }
    static String schedule(String stored) { return CUSTOM.equals(stored) ? CUSTOM : SUN; }
    static int minutes(int stored, int fallback) { return stored >= 0 && stored < 24 * 60 ? stored : fallback; }

    /** The schedule's answer now. */
    static final class State {
        final boolean night;
        /** The next scheduled switch (epoch ms), or -1 when none comes in the next few days (or Off / On). */
        final long nextMs;
        final Source source;
        /** Today's sun, when sun times are in use; null otherwise. */
        final SunTimes.Day.Kind today;
        State(boolean night, long nextMs, Source source, SunTimes.Day.Kind today) {
            this.night = night; this.nextMs = nextMs; this.source = source; this.today = today;
        }
    }

    /**
     * mode and schedule as stored; startMin and endMin the custom times; place the sun's place or null
     * (then Scheduled sunset falls back to the custom times, Source.NONE).
     */
    static State state(String mode, String schedule, int startMin, int endMin, Place place, ZoneId zone, long nowMs) {
        mode = mode(mode);
        if (OFF.equals(mode)) return new State(false, -1, null, null);
        if (ON.equals(mode)) return new State(true, -1, null, null);
        boolean sun = SUN.equals(schedule(schedule)) && place != null;
        Source source = sun ? place.source : SUN.equals(schedule(schedule)) ? Source.NONE : Source.CUSTOM;
        LocalDate today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate();
        List<long[]> nights = new ArrayList<>();
        SunTimes.Day.Kind kind = null;
        if (sun) {
            // Nights from the sun's actual horizon crossings (SunTimes.nights), not from each local day's
            // sunrise and sunset: near midnight sun a sunset comes after 00:00 (Tromsø, Jul 26: 00:10 on
            // the 27th), and the days next to a polar day or night need no special case.
            kind = SunTimes.day(today, zone, place.latitude, place.longitude).kind;
            long from = today.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
            long to = today.plusDays(4).atStartOfDay(zone).toInstant().toEpochMilli();
            nights.addAll(SunTimes.nights(from, to, place.latitude, place.longitude));
        }
        // Custom times: the same clock times each local day (a 23- or 25-hour day keeps them).
        for (int offset = -1; offset <= 3 && !sun; offset++) {
            LocalDate date = today.plusDays(offset);
            long start = date.atStartOfDay(zone).toInstant().toEpochMilli();
            long end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
            int from = minutes(startMin, START_DEFAULT), to = minutes(endMin, END_DEFAULT);
            long at = time(date, from, zone), until = time(date, to, zone);
            if (from > to) { add(nights, start, until); add(nights, at, end); }
            else if (from < to) add(nights, at, until);
        }
        List<long[]> merged = merge(nights);
        boolean night = false;
        long next = -1;
        for (long[] span : merged) {
            if (span[0] <= nowMs && nowMs < span[1]) { night = true; next = span[1]; break; }
            if (span[0] > nowMs) { next = span[0]; break; }
        }
        // The last span ends at the window's edge: that isn't a real switch.
        long edge = today.plusDays(4).atStartOfDay(zone).toInstant().toEpochMilli();
        if (next >= edge) next = -1;
        return new State(night, next, source, kind);
    }

    /** The moon button's choice: the opposite of what's shown, until the next scheduled switch. */
    static final class Override {
        final boolean night;
        final long untilMs;
        Override(boolean night, long untilMs) { this.night = night; this.untilMs = untilMs; }
    }

    /** A tap on the moon while shown is on screen. In Off or On, or with no switch coming, it lasts the visit. */
    static Override tap(State state, boolean shown) {
        return new Override(!shown, state.nextMs < 0 ? Long.MAX_VALUE : state.nextMs);
    }

    /** What riding mode shows: the override until it runs out, then the schedule. */
    static boolean shown(State state, Override override, long nowMs) {
        return override != null && nowMs < override.untilMs ? override.night : state.night;
    }

    /** When to look again (epoch ms): the next switch or the override's end, whichever is first; -1 for never. */
    static long wake(State state, Override override, long nowMs) {
        long next = state.nextMs;
        if (override != null && nowMs < override.untilMs && override.untilMs != Long.MAX_VALUE)
            next = next < 0 ? override.untilMs : Math.min(next, override.untilMs);
        return next;
    }

    private static long time(LocalDate date, int minutes, ZoneId zone) {
        return date.atTime(LocalTime.of(minutes / 60, minutes % 60)).atZone(zone).toInstant().toEpochMilli();
    }

    private static void add(List<long[]> spans, long from, long to) { if (to > from) spans.add(new long[]{from, to}); }

    private static List<long[]> merge(List<long[]> spans) {
        spans.sort((a, b) -> Long.compare(a[0], b[0]));
        List<long[]> out = new ArrayList<>();
        for (long[] span : spans) {
            long[] last = out.isEmpty() ? null : out.get(out.size() - 1);
            if (last != null && span[0] <= last[1]) last[1] = Math.max(last[1], span[1]);
            else out.add(new long[]{span[0], span[1]});
        }
        return out;
    }
}
