package com.jjricks.volt;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;
import java.util.Map;

/**
 * The summary at the top of Battery & charging (hub layout plan, PR 4): the battery level with the range left, then
 * whether it is charging and to which limit. Readings come from ScooterService.dashboardReadings(), so the range is
 * the learned one where the owner chose it, as on Home. A level that isn't known hides the card. All words come from
 * HubText; numbers and units from Fmt, and a refresh sets them again, so nothing rendered is kept.
 */
final class HubBatterySummary implements AppScreen {
    private static final double KM_PER_MILE = 1.609344;

    private final ScreenHost host;
    private final Ui ui;
    private final LinearLayout view, card;
    private final TextView level, state;

    HubBatterySummary(ScreenHost host) {
        this.host = host; ui = host.ui();
        view = new LinearLayout(host.activity()); view.setOrientation(LinearLayout.VERTICAL);
        card = ui.card(); card.setTag("hub-battery-summary");
        level = ui.text("", 22, true); card.addView(level, new LinearLayout.LayoutParams(-1, -2));
        state = ui.caption(""); state.setPadding(0, ui.dp(6), 0, 0); card.addView(state, new LinearLayout.LayoutParams(-1, -2));
        view.addView(card);
        refresh();
    }

    @Override public View view() { return view; }

    @Override public void refresh() {
        ScooterService service = host.service();
        Map<String, TelemetryHistory.Reading> readings = service == null ? null : service.dashboardReadings();
        TelemetryHistory.Reading soc = readings == null ? null : readings.get("battery_percent");
        if (soc == null || soc.number == null || !Double.isFinite(soc.number)) { card.setVisibility(View.GONE); return; }
        card.setVisibility(View.VISIBLE);
        Formatted percent = Fmt.percent(Fmt.integerNumber(Math.round(soc.number)));
        CharSequence range = range(readings.get("remaining_range_km"), service.settings.getString("units", "mi"));
        level.setText(range == null ? percent : HubText.batteryLevelRange(percent, range));
        level.setTextColor(soc.live ? ui.text : ui.muted);
        TelemetryHistory.Reading charge = readings.get("charging_state");
        boolean charging = charge != null && charge.live && charge.number != null && charge.number == 1;
        Integer limit = limit(service);
        state.setText(!charging ? HubText.batteryNotCharging()
                : limit != null ? HubText.batteryChargingTo(Fmt.percent(Fmt.integerNumber(limit)))
                : HubText.batteryLevel(percent, true));
    }

    /** The range left in the rider's units ("mi", "km" or "dual"), or null when there is none. */
    private static CharSequence range(TelemetryHistory.Reading range, String units) {
        if (range == null || range.number == null || !Double.isFinite(range.number) || range.number < 0) return null;
        Formatted km = Fmt.quantity(Fmt.decimalNumber(Locale.US, range.number, 0), Fmt.unit(UnitCode.KM));
        Formatted miles = Fmt.quantity(Fmt.decimalNumber(Locale.US, range.number / KM_PER_MILE, 0), Fmt.unit(UnitCode.MI));
        if ("km".equals(units)) return km;
        return "dual".equals(units) ? Fmt.dual(miles, km) : miles;
    }

    /** The charge limit in percent where the model has one and its value is known; null otherwise. */
    private static Integer limit(ScooterService service) {
        BleController ble = service.ble;
        if (ble == null || !ble.controlOffered("charge_limit")) return null;
        Integer live = ble.featureValue("charge_limit");
        Integer value = live != null ? live : service.displayFeatures().get("charge_limit");
        return value == null || value < 0 || value > 100 ? null : value;
    }
}
