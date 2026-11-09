package com.jjricks.volt;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * The files one ride can export by hand from ride details (Rides): each format's id, file name, MIME type and
 * content, which formats a ride offers, the remembered choice, and the staging folder a several-file export
 * writes before Android's folder picker answers. Pure (no Android types), so the host tests run it.
 *
 * Every file but Everything (JSON) is byte for byte what the separate Export CSV, Export JSON and Export phone data
 * buttons wrote: RideStore.exportCsv's copy of the sensor journal, Summary.toJson, Track.toCsv, toGpx, the phone's
 * raw journal and motion files as they are, and combinedJson. Everything (JSON) is
 * RideExportEverything's one file with all of those recordings (docs/ride-export-everything.md). Files, names and stored values are
 * English and locale-independent; nothing here reads the catalog (RideExportFilesTest proves it under a
 * throwing text source). Automatic ride copies (RideAutoExport) save this class's Everything payload, with their own
 * folder and settings.
 */
final class RideExportFiles {
    private RideExportFiles() { }

    /** The remembered choice: format ids, comma-separated. Backed up with App preferences. */
    static final String CHOICE = "ride_export_choice";
    /** The folder a several-file export last went to, only as the picker's starting place. Never backed up. */
    static final String FOLDER_HINT = "ride_export_folder_hint";
    /** Intent extras for the several-file save: the staging folder's path, the ride id and the format ids. */
    static final String EXTRA_DIR = "ride_export_dir", EXTRA_RIDE = "ride_export_ride", EXTRA_FORMATS = "ride_export_formats";
    /** Staging folders in the cache: this prefix plus the time they were made. */
    private static final String STAGING = "ride-export-";
    /** A staging folder older than this was left by a pick that never answered (the process died). */
    static final long STALE_MS = 24L * 60 * 60 * 1000;

    /**
     * The sheet lists Everything first and checks it by default. While false (before the sheet had its label), it stayed
     * off the sheet and out of the default and the remembered choice, and every other format behaved as before.
     */
    static final boolean EVERYTHING_ON_SHEET = true;

    /** One export, in the order the sheet lists them: Everything first, then the scooter's two files, then the phone's five. */
    enum Format {
        /** All of the ride's recordings in one JSON file (RideExportEverything). Offered whenever the ride is. */
        EVERYTHING_JSON("everything_json", "-everything.json", "application/json", false),
        /** Ride details, Export CSV: the scooter sensor journal as it is. */
        SENSOR_CSV("sensor_csv", "-sensors.csv", "text/csv", false),
        /** Ride details, Export JSON: the ride's analysis. */
        ANALYSIS_JSON("analysis_json", "-analysis.json", "application/json", false),
        /** Export phone data, Route CSV. */
        ROUTE_CSV("route_csv", "-route.csv", "text/csv", true),
        /** Export phone data, GPX. */
        GPX("gpx", ".gpx", "application/gpx+xml", true),
        /** Export phone data, Phone raw CSV (also the route notice's Phone journal CSV): the phone journal as it is. */
        PHONE_RAW("phone_raw", "-phone.csv", "text/csv", true),
        /** Export phone data, Motion raw CSV (also the route notice's Motion CSV): the motion file as it is. */
        MOTION_RAW("motion_raw", "-motion.csv", "text/csv", true),
        /** Export phone data, All (JSON): the scooter analysis and the phone track in one file. */
        COMBINED_JSON("combined_json", "-combined.json", "application/json", true);

        /** Stored in the remembered choice and the intent; never shown. */
        final String id;
        final String suffix, mime;
        /** True for the five that come from the phone's recording. */
        final boolean phone;
        Format(String id, String suffix, String mime, boolean phone) { this.id = id; this.suffix = suffix; this.mime = mime; this.phone = phone; }
        /** The sheet's group: 0 for Everything on its own, 1 for the scooter's files, 2 for the phone's. */
        int section() { return this == EVERYTHING_JSON ? 0 : phone ? 2 : 1; }
        /** The stored id read back, or null for anything unknown. */
        static Format of(String id) {
            for (Format f : values()) if (f.id.equals(id)) return f;
            return null;
        }
    }

    /** ride-&lt;first 8 characters of the id&gt; plus the format's ending: the name every manual export used. */
    static String name(Format format, String rideId) {
        return "ride-" + rideId.substring(0, Math.min(8, rideId.length())) + format.suffix;
    }

