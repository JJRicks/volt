package com.jjricks.volt;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A detailed live picture of one Home style (owner's answers 9, 10 and 12 on #383: real screenshots, or a much more
 * detailed preview than the first skeleton). It is drawn, not captured: the app's own colours for the current theme,
 * the scooter's real photo (ScooterPhotos; none for a model with no bundled photo, as on Home, and the UT5 MAX's photo
 * as a stand-in before a model is known), the scooter's name,
 * and sample readings formatted in the owner's units. Clean shows its header with the search and Settings buttons,
 * the battery card (charge, range, photo, bar, odometer), the Lock bar, the owner's four quick actions (this scooter's
 * defaults until they change them; round buttons with the short names Clean shows under them), the subject rows with
 * their names and the tab bar. Widgets follows the default phone Home: its header with the RIDE pill, the overview
 * card, the Lock bar, reading tiles (power, temperature, battery health), this model's Setting tiles, the Ride
 * recorder and the tab bar.
 * Words with no sample value (status, labels) are drawn as soft bars, as at a thumbnail's size.
 *
 * <p>Everything is drawn on a phone-shaped canvas of {@link #VIRTUAL_WIDTH} by {@link #VIRTUAL_HEIGHT} dp, scaled to
 * the view, so it keeps the real screen's proportions at any size. Its name for screen readers comes from HubText;
 * the drawing itself is one image. The maintainers may swap it for screenshots from their standard set.
 * Used by setup's last step, Settings › Home style, Clean's Edit sheet and the What's new sheet.
 */
final class HomeStylePreview extends View {
    /** The phone-sized canvas the picture is laid out on, in dp; the view scales it to fit. */
    static final float VIRTUAL_WIDTH = 320, VIRTUAL_HEIGHT = 568;
    /** Never taller than this, whatever width it is given. */
    private static final int MAX_HEIGHT_DP = 300;
    /** Nor taller than this share of the window's height. */
    private static final float WINDOW_SHARE = .4f;
    /** The stand-in photo before a scooter is known, decoded once. */
    private static Bitmap fallbackPhoto;

    private final Ui ui;
    private final boolean clean;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint words = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint photoPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF box = new RectF();
    /** The phone's own bounds, kept apart from box, which the drawing reuses for its parts (council early review, note 6). */
    private final RectF frame = new RectF();
    /** The frame's rounded shape, which the screen's content is clipped to so it never shows past the corners. */
    private final Path clip = new Path();
    private final Bitmap photo;
    /** The scooter's name, or "" (a bar is drawn). */
    private final String name;
    /** Sample values in the owner's units. */
    private final String charge, range, odometer, power, temperature, health;
    /** Clean's four quick actions for this scooter (icon, name, id), and the icons of the Widgets Home's Setting tiles. */
    private final List<String[]> quick = new ArrayList<>();
    private final List<String> settingTiles = new ArrayList<>();
    /** The subjects' names, in Clean's order. */
    private final List<String> subjects = new ArrayList<>();
    private final List<String> subjectIcons = new ArrayList<>();

    HomeStylePreview(ScreenHost host, boolean clean) {
        super(host.activity());
        this.ui = host.ui();
        this.clean = clean;
        Context context = host.activity();
        ScooterService service = host.service();
        ScooterProfiles.Profile profile = service == null ? null : service.profiles.active();
        DeviceDescriptor model = profile == null ? null : profile.savedDescriptor();
        // The stand-in only before a model is known: a known model with no bundled photo shows none, as on Home.
        photo = model == null ? fallback(context) : ScooterPhotos.get(context.getResources(), model, 4);
        // The name Clean's header shows (DashboardScreen.headerName: the simulated model's while simulating).
        String header = DashboardScreen.headerName(service, profile);
        name = header != null ? header : profile == null ? "" : profile.displayName();
        SharedPreferences settings = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        boolean metric = "km".equals(settings.getString("units", "mi"));
        charge = Fmt.percent(Fmt.integerNumber(82)).toString();
        range = distance(metric ? 50 : 31, metric, 0);
        odometer = metric ? grouped(2008, UnitCode.KM) : grouped(1248, UnitCode.MI);
        power = Fmt.quantity(Fmt.integerNumber(412), Fmt.unit(UnitCode.WATT)).toString();
        temperature = DisplayTemperature.format(31.0, settings.getString("temperature_unit", "c"), 0);
        health = Fmt.percent(Fmt.integerNumber(96)).toString();
        // The owner's own four, as Clean shows them (CleanHomeScreen.loadQuick), or this scooter's defaults.
        String stored = profile == null ? null : settings.getString(HomeQuickActions.key(profile.id), null);
        for (String id : HomeQuickActions.load(stored, HomeQuickDefaults.forScooter(service))) {
            QuickAccessActions.Entry entry = HomeQuickActions.entry(id);
            if (entry != null) quick.add(new String[] {QuickAccessGrid.roundGlyph(id, entry.icon), QuickAccessGrid.roundName(entry), id});
        }
        for (String id : model == null ? ModelTiles.DEFAULT : ModelTiles.settingTiles(model)) {
            QuickAccessActions.Entry entry = QuickAccessActions.find(id);
            if (entry != null) settingTiles.add(entry.icon);
        }
        for (String subject : HubPages.SUBJECTS) {
            subjects.add(HubText.title(subject).render());
            subjectIcons.add(HubPages.icon(subject));
        }
        setContentDescription(HubText.homeStylePreview(clean).render());
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    private static String distance(double value, boolean metric, int decimals) {
        return Fmt.quantity(decimals == 0 ? Fmt.integerNumber(Math.round(value)) : Fmt.decimalNumber(Locale.US, value, decimals),
                Fmt.unit(metric ? UnitCode.KM : UnitCode.MI)).toString();
    }

    private static String grouped(long value, UnitCode unit) {
        return Fmt.quantity(Fmt.number(Fmt.groupedInteger(Locale.US, value)), Fmt.unit(unit)).toString();
    }

    private static synchronized Bitmap fallback(Context context) {
        if (fallbackPhoto != null) return fallbackPhoto;
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 8;
            fallbackPhoto = BitmapFactory.decodeResource(context.getResources(), R.drawable.ut5max, options);
        } catch (RuntimeException | OutOfMemoryError unavailable) {
            fallbackPhoto = null;
        }
        return fallbackPhoto;
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        // Never more than 40% of the window, so the buttons under the choice stay in view on a landscape phone.
        int cap = Math.min(ui.dp(MAX_HEIGHT_DP), Math.round(getResources().getDisplayMetrics().heightPixels * WINDOW_SHARE));
        int height = Math.min(cap, Math.round(width * VIRTUAL_HEIGHT / VIRTUAL_WIDTH));
        setMeasuredDimension(width, resolveSize(height, heightSpec));
    }

    @Override protected void onDraw(Canvas canvas) {
        float h = getHeight(), w = Math.min(getWidth(), h * VIRTUAL_WIDTH / VIRTUAL_HEIGHT);
        if (w <= 0 || h <= 0) return;
        float s = w / VIRTUAL_WIDTH;
        float left = (getWidth() - w) / 2f, top = (h - VIRTUAL_HEIGHT * s) / 2f;
        // The phone: the page's background inside a rounded frame.
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(ui.bg);
        frame.set(left, top, left + w, top + VIRTUAL_HEIGHT * s);
        float corner = ui.dp(12);
        canvas.drawRoundRect(frame, corner, corner, paint);
        canvas.save();
        clip.reset();
        clip.addRoundRect(frame, corner, corner, Path.Direction.CW);
        canvas.clipPath(clip);
        canvas.translate(left, top);
        canvas.scale(s, s);
        if (clean) drawClean(canvas);
        else drawWidgets(canvas);
        canvas.restore();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(ui.dp(1.5f));
        paint.setColor(ui.outline);
        canvas.drawRoundRect(frame, corner, corner, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    // ===== Clean =====

    private void drawClean(Canvas c) {
        // Header as Clean draws it (part 8b): the connection line, then the scooter's name with search and Settings (no
        // Edit button: a long-press on the quick actions edits them).
        paint.setColor(ui.ok);
        c.drawCircle(20, 24, 4, paint);
        bar(c, 30, 21, 128, 6);
        title(c, 16, 70, 196, 22);
        roundButton(c, 252, 62, "search");
        roundButton(c, 288, 62, "gear");
        // The top area: the battery card.
        card(c, 16, 90, 304, 262, 20, ui.surface);
        text(c, charge, 30, 140, 140, 40, ui.text, true);
        text(c, range, 31, 164, 130, 14, ui.muted, false);
        photo(c, 150, 98, 298, 206);
        meter(c, 30, 216, 290, .82f);
        bar(c, 30, 232, 54, 5);
        text(c, odometer, 30, 252, 120, 13, ui.text, true);
        bar(c, 164, 232, 50, 5);
        bar(c, 164, 243, 64, 8, ui.text);
        // The Lock bar.
        lockBar(c, 16, 274, 304, 326);
        // The four quick actions as Clean's round buttons (part 8b): a grey circle, one short name under it.
        float cell = (288 - 24) / 4f;
        for (int i = 0; i < Math.min(4, quick.size()); i++) {
            String[] action = quick.get(i);
            float x = 16 + i * (cell + 8);
            paint.setColor(ui.raised);
            c.drawCircle(x + cell / 2, 362, 22, paint);
            ui.drawIcon(c, action[0], ui.text, x + cell / 2, 362, 20);
            centred(c, action[1], x + cell / 2, 401, cell + 4, 11, ui.muted, false);
        }
        // The subjects: icon, name, a summary line and the chevron.
        card(c, 16, 418, 304, VIRTUAL_HEIGHT + 20, 18, ui.surface);
        float y = 418;
        for (int i = 0; i < subjects.size() && y < VIRTUAL_HEIGHT; i++) {
            if (i > 0) {
                paint.setColor(ui.outline);
                c.drawRect(60, y, 304, y + 1, paint);
            }
            ui.drawIcon(c, subjectIcons.get(i), ui.accent, 38, y + 26, 18);
            text(c, subjects.get(i), 60, y + 24, 200, 14, ui.text, false);
            bar(c, 60, y + 33, 70 + (i * 37) % 60, 5);
            ui.drawIcon(c, "chevron", ui.muted, 286, y + 26, 13);
            y += 52;
        }
        // Clean still has the tab bar until the dock replaces it.
        tabBar(c);
    }

    // ===== Widgets =====

    private void drawWidgets(Canvas c) {
        // Header: the scooter's name, the RIDE pill and the pencil.
        title(c, 16, 42, 166, 22);
        card(c, 192, 20, 268, 48, 14, ui.accent);
        ui.drawIcon(c, "speed", ui.onAccent, 208, 34, 14);
        String ride = quickName(HomeQuickActions.RIDING);
        words.setLetterSpacing(.06f);
        text(c, ride, 220, 38, 44, 11, ui.onAccent, true);
        words.setLetterSpacing(0);
        roundButton(c, 288, 34, "pencil");
        // The battery widget, two columns wide.
        card(c, 16, 62, 304, 192, 20, ui.surface);
        text(c, charge, 30, 108, 140, 36, ui.text, true);
        text(c, range, 31, 130, 130, 13, ui.muted, false);
        photo(c, 156, 70, 296, 166);
        meter(c, 30, 176, 290, .82f);
        // The Lock bar.
        lockBar(c, 16, 202, 304, 250);
        // Reading tiles: power, temperature and battery health, as on the default Home.
        String[] values = {power, temperature, health};
        String[] icons = {"bolt", "temperature", "heart"};
        float third = (288 - 16) / 3f;
        for (int i = 0; i < values.length; i++) {
            float x = 16 + i * (third + 8);
            card(c, x, 260, x + third, 330, 18, ui.surface);
            ui.drawIcon(c, icons[i], ui.muted, x + 18, 278, 14);
            bar(c, x + 30, 275, third - 44, 6);
            text(c, values[i], x + 12, 316, third - 20, 17, ui.text, true);
        }
        // Setting tiles: this model's (maximum speed and charge timing by default).
        int count = Math.max(1, settingTiles.size());
        float tile = (288 - 8 * (count - 1)) / (float) count;
        for (int i = 0; i < settingTiles.size(); i++) {
            float x = 16 + i * (tile + 8);
            card(c, x, 340, x + tile, 388, 16, ui.surface);
            paint.setColor(ui.raised);
            c.drawCircle(x + 26, 364, 14, paint);
            ui.drawIcon(c, settingTiles.get(i), ui.accent, x + 26, 364, 16);
            bar(c, x + 48, 356, Math.min(80, tile - 64), 6, ui.text);
            bar(c, x + 48, 367, Math.min(52, tile - 64), 5);
        }
        // The Ride recorder: the record button and its words.
        card(c, 16, 398, 304, 452, 18, ui.surface);
        paint.setColor(ui.accent);
        c.drawCircle(43, 425, 15, paint);
        ui.drawIcon(c, "record", ui.onAccent, 43, 425, 16);
        bar(c, 68, 416, 96, 7, ui.text);
        bar(c, 68, 429, 128, 5);
        tabBar(c);
    }

    /** The tab bar, floating over the bottom of the page: Home selected, the Scooter tab raised in the middle. */
    private void tabBar(Canvas c) {
        float dockTop = VIRTUAL_HEIGHT - 66, dockBottom = VIRTUAL_HEIGHT - 12;
        paint.setColor(Ui.withAlpha(ui.bg, 200));
        c.drawRect(0, dockTop - 12, VIRTUAL_WIDTH, VIRTUAL_HEIGHT, paint);
        card(c, 16, dockTop, 304, dockBottom, 27, ui.surface);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1);
        paint.setColor(ui.raised);
        box.set(16, dockTop, 304, dockBottom);
        c.drawRoundRect(box, 27, 27, paint);
        paint.setStyle(Paint.Style.FILL);
        String[] tabs = {"home", "stats", "scooter", "rides", "more"};
        float step = 288 / 5f, middle = (dockTop + dockBottom) / 2;
        for (int i = 0; i < tabs.length; i++) {
            float x = 16 + step * (i + .5f);
            if (i == 2) {
                paint.setColor(ui.accent);
                c.drawCircle(x, middle - 6, 25, paint);
                ui.drawIcon(c, tabs[i], ui.onAccent, x, middle - 6, 22);
                continue;
            }
            ui.drawIcon(c, tabs[i], i == 0 ? ui.text : ui.muted, x, middle - 4, 18);
            if (i == 0) {
                paint.setColor(ui.accent);
                c.drawCircle(x, middle + 13, 2.5f, paint);
            }
        }
    }

    /** The shown name of one of Clean's own actions, or "". */
    private static String quickName(String id) {
        Msg label = HubText.quickActionLabel(id);
        return label == null ? "" : label.render();
    }

    // ===== Pieces, in the canvas's dp =====

    private void card(Canvas c, float l, float t, float r, float b, float radius, int color) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        box.set(l, t, r, b);
        float most = Math.min(radius, Math.min(r - l, b - t) / 2);
        c.drawRoundRect(box, most, most, paint);
    }

    /** A word with no sample value: a soft bar the height of its text. */
    private void bar(Canvas c, float x, float y, float width, float height) {
        bar(c, x, y, width, height, ui.muted);
    }

    private void bar(Canvas c, float x, float y, float width, float height, int color) {
        card(c, x, y, x + width, y + height, height / 2, Ui.withAlpha(color, ui.dark ? 90 : 70));
    }

    private void roundButton(Canvas c, float cx, float cy, String icon) {
        paint.setColor(ui.dark ? ui.surface : ui.raised);
        c.drawCircle(cx, cy, 16, paint);
        ui.drawIcon(c, icon, ui.text, cx, cy, 15);
    }

    /** The scooter's name, or a bar when there is none yet. */
    private void title(Canvas c, float x, float baseline, float width, float size) {
        if (name.isEmpty()) bar(c, x, baseline - size * .7f, width * .6f, size * .7f, ui.text);
        else text(c, name, x, baseline, width, size, ui.text, true);
    }

    private void text(Canvas c, String value, float x, float baseline, float width, float size, int color, boolean bold) {
        if (value == null || value.isEmpty()) return;
        words.setTypeface(bold ? ui.bold() : ui.medium());
        words.setTextSize(size);
        words.setColor(color);
        words.setTextAlign(Paint.Align.LEFT);
        CharSequence shown = TextUtils.ellipsize(value, words, width, TextUtils.TruncateAt.END);
        c.drawText(shown, 0, shown.length(), x, baseline, words);
    }

    private void centred(Canvas c, String value, float cx, float baseline, float width, float size, int color, boolean bold) {
        if (value == null || value.isEmpty()) return;
        words.setTypeface(bold ? ui.bold() : ui.medium());
        words.setTextSize(size);
        words.setColor(color);
        words.setTextAlign(Paint.Align.CENTER);
        CharSequence shown = TextUtils.ellipsize(value, words, width, TextUtils.TruncateAt.END);
        c.drawText(shown, 0, shown.length(), cx, baseline, words);
        words.setTextAlign(Paint.Align.LEFT);
    }

    /** The battery bar: a muted track with the charge in the accent colour. */
    private void meter(Canvas c, float l, float t, float r, float level) {
        card(c, l, t, r, t + 7, 3.5f, Ui.withAlpha(ui.muted, 60));
        card(c, l, t, l + (r - l) * level, t + 7, 3.5f, ui.accent);
    }

    /** The scooter's photo, fitted inside the box and kept in proportion. */
    private void photo(Canvas c, float l, float t, float r, float b) {
        if (photo == null || photo.getWidth() <= 0 || photo.getHeight() <= 0) return;
        float scale = Math.min((r - l) / photo.getWidth(), (b - t) / photo.getHeight());
        float pw = photo.getWidth() * scale, ph = photo.getHeight() * scale;
        box.set(r - pw, t + (b - t - ph) / 2, r, t + (b - t - ph) / 2 + ph);
        c.drawBitmap(photo, null, box, photoPaint);
    }

    /** The Lock bar: a padlock disc at the start and its words. */
    private void lockBar(Canvas c, float l, float t, float r, float b) {
        float height = b - t;
        card(c, l, t, r, b, height / 2, ui.surface);
        paint.setColor(ui.raised);
        c.drawCircle(l + height / 2, t + height / 2, height / 2 - 6, paint);
        ui.drawIcon(c, "lock", ui.text, l + height / 2, t + height / 2, 18);
        bar(c, l + height + 10, t + height / 2 - 4, 92, 8, ui.text);
        bar(c, r - 66, t + height / 2 - 3, 46, 6);
    }
}
