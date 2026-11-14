package com.jjricks.volt;

/**
 * How big the charging percentage is over a charging picture, and how big the picture is under it (the contributor's
 * charging pictures, 14 Nov 2026 on #383; review of #483: large text and short or wide stages). Pure Java, so the JVM
 * suite checks it at the sizes the maintainers look at (tests/ChargePictureTest.java).
 *
 * <p>The approved prototype draws, on its 344 dp phone, the UT5 MAX picture {@link #PROTO_BOX_DP} tall (283.6 dp
 * wide) with the 60 px number over it: the digits' baseline {@link #PROTO_OVERLAP_DP} below the picture's top, so the
 * number sits above the deck and overlaps only the stem's tip. That relationship is kept at every size, measured on
 * a reference box (the UT5 MAX's picture at this stage's width and floor; a folded picture scales with the box):
 * <ul>
 *   <li>the number is at most {@link #TEXT_PER_BOX} of the box's height (so a large text size does not make it
 *       outgrow the picture);</li>
 *   <li>its baseline is at most {@link #OVERLAP_PER_BOX} of the box's height below the box's top (so it never sits
 *       on the deck); the box is lowered and made smaller to keep it so;</li>
 *   <li>at a larger Text size the number grows from that share by the Text size, up to its size at 1.0x text, and
 *       the picture gives way (smaller and lower) to make room for it: so at 1.4x the number is never smaller than
 *       at 1.0x on a stage where the picture allows the 1.0x size (an accessibility rule), and never larger than the
 *       size it would have without a picture.</li>
 * </ul>
 * At 1.0x text on a short, wide stage (a two-pane window) the number is the picture's share, smaller than its own
 * 60 sp, rather than as tall as the scooter (review of #483, D2).
 * Every length is in pixels; {@code baselineShare} is the percentage view's baseline below its top per pixel of text
 * size (its font's ascent with the view's padding), so the rule needs no Android type.
 */
final class ChargePictureFit {
    private ChargePictureFit() {}

    /** The prototype's picture height (283.6 dp wide, the 1438 x 691 cut-out), its number's size and baseline depth. */
    static final double PROTO_BOX_DP = 283.6 * 691 / 1438, PROTO_TEXT_DP = 60, PROTO_OVERLAP_DP = 268 - 226.3;
    /** The number's size per pixel of the box's height, and its baseline's depth into the box per pixel of height. */
    static final double TEXT_PER_BOX = PROTO_TEXT_DP / PROTO_BOX_DP, OVERLAP_PER_BOX = PROTO_OVERLAP_DP / PROTO_BOX_DP;
    /** Slack on the baseline rule (dp), so a sub-pixel difference in font metrics never shrinks the prototype's picture. */
    static final double SLACK_DP = 1;
    /** Widgets: the floor sits this far above the stage's foot (the charge bar's line under it, as the photo's was). */
    static final int WIDGETS_FOOT_DP = 20;
    /** Widgets: the box is at most the old photo's height (dp), so a wide window's takeover stays short. */
    static final int WIDGETS_BOX_MAX_DP = 196;

    /** The fitted sizes. */
    static final class Fit {
        /** The percentage's text size; the box's height (the UT5 MAX picture's, 0: no picture); the percentage's top. */
        final float text, box, percentTop;
        /** Where the tyres stand, from the stage's top. */
        final float floor;

        Fit(float text, float box, float percentTop, float floor) {
            this.text = text; this.box = box; this.percentTop = percentTop; this.floor = floor;
        }

        /** The size the shade behind the number is drawn at: the text's, at most the box's share (TEXT_PER_BOX). */
        float shadeSize() { return (float) Math.min(text, box * TEXT_PER_BOX); }

        /** The box's scale against the picture the width alone allows (1: full size). */
        float boxShare(float width) { float w = widthBox(width); return w <= 0 ? 0 : Math.min(1, box / w); }

        @Override public String toString() { return "text=" + text + " box=" + box + " top=" + percentTop + " floor=" + floor; }
    }

    /** The UT5 MAX picture's height when only the width limits it (its share of the content width). */
    static float widthBox(float width) {
        ChargeWireModels.Model m = ChargeWireModels.UT5_MAX;
        return width <= 0 ? 0 : (float) (m.widthShare * width * m.height / m.width);
    }

