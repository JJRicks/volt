package com.jjricks.volt;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Riding mode's steady chips and highlight (a tester, Nov 14): chips fitted from each reading's widest value, never the
 * value on show; a row that fills the width, larger with fewer chips; None for a chip slot; a column that hides its
 * lowest chips when it's short; and a highlight that grows into spare room at a size that depends on the layout alone.
 */
public final class RidingChipSteadyTest {
    private static int checks;
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }

    public static void main(String[] args) {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(Locale.US);
            T.install(null);
            spread();
            sizes();
            steady();
            column();
            none();
            highlight();
            clockDay();
            speeds();
        } finally {
            T.install(null);
            Locale.setDefault(saved);
        }
        System.out.println("RidingChipSteadyTest: " + checks + " checks passed (row fill, sizes, widest values, short column, "
                + "None, highlight growth, every time of day, every speed)");
    }

    private static void spread() {
        int[] out = RidingChipFit.spread(new int[]{100, 50, 80}, 8, 300);
        check(out[0] == 118 && out[1] == 68 && out[2] == 98, "each chip takes an equal share of what's left");
        check(out[0] + out[1] + out[2] + 16 == 300, "the row's ends meet the room's edges");
        int[] odd = RidingChipFit.spread(new int[]{10, 10, 10}, 0, 32);
        check(odd[0] == 11 && odd[1] == 11 && odd[2] == 10, "the first chips take the odd pixels");
        int[] one = RidingChipFit.spread(new int[]{120}, 8, 380);
        check(one[0] == 380, "one chip spans the row");
        int[] over = RidingChipFit.spread(new int[]{200, 200}, 8, 300);
        check(over[0] == 200 && over[1] == 200, "widths that overfill are left as they are");
        check(RidingChipFit.spread(new int[]{50}, 8, Integer.MAX_VALUE)[0] == 50, "an unbounded row keeps its widths");
        check(RidingChipFit.spread(new int[0], 8, 300).length == 0, "no chips, nothing to spread");
    }

    /** Widths that scale with the size, as text does; the padding (20) doesn't. */
    private static RidingChipFit.SizedWidths scaled(final int[][] text) {
        return (size, chip, rung, step) -> 20 + Math.round(text[rung][chip] * RidingChipFit.SIZE_SCALE[size]);
    }

    private static int[][] rungs(int... widths) {
        int[][] out = new int[RidingChipFit.RUNGS][];
        for (int r = 0; r < RidingChipFit.RUNGS; r++) out[r] = widths.clone();
        return out;
    }

    private static void sizes() {
        check(RidingChipFit.largestSize(1) == RidingChipFit.SIZE_LARGE, "one chip may draw large");
        check(RidingChipFit.largestSize(2) == RidingChipFit.SIZE_MEDIUM, "two chips medium");
        check(RidingChipFit.largestSize(3) == RidingChipFit.SIZE_NORMAL, "three chips keep today's size");
        check(RidingChipFit.SIZE_SCALE[RidingChipFit.SIZE_NORMAL] == 1f, "the three-chip size is today's");
        for (int i = 1; i < RidingChipFit.SIZES; i++) check(RidingChipFit.SIZE_SCALE[i] > RidingChipFit.SIZE_SCALE[i - 1], "sizes only grow");

        String[][] marks = new String[1][];
        String[] contents = {RidingLayout.CLOCK};
        RidingChipFit.SizedWidths one = scaled(rungs(100));
        RidingChipFit.Sized large = RidingChipFit.sized(marks, contents, one, one, null, RidingChipFit.largestSize(1), 8, 400);
        check(large.size == RidingChipFit.SIZE_LARGE && large.fit.count == 1, "one chip with room draws large");
        // Large needs 160, medium 140, normal 120.
        RidingChipFit.Sized medium = RidingChipFit.sized(marks, contents, one, one, null, RidingChipFit.largestSize(1), 8, 150);
        check(medium.size == RidingChipFit.SIZE_MEDIUM, "too tight for large: medium");
        RidingChipFit.Sized normal = RidingChipFit.sized(marks, contents, one, one, null, RidingChipFit.largestSize(1), 8, 130);
        check(normal.size == RidingChipFit.SIZE_NORMAL && normal.fit.count == 1, "too tight for medium: today's size, never smaller");

        // Three chips never grow, even with room to spare.
        String[][] three = new String[3][];
        String[] threeContents = {RidingLayout.DRIVE_MODE, RidingLayout.BATTERY_TEMPERATURE, RidingLayout.CLOCK};
        RidingChipFit.SizedWidths w3 = scaled(rungs(60, 60, 60));
        check(RidingChipFit.sized(three, threeContents, w3, w3, null, RidingChipFit.largestSize(3), 8, 2000).size == RidingChipFit.SIZE_NORMAL,
                "three chips keep today's size");

        // Two chips: medium only where it costs no rung. Full is 100 + 100, short names 60 + 60 (scaled text).
        int[][] named = new int[RidingChipFit.RUNGS][];
        for (int r = 0; r < RidingChipFit.RUNGS; r++) named[r] = r < RidingChipFit.SHORT_NAMES ? new int[]{100, 100} : new int[]{60, 60};
        String[][] twoMarks = {{"Average speed", "Avg", null}, {"Voltage", "Voltage", null}};
        String[] twoContents = {RidingLayout.AVERAGE_SPEED, RidingLayout.VOLTAGE};
        RidingChipFit.SizedWidths w2 = scaled(named);
        // Normal full: 240 + 8. Medium full: 280 + 8.
        RidingChipFit.Sized fits = RidingChipFit.sized(twoMarks, twoContents, w2, w2, null, RidingChipFit.largestSize(2), 8, 290);
        check(fits.size == RidingChipFit.SIZE_MEDIUM && fits.fit.rung == RidingChipFit.FULL, "two chips draw medium with their full names");
        RidingChipFit.Sized keeps = RidingChipFit.sized(twoMarks, twoContents, w2, w2, null, RidingChipFit.largestSize(2), 8, 260);
        check(keeps.size == RidingChipFit.SIZE_NORMAL && keeps.fit.rung == RidingChipFit.FULL,
                "a larger size never costs a name: full names at today's size beat short names larger");
        RidingChipFit.Sized shorter = RidingChipFit.sized(twoMarks, twoContents, w2, w2, null, RidingChipFit.largestSize(2), 8, 200);
        check(shorter.fit.rung == RidingChipFit.SHORT_NAMES && shorter.fit.count == 2, "a tight row shortens names, as before");
    }

    /**
     * The fit takes a reading's widest value, so every value it shows fits inside the chip and nothing moves: shown here
     * with a width model where each figure is as wide as its own glyph, and 8 the widest.
     */
    private static void steady() {
        String digits = "0123456789";
        check("88.8 km/h".equals(RidingChipFit.widened("12.3 km/h", digits, 8)), "every figure becomes the widest");
        check("-888 Wh/mi".equals(RidingChipFit.widened("-417 Wh/mi", digits, 8)), "signs and units stay");
        check(RidingChipFit.widened(null, digits, 8) == null, "nothing stays nothing");
        check("12".equals(RidingChipFit.widened("12", "0123", 3)), "a language without ten single figures keeps its text");
        check("٨٨".equals(RidingChipFit.widened("١٢", "٠١٢٣٤٥٦٧٨٩", 8)), "another script's figures widen to its own widest");

        float[] glyph = {.55f, .40f, .52f, .52f, .54f, .52f, .54f, .48f, .56f, .54f};
        String sample = RidingChipFit.widened("888.8 V", digits, 8);
        float widest = width(sample, glyph);
        for (int tenths = 0; tenths <= 9999; tenths++) {
            String value = String.format(Locale.US, "%.1f V", tenths / 10.0);
            if (width(value, glyph) > widest + 1e-4f) { check(false, "a voltage wider than its sample: " + value); return; }
        }
        check(true, "no voltage from 0.0 to 999.9 is wider than its widened sample");
        // The fit reads the sample's width only: the same widths give the same fit, whatever is on show.
        int w = Math.round(widest * 100);
        int[][] fixed = rungs(w + 40, w + 40, w + 40);
        RidingChipFit a = RidingChipFit.row(new String[3][], new String[3], (chip, rung, step) -> fixed[rung][chip], 8, 500);
        RidingChipFit b = RidingChipFit.row(new String[3][], new String[3], (chip, rung, step) -> fixed[rung][chip], 8, 500);
        check(a.count == b.count && a.rung == b.rung, "the same widest values fit the same way every time");
    }

    private static float width(String text, float[] glyph) {
        float out = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            out += c >= '0' && c <= '9' ? glyph[c - '0'] : c == '.' ? .25f : c == ' ' ? .25f : .6f;
        }
        return out;
    }

    private static void column() {
        check(RidingChipFit.stackable(40, 8, 100) == 2, "two 40 dp chips and a gap fit in 100");
        check(RidingChipFit.stackable(40, 8, 135) == 2, "a third needs 136");
        check(RidingChipFit.stackable(40, 8, 136) == 3, "and fits at 136");
        check(RidingChipFit.stackable(40, 8, 39) == 0, "a column shorter than a chip shows none");
        check(RidingChipFit.stackable(0, 8, 100) == Integer.MAX_VALUE, "an unmeasured chip sets no limit");

        String[][] marks = new String[3][];
        String[] contents = {RidingLayout.DRIVE_MODE, RidingLayout.BATTERY_TEMPERATURE, RidingLayout.CLOCK};
        int[][] widths = rungs(90, 90, 90);
        RidingChipFit.Widths w = (chip, rung, step) -> widths[rung][chip];
        RidingChipFit all = RidingChipFit.column(marks, contents, w, 200, Integer.MAX_VALUE);
        check(all.count == 3, "a tall column shows every chip");
        RidingChipFit two = RidingChipFit.column(marks, contents, w, 200, 2);
        check(two.count == 2 && two.shown[0] && two.shown[1] && !two.shown[2], "a short column hides the lowest chip");
        for (int i = 0; i < 5; i++) {
            RidingChipFit again = RidingChipFit.column(marks, contents, w, 200, 2);
            check(java.util.Arrays.equals(again.shown, two.shown), "always the same chip");
        }
        check(RidingChipFit.column(marks, contents, w, 200, 0).count == 0, "no room, no chips");
        RidingChipFit plain = RidingChipFit.column(marks, contents, w, 200);
        RidingChipFit limited = RidingChipFit.column(marks, contents, w, 200, Integer.MAX_VALUE);
        check(java.util.Arrays.equals(plain.shown, limited.shown) && plain.rung == limited.rung, "with no limit the column fits as it always did");
        RidingChipFit stepped = RidingChipFit.steppingColumn(marks, contents, w, w, new boolean[3], 200, 1);
        check(stepped.count == 1 && stepped.shown[0], "the stepping column keeps the limit too");
    }

    private static void none() {
        check(RidingLayout.NONE.equals("none"), "the saved id is none");
        check(RidingLayout.accepts(RidingLayout.Kind.CHIP, RidingLayout.NONE), "a chip slot takes None");
        for (RidingLayout.Kind kind : RidingLayout.Kind.values())
            if (kind != RidingLayout.Kind.CHIP) check(!RidingLayout.accepts(kind, RidingLayout.NONE), "only a chip slot takes None: " + kind);
        check(!RidingLayout.choices(RidingLayout.Kind.CHIP).contains(RidingLayout.NONE), "None is no reading in the catalog");
        check(!RidingLayout.shows(RidingLayout.NONE) && RidingLayout.shows(RidingLayout.CLOCK) && !RidingLayout.shows(null), "None shows nothing");

        RidingLayout.Spot chip = RidingLayout.frameDef(RidingLayout.HALO).spot("chip_1");
        List<String> options = RidingEditor.options(chip);
        check(RidingLayout.NONE.equals(options.get(0)), "the editor offers None first for a chip slot");
        check(options.subList(1, options.size()).equals(RidingEditor.choices(chip)), "then every reading, as before");
        RidingLayout.Spot tile = RidingLayout.frameDef(RidingLayout.HALO).spot("tile_1");
        check(RidingEditor.options(tile).equals(RidingEditor.choices(tile)), "other spots offer exactly their readings");
        check("None".equals(T.ridingContentNone()), "the editor names it None");

        RidingLayout layout = new RidingLayout();
        for (String spot : RidingFrames.HALO_CHIPS) check(layout.setContent(RidingLayout.HALO, spot, RidingLayout.NONE), "every Halo chip slot takes None");
        check(!layout.setContent(RidingLayout.HALO, "tile_1", RidingLayout.NONE), "a tile refuses None");
        String json = layout.toJson();
        check(json.contains("\"chip_1\":\"none\""), "None is saved by its id");
        RidingLayout back = RidingLayout.parse(json);
        for (String spot : RidingFrames.HALO_CHIPS) check(RidingLayout.NONE.equals(back.content(RidingLayout.HALO, spot)), "None comes back from the saved layout");
        check(back.toJson().equals(json), "a backup round-trips None unchanged");
        check(back.on(RidingLayout.HALO, RidingLayout.CHIPS), "all None keeps the chips switch as it was");

        // A Volt that doesn't know an id refuses it and shows that slot's default (how a Volt from before None reads it).
        RidingLayout older = RidingLayout.parse("{\"v\":1,\"frame\":\"halo\",\"frames\":{\"halo\":{\"spots\":{\"chip_1\":\"sparkles\",\"chip_2\":\"clock\"}}}}");
        check(RidingLayout.DRIVE_MODE.equals(older.content(RidingLayout.HALO, "chip_1")), "an unknown chip id shows the slot's default");
        check(RidingLayout.CLOCK.equals(older.content(RidingLayout.HALO, "chip_2")), "and the other slots keep their choices");
        RidingLayout tileNone = RidingLayout.parse("{\"v\":1,\"frames\":{\"halo\":{\"spots\":{\"tile_1\":\"none\"}}}}");
        check(RidingLayout.TRIP.equals(tileNone.content(RidingLayout.HALO, "tile_1")), "None where it can't go reads as the default");

        RidingEditor editor = new RidingEditor(new RidingLayout());
        check(editor.showFrame(RidingLayout.HALO) && editor.choose("chip_2", RidingLayout.NONE), "the editor sets None");
        check(RidingLayout.NONE.equals(editor.content("chip_2")) && editor.changed(), "and it's a change to save");
    }

    private static void highlight() {
        check(RidingHighlightSize.grows(RidingLayout.CLOCK) && RidingHighlightSize.grows(RidingLayout.RIDE_TIME)
                && RidingHighlightSize.grows(RidingLayout.RECORDING_TIME) && RidingHighlightSize.grows(RidingLayout.BATTERY)
                && RidingHighlightSize.grows(RidingLayout.SPEED) && RidingHighlightSize.grows(RidingLayout.AVERAGE_SPEED)
                && RidingHighlightSize.grows(RidingLayout.MAX_SPEED), "readings with a known widest value grow");
        check(!RidingHighlightSize.grows(RidingLayout.RANGE_AT_PACE) && !RidingHighlightSize.grows(RidingLayout.GPS_ACCURACY)
                && !RidingHighlightSize.grows(RidingLayout.DRIVE_MODE) && !RidingHighlightSize.grows(null), "the others keep their size");

        float base = 84, big = 200;
        check(RidingHighlightSize.px(2f, .8f, 100, 60, base, big) == base, "no spare room: the designed size");
        check(RidingHighlightSize.px(2f, .8f, 180, 100, base, big) == 90, "spare room: the size at which the widest value fits");
        check(RidingHighlightSize.px(2f, .8f, 1000, 1000, base, big) == base * RidingHighlightSize.GROW_MAX, "growth is capped");
        check(RidingHighlightSize.px(2f, .8f, 1000, 1000, base, 90) == 90, "never larger than the big number");
        check(RidingHighlightSize.px(2f, .8f, 1000, 1000, base, 50) == base, "a big number smaller than the highlight leaves it as designed");
        check(RidingHighlightSize.px(0, .8f, 1000, 1000, base, big) == base && RidingHighlightSize.px(2f, .8f, 0, 0, base, big) == base,
                "nothing measured: the designed size");
        // Only the layout moves it: the same inputs give the same size; a change of room, cap or design changes it.
        float at = RidingHighlightSize.px(2f, .8f, 200, 100, base, big);
        check(RidingHighlightSize.px(2f, .8f, 200, 100, base, big) == at, "the same layout, the same size");
        check(RidingHighlightSize.px(2f, .8f, 210, 100, base, big) != at, "a wider spot changes it");
        check(RidingHighlightSize.px(2.2f, .8f, 200, 100, base, big) != at, "a wider widest value (units, format, language) changes it");
        check(RidingHighlightSize.px(2f, .8f, 200, 72, base, big) != at, "a shorter spot changes it");
    }

    /** Character widths with figures of one width (tnum), per px of size. */
    private static float tabular(String text) {
        float out = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') out += .56f;
            else if (c == ':') out += .26f;
            else if (c == ' ' || c == ' ' || c == ' ') out += .24f;
            else if (c == 'A') out += .66f;
            else if (c == 'P') out += .62f;
            else if (c == 'M') out += .82f;
            else out += .7f;
        }
        return out;
    }

    /**
     * Every minute and second of a day in each clock format draws at the one size worked out from the samples: no time
     * is wider than the widest sample, so none of them shrinks it.
     */
    private static void clockDay() {
        String[] patterns = {"H:mm", "HH:mm", "h:mm a", "hh:mm a", "H:mm:ss", "HH:mm:ss", "h:mm:ss a", "hh:mm:ss a"};
        float base = 84, big = 200, height = 140;
        for (String pattern : patterns) {
            SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
            Calendar day = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US);
            float widest = 0;
            for (int[] t : RidingHighlightSize.CLOCK_SAMPLES) {
                day.clear();
                day.set(2026, Calendar.NOVEMBER, 14, t[0], t[1], t[2]);
                widest = Math.max(widest, tabular(format.format(day.getTime())));
            }
            // A spot with room to spare (the Clock beside a hidden power bar).
            float room = spare(widest, base);
            float size = RidingHighlightSize.px(widest, .72f, room, height, base, big);
            check(size > base, pattern + ": grows into the spare room");
            boolean seconds = pattern.contains("ss");
            boolean all = true;
            int steps = seconds ? 24 * 3600 : 24 * 60;
            for (int i = 0; i < steps && all; i++) {
                day.clear();
                day.set(2026, Calendar.NOVEMBER, 14, 0, 0, 0);
                day.add(seconds ? Calendar.SECOND : Calendar.MINUTE, i);
                String shown = format.format(day.getTime());
                // The size drawn is the one worked out, unless the time is wider than room at that size (it never is).
                float drawn = tabular(shown) * size <= room + 1e-3f ? size : room / tabular(shown);
                all = drawn == size;
                if (!all) check(false, pattern + ": " + shown + " would draw smaller than the rest");
            }
            check(all, pattern + ": every " + (seconds ? "second" : "minute") + " of the day draws at one size");
        }
    }

    /** Room 15 % wider than the widest value needs at the designed size. */
    private static float spare(float widest, float base) { return widest * base * 1.15f; }

    /** Every speed from 0 to 999.9 fits the size worked out from 888.8, so a speed highlight never changes size. */
    private static void speeds() {
        String sample = String.format(Locale.US, "%.1f", RidingHighlightSize.SPEED_SAMPLE) + " mph";
        float widest = tabular(sample), room = spare(widest, 84);
        float size = RidingHighlightSize.px(widest, .72f, room, 140, 84, 200);
        boolean all = size > 84;
        for (int tenths = 0; tenths <= 9999 && all; tenths++) {
            String shown = String.format(Locale.US, "%.1f mph", tenths / 10.0);
            all = tabular(shown) * size <= room + 1e-3f;
        }
        check(all, "every speed to 999.9 draws at the one size");
        String battery = RidingHighlightSize.BATTERY_SAMPLE + " %";
        float batteryRoom = spare(tabular(battery), 84);
        float batterySize = RidingHighlightSize.px(tabular(battery), .72f, batteryRoom, 140, 84, 200);
        boolean every = batterySize > 84;
        for (int percent = 0; percent <= 100 && every; percent++) every = tabular(percent + " %") * batterySize <= batteryRoom + 1e-3f;
        check(every, "every battery percentage draws at the one size");
        String ride = "10:58:58";
        check(RidingHighlightSize.STOPWATCH_SAMPLE_MS == ((10 * 60 + 58) * 60 + 58) * 1000L, "the stopwatch sample has a two-figure hour");
        float rideRoom = spare(tabular(ride), 84);
        float rideSize = RidingHighlightSize.px(tabular(ride), .72f, rideRoom, 140, 84, 200);
        boolean rides = rideSize > 84;
        for (int s = 0; s < 100 * 3600 && rides; s += 7) {
            String shown = s >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String.format(Locale.US, "%d:%02d", s / 60, s % 60);
            rides = tabular(shown) * rideSize <= rideRoom + 1e-3f;
        }
        check(rides, "every ride time under 100 hours draws at the one size");
    }
}
