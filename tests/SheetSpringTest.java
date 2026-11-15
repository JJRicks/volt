package com.jjricks.volt;

/**
 * SheetSpring: how the dock's All sheet (DragSheet) moves once a finger lets go. Geometry is in px at a 2.625 density
 * (a 420 dpi phone) on sheets 300, 600 and 900 dp tall; every settle is sampled each millisecond.
 */
public final class SheetSpringTest {
    private static int checks;
    private static final double DP = 2.625, CAP = SheetSpring.CAP_DP * DP, REST = SheetSpring.REST_DP * DP;
    private static final double FLING = SheetSpring.FLING_DP * DP, MIN_FLING = 50 * DP;
    private static final double[] TRAVELS = {300 * DP, 600 * DP, 900 * DP};

    public static void main(String[] args) {
        springMatchesItsEquation();
        velocityIsThePositionsSlope();
        openingBouncesOnceWithinTheCap();
        openingSettlesInTheOwnersWindow();
        closingNeverBounces();
        closingTakesAboutAThirdOfASecond();
        handOffKeepsPositionAndSpeed();
        releaseDecision();
        quickFlickStillOpens();
        caughtCarriesOn();
        rubberBand();
        endsExactly();
        System.out.println("SheetSpring: " + checks + " checks passed");
    }

    /** Both springs solve x'' + 2 z w x' + w^2 x = 0 from x0, v0 (checked by finite differences). */
    private static void springMatchesItsEquation() {
        for (SheetSpring.Spring spring : new SheetSpring.Spring[]{SheetSpring.OPENING, SheetSpring.CLOSING}) {
            double z = spring.damping, w = spring.stiffness, h = 1e-4;
            for (double[] start : new double[][]{{-1000, 0}, {500, -3000}, {-20, 4000}, {0, 1000}}) {
                double x0 = start[0], v0 = start[1];
                near(spring.position(x0, v0, 0), x0, 1e-9, "starts at x0");
                near(spring.velocity(x0, v0, 0), v0, 1e-6, "starts at v0");
                for (double t = .01; t < .8; t += .037) {
                    double x = spring.position(x0, v0, t), a = (spring.position(x0, v0, t + h) - 2 * x + spring.position(x0, v0, t - h)) / (h * h);
                    double v = spring.velocity(x0, v0, t);
                    near(a + 2 * z * w * v + w * w * x, 0, 1e-3 * (Math.abs(x0) * w * w + Math.abs(v0) * w + 1), "the equation holds at " + t + " (z " + z + ")");
                }
            }
        }
        try { new SheetSpring.Spring(1.2, 10); require(false, "an overdamped spring is refused"); } catch (IllegalArgumentException expected) { require(true, ""); }
        try { new SheetSpring.Spring(.5, 0); require(false, "no stiffness is refused"); } catch (IllegalArgumentException expected) { require(true, ""); }
    }

    private static void velocityIsThePositionsSlope() {
        double h = 1e-6;
        for (SheetSpring.Spring spring : new SheetSpring.Spring[]{SheetSpring.OPENING, SheetSpring.CLOSING})
            for (double t = 0; t < .6; t += .05) {
                double slope = (spring.position(-800, 1200, t + h) - spring.position(-800, 1200, t - Math.min(h, t))) / (h + Math.min(h, t));
                near(spring.velocity(-800, 1200, t), slope, 1e-2 * 800 * 18, "velocity is the slope at " + t);
            }
    }

