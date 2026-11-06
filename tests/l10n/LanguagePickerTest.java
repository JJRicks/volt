package com.jjricks.volt;

import java.util.Arrays;

/**
 * The Language sheet's lists (LanguagePicker): shipped languages are the choices, draft languages fill the
 * In progress section, held languages are in neither, and the section is empty (hidden) with no draft. Also
 * checks the real table from l10n/lang/*.tsv: the drafts there are listed In progress and never resolve.
 */
public final class LanguagePickerTest {
    private static int checks;

    public static void main(String[] args) {
        String[] mixed = {"stable", "beta", "draft", "held", "draft", "beta"};
        check(Arrays.equals(LanguagePicker.choices(mixed), new int[]{0, 1, 5}), "choices are the beta and stable languages, in order");
        check(Arrays.equals(LanguagePicker.inProgress(mixed), new int[]{2, 4}), "In progress lists only the draft languages, in order");
        String[] none = {"stable", "beta", "held"};
        check(LanguagePicker.inProgress(none).length == 0, "no draft language: the In progress section is hidden");
        check(LanguagePicker.inProgress(new String[]{"stable"}).length == 0, "English alone: no In progress section");
        String[] shipped = {"stable", "beta", "draft"};
        String[] promoted = {"stable", "beta", "beta"};
        check(Arrays.equals(LanguagePicker.inProgress(shipped), new int[]{2}) && Arrays.equals(LanguagePicker.choices(promoted), new int[]{0, 1, 2})
                && LanguagePicker.inProgress(promoted).length == 0, "a draft that ships as beta moves from In progress into the choices");
        check(!LanguagePicker.inProgress("held") && !LanguagePicker.shipped("held") && !LanguagePicker.inProgress("beta")
                && !LanguagePicker.shipped((String) null) && !LanguagePicker.inProgress((String) null), "held and unknown statuses are in neither list");

        // The real table, from l10n/lang/*.tsv.
        int[] choices = LanguagePicker.choices(AppLanguages.STATUSES), drafts = LanguagePicker.inProgress(AppLanguages.STATUSES);
        check(choices.length >= 1 && AppLanguages.tag(choices[0]).equals(AppLanguages.ENGLISH), "English is the first choice");
        for (int i = 0; i < AppLanguages.size(); i++) {
            boolean draft = "draft".equals(AppLanguages.STATUSES[i]);
            check(contains(drafts, i) == draft, AppLanguages.tag(i) + " is In progress exactly when it is a draft");
            check(contains(choices, i) == AppLanguages.shipped(i), AppLanguages.tag(i) + " is a choice exactly when it ships");
            if (draft) check(AppLanguages.resolve(AppLanguages.tag(i)).equals(AppLanguages.ENGLISH), AppLanguages.tag(i) + " is a draft and resolves to English");
        }
        System.out.println("LanguagePickerTest: " + checks + " checks passed (" + choices.length + " choices, " + drafts.length + " in progress)");
    }

    private static boolean contains(int[] values, int value) {
        for (int v : values) if (v == value) return true;
        return false;
    }

    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
