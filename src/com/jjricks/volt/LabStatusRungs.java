package com.jjricks.volt;

/**
 * The quiet words of an Experimental controls row with no try kept (MoreScreen.labStatus), as the narrow-label ladder
 * MoreScreen's LadderPill draws: full first, then shorter rungs; it shows the first that fits beside the Try button and
 * speaks the first (the full words) always. Pure, so the JVM suite tests it (tests/l10n/LabStatusRungsTest.java).
 */
final class LabStatusRungs {
    private LabStatusRungs() {}

    /**
     * A control tried on this scooter before, whose try was pruned (TestJournal.triedBefore): "Tried before", then
     * "Tried", then no words. The short rung comes before the empty one because no words reads as the untried default
     * (Not tried yet's own last rung), which would hide that it was tried (review of #622).
     */
    static String[] triedBefore() {
        return new String[] {T.moreLabStatusTriedBefore(), T.moreLabStatusTriedBeforeShort(), ""};
    }
}
