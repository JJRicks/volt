package com.jjricks.volt;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.ToIntFunction;

/**
 * An Experimental controls row whose try was pruned says Tried before; where that doesn't fit beside Try, it says Tried
 * (review of #622, P3). Its only fallback used to be no words, the untried default's own last rung, so a narrow row that
 * was tried looked exactly like one never tried. The words step down full, short, empty, and the row speaks the full
 * words whatever it draws.
 */
public final class LabStatusRungsTest {
    private static int checks;

    public static void main(String[] args) {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(Locale.US);
            T.install(null);
            rungs();
            narrowRowSaysTried();
            wired();
        } finally {
            T.install(null);
            Locale.setDefault(saved);
        }
        System.out.println("LabStatusRungsTest: " + checks + " checks passed");
    }

    /** Full, short, then no words, the short one a whole shorter word. */
    private static void rungs() {
        String[] rungs = LabStatusRungs.triedBefore();
        check(Arrays.equals(rungs, new String[] {"Tried before", "Tried", ""}), "Tried before, Tried, then no words: " + Arrays.toString(rungs));
        check(rungs[1].length() < rungs[0].length() && !rungs[1].isEmpty() && !rungs[1].contains(" "), "the short rung is one shorter word");
        check(rungs[rungs.length - 1].isEmpty(), "the last rung is still no words, where even Tried doesn't fit");
        check(LabStatusRungs.triedBefore() != rungs, "a fresh ladder each time");
    }

    /**
     * MoreScreen's LadderPill draws the first rung whose width fits its room, else its last (wired() pins that loop). In
     * a room too narrow for Tried before but wide enough for Tried, the row says Tried, never no words.
     */
    private static void narrowRowSaysTried() {
        ToIntFunction<String> width = String::length;
        String[] rungs = LabStatusRungs.triedBefore();
        check(pick(rungs, 20, width).equals("Tried before"), "a wide row says Tried before");
        check(pick(rungs, 8, width).equals("Tried"), "a narrow row says Tried, not no words (it was: " + pick(new String[] {"Tried before", ""}, 8, width) + ")");
        check(pick(rungs, 3, width).isEmpty(), "a row too narrow for Tried shows no words");
    }

    /** LadderPill's choice: the first rung that fits room, else the last one. */
    private static String pick(String[] ladder, int room, ToIntFunction<String> width) {
        String pick = ladder[ladder.length - 1];
        for (String word : ladder) if (width.applyAsInt(word) <= room) { pick = word; break; }
        return pick;
    }

    /** The row takes these rungs; the pill picks as pick() does and speaks the full words; Not tried yet is unchanged. */
    private static void wired() {
        String more = read("src/com/jjricks/volt/MoreScreen.java");
        check(more.contains("if (last == null && !ble.simulating() && ble.triedBefore(key)) { pill.quiet(ui.muted, LabStatusRungs.triedBefore()); return; }"),
                "labStatus shows a pruned try with LabStatusRungs.triedBefore()");
        check(more.contains("for (String word : ladder) if (getPaint().measureText(word) <= room) { pick = word; break; }")
                && more.contains("String pick = ladder[ladder.length - 1];"), "LadderPill picks the first rung that fits, else the last");
        check(more.contains("ladder = words; setContentDescription(words[0]);"), "the pill speaks its full words");
        check(more.contains("if (last == null) { pill.quiet(ui.muted, T.moreLabStatusNotTried(), \"\"); return; }"),
                "Not tried yet, the default state, still steps straight down to no words");
    }

    private static String read(String path) {
        try {
            return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
}
