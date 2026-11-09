package com.jjricks.volt;

import java.util.ArrayList;
import java.util.List;

/**
 * Which l10n/lang/*.tsv languages the Language sheet lists, and where (plan section 5). Pure, so the JVM
 * tests can run it on any status table: shipped languages (beta, stable) are the choices; draft languages
 * are the In progress section, shown but never choosable; held languages are not listed. A draft that
 * ships moves from the section into the choices with no code change, and the section is hidden when no
 * language is in draft.
 */
final class LanguagePicker {
    private LanguagePicker() {}

    static boolean shipped(String status) { return "beta".equals(status) || "stable".equals(status); }

    static boolean inProgress(String status) { return "draft".equals(status); }

    /** Indexes of the shipped languages, in table order (English first). */
    static int[] choices(String[] statuses) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < statuses.length; i++) if (shipped(statuses[i])) out.add(i);
        return array(out);
    }

    /** Indexes of the draft languages, in table order; empty hides the In progress section. */
    static int[] inProgress(String[] statuses) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < statuses.length; i++) if (inProgress(statuses[i])) out.add(i);
        return array(out);
    }

    private static int[] array(List<Integer> values) {
        int[] out = new int[values.size()];
        for (int i = 0; i < out.length; i++) out[i] = values.get(i);
        return out;
    }
}
