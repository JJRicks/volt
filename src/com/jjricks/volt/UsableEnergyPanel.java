package com.jjricks.volt;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * "Usable ride energy" under an Experimental tag, below the rows of Battery & charging › Estimates & charger
 * (hub layout plan, deferred from part 4 to part 8): Clean Home has no widgets, so its owners find the estimate here.
 * The card is the widget dashboard's own capacity widget (DashboardScreen.EnergyPanel), with its text; the label
 * comes from HubText, so this file holds no catalog references.
 */
final class UsableEnergyPanel implements AppScreen {
    /** View tag of the Experimental label above the card (the card is tagged battery-usable-energy). */
    static final String TAG_LABEL = "battery-usable-energy-label";

    private final ScreenHost host;
    private final DashboardScreen.EnergyPanel panel;
    private final LinearLayout view;
    private boolean closed;

    UsableEnergyPanel(ScreenHost host, DashboardScreen.Actions actions) {
        this.host = host;
        Ui ui = host.ui();
        panel = DashboardScreen.energyPanel(host, actions);
        view = new LinearLayout(host.activity());
        view.setOrientation(LinearLayout.VERTICAL);
        // The Layout Plan's EXPERIMENTAL tag, as on the other Experimental rows, above the card it marks.
        TextView label = ui.sectionLabel(HubText.experimentalBadge().render());
        ui.experimentalTag(label);
        label.setTag(TAG_LABEL);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(-2, -2);
        labelParams.setMarginStart(ui.dp(4)); labelParams.topMargin = ui.dp(14); labelParams.bottomMargin = ui.dp(8);
        view.addView(label, labelParams);
        LinearLayout.LayoutParams card = new LinearLayout.LayoutParams(-1, -2);
        card.bottomMargin = ui.dp(16);
        view.addView(panel.view(), card);
        refresh();
    }

    @Override public View view() { return view; }

    @Override public void refresh() {
        if (closed) return;
        ScooterService service = host.service();
        ScooterProfiles.Profile current = service == null ? null : service.profiles.active();
        // Another scooter: the page rebuilds, so the estimate is that scooter's.
        if (!panel.profileId().equals(current == null ? "unconfigured" : current.id)) { host.rebuild(); return; }
        panel.refresh();
    }

    @Override public void close() {
        closed = true;
        panel.close();
    }
}
