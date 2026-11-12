package com.jjricks.volt;

/**
 * Whether a dialog's buttons stay pinned under its scrolling body or scroll with it. Pinned is better, since the
 * buttons stay in view however long the body is, but a pinned footer takes its height first: in a very short window
 * (split screen, the largest text, a button label that wraps) it could leave the body no room, and the title and
 * notes above it could not be reached. Pure Java, so it is tested on the JVM.
 */
final class PinnedFooter {
    private PinnedFooter() {}

    /** The body room, in dp, a pinned footer must leave when the body is taller than that. */
    static final int MIN_BODY_DP = 120;

    /**
     * True when the footer should scroll with the body: the dialog's height, less the footer's, leaves the body less
     * than all of its content or the given minimum, whichever is smaller. The answer depends only on these heights,
     * not on where the footer is now, so moving the footer never changes it and the dialog cannot flip back and forth.
     *
     * @param dialog the height the dialog's content has (all of it when it fits, else what the window allows)
     * @param footer the footer's height
     * @param body the scrolling content's full height without the footer
     * @param minimum the body room to keep, in pixels
     */
    static boolean scrolls(int dialog, int footer, int body, int minimum) {
        if (dialog <= 0 || footer <= 0) return false;
        return dialog - footer < Math.min(Math.max(0, body), Math.max(0, minimum));
    }
}
