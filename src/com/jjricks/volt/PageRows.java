package com.jjricks.volt;

/**
 * What some Hub page rows show: two of Battery & charging's and the Ride page's recent rides (the owner's Layout
 * Plan; the contributor's pick on #383). Pure, so the JVM suite tests it.
 */
final class PageRows {
    private PageRows() { }

    /** The one Charge done by row's value ("Charge done by  7:30 am ›"). */
    enum DoneBy { TIME, OFF, MEASURING }

    /** Measuring first (its minutes left, as the switch row said), then the finish time while it is on, else Off. */
    static DoneBy doneBy(boolean on, boolean measuring) {
        return measuring ? DoneBy.MEASURING : on ? DoneBy.TIME : DoneBy.OFF;
    }

    /** The Live charging row's sub-line. */
    enum LiveSub { NONE, NOTIFICATIONS_OFF, LOCK_SCREEN }

    /**
     * The warning first (on, but notifications are off), as before; otherwise the Layout Plan's "On the lock screen"
     * while the card is on and shows there (its lock screen choice is not Hidden); nothing while it is off.
     */
    static LiveSub liveSub(boolean on, boolean allowed, boolean hiddenOnLock) {
        if (!on) return LiveSub.NONE;
        if (!allowed) return LiveSub.NOTIFICATIONS_OFF;
        return hiddenOnLock ? LiveSub.NONE : LiveSub.LOCK_SCREEN;
    }

    /**
     * A recent ride's sub-line on the Ride page ("6.2 mi · 24m · −18 %"): distance, duration and the battery change,
     * each left out when it is null or empty, joined by " · ".
     */
    static String rideLine(String distance, String duration, String battery) {
        StringBuilder out = new StringBuilder();
        for (String part : new String[]{distance, duration, battery}) {
            if (part == null || part.isEmpty()) continue;
            if (out.length() > 0) out.append(" · ");
            out.append(part);
        }
        return out.toString();
    }
}
