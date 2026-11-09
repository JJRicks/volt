package com.jjricks.volt;

import java.util.function.LongFunction;

/**
 * What the live charging card shows (charging-live plan, PR 1): one immutable view of a charge, built from
 * facts the service already has, so its wording is unit-tested off Android. Rendered when posted, in the
 * language shown then. Android 8 to 16 all get this ongoing-notification look; Android 16's Live Update
 * promotion is a later step.
 *
 * The private version (unlocked phone) always has the scooter's name, as the header's subtext, and the
 * charging power. The lock screen's public version follows the owner's choice: DETAILS is the same card,
 * LEVEL leaves out the name and the power, and HIDDEN shows none of it.
 */
public final class ChargeLiveState {
    public enum Kind { CHARGING, WAITING, RECONNECTING }
    /** The lock screen choice; its id is what the preference stores. */
    public enum Detail {
        DETAILS("details"), LEVEL("level"), HIDDEN("hidden");
        public final String id;
        Detail(String id) { this.id = id; }
        /** Unknown or missing text is DETAILS, the owner's default (Q46 B). */
        public static Detail of(String id) {
            for (Detail d : values()) if (d.id.equals(id)) return d;
            return DETAILS;
        }
    }

    /** The facts one card is built from. */
    public static final class Input {
        /** The battery level, the last one seen when not fresh; null when none was seen. */
        public Integer percent;
        /** percent is an old reading (connected, but no fresh level came in); the dial won't show it. */
        public boolean percentStale;
        /** The scooter's own charge limit, or null for a model without one (or none read yet). */
        public Integer limit;
        /** Minutes left to the limit (or to full without one); null when not known. */
        public Double minutesLeft;
        public Double watts;
        /** A Volt charge schedule is waiting to start; startWallMs is that start, doneByWallMs Charge done by's finish (0 for Scheduled charging). */
        public boolean waiting;
        public long startWallMs, doneByWallMs;
        public boolean reconnecting;
        public String scooter;
        public Detail detail = Detail.DETAILS;
        public long nowWall;
    }

    public final Kind kind;
    public final String title, text, subText, publicTitle, publicText, publicSubText;
    /** The bar: 0 to max (the limit, or 100), filled to progress; progress is -1 without a level. */
    public final int progress, max;
    /**
     * Equal (equals) unless what the card shows changes: a whole percent, the finish time, the state, the limit,
     * the name, or whether a power reading is shown. The power's value is left out; {@link Reposts} paces it.
     */
    public final java.util.List<Object> dedupeKey;
    /** The charging power the card shows, in whole watts; null when it shows none (no reading, or not charging). */
    public final Integer watts;
    public final Detail detail;
    /** The battery level shown (0 to 100), the last one seen while out of reach; null without one. */
    public final Integer percent;
    /**
     * The level for the promoted card's dial (ChargeLiveDial): a fresh reading while charging or waiting. Null when the
     * level isn't known right now (reconnecting, or no fresh reading): no dial then, and the card keeps its plain title.
     */
    public final Integer dialPercent;
    /** The promoted card's title: the target alone when the dial shows the level ("Charging to 80%"), or the plain title. */
    public final String dialTitle;

    private ChargeLiveState(Kind kind, String title, String text, String subText, String publicTitle, String publicText,
            String publicSubText, int progress, int max, java.util.List<Object> dedupeKey, Detail detail, Integer percent, Integer watts, Integer dialPercent, String dialTitle) {
        this.kind = kind;this.title = title;this.text = text;this.subText = subText;this.publicTitle = publicTitle;
        this.publicText = publicText;this.publicSubText = publicSubText;this.progress = progress;this.max = max;
        this.dedupeKey = dedupeKey;this.detail = detail;
        this.percent = percent;this.watts = watts;this.dialPercent = dialPercent;this.dialTitle = dialTitle;
    }

