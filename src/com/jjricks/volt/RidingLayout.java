package com.jjricks.volt;

import java.io.IOException;
import java.util.*;

/**
 * Riding mode's layouts: designed frames with typed spots (riding-mode plan, Q8 A). The rider picks
 * a frame, then what each spot shows, and switches the optional parts on or off. Each frame keeps
 * its own choices, so switching frames loses nothing. There are no saved slots (Q18 A).
 *
 * This class holds ids only. The names the screen shows for frames, spots and contents come from
 * the text catalog in the UI change, translated as words (Q19 A).
 *
 * Parsing is strict but never throws: an unknown frame falls back to the default frame, and an
 * unknown content, or a content that the spot's kind can't hold, falls back to that spot's default.
 * That holds both ways across versions, so VERSION stays 1 when a kind accepts more: 1.13.0 only let a
 * chip hold drive mode, battery temperature or the clock, and reading this version's "average_speed"
 * chip it refuses the id and shows that chip's default, while the other spots keep their choices.
 *
 * A chip spot may also hold {@link #NONE} (a tester, Nov 14): the slot shows nothing and takes no room. It is no
 * reading, so it isn't in the catalog; only a chip spot accepts it. A Volt from before it reads "none" as an unknown
 * content and shows that chip's default, as above; it never crashes on it.
 */
final class RidingLayout {
    /** What a spot is, which decides the contents it accepts. */
    enum Kind {
        /** The one huge number. */
        BIG,
        /** The arc around the big number (Halo). */
        RING,
        /** The wide reading under the big number. */
        HIGHLIGHT,
        /** A regular reading cell. */
        TILE,
        /** A small reading in a row of small readings (Halo). */
        STRIP,
        /** The live chart (Trace). */
        TRACE,
        /** A small chip beside the gauges: any reading but speed, with its name or icon unless it names itself. */
        CHIP,
        /** The top bar's status pill (it turns into the recording pill while a ride records). */
        TOP
    }

    static final String FOCUS = "focus", HALO = "halo", TRACE = "trace";
    /** Shown order. Focus is the default (owner, Q10). */
    static final String[] FRAMES = {FOCUS, HALO, TRACE};
    static final String DEFAULT_FRAME = FOCUS;

    // Single readings.
    static final String SPEED = "speed", POWER = "power", BATTERY = "battery", TRIP = "trip", ODOMETER = "odometer",
            RIDE_TIME = "ride_time", RECORDING_TIME = "recording_time", AVERAGE_SPEED = "average_speed",
            MAX_SPEED = "max_speed", EFFICIENCY = "efficiency", RIDE_ENERGY = "ride_energy", VOLTAGE = "voltage",
            CURRENT = "current", BATTERY_TEMPERATURE = "battery_temperature", DRIVE_MODE = "drive_mode", CLOCK = "clock";
    /** The three range readings a range spot can show; Range at this pace is the default (owner, live range). */
    static final String RANGE_AT_PACE = "range_at_pace", RANGE_SCOOTER = "range_scooter", RANGE_VOLT = "range_volt";
    /**
     * The latest GPS fix's accuracy while a ride records (owner, Nov 12): an optional reading for a tile or a chip, in no
     * frame by default. It only reads the phone recorder, never starting GPS or keeping it or the screen awake.
     */
    static final String GPS_ACCURACY = "gps_accuracy";
    // Ring, trace and top-bar contents.
    static final String RING_SPEED = "ring_speed", TRACE_SPEED_POWER = "trace_speed_power", TRACE_POWER = "trace_power",
            TRACE_SPEED = "trace_speed", RECORD = "record", STATUS = "status";
    // RECORD is a retired top-bar choice (owner, Nov 6): the status pill now becomes the recording pill, so
    // Record and Status showed the same thing. migrated() reads it as STATUS wherever it appears.
    // Switches.
    static final String POWER_BAR = "power_bar", CHIPS = "chips";
    /** An empty chip slot: no chip, and no room taken. Only a chip spot accepts it (see accepts). */
    static final String NONE = "none";
    /** Speed and range to tenths: on by default, with a switch in the layout editor (owner, Q23 B). */
    static final String TENTHS = "tenths";

