package com.jjricks.volt;

/**
 * Clean Home's quick actions exactly as the Layout Plan's mock-up draws them (the contributor's decisions on #383,
 * 13 Nov 2026): four equal round buttons in one row and ONE short name on ONE line under each circle ("Ride",
 * "Record", "Cruise"), with no second value line: an action that is on shows it by its circle's red fill, and its
 * state is spoken. Pure Java, so the JVM suite checks it at the sizes the maintainers check
 * (tests/QuickActionLayoutTest).
 *
 * <p>The circle does not grow with large text: only the name under it does. A name is never cut, mid-word or
 * otherwise (the owner's ladder for narrow labels): the action's name (QuickAccessGrid picks it) is shown whole at
 * full size, else a little smaller, down to {@link #MIN_SCALE} of its size; failing that, a shorter name the catalog
 * already has, the same way; failing that, the icon alone, with the full name still spoken. Widths are measured at the
 * full size, in the same unit as {@code room}.
 *
 * <p>How a circle shows its state ({@link #look}): filled red while the action is on. The lock is Volt's one
 * control whose state must never be guessed: while Volt has not read it, the circle has its own look (no disc, a
 * dashed ring, the padlock muted), which reads as neither locked nor unlocked; a state Volt has read shows as
 * locked (filled, closed padlock) or unlocked (open padlock), dimmed while it is the last known one.
 */
final class QuickActionLayout {
    /** The plan's circle (54 dp), and the smallest a circle gets on a narrow column. */
    static final int CIRCLE_DP = 54, MIN_CIRCLE_DP = 44;
    /** Four columns while each is at least this wide; else two rows of two. */
    static final int MIN_COLUMN_DP = 64;
    /**
     * The space between columns (and rows), the column's side padding (none: the gap already keeps two names apart),
     * and the space between the circle and the name.
     */
    static final int GAP_DP = 6, SIDE_DP = 0, NAME_GAP_DP = 6;
    /** The name's text size (sp, before the text scale): the mock-up's 13.5 sp. */
    static final float NAME_SP = 13.5f;
    /** A name gets no smaller than this share of its size, and only once it does not fit at full size. */
    static final float MIN_SCALE = .75f;
    private static final float SCALE_STEP = .05f;

    private QuickActionLayout() {}

    /** A text's width at the size being fitted. */
    interface Width { float of(String text); }

    /**
     * A text's width at a share of the full size, measured at that size (text does not always scale in proportion:
     * hinting and rounding can make a smaller size a pixel or two wider than the full width times the share).
     */
    interface ScaledWidth { float of(String text, float scale); }

    /** The name under a circle: its text (null: the icon alone) and its size as a share of the full size. */
    static final class Name {
        final String text;
        final float scale;

        Name(String text, float scale) { this.text = text; this.scale = scale; }

        boolean iconOnly() { return text == null; }

        @Override public String toString() {
            return text == null ? "icon-only" : "\"" + text + "\"" + (scale < 1 ? " at " + scale : "");
        }
    }

    /** Columns for {@code count} actions across {@code width} dp: four in a row while each column is wide enough. */
    static int columns(float width, int count) {
        if (count <= 1) return 1;
        int columns = Math.min(4, count);
        while (columns > 1 && column(width, columns) < MIN_COLUMN_DP) columns = columns == 4 ? 2 : columns - 1;
        return columns;
    }

    /** One column's width in dp, for a row {@code width} dp wide and that many columns. */
    static float column(float width, int columns) {
        return (width - (columns - 1) * GAP_DP) / Math.max(1, columns);
    }

    /** The circle's diameter in a column this wide (dp): the plan's 54 dp, less on a narrow column. */
    static int circleDp(float column) {
        return (int) Math.max(MIN_CIRCLE_DP, Math.min(CIRCLE_DP, Math.floor(column - 2 * SIDE_DP)));
    }

    /** The room for the name in a column this wide (dp): the column less its side padding. */
    static float room(float column) { return Math.max(0, column - 2 * SIDE_DP); }

    /**
     * A ladder of names with a one-word name first (the contributor's pick on #383: short names under the circles):
     * where the first name has more than one word and a later one is a single word, that word moves to the front and
     * the rest keep their order. So pt-BR cruise control reads "Cruzeiro" (its Setting tile rung) rather than Live
     * Stats' "Controle de cruzeiro", even in a wide column where the long name would fit (review of #449 f5540133,
     * finding 3). A ladder whose first name is already one word, or that has no one-word name, is unchanged.
     */
    static String[] oneWordFirst(String... names) {
        if (names.length == 0 || names[0] == null || oneWord(names[0])) return names;
        for (int i = 1; i < names.length; i++) {
            if (names[i] == null || names[i].isEmpty() || !oneWord(names[i])) continue;
            String[] ordered = new String[names.length];
            ordered[0] = names[i];
            for (int from = 0, to = 1; from < names.length; from++) if (from != i) ordered[to++] = names[from];
            return ordered;
        }
        return names;
    }

    /** No space of any kind inside the trimmed name. */
    private static boolean oneWord(String name) {
        String trimmed = name.trim();
        for (int i = 0; i < trimmed.length(); i++) if (Character.isWhitespace(trimmed.charAt(i)) || Character.isSpaceChar(trimmed.charAt(i))) return false;
        return true;
    }

