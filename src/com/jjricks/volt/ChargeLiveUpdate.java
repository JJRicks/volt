package com.jjricks.volt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Android 16's Live Update promotion of the live charging card (charging-live plan, PR 2). Pure and Android-free:
 * the ProgressStyle bar (segments and points as colour roles), the status-bar chip's text, when Volt asks for
 * promotion at all, and the developer row's status. The service turns roles into colours and calls the platform.
 *
 * Volt asks only on Android 16 or later, when Android has a promotion request (EXTRA_REQUEST_PROMOTED_ONGOING or
 * Builder.setRequestPromotedOngoing), allows Volt to post promoted notifications, and no charge recording needs the
 * card's summary line. Otherwise the card is the plain one, unchanged.
 */
public final class ChargeLiveUpdate {
    /** The first Android with ProgressStyle and the promotion APIs (Android 16). */
    public static final int SDK = 36;
    /** ProgressStyle's length: segments add up to it, so a percent is a position. */
    public static final int MAX = 100;

    /** What a part of the bar means; the service picks Volt's colour for each. */
    public enum Role {
        /** Charging toward the target: Volt red, as the Battery cell. */
        CHARGING,
        /** Charge done by or Scheduled charging holds the charge: Volt orange. */
        HELD,
        /** Out of reach: the last level, greyed. */
        STALE,
        /** Past the charge limit, where the scooter won't charge: quiet grey. */
        PAST_LIMIT,
        /** The charge limit. */
        LIMIT,
        /** A battery alert's level, on a model without a charge limit. */
        ALERT
    }
    public static final class Segment {
        public final int length;public final Role role;
        Segment(int length, Role role) { this.length = length;this.role = role; }
    }
    public static final class Point {
        public final int position;public final Role role;
        Point(int position, Role role) { this.position = position;this.role = role; }
    }

    public final List<Segment> segments;
    public final List<Point> points;
    /** The tracker's position, 0 to MAX; indeterminate when no level was ever read. */
    public final int progress;
    public final boolean indeterminate;
    /** The chip's text ("72%"), or null without a level. */
    public final String shortCritical;

    private ChargeLiveUpdate(List<Segment> segments, List<Point> points, int progress, boolean indeterminate, String shortCritical) {
        this.segments = segments;this.points = points;this.progress = progress;this.indeterminate = indeterminate;
        this.shortCritical = shortCritical;
    }

    /** The bar for one card. alertAt is a battery alert's level (models without a charge limit only), or null. */
    public static ChargeLiveUpdate of(ChargeLiveState live, Integer alertAt) {
        Role fill = live.kind == ChargeLiveState.Kind.RECONNECTING ? Role.STALE : live.kind == ChargeLiveState.Kind.WAITING ? Role.HELD : Role.CHARGING;
        List<Segment> segments = new ArrayList<>();
        List<Point> points = new ArrayList<>();
        boolean capped = live.max > 0 && live.max < MAX;
        if (capped) {
            segments.add(new Segment(live.max, fill));
            segments.add(new Segment(MAX - live.max, Role.PAST_LIMIT));
            points.add(new Point(live.max, Role.LIMIT));
        } else {
            segments.add(new Segment(MAX, fill));
            if (alertAt != null && alertAt > 0 && alertAt < MAX) points.add(new Point(alertAt, Role.ALERT));
        }
        Integer pct = live.percent;
        int progress = pct == null ? 0 : Math.max(0, Math.min(MAX, pct));
        String chip = pct == null ? null : T.fmtPercent(Fmt.integer(progress));
        return new ChargeLiveUpdate(Collections.unmodifiableList(segments), Collections.unmodifiableList(points), progress, pct == null, chip);
    }

    /** Whether Volt asks Android to promote this card; when false, the card is exactly the plain one. */
    public static boolean promote(int sdk, boolean requestAvailable, boolean allowed, boolean chargeRecording) {
        return sdk >= SDK && requestAvailable && allowed && !chargeRecording;
    }

    /** The developer row's answer: one glance at whether the Pixel promotes the card. */
    public enum Status { OLD_ANDROID, UNAVAILABLE, NOT_ALLOWED, PROMOTED, NOT_PROMOTABLE, NOT_PROMOTED, READY }

    /**
     * showing: the live card is the connection notice now, asked to be promoted; promoted: Android set
     * FLAG_PROMOTED_ONGOING on it; promotable: its hasPromotableCharacteristics(). The card only shows while Volt is
     * out of sight, so with none showing the row reports how the last one went (last, null before any).
     */
    public static Status status(int sdk, boolean requestAvailable, boolean allowed, boolean showing, boolean promoted, boolean promotable, Status last) {
        if (sdk < SDK) return Status.OLD_ANDROID;
        if (!requestAvailable) return Status.UNAVAILABLE;
        if (!allowed) return Status.NOT_ALLOWED;
        if (!showing) return last == Status.PROMOTED || last == Status.NOT_PROMOTED || last == Status.NOT_PROMOTABLE ? last : Status.READY;
        if (promoted) return Status.PROMOTED;
        return promotable ? Status.NOT_PROMOTED : Status.NOT_PROMOTABLE;
    }

    public static String label(Status status) {
        switch (status) {
            case OLD_ANDROID: return T.chargeLiveUpdateOldAndroid();
            case UNAVAILABLE: return T.chargeLiveUpdateUnavailable();
            case NOT_ALLOWED: return T.chargeLiveUpdateNotAllowed();
            case PROMOTED: return T.chargeLiveUpdatePromoted();
            case NOT_PROMOTABLE: return T.chargeLiveUpdateNotPromotable();
            case NOT_PROMOTED: return T.chargeLiveUpdateNotPromoted();
            default: return T.chargeLiveUpdateReady();
        }
    }
}
