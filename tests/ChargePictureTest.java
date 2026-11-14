package com.jjricks.volt;

/**
 * The still folded charging pictures (ChargeFolded) and the percentage's shade per picture type (ChargePercentShade),
 * checked against the approved prototype (the contributor's charging pictures, 14 Nov 2026 on #383; scratchpad
 * proto-charge, models.json): its phone is 344 dp wide with 18 dp padding, the folded picture 246.4 dp wide from
 * 49 dp, the tyres at 362.6 dp, the percentage's 60 px text from 120.5 to 215.5 dp on a 268 dp baseline.
 */
public final class ChargePictureTest {
    private static int checks;

    public static void main(String[] args) {
        folded();
        placement();
        halo();
        cutoff();
        largeText();
        System.out.println("ChargePicture: " + checks + " checks passed");
    }

    private static void folded() {
        String[] keys = {"st3pro", "n65i", "gt3max", "gt5max", "xt5ultra"};
        require(ChargeFolded.all().length == keys.length, "five folded pictures");
        for (String key : keys) {
            ChargeFolded.Model m = ChargeFolded.forKey(key);
            require(m != null && m.key.equals(key), key + " has a folded picture");
            require(ChargeWireModels.forKey(key) == null, key + " is not animated");
            require(m.tyreY > m.height * .95 && m.tyreY < m.height, key + ": the tyres at the cut-out's foot");
            int[] size = WebpSize.of("res/drawable-nodpi/" + key + "_folded.webp");
            require(size[0] == m.width && size[1] == m.height, key + ": the bundled cut-out is the table's size");
            require(new java.io.File("res/drawable-nodpi/" + key + ".webp").isFile(), key + " is a bundled photo key");
        }
        for (String other : new String[] {"ut5max", "ut5ultrax", "", "XT5 Ultra"})
            require(ChargeFolded.forKey(other) == null, "'" + other + "' has no folded picture");
        require(ChargeFolded.forKey(null) == null, "no model, no picture");
    }

    /** Each model's place on the prototype's phone (models.json phone_layout_dp), at 3 px per dp. */
    private static void placement() {
        double[][] expected = {{49.0, 252.6}, {49.0, 242.1}, {49.0, 255.1}, {49.0, 242.3}, {49.0, 245.2}};
        ChargeFolded.Model[] all = ChargeFolded.all();
        // The stage's floor: the tyres at 362.6 dp on screen; the stage's top anywhere above (here at 200 dp).
        float floor = (float) ((362.6 - 200) * 3);
        for (int i = 0; i < all.length; i++) {
            ChargeFolded.Model m = all[i];
            ChargeWireModels.Placement p = ChargeFolded.place(m, 924, floor);
            near(p.scale * m.width / 3, 246.4, .05, m.key + ": 80% of the 308 dp content width");
            near(p.left / 3 + 18, expected[i][0], .25, m.key + ": centred (its left edge)");
            near(p.top / 3 + 200, expected[i][1], .15, m.key + ": its top as the prototype's");
            near(p.top + m.tyreY * p.scale, floor, .01, m.key + ": the tyres on the floor");
            // A short stage: smaller, centred, still on the floor.
            ChargeWireModels.Placement q = ChargeFolded.place(m, 924, 150);
            near(q.top, 0, .01, m.key + ": a short stage: up to its top");
            near(q.left + q.scale * m.width / 2, 462, .01, m.key + ": still centred");
        }
        require(ChargeFolded.place(ChargeFolded.N65I, 0, 10).scale == 0, "no room, no picture");
        // The tyres stand where the wire picture's do: its gap above the track for every picture.
        near(362.6, 372 - ChargeWireModels.UT5_MAX.tyreGapDp, .05, "the shared floor: 9.4 dp over the track");
    }

    private static final float LEFT = 120.48f, RIGHT = 215.53f, BASE = 268, SIZE = 60;

    private static void halo() {
        float[] h = ChargePercentShade.halo(LEFT, RIGHT, BASE, SIZE);
        near(h[0], 172, .05, "the halo's centre: the prototype's box centre across");
        near(h[1], 254, .05, "and down (its box 211 to 297)");
        near(h[2] * 2, 168, .01, "168 dp across");
        // The number 4 dp left of the content's centre (172): its advance's centre at 168, the halo on the centre.
        near((LEFT + RIGHT) / 2 + ChargePercentShade.percentLeft(SIZE), 172, .05, "the digits sit 4 dp left of the centre");
        near(ChargePercentShade.HALO_ALPHAS[0], .47, 1e-6, "47% at the centre");
        near(ChargePercentShade.HALO_ALPHAS[1], .24, 1e-6, "24% at 55%");
        near(ChargePercentShade.HALO_STOPS[1], .55, 1e-6, "the middle stop");
    }

