package com.jjricks.volt;

/**
 * Clean Home's quick actions exactly as the Layout Plan's mock-up draws them (the contributor's decisions on #383,
 * 13 Nov 2026): four equal round buttons in one row and ONE short name on ONE line under each circle ("Ride",
 * "Record", "Cruise"), with no second value line: an action that is on shows it by its circle's red fill, and its
 * state is spoken. Pure Java, so the JVM suite checks it at the sizes the maintainers check
 * (tests/QuickActionLayoutTest).
 *
 * <p>The circle does not grow with large text: only the name under it does. The name is the action's short form
 * (QuickAccessGrid picks it); where even that is wider than its column, it gets a little smaller, down to
 * {@link #MIN_SCALE} of its size, when that keeps it whole. A name too wide even then stays at the row's size and ends
 * in an ellipsis on its one line (a smaller cut name says no more, and would be the only small one in the row); its
 * spoken name stays whole. Widths are measured at the full size, in the same unit as {@code room}.
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

    /** The name under a circle: its text, its size as a share of the full size, and whether it ends in an ellipsis. */
    static final class Name {
        final String text;
        final float scale;
        final boolean clipped;

        Name(String text, float scale, boolean clipped) { this.text = text; this.scale = scale; this.clipped = clipped; }

        @Override public String toString() {
            return text == null ? "none" : "\"" + text + "\"" + (scale < 1 ? " at " + scale : "") + (clipped ? ", clipped" : "");
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

    /** The short name on one line: at full size, else a little smaller while that keeps it whole, else at full size with an ellipsis. */
    static Name name(String name, float room, Width width) {
        if (name == null || name.isEmpty()) return new Name(null, 1, false);
        float wide = width.of(name);
        if (wide <= room) return new Name(name, 1, false);
        for (int step = 1; 1 - step * SCALE_STEP >= MIN_SCALE - 1e-4f; step++) {
            float scale = Math.round((1 - step * SCALE_STEP) * 100) / 100f;
            if (wide * scale <= room) return new Name(name, scale, false);
        }
        return new Name(name, 1, true);
    }

    /**
     * The four names as one row (part 10's notes, built in part 11): each fitted alone ({@link #name}), then all at the
     * smallest size any whole name needs, so when one name has to get smaller the others match it and the row reads
     * as one line of one size. A name that does not fit even at {@link #MIN_SCALE} takes that shared size too and ends
     * in an ellipsis; the shared size never goes under MIN_SCALE.
     */
    static Name[] names(String[] names, float room, Width width) {
        Name[] alone = new Name[names.length];
        float shared = 1;
        for (int i = 0; i < names.length; i++) {
            alone[i] = name(names[i], room, width);
            if (alone[i].text != null && !alone[i].clipped) shared = Math.min(shared, alone[i].scale);
        }
        Name[] out = new Name[names.length];
        for (int i = 0; i < names.length; i++) {
            if (alone[i].text == null) { out[i] = alone[i]; continue; }
            boolean whole = width.of(alone[i].text) * shared <= room;
            out[i] = new Name(alone[i].text, shared, !whole);
        }
        return out;
    }
}