    /**
     * Clean's stage: {@code width} wide, the tyres at {@code floor} below its top, the percentage's top at
     * {@code percentTop} (above the stage's top while it moves up into the line's gap). {@code textFull}: the size
     * ChargingFit gives the number (its text size at this Text size, shrunk where the slot is short); {@code textOneX}:
     * its size at 1.0x text, shrunk the same way; {@code textScale}: the Text size.
     */
    static Fit clean(float width, float floor, float percentTop, float baselineShare, float textFull, float textOneX, float textScale,
                     float density) {
        if (width <= 0 || floor <= 0) return new Fit(textFull, 0, percentTop, floor);
        float slack = (float) (SLACK_DP * density);
        float cap = Math.min(widthBox(width), floor);
        // The largest box whose proportional number fits over it: its top at the floor less the box, the number's
        // baseline the overlap below that, the number's top at percentTop.
        float proportional = (float) ((floor - percentTop + slack) / (1 + baselineShare * TEXT_PER_BOX - OVERLAP_PER_BOX));
        float text = text(Math.min(cap, proportional), textFull, textOneX, textScale);
        float box = Math.max(0, Math.min(cap, (float) ((floor - percentTop - baselineShare * text + slack) / (1 - OVERLAP_PER_BOX))));
        return new Fit(text, box, percentTop, floor);
    }

    /**
     * Clean with a picture model, in a slot ChargingFit plans by #449's rules (the line, the tiles that always stay, the
     * percentage that never grows as the slot shrinks, the track that makes way). The slot's heights are ChargingFit's
     * ({@code inner}, the line, its short form, the percentage at full size, the readings); {@code textSize} is the
     * percentage's full size at this Text size; {@code foot}: the tyres' height above the stage's foot; {@code lift}:
     * how far the percentage moves up into the line's gap while the line shows over a picture; {@code padTop}: its
     * view's top padding. Returns the fit and the plan it was made on ({@link SlotFit}).
     * <ul>
     *   <li>Where the plan has a photo, the picture takes its place and {@link #clean} sizes the two together on the
     *       plan's stage (the approved look).</li>
     *   <li>Where it has none (a slot too short for even the smallest photo), the picture does not show and the
     *       percentage keeps the plan's place, no larger than over the smallest picture the plan shows
     *       ({@link ChargingFit#photoStage}).</li>
     *   <li>Either way the percentage is never larger than in any taller slot: where the plan's stage grows as the slot
     *       shrinks (the gap over the readings closing, the line shortening or going), the picture may grow with it
     *       but the number keeps the size it had just above (#449: the percentage never grows as the slot shrinks).</li>
     * </ul>
     */
    static SlotFit cleanSlot(int inner, int line, int shortLine, int percent, int readings, float width, float foot, float lift,
                             float padTop, float baselineShare, float textSize, float textScale, float density) {
        ChargingFit.Plan plan = ChargingFit.plan(inner, line, shortLine, percent, readings, density);
        Fit fit = onPlan(plan, width, foot, lift, padTop, baselineShare, textSize, textScale, density);
        // Every stage a taller slot can have that is not already larger than this one's lies within this reach: the
        // line, its gap and the gap over the readings that closes (a slot that much taller has a taller stage).
        int reach = Math.max(0, line) + Math.round((ChargingFit.LINE_GAP_DP + ChargingFit.READINGS_GAP_DP) * density) + 1;
        float least = fit.text;
        for (int taller = inner + 1; taller <= inner + reach; taller++) {
            ChargingFit.Plan above = ChargingFit.plan(taller, line, shortLine, percent, readings, density);
            if (above.percent) least = Math.min(least, onPlan(above, width, foot, lift, padTop, baselineShare, textSize, textScale, density).text);
        }
        if (least < fit.text) fit = new Fit(least, fit.box, fit.percentTop, fit.floor);
        return new SlotFit(plan, fit);
    }

    /** A slot's plan (ChargingFit) and the fit on it. */
    static final class SlotFit {
        final ChargingFit.Plan plan;
        final Fit fit;
        SlotFit(ChargingFit.Plan plan, Fit fit) { this.plan = plan; this.fit = fit; }
    }

