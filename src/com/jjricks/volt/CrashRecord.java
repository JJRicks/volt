package com.jjricks.volt;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The last uncaught crash, for the report diagnostics file only: the exception's class, its causes' classes, Volt's
 * own stack frames (class, method and line in com.jjricks.volt) and how many other frames there were, the app version
 * and the wall time. Never the exception's message (a message can carry any text, a serial or a name), never another
 * package's frame. One small file in no-backup storage, written once when the process is about to die and read only
 * when a report is built; a later crash replaces it. Pure Java: no Android types.
 */
final class CrashRecord {
    static final String FORMAT = "volt-crash-v1";
    /** Own frames kept per throwable and in all, and causes kept below the top one: a bounded part of the diagnostics file. */
    static final int MAX_FRAMES = 24, MAX_TOTAL_FRAMES = 48, MAX_CAUSES = 4;
    /** A file larger than this is not one of ours. */
    static final int MAX_FILE_BYTES = 16 * 1024;
    private static final String OWN = "com.jjricks.volt.";
    /** A Java class name; anything else (a message, a path) never reaches the record. */
    private static final Pattern CLASS = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*){0,12}"),
            FRAME = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]{0,63}\\.[A-Za-z_$<>][A-Za-z0-9_$<>]{0,47}:-?[0-9]{1,6}");

    static final class Thrown {
        final String exception; final List<String> frames; final int otherFrames;
        Thrown(String exception, List<String> frames, int otherFrames) {
            this.exception = exception; this.frames = Collections.unmodifiableList(frames); this.otherFrames = otherFrames;
        }
    }
    final long wallMs;
    final String appVersion;
    /** The thrown exception first, then its causes (at most MAX_CAUSES). */
    final List<Thrown> chain;

    private CrashRecord(long wallMs, String appVersion, List<Thrown> chain) {
        this.wallMs = wallMs; this.appVersion = appVersion; this.chain = Collections.unmodifiableList(chain);
    }

    /** The file's text for this throwable: one fact per line, every value checked by its pattern. */
    static String encode(Throwable thrown, String appVersion, long wallMs) {
        StringBuilder out = new StringBuilder(FORMAT).append('\n');
        out.append("wall=").append(Math.max(0, wallMs)).append('\n');
        out.append("version=").append(version(appVersion)).append('\n');
        Throwable t = thrown;
        int total = 0;
        for (int depth = 0; t != null && depth <= MAX_CAUSES; depth++, t = t.getCause() == t ? null : t.getCause()) {
            out.append("exception=").append(className(t.getClass().getName())).append('\n');
            int kept = 0, other = 0;
            for (StackTraceElement e : t.getStackTrace()) {
                String frame = e == null ? null : frame(e.getClassName(), e.getMethodName(), e.getLineNumber());
                if (frame != null && kept < MAX_FRAMES && total < MAX_TOTAL_FRAMES) { out.append("at=").append(frame).append('\n'); kept++; total++; } else other++;
            }
            out.append("other=").append(other).append('\n');
        }
        return out.toString();
    }

    /** A saved file's record, or null when it is not one (unreadable, another format, nothing thrown). Lines that fail their pattern are dropped. */
    static CrashRecord decode(String text) {
        if (text == null || !text.startsWith(FORMAT + "\n")) return null;
        long wall = 0; String version = "unknown";
        List<Thrown> chain = new ArrayList<>();
        String exception = null; List<String> frames = new ArrayList<>(); int other = 0, total = 0;
        for (String line : text.split("\n")) {
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String key = line.substring(0, eq), value = line.substring(eq + 1);
            switch (key) {
                case "wall": wall = number(value); break;
                case "version": version = version(value); break;
                case "exception":
                    if (exception != null) chain.add(new Thrown(exception, frames, other));
                    if (chain.size() > MAX_CAUSES) { exception = null; break; }
                    exception = className(value); frames = new ArrayList<>(); other = 0; break;
                case "at": if (exception != null && frames.size() < MAX_FRAMES && total < MAX_TOTAL_FRAMES && FRAME.matcher(value).matches()) { frames.add(value); total++; } break;
                case "other": if (exception != null) other = (int) Math.min(100_000, number(value)); break;
                default: break;
            }
        }
        if (exception != null && chain.size() <= MAX_CAUSES) chain.add(new Thrown(exception, frames, other));
        return chain.isEmpty() ? null : new CrashRecord(wall, version, chain);
    }

    /** The saved record, or null: none, unreadable or too large. Never throws. */
    static CrashRecord read(File file) {
        if (file == null) return null;
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[MAX_FILE_BYTES + 1]; int n = 0, r;
            while (n < buffer.length && (r = in.read(buffer, n, buffer.length - n)) > 0) n += r;
            return n > MAX_FILE_BYTES ? null : decode(new String(buffer, 0, n, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException unreadable) { return null; }
    }

    /** Writes the record through a temporary file and a rename, so a half-written record is never read. Never throws. */
    static boolean write(File file, Throwable thrown, String appVersion, long wallMs) {
        try {
            File temp = new File(file.getPath() + ".tmp");
            try (OutputStream out = new FileOutputStream(temp)) { out.write(encode(thrown, appVersion, wallMs).getBytes(StandardCharsets.UTF_8)); }
            return temp.renameTo(file);
        } catch (Throwable failed) { return false; }
    }

    private static volatile boolean installed;
    /**
     * Records the next uncaught exception in file, then hands it to the handler that was there before (Android's,
     * which ends the process as usual). Once per process; it does nothing until a crash.
     */
    static synchronized void install(File file, String appVersion) {
        if (installed || file == null) return;
        installed = true;
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, thrown) -> {
            try { write(file, thrown, appVersion, System.currentTimeMillis()); } catch (Throwable ignored) { /* the crash itself goes on */ }
            if (previous != null) previous.uncaughtException(thread, thrown);
        });
    }

    /** Volt's own frame as Class.method:line (the class without the package), else null. */
    static String frame(String className, String method, int line) {
        if (className == null || method == null || !className.startsWith(OWN)) return null;
        String f = className.substring(OWN.length()) + "." + method + ":" + Math.max(-1, Math.min(999_999, line));
        return FRAME.matcher(f).matches() ? f : null;
    }
    private static String className(String name) { return name != null && name.length() <= 200 && CLASS.matcher(name).matches() ? name : "unknown"; }
    private static String version(String v) { return v != null && v.matches(CommandLog.APP_VERSION) ? v : "unknown"; }
    private static long number(String v) { try { return Math.max(0, Long.parseLong(v.trim())); } catch (RuntimeException bad) { return 0; } }
}
