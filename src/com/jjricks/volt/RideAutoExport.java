package com.jjricks.volt;

import java.io.*;
import java.util.*;

/**
 * Opt-in automatic copies of each finished ride into one folder the rider chose: the ride's Everything (JSON) file,
 * ride-&lt;id&gt;-everything.json, made by the same code as the Export ride data sheet (RideExportFiles.payload, which
 * calls RideExportEverything). This class is the pure part (no Android types): preference keys, the file name, the
 * persisted waiting list and one export pass over a {@link Folder}.
 * RideAutoExportRunner runs a pass off the main thread once a ride's phone journal has finished saving;
 * RideAutoExportFolder is the Storage Access Framework folder. Volt never uploads anything: what happens to
 * the folder afterwards is up to the rider's own apps.
 *
 * Everything here is English and locale-independent: the file is byte for byte the sheet's Everything export,
 * and the stored values are ids. Nothing here reads the catalog (RideAutoExportTest proves it
 * under a throwing text source).
 */
final class RideAutoExport {
    /**
     * The one switch, saving each ride's Everything file. Backed up with App preferences. Default off. It keeps the key
     * of the ride JSON switch it replaced, so a Volt from before the change restores it as that switch: on there means
     * its own JSON copy (Scooter and phone JSON, or Analysis JSON), the nearest it has.
     */
    static final String ON = "ride_auto_export_json";
    /**
     * The retired Route CSV switch. Still read, so a rider who had it on, on this phone or in an older backup, has auto-save
     * on (either switch on means on). Written false whenever the switch is set, so a newer backup restored here or in an
     * older Volt never brings it back. Backed up with App preferences.
     */
    static final String LEGACY_ROUTE_CSV = "ride_auto_export_route_csv";
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
        /** Auto-save is off. */
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

    /**
     * Whether a pass's failure posts the notice: never while Volt is visible (the row shows it), and once per run of
     * one stored failure kind. before is the stored failure when the pass started; notified is what notifiedAfter kept
     * from earlier passes in this service run. A failure first stored while Volt was open was never posted, so the
     * same failure on a later hidden ride still is. With nothing stored before the pass, notified is stale (both
     * turning auto-save off clears the stored failure without a pass), and a hidden failure always posts.
     */
    static boolean notifies(Problem before, Problem after, Problem notified, boolean visible) {
        if (after == Problem.NONE || visible) return false;
        return before == Problem.NONE || after != notified;
    }

    /**
     * The posted kind to keep after a pass: the kind it just posted, NONE after a success, and NONE when the stored
     * failure changed kind without a notice (Volt was visible), since the kind posted before then no longer applies.
     * Otherwise the stored failure carried on unchanged, and so does what was posted for it.
     */
    static Problem notifiedAfter(Problem before, Problem after, Problem notified, boolean visible) {
        if (after == Problem.NONE) return Problem.NONE;
        if (notifies(before, after, notified, visible)) return after;
        return after != before ? Problem.NONE : notified;
    }

    /** The sheet's Everything (JSON) name: ride-&lt;first 8 characters of the id&gt;-everything.json. */
    static String everythingName(String rideId) { return RideExportFiles.name(RideExportFiles.Format.EVERYTHING_JSON, rideId); }
    /** Hidden while it is written; renamed to the final name only once complete. Never ends in .csv or .json. */
    static String temporaryName(String name) { return "." + name + ".partial"; }

    /** Whether auto-save is on: the switch, or the retired Route CSV switch from before it (see LEGACY_ROUTE_CSV). */
    static boolean on(boolean stored, boolean legacyRouteCsv) { return stored || legacyRouteCsv; }
    /**
     * Moves the retired Route CSV switch onto the switch's key: on means ON true and LEGACY_ROUTE_CSV false, so a backup
     * restored into an older Volt turns on its ride JSON switch. Leaves every other key (folder, waiting rides) as it is.
     * Returns true when it changed the values.
     */
    static boolean normalize(Map<String, Object> settings) {
        if (settings == null || !Boolean.TRUE.equals(settings.get(LEGACY_ROUTE_CSV))) return false;
        settings.put(ON, Boolean.TRUE);
        settings.put(LEGACY_ROUTE_CSV, Boolean.FALSE);
        return true;
    }

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

    /** One ride's file, written in the cache: its final name, and the staging folder that holds it (removed by discard). */
    static final class Output {
        final String name; final File file;
        private final File owned;
        Output(String name, File file, File owned) { this.name = name; this.file = file; this.owned = owned; }
        /** Removes the staging folder with its file. Safe to call more than once. */
        void discard() { RideExportFiles.remove(owned); }
    }

