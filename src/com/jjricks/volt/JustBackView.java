package com.jjricks.volt;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * Clean Home's "Just back" moment (hub layout plan 1.5 and 1.7, PR 9): for about an hour after a ride ends, the top
 * area shows it. A muted "Ride ended 12 minutes ago" with a round X, the distance large, the route drawn on a plain
 * dark panel (from the phone journal's own GPS fixes; never a map tile), then Ride time, Avg speed and Battery drop
 * (Battery gain for a ride that ended with more charge, as the Rides page says).
 * Tapping it opens that ride; the X closes it until the next ride.
 *
 * <p>It fills the top area's fixed height: the route panel takes what the words and tiles leave and draws nothing when
 * that is too little (large text), so nothing below the top area moves. Text from HubText and the ride formatters
 * (RideText, RidingFocus, Fmt); this file holds no catalog references.
 */
final class JustBackView extends LinearLayout {
    /** View tags: the card itself (it opens the ride), its X and the route panel. */
    static final String TAG = "home-just-back", TAG_CLOSE = "home-just-back-close", TAG_ROUTE = "home-just-back-route";
    /** Below this height the route panel is not worth drawing; the words and tiles keep the space. */
    private static final int MIN_ROUTE_DP = 40;

    private final Ui ui;
    private final TextView saved, distance;
    private final RouteView route;
    private final Cell time, speed, battery;
    private String rideId;
    private String shownKey = "";

    /** open gets the shown ride's id; close too. */
    JustBackView(ScreenHost host, java.util.function.Consumer<String> open, java.util.function.Consumer<String> close) {
        super(host.activity());
        ui = host.ui();
        setOrientation(VERTICAL);
        setTag(TAG);
        setPadding(ui.dp(16), ui.dp(6), ui.dp(8), ui.dp(14));
        setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(ui.accent, 26)), ui.shape(ui.surface, 20, 0), ui.shape(Color.WHITE, 20, 0)));
        setClickable(true);
        setFocusable(true);
        setOnClickListener(v -> { if (rideId != null) open.accept(rideId); });

        LinearLayout first = new LinearLayout(host.activity());
        first.setOrientation(HORIZONTAL);
        first.setGravity(Gravity.CENTER_VERTICAL);
        saved = ui.caption("");
        saved.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13 * ui.textScale);
        saved.setMaxLines(1);
        saved.setEllipsize(TextUtils.TruncateAt.END);
        first.addView(saved, new LinearLayout.LayoutParams(0, -2, 1));
        first.addView(closeButton(host, () -> { if (rideId != null) close.accept(rideId); }), new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        addView(first, new LinearLayout.LayoutParams(-1, -2));

        distance = ui.text("", 36, true);
        distance.setMaxLines(1);
        distance.setGravity(Gravity.CENTER_VERTICAL);
        int floor = Math.round(ui.sp(18)), top = Math.round(ui.sp(36));
        distance.setAutoSizeTextTypeUniformWithConfiguration(floor, top, 1, TypedValue.COMPLEX_UNIT_PX);
        distance.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams distanceSize = new LinearLayout.LayoutParams(-1, Math.round(ui.sp(36) * 1.25f));
        distanceSize.setMarginEnd(ui.dp(8));
        addView(distance, distanceSize);

        route = new RouteView(host);
        route.setTag(TAG_ROUTE);
        LinearLayout.LayoutParams routeSize = new LinearLayout.LayoutParams(-1, 0, 1);
        routeSize.topMargin = ui.dp(8);
        routeSize.setMarginEnd(ui.dp(8));
        addView(route, routeSize);

        LinearLayout tiles = new LinearLayout(host.activity());
        tiles.setOrientation(HORIZONTAL);
        tiles.setBaselineAligned(false);
        // The tiles read as one group of their own: the card's name says only the ride and that it opens.
        tiles.setFocusable(true);
        if (android.os.Build.VERSION.SDK_INT >= 28) tiles.setScreenReaderFocusable(true);
        time = new Cell(host, HubText.justBackRideTime());
        speed = new Cell(host, HubText.justBackAvgSpeed());
        battery = new Cell(host, HubText.justBackBattery(null));
        Cell[] cells = {time, speed, battery};
        for (int i = 0; i < cells.length; i++) {
            LinearLayout.LayoutParams cellSize = new LinearLayout.LayoutParams(0, -1, 1);
            if (i > 0) cellSize.setMarginStart(ui.dp(8));
            tiles.addView(cells[i], cellSize);
        }
        LinearLayout.LayoutParams tilesSize = new LinearLayout.LayoutParams(-1, -2);
        tilesSize.topMargin = ui.dp(10);
        tilesSize.setMarginEnd(ui.dp(8));
        addView(tiles, tilesSize);
    }

    /** The ride shown, or null before the first bind. */
    String rideId() { return rideId; }

    /**
     * Shows ride s (a finished ride) as of now, in the "units" preference. Cheap to call on every refresh: only the
     * minutes line changes between rides, and the rest is set again only when the ride or the units change.
     */
    void bind(RideStore.Summary s, String units, long now) {
        saved.setText(HubText.justBackEnded(HomeMoment.minutesAgo(s.shownEndWallMs, now)).render());
        String key = s.id + ":" + units;
        if (key.equals(shownKey)) return;
        boolean otherRide = !s.id.equals(rideId);
        shownKey = key;
        rideId = s.id;
        if (otherRide) route.setRoute(null);
        Formatted miles = s.distanceKm == null || !Double.isFinite(s.distanceKm) ? null : RideText.distance(s.distanceKm, units);
        distance.setText(miles == null ? "—" : large(miles));
        setContentDescription(HubText.justBackDescription(miles == null ? "—" : miles.toString()).render());
        time.set(s.shownElapsedMs > 0 ? RidingFocus.clockText(s.shownElapsedMs) : null);
        speed.set(s.averageSpeedKmh == null || !Double.isFinite(s.averageSpeedKmh) ? null : RideText.speed(s.averageSpeedKmh, units).toString());
        Double drop = s.batteryDropPercent;
        // A ride that gained charge (regeneration downhill) says Battery gain with the size of the gain, as Rides does.
        battery.setLabel(HubText.justBackBattery(drop == null || !Double.isFinite(drop) ? null : drop));
        battery.set(drop == null || !Double.isFinite(drop) ? null
                : Fmt.percent(Fmt.decimalNumber(Locale.US, Math.abs(drop), 0)).toString());
    }

    /** The route of ride id, from RidesScreen.readThumb; empty when the ride has no usable GPS fixes. */
    void setRoute(String id, float[] outline) {
        if (id != null && id.equals(rideId)) route.setRoute(outline);
    }

    /** The distance's numbers large, the rest (its unit, a second unit) smaller and muted. */
    private CharSequence large(Formatted value) {
        SpannableString styled = new SpannableString(value.toString());
        int from = 0;
        for (int i = 0; i <= value.numbers(); i++) {
            int to = i < value.numbers() ? value.numberStart(i) : value.length();
            if (to > from) {
                styled.setSpan(new RelativeSizeSpan(.45f), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                styled.setSpan(new ForegroundColorSpan(ui.muted), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (i < value.numbers()) from = value.numberEnd(i);
        }
        return styled;
    }

    private View closeButton(ScreenHost host, Runnable action) {
        FrameLayout target = new FrameLayout(host.activity());
        GradientDrawable disc = new GradientDrawable();
        disc.setShape(GradientDrawable.OVAL);
        disc.setColor(ui.raised);
        target.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(ui.accent, 35)), new InsetDrawable(disc, ui.dp(10)), null));
        target.addView(ui.icon("close", 14, ui.text), new FrameLayout.LayoutParams(ui.dp(14), ui.dp(14), Gravity.CENTER));
        String spoken = HubText.justBackClose().render();
        target.setContentDescription(spoken);
        target.setTooltipText(spoken);
        target.setTag(TAG_CLOSE);
        target.setClickable(true);
        target.setFocusable(true);
        target.setOnClickListener(v -> action.run());
        return target;
    }

    /** One of the three small tiles: its label above a value that fits its width. */
    private static final class Cell extends LinearLayout {
        private final TextView name, value;

        Cell(ScreenHost host, Msg label) {
            super(host.activity());
            Ui ui = host.ui();
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8));
            setBackground(ui.shape(ui.raised, 14, 0));
            name = ui.caption(label.render());
            name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.END);
            addView(name, new LinearLayout.LayoutParams(-1, -2));
            value = ui.text("—", 17, true);
            value.setMaxLines(1);
            int floor = Math.round(ui.sp(Ui.READABLE_FLOOR_SP)), top = Math.round(ui.sp(17));
            if (floor < top) value.setAutoSizeTextTypeUniformWithConfiguration(floor, top, 1, TypedValue.COMPLEX_UNIT_PX);
            LinearLayout.LayoutParams valueSize = new LinearLayout.LayoutParams(-1, Math.round(ui.sp(17) * 1.35f));
            valueSize.topMargin = ui.dp(3);
            addView(value, valueSize);
        }

        /** The tile's name, set again when the ride changes (the battery tile names a drop or a gain). */
        void setLabel(Msg label) {
            name.setText(label.render());
        }

        /** A measured value, or null for one the ride did not measure (a dash, muted). */
        void set(String shown) {
            value.setText(shown == null ? "—" : shown);
            value.setAlpha(shown == null ? .6f : 1f);
        }
    }

    /** The route on a plain dark rounded panel, with its start and end marked; a route icon while there is none. */
    private static final class RouteView extends View {
        private final Ui ui;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private final Path path = new Path();
        private float[] route;

        RouteView(ScreenHost host) {
            super(host.activity());
            ui = host.ui();
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void setRoute(float[] value) {
            route = value;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            // What the words and tiles leave (large text) may be too little for a route: then the panel stays empty.
            if (getHeight() < ui.dp(MIN_ROUTE_DP)) return;
            box.set(0, 0, getWidth(), getHeight());
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(ui.dark ? ui.bg : Color.rgb(0x1f, 0x22, 0x27));
            canvas.drawRoundRect(box, ui.dp(14), ui.dp(14), paint);
            if (route == null || route.length < 4) {
                ui.drawIcon(canvas, "route", Ui.withAlpha(Color.WHITE, 90), getWidth() / 2f, getHeight() / 2f, ui.dp(22));
                return;
            }
            float inset = ui.dp(14), w = getWidth() - 2 * inset, h = getHeight() - 2 * inset;
            if (w <= 0 || h <= 0) return;
            float scale = Math.min(w / Math.max(1e-4f, route[0]), h / Math.max(1e-4f, route[1]));
            if (route[0] < 1e-4f && route[1] < 1e-4f) scale = 0;
            float ox = inset + (w - route[0] * scale) / 2, oy = inset + (h - route[1] * scale) / 2;
            float sx = ox + route[2] * scale, sy = oy + route[3] * scale;
            float ex = ox + route[route.length - 2] * scale, ey = oy + route[route.length - 1] * scale;
            if (route.length > 4 && scale > 0) {
                path.reset();
                path.moveTo(sx, sy);
                for (int i = 4; i + 1 < route.length; i += 2) path.lineTo(ox + route[i] * scale, oy + route[i + 1] * scale);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(ui.dp(3));
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeJoin(Paint.Join.ROUND);
                paint.setColor(ui.accent);
                canvas.drawPath(path, paint);
            }
            paint.setStyle(Paint.Style.FILL);
            // Start: a light dot; end: the accent dot ringed in light.
            paint.setColor(Color.WHITE);
            canvas.drawCircle(sx, sy, ui.dp(4.5f), paint);
            canvas.drawCircle(ex, ey, ui.dp(6), paint);
            paint.setColor(ui.accent);
            canvas.drawCircle(ex, ey, ui.dp(4), paint);
        }
    }
}
