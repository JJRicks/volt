package com.jjricks.volt;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
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
 * the scooter's real photo (ScooterPhotos; the UT5 MAX's photo as a stand-in before one is known), the scooter's name,
 * and sample readings formatted in the owner's units. Clean shows its header with the search and Settings buttons,
 * the battery card (charge, range, photo, bar, odometer), the Lock bar, the four quick actions this scooter would get
 * (the red RIDE pill first, with their real names and icons) and the subject rows with their names. Widgets shows its
 * header with the RIDE pill, the battery widget, the Lock bar, the Quick access row, reading widgets and the tab bar.
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
    /** The stand-in photo before a scooter is known, decoded once. */
    private static Bitmap fallbackPhoto;

    private final Ui ui;
    private final boolean clean;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint words = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint photoPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF box = new RectF();
    private final Bitmap photo;
    /** The scooter's name, or "" (a bar is drawn). */
    private final String name;
    /** Sample values in the owner's units. */
    private final String charge, range, odometer, speed, trip, power, voltage;
    /** Clean's four quick actions for this scooter (icon, name), and the Widgets Quick access row's icons. */
    private final List<String[]> quick = new ArrayList<>();
    private final List<String> widgetQuick = new ArrayList<>();
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
        Bitmap own = model == null ? null : ScooterPhotos.get(context.getResources(), model, 4);
        photo = own != null ? own : fallback(context);
        name = profile == null ? "" : profile.displayName();
        SharedPreferences settings = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        boolean metric = "km".equals(settings.getString("units", "mi"));
        charge = Fmt.percent(Fmt.integerNumber(82)).toString();
        range = distance(metric ? 50 : 31, metric, 0);
        odometer = metric ? grouped(2008, UnitCode.KM) : grouped(1248, UnitCode.MI);
        speed = Fmt.quantity(Fmt.decimalNumber(Locale.US, metric ? 20.0 : 12.4, 1), Fmt.unit(metric ? UnitCode.KMH : UnitCode.MPH)).toString();
        trip = distance(metric ? 12.6 : 7.8, metric, 1);
        power = Fmt.quantity(Fmt.integerNumber(412), Fmt.unit(UnitCode.WATT)).toString();
        voltage = Fmt.quantity(Fmt.decimalNumber(Locale.US, 54.2, 1), Fmt.unit(UnitCode.VOLT)).toString();
        for (String id : HomeQuickDefaults.forScooter(service)) {
            QuickAccessActions.Entry entry = HomeQuickActions.entry(id);
            if (entry != null) quick.add(new String[] {entry.icon, entry.label().render(), id});
        }
        for (String id : QuickAccessActions.defaultIds()) {
            QuickAccessActions.Entry entry = QuickAccessActions.find(id);
            if (entry != null) widgetQuick.add(entry.icon);
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
        int height = Math.min(ui.dp(MAX_HEIGHT_DP), Math.round(width * VIRTUAL_HEIGHT / VIRTUAL_WIDTH));
        setMeasuredDimension(width, MeasureSpec.getMode(heightSpec) == MeasureSpec.EXACTLY ? MeasureSpec.getSize(heightSpec) : height);
    }

    @Override protected void onDraw(Canvas canvas) {
        float h = getHeight(), w = Math.min(getWidth(), h * VIRTUAL_WIDTH / VIRTUAL_HEIGHT);
        if (w <= 0 || h <= 0) return;
        float s = w / VIRTUAL_WIDTH;
        float left = (getWidth() - w) / 2f, top = (h - VIRTUAL_HEIGHT * s) / 2f;
        // The phone: the page's background inside a rounded frame.
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(ui.bg);
        box.set(left, top, left + w, top + VIRTUAL_HEIGHT * s);
        float corner = ui.dp(12);
        canvas.drawRoundRect(box, corner, corner, paint);
        canvas.save();
        canvas.clipRect(box);
        canvas.translate(left, top);
        canvas.scale(s, s);
        if (clean) drawClean(canvas);
        else drawWidgets(canvas);
        canvas.restore();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(ui.dp(1.5f));
        paint.setColor(ui.outline);
        canvas.drawRoundRect(box, corner, corner, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    // ===== Clean =====

    private void drawClean(Canvas c) {
        // Header: the connection line, search and Settings; the scooter's name and Edit.
        paint.setColor(ui.ok);
        c.drawCircle(20, 24, 4, paint);
        bar(c, 30, 21, 128, 6);
        roundButton(c, 252, 24, "search");
        roundButton(c, 288, 24, "gear");
        title(c, 16, 70, 220, 22);
        roundButton(c, 288, 62, "pencil");
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
        // The four quick actions: the red RIDE pill into riding mode, then the round grey ones.
        float cell = (288 - 24) / 4f;
        for (int i = 0; i < Math.min(4, quick.size()); i++) {
            String[] action = quick.get(i);
            float x = 16 + i * (cell + 8);
            boolean ride = HomeQuickActions.RIDING.equals(action[2]);
            card(c, x, 338, x + cell, 404, ride ? 33 : 12, ride ? ui.accent : ui.raised);
            ui.drawIcon(c, action[0], ride ? ui.onAccent : ui.accent, x + cell / 2, 360, 20);
            words.setLetterSpacing(ride ? .06f : 0);
            centred(c, action[1], x + cell / 2, 392, cell - 8, 11, ride ? ui.onAccent : ui.text, ride);
            words.setLetterSpacing(0);
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
        // Quick access: four square buttons.
        float cell = (288 - 24) / 4f;
        for (int i = 0; i < Math.min(4, widgetQuick.size()); i++) {
            float x = 16 + i * (cell + 8);
            card(c, x, 260, x + cell, 312, 16, ui.raised);
            ui.drawIcon(c, widgetQuick.get(i), ui.accent, x + cell / 2, 280, 18);
            bar(c, x + cell / 2 - 18, 298, 36, 5);
        }
        // Reading widgets, two by two.
        String[] values = {speed, trip, power, voltage};
        String[] icons = {"speed", "route", "power", "bolt"};
        for (int i = 0; i < values.length; i++) {
            float x = i % 2 == 0 ? 16 : 165, y = 322 + (i / 2) * 82;
            card(c, x, y, x + 139, y + 72, 18, ui.surface);
            ui.drawIcon(c, icons[i], ui.muted, x + 22, y + 20, 14);
            bar(c, x + 34, y + 17, 52, 6);
            text(c, values[i], x + 14, y + 56, 116, 20, ui.text, true);
        }
        // The tab bar, floating over the bottom of the page: Home selected, the Scooter tab raised in the middle.
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
