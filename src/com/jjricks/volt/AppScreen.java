package com.jjricks.volt;
import android.view.View;
/** A screen can refresh values without rebuilding its inputs or scroll position. */
public interface AppScreen {
    View view(); void refresh(); default void close() {}
    /** System Back inside the screen (end an editor, clear a search, leave an in-page sub-page); false lets the host go back a page. */
    default boolean back() { return false; }
    /** Holding something riding mode must not open over by itself: an editor, a form, a setup flow or an in-page sub-page. */
    default boolean busy() { return false; }
}
