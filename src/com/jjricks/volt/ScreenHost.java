package com.jjricks.volt;
import android.app.Activity;
import java.io.File;
/** Shared native screen contract; connection ownership remains in the service. */
public interface ScreenHost {
    Activity activity(); Ui ui(); ScooterService service();
    /** The activity is resumed right now. False by default, so a host that doesn't say keeps riding mode's display paused. */
    default boolean resumed() { return false; }
    void redraw(); void rebuild(); void toast(String text); void connect();
    /** Opens a Hub page (HubPages id; legacy tab ids are accepted) above the current one. */
    void open(String page);
    /** The same, with one-shot arguments for the page built (HubNav.Args); args may be null. */
    void open(String page, HubNav.Args args);
    /** A catalog message as a toast, rendered now in the current language. */
    default void toast(Msg message) { toast(message.render()); }
    void requestBluetooth(); void importOwner();
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
    default void exportDashboardLayouts() { }
    default void importDashboardLayouts() { }
    /** Opens the owner's email app with a draft to Volt's report address; false when no app can. */
    default boolean emailVolt(String subject, String body) { return false; }
    /** Opens the owner's email app with a plain draft to Volt's report address, with no sheet and nothing attached; false when no app can. */
    default boolean emailDraft(String subject, String body) { return false; }
    /** Asks which scooter this is (and optionally its drive modes), then drafts the finished check's report to Volt's report address. */
    default void emailReport(CompatibilityReport report) { }
    /** Asks which listed NAVEE model this scooter is and keeps the answer on its profile. */
    default void chooseScooterModel(String profileId) { }
    /** Opens riding mode (Home's RIDE button). */
    default void openRiding() { }
    /** Home's lock switch for riding mode's stopped controls (owner, Nov 8), through the same tap queue, only while stopped says so; null when the host has none. */
    default android.view.View ridingLock(java.util.function.BooleanSupplier stopped) { return null; }
}
