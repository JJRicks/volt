package com.jjricks.volt;
import android.app.Activity;
import java.io.File;
/** Shared native screen contract; connection ownership remains in the service. */
public interface ScreenHost {
    Activity activity(); Ui ui(); ScooterService service();
    /** The activity is resumed right now. False by default, so a host that doesn't say keeps riding mode's display paused. */
    default boolean resumed() { return false; }
    /**
     * Volt's window is back from what covered it (Android's permission dialog): resumed, focused again, and its frames on
     * time (MainActivity.watchWindowBack). Settings' granted Nearby devices card leaves only then. Resumed by default.
     */
    default boolean windowBack() { return resumed(); }
    /** Settings saw Nearby devices permission granted: the service starts if it isn't bound or starting. Nothing by default. */
    default void nearbyGranted() {}
    void redraw(); void rebuild(); void toast(String text); void connect();
    /** A redraw in delayMs, when a control's pending spinner is due (PendingControl.nextLookIn). Nothing by default. */
    default void redrawIn(long delayMs) {}
    /**
     * One look at a control waiting on the scooter (PendingControl): true while its spinner shows. Schedules the redraw
     * that starts (or backstops) it, and has speaker tell a screen reader "Waiting for the scooter" once per wait.
     */
    default boolean pendingLook(PendingControl look, String key, boolean waiting, long sinceMs, android.view.View speaker) {
        long now = android.os.SystemClock.elapsedRealtime();
        look.look(waiting, sinceMs, now);
        long next = look.nextLookIn(now);
        if (next > 0) redrawIn(next);
        boolean spin = look.showing(now);
        if (spin && resumed() && speaker != null && speaker.isShown() && look.announce(key, now))
            speaker.announceForAccessibility(T.mainControlWaiting());
        return spin;
    }
    /** Opens a Hub page (HubPages id; legacy tab ids are accepted) above the current one. */
    void open(String page);
    /** The same, with one-shot arguments for the page built (HubNav.Args); args may be null. */
    void open(String page, HubNav.Args args);
    /** The page shown (HubPages id), or null where the host keeps no page stack. */
    default String shownPage() { return null; }
    /**
     * The width a page has, in dp: the whole window since the Hub has no side rail (hub layout plan, PR 11). A pane that
     * hosts a page (PR 13) can say less.
     */
    default int contentWidthDp() { return activity().getResources().getConfiguration().screenWidthDp; }
    /** A catalog message as a toast, rendered now in the current language. */
    default void toast(Msg message) { toast(message.render()); }
    void requestBluetooth(); void importOwner();
    /**
     * Whether Nearby devices permission (Bluetooth) is granted: without it the service never binds and service() stays
     * null. True by default, for a host that doesn't say.
     */
    default boolean nearbyAllowed() { return true; }
    /**
     * Volt's settings ("app_settings"): the service's own copy while it is bound, and the same preferences read here while
     * it is not (Settings' app rows work without Nearby devices permission, the contributor's decision, 15 Nov, #383).
     */
    default android.content.SharedPreferences appSettings() {
        ScooterService service = service();
        return service != null ? service.settings : activity().getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE);
    }
    void requestRideLocation(Runnable ready);
    /** The same request, made for auto-record: it also keeps the note of a decline. */
    void requestAutoRecordLocation(Runnable ready);
    void saveText(String name,String mime,String content);
    void saveFile(String name,String mime,File content);
    /**
     * Like saveFile, then runs copied once content has been copied for the Save dialog, or the save was refused or
     * failed, so the caller can delete it. Never before the copy ends. This default copies nothing itself, so it runs
     * copied as soon as saveFile returns; a host whose saveFile copies later overrides it.
     */
    default void saveFile(String name,String mime,File content,Runnable copied) { try { saveFile(name, mime, content); } finally { if (copied != null) copied.run(); } }
    /** Saves a compatibility report file; its tries count as reported only once the file is written. */
    default void saveReport(CompatibilityReport report) { saveText("volt-compat-report.json", "application/json", report.json()); }
    /** Back up & restore. A null direction first asks Back up or Restore; preselect is a category ID or null. */
    default void dataBackup(Boolean restore, String preselect) { }
    /** A Volt bottom sheet: its body (grab handle, title, close) and the shown sheet around that body. */
    android.widget.LinearLayout sheetContent(String title, String explanation, Runnable close);
    /** The same sheet body with catalog text, rendered now; explanation may be null. */
    default android.widget.LinearLayout sheetContent(Msg title, Msg explanation, Runnable close) {
        return sheetContent(title.render(), explanation == null ? null : explanation.render(), close);
    }
    android.app.AlertDialog showSheet(android.view.View content);
    /**
     * The same sheet as one that follows a finger and springs (DragSheet), not shown yet: call its open() or pull().
     * source: the view it grows out of (the dock), or null for one that rises from the screen's edge. header: the views
     * of content that move it whenever they are dragged (DragSheet.header). A host that keeps its dialogs (for
     * dialogShowing and Back) overrides this to keep this one too.
     */
    default DragSheet dragSheet(DragSheet.Source source, android.view.View content, android.view.View... header) { return new DragSheet(activity(), ui(), source, content, header, null); }
    /** One of the host's own sheets or dialogs is showing over the screen right now (riding mode's alert banner waits). */
    default boolean dialogShowing() { return false; }
    default void exportDashboardLayouts() { }
    default void importDashboardLayouts() { }
    /** Opens the owner's email app with a draft to Volt's report address; false when no app can. */
    default boolean emailVolt(String subject, String body) { return false; }
    /**
     * Emails this exported file of charge recordings to Volt's report address, attached as the report emails attach
     * theirs. The file is moved off the main thread (never read into memory) and is gone afterwards; the host toasts
     * when it can't be attached or no app can open the email. model: the scooter's model line taken when the export was
     * asked for (ChargeSend.Export), empty for none.
     */
    default void emailChargeRecordings(File recordings, String model) { recordings.delete(); }
    /** Opens the owner's email app with a plain draft to Volt's report address, with no sheet and nothing attached; false when no app can. */
    default boolean emailDraft(String subject, String body) { return false; }
    /** Asks which scooter this is (and optionally its drive modes), then drafts the finished check's report to Volt's report address. */
    default void emailReport(CompatibilityReport report) { }
    /** Find it nearby (FindNearby): the scan sheet in find mode for the active scooter at a new Bluetooth address. */
    default void findNearby() { }
    /** Asks which listed NAVEE model this scooter is and keeps the answer on its profile. */
    default void chooseScooterModel(String profileId) { }
    /** Opens riding mode (Home's RIDE button). */
    default void openRiding() { }
    /**
     * Home's lock switch for riding mode's stopped controls (owner, Nov 8), through the same tap queue: it takes a tap
     * only while shown says so, and a tap goes out only once still says the scooter is completely stopped (owner, Nov 19);
     * null when the host has none.
     */
    default android.view.View ridingLock(java.util.function.BooleanSupplier shown, java.util.function.BooleanSupplier still) { return null; }
}