    /** Why a format is not offered for this ride. */
    enum Unavailable {
        /** Offered. */
        NONE,
        /** The ride has no phone recording: the phone formats are hidden, as Export phone data was. */
        NO_PHONE,
        /** The phone's files are still being written. */
        SAVING,
        /** The phone recording has no GPS fixes, so a route file would hold no route. */
        NO_FIXES,
        /** The phone journal could not be read, so the route, GPX and combined files cannot be made. */
        UNREADABLE,
        /** This raw file was never written for the ride. */
        NOT_RECORDED
    }

    /**
     * What the phone recording looks like when the sheet opens. track is the loaded recording, or null when it
     * failed to load (unreadable) or there are no phone files.
     */
    static final class Phone {
        final boolean saving, journal, motion, unreadable;
        final PhoneRideStore.Track track;
        Phone(boolean saving, boolean journal, boolean motion, PhoneRideStore.Track track, boolean unreadable) {
            this.saving = saving; this.journal = journal; this.motion = motion; this.track = track; this.unreadable = unreadable;
        }
        /** Ride details showed phone data for it: files, and a recording with a fix or a motion sample (or one that failed to read). */
        boolean recorded() {
            if (!journal && !motion) return false;
            if (saving) return true;
            if (unreadable) return true;
            return track != null && (track.locationCount > 0 || track.motionSampleCount > 0);
        }
    }

    /** Whether format can be exported for this ride, and why not. phone is null for a ride Volt has no phone files for. */
    static Unavailable why(Format format, Phone phone) {
        // Everything holds the phone files when there are any, so it waits while this ride's are still being written.
        if (format == Format.EVERYTHING_JSON) return phone != null && phone.recorded() && phone.saving ? Unavailable.SAVING : Unavailable.NONE;
        if (!format.phone) return Unavailable.NONE;
        if (phone == null || !phone.recorded()) return Unavailable.NO_PHONE;
        if (phone.saving) return Unavailable.SAVING;
        switch (format) {
            case PHONE_RAW: return phone.journal ? Unavailable.NONE : Unavailable.NOT_RECORDED;
            case MOTION_RAW: return phone.motion ? Unavailable.NONE : Unavailable.NOT_RECORDED;
            default:
                if (phone.unreadable || phone.track == null) return Unavailable.UNREADABLE;
                if ((format == Format.ROUTE_CSV || format == Format.GPX) && phone.track.locationCount == 0) return Unavailable.NO_FIXES;
                return Unavailable.NONE;
        }
    }

    /** The formats the sheet lists for this ride (offered or disabled with a reason), in order; hidden ones are left out. */
    static List<Format> listed(Phone phone) {
        List<Format> out = new ArrayList<>();
        for (Format f : Format.values()) if (why(f, phone) != Unavailable.NO_PHONE && (f != Format.EVERYTHING_JSON || EVERYTHING_ON_SHEET)) out.add(f);
        return out;
    }

    /** Before anything is remembered: DEFAULT_EVERYTHING once the sheet lists Everything, else DEFAULT_FILES. */
    static final Set<Format> DEFAULT_EVERYTHING = Collections.unmodifiableSet(EnumSet.of(Format.EVERYTHING_JSON));
    /** The two files ride details always offered, Export CSV and Export JSON. */
    static final Set<Format> DEFAULT_FILES = Collections.unmodifiableSet(EnumSet.of(Format.SENSOR_CSV, Format.ANALYSIS_JSON));
    static final Set<Format> DEFAULT = defaults(EVERYTHING_ON_SHEET);
    static Set<Format> defaults(boolean everything) { return everything ? DEFAULT_EVERYTHING : DEFAULT_FILES; }
    /**
     * Stored with a choice that leaves Everything unchecked, by a Volt whose sheet lists it. A choice with neither this
     * nor everything_json was stored before Everything existed, so Everything is added to it once; the next export
     * stores one or the other. Never a format id, so an older Volt reading it back drops it.
     */
    static final String EVERYTHING_OFF = "everything_json_off";

