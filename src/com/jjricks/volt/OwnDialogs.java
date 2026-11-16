package com.jjricks.volt;

import android.app.Dialog;
import android.view.Window;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;

/**
 * Volt's own dialogs and sheets that are on screen now (#392 early review): leaving Volt while one is open keeps the
 * page under it (HubNav.awayClockStarts), so an editor's unsaved text survives a short trip away. Motion.present and
 * MainActivity's dialog styling add each dialog they handle; a dialog missing here only means its page follows the
 * one-minute rule as any other page does. Used from the main thread only.
 */
final class OwnDialogs {
    private static final ArrayList<WeakReference<Dialog>> DIALOGS = new ArrayList<>();
    /** Sheets drawn in Volt's own window (DragSheet, the dock's All sheet): no Dialog, but a sheet over the page all the same. */
    private static final ArrayList<WeakReference<DragSheet>> SHEETS = new ArrayList<>();

    private OwnDialogs() {}

    static void track(Dialog dialog) {
        if (dialog == null) return;
        for (Iterator<WeakReference<Dialog>> it = DIALOGS.iterator(); it.hasNext(); ) {
            Dialog known = it.next().get();
            if (known == dialog) return;
            if (known == null || !known.isShowing()) it.remove();
        }
        DIALOGS.add(new WeakReference<>(dialog));
    }

    static void track(DragSheet sheet) {
        if (sheet == null) return;
        for (Iterator<WeakReference<DragSheet>> it = SHEETS.iterator(); it.hasNext(); ) {
            DragSheet known = it.next().get();
            if (known == sheet) return;
            if (known == null || !known.showing()) it.remove();
        }
        SHEETS.add(new WeakReference<>(sheet));
    }

    /** Whether one of them is showing: a dialog left behind by a destroyed window no longer counts. */
    static boolean showing() {
        boolean any = false;
        for (Iterator<WeakReference<Dialog>> it = DIALOGS.iterator(); it.hasNext(); ) {
            Dialog dialog = it.next().get();
            if (dialog == null) { it.remove(); continue; }
            Window window = dialog.isShowing() ? dialog.getWindow() : null;
            if (window != null && window.getDecorView().isAttachedToWindow()) any = true;
        }
        for (Iterator<WeakReference<DragSheet>> it = SHEETS.iterator(); it.hasNext(); ) {
            DragSheet sheet = it.next().get();
            if (sheet == null) { it.remove(); continue; }
            if (sheet.showing() && sheet.view().isAttachedToWindow()) any = true;
        }
        return any;
    }
}
