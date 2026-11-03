package com.jjricks.volt;

import java.util.Arrays;

/**
 * Display text built by {@link Fmt}, with where its numbers and unit symbols sit (plan section 4.2).
 * Code that styles or splits a value ("12.5" large, "km/h" small; "7.8 mph / 12.5 km" on two lines)
 * reads these ranges instead of searching the text, so it keeps working when a language puts the unit
 * first, uses another space, or writes "50 %" or "%50".
 *
 * Like a String, a Formatted is rendered now, in the current language: show it, don't keep it (rule R2).
 * Ranges are half-open [start, end) offsets into {@link #toString()}, in text order.
 */
public final class Formatted implements CharSequence {
    private final String text;
    private final int[] numbers;
    private final int[] units;

    Formatted(String text, int[] numbers, int[] units) {
        if (text == null) throw new NullPointerException("text");
        this.text = text;
        this.numbers = numbers.clone();
        this.units = units.clone();
    }

    /** Text with no number or unit inside it, such as a feature's own label. */
    static Formatted plain(CharSequence text) { return new Formatted(text.toString(), new int[0], new int[0]); }

    /** How many numbers the text holds. */
    public int numbers() { return numbers.length / 2; }
    public int numberStart(int index) { return numbers[2 * index]; }
    public int numberEnd(int index) { return numbers[2 * index + 1]; }

    /** How many unit symbols the text holds. */
    public int units() { return units.length / 2; }
    public int unitStart(int index) { return units[2 * index]; }
    public int unitEnd(int index) { return units[2 * index + 1]; }

    int[] numberRanges() { return numbers.clone(); }
    int[] unitRanges() { return units.clone(); }

    @Override public String toString() { return text; }
    @Override public int length() { return text.length(); }
    @Override public char charAt(int index) { return text.charAt(index); }
    @Override public CharSequence subSequence(int start, int end) { return text.subSequence(start, end); }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof Formatted)) return false;
        Formatted that = (Formatted) other;
        return text.equals(that.text) && Arrays.equals(numbers, that.numbers) && Arrays.equals(units, that.units);
    }

    @Override public int hashCode() {
        return 31 * (31 * text.hashCode() + Arrays.hashCode(numbers)) + Arrays.hashCode(units);
    }
}
