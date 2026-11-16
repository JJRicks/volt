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
        System.out.println("ActionBannerFitTest: " + checks + " checks passed");
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
