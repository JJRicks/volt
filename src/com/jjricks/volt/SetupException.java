package com.jjricks.volt;

/**
 * A refused owner setup (a typed identity, a setup file, a staged import) whose message is shown to the
 * person (plan section 1.6). It is an IllegalArgumentException, so every existing catch still takes it;
 * {@link #getMessage()} keeps the English text, so logs, toasts not yet converted and tests stay
 * byte-identical, and screens show {@link #msg()} in the current language.
 *
 * OwnerIdentityParser cannot build one: the Shizuku shell process runs that parser, and CI compiles it
 * without the catalog (tests/owner-capture-guard-test.py). Its refusals stay English ids, and
 * {@link #shown(RuntimeException)} gives a screen the text for either kind.
 */
public class SetupException extends IllegalArgumentException {
    private final Msg msg;

    public SetupException(Msg msg) {
        super(english(msg));
        this.msg = msg;
    }

    private static String english(Msg msg) {
        if (msg == null) throw new NullPointerException("msg");
        return msg.english();
    }

    /** What the screen shows, rendered at display time. */
    public Msg msg() { return msg; }

    /**
     * The text a setup screen shows for a refusal: a SetupException's own message, the catalog text of
     * one of OwnerIdentityParser's English refusals, or, for anything else, its English message as it is.
     */
    static CharSequence shown(RuntimeException refused) {
        if (refused instanceof SetupException) return ((SetupException) refused).msg();
        String english = refused.getMessage();
        if (refused instanceof OwnerIdentityParser.ConflictingIdentityException
                || OwnerIdentityParser.CONFLICTING.equals(english)) return M.ownerSetupConflicting();
        if (OwnerIdentityParser.EMPTY_OR_TOO_LARGE.equals(english)) return M.ownerSetupEmptyOrLarge();
        if (OwnerIdentityParser.NEED_HEX.equals(english)) return M.ownerSetupNeedHex();
        if (OwnerIdentityParser.NO_IDENTITY.equals(english)) return M.ownerSetupNoIdentity();
        if (OwnerIdentityParser.UNSUPPORTED_ROLE.equals(english)) return M.ownerSetupUnsupportedRole();
        return english == null ? "" : english;
    }
}