    /** The fit on one plan: the picture's with a photo, else the percentage no larger than over the smallest picture. */
    static Fit onPlan(ChargingFit.Plan plan, float width, float foot, float lift, float padTop, float baselineShare,
                      float textSize, float textScale, float density) {
        float full = textSize * plan.percentScale, oneX = full / Math.max(1, textScale);
        if (plan.photo()) return clean(width, plan.stageHeight - foot, padTop - (plan.line ? lift : 0), baselineShare, full, oneX, textScale, density);
        float least = clean(width, ChargingFit.photoStage(density) - foot, padTop, baselineShare, full, oneX, textScale, density).text;
        return new Fit(Math.min(full, least), 0, padTop, 0);
    }

    /**
     * Widgets' stage, whose height follows the picture: the box as large as the width allows (at most
     * {@link #WIDGETS_BOX_MAX_DP}), the number at its share (grown by the Text size as on Clean), its baseline the overlap
     * below the box's top. Returns the floor; the stage is {@link #WIDGETS_FOOT_DP} taller.
     */
    static Fit widgets(float width, float baselineShare, float textFull, float textOneX, float textScale, float density) {
        float box = Math.min(widthBox(width), WIDGETS_BOX_MAX_DP * density);
        if (box <= 0) return new Fit(textFull, 0, 0, 0);
        float text = text(box, textFull, textOneX, textScale);
        float baseline = baselineShare * text, top = (float) (baseline - OVERLAP_PER_BOX * box);
        // A number shorter than the overlap: the box from the stage's top, the number lower.
        float percentTop = Math.max(0, -top);
        return new Fit(text, box, percentTop, percentTop + top + box);
    }

    // ---- the layers' measure and the cable's reach (pure rules the views follow) ----

    /** View.MeasureSpec's mode bits and its EXACTLY mode (the platform's values; the JVM suite has no Android). */
    static final int MODE_MASK = 3 << 30, EXACTLY = 1 << 30;

    /**
     * A picture layer's measured height: the stage's, only when it gives an exact one; otherwise none. So a stage that
     * wraps its content (Widgets' takeover; its height animates while it opens and closes) is sized by its own parts,
     * never stretched by the layers to all the height it is offered (review of #483, C1).
     */
    static int layerHeight(int heightSpec) {
        return (heightSpec & MODE_MASK) == EXACTLY ? heightSpec & ~MODE_MASK : 0;
    }

    /**
     * The charging picture is at rest: the view shown and charging, its layers visible, not animating open or closed,
     * not editing, the screen not closed. Only then may the wire picture's layers draw past their stage (the cable off
     * the screen's edge, {@link #unclipped}); any other moment its parents clip again, so a tile dragged in edit mode
     * or rows sliding while the takeover opens or closes are clipped as on the base branch (review of #483, C2).
     */
    static boolean atRest(boolean shown, boolean visible, boolean revealing, boolean editing, boolean closed) {
        return shown && visible && !revealing && !editing && !closed;
    }

    /** The parents stop clipping only at rest and only where the cable runs off the screen's edge (not faded out). */
    static boolean unclipped(boolean atRest, boolean runsOff) { return atRest && runsOff; }

    /** The cable fades out this far past the picture's right edge, where the screen's edge is further than that (dp). */
    static final int FADE_DP = 60;
    /** It keeps its full strength this far past the picture before it fades (dp). */
    static final int FADE_FROM_DP = 12;

    /**
     * Where the cable ends, in the stage's pixels: {fadeStart, fadeEnd}, both infinite while it runs off the screen's
     * edge (the edge within FADE_DP of the picture's right edge, or none known); otherwise it fades out from
     * FADE_FROM_DP to FADE_DP past the picture, so a wide window shows no long taut cable across an empty page.
     */
    static float[] cableEnd(float pictureRight, float edge, float density, float[] out) {
        float fade = FADE_DP * density;
        if (Float.isNaN(edge) || edge - pictureRight <= fade) { out[0] = out[1] = Float.POSITIVE_INFINITY; }
        else { out[0] = pictureRight + FADE_FROM_DP * density; out[1] = pictureRight + fade; }
        return out;
    }

    /**
     * The number over a box this tall: its share of the box; at a larger Text size that share times the Text size, up
     * to its 1.0x size; never above its full size.
     */
    static float text(float box, float textFull, float textOneX, float textScale) {
        float share = (float) (TEXT_PER_BOX * box);
        // Within half a pixel of the full size it is the full size (the prototype's own phone).
        if (share >= textFull - .5f) return textFull;
        float least = Math.min(Math.min(textOneX, textFull), share * Math.max(1, textScale));
        return Math.min(textFull, Math.max(least, share));
    }
}