    /** One content item and the spot kinds that can hold it. */
    static final class Content {
        final String id;
        final Set<Kind> accepts;
        private Content(String id, Set<Kind> accepts) { this.id = id; this.accepts = Collections.unmodifiableSet(accepts); }
    }

    /**
     * The content catalog, live readings first. There is no lock state: riding mode never shows or
     * changes the lock (owner, Q16).
     */
    static final List<Content> CATALOG;
    private static final Map<String, Content> BY_ID = new LinkedHashMap<>();
    static {
        Set<Kind> single = EnumSet.of(Kind.BIG, Kind.HIGHLIGHT, Kind.TILE, Kind.STRIP);
        List<Content> all = new ArrayList<>();
        for (String id : new String[]{SPEED, POWER, RANGE_AT_PACE, RANGE_SCOOTER, RANGE_VOLT, BATTERY, TRIP, RIDE_TIME,
                RECORDING_TIME, AVERAGE_SPEED, MAX_SPEED, EFFICIENCY, RIDE_ENERGY, VOLTAGE, CURRENT, BATTERY_TEMPERATURE,
                DRIVE_MODE, CLOCK, ODOMETER}) {
            Set<Kind> kinds = EnumSet.copyOf(single);
            if (id.equals(BATTERY) || id.equals(POWER)) kinds.add(Kind.RING);
            // A chip holds every reading but speed, which the big number shows (a tester asked for more chip choices).
            if (!id.equals(SPEED)) kinds.add(Kind.CHIP);
            all.add(new Content(id, kinds));
        }
        all.add(new Content(GPS_ACCURACY, EnumSet.of(Kind.TILE, Kind.CHIP)));
        all.add(new Content(RING_SPEED, EnumSet.of(Kind.RING)));
        for (String id : new String[]{TRACE_SPEED_POWER, TRACE_POWER, TRACE_SPEED}) all.add(new Content(id, EnumSet.of(Kind.TRACE)));
        all.add(new Content(STATUS, EnumSet.of(Kind.TOP)));
        for (Content content : all) BY_ID.put(content.id, content);
        CATALOG = Collections.unmodifiableList(all);
    }

    static Content item(String id) { return id == null ? null : BY_ID.get(id); }
    static boolean accepts(Kind kind, String contentId) {
        if (NONE.equals(contentId)) return kind == Kind.CHIP;
        Content c = item(contentId);
        return c != null && c.accepts.contains(kind);
    }
    /** True for a spot content that shows something: anything but an empty chip slot (or no content at all). */
    static boolean shows(String contentId) { return contentId != null && !NONE.equals(contentId); }
    /** The contents a spot of this kind can show, in catalog order. */
    static List<String> choices(Kind kind) {
        List<String> out = new ArrayList<>();
        for (Content c : CATALOG) if (c.accepts.contains(kind)) out.add(c.id);
        return out;
    }

    /**
     * The icon a reading's name falls back to on a narrow label (and its chip's icon), or null for a reading that has none.
     * Readings that can sit side by side each have their own, so a group of them can always show icons together
     * (RidingNameGroup) without two readings looking alike: Average and Max speed mark the speedometer the way Halo's ring
     * marks the ride (a dot for the average, a marker past the dial for the max), Trip ends at a flag where Range at this pace keeps the
     * route ahead, and Ride time's stopwatch and Recording's record dot stand apart from the clock. Speed has the plain
     * speedometer, Efficiency a leaf, Voltage and Current the circuit symbols of a voltmeter and an ammeter, Drive mode a
     * gear-shift pattern and the Odometer a distance arrow. The three range readings share the route and are told apart by their
     * names. Every reading a tile or the strip can show has one.
     */
    static String icon(String contentId) {
        if (contentId == null) return null;
        switch (contentId) {
            case RANGE_AT_PACE: case RANGE_SCOOTER: case RANGE_VOLT: return "route";
            case TRIP: return "trip";
            case BATTERY: return "battery";
            case RIDE_TIME: return "stopwatch";
            case RECORDING_TIME: return "recording";
            case AVERAGE_SPEED: return "speed_average";
            case MAX_SPEED: return "speed_max";
            case RIDE_ENERGY: return "energy";
            case BATTERY_TEMPERATURE: return "temperature";
            case CLOCK: return "clock";
            case POWER: return "power";
            case SPEED: return "speed";
            case EFFICIENCY: return "efficiency";
            case VOLTAGE: return "voltage";
            case CURRENT: return "current";
            case DRIVE_MODE: return "mode";
            case ODOMETER: return "distance";
            case GPS_ACCURACY: return "gps_accuracy";
            default: return null;
        }
    }

