package com.jjricks.volt;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * The Home header's Connecting… status (DashboardWords.connectingText): the base English, the spinner's U+FFFC as its
 * last character, the word following the installed text source, and DashboardScreen showing it whenever the app's
 * language is not English (it keeps its English line only where Fmt.legacy() holds, and there the two agree).
 */
public final class ConnectingLabelTest {
    private static int checks;
    private static final char SPINNER = 0xFFFC;

    private static final T.Source ECHO = new T.Source() {
        @Override public String string(int id) { return "⟦" + T.key(id) + "⟧"; }
        @Override public String plural(int id, long quantity) { return "⟦" + T.key(id) + "⟧"; }
        @Override public String language() { return "es"; }
    };

    public static void main(String[] args) throws Exception {
        try {
            T.install(null);
            check(Fmt.legacy(), "English is the legacy path");
            check(("Connecting…  " + SPINNER).equals(DashboardWords.connectingText()),
                    "English keeps the base text: " + DashboardWords.connectingText());
            T.install(ECHO);
            String shown = DashboardWords.connectingText();
            check(shown.equals("⟦ble_status_connecting⟧  " + SPINNER), "the word follows the source: " + shown);
            check(shown.charAt(shown.length() - 1) == SPINNER, "the spinner's place stays last");
            check(!Fmt.legacy(), "a non-English source leaves the legacy English line");
        } finally {
            T.install(null);
        }
        String screen = new String(Files.readAllBytes(Paths.get("src/com/jjricks/volt/DashboardScreen.java")),
                StandardCharsets.UTF_8);
        check(screen.contains("if (!Fmt.legacy()) text = new android.text.SpannableString(DashboardWords.connectingText());"),
                "outside English the header's spinner text comes from DashboardWords");
        int literals = screen.split("SpannableString\\(\"Connecting", -1).length - 1;
        check(literals == 1, "only the one legacy English line builds a Connecting literal: " + literals);
        System.out.println("ConnectingLabelTest: " + checks + " checks passed (Home header Connecting… text)");
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        checks++;
    }
}
