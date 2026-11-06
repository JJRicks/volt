package com.jjricks.volt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Opt-in automatic copies of each finished ride into one folder the rider chose: the Route CSV, the ride
 * JSON, or both. This class is the pure part (no Android types): preference keys, file names, the
 * persisted waiting list, what each ride's files hold and one export pass over a {@link Folder}.
 * RideAutoExportRunner runs a pass off the main thread once a ride's phone journal has finished saving;
 * RideAutoExportFolder is the Storage Access Framework folder. Volt never uploads anything: what happens to
 * the folder afterwards is up to the rider's own apps.
 *
 * Everything here is English and locale-independent: the files are byte for byte the manual Rides
 * exports, and the stored values are ids. Nothing here reads the catalog (RideAutoExportTest proves it
 * under a throwing text source).
 */
final class RideAutoExport {
    /** On/off for the Route CSV copy. Backed up with App preferences. Default off. */
    static final String ROUTE_CSV = "ride_auto_export_route_csv";
    /** On/off for the ride JSON copy. Backed up with App preferences. Default off. */
    static final String JSON = "ride_auto_export_json";
    /** The chosen tree URI, granted to this installation with takePersistableUriPermission. Never backed up. */
    static final String FOLDER = "ride_auto_export_folder";
    /** Ride ids still to export, oldest first, comma-separated. Never backed up. */
    static final String PENDING = "ride_auto_export_pending";
    /** Why the last pass left rides waiting: a {@link Problem} id, or absent when it did not. Never backed up. */
    static final String PROBLEM = "ride_auto_export_problem";
    /** Ten years of daily rides; past this the oldest waiting ride is dropped (never reached in practice). */
    static final int MAX_PENDING = 4000;
    private static final String ID = "[a-zA-Z0-9-]{1,80}";

    private RideAutoExport() { }

    /** What a pass left behind, stored by its id. */
    enum Problem {
        /** Nothing is waiting because of a failure. */
        NONE(""),
        /** No folder on this phone, or the folder or its grant is gone: the rider has to choose it again. */
        FOLDER("folder"),
        /** The folder answered but a write failed (storage full, provider error); retried later as it is. */
        SAVE("save");
        final String id;
        Problem(String id) { this.id = id; }
        /** The stored id read back; anything unknown is NONE. */
        static Problem of(String stored) {
            for (Problem p : values()) if (p.id.equals(stored)) return p;
            return NONE;
        }
    }

    /** What the settings row says, decided from stored values only. */
    enum RowState {
        /** This type is off. */
        OFF,
        /** On, but this phone has no folder (a restored setting): rides wait until one is chosen. */
        CHOOSE_FOLDER,
        /** On, but the folder or its grant is gone. */
        FOLDER_PROBLEM,
        /** On, and the last write failed; Volt retries. */
        SAVE_PROBLEM,
        /** On and working. */
        SAVING
    }
    static RowState rowState(boolean on, boolean folderStored, boolean granted, Problem problem) {
        if (!on) return RowState.OFF;
        if (!folderStored) return RowState.CHOOSE_FOLDER;
        if (!granted || problem == Problem.FOLDER) return RowState.FOLDER_PROBLEM;
        if (problem == Problem.SAVE) return RowState.SAVE_PROBLEM;
        return RowState.SAVING;
    }

    /** The manual exports' name prefix: ride-&lt;first 8 characters of the id&gt;. */
    private static String prefix(String rideId) { return "ride-" + rideId.substring(0, Math.min(8, rideId.length())); }
    /** Rides, Export phone data, Route CSV. */
    static String routeCsvName(String rideId) { return prefix(rideId) + "-route.csv"; }
    /** Rides, Export phone data, All (JSON). */
    static String combinedJsonName(String rideId) { return prefix(rideId) + "-combined.json"; }
    /** Ride details, Export JSON: a ride with no phone recording. */
    static String analysisJsonName(String rideId) { return prefix(rideId) + "-analysis.json"; }
    /** Hidden while it is written; renamed to the final name only once complete. Never ends in .csv or .json. */
    static String temporaryName(String name) { return "." + name + ".partial"; }

    /** The All (JSON) export's text. RidesScreen's manual export calls this too, so both stay byte for byte equal. */
    static String combinedJson(RideStore.Summary scooter, PhoneRideStore.Track phone) {
        return "{\"scooter\":" + (scooter == null ? "null" : scooter.toJson()) + ",\"phone\":" + phone.toJson() + "}";
    }

    static boolean anyOn(boolean routeCsv, boolean json) { return routeCsv || json; }

    static List<String> parse(String stored) {
        List<String> ids = new ArrayList<>();
        if (stored == null) return ids;
        for (String id : stored.split(",")) if (id.matches(ID) && !ids.contains(id)) ids.add(id);
        return ids;
    }
    static String format(List<String> ids) {
        StringBuilder out = new StringBuilder();
        for (String id : ids) { if (out.length() > 0) out.append(','); out.append(id); }
        return out.toString();
    }
    /** Adds a finished ride once, at the end; an id that is already waiting keeps its place. */
    static String add(String stored, String id) {
        List<String> ids = parse(stored);
        if (id == null || !id.matches(ID) || ids.contains(id)) return format(ids);
        ids.add(id);
        while (ids.size() > MAX_PENDING) ids.remove(0);
        return format(ids);
    }
    /** The stored list without the rides a pass finished; rides added meanwhile stay. */
    static String remove(String stored, Collection<String> finished) {
        List<String> ids = parse(stored); ids.removeAll(finished); return format(ids);
    }

