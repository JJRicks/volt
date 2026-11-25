package com.jjricks.volt;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Rides the short-ride rule discarded (RideStore.kept), held for {@link #KEEP_MS} so Undo can bring one back (owner, Nov 24
 * 2026). Each is a folder named by its ride id under the holding folder, which lives in the app's cache storage: outside
 * the ride list, every backup, the app data export and Android's own backup (Android may clear it when the phone runs out
 * of space, which only ends a hold early). Its files are moved in and back exactly as they were,
 * never copied, so a ride is in one place at a time. The folder's modified time is when the ride was held; files added to
 * it later (the phone track, once its recorder finishes) keep that time. Everything is on disk, so a process death in
 * between loses nothing.
 */
final class RideHold {
    /** How long a discarded ride stays restorable. */
    static final long KEEP_MS = 24L * 60 * 60 * 1000;
    private final File root;

    RideHold(File root) { this.root = root; }

    /** Ride ids are UUIDs (RideStore): only one in its own canonical form names an entry. */
    static boolean validId(String id) {
        try { return id != null && java.util.UUID.fromString(id).toString().equalsIgnoreCase(id); }
        catch (IllegalArgumentException invalid) { return false; }
    }
    private File entry(String id) {
        if (!validId(id)) throw new IllegalArgumentException();
        return new File(root, id);
    }

    /** Whether the ride is held now. */
    synchronized boolean holds(String id) { return validId(id) && entry(id).isDirectory(); }

    /**
     * Moves the ride's files (those that exist) into a new holding entry stamped with wallMs. False when nothing could be held:
     * then whatever moved is put back, and the caller keeps the ride.
     */
    synchronized boolean hold(String id, long wallMs, File... files) {
        File entry = entry(id);
        if (entry.exists() || !(root.isDirectory() || root.mkdirs()) || !entry.mkdir()) return false;
        if (!moveIn(entry, files)) { putBack(entry, files); entry.delete(); return false; }
        entry.setLastModified(Math.max(0, wallMs));
        return true;
    }

    /** Adds files (those that exist) to a held ride, keeping its stamp. False when it isn't held or a move failed. */
    synchronized boolean add(String id, File... files) {
        File entry = entry(id);
        if (!entry.isDirectory()) return false;
        long stamp = entry.lastModified();
        boolean moved = moveIn(entry, files);
        entry.setLastModified(stamp);
        return moved;
    }

    /**
     * Moves a held ride back: its journal to journal, and each other held file to the target of the same name. Nothing moves
     * unless the journal is held and no target already exists; then the entry is removed. False when there was nothing to
     * restore (never held, or purged) or a target is taken, which leaves everything where it is.
     */
    synchronized boolean restore(String id, File journal, File... others) {
        File entry = entry(id);
        if (!new File(entry, journal.getName()).isFile()) return false;
        List<File> targets = new ArrayList<>();
        for (File other : others) if (new File(entry, other.getName()).isFile()) targets.add(other);
        targets.add(journal);
        for (File target : targets) if (target.exists()) return false;
        for (File target : targets) {
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return false;
        }
        // The journal last: once it is back, the ride is in the list with its phone files beside it.
        for (File target : targets) {
            try { Files.move(new File(entry, target.getName()).toPath(), target.toPath()); }
            catch (IOException | RuntimeException failed) { return false; }
        }
        delete(entry);
        return true;
    }

    /**
     * Removes every entry held more than KEEP_MS before nowWallMs, or stamped more than KEEP_MS after it (a clock set back).
     * Returns the ids removed.
     */
    synchronized List<String> purge(long nowWallMs) {
        List<String> removed = new ArrayList<>();
        File[] entries = root.listFiles();
        if (entries == null) return removed;
        for (File entry : entries) {
            if (!entry.isDirectory()) { entry.delete(); continue; }
            long age = nowWallMs - entry.lastModified();
            if (age > KEEP_MS || age < -KEEP_MS) { delete(entry); removed.add(entry.getName()); }
        }
        return removed;
    }

    private static boolean moveIn(File entry, File... files) {
        for (File file : files) {
            if (file == null || !file.isFile()) continue;
            try { Files.move(file.toPath(), new File(entry, file.getName()).toPath()); }
            catch (IOException | RuntimeException failed) { return false; }
        }
        return true;
    }
    private static void putBack(File entry, File... files) {
        for (File file : files) {
            if (file == null) continue;
            File held = new File(entry, file.getName());
            if (held.isFile() && !file.exists()) try { Files.move(held.toPath(), file.toPath()); } catch (IOException | RuntimeException ignored) { }
        }
    }
    private static void delete(File entry) {
        File[] files = entry.listFiles();
        if (files != null) for (File file : files) file.delete();
        entry.delete();
    }
}
