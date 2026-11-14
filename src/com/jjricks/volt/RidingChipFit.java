package com.jjricks.volt;

/**
 * How many riding-mode chips a row can show, and how tight they must get to do it. A narrow row
 * gives up its decoration before it gives up a reading: first the drive mode's letter spacing, then
 * the reading names shorten (full, then abbreviated, then an icon), then the icons that only
 * decorate (battery temperature's thermometer, the clock's face) go, then some padding, and only
 * then the last chip. The rung applies to the whole row so the chips stay alike. Text is never
 * shrunk or broken: a chip shows whole or not at all.
 *
 * A chip that holds a reading whose value doesn't name itself (Average speed, Odometer and the
 * rest) always carries a name or an icon beside its value. Drive mode, battery temperature and the
 * clock name themselves ("SPORT", "31 °C", "12:48") and look as they always have.
 */
final class RidingChipFit {
    /**
     * Rungs, widest first; each keeps the one before: as designed (full names); no letter spacing;
     * short names; names as icons; no decorative icons; tight padding.
     */
    static final int FULL = 0, NO_SPACING = 1, SHORT_NAMES = 2, ICON_NAMES = 3, NO_ICONS = 4, TIGHT = 5, RUNGS = 6;
    /** A chip's name ladder: its full name, its short name, its icon. */
    static final int NAME_FULL = 0, NAME_SHORT = 1, NAME_ICON = 2;

    /** A chip's whole width at a rung and name step (-1 for a chip without names). */
    interface Widths {
        int at(int chip, int rung, int step);
    }

    /** How many chips show; a later chip is lower priority. */
    final int count;
    /** The rung the shown chips use: the widest one at which they all fit. */
    final int rung;
    /** Which chips show, by position: in a row, the first count of them. */
    final boolean[] shown;
    /** Each shown chip's name step at that rung, worked out against the chips that show; -1 for the rest. */
    final int[] steps;
    /** The chips that show their compact form (a GPS state's dash) because in full they (or, in {@link #steadyStepping}, a later chip) would step aside. */
    final boolean[] compact;

    private RidingChipFit(int count, int rung, boolean[] shown, int[] steps) {
        this(count, rung, shown, steps, new boolean[shown.length]);
    }

    private RidingChipFit(int count, int rung, boolean[] shown, int[] steps, boolean[] compact) {
        this.count = count;
        this.rung = rung;
        this.shown = shown;
        this.steps = steps;
        this.compact = compact;
    }

    /**
     * True when a chip showing its compact form must be fitted again because its full value changed. Every GPS
     * state's compact form is the same dash, so the text on screen does not change and nothing else would measure the
     * chip again: without this, "Not recording" turning to "Off" would keep the dash although the words now fit.
     */
    static boolean refitsCompact(boolean compactShown, String was, String now) {
        return compactShown && !java.util.Objects.equals(was, now);
    }

    /**
     * A row or column in which some chips have a compact form for their value: a GPS accuracy state ("Not
     * recording") whose mark is the no-reading dash. Every chip first tries as it reads in full, exactly as
     * {@link #row} or {@link #column} would fit it; a chip with a compact form that would then step aside tries again
     * in that form, so the reading the rider chose shows as a mark rather than not at all. A chip that shows in full
     * keeps its full value, and with no chip able to step down the fit is the plain one. Riding mode's steady row uses
     * {@link #steadyStepping}, which goes one step further.
     *
     * @param full each chip's widths with its value in full
     * @param compact each chip's widths with its compact value (read only for chips in canStep)
     * @param canStep the chips that have a compact form now
     */
    static RidingChipFit stepping(final String[][] marks, final String[] contents, Widths full, final Widths compact, boolean[] canStep,
                                  final boolean stacked, final int gap, final int room) {
        return stepping(marks.length, fitter(marks, contents, stacked, gap, room), full, compact, canStep, false);
    }

    /**
     * {@link #stepping}, then, if a chip still steps aside, every chip with a compact form tries it, shown in full or
     * not, and that fit is taken when more chips show. Failure mode it closes: the steady fit sizes a GPS chip from its
     * widest state ("Not recording"), so a GPS chip early in the row fitted in full and pushed a later chip out for the
     * whole ride, where its dash would have let them all show (the council on #493). With nothing gained, the fit is
     * {@link #stepping}'s. It depends on the widths alone, each chip's widest, so it never changes with the value on show.
     */
    static RidingChipFit steadyStepping(final String[][] marks, final String[] contents, Widths full, final Widths compact, boolean[] canStep,
                                        final boolean stacked, final int gap, final int room) {
        return stepping(marks.length, fitter(marks, contents, stacked, gap, room), full, compact, canStep, true);
    }

