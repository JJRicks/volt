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
    private final LinearLayout first;
    private final RouteView route;
    private final Tiles tiles;
    private final Cell time, speed, battery;
    private String rideId;
    private String shownKey = "";
    /** The card's spoken name without the ended line, which bind puts in front of it each time. */
    private Msg spokenRide;

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

        first = new LinearLayout(host.activity());
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

        time = new Cell(host, HubText.justBackRideTime());
        speed = new Cell(host, HubText.justBackAvgSpeed());
        battery = new Cell(host, HubText.justBackBattery(null));
        tiles = new Tiles(host, ui.dp(8), time, speed, battery);
        // The tiles read as one group of their own: the card's name says only the ride and that it opens.
        tiles.setFocusable(true);
        if (android.os.Build.VERSION.SDK_INT >= 28) tiles.setScreenReaderFocusable(true);
        LinearLayout.LayoutParams tilesSize = new LinearLayout.LayoutParams(-1, -2);
        tilesSize.topMargin = ui.dp(10);
        tilesSize.setMarginEnd(ui.dp(8));
        addView(tiles, tilesSize);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        // The tiles may take two rows only when they still fit the top area's fixed height with the words above them;
        // the route panel cannot give up more than all of its height (council early review of part 9, second round).
        int room = Integer.MAX_VALUE;
        if (MeasureSpec.getMode(heightSpec) == MeasureSpec.EXACTLY) {
            measureChildWithMargins(first, widthSpec, 0, heightSpec, 0);
            room = MeasureSpec.getSize(heightSpec) - getPaddingTop() - getPaddingBottom()
                    - outer(first, first.getMeasuredHeight()) - outer(distance, ((LayoutParams) distance.getLayoutParams()).height)
                    - outer(route, 0) - outer(tiles, 0);
        }
        tiles.setMaxHeight(Math.max(0, room));
        super.onMeasure(widthSpec, heightSpec);
    }

    /** A child's height plus its top and bottom margins. */
    private static int outer(View child, int height) {
        LayoutParams size = (LayoutParams) child.getLayoutParams();
        return height + size.topMargin + size.bottomMargin;
    }

    /** The ride shown, or null before the first bind. */
    String rideId() { return rideId; }

    /**
     * Shows ride s (a finished ride) as of now, in the "units" preference. Cheap to call on every refresh: only the
     * minutes line changes between rides, and the rest is set again only when the ride or the units change.
     */
    void bind(RideStore.Summary s, String units, long now) {
        String ended = HubText.justBackEnded(HomeMoment.minutesAgo(s.shownEndWallMs, now)).render();
        saved.setText(ended);
        String key = s.id + ":" + units;
        if (!key.equals(shownKey)) show(s, units, key);
        // The line sighted owners read first is spoken first too (council early review of part 9, note 7).
        // Two sentences, as two separately translated strings; a combined one waits for a batch part.
        String spoken = ended + (endsSentence(ended) ? " " : ". ") + spokenRide.render();
        if (!spoken.contentEquals(getContentDescription() == null ? "" : getContentDescription())) setContentDescription(spoken);
    }

    /** Whether text already ends in sentence punctuation (a translation may end its line with one). */
    private static boolean endsSentence(String text) {
        String trimmed = text.trim();
        return !trimmed.isEmpty() && ".!?\u3002\uff01\uff1f\u061f\u0964".indexOf(trimmed.charAt(trimmed.length() - 1)) >= 0;
    }

    /** Everything but the ended line, for ride s in units; key is the ride and units it shows. */
    private void show(RideStore.Summary s, String units, String key) {
        boolean otherRide = !s.id.equals(rideId);
        shownKey = key;
        rideId = s.id;
        if (otherRide) route.setRoute(null);
        Formatted miles = s.distanceKm == null || !Double.isFinite(s.distanceKm) ? null : RideText.distance(s.distanceKm, units);
        distance.setText(miles == null ? "—" : large(miles));
        spokenRide = HubText.justBackDescription(miles == null ? "—" : miles.toString());
        String clock = s.shownElapsedMs > 0 ? RidingFocus.clockText(s.shownElapsedMs) : null;
        time.set(clock, clock);
        if (s.averageSpeedKmh == null || !Double.isFinite(s.averageSpeedKmh)) speed.set(null, null);
        else {
            // Both units may not fit a narrow tile at its readable floor: then the first unit alone, both spoken (note 3).
            String both = RideText.speed(s.averageSpeedKmh, units).toString();
            speed.set(both, "dual".equals(units) ? RideText.speed(s.averageSpeedKmh, "mi").toString() : both);
        }
        Double drop = s.batteryDropPercent;
        boolean known = drop != null && Double.isFinite(drop);
        // A ride that gained charge (regeneration downhill) says Battery gain with the size of the gain, as Rides does.
        battery.setLabel(HubText.justBackBattery(known ? drop : null));
        String change = known ? Fmt.percent(Fmt.decimalNumber(Locale.US, Math.abs(drop), 0)).toString() : null;
        battery.set(change, change);
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

    /**
     * The three tiles: three in a row while every name fits a third whole in two lines, else two in a row with the
     * battery tile spanning the second row (TileFit.columns, council early review of part 9, note 2). The names keep
     * their full words: no name is broken inside a word or cut at normal text size; the route panel above gives up
     * the height a second row takes (it draws nothing when too little is left). When even all of the route panel's
     * height is not enough for a second row (large text), the tiles stay three in a row with names ellipsized at two
     * lines, so nothing runs past the top area's bottom edge.
     */
    private static final class Tiles extends android.view.ViewGroup {
        private final Cell[] cells;
        private final int gap;
        private int columns = 3;
        private int maxHeight = Integer.MAX_VALUE;

        Tiles(ScreenHost host, int gap, Cell... cells) {
            super(host.activity());
            this.gap = gap;
            this.cells = cells;
            for (Cell cell : cells) addView(cell);
        }

        /** The most height the tiles may take, set by JustBackView before each measure. */
        void setMaxHeight(int value) { maxHeight = value; }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec);
            int third = (width - 2 * gap) / 3;
            String[] names = new String[cells.length];
            for (int i = 0; i < cells.length; i++) names[i] = cells[i].label();
            Cell first = cells[0];
            int wanted = MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED ? 3
                    : TileFit.columns(names, first.namePaint()::measureText, third - first.horizontalPadding(), Cell.NAME_LINES);
            int height = measureAt(wanted, width);
            if (wanted != 3) {
                int fewer = TileFit.columns(names, first.namePaint()::measureText, third - first.horizontalPadding(), Cell.NAME_LINES, height, maxHeight);
                if (fewer == 3) height = measureAt(3, width);
            }
            setMeasuredDimension(width, resolveSize(height, heightSpec));
        }

        /** Measures the cells with count tiles in a row and returns the height they take. */
        private int measureAt(int count, int width) {
            columns = count;
            int height = 0, row = 0;
            for (int i = 0; i < cells.length; i++) {
                int span = columns == 3 ? 1 : i == cells.length - 1 && cells.length % 2 == 1 ? 2 : 1;
                int each = (width - (columns - 1) * gap) / columns;
                int cellWidth = span == 2 ? width : each;
                cells[i].measure(MeasureSpec.makeMeasureSpec(Math.max(0, cellWidth), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                row = Math.max(row, cells[i].getMeasuredHeight());
                boolean rowEnds = columns == 3 ? i == cells.length - 1 : i % 2 == 1 || i == cells.length - 1;
                if (rowEnds) { height += (height > 0 ? gap : 0) + row; row = 0; }
            }
            // Each row's tiles share its height, so a row reads as one band.
            int top = 0, rowStart = 0;
            for (int i = 0; i < cells.length; i++) {
                boolean rowEnds = columns == 3 ? i == cells.length - 1 : i % 2 == 1 || i == cells.length - 1;
                if (!rowEnds) continue;
                int tallest = 0;
                for (int j = rowStart; j <= i; j++) tallest = Math.max(tallest, cells[j].getMeasuredHeight());
                for (int j = rowStart; j <= i; j++)
                    cells[j].measure(MeasureSpec.makeMeasureSpec(cells[j].getMeasuredWidth(), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(tallest, MeasureSpec.EXACTLY));
                rowStart = i + 1;
            }
            return height;
        }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
            int width = r - l, x = 0, y = 0, rowHeight = 0;
            for (int i = 0; i < cells.length; i++) {
                Cell cell = cells[i];
                int w = cell.getMeasuredWidth(), h = cell.getMeasuredHeight();
                int left = rtl ? width - x - w : x;
                cell.layout(left, y, left + w, y + h);
                rowHeight = Math.max(rowHeight, h);
                boolean rowEnds = columns == 3 ? i == cells.length - 1 : i % 2 == 1 || i == cells.length - 1;
                if (rowEnds) { y += rowHeight + gap; x = 0; rowHeight = 0; }
                else x += w + gap;
            }
        }
    }

    /**
     * One of the three small tiles: its name (whole words, at most two lines; Tiles gives it the room) above a value
     * that fits its width. The value follows the narrow-label ladder (TileFit.pick, note 3): whole on one line at its
     * readable floor or larger, else its short rung (the speed's first unit alone, with units set to both), while the
     * full value is what is spoken.
     */
    private static final class Cell extends LinearLayout {
        static final int NAME_LINES = 2;
        private final TextView name;
        private final Rungs value;
        private final float valueFloor;
        private final Paint floorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Cell(ScreenHost host, Msg label) {
            super(host.activity());
            Ui ui = host.ui();
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8));
            setBackground(ui.shape(ui.raised, 14, 0));
            name = ui.caption(label.render());
            name.setMaxLines(NAME_LINES);
            // Only with very large text, where even half a row is too narrow, does a name still end in an ellipsis.
            name.setEllipsize(TextUtils.TruncateAt.END);
            addView(name, new LinearLayout.LayoutParams(-1, -2));
            value = new Rungs(ui.text("—", 17, true), 1);
            int floor = Math.round(ui.sp(Ui.READABLE_FLOOR_SP)), top = Math.round(ui.sp(17));
            if (floor < top) value.view.setAutoSizeTextTypeUniformWithConfiguration(floor, top, 1, TypedValue.COMPLEX_UNIT_PX);
            valueFloor = Math.min(floor, top);
            LinearLayout.LayoutParams valueSize = new LinearLayout.LayoutParams(-1, Math.round(ui.sp(17) * 1.35f));
            valueSize.topMargin = ui.dp(3);
            addView(value.view, valueSize);
        }

        /** The tile's name as shown. */
        String label() { return name.getText().toString(); }

        Paint namePaint() { return name.getPaint(); }

        int horizontalPadding() { return getPaddingLeft() + getPaddingRight(); }

        /** The tile's name, set again when the ride changes (the battery tile names a drop or a gain). */
        void setLabel(Msg label) {
            String shown = label.render();
            if (!shown.contentEquals(name.getText())) { name.setText(shown); requestLayout(); }
        }

        /** A measured value and its short rung, or null for one the ride did not measure (a dash, muted). */
        void set(String shown, String shorter) {
            String full = shown == null ? "—" : shown;
            value.set(full, shown == null || shorter == null ? full : shorter);
            value.view.setAlpha(shown == null ? .6f : 1f);
            requestLayout();
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            if (MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED) {
                int room = MeasureSpec.getSize(widthSpec) - horizontalPadding();
                floorPaint.set(value.view.getPaint());
                floorPaint.setTextSize(valueFloor);
                value.show(TileFit.pick(value.rungs, floorPaint::measureText, room, 1) > 0);
            }
            super.onMeasure(widthSpec, heightSpec);
        }
    }

    /** A tile value with its long and short rung; the long one is always what is spoken. */
    private static final class Rungs {
        final TextView view;
        String[] rungs = {"", ""};

        Rungs(TextView view, int lines) {
            this.view = view;
            view.setMaxLines(lines);
            // Only the short rung can still be too wide (very large text); it ends in an ellipsis then.
            view.setEllipsize(TextUtils.TruncateAt.END);
        }

        void set(String full, String shorter) {
            rungs = new String[]{full, shorter};
            view.setContentDescription(full);
            show(false);
        }

        void show(boolean shorter) {
            String pick = rungs[shorter ? 1 : 0];
            if (!pick.contentEquals(view.getText())) view.setText(pick);
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