    /**
     * From any release that opens (a quarter to all of it shown, at rest or flung up at up to 6000 dp/s), the sheet
     * reaches open, passes it once by under the cap, comes back, and any second ripple is barely visible.
     */
    private static void openingBouncesOnceWithinTheCap() {
        double biggest = 0;
        for (double travel : TRAVELS) for (double shown = .25; shown <= 1.0001; shown += .05) for (double up : new double[]{0, 300, 700, 2000, 4000, 6000}) {
            SheetSpring.Plan plan = SheetSpring.Plan.open((1 - shown) * travel, -up * DP, travel, CAP, REST);
            double lowest = 0, ripple = 0; boolean passed = false, back = false; double previous = (1 - shown) * travel;
            for (double t = 0; t <= plan.seconds; t += .001) {
                double o = plan.offset(t);
                require(o > -CAP, "never past the cap: " + o / DP + " dp");
                if (o < 0) passed = true;
                if (passed && o >= previous && o < 0 && !back) back = true;
                if (back && o > 0) ripple = Math.max(ripple, o);
                lowest = Math.min(lowest, o);
                previous = o;
            }
            if (shown < .95 || up > 0) require(lowest < -.5 * DP, "a visible bounce from " + shown + " at " + up + " dp/s: " + lowest / DP + " dp");
            // Under a dp for an ordinary release; even the hardest flings (the bounce held at the cap) keep it under 3 dp.
            require(ripple < (up <= 2000 && shown < .85 ? 1 : 3) * DP, "the second ripple is barely visible: " + ripple / DP + " dp from " + shown + " at " + up);
            biggest = Math.max(biggest, -lowest);
        }
        require(biggest < CAP && biggest > CAP * .9, "the hardest fling nears the cap without passing it: " + biggest / DP + " dp");
        // From rest a quarter shown on a 600 dp sheet: about 3% of the way (11 dp), the peak a quarter second in.
        double travel = 600 * DP;
        SheetSpring.Plan rest = SheetSpring.Plan.open(.75 * travel, 0, travel, CAP, REST);
        double lowest = 0, at = 0;
        for (double t = 0; t <= rest.seconds; t += .001) if (rest.offset(t) < lowest) { lowest = rest.offset(t); at = t; }
        require(-lowest / DP > 8 && -lowest / DP < 14, "from rest the bounce is about 11 dp: " + -lowest / DP);
        require(at > .2 && at < .3, "its peak about a quarter second in: " + at);
        // The way there is monotonic: no wobble before the one bounce.
        double previous = rest.offset(0);
        for (double t = .001; t <= at; t += .001) { require(rest.offset(t) <= previous + 1e-9, "monotonic until the bounce at " + t); previous = rest.offset(t); }
        previous = rest.offset(at);
        for (double t = at + .001; t <= .45; t += .001) { require(rest.offset(t) >= previous - 1e-9, "the way back from the bounce is monotonic at " + t); previous = rest.offset(t); }
    }

    /** Settled (within half a dp) in about 450 to 550 ms from a commit-point release (a tablet-tall 900 dp sheet up to 600), and never past 800 ms (the owner's window). */
    private static void openingSettlesInTheOwnersWindow() {
        for (double travel : TRAVELS) {
            SheetSpring.Plan commit = SheetSpring.Plan.open(.75 * travel, 0, travel, CAP, REST);
            require(commit.millis() >= 450 && commit.millis() <= (travel > 700 * DP ? 600 : 550), "a commit-point release settles in about half a second: " + commit.millis() + " ms on " + travel / DP + " dp");
            for (double shown = .25; shown <= 1; shown += .05) for (double up : new double[]{0, 700, 3000}) {
                SheetSpring.Plan plan = SheetSpring.Plan.open((1 - shown) * travel, -up * DP, travel, CAP, REST);
                require(plan.millis() <= 800, "settles within 800 ms: " + plan.millis());
                for (double t = plan.seconds; t <= plan.seconds + .3; t += .005)
                    require(Math.abs(plan.offset(t)) <= REST, "stays settled after its end");
                // Still moving visibly until near its end: the animation is not cut short.
                if (shown < .9) require(Math.abs(plan.offset(plan.seconds - .1)) <= 3 * DP, "close to rest 100 ms before the end");
            }
        }
        SheetSpring.Plan already = SheetSpring.Plan.open(0, 0, 600 * DP, CAP, REST);
        require(already.millis() == 0, "already open and still: nothing to do");
    }

