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
    /** When Android's countdown in the header reaches zero (wall clock); 0 shows no countdown. */
    public final long countdownEndWallMs;
    /** Equal (equals) unless what the card shows changes: a whole percent, a whole minute, the state, the limit or the name. */
    public final java.util.List<Object> dedupeKey;
    public final Detail detail;
    /** The battery level shown (0 to 100), the last one seen while out of reach; null without one. */
    public final Integer percent;

    private ChargeLiveState(Kind kind, String title, String text, String subText, String publicTitle, String publicText,
            String publicSubText, int progress, int max, long countdownEndWallMs, java.util.List<Object> dedupeKey, Detail detail, Integer percent) {
        this.kind = kind;this.title = title;this.text = text;this.subText = subText;this.publicTitle = publicTitle;
        this.publicText = publicText;this.publicSubText = publicSubText;this.progress = progress;this.max = max;
        this.countdownEndWallMs = countdownEndWallMs;this.dedupeKey = dedupeKey;this.detail = detail;
        this.percent = percent;
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
        String text, plain;
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
                    String left = duration(minutes), done = clock.apply(in.nowWall + minutes * 60_000L);
                    plain = T.chargeLiveTextEta(left, done);
                    text = power == null ? plain : T.chargeLiveTextEtaWatts(left, done, power);
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
        long countdown = eta ? in.nowWall + minutes * 60_000L : 0;
        // Power is left out: a watt reading that wiggles never reposts the card (it shows the latest power when it does).
        java.util.List<Object> key = java.util.Arrays.asList(kind, pct, eta ? minutes : -1L, max, in.startWallMs, in.doneByWallMs, detail, name);
        return new ChargeLiveState(kind, title, text, name, publicTitle, publicText, publicSub, progress, max, countdown, key, detail, pct);
    }

    /** "≈ 38m", "≈ 1h 25m": the Home charging card's own wording. */
    static String duration(long minutes) {
        return minutes >= 60 ? T.dashboardDurationHours(Fmt.integer(minutes / 60), Fmt.integer(minutes % 60)) : T.dashboardDurationMinutes(Fmt.integer(minutes));
    }
}
