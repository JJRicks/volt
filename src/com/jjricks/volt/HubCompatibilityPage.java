package com.jjricks.volt;

import android.view.View;
import android.widget.LinearLayout;

/**
 * Service › Compatibility test (hub layout plan, PR 4): the compatibility check that Developer options and the
 * Experimental controls' Review open, as a page of its own. recoveryOnly shows only the unfinished test's review, as
 * the Scooter page's Review pill and a Lab try's Review did inside More. No text of its own: CompatibilityPanel has it.
 */
final class HubCompatibilityPage implements AppScreen {
    private final LinearLayout view;
    private final CompatibilityPanel panel;

    HubCompatibilityPage(ScreenHost host, boolean recoveryOnly) {
        view = new LinearLayout(host.activity()); view.setOrientation(LinearLayout.VERTICAL);
        panel = new CompatibilityPanel(host, view, recoveryOnly);
    }

    @Override public View view() { return view; }
    @Override public void refresh() { panel.refresh(); }
    /** The check runs tests and restores settings: riding mode never opens over it by itself (as over More). */
    @Override public boolean busy() { return true; }
}
