package com.jjricks.volt;

/**
 * Riding mode's system bars. MainActivity hides the navigation bar while riding and keeps the status
 * bar for the phone's clock and battery (#254). The owner's "Hide status bar" setting (off by
 * default, Nov 6) makes RidingDisplay hide the status bar too.
 */
final class RidingBars {
    static final String HIDE_STATUS_BAR_KEY = "riding_hide_status_bar";

    private RidingBars() {}

    /** Off unless the rider turned it on. */
    static boolean hidesStatusBar(Boolean stored) {
        return Boolean.TRUE.equals(stored);
    }
}
