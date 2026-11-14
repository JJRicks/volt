package com.jjricks.volt;

/** IconLabelBalance: a centred icon label's text centres on the whole label, as far as its text allows, never at its cost. */
public final class IconLabelBalanceTest {
    private static final int NONE = IconLabelBalance.NONE;
    private static int checks;
    public static void main(String[] args) {
        // Ui.button at 1x density: 18 px padding each side, a 30 px icon and a 12 px gap.
        int width = 360, pad = 18, icon = 30, gap = 12;
        int spacer = IconLabelBalance.spacer(true, width, pad, pad, icon, gap, 100f);
        require(spacer == icon, "Equal padding: the full spacer is as wide as the icon, got " + spacer);
        // The text's room then sits in the middle of the label: as far from each edge.
        int left = pad + icon + gap, right = width - (pad + gap + spacer);
        require(left == width - right, "The balanced room is centred: " + left + " from the start, " + (width - right) + " from the end");
        // Unbalanced, the room's centre sat (icon + gap) / 2 past the label's centre: the bug.
        require((pad + icon + gap + width - pad) / 2 - width / 2 == (icon + gap) / 2, "Today's text sits 21 px off centre");

        // Start-aligned labels (rows) never balance, and neither does a label with no icon.
        require(IconLabelBalance.spacer(false, width, pad, pad, icon, gap, 100f) == NONE, "A start-aligned label keeps its layout");
        require(IconLabelBalance.spacer(true, width, pad, pad, 0, gap, 100f) == NONE, "No icon, nothing to balance");
        require(IconLabelBalance.spacer(true, 0, pad, pad, icon, gap, 100f) == NONE, "Not laid out yet: no balance");

        // Today's room beside the icon is 282 px; the full balance takes 42 more (spacer 30 and its 12 px gap).
        int todayRoom = width - 2 * pad - icon - gap;
        require(IconLabelBalance.spacer(true, width, pad, pad, icon, gap, 240f) == 30, "Text exactly as wide as the balanced room: full spacer");
        // Partial: the widest spacer that still fits the text, rounded up as TextView does.
        require(IconLabelBalance.spacer(true, width, pad, pad, icon, gap, 240.01f) == 29, "A fraction too wide for the full balance: one px less");
        require(IconLabelBalance.spacer(true, width, pad, pad, icon, gap, 260f) == 10, "Tighter text: a narrower spacer");
        require(IconLabelBalance.spacer(true, width, pad, pad, icon, gap, 270f) == 0, "Room for the gap alone: a 0 px spacer, still 6 px nearer centre");
        require(IconLabelBalance.spacer(true, width, pad, pad, icon, gap, 271f) == NONE, "Not even room for the gap: today's layout");
        require(IconLabelBalance.spacer(true, width, pad, pad, icon, gap, todayRoom) == NONE,
                "Text that fits only without the balance keeps today's layout, off centre");
        require(IconLabelBalance.spacer(true, width, pad, pad, icon, gap, todayRoom + 50) == NONE,
                "Text that already wraps keeps today's layout and its line breaks");
        require(IconLabelBalance.spacer(true, width, pad, pad, icon, gap, Float.NaN) == NONE
                && IconLabelBalance.spacer(true, width, pad, pad, icon, gap, Float.POSITIVE_INFINITY) == NONE, "Unmeasurable text: no balance");

        // Every text width: the spacer is NONE or within [0, full], never wraps the text, and is the widest that fits.
        int lastOffset = Integer.MIN_VALUE;
        for (float text = 0; text <= todayRoom + 20; text += 0.25f) {
            int s = IconLabelBalance.spacer(true, width, pad, pad, icon, gap, text);
            int need = (int) Math.ceil(text);
            if (s == NONE) {
                require(need > todayRoom - gap, "No spacer only when even the gap would wrap the text: " + text);
                continue;
            }
            require(s >= 0 && s <= icon, "Spacer within [0, full]: " + s + " at " + text);
            require(need <= todayRoom - gap - s, "The spacer never wraps the text: " + s + " at " + text);
            require(s == icon || need > todayRoom - gap - (s + 1), "The widest spacer that fits: " + s + " at " + text);
            // The text's offset from centre, (full - spacer) / 2, only grows as the text widens.
            int offset = icon - s;
            require(offset >= lastOffset, "Wider text never sits nearer centre than narrower text: " + text);
            lastOffset = offset;
        }

        // Stats' Refresh and Export: 12 px padding and an 8 px gap set after decorate; 88 px of room today.
        require(IconLabelBalance.spacer(true, 150, 12, 12, 30, 8, 50f) == 30, "Narrow Stats button with short text: full balance");
        require(IconLabelBalance.spacer(true, 150, 12, 12, 30, 8, 51f) == 29, "Narrow Stats button, a little longer: partial");
        require(IconLabelBalance.spacer(true, 150, 12, 12, 30, 8, 80f) == 0, "Room for the gap alone");
        require(IconLabelBalance.spacer(true, 150, 12, 12, 30, 8, 81f) == NONE, "No room for the gap: today's layout");
        // Lock title's narrow card shrinks its glyph to 0: nothing to balance.
        require(IconLabelBalance.spacer(true, 100, 0, 0, 0, 0, 20f) == NONE, "A 0 px glyph needs no balance");

        // Uneven padding: the spacer makes up the start side's extra, and none is needed when the end is already wider.
        int s = IconLabelBalance.spacer(true, width, 24, 18, icon, gap, 50f);
        require(s == 36 && 24 + icon + gap == 18 + gap + s, "Wider start padding: a wider spacer that still centres the room");
        require(IconLabelBalance.spacer(true, width, 0, 30, icon, gap, 50f) == NONE, "End padding as wide as the icon: already balanced");
        require(IconLabelBalance.spacer(true, width, 0, 40, icon, gap, 50f) == NONE, "End padding wider than the icon: no spacer");

        // The decision depends only on the label's own measures, never on whether it is balanced now: idempotent.
        for (int i = 0; i < 3; i++) require(IconLabelBalance.spacer(true, width, pad, pad, icon, gap, 260f) == 10, "Same inputs, same spacer");

        // A parent measuring the label (Stats' StackingRow) sees the width it saw before the balance.
        int measured = pad + icon + gap + 100 + gap + spacer + pad;
        require(IconLabelBalance.unbalanced(measured, spacer, gap) == pad + icon + gap + 100 + pad, "Unbalanced width drops the spacer and its gap");
        require(IconLabelBalance.unbalanced(200, 0, gap) == 188, "A 0 px spacer still drops its gap");
        require(IconLabelBalance.unbalanced(measured, NONE, gap) == measured, "No spacer, no change");
        require(IconLabelBalance.unbalanced(200, 30, -5) == 170, "A negative gap counts as none");

        System.out.println("IconLabelBalance: " + checks + " checks passed");
    }
    private static void require(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
}
