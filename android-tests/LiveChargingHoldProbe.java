package com.jjricks.volt;

import android.os.SystemClock;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Reads a LiveChargingHoldCheck.Observed from the real service; call on the main thread right after leaving Volt. */
final class LiveChargingHoldProbe {
    private LiveChargingHoldProbe() {}

    static LiveChargingHoldCheck.Observed observe(ScooterService service) throws ReflectiveOperationException {
        Field field = ScooterService.class.getDeclaredField("chargeLive");
        field.setAccessible(true);
        ChargeLiveHold hold = (ChargeLiveHold) field.get(service);
        Method facts = ScooterService.class.getDeclaredMethod("chargeLiveFacts");
        facts.setAccessible(true);
        ChargeLiveHold.Facts f = (ChargeLiveHold.Facts) facts.invoke(service);
        Method effective = ScooterService.class.getDeclaredMethod("chargeLiveHold");
        effective.setAccessible(true);
        LiveChargingHoldCheck.Observed o = new LiveChargingHoldCheck.Observed();
        o.armed = hold.active();
        o.effective = Boolean.TRUE.equals(effective.invoke(service));
        o.notificationsAllowed = service.chargeLiveAllowed();
        o.enabled = f.enabled;
        o.connected = f.connected;
        o.scheduled = f.scheduled;
        o.charging = f.charging;
        o.workAllowed = service.workAllowed();
        o.graceDeadlineMs = service.backgroundDeadlineMs();
        o.nowMs = SystemClock.elapsedRealtime();
        for (ChargeLiveHold.Stopped s : hold.stopped(o.nowMs)) if (s.profile.equals(f.profile)) o.stoppedForCharge = true;
        return o;
    }
}
