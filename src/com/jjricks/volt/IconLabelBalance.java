package com.jjricks.volt;

/**
 * Centres an icon label's text on the whole label (owner, Nov 14). {@link Ui#decorate} puts a 30 dp icon at the
 * label's start, so a centred label (Back up Volt, Release this scooter…, Add a scooter) centred its text in the
 * room beside the icon, about 21 dp past the button's true centre. An invisible end spacer as wide as the start
 * side's extra (start padding plus the icon, less the end padding) balances it: TextView adds the same compound
 * gap beside the spacer as beside the icon, so the text's room sits in the middle of the label.
 *
 * The balance never trades text for centring (the narrow-label ladder): the spacer goes on only when the text's
 * widest line fits unwrapped in the balanced room. Otherwise the label keeps today's layout, a little off centre,
 * with no new wrap, ellipsis, smaller text or shorter rung. Only centred labels balance; start-aligned rows keep
 * their layout exactly. A parent that measures a label to pick a layout subtracts the spacer
 * ({@link #unbalanced}), so the balance never changes which layout it picks.
 *
 * Pure Java so the JVM tests reach it; Ui's IconBalance reads the label at layout time, since gravity may be set
 * before or after decorate and a label can be decorated twice.
 */
final class IconLabelBalance {
    private IconLabelBalance() {}

    /**
     * The end spacer's width in px, or 0 to leave the label as it is.
     *
     * @param centred whether the label's text is horizontally centred
     * @param width the label's laid-out width
     * @param paddingStart the label's start padding, without the icon
     * @param paddingEnd the label's end padding, without any spacer
     * @param iconWidth the start icon's width (0 when it has none)
     * @param gap the compound drawable padding, which TextView puts beside each drawable
     * @param textWidth the text's widest line, unwrapped, at the label's own paint
     */
    static int spacer(boolean centred, int width, int paddingStart, int paddingEnd, int iconWidth, int gap, float textWidth) {
        if (!centred || width <= 0 || iconWidth <= 0 || !(textWidth >= 0) || Float.isInfinite(textWidth)) return 0;
        int spacer = paddingStart + iconWidth - paddingEnd;
        if (spacer <= 0) return 0;
        int beside = Math.max(0, gap);
        // The room the text gets beside the spacer too; TextView lays out one line when it fits the rounded-up width.
        long balancedRoom = (long) width - paddingStart - iconWidth - beside - paddingEnd - beside - spacer;
        return (long) Math.ceil(textWidth) <= balancedRoom ? spacer : 0;
    }

    /** A measured width without the spacer (and the gap beside it): what the label measured before the balance. */
    static int unbalanced(int measuredWidth, int spacer, int gap) {
        return spacer > 0 ? measuredWidth - spacer - Math.max(0, gap) : measuredWidth;
    }
}