    /** Snap-back and close: critically damped, so the sheet only ever goes down (unless flung up first), and never back above where it was. */
    private static void closingNeverBounces() {
        for (double travel : TRAVELS) for (double shown = .01; shown <= 1.0001; shown += .03) for (double down : new double[]{0, 300, 700, 3000}) {
            double from = (1 - shown) * travel;
            SheetSpring.Plan plan = SheetSpring.Plan.close(from, down * DP, travel);
            require(!plan.open, "a close plan");
            double previous = from;
            for (double t = .001; t <= plan.seconds; t += .001) {
                double o = plan.offset(t);
                require(o >= previous - 1e-9, "closing only goes down: " + previous + " -> " + o + " at " + t);
                previous = o;
            }
            require(plan.offset(plan.seconds) >= travel, "it ends wholly under the edge");
        }
        // Released with a slow upward drift below the commit point: it may rise a hair first, then goes, with no bounce back.
        double travel = 600 * DP;
        SheetSpring.Plan drift = SheetSpring.Plan.close(.85 * travel, -300 * DP, travel);
        double highest = .85 * travel; boolean falling = false; double previous = highest;
        for (double t = .001; t <= drift.seconds; t += .001) {
            double o = drift.offset(t);
            if (o > previous) falling = true;
            if (falling) require(o >= previous - 1e-9, "once it falls it keeps falling");
            highest = Math.min(highest, o); previous = o;
        }
        require(highest > .8 * travel, "a slow upward drift lifts it barely: " + (1 - highest / travel));
    }

    /** From rest every close takes about 0.3 s (a 10% snap-back and a close from open alike); a fling down is quicker. */
    private static void closingTakesAboutAThirdOfASecond() {
        for (double travel : TRAVELS) for (double shown : new double[]{.05, .1, .15, .24, .5, 1}) {
            SheetSpring.Plan plan = SheetSpring.Plan.close((1 - shown) * travel, 0, travel);
            require(plan.millis() >= 250 && plan.millis() <= 350, "a close from " + shown + " shown takes " + plan.millis() + " ms");
            SheetSpring.Plan flung = SheetSpring.Plan.close((1 - shown) * travel, 2000 * DP, travel);
            require(flung.millis() < plan.millis(), "a fling down is quicker");
        }
        require(SheetSpring.Plan.close(600 * DP, 0, 600 * DP).millis() == 0, "already closed: nothing to do");
        require(SheetSpring.Plan.close(700 * DP, 0, 600 * DP).millis() == 0, "below closed: nothing to do");
    }

    /** Position and velocity carry over from the finger, both on the way and past open (where the soft limit is undone). */
    private static void handOffKeepsPositionAndSpeed() {
        double travel = 600 * DP;
        for (double offset : new double[]{.6 * travel, .1 * travel, 0, -5 * DP, -15 * DP})
            for (double v : new double[]{-1500 * DP, -200 * DP, 0, 400 * DP}) {
                SheetSpring.Plan open = SheetSpring.Plan.open(offset, v, travel, CAP, REST);
                if (open.seconds == 0) continue;
                near(open.offset(0), offset, 1e-6 * travel, "opening starts where the sheet is (" + offset + ")");
                near(open.velocity(0), v, 1e-3 * Math.abs(v) + 1e-6, "opening starts at the finger's speed (" + offset + ", " + v + ")");
                double h = 1e-5;
                near((open.offset(h) - open.offset(0)) / h, v, .02 * Math.abs(v) + 50, "and moves at it (" + offset + ", " + v + ")");
                if (offset < 0) continue;
                SheetSpring.Plan close = SheetSpring.Plan.close(offset, v, travel);
                near(close.offset(0), offset, 1e-6 * travel, "closing starts where the sheet is");
                near(close.velocity(0), v, 1e-3 * Math.abs(v) + 1e-6, "closing starts at the finger's speed");
            }
    }

