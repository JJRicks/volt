package com.jjricks.volt;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;

/**
 * Night vision's schedule (ride-display plan, PR 3): SunTimes against published sunrise and sunset
 * times and against an independent algorithm (the Almanac for Computers' sunrise equation),
 * NightSchedule's windows (overnight, DST days, polar days, the moon override), NightPlaces (the
 * generated time-zone table and the rounded last-ride fix) and NightVision's dim range and filter.
 * Listed in tests/l10n/riding-night.sources; tests/run.sh runs it from the repository root.
 */
public final class NightScheduleTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static final long MINUTE = 60_000L, HOUR = 60 * MINUTE;

    public static void main(String[] args) throws IOException {
        published();
        almanacAgreement();
        polar();
        dateLine();
        dstDays();
        customWindows();
        sunWindows();
        overrides();
        offOn();
        normalizing();
        zoneTable();
        rideFix();
        nightVision();
        System.out.println("NightScheduleTest: " + checks + " checks passed");
    }

    private static final ZoneId DENVER = ZoneId.of("America/Denver"), MADRID = ZoneId.of("Europe/Madrid"),
            OSLO = ZoneId.of("Europe/Oslo"), KIRITIMATI = ZoneId.of("Pacific/Kiritimati"), AUCKLAND = ZoneId.of("Pacific/Auckland");

    /** Published times (NOAA's solar calculator, to the minute), within 2 minutes. */
    private static void published() {
        near(DENVER, "2026-06-21", 40.7608, -111.8910, "05:57", "21:03", "Salt Lake City, June");
        near(DENVER, "2026-12-21", 40.7608, -111.8910, "07:48", "17:03", "Salt Lake City, December");
        near(MADRID, "2026-06-21", 40.4168, -3.7038, "06:45", "21:48", "Madrid, June");
        near(MADRID, "2026-12-21", 40.4168, -3.7038, "08:34", "17:51", "Madrid, December");
        near(AUCKLAND, "2026-12-21", -36.8485, 174.7633, "05:58", "20:40", "Auckland, December");
        near(AUCKLAND, "2026-06-21", -36.8485, 174.7633, "07:33", "17:12", "Auckland, June");
    }

    private static void near(ZoneId zone, String date, double latitude, double longitude, String rise, String set, String what) {
        SunTimes.Day day = SunTimes.day(LocalDate.parse(date), zone, latitude, longitude);
        check(day.kind == SunTimes.Day.Kind.NORMAL, what + ": a normal day");
        long expectedRise = LocalDate.parse(date).atTime(LocalTime.parse(rise)).atZone(zone).toInstant().toEpochMilli();
        long expectedSet = LocalDate.parse(date).atTime(LocalTime.parse(set)).atZone(zone).toInstant().toEpochMilli();
        check(Math.abs(day.riseMs - expectedRise) <= 2 * MINUTE, what + ": sunrise " + clock(day.riseMs, zone) + " vs " + rise);
        check(Math.abs(day.setMs - expectedSet) <= 2 * MINUTE, what + ": sunset " + clock(day.setMs, zone) + " vs " + set);
    }

    /** Every 9th day of 2026, latitudes −55..60 and longitudes all round: within 3 minutes of the Almanac's equation. */
    private static void almanacAgreement() {
        int compared = 0;
        double worst = 0;
        for (double latitude = -55; latitude <= 60; latitude += 11.5)
            for (double longitude = -175; longitude <= 175; longitude += 35)
                for (int n = 1; n <= 365; n += 9) {
                    LocalDate date = LocalDate.ofYearDay(2026, n);
                    SunTimes.Day day = SunTimes.day(date, ZoneId.of("UTC"), latitude, longitude);
                    Double rise = almanac(n, latitude, longitude, true), set = almanac(n, latitude, longitude, false);
                    if (rise == null || set == null) continue;
                    check(day.kind == SunTimes.Day.Kind.NORMAL, "normal day at " + latitude + "," + longitude);
                    worst = Math.max(worst, Math.max(gap(day.riseMs, rise), gap(day.setMs, set)));
                    compared++;
                }
        check(compared > 1000, "compared " + compared);
        check(worst <= 3, "SunTimes and the Almanac agree within 3 minutes (worst " + worst + ")");
    }

    /** Minutes between an epoch time's UTC time of day and hours (UT), around the clock. */
    private static double gap(long epochMs, double hours) {
        double ours = Math.floorMod(epochMs, 86_400_000L) / 60_000.0, theirs = hours * 60;
        double d = Math.abs(ours - theirs) % 1440;
        return Math.min(d, 1440 - d);
    }

    /** The Almanac for Computers (1990) sunrise equation, UT hours; null when the sun doesn't rise or set. */
    private static Double almanac(int dayOfYear, double latitude, double longitude, boolean rise) {
        double lngHour = longitude / 15, t = dayOfYear + ((rise ? 6 : 18) - lngHour) / 24;
        double m = 0.9856 * t - 3.289;
        double l = mod(m + 1.916 * sin(m) + 0.020 * sin(2 * m) + 282.634, 360);
        double ra = mod(Math.toDegrees(Math.atan(0.91764 * Math.tan(Math.toRadians(l)))), 360);
        ra = (ra + (Math.floor(l / 90) * 90 - Math.floor(ra / 90) * 90)) / 15;
        double sinDec = 0.39782 * sin(l), cosDec = Math.cos(Math.asin(sinDec));
        double cosH = (Math.cos(Math.toRadians(90.833)) - sinDec * sin(latitude)) / (cosDec * Math.cos(Math.toRadians(latitude)));
        if (cosH > 1 || cosH < -1) return null;
        double h = (rise ? 360 - Math.toDegrees(Math.acos(cosH)) : Math.toDegrees(Math.acos(cosH))) / 15;
        return mod(h + ra - 0.06571 * t - 6.622 - lngHour, 24);
    }
    private static double sin(double degrees) { return Math.sin(Math.toRadians(degrees)); }
    private static double mod(double v, double m) { double r = v % m; return r < 0 ? r + m : r; }

    private static void polar() {
        check(SunTimes.day(LocalDate.parse("2026-06-21"), OSLO, 69.6492, 18.9553).kind == SunTimes.Day.Kind.ALWAYS_DAY, "Tromsø in June: no sunset");
        check(SunTimes.day(LocalDate.parse("2026-12-21"), OSLO, 69.6492, 18.9553).kind == SunTimes.Day.Kind.ALWAYS_NIGHT, "Tromsø in December: no sunrise");
        check(SunTimes.day(LocalDate.parse("2026-03-20"), OSLO, 69.6492, 18.9553).kind == SunTimes.Day.Kind.NORMAL, "Tromsø at the equinox: a normal day");
        NightSchedule.Place tromso = new NightSchedule.Place(69.6492, 18.9553, NightSchedule.Source.RIDE);
        long june = at(OSLO, "2026-06-21T23:30"), december = at(OSLO, "2026-12-21T12:00");
        NightSchedule.State summer = sun(tromso, OSLO, june);
        check(!summer.night && summer.today == SunTimes.Day.Kind.ALWAYS_DAY, "midnight sun: day, \"No sunset today\"");
        check(summer.nextMs == -1, "midnight sun: no switch in the next days");
        NightSchedule.State winter = sun(tromso, OSLO, december);
        check(winter.night && winter.today == SunTimes.Day.Kind.ALWAYS_NIGHT && winter.nextMs == -1, "polar night: night all day, no switch");
        // The first sunrise after the polar night (mid-January) shows up as the next switch once it's close.
        NightSchedule.State january = sun(tromso, OSLO, at(OSLO, "2027-01-14T10:00"));
        check(january.night && january.nextMs > 0, "the polar night's end is found once it's within the window");
    }

    /** Kiritimati (UTC+14): the local day's sun, not the UTC day's. */
    private static void dateLine() {
        for (String date : new String[]{"2026-01-01", "2026-06-21", "2026-12-31"}) {
            SunTimes.Day day = SunTimes.day(LocalDate.parse(date), KIRITIMATI, 1.87, -157.43);
            long start = LocalDate.parse(date).atStartOfDay(KIRITIMATI).toInstant().toEpochMilli();
            check(day.riseMs > start && day.setMs < start + 24 * HOUR, "Kiritimati " + date + ": sunrise and sunset on that local day");
            String rise = clock(day.riseMs, KIRITIMATI), set = clock(day.setMs, KIRITIMATI);
            check(rise.compareTo("06:00") > 0 && rise.compareTo("07:00") < 0, "Kiritimati sunrise about 06:30: " + rise);
            check(set.compareTo("18:00") > 0 && set.compareTo("19:00") < 0, "Kiritimati sunset about 18:30: " + set);
        }
        SunTimes.Day apia = SunTimes.day(LocalDate.parse("2026-06-21"), ZoneId.of("Pacific/Apia"), -13.83, -171.76);
        check(clock(apia.riseMs, ZoneId.of("Pacific/Apia")).startsWith("06:") || clock(apia.riseMs, ZoneId.of("Pacific/Apia")).startsWith("07:"), "Apia sunrise in the morning");
    }

    /** DST days in Denver: the spring day has 23 hours, the autumn day 25; custom times keep the wall clock. */
    private static void dstDays() {
        long spring = at(DENVER, "2026-03-08T12:00");
        NightSchedule.State s = custom(20 * 60, 7 * 60, DENVER, spring);
        check(!s.night && s.nextMs == at(DENVER, "2026-03-08T20:00"), "spring DST day: on at 20:00 local");
        NightSchedule.State before = custom(20 * 60, 7 * 60, DENVER, at(DENVER, "2026-03-08T01:30"));
        check(before.night && before.nextMs == at(DENVER, "2026-03-08T07:00"), "spring DST night ends at 07:00 local");
        // 02:30 doesn't exist on the spring day: a custom time there moves to 03:30, as java.time does.
        NightSchedule.State gapped = custom(2 * 60 + 30, 7 * 60, DENVER, at(DENVER, "2026-03-08T01:00"));
        check(!gapped.night && gapped.nextMs == ZonedDateTime.of(LocalDateTime.parse("2026-03-08T03:30"), DENVER).toInstant().toEpochMilli(), "a custom time in the spring gap starts after it");
        NightSchedule.State autumn = custom(20 * 60, 7 * 60, DENVER, at(DENVER, "2026-11-01T01:30"));
        check(autumn.night && autumn.nextMs == at(DENVER, "2026-11-01T07:00"), "autumn DST night ends at 07:00 local");
        check(autumn.nextMs - at(DENVER, "2026-10-31T20:00") == 12 * HOUR, "the autumn night is an hour longer");
        // Sun times across DST: sunset moves by about an hour on the wall clock, sunrise likewise.
        NightSchedule.Place slc = new NightSchedule.Place(40.76, -111.89, NightSchedule.Source.ZONE);
        long sunsetBefore = sun(slc, DENVER, at(DENVER, "2026-03-07T12:00")).nextMs, sunsetAfter = sun(slc, DENVER, at(DENVER, "2026-03-08T12:00")).nextMs;
        check(sunsetAfter - sunsetBefore > 23 * HOUR && sunsetAfter - sunsetBefore < 25 * HOUR, "sunset a day apart across spring DST");
        check(clock(sunsetAfter, DENVER).compareTo("19:00") > 0 && clock(sunsetBefore, DENVER).compareTo("19:00") < 0, "sunset jumps past 19:00 on the wall clock");
    }

    private static void customWindows() {
        // Overnight 20:00–07:00.
        check(!custom(20 * 60, 7 * 60, MADRID, at(MADRID, "2026-07-01T19:59")).night, "19:59: day");
        NightSchedule.State evening = custom(20 * 60, 7 * 60, MADRID, at(MADRID, "2026-07-01T20:00"));
        check(evening.night && evening.nextMs == at(MADRID, "2026-07-02T07:00"), "20:00: night until 07:00 next morning (across midnight)");
        NightSchedule.State midnight = custom(20 * 60, 7 * 60, MADRID, at(MADRID, "2026-07-02T00:00"));
        check(midnight.night && midnight.nextMs == at(MADRID, "2026-07-02T07:00"), "midnight: still night, the same switch");
        NightSchedule.State morning = custom(20 * 60, 7 * 60, MADRID, at(MADRID, "2026-07-02T07:00"));
        check(!morning.night && morning.nextMs == at(MADRID, "2026-07-02T20:00"), "07:00: day until 20:00");
        // Same-day window 01:00–05:00.
        NightSchedule.State inside = custom(60, 5 * 60, MADRID, at(MADRID, "2026-07-02T03:00"));
        check(inside.night && inside.nextMs == at(MADRID, "2026-07-02T05:00"), "a same-day window");
        check(!custom(60, 5 * 60, MADRID, at(MADRID, "2026-07-02T06:00")).night, "after a same-day window");
        // Equal times: never night.
        NightSchedule.State equal = custom(8 * 60, 8 * 60, MADRID, at(MADRID, "2026-07-02T08:00"));
        check(!equal.night && equal.nextMs == -1, "equal times: never night, no switch");
        check(equal.source == NightSchedule.Source.CUSTOM, "custom source");
        // Out-of-range stored minutes fall back to the defaults.
        NightSchedule.State fallback = custom(-5, 99_999, MADRID, at(MADRID, "2026-07-01T21:00"));
        check(fallback.night && fallback.nextMs == at(MADRID, "2026-07-02T07:00"), "bad stored minutes use 20:00–07:00");
    }

    private static void sunWindows() {
        NightSchedule.Place madrid = new NightSchedule.Place(40.4168, -3.7038, NightSchedule.Source.RIDE);
        check(madrid.latitude == 40.4 && madrid.longitude == -3.7, "a place keeps 0.1° only");
        NightSchedule.State noon = sun(madrid, MADRID, at(MADRID, "2026-06-21T14:00"));
        check(!noon.night && noon.source == NightSchedule.Source.RIDE && noon.today == SunTimes.Day.Kind.NORMAL, "Madrid at 14:00: day, from the ride");
        check(Math.abs(noon.nextMs - at(MADRID, "2026-06-21T21:48")) <= 3 * MINUTE, "next: sunset about 21:48, " + clock(noon.nextMs, MADRID));
        NightSchedule.State late = sun(madrid, MADRID, at(MADRID, "2026-06-21T23:00"));
        check(late.night && Math.abs(late.nextMs - at(MADRID, "2026-06-22T06:45")) <= 3 * MINUTE, "23:00: night until about 06:45, " + clock(late.nextMs, MADRID));
        NightSchedule.State early = sun(madrid, MADRID, at(MADRID, "2026-06-22T03:00"));
        check(early.night && early.nextMs == late.nextMs, "03:00: the same night");
        // No place: Scheduled sunset falls back to the custom times, and says so.
        NightSchedule.State none = NightSchedule.state(NightSchedule.SCHEDULED, NightSchedule.SUN, 20 * 60, 7 * 60, null, MADRID, at(MADRID, "2026-06-21T21:00"));
        check(none.night && none.source == NightSchedule.Source.NONE && none.today == null, "no place: the custom times, Source.NONE");
    }

    private static void overrides() {
        NightSchedule.Place madrid = new NightSchedule.Place(40.4, -3.7, NightSchedule.Source.ZONE);
        long now = at(MADRID, "2026-06-21T14:00");
        NightSchedule.State day = sun(madrid, MADRID, now);
        boolean shown = NightSchedule.shown(day, null, now);
        check(!shown, "no override: the schedule");
        NightSchedule.Override tap = NightSchedule.tap(day, shown);
        check(tap.night && tap.untilMs == day.nextMs, "a tap by day turns it on until sunset");
        check(NightSchedule.shown(day, tap, now + HOUR), "shown on after the tap");
        check(NightSchedule.wake(day, tap, now) == day.nextMs, "wake at sunset");
        // At sunset the override runs out and the schedule says night: still on, now from the schedule.
        NightSchedule.State dusk = sun(madrid, MADRID, day.nextMs);
        check(dusk.night && NightSchedule.shown(dusk, tap, day.nextMs), "at sunset the schedule takes over");
        // A tap at night turns it off until sunrise; at sunrise the schedule says day.
        NightSchedule.Override off = NightSchedule.tap(dusk, true);
        check(!off.night && off.untilMs == dusk.nextMs, "a tap at night turns it off until sunrise");
        check(!NightSchedule.shown(dusk, off, dusk.nextMs - MINUTE), "off until sunrise");
        NightSchedule.State dawn = sun(madrid, MADRID, dusk.nextMs);
        check(!dawn.night && !NightSchedule.shown(dawn, off, dusk.nextMs), "after sunrise: day from the schedule");
        // A second tap undoes the first.
        NightSchedule.Override back = NightSchedule.tap(day, NightSchedule.shown(day, tap, now));
        check(!back.night && !NightSchedule.shown(day, back, now), "a second tap goes back");
        // A tap in polar day lasts the visit.
        NightSchedule.State polar = sun(new NightSchedule.Place(69.6, 19, NightSchedule.Source.RIDE), OSLO, at(OSLO, "2026-06-21T23:30"));
        NightSchedule.Override always = NightSchedule.tap(polar, false);
        check(always.night && always.untilMs == Long.MAX_VALUE && NightSchedule.wake(polar, always, 0) == -1, "polar day: a tap lasts while riding mode is open");
    }

    private static void offOn() {
        long now = at(MADRID, "2026-06-21T23:00");
        NightSchedule.State off = NightSchedule.state(NightSchedule.OFF, NightSchedule.SUN, 0, 0, null, MADRID, now);
        check(!off.night && off.nextMs == -1 && off.source == null, "Off: never night");
        NightSchedule.State on = NightSchedule.state(NightSchedule.ON, NightSchedule.SUN, 0, 0, null, MADRID, now);
        check(on.night && on.nextMs == -1, "On: always night");
        NightSchedule.Override tap = NightSchedule.tap(on, true);
        check(!tap.night && tap.untilMs == Long.MAX_VALUE && !NightSchedule.shown(on, tap, now + 100 * HOUR), "On: a tap turns it off while riding mode is open");
        check(NightSchedule.shown(off, NightSchedule.tap(off, false), now), "Off: a tap turns it on");
    }

    private static void normalizing() {
        check(NightSchedule.mode(null).equals(NightSchedule.OFF) && NightSchedule.mode("bright").equals(NightSchedule.OFF), "unknown mode: Off");
        check(NightSchedule.mode("scheduled").equals(NightSchedule.SCHEDULED) && NightSchedule.mode("on").equals(NightSchedule.ON), "known modes kept");
        check(NightSchedule.schedule(null).equals(NightSchedule.SUN) && NightSchedule.schedule("custom").equals(NightSchedule.CUSTOM), "schedule default: sunset to sunrise");
        check(NightSchedule.minutes(1439, 5) == 1439 && NightSchedule.minutes(1440, 5) == 5 && NightSchedule.minutes(-1, 5) == 5, "minutes range");
        check(NightSchedule.round(40.76) == 40.8 && NightSchedule.round(-111.89) == -111.9 && NightSchedule.round(0.04) == 0.0, "rounding to 0.1°");
        for (String key : new String[]{NightSchedule.MODE_KEY, NightSchedule.SCHEDULE_KEY, NightSchedule.START_KEY, NightSchedule.END_KEY, NightSchedule.AREA_KEY})
            check(key.startsWith("riding_night_"), "key " + key);
    }

    private static void zoneTable() throws IOException {
        Map<String, double[]> table;
        try (InputStreamReader in = new InputStreamReader(new FileInputStream("res/raw/tz_coords.txt"), StandardCharsets.UTF_8)) {
            table = NightPlaces.zones(in);
        }
        check(table.size() > 400, "the table has every zone: " + table.size());
        String[] expected = {"America/Denver", "Europe/Madrid", "Europe/Oslo", "Pacific/Kiritimati", "Asia/Tokyo", "Australia/Sydney", "America/New_York", "Europe/London"};
        for (String zone : expected) check(table.containsKey(zone), "zone " + zone);
        check(table.containsKey("US/Mountain") && table.get("US/Mountain")[1] == table.get("America/Denver")[1], "links share their target's place");
        NightSchedule.Place denver = NightPlaces.zone(table, "America/Denver");
        check(denver.source == NightSchedule.Source.ZONE && denver.latitude == 39.7 && denver.longitude == -105.0, "Denver's reference city");
        check(NightPlaces.zone(table, "Mars/Olympus") == null && NightPlaces.zone(table, null) == null, "an unknown zone has no place");
        for (Map.Entry<String, double[]> e : table.entrySet()) {
            double[] p = e.getValue();
            check(Math.abs(p[0]) <= 90 && Math.abs(p[1]) <= 180 && Math.round(p[0] * 10) == p[0] * 10, "row " + e.getKey());
        }
        String text = new String(Files.readAllBytes(new File("res/raw/tz_coords.txt").toPath()), StandardCharsets.UTF_8);
        check(text.startsWith("# Night vision's time-zone places") && text.contains("tools/riding/tz_coords.py"), "the table says where it comes from");
        // Malformed lines are skipped.
        Map<String, double[]> odd = NightPlaces.zones(new java.io.StringReader("# c\n\nA/B 1.0 2.0\nbad\nC/D x 1\nE/F 91 0\n"));
        check(odd.size() == 1 && odd.containsKey("A/B"), "bad rows skipped");
    }

    private static void rideFix() throws IOException {
        String journal = "event,wall_ms,monotonic_ms,a,b,c,d,e,f,g,h,i,j\nSTART,1,1,\nTELEMETRY,2,2,1,2,3\n"
                + "GPS,3,3,40.76012,-111.89104,500,1,1,1,1\nGPS,4,4,40.76543,-111.88765,8,5,1,1300,2\nGPS,5,5,41.5,-112.5,8,5,1,1300,2\n";
        NightSchedule.Place first = NightPlaces.firstFix(new ByteArrayInputStream(journal.getBytes(StandardCharsets.UTF_8)));
        check(first != null && first.latitude == 40.8 && first.longitude == -111.9 && first.source == NightSchedule.Source.RIDE, "the first good fix, rounded");
        check(NightPlaces.firstFix(new ByteArrayInputStream("GPS,1,1,0,0,5\nGPS,1,1,95,0,5\nGPS,1,1,a,b,c\n".getBytes(StandardCharsets.UTF_8))) == null, "null island, out-of-range and garbage rejected");
        File dir = Files.createTempDirectory("night-places").toFile();
        try {
            check(NightPlaces.lastRide(dir) == null, "no rides: no place");
            File older = write(dir, "ride-a-phone.csv", "GPS,1,1,10.04,20.04,5\n", 1_000_000);
            File newer = write(dir, "ride-b-phone.csv", "START,1,1,\n", 2_000_000);
            write(dir, "ride-b-motion.csv", "GPS,1,1,50,50,5\n", 3_000_000);
            NightSchedule.Place last = NightPlaces.lastRide(dir);
            check(last != null && last.latitude == 10.0 && last.longitude == 20.0, "the newest journal with a fix; motion files ignored");
            write(dir, "ride-c-phone.csv", "GPS,1,1,-33.87,151.21,9\n", 4_000_000);
            check(NightPlaces.lastRide(dir).latitude == -33.9, "a newer ride wins");
            check(older.delete() && newer.delete(), "cleanup");
            check(new File(dir, "ride-c-phone.csv").delete(), "deleting rides removes the place");
            check(NightPlaces.lastRide(dir) == null, "no ride, no place: nothing is kept");
        } finally {
            File[] left = dir.listFiles();
            if (left != null) for (File f : left) f.delete();
            dir.delete();
        }
        check(NightPlaces.lastRide(null) == null && NightPlaces.lastRide(new File("/nonexistent-night")) == null, "a missing folder");
        check(NightPlaces.JOURNAL_DIR.equals("phone-rides"), "the phone journals' folder (ScooterService)");
        String service = new String(Files.readAllBytes(new File("src/com/jjricks/volt/ScooterService.java").toPath()), StandardCharsets.UTF_8);
        check(service.contains("new PhoneRideStore(new File(getFilesDir(), \"phone-rides\"))"), "ScooterService keeps the journals there");
        String store = new String(Files.readAllBytes(new File("src/com/jjricks/volt/PhoneRideStore.java").toPath()), StandardCharsets.UTF_8);
        check(store.contains("return file(id, \"-phone.csv\");") && store.contains("event.equals(\"GPS\")") && store.contains("q.lat = required(v, 3);")
                && store.contains("q.lon = required(v, 4);") && store.contains("q.accuracy = required(v, 5);"), "the journal layout NightPlaces reads");
    }

    private static File write(File dir, String name, String text, long modified) throws IOException {
        File f = new File(dir, name);
        try (FileOutputStream out = new FileOutputStream(f)) { out.write(text.getBytes(StandardCharsets.UTF_8)); }
        check(f.setLastModified(modified), "mtime " + name);
        return f;
    }

    private static void nightVision() {
        check(Math.abs(NightVision.level(BrightnessCurve.FLOOR) - NightVision.FLOOR) < 1e-6, "the day floor maps to 0.01");
        check(Math.abs(NightVision.level(1f) - NightVision.CEILING) < 1e-6, "full maps to 0.35");
        check(NightVision.level(-1f) == -1f, "the phone's own brightness stays the phone's");
        float last = 0;
        for (int n = 0; n <= BrightnessCurve.NOTCHES; n++) {
            float level = NightVision.level(BrightnessCurve.level(n));
            check(level > last && level <= NightVision.CEILING, "night levels rise with the notch");
            last = level;
        }
        check(NightVision.level(2f) == NightVision.CEILING && NightVision.level(0f) == NightVision.FLOOR, "out-of-range clamps");
        int[] white = NightVision.filter(255, 255, 255), green = NightVision.filter(0x5F, 0xCB, 0x7E), red = NightVision.filter(0xE3, 0x2B, 0x2F);
        int[] grey = NightVision.filter(0x9A, 0x9C, 0x95);
        for (int[] c : new int[][]{white, green, red, grey}) check(c[2] == 0 && c[1] < c[0] / 4, "no blue, almost no green");
        check(white[0] > red[0] && red[0] > grey[0] / 2, "white is brightest; red accents stay visible");
        check(red[0] > 120, "the red accent keeps its strength: " + red[0]);
        check(NightVision.MATRIX.length == 20 && NightVision.MATRIX[18] == 1, "a 4×5 matrix keeping alpha");
    }

    private static NightSchedule.State sun(NightSchedule.Place place, ZoneId zone, long now) {
        return NightSchedule.state(NightSchedule.SCHEDULED, NightSchedule.SUN, 0, 0, place, zone, now);
    }
    private static NightSchedule.State custom(int start, int end, ZoneId zone, long now) {
        return NightSchedule.state(NightSchedule.SCHEDULED, NightSchedule.CUSTOM, start, end, null, zone, now);
    }
    private static long at(ZoneId zone, String local) { return LocalDateTime.parse(local).atZone(zone).toInstant().toEpochMilli(); }
    private static String clock(long ms, ZoneId zone) { return Instant.ofEpochMilli(ms).atZone(zone).toLocalTime().toString().substring(0, 5); }
}
