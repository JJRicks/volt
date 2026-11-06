package com.jjricks.volt;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * The Home header's Connecting… status (DashboardWords.connectingText): the base English, the spinner's U+FFFC as its
 * last character, the word following the installed text source, and DashboardScreen building it from no literal.
 */
public final class ConnectingLabelTest {
    private static int checks;

    private static final T.Source ECHO = new T.Source() {
        @Override public String string(int id) { return "⟦" + T.key(id) + "⟧"; }
        @Override public String plural(int id, long quantity) { return "⟦" + T.key(id) + "⟧"; }
        @Override public String language() { return "es"; }
    };

    public static void main(String[] args) throws Exception {
        try {
            T.install(null);
            check("Connecting…  ￼".equals(DashboardWords.connectingText()),
                    "English keeps the base text: " + DashboardWords.connectingText());
            T.install(ECHO);
            String shown = DashboardWords.connectingText();
            check(shown.equals("⟦ble_status_connecting⟧  ￼"), "the word follows the source: " + shown);
            check(shown.charAt(shown.length() - 1) == '￼', "the spinner's place stays last");
        } finally {
            T.install(null);
        }
        String screen = new String(Files.readAllBytes(Paths.get("src/com/jjricks/volt/DashboardScreen.java")),
                StandardCharsets.UTF_8);
        check(!screen.contains("SpannableString(\"Connecting"), "DashboardScreen builds no Connecting literal");
        check(screen.contains("new android.text.SpannableString(DashboardWords.connectingText())"),
                "the header's spinner text comes from DashboardWords");
        System.out.println("ConnectingLabelTest: " + checks + " checks passed (Home header Connecting… text)");
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        checks++;
    }
}
