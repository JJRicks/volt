package com.jjricks.volt;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Properties;
import java.util.function.LongSupplier;

/** On-demand raster tiles only. No prefetch, accounts, analytics, or route uploads. */
final class MapTiles {
    static final String DEFAULT_URL = MapSource.USGS_URL;
    private static final long WEEK = 7L * 24 * 60 * 60 * 1000;
    private static final long FAILURE_BACKOFF = 30_000;
    private static final long DENIED_BACKOFF = 15 * 60_000;
    private static final String CACHE_SCHEMA = "2";
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    interface ConnectionFactory {
        HttpURLConnection open(URL address) throws IOException;
    }
    private final File directory;
    private final String template;
    private final String userAgent;
    private final ConnectionFactory connections;
    private final LongSupplier clock;
    private volatile String status = "";
    private volatile boolean blocked;
    private volatile long pausedUntil;
    /** The pause reason as display text; status keeps its English, which network.meta saves. */
    private volatile Msg shown;

    MapTiles(Context context, String url) {
        this(context, url, address -> (HttpURLConnection) address.openConnection(),
                System::currentTimeMillis);
    }

    // A local connection/clock seam keeps provider and cache tests off the public tile servers.
    MapTiles(Context context, String url, ConnectionFactory connections, LongSupplier clock) {
        template = validUrl(url) ? url : DEFAULT_URL;
        userAgent = "Volt/" + AppVersion.name(context)
                + " (Android; https://github.com/JJRicks/volt)";
        this.connections = connections;
        this.clock = clock;
        directory = new File(context.getCacheDir(), "map-tiles/" + digest(template));
        directory.mkdirs();
        Properties pause = readMeta(new File(directory, "network.meta"));
        pausedUntil = number(pause.getProperty("until"), 0);
        if (pausedUntil > clock.getAsLong()) {
            blocked = Boolean.parseBoolean(pause.getProperty("blocked"));
            status = pause.getProperty("status", "Map tiles temporarily unavailable");
            shown = restored(pause.getProperty("reason"), pause.getProperty("http"), status);
        }
    }

    /** The pause reason in the current language, or "" while tiles load normally. */
    String status() {
        // One read of shown: a tile worker's succeeded() may clear it between two reads.
        Msg message = shown;
        return message == null ? status : message.render();
    }
    boolean blocked() { return blocked; }
    boolean networkPaused() { return blocked || pausedUntil > clock.getAsLong(); }

    static boolean validUrl(String template) {
        if (template == null
                || !template.contains("{z}")
                || !template.contains("{x}")
                || !template.contains("{y}")) return false;
        try {
            URI url = new URI(template.replace("{z}", "0").replace("{x}", "0").replace("{y}", "0"));
            return "https".equalsIgnoreCase(url.getScheme())
                    && url.getHost() != null
                    && url.getUserInfo() == null
                    && url.getFragment() == null;
        } catch (Exception e) {
            return false;
        }
    }

