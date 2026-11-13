package com.jjricks.volt;

import java.util.*;

/** Riding mode frames, spots and the content catalog: defaults, kinds, JSON round trips and fallbacks. */
public final class RidingLayoutTest {
    private static int checks;
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }

    public static void main(String[] args) {
        defaults();
        catalog();
        editing();
        json();
        fallbacks();
        chips();
        System.out.println("RidingLayoutTest: " + checks + " checks passed (Focus default, catalog kinds, per-frame memory, JSON fallbacks)");
    }

    private static void defaults() {
        RidingLayout layout = new RidingLayout();
        check(RidingLayout.FOCUS.equals(layout.frame()) && RidingLayout.FOCUS.equals(RidingLayout.DEFAULT_FRAME), "Focus is the default frame (owner, Q10)");
        check(Arrays.asList(RidingLayout.FRAMES).equals(Arrays.asList("focus", "halo", "trace")), "three frames, Focus first");
        // Focus with the owner's tweaks.
        check(RidingLayout.STATUS.equals(layout.content("focus", "top")), "Focus's top bar is the status pill (Record retired, owner Nov 6)");
        check(RidingLayout.SPEED.equals(layout.content("focus", "big")), "Focus's big number is speed");
        check(RidingLayout.RANGE_AT_PACE.equals(layout.content("focus", "highlight")), "Focus highlights Range at this pace");
        List<String> tiles = new ArrayList<>();
        for (String spot : new String[]{"tile_1", "tile_2", "tile_3", "tile_4"}) tiles.add(layout.content("focus", spot));
        check(tiles.equals(Arrays.asList("battery", "trip", "ride_time", "battery_temperature")), "Focus: battery, then trip, ride time and battery temperature in the bottom row: " + tiles);
        check(layout.on("focus", RidingLayout.POWER_BAR) && !layout.on("focus", RidingLayout.CHIPS), "Focus has the power bar and no chips");
        check(RidingLayout.RING_SPEED.equals(layout.content("halo", "ring")) && layout.on("halo", RidingLayout.CHIPS), "Halo keeps its speed ring and chips");
        check(RidingLayout.TRACE_SPEED_POWER.equals(layout.content("trace", "trace")), "Trace charts speed over power");
        check(layout.content("focus", "ring") == null && layout.content("nope", "big") == null, "unknown spots and frames have no content");
        for (String frame : RidingLayout.FRAMES) {
            check(layout.isDefault(frame), "a new layout is the shipped default: " + frame);
            RidingLayout.Frame def = RidingLayout.frameDef(frame);
            Set<String> ids = new HashSet<>();
            for (RidingLayout.Spot spot : def.spots) {
                check(ids.add(spot.id), "spot ids are unique in " + frame);
                check(RidingLayout.accepts(spot.kind, spot.fallback), "each default fits its spot: " + frame + "/" + spot.id);
                check(spot.id.matches("[a-z][a-z0-9_]+"), "spot ids are storage ids: " + spot.id);
            }
            check(def.spot("big") != null && def.spot("top") != null, "every frame has a big number and a top bar: " + frame);
        }
    }

    private static void catalog() {
        Set<String> ids = new HashSet<>();
        for (RidingLayout.Content c : RidingLayout.CATALOG) {
            check(ids.add(c.id), "catalog ids are unique: " + c.id);
            check(!c.accepts.isEmpty(), "each content fits some spot: " + c.id);
            check(!c.id.matches("(.*_)?lock(_.*)?"), "no lock state in riding mode (owner, Q16): " + c.id);
        }
        for (String range : new String[]{RidingLayout.RANGE_AT_PACE, RidingLayout.RANGE_SCOOTER, RidingLayout.RANGE_VOLT})
            check(RidingLayout.accepts(RidingLayout.Kind.HIGHLIGHT, range) && RidingLayout.accepts(RidingLayout.Kind.BIG, range), "a range spot can show " + range);
        check(RidingLayout.item(RidingLayout.RIDE_TIME) != null && RidingLayout.item(RidingLayout.RECORDING_TIME) != null, "ride time and recording time are separate contents");
        check(!RidingLayout.accepts(RidingLayout.Kind.RING, RidingLayout.DRIVE_MODE), "drive mode can't go in the ring");
        check(RidingLayout.accepts(RidingLayout.Kind.RING, RidingLayout.BATTERY) && RidingLayout.accepts(RidingLayout.Kind.RING, RidingLayout.POWER), "the ring takes battery and power");
        // Chips hold every reading but speed (a tester asked for more choices), in catalog order like the tiles.
        // The optional GPS accuracy reading (owner, Nov 12) joins them last, with the name its text pull request
        // adds; that pull request checks its place. These are the readings that were there before it.
        List<String> chips = new ArrayList<>(RidingLayout.choices(RidingLayout.Kind.CHIP));
        chips.remove("gps_accuracy");
        check(chips.equals(Arrays.asList("power", "range_at_pace", "range_scooter", "range_volt", "battery", "trip",
                "ride_time", "recording_time", "average_speed", "max_speed", "efficiency", "ride_energy", "voltage", "current",
                "battery_temperature", "drive_mode", "clock", "odometer")), "chips: every reading but speed, no lock: " + chips);
        for (String id : RidingLayout.choices(RidingLayout.Kind.CHIP))
            check(RidingLayout.accepts(RidingLayout.Kind.TILE, id), "a chip shows only what a tile can: " + id);
        check(RidingLayout.chipNamesItself(RidingLayout.DRIVE_MODE) && RidingLayout.chipNamesItself(RidingLayout.BATTERY_TEMPERATURE)
                && RidingLayout.chipNamesItself(RidingLayout.CLOCK), "drive mode, temperature and the clock name themselves");
        for (String id : new String[]{RidingLayout.AVERAGE_SPEED, RidingLayout.MAX_SPEED, RidingLayout.CURRENT, RidingLayout.ODOMETER, RidingLayout.BATTERY, null})
            check(!RidingLayout.chipNamesItself(id), "every other chip carries a name: " + id);
        check(RidingLayout.choices(RidingLayout.Kind.TOP).equals(Arrays.asList("status")), "the top bar holds only the status pill (Record retired)");
        check(RidingLayout.item(RidingLayout.RECORD) == null && !RidingLayout.accepts(RidingLayout.Kind.TOP, RidingLayout.RECORD), "Record is no longer a choice");
        check(RidingLayout.choices(RidingLayout.Kind.TRACE).size() == 3, "three traces");
        check(!RidingLayout.accepts(RidingLayout.Kind.TILE, RidingLayout.RECORD) && !RidingLayout.accepts(RidingLayout.Kind.TILE, RidingLayout.TRACE_POWER), "controls and charts aren't readings");
        check(RidingLayout.item(null) == null && !RidingLayout.accepts(RidingLayout.Kind.TILE, null), "null content is unknown");
    }

    private static void editing() {
        RidingLayout layout = new RidingLayout();
        check(layout.setContent("focus", "highlight", RidingLayout.RANGE_SCOOTER) && RidingLayout.RANGE_SCOOTER.equals(layout.content("focus", "highlight")), "the range spot can show the scooter's estimate");
        check(!layout.setContent("focus", "highlight", RidingLayout.RECORD) && RidingLayout.RANGE_SCOOTER.equals(layout.content("focus", "highlight")), "a wrong kind is refused and the choice kept");
        check(!layout.setContent("focus", "ring", RidingLayout.BATTERY) && !layout.setContent("nope", "big", RidingLayout.SPEED), "unknown spots and frames are refused");
        check(layout.setFrame("halo") && layout.setContent("halo", "big", RidingLayout.POWER), "switch to Halo and change it");
        check(!layout.setFrame("cockpit") && "halo".equals(layout.frame()), "an unknown frame is refused");
        check(RidingLayout.RANGE_SCOOTER.equals(layout.content("focus", "highlight")), "Focus remembers its choice while Halo is shown");
        check(RidingLayout.POWER.equals(layout.content("big")), "content(spot) reads the current frame");
        check(layout.setOn("halo", RidingLayout.CHIPS, false) && !layout.on(RidingLayout.CHIPS), "chips switch off");
        check(!layout.setOn("focus", RidingLayout.CHIPS, true) && !layout.on("focus", RidingLayout.CHIPS), "a frame without chips can't switch them on");
        layout.reset("halo");
        check(layout.isDefault("halo") && layout.on("halo", RidingLayout.CHIPS) && RidingLayout.SPEED.equals(layout.content("halo", "big")), "Reset restores Halo's shipped choices");
        check(!layout.isDefault("focus"), "Reset leaves other frames alone");
        check(layout.setContent("focus", "highlight", RidingLayout.RANGE_AT_PACE) && layout.isDefault("focus"), "choosing the default again counts as default");
    }

    private static void json() {
        RidingLayout layout = new RidingLayout();
        check(layout.toJson().equals("{\"v\":1,\"frame\":\"focus\",\"frames\":{}}"), "a default layout is compact: " + layout.toJson());
        layout.setFrame("trace");
        layout.setContent("trace", "trace", RidingLayout.TRACE_POWER);
        layout.setOn("trace", RidingLayout.POWER_BAR, false);
        layout.setContent("focus", "tile_4", RidingLayout.CLOCK);
        RidingLayout back = RidingLayout.parse(layout.toJson());
        check(back.toJson().equals(layout.toJson()), "JSON round trips: " + layout.toJson());
        check("trace".equals(back.frame()) && RidingLayout.TRACE_POWER.equals(back.content("trace", "trace")) && !back.on("trace", RidingLayout.POWER_BAR)
                && RidingLayout.CLOCK.equals(back.content("focus", "tile_4")), "choices survive a round trip");
        check(RidingLayout.parse(back.toJson()).toJson().equals(layout.toJson()), "a second round trip is stable");
    }

    /** Chip choices across versions: 1.13.0 allowed only drive mode, temperature and the clock. */
    private static void chips() {
        // A 1.13.0 layout (or backup) loads unchanged: its chip choices are still accepted.
        RidingLayout old = RidingLayout.parse("{\"v\":1,\"frame\":\"halo\",\"frames\":{\"halo\":{\"spots\":{\"chip_1\":\"clock\",\"chip_3\":\"drive_mode\"}},"
                + "\"trace\":{\"spots\":{\"chip_2\":\"clock\"}}}}");
        check(RidingLayout.CLOCK.equals(old.content("halo", "chip_1")) && RidingLayout.BATTERY_TEMPERATURE.equals(old.content("halo", "chip_2"))
                && RidingLayout.DRIVE_MODE.equals(old.content("halo", "chip_3")) && RidingLayout.CLOCK.equals(old.content("trace", "chip_2")), "a 1.13.0 chip layout loads as saved");
        // The new choices, in every chip spot of every frame, and they round trip.
        RidingLayout layout = new RidingLayout();
        String[] picks = {RidingLayout.AVERAGE_SPEED, RidingLayout.MAX_SPEED, RidingLayout.CURRENT, RidingLayout.ODOMETER, RidingLayout.BATTERY};
        check(layout.setContent("halo", "chip_1", picks[0]) && layout.setContent("halo", "chip_2", picks[1]) && layout.setContent("halo", "chip_3", picks[2])
                && layout.setContent("trace", "chip_1", picks[3]) && layout.setContent("trace", "chip_2", picks[4]), "every chip spot takes the new readings");
        RidingLayout back = RidingLayout.parse(layout.toJson());
        check(picks[0].equals(back.content("halo", "chip_1")) && picks[1].equals(back.content("halo", "chip_2")) && picks[2].equals(back.content("halo", "chip_3"))
                && picks[3].equals(back.content("trace", "chip_1")) && picks[4].equals(back.content("trace", "chip_2")), "new chip choices round trip: " + layout.toJson());
        check(layout.toJson().startsWith("{\"v\":1,"), "the format version stays 1, so 1.13.0 still reads the rest of the layout");
        // What a chip can't hold, saved by hand or by a later version, falls back to that spot's default; neighbours stay.
        RidingLayout bad = RidingLayout.parse("{\"v\":1,\"frame\":\"trace\",\"frames\":{\"halo\":{\"spots\":{\"chip_1\":\"speed\",\"chip_2\":\"ring_speed\",\"chip_3\":\"trace_power\"}},"
                + "\"trace\":{\"spots\":{\"chip_1\":\"record\",\"chip_2\":\"lap_time\",\"tile_1\":\"odometer\"}}}}");
        check(RidingLayout.DRIVE_MODE.equals(bad.content("halo", "chip_1")) && RidingLayout.BATTERY_TEMPERATURE.equals(bad.content("halo", "chip_2"))
                && RidingLayout.CLOCK.equals(bad.content("halo", "chip_3")), "speed, the ring's and the chart's contents fall back in a chip");
        check(RidingLayout.DRIVE_MODE.equals(bad.content("trace", "chip_1")) && RidingLayout.BATTERY_TEMPERATURE.equals(bad.content("trace", "chip_2"))
                && RidingLayout.ODOMETER.equals(bad.content("trace", "tile_1")) && bad.isDefault("halo"), "the top bar's Record and an unknown id fall back; the tile stays");
        RidingLayout wrongType = RidingLayout.parse("{\"v\":1,\"frame\":\"halo\",\"frames\":{\"halo\":{\"spots\":{\"chip_1\":null,\"chip_2\":7,\"chip_3\":{\"id\":\"current\"}}}}}");
        check(wrongType.isDefault("halo") && "halo".equals(wrongType.frame()), "null, number and object chip values fall back without throwing");
        check(!layout.setContent("halo", "chip_1", null) && !layout.setContent("halo", "chip_1", RidingLayout.SPEED) && picks[0].equals(layout.content("halo", "chip_1")),
                "a refused chip choice keeps the current one");
    }

    private static void fallbacks() {
        for (String bad : new String[]{null, "", "not json", "[1,2]", "{\"frame\":7}", "{\"frames\":[]}", "{\"v\":1,\"frame\":\"focus\"", "\"focus\""}) {
            RidingLayout layout = RidingLayout.parse(bad);
            check("focus".equals(layout.frame()) && layout.isDefault("focus") && layout.isDefault("halo"), "unreadable text gives the defaults: " + bad);
        }
        // The retired Record choice (owner, Nov 6): a saved layout that picked it loads and shows the status pill; its other choices stay.
        RidingLayout legacy = RidingLayout.parse("{\"v\":1,\"frame\":\"halo\",\"frames\":{\"halo\":{\"spots\":{\"top\":\"record\",\"tile_1\":\"voltage\"}},\"focus\":{\"spots\":{\"top\":\"record\"}}}}");
        check("halo".equals(legacy.frame()) && RidingLayout.STATUS.equals(legacy.content("halo", "top")) && RidingLayout.STATUS.equals(legacy.content("focus", "top")), "a saved Record loads as the status pill");
        check(RidingLayout.VOLTAGE.equals(legacy.content("halo", "tile_1")) && legacy.isDefault("focus"), "the rest of a migrated layout is kept");
        check(!legacy.toJson().contains("record"), "a migrated layout saves without Record: " + legacy.toJson());
        check(RidingLayout.STATUS.equals(RidingLayout.migrated(RidingLayout.RECORD)) && RidingLayout.SPEED.equals(RidingLayout.migrated(RidingLayout.SPEED)), "only Record is renamed");
        RidingLayout unknownFrame = RidingLayout.parse("{\"v\":1,\"frame\":\"cockpit\",\"frames\":{\"cockpit\":{\"spots\":{\"big\":\"speed\"}}}}");
        check("focus".equals(unknownFrame.frame()) && unknownFrame.toJson().equals(new RidingLayout().toJson()), "an unknown frame falls back to Focus and is dropped");
        RidingLayout mixed = RidingLayout.parse("{\"v\":1,\"frame\":\"halo\",\"frames\":{\"halo\":{\"spots\":{\"big\":\"lock_state\",\"ring\":\"drive_mode\",\"tile_1\":\"voltage\",\"nowhere\":\"speed\",\"tile_2\":5},"
                + "\"switches\":{\"chips\":false,\"power_bar\":\"no\",\"glow\":true}}}}");
        check("halo".equals(mixed.frame()), "a known frame is kept");
        check(RidingLayout.SPEED.equals(mixed.content("halo", "big")), "an unknown content (old lock state) falls back to the spot default");
        check(RidingLayout.RING_SPEED.equals(mixed.content("halo", "ring")), "a content in the wrong kind falls back");
        check(RidingLayout.VOLTAGE.equals(mixed.content("halo", "tile_1")), "valid choices next to bad ones are kept");
        check(RidingLayout.BATTERY.equals(mixed.content("halo", "tile_2")), "a non-text content falls back");
        check(!mixed.on("halo", RidingLayout.CHIPS) && mixed.on("halo", RidingLayout.POWER_BAR), "a valid switch is kept and a non-boolean one falls back");
        check(!mixed.toJson().contains("nowhere") && !mixed.toJson().contains("glow") && !mixed.toJson().contains("lock_state"), "unknown spots and switches are not written back");
        RidingLayout newer = RidingLayout.parse("{\"v\":2,\"frame\":\"halo\",\"frames\":{\"halo\":{\"spots\":{\"big\":\"power\"}}}}");
        check("focus".equals(newer.frame()) && newer.isDefault("halo"), "a newer format shows the defaults instead of guessing");
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 50; i++) deep.append('[');
        check(RidingLayout.parse(deep.toString()).isDefault("focus"), "deeply nested text never throws");
    }
}
