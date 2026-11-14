package com.jjricks.volt;

import java.util.Locale;

/**
 * Riding mode's usual room (the owner, Nov 14): efficiency, battery temperature, Volt's range and GPS accuracy size their
 * chips from their usual values, and a value past them widens the chip once, for the rest of the ride
 * (RidingChipFit.Reserve). Every other reading keeps its widest values, as #493 made it. Also the two P3s from #493's
 * second council round: a kept value is kept widened (so ±1188 ft, then ±1888 ft, widen once), and a value is checked
 * in every form, so a fix wider than a compact chip's samples is kept before the chip's first fit.
 */
public final class RidingChipWidenTest {
    private static int checks;
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }

    private static final String DIGITS = "0123456789";
    private static final int WIDEST = 8;

    /** A made-up font: 8 is the widest figure, 1 the narrowest; words are wider than figures. */
    private static float width(String text) {
        float w = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '8') w += 10;
            else if (c == '1') w += 6;
            else if (c >= '0' && c <= '9') w += 9;
            else if (c == ' ' || c == ' ' || c == ' ') w += 3;
            else if (c == '-' || c == '−' || c == '.' || c == ':' || c == '/') w += 5;
            else if (c == '—') w += 12;
            else if (Character.isUpperCase(c)) w += 11;
            else w += 8;
        }
        return w;
    }

    private static final RidingChipFit.Measure MEASURE = RidingChipWidenTest::width;

    public static void main(String[] args) {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(Locale.US);
            T.install(null);
            whichReadings();
            usualValues();
            gpsWidensOnce();
            compactFixBeforeFirstFit();
            almostEmpty();
            efficiencyRegen();
            packTemperature();
            strictReadings();
            neverShrinks();
            traceColumnFromWidest();
            rowHidesTheLast();
            sourceWiring();
        } finally {
            T.install(null);
            Locale.setDefault(saved);
        }
        System.out.println("RidingChipWidenTest: " + checks + " checks passed (the four usual-room readings, usual values, "
                + "GPS widens once, a compact fix before the first fit, Almost empty, regen, a hot pack, strict readings, "
                + "never shrinks, Trace's column from the widest values, the last chip hides, wiring)");
    }

    /** Samples by form: in full and on the tightest rung the same, in compact form each its compact text (or itself). */
    private static String[][] forms(String[] full, String[] compact) {
        String[][] out = new String[RidingChipFit.FORMS][];
        out[RidingChipFit.FORM_FULL] = full.clone();
        out[RidingChipFit.FORM_TIGHT] = full.clone();
        out[RidingChipFit.FORM_COMPACT] = (compact == null ? full : compact).clone();
        return out;
    }

    private static String wide(String text) { return RidingChipFit.widened(text, DIGITS, WIDEST); }

    private static boolean see(RidingChipFit.Reserve reserve, String value, String compact, String[][] usual, String[][] strict) {
        return reserve.see(RidingChipFit.drawnByForm(value, null, compact), usual, strict, MEASURE, DIGITS, WIDEST);
    }

    /** True when a value draws inside its chip in every form it can be drawn in. */
    private static boolean inside(RidingChipFit.Reserve reserve, String value, String compact, String[][] usual, String[][] strict) {
        float[] widest = reserve.widest(reserve.samples(usual, strict), MEASURE);
        String[] drawn = RidingChipFit.drawnByForm(value, null, compact);
        for (int form = 0; form < RidingChipFit.FORMS; form++)
            if (drawn[form] != null && width(drawn[form]) > widest[form]) return false;
        return true;
    }

    private static void whichReadings() {
        String[] usual = {RidingLayout.EFFICIENCY, RidingLayout.BATTERY_TEMPERATURE, RidingLayout.RANGE_VOLT, RidingLayout.GPS_ACCURACY};
        for (String content : usual) check(RidingLayout.chipHasUsualRoom(content), content + " has usual room (the owner, Nov 14)");
        String[] strict = {RidingLayout.SPEED, RidingLayout.POWER, RidingLayout.BATTERY, RidingLayout.TRIP, RidingLayout.ODOMETER,
                RidingLayout.RIDE_TIME, RidingLayout.RECORDING_TIME, RidingLayout.AVERAGE_SPEED, RidingLayout.MAX_SPEED,
                RidingLayout.RIDE_ENERGY, RidingLayout.VOLTAGE, RidingLayout.CURRENT, RidingLayout.DRIVE_MODE, RidingLayout.CLOCK,
                RidingLayout.RANGE_AT_PACE, RidingLayout.RANGE_SCOOTER, RidingLayout.NONE, null};
        for (String content : strict) check(!RidingLayout.chipHasUsualRoom(content), content + " keeps its widest values");
    }

    private static void usualValues() {
        check(RidingChipFit.USUAL_TWO_FIGURES == 88, "two figures, every one the widest");
        // ±88 ft is 26.8 m: two figures in metres too.
        long metres = GpsAccuracy.whole(GpsAccuracy.shown(RidingChipFit.USUAL_TWO_FIGURES / GpsAccuracy.FEET_PER_METRE, false));
        check(String.valueOf(metres).length() == 2, "±88 ft in metres has two figures: " + metres);
        String gps = GpsAccuracyText.value(RidingChipFit.USUAL_TWO_FIGURES, GpsAccuracy.unit(true)).toString();
        check(gps.startsWith("±88") && figures(gps) == 2, "GPS's usual sample: " + gps);
        // The pack's usual range, in each unit: two figures in °C, three in °F (48 °C is 118 °F), from the conversion.
        String lowC = DisplayTemperature.quantity(RidingChipFit.USUAL_PACK_LOW_C, "c", 0).toString();
        String highC = DisplayTemperature.quantity(RidingChipFit.USUAL_PACK_HIGH_C, "c", 0).toString();
        String lowF = DisplayTemperature.quantity(RidingChipFit.USUAL_PACK_LOW_C, "f", 0).toString();
        String highF = DisplayTemperature.quantity(RidingChipFit.USUAL_PACK_HIGH_C, "f", 0).toString();
        check(lowC.contains("9") && figures(lowC) == 1 && highC.startsWith("48") && figures(highC) == 2, "°C: " + lowC + " to " + highC);
        check(lowF.startsWith("16") && figures(lowF) == 2 && highF.startsWith("118") && figures(highF) == 3, "°F: " + lowF + " to " + highF);
    }

    private static int figures(String text) {
        int n = 0;
        for (int i = 0; i < text.length(); i++) if (DIGITS.indexOf(text.charAt(i)) >= 0) n++;
        return n;
    }

    /** GPS accuracy, feet: usual ±88 ft; widest ±888 ft and the states. */
    private static String[][] gpsUsual() {
        return forms(new String[]{"±88 ft", "Not recording", "Off", "No fix", "—"}, new String[]{"±88 ft", "—", "—", "—", "—"});
    }

    private static String[][] gpsStrict() {
        return forms(new String[]{"±888 ft", "Not recording", "Off", "No fix", "—"}, new String[]{"±888 ft", "—", "—", "—", "—"});
    }

    private static void gpsWidensOnce() {
        RidingChipFit.Reserve reserve = new RidingChipFit.Reserve();
        String[][] usual = gpsUsual(), strict = gpsStrict();
        check(java.util.Arrays.deepEquals(reserve.samples(usual, strict), usual), "a GPS chip starts at its usual samples");
        for (String fix : new String[]{"±3 ft", "±12 ft", "±45 ft", "±88 ft"})
            check(!see(reserve, fix, null, usual, strict) && !reserve.pastUsual(), "a usual fix widens nothing: " + fix);
        check(!see(reserve, "Not recording", "—", usual, strict), "a state is within the samples");
        // ±1000 ft: past the usual room, so the chip takes its widest samples, and past those too, so it keeps ±8888 ft.
        check(see(reserve, "±1000 ft", null, usual, strict) && reserve.pastUsual(), "±1000 ft widens the chip");
        check(reserve.kept().equals(java.util.Collections.singletonList("±8888 ft")), "kept widened, not as shown: " + reserve.kept());
        check(inside(reserve, "±1000 ft", null, usual, strict), "±1000 ft draws inside its chip");
        // P3 1 (the council on #493): the next fixes with as many figures widen nothing more.
        for (String fix : new String[]{"±1188 ft", "±1888 ft", "±9999 ft", "±250 ft", "±12 ft", "±1000 ft"})
            check(!see(reserve, fix, null, usual, strict), "widens once, so not again for " + fix);
        // A fifth figure is wider than anything kept: one more widening, for that count of figures.
        check(see(reserve, "±12000 ft", null, usual, strict) && reserve.kept().size() == 2, "a fifth figure widens once more");
        check(!see(reserve, "±18888 ft", null, usual, strict), "and only once");

        // The same order with ±1188 ft first: a strict-only reading too keeps the widened form.
        RidingChipFit.Reserve odometer = new RidingChipFit.Reserve();
        String[][] odo = forms(new String[]{"88888.8 km", "—"}, null);
        check(see(odometer, "234567.8 km", null, null, odo), "an odometer past its widest widens");
        check(odometer.kept().equals(java.util.Collections.singletonList("888888.8 km")), "kept with every figure the widest");
        check(!see(odometer, "298765.4 km", null, null, odo), "the next six-figure odometer fits");
    }

    /**
     * P3 2 (the council on #493): before a GPS chip's first fit it doesn't know it will be planned compact; a ±1000 ft fix
     * measured only against its full samples ("Not recording") was never kept, and drew past the compact chip.
     */
    private static void compactFixBeforeFirstFit() {
        String[][] full = forms(new String[]{"±888 ft", "Not recording", "—"}, new String[]{"±888 ft", "—", "—"});
        RidingChipFit.Reserve reserve = new RidingChipFit.Reserve();
        float[] before = reserve.widest(reserve.samples(null, full), MEASURE);
        float fix = width("±1000 ft");
        check(!RidingChipFit.widens(fix, RidingChipFit.FORM_FULL, before), "setup: in full the fix is within \"Not recording\"");
        check(RidingChipFit.widens(fix, RidingChipFit.FORM_COMPACT, before), "setup: in compact form it isn't");
        check(see(reserve, "±1000 ft", null, null, full), "a fix past the compact samples is kept whatever form the chip is in now");
        check(inside(reserve, "±1000 ft", null, null, full), "so a compact chip draws it inside");
        float[] after = reserve.widest(reserve.samples(null, full), MEASURE);
        check(after[RidingChipFit.FORM_FULL] == before[RidingChipFit.FORM_FULL], "and the full form is no wider for it");
        // A state draws its words only where they fit (else its dash): never measured in compact form.
        check(RidingChipFit.drawnByForm("Not recording", null, "—")[RidingChipFit.FORM_COMPACT] == null, "a state's compact form isn't measured");
        check("10:58:58".equals(RidingChipFit.drawnByForm("10:58:58 PM", "10:58:58", null)[RidingChipFit.FORM_TIGHT]), "the tight rung draws the tight text");
    }

    private static void almostEmpty() {
        RidingChipFit.Reserve reserve = new RidingChipFit.Reserve();
        String[][] usual = forms(new String[]{"≈ 88 mi", "—"}, null);
        String[][] strict = forms(new String[]{"≈ 888 mi", "Almost empty", "—"}, null);
        check(!see(reserve, "≈ 23 mi", null, usual, strict) && !see(reserve, "≈ 88 mi", null, usual, strict), "two figures fit the usual room");
        check(see(reserve, "Almost empty", null, usual, strict) && reserve.pastUsual(), "Almost empty widens Volt's range");
        check(reserve.kept().isEmpty(), "its widest samples hold the words, so nothing else is kept");
        check(inside(reserve, "Almost empty", null, usual, strict), "Almost empty draws inside its chip");
        check(!see(reserve, "≈ 120 mi", null, usual, strict) && !see(reserve, "Almost empty", null, usual, strict), "once wide, it stays and widens no more");
        // The other way round: a three-figure range first, then Almost empty, still one widening.
        RidingChipFit.Reserve other = new RidingChipFit.Reserve();
        check(see(other, "≈ 120 mi", null, usual, strict), "a three-figure range widens it");
        check(!see(other, "Almost empty", null, usual, strict), "and Almost empty then fits");
        // Words are kept as they are when even the widest samples don't hold them.
        RidingChipFit.Reserve words = new RidingChipFit.Reserve();
        check(see(words, "Almost empty", null, usual, forms(new String[]{"≈ 888 mi", "—"}, null))
                && words.kept().equals(java.util.Collections.singletonList("Almost empty")), "words are kept as they are");
    }

    private static void efficiencyRegen() {
        RidingChipFit.Reserve reserve = new RidingChipFit.Reserve();
        String[][] usual = forms(new String[]{"-88 Wh/mi", "—"}, null);
        String[][] strict = forms(new String[]{"-888 Wh/mi", "—"}, null);
        for (String v : new String[]{"18 Wh/mi", "42 Wh/mi", "-31 Wh/mi", "88 Wh/mi"}) check(!see(reserve, v, null, usual, strict), "usual: " + v);
        check(see(reserve, "-120 Wh/mi", null, usual, strict) && reserve.pastUsual() && reserve.kept().isEmpty(), "-120 Wh/mi widens to the widest samples");
        check(inside(reserve, "-120 Wh/mi", null, usual, strict), "-120 Wh/mi draws inside its chip");
        for (String v : new String[]{"-150 Wh/mi", "-999 Wh/mi", "310 Wh/mi"}) check(!see(reserve, v, null, usual, strict), "once: " + v);
    }

    private static void packTemperature() {
        for (String unit : new String[]{"c", "f"}) {
            String[] usualTexts = {wide(DisplayTemperature.quantity(RidingChipFit.USUAL_PACK_LOW_C, unit, 0).toString()),
                    wide(DisplayTemperature.quantity(RidingChipFit.USUAL_PACK_HIGH_C, unit, 0).toString()), "—"};
            String[] strictTexts = {wide(DisplayTemperature.quantity(-88.0, unit, 0).toString()),
                    wide(DisplayTemperature.quantity(88.0, unit, 0).toString()), "—"};
            String[][] usual = forms(usualTexts, null), strict = forms(strictTexts, null);
            RidingChipFit.Reserve reserve = new RidingChipFit.Reserve();
            for (double c : new double[]{-9, 0, 21, 35, 48})
                check(!see(reserve, DisplayTemperature.quantity(c, unit, 0).toString(), null, usual, strict), unit + ": usual " + c + " °C");
            // A 55 °C pack: still within the usual room's figures (55 °C, 131 °F), so it draws inside without widening.
            String hot = DisplayTemperature.quantity(55.0, unit, 0).toString();
            see(reserve, hot, null, usual, strict);
            check(inside(reserve, hot, null, usual, strict), unit + ": a 55 °C pack draws inside its chip: " + hot);
            // A cold one past the usual room widens once in °C ("-25 °C": a sign and two figures); in °F it is -13 °F,
            // within the usual room's three figures.
            String cold = DisplayTemperature.quantity(-25.0, unit, 0).toString();
            boolean celsius = "c".equals(unit);
            check(see(reserve, cold, null, usual, strict) == celsius && reserve.pastUsual() == celsius, unit + ": " + cold + " widens the chip only in °C");
            check(inside(reserve, cold, null, usual, strict) && inside(reserve, hot, null, usual, strict), unit + ": both draw inside");
            check(!see(reserve, DisplayTemperature.quantity(-30.0, unit, 0).toString(), null, usual, strict), unit + ": once");
        }
    }

    /** A reading without usual room is sized from its widest values alone, as #493 made it. */
    private static void strictReadings() {
        String[][] clock = forms(new String[]{"88:88 PM", "—"}, null);
        RidingChipFit.Reserve reserve = new RidingChipFit.Reserve();
        check(java.util.Arrays.deepEquals(reserve.samples(null, clock), clock), "a strict reading's samples are its widest values, unchanged");
        for (String t : new String[]{"12:48 PM", "10:58 AM", "1:05 PM"}) check(!see(reserve, t, null, null, clock), "the clock never widens: " + t);
        check(!reserve.pastUsual(), "a strict reading has no usual room to go past");
    }

    /** Whatever values come, a chip's widths only grow, and each count of figures widens it at most once. */
    private static void neverShrinks() {
        java.util.Random random = new java.util.Random(493);
        String[][] usual = gpsUsual(), strict = gpsStrict();
        for (int run = 0; run < 200; run++) {
            RidingChipFit.Reserve reserve = new RidingChipFit.Reserve();
            float[] last = reserve.widest(reserve.samples(usual, strict), MEASURE).clone();
            int widenings = 0;
            for (int i = 0; i < 60; i++) {
                int pick = random.nextInt(10);
                String value = pick == 0 ? "Not recording" : "±" + (long) Math.pow(10, random.nextInt(5)) * (1 + random.nextInt(9)) + " ft";
                if (see(reserve, value, pick == 0 ? "—" : null, usual, strict)) widenings++;
                check(inside(reserve, value, pick == 0 ? "—" : null, usual, strict), "drawn inside: " + value);
                float[] now = reserve.widest(reserve.samples(usual, strict), MEASURE);
                for (int form = 0; form < RidingChipFit.FORMS; form++) check(now[form] >= last[form], "a chip never shrinks back");
                last = now.clone();
                // The same value again changes nothing: a fit after widening finds nothing more to widen.
                check(!see(reserve, value, pick == 0 ? "—" : null, usual, strict), "seeing the same value again widens nothing");
            }
            // Up to five figures here: the usual room's two, then at most one widening each for three, four and five.
            check(widenings <= 3, "at most one widening per count of figures: " + widenings);
        }
    }

    /**
     * Trace's column fits and takes its room from the widest values (Reserve.samples with no usual room, as #493 sized every
     * chip), never from the usual ones (the council on #500). So whatever a usual-room reading shows within its widest
     * values, widened or not, the column's samples stay the widest values: its room and width never depend on a widening,
     * and a chip that fitted the column before widening fits it after, never becoming a follower. Random sequences of GPS
     * fixes and pack temperatures within those values widen each chip at most once and never shrink it.
     */
    private static void traceColumnFromWidest() {
        java.util.Random random = new java.util.Random(500);
        String[][] gpsUsual = gpsUsual(), gpsStrict = gpsStrict();
        for (String unit : new String[]{"c", "f"}) {
            String[][] tempUsual = forms(new String[]{wide(DisplayTemperature.quantity(RidingChipFit.USUAL_PACK_LOW_C, unit, 0).toString()),
                    wide(DisplayTemperature.quantity(RidingChipFit.USUAL_PACK_HIGH_C, unit, 0).toString()), "—"}, null);
            String[][] tempStrict = forms(new String[]{wide(DisplayTemperature.quantity(-88.0, unit, 0).toString()),
                    wide(DisplayTemperature.quantity(88.0, unit, 0).toString()), "—"}, null);
            for (int run = 0; run < 200; run++) {
                RidingChipFit.Reserve gps = new RidingChipFit.Reserve(), temp = new RidingChipFit.Reserve();
                float[] gpsColumn = gps.widest(gps.samples(null, gpsStrict), MEASURE).clone();
                float[] tempColumn = temp.widest(temp.samples(null, tempStrict), MEASURE).clone();
                float[] gpsLast = gps.widest(gps.samples(gpsUsual, gpsStrict), MEASURE).clone();
                float[] tempLast = temp.widest(temp.samples(tempUsual, tempStrict), MEASURE).clone();
                int gpsWidenings = 0, tempWidenings = 0;
                for (int i = 0; i < 40; i++) {
                    // A fix of one to three figures (±150 ft widens a chip from its usual ±88 ft to its widest ±888 ft).
                    int pick = random.nextInt(8);
                    String fix = pick == 0 ? "Not recording" : "±" + (1 + random.nextInt(999)) + " ft";
                    if (see(gps, fix, pick == 0 ? "—" : null, gpsUsual, gpsStrict)) gpsWidenings++;
                    check(inside(gps, fix, pick == 0 ? "—" : null, gpsUsual, gpsStrict), "a fix draws inside: " + fix);
                    // A pack from -88 to 88 °C, in the unit shown.
                    String pack = DisplayTemperature.quantity((double) (random.nextInt(177) - 88), unit, 0).toString();
                    if (see(temp, pack, null, tempUsual, tempStrict)) tempWidenings++;
                    check(inside(temp, pack, null, tempUsual, tempStrict), unit + ": a pack draws inside: " + pack);
                    check(java.util.Arrays.deepEquals(gps.samples(null, gpsStrict), gpsStrict)
                            && java.util.Arrays.deepEquals(temp.samples(null, tempStrict), tempStrict),
                            "the column's samples are the widest values, widened or not");
                    check(java.util.Arrays.equals(gps.widest(gps.samples(null, gpsStrict), MEASURE), gpsColumn)
                            && java.util.Arrays.equals(temp.widest(temp.samples(null, tempStrict), MEASURE), tempColumn),
                            "the column's room and widths never depend on a widening");
                    float[] gpsNow = gps.widest(gps.samples(gpsUsual, gpsStrict), MEASURE);
                    float[] tempNow = temp.widest(temp.samples(tempUsual, tempStrict), MEASURE);
                    for (int form = 0; form < RidingChipFit.FORMS; form++) {
                        check(gpsNow[form] >= gpsLast[form] && tempNow[form] >= tempLast[form], "a chip never shrinks");
                        // Its own width never passes the column's, so the widened chip fits wherever it fitted before.
                        check(gpsNow[form] <= gpsColumn[form] && tempNow[form] <= tempColumn[form], "a chip's width stays within its widest");
                    }
                    gpsLast = gpsNow.clone();
                    tempLast = tempNow.clone();
                }
                check(gpsWidenings <= 1 && tempWidenings <= 1, unit + ": at most one widening per reading: " + gpsWidenings + ", " + tempWidenings);
            }
        }
        // The column's room: from the widest values, so a widening within them never changes it.
        int base = 99, size = 234;
        check(RidingChipFit.columnRoom(base, size, new int[]{99, 62}, new int[]{99, 62}) == base,
                "a GPS chip widened to its widest (±888 ft) fits the room it fitted before: the room stays");
        check(RidingChipFit.columnRoom(base, size, new int[]{99, 62}, new int[]{111, 62}) == 111,
                "a fix past even the widest (±1000 ft, kept as ±8888 ft) widens the column once to hold it, no follower");
        check(RidingChipFit.columnRoom(base, size, new int[]{99, 62}, new int[]{300, 62}) == size, "never past the row");
        check(RidingChipFit.columnRoom(base, size, new int[]{140, 62}, new int[]{150, 62}) == base,
                "a chip whose widest never fitted (Max speed) leaves the room alone: it follows its value, as before");
        // With the fake font: the GPS chip's tightest widths, from its widest values and with a ±1000 ft fix kept.
        RidingChipFit.Reserve gps = new RidingChipFit.Reserve();
        String[][] usual = gpsUsual(), strict = gpsStrict();
        int fitted = Math.round(gps.widest(gps.samples(null, strict), MEASURE)[RidingChipFit.FORM_COMPACT]);
        see(gps, "±150 ft", null, usual, strict);
        int afterWidest = Math.round(gps.widest(gps.samples(null, strict), MEASURE)[RidingChipFit.FORM_COMPACT]);
        check(afterWidest == fitted && RidingChipFit.columnRoom(fitted, size, new int[]{fitted}, new int[]{afterWidest}) == fitted,
                "±150 ft: the room is the one before widening");
        see(gps, "±1000 ft", null, usual, strict);
        int afterKept = Math.round(gps.widest(gps.samples(null, strict), MEASURE)[RidingChipFit.FORM_COMPACT]);
        int room = RidingChipFit.columnRoom(fitted, size, new int[]{fitted}, new int[]{afterKept});
        check(afterKept > fitted && room == afterKept, "±1000 ft: the room grows once to the kept width");
        see(gps, "±1888 ft", null, usual, strict);
        check(RidingChipFit.columnRoom(fitted, size, new int[]{fitted},
                new int[]{Math.round(gps.widest(gps.samples(null, strict), MEASURE)[RidingChipFit.FORM_COMPACT])}) == room,
                "another four-figure fix: the same room, no second move");
    }

    /** Fixed widths [rung][chip], the same whatever the name step and size. */
    private static RidingChipFit.SizedWidths fixed(final int... widths) {
        return (size, chip, rung, step) -> Math.round(widths[chip] * RidingChipFit.SIZE_SCALE[size]);
    }

    /** A middle chip that widens hides the last chip in slot order, never itself or another middle one, and it settles. */
    private static void rowHidesTheLast() {
        String[][] marks = {null, null, null, null};
        String[] contents = {RidingLayout.DRIVE_MODE, RidingLayout.EFFICIENCY, RidingLayout.BATTERY_TEMPERATURE, RidingLayout.CLOCK};
        // 80 + 70 + 60 + 70 + 3 * 8 = 304 fits 310; the efficiency chip widening to 120 overfills it.
        RidingChipFit.Sized before = RidingChipFit.sized(marks, contents, fixed(80, 70, 60, 70), fixed(80, 70, 60, 70), null, 3, 8, 310);
        check(before.fit.count == 4, "before: every chip shows");
        RidingChipFit.Sized after = RidingChipFit.sized(marks, contents, fixed(80, 120, 60, 70), fixed(80, 120, 60, 70), null, 3, 8, 310);
        check(after.fit.count == 3 && after.fit.shown[0] && after.fit.shown[1] && after.fit.shown[2] && !after.fit.shown[3],
                "after: the last chip hides, the widened one stays");
        RidingChipFit.Sized again = RidingChipFit.sized(marks, contents, fixed(80, 120, 60, 70), fixed(80, 120, 60, 70), null, 3, 8, 310);
        check(java.util.Arrays.equals(again.fit.shown, after.fit.shown) && again.size == after.size, "the same widths fit the same way: no flicker");
    }

    private static String source(String path) {
        try {
            return new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new AssertionError("can't read " + path, e);
        }
    }

    /** RidingScreen asks for usual samples only for a chip, and the highlight keeps its own (Halo's growth is untouched). */
    private static void sourceWiring() {
        String screen = source("src/com/jjricks/volt/RidingScreen.java");
        check(screen.contains("return samples(content, units, chip, false);"), "the three-argument samples (the highlight's) has no usual room");
        check(screen.contains("usual = usual && chip && RidingLayout.chipHasUsualRoom(content);"), "usual room only for a chip of the four readings");
        check(screen.contains("usualForms = RidingLayout.chipHasUsualRoom(content) ? forms(samples(content, units, true, true)) : null;"),
                "a chip takes its usual samples only for the four readings");
        check(screen.contains("for (Shown s : samples(content, units, false))"), "the highlight's widest values are the full ones");
        // Trace's column: its room, its fit and its width from each chip's widest values (traceColumnFromWidest).
        check(screen.contains("String[][] widest = reserve.samples(null, strictForms);"), "a chip's widest samples ignore its usual room");
        check(screen.contains("room = Math.max(room, Math.min(size, chip.widestWidthAt(RidingChipFit.SIZE_NORMAL, RidingChipFit.FULL, -1, false)));"),
                "the column's room comes from the self-naming chips' widest values");
        check(screen.contains("&& chip.widestWidthAt(normal, RidingChipFit.TIGHT, RidingChipFit.NAME_ICON, canStep[i]) > room;"),
                "a chip follows its value only when its widest can't fit the column");
        check(screen.contains(": wanted.get(chip).widestWidthAt(normal, rung, step, false),")
                && screen.contains(": wanted.get(chip).widestWidthAt(normal, rung, step, true), canStep, room, limit);"),
                "the column fits from the widest values");
        check(screen.contains("room = RidingChipFit.columnRoom(room, size, fitted, kept);"), "the column's room widens once for a value past the widest");
        check(screen.contains("if (group.getMinimumWidth() != columnWidth) group.setMinimumWidth(columnWidth);"),
                "the column keeps its chips' widest width");
        int row = screen.indexOf("private void fitRow("), column = screen.lastIndexOf("/**", screen.indexOf("private void fitColumn("));
        check(row > 0 && column > row && !screen.substring(row, column).contains("widestWidthAt"), "Halo's row fits as before");
    }
}
