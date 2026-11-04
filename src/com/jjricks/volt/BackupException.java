package com.jjricks.volt;

import java.io.IOException;

/**
 * Why a backup, a backup file or a restore was refused or failed (plan section 1.6). It is still an
 * IOException, so every existing {@code catch (IOException e)} and {@code instanceof IOException} check
 * catches it. {@link #getMessage()} keeps the English text, so logs, reports and the existing tests
 * that read it stay byte-identical; a screen shows {@link #msg()} in the current language and decides
 * by {@link #code()}, never by comparing the English.
 */
final class BackupException extends IOException {
    /** What kind of refusal this is; a screen branches on it instead of on the text. */
    enum Code {
        /** Refused or failed for the reason in the message. */
        FAILED,
        /** Saved data changed after the review: review again. Nothing was changed. */
        CHANGED,
        /** Not enough free storage for the restore. Nothing was changed. */
        NO_SPACE
    }

    private final Code code;
    private final Msg msg;

    BackupException(Msg msg) {
        super(english(msg));
        this.code = Code.FAILED;
        this.msg = msg;
    }

    BackupException(Msg msg, Throwable cause) {
        super(english(msg), cause);
        this.code = Code.FAILED;
        this.msg = msg;
    }

    BackupException(Code code, Msg msg) {
        super(english(msg));
        if (code == null) throw new NullPointerException("code");
        this.code = code;
        this.msg = msg;
    }

    private static String english(Msg msg) {
        if (msg == null) throw new NullPointerException("msg");
        return msg.english();
    }

    Code code() { return code; }

    /** What the screen shows, rendered at display time. */
    Msg msg() { return msg; }
}