    /** The remembered choice; absent is DEFAULT, unknown ids are dropped. See parse(stored, everything). */
    static Set<Format> parse(String stored) { return parse(stored, EVERYTHING_ON_SHEET); }
    /**
     * The remembered choice, read by a sheet that does (everything) or does not list Everything. Absent is the
     * default. Without everything, an empty stored value stays empty and Everything is never in it. With everything,
     * a choice from before Everything existed (no everything_json and no EVERYTHING_OFF) gains it, keeping its other
     * boxes; an explicit choice is read as it is.
     */
    static Set<Format> parse(String stored, boolean everything) {
        if (stored == null) return EnumSet.copyOf(defaults(everything));
        Set<Format> out = EnumSet.noneOf(Format.class);
        boolean off = false;
        for (String id : stored.split(",")) { Format f = Format.of(id); if (f != null) out.add(f); off |= EVERYTHING_OFF.equals(id); }
        if (!everything) out.remove(Format.EVERYTHING_JSON);
        else if (!off) out.add(Format.EVERYTHING_JSON);
        return out;
    }
    static String format(Set<Format> chosen) { return format(chosen, EVERYTHING_ON_SHEET); }
    /** Format ids in sheet order; a sheet that lists Everything adds EVERYTHING_OFF when it was left unchecked. */
    static String format(Set<Format> chosen, boolean everything) {
        StringBuilder out = new StringBuilder();
        for (Format f : Format.values()) if (chosen.contains(f) && (everything || f != Format.EVERYTHING_JSON)) { if (out.length() > 0) out.append(','); out.append(f.id); }
        if (everything && !chosen.contains(Format.EVERYTHING_JSON)) { if (out.length() > 0) out.append(','); out.append(EVERYTHING_OFF); }
        return out.toString();
    }
    /** The boxes checked when the sheet opens: the remembered formats this ride offers. */
    static Set<Format> initial(Set<Format> remembered, Phone phone) {
        Set<Format> out = EnumSet.noneOf(Format.class);
        for (Format f : remembered) if (why(f, phone) == Unavailable.NONE) out.add(f);
        return out;
    }
    /**
     * What to remember after an export: the boxes as they were for each format this ride offered, and the earlier
     * choice for the rest, so a ride without phone data does not forget the phone formats.
     */
    static Set<Format> remember(Set<Format> previous, Phone phone, Set<Format> checked) {
        Set<Format> out = EnumSet.noneOf(Format.class);
        for (Format f : Format.values()) {
            boolean offered = why(f, phone) == Unavailable.NONE;
            if (offered ? checked.contains(f) : previous.contains(f)) out.add(f);
        }
        return out;
    }

    /** The phone file a raw format copies. */
    static File rawFile(Format format, PhoneRideStore store, String rideId) {
        if (format == Format.PHONE_RAW) return store.rawCsv(rideId);
        if (format == Format.MOTION_RAW) return store.motionCsv(rideId);
        throw new IllegalArgumentException(format.id);
    }

    /** The raw file was never written (or was deleted) for this ride. */
    static final class Missing extends IOException {
        Missing(String name) { super(name); }
    }

    /**
     * What one export holds: text, which ScreenHost.saveText writes as UTF-8, or a file, which ScreenHost.saveFile
     * copies as it is. Exactly one is set.
     */
    static final class Payload {
        final String text; final File file;
        /**
         * A staging folder that holds only this export's file (Everything), or null. Whoever takes the payload calls
         * discard once the file has been copied, failed or been given up; removeStale catches a save the process lost.
         */
        final File owned;
        private Payload(String text, File file) { this(text, file, null); }
        private Payload(String text, File file, File owned) { this.text = text; this.file = file; this.owned = owned; }
        /** Removes the folder this payload owns, with its file. Safe to call more than once, and on any payload. */
        void discard() { remove(owned); }
        /** The bytes that land in the saved file. */
        byte[] bytes() throws IOException {
            if (text != null) return text.getBytes(StandardCharsets.UTF_8);
            try (InputStream in = new FileInputStream(file)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream(); copy(in, out); return out.toByteArray();
            }
        }
    }