    private static Fitter fitter(final String[][] marks, final String[] contents, final boolean stacked, final int gap, final int room) {
        return w -> stacked ? column(marks, contents, w, room) : row(marks, contents, w, gap, room);
    }

    /** A fit of a row or column's chips from their widths. */
    private interface Fitter {
        RidingChipFit fit(Widths widths);
    }

    private static RidingChipFit stepping(int of, Fitter fitter, final Widths full, final Widths compact, boolean[] canStep,
                                          boolean steady) {
        RidingChipFit plain = fitter.fit(full);
        if (canStep == null) return plain;
        boolean[] dropped = new boolean[of], every = new boolean[of];
        for (int chip = 0; chip < of; chip++) {
            every[chip] = canStep[chip];
            dropped[chip] = canStep[chip] && !plain.shown[chip];
        }
        RidingChipFit fit = count(dropped) == 0 ? plain : withCompact(fitter, full, compact, dropped);
        if (steady && count(every) > count(dropped) && fit.count < of) {
            RidingChipFit all = withCompact(fitter, full, compact, every);
            if (all.count > fit.count) fit = all;
        }
        return fit;
    }

    /** The fit with the chips in use in their compact form, marked compact where they show. */
    private static RidingChipFit withCompact(Fitter fitter, final Widths full, final Widths compact, final boolean[] use) {
        RidingChipFit fit = fitter.fit((chip, rung, step) -> use[chip] ? compact.at(chip, rung, step) : full.at(chip, rung, step));
        boolean[] shownCompact = new boolean[use.length];
        for (int chip = 0; chip < use.length; chip++) shownCompact[chip] = use[chip] && fit.shown[chip];
        return new RidingChipFit(fit.count, fit.rung, fit.shown, fit.steps, shownCompact);
    }

    private static boolean[] first(int count, int of) {
        boolean[] out = new boolean[of];
        for (int i = 0; i < count && i < of; i++) out[i] = true;
        return out;
    }

    private static int count(boolean[] set) {
        int n = 0;
        for (boolean s : set) if (s) n++;
        return n;
    }

    /** Each chip's name step at a rung against the chips in set; -1 outside it. */
    static int[] steps(String[][] marks, String[] contents, boolean[] set, int rung) {
        int[] out = new int[marks.length];
        for (int chip = 0; chip < marks.length; chip++) out[chip] = set[chip] ? nameStep(marks, contents, set, chip, rung) : -1;
        return out;
    }

    /** Fixed widths with no names, [rung][chip]. */
    private static Widths fixed(final int[][] widths) {
        return new Widths() { @Override public int at(int chip, int rung, int step) { return widths[rung][chip]; } };
    }

    private static String[][] unnamed(int of) { return new String[of][]; }

    /** A row with fixed widths and no names to work out (see {@link #row}). */
    static RidingChipFit choose(int[][] widths, int gap, int room) {
        int of = widths[FULL].length;
        return row(unnamed(of), new String[of], fixed(widths), gap, room);
    }

    /**
     * Keeps as many chips as fit at any rung, and among those takes the widest rung, so a row that
     * has already lost a chip keeps its icons when the rest have room for them. Each candidate row
     * works out its names among its own chips: a chip that stepped aside no longer holds a mark, so
     * the rest can take it (and so can be narrower).
     *
     * @param marks each chip's marks (see {@link #nameStep}), in priority order
     * @param gap the space between two chips
     * @param room the row's width
     */
    static RidingChipFit row(String[][] marks, String[] contents, Widths widths, int gap, int room) {
        int of = marks.length;
        for (int count = of; count > 0; count--) {
            boolean[] set = first(count, of);
            for (int rung = FULL; rung < RUNGS; rung++) {
                int[] steps = steps(marks, contents, set, rung);
                long used = gap * (count - 1L);
                for (int chip = 0; chip < count; chip++) used += widths.at(chip, rung, steps[chip]);
                if (used <= room) return new RidingChipFit(count, rung, set, steps);
            }
        }
        return new RidingChipFit(0, FULL, first(0, of), steps(marks, contents, first(0, of), FULL));
    }

    /** A column with fixed widths and no names to work out (see {@link #column}). */
    static RidingChipFit stack(int[][] widths, int room) {
        int of = widths[FULL].length;
        return column(unnamed(of), new String[of], fixed(widths), room);
    }

