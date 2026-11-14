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


/**
 * Clean Home's "Just back" moment (hub layout plan 1.5 and 1.7, PR 9): for about an hour after a ride ends, the top
 * area shows it, as the Layout Plan's mock-up draws it (the contributor's decisions on #383): on the page itself, no
 * card. A muted "Ride ended 12 minutes ago" centred with a small round X at the right, the distance large and centred
 * (about 60 sp, its unit smaller in the text colour), the route drawn on a rounded panel in the card colour with a faint
 * dot grid, a green start dot and a white end dot (from the phone journal's own GPS fixes; never a map tile), then three tiles: Ride time, Avg
 * speed and Battery with the signed change ("−18%"). Tapping it opens that ride; the X closes it until the next ride.
 *
 * <p>It fills the top area's fixed height (the same as parked and charging), so the Lock bar never moves: the route
 * panel takes what the words and tiles leave and goes when that is too little, then the distance gets smaller
 * (JustBackFit.split). Text from HubText and the ride formatters (RideText, Fmt); this file holds no
 * catalog references.
 */
final class JustBackView extends LinearLayout {
    /** View tags: the card itself (it opens the ride), its X and the route panel. */
    static final String TAG = "home-just-back", TAG_CLOSE = "home-just-back-close", TAG_ROUTE = "home-just-back-route";
    /** Below this height the route panel is not worth drawing; the words and tiles keep the space. */
    private static final int MIN_ROUTE_DP = JustBackFit.MIN_ROUTE_DP;
    /** The gaps above the route panel and above the tiles, and the X's disc inside its 48 dp target. */
    private static final int ROUTE_GAP_DP = 6, TILES_GAP_DP = 8, CLOSE_DISC_DP = 30;
    /** The distance's line box, as a share of its text size, and the smallest text it shrinks to on a short area. */
    private static final float DISTANCE_LINE = 1.16f, DISTANCE_FLOOR_SP = 28;

    private final Ui ui;
    private final TextView saved, distance;
    private final FrameLayout first;
    /** The distance's full and smallest line box (px). */
    private final int distanceFull, distanceFloor;
    private final RouteView route;
    private final Tiles tiles;
    private final Cell time, speed, battery;
    private String rideId;
    private String shownKey = "";
    /** The distance as the card shows it ("6.2 mi"), for the card's spoken sentence (HubText.justBackSpoken). */
    private CharSequence spokenDistance;

