package com.jjricks.volt;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Sunrise and sunset on the phone, for night vision's schedule (ride-display plan, PR 3). It uses NOAA's
 * solar calculator equations: the sun's centre 0.833° below the horizon (refraction and the sun's
 * radius), about a minute from published times for latitudes below the polar circles. Nothing is looked
 * up online. Pure Java, so the JVM tests check it against reference times.
 */
final class SunTimes {
    private SunTimes() { }

    /** A local day's sun: the times of a normal day, or a day the sun never sets or never rises. */
    static final class Day {
        enum Kind { NORMAL, ALWAYS_DAY, ALWAYS_NIGHT }
        final Kind kind;
        /** Epoch ms; meaningful for NORMAL only. */
        final long riseMs, setMs;
        Day(Kind kind, long riseMs, long setMs) { this.kind = kind; this.riseMs = riseMs; this.setMs = setMs; }
    }

    private static final double ZENITH = 90.833;
    private static final long MINUTE = 60_000L, DAY = 86_400_000L;

    /** The sun on the local calendar day date in zone, at latitude and longitude (east positive). */
    static Day day(LocalDate date, ZoneId zone, double latitude, double longitude) {
        long start = date.atStartOfDay(zone).toInstant().toEpochMilli();
        long end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        // The UTC day whose solar noon falls inside this local day (a date-line zone's is the day before).
        long middle = start + (end - start) / 2;
        long utcDay = Math.floorDiv(middle, DAY) * DAY;
        long noon = utcDay + Math.round((720 - 4 * longitude) * MINUTE);
        if (noon - middle > DAY / 2) utcDay -= DAY;
        else if (middle - noon > DAY / 2) utcDay += DAY;
        double julianDay = utcDay / (double) DAY + 2440587.5;
        Double rise = event(true, julianDay, latitude, longitude), set = event(false, julianDay, latitude, longitude);
        if (rise == null || set == null) {
            double t = century(julianDay + 0.5 - longitude / 360);
            boolean up = altitudeAtNoon(t, latitude) > 0;
            return new Day(up ? Day.Kind.ALWAYS_DAY : Day.Kind.ALWAYS_NIGHT, -1, -1);
        }
        return new Day(Day.Kind.NORMAL, utcDay + Math.round(rise * MINUTE), utcDay + Math.round(set * MINUTE));
    }

    /** Minutes after 00:00 UTC of julianDay's date, refined once at the event; null when it doesn't happen. */
    private static Double event(boolean rise, double julianDay, double latitude, double longitude) {
        Double minutes = minutes(rise, century(julianDay + 0.5 - longitude / 360), latitude, longitude);
        if (minutes == null) return null;
        Double refined = minutes(rise, century(julianDay + minutes / 1440), latitude, longitude);
        return refined == null ? minutes : refined;
    }

    private static Double minutes(boolean rise, double t, double latitude, double longitude) {
        double declination = declination(t);
        double cosHour = (Math.cos(Math.toRadians(ZENITH)) / (Math.cos(Math.toRadians(latitude)) * Math.cos(declination)))
                - Math.tan(Math.toRadians(latitude)) * Math.tan(declination);
        if (cosHour > 1 || cosHour < -1) return null;
        double hour = Math.toDegrees(Math.acos(cosHour));
        double noon = 720 - 4 * longitude - equationOfTime(t);
        return rise ? noon - 4 * hour : noon + 4 * hour;
    }

    private static double altitudeAtNoon(double t, double latitude) {
        return 90 - Math.abs(latitude - Math.toDegrees(declination(t)));
    }

    private static double century(double julianDay) { return (julianDay - 2451545.0) / 36525.0; }

    private static double meanLongitude(double t) {
        double l = (280.46646 + t * (36000.76983 + t * 0.0003032)) % 360;
        return l < 0 ? l + 360 : l;
    }
    private static double meanAnomaly(double t) { return 357.52911 + t * (35999.05029 - 0.0001537 * t); }
    private static double eccentricity(double t) { return 0.016708634 - t * (0.000042037 + 0.0000001267 * t); }
    private static double omega(double t) { return Math.toRadians(125.04 - 1934.136 * t); }

    private static double obliquity(double t) {
        double mean = 23 + (26 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60) / 60;
        return Math.toRadians(mean + 0.00256 * Math.cos(omega(t)));
    }

    private static double declination(double t) {
        double m = Math.toRadians(meanAnomaly(t));
        double center = Math.sin(m) * (1.914602 - t * (0.004817 + 0.000014 * t))
                + Math.sin(2 * m) * (0.019993 - 0.000101 * t) + Math.sin(3 * m) * 0.000289;
        double apparent = Math.toRadians(meanLongitude(t) + center - 0.00569 - 0.00478 * Math.sin(omega(t)));
        return Math.asin(Math.sin(obliquity(t)) * Math.sin(apparent));
    }

    /** Minutes. */
    private static double equationOfTime(double t) {
        double e = eccentricity(t), l = Math.toRadians(meanLongitude(t)), m = Math.toRadians(meanAnomaly(t));
        double y = Math.tan(obliquity(t) / 2); y *= y;
        double value = y * Math.sin(2 * l) - 2 * e * Math.sin(m) + 4 * e * y * Math.sin(m) * Math.cos(2 * l)
                - 0.5 * y * y * Math.sin(4 * l) - 1.25 * e * e * Math.sin(2 * m);
        return 4 * Math.toDegrees(value);
    }
}
