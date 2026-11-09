package com.jjricks.volt;

/** ReorderDrag: a drag counts from where the finger is when the row lifts, past the touch slop (Codex, #258 round 2). */
public final class ReorderDragTest {
    private static int checks;
    public static void main(String[] args) {
        float slop = 24;
        // Codex's geometry: three 200 px sections at 0, 212, 424 (12 px gaps); the finger presses the second one at 250.
        // Folding the first to 60 px moves the held one up to 72 before it lifts; the finger is still at 250.
        int[] folded = {0, 72, 144}, heights = {60, 60, 60};
        ReorderDrag drag = new ReorderDrag(slop);
        drag.anchor(250);
        float dy = drag.offset(250);
        require(dy == 0, "A finger that has not moved since the fold does not move the row (" + dy + ")");
        require(ReorderDrag.target(folded, heights, 1, folded[1] + dy, folded[1] + dy + 60) == 1, "An unmoved finger keeps the slot");
        // The old anchor (the row's new top plus the offset taken on the expanded row) read the fold as a 140 px drag.
        float old = 250 - (72 + (250 - 212));
        require(old == 140 && ReorderDrag.target(folded, heights, 1, folded[1] + old, folded[1] + old + 60) == 2,
                "The reproduction: the old anchor moves the held section to slot 2");
        // Jitter within the slop, either way, never moves it.
        for (float y : new float[]{251, 247, 262, 239, 273.9f, 226.1f}) {
            dy = drag.offset(y);
            require(dy == 0 && ReorderDrag.target(folded, heights, 1, folded[1], folded[1] + 60) == 1, "Jitter at " + y + " stays put");
        }
        // A deliberate drag follows the finger from the anchor, and keeps following once it has started.
        dy = drag.offset(250 + 80);
        require(dy == 80, "Past the slop the row follows the finger (" + dy + ")");
        require(ReorderDrag.target(folded, heights, 1, folded[1] + dy, folded[1] + dy + 60) == 2, "Dragging 80 px down passes the next middle");
        require(drag.offset(250 + 5) == 5, "Once dragging, small moves follow exactly (no slop again)");
        require(drag.offset(250 - 70) == -70 && ReorderDrag.target(folded, heights, 1, folded[1] - 70, folded[1] - 10) == 0, "Dragging up passes the first middle");
        // A new lift starts over.
        drag.anchor(400);
        require(drag.offset(410) == 0 && drag.offset(380) == 0 && drag.offset(424) == 24, "A new anchor starts within the slop again");
        // No slop: follows at once. Negative slop counts as none.
        ReorderDrag exact = new ReorderDrag(-3);
        exact.anchor(100);
        require(exact.offset(101) == 1, "Zero slop follows at once");
        // Target edges: rows of different heights still trade places; a lone row stays.
        int[] tops = {0, 50, 250}, mixed = {40, 190, 40};
        require(ReorderDrag.target(tops, mixed, 0, 150, 190) == 1, "A short row passes a tall one once its bottom passes the tall row's middle");
        require(ReorderDrag.target(tops, mixed, 0, 100, 140) == 0, "Not yet past the tall row's middle");
        require(ReorderDrag.target(new int[]{0}, new int[]{60}, 0, 500, 560) == 0, "A single row has nowhere to go");
        System.out.println("ReorderDrag: " + checks + " checks passed");
    }
    private static void require(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
}
