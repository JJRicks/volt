package com.jjricks.volt;

import android.os.SystemClock;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Reads the live charging hold from the real service for LiveChargingHoldCheck; call on the main thread. */
public final class LiveChargingHoldProbe {
    private LiveChargingHoldProbe() {}

    /** Call in the same main-thread message as service.visible(false), just before it: the arming decision's own view. */
    public static LiveChargingHoldCheck.Conditions conditions(ScooterService service) throws ReflectiveOperationException {
        Method facts = ScooterService.class.getDeclaredMethod("chargeLiveFacts");
        facts.setAccessible(true);
        ChargeLiveHold.Facts f = (ChargeLiveHold.Facts) facts.invoke(service);
        LiveChargingHoldCheck.Conditions c = new LiveChargingHoldCheck.Conditions();
        c.enabled = f.enabled;
        c.connected = f.connected;
        c.scheduled = f.scheduled;
        c.charging = f.charging;
        for (ChargeLiveHold.Stopped s : hold(service).stopped(SystemClock.elapsedRealtime())) if (s.profile.equals(f.profile)) c.stoppedForCharge = true;
        return c;
    }

    /** Call right after leaving Volt, with what conditions() read just before it. */
    public static LiveChargingHoldCheck.Observed observe(ScooterService service, LiveChargingHoldCheck.Conditions before) throws ReflectiveOperationException {
        Method effective = ScooterService.class.getDeclaredMethod("chargeLiveHold");
        effective.setAccessible(true);
        LiveChargingHoldCheck.Observed o = new LiveChargingHoldCheck.Observed();
        o.before = before;
        o.armed = hold(service).active();
        o.effective = Boolean.TRUE.equals(effective.invoke(service));
        o.notificationsAllowed = service.chargeLiveAllowed();
        o.workAllowed = service.workAllowed();
        o.graceDeadlineMs = service.backgroundDeadlineMs();
        o.nowMs = SystemClock.elapsedRealtime();
        return o;
    }

    private static ChargeLiveHold hold(ScooterService service) throws ReflectiveOperationException {
        Field field = ScooterService.class.getDeclaredField("chargeLive");
        field.setAccessible(true);
        return (ChargeLiveHold) field.get(service);
    }
}
