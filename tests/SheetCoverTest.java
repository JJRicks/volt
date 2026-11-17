package com.jjricks.volt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * SheetCover: sheets in Volt's own window covering the rest of it, nested. The review's case (a let-go sheet still
 * sliding away when a new one opens, then each going in either order) and seeded random sequences of cover, focus,
 * icons and uncover over three sheets, checked against a plain model after every step.
 */
public final class SheetCoverTest {
    private static int checks;
    private static final int HIDDEN = 4, BLOCKED = 0x60000, AUTO = 0, AFTER = 0x40000, BEFORE = 0x20000, NO = 2;

    private static final class FakeView {
        final String name; int importance, focus;
        FakeView(String name, int importance, int focus) { this.name = name; this.importance = importance; this.focus = focus; }
    }

    private static final SheetCover.Views<FakeView> VIEWS = new SheetCover.Views<FakeView>() {
        @Override public int importance(FakeView view) { return view.importance; }
        @Override public void importance(FakeView view, int value) { view.importance = value; }
        @Override public int focus(FakeView view) { return view.focus; }
        @Override public void focus(FakeView view, int value) {
            require(view.focus >= 0, view.name + " is not a group but its focusability was written");
            view.focus = value;
        }
    };

    public static void main(String[] args) {
        oneSheet();
        letGoThenNew(true);
        letGoThenNew(false);
        closingDoesNotUnblockOpen();
        lateView();
        icons();
        strangers();
        random();
        System.out.println("SheetCover: " + checks + " checks passed");
    }

    private static List<FakeView> page() {
        return new ArrayList<>(Arrays.asList(new FakeView("content", AUTO, AFTER), new FakeView("bar", NO, BEFORE), new FakeView("plain", AUTO, -1)));
    }

    private static SheetCover<FakeView> cover() { return new SheetCover<>(VIEWS, HIDDEN, BLOCKED); }

    private static void oneSheet() {
        List<FakeView> page = page();
        SheetCover<FakeView> cover = cover();
        Object a = "a";
        cover.cover(a, page);
        covered(page, true, "one sheet shown");
        cover.wantsFocus(a, false);
        hiddenOnly(page, "one sheet settling closed");
        cover.wantsFocus(a, true);
        covered(page, true, "one sheet caught again");
        cover.uncover(a);
        original(page, "one sheet gone");
        require(!cover.covering() && !cover.blocking() && cover.coveredCount() == 0, "one sheet gone: the record is not empty");
    }

    /** The review's case: the old sheet let go (closing, no focus), a new one opens over it, then either goes first. */
    private static void letGoThenNew(boolean oldFirst) {
        List<FakeView> page = page();
        SheetCover<FakeView> cover = cover();
        Object old = "old", now = "new";
        cover.cover(old, page);
        cover.wantsFocus(old, false);
        cover.cover(now, page);
        covered(page, true, "a new sheet over a let-go one");
        if (oldFirst) {
            cover.uncover(old);
            covered(page, true, "the let-go sheet gone under the open one");
            cover.wantsFocus(now, false);
            hiddenOnly(page, "the new sheet settling closed alone");
            cover.uncover(now);
        } else {
            cover.wantsFocus(now, false);
            cover.uncover(now);
            hiddenOnly(page, "the new sheet gone while the let-go one still slides");
            cover.uncover(old);
        }
        original(page, "both sheets gone (" + (oldFirst ? "old" : "new") + " first)");
    }

    private static void closingDoesNotUnblockOpen() {
        List<FakeView> page = page();
        SheetCover<FakeView> cover = cover();
        cover.cover("a", page);
        cover.cover("b", page);
        cover.wantsFocus("a", false);
        covered(page, true, "one closing, one open");
        require(cover.blocking(), "one closing, one open: not blocking");
        cover.wantsFocus("b", false);
        hiddenOnly(page, "both closing");
        cover.uncover("b");
        cover.uncover("a");
        original(page, "both gone");
    }

    /** A view that joins the window between two sheets is saved by the first sheet that sees it, and written back with the rest. */
    private static void lateView() {
        List<FakeView> page = page();
        SheetCover<FakeView> cover = cover();
        cover.cover("a", page);
        FakeView late = new FakeView("late", NO, BEFORE);
        List<FakeView> more = new ArrayList<>(page);
        more.add(late);
        cover.cover("b", more);
        require(late.importance == HIDDEN && late.focus == BLOCKED, "a late view is not covered");
        cover.uncover("a");
        cover.uncover("b");
        original(more, "a late view's sheets gone");
    }