    /**
     * Chips stacked in a column (Trace's, beside the big number), each as wide as room at most. In
     * priority order, a chip joins when it and the chips already in fit at a shared rung, their names
     * worked out among themselves; a chip that can't join steps aside on its own, holding no mark. They
     * take the widest rung at which they all fit.
     */
    static RidingChipFit column(String[][] marks, String[] contents, Widths widths, int room) {
        int of = marks.length;
        boolean[] set = new boolean[of];
        for (int chip = 0; chip < of; chip++) {
            set[chip] = true;
            if (widestColumnRung(marks, contents, widths, set, room) < 0) set[chip] = false;
        }
        int rung = Math.max(FULL, widestColumnRung(marks, contents, widths, set, room));
        return new RidingChipFit(count(set), rung, set, steps(marks, contents, set, rung));
    }

    private static int widestColumnRung(String[][] marks, String[] contents, Widths widths, boolean[] set, int room) {
        if (count(set) == 0) return FULL;
        for (int rung = FULL; rung < RUNGS; rung++) {
            int[] steps = steps(marks, contents, set, rung);
            boolean all = true;
            for (int chip = 0; chip < set.length && all; chip++) all = !set[chip] || widths.at(chip, rung, steps[chip]) <= room;
            if (all) return rung;
        }
        return -1;
    }

    /** The step of the name ladder a rung asks for. */
    static int nameStep(int rung) {
        return rung <= NO_SPACING ? NAME_FULL : rung == SHORT_NAMES ? NAME_SHORT : NAME_ICON;
    }

    /**
     * The name step a chip takes at a rung: the one the rung asks for, unless that step is missing or
     * another chip in the group (one showing a different reading) carries the same mark, in which case it
     * keeps a longer one. So two range readings never both show the route alone, nor
     * both show "Range". A decorative icon (the clock face, the thermometer) counts as
     * taken while it shows, so not from {@link #NO_ICONS} on.
     *
     * @param marks each chip's marks, [chip][step], null where the chip has no such step; a chip that
     *              names itself lists only its decorative icon
     * @param contents each chip's content id, so two chips showing the same reading never push each other
     * @param set the chips that show; only they hold marks (null: every chip)
     * @return the step, or -1 for a chip without names
     */
    static int nameStep(String[][] marks, String[] contents, boolean[] set, int chip, int rung) {
        String[] own = marks[chip];
        if (own == null || own.length <= NAME_FULL || own[NAME_FULL] == null) return -1;
        int step = Math.min(nameStep(rung), own.length - 1);
        while (step > NAME_FULL && (own[step] == null || taken(marks, contents, set, chip, own[step], rung))) step--;
        return step;
    }

    /** {@link #nameStep(String[][], String[], boolean[], int, int)} with every chip showing. */
    static int nameStep(String[][] marks, String[] contents, int chip, int rung) {
        return nameStep(marks, contents, null, chip, rung);
    }

    private static boolean taken(String[][] marks, String[] contents, boolean[] set, int chip, String mark, int rung) {
        for (int other = 0; other < marks.length; other++) {
            if (other == chip || marks[other] == null || (set != null && !set[other])) continue;
            if (contents[other] != null && contents[other].equals(contents[chip])) continue;
            boolean decorative = marks[other].length <= NAME_FULL || marks[other][NAME_FULL] == null;
            if (decorative && rung >= NO_ICONS) continue;
            for (String m : marks[other]) if (mark.equals(m)) return true;
        }
        return false;
    }

    /** A mark for an icon, apart from any name a translation could give. */
    static String iconMark(String key) { return key == null ? null : "\u0000icon:" + key; }

    // The steady row (a tester, Nov 14): widths come from each reading's widest value, never the value on show, and the
    // chips share the whole row, larger when there are fewer of them.

    /**
     * Text sizes for a row's chips, by how many chip slots the rider filled: three keep the size chips always had, two
     * draw a little larger, one larger still. A larger size is used only where it costs no chip and no name (see
     * {@link #sized}), so text never draws smaller than the three-chip size.
     */
    static final int SIZE_NORMAL = 0, SIZE_MEDIUM = 1, SIZE_LARGE = 2, SIZES = 3;
    /** Each size's scale of the three-chip text and icons. */
    static final float[] SIZE_SCALE = {1f, 1.2f, 1.4f};

