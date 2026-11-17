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
        tallerStage();
        shadeFromBox();
        layers();
        clipping();
        cableEnd();
        cableInColumn();
        fontScale();
        nonlinearFont();
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
        int inner = TopArea.heightDp(width, height, wide, text);
        int across = Math.min(width - 36, 560);
        int[] pill = ChargingFitTest.pill(ChargingFit.LINE_SP * text, across, 1);
        int oneLine = pill[0], line = pill[1];
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

    /**
     * #501's taller top area on wider phones (360x800, 393x852, 412x901, 430x932 dp; 344x882 and the short and wide
     * windows keep theirs): the picture is as large as the width allows and the stage is taller than the number and the
     * picture need. The number moves down onto its picture (ChargePictureFit.attached), its baseline the prototype's
     * overlap into the box, never floating over empty space above the stem; its size and the picture's do not change.
     * Where the height limits the picture (as on the stages measured on the emulator at 344x882 and 725x604 dp) nothing moves.
     */
    private static void tallerStage() {
        for (int[] window : new int[][] {{344, 882}, {360, 800}, {393, 852}, {412, 901}, {430, 932}, {411, 731}, {360, 640}, {725, 604}})
            for (float text : new float[] {1f, 1.4f}) {
                String name = window[0] + "x" + window[1] + " at " + text + "x";
                int[] s = slot(window[0], window[1], text);
                ChargingFit.Plan plan = ChargingFit.plan(s[0], s[1], s[2], s[3], s[4], D);
                if (!plan.photo()) continue;
                float lift = plan.line ? ChargePicture_LIFT * D : 0;
                ChargePictureFit.Fit f = ChargePictureFit.cleanSlot(s[0], s[1], s[2], s[3], s[4], s[5], FOOT, ChargePicture_LIFT * D, 0, SHARE, 60 * D * text, text, D).fit;
                float depth = f.percentTop + SHARE * f.text - (f.floor - f.box), rule = (float) (ChargePictureFit.OVERLAP_PER_BOX * f.box);
                require(depth >= rule - .01f, name + ": the number on its picture (baseline " + depth / D + " dp into the box, the rule " + rule / D + ")");
                require(depth <= rule + ChargePictureFit.SLACK_DP * D + .01f, name + ": no deeper than the prototype's overlap");
                require(f.percentTop >= -lift - .01f && f.floor - f.box >= -.01f, name + ": the number and the picture inside the stage");
            }
        // The real stages the emulator measures (dp, 344 dp content 308 wide): 344x882 keeps its number where it was;
        // 430x932's 247 dp stage moves it down about 46 dp onto the picture.
        float width = 308 * D, near344 = 174 * D - FOOT, tall = 247 * D - FOOT, wide430 = 394 * D;
        ChargePictureFit.Fit at344 = ChargePictureFit.clean(width, near344, -10 * D, SHARE, 60 * D, 60 * D, 1, D);
        require(ChargePictureFit.attached(at344, SHARE) == at344, "344x882: the approved number does not move");
        ChargePictureFit.Fit at430 = ChargePictureFit.clean(wide430, tall, -10 * D, SHARE, 60 * D, 60 * D, 1, D);
        ChargePictureFit.Fit moved = ChargePictureFit.attached(at430, SHARE);
        near(moved.percentTop - at430.percentTop, 46.3 * D, 1.5 * D, "430x932: the number moves down onto its picture");
        require(moved.text == at430.text && moved.box == at430.box, "430x932: the same number and picture, only placed");
        require(ChargePictureFit.attached(new ChargePictureFit.Fit(60, 0, 0, 100), SHARE).percentTop == 0, "no picture, no move");
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

    /**
     * A wide portrait window (800 x 1280 dp): Clean's page is a 560 dp column the window's edge is 120 dp past. The
     * cable fades (the screen's edge is far), but the column's clip would cut it square at nearly full strength before
     * the fade ends, so the parents open and the fade ends in the margin, short of the screen's edge (review of #483,
     * P3-2). Where nothing clips short of the fade's end (725 dp) or the cable runs off (344 dp) nothing changes.
     */
    private static void cableInColumn() {
        float[] out = new float[2];
        // The column 560 dp from 120 dp; the picture's right edge a few dp short of the column's.
        float right = 670 * D, column = 680 * D, screen = 800 * D;
        ChargePictureFit.cableEnd(right, screen, D, out);
        require(!Float.isInfinite(out[1]), "800 dp: the screen's edge 130 dp past the picture, the cable fades");
        require(ChargeWireComet.fadeAt(column, out[0], out[1]) > .85, "800 dp: the column would cut it at nearly full strength");
        require(ChargePictureFit.needsRoom(out, column), "800 dp: so the parents open");
        require(out[1] < screen, "800 dp: the fade ends short of the screen's edge: " + out[1] / D);
        near((out[1] - out[0]) / D, 48, 1e-3, "800 dp: the same fade as on any wide window");
        require(ChargePictureFit.unclipped(true, ChargePictureFit.needsRoom(out, column)) && !ChargePictureFit.unclipped(false, true), "open only at rest");
        // 725 dp two panes: the stage reaches past the fade's end: nothing opens, as before.
        ChargePictureFit.cableEnd(400 * D, 700 * D, D, out);
        require(!ChargePictureFit.needsRoom(out, 460 * D) && !ChargePictureFit.needsRoom(out, 700 * D) && !ChargePictureFit.needsRoom(out, Float.POSITIVE_INFINITY), "725 dp: no clip short of the fade: clipped as before");
        require(ChargePictureFit.needsRoom(out, 459 * D), "a clip 1 dp short of the fade's end: open");
        // The phone (344 dp): it runs off: open, whatever clips (as before).
        ChargePictureFit.cableEnd(305 * D, 326 * D, D, out);
        require(ChargePictureFit.needsRoom(out, 308 * D) && ChargePictureFit.needsRoom(out, Float.POSITIVE_INFINITY), "344 dp: runs off, open");
    }

    /**
     * The percentage's fit grows with Volt's Text size times Android's font size (review of #483, P3-4): 1.4x either
     * way gives the same fit at 344 x 882 and 725 x 604 dp, on Clean and Widgets; 1.0x is unchanged.
     */
    private static void fontScale() {
        near(ChargePictureFit.growth(1, 1), 1, 0, "1.0x: no growth");
        near(ChargePictureFit.growth(1.4f, 1), 1.4f, 0, "Volt's 1.4x");
        near(ChargePictureFit.growth(1, 1.4f), 1.4f, 1e-6, "Android's 1.4x");
        near(ChargePictureFit.growth(1.4f, 1.3f), 1.82f, 1e-5, "both multiply");
        near(ChargePictureFit.growth(1.2f, 0), 1.2f, 0, "an unknown font size counts as 1");
        for (int[] window : new int[][] {{344, 882}, {725, 604}}) {
            int[] s = slot(window[0], window[1], 1.4f);
            String name = window[0] + "x" + window[1];
            // The text sizes are in sp at Volt's size: with Android's 1.4x the full size is the same 84 dp.
            float volt = ChargePictureFit.cleanSlot(s[0], s[1], s[2], s[3], s[4], s[5], FOOT, ChargePicture_LIFT * D, 0, SHARE, 60 * D * 1.4f, ChargePictureFit.growth(1.4f, 1), D).fit.text;
            float android = ChargePictureFit.cleanSlot(s[0], s[1], s[2], s[3], s[4], s[5], FOOT, ChargePicture_LIFT * D, 0, SHARE, 60 * D * 1.4f, ChargePictureFit.growth(1, 1.4f), D).fit.text;
            near(android, volt, 1e-3, name + " Clean: Android's 1.4x grows the number as Volt's does");
            float before = ChargePictureFit.cleanSlot(s[0], s[1], s[2], s[3], s[4], s[5], FOOT, ChargePicture_LIFT * D, 0, SHARE, 60 * D * 1.4f, 1, D).fit.text;
            require(android >= before, name + " Clean: at least what the Text size alone gave: " + android / D + " vs " + before / D);
            float w = (window[0] - 32) * D, full = 60 * D * 1.4f;
            ChargePictureFit.Fit a = ChargePictureFit.widgets(w, SHARE, full, full / ChargePictureFit.growth(1, 1.4f), ChargePictureFit.growth(1, 1.4f), D);
            ChargePictureFit.Fit v = ChargePictureFit.widgets(w, SHARE, full, full / 1.4f, 1.4f, D);
            near(a.text, v.text, 1e-3, name + " Widgets: Android's 1.4x as Volt's");
        }
        // The review's case: a 280 dp window, Android at 140%, Volt at 1.0x: the number grows past the picture's share.
        float full = 60 * D * 1.4f;
        ChargePictureFit.Fit narrow = ChargePictureFit.widgets(248 * D, SHARE, full, full / 1.4f, ChargePictureFit.growth(1, 1.4f), D);
        ChargePictureFit.Fit old = ChargePictureFit.widgets(248 * D, SHARE, full, full, 1, D);
        require(narrow.text > old.text + 5 * D, "280 dp at Android's 140%: the number grows: " + narrow.text / D + " (was " + old.text / D + ")");
    }

    /** Android 14's FontScaleConverter tables (sp to dp) at 130% and 200%: from about 30 sp a size barely grows. */
    private static final float[] TABLE_SP = {8, 10, 12, 14, 18, 20, 24, 30, 100};
    private static final float[] DP_130 = {10.4f, 13, 15.6f, 18.8f, 21.6f, 23.6f, 26.4f, 30, 100};
    private static final float[] DP_200 = {16, 20, 24, 26, 30, 34, 36, 38, 100};

    /** A size in sp as dp: Android 14's table (interpolated, from 0 below its first point), or linear (table null). */
    private static float toDp(float sp, float fontScale, float[] table) {
        if (table == null) return sp * fontScale;
        int last = TABLE_SP.length - 1;
        if (sp <= TABLE_SP[0]) return sp * table[0] / TABLE_SP[0];
        int i = 1;
        while (i < last && sp > TABLE_SP[i]) i++;
        float x0 = TABLE_SP[i - 1], x1 = TABLE_SP[i], y0 = table[i - 1], y1 = table[i];
        return y0 + (sp - x0) * (y1 - y0) / (x1 - x0);
    }

    /** {@link #slot} at Volt's 1.0x with Android's font at {@code fontScale}, each size converted as {@link #toDp} does. */
    private static int[] slotAt(int width, int height, float fontScale, float[] table) {
        boolean wide = WideLayout.wide(width, fontScale);
        int inner = TopArea.heightDp(width, height, wide, fontScale);
        int across = Math.min(width - 36, 560);
        int[] pill = ChargingFitTest.pill(toDp(ChargingFit.LINE_SP, fontScale, table), across, 1);
        int oneLine = pill[0], line = pill[1];
        int percent = Math.round(toDp(72, fontScale, table) * ChargingFit.PERCENT_SP / 72f * 1.17f);
        float tile = 20 + toDp(12, fontScale, table) * 1.17f + 2 + Math.max(ChargingFit.VALUE_MIN_DP, toDp(ChargingFit.VALUE_SP, fontScale, table) * 1.33f);
        int readings = Math.round(across >= ChargingFit.PAIR_DP ? tile : 2 * tile + 8);
        return new int[] {(int) (inner * D), (int) (line * D), (int) (oneLine * D), (int) (percent * D), (int) (readings * D), (int) (across * D)};
    }

    /**
     * A larger Android font size never makes the percentage smaller than at 100% (review of #483, P3). From Android 14
     * large sizes grow nonlinearly (72 sp at 130% is 72 dp, at 200% about 75 dp), so the number's 1.0x size is found
     * from its measured growth (ChargePictureFit.sizeGrowth), not by dividing by the font size; before Android 14 (sp
     * linear) the two are the same and nothing changes. At 344 x 882 and 725 x 604 dp, Clean and Widgets, Volt at 1.0x:
     * the number is at least its size at 100% wherever the slot's plan keeps the percentage's full size, never smaller
     * than the earlier rule made it, and grows from the picture's share as far as the picture allows.
     */
    private static void nonlinearFont() {
        near(ChargePictureFit.sizeGrowth(72 * D, 72, D), 1, 1e-6, "100%: no growth");
        near(ChargePictureFit.sizeGrowth(72 * 1.3f * D, 72, D), 1.3f, 1e-6, "linear 130%: the font size");
        near(ChargePictureFit.sizeGrowth(toDp(72, 1.3f, DP_130) * D, 72, D), 1, 1e-6, "Android 14 at 130%: 72 sp is 72 dp");
        near(ChargePictureFit.sizeGrowth(toDp(72, 2, DP_200) * D, 72, D), 75.2f / 72, 1e-4, "Android 14 at 200%: 72 sp is 75.2 dp");
        near(ChargePictureFit.sizeGrowth(0, 72, D), 1, 0, "unknown: 1");
        near(ChargePictureFit.sizeGrowth(72 * D, 72, 0), 1, 0, "no density: 1");
        Object[][] cases = {{"Android 14 at 130%", 1.3f, DP_130}, {"Android 14 at 200%", 2f, DP_200},
                {"Android 13 at 130%", 1.3f, null}, {"Android 13 at 200%", 2f, null}};
        for (int[] window : new int[][] {{344, 882}, {725, 604}}) {
            String size = window[0] + "x" + window[1];
            int[] base = slot(window[0], window[1], 1);
            float cleanBase = ChargePictureFit.cleanSlot(base[0], base[1], base[2], base[3], base[4], base[5], FOOT, ChargePicture_LIFT * D, 0, SHARE, 60 * D, 1, D).fit.text;
            float w = (window[0] - 32) * D;
            float widgetsBase = ChargePictureFit.widgets(w, SHARE, 60 * D, 60 * D, 1, D).text;
            for (Object[] c : cases) {
                String name = size + " " + c[0];
                float fontScale = (float) c[1];
                float[] table = (float[]) c[2];
                int[] s = slotAt(window[0], window[1], fontScale, table);
                // The number's full size: its 72 sp as converted, at the Layout Plan's 60/72 (DashboardScreen).
                float full = toDp(72, fontScale, table) * D * ChargingFit.PERCENT_SP / 72f;
                float grown = ChargePictureFit.sizeGrowth(toDp(72, fontScale, table) * D, 72, D);
                ChargePictureFit.SlotFit fit = ChargePictureFit.cleanSlot(s[0], s[1], s[2], s[3], s[4], s[5], FOOT, ChargePicture_LIFT * D, 0, SHARE, full, fontScale, grown, D);
                ChargePictureFit.Fit f = fit.fit;
                float earlier = ChargePictureFit.cleanSlot(s[0], s[1], s[2], s[3], s[4], s[5], FOOT, ChargePicture_LIFT * D, 0, SHARE, full, fontScale, D).fit.text;
                // Where the slot's plan keeps the percentage's full size (ChargingFit: a slot too short for it shrinks it,
                // #449's rule, whatever the picture), the number is at least its size at 100%; and never smaller than
                // the fit that divided by the font size.
                if (fit.plan.percentScale >= 1) require(f.text >= cleanBase - .01f, name + " Clean: the number " + f.text / D + " dp is smaller than at 100% (" + cleanBase / D + ")");
                require(f.text >= earlier - .01f, name + " Clean: the number " + f.text / D + " dp is smaller than the earlier rule's " + earlier / D);
                float oneX = 60 * D * fit.plan.percentScale;
                require(f.text <= ChargePictureFit.TEXT_PER_BOX * f.box * fontScale + .5f || f.text <= oneX + .5f,
                        name + " Clean: the number " + f.text / D + " outgrows its picture " + f.box / D);
                require(f.text <= full * fit.plan.percentScale + .01f, name + " Clean: never above its full size");
                float share = (float) (ChargePictureFit.TEXT_PER_BOX * f.box);
                require(f.text >= Math.min(oneX, share * fontScale) - .5f || f.text >= full * fit.plan.percentScale - .5f,
                        name + " Clean: the number " + f.text / D + " does not grow as far as the picture allows (" + Math.min(oneX, share * fontScale) / D + ")");
                // Before Android 14 the measured growth is the font size: the fit is the earlier rule's.
                if (table == null) near(f.text, earlier, 1e-3, name + " Clean: unchanged where sp is linear");
                ChargePictureFit.Fit wf = ChargePictureFit.widgets(w, SHARE, full, full / Math.max(1, grown), fontScale, D);
                require(wf.text >= widgetsBase - .01f, name + " Widgets: the number " + wf.text / D + " dp is smaller than at 100% (" + widgetsBase / D + ")");
                require(wf.text <= ChargePictureFit.TEXT_PER_BOX * wf.box * fontScale + .5f || wf.text <= 60 * D + .5f,
                        name + " Widgets: the number " + wf.text / D + " outgrows its picture " + wf.box / D);
                if (table == null) near(wf.text, ChargePictureFit.widgets(w, SHARE, full, full / fontScale, fontScale, D).text, 1e-3, name + " Widgets: unchanged where sp is linear");
            }
        }
        // The review's case on the emulator: 344 x 882 at Android 14's 130%, Volt at 1.0x, Clean's stage leaving the
        // picture a 51.9 dp share. The number's 72 sp converts to 72 dp (60 dp at the Layout Plan's 60/72), so dividing
        // by the font size took its 1.0x size as 46.2 dp and capped it at the share, 51.9 dp (it was 60 dp at 100%); its
        // measured growth (1.0) keeps its 1.0x size at 60 dp, and the share grown by the font size allows it.
        float full = toDp(72, 1.3f, DP_130) * D * ChargingFit.PERCENT_SP / 72f;
        near(full, 60 * D, 1e-3, "72 sp at Android 14's 130% at 60/72: 60 dp");
        float box = (float) (51.9 * D / ChargePictureFit.TEXT_PER_BOX);
        float linear = ChargePictureFit.text(box, full, full / 1.3f, 1.3f);
        float measured = ChargePictureFit.text(box, full, full / ChargePictureFit.sizeGrowth(toDp(72, 1.3f, DP_130) * D, 72, D), 1.3f);
        near(linear / D, 51.9, 1e-3, "dividing by the font size: capped at the 51.9 dp share");
        near(measured / D, 60, 1e-3, "the measured growth: 60 dp, as at 100%");
        // Widgets at 344 and 725 dp: the share allows the full 60 dp both ways (Android 14 does not grow 72 sp at 130%).
        for (int width : new int[] {344, 725}) {
            float w = (width - 32) * D;
            near(ChargePictureFit.widgets(w, SHARE, full, full / ChargePictureFit.sizeGrowth(72 * D, 72, D), 1.3f, D).text / D, 60, 1e-3, width + " Widgets at 130%: 60 dp");
        }
        // Volt's own Text size at Android's 100% (no converter: linear) fits as before.
        int[] v = slot(725, 604, 1.4f);
        near(ChargePictureFit.cleanSlot(v[0], v[1], v[2], v[3], v[4], v[5], FOOT, ChargePicture_LIFT * D, 0, SHARE, 60 * D * 1.4f, 1.4f, ChargePictureFit.sizeGrowth(72 * 1.4f * D, 72, D), D).fit.text,
                ChargePictureFit.cleanSlot(v[0], v[1], v[2], v[3], v[4], v[5], FOOT, ChargePicture_LIFT * D, 0, SHARE, 60 * D * 1.4f, 1.4f, D).fit.text, 1e-3,
                "725x604 at Volt's 1.4x: as before");
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
