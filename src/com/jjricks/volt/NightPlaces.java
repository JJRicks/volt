package com.jjricks.volt;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

/**
 * The places night vision's sunset schedule can use, without any new permission or network
 * (ride-display plan, PR 3):
 *   - the last place a ride was recorded with location, rounded to 0.1° (about 11 km), only once the
 *     rider has agreed (owner Q21 A). It's read from the newest phone journals each time and never
 *     stored, so deleting rides removes it;
 *   - the time zone's reference city from tzdata, in res/raw/tz_coords.txt (tools/riding/tz_coords.py).
 * Pure Java.
 */
final class NightPlaces {
    private NightPlaces() { }

    /** ScooterService's folder of phone journals, under the app's files. */
    static final String JOURNAL_DIR = "phone-rides";
    /** The newest journals checked for a fix, and how much of each is read. */
    static final int JOURNALS = 5, READ_BYTES = 256 * 1024;
    /** Phone journals store fixes up to 35 m accuracy; the schedule needs far less. */
    private static final double ACCURACY_M = 200;

    /** Zone id to {latitude, longitude} from the generated table: "Zone lat lon" lines, # comments. */
    static Map<String, double[]> zones(Reader source) throws IOException {
        Map<String, double[]> out = new HashMap<>();
        BufferedReader in = new BufferedReader(source);
        for (String line; (line = in.readLine()) != null; ) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] parts = line.split("\\s+");
            if (parts.length != 3) continue;
            try {
                double latitude = Double.parseDouble(parts[1]), longitude = Double.parseDouble(parts[2]);
                if (Math.abs(latitude) <= 90 && Math.abs(longitude) <= 180) out.put(parts[0], new double[]{latitude, longitude});
            } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    /** The time zone's place, or null when the table doesn't know the zone. */
    static NightSchedule.Place zone(Map<String, double[]> table, String id) {
        double[] place = id == null ? null : table.get(id);
        return place == null ? null : new NightSchedule.Place(place[0], place[1], NightSchedule.Source.ZONE);
    }

    /**
     * The first good fix of the newest phone journal (…-phone.csv) that has one, rounded; null when no
     * recent ride was recorded with location.
     */
    static NightSchedule.Place lastRide(File directory) {
        File[] journals = directory == null ? null : directory.listFiles((dir, name) -> name.endsWith("-phone.csv"));
        if (journals == null || journals.length == 0) return null;
        Arrays.sort(journals, Comparator.comparingLong(File::lastModified).reversed());
        for (int i = 0; i < Math.min(JOURNALS, journals.length); i++) {
            try (InputStream in = new FileInputStream(journals[i])) {
                NightSchedule.Place place = firstFix(in);
                if (place != null) return place;
            } catch (IOException | RuntimeException ignored) { }
        }
        return null;
    }

    /** The first GPS row of a phone journal: GPS,wall,mono,latitude,longitude,accuracy,… */
    static NightSchedule.Place firstFix(InputStream journal) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(journal, StandardCharsets.UTF_8));
        int read = 0;
        for (String line; (line = in.readLine()) != null && read < READ_BYTES; ) {
            read += line.length() + 1;
            if (!line.startsWith("GPS,")) continue;
            String[] v = line.split(",", -1);
            if (v.length < 6) continue;
            try {
                double latitude = Double.parseDouble(v[3]), longitude = Double.parseDouble(v[4]), accuracy = Double.parseDouble(v[5]);
                if (Math.abs(latitude) > 90 || Math.abs(longitude) > 180 || !(accuracy >= 0) || accuracy > ACCURACY_M) continue;
                if (latitude == 0 && longitude == 0) continue;
                return new NightSchedule.Place(latitude, longitude, NightSchedule.Source.RIDE);
            } catch (NumberFormatException ignored) { }
        }
        return null;
    }
}
