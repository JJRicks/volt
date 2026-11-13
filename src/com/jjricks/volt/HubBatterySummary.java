package com.jjricks.volt;

import android.os.Build;
import android.text.Layout;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;
import java.util.Map;

/**
 * The summary at the top of Battery & charging (the owner's Layout Plan): the battery level large with the range left
 * under it; beside them the reported health, the pack temperature and whether it is charging and to which limit; and
 * under both the charge track with the charge-limit tick (ChargeTrack, the track Clean's charging view draws). Readings come from ScooterService.dashboardReadings(), so the range is
 * the learned one where the owner chose it, as on Home. A level that isn't known hides the card. Words come from
 * HubText, the range line and the plain "Charging" (no known limit) from DashboardScreen and BatteryOverviewSpeech, as
 * Home's battery card says them, and the health and temperature names from ScooterReadingLabels; a last-seen level is
 * spoken in that card's "…, last seen" forms (DashboardScreen.overviewSpoken); numbers and units from Fmt, and a
 * refresh sets them again, so nothing rendered is kept. Health and temperature show only from live readings the model
 * reports (HubSummaries.health, packTemperature), and charging only beside a live level (HubSummaries.card).
 */
final class HubBatterySummary implements AppScreen {
    private static final double KM_PER_MILE = 1.609344;
    /** The percent sign beside the large level, relative to the number (as Home's battery card draws it). */
    private static final float SIGN = .44f;

    private final ScreenHost host;
    private final Ui ui;
    private final LinearLayout view, card, side, healthRow, packRow;
    private final TextView level, rangeLine, state, healthName, healthValue, packName, packValue;
    private final ChargeTrack track;

    HubBatterySummary(ScreenHost host) {
        this.host = host; ui = host.ui();
        view = new LinearLayout(host.activity()); view.setOrientation(LinearLayout.VERTICAL);
        card = ui.card(); card.setTag("hub-battery-summary");
        LinearLayout figures = new SideOrStack();
        LinearLayout main = new LinearLayout(host.activity()); main.setOrientation(LinearLayout.VERTICAL);
        level = ui.text("", 48, true); level.setMaxLines(1); main.addView(level, new LinearLayout.LayoutParams(-2, -2));
        rangeLine = ui.text("", 14, false); rangeLine.setTextColor(ui.muted); rangeLine.setPadding(0, ui.dp(6), 0, 0);
        main.addView(rangeLine, new LinearLayout.LayoutParams(-1, -2));
        figures.addView(main);
        side = new LinearLayout(host.activity()); side.setOrientation(LinearLayout.VERTICAL);
        healthName = ui.text("", 14, false); healthValue = ui.text("", 14, true);
        healthRow = fact(healthName, healthValue); side.addView(healthRow);
        packName = ui.text("", 14, false); packValue = ui.text("", 14, true);
        packRow = fact(packName, packValue); side.addView(packRow);
        state = ui.text("", 14, false); state.setTextColor(ui.muted); state.setPadding(0, ui.dp(2), 0, ui.dp(2));
        side.addView(state, new LinearLayout.LayoutParams(-2, -2));
        figures.addView(side);
        card.addView(figures, new LinearLayout.LayoutParams(-1, -2));
        track = new ChargeTrack(host.activity(), ui);
        LinearLayout.LayoutParams trackParams = new LinearLayout.LayoutParams(-1, ChargeTrack.height(ui)); trackParams.topMargin = ui.dp(14);
        card.addView(track, trackParams);
        view.addView(card);
        refresh();
    }