    /**
     * True for the chips whose value names itself ("SPORT", "31 °C", "12:48"): drive mode, battery
     * temperature and the clock. Every other chip carries the reading's name or icon beside its value.
     */
    static boolean chipNamesItself(String contentId) {
        return DRIVE_MODE.equals(contentId) || BATTERY_TEMPERATURE.equals(contentId) || CLOCK.equals(contentId);
    }

    /** A place in a frame. Its id is stable storage; its default is the shipped choice. */
    static final class Spot {
        final String id, fallback;
        final Kind kind;
        private Spot(String id, Kind kind, String fallback) {
            fallback = migrated(fallback);
            if (!accepts(kind, fallback)) throw new IllegalArgumentException(id);
            this.id = id; this.kind = kind; this.fallback = fallback;
        }
    }

    /** A designed frame: its spots and its switches with their shipped values. */
    static final class Frame {
        final String id;
        final List<Spot> spots;
        final Map<String, Boolean> switches;
        private Frame(String id, Spot[] spots, String[] switchIds, boolean[] switchDefaults) {
            this.id = id;
            this.spots = Collections.unmodifiableList(Arrays.asList(spots));
            Map<String, Boolean> map = new LinkedHashMap<>();
            for (int i = 0; i < switchIds.length; i++) map.put(switchIds[i], switchDefaults[i]);
            this.switches = Collections.unmodifiableMap(map);
        }
        Spot spot(String spotId) { for (Spot s : spots) if (s.id.equals(spotId)) return s; return null; }
    }

    private static Spot spot(String id, Kind kind, String fallback) { return new Spot(id, kind, fallback); }

    /** A frame's spots with more after them, in order (the chip slots added on Nov 14). */
    private static Spot[] join(Spot[] spots, Spot... more) {
        Spot[] out = Arrays.copyOf(spots, spots.length + more.length);
        System.arraycopy(more, 0, out, spots.length, more.length);
        return out;
    }

    private static final Map<String, Frame> FRAME_DEFS = new LinkedHashMap<>();
    static {
        // Focus (concept-b-focus) with the owner's tweaks: battery temperature joins the bottom row beside
        // trip and ride time. Its top bar is the status pill, which turns into the recording pill while a
        // ride records (owner, Nov 6; the Record control it once named was retired).
        FRAME_DEFS.put(FOCUS, new Frame(FOCUS, join(new Spot[]{
                spot("top", Kind.TOP, RECORD),
                spot("big", Kind.BIG, SPEED),
                spot("highlight", Kind.HIGHLIGHT, RANGE_AT_PACE),
                spot("tile_1", Kind.TILE, BATTERY),
                spot("tile_2", Kind.TILE, TRIP),
                spot("tile_3", Kind.TILE, RIDE_TIME),
                spot("tile_4", Kind.TILE, BATTERY_TEMPERATURE)},
                // Up to three chips beside the speed (a tester, Nov 14), none by default, so Focus looks as it did.
                spot("chip_1", Kind.CHIP, NONE), spot("chip_2", Kind.CHIP, NONE), spot("chip_3", Kind.CHIP, NONE)),
                new String[]{POWER_BAR, TENTHS}, new boolean[]{true, true}));
        // Halo (concept-a-halo): speed in the ring, range under it, two tiles, a strip and chips.
        FRAME_DEFS.put(HALO, new Frame(HALO, new Spot[]{
                spot("top", Kind.TOP, STATUS),
                spot("big", Kind.BIG, SPEED),
                spot("ring", Kind.RING, RING_SPEED),
                spot("highlight", Kind.HIGHLIGHT, RANGE_AT_PACE),
                spot("tile_1", Kind.TILE, TRIP),
                spot("tile_2", Kind.TILE, BATTERY),
                spot("strip_1", Kind.STRIP, AVERAGE_SPEED),
                spot("strip_2", Kind.STRIP, RIDE_TIME),
                spot("chip_1", Kind.CHIP, DRIVE_MODE),
                spot("chip_2", Kind.CHIP, BATTERY_TEMPERATURE),
                spot("chip_3", Kind.CHIP, CLOCK)},
                new String[]{POWER_BAR, CHIPS, TENTHS}, new boolean[]{true, true, true}));
        // Trace (concept-c-trace): a 60 s chart across the screen under the big number.
        FRAME_DEFS.put(TRACE, new Frame(TRACE, join(new Spot[]{
                spot("top", Kind.TOP, STATUS),
                spot("big", Kind.BIG, SPEED),
                spot("highlight", Kind.HIGHLIGHT, RANGE_AT_PACE),
                spot("tile_1", Kind.TILE, TRIP),
                spot("tile_2", Kind.TILE, BATTERY),
                spot("trace", Kind.TRACE, TRACE_SPEED_POWER),
                spot("chip_1", Kind.CHIP, DRIVE_MODE),
                spot("chip_2", Kind.CHIP, BATTERY_TEMPERATURE)},
                // A third chip under the two (a tester, Nov 14), none by default, so Trace looks as it did.
                spot("chip_3", Kind.CHIP, NONE)),
                new String[]{POWER_BAR, CHIPS, TENTHS}, new boolean[]{true, true, true}));
    }

