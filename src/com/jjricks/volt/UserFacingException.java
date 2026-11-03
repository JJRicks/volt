package com.jjricks.volt;

/**
 * An error whose message is shown to the person (plan section 1.6). The screen shows {@link #msg()}
 * in the current language; {@link #getMessage()} keeps the English text, so logs, reports and the
 * existing tests that read it stay byte-identical. Developer errors (protocol, layout, journals) are
 * never UserFacingExceptions and are never shown.
 */
public class UserFacingException extends RuntimeException {
    private final Msg msg;

    public UserFacingException(Msg msg) {
        super(english(msg));
        this.msg = msg;
    }

    public UserFacingException(Msg msg, Throwable cause) {
        super(english(msg), cause);
        this.msg = msg;
    }

    private static String english(Msg msg) {
        if (msg == null) throw new NullPointerException("msg");
        return msg.english();
    }

    /** What the screen shows, rendered at display time. */
    public Msg msg() { return msg; }
}