    /** The largest size a row of this many filled chip slots may use. */
    static int largestSize(int filled) {
        return filled <= 1 ? SIZE_LARGE : filled == 2 ? SIZE_MEDIUM : SIZE_NORMAL;
    }

    /** A chip's whole width at a size, rung and name step. */
    interface SizedWidths {
        int at(int size, int chip, int rung, int step);
    }

    /** A row's fit with the size its chips draw at. */
    static final class Sized {
        final int size;
        final RidingChipFit fit;
        Sized(int size, RidingChipFit fit) { this.size = size; this.fit = fit; }
    }

    private static int compactCount(RidingChipFit fit) { return count(fit.compact); }

    private static Widths atSize(final SizedWidths widths, final int size) {
        return new Widths() { @Override public int at(int chip, int rung, int step) { return widths.at(size, chip, rung, step); } };
    }

    /**
     * A row fitted at the largest size it may use that shows as much as the three-chip size would: the same chips, at a
     * rung no tighter, and no more of them in their compact form. Otherwise the three-chip size, fitted as
     * {@link #steadyStepping} fits it. The widths are meant to be each chip's widest value, so the answer never changes while
     * the values do.
     */
    static Sized sized(String[][] marks, String[] contents, SizedWidths full, SizedWidths compact, boolean[] canStep,
                       int largest, int gap, int room) {
        RidingChipFit base = steadyStepping(marks, contents, atSize(full, SIZE_NORMAL), atSize(compact, SIZE_NORMAL), canStep, false, gap, room);
        for (int size = Math.min(largest, SIZES - 1); size > SIZE_NORMAL; size--) {
            RidingChipFit fit = steadyStepping(marks, contents, atSize(full, size), atSize(compact, size), canStep, false, gap, room);
            if (java.util.Arrays.equals(fit.shown, base.shown) && fit.rung <= base.rung && compactCount(fit) <= compactCount(base))
                return new Sized(size, fit);
        }
        return new Sized(SIZE_NORMAL, base);
    }

    /**
     * The shown chips' widths once they fill the row: each keeps its own width and takes an equal part of what's left
     * (the first ones take the odd pixels), so the row's two ends meet the tiles' edges above it. Widths that already
     * overfill the row are returned as they are.
     */
    static int[] spread(int[] widths, int gap, int room) {
        int[] out = widths.clone();
        int n = widths.length;
        if (n == 0) return out;
        long used = gap * (n - 1L);
        for (int w : widths) used += w;
        long extra = room - used;
        if (extra <= 0 || room == Integer.MAX_VALUE) return out;
        long each = extra / n, odd = extra % n;
        for (int i = 0; i < n; i++) out[i] += (int) (each + (i < odd ? 1 : 0));
        return out;
    }

    /** How many chips of one height stack in a column this tall, with gaps between them. */
    static int stackable(int chipHeight, int gap, int height) {
        if (chipHeight <= 0) return Integer.MAX_VALUE;
        if (height < chipHeight) return 0;
        return 1 + (height - chipHeight) / Math.max(1, chipHeight + gap);
    }

    /**
     * {@link #column} with at most limit chips: a column too short for every chip hides the lowest priority ones, always
     * the same ones for the same column, before the widths are worked out.
     */
    static RidingChipFit column(String[][] marks, String[] contents, Widths widths, int room, int limit) {
        int of = marks.length;
        boolean[] set = new boolean[of];
        for (int chip = 0; chip < of; chip++) {
            if (count(set) >= limit) break;
            set[chip] = true;
            if (widestColumnRung(marks, contents, widths, set, room) < 0) set[chip] = false;
        }
        int rung = Math.max(FULL, widestColumnRung(marks, contents, widths, set, room));
        return new RidingChipFit(count(set), rung, set, steps(marks, contents, set, rung));
    }

    /** {@link #steadyStepping} for a column of at most limit chips (see {@link #column(String[][], String[], Widths, int, int)}). */
    static RidingChipFit steppingColumn(final String[][] marks, final String[] contents, Widths full, final Widths compact, boolean[] canStep,
                                        final int room, final int limit) {
        return stepping(marks.length, w -> column(marks, contents, w, room, limit), full, compact, canStep, true);
    }

    // The samples a chip is sized from, by the form it's in, and the values it must keep beyond them (Chip.seeWide).

    /** A chip's forms: in full, at the tightest rung (the clock without its AM/PM), and in its compact form (GPS). */
    static final int FORM_FULL = 0, FORM_TIGHT = 1, FORM_COMPACT = 2, FORMS = 3;

