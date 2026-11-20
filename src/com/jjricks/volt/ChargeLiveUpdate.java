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
 * Builder.setRequestPromotedOngoing) and allows Volt to post promoted notifications. Otherwise the card is the plain
 * one, unchanged. A charge recording asks too (owner, Nov 20 2026: he records every charge, and the recording used to
 * keep the card plain for its summary line): the Live Update is the same as without one. While the recording has
 * stopped charging (recordingStopped) the card would still read as charging, so it is the plain one, the recording's
 * status as its text. That is the recording's own state (ChargeSessionStore's pause), opened and closed by fresh
 * readings, so the card doesn't alternate with reading noise.
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

    /**
     * Whether Volt asks Android to promote this card; when false, the card is exactly the plain one. A running charge
     * recording's card is promoted like any other, except while the recording has stopped charging (recordingStopped),
     * when the plain card's text is the recording's status instead.
     */
    public static boolean promote(int sdk, boolean requestAvailable, boolean allowed, boolean recordingStopped) {
        return sdk >= SDK && requestAvailable && allowed && !recordingStopped;
    }

    /**
     * Review of #587 (F02): a running recording's charge has stopped, and the card would still read as charging. Its
     * charger stopped on its own (chargerPaused, a 2 or 30 minute wait), or Volt's schedule holds the current off
     * (schedulePaused) on a card that isn't already the schedule's wait (Kind.WAITING, "Starts 3:40 AM", which says so).
     * The card is then the plain one with the recording's status ("charger stopped · waiting for it to resume") as its
     * text: Android 16 shows no BigText summary beside the scooter's name, so a summary alone went unseen.
     */
    public static boolean recordingStopped(boolean chargerPaused, boolean schedulePaused, ChargeLiveState.Kind kind) {
        return chargerPaused || schedulePaused && kind != ChargeLiveState.Kind.WAITING;
    }

    /**
     * What the plain card and its lock screen version say. The card's own words, except while the recording has
     * stopped charging (recordingStopped): those read as charging ("Charging · 62%", "Estimating time left"), so the
     * card and the lock screen both take the recording's title and status, the text the service gives a charge
     * recording ("Charge recording is active", "charger stopped · waiting for it to resume"). Review of #587 r2: F02 the
     * title, F01 the lock screen. A lock screen set to show none of the card (Detail.HIDDEN: no public title) still shows none.
     */
    public static final class Words {
        public final String title, text, publicTitle, publicText;
        private Words(String title, String text, String publicTitle, String publicText) {
            this.title = title;this.text = text;this.publicTitle = publicTitle;this.publicText = publicText;
        }
    }
    public static Words words(ChargeLiveState live, boolean recordingStopped, String recordingTitle, String recordingStatus) {
        if (!recordingStopped) return new Words(live.title, live.text, live.publicTitle, live.publicText);
        boolean hidden = live.publicTitle == null;
        return new Words(recordingTitle, recordingStatus, hidden ? null : recordingTitle, hidden ? null : recordingStatus);
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

    /**
     * Whether the card takes the dial and the shorter title (Codex review of #336). Asking isn't being promoted, so a
     * card keeps the plain look, the level in its title, until it is read back promoted; a card Android keeps declining
     * therefore never changes. Once promoted and then read back unpromoted it stays plain for the rest of the hold, so
     * the look can't alternate: at most two reposts per hold come from here. Not thread-safe; the service calls it on
     * its handler.
     */
    public static final class Look {
        private boolean promoted, declined;
        public boolean dial() { return promoted && !declined; }
        /** The posted card read back; returns true when the card should be rebuilt. */
        public boolean observed(boolean nowPromoted) {
            if (declined || nowPromoted == promoted) return false;
            promoted = nowPromoted;
            declined = !nowPromoted;
            return true;
        }
        public void reset() { promoted = declined = false; }
    }

    /**
     * The last live card's verdict, kept for the developer row. Opening Volt ends the hold, and so the card, before the
     * row can be opened, so the service records what Android did while the card is posted (a moment after each post,
     * and again just before the hold ends) and stores it on this device. built() is called for every connection notice
     * (asked: it requested promotion); observed() when the service reads the posted card back. Only an asked card's
     * reading counts, so a later plain notice never overwrites it.
     */
    public static final class Last {
        private boolean asked, promotable;
        private Status status;
        private long wallMs;

        public void built(boolean asked, boolean promotable) { this.asked = asked;this.promotable = asked && promotable; }
        public boolean asked() { return asked; }
        /** The posted card read back; returns true when the stored verdict changed. */
        public boolean observed(boolean promoted, long wallMs) {
            if (!asked) return false;
            Status next = promoted ? Status.PROMOTED : promotable ? Status.NOT_PROMOTED : Status.NOT_PROMOTABLE;
            boolean changed = next != status;
            status = next;this.wallMs = wallMs;
            return changed;
        }
        /** The last card's verdict (PROMOTED, NOT_PROMOTED or NOT_PROMOTABLE), or null before any card. */
        public Status status() { return status; }
        /** When the verdict was read (wall clock), 0 before any. */
        public long wallMs() { return wallMs; }
        /** A verdict stored by an earlier service; anything but a card verdict is ignored. */
        public void restore(Status status, long wallMs) {
            if (status != Status.PROMOTED && status != Status.NOT_PROMOTED && status != Status.NOT_PROMOTABLE) return;
            this.status = status;this.wallMs = wallMs;
        }
        /** The row: Android's own state first, then the last card's verdict, READY only before any card. */
        public Status row(int sdk, boolean requestAvailable, boolean allowed) {
            return ChargeLiveUpdate.status(sdk, requestAvailable, allowed, false, false, false, status);
        }
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
