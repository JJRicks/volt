package com.jjricks.volt;

/**
 * When Volt's window keeps the screen on, and whether its flag needs touching. The body refresh asks every second;
 * Android lays the window out again for every addFlags or clearFlags call, even one that changes nothing, so the flag
 * is set or cleared only when the wanted state differs from the window's. Pure and Android-free.
 */
final class KeepScreenOn {
    /** WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON (a constant Android never changes). */
    static final int FLAG = 0x00000080;

    private KeepScreenOn() {}

    /** On screen, and either a ride recording with the keep-awake setting on, or riding mode showing (a handlebar display). */
    static boolean wanted(boolean resumed, boolean keepAwakeSetting, boolean recording, boolean ridingShown) {
        return resumed && keepAwakeSetting && recording || resumed && ridingShown;
    }

    /** 1 to set the flag, -1 to clear it, 0 when the window's flags already say what's wanted. */
    static int change(boolean wanted, int windowFlags) {
        boolean on = (windowFlags & FLAG) != 0;
        return wanted == on ? 0 : wanted ? 1 : -1;
    }
}
