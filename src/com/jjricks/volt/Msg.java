package com.jjricks.volt;

import java.util.Arrays;
import java.util.Locale;

/**
 * A catalog message rendered later, at display time, in whatever language is current then
 * (l10n/README.md). The generated {@code M} class builds these: {@code M.dashMoveWidget(title)}.
 *
 * A Msg is what code stores, caches or hands to another thread instead of rendered text (rule R2), so
 * a language change never leaves stale words behind. It is a CharSequence, so it can be an argument of
 * another Msg or of a {@code T} accessor; it renders whenever its text is read. Two messages are equal
 * when their ids, quantities and arguments are equal, whatever the language.
 */
public final class Msg implements CharSequence {
    /** The id of a verbatim message: text that is not from the catalog, such as a scooter's name. */
    static final int VERBATIM = -1;

    private final int id;
    private final boolean plural;
    private final long quantity;
    private final Object[] args;
    private final String verbatim;

    private Msg(int id, boolean plural, long quantity, Object[] args, String verbatim) {
        this.id = id;
        this.plural = plural;
        this.quantity = quantity;
        this.args = args == null ? new Object[0] : args.clone();
        this.verbatim = verbatim;
    }

    /** A string id with its arguments (the generated M accessors call this). */
    public static Msg of(int id, Object... args) {
        check(id, false);
        return new Msg(id, false, 0, args, null);
    }

    /** A plural id for a quantity with its arguments (the generated M accessors call this). */
    public static Msg plural(int id, long quantity, Object... args) {
        check(id, true);
        return new Msg(id, true, quantity, args, null);
    }

    /** Text that is not from the catalog and is shown as it is: user data, model names, numbers. */
    public static Msg verbatim(CharSequence text) {
        if (text == null) throw new NullPointerException("text");
        return new Msg(VERBATIM, false, 0, null, text.toString());
    }

    private static void check(int id, boolean plural) {
        if (id < 0 || id >= T.size()) throw new IllegalArgumentException(String.valueOf(id));
        if (T.isPlural(id) != plural) throw new IllegalArgumentException(T.key(id));
    }

    /** The catalog id, or -1 for a verbatim message. */
    public int id() { return id; }
    public boolean isPlural() { return plural; }
    public long quantity() { return quantity; }
    /** A copy of the arguments, in placeholder order (the quantity is not among them). */
    public Object[] args() { return args.clone(); }

    /** The text now, in the current language. */
    public String render() {
        if (verbatim != null) return verbatim;
        return plural ? T.renderPlural(id, quantity, args) : T.render(id, args);
    }

    /**
     * The English text whatever language is installed: what logs, reports and getMessage() keep.
     * Arguments that are messages render in English too.
     */
    public String english() {
        if (verbatim != null) return verbatim;
        Object[] english = new Object[args.length];
        for (int i = 0; i < args.length; i++) english[i] = args[i] instanceof Msg ? ((Msg) args[i]).english() : args[i];
        String text = plural ? EnglishText.INSTANCE.plural(id, quantity) : EnglishText.INSTANCE.string(id);
        return T.isFormatted(id) ? String.format(Locale.ROOT, text, english) : text;
    }

    @Override public String toString() { return render(); }
    @Override public int length() { return snapshot().length(); }
    @Override public char charAt(int index) { return snapshot().charAt(index); }
    @Override public CharSequence subSequence(int start, int end) { return snapshot().subSequence(start, end); }

    /**
     * The text the CharSequence methods read. StringBuilder.append(CharSequence) and TextUtils.getChars
     * copy one character at a time, so these reuse one rendering while the installed source is the same
     * (every language change installs a new one) instead of rendering once per character.
     */
    private String snapshot() {
        Snapshot current = snapshot;
        T.Source source = T.source();
        if (current == null || current.source != source) {
            current = new Snapshot(source, render());
            snapshot = current;
        }
        return current.text;
    }

    private transient volatile Snapshot snapshot;

    private static final class Snapshot {
        final T.Source source;
        final String text;
        Snapshot(T.Source source, String text) { this.source = source; this.text = text; }
    }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof Msg)) return false;
        Msg that = (Msg) other;
        return id == that.id && plural == that.plural && quantity == that.quantity
                && (verbatim == null ? that.verbatim == null : verbatim.equals(that.verbatim))
                && Arrays.deepEquals(args, that.args);
    }

    @Override public int hashCode() {
        int hash = 31 * id + (plural ? 1 : 0);
        hash = 31 * hash + (int) (quantity ^ (quantity >>> 32));
        hash = 31 * hash + (verbatim == null ? 0 : verbatim.hashCode());
        return 31 * hash + Arrays.deepHashCode(args);
    }
}