    /** clock formats a wall time as the phone's clock (DisplayTime.clock in the service). */
    public static ChargeLiveState of(Input in, LongFunction<String> clock) {
        Detail detail = in.detail == null ? Detail.DETAILS : in.detail;
        Integer pct = in.percent == null ? null : Math.max(0, Math.min(100, in.percent));
        boolean capped = in.limit != null && in.limit > 0 && in.limit < 100;
        int max = capped ? in.limit : 100;
        Kind kind = in.reconnecting ? Kind.RECONNECTING : in.waiting ? Kind.WAITING : Kind.CHARGING;
        long minutes = in.minutesLeft == null || !Double.isFinite(in.minutesLeft) ? 0 : Math.max(0, Math.round(in.minutesLeft));
        boolean eta = kind == Kind.CHARGING && minutes > 0;
        Integer watts = in.watts == null || !Double.isFinite(in.watts) || in.watts < 1 ? null : (int) Math.round(in.watts);
        String title;
        String text, plain, done = null;
        switch (kind) {
            case RECONNECTING:
                title = pct == null ? (capped ? T.chargingAlertWatchTitle(Fmt.integer(max)) : T.chargingAlertWatchFull()) : T.chargeLiveTitleLastSeen(Fmt.integer(pct));
                text = plain = T.chargeLiveTextReconnecting();
                break;
            case WAITING:
                String start = clock.apply(in.startWallMs);
                title = pct == null ? T.chargeLiveTitleWaitingTime(start) : T.chargeLiveTitleWaiting(start, Fmt.integer(pct));
                text = plain = in.doneByWallMs > 0 ? T.chargeLiveTextDoneBy(clock.apply(in.doneByWallMs)) : T.chargeLiveTextScheduled();
                break;
            default:
                title = pct == null ? (capped ? T.chargingAlertWatchTitle(Fmt.integer(max)) : T.chargingAlertWatchFull())
                        : capped ? T.chargeLiveTitleLimit(Fmt.integer(max), Fmt.integer(pct)) : T.chargeLiveTitleFull(Fmt.integer(pct));
                String power = watts == null ? null : T.fmtQuantity(Fmt.integer(watts), T.unitsWatt());
                if (eta) {
                    // Owner, 2026-11-06: only the finish time and the power ("Done 10:42 PM · 412 W"), no time left or countdown.
                    done = clock.apply(in.nowWall + minutes * 60_000L);
                    plain = T.chargeLiveTextEta(done);
                    text = power == null ? plain : T.chargeLiveTextEtaWatts(done, power);
                } else {
                    plain = T.chargeLiveTextEstimating();
                    text = power == null ? plain : T.chargeLiveTextEstimatingWatts(power);
                }
        }
        String name = in.scooter == null || in.scooter.isEmpty() ? null : in.scooter;
        String publicTitle = detail == Detail.HIDDEN ? null : title;
        String publicText = detail == Detail.HIDDEN ? null : detail == Detail.LEVEL ? plain : text;
        String publicSub = detail == Detail.DETAILS ? name : null;
        int progress = pct == null ? -1 : Math.min(max, pct);
        // The power's value is left out: a watt reading that wiggles doesn't repost the card (Reposts paces a real change).
        // Whether one is shown is in: losing the reading or getting it back reposts at once.
        // The finish time as shown, not the minutes left: a minute that passes with the same finish time changes nothing.
        Integer shownWatts = kind == Kind.CHARGING ? watts : null;
        // Only a level read just now goes on the dial. Reconnecting or without a fresh read there is none, and the plain title keeps saying what it knows.
        Integer dialPercent = kind != Kind.RECONNECTING && !in.percentStale ? pct : null;
        String dialTitle = dialPercent == null ? title : kind == Kind.WAITING ? T.chargeLiveTitleWaitingTime(clock.apply(in.startWallMs))
                : capped ? T.chargingAlertWatchTitle(Fmt.integer(max)) : T.chargingAlertWatchFull();
        java.util.List<Object> key = java.util.Arrays.asList(kind, pct, done, max, in.startWallMs, in.doneByWallMs, detail, name, shownWatts != null, dialPercent != null);
        return new ChargeLiveState(kind, title, text, name, publicTitle, publicText, publicSub, progress, max, key, detail, pct, shownWatts, dialPercent, dialTitle);
    }

    /**
     * When the shown card is reposted (Codex review of #274): at once when its dedupe key changes; for the power
     * alone, when it moved by at least POWER_MIN_W and POWER_MIN_SHARE of the shown value, and at most once per
     * POWER_PACE_MS since the card was last posted. So a big change or a lost or regained reading reaches the card,
     * a few watts of wobble don't, and a burst of readings costs one repost per pace.
     *
     * The baseline is what was really posted (round 4): due() only asks, and posted() records the card that a post
     * carried, whichever path built it (a paced or coalesced post, startForeground, any other updateNotification),
     * or null when the connection notice went out without the card. Not thread-safe; the service calls it on its
     * handler.
     */
    public static final class Reposts {
        public static final int POWER_MIN_W = 10;
        public static final double POWER_MIN_SHARE = 0.10;
        public static final long POWER_PACE_MS = 30_000L;
        private java.util.List<Object> key;
        private Integer watts;
        private long postedMs;

        /** True when the card should be posted again now. It records nothing: posted() does, once a post goes out. */
        public boolean due(ChargeLiveState live, long nowMs) {
            return key == null || !live.dedupeKey.equals(key) || powerMoved(watts, live.watts, nowMs - postedMs);
        }

        /** A connection notice went out at nowMs carrying this card, or no card (null): the new baseline. */
        public void posted(ChargeLiveState live, long nowMs) {
            if (live == null) { key = null; watts = null; return; }
            key = live.dedupeKey; watts = live.watts; postedMs = nowMs;
        }

        /** Forget the last post (a new hold, a new lock screen choice): the next check posts. */
        public void reset() { key = null; }

        static boolean powerMoved(Integer shown, Integer now, long sinceMs) {
            if (shown == null || now == null) return shown != now;
            int moved = Math.abs(now - shown);
            return sinceMs >= POWER_PACE_MS && moved >= POWER_MIN_W && moved >= POWER_MIN_SHARE * shown;
        }
    }

    /** The dial's number and text size. */
    public static final class DialFit {
        public final String text;public final float size;
        DialFit(String text, float size) { this.text = text;this.size = size; }
    }

    /**
     * The dial's number, narrow-label style: the percent at full size, smaller only down to floor, and past that the
     * bare number (a language's wider "100 %") rather than tiny text or a broken one.
     */
    public static DialFit dialFit(String percent, String bare, float size, float floor, float room, java.util.function.ToDoubleFunction<String> width) {
        double wide = width.applyAsDouble(percent);
        if (wide <= room) return new DialFit(percent, size);
        float fitted = (float) (size * room / wide);
        if (fitted >= floor) return new DialFit(percent, fitted);
        double bareWide = width.applyAsDouble(bare);
        return new DialFit(bare, bareWide <= room ? size : (float) (size * room / bareWide));
    }

    /** The card's one action: it ends Volt's hold and lets the connection go, and never touches charging (owner, 2026-11-06). */
    public static String actionLabel() {
        return T.chargeLiveActionDisconnect();
    }
}