    /** A line of the right column: a reading's name, muted, then its value; spoken as one item. */
    private LinearLayout fact(TextView name, TextView value) {
        LinearLayout row = new LinearLayout(host.activity()); row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(0, ui.dp(2), 0, ui.dp(2));
        name.setTextColor(ui.muted); name.setMaxLines(1);
        row.addView(name, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(-2, -2); valueParams.leftMargin = ui.dp(6);
        value.setMaxLines(1); row.addView(value, valueParams);
        if (Build.VERSION.SDK_INT >= 28) row.setScreenReaderFocusable(true);
        return row;
    }

    @Override public View view() { return view; }

    @Override public void refresh() {
        ScooterService service = host.service();
        Map<String, TelemetryHistory.Reading> readings = service == null ? null : service.dashboardReadings();
        TelemetryHistory.Reading soc = readings == null ? null : readings.get("battery_percent");
        if (soc == null || soc.number == null || !Double.isFinite(soc.number)) { card.setVisibility(View.GONE); return; }
        card.setVisibility(View.VISIBLE);
        double clamped = Math.max(0, Math.min(100, soc.number));
        CharSequence big = large(Fmt.percent(Fmt.integerNumber(Math.round(soc.number))));
        if (!big.toString().contentEquals(level.getText())) level.setText(big);
        level.setTextColor(soc.live ? ui.text : ui.muted);
        CharSequence range = range(readings.get("remaining_range_km"), service.settings.getString("units", "mi"));
        // "about 40 mi left", the range line Home's battery card draws.
        rangeLine.setText(range == null ? "" : DashboardScreen.rangeLeft(DashboardScreen.aboutRange(range)));
        rangeLine.setVisibility(range == null ? View.GONE : View.VISIBLE);
        // A last-seen level is drawn muted; while connected nothing else on the page says it is old, so it is spoken in
        // Home's battery card's words: "Battery 62 percent, about 40 mi of range left, last seen 14:05". The range goes in
        // as Home's card passes it, an estimate wrapped in "about" (DashboardScreen.aboutRange), as the drawn line says it.
        // That sentence holds the range, so the range line is then not read again.
        level.setContentDescription(soc.live ? null : DashboardScreen.overviewSpoken(clamped, Long.toString(Math.round(clamped)),
                range == null ? "" : BatteryOverviewSpeech.line(false, DashboardScreen.aboutRange(range)),
                DisplayTime.recent(host.activity(), service.settings, soc.capturedWallMs, System.currentTimeMillis())).render());
        rangeLine.setImportantForAccessibility(soc.live ? View.IMPORTANT_FOR_ACCESSIBILITY_AUTO : View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        // Read live as Home's battery card speaks it, "about 40 mi of range left": some languages draw the value alone.
        rangeLine.setContentDescription(soc.live && range != null ? BatteryOverviewSpeech.line(false, DashboardScreen.aboutRange(range)) : null);
        // The right column: health and pack temperature where the model reports them live.
        TelemetryHistory.Reading healthReading = readings.get("battery_health_percent");
        Integer health = healthReading == null ? null : HubSummaries.health(healthReading.number, healthReading.live);
        healthRow.setVisibility(health == null ? View.GONE : View.VISIBLE);
        if (health != null) {
            healthName.setText(ScooterReadingLabels.compact(healthReading.key, ""));
            healthValue.setText(Fmt.percent(Fmt.integerNumber(health)));
            // Drawn by its compact name; spoken by its full one with the value, as one item.
            healthRow.setContentDescription(DashboardScreen.readingSpoken(ScooterReadingLabels.full(healthReading.key, ""), healthValue.getText()));
        }
        TelemetryHistory.Reading packReading = readings.get("temperature_c");
        Double pack = packReading == null ? null : HubSummaries.packTemperature(packReading.number, packReading.live);
        Formatted packShown = pack == null ? null : DisplayTemperature.quantity(pack, service.settings.getString("temperature_unit", "c"), 0);
        packRow.setVisibility(packShown == null ? View.GONE : View.VISIBLE);
        if (packShown != null) {
            packName.setText(ScooterReadingLabels.compact(packReading.key, ""));
            packValue.setText(packShown);
            packRow.setContentDescription(DashboardScreen.readingSpoken(ScooterReadingLabels.full(packReading.key, ""), packValue.getText()));
        }
        // Charging or not only from a live reading (as the Scooter tab's Battery summary), and only beside a live level:
        // a last-seen or missing state, or a last-seen level above it, hides the line (HubSummaries.card).
        TelemetryHistory.Reading charge = readings.get("charging_state");
        Boolean charging = charge == null || !charge.live || charge.number == null ? null : charge.number == 1;
        Integer limit = HubSubjects.chargeLimit(service);
        HubSummaries.CardState shown = HubSummaries.card(charging, HubSummaries.live(soc.number, soc.live) != null, limit);
        state.setVisibility(shown == null ? View.GONE : View.VISIBLE);
        // Charging without a known limit: a plain "Charging", the word Home's battery card uses (the level is beside it).
        if (shown != null) state.setText(shown == HubSummaries.CardState.NOT_CHARGING ? HubText.batteryNotCharging()
                : shown == HubSummaries.CardState.CHARGING_TO_LIMIT ? HubText.batteryChargingTo(Fmt.percent(Fmt.integerNumber(limit)))
                : BatteryOverviewSpeech.line(true, ""));
        side.setVisibility(health == null && packShown == null && shown == null ? View.GONE : View.VISIBLE);
        // The track: filled to the level (grey when last seen, as the number is), with the tick at a known charge limit.
        track.set(clamped, limit == null ? Double.NaN : limit, soc.live ? ui.accent : ui.dim, ui.text);
    }

    /** The level as Fmt writes it, with everything but the number (the percent sign) drawn smaller. */
    private static CharSequence large(Formatted percent) {
        SpannableString shown = new SpannableString(percent.toString());
        if (percent.numbers() == 0) return shown;
        int start = percent.numberStart(0), end = percent.numberEnd(0);
        if (start > 0) shown.setSpan(new RelativeSizeSpan(SIGN), 0, start, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (end < shown.length()) shown.setSpan(new RelativeSizeSpan(SIGN), end, shown.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return shown;
    }

    /**
     * The level and range beside the right column, or the column under them when both don't fit side by side (a narrow
     * screen at large text): the large level is never shrunk or cut.
     */
    private final class SideOrStack extends LinearLayout {
        /** Null until the first measure. */
        private Boolean stacked;

        SideOrStack() { super(host.activity()); setBaselineAligned(false); }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            View column = getChildAt(1);
            boolean stack = false;
            if (column.getVisibility() != GONE && MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED) {
                int room = MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
                column.measure(MeasureSpec.makeMeasureSpec(Math.max(0, room), MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                float need = Layout.getDesiredWidth(level.getText(), level.getPaint()) + level.getPaddingLeft() + level.getPaddingRight();
                stack = room - column.getMeasuredWidth() - ui.dp(12) < need;
            }
            arrange(stack);
            super.onMeasure(widthSpec, heightSpec);
        }

        private void arrange(boolean stack) {
            if (stacked != null && stacked == stack) return;
            stacked = stack;
            LayoutParams main = (LayoutParams) getChildAt(0).getLayoutParams(), column = (LayoutParams) getChildAt(1).getLayoutParams();
            main.width = stack ? LayoutParams.MATCH_PARENT : 0; main.weight = stack ? 0 : 1;
            column.width = LayoutParams.WRAP_CONTENT; column.gravity = Gravity.TOP;
            column.leftMargin = stack ? 0 : ui.dp(12); column.topMargin = stack ? ui.dp(10) : 0;
            setOrientation(stack ? VERTICAL : HORIZONTAL);
        }
    }

    /** The range left in the rider's units ("mi", "km" or "dual"), or null when there is none. */
    private static CharSequence range(TelemetryHistory.Reading range, String units) {
        if (range == null || range.number == null || !Double.isFinite(range.number) || range.number < 0) return null;
        Formatted km = Fmt.quantity(Fmt.decimalNumber(Locale.US, range.number, 0), Fmt.unit(UnitCode.KM));
        Formatted miles = Fmt.quantity(Fmt.decimalNumber(Locale.US, range.number / KM_PER_MILE, 0), Fmt.unit(UnitCode.MI));
        if ("km".equals(units)) return km;
        return "dual".equals(units) ? Fmt.dual(miles, km) : miles;
    }
}
