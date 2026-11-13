package com.jjricks.volt;

import java.util.*;

/**
 * The lifetime of the one-time offer to leave USGS (OneQuestion, as MapPreferences.offerOsmSwitch drives it): a
 * ride's inline and expanded maps both asking, an answer then a dismissal, a dismissal then an answer, the ride
 * screen closing, the activity being recreated. Each shows at most one question per window, and a saved answer is
 * never overwritten. The saved answer follows MapPreferences.answerUsgsSwitch: written only when OneQuestion says
 * this answer counts, and never over an answer already saved.
 */
public final class OneQuestionTest {
    private static int checks;

    /** One question's dialog, as the app's AlertDialog: showing until closed. */
    private static final class Dialog { boolean showing = true; final String name; Dialog(String name) { this.name = name; } public String toString() { return name; } }

    /** The app side: OneQuestion plus the saved answer (app_settings), as MapPreferences wires them. */
    private static final class App {
        final OneQuestion<Dialog> questions = new OneQuestion<>();
        Boolean saved; // null: unanswered; true: Switch; false: Keep USGS
        int shown;

        /** offerOsmSwitch's posted check, for asker (a map) in window. Returns the dialog it showed, or null. */
        Dialog ask(Object window, Object asker) {
            Dialog open = questions.current(window);
            if (open != null && !open.showing) { questions.closed(open); open = null; }
            if (open != null) { check(questions.join(window, asker), "joins the open question"); return null; }
            if (saved != null) return null; // MapDefault.asksToLeaveUsgs: answered, no offer
            Dialog dialog = new Dialog("question " + (++shown));
            close(questions.opened(window, asker, dialog));
            return dialog;
        }
        /** A button, or Back on the question itself (cancel). */
        void answer(Dialog dialog, boolean switchToOsm) {
            List<Dialog> others = new ArrayList<>();
            if (questions.answer(dialog, others) && saved == null) saved = switchToOsm;
            for (Dialog other : others) close(other);
            closed(dialog); // the dialog dismisses itself after a button or a cancel
        }
        /** closeSwitchQuestion: dismiss, never cancel. */
        void close(Dialog dialog) { if (dialog == null) return; questions.closed(dialog); dialog.showing = false; }
        /** The dismiss listener. */
        void closed(Dialog dialog) { dialog.showing = false; questions.closed(dialog); }
        /** An asking map left its window. */
        void left(Object asker) { close(questions.left(asker)); }
        int showing(Collection<Dialog> dialogs) { int n = 0; for (Dialog d : dialogs) if (d.showing) n++; return n; }
    }

    public static void main(String[] args) {
        twoMaps();
        answerThenDismissal();
        dismissalThenAnswer();
        screenClosed();
        activityRecreated();
        twoWindows();
        System.out.println("OneQuestionTest: " + checks + " checks");
    }

    /** Finding 1: the expanded map and the inline map both hear the basemap turn on; one question, and Switch sticks. */
    private static void twoMaps() {
        App app = new App(); Object activity = new Object(), inline = new Object(), expanded = new Object();
        Dialog first = app.ask(activity, expanded), second = app.ask(activity, inline);
        check(first != null && second == null && app.questions.openCount() == 1, "two maps asking at once show one question");
        app.answer(first, true);
        check(Boolean.TRUE.equals(app.saved) && !first.showing && app.questions.openCount() == 0, "Switch is saved and the question closes");
        app.answer(first, false); // a late Back on the same dialog
        check(Boolean.TRUE.equals(app.saved), "a later Back never overwrites Switch");
        check(app.ask(activity, inline) == null && app.ask(activity, expanded) == null, "answered: never asked again");
    }

    /** An answer, then the screen closes and every map leaves: nothing more is recorded. */
    private static void answerThenDismissal() {
        App app = new App(); Object activity = new Object(), inline = new Object(), expanded = new Object();
        Dialog question = app.ask(activity, inline); app.ask(activity, expanded);
        app.answer(question, false);
        app.left(expanded); app.left(inline); app.answer(question, true);
        check(Boolean.FALSE.equals(app.saved), "Keep USGS stays through a later dismissal and a stray Switch");
    }

    /** The screen closes first (nothing recorded, asked again next time), then the person answers the new question. */
    private static void dismissalThenAnswer() {
        App app = new App(); Object activity = new Object(), inline = new Object();
        Dialog question = app.ask(activity, inline);
        app.left(inline);
        check(!question.showing && app.saved == null && app.questions.openCount() == 0, "closing with its screen records nothing");
        app.answer(question, false); // a Back that arrives after the quiet close
        check(app.saved == null, "a Back after the quiet close records nothing");
        Object again = new Object(); Dialog next = app.ask(activity, again);
        check(next != null && next != question, "asked again the next time it applies");
        app.answer(next, true);
        check(Boolean.TRUE.equals(app.saved), "the new question's answer is saved");
    }

    /** Finding 2: the ride screen closes from outside (a maintenance notice opens Maintenance) with the question open. */
    private static void screenClosed() {
        App app = new App(); Object activity = new Object(), inline = new Object(), expanded = new Object();
        Dialog question = app.ask(activity, inline); app.ask(activity, expanded);
        app.left(expanded);
        check(question.showing, "one map leaving (the expanded map closing) keeps the question while another asking map stays");
        app.left(inline);
        check(!question.showing && app.saved == null, "the last asking map leaving closes the question without an answer");
        app.answer(question, false);
        check(app.saved == null, "Back on the next page answers nothing: the question is gone");
    }

    /** The activity is recreated: the old window's maps leave, the new window's map asks again. */
    private static void activityRecreated() {
        App app = new App(); Object old = new Object(), oldMap = new Object();
        Dialog question = app.ask(old, oldMap);
        app.left(oldMap);
        Object fresh = new Object(), freshMap = new Object();
        Dialog again = app.ask(fresh, freshMap);
        check(!question.showing && again != null && again.showing && app.questions.openCount() == 1 && app.saved == null,
                "recreated: the old question closed unanswered, one new question in the new window");
        app.answer(again, false);
        check(Boolean.FALSE.equals(app.saved), "Keep USGS in the new window is saved");
    }

    /** Two windows at once (a recreation that left the old question up): one answer settles and closes both. */
    private static void twoWindows() {
        App app = new App(); Object a = new Object(), b = new Object();
        Dialog first = app.ask(a, new Object()), second = app.ask(b, new Object());
        check(first != null && second != null && app.questions.openCount() == 2, "one question per window");
        app.answer(second, true);
        check(!first.showing && !second.showing && Boolean.TRUE.equals(app.saved), "an answer closes every copy");
        app.answer(first, false);
        check(Boolean.TRUE.equals(app.saved), "a Back on the other window's copy never overwrites the answer");
        Dialog stale = new Dialog("stale"); app.questions.opened(a, new Object(), stale); stale.showing = false;
        check(app.ask(a, new Object()) == null && app.questions.current(a) == null, "a question no longer showing is forgotten, and an answered person is not asked");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
