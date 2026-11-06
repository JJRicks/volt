package com.jjricks.volt;

import android.app.Activity;

/**
 * The app's {@link RideRecordingControl}: the service records and the activity shows the messages,
 * the location prompt and the redraw. Any screen starts or stops a ride with
 * {@code RideRecordingApp.of(host).start()} / {@code .stop()} (or {@code .toggle()} for the Rides
 * buttons) and stays where it is. Each call reads the current service, so a control kept across a
 * service restart still works.
 */
final class RideRecordingApp {
    private RideRecordingApp() {}

    static RideRecordingControl of(ScreenHost host) {
        return new RideRecordingControl(new ServiceRecorder(host), new HostScreen(host));
    }

    private static final class ServiceRecorder implements RideRecordingControl.Recorder {
        private final ScreenHost host;
        ServiceRecorder(ScreenHost host) { this.host = host; }
        @Override public boolean recording() { ScooterService s = host.service(); return s != null && s.isRecording(); }
        @Override public boolean simulating() { ScooterService s = host.service(); return s != null && s.simulating(); }
        @Override public boolean saving() { ScooterService s = host.service(); return s != null && s.phoneSaving(); }
        @Override public boolean storageReady() { ScooterService s = host.service(); return s != null && s.rides != null; }
        @Override public boolean connected() { ScooterService s = host.service(); return s != null && s.ble.ready(); }
        @Override public void leaveSimulation() { ScooterService s = host.service(); if (s != null) s.setSimulation(false); }
        @Override public void start(String name) { ScooterService s = host.service(); if (s != null) s.startRide(name); }
        @Override public void stop() { ScooterService s = host.service(); if (s != null) s.stopRide(); }
    }

    private static final class HostScreen implements RideRecordingControl.Screen {
        private final ScreenHost host;
        HostScreen(ScreenHost host) { this.host = host; }
        @Override public void toast(String text) { host.toast(text); }
        @Override public void rebuild() { host.rebuild(); }
        @Override public boolean phoneGps() {
            ScooterService s = host.service();
            return s == null || s.settings.getBoolean("phone_gps", true);
        }
        @Override public void requestLocation(Runnable ready) { host.requestRideLocation(ready); }
        @Override public boolean gone() {
            Activity activity = host.activity();
            return activity.isDestroyed() || activity.isFinishing();
        }
        @Override public String rideName() { return RidesScreen.defaultRideName(host); }
        @Override public void confirmed() { Haptics.confirm(host.activity().getWindow().getDecorView()); }
        @Override public void changed() { host.redraw(); }
    }
}
