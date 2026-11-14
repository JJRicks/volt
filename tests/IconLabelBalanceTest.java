package com.jjricks.volt;

/** IconLabelBalance: a centred icon label's text centres on the whole label, never at the cost of its text. */
public final class IconLabelBalanceTest {
    private static int checks;
    public static void main(String[] args) {
        // Ui.button at 1x density: 18 px padding each side, a 30 px icon and a 12 px gap.
        int width = 360, pad = 18, icon = 30, gap = 12;
        int spacer = IconLabelBalance.spacer(true, width, pad, pad, icon, gap, 100f);
        require(spacer == icon, "Equal padding: the spacer is as wide as the icon, got " + spacer);
        // The text's room then sits in the middle of the label: as far from each edge.
        int left = pad + icon + gap, right = width - (pad + gap + spacer);
        require(left == width - right, "The balanced room is centred: " + left + " from the start, " + (width - right) + " from the end");
        // Unbalanced, the room's centre sat (icon + gap) / 2 past the label's centre: the bug.
        require((pad + icon + gap + width - pad) / 2 - width / 2 == (icon + gap) / 2, "Today's text sits 21 px off centre");

        // Start-aligned labels (rows) never balance, and neither does a label with no icon.
        require(IconLabelBalance.spacer(false, width, pad, pad, icon, gap, 100f) == 0, "A start-aligned label keeps its layout");
        require(IconLabelBalance.spacer(true, width, pad, pad, 0, gap, 100f) == 0, "No icon, nothing to balance");
        require(IconLabelBalance.spacer(true, 0, pad, pad, icon, gap, 100f) == 0, "Not laid out yet: no balance");

        // Never trade text for centring: the full text must fit unwrapped beside the spacer too.
        int balancedRoom = width - 2 * (pad + icon + gap); // 240
        require(IconLabelBalance.spacer(true, width, pad, pad, icon, gap, balancedRoom) == icon, "Text exactly as wide as the balanced room balances");
        require(IconLabelBalance.spacer(true, width, pad, pad, icon, gap, balancedRoom + 0.01f) == 0,
                "Text a fraction too wide (TextView rounds up) keeps today's layout");
        int todayRoom = width - 2 * pad - icon - gap; // 282
        require(IconLabelBalance.spacer(true, width, pad, pad, icon, gap, todayRoom) == 0,
                "Text that fits only without the balance keeps today's layout, off centre");
        require(IconLabelBalance.spacer(true, width, pad, pad, icon, gap, todayRoom + 50) == 0,
                "Text that already wraps keeps today's layout and its line breaks");
        require(IconLabelBalance.spacer(true, width, pad, pad, icon, gap, Float.NaN) == 0
                && IconLabelBalance.spacer(true, width, pad, pad, icon, gap, Float.POSITIVE_INFINITY) == 0, "Unmeasurable text: no balance");

        // Stats' Refresh and Export: 12 px padding and an 8 px gap set after decorate.
        require(IconLabelBalance.spacer(true, 150, 12, 12, 30, 8, 50f) == 30, "Narrow Stats button with short text balances");
        require(IconLabelBalance.spacer(true, 150, 12, 12, 30, 8, 51f) == 0, "Narrow Stats button with longer text keeps today's layout");
        // Lock title's narrow card shrinks its glyph to 0: nothing to balance.
        require(IconLabelBalance.spacer(true, 100, 0, 0, 0, 0, 20f) == 0, "A 0 px glyph needs no balance");

        // Uneven padding: the spacer makes up the start side's extra, and none is needed when the end is already wider.
        require(IconLabelBalance.spacer(true, width, 24, 18, icon, gap, 50f) == 36, "Wider start padding: a wider spacer");
        int s = IconLabelBalance.spacer(true, width, 24, 18, icon, gap, 50f);
        require(24 + icon + gap == 18 + gap + s, "Uneven padding still centres the room");
        require(IconLabelBalance.spacer(true, width, 0, 30, icon, gap, 50f) == 0, "End padding as wide as the icon: already balanced");
        require(IconLabelBalance.spacer(true, width, 0, 40, icon, gap, 50f) == 0, "End padding wider than the icon: no spacer");

        // The decision depends only on the label's own measures, never on whether it is balanced now: idempotent.
        for (int i = 0; i < 3; i++) require(IconLabelBalance.spacer(true, width, pad, pad, icon, gap, 100f) == icon, "Same inputs, same spacer");

        // A parent measuring the label (Stats' StackingRow) sees the width it saw before the balance.
        int measured = pad + icon + gap + 100 + gap + spacer + pad;
        require(IconLabelBalance.unbalanced(measured, spacer, gap) == pad + icon + gap + 100 + pad, "Unbalanced width drops the spacer and its gap");
        require(IconLabelBalance.unbalanced(measured, 0, gap) == measured, "No spacer, no change");
        require(IconLabelBalance.unbalanced(200, 30, -5) == 170, "A negative gap counts as none");

        System.out.println("IconLabelBalance: " + checks + " checks passed");
    }
    private static void require(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
}
