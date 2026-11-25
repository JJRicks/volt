package com.jjricks.volt;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * THROWAWAY PROTOTYPE (claude/206-live-updates-proto, never merged): posts sample ride recording and live charging
 * notifications in the design options, so the owner can pick from emulator renders. English is hard-coded on purpose.
 *
 *   adb shell am start -n com.jjricks.volt/.LiveUpdateProtoActivity --es n ride|charge|connected|clear --es v A|B|C|plain
 *       [--es ch low|default] [--ez promote true|false]
 *
 * Channel "scooter" is Volt's own (IMPORTANCE_LOW, created here only if missing); "scooter_quiet" is a DEFAULT
 * channel with no sound and no vibration, to show where a plain notice would sit on Android 8-15. Nothing here makes
 * a sound or vibrates.
 */
public final class LiveUpdateProtoActivity extends Activity {
    private static final String TAG = "LUPROTO";
    private static final String PROMOTE_EXTRA = "android.requestPromotedOngoing";
    private static final int RIDE_ID = 9071, CHARGE_ID = 9072, CONNECTED_ID = 9073;
    private static final int RED = Color.rgb(0xE3, 0x2B, 0x2F), GREEN = Color.rgb(0x5F, 0xCB, 0x7E),
            GREY = Color.rgb(0x9A, 0x9C, 0x95), AMBER = Color.rgb(0xE8, 0xA2, 0x3A);

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent in = getIntent();
        String n = in.getStringExtra("n"), v = in.getStringExtra("v"), ch = in.getStringExtra("ch");
        boolean promote = in.getBooleanExtra("promote", true);
        NotificationManager nm = getSystemService(NotificationManager.class);
        channels(nm);
        String channel = "default".equals(ch) ? "scooter_quiet" : "scooter";
        try {
            if ("clear".equals(n)) { nm.cancel(RIDE_ID); nm.cancel(CHARGE_ID); nm.cancel(CONNECTED_ID); }
            else if ("ride".equals(n)) nm.notify(RIDE_ID, ride(channel, v == null ? "A" : v, promote));
            else if ("charge".equals(n)) nm.notify(CHARGE_ID, charge(channel, v == null ? "A" : v, promote));
            else if ("connected".equals(n)) nm.notify(CONNECTED_ID, connected(channel));
        } catch (RuntimeException e) { Log.e(TAG, "post failed", e); }
        new Handler(Looper.getMainLooper()).postDelayed(() -> report(nm), 1500);
        finish();
    }

    private void channels(NotificationManager nm) {
        if (nm.getNotificationChannel("scooter") == null) {
            NotificationChannel c = new NotificationChannel("scooter", "Scooter connection", NotificationManager.IMPORTANCE_LOW);
            c.setShowBadge(false);
            nm.createNotificationChannel(c);
        }
        if (nm.getNotificationChannel("scooter_quiet") == null) {
            NotificationChannel c = new NotificationChannel("scooter_quiet", "Scooter (quiet, prototype)", NotificationManager.IMPORTANCE_DEFAULT);
            c.setSound(null, null);
            c.enableVibration(false);
            c.setVibrationPattern(null);
            c.enableLights(false);
            c.setShowBadge(false);
            nm.createNotificationChannel(c);
        }
    }

    private void report(NotificationManager nm) {
        StringBuilder out = new StringBuilder("sdk=" + Build.VERSION.SDK_INT);
        try { if (Build.VERSION.SDK_INT >= 36) out.append(" canPostPromoted=").append(nm.canPostPromotedNotifications()); }
        catch (Throwable t) { out.append(" canPostPromoted=?"); }
        for (StatusBarNotification s : nm.getActiveNotifications()) {
            Notification n = s.getNotification();
            boolean promotable = false;
            try { if (Build.VERSION.SDK_INT >= 36) promotable = n.hasPromotableCharacteristics(); } catch (Throwable ignored) {}
            boolean promoted = Build.VERSION.SDK_INT >= 36 && (n.flags & Notification.FLAG_PROMOTED_ONGOING) != 0;
            out.append(" | id=").append(s.getId()).append(" ch=").append(n.getChannelId())
                    .append(" promotable=").append(promotable).append(" promoted=").append(promoted);
        }
        Log.i(TAG, out.toString());
    }

    private PendingIntent open() {
        return PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private PendingIntent noop(int code) {
        return PendingIntent.getActivity(this, code, new Intent(this, LiveUpdateProtoActivity.class).putExtra("n", "none"),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification.Builder base(String channel, String title, CharSequence text) {
        return new Notification.Builder(this, channel).setSmallIcon(R.drawable.ic_volt_notification)
                .setContentTitle(title).setContentText(text).setContentIntent(open())
                .setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PRIVATE);
    }

    private static void ask(Notification.Builder b) {
        Bundle request = new Bundle();
        request.putBoolean(PROMOTE_EXTRA, true);
        b.addExtras(request);
    }

    /** 12 min 34 s into a ride, 4.2 mi, the scooter at 74%. */
    private Notification ride(String channel, String v, boolean promote) {
        long start = System.currentTimeMillis() - (12 * 60 + 34) * 1000L;
        Notification.Builder b;
        switch (v) {
            case "B":
                // Distance chip; the running clock sits in the card's header instead.
                b = base(channel, "Ride recording", "4.2 mi · UT5 MAX 74%");
                b.setWhen(start).setShowWhen(true).setUsesChronometer(true);
                if (promote) b.setShortCriticalText("4.2 mi");
                break;
            case "C":
                // Battery bar: ProgressStyle with the scooter's charge as the bar, the clock as the chip.
                b = base(channel, "Ride recording · 4.2 mi", "Battery 74% · about 19 mi left");
                b.setWhen(start).setShowWhen(true).setUsesChronometer(true);
                if (promote) {
                    List<Notification.ProgressStyle.Segment> seg = new ArrayList<>();
                    seg.add(new Notification.ProgressStyle.Segment(100).setColor(GREEN));
                    b.setStyle(new Notification.ProgressStyle().setProgressSegments(seg).setProgress(74)
                            .setStyledByProgress(true)
                            .setProgressTrackerIcon(Icon.createWithResource(this, R.drawable.ic_charge_bar_bolt)));
                } else {
                    b.setProgress(100, 74, false);
                }
                break;
            case "plain":
                // Today's notice, unchanged (also what Android 15 and older, or Live updates off, show).
                b = base(channel, "Ride recording is active", "4.2 mi");
                b.setWhen(start).setShowWhen(true).setUsesChronometer(true);
                break;
            default: // "A"
                // Clock chip: the chip is the ride's running time (chronometer); the card says the distance.
                b = base(channel, "Ride recording", "4.2 mi · UT5 MAX 74%");
                b.setWhen(start).setShowWhen(true).setUsesChronometer(true);
        }
        b.addAction(new Notification.Action.Builder(null, "Stop recording", noop(11)).build());
        if (promote && !"plain".equals(v)) ask(b);
        return b.build();
    }

    /** Charging to 80%, at 62%, done 4:45 PM at 412 W. */
    private Notification charge(String channel, String v, boolean promote) {
        int pct = 62, limit = 80;
        boolean live = promote && !"plain".equals(v);
        boolean dial = live && !"C".equals(v);
        String title = dial ? "Charging to 80%" : "Charging to 80% · 62%";
        String text = "Done 4:45 PM · 412 W";
        Notification.Builder b = base(channel, title, text).setColor(RED).setSubText("UT5 MAX").setShowWhen(false);
        if (live) {
            List<Notification.ProgressStyle.Segment> seg = new ArrayList<>();
            seg.add(new Notification.ProgressStyle.Segment(limit).setColor(RED));
            seg.add(new Notification.ProgressStyle.Segment(100 - limit).setColor(GREY));
            List<Notification.ProgressStyle.Point> pts = new ArrayList<>();
            pts.add(new Notification.ProgressStyle.Point(limit).setColor(GREEN));
            b.setStyle(new Notification.ProgressStyle().setProgressSegments(seg).setProgressPoints(pts).setProgress(pct)
                    .setProgressTrackerIcon(Icon.createWithResource(this, R.drawable.ic_charge_bar_bolt)));
            b.setShortCriticalText("B".equals(v) ? "4:45 PM" : "62%");
            if (dial) {
                try { Icon d = new ChargeLiveDial().icon(pct); if (d != null) b.setLargeIcon(d); } catch (RuntimeException ignored) {}
            }
            ask(b);
        } else {
            b.setProgress(limit, pct, false);
            b.setStyle(new Notification.BigTextStyle().bigText(text));
        }
        Notification.Builder lock = new Notification.Builder(this, channel).setSmallIcon(R.drawable.ic_volt_notification).setColor(RED)
                .setContentTitle("Charging to 80% · 62%").setContentText(text).setSubText("UT5 MAX").setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE).setProgress(limit, pct, false).setShowWhen(false);
        b.setPublicVersion(lock.build());
        b.addAction(new Notification.Action.Builder(null, "Disconnect", noop(12)).build());
        return b.build();
    }

    /** The everyday connected notice (never a Live Update: not time-sensitive). */
    private Notification connected(String channel) {
        Notification.Builder b = base(channel, "Scooter connected", "UT5 MAX · 82%");
        b.addAction(new Notification.Action.Builder(null, "Disconnect", noop(13)).build());
        return b.build();
    }
}
