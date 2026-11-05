package com.jjricks.volt;

/**
 * Display text for ChargeScheduleController's English ids (batch B19, l10n/README.md). The controller keeps
 * English where it is data: the View's blocked reasons, its completion messages and State.status, which it
 * saves, logs and compares (tests/ChargeScheduleControllerTest reads them). A screen passes such a reason
 * through shown() just before showing it.
 *
 * Pure Java (no Android), so the JVM tests reach it. A reason is matched only by its exact English: anything
 * this class does not know, including text already rendered in the current language, shows as it is.
 */
final class ChargeScheduleText {
    private ChargeScheduleText() {}

    private static Msg[] known() {
        return new Msg[]{M.commonWaitCurrentChange(), M.chargingScheduleWaitTest(), M.commonConnectChargerFirst(),
                M.mainScheduleDisableDoneBy(), M.mainAlertChooseScooter(), M.mainScheduleConnectTheScooter(),
                M.mainScheduleNotOnModel(), M.mainScheduleTurnOffDoneBy(), M.mainScheduleTurnOffScheduled(),
                M.mainScheduleWaitReadings(), M.mainScheduleAlreadyAtLimit(), M.mainScheduleCancelNotConfirmed(),
                M.mainScheduleClockFailed(), M.mainScheduleBatteryChanged(), M.mainScheduleStartNotConfirmed(),
                M.mainScheduleLimitChanged(), M.mainScheduleStartChangedEstimate(), M.mainSchedulePassedNotCancelled(),
                M.mainScheduleLimitSavedNoEstimate(), M.mainScheduleBatterySavedNoEstimate(), M.mainScheduleEnableNotConfirmed(),
                M.mainScheduleConnectionChanged(), M.mainScheduleStoppedBeforeFinish()};
    }

    /** What the screen shows for a reason in the controller's English; null stays null. */
    static String shown(String english) {
        if (english == null) return null;
        for (Msg message : known()) if (message.english().equals(english)) return message.render();
        for (CapabilityResolver.Reason reason : CapabilityResolver.Reason.values()) {
            CharSequence message = CapabilityResolver.message(reason);
            if (message instanceof Msg && ((Msg) message).english().equals(english)) return ((Msg) message).render();
        }
        String doneBy = M.mainScheduleOffRestoreDoneBy("").english(), scheduled = M.mainScheduleOffRestoreScheduled("").english();
        if (english.startsWith(doneBy)) return T.mainScheduleOffRestoreDoneBy(shown(english.substring(doneBy.length())));
        if (english.startsWith(scheduled)) return T.mainScheduleOffRestoreScheduled(shown(english.substring(scheduled.length())));
        if (M.mainScheduleOffClockDoneBy().english().equals(english)) return T.mainScheduleOffClockDoneBy();
        if (M.mainScheduleOffClockScheduled().english().equals(english)) return T.mainScheduleOffClockScheduled();
        if (M.mainScheduleOffStartDoneBy().english().equals(english)) return T.mainScheduleOffStartDoneBy();
        if (M.mainScheduleOffStartScheduled().english().equals(english)) return T.mainScheduleOffStartScheduled();
        return english;
    }
}
