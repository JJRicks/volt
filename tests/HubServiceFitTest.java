package com.jjricks.volt;

/**
 * HubServiceFit: where a Service card line puts the check's status (hub layout plan 1.11, PR 13; #394 early review, 3).
 * Widths in dp with a fixed per-character estimate, at the sizes the maintainers check: 344x882 and 725x604 dp, 1x and
 * 1.4x text. The card only shows on the wide Home (725 dp at 1x: a 328 dp scooter pane); at 344 dp and at 725 dp with
 * 1.4x text Home has one pane and no card, so those rows check the rule on the room such a card would have.
 */
public final class HubServiceFitTest {
    private static int checks;

    public static void main(String[] args) {
        // 725x604 dp at 1x: the scooter pane is 328 dp, the card pads 14 dp a side, the dot and its gap take 20 dp.
        float wide = body(328, 1f);
        require(Math.abs(wide - 280) < .01f, "725 dp, 1x: a 280 dp body: " + wide);
        float gap = HubServiceFit.GAP_DP;
        // The mock-up's lines fit beside each other.
        require(HubServiceFit.beside(wide, gap, w("40 mi left", 13.5f, 1f), longest("Tyre pressure", 1f)), "Tyre pressure beside 40 mi left");
        require(HubServiceFit.beside(wide, gap, w("18 days left", 13.5f, 1f), longest("Brakes", 1f)), "Brakes beside 18 days left");
        // The review's German case: the status is long, Klappmechanismus does not fit what it leaves: under the name.
        float status = w("Warten auf den Kilometerzähler", 13.5f, 1f);
        float word = longest("Schrauben & Klappmechanismus", 1f);
        require(!HubServiceFit.beside(wide, gap, status, word), "German: the status goes under the name: " + status + " + " + word);
        // Under the name, the longest word has the whole body.
        require(word <= wide, "Klappmechanismus fits the body on its own");

        // 725 dp at 1.4x text is the phone layout (725 / 1.4 < 650): no card; a 1.4x line in the same pane would still
        // keep a short English pair side by side and move the German status under its name.
        float wideLarge = body(328, 1.4f);
        require(HubServiceFit.beside(wideLarge, gap * 1.4f, w("40 mi left", 13.5f, 1.4f), longest("Tyre pressure", 1.4f)), "1.4x: English beside");
        require(!HubServiceFit.beside(wideLarge, gap * 1.4f, w("Warten auf den Kilometerzähler", 13.5f, 1.4f), longest("Schrauben & Klappmechanismus", 1.4f)), "1.4x: German under");

        // 344x882 dp (the phone's 308 dp column) at 1x and 1.4x: the same answers.
        float phone = body(308, 1f), phoneLarge = body(308, 1.4f);
        require(HubServiceFit.beside(phone, gap, w("40 mi left", 13.5f, 1f), longest("Tyre pressure", 1f)), "344 dp, 1x: beside");
        require(!HubServiceFit.beside(phoneLarge, gap * 1.4f, w("Warten auf den Kilometerzähler", 13.5f, 1.4f), longest("Schrauben & Klappmechanismus", 1.4f)), "344 dp, 1.4x: German under");

        // Second review, 2: in French at 725 dp the tread reminder's longest word ("roulement") fits beside "En attente du
        // kilométrage", but the whole name needs three lines there: it goes under, where it takes two at most.
        String tread = "Bande de roulement des pneus", waiting = "En attente du kilométrage";
        float french = w(waiting, 13.5f, 1f);
        require(HubServiceFit.beside(wide, gap, french, longest(tread, 1f)), "French: the longest word alone would fit beside");
        require(lines(tread, wide - gap - french) == 3, "French: the name needs three lines beside the status: " + lines(tread, wide - gap - french));
        require(!HubServiceFit.beside(wide, gap, french, tread, name(1f)), "French: the status goes under the tread reminder's name");
        require(lines(tread, wide) <= HubServiceFit.NAME_LINES, "French: under, the name fits its two lines");
        // The emulator's long custom name, at 725 dp (a 280 dp body) and 660 dp (the 650 dp floor's pane: 320 dp, a
        // 272 dp body), beside a short status: three lines beside it, so the status goes under and the name fits two.
        String custom = "Check the folding mechanism and stem bolts";
        for (float body : new float[] {wide, body(320, 1f)}) {
            float days = w("18 days left", 13.5f, 1f);
            require(!HubServiceFit.beside(body, gap, days, custom, name(1f)), "Custom name at " + body + " dp: the status goes under");
            require(lines(custom, body) <= HubServiceFit.NAME_LINES, "Custom name at " + body + " dp: two lines under the status");
        }
        // The mock-up's pairs still sit side by side by the whole-name rule.
        require(HubServiceFit.beside(wide, gap, w("40 mi left", 13.5f, 1f), "Tyre pressure", name(1f)), "Whole name: Tyre pressure beside 40 mi left");
        require(HubServiceFit.beside(wide, gap, w("18 days left", 13.5f, 1f), "Brakes", name(1f)), "Whole name: Brakes beside 18 days left");
        // Two lines beside are fine: "Schrauben & Klappmechanismus" beside a short status.
        require(HubServiceFit.beside(wide, gap, w("3 Tage", 13.5f, 1f), "Schrauben & Klappmechanismus", name(1f)), "German: two lines beside a short status");
        // The whole-name rule never keeps beside what the longest-word rule moves under.
        require(!HubServiceFit.beside(wide, gap, status, "Schrauben & Klappmechanismus", name(1f)), "German: under by the whole-name rule too");

        // lines: greedy at spaces, each line measured whole; a word wider than the room takes a line of its own.
        require(HubServiceFit.lines("ab cd ef", 5, String::length) == 2, "ab cd | ef");
        require(HubServiceFit.lines("ab cd ef", 8, String::length) == 1, "ab cd ef on one line");
        require(HubServiceFit.lines("abcdefgh ab", 4, String::length) == 2, "A too-wide word on its own line");
        require(HubServiceFit.lines("  ", 4, String::length) == 0 && HubServiceFit.lines(null, 4, String::length) == 0, "No lines for no words");

        // Edges: no room never fits; a status that leaves exactly the word fits.
        require(!HubServiceFit.beside(0, 0, 0, 0), "No room");
        require(HubServiceFit.beside(100, 10, 40, 50), "Exactly fits");
        require(!HubServiceFit.beside(100, 10, 40, 50.5f), "Half a dp over does not");
        // longestWord splits at spaces only and ignores runs of them.
        require(HubServiceFit.longestWord("  ab   abcd a ", String::length) == 4, "Longest word");
        require(HubServiceFit.longestWord(null, String::length) == 0 && HubServiceFit.longestWord("", String::length) == 0, "No words");
        System.out.println("HubServiceFitTest: " + checks + " checks passed");
    }

    /** The card body's width (dp) in a pane this wide: 14 dp padding a side, the dot and its gap (fixed dp). */
    private static float body(float pane, float scale) {
        return pane - 2 * 14 - (HubServiceFit.DOT_DP + HubServiceFit.DOT_GAP_DP);
    }

    /** An estimate of a text's width (dp): 0.55 em a character, at size sp and the text scale. */
    private static float w(String text, float sp, float scale) { return text.length() * sp * scale * .55f; }

    /** The name's width rule at a text scale. */
    private static HubServiceFit.Width name(float scale) { return t -> w(t, HubServiceFit.NAME_SP, scale); }

    /** The name's lines (at 1x) in a line this wide. */
    private static int lines(String text, float room) { return HubServiceFit.lines(text, room, name(1f)); }

    private static float longest(String text, float scale) {
        return HubServiceFit.longestWord(text, t -> w(t, HubServiceFit.NAME_SP, scale));
    }

    private static void require(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }
}