    static Frame frameDef(String id) { return id == null ? null : FRAME_DEFS.get(id); }

    /** The JSON format version this class writes. */
    static final int VERSION = 1;
    private static final int MAX_DEPTH = 5;

    private String frame = DEFAULT_FRAME;
    /** frame id to (spot id to content id), only for choices that differ from the frame's default. */
    private final Map<String, Map<String, String>> spots = new LinkedHashMap<>();
    /** frame id to (switch id to value), only for choices that differ from the frame's default. */
    private final Map<String, Map<String, Boolean>> switches = new LinkedHashMap<>();

    RidingLayout() { }

    String frame() { return frame; }
    /** Chooses a frame. An unknown id is refused and the current frame kept. */
    boolean setFrame(String id) { if (frameDef(id) == null) return false; frame = id; return true; }

    /** What a frame's spot shows: the rider's choice, else the frame default. Null for an unknown spot. */
    String content(String frameId, String spotId) {
        Frame f = frameDef(frameId);
        Spot s = f == null ? null : f.spot(spotId);
        if (s == null) return null;
        Map<String, String> chosen = spots.get(frameId);
        String id = chosen == null ? null : chosen.get(spotId);
        return id != null ? id : s.fallback;
    }
    String content(String spotId) { return content(frame, spotId); }

    /** Sets a spot's content. Refused (false) when the frame or spot is unknown or the kind can't hold it. */
    boolean setContent(String frameId, String spotId, String contentId) {
        Frame f = frameDef(frameId);
        Spot s = f == null ? null : f.spot(spotId);
        if (s == null || !accepts(s.kind, contentId)) return false;
        Map<String, String> chosen = spots.get(frameId);
        if (contentId.equals(s.fallback)) { if (chosen != null) chosen.remove(spotId); }
        else { if (chosen == null) spots.put(frameId, chosen = new LinkedHashMap<>()); chosen.put(spotId, contentId); }
        return true;
    }

    /** A switch's value; false for a switch the frame doesn't have. */
    boolean on(String frameId, String switchId) {
        Frame f = frameDef(frameId);
        if (f == null || !f.switches.containsKey(switchId)) return false;
        Map<String, Boolean> chosen = switches.get(frameId);
        Boolean value = chosen == null ? null : chosen.get(switchId);
        return value != null ? value : f.switches.get(switchId);
    }
    boolean on(String switchId) { return on(frame, switchId); }

