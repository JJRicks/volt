package com.jjricks.volt;

/** HomeMoment: what Clean Home's top area shows (hub layout plan 1.7, PR 6). */
public final class HomeMomentTest {
    private static int checks;
    private static final long MIN = 60_000L, END = 1_700_000_000_000L;

    public static void main(String[] args) {
        priority();
        window();
        dismissed();
        switches();
        keys();
        System.out.println("HomeMoment: " + checks + " checks passed");
    }

    private static int at(long now) { return HomeMoment.choose(true, false, true, false, "r1", END, null, now); }

    private static void priority() {
        require(HomeMoment.choose(true, false, true, false, null, 0, null, END) == HomeMoment.PARKED, "No ride, not charging: parked");
        require(HomeMoment.choose(true, true, true, false, "r1", END, null, END + MIN) == HomeMoment.CHARGING, "Charging wins over Just back");
        require(HomeMoment.choose(true, true, true, false, null, 0, null, END) == HomeMoment.CHARGING, "Charging shows by itself");
        require(HomeMoment.choose(true, false, true, false, "r1", END, null, END + MIN) == HomeMoment.JUST_BACK, "After unplugging Just back resumes inside its hour");
        require(HomeMoment.choose(true, false, true, true, "r1", END, null, END + MIN) == HomeMoment.PARKED, "Never while recording");
        require(HomeMoment.choose(true, true, true, true, "r1", END, null, END + MIN) == HomeMoment.CHARGING, "Charging still shows while recording");
    }

    private static void window() {
        require(at(END) == HomeMoment.JUST_BACK, "Right as the ride ends");
        require(at(END + 59 * MIN + 59_000L) == HomeMoment.JUST_BACK, "At 59:59");
        require(at(END + 60 * MIN) == HomeMoment.PARKED, "Gone at 60:00");
        require(at(END + 3 * 60 * MIN) == HomeMoment.PARKED, "Long after");
        require(at(END - 5 * MIN) == HomeMoment.JUST_BACK, "A ride end in the future (clock skew) counts as just ended");
        require(HomeMoment.choose(true, false, true, false, "r1", 0, null, END) == HomeMoment.PARKED, "No end time: parked");
        require(HomeMoment.choose(true, false, true, false, "", END, null, END) == HomeMoment.PARKED, "Empty ride id: parked");
        require(HomeMoment.minutesAgo(END, END + 12 * MIN + 30_000L) == 12, "Whole minutes ago");
        require(HomeMoment.minutesAgo(END, END - MIN) == 0 && HomeMoment.minutesAgo(END, END + 59_000L) == 0, "Under a minute or a future end is 0 (just now)");
    }

    private static void dismissed() {
        require(HomeMoment.choose(true, false, true, false, "r1", END, "r1", END + MIN) == HomeMoment.PARKED, "Closed: parked");
        require(HomeMoment.choose(true, false, true, false, "r2", END, "r1", END + MIN) == HomeMoment.JUST_BACK, "A newer ride shows again");
    }

    private static void switches() {
        require(HomeMoment.choose(false, true, true, false, "r1", END, null, END + MIN) == HomeMoment.JUST_BACK, "Charging switched off: Just back still shows");
        require(HomeMoment.choose(false, true, true, false, null, 0, null, END) == HomeMoment.PARKED, "Charging switched off: parked");
        require(HomeMoment.choose(true, false, false, false, "r1", END, null, END + MIN) == HomeMoment.PARKED, "Ride switched off: parked");
        require(HomeMoment.on(null) && HomeMoment.on(Boolean.TRUE) && HomeMoment.on("false"), "Absent or anything but false is on");
        require(!HomeMoment.on(Boolean.FALSE), "Only a stored false is off");
    }

    private static void keys() {
        require(BackupRegistry.PREFERENCES.equals(BackupRegistry.category("app_settings", HomeMoment.CHARGING_KEY))
                && BackupRegistry.PREFERENCES.equals(BackupRegistry.category("app_settings", HomeMoment.RIDE_KEY)), "Both switches travel as App preferences");
        BackupRegistry.Rule dismissed = BackupRegistry.rule("app_settings", HomeMoment.DISMISSED_KEY);
        require(dismissed != null && dismissed.excluded(), "A closed card names a ride on this phone and stays here");
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