    /**
     * One format's content. summary is the ride's analysis (null for a phone recording without a scooter journal);
     * track is the loaded phone recording, needed by the route, GPX and combined files. The sensor CSV is RideStore's
     * copy of the journal into cache (as Export CSV made it, ride-&lt;id&gt;.csv), then saved from there.
     * Everything is written as a stream, with appVersion as its app version, into a new staging folder of its own
     * (see newStaging), under its saved name; the payload owns that folder, so the caller calls discard once the file
     * is copied. It can take a moment on a long ride, so callers make it off the main thread.
     */
    static Payload payload(Format format, String rideId, RideStore rides, RideStore.Summary summary,
                           PhoneRideStore phone, PhoneRideStore.Track track, File cacheDir) throws IOException {
        return payload(format, rideId, rides, summary, phone, track, cacheDir, null);
    }
    static Payload payload(Format format, String rideId, RideStore rides, RideStore.Summary summary,
                           PhoneRideStore phone, PhoneRideStore.Track track, File cacheDir, String appVersion) throws IOException {
        switch (format) {
            case EVERYTHING_JSON: {
                File own = newStaging(cacheDir, System.currentTimeMillis());
                File output = new File(own, name(format, rideId));
                try {
                    RideExportEverything.write(output, rideId, appVersion, rides.journal(rideId), summary,
                            phone == null ? null : phone.rawCsv(rideId), phone == null ? null : phone.motionCsv(rideId), track);
                } catch (IOException | RuntimeException | Error failure) { remove(own); throw failure; }
                return new Payload(null, output, own);
            }
            case SENSOR_CSV: {
                File output = new File(cacheDir, "ride-" + rideId + ".csv"); rides.exportCsv(rideId, output);
                return new Payload(null, output);
            }
            case ANALYSIS_JSON:
                if (summary == null) throw new Missing(name(format, rideId));
                return new Payload(summary.toJson(), null);
            case ROUTE_CSV: return new Payload(need(track).toCsv(), null);
            case GPX: return new Payload(need(track).toGpx(), null);
            case COMBINED_JSON: return new Payload(combinedJson(summary, need(track)), null);
            default: {
                File file = rawFile(format, phone, rideId);
                if (!file.isFile()) throw new Missing(name(format, rideId));
                return new Payload(null, file);
            }
        }
    }
    /** Scooter and phone JSON's text: the analysis (null without a scooter journal) and the phone track. */
    static String combinedJson(RideStore.Summary scooter, PhoneRideStore.Track phone) {
        return "{\"scooter\":" + (scooter == null ? "null" : scooter.toJson()) + ",\"phone\":" + phone.toJson() + "}";
    }
    private static PhoneRideStore.Track need(PhoneRideStore.Track track) throws IOException {
        if (track == null) throw new IOException("The phone recording is not loaded");
        return track;
    }

    // ---- Several files: staged in the cache, then copied into the folder the rider picks ----------------------

    /** A new, empty staging folder in cacheDir. Old ones a lost pick left behind are removed first. */
    static File newStaging(File cacheDir, long now) throws IOException {
        removeStale(cacheDir, now);
        File dir = new File(cacheDir, STAGING + now);
        for (int n = 1; dir.exists(); n++) dir = new File(cacheDir, STAGING + now + "-" + n);
        if (!dir.mkdirs()) throw new IOException("Could not create " + dir.getName());
        return dir;
    }
    /** Writes one export into the staging folder under its final name, and returns the file. */
    static File stage(File staging, Format format, String rideId, Payload payload) throws IOException {
        File out = new File(staging, name(format, rideId));
        try (OutputStream stream = new FileOutputStream(out)) {
            if (payload.text != null) stream.write(payload.text.getBytes(StandardCharsets.UTF_8));
            else try (InputStream in = new FileInputStream(payload.file)) { copy(in, stream); }
        }
        return out;
    }
    /** True for a folder newStaging made (so cleanup never touches anything else in the cache). */
    static boolean isStaging(File dir) {
        return dir != null && dir.isDirectory() && dir.getName().startsWith(STAGING);
    }
    /** Removes a staging folder and its files. */
    static void remove(File staging) {
        if (!isStaging(staging)) return;
        File[] files = staging.listFiles();
        if (files != null) for (File f : files) f.delete();
        staging.delete();
    }
    static void removeStale(File cacheDir, long now) {
        File[] dirs = cacheDir.listFiles();
        if (dirs == null) return;
        for (File dir : dirs) if (isStaging(dir) && now - dir.lastModified() > STALE_MS) remove(dir);
    }
    /** The format ids an intent carries, in order; unknown ones dropped. */
    static List<Format> formats(String[] ids) {
        List<Format> out = new ArrayList<>();
        if (ids != null) for (String id : ids) { Format f = Format.of(id); if (f != null && !out.contains(f)) out.add(f); }
        return out;
    }
    static String[] ids(Collection<Format> formats) {
        String[] out = new String[formats.size()]; int i = 0;
        for (Format f : formats) out[i++] = f.id;
        return out;
    }

    static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[16384]; int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
    }
}
