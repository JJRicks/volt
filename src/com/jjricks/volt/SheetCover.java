package com.jjricks.volt;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * What sheets drawn in Volt's own window (DragSheet) do to the rest of that window, nested. A dialog's own window hid
 * the page from TalkBack and kept key focus off it for free; a sheet in the same window does it by hand, and more than one
 * can be attached at once (a let-go sheet still sliding away under a new one). So one record per window, not per sheet:
 *
 * <ul>
 *   <li>Each covered view's true settings (its accessibility importance and its descendants' focusability) are saved by
 *   the first sheet that covers it and written back only when the last sheet has gone, never a sheet's view of what an
 *   older sheet had already changed.</li>
 *   <li>Hidden from TalkBack while any sheet is attached (a closing one still covers the page).</li>
 *   <li>Focus blocked while any sheet wants it (shown and not settling closed: wantsFocus). One closing sheet giving up
 *   focus does not unblock a page another sheet still holds.</li>
 *   <li>The light theme's status bar icons are white while any sheet asks for white (its dim behind them is past the
 *   contrast crossover, SheetSpring.whiteIcons); the dims stack, so one asking is enough. They turn back when none does,
 *   or the last sheet goes.</li>
 * </ul>
 *
 * Plain Java (V is the view type, Views reads and writes it) so tests/SheetCoverTest.java runs it off a device.
 */
final class SheetCover<V> {
    /** How the views are read and written. focus returns -1 for a view that holds no others (not a group). */
    interface Views<V> {
        int importance(V view);
        void importance(V view, int value);
        int focus(V view);
        void focus(V view, int value);
    }

    private final Views<V> views;
    private final int hidden, blocked;
    /** Each covered view: {importance, focus} as they were before the first sheet. */
    private final Map<V, int[]> originals = new IdentityHashMap<>();
    private final Set<Object> holders = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Object> focusHolders = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Object> whiteHolders = Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean white;

    /** hidden: the importance that hides a view and its children; blocked: the focusability that keeps focus out of them. */
    SheetCover(Views<V> views, int hidden, int blocked) {
        this.views = views;
        this.hidden = hidden;
        this.blocked = blocked;
    }

    /** A sheet shows over these views (the window's other children; never another sheet's overlay). It wants focus. */
    void cover(Object sheet, Iterable<V> covered) {
        holders.add(sheet);
        focusHolders.add(sheet);
        for (V view : covered) {
            if (originals.containsKey(view)) continue;
            originals.put(view, new int[]{views.importance(view), views.focus(view)});
        }
        apply();
    }

    /** A sheet takes focus (shown, or caught again while closing) or gives it up (settling closed). */
    void wantsFocus(Object sheet, boolean wants) {
        if (!holders.contains(sheet)) return;
        if (wants) focusHolders.add(sheet); else focusHolders.remove(sheet);
        apply();
    }

    /**
     * A sheet has gone. The last one writes every view back as it was before the first. Returns the icons' new
     * whiteness if it changed (Boolean.FALSE when the last white one went), else null.
     */
    Boolean uncover(Object sheet) {
        if (!holders.remove(sheet)) return null;
        focusHolders.remove(sheet);
        Boolean icons = whiten(sheet, false);
        if (holders.isEmpty()) {
            for (Map.Entry<V, int[]> each : originals.entrySet()) {
                views.importance(each.getKey(), each.getValue()[0]);
                if (each.getValue()[1] >= 0) views.focus(each.getKey(), each.getValue()[1]);
            }
            originals.clear();
        } else {
            apply();
        }
        return icons;
    }

    /** A sheet asks for white status bar icons or not. Returns the window's new whiteness if it changed, else null. */
    Boolean icons(Object sheet, boolean wantsWhite) {
        if (!holders.contains(sheet)) return null;
        return whiten(sheet, wantsWhite);
    }

    /** Some sheet keeps the page's focus blocked. */
    boolean blocking() { return !focusHolders.isEmpty(); }

    /** Some sheet is attached. */
    boolean covering() { return !holders.isEmpty(); }

    /** The views covered now (for tests). */
    int coveredCount() { return originals.size(); }

    private Boolean whiten(Object sheet, boolean wantsWhite) {
        if (wantsWhite) whiteHolders.add(sheet); else whiteHolders.remove(sheet);
        boolean now = !whiteHolders.isEmpty();
        if (now == white) return null;
        white = now;
        return now;
    }

    private void apply() {
        boolean block = !focusHolders.isEmpty();
        for (Map.Entry<V, int[]> each : originals.entrySet()) {
            if (holders.isEmpty()) break;
            views.importance(each.getKey(), hidden);
            if (each.getValue()[1] >= 0) views.focus(each.getKey(), block ? blocked : each.getValue()[1]);
        }
    }
}