    private static void icons() {
        List<FakeView> page = page();
        SheetCover<FakeView> cover = cover();
        cover.cover("a", page);
        cover.cover("b", page);
        require(Boolean.TRUE.equals(cover.icons("a", true)), "the first white sheet does not turn the icons white");
        require(cover.icons("b", true) == null, "a second white sheet changes the icons again");
        require(cover.icons("a", false) == null, "one of two white sheets turning dark turns the icons dark");
        require(Boolean.FALSE.equals(cover.uncover("b")), "the last white sheet going does not turn the icons back");
        require(cover.icons("zzz", true) == null, "a sheet that is not covering turns the icons");
        require(cover.uncover("a") == null, "a dark sheet going changes the icons");
    }

    private static void strangers() {
        List<FakeView> page = page();
        SheetCover<FakeView> cover = cover();
        require(cover.uncover("never") == null, "a sheet that never covered changes something going");
        cover.wantsFocus("never", true);
        original(page, "a sheet that never covered");
        cover.cover("a", page);
        cover.uncover("a");
        cover.uncover("a");
        original(page, "a sheet gone twice");
    }

    /** Seeded random sequences over three sheets, against a model: hidden iff any sheet is attached, blocked iff any wants focus. */
    private static void random() {
        Random random = new Random(517);
        for (int run = 0; run < 20000; run++) {
            List<FakeView> page = page();
            SheetCover<FakeView> cover = cover();
            Set<Integer> attached = new HashSet<>(), wanting = new HashSet<>(), white = new HashSet<>(), gone = new HashSet<>();
            boolean shownWhite = false;
            Object[] sheets = {"s0", "s1", "s2"};
            for (int step = 0; step < 12; step++) {
                int s = random.nextInt(3), op = random.nextInt(4);
                Boolean change = null;
                if (op == 0 && !attached.contains(s) && !gone.contains(s)) { cover.cover(sheets[s], page); attached.add(s); wanting.add(s); }
                else if (op == 1 && attached.contains(s)) { boolean w = random.nextBoolean(); cover.wantsFocus(sheets[s], w); if (w) wanting.add(s); else wanting.remove(s); }
                else if (op == 2) { boolean w = random.nextBoolean(); change = cover.icons(sheets[s], w); if (attached.contains(s)) { if (w) white.add(s); else white.remove(s); } }
                else if (op == 3 && attached.contains(s)) { change = cover.uncover(sheets[s]); attached.remove(s); wanting.remove(s); white.remove(s); gone.add(s); }
                boolean nowWhite = !white.isEmpty();
                require(change == null ? nowWhite == shownWhite : change == nowWhite && nowWhite != shownWhite, "run " + run + " step " + step + ": icons " + change + ", model " + nowWhite);
                shownWhite = nowWhite;
                if (attached.isEmpty()) original(page, "run " + run + " step " + step);
                else covered(page, !wanting.isEmpty(), "run " + run + " step " + step);
            }
            for (int s = 0; s < 3; s++) if (attached.contains(s)) cover.uncover(sheets[s]);
            original(page, "run " + run + " end");
        }
    }

    private static void covered(List<FakeView> page, boolean blocked, String what) {
        for (FakeView view : page) {
            require(view.importance == HIDDEN, what + ": " + view.name + " is not hidden from TalkBack");
            if (view.focus >= 0 || view.name.equals("plain")) {
                int want = view.name.equals("plain") ? -1 : blocked ? BLOCKED : originalFocus(view);
                require(view.focus == want, what + ": " + view.name + "'s focus is " + view.focus + ", wanted " + want);
            }
        }
    }

    private static void hiddenOnly(List<FakeView> page, String what) { covered(page, false, what); }

    private static void original(List<FakeView> page, String what) {
        for (FakeView view : page) {
            require(view.importance == originalImportance(view), what + ": " + view.name + "'s importance is " + view.importance);
            require(view.focus == originalFocus(view), what + ": " + view.name + "'s focus is " + view.focus);
        }
    }

    private static int originalImportance(FakeView view) { return view.name.equals("bar") || view.name.equals("late") ? NO : AUTO; }
    private static int originalFocus(FakeView view) {
        switch (view.name) { case "content": return AFTER; case "plain": return -1; default: return BEFORE; }
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new IllegalStateException(message);
    }
}