    /** V1 is a quarter of the travel from where the drag began; a fling decides alone; at exactly a quarter, it commits. */
    private static void releaseDecision() {
        double travel = 600 * DP; long slow = 1000;
        // From closed (pulled up from the dock).
        require(!SheetSpring.opens(false, travel, travel, 0, FLING, MIN_FLING, slow), "nothing shown: closed");
        require(!SheetSpring.opens(false, .9 * travel, travel, 0, FLING, MIN_FLING, slow), "10% shown: back");
        require(!SheetSpring.opens(false, .76 * travel, travel, 0, FLING, MIN_FLING, slow), "24% shown: back");
        require(SheetSpring.opens(false, .75 * travel, travel, 0, FLING, MIN_FLING, slow), "exactly 25%: open");
        require(SheetSpring.opens(false, .6 * travel, travel, 0, FLING, MIN_FLING, slow), "40% shown: open");
        require(SheetSpring.opens(false, -10, travel, 0, FLING, MIN_FLING, slow), "past open: open");
        require(SheetSpring.opens(false, .95 * travel, travel, -FLING, FLING, MIN_FLING, slow), "5% shown, flung up: open");
        require(!SheetSpring.opens(false, .2 * travel, travel, FLING, FLING, MIN_FLING, slow), "80% shown, flung down: closed");
        require(SheetSpring.opens(false, .6 * travel, travel, FLING * .9, FLING, MIN_FLING, slow), "40% shown, drifting down below a fling: open");
        require(!SheetSpring.opens(false, .9 * travel, travel, -FLING * .9, FLING, MIN_FLING, slow), "10% shown, rising below a fling: back");
        // From open (dragged on the sheet).
        require(SheetSpring.opens(true, .1 * travel, travel, 0, FLING, MIN_FLING, slow), "10% down: back open");
        require(SheetSpring.opens(true, .24 * travel, travel, 0, FLING, MIN_FLING, slow), "24% down: back open");
        require(!SheetSpring.opens(true, .25 * travel, travel, 0, FLING, MIN_FLING, slow), "exactly 25% down: closes");
        require(!SheetSpring.opens(true, .4 * travel, travel, 0, FLING, MIN_FLING, slow), "40% down: closes");
        require(!SheetSpring.opens(true, .05 * travel, travel, FLING, FLING, MIN_FLING, slow), "5% down, flung down: closes");
        require(SheetSpring.opens(true, .6 * travel, travel, -FLING, FLING, MIN_FLING, slow), "60% down, flung up: open");
        require(SheetSpring.opens(true, -10, travel, 0, FLING, MIN_FLING, slow), "pulled past open: open");
        // No travel known: stays as it was.
        require(SheetSpring.opens(true, 0, 0, 0, FLING, MIN_FLING, slow) && !SheetSpring.opens(false, 0, 0, 0, FLING, MIN_FLING, slow), "no travel: as it started");
    }

