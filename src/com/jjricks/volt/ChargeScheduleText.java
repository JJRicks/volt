package com.jjricks.volt;

/**
 * Display text for ChargeScheduleController's English ids (batch B19, l10n/README.md), and the only place they
 * become text. The controller keeps English where it is data: the View's blocked reasons and State.status, which
 * it saves, logs and compares (tests/ChargeScheduleControllerTest reads them). What a completion answers is only
 * ever shown, so the controller renders that itself, through this class for a blocked reason. A screen passes a
 * reason or a saved status through shown() just before showing it.
 *
 * Pure Java (no Android), so the JVM tests reach it. A reason is matched only by its exact English: anything
 * this class does not know, including text already rendered in the current language, shows as it is.
 */
final class ChargeScheduleText {
    private ChargeScheduleText() {}

    private static Msg[] known() {
        return new Msg[]{M.commonWaitCurrentChange(), M.chargingScheduleWaitTest(), M.commonConnectChargerFirst(),
                M.chargingScheduleDisableDoneBy(), M.chargingScheduleChooseScooter(), M.chargingScheduleConnectScooter(),
                M.chargingScheduleNotOnModel(), M.chargingScheduleTurnOffDoneBy(), M.chargingScheduleTurnOffScheduled(),
                M.chargingScheduleWaitReadings(), M.chargingScheduleAtLimit()};
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