    /** The form a chip is sized in: its planned compact form, else by its rung. */
    static int form(int rung, boolean compactPlanned) {
        return compactPlanned ? FORM_COMPACT : rung == TIGHT ? FORM_TIGHT : FORM_FULL;
    }

    /**
     * The text a chip draws whatever its width, in a form: at the tightest rung its tight text where it has one; in its
     * compact form a value without a compact form of its own (a GPS fix's figure). A value with one (a GPS state) is
     * null there: its words show only where they fit, else its compact form, so it never draws past the chip.
     */
    static String drawn(int form, String full, String tight, String compact) {
        if (form == FORM_COMPACT) return compact == null ? full : null;
        if (form == FORM_TIGHT && tight != null) return tight;
        return full;
    }

    /**
     * True when a value drawn in a form is wider than every sample of that form, so the chip must keep it with them and be
     * fitted again, once. Measured against the form the chip is sized in, never another: a ±1000 ft fix in a compact GPS
     * chip (sized from ±888 ft) is narrower than "Not recording" and was never kept, so it drew past the chip's edge (the
     * council on #493).
     */
    static boolean widens(float drawnWidth, int form, float[] widestByForm) {
        return widestByForm != null && form >= 0 && form < widestByForm.length && drawnWidth > widestByForm[form];
    }

    // Usual room (the owner, Nov 14): four readings are sized from their usual values, not their widest, so a row keeps the
    // chips it had before the steady fit. A value past that room widens its chip once, for the rest of the ride (Reserve).

    /** Battery temperature's usual range, °C, shown in the rider's unit: -9 °C is 16 °F and 48 °C is 118 °F, three figures. */
    static final double USUAL_PACK_LOW_C = -9, USUAL_PACK_HIGH_C = 48;
    /**
     * Efficiency's, Volt's range's and GPS accuracy's usual figure: two, in whatever unit shows (-88 Wh/mi or Wh/km, ≈ 88 mi
     * or km, ±88 ft; ±88 ft is 26.8 m, so two figures in metres too).
     */
    static final int USUAL_TWO_FIGURES = 88;

    /** A text's drawn width (a chip's value at the three-chip size). */
    interface Measure {
        float width(String text);
    }

    /**
     * What a chip is sized from, by form, and what it keeps beyond that for the rest of the ride. A reading with usual
     * samples (see above) uses them until a value is wider than them; then it takes its strict samples (each a supported
     * scooter's widest value, as for every other reading), once, and never goes back. A value wider than those is kept
     * too, in its widened form (every figure the widest), so a chip widens at most once for each count of figures:
     * ±1188 ft and then ±1888 ft widen it once, not twice (the council on #493). Words ("Almost empty") have no figures and
     * are kept as they are. Nothing kept is ever dropped, so a chip never shrinks back and the fit can't flicker.
     */
    static final class Reserve {
        private boolean pastUsual;
        private final java.util.List<String> kept = new java.util.ArrayList<>();
        private String[][] measuredFor;
        private float[] measuredWidest;

        /** True once a value went past this reading's usual samples. */
        boolean pastUsual() { return pastUsual; }

        /** The values kept beyond the samples, in the order they came. */
        java.util.List<String> kept() { return java.util.Collections.unmodifiableList(kept); }

        /**
         * The samples to size the chip from, [form][]: its usual ones (when it has some and no value went past them), else
         * its strict ones with the usual ones (so nothing gets narrower), then every value kept.
         *
         * @param usual the usual samples [form][], or null for a reading sized from its widest values alone
         * @param strict the widest values [form][]
         */
        String[][] samples(String[][] usual, String[][] strict) {
            String[][] out = new String[FORMS][];
            for (int form = 0; form < FORMS; form++) {
                java.util.List<String> all = new java.util.ArrayList<>();
                if (usual == null || pastUsual) java.util.Collections.addAll(all, strict[form]);
                if (usual != null) java.util.Collections.addAll(all, usual[form]);
                all.addAll(kept);
                out[form] = all.toArray(new String[0]);
            }
            return out;
        }

        /** The widest of each form's samples, measured again only when the samples change. */
        float[] widest(String[][] samples, Measure measure) {
            if (measuredWidest != null && java.util.Arrays.deepEquals(samples, measuredFor)) return measuredWidest;
            float[] out = new float[FORMS];
            for (int form = 0; form < FORMS; form++)
                for (String v : samples[form]) out[form] = Math.max(out[form], measure.width(v));
            measuredFor = samples;
            measuredWidest = out;
            return out;
        }