    /**
     * The name on one line, never cut: {@code names} in order (the action's name, then shorter names the catalog
     * already has). A shorter word comes before smaller text (the contributor docs' rule for narrow labels): the first
     * name whole at full size; else the largest size, in steps down to {@link #MIN_SCALE}, at which one of them is
     * whole (at the same size, the earlier name); none of them: the icon alone. Null and empty names, and a name no
     * narrower than one already tried, are skipped.
     */
    static Name name(float room, Width width, String... names) {
        return name(room, (text, scale) -> width.of(text) * scale, names);
    }

    /** As {@link #name(float, Width, String...)}, each size measured as it is drawn. */
    static Name name(float room, ScaledWidth width, String... names) {
        java.util.ArrayList<String> ladder = new java.util.ArrayList<>();
        float tried = Float.MAX_VALUE;
        for (String name : names) {
            if (name == null || name.isEmpty()) continue;
            float wide = width.of(name, 1);
            if (wide >= tried) continue;
            tried = wide;
            if (wide <= room) return new Name(name, 1);
            ladder.add(name);
        }
        for (int step = 1; 1 - step * SCALE_STEP >= MIN_SCALE - 1e-4f; step++) {
            float scale = Math.round((1 - step * SCALE_STEP) * 100) / 100f;
            for (String name : ladder) if (width.of(name, scale) <= room) return new Name(name, scale);
        }
        return new Name(null, 1);
    }

    /** How a round action's circle looks. */
    enum Look {
        /** On: the circle filled red. */
        ON,
        /** Off: the plain raised circle. */
        OFF,
        /** The lock's state is not known: no disc, a dashed ring and a muted padlock (neither locked nor unlocked). */
        UNKNOWN
    }

    /** A round action's look and whether it is dimmed (a state Volt last saw, not one it reads now). */
    static final class State {
        final Look look;
        final boolean dimmed;
        /** The lock only: its padlock is open (last known or read unlocked). */
        final boolean open;

        State(Look look, boolean dimmed, boolean open) { this.look = look; this.dimmed = dimmed; this.open = open; }

        @Override public String toString() { return look + (open ? " open" : "") + (dimmed ? ", dimmed" : ""); }
    }

    /**
     * The lock's circle: {@code value} is the lock's state as Volt last read it (1 locked, 0 unlocked; null or anything
     * else: never read, or unreadable), {@code live} whether that reading is current. Locked is filled, unlocked has the
     * open padlock, both dimmed while they are the last known state; an unknown state has its own look, live or not.
     */
    static State lock(Integer value, boolean live) {
        if (value == null || value != 0 && value != 1) return new State(Look.UNKNOWN, false, false);
        return new State(value == 1 ? Look.ON : Look.OFF, !live, value == 0);
    }

    /**
     * Battery alerts' circle: on while the charge alert ({@code level} above 0) or the low-battery alert ({@code low} not
     * null) is set. These are Volt's own saved settings, so they show the same whether or not the scooter is
     * connected; {@code level} null means the scooter has no battery alerts (off, dimmed).
     */
    static State alerts(Integer level, Integer low) {
        if (level == null) return new State(Look.OFF, true, false);
        return new State(level > 0 || low != null ? Look.ON : Look.OFF, false, false);
    }

    /**
     * A control whose setting is not a plain on/off but has an Off among its settings (energy recovery's Off, Low,
     * Medium and High; a light's Off, On and Follow headlight; an on/off control that is not a direct toggle on this
     * model): on whenever its setting is anything but Off, read now or last known ({@code value}, the scooter's raw
     * value; null: never read or unreadable), dimmed while it is the last known one or not known at all.
     * {@code off} is the model's Off value; null where the model offers no Off (energy recovery's Low, Medium and High
     * only), so every setting is on.
     */
    static State setting(Integer value, Integer off, boolean live) {
        if (value == null) return new State(Look.OFF, true, false);
        return new State(off == null || value.intValue() != off.intValue() ? Look.ON : Look.OFF, !live, false);
    }

    /**
     * Charge done by's circle: on while the schedule is armed ({@code armed}, Volt's saved schedule), connected or not;
     * dimmed while the scooter is not connected (the schedule then waits for it).
     */
    static State schedule(boolean armed, boolean live) {
        return new State(armed ? Look.ON : Look.OFF, !live, false);
    }

    /**
     * The four names as one row (part 10's notes, built in part 11): each ladder fitted alone ({@link #name}), then all
     * at the smallest size any shown name needs, so when one name has to get smaller the others match it and the row
     * reads as one line of one size. Each keeps the name its own ladder chose; a column that shows the icon alone
     * stays so and sets no size. The shared size is never under {@link #MIN_SCALE} (no name is ever chosen below it),
     * and a name is never cut: one that would be wider than its room at the shared size (hinting) keeps its own size.
     */
    static Name[] names(float room, ScaledWidth width, String[]... ladders) {
        Name[] alone = new Name[ladders.length];
        float shared = 1;
        for (int i = 0; i < ladders.length; i++) {
            alone[i] = name(room, width, ladders[i] == null ? new String[0] : ladders[i]);
            if (!alone[i].iconOnly()) shared = Math.min(shared, alone[i].scale);
        }
        Name[] out = new Name[ladders.length];
        for (int i = 0; i < ladders.length; i++) {
            Name own = alone[i];
            out[i] = own.iconOnly() || own.scale == shared || width.of(own.text, shared) > room ? own : new Name(own.text, shared);
        }
        return out;
    }

    /** As {@link #names(float, ScaledWidth, String[]...)}, widths measured at the full size. */
    static Name[] names(float room, Width width, String[]... ladders) {
        return names(room, (text, scale) -> width.of(text) * scale, ladders);
    }
}