    boolean setOn(String frameId, String switchId, boolean value) {
        Frame f = frameDef(frameId);
        if (f == null || !f.switches.containsKey(switchId)) return false;
        Map<String, Boolean> chosen = switches.get(frameId);
        if (value == f.switches.get(switchId)) { if (chosen != null) chosen.remove(switchId); }
        else { if (chosen == null) switches.put(frameId, chosen = new LinkedHashMap<>()); chosen.put(switchId, value); }
        return true;
    }

    /** Reset to default: the frame's shipped choices. Other frames keep theirs. */
    void reset(String frameId) { spots.remove(frameId); switches.remove(frameId); }
    /** True when the frame shows its shipped choices. */
    boolean isDefault(String frameId) {
        Map<String, String> a = spots.get(frameId);
        Map<String, Boolean> b = switches.get(frameId);
        return (a == null || a.isEmpty()) && (b == null || b.isEmpty());
    }

    /** Compact JSON: the chosen frame and only the choices that differ from each frame's defaults. */
    String toJson() {
        StringBuilder out = new StringBuilder("{\"v\":").append(VERSION).append(",\"frame\":").append(BackupJson.quote(frame)).append(",\"frames\":{");
        boolean firstFrame = true;
        for (String id : FRAMES) {
            Map<String, String> a = spots.get(id);
            Map<String, Boolean> b = switches.get(id);
            boolean hasA = a != null && !a.isEmpty(), hasB = b != null && !b.isEmpty();
            if (!hasA && !hasB) continue;
            if (!firstFrame) out.append(',');
            firstFrame = false;
            out.append(BackupJson.quote(id)).append(":{");
            if (hasA) {
                out.append("\"spots\":{");
                boolean first = true;
                for (Map.Entry<String, String> e : a.entrySet()) {
                    if (!first) out.append(',');
                    first = false;
                    out.append(BackupJson.quote(e.getKey())).append(':').append(BackupJson.quote(e.getValue()));
                }
                out.append('}');
            }
            if (hasB) {
                if (hasA) out.append(',');
                out.append("\"switches\":{");
                boolean first = true;
                for (Map.Entry<String, Boolean> e : b.entrySet()) {
                    if (!first) out.append(',');
                    first = false;
                    out.append(BackupJson.quote(e.getKey())).append(':').append(e.getValue() ? "true" : "false");
                }
                out.append('}');
            }
            out.append('}');
        }
        return out.append("}}").toString();
    }

    /** A saved content id as this version names it: the retired Record choice is the status pill. */
    static String migrated(String contentId) { return RECORD.equals(contentId) ? STATUS : contentId; }

    /** Reads saved JSON. Null, malformed or foreign text gives the shipped defaults; it never throws. */
    static RidingLayout parse(String json) {
        RidingLayout layout = new RidingLayout();
        if (json == null || json.isEmpty()) return layout;
        Map<String, Object> root;
        try { root = BackupJson.object(BackupJson.parse(json, MAX_DEPTH)); }
        catch (IOException | RuntimeException unreadable) { return layout; }
        Object version = root.get("v");
        // A newer format may mean different things by the same ids: show the defaults rather than guess.
        if (version instanceof Long && (Long) version > VERSION) return layout;
        Object chosen = root.get("frame");
        if (chosen instanceof String) layout.setFrame((String) chosen);
        Object frames = root.get("frames");
        if (!(frames instanceof Map)) return layout;
        for (Map.Entry<?, ?> f : ((Map<?, ?>) frames).entrySet()) {
            String frameId = String.valueOf(f.getKey());
            if (frameDef(frameId) == null || !(f.getValue() instanceof Map)) continue;
            Map<?, ?> body = (Map<?, ?>) f.getValue();
            if (body.get("spots") instanceof Map)
                for (Map.Entry<?, ?> s : ((Map<?, ?>) body.get("spots")).entrySet())
                    if (s.getValue() instanceof String) layout.setContent(frameId, String.valueOf(s.getKey()), migrated((String) s.getValue()));
            if (body.get("switches") instanceof Map)
                for (Map.Entry<?, ?> s : ((Map<?, ?>) body.get("switches")).entrySet())
                    if (s.getValue() instanceof Boolean) layout.setOn(frameId, String.valueOf(s.getKey()), (Boolean) s.getValue());
        }
        return layout;
    }
}