    /** A flick that lifts right after the swipe is recognised (today's behaviour): open even below the fling speed. */
    /** A sheet caught while it settled: it carries on unless the catch moved it a quarter of the travel against its settle. */
    private static void caughtCarriesOn() {
        double travel = 1800;
        long slow = 900;
        // Caught closing with half of it showing (offset 900).
        require(!SheetSpring.caughtOpens(true, 900, 900, travel, 0, FLING, MIN_FLING, slow), "a plain tap on a closing sheet: carries on closing");
        require(!SheetSpring.caughtOpens(true, 900, 900, travel, 0, FLING, MIN_FLING, 80), "a quick plain tap: carries on closing");
        require(!SheetSpring.caughtOpens(true, 900, 900 - .24 * travel, travel, 0, FLING, MIN_FLING, slow), "caught closing, up 24%: carries on closing");
        require(SheetSpring.caughtOpens(true, 900, 900 - .25 * travel, travel, 0, FLING, MIN_FLING, slow), "caught closing, up exactly 25%: reopens");
        require(!SheetSpring.caughtOpens(true, 900, 900 + 100, travel, 0, FLING, MIN_FLING, slow), "caught closing, taken lower: closes");
        // Taken up a third and back to where it was caught: carries on closing (opens() would reopen half a sheet).
        require(!SheetSpring.caughtOpens(true, 900, 900, travel, 0, FLING, MIN_FLING, slow) && SheetSpring.opens(false, 900, travel, 0, FLING, MIN_FLING, slow),
                "caught closing and back to its start: closes, where a pull from closed at the same place would open");
        // Caught low (a tenth showing): up 25% of the travel reopens though only 35% shows.
        require(SheetSpring.caughtOpens(true, .9 * travel, .65 * travel, travel, 0, FLING, MIN_FLING, slow), "caught at a tenth, up 25%: reopens");
        // Flings decide first.
        require(SheetSpring.caughtOpens(true, 900, 900, travel, -FLING, FLING, MIN_FLING, slow), "caught closing, flung up: reopens");
        require(!SheetSpring.caughtOpens(true, 900, 900 - .4 * travel, travel, FLING, FLING, MIN_FLING, slow), "caught closing, up 40% but flung down: closes");
        require(SheetSpring.caughtOpens(true, 900, 900 - 50, travel, -MIN_FLING, FLING, MIN_FLING, 120), "caught closing, a quick flick up: reopens");
        // Caught opening (offset 300): keeps opening unless dragged down a quarter, or flung down.
        require(SheetSpring.caughtOpens(false, 300, 300, travel, 0, FLING, MIN_FLING, slow), "a plain catch on an opening sheet: keeps opening");
        require(SheetSpring.caughtOpens(false, 300, 300 + .24 * travel, travel, 0, FLING, MIN_FLING, slow), "caught opening, down 24%: keeps opening");
        require(!SheetSpring.caughtOpens(false, 300, 300 + .25 * travel, travel, 0, FLING, MIN_FLING, slow), "caught opening, down exactly 25%: closes");
        require(SheetSpring.caughtOpens(false, 300, 250, travel, 0, FLING, MIN_FLING, slow), "caught opening, taken higher: opens");
        require(!SheetSpring.caughtOpens(false, 300, 300, travel, FLING, FLING, MIN_FLING, slow), "caught opening, flung down: closes");
        require(!SheetSpring.caughtOpens(false, 300, 300 + 50, travel, MIN_FLING, FLING, MIN_FLING, 120), "caught opening, a quick flick down: closes");
        // Caught near an end, pushed all the way to the other: that end, though under a quarter from the catch.
        require(SheetSpring.caughtOpens(true, .1 * travel, 0, travel, 0, FLING, MIN_FLING, slow), "caught closing at 90% shown, pushed fully open: stays open");
        require(SheetSpring.caughtOpens(true, .1 * travel, -10, travel, 0, FLING, MIN_FLING, slow), "caught closing at 90% shown, pushed past open: stays open");
        require(!SheetSpring.caughtOpens(true, .1 * travel, 1, travel, 0, FLING, MIN_FLING, slow), "caught closing at 90% shown, pushed to just under open: closes");
        require(!SheetSpring.caughtOpens(false, .9 * travel, travel, travel, 0, FLING, MIN_FLING, slow), "caught opening at 10% shown, pulled fully down: closes");
        require(SheetSpring.caughtOpens(false, .9 * travel, travel - 1, travel, 0, FLING, MIN_FLING, slow), "caught opening at 10% shown, pulled almost down: keeps opening");
        // No travel yet: the settle it was on.
        require(!SheetSpring.caughtOpens(true, 0, 0, 0, 0, FLING, MIN_FLING, slow) && SheetSpring.caughtOpens(false, 0, 0, 0, 0, FLING, MIN_FLING, slow), "no travel: carries on");
    }

