package com.jjricks.volt;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rides never replays a journal on the UI thread (quick fixes PR 2). RideStore.listCached() and trendCached() answer
 * from memory or return null, never replaying; a miss is left to list() and trend() on RidesScreen's worker.
 * Isolated temporary journals only; no scooter.
 */
public final class RideListCachedTest {
    private static int checks;
    private static final long WALL = 1791806400000L;

    public static void main(String[] args) throws Exception {
        coldCacheMissesWithoutReplaying();
        anyChangedJournalMisses();
        activeRideIsAnsweredFromMemory();
        unreadableJournalAlwaysMisses();
        previewHitsAndMisses();
        ridesScreenKeepsReadsOffTheUiThread();
        System.out.println("RideListCachedTest: " + checks + " checks passed");
    }

    private static void coldCacheMissesWithoutReplaying() throws Exception {
        File dir = directory(); finishedRides(new RideStore(dir), 4, 30);
        RideStore store = new RideStore(dir, true);
        long before = replays();
        check(store.listCached() == null && replays() == before, "A cold cache misses, and listCached() replays nothing");
        List<RideStore.Summary> listed = store.list();
        before = replays();
        List<RideStore.Summary> cached = store.listCached();
        check(cached != null && replays() == before, "After list(), listCached() hits without a replay");
        check(json(cached).equals(json(listed)), "listCached() equals list(), newest first");
        check(json(new RideStore(directory()).listCached()).isEmpty(), "No rides is an empty list, not a miss");
    }

    private static void anyChangedJournalMisses() throws Exception {
        File dir = directory(); List<String> ids = finishedRides(new RideStore(dir), 3, 20);
        RideStore store = new RideStore(dir);
        check(store.listCached() != null, "The service-start read fills the cache");
        File touched = new File(dir, ids.get(1) + ".csv");
        check(touched.setLastModified(touched.lastModified() + 5000), "Fixture changes the modified time");
        long before = replays();
        check(store.listCached() == null && replays() == before, "A changed modified time misses without a replay");
        store.list();
        check(store.listCached() != null, "list() fills it again");
        Files.write(touched.toPath(), "\n".getBytes(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.APPEND);
        check(store.listCached() == null, "A changed length misses");
        store.list();
        store.rename(ids.get(0), "Renamed");
        check(store.listCached() == null, "A rename misses until list() reads the ride again");
        store.list();
        check(store.delete(ids.get(2)), "Fixture deletes a ride");
        List<RideStore.Summary> left = store.listCached();
        check(left != null && left.size() == 2 && left.stream().noneMatch(s -> s.id.equals(ids.get(2))), "A deleted ride just leaves the list");
        check(left.stream().anyMatch(s -> s.label.equals("Renamed")), "The renamed ride shows its new name");
    }

    private static void activeRideIsAnsweredFromMemory() throws Exception {
        File dir = directory(); RideStore store = new RideStore(dir); finishedRides(store, 2, 10);
        store.list();
        String active = store.start("UT5", "Now", WALL + 9 * 86400000L, 0);
        store.sample(WALL + 9 * 86400000L, 0, snapshot(0, 100, 20, 1));
        long before = replays();
        List<RideStore.Summary> cached = store.listCached();
        check(cached != null && cached.size() == 3 && cached.get(0).id.equals(active) && cached.get(0).active,
                "The ride being recorded comes from memory, first");
        check(store.trendCached(active) != null && replays() == before, "Its preview also comes from memory");
        store.stop(WALL + 9 * 86400000L + 1000, 1000);
        store.list();
        check(store.listCached() != null && store.listCached().size() == 3, "Once read, the stopped ride is cached too");
    }

    private static void unreadableJournalAlwaysMisses() throws Exception {
        File dir = directory(); RideStore store = new RideStore(dir); finishedRides(store, 2, 10);
        store.list();
        check(new File(dir, "00000000-0000-4000-8000-000000000000.csv").createNewFile(), "Fixture adds an empty journal");
        store.list();
        check(store.listCached() == null, "A journal that can't be read is left to list() on the worker");
    }

    private static void previewHitsAndMisses() throws Exception {
        File dir = directory(); List<String> ids = finishedRides(new RideStore(dir), RideStore.PREVIEW_CAP + 10, 2);
        RideStore store = new RideStore(dir, true);
        String newest = ids.get(ids.size() - 1);
        check(store.trendCached(newest) == null, "A cold preview misses");
        List<RideStore.Point> trend = store.trend(newest, RideStore.MAX_TREND_POINTS);
        long before = replays();
        List<RideStore.Point> cached = store.trendCached(newest);
        check(cached != null && cached.size() == trend.size() && replays() == before, "After trend(), trendCached() hits without a replay");
        for (String id : ids) store.trend(id, RideStore.MAX_TREND_POINTS);
        int trimmed = 0;
        before = replays();
        for (String id : ids) if (store.trendCached(id) == null) trimmed++;
        check(trimmed == 10 && replays() == before, "Previews trimmed past the cap miss, without a replay (" + trimmed + ")");
        File touched = new File(dir, newest + ".csv");
        check(touched.setLastModified(touched.lastModified() + 5000) && store.trendCached(newest) == null, "A changed journal's preview misses");
    }

    /** The UI-thread paths use the cache-only reads; list() and trend() for history run on rideListLoader. */
    private static void ridesScreenKeepsReadsOffTheUiThread() throws Exception {
        String source = new String(Files.readAllBytes(Paths.get("src/com/jjricks/volt/RidesScreen.java")), StandardCharsets.UTF_8);
        check(!source.contains("service.rides.list()"), "RidesScreen never calls list() on the service's store directly");
        check(source.contains("service.rides.listCached()"), "History and comparison try the cache first");
        check(source.contains("service.rides.trendCached(ride.id)"), "The wide pane tries the cached preview first");
        int worker = source.indexOf("rideListLoader.execute(");
        check(worker > 0 && source.indexOf("store.list()", worker) > worker, "The history read runs on rideListLoader");
    }

    private static List<String> finishedRides(RideStore store, int count, int frames) throws Exception {
        List<String> ids = new ArrayList<>();
        for (int k = 0; k < count; k++) {
            long wall = WALL + k * 86400000L;
            String id = store.start("UT5", "Ride " + k, wall, 0);
            for (int i = 0; i < frames; i++) store.sample(wall + i * 1000L, i * 1000L, snapshot(i * 1000L, 100 + i % 5, 20, i * .001));
            store.stop(wall + frames * 1000L, frames * 1000L);
            ids.add(id);
        }
        return ids;
    }
    private static Map<String, RideStore.Sample> snapshot(long mono, double watts, double speed, double odo) {
        Map<String, RideStore.Sample> values = new LinkedHashMap<String, RideStore.Sample>();
        values.put("voltage_v", new RideStore.Sample(50.0, "50 V", "V", 0x72, mono));
        values.put("power_w", new RideStore.Sample(watts, "power", "W", 0x72, mono));
        values.put("speed_kmh", new RideStore.Sample(speed, mono));
        values.put("odometer_km", new RideStore.Sample(odo, mono));
        values.put("battery_percent", new RideStore.Sample(80, mono));
        return values;
    }
    private static String json(List<RideStore.Summary> list) {
        StringBuilder out = new StringBuilder();
        for (RideStore.Summary s : list) out.append(s.toJson()).append('\n');
        return out.toString();
    }
    private static File directory() throws Exception { return Files.createTempDirectory("volt-rides-cached-test-").toFile(); }
    private static long replays() { return RideStore.replays.get(); }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
}
