package com.jjricks.volt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The lifetime of a one-time question that several views of one screen can ask at the same moment: the offer
 * to leave USGS (MapPreferences.offerOsmSwitch), which a ride's inline map and its expanded map both ask when
 * the basemap turns on. Pure Java (no Android), so tests/l10n/OneQuestionTest runs exactly what the app runs.
 *
 * - At most one question is open per window (an activity). A second asker joins the open one.
 * - When every view that asked has left its window (the ride screen closed, the activity navigated elsewhere
 *   or recreated), the question is closed quietly: nothing is recorded, so it is asked again next time.
 * - Only the first answer counts. It settles every open copy, in every window; those are closed quietly, and
 *   nothing a settled or closed question does afterwards (a Back, a dismissal) is recorded.
 *
 * Windows, askers and questions are held weakly, so a window that went away without closing its question
 * never keeps it alive.
 */
final class OneQuestion<Q> {
    private static final class Open<Q> {
        final Q question;
        final Set<Object> askers = Collections.newSetFromMap(new WeakHashMap<>());
        Open(Q question) { this.question = question; }
    }

    private final Map<Object, Open<Q>> byWindow = new WeakHashMap<>();
    /** Questions that are open and unanswered: only these can record an answer. */
    private final Set<Q> unsettled = Collections.newSetFromMap(new WeakHashMap<>());

    /** The question open in this window, or null. */
    Q current(Object window) {
        Open<Q> open = byWindow.get(window);
        return open == null ? null : open.question;
    }

    /**
     * An asker in window wants the question. True when one is already open there: the asker joins it and the
     * caller shows nothing. False when the caller may build one, after checking it still applies, and report it
     * with opened().
     */
    boolean join(Object window, Object asker) {
        Open<Q> open = byWindow.get(window);
        if (open == null) return false;
        open.askers.add(asker);
        return true;
    }

    /** The caller showed question in window for asker. A question still open there is replaced (and returned to close quietly). */
    Q opened(Object window, Object asker, Q question) {
        Open<Q> previous = byWindow.get(window), open = new Open<>(question);
        open.askers.add(asker);
        byWindow.put(window, open);
        unsettled.add(question);
        if (previous == null || previous.question == question) return null;
        unsettled.remove(previous.question);
        return previous.question;
    }

    /**
     * The asker left its window. Returns the question to close quietly when no asker of it is left, else null.
     * Closing quietly settles it first, so a late Back or dismissal records nothing.
     */
    Q left(Object asker) {
        for (Open<Q> open : new ArrayList<>(byWindow.values())) {
            if (!open.askers.remove(asker) || !open.askers.isEmpty()) continue;
            unsettled.remove(open.question);
            return open.question;
        }
        return null;
    }

    /**
     * An answer given on question: a button, or Back on the question itself. True when this answer is the one to
     * record: the question is open and unanswered. Every open question then settles, and the others are added to
     * closeQuietly; a false return records nothing and changes nothing.
     */
    boolean answer(Q question, List<Q> closeQuietly) {
        if (!unsettled.remove(question)) return false;
        for (Open<Q> open : byWindow.values()) if (open.question != question && unsettled.remove(open.question)) closeQuietly.add(open.question);
        return true;
    }

    /** question is gone, for any reason. It records nothing from now on and frees its window. */
    void closed(Q question) {
        unsettled.remove(question);
        byWindow.values().removeIf(open -> open.question == question);
    }

    /** Open questions, answered or not, across windows. */
    int openCount() { return byWindow.size(); }
}