    /** open gets the shown ride's id; close too. */
    JustBackView(ScreenHost host, java.util.function.Consumer<String> open, java.util.function.Consumer<String> close) {
        super(host.activity());
        ui = host.ui();
        setOrientation(VERTICAL);
        setTag(TAG);
        // On the page background, as the mock-up draws it: only the press ripple shows, inside the area's bounds.
        setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(ui.accent, 26)), null, ui.shape(Color.WHITE, 20, 0)));
        setClickable(true);
        setFocusable(true);
        setOnClickListener(v -> { if (rideId != null) open.accept(rideId); });

        // The ended line centred over the distance, the X at the right on the same line. A line too long to stay
        // centred clear of the X (large text, long languages) starts at the left edge, on two lines if it must, instead
        // of being cut early.
        first = new FrameLayout(host.activity()) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                FrameLayout.LayoutParams size = (FrameLayout.LayoutParams) saved.getLayoutParams();
                int x = ui.dp(48), room = MeasureSpec.getSize(widthSpec) - 2 * x;
                boolean centred = saved.getPaint().measureText(String.valueOf(saved.getText())) <= room;
                int start = centred ? x : 0;
                // The resolved side margin too: a margin set on the params after they were resolved is otherwise only
                // read at the next layout-direction change, and the line stayed centred and cut (emulator, 1.4x text).
                boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
                if (size.getMarginStart() != start || (rtl ? size.rightMargin : size.leftMargin) != start) {
                    size.setMarginStart(start);
                    if (rtl) size.rightMargin = start; else size.leftMargin = start;
                }
                // Still too long beside the X: a second line rather than an early cut.
                boolean one = centred || saved.getPaint().measureText(String.valueOf(saved.getText())) <= room + x;
                if (saved.getMaxLines() != (one ? 1 : 2)) saved.setMaxLines(one ? 1 : 2);
                saved.setGravity(centred ? Gravity.CENTER : Gravity.START | Gravity.CENTER_VERTICAL);
                super.onMeasure(widthSpec, heightSpec);
            }
        };
        saved = ui.caption("");
        saved.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f * ui.textScale);
        saved.setMaxLines(1);
        saved.setEllipsize(TextUtils.TruncateAt.END);
        saved.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams savedSize = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL);
        savedSize.setMarginStart(ui.dp(48));
        savedSize.setMarginEnd(ui.dp(48));
        first.addView(saved, savedSize);
        first.addView(closeButton(host, () -> { if (rideId != null) close.accept(rideId); }),
                new FrameLayout.LayoutParams(ui.dp(48), ui.dp(48), Gravity.END | Gravity.CENTER_VERTICAL));
        first.setMinimumHeight(ui.dp(48));
        addView(first, new LinearLayout.LayoutParams(-1, -2));

        // The distance: about 60 sp, growing only a little with large text (JustBackFit.distanceSp), smaller only
        // when the area is short (JustBackFit.split gives it its line box).
        float distanceSp = JustBackFit.distanceSp(ui.textScale);
        distance = ui.text("", distanceSp, true);
        distance.setMaxLines(1);
        distance.setGravity(Gravity.CENTER);
        distance.setIncludeFontPadding(false);
        int top = Math.round(ui.sp(distanceSp)), floor = Math.min(top, Math.round(ui.sp(DISTANCE_FLOOR_SP / ui.textScale)));
        if (floor < top) distance.setAutoSizeTextTypeUniformWithConfiguration(floor, top, 1, TypedValue.COMPLEX_UNIT_PX);
        distance.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        distanceFull = Math.round(top * DISTANCE_LINE);
        distanceFloor = Math.round(floor * DISTANCE_LINE);
        addView(distance, new LinearLayout.LayoutParams(-1, distanceFull));

        route = new RouteView(host);
        route.setTag(TAG_ROUTE);
        LinearLayout.LayoutParams routeSize = new LinearLayout.LayoutParams(-1, 0);
        routeSize.topMargin = ui.dp(ROUTE_GAP_DP);
        addView(route, routeSize);

        time = new Cell(host, HubText.justBackRideTime());
        speed = new Cell(host, HubText.justBackAvgSpeed());
        battery = new Cell(host, HubText.justBackBatteryName());
        tiles = new Tiles(host, ui.dp(8), time, speed, battery);
        // The tiles read as one group of their own: the card's name says only the ride and that it opens.
        tiles.setFocusable(true);
        if (android.os.Build.VERSION.SDK_INT >= 28) tiles.setScreenReaderFocusable(true);
        LinearLayout.LayoutParams tilesSize = new LinearLayout.LayoutParams(-1, -2);
        tilesSize.topMargin = ui.dp(TILES_GAP_DP);
        addView(tiles, tilesSize);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        LayoutParams distanceSize = (LayoutParams) distance.getLayoutParams(), routeSize = (LayoutParams) route.getLayoutParams();
        if (MeasureSpec.getMode(heightSpec) != MeasureSpec.EXACTLY) {
            distanceSize.height = distanceFull;
            routeSize.height = ui.dp(MIN_ROUTE_DP * 2);
            routeSize.topMargin = ui.dp(ROUTE_GAP_DP);
            tiles.setMaxHeight(Integer.MAX_VALUE);
            super.onMeasure(widthSpec, heightSpec);
            return;
        }
        // The tiles may take two rows only when they still fit the top area's fixed height with the ended line and
        // the distance at its smallest above them (council early review of part 9, second round).
        int height = MeasureSpec.getSize(heightSpec) - getPaddingTop() - getPaddingBottom();
        measureChildWithMargins(first, widthSpec, 0, heightSpec, 0);
        int words = outer(first, first.getMeasuredHeight());
        tiles.setMaxHeight(Math.max(0, height - words - distanceFloor - outer(tiles, 0)));
        measureChildWithMargins(tiles, widthSpec, 0, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), 0);
        // What is left between them: the distance keeps its box, the route takes the rest or goes (JustBackFit.split).
        int[] split = JustBackFit.split(height - words - outer(tiles, tiles.getMeasuredHeight()), distanceFull, distanceFloor,
                ui.dp(MIN_ROUTE_DP), ui.dp(ROUTE_GAP_DP));
        distanceSize.height = split[0];
        routeSize.height = split[1];
        routeSize.topMargin = split[2];
        route.setVisibility(split[1] > 0 ? VISIBLE : GONE);
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
        long minutes = HomeMoment.minutesAgo(s.shownEndWallMs, now);
        String ended = HubText.justBackEnded(minutes).render();
        saved.setText(ended);
        String key = s.id + ":" + units;
        if (!key.equals(shownKey)) show(s, units, key);
        // One whole sentence per language, the ended line sighted owners read first spoken first too (council early review
        // of part 9, note 7), then the distance (part 10's text).
        String spoken = HubText.justBackSpoken(minutes, spokenDistance).render();
        if (!spoken.contentEquals(getContentDescription() == null ? "" : getContentDescription())) setContentDescription(spoken);
    }

    /** Everything but the ended line, for ride s in units; key is the ride and units it shows. */
    private void show(RideStore.Summary s, String units, String key) {
        boolean otherRide = !s.id.equals(rideId);
        shownKey = key;
        rideId = s.id;
        if (otherRide) route.setRoute(null);
        Formatted miles = s.distanceKm == null || !Double.isFinite(s.distanceKm) ? null : RideText.distance(s.distanceKm, units, 1);
        distance.setText(miles == null ? "—" : large(miles));
        spokenDistance = distance.getText().toString();
        // A duration, short as the mock-up writes it ("24m", "1h 02m"), not a clock that reads as a time of day.
        String duration = s.shownElapsedMs > 0 ? HubText.justBackRideTimeValue(s.shownElapsedMs).render() : null;
        // Spoken in words ("20 minutes", "1 hour 2 minutes"), so a screen reader never reads "20m" as a distance.
        time.set(duration, duration == null ? null : HubText.justBackRideTimeSpoken(s.shownElapsedMs).render());
        // The saved average, else the measured distance over the moving or shown time (RideAverages, as the ride's page
        // on Rides): a ride with a distance and a time never shows a dash. Formatted as Rides formats a speed (RideText.speed).
        Double average = RideAverages.speedKmh(s);
        if (average == null) speed.set(null, null);
        else {
            // Both units may not fit a narrow tile at its readable floor: then the first unit alone, both spoken (note 3).
            Formatted both = RideText.speed(average, units);
            speed.set(styled(both, JustBackFit.VALUE_UNIT_RATIO, false), "dual".equals(units)
                    ? styled(RideText.speed(average, "mi"), JustBackFit.VALUE_UNIT_RATIO, false) : null, both.toString());
        }
        // "Battery" with the signed change, as the mock-up has it ("−18%", "+2%" for a ride that gained charge).
        String change = JustBackFit.signedChange(s.batteryDropPercent);
        if (change == null) battery.set(null, null);
        else {
            Formatted percent = Fmt.percent(Fmt.number(change));
            battery.set(styled(percent, JustBackFit.VALUE_UNIT_RATIO, false), null, percent.toString());
        }
    }

    /** The route of ride id, from RidesScreen.readThumb; empty when the ride has no usable GPS fixes. */
    void setRoute(String id, float[] outline) {
        if (id != null && id.equals(rideId)) route.setRoute(outline);
    }

    /** The distance's numbers large, the rest (its unit, a second unit) smaller, in the text colour as the mock-up has it. */
    private CharSequence large(Formatted value) {
        return styled(value, JustBackFit.DISTANCE_UNIT_RATIO, false);
    }

    /** value with its numbers at full size and everything else (units, the percent sign) at ratio, muted if asked. */
    private CharSequence styled(Formatted value, float ratio, boolean muted) {
        SpannableString styled = new SpannableString(value.toString());
        int from = 0;
        for (int i = 0; i <= value.numbers(); i++) {
            int to = i < value.numbers() ? value.numberStart(i) : value.length();
            if (to > from) {
                styled.setSpan(new RelativeSizeSpan(ratio), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                if (muted) styled.setSpan(new ForegroundColorSpan(ui.muted), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (i < value.numbers()) from = value.numberEnd(i);
        }
        return styled;
    }

    private View closeButton(ScreenHost host, Runnable action) {
        FrameLayout target = new FrameLayout(host.activity());
        GradientDrawable disc = new GradientDrawable();
        disc.setShape(GradientDrawable.OVAL);
        disc.setColor(ui.dark ? ui.surface : ui.raised);
        target.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(ui.accent, 35)), new InsetDrawable(disc, ui.dp((48 - CLOSE_DISC_DP) / 2)), null));
        target.addView(ui.icon("close", 13, ui.muted), new FrameLayout.LayoutParams(ui.dp(13), ui.dp(13), Gravity.CENTER));
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
     * on one line, its number large and its unit small as the mock-up draws "15.5 mph" and "−18%". The value takes the
     * largest size that fits the tile, down to the readable floor (JustBackFit.valueSize), so a unit is never cut;
     * below the floor it takes its short rung (the speed's first unit alone, with units set to both), while the full
     * value is what is spoken.
     */
    private static final class Cell extends LinearLayout {
        static final int NAME_LINES = 2;
        private final TextView name, value;
        private final float valueTop, valueFloor;
        private final android.text.TextPaint measure = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
        private CharSequence full = "—", shorter;

        Cell(ScreenHost host, Msg label) {
            super(host.activity());
            Ui ui = host.ui();
            setOrientation(VERTICAL);
            // From the top, the value at the bottom: a row's names and values line up whatever a name's line count.
            setGravity(Gravity.TOP);
            setPadding(ui.dp(10), ui.dp(9), ui.dp(10), ui.dp(9));
            // The charging moment's tile colour and corner (DashboardScreen.InfoCell), so the row keeps its look.
            setBackground(ui.shape(ui.raised, 14, 0));
            name = ui.caption(label.render());
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f * ui.textScale);
            name.setMaxLines(NAME_LINES);
            // Only with very large text, where even half a row is too narrow, does a name still end in an ellipsis.
            name.setEllipsize(TextUtils.TruncateAt.END);
            addView(name, new LinearLayout.LayoutParams(-1, -2));
            addView(new View(host.activity()), new LinearLayout.LayoutParams(-1, 0, 1));
            value = ui.text("—", JustBackFit.VALUE_SP, true);
            value.setMaxLines(1);
            value.setIncludeFontPadding(false);
            // At the bottom of its box: a value shrunk to fit its tile keeps the row's baseline, not the box's top.
            value.setGravity(Gravity.START | Gravity.BOTTOM);
            // Only the short rung at the floor can still be too wide (very large text); it ends in an ellipsis then.
            value.setEllipsize(TextUtils.TruncateAt.END);
            valueTop = ui.sp(JustBackFit.VALUE_SP);
            valueFloor = Math.min(valueTop, ui.sp(Ui.READABLE_FLOOR_SP));
            LinearLayout.LayoutParams valueSize = new LinearLayout.LayoutParams(-1, Math.round(valueTop * 1.25f));
            valueSize.topMargin = ui.dp(4);
            addView(value, valueSize);
        }

        /** The tile's name as shown. */
        String label() { return name.getText().toString(); }

        Paint namePaint() { return name.getPaint(); }

        int horizontalPadding() { return getPaddingLeft() + getPaddingRight(); }

        /**
         * A measured value (styled), its short rung (null: none) and what is spoken; null shown for one the ride did
         * not measure (a dash, muted).
         */
        void set(CharSequence shown, CharSequence short_, String spoken) {
            full = shown == null ? "—" : shown;
            shorter = shown == null ? null : short_;
            value.setContentDescription(shown == null ? null : spoken);
            value.setAlpha(shown == null ? .6f : 1f);
            if (!full.toString().contentEquals(value.getText())) value.setText(full);
            requestLayout();
        }

        /** The same, for a value with no units to style (the ride time). */
        void set(String shown, String spoken) { set(shown, null, spoken); }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            if (MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED) {
                float room = MeasureSpec.getSize(widthSpec) - horizontalPadding();
                CharSequence pick = full;
                float size = fit(full, room);
                if (size < 0 && shorter != null) { pick = shorter; size = fit(shorter, room); }
                if (size < 0) size = valueFloor;
                if (!pick.toString().contentEquals(value.getText())) value.setText(pick);
                if (Math.abs(value.getTextSize() - size) > .5f) value.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
            }
            super.onMeasure(widthSpec, heightSpec);
        }

        /** The size text takes in room (JustBackFit.valueSize), or -1 when it does not fit at the floor. */
        private float fit(CharSequence text, float room) {
            measure.set(value.getPaint());
            measure.setTextSize(valueTop);
            // Spans (the small unit) count; a little slack for the bold face's overhang.
            float width = android.text.Layout.getDesiredWidth(text, measure) + 2;
            return JustBackFit.valueSize(valueTop, valueFloor, width, room);
        }
    }

    /** The route on a rounded panel in the card colour, with its start and end marked; a route icon while there is none. */
    private static final class RouteView extends View {
        private final Ui ui;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private final Path path = new Path();
        private float[] route;
        /** The mock-up's dot grid: a faint dot every GRID_DP, GRID_INSET_DP in from the panel's edges. */
        private static final int GRID_DP = 14, GRID_INSET_DP = 12;

        RouteView(ScreenHost host) {
            super(host.activity());
            ui = host.ui();
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        /** The panel: the card colour, as the tiles' on the dark page and one step darker than the light page. */
        private int panelColor() { return ui.dark ? ui.surface : ui.raised; }

        void setRoute(float[] value) {
            route = value;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            // What the words and tiles leave (large text) may be too little for a route: then the panel stays empty.
            if (getHeight() < ui.dp(MIN_ROUTE_DP)) return;
            box.set(0, 0, getWidth(), getHeight());
            paint.setStyle(Paint.Style.FILL);
            // The card colour in both themes, as the mock-up draws it, with its faint dot grid.
            int panel = panelColor();
            paint.setColor(panel);
            canvas.drawRoundRect(box, ui.dp(20), ui.dp(20), paint);
            paint.setColor(Ui.withAlpha(ui.muted, 70));
            float step = ui.dp(GRID_DP), grid = ui.dp(GRID_INSET_DP), dot = ui.dp(1) / 2f + .5f;
            for (float y = grid; y <= getHeight() - grid + .5f; y += step)
                for (float x = grid; x <= getWidth() - grid + .5f; x += step) canvas.drawCircle(x, y, dot, paint);
            if (route == null || route.length < 4) {
                ui.drawIcon(canvas, "route", Ui.withAlpha(ui.muted, 140), getWidth() / 2f, getHeight() / 2f, ui.dp(22));
                return;
            }
            float inset = Math.min(ui.dp(18), getHeight() / 5f), w = getWidth() - 2 * inset, h = getHeight() - 2 * inset;
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
                paint.setStrokeWidth(ui.dp(4));
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeJoin(Paint.Join.ROUND);
                paint.setColor(ui.accent);
                canvas.drawPath(path, paint);
            }
            paint.setStyle(Paint.Style.FILL);
            // As the mock-up: a green start dot and a white end dot (the text colour on the light panel, where white
            // would vanish), each ringed in the panel's colour.
            // A loop ends where it started: the end's white dot then rings the start's smaller green one.
            boolean together = Math.hypot(ex - sx, ey - sy) < ui.dp(10);
            paint.setColor(panel);
            canvas.drawCircle(sx, sy, ui.dp(7), paint);
            canvas.drawCircle(ex, ey, ui.dp(7), paint);
            paint.setColor(ui.dark ? Color.WHITE : ui.text);
            canvas.drawCircle(ex, ey, ui.dp(5), paint);
            paint.setColor(ui.ok);
            canvas.drawCircle(sx, sy, ui.dp(together ? 3 : 5), paint);
        }
    }
}
