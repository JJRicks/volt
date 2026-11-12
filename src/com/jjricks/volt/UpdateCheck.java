package com.jjricks.volt;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Manual check for a newer Volt release on GitHub, only after the owner's consent. It reads one
 * small JSON reply and offers its release page, plus that release's APK address when the reply lists exactly the file
 * Volt expects (Result.download), for the browser to open. Volt itself never downloads or installs anything.
 */
final class UpdateCheck {
    static final String ENDPOINT = "https://api.github.com/repos/JJRicks/volt/releases/latest";
    /** Only a release page in Volt's own repository is ever offered. */
    static final String RELEASES = "https://github.com/JJRicks/volt/releases/";
    /** A release's files: releases/download/<tag>/<file>. Only volt-<version>.apk is ever offered from there. */
    static final String DOWNLOADS = RELEASES + "download/";
    static final String APK_PREFIX = "volt-", APK_SUFFIX = ".apk";
    /** The longest page or APK address Volt will hand the browser. */
    static final int MAX_URL = 200;
    /** app_settings: the owner agreed that Volt may ask GitHub. */
    static final String CONSENT = "update_check_consented";
    static final int MAX_BYTES = 256 * 1024, MAX_NOTES = 3000;
    private UpdateCheck() {}

    interface Fetcher { String get(String url, String userAgent) throws IOException; }
    /** Replaced only by tests, which never reach the network. */
    static Fetcher fetcher = UpdateCheck::https;
    /**
     * The automatic check's gate around this thread's request (UpdateCheckJob, through fetch): open runs before the
     * connection connects, send after connect() opened it (TCP and TLS; a GET writes no HTTP request there, on the
     * JVM or Android's OkHttp) and before the request is written. Either may throw to stop the request. Unset for
     * the manual check, which connects as before.
     */
    interface Gate { void open(HttpURLConnection connection) throws IOException; void send() throws IOException; }
    static final ThreadLocal<Gate> gate = new ThreadLocal<>();

    /** The automatic check's request for an admitted run: the manual check's fetch, gated by the run (UpdateRuns). */
    static String fetch(UpdateRuns.Run run, String address, String userAgent) throws IOException {
        gate.set(new Gate() {
            @Override public void open(HttpURLConnection connection) throws IOException {
                if (!run.onAbort(connection::disconnect)) throw new java.io.InterruptedIOException();
            }
            @Override public void send() throws IOException { if (!run.send()) throw new java.io.InterruptedIOException(); }
        });
        try { return fetcher.get(address, userAgent); } finally { gate.remove(); }
    }

    /**
     * The line shown above a new release's notes, which come from GitHub in English: empty while Volt is English
     * (the dialog then looks as it always did), else "Release notes are in English." in the app's language.
     */
    static String notesLanguageNote() { return Fmt.legacy() ? "" : T.compatUpdateNotesInEnglish(); }

    enum Outcome { AVAILABLE, CURRENT, UNREADABLE }
    static final class Result {
        /**
         * url: the release page (safePage). download: its APK (safeDownload), or "" when the reply lists none.
         * notes: the release notes as plain text (plain). markdown: the same notes as GitHub wrote them, cut the
         * same way (ReleaseNotes.clip), for the dialog to draw with their formatting (ReleaseNotesView).
         */
        final Outcome outcome; final String latest, url, download, notes, markdown;
        Result(Outcome outcome, String latest, String url, String download, String notes) {
            this(outcome, latest, url, download, notes, notes);
        }
        Result(Outcome outcome, String latest, String url, String download, String notes, String markdown) {
            this.outcome = outcome; this.latest = latest; this.url = url; this.download = download; this.notes = notes;
            this.markdown = markdown;
        }
        static Result unreadable() { return new Result(Outcome.UNREADABLE, "", "", "", ""); }
        /** Download opens the APK; without one it falls back to the release page. */
        boolean direct() { return !download.isEmpty(); }
        /** What Download hands the browser: the APK when there is one, else the page. */
        String target() { return direct() ? download : url; }
    }

    /** The same User-Agent the map tiles send. */
    static String userAgent(String installed) { return "Volt/" + installed + " (Android; https://github.com/JJRicks/volt)"; }

    /** Leading numeric parts (see VersionName): "v1.5.1" is 1.5.1 and the dev build "1.11.1-dev+abc1234" is 1.11.1. */
    static int[] version(String text) { return VersionName.numbers(text); }

    /** Missing parts count as 0, so 1.5 equals 1.5.0. */
    static int compare(int[] a, int[] b) { return VersionName.compare(a, b); }

