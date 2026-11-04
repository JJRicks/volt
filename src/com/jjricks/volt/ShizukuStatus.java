package com.jjricks.volt;

/**
 * Every status line ShizukuCaptureClient sets in {@link ShizukuCaptureClient#message} (plan B6: the client's
 * message becomes an enum plus Msg). The field keeps each line's English, an id that ShizukuCaptureClientTest
 * and the census validations compare; a screen shows {@link #display(String)}, the catalog text in the current
 * language. ShizukuSetupActivity's own "No browser found" line is its NO_BROWSER. Only the app process uses
 * this: the Shizuku shell process never sees a status line.
 */
enum ShizukuStatus {
    ACCESS_DENIED(T.OWNER_STATUS_ACCESS_DENIED, ShizukuCaptureClient.ACCESS_DENIED),
    NOT_RUNNING(T.OWNER_STATUS_NOT_RUNNING, ShizukuCaptureClient.NOT_RUNNING),
    UPDATE_SHIZUKU(T.OWNER_STATUS_UPDATE_SHIZUKU, ShizukuCaptureClient.UPDATE_SHIZUKU),
    ROOT_UNSUPPORTED(T.OWNER_STATUS_ROOT_UNSUPPORTED, ShizukuCaptureClient.ROOT_UNSUPPORTED),
    ALLOW_IN_SHIZUKU(T.OWNER_STATUS_ALLOW_IN_SHIZUKU, ShizukuCaptureClient.ALLOW_IN_SHIZUKU),
    SHIZUKU_STOPPED(T.OWNER_STATUS_SHIZUKU_STOPPED, ShizukuCaptureClient.SHIZUKU_STOPPED),
    CLEANUP_UNKNOWN(T.OWNER_STATUS_CLEANUP_UNKNOWN, ShizukuCaptureClient.CLEANUP_UNKNOWN),
    START_FAILED(T.OWNER_STATUS_START_FAILED, ShizukuCaptureClient.START_FAILED),
    HELPER_NOT_STARTED(T.OWNER_STATUS_HELPER_NOT_STARTED, ShizukuCaptureClient.HELPER_NOT_STARTED),
    CLEANUP_UNCONFIRMED(T.OWNER_STATUS_CLEANUP_UNCONFIRMED, ShizukuCaptureClient.CLEANUP_UNCONFIRMED),
    NOTHING_FOUND(T.OWNER_STATUS_NOTHING_FOUND, ShizukuCaptureClient.NOTHING_FOUND),
    SEVERAL_SCOOTERS(T.OWNER_STATUS_SEVERAL_SCOOTERS, ShizukuCaptureClient.SEVERAL_SCOOTERS),
    CAPTURE_BUSY(T.OWNER_STATUS_CAPTURE_BUSY, ShizukuCaptureClient.CAPTURE_BUSY),
    LOG_UNREADABLE(T.OWNER_STATUS_LOG_UNREADABLE, ShizukuCaptureClient.LOG_UNREADABLE),
    STOPPED(T.OWNER_STATUS_STOPPED, ShizukuCaptureClient.STOPPED),
    CAPTURE_FAILED(T.OWNER_STATUS_CAPTURE_FAILED, ShizukuCaptureClient.CAPTURE_FAILED),
    RESTORE_AGAIN(T.OWNER_STATUS_RESTORE_AGAIN, ShizukuCaptureClient.RESTORE_AGAIN),
    RESTORING(T.OWNER_STATUS_RESTORING, ShizukuCaptureClient.RESTORING),
    LOST(T.OWNER_STATUS_LOST, ShizukuCaptureClient.LOST);

    /** The catalog id of the text a screen shows. */
    final int text;
    /** The English: the value {@link ShizukuCaptureClient#message} holds. */
    final String english;
    ShizukuStatus(int text, String english) { this.text = text; this.english = english; }
    /** The status line in the current language, rendered when read. */
    Msg msg() { return Msg.of(text); }
    /** The status whose English is {@code message}, or null ("" and any other text). */
    static ShizukuStatus of(String message) {
        for (ShizukuStatus status : values()) if (status.english.equals(message)) return status;
        return null;
    }

    /** What a screen shows for a status line: its catalog text, or the line itself when it is no status ("" stays ""). */
    static String display(String message) {
        ShizukuStatus status = of(message);
        return status == null ? message : T.render(status.text);
    }
}
