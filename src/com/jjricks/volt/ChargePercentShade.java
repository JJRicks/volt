package com.jjricks.volt;

/**
 * What sits behind the charging percentage when a charging picture shows under it (the contributor's charging
 * pictures, 14 Nov 2026 on #383), by picture type. Pure Java; the view draws it.
 *
 * <ul>
 *   <li>{@link #HALO}, for the animated wire pictures (ChargeWireModels): the approved dark radial halo, a circle 2.8
 *       em across centred on the number, its fill at 47% in the middle, 24% at 55% of the way out, then nothing.
 *   <li>{@link #CUTOFF}, for the folded pictures (ChargeFolded): a rounded box hugging the number (7/60 em outside its
 *       advance, from 0.8 em above the baseline to 8.5/60 em below it, corners 14/60 em) in the page's own background,
 *       blurred by a Gaussian of 5/60 em; so it is about 92% opaque at the glyphs and gone about 17/60 em beyond them,
 *       and whatever part of the scooter is behind the number disappears into the page.
 * </ul>
 *
 * Every size is a share of an em: the halo's radius and the cut-off's reach below the baseline (its bottom, corners
 * and blur) of the {@code em} the picture allows (ChargePictureFit.Fit.shadeSize: the text size, at most the picture
 * box's share), the parts that hug the number (the halo's centre, the cut-off's sides and top) of the text's own
 * {@code size}. So the shade grows with the picture, and never erases more of a picture smaller than the number than
 * the prototype erases of its own. Coordinates are the parent's pixels: the text's advance from {@code left} to
 * {@code right} and its {@code baseline}. The results go into the caller's array (nothing is allocated per frame).
 */
final class ChargePercentShade {
    private ChargePercentShade() {}

    static final int NONE = 0, HALO = 1, CUTOFF = 2;

    /** The prototype's text size (px = dp on its phone), the unit every size below is a share of. */
    static final double EM = 60;

    /**
     * Over a picture on Clean the number sits this share of its size left of the content's centre: the prototype pads
     * its box 4 left and 12 right and centres the box, so the halo is centred on the content and the digits 4 dp left.
     */
    static final double PERCENT_LEFT = 4 / EM;

    /** How far the percentage moves left over a picture (px) at this text size. */
    static float percentLeft(float size) { return (float) (PERCENT_LEFT * size); }

    // ---- the halo ----
    /** Its radius, its centre right of the advance's centre (the prototype's box is padded 4 left, 12 right) and above the baseline. */
    static final double HALO_RADIUS = 84 / EM, HALO_RIGHT = 4 / EM, HALO_UP = 14 / EM;
    /** Its fill's opacity at the centre, at {@link #HALO_STOPS}[1] of the way out and at the edge. */
    static final float[] HALO_ALPHAS = {.47f, .24f, 0}, HALO_STOPS = {0, .55f, 1};

    /** The halo's centre and radius, {cx, cy, r}, into out. */
    static float[] halo(float left, float right, float baseline, float size, float em, float[] out) {
        out[0] = (float) ((left + right) / 2 + HALO_RIGHT * size);
        out[1] = (float) (baseline - HALO_UP * size);
        out[2] = (float) (HALO_RADIUS * em);
        return out;
    }

    // ---- the cut-off ----
    /** The box's edges from the text (outward from the advance's sides, above and below the baseline), its corners and blur. */
    static final double CUT_SIDE = 7 / EM, CUT_ABOVE = 48 / EM, CUT_BELOW = 8.5 / EM, CUT_RADIUS = 14 / EM, CUT_SIGMA = 5 / EM;
    /** How far past the box the blur still shows (3 sigma: under 0.2%). */
    static final double CUT_REACH = 3 * CUT_SIGMA;

    /** The unblurred box, {left, top, right, bottom}, into out: its sides and top from the text, its bottom from em. */
    static float[] cutoffBox(float left, float right, float baseline, float size, float em, float[] out) {
        out[0] = (float) (left - CUT_SIDE * size);
        out[1] = (float) (baseline - CUT_ABOVE * size);
        out[2] = (float) (right + CUT_SIDE * size);
        out[3] = (float) (baseline + CUT_BELOW * em);
        return out;
    }

    static float cutoffRadius(float size) { return (float) (CUT_RADIUS * size); }
    static float cutoffSigma(float size) { return (float) (CUT_SIGMA * size); }
    static float cutoffReach(float size) { return (float) (CUT_REACH * size); }

    /**
     * The blurred box's opacity at (x, y), 0 to 1: the Gaussian-blurred edge of the rounded box (one minus the normal
     * distribution's tail at the signed distance from its edge, in sigmas). Exact along the straight edges; at the
     * corners (radius almost three sigmas) within a few percent of the true blur.
     */
    static double cutoffAlpha(float[] box, float radius, float sigma, double x, double y) {
        double cx = (box[0] + box[2]) / 2.0, cy = (box[1] + box[3]) / 2.0;
        double hx = (box[2] - box[0]) / 2.0 - radius, hy = (box[3] - box[1]) / 2.0 - radius;
        double qx = Math.abs(x - cx) - hx, qy = Math.abs(y - cy) - hy;
        double outside = Math.hypot(Math.max(qx, 0), Math.max(qy, 0)), inside = Math.min(Math.max(qx, qy), 0);
        double distance = outside + inside - radius;
        return sigma <= 0 ? (distance <= 0 ? 1 : 0) : phi(-distance / sigma);
    }

    /**
     * The blurred box's opacity at each pixel centre of a {@code width} x {@code height} grid whose top-left pixel
     * starts at (x0, y0), as 0-255 alpha bytes in rows (the view fills a bitmap with them in the page colour).
     */
    static int[] cutoffMask(float[] box, float radius, float sigma, float x0, float y0, int width, int height) {
        int[] alpha = new int[Math.max(0, width) * Math.max(0, height)];
        for (int j = 0; j < height; j++)
            for (int i = 0; i < width; i++)
                alpha[j * width + i] = (int) Math.round(255 * cutoffAlpha(box, radius, sigma, x0 + i + .5, y0 + j + .5));
        return alpha;
    }

    /** The standard normal distribution's cumulative probability (Abramowitz and Stegun 7.1.26, within 1.5e-7). */
    static double phi(double z) {
        double x = Math.abs(z) / Math.sqrt(2), t = 1 / (1 + 0.3275911 * x);
        double erf = 1 - t * (0.254829592 + t * (-0.284496736 + t * (1.421413741 + t * (-1.453152027 + t * 1.061405429)))) * Math.exp(-x * x);
        return z >= 0 ? (1 + erf) / 2 : (1 - erf) / 2;
    }
}
