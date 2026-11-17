package com.jjricks.volt;
import android.view.View;
/** A screen can refresh values without rebuilding its inputs or scroll position. */
public interface AppScreen {
    View view(); void refresh(); default void close() {}
    /** System Back inside the screen (end an editor, clear a search, leave an in-page sub-page); false lets the host go back a page. */
    default boolean back() { return false; }
    /** Holding something riding mode must not open over by itself: an editor, a form, a setup flow or an in-page sub-page. */
    default boolean busy() { return false; }
    /**
     * The view search asks this page to bring on screen (hub layout plan 1.12): a row by its id (a feature key or a
     * hand-built row's id) or its view tag. Null lets the host look the tag up in the page itself.
     */
    default View anchor(String id) { return null; }
    /**
     * After search brought anchor(id)'s row on screen: opens the sheet that holds what the id names, when a sheet over
     * the page holds it rather than the page (Settings' Updates sheet holds Automatic update checks and Check for
     * updates), and brings it on screen there; true when it did (the host then leaves the row on the page unflashed).
     * Back closes the sheet first, then leaves the page, as from any other result.
     */
    default boolean openHeld(String id) { return false; }
}
