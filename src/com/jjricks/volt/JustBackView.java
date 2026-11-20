package com.jjricks.volt;

import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.SystemClock;
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
 * dot grid, a green start dot and a white end dot (from the phone journal's own GPS fixes), then three tiles: Ride time, Avg
 * speed and Battery with the signed change ("−18%"). Tapping it opens that ride; the X closes it until the next ride. A
 * round switch in the panel's bottom corner puts map tiles behind the route (owner, Nov 19 2026): its own preference,
 * off by default, so the plain panel stays what Home shows until someone asks for the map (RouteView).
 *
 * <p>It fills the top area's fixed height (the same as parked and charging), so the Lock bar never moves: the route
 * panel takes what the words and tiles leave and goes when that is too little, then the distance gets smaller
 * (JustBackFit.split). Text from HubText and the ride formatters (RideText, Fmt); this file holds no
 * catalog references.
 */
final class JustBackView extends LinearLayout {
    /** View tags: the card itself (it opens the ride), its X and the route panel. */
    static final String TAG = "home-just-back", TAG_CLOSE = "home-just-back-close", TAG_ROUTE = "home-just-back-route";
    /** View tag: the basemap switch in the route panel's corner. */
    static final String TAG_BASEMAP = "home-just-back-basemap";
    /** How long the tile layer and each newly arrived tile take to fade in. */
    private static final long TILE_FADE_MS = 400;
    /** The end dots' ring, kept clear of the basemap switch. */
    private static final int DOT_RING_DP = 7;
    /**
     * The dark theme's tiles (JustBackBasemap.darkTiles): lightness inverted, colour softened to 40%, contrast at 85%
     * and lifted so a street map's land sits just above the panel's own grey. Chosen from renders of a light street map
     * on the dark panel against plain dimming, which left a flat mid-grey map as bright as the text (Nov 19 2026).
     */
    private static final float DARK_SATURATION = .4f, DARK_CONTRAST = .85f, DARK_LIFT = 20;
    /**
     * The size a tile is drawn at its own zoom: half the ride maps' 256 dp (owner, Nov 19 2026: "let's get a finer map"),
     * so the card asks for the zoom one finer than a ride map would at the same scale: crisper streets and smaller
     * labels on the small panel, about four times the tiles (still only those the panel shows, through the same cache).
     */
    private static final int TILE_DP = 128;
    /** The route's casing over a map: RouteMapView's. */
    private static final int CASING = 0xff20242c;
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
        // centred clear of the X on both sides is centred in the room left of the X; only one too long even for that
        // (large text, long languages) starts at the left edge, on two lines, instead of being cut early.
        first = new FrameLayout(host.activity()) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                FrameLayout.LayoutParams size = (FrameLayout.LayoutParams) saved.getLayoutParams();
                int x = ui.dp(48), room = MeasureSpec.getSize(widthSpec) - 2 * x;
                float wide = saved.getPaint().measureText(String.valueOf(saved.getText()));
                boolean centred = wide <= room, one = wide <= room + x;
                int start = centred ? x : 0;
                // The resolved side margin too: a margin set on the params after they were resolved is otherwise only
                // read at the next layout-direction change, and the line stayed centred and cut (emulator, 1.4x text).
                boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
                if (size.getMarginStart() != start || (rtl ? size.rightMargin : size.leftMargin) != start) {
                    size.setMarginStart(start);
                    if (rtl) size.rightMargin = start; else size.leftMargin = start;
                }
                if (saved.getMaxLines() != (one ? 1 : 2)) saved.setMaxLines(one ? 1 : 2);
                saved.setGravity(one ? Gravity.CENTER : Gravity.START | Gravity.CENTER_VERTICAL);
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
        if (otherRide) route.setRoute(null, null);
        Formatted miles = s.distanceKm == null || !Double.isFinite(s.distanceKm) ? null : RideText.distance(s.distanceKm, units, 1);
        distance.setText(miles == null ? "—" : large(miles));
        spokenDistance = distance.getText().toString();
        // A duration, short as the mock-up writes it ("24m", "1h 02m"), not a clock that reads as a time of day.
        String duration = s.shownElapsedMs > 0 ? HubText.justBackRideTimeValue(s.shownElapsedMs).render() : null;
        // Spoken in words ("20 minutes", "1 hour 2 minutes"), so a screen reader never reads "20m" as a distance.
        if (duration == null) time.set(null, null);
        else time.set(duration, HubText.justBackRideTimeSpoken(s.shownElapsedMs).render());
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

    /**
     * The route of ride id, from RidesScreen.readThumb (empty when the ride has no usable GPS fixes), with its fixes' map
     * area for the basemap (JustBackBasemap.box; NaN without a route).
     */
    void setRoute(String id, float[] outline, double[] area) {
        if (id != null && id.equals(rideId)) route.setRoute(outline, area);
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
     * battery tile spanning the second row (TileFit.layout, council early review of part 9, note 2). The names keep
     * their full words: no name is broken inside a word; the route panel above gives up the height a second row takes
     * (it draws nothing when too little is left). When even all of the route panel's height is not enough for a second
     * row (a short split-screen window, council's second review, finding 3), the two rows take the tiles' tight padding;
     * only when even that does not fit (very large text in a very short window) do the tiles stay three in a row, a name
     * with a word too wide for its third then on one line ending in an ellipsis (TileFit.nameLines), never broken inside
     * the word, so nothing runs past the top area's bottom edge.
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
            // The names a little smaller, together, before any wraps (JustBackFit.nameScale): one line each, as the mock-up.
            if (MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED) {
                float[] widths = new float[cells.length];
                for (int i = 0; i < cells.length; i++) widths[i] = cells[i].nameWidthAtFull();
                float scale = JustBackFit.nameScale(widths, third - first.horizontalPadding() - 1, first.nameFull, first.nameFloor);
                for (Cell cell : cells) cell.nameScale(scale);
            }
            int layout = TileFit.THREE;
            if (MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED
                    && TileFit.columns(names, first.namePaint()::measureText, third - first.horizontalPadding(), Cell.NAME_LINES) != 3) {
                // Two rows as the tiles are now, then the same with the other padding by its known difference, so the
                // padding changes (and asks for a layout) only when the answer does.
                int now = measureAt(2, width), saving = 2 * first.tightSaving();
                int twoRows = first.isTight() ? now + saving : now, tightHeight = twoRows - saving;
                layout = TileFit.layout(names, first.namePaint()::measureText, third - first.horizontalPadding(), Cell.NAME_LINES,
                        twoRows, tightHeight, maxHeight);
            }
            tight(layout == TileFit.TWO_TIGHT);
            int height = measureAt(layout == TileFit.TWO || layout == TileFit.TWO_TIGHT ? 2 : 3, width);
            setMeasuredDimension(width, resolveSize(height, heightSpec));
        }

        /** The tiles' tight padding (TileFit.TWO_TIGHT) or their usual one. */
        private void tight(boolean on) {
            for (Cell cell : cells) cell.tight(on);
        }

        /** The width of tile i with count tiles in a row (the last spans the row in two columns). */
        private int cellWidth(int i, int count, int width) {
            int span = count == 3 ? 1 : i == cells.length - 1 && cells.length % 2 == 1 ? 2 : 1;
            return Math.max(0, span == 2 ? width : (width - (count - 1) * gap) / count);
        }

        /** Measures the cells with count tiles in a row and returns the height they take. */
        private int measureAt(int count, int width) {
            columns = count;
            // One value size for all the tiles, as the mock-up (review of #390): a long value no longer shrinks alone.
            float[] fits = new float[cells.length];
            for (int i = 0; i < cells.length; i++) fits[i] = cells[i].fitSize(cellWidth(i, count, width) - cells[i].horizontalPadding());
            float size = JustBackFit.rowValueSize(cells[0].valueTop, fits);
            for (Cell cell : cells) cell.valueSize(size);
            int height = 0, row = 0;
            for (int i = 0; i < cells.length; i++) {
                int cellWidth = cellWidth(i, count, width);
                // Whole words only: a name with a word wider than its tile takes one line and ends in an ellipsis.
                cells[i].nameLines(TileFit.nameLines(cells[i].label(), cells[i].namePaint()::measureText,
                        cellWidth - cells[i].horizontalPadding(), Cell.NAME_LINES));
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
     * on one line, its number large and its unit small as the mock-up draws "15.5 mph" and "−18%". The row's values take
     * one size, the largest at which each fits its tile, down to the readable floor (JustBackFit.valueSize and
     * rowValueSize), on one baseline, so a unit is never cut;
     * below the floor it takes its short rung (the speed's first unit alone, with units set to both), while the full
     * value is what is spoken.
     */
    private static final class Cell extends LinearLayout {
        static final int NAME_LINES = 2;
        private final TextView name, value;
        private final float valueTop, valueFloor;
        /** The row's value size (px) set by Tiles; 0 before the first. */
        private float rowSize;
        /** The name's full size and the readable floor (px; the floor is not grown by the app's text size). */
        private final float nameFull, nameFloor;
        private final android.text.TextPaint measure = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
        private CharSequence full = "—", shorter;
        /** The usual and the tight (TileFit.TWO_TIGHT) padding above and below, and the gap above the value (px). */
        private final int padding, tightPadding, valueGap, tightValueGap;
        private boolean tight;

        Cell(ScreenHost host, Msg label) {
            super(host.activity());
            Ui ui = host.ui();
            padding = ui.dp(9);
            tightPadding = ui.dp(4);
            valueGap = ui.dp(4);
            tightValueGap = 0;
            setOrientation(VERTICAL);
            // From the top, the value at the bottom: a row's names and values line up whatever a name's line count.
            setGravity(Gravity.TOP);
            setPadding(ui.dp(10), padding, ui.dp(10), padding);
            // The charging moment's tile colour and corner (DashboardScreen.InfoCell), so the row keeps its look.
            setBackground(ui.shape(ui.raised, 14, 0));
            name = ui.caption(label.render());
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, JustBackFit.NAME_SP * ui.textScale);
            nameFull = name.getTextSize();
            nameFloor = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, Ui.READABLE_FLOOR_SP, getResources().getDisplayMetrics());
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
            valueSize.topMargin = valueGap;
            addView(value, valueSize);
        }

        boolean isTight() { return tight; }

        /** How much shorter one row of tiles is with the tight padding (px). */
        int tightSaving() { return 2 * (padding - tightPadding) + (valueGap - tightValueGap); }

        /** The tight padding (TileFit.TWO_TIGHT: two rows in a short window) or the usual one. */
        void tight(boolean on) {
            if (tight == on) return;
            tight = on;
            int vertical = on ? tightPadding : padding;
            setPadding(getPaddingLeft(), vertical, getPaddingRight(), vertical);
            ((LinearLayout.LayoutParams) value.getLayoutParams()).topMargin = on ? tightValueGap : valueGap;
        }

        /** The most lines the name takes (TileFit.nameLines). */
        void nameLines(int lines) {
            if (name.getMaxLines() != lines) name.setMaxLines(lines);
        }

        /** The tile's name as shown. */
        String label() { return name.getText().toString(); }

        Paint namePaint() { return name.getPaint(); }

        /** The name's width at its full size (px), measured on a copy of its paint. */
        float nameWidthAtFull() {
            measure.set(name.getPaint());
            measure.setTextSize(nameFull);
            return measure.measureText(label());
        }

        /** The name at this share of its full size (JustBackFit.nameScale). */
        void nameScale(float scale) {
            float size = nameFull * scale;
            if (Math.abs(name.getTextSize() - size) > .25f) name.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
        }

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

        /**
         * The size this tile's value would take on its own in room (px): its full value's fit, else its short rung's,
         * else the floor. Tiles takes the smallest of the row's and gives it back with valueSize.
         */
        float fitSize(float room) {
            float size = fit(full, room);
            if (size < 0 && shorter != null) size = fit(shorter, room);
            return size < 0 ? valueFloor : size;
        }

        /** The row's value size (px), from Tiles; 0 lets the tile pick its own. */
        void valueSize(float size) { rowSize = size; }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            if (MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED) {
                float room = MeasureSpec.getSize(widthSpec) - horizontalPadding();
                float size = rowSize > 0 ? rowSize : fitSize(room);
                // The full value when it fits at the row's size, else its short rung (ellipsized at the floor at worst).
                CharSequence pick = full;
                if (shorter != null && widthAt(full, size) > room) pick = shorter;
                if (!pick.toString().contentEquals(value.getText())) value.setText(pick);
                if (Math.abs(value.getTextSize() - size) > .5f) value.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
                // The baseline where a full-size value's would be: the box is aligned at its bottom, and a smaller
                // size has a smaller descent, so the difference goes below it as padding.
                int lift = Math.max(0, Math.round(descentAt(valueTop) - descentAt(value.getTextSize())));
                if (value.getPaddingBottom() != lift) value.setPadding(0, 0, 0, lift);
            }
            super.onMeasure(widthSpec, heightSpec);
        }

        /** The value's font descent at size s (px). */
        private float descentAt(float s) {
            measure.set(value.getPaint());
            measure.setTextSize(s);
            return measure.getFontMetrics().descent;
        }

        /** text's width at size s (px), spans and the bold overhang's slack included, as fit measures it. */
        private float widthAt(CharSequence text, float s) {
            measure.set(value.getPaint());
            measure.setTextSize(s);
            return android.text.Layout.getDesiredWidth(text, measure) + 2;
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

    /**
     * The route on a rounded panel in the card colour, with its start and end marked; a route icon while there is none.
     * With a route, the round basemap switch (BasemapSwitch) sits in the panel's bottom corner on the end side (the X
     * takes the top one), and the route is fitted clear of it: where the usual fit would put a fix, a stretch of the
     * route or a dot's ring under the switch's 48 dp target, the route is fitted again in the room beside it
     * (JustBackBasemap.touches), so the switch never covers the end dot. Switched on (JustBackBasemap.KEY, the card's own
     * preference, off by default), map tiles from the ride maps' provider (MapSource) lie behind the route, one zoom
     * finer than the ride maps draw them (TILE_DP), with its credit in the other bottom corner drawn over the route and a
     * dark casing under the route: the layer and each tile fade in, never pop, and in the dark theme the tiles are drawn
     * dark (JustBackBasemap.darkTiles), so no white map flashes up on a dark Home. Until a tile arrives, and without a
     * network, the panel stays as it is off, with no message.
     */
    private static final class RouteView extends FrameLayout {
        private final ScreenHost host;
        private final Ui ui;
        private final SharedPreferences settings;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG), tilePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final android.text.TextPaint creditPaint = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF(), tileBox = new RectF();
        private final Path path = new Path(), panelShape = new Path();
        private float[] route;
        /** The route's map area, {west, north, east, south} in Web Mercator world units (JustBackBasemap.box), or null. */
        private double[] area;
        private final BasemapSwitch toggle;
        /** The preference as shown, and the tile layer's fade: from fadeFrom at fadeStart toward fadeTo. */
        private boolean basemap;
        private float fadeFrom, fadeTo;
        private long fadeStart;
        /** The privacy explanation the ride maps share, while it shows for this switch (MapPreferences.setJustBack). */
        private android.app.AlertDialog prompt;
        private MapSource source;
        private MapTiles provider;
        private final android.util.LruCache<String, Bitmap> tiles = new android.util.LruCache<String, Bitmap>(8 * 1024 * 1024) {
            @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
        };
        private final java.util.Set<String> pending = new java.util.HashSet<>();
        private final java.util.Map<String, Long> arrivals = new java.util.HashMap<>(), failed = new java.util.HashMap<>();
        /** Answers to requests made before a provider change or a detach go nowhere. */
        private volatile long generation;
        private boolean attached;
        /** Tile requests sent (the validation's proof that switched on fetches, switched off never does). */
        private int requests;
        /** Whether the last frame drew a map tile (then the casing and the credit show). */
        private boolean mapShown;
        /** The last frame's fit: the outline's origin (px) and scale (px per outline unit). */
        private final float[] fit = new float[3];
        /** The dark theme's tile colours (JustBackBasemap.darkTiles); null in the light theme, which draws tiles as they are. */
        private final ColorFilter tileColors;
        private final SharedPreferences.OnSharedPreferenceChangeListener changed = (p, key) -> {
            if (key == null || key.equals(JustBackBasemap.KEY)) showBasemap(true);
            if (key == null || key.startsWith("map_")) followSource();
        };

        RouteView(ScreenHost host) {
            super(host.activity());
            this.host = host;
            ui = host.ui();
            settings = host.appSettings();
            setWillNotDraw(false);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            tileColors = ui.dark ? new ColorMatrixColorFilter(JustBackBasemap.darkTiles(DARK_SATURATION, DARK_CONTRAST, DARK_LIFT)) : null;
            creditPaint.setTypeface(ui.regular());
            toggle = new BasemapSwitch(host, this::flip);
            toggle.setVisibility(GONE);
            addView(toggle, new FrameLayout.LayoutParams(ui.dp(48), ui.dp(48), Gravity.BOTTOM | Gravity.END));
            basemap = MapPreferences.justBackEnabled(settings);
            fadeFrom = fadeTo = basemap ? 1 : 0;
            toggle.show(basemap);
        }

        /** The panel: the card colour, as the tiles' on the dark page and one step darker than the light page. */
        private int panelColor() { return ui.dark ? ui.surface : ui.raised; }

        /** The route's outline (RidesScreen.readThumb) and its map area (null without one). */
        void setRoute(float[] value, double[] where) {
            route = value;
            area = where != null && where.length == 4 && !Double.isNaN(where[0]) ? where.clone() : null;
            updateSwitch();
            invalidate();
        }

        private boolean hasRoute() { return route != null && route.length >= 4; }

        /** The switch shows with a route that has a map area, on a panel tall enough to draw it. */
        private void updateSwitch() {
            int shown = hasRoute() && area != null && (getHeight() == 0 || getHeight() >= ui.dp(MIN_ROUTE_DP)) ? VISIBLE : GONE;
            if (toggle.getVisibility() != shown) toggle.setVisibility(shown);
        }

        /** A tap on the switch: the other state, through the shared explanation the first time it turns on. */
        private void flip() {
            if (prompt != null && prompt.isShowing()) return;
            boolean next = !basemap;
            prompt = MapPreferences.setJustBack(host, next, () -> showBasemap(true));
            if (prompt == null) Haptics.toggle(toggle, next);
        }

        /** Follows the preference: the switch at once, the tile layer fading to it (animate) or at once. */
        private void showBasemap(boolean animate) {
            boolean on = MapPreferences.justBackEnabled(settings);
            toggle.show(on);
            if (on == basemap) return;
            basemap = on;
            long now = SystemClock.uptimeMillis();
            fadeFrom = animate && Ui.motionEnabled() ? layer(now) : (on ? 1 : 0);
            fadeTo = on ? 1 : 0;
            fadeStart = now;
            if (on) failed.clear();
            invalidate();
        }

        /** The tile layer's opacity now, 0 to 1. */
        private float layer(long now) {
            if (fadeFrom == fadeTo) return fadeTo;
            float progress = (now - fadeStart) / (float) TILE_FADE_MS;
            if (progress >= 1 || !Ui.motionEnabled()) { fadeFrom = fadeTo; return fadeTo; }
            return fadeFrom + (fadeTo - fadeFrom) * Motion.FADE.getInterpolation(Math.max(0, progress));
        }

        /** The provider the ride maps use, followed when it changes (Maps settings, a restore): its tiles start afresh. */
        private void followSource() {
            MapSource now = MapSource.load(settings);
            if (source != null && now.url.equals(source.url) && now.attribution.equals(source.attribution)) return;
            source = now;
            provider = null;
            generation++;
            pending.clear(); failed.clear(); arrivals.clear(); tiles.evictAll();
            invalidate();
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            attached = true;
            settings.registerOnSharedPreferenceChangeListener(changed);
            showBasemap(false);
            followSource();
        }

        @Override protected void onDetachedFromWindow() {
            settings.unregisterOnSharedPreferenceChangeListener(changed);
            attached = false;
            generation++;
            pending.clear();
            if (prompt != null) { prompt.dismiss(); prompt = null; }
            super.onDetachedFromWindow();
        }

        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            panelShape.reset();
            panelShape.addRoundRect(0, 0, w, h, ui.dp(20), ui.dp(20), Path.Direction.CW);
            post(this::updateSwitch);
        }

        @Override protected void onDraw(Canvas canvas) {
            mapShown = false;
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
            if (!hasRoute()) {
                ui.drawIcon(canvas, "route", Ui.withAlpha(ui.muted, 140), getWidth() / 2f, getHeight() / 2f, ui.dp(22));
                return;
            }
            float inset = Math.min(ui.dp(18), getHeight() / 5f);
            float left = inset, top = inset, right = getWidth() - inset, bottom = getHeight() - inset;
            if (!fitInto(left, top, right, bottom)) return;
            // Clear of the basemap switch: fitted again beside it when the usual fit reaches under it.
            if (toggle.getVisibility() == VISIBLE) {
                float[] corner = {toggle.getLeft(), toggle.getTop(), toggle.getRight(), toggle.getBottom()};
                if (JustBackBasemap.touches(route, fit[0], fit[1], fit[2], corner, ui.dp(DOT_RING_DP))) {
                    if (corner[0] >= getWidth() / 2f) right = Math.min(right, corner[0] - ui.dp(DOT_RING_DP + 1));
                    else left = Math.max(left, corner[2] + ui.dp(DOT_RING_DP + 1));
                    if (!fitInto(left, top, right, bottom)) return;
                }
            }
            float ox = fit[0], oy = fit[1], scale = fit[2];
            float layer = layer(SystemClock.uptimeMillis());
            if (layer > 0 && area != null) mapShown = drawTiles(canvas, layer);
            if (layer > 0 && layer < 1) postInvalidateOnAnimation();
            float sx = ox + route[2] * scale, sy = oy + route[3] * scale;
            float ex = ox + route[route.length - 2] * scale, ey = oy + route[route.length - 1] * scale;
            if (route.length > 4 && scale > 0) {
                path.reset();
                path.moveTo(sx, sy);
                for (int i = 4; i + 1 < route.length; i += 2) path.lineTo(ox + route[i] * scale, oy + route[i + 1] * scale);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeJoin(Paint.Join.ROUND);
                if (mapShown) {
                    // Over a map, a dark casing keeps the route clear of the map's own reds and oranges (as RouteMapView's).
                    paint.setStrokeWidth(ui.dp(7));
                    paint.setColor(Ui.withAlpha(CASING, Math.round(255 * layer)));
                    canvas.drawPath(path, paint);
                }
                paint.setStrokeWidth(ui.dp(4));
                paint.setColor(ui.accent);
                canvas.drawPath(path, paint);
            }
            paint.setStyle(Paint.Style.FILL);
            // As the mock-up: a green start dot and a white end dot (the text colour on the light panel, where white
            // would vanish), each ringed in the panel's colour.
            // A loop ends where it started: the end's white dot then rings the start's smaller green one.
            boolean together = Math.hypot(ex - sx, ey - sy) < ui.dp(10);
            paint.setColor(panel);
            canvas.drawCircle(sx, sy, ui.dp(DOT_RING_DP), paint);
            canvas.drawCircle(ex, ey, ui.dp(DOT_RING_DP), paint);
            paint.setColor(ui.dark ? Color.WHITE : ui.text);
            canvas.drawCircle(ex, ey, ui.dp(5), paint);
            paint.setColor(ui.ok);
            canvas.drawCircle(sx, sy, ui.dp(together ? 3 : 5), paint);
            // The credit last, over the route (owner, Nov 19 2026: "draw the credit over"), so it always reads whole.
            if (mapShown) drawCredit(canvas, layer);
        }

        /**
         * Fits the outline into the box, centred and as large as it goes: fit gets its origin and scale (0 for a ride
         * with no extent, drawn at the centre). False when the box has no room.
         */
        private boolean fitInto(float left, float top, float right, float bottom) {
            float w = right - left, h = bottom - top;
            if (w <= 0 || h <= 0) return false;
            float scale = Math.min(w / Math.max(1e-4f, route[0]), h / Math.max(1e-4f, route[1]));
            if (route[0] < 1e-4f && route[1] < 1e-4f) scale = 0;
            fit[0] = left + (w - route[0] * scale) / 2;
            fit[1] = top + (h - route[1] * scale) / 2;
            fit[2] = scale;
            return true;
        }

        /**
         * The tiles under the panel at the zoom the route's fit gives (JustBackBasemap.world and tileZoom), each faded in
         * on arrival, inside the panel's rounded shape, asking for the missing ones. True when one was drawn.
         */
        private boolean drawTiles(Canvas canvas, float layer) {
            if (source == null) followSource();
            double tilePx = ui.dp(TILE_DP);
            double world = JustBackBasemap.world(route, area, fit[2], tilePx);
            int zoom = JustBackBasemap.tileZoom(world, tilePx, MapSource.maximumTileZoom(source.url));
            if (zoom < 0) return false;
            if (provider == null) provider = new MapTiles(getContext(), source.url);
            // The area's box is where the outline's is drawn: its centre on the outline's centre.
            double centreX = fit[0] + route[0] * fit[2] / 2.0, centreY = fit[1] + route[1] * fit[2] / 2.0;
            double left = (area[0] + area[2]) / 2 * world - centreX, top = (area[1] + area[3]) / 2 * world - centreY;
            double size = world / Math.pow(2, zoom);
            int n = 1 << zoom;
            int startX = (int) Math.floor(left / size), endX = (int) Math.floor((left + getWidth()) / size);
            int startY = (int) Math.floor(top / size), endY = (int) Math.floor((top + getHeight()) / size);
            long now = SystemClock.uptimeMillis();
            boolean drew = false, fading = false;
            canvas.save();
            canvas.clipPath(panelShape);
            tilePaint.setColorFilter(tileColors);
            for (int ty = Math.max(0, startY); ty <= Math.min(n - 1, endY); ty++)
                for (int tx = startX; tx <= endX; tx++) {
                    int wrapX = ((tx % n) + n) % n;
                    String key = zoom + "/" + wrapX + "/" + ty;
                    Bitmap tile = tiles.get(key);
                    if (tile == null) { request(key, zoom, wrapX, ty); continue; }
                    int alpha = tileAlpha(key, now);
                    fading |= alpha < 255;
                    tilePaint.setAlpha(Math.round(alpha * layer));
                    tileBox.set((float) (tx * size - left), (float) (ty * size - top), (float) ((tx + 1) * size - left), (float) ((ty + 1) * size - top));
                    canvas.drawBitmap(tile, null, tileBox, tilePaint);
                    drew = true;
                }
            canvas.restore();
            if (fading) postInvalidateOnAnimation();
            return drew;
        }

        /** A newly arrived tile's opacity (0 to 255), fading up over TILE_FADE_MS. */
        private int tileAlpha(String key, long now) {
            Long arrived = arrivals.get(key);
            if (arrived == null) return 255;
            float progress = (now - arrived) / (float) TILE_FADE_MS;
            if (progress >= 1 || !Ui.motionEnabled()) { arrivals.remove(key); return 255; }
            return Math.round(255 * Motion.FADE.getInterpolation(Math.max(0, progress)));
        }

        /** Asks the provider for one tile off the main thread, while the switch is on; a failed tile waits a minute. */
        private void request(String key, int z, int x, int y) {
            if (!attached || !basemap || provider == null || pending.contains(key) || pending.size() >= 24) return;
            Long failedAt = failed.get(key);
            if (failedAt != null && SystemClock.elapsedRealtime() - failedAt < 60_000) return;
            pending.add(key);
            requests++;
            long asked = generation;
            MapTiles from = provider;
            try {
                RouteMapView.TILES.execute(() -> {
                    Bitmap result = asked == generation ? from.get(z, x, y) : null;
                    post(() -> {
                        if (asked != generation) return;
                        pending.remove(key);
                        if (result != null) {
                            tiles.put(key, result);
                            if (Ui.motionEnabled()) arrivals.put(key, SystemClock.uptimeMillis());
                        } else failed.put(key, SystemClock.elapsedRealtime());
                        if (attached) invalidate();
                    });
                });
            } catch (java.util.concurrent.RejectedExecutionException stopped) {
                pending.remove(key);
            }
        }

        /** The provider's credit in the bottom corner the switch leaves, on the maps' plate, as the ride maps show it. */
        private void drawCredit(Canvas canvas, float layer) {
            String credit = source.displayAttribution();
            creditPaint.setTextSize(Math.min(ui.sp(10.5f), ui.dp(15)));
            float pad = ui.dp(6), margin = ui.dp(6);
            float room = getWidth() - ui.dp(48) - 2 * margin - 2 * pad;
            if (room <= 0) return;
            String shown = TextUtils.ellipsize(credit, creditPaint, room, TextUtils.TruncateAt.END).toString();
            Paint.FontMetrics metrics = creditPaint.getFontMetrics();
            float width = creditPaint.measureText(shown) + 2 * pad, height = metrics.descent - metrics.ascent + ui.dp(4);
            boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
            float left = rtl ? getWidth() - margin - width : margin, top = getHeight() - margin - height;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Ui.withAlpha(ui.dark ? ui.bg : ui.text, Math.round(205 * layer)));
            box.set(left, top, left + width, top + height);
            canvas.drawRoundRect(box, ui.dp(7), ui.dp(7), paint);
            creditPaint.setColor(Ui.withAlpha(ui.dark ? ui.text : ui.surface, Math.round(255 * layer)));
            canvas.drawText(shown, left + pad, top + ui.dp(2) - metrics.ascent, creditPaint);
        }

        /** The mock-up's dot grid: a faint dot every GRID_DP, GRID_INSET_DP in from the panel's edges. */
        private static final int GRID_DP = 14, GRID_INSET_DP = 12;
    }

    /**
     * The round basemap switch in the route panel's corner: a 30 dp disc in a 48 dp target, as the X's, holding a folded
     * map; filled with the accent while on, outlined while off. Spoken as a switch named Basemap (the ride maps' name for
     * it) with its state. Its taps are its own: the card under it does not open the ride.
     */
    private static final class BasemapSwitch extends FrameLayout {
        private final Ui ui;
        private final GradientDrawable disc = new GradientDrawable();
        private boolean on;

        BasemapSwitch(ScreenHost host, Runnable flip) {
            super(host.activity());
            ui = host.ui();
            setWillNotDraw(false);
            disc.setShape(GradientDrawable.OVAL);
            setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(ui.accent, 35)),
                    new InsetDrawable(disc, ui.dp((48 - CLOSE_DISC_DP) / 2)), null));
            String name = MapPreferences.basemapName();
            setContentDescription(name);
            setTooltipText(name);
            setTag(TAG_BASEMAP);
            setClickable(true);
            setFocusable(true);
            setOnClickListener(v -> flip.run());
            setAccessibilityDelegate(new AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View view, android.view.accessibility.AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(view, info);
                    info.setClassName(android.widget.Switch.class.getName());
                    info.setCheckable(true);
                    info.setChecked(on);
                }
            });
            show(false);
        }

        boolean isOn() { return on; }

        void show(boolean value) {
            on = value;
            disc.setColor(value ? ui.accent : ui.dark ? ui.raised : ui.surface);
            disc.setStroke(value ? 0 : ui.dp(1), ui.outline);
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            ui.drawIcon(canvas, "map", on ? ui.onAccent : ui.muted, getWidth() / 2f, getHeight() / 2f, ui.dp(16));
        }
    }
}
