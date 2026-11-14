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
        fit();
        withPlan();
        shadeFromBox();
        layers();
        clipping();
        cableEnd();
        registryKeys();
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
            ChargeWireModels.Placement p = ChargeFolded.place(m, 924, floor, 1);
            near(p.scale * m.width / 3, 246.4, .05, m.key + ": 80% of the 308 dp content width");
            near(p.left / 3 + 18, expected[i][0], .25, m.key + ": centred (its left edge)");
            near(p.top / 3 + 200, expected[i][1], .15, m.key + ": its top as the prototype's");
            near(p.top + m.tyreY * p.scale, floor, .01, m.key + ": the tyres on the floor");
            // A smaller box (ChargePictureFit): smaller in the same share, centred, still on the floor.
            ChargeWireModels.Placement q = ChargeFolded.place(m, 924, floor, .75f);
            near(q.scale, p.scale * .75, 1e-6, m.key + ": three quarters of the box, three quarters of the size");
            near(q.left + q.scale * m.width / 2, 462, .01, m.key + ": still centred");
            near(q.top + m.tyreY * q.scale, floor, .01, m.key + ": still on the floor");
            near(ChargeFolded.place(m, 924, floor, 2).scale, p.scale, 1e-6, m.key + ": never larger than its full size");
        }
        require(ChargeFolded.place(ChargeFolded.N65I, 0, 10, 1).scale == 0 && ChargeFolded.place(ChargeFolded.N65I, 100, 10, 0).scale == 0, "no room, no picture");
        // The tyres stand where the wire picture's do: its gap above the track for every picture.
        near(362.6, 372 - ChargeWireModels.UT5_MAX.tyreGapDp, .05, "the shared floor: 9.4 dp over the track");
    }

    private static final float LEFT = 120.48f, RIGHT = 215.53f, BASE = 268, SIZE = 60;

    private static void halo() {
        float[] h = ChargePercentShade.halo(LEFT, RIGHT, BASE, SIZE, SIZE, new float[3]);
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
        float[] box = ChargePercentShade.cutoffBox(LEFT, RIGHT, BASE, SIZE, SIZE, new float[4]);
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
        float[] box = ChargePercentShade.cutoffBox(left, right, BASE, SIZE * k, SIZE * k, new float[4]);
        near(box[0], left - 7 * k, 1e-3, "the cut-off's side grows");
        near(BASE - box[1], 48 * k, 1e-3, "its top");
        near(box[3] - BASE, 8.5 * k, 1e-3, "its bottom");
        near(ChargePercentShade.cutoffSigma(SIZE * k), 5 * k, 1e-4, "its blur");
        float r = ChargePercentShade.cutoffRadius(SIZE * k), sigma = ChargePercentShade.cutoffSigma(SIZE * k);
        near(ChargePercentShade.cutoffAlpha(box, r, sigma, left, (box[1] + box[3]) / 2), .92, .01, "still 92% at the glyphs");
        near(ChargePercentShade.halo(left, right, BASE, SIZE * k, SIZE * k, new float[3])[2] * 2, 168 * k, 1e-3, "the halo grows");
        float[] none = ChargePercentShade.cutoffBox(0, 0, 0, 0, 0, new float[4]);
        require(none[0] == 0 && none[2] == 0, "no text, no box");
    }

    // ---- the percentage and the picture over each other (ChargePictureFit; review of #483, D1-D3) ----

    /** Saira Bold's top (its bounding box's 1138/1000 em: the percentage's baseline below its view's top per px). */
    private static final float SHARE = 1.138f;
    private static final float D = 3;

    /**
     * Clean's stages as the app lays them out (3 px per dp): the slot's width and the tyres' floor below the stage's top
     * (the percentage's top 10 dp above it, in the line's gap), at 1.0x and 1.4x Text size. 344 dp at both sizes and
     * 412 and 725 dp at 1.0x were measured on the emulator at #483's first head (ChargingFit is unchanged); 412 and
     * 725 dp at 1.4x are their 1.0x stages less the taller readings (18 dp, as on 344 dp). {name, width dp, floor dp,
     * text scale}.
     */
    private static final Object[][] CLEAN = {
            {"344x882 1.0x", 308f, 152.6f, 1f}, {"344x882 1.4x", 308f, 134.5f, 1.4f},
            {"412x915 1.0x", 376f, 164.6f, 1f}, {"412x915 1.4x", 376f, 146.5f, 1.4f},
            {"725x604 1.0x", 559f, 102.6f, 1f}, {"725x604 1.4x", 559f, 84.5f, 1.4f}};

    private static void fit() {
        for (Object[] c : CLEAN) {
            String name = (String) c[0];
            float width = (Float) c[1] * D, floor = (Float) c[2] * D, scale = (Float) c[3], top = -10 * D;
            float full = 60 * D * scale, oneX = 60 * D;
            ChargePictureFit.Fit f = fitOf(c);
            float baseline = top + SHARE * f.text, boxTop = floor - f.box, share = (float) (ChargePictureFit.TEXT_PER_BOX * f.box);
            require(f.text <= full + .01f, name + ": never above its own size: " + f.text / D);
            // At 1.0x the number is at most the picture's share; at 1.4x it may be larger, up to its 1.0x size.
            if (scale == 1) require(f.text <= share + .5f, name + ": at 1.0x no larger than the picture's share: " + f.text / D + " over " + f.box / D);
            else require(f.text <= Math.max(share + .5f, oneX + .01f), name + ": at 1.4x at most its 1.0x size over a smaller picture");
            // At 1.4x it is never smaller than at 1.0x on the same stage.
            if (scale > 1) {
                ChargePictureFit.Fit at1 = ChargePictureFit.clean(width, floor, top, SHARE, oneX, oneX, 1, D);
                require(f.text >= at1.text - .01f, name + ": no smaller than at 1.0x (" + f.text / D + " vs " + at1.text / D + ")");
            }
            // Its baseline sits no deeper in the box than the prototype's (above the deck, over the stem's tip only).
            require(baseline - boxTop <= ChargePictureFit.OVERLAP_PER_BOX * f.box + ChargePictureFit.SLACK_DP * D + .01f,
                    name + ": the baseline " + (baseline - boxTop) / D + " dp into a " + f.box / D + " dp box");
            // The picture: no wider than the width allows, standing on the floor, never above the stage's top.
            require(f.box <= ChargePictureFit.widthBox(width) + .01f && boxTop >= -.01f && f.box > 20 * D, name + ": the box " + f.box / D);
            require(f.shadeSize() <= f.text + .01f && f.shadeSize() <= share + .01f, name + ": the shade's size from the box");
        }
        // The prototype's own phone at 1.0x: unchanged (the 283.6 dp picture under the 60 dp number).
        ChargePictureFit.Fit proto = fitOf(CLEAN[0]);
        near(proto.text / D, 60, 1e-4, "344 dp at 1.0x: the full 60 dp number");
        near(proto.box / D, ChargePictureFit.PROTO_BOX_DP, .01, "and the prototype's 136.3 dp picture");
        // 1.4x on the phone: the number stays at its 1.0x size (the picture cannot grow), the picture gives way.
        ChargePictureFit.Fit large = fitOf(CLEAN[1]);
        near(large.text / D, 60, 1e-4, "344 dp at 1.4x: the number at its 1.0x size");
        require(large.box < proto.box && large.box > proto.box * .7f, "344 dp at 1.4x: the picture a little smaller: " + large.box / D);
        // 412 dp: the full number over a larger picture than the phone's.
        ChargePictureFit.Fit wide412 = fitOf(CLEAN[2]);
        near(wide412.text / D, 60, 1e-4, "412 dp at 1.0x: the full number");
        require(wide412.box > proto.box, "412 dp: a larger picture than the phone's: " + wide412.box / D);
        // 725 dp (two panes, a short slot): the number at the picture's share, not as tall as the scooter.
        ChargePictureFit.Fit wide = fitOf(CLEAN[4]);
        near(wide.text, ChargePictureFit.TEXT_PER_BOX * wide.box, .5, "725 dp at 1.0x: the number at the picture's share");
        require(wide.text > 36 * D && wide.text < 50 * D && wide.box > 85 * D, "725 dp: a readable number over a fair picture: " + wide.text / D + " over " + wide.box / D);
        ChargePictureFit.Fit wide14 = fitOf(CLEAN[5]);
        require(wide14.text > wide.text, "725 dp at 1.4x: a larger number than at 1.0x: " + wide14.text / D);
        // Widgets: the number on the picture as on Clean; the stage as tall as the picture's floor (plus the bar's foot).
        for (float w : new float[] {312, 380, 560}) for (float scale : new float[] {1, 1.4f}) {
            ChargePictureFit.Fit f = ChargePictureFit.widgets(w * D, SHARE, 60 * D * scale, 60 * D, scale, D);
            String name = "Widgets " + w + " dp at " + scale + "x";
            float baseline = f.percentTop + SHARE * f.text, boxTop = f.floor - f.box;
            require(f.text >= 60 * D - .01f && f.text <= 60 * D * scale + .01f, name + ": the number's size " + f.text / D);
            near(baseline - boxTop, ChargePictureFit.OVERLAP_PER_BOX * f.box, .01, name + ": the baseline the prototype's depth into the picture");
            require(boxTop >= -.01f && f.percentTop >= 0, name + ": inside the stage");
            require(f.box <= ChargePictureFit.WIDGETS_BOX_MAX_DP * D + .01f, name + ": the picture at most the old photo's height");
        }
        ChargePictureFit.Fit phone = ChargePictureFit.widgets(312 * D, SHARE, 60 * D, 60 * D, 1, D);
        near(phone.text / D, 60, 1e-4, "Widgets on the phone: Clean's 60 dp number");
        require(phone.floor + ChargePictureFit.WIDGETS_FOOT_DP * D < (236 + 72 * 1.4) * D, "Widgets: the takeover shorter than the photo's stage under the 72 sp number");
        require(ChargePictureFit.clean(0, 100, 0, SHARE, 60, 60, 1, D).box == 0, "no width, no picture");
        // The text rule alone: the share at 1.0x; grown by the Text size up to the 1.0x size; the full size near it.
        near(ChargePictureFit.text(100, 84, 60, 1.4f), Math.min(60, ChargePictureFit.TEXT_PER_BOX * 100 * 1.4), 1e-3, "1.4x: the share grown, up to 1.0x");
        near(ChargePictureFit.text(100, 60, 60, 1), ChargePictureFit.TEXT_PER_BOX * 100, 1e-3, "1.0x: the share");
        near(ChargePictureFit.text(1000, 60, 60, 1), 60, 0, "a large box: the full size");
    }

    // ---- the two fits together: ChargingFit's plan (#449) and the picture's fit (this part) ----

    /** The tyres' height above the stage's foot: the track's tick, the prototype's 8 dp track and the picture's gap. */
    private static final float FOOT = (float) ((ChargingFit.TICK_DP + 8 + ChargeWireModels.UT5_MAX.tyreGapDp) * D);

    /** Clean's slot at a window, in pixels (3 per dp), estimated as ChargingFitTest does: {inner, line, short line, percent, readings, width}. */
    private static int[] slot(int width, int height, float text) {
        boolean wide = WideLayout.wide(width, text);
        int inner = TopArea.heightDp(height, wide, text);
        int across = Math.min(width - 36, 560);
        int oneLine = Math.round(ChargingFit.LINE_SP * text * 1.17f);
        int line = 12.7f * ChargingFit.LINE_SP * text > across ? 2 * oneLine : oneLine;
        int percent = Math.round(ChargingFit.PERCENT_SP * text * 1.17f);
        float tile = 20 + 12 * text * 1.17f + 2 + Math.max(ChargingFit.VALUE_MIN_DP * text, ChargingFit.VALUE_SP * text * 1.33f);
        int readings = Math.round(across >= ChargingFit.PAIR_DP ? tile : 2 * tile + 8);
        return new int[] {(int) (inner * D), (int) (line * D), (int) (oneLine * D), (int) (percent * D), (int) (readings * D), (int) (across * D)};
    }

    /**
     * 344x882 and 725x604 dp at 1.0x and 1.4x, with a picture (the UT5 MAX, the folded models) and without one (the
     * XT5 Pro): #449's rules for the line, the tiles, the percentage and the track hold, and the picture keeps its fit.
     */
    private static void withPlan() {
        for (int[] window : new int[][] {{344, 882}, {725, 604}})
            for (float text : new float[] {1f, 1.4f}) {
                String name = window[0] + "x" + window[1] + " at " + text + "x";
                int[] s = slot(window[0], window[1], text);
                ChargingFit.Plan plan = ChargingFit.plan(s[0], s[1], s[2], s[3], s[4], D);
                float full = 60 * D * text * plan.percentScale;
                require(plan.readings && plan.percent && plan.track && plan.photo(), name + ": the tiles, the percentage, the track and room for the picture: " + plan);
                // With a picture: the picture's own fit on the plan's stage (the pictures as approved), the number no
                // larger than in a taller slot.
                float lift = plan.line ? ChargePicture_LIFT * D : 0;
                ChargePictureFit.SlotFit slotFit = ChargePictureFit.cleanSlot(s[0], s[1], s[2], s[3], s[4], s[5], FOOT, ChargePicture_LIFT * D, 0, SHARE, 60 * D * text, text, D);
                ChargePictureFit.Fit f = slotFit.fit;
                ChargePictureFit.Fit direct = ChargePictureFit.clean(s[5], plan.stageHeight - FOOT, -lift, SHARE, full, full / text, text, D);
                require(slotFit.plan.toString().equals(plan.toString()), name + ": the plan is ChargingFit's");
                require(f.box == direct.box && f.text <= direct.text, name + ": the picture's fit on the plan's stage: " + f);
                // The maintainers' phone and wide window at 1.0x and the phone at 1.4x: the approved fit unchanged.
                if (!(window[0] == 725 && text > 1)) require(f.text == direct.text, name + ": the number as the picture's fit: " + f);
                require(f.box > 40 * D && f.text <= full + .01f && f.text >= 30 * D, name + ": a fair picture under a readable number: " + f);
                // The number stays inside the stage with its lift; the picture's top never above the stage.
                require(f.floor - f.box >= -.01f, name + ": the picture inside the stage");
                // Without a picture (the XT5 Pro): #449's noPhotoTop, the percentage and the track centred in the stage.
                int under = (int) ((ChargingFit.TRACK_DP + ChargingFit.TICK_DP + ChargingFit.PHOTO_GAP_DP) * D);
                int percentHeight = Math.round(ChargingFit.scaled(s[3], plan.percentScale));
                int top = ChargingFit.noPhotoTop(plan.stageHeight, percentHeight, under);
                require(top >= 0 && Math.abs(top - (plan.stageHeight - top - percentHeight - under)) <= 1,
                        name + ": no picture: the percentage and the track centred (top " + top / D + " dp of " + plan.stageHeight / D + ")");
                System.out.println("  " + name + ": " + plan + " / picture " + f + " (on the plan alone " + direct.text + ") / no picture: top " + top);
            }
        // A slot getting shorter one pixel at a time with a picture: the number never grows (#449's rule), including
        // where the slot becomes too short for the picture (the picture goes, the number stays no larger).
        for (float text : new float[] {1f, 1.4f}) {
            int[] s = slot(344, 882, text);
            float last = Float.MAX_VALUE;
            boolean pictureGone = false, trackGone = false;
            for (int inner = s[0]; inner >= 0; inner--) {
                ChargingFit.Plan plan = ChargingFit.plan(inner, s[1], s[2], s[3], s[4], D);
                ChargePictureFit.Fit f = ChargePictureFit.cleanSlot(inner, s[1], s[2], s[3], s[4], s[5], FOOT, ChargePicture_LIFT * D, 0, SHARE, 60 * D * text, text, D).fit;
                float now = plan.percent ? f.text : 0;
                require(now <= last + .01f, text + "x, inner " + inner + ": the number never grows (" + now / D + " after " + last / D + "): " + plan);
                require(plan.readings, text + "x, inner " + inner + ": the tiles stay");
                if (!plan.photo()) { pictureGone = true; require(f.box == 0, "no photo in the plan, no picture"); }
                if (!plan.track) trackGone = true;
                require(!trackGone || !plan.photo(), text + "x, inner " + inner + ": the picture never comes back once the track has made way");
                last = now;
            }
            require(pictureGone && trackGone, text + "x: the picture and the track went in the shortest slots");
        }
    }

    /** ChargePicture.PERCENT_LIFT_DP (ChargePicture itself needs Android). */
    private static final float ChargePicture_LIFT = 10;

    private static ChargePictureFit.Fit fitOf(Object[] c) {
        float scale = (Float) c[3];
        return ChargePictureFit.clean((Float) c[1] * D, (Float) c[2] * D, -10 * D, SHARE, 60 * D * scale, 60 * D, scale, D);
    }

    /** The shade reaches out by the box's em: the cut-off's foot and blur and the halo's radius shrink with the picture. */
    private static void shadeFromBox() {
        float em = 40;
        float[] box = ChargePercentShade.cutoffBox(LEFT, RIGHT, BASE, SIZE, em, new float[4]);
        near(box[0], LEFT - 7, 1e-3, "the sides still hug the number");
        near(BASE - box[1], 48, 1e-3, "and its top");
        near(box[3] - BASE, 8.5 * em / 60, 1e-3, "its foot from the box's em");
        near(ChargePercentShade.halo(LEFT, RIGHT, BASE, SIZE, em, new float[3])[2], 84 * em / 60, 1e-3, "the halo's radius from it");
        float[] out = new float[4];
        require(ChargePercentShade.cutoffBox(LEFT, RIGHT, BASE, SIZE, em, out) == out, "into the caller's array (no allocation per frame)");
    }

    /** The layers' measure (review of #483, C1): the stage's width, and its height only when exact. */
    private static void layers() {
        int exactly = 1 << 30, atMost = 2 << 30, unspecified = 0;
        near(ChargePictureFit.layerHeight(exactly | 600), 600, 0, "an exact height: the stage's");
        near(ChargePictureFit.layerHeight(atMost | 600), 0, 0, "at most (the takeover animating open or closed): none");
        near(ChargePictureFit.layerHeight(unspecified | 0), 0, 0, "unspecified: none");
        require(ChargePictureFit.EXACTLY == exactly, "the platform's EXACTLY");
    }

    /** The parents stop clipping only at rest and only where the cable runs off the edge (review of #483, C2, D4). */
    private static void clipping() {
        require(ChargePictureFit.atRest(true, true, false, false, false), "shown, visible, still: at rest");
        require(!ChargePictureFit.atRest(true, true, true, false, false), "animating open or closed: not at rest");
        require(!ChargePictureFit.atRest(true, true, false, true, false), "editing: not at rest");
        require(!ChargePictureFit.atRest(false, true, false, false, false), "hidden (charging stopped): not at rest");
        require(!ChargePictureFit.atRest(true, false, false, false, false), "the picture not visible: not at rest");
        require(!ChargePictureFit.atRest(true, true, false, false, true), "closed: not at rest");
        require(ChargePictureFit.unclipped(true, true), "at rest, running off the edge: unclipped");
        require(!ChargePictureFit.unclipped(true, false), "at rest, faded short of the edge: clipped");
        require(!ChargePictureFit.unclipped(false, true), "not at rest: clipped");
    }

    /** On a wide window the cable fades out about 60 dp past the scooter's tail (review of #483, D2). */
    private static void cableEnd() {
        float[] out = new float[2];
        ChargePictureFit.cableEnd(305 * D, 326 * D, D, out);
        require(Float.isInfinite(out[0]) && Float.isInfinite(out[1]), "the phone: the edge 21 dp past the tail, the cable runs off it");
        ChargePictureFit.cableEnd(300 * D, Float.NaN, D, out);
        require(Float.isInfinite(out[1]), "no scroller: it runs on");
        ChargePictureFit.cableEnd(400 * D, 700 * D, D, out);
        near(out[0] / D, 412, 1e-3, "a far edge: full strength 12 dp past the tail");
        near(out[1] / D, 460, 1e-3, "gone 60 dp past it");
    }

    /** Each charging picture's key is a photo key ModelRegistry gives a model (review of #483, C9). */
    private static void registryKeys() {
        java.util.Set<String> keys = new java.util.HashSet<>();
        keys.add(ModelRegistry.ownerPhoto("UT5 Max"));
        for (String[] named : ModelRegistry.knownModels().values()) keys.add(ModelRegistry.ownerPhoto(named[0]));
        for (String name : ModelRegistry.WAITING_PHOTOS.keySet()) keys.add(ModelRegistry.ownerPhoto(name));
        String[] pictures = {"ut5max", "ut5ultrax", "st3pro", "n65i", "gt3max", "gt5max", "xt5ultra"};
        for (String key : pictures) {
            require(keys.contains(key), key + ": ModelRegistry gives it to a model");
            require((ChargeWireModels.forKey(key) != null) != (ChargeFolded.forKey(key) != null), key + ": exactly one picture");
        }
        int pictured = 0;
        for (String key : keys) if (ChargeWireModels.forKey(key) != null || ChargeFolded.forKey(key) != null) pictured++;
        near(pictured, 7, 0, "the seven bundled models each have a picture");
        near(ChargeWireModels.all().length + ChargeFolded.all().length, 7, 0, "and the tables have no other");
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