    /** GitHub's latest-release reply against the installed version; anything unexpected is UNREADABLE. */
    static Result evaluate(String installed, String json) {
        try {
            Map<String, Object> release = BackupJson.object(BackupJson.parse(json, 8));
            Object tag = release.get("tag_name"), url = release.get("html_url"), body = release.get("body");
            if (Boolean.TRUE.equals(release.get("draft")) || Boolean.TRUE.equals(release.get("prerelease"))) return Result.unreadable();
            if (!(tag instanceof String) || !(url instanceof String) || !safePage((String) url)) return Result.unreadable();
            int[] latest = version((String) tag), mine = version(installed);
            if (latest == null || mine == null) return Result.unreadable();
            String notes = body instanceof String ? plain((String) body) : "";
            if (notes.length() > MAX_NOTES) notes = notes.substring(0, MAX_NOTES).trim() + "…";
            String markdown = body instanceof String ? ReleaseNotes.clip((String) body, MAX_NOTES) : "";
            String name = ((String) tag).startsWith("v") ? ((String) tag).substring(1) : (String) tag;
            return new Result(compare(latest, mine) > 0 ? Outcome.AVAILABLE : Outcome.CURRENT, name, (String) url,
                    apk((String) tag, release.get("assets")), notes, markdown);
        } catch (IOException | RuntimeException unreadable) {
            return Result.unreadable();
        }
    }

    /** GitHub's markdown notes as plain text: no emphasis markers, headings as lines, list dashes as bullets. */
    static String plain(String markdown) {
        StringBuilder out = new StringBuilder();
        for (String raw : markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            String line = raw.replace("**", "").replace("__", "").replace("`", "");
            String trimmed = line.trim();
            if (trimmed.startsWith("#")) line = trimmed.replaceFirst("^#+\\s*", "");
            else if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) line = line.substring(0, line.indexOf(trimmed)) + "• " + trimmed.substring(2);
            out.append(line).append('\n');
        }
        return out.toString().replaceAll("\n{3,}", "\n\n").trim();
    }

    /** Only a release page in Volt's repository: releases/latest or releases/tag/<tag>, never a download or anything else. */
    static boolean safePage(String url) {
        if (url == null || url.length() > MAX_URL) return false;
        if (url.equals(RELEASES + "latest")) return true;
        if (!url.startsWith(RELEASES + "tag/")) return false;
        String tag = url.substring((RELEASES + "tag/").length());
        if (tag.isEmpty() || tag.contains("..")) return false;
        for (int i = 0; i < tag.length(); i++) {
            char c = tag.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_')) return false;
        }
        return true;
    }

    /**
     * The only APK address Volt hands the browser for a release tag: releases/download/<tag>/volt-<version>.apk,
     * where version is the tag without its leading "v" (v1.13.1 → volt-1.13.1.apk). Null for a tag that is empty,
     * has anything but ASCII letters, digits, '.', '-' and '_', contains "..", or makes the address too long.
     */
    static String expectedApk(String tag) {
        if (tag == null || tag.isEmpty() || tag.contains("..")) return null;
        for (int i = 0; i < tag.length(); i++) {
            char c = tag.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '.' || c == '-' || c == '_')) return null;
        }
        String version = tag.startsWith("v") ? tag.substring(1) : tag;
        if (version.isEmpty()) return null;
        String url = DOWNLOADS + tag + "/" + APK_PREFIX + version + APK_SUFFIX;
        return url.length() > MAX_URL ? null : url;
    }

    /** An APK address of exactly expectedApk's form for the tag it names; everything else is refused. */
    static boolean safeDownload(String url) {
        if (url == null || url.length() > MAX_URL || !url.startsWith(DOWNLOADS)) return false;
        int slash = url.indexOf('/', DOWNLOADS.length());
        return slash > DOWNLOADS.length() && url.equals(expectedApk(url.substring(DOWNLOADS.length(), slash)));
    }

    /**
     * The release's APK from its reply: expectedApk(tag), only when an uploaded asset's browser_download_url is
     * exactly that address. "" otherwise (assets missing or malformed, the file renamed or still uploading), and
     * Download then opens the release page. SHA256SUMS and the source zip never match.
     */
    static String apk(String tag, Object assets) {
        String expected = expectedApk(tag);
        if (expected == null || !(assets instanceof List)) return "";
        for (Object item : (List<?>) assets) {
            if (!(item instanceof Map)) continue;
            Map<?, ?> asset = (Map<?, ?>) item;
            // GitHub lists a file still being uploaded with state "starter"; its address would not download yet.
            if (expected.equals(asset.get("browser_download_url")) && "uploaded".equals(asset.get("state"))) return expected;
        }
        return "";
    }

    /** One bounded HTTPS GET: no redirects, short timeouts, at most MAX_BYTES. */
    private static String https(String address, String userAgent) throws IOException {
        if (!address.startsWith("https://")) throw new IOException("Only HTTPS");
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        try {
            connection.setConnectTimeout(8000); connection.setReadTimeout(8000);
            connection.setInstanceFollowRedirects(false); connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", userAgent);
            Gate automatic = gate.get();
            if (automatic != null) { automatic.open(connection); connection.connect(); automatic.send(); }
            if (connection.getResponseCode() != 200) throw new IOException("HTTP " + connection.getResponseCode());
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] chunk = new byte[8192]; int n;
                while ((n = in.read(chunk)) > 0) {
                    if (out.size() + n > MAX_BYTES) throw new IOException("Reply too large");
                    out.write(chunk, 0, n);
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally { connection.disconnect(); }
    }
}
