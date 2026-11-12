package com.jjricks.volt;

import android.view.View;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A Hub page built from several screens stacked top to bottom (hub layout plan): the search page is its search
 * and the subject list. Refresh, close, Back and busy reach every part; Back goes to the first part that takes it.
 * A part must not be a ScrollView (MainActivity wraps the whole page in one).
 */
final class HubPageScreen implements AppScreen {
    private final LinearLayout view;
    private final List<AppScreen> parts;

    HubPageScreen(ScreenHost host, AppScreen... parts) {
        this.parts = new ArrayList<>(Arrays.asList(parts));
        view = new LinearLayout(host.activity()); view.setOrientation(LinearLayout.VERTICAL);
        for (AppScreen part : this.parts) view.addView(part.view(), new LinearLayout.LayoutParams(-1, -2));
    }

    @Override public View view() { return view; }
    @Override public void refresh() { for (AppScreen part : parts) part.refresh(); }
    @Override public void close() { for (AppScreen part : parts) part.close(); }
    @Override public boolean back() { for (AppScreen part : parts) if (part.back()) return true; return false; }
    @Override public boolean busy() { for (AppScreen part : parts) if (part.busy()) return true; return false; }
    @Override public View anchor(String id) {
        for (AppScreen part : parts) { View found = part.anchor(id); if (found != null) return found; }
        return null;
    }
}
