package com.jjricks.volt;

/**
 * Review of #591 (F02, F01): while a charge recording waits for a stopped charger, the live card and its lock screen
 * versions show the battery level in the recording's title ("Charge recording · 72%"), the line the service passes as
 * ChargeLiveUpdate.words' level title. Reconnecting keeps "Charge recording · reconnecting", no level yet keeps
 * "Charge recording is active", and Hidden shows nothing. Listed in tests/l10n/charge-live-paused-title.sources;
 * tests/run.sh runs it.
 */
public final class ChargeLivePausedTitleTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }

    public static void main(String[] args) {
        check("Charge recording · 72%".equals(ChargeLiveUpdate.recordingLevelTitle(72)), "the line: " + ChargeLiveUpdate.recordingLevelTitle(72));
        check("Charge recording · 100%".equals(ChargeLiveUpdate.recordingLevelTitle(100)), "a full battery: " + ChargeLiveUpdate.recordingLevelTitle(100));
        String status = T.serviceChargeStockChargerPaused();
        for (ChargeLiveState.Detail detail : ChargeLiveState.Detail.values()) {
            boolean hidden = detail == ChargeLiveState.Detail.HIDDEN;
            ChargeLiveUpdate.Words paused = ChargeLiveUpdate.words(card(72, false, detail), true, T.serviceNoticeChargeActive(),
                    ChargeLiveUpdate::recordingLevelTitle, status);
            check("Charge recording · 72%".equals(paused.title), detail + ": the card's title " + paused.title);
            check("Stock charger · charger stopped · waiting for it to resume".equals(paused.text), detail + ": the card's text " + paused.text);
            if (hidden) check(paused.publicTitle == null && paused.publicText == null, "Hidden: nothing on the lock screen");
            else check("Charge recording · 72%".equals(paused.publicTitle) && status.equals(paused.publicText),
                    detail + ": the lock screen reads " + paused.publicTitle + " | " + paused.publicText);

            ChargeLiveUpdate.Words away = ChargeLiveUpdate.words(card(72, true, detail), true, T.serviceNoticeChargeReconnecting(),
                    ChargeLiveUpdate::recordingLevelTitle, status);
            check("Charge recording · reconnecting".equals(away.title), detail + ": reconnecting keeps its title, not " + away.title);
            if (!hidden) check("Charge recording · reconnecting".equals(away.publicTitle), detail + ": and on the lock screen, not " + away.publicTitle);

            ChargeLiveUpdate.Words unread = ChargeLiveUpdate.words(card(null, false, detail), true, T.serviceNoticeChargeActive(),
                    ChargeLiveUpdate::recordingLevelTitle, status);
            check("Charge recording is active".equals(unread.title), detail + ": no level yet, " + unread.title);
            if (!hidden) check("Charge recording is active".equals(unread.publicTitle), detail + ": no level yet on the lock screen, " + unread.publicTitle);
        }
        System.out.println("ChargeLivePausedTitleTest: " + checks + " checks passed (the paused recording's title with the level, reconnecting, no level, lock screens)");
    }

    private static ChargeLiveState card(Integer pct, boolean reconnecting, ChargeLiveState.Detail detail) {
        ChargeLiveState.Input in = new ChargeLiveState.Input();
        in.percent = pct;in.reconnecting = reconnecting;in.scooter = "UT5 MAX";in.nowWall = 1_000_000L;in.detail = detail;
        return ChargeLiveState.of(in, t -> "3:40 AM");
    }
}
