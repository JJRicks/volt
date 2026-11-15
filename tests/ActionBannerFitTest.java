package com.jjricks.volt;

/** ActionBannerFit: a subject page's banner keeps its pill's label whole and puts the pill under the words when needed. */
public final class ActionBannerFitTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        // The pill as the banners build it: 16 dp a side, never under 64 dp.
        require(ActionBannerFit.PILL_PAD_DP == 16 && ActionBannerFit.PILL_MIN_DP == 64 && ActionBannerFit.GAP_DP == 8, "the pill's padding, its narrowest and the gap");
        // The council's capture (review of #392): "Connect" (12.5 sp bold) is about 46 dp wide at 1x and 65 dp at 1.4x. At
        // 1x the 64 dp pill held it; at 1.4x the pill was the label alone (no padding) and cut its "t". With its padding
        // the pill is 78 dp at 1x and 97 dp at 1.4x, wider than the label by 32 dp at every size.
        require(ActionBannerFit.pill(46, 16, 64) == 78 && ActionBannerFit.pill(65, 16, 64) == 97, "Connect with its padding at 1x and 1.4x");
        require(ActionBannerFit.pill(20, 16, 64) == 64, "a short label: the pill's narrowest");
        for (float label = 0; label < 400; label += 7.5f)
            require(ActionBannerFit.pill(label, 16, 64) >= label + 32, label + " dp label: whole inside its padding");
        require(ActionBannerFit.pill(-5, -1, -1) == 0, "nonsense never makes a negative pill");
        // Longest word, by characters here (each 1 unit wide).
        ActionBannerFit.Width chars = String::length;
        require(ActionBannerFit.longestWord("Last seen (disconnected)", chars) == 14, "\"(disconnected)\" is the longest word");
        require(ActionBannerFit.longestWord("  Zuletzt   (getrennt) ", chars) == 10, "spaces around and between words are not words");
        require(ActionBannerFit.longestWord("", chars) == 0 && ActionBannerFit.longestWord(null, chars) == 0, "no words: 0");
        // The banner on the checked phones: the page is the window less 19 dp a side; the banner's 14 dp and 8 dp padding,
        // the 18 dp Bluetooth icon and its 10 dp gap leave the rest for the words and the pill. Words at 13 sp in Saira
        // Regular and the label at 12.5 sp in Saira Bold, as built, in the app's languages with the longest words.
        java.awt.Font regular = java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, new java.io.File(args[0]));
        java.awt.Font bold = java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, new java.io.File(args[1]));
        java.awt.font.FontRenderContext context = new java.awt.font.FontRenderContext(null, true, true);
        String[][] languages = {
                {"en", "Last seen (disconnected)", "Connect"},
                {"de", "Zuletzt (getrennt)", "Verbinden"},
                {"pt", "\u00daltima leitura (desconectado)", "Conectar"}};
        for (int window : new int[]{344, 360, 412})
            for (String[] language : languages)
                for (float scale : new float[]{1f, 1.4f}) {
                    float room = window - 2 * 19 - 14 - 8 - 18 - 10;
                    java.awt.Font words = regular.deriveFont(13 * scale), label = bold.deriveFont(12.5f * scale);
                    float word = ActionBannerFit.longestWord(language[1], text -> (float) words.getStringBounds(text, context).getWidth());
                    float pill = ActionBannerFit.pill((float) label.getStringBounds(language[2], context).getWidth(), ActionBannerFit.PILL_PAD_DP, ActionBannerFit.PILL_MIN_DP);
                    require(!ActionBannerFit.actionBelow(room, pill, ActionBannerFit.GAP_DP, word),
                            language[0] + " at " + window + " dp, " + scale + "x: the pill stays beside the words (they wrap beside it)");
                }
        // Narrower than any phone the app is checked on, or a larger text: the words' longest word no longer fits beside the
        // pill, so the pill goes under them instead of squeezing a word in two.
        require(ActionBannerFit.actionBelow(180, 97, 8, 120), "75 dp beside the pill for a 120 dp word: the pill goes under");
        require(!ActionBannerFit.actionBelow(225, 97, 8, 120), "exactly the word's width beside the pill: it stays beside");
        require(ActionBannerFit.actionBelow(224.5f, 97, 8, 120), "half a dp short: under");
        require(!ActionBannerFit.actionBelow(256, 97, 8, 0), "no words: beside");
        clockRow(regular, bold, context);
        allowRows(regular, bold, context);
        System.out.println("ActionBannerFitTest: " + checks + " checks passed");
    }

    private static float width(java.awt.Font font, String text, java.awt.font.FontRenderContext context) {
        return (float) font.getStringBounds(text, context).getWidth();
    }

    /**
     * The clock row on Lights & sound (council review of #506, findings 1 and 2): Sync is decided from the pill alone, with
     * the spinner's 30 dp always kept out of the name's room, so the row is the same whether a sync is pending or not; and
     * under the name the pill alone has the row's width (the spinner stays with the name), so its label is whole down to
     * a 280 dp window. The row's room is the window less 60 dp (page and card padding, measured on the emulator: 220 dp at
     * 280, 284 dp at 344, 352 dp at 412); the name is 15 sp with its 45 dp icon tile, the label 12.5 sp bold.
     */
    private static void clockRow(java.awt.Font regular, java.awt.Font bold, java.awt.font.FontRenderContext context) {
        float spinner = 30, gap = 10, tile = 45;
        String[][] languages = {{"en", "Scooter clock", "Sync"}, {"de", "Scooter-Uhr", "Synchronisieren"}, {"nl", "Klok van de step", "Synchroniseren"}};
        for (int window : new int[]{280, 300, 320, 344, 360, 412})
            for (float scale : new float[]{1f, 1.1f, 1.15f, 1.3f, 1.35f, 1.4f})
                for (String[] language : languages) {
                    float room = window - 60;
                    java.awt.Font name = regular.deriveFont(15 * scale), label = bold.deriveFont(12.5f * scale);
                    float word = ActionBannerFit.longestWord(language[1], text -> width(name, text, context)) + tile;
                    float pill = ActionBannerFit.pill(width(label, language[2], context), ActionBannerFit.PILL_PAD_DP, ActionBannerFit.PILL_MIN_DP);
                    String at = language[0] + " at " + window + " dp, " + scale + "x";
                    boolean below = ActionBannerFit.actionBelow(room, pill, gap, word, spinner);
                    // The decision never depends on the spinner showing: the same call either way, so only the inputs above.
                    require(below == ActionBannerFit.actionBelow(room, pill, gap, word, spinner), at + ": the same with the spinner shown and hidden");
                    if (!below) {
                        // Beside: the name keeps its longest word whole with the spinner showing (and so with it hidden).
                        require(room - spinner - pill - gap >= word, at + ": the name whole beside Sync while it syncs");
                    } else {
                        // Under the name: the pill alone on its line, after the name's indent where that still fits.
                        float indent = ActionBannerFit.stackedIndent(room, pill, tile);
                        require(pill <= room - indent, at + ": \"" + language[2] + "\" whole under the name");
                        require(room - spinner >= word, at + ": the name whole beside the spinner");
                    }
                }
        // The council's cases. German at 1.4x on 412 dp: under the name, syncing or not (main put it beside, then under).
        java.awt.Font name14 = regular.deriveFont(15 * 1.4f), label14 = bold.deriveFont(12.5f * 1.4f);
        float deWord = width(name14, "Scooter-Uhr", context) + tile, dePill = width(label14, "Synchronisieren", context) + 32;
        require(ActionBannerFit.actionBelow(352, dePill, gap, deWord, spinner), "de 412 dp 1.4x: Sync under the name in both states");
        require(!ActionBannerFit.actionBelow(352, dePill, gap, deWord) && ActionBannerFit.actionBelow(352 - spinner, dePill, gap, deWord),
                "de 412 dp 1.4x: deciding with the spinner's room counted only while it shows would move the pill (#506)");
        // German at 1.4x on 280 dp: 220 dp of room; the pill (about 164 dp) is whole after the 45 dp indent, and the
        // spinner no longer takes room from it (on #506 it left 145 dp and "Synchronisieren" broke).
        require(dePill <= 220 - ActionBannerFit.stackedIndent(220, dePill, tile) && 220 - tile - spinner < dePill, "de 280 dp 1.4x: Synchronisieren whole");
        require(ActionBannerFit.stackedIndent(150, 120, 45) == 0 && ActionBannerFit.stackedIndent(200, 120, 45) == 45, "the indent goes before the label does");
        for (float room = 0; room <= 400; room += 5)
            for (float pill = 0; pill <= 400; pill += 5)
                if (pill <= room) require(pill <= room - ActionBannerFit.stackedIndent(room, pill, 45), "a pill that fits the row keeps its whole label");
    }

    /**
     * The Allow rows of the Live charging and Battery alerts sheets (council review of #506, finding 3): on the same rule,
     * Allow goes under the text when the text's longest word no longer fits beside it. The row's room is the window less
     * 58 dp (the sheet's 18 dp a side and the row's 14 + 8 dp padding); the text is 13 sp, the label 12.5 sp bold.
     */
    private static void allowRows(java.awt.Font regular, java.awt.Font bold, java.awt.font.FontRenderContext context) {
        // word, label, window, text scale: each fitted beside the bare pill on main before #506, and would break beside the
        // padded pill: so the rule puts the pill under the text, where the word has the row's whole room.
        Object[][] cases = {
                {"Verbindungsbenachrichtigung", "Zulassen", 412, 1.35f}, {"Verbindungsbenachrichtigung", "Zulassen", 412, 1.4f},
                {"Verbindungsbenachrichtigung", "Zulassen", 344, 1.1f}, {"Verbindungsbenachrichtigung", "Zulassen", 344, 1.15f},
                {"accuwaarschuwingen", "Toestaan", 344, 1.4f},
                {"Benachrichtigungen", "Zulassen", 310, 1.4f}, {"Benachrichtigungen", "Zulassen", 320, 1.4f}, {"Benachrichtigungen", "Zulassen", 340, 1.4f}};
        for (Object[] c : cases) {
            String word = (String) c[0], allow = (String) c[1]; int window = (Integer) c[2]; float scale = (Float) c[3];
            float room = window - 58, w = width(regular.deriveFont(13 * scale), word, context), label = width(bold.deriveFont(12.5f * scale), allow, context);
            String at = word + " at " + window + " dp, " + scale + "x";
            require(room - Math.max(64, label) - ActionBannerFit.GAP_DP >= w, at + ": fitted beside the bare pill before #506");
            float pill = ActionBannerFit.pill(label, ActionBannerFit.PILL_PAD_DP, ActionBannerFit.PILL_MIN_DP);
            require(room - pill - ActionBannerFit.GAP_DP < w, at + ": would break beside the padded pill");
            require(ActionBannerFit.actionBelow(room, pill, ActionBannerFit.GAP_DP, w) && room >= w && room >= pill, at + ": Allow under the text, the word and the label whole");
        }
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