    /** The phone's saved ride data. */
    interface Source {
        /** The ride's Everything file, written now, or null when the ride is deleted. The caller discards it. */
        Output everything(String rideId) throws IOException;
    }

    /**
     * The sheet's Everything export, read from the stores the recording wrote: RideExportFiles.payload with the ride's
     * analysis and its phone recording as the sheet loads them, into a staging folder in cacheDir. Only reads the stores.
     * A ride the sheet would call unavailable (no analysis: deleted) has nothing to write.
     */
    static Source stores(RideStore rides, PhoneRideStore phone, File cacheDir, String appVersion) {
        return rideId -> {
            RideStore.Summary summary = rides == null ? null : rides.get(rideId);
            if (summary == null) return null;
            RideExportFiles.Payload payload = RideExportFiles.payload(RideExportFiles.Format.EVERYTHING_JSON, rideId, rides, summary,
                    phone, phoneTrack(phone, rideId), cacheDir, appVersion);
            return new Output(everythingName(rideId), payload.file, payload.owned);
        };
    }
    /**
     * The ride's phone recording as the sheet loads it for Everything: null when there are no phone files, or when they
     * cannot be read (Everything still copies the files as they are).
     */
    static PhoneRideStore.Track phoneTrack(PhoneRideStore phone, String rideId) {
        if (phone == null || (!phone.rawCsv(rideId).isFile() && !phone.motionCsv(rideId).isFile())) return null;
        try { return phone.loadSnapshot(rideId); } catch (IOException | RuntimeException unreadable) { return null; }
    }

    /** One folder, addressed by file name. */
    interface Folder {
        /** The named file's content, or null when there is none. The caller closes it. */
        InputStream open(String name) throws IOException;
        /** Removes the named file if it exists. */
        void delete(String name) throws IOException;
        /** Writes a new, complete file with data's bytes, and returns the name the folder actually gave it. */
        String create(String name, File data) throws IOException;
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
    static Outcome save(Folder folder, String name, File data) throws IOException {
        String temporary = temporaryName(name);
        Boolean same;
        try (InputStream existing = folder.open(name)) { same = existing == null ? null : same(existing, data); }
        // A temporary file left by an interrupted pass would make the folder pick another name.
        folder.delete(temporary);
        if (Boolean.TRUE.equals(same)) return Outcome.UNCHANGED;
        String written = folder.create(temporary, data);
        try {
            if (same != null) folder.delete(name);
            folder.rename(written, name);
        } catch (IOException | RuntimeException failure) {
            try { folder.delete(written); } catch (IOException | RuntimeException ignored) { }
            throw failure;
        }
        return Outcome.SAVED;
    }
    /** Whether the stream holds exactly the file's bytes, compared as both are read (an Everything file can be large). */
    static boolean same(InputStream existing, File data) throws IOException {
        try (InputStream mine = new BufferedInputStream(new FileInputStream(data), 65536)) {
            InputStream theirs = new BufferedInputStream(existing, 65536);
            for (;;) {
                int a = mine.read(), b = theirs.read();
                if (a != b) return false;
                if (a < 0) return true;
            }
        }
    }

    /** What one pass did. */
    static final class Result {
        /** Rides that need nothing more: the file was written, was already there, or the ride has nothing to write. */
        final List<String> finished = new ArrayList<>();
        /** Names of the files written (not those left alone). */
        final List<String> saved = new ArrayList<>();
        Problem problem = Problem.NONE;
    }

    /**
     * Exports the waiting rides in order. A ride leaves the list only once its file is written (or was already
     * there). A deleted ride has nothing to write: it is finished quietly and needs no folder. No folder, or one
     * that cannot be reached, stops the pass at the first ride with a file and keeps every
     * remaining ride waiting; any other failure keeps that ride waiting and moves on. The ride recordings
     * themselves are only read, and each file made in the cache is removed once written.
     */
    static Result run(List<String> pending, Source source, Folder folder) {
        Result result = new Result();
        for (String id : pending) {
            Output file = null;
            try {
                file = source.everything(id);
                if (file != null) {
                    if (folder == null) throw new Unavailable("No folder chosen");
                    if (save(folder, file.name, file.file) == Outcome.SAVED) result.saved.add(file.name);
                }
                result.finished.add(id);
            } catch (Unavailable gone) {
                result.problem = Problem.FOLDER; break;
            } catch (IOException | RuntimeException failure) {
                result.problem = Problem.SAVE;
            } finally {
                if (file != null) file.discard();
            }
        }
        return result;
    }
}