    Bitmap get(int z, int x, int y) {
        File image = new File(directory, z + "-" + x + "-" + y + ".png");
        File meta = new File(directory, image.getName() + ".meta");
        Properties info = readMeta(meta);
        // Older versions cached policy-error PNGs returned with HTTP 200. Keep those files,
        // but require a complete, unblocked response before trusting any legacy image again.
        Bitmap cached = CACHE_SCHEMA.equals(info.getProperty("schema")) ? decode(image) : null;
        long now = clock.getAsLong();
        if (cached != null && number(info.getProperty("expires"), 0) > now) return cached;
        Bitmap fallback = Boolean.parseBoolean(info.getProperty("revalidate")) ? null : cached;
        if (networkPaused()) return fallback;
        HttpURLConnection connection = null;
        try {
            String address =
                    template.replace("{z}", Integer.toString(z))
                            .replace("{x}", Integer.toString(x))
                            .replace("{y}", Integer.toString(y));
            connection = connections.open(new URL(address));
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", userAgent);
            if (cached != null) {
                if (info.getProperty("etag") != null)
                    connection.setRequestProperty("If-None-Match", info.getProperty("etag"));
                if (info.getProperty("modified") != null)
                    connection.setRequestProperty("If-Modified-Since", info.getProperty("modified"));
            }
            int code = connection.getResponseCode();
            String denial = connection.getHeaderField("X-Blocked");
            if ((denial != null && !denial.trim().isEmpty()) || code == 401 || code == 403) {
                pause(M.mapsTilesBlocked(), true, DENIED_BACKOFF, connection);
                return fallback;
            }
            if (code == 429) {
                pause(M.mapsTilesRateLimited(), false, 60_000, connection);
                return fallback;
            }
            if (code != 200 && code != 304) {
                pause(M.mapsTilesHttp(code), false, FAILURE_BACKOFF, connection);
                return fallback;
            }
            if (code == 304 && cached == null) {
                pause(M.mapsTilesInvalidResponse(), false, FAILURE_BACKOFF, connection);
                return null;
            }
            Properties refreshed = code == 304 ? info : new Properties();
            String control = connection.getHeaderField("Cache-Control");
            // A 304 can omit cache directives; retain the original directives in that case.
            if (control == null && code == 304) control = info.getProperty("control");
            boolean noStore = false, revalidate = false, noCache = false;
            long expires = connection.getHeaderFieldDate("Expires", now + WEEK);
            if (control != null) {
                refreshed.setProperty("control", control);
                for (String directive : control.split(",")) {
                    String item = directive.trim().toLowerCase(Locale.ROOT);
                    if (item.startsWith("max-age=")) {
                        long seconds = Math.max(0, number(item.substring(8).replace("\"", ""), WEEK / 1000)
                                - Math.max(0, connection.getHeaderFieldInt("Age", 0)));
                        expires = now + Math.min(seconds, (Long.MAX_VALUE - now) / 1000) * 1000;
                    }
                    if (item.startsWith("no-cache")) {
                        noCache = true;
                        revalidate = true;
                    }
                    if (item.equals("must-revalidate")) revalidate = true;
                    if (item.equals("no-store")) noStore = true;
                }
            }
            if (noCache) expires = now;
            refreshed.setProperty("schema", CACHE_SCHEMA);
            refreshed.setProperty("downloaded", Long.toString(now));
            refreshed.setProperty("expires", Long.toString(expires));
            refreshed.setProperty("revalidate", Boolean.toString(revalidate));
            String etag = connection.getHeaderField("ETag"),
                    modified = connection.getHeaderField("Last-Modified");
            if (etag != null) refreshed.setProperty("etag", etag);
            if (modified != null) refreshed.setProperty("modified", modified);
            if (code == 304) {
                if (!noStore) saveMeta(meta, refreshed);
                else {
                    image.delete();
                    meta.delete();
                }
                succeeded();
                return cached;
            }
            if (connection.getContentLengthLong() > MAX_BYTES)
                throw new IOException("Oversized tile");
            byte[] bytes;
            try (InputStream input = connection.getInputStream();
                    ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] block = new byte[8192];
                int read;
                while ((read = input.read(block)) != -1) {
                    if (output.size() + read > MAX_BYTES) throw new IOException("Oversized tile");
                    output.write(block, 0, read);
                }
                bytes = output.toByteArray();
            }
            BitmapFactory.Options size = new BitmapFactory.Options();
            size.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, size);
            if (size.outWidth < 1 || size.outWidth > 1024 || size.outHeight < 1 || size.outHeight > 1024)
                throw new IOException("Invalid tile image");
            Bitmap result = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (result == null) throw new IOException("Invalid tile image");
            if (!noStore) {
                File temporary = new File(directory, image.getName() + ".part");
                try {
                    try (OutputStream output = new FileOutputStream(temporary)) { output.write(bytes); }
                    if (temporary.renameTo(image)) {
                        saveMeta(meta, refreshed);
                        pruneOldCache(now);
                    }
                } catch (IOException ignored) {
                    // A full cache must not hide an otherwise valid map response.
                } finally {
                    temporary.delete();
                }
            } else {
                image.delete();
                meta.delete();
            }
            succeeded();
            return result;
        } catch (IOException e) {
            pause(M.mapsTilesOffline(), false, FAILURE_BACKOFF, null);
            return fallback;
        } catch (RuntimeException e) {
            pause(M.mapsTilesUnavailable(), false, FAILURE_BACKOFF, null);
            return fallback;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private synchronized void succeeded() {
        // Another viewport request may already have received a provider denial.
        if (networkPaused()) return;
        status = "";
        shown = null;
        pausedUntil = 0;
        new File(directory, "network.meta").delete();
    }

    private synchronized void pause(Msg message, boolean denied, long minimum, HttpURLConnection connection) {
        long now = clock.getAsLong(), until = now + minimum;
        if (connection != null) {
            String retry = connection.getHeaderField("Retry-After");
            if (retry != null) {
                long seconds = number(retry.trim(), -1);
                long requested = seconds >= 0
                        ? now + Math.min(seconds, (Long.MAX_VALUE - now) / 1000) * 1000
                        : connection.getHeaderFieldDate("Retry-After", 0);
                until = Math.max(until, requested);
            }
        }
        pausedUntil = Math.max(pausedUntil, until);
        if (!blocked || denied) { status = message.english(); shown = message; }
        blocked |= denied;
        Properties info = new Properties();
        info.setProperty("until", Long.toString(pausedUntil));
        info.setProperty("blocked", Boolean.toString(blocked));
        info.setProperty("status", status);
        String reason = reason(shown);
        if (reason != null) info.setProperty("reason", reason);
        if (shown != null && shown.id() == T.MAPS_TILES_HTTP) info.setProperty("http", String.valueOf(shown.args()[0]));
        saveMeta(new File(directory, "network.meta"), info);
    }

    /** The id network.meta saves for a pause's text (its English stays in "status" for older versions). */
    private static String reason(Msg message) {
        if (message == null) return null;
        if (message.id() == T.MAPS_TILES_HTTP) return "http";
        for (int i = 0; i < REASON_IDS.length; i++) if (REASON_IDS[i] == message.id()) return REASONS[i];
        return null;
    }

    /**
     * A saved pause's text: from its reason id (and HTTP status code), or for a file an older version
     * wrote (English status only), the reason whose English it is. Anything else is shown as saved.
     */
    static Msg restored(String reason, String http, String legacy) {
        if ("http".equals(reason)) {
            // Any saved code, -1 included (getResponseCode() for a response that is not valid HTTP).
            long code = number(http, Long.MIN_VALUE);
            if (code != Long.MIN_VALUE) return M.mapsTilesHttp(code);
        }
        for (int i = 0; i < REASONS.length; i++) if (REASONS[i].equals(reason)) return Msg.of(REASON_IDS[i]);
        for (int id : REASON_IDS) if (Msg.of(id).english().equals(legacy)) return Msg.of(id);
        // An older version's HTTP pause: the only number in its English is the status code.
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < legacy.length(); i++) if (Character.isDigit(legacy.charAt(i))) digits.append(legacy.charAt(i));
        long code = number(digits.toString(), -1);
        if (code >= 0 && M.mapsTilesHttp(code).english().equals(legacy)) return M.mapsTilesHttp(code);
        // The scan drops a minus sign: HTTP -1 is the same digits, negated.
        if (code >= 0 && M.mapsTilesHttp(-code).english().equals(legacy)) return M.mapsTilesHttp(-code);
        return Msg.verbatim(legacy);
    }

    /** Pause reasons network.meta saves, with their texts (R5: ids only). */
    private static final String[] REASONS = {"blocked", "rate_limited", "invalid_response", "offline", "unavailable", "paused"};
    private static final int[] REASON_IDS = {T.MAPS_TILES_BLOCKED, T.MAPS_TILES_RATE_LIMITED, T.MAPS_TILES_INVALID_RESPONSE,
            T.MAPS_TILES_OFFLINE, T.MAPS_TILES_UNAVAILABLE, T.MAPS_TILES_PAUSED};

    private Bitmap decode(File file) {
        if (!file.isFile() || file.length() > MAX_BYTES) return null;
        BitmapFactory.Options size = new BitmapFactory.Options();
        size.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), size);
        return size.outWidth > 0 && size.outWidth <= 1024 && size.outHeight > 0 && size.outHeight <= 1024
                ? BitmapFactory.decodeFile(file.getPath()) : null;
    }

    private void pruneOldCache(long now) {
        // Retain at least a week; no cache churn within the provider's minimum TTL.
        File[] files = directory.listFiles();
        if (files == null || files.length < 1000) return;
        for (File file : files)
            if (!file.getName().equals("network.meta")
                    && now - file.lastModified() > 30L * 24 * 60 * 60 * 1000) file.delete();
    }

    private static Properties readMeta(File file) {
        Properties info = new Properties();
        try (InputStream in = new FileInputStream(file)) { info.load(in); }
        catch (IOException ignored) { }
        return info;
    }

    private static void saveMeta(File file, Properties value) {
        File temporary = new File(file.getParentFile(), file.getName() + ".part");
        try {
            try (OutputStream out = new FileOutputStream(temporary)) { value.store(out, "Tile cache"); }
            temporary.renameTo(file);
        } catch (IOException ignored) { }
        finally { temporary.delete(); }
    }

    private static long number(String value, long fallback) {
        try { return Long.parseLong(value); }
        catch (Exception e) { return fallback; }
    }

    private static String digest(String text) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < 12; i++) out.append(String.format(Locale.ROOT, "%02x", bytes[i] & 255));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
