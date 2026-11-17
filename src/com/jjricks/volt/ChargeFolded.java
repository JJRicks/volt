package com.jjricks.volt;

/**
 * The still charging picture's per-model table (the contributor's charging pictures, 14 Nov 2026 on #383): the models
 * shown folded under the percentage instead of the animated wire picture (ChargeWireModels). Pure Java; the view
 * (ChargePicture) picks the bundled cut-out ({@code <key>_folded.webp}) by {@link Model#key} and places it with
 * {@link #place}, so another model is one more entry here plus its cut-out.
 *
 * <p>Each picture is static (no animation): centred at {@link #WIDTH_SHARE} of the content width, its tyres standing
 * where the wire picture's do (on the red floor glow, ChargeWireModels.Model.tyreGapDp above the track), and the
 * percentage in front of it with its cut-off shadow (ChargePercentShade).
 */
final class ChargeFolded {
    private ChargeFolded() {}

    /** The picture's width as a share of the content width (the prototype's 246.4 dp of 308). */
    static final double WIDTH_SHARE = 0.8;

    /** One model's folded cut-out. */
    static final class Model {
        /** The model's photo key (ModelRegistry.photo), e.g. "st3pro". */
        final String key;
        /** The cut-out's size in pixels and the row its tyres touch the floor on. */
        final int width, height, tyreY;

        Model(String key, int width, int height, int tyreY) {
            this.key = key; this.width = width; this.height = height; this.tyreY = tyreY;
        }
    }

    /** NAVEE product photos supplied by the contributor, cut out (or trimmed) on a transparent background. */
    static final Model ST3_PRO = new Model("st3pro", 1062, 477, 474);
    static final Model N65I = new Model("n65i", 718, 352, 351);
    static final Model GT3_MAX = new Model("gt3max", 1100, 483, 480);
    static final Model GT5_MAX = new Model("gt5max", 1100, 538, 537);
    static final Model XT5_ULTRA = new Model("xt5ultra", 1100, 526, 524);
    /**
     * NAVEE's folded side views from its model catalog, supplied by the maintainer on 17 November 2026, for models with
     * no folded picture before (the owner: "fill in any missing folded ones dont replace any"): trimmed, at most 1100 px
     * wide, the GT5 Pro's floor shadow cut away. GT3 and GT3 Pro are one image in the catalog.
     */
    static final Model G5_PRO = new Model("g5pro", 1100, 506, 505);
    static final Model GT3 = new Model("gt3", 1100, 483, 481);
    static final Model GT3_PRO = new Model("gt3pro", 1100, 483, 481);
    static final Model GT5_PRO = new Model("gt5pro", 1100, 493, 491);
    static final Model K100_MAX = new Model("k100max", 1100, 552, 551);
    static final Model N65I_II = new Model("n65iii", 1100, 524, 522);
    static final Model V25_SERIES = new Model("v25series", 720, 337, 336);
    static final Model V40_SERIES = new Model("v40series", 716, 349, 348);
    static final Model XT5_MAX = new Model("xt5max", 1100, 478, 475);
    static final Model XT5_PRO = new Model("xt5pro", 1100, 524, 522);

    private static final Model[] ALL = {ST3_PRO, N65I, GT3_MAX, GT5_MAX, XT5_ULTRA,
            G5_PRO, GT3, GT3_PRO, GT5_PRO, K100_MAX, N65I_II, V25_SERIES, V40_SERIES, XT5_MAX, XT5_PRO};

    /** The model's folded picture, or null when it has none. */
    static Model forKey(String key) {
        if (key == null) return null;
        for (Model model : ALL) if (model.key.equals(key)) return model;
        return null;
    }

    static Model[] all() { return ALL.clone(); }

    /**
     * The picture at {@code share} of its full size (ChargePictureFit.Fit.boxShare: the reference box against the one
     * the width allows), so it grows and shrinks with the wire picture's box: {@link #WIDTH_SHARE} of the width times
     * the share, centred, its tyres at {@code floor}.
     */
    static ChargeWireModels.Placement place(Model m, int width, float floor, float share) {
        if (width <= 0 || share <= 0) return new ChargeWireModels.Placement(0, 0, 0);
        float scale = (float) (WIDTH_SHARE * width / m.width * Math.min(1, share));
        return new ChargeWireModels.Placement(scale, (width - m.width * scale) / 2, floor - m.tyreY * scale);
    }
}