    private static void quickFlickStillOpens() {
        double travel = 600 * DP, shown = 20 * DP;
        require(SheetSpring.opens(false, travel - shown, travel, -400 * DP, FLING, MIN_FLING, 80), "an 80 ms flick at 400 dp/s opens");
        require(SheetSpring.opens(false, travel - shown, travel, -MIN_FLING, FLING, MIN_FLING, SheetSpring.QUICK_MS), "at the quick limit, at the minimum fling, opens");
        require(!SheetSpring.opens(false, travel - shown, travel, -400 * DP, FLING, MIN_FLING, SheetSpring.QUICK_MS + 1), "a slower gesture at the same speed does not");
        require(!SheetSpring.opens(false, travel - shown, travel, -MIN_FLING * .5, FLING, MIN_FLING, 80), "a quick touch barely moving at the end does not");
        require(!SheetSpring.opens(false, travel - shown, travel, 0, FLING, MIN_FLING, -1), "settling by position (-1) is no flick");
        require(!SheetSpring.opens(true, .05 * travel, travel, 400 * DP, FLING, MIN_FLING, 90), "a quick flick down on the open sheet closes it");
    }

    /** Past open a drag moves the sheet a third as far, easing toward the cap, never past it; the inverse round-trips. */
    private static void rubberBand() {
        double travel = 600 * DP;
        near(SheetSpring.dragged(-3, travel, CAP), -1, .01, "a third of the finger at first");
        double previous = 0;
        for (double raw = 0; raw >= -2000 * DP; raw -= 1) {
            double o = SheetSpring.dragged(raw, travel, CAP);
            require(o >= -CAP && o <= previous, "monotonic and never past the cap at " + raw);
            previous = o;
        }
        require(SheetSpring.dragged(-60 * DP, travel, CAP) < -.7 * CAP, "a long pull nears the cap");
        require(SheetSpring.dragged(100, travel, CAP) == 100 && SheetSpring.dragged(travel + 500, travel, CAP) == travel, "1:1 on the way, never below closed");
        for (double raw : new double[]{-1, -10, -100, -400, 0, 50, 800})
            near(SheetSpring.undragged(SheetSpring.dragged(raw, travel, CAP), CAP), raw, Math.abs(raw) * 1e-6 + 1e-6, "undragged round-trips " + raw);
        near(SheetSpring.dragSlope(0, CAP), 1, 0, "1:1 at open");
        near(SheetSpring.dragSlope(-1e-9, CAP), 1 / 3d, 1e-6, "a third just past open");
        require(SheetSpring.dragSlope(-400, CAP) < 1 / 3d, "less further on");
        // The bounce's soft limit: smooth at open, never past the cap.
        near(SheetSpring.capped(-1e-6, CAP), -1e-6, 1e-9, "no kink at open");
        require(SheetSpring.capped(-1e6, CAP) >= -CAP && SheetSpring.capped(5, CAP) == 5, "capped past open only");
    }

    /** A plan ends exactly at open (0) or at closed (the travel). */
    private static void endsExactly() {
        double travel = 600 * DP;
        SheetSpring.Plan open = SheetSpring.Plan.open(.5 * travel, 0, travel, CAP, REST);
        require(open.offset(open.seconds) == 0 && open.offset(open.seconds + 1) == 0, "open ends at 0");
        SheetSpring.Plan close = SheetSpring.Plan.close(.5 * travel, 0, travel);
        require(close.offset(close.seconds) == travel, "closed ends at the travel");
        require(open.seconds <= SheetSpring.LONGEST && close.seconds <= SheetSpring.LONGEST, "both within the longest");
    }

    private static void near(double actual, double expected, double tolerance, String what) {
        require(Math.abs(actual - expected) <= tolerance, what + ": " + actual + " vs " + expected);
    }

    private static void require(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }
}
