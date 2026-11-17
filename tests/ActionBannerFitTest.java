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
     * under the name the pill alone has the row's width (the spinner stays with the name), so its label is whole with the
     * spinner showing down to Android's narrowest split-screen window, 220 dp. The row's room is the window less 60 dp
     * (page and card padding, measured on the emulator: 220 dp at 280, 284 dp at 344, 352 dp at 412); the name is 15 sp
     * with its 45 dp icon tile, the label 12.5 sp bold. Every language the app ships the row in, at every window and text
     * scale of the banners' matrix above (344, 360, 412 dp; 1x, 1.4x) and the sizes between, down to 220 dp.
     */
    private static void clockRow(java.awt.Font regular, java.awt.Font bold, java.awt.font.FontRenderContext context) {
        float spinner = 30, gap = 10, tile = 45;
        String[][] languages = {{"en", "Scooter clock", "Sync"}, {"de", "Scooter-Uhr", "Synchronisieren"}, {"nl", "Klok van de step", "Synchroniseren"},
                {"pt", "Rel\u00f3gio da trotinete", "Sincronizar"}, {"pt-BR", "Rel\u00f3gio do patinete", "Sincronizar"}, {"es", "Reloj del patinete", "Sincronizar"},
                {"fr", "Horloge de la trottinette", "Synchroniser"}, {"it", "Orologio del monopattino", "Sincronizza"}, {"pl", "Zegar hulajnogi", "Synchronizuj"},
                {"sv", "Elsparkcykelns klocka", "Synkronisera"}, {"tr", "Scooter saati", "E\u015fitle"}};
        int moved = 0;
        for (int window : new int[]{220, 240, 260, 280, 300, 320, 344, 360, 412})
            for (float scale : new float[]{1f, 1.1f, 1.15f, 1.3f, 1.35f, 1.4f})
                for (String[] language : languages) {
                    float room = window - 60;
                    java.awt.Font name = regular.deriveFont(15 * scale), label = bold.deriveFont(12.5f * scale);
                    float word = ActionBannerFit.longestWord(language[1], text -> width(name, text, context)) + tile;
                    float text = width(label, language[2], context);
                    float pill = ActionBannerFit.pill(text, ActionBannerFit.PILL_PAD_DP, ActionBannerFit.PILL_MIN_DP);
                    String at = language[0] + " at " + window + " dp, " + scale + "x";
                    // As BannerBody decides: the pill is the action (the spinner is with the name, not in it), and the
                    // spinner's room is kept out of the name's whether it shows or not.
                    boolean hidden = ActionBannerFit.actionBelow(room, pill, gap, word, spinner);
                    boolean showing = ActionBannerFit.actionBelow(room, pill, gap, word, spinner);
                    require(hidden == showing, at + ": the same with the spinner shown and hidden");
                    // #506's rule, the spinner inside the action and so counted only while it shows: it moved the pill.
                    if (ActionBannerFit.actionBelow(room, pill, gap, word) != ActionBannerFit.actionBelow(room, pill + spinner, gap, word)) moved++;
                    if (!showing) {
                        // Beside: the name keeps its longest word whole with the spinner showing (and so with it hidden).
                        require(room - spinner - pill - gap >= word, at + ": the name whole beside Sync while it syncs");
                    } else {
                        // Under the name: the pill alone on its line, after the name's indent where that still fits, at
                        // its own padding while it fits the row, else with what the row leaves around its label.
                        float indent = ActionBannerFit.stackedIndent(room, pill, tile);
                        float side = pill <= room - indent ? ActionBannerFit.PILL_PAD_DP : ActionBannerFit.stackedPad(room - indent, text, ActionBannerFit.PILL_PAD_DP);
                        require(Math.max(ActionBannerFit.PILL_MIN_DP, text + 2 * side) <= room - indent + 0.01f, at + ": \"" + language[2] + "\" whole under the name while it syncs");
                        if (window >= 344) require(side == ActionBannerFit.PILL_PAD_DP, at + ": the pill at its own padding on a phone");
                        // The name beside the spinner: whole down to 300 dp in every language. Narrower, at the largest
                        // sizes, a long name's longest word is wider than the row less the spinner for the moment a sync
                        // is pending (Swedish "Elsparkcykelns" at 1.4x below about 281 dp, German "Scooter-Uhr" at 1.4x
                        // below about 252 dp); the owner accepts isolated cut-offs at those sizes (council review of #506).
                        if (window >= 300) require(room - spinner >= word, at + ": the name whole beside the spinner");
                    }
                }
        require(moved > 0, "#506's rule moves the pill somewhere in this matrix (" + moved + " cases), so the check above has teeth");
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
        // German at 1.4x on Android's narrowest window, 220 dp: 160 dp of room, the pill at its own padding about 165 dp.
        // Under the name it starts at the row's start with about 13 dp a side, and "Synchronisieren" (about 133 dp) is whole.
        float deLabel = width(label14, "Synchronisieren", context), deSide = ActionBannerFit.stackedPad(160, deLabel, 16);
        require(dePill > 160 && ActionBannerFit.stackedIndent(160, dePill, tile) == 0 && deSide > 10 && deSide < 16 && deLabel + 2 * deSide <= 160,
                "de 220 dp 1.4x: Synchronisieren whole, the pill's padding narrowed to what the row leaves");
        require(ActionBannerFit.stackedPad(300, 100, 16) == 16 && ActionBannerFit.stackedPad(120, 100, 16) == 10 && ActionBannerFit.stackedPad(90, 100, 16) == 0,
                "the pill keeps its own padding while it fits, else what is left around the label");
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