    private static void cutoff() {
        float[] box = ChargePercentShade.cutoffBox(LEFT, RIGHT, BASE, SIZE);
        // The prototype's ::before: 3 px left of the padded box (4 px padding) and 5 px in from its right (12 px), 9 px
        // below its top (211) and 20.5 px above its bottom (297).
        near(box[0], 113.48, .01, "the box's left: 7 dp outside the text");
        near(box[2], 222.53, .01, "its right: 7 dp outside");
        near(box[1], 220, .01, "its top");
        near(box[3], 276.5, .01, "its bottom");
        float r = ChargePercentShade.cutoffRadius(SIZE), sigma = ChargePercentShade.cutoffSigma(SIZE);
        near(r, 14, 1e-4, "radius 14");
        near(sigma, 5, 1e-4, "blurred by 5 (CSS blur: one standard deviation)");
        double mid = (box[1] + box[3]) / 2;
        // About 92% opaque at the glyphs' edges, 7 dp inside the box's.
        near(ChargePercentShade.cutoffAlpha(box, r, sigma, LEFT, mid), .92, .01, "92% at the text's left edge");
        near(ChargePercentShade.cutoffAlpha(box, r, sigma, RIGHT, mid), .92, .01, "92% at its right edge");
        near(ChargePercentShade.cutoffAlpha(box, r, sigma, (LEFT + RIGHT) / 2, mid), 1, .001, "opaque behind the digits");
        near(ChargePercentShade.cutoffAlpha(box, r, sigma, box[0], mid), .5, .001, "half at the box's edge");
        // Gone about 17 dp beyond the glyphs.
        require(ChargePercentShade.cutoffAlpha(box, r, sigma, LEFT - 17, mid) < .03, "under 3% 17 dp beyond the text");
        require(ChargePercentShade.cutoffAlpha(box, r, sigma, RIGHT + 17, mid) < .03, "on both sides");
        require(ChargePercentShade.cutoffAlpha(box, r, sigma, (LEFT + RIGHT) / 2, box[3] + 10) < .03, "and below");
        near(ChargePercentShade.cutoffReach(SIZE), 15, 1e-4, "drawn out to 3 sigma");
        require(ChargePercentShade.cutoffAlpha(box, r, sigma, box[0] - 15, mid) < .002, "nothing left at its reach");
        // The corners are round: the box's own corner is under half, a point inside the radius on the diagonal over.
        require(ChargePercentShade.cutoffAlpha(box, r, sigma, box[0], box[1]) < .2, "a round corner");
        // The mask: rows of 0-255 alphas, symmetric across.
        int w = 40, hgt = 10;
        int[] mask = ChargePercentShade.cutoffMask(box, r, sigma, box[0] - 20, (float) mid - 5, w, hgt);
        require(mask.length == w * hgt, "one alpha per pixel");
        require(mask[5 * w] < 5 && mask[5 * w + w - 1] > 240, "fading out to the left of the box, solid inside");
        near(ChargePercentShade.phi(0), .5, 1e-7, "phi(0)");
        near(ChargePercentShade.phi(1.4), .91924, 1e-4, "phi(1.4)");
        near(ChargePercentShade.phi(-2), .02275, 1e-4, "phi(-2)");
    }

    /** At large text the percentage grows; the halo and the cut-off grow with it and keep their shape. */
    private static void largeText() {
        float k = 1.4f;
        float left = 172 - (172 - LEFT) * k, right = 172 + (RIGHT - 172) * k;
        float[] box = ChargePercentShade.cutoffBox(left, right, BASE, SIZE * k);
        near(box[0], left - 7 * k, 1e-3, "the cut-off's side grows");
        near(BASE - box[1], 48 * k, 1e-3, "its top");
        near(box[3] - BASE, 8.5 * k, 1e-3, "its bottom");
        near(ChargePercentShade.cutoffSigma(SIZE * k), 5 * k, 1e-4, "its blur");
        float r = ChargePercentShade.cutoffRadius(SIZE * k), sigma = ChargePercentShade.cutoffSigma(SIZE * k);
        near(ChargePercentShade.cutoffAlpha(box, r, sigma, left, (box[1] + box[3]) / 2), .92, .01, "still 92% at the glyphs");
        near(ChargePercentShade.halo(left, right, BASE, SIZE * k)[2] * 2, 168 * k, 1e-3, "the halo grows");
        float[] none = ChargePercentShade.cutoffBox(0, 0, 0, 0);
        require(none[0] == 0 && none[2] == 0, "no text, no box");
    }

    private static void near(double actual, double expected, double tolerance, String message) {
        require(Math.abs(actual - expected) <= tolerance, message + ": " + actual + " (expected " + expected + ")");
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static final class WebpSize {
        static int[] of(String path) { return ChargeWireTest.WebpSize.of(path); }
    }
}
