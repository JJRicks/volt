package com.jjricks.volt;

/**
 * The animated charging picture's per-model table (Clean Home's charging view, the contributor's charging animation
 * of 14 Nov 2026 on #383): which models have one, and each one's measured picture. Pure Java; the view
 * (DashboardScreen.ChargingHero with ChargeWireView) picks the bundled cut-out by {@link Model#key} and draws the rest
 * from these numbers, so another model is one more entry here plus its cut-out. Models whose picture is a still folded
 * photo are in ChargeFolded.
 *
 * <p>Every coordinate is in the cut-out's own pixels (x to the right, y down), as measured on the source render: the
 * cable's centre line as two cubic Bézier segments from the charging plug's tip to the picture's right edge, the
 * battery bay's cells box, and the dark panel's edges around it.
 */
final class ChargeWireModels {
    private ChargeWireModels() {}

    /** One model's picture. */
    static final class Model {
        /** The model's photo key (ModelRegistry.photo), e.g. "ut5max". */
        final String key;
        /** The cut-out's size in pixels. */
        final int width, height;
        /**
         * The cable's centre line: Bézier segments of four points each, {x0,y0, x1,y1, x2,y2, x3,y3}, from the plug's
         * tip to the picture's right edge, each starting where the last ends.
         */
        final double[][] wire;
        /** The cells' box {left, top, right, bottom}. */
        final double[] cells;
        /**
         * The dark panel behind the cells, out to the bay's inner edges: its top this far above the cells' box, its
         * bottom this far below, its right edge this far right of it; its left edge leans, this far left of the box at
         * the top and at the bottom; and its corners' radius.
         */
        final double panelAbove, panelBelow, panelRight, panelLeftTop, panelLeftBottom, panelRadius;
        /** The cable's width (its core and anti-aliased edge in the cut-out). */
        final double cableWidth;
        /**
         * Where the picture sits in the stage (Clean's charging view, the content width): its width as a share of the
         * content width and its centre's place across it, as the prototype draws it on a 344 dp phone (283.6 dp wide
         * from 21.3 dp on a 308 dp content width, about 92%); it shrinks to fit a shorter stage (ChargePictureFit).
         */
        final double widthShare, centreShare;
        /** The tyres' gap above the track's top (dp). */
        final double tyreGapDp;

        Model(String key, int width, int height, double[][] wire, double[] cells,
              double panelAbove, double panelBelow, double panelRight, double panelLeftTop, double panelLeftBottom,
              double panelRadius, double cableWidth, double widthShare, double centreShare, double tyreGapDp) {
            this.key = key; this.width = width; this.height = height; this.wire = wire; this.cells = cells;
            this.panelAbove = panelAbove; this.panelBelow = panelBelow; this.panelRight = panelRight;
            this.panelLeftTop = panelLeftTop; this.panelLeftBottom = panelLeftBottom; this.panelRadius = panelRadius;
            this.cableWidth = cableWidth; this.widthShare = widthShare; this.centreShare = centreShare;
            this.tyreGapDp = tyreGapDp;
        }

        /** Another model with exactly this one's picture geometry (its own cut-out over the same body). */
        Model(String key, Model same) {
            this(key, same.width, same.height, same.wire, same.cells, same.panelAbove, same.panelBelow, same.panelRight,
                    same.panelLeftTop, same.panelLeftBottom, same.panelRadius, same.cableWidth, same.widthShare,
                    same.centreShare, same.tyreGapDp);
        }
    }

    /**
     * The UT5 MAX: a NAVEE marketing render (1600 x 833), cut out 1438 x 691 from (162, 82) with the stem faded at the
     * top, the cable and the battery pack redrawn grey. The numbers are the prototype's (scratchpad proto-charge,
     * wire-path.json less the cut's offset): the panel's lip 10.4 px above the cells, the left wall leaning from 1.8 to
     * 6 px left of them, the pale sill ending 2 px below and the right edge 1 px outside.
     */
    static final Model UT5_MAX = new Model("ut5max", 1438, 691,
            new double[][] {
                    {412.0, 436.7, 565.67, 425.13, 719.33, 365.97, 873.0, 343.77},
                    {873.0, 343.77, 1061.33, 316.55, 1249.67, 344.84, 1438.0, 373.12}},
            new double[] {434, 442, 776, 551},
            10.4, 2, 1, 1.8, 6, 2, 7.6,
            283.6 / 308, (21.3 + 283.6 / 2) / 308, 9.4);

    /**
     * The UT5 Ultra X: the UT5 MAX's cut-out with the Ultra X render's gold parts pieced in (registered onto the MAX's
     * render), so the same geometry, cable, bay, cells and layout; its alpha (the stem fade included) is the MAX's byte
     * for byte, and the gold dims toward the page along the stem so it fades out where the MAX's stem does.
     */
    static final Model UT5_ULTRA_X = new Model("ut5ultrax", UT5_MAX);

    /** Each model with an animated picture, by its photo key. */
    private static final Model[] ALL = {UT5_MAX, UT5_ULTRA_X};

    /** The model's picture, or null when it keeps the plain photo. */
    static Model forKey(String key) {
        if (key == null) return null;
        for (Model model : ALL) if (model.key.equals(key)) return model;
        return null;
    }

    static Model[] all() { return ALL.clone(); }

    /** Where the picture goes in a stage: its scale (view pixels per cut-out pixel) and its top-left corner. */
    static final class Placement {
        final float scale, left, top;
        Placement(float scale, float left, float top) { this.scale = scale; this.left = left; this.top = top; }
        float width(Model m) { return m.width * scale; }
        float height(Model m) { return m.height * scale; }
        @Override public String toString() { return "scale=" + scale + " left=" + left + " top=" + top; }
    }

    /**
     * The picture {@code box} pixels tall (ChargePictureFit's box) in a stage {@code width} pixels wide, its tyres at
     * {@code floor}, kept at its centre's place across.
     */
    static Placement place(Model m, int width, float floor, float box) {
        if (width <= 0 || box <= 0) return new Placement(0, 0, 0);
        float scale = box / m.height, w = m.width * scale;
        return new Placement(scale, (float) (m.centreShare * width - w / 2), floor - m.height * scale);
    }
}