        /**
         * Sees the text the chip would draw in each form ({@link #drawn}; null where a form has nothing to measure).
         * Every form is checked, not only the one the chip is sized in now: before its first fit a chip doesn't know it
         * will be planned compact, and a ±1000 ft fix measured against "Not recording" alone was never kept, so it drew
         * past the compact chip's edge until the next value (the council on #493). Keeping a value for a form the chip
         * isn't in costs nothing there, as a form's width is its widest sample.
         *
         * @return true when the samples changed: the chip must be fitted again, once
         */
        boolean see(String[] drawn, String[][] usual, String[][] strict, Measure measure, String digits, int widestDigit) {
            if (!wider(drawn, samples(usual, strict), measure)) return false;
            if (usual != null) pastUsual = true;
            for (int form = 0; form < FORMS; form++) {
                String value = drawn[form];
                if (value == null || !widens(measure.width(value), form, widest(samples(usual, strict), measure))) continue;
                String wide = widened(value, digits, widestDigit);
                // The widened form, unless the font's widest figure was measured a hair narrow: never keep less than the value.
                String keep = measure.width(wide) >= measure.width(value) ? wide : value;
                if (!kept.contains(keep)) kept.add(keep);
            }
            return true;
        }

        private boolean wider(String[] drawn, String[][] samples, Measure measure) {
            float[] widest = widest(samples, measure);
            for (int form = 0; form < FORMS && form < drawn.length; form++)
                if (drawn[form] != null && widens(measure.width(drawn[form]), form, widest)) return true;
            return false;
        }
    }

    /**
     * Trace's column room: base (its share of the row, or a self-naming chip's widest), widened once for each chip whose
     * widest values fitted base (fitted[i], its tightest width from them) but which has since kept a value past them
     * (kept[i], its tightest width with that value), up to size. A usual-room reading going past its usual values changes
     * neither width, so never the room; a chip whose widest values never fitted (Max speed's 888.8 mph) still follows
     * its value (fitColumn), as before.
     */
    static int columnRoom(int base, int size, int[] fitted, int[] kept) {
        int room = base;
        for (int i = 0; i < fitted.length; i++)
            if (kept[i] > fitted[i] && fitted[i] <= base) room = Math.max(room, Math.min(size, kept[i]));
        return room;
    }

    /** The text a chip draws in each form ({@link #drawn}), for {@link Reserve#see}. */
    static String[] drawnByForm(String full, String tight, String compact) {
        String[] out = new String[FORMS];
        for (int form = 0; form < FORMS; form++) out[form] = drawn(form, full, tight, compact);
        return out;
    }

    /**
     * A chip's no-reading text: the dash in place of the number, with the unit the reading's values carry where the
     * language puts it. The unit is the text after the number, as written ("— Wh/mi", "—%" in English, "— %" in French);
     * with nothing after it, the text before it when that's a unit (Turkish writes "%72", so "%—"), never a sign of its own
     * ("≈", "±"). A value with no text around its number (a clock, whose AM/PM is in its text; a stopwatch) is the dash
     * alone.
     *
     * @param prefix the text before a sample value's number
     * @param suffix the text after it
     */
    static String emptyValue(String dash, CharSequence prefix, CharSequence suffix) {
        String after = suffix == null ? "" : suffix.toString(), before = prefix == null ? "" : prefix.toString();
        if (!after.trim().isEmpty()) return dash + after;
        if (unitLike(before)) return before + dash;
        return dash;
    }

    /**
     * True for text that holds a letter or a percent-like mark (%, ‰, the Arabic percent sign: Unicode's "other
     * punctuation"): a unit. The signs a number can carry ("≈", "±", "+", "~", a minus) are math symbols or dashes.
     */
    private static boolean unitLike(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetter(c) || Character.getType(c) == Character.OTHER_PUNCTUATION) return true;
        }
        return false;
    }

    /**
     * A reading's text with every figure set to the font's widest one, so its width is the most that many figures can
     * take: "12.3 km/h" becomes "88.8 km/h" where 8 is the widest. digits holds the language's ten figures, 0 to 9, one
     * character each; widest is the index of the widest. Other characters stay.
     */
    static String widened(CharSequence text, String digits, int widest) {
        if (text == null) return null;
        if (digits == null || digits.length() != 10 || widest < 0 || widest > 9) return text.toString();
        char wide = digits.charAt(widest);
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            out.append(digits.indexOf(c) >= 0 ? wide : c);
        }
        return out.toString();
    }
}