    /**
     * A short label for a chosen tree's document id: the path after the volume for local storage
     * ("primary:Download/VoltRides" is "Download/VoltRides"), or null when the provider's id is opaque.
     * It is the folder's own name, shown as it is (a value, not catalog text).
     */
    static String folderLabel(String treeDocumentId) {
        if (treeDocumentId == null) return null;
        int colon = treeDocumentId.indexOf(':');
        if (colon < 0) return null;
        String path = treeDocumentId.substring(colon + 1);
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        return path.isEmpty() || path.length() > 120 ? null : path;
    }

    /** One file to write: its final name and its text. */
    static final class Output {
        final String name, text;
        Output(String name, String text) { this.name = name; this.text = text; }
    }

    /** The phone's saved ride data. */
    interface Source {
        /** The ride's Route CSV, exactly as Rides exports it, or null when it has no GPS fixes (or is deleted). */
        String routeCsv(String rideId) throws IOException;
        /**
         * The ride's JSON: All (JSON) when it has a phone recording, else the ride details' Export JSON;
         * null when the ride is deleted.
         */
        Output json(String rideId) throws IOException;
    }

    /** The files Rides exports by hand, read from the stores the recording wrote. Only reads. */
    static Source stores(RideStore rides, PhoneRideStore phone) {
        return new Source() {
            @Override public String routeCsv(String rideId) throws IOException {
                PhoneRideStore.Track track = phoneTrack(phone, rideId);
                return track == null || track.fullPoints().isEmpty() ? null : track.toCsv();
            }
            @Override public Output json(String rideId) throws IOException {
                RideStore.Summary summary = rides == null ? null : rides.get(rideId);
                PhoneRideStore.Track track = phoneTrack(phone, rideId);
                if (track != null) return new Output(combinedJsonName(rideId), combinedJson(summary, track));
                return summary == null ? null : new Output(analysisJsonName(rideId), summary.toJson());
            }
        };
    }
    /**
     * The ride's phone recording as ride details shows it, or null when it has none: no phone files, or
     * files with neither a location nor a motion sample (ride details then shows no Export phone data).
     */
    static PhoneRideStore.Track phoneTrack(PhoneRideStore phone, String rideId) throws IOException {
        if (phone == null || (!phone.rawCsv(rideId).isFile() && !phone.motionCsv(rideId).isFile())) return null;
        PhoneRideStore.Track track = phone.loadSnapshot(rideId);
        return track.locationCount == 0 && track.motionSampleCount == 0 ? null : track;
    }

    /** One folder, addressed by file name. */
    interface Folder {
        /** The named file's bytes, or null when there is none. */
        byte[] read(String name) throws IOException;
        /** Removes the named file if it exists. */
        void delete(String name) throws IOException;
        /** Writes a new, complete file, and returns the name the folder actually gave it. */
        String create(String name, byte[] data) throws IOException;
        /** Renames from to to; to does not exist. Fails rather than choosing another name. */
        void rename(String from, String to) throws IOException;
    }
    /** The folder itself cannot be reached: none chosen, deleted, unmounted or its grant revoked. */
    static final class Unavailable extends IOException {
        Unavailable(String message) { super(message); }
        Unavailable(String message, Throwable cause) { super(message, cause); }
    }

    enum Outcome { SAVED, UNCHANGED }

    /**
     * Writes one file: the complete file goes under a hidden temporary name and is then renamed, so a sync
     * app never sees a partial file under the final name. A file of the same name with the same bytes is
     * left alone; a different one is replaced. Never creates a "name (1)" copy.
     */
    static Outcome save(Folder folder, String name, String text) throws IOException {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        String temporary = temporaryName(name);
        byte[] existing = folder.read(name);
        // A temporary file left by an interrupted pass would make the folder pick another name.
        folder.delete(temporary);
        if (existing != null && Arrays.equals(existing, data)) return Outcome.UNCHANGED;
        String written = folder.create(temporary, data);
        try {
            if (existing != null) folder.delete(name);
            folder.rename(written, name);
        } catch (IOException | RuntimeException failure) {
            try { folder.delete(written); } catch (IOException | RuntimeException ignored) { }
            throw failure;
        }
        return Outcome.SAVED;
    }

    /** What one pass did. */
    static final class Result {
        /** Rides that need nothing more: every type that is on was written, already there, or has nothing to write. */
        final List<String> finished = new ArrayList<>();
        /** Names of the files written (not those left alone). */
        final List<String> saved = new ArrayList<>();
        Problem problem = Problem.NONE;
    }

    /**
     * Exports the waiting rides in order, each type that is on. A ride leaves the list only once all of its
     * files are written. A ride with nothing to write (no GPS fixes for the Route CSV only, or deleted) is
     * finished quietly and needs no folder. A folder that cannot be reached stops the pass and keeps every
     * remaining ride waiting; any other failure keeps that ride waiting and moves on. The ride recordings
     * themselves are only read.
     */
    static Result run(List<String> pending, boolean routeCsv, boolean json, Source source, Folder folder) {
        Result result = new Result();
        for (String id : pending) {
            try {
                List<Output> files = new ArrayList<>();
                if (routeCsv) { String csv = source.routeCsv(id); if (csv != null) files.add(new Output(routeCsvName(id), csv)); }
                if (json) { Output file = source.json(id); if (file != null) files.add(file); }
                if (!files.isEmpty() && folder == null) throw new Unavailable("No folder chosen");
                for (Output file : files) if (save(folder, file.name, file.text) == Outcome.SAVED) result.saved.add(file.name);
                result.finished.add(id);
            } catch (Unavailable gone) {
                result.problem = Problem.FOLDER; break;
            } catch (IOException | RuntimeException failure) {
                result.problem = Problem.SAVE;
            }
        }
        return result;
    }
}
