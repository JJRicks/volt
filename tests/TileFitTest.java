package com.jjricks.volt;

import java.util.function.ToDoubleFunction;

/** TileFit: Just back's tile names fit whole, by the tiles' layout, and values take the first rung that fits (council early review of part 9). */
public final class TileFitTest {
    private static int checks;
    /** One unit per character, as a monospace font would measure. */
    private static final ToDoubleFunction<String> CHARS = String::length;

    public static void main(String[] args) {
        // Note 2: Swedish "Medelhastighet" (14) in a 12-wide tile would break inside the word; it fits only whole.
        require(!TileFit.fits("Medelhastighet", CHARS, 12, 2) && TileFit.fits("Medelhastighet", CHARS, 14, 2), "A word wider than the room never fits, at any line count");
        // Polish "Spadek poziomu baterii": three lines in a 9-wide tile; two lines are allowed, three are not.
        require(!TileFit.fits("Spadek poziomu baterii", CHARS, 9, 2) && TileFit.fits("Spadek poziomu baterii", CHARS, 9, 3), "A name needing three lines does not fit two");
        require(TileFit.fits("Spadek poziomu baterii", CHARS, 14, 2), "Two lines at word breaks fit");
        require(TileFit.fits("Battery drop", CHARS, 12, 1) && !TileFit.fits("Battery drop", CHARS, 11, 1) && TileFit.fits("Battery drop", CHARS, 7, 2),
                "A line holds words joined by a space while they fit, then wraps at the space");
        // Note 3: a value is one line; both units fall back to the first unit alone.
        String[] dual = {"15.5 mph / 25.0 km/h", "15.5 mph"};
        require(TileFit.pick(dual, CHARS, 12, 1) == 1, "Both units wider than the tile: the first unit alone");
        require(TileFit.pick(dual, CHARS, 20, 1) == 0, "Room for both: both");
        require(TileFit.pick(new String[]{"abcdefghij", "abcdefgh"}, CHARS, 3, 2) == 1, "When nothing fits, the last rung (the caller lets it ellipsize)");
        require(TileFit.fits("", CHARS, 0, 1) && TileFit.fits(null, CHARS, 5, 1), "Empty text fits");
        require(!TileFit.fits("a", CHARS, 0, 1) && !TileFit.fits("a", CHARS, 5, 0), "No room or no lines: nothing fits");
        require(TileFit.fits("  Ride   time ", CHARS, 9, 1), "Runs of spaces wrap as one");
        // The layout step for names: three in a row while every name fits a third whole, else two.
        require(TileFit.columns(new String[]{"Ride time", "Avg speed", "Battery drop"}, CHARS, 12, 2) == 3, "English names fit thirds: three in a row");
        require(TileFit.columns(new String[]{"Tripptid", "Medelhastighet", "Minskad batterinivå"}, CHARS, 12, 2) == 2,
                "Swedish Medelhastighet (14) does not fit a third (12): two in a row");
        require(TileFit.columns(new String[]{"Fahrzeit", "Ø Tempo", "Akkuverlust"}, CHARS, 10, 2) == 2, "German Akkuverlust (11) in 10: two in a row");
        require(TileFit.columns(new String[]{"Czas jazdy", "Śr. prędkość", "Spadek poziomu baterii"}, CHARS, 9, 2) == 2,
                "Polish battery name needing three lines: two in a row");
        require(TileFit.fits("Spadek poziomu baterii", CHARS, 15, 2) && TileFit.fits("Medelhastighet", CHARS, 15, 2),
                "and half a row (about twice a third) holds them whole");
        // Two rows must fit the tiles' fixed-height area (large text): else three in a row, names ellipsized.
        String[] swedish = {"Tripptid", "Medelhastighet", "Minskad batterinivå"};
        require(TileFit.columns(swedish, CHARS, 12, 2, 200, 180) == 3, "Two rows taller than the room: three in a row");
        require(TileFit.columns(swedish, CHARS, 12, 2, 180, 180) == 2, "Two rows that fit the room: two in a row");
        require(TileFit.columns(new String[]{"Ride time", "Avg speed", "Battery drop"}, CHARS, 12, 2, 0, 0) == 3, "Names that fit thirds stay three in a row");
        // The council's second review of part 9, finding 3: when two rows are taller than the room, two rows with the tight
        // padding before three in a row, and three in a row only with names cut whole (one line, an ellipsis), never broken.
        require(TileFit.layout(swedish, CHARS, 12, 2, 180, 160, 200) == TileFit.TWO, "Two rows fit: two in a row");
        require(TileFit.layout(swedish, CHARS, 12, 2, 200, 170, 180) == TileFit.TWO_TIGHT, "Only the tight rows fit: two tight rows");
        require(TileFit.layout(swedish, CHARS, 12, 2, 200, 190, 180) == TileFit.THREE_CUT, "Neither fits: three in a row, names cut whole");
        require(TileFit.layout(new String[]{"Ride time", "Avg speed", "Battery"}, CHARS, 12, 2, 999, 999, 0) == TileFit.THREE, "Names that fit thirds: three in a row");
        require(TileFit.nameLines("Medelhastighet", CHARS, 12, 2) == 1, "A word wider than the tile: one line, ending in an ellipsis, never broken inside");
        require(TileFit.nameLines("Medelhastighet", CHARS, 14, 2) == 2 && TileFit.nameLines("Spadek poziomu baterii", CHARS, 9, 2) == 2,
                "Words that fit: up to two lines, wrapped at spaces");
        require(TileFit.nameLines("Vitesse moyennement", CHARS, 10, 2) == 1, "One word too wide is enough");
        require(TileFit.nameLines("", CHARS, 1, 2) == 2 && TileFit.nameLines(null, CHARS, 1, 2) == 2, "No name: the usual lines");
        System.out.println("TileFit: " + checks + " checks passed");
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
