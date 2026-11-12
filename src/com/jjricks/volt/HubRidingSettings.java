package com.jjricks.volt;

import android.view.View;
import android.widget.LinearLayout;

/**
 * Ride › Riding mode (hub layout plan, PR 5): riding mode's display settings as a page, the same menu riding mode's
 * tune sheet shows (MoreScreen.RidingSettingsMenu), so both edit the same keys the same way. Outside riding mode every
 * change may save, and a change only redraws. No text of its own: the menu has it.
 */
final class HubRidingSettings implements AppScreen {
    private final LinearLayout view;
    private final MoreScreen.RidingSettingsMenu menu;

    HubRidingSettings(ScreenHost host) {
        view = new LinearLayout(host.activity()); view.setOrientation(LinearLayout.VERTICAL);
        menu = new MoreScreen.RidingSettingsMenu(host, host.ui(), host.service().settings, host::redraw, () -> true);
        menu.fill(view);
    }

    @Override public View view() { return view; }
    @Override public void refresh() { }
    /** Its dialogs (time pickers, the ride area consent) close with the page, and a late answer saves nothing. */
    @Override public void close() { menu.close(); }
}
