package com.jjricks.volt;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Clean Home's seven subjects (hub layout plan, PR 8), in Home's order: each row has the subject's icon, name and a
 * live one-line summary, and opens the subject's own page (ScreenHost.open with its HubPages id: the pages parts 3-5
 * built, as the Scooter tab's index opens them). While a maintenance check is due, an orange dot leads Service's summary. A
 * summary whose state isn't known shows no line. The scooter subjects' lines come from HubSubjects.summary, the same
 * source as the Scooter tab's index; Ride's and Data's lines are Clean's own (Ride shows the running recording while a
 * ride records, HubSummaries.rideLine). Words from HubText.
 */
final class CleanSubjects {
    /** View tag of a subject's row: "home-subject-" and its page id. */
    static String tag(String subject) { return "home-subject-" + subject; }

    private final ScreenHost host;
    private final Ui ui;
    private final SharedPreferences preferences;
    private final LinearLayout view;
    private final List<String> ids = new ArrayList<>();
    private final List<TextView> names = new ArrayList<>(), values = new ArrayList<>();
    private final List<View> dots = new ArrayList<>();
    private boolean closed;
    /**
     * The newest finished ride and when the rides were last looked at: listing the rides folder waits at least
     * RIDE_EVERY_MS between refreshes. Only the data is kept; its line is written again on every refresh, so a new
     * language, units or day shows at once.
     */
    private boolean ridesKnown;
    private RideStore.Summary lastRide;
    private long rideAt = Long.MIN_VALUE;
    /** A ride was recording at the last refresh: when it ends, the rides are looked at again at once. */
    private boolean wasRecording;
    /** The newest finished ride Clean's top area found (rideFound), or null. */
    private RideStore.Summary foundRide;
    private static final long RIDE_EVERY_MS = 30_000;

    CleanSubjects(ScreenHost host) {
        this.host = host;
        ui = host.ui();
        preferences = host.activity().getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        view = ui.groupCard();
        for (String subject : HubPages.SUBJECTS) {
            if (!ids.isEmpty()) ui.divider(view);
            view.addView(line(subject));
        }
    }

    View view() { return view; }

    private View line(String subject) {
        // The Layout Plan's row (about 54 dp): a 33 dp raised tile with the subject's glyph in the accent's text colour,
        // spanning both lines, then the name over its summary, and the chevron. 48 dp at least, to touch.
        LinearLayout line = new LinearLayout(host.activity());
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setBaselineAligned(false);
        line.setMinimumHeight(ui.dp(48));
        line.setPadding(0, ui.dp(5), 0, ui.dp(5));
        line.setTag(tag(subject));
        FrameLayout tile = new FrameLayout(host.activity());
        tile.setBackground(ui.shape(ui.raised, 10, 0));
        tile.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        tile.addView(ui.icon(HubPages.icon(subject), 18, ui.accentText), new FrameLayout.LayoutParams(ui.dp(18), ui.dp(18), Gravity.CENTER));
        LinearLayout.LayoutParams tileSize = new LinearLayout.LayoutParams(ui.dp(33), ui.dp(33));
        tileSize.setMarginEnd(ui.dp(12));
        line.addView(tile, tileSize);
        LinearLayout words = new LinearLayout(host.activity());
        words.setOrientation(LinearLayout.VERTICAL);
        TextView name = ui.text(HubText.title(subject), 15.5f, false);
        name.setTypeface(ui.medium());
        name.setMaxLines(2);
        name.setIncludeFontPadding(false);
        words.addView(name, new LinearLayout.LayoutParams(-1, -2));
        // The summary line, under the name: Service's due dot leads it ("● Tyre check in 40 mi"), as in the plan.
        LinearLayout summary = new LinearLayout(host.activity());
        summary.setOrientation(LinearLayout.HORIZONTAL);
        summary.setGravity(Gravity.CENTER_VERTICAL);
        summary.setPaddingRelative(0, 0, 0, 0);
        summary.setVisibility(View.GONE);
        View dot = new View(host.activity());
        GradientDrawable disc = new GradientDrawable();
        disc.setShape(GradientDrawable.OVAL);
        disc.setColor(ui.orange);
        dot.setBackground(disc);
        dot.setVisibility(View.GONE);
        LinearLayout.LayoutParams dotSize = new LinearLayout.LayoutParams(ui.dp(8), ui.dp(8));
        dotSize.setMarginEnd(ui.dp(6));
        summary.addView(dot, dotSize);
        TextView value = ui.caption("");
        value.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13 * ui.textScale);
        value.setIncludeFontPadding(false);
        value.setMaxLines(1);
        value.setEllipsize(TextUtils.TruncateAt.END);
        value.setVisibility(View.GONE);
        summary.addView(value, new LinearLayout.LayoutParams(0, -2, 1));
        words.addView(summary, new LinearLayout.LayoutParams(-1, -2));
        line.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams chevronSize = new LinearLayout.LayoutParams(ui.dp(15), ui.dp(15));
        chevronSize.setMarginStart(ui.dp(8));
        line.addView(ui.icon("chevron", 15, ui.muted), chevronSize);
        line.setClickable(true);
        line.setFocusable(true);
        line.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(ui.accent, 26)), null, ui.shape(Color.WHITE, 10, 0)));
        line.setOnClickListener(v -> { if (!closed) host.open(subject); });
        ids.add(subject);
        names.add(name);
        values.add(value);
        dots.add(dot);
        return line;
    }

    void refresh() {
        if (closed) return;
        ScooterService service = host.service();
        for (int i = 0; i < ids.size(); i++) {
            String subject = ids.get(i);
            CharSequence summary = summary(service, subject);
            TextView value = values.get(i);
            if (summary == null || summary.length() == 0) { value.setText(""); value.setVisibility(View.GONE); }
            else {
                if (!TextUtils.equals(summary.toString(), value.getText())) value.setText(summary);
                value.setVisibility(View.VISIBLE);
            }
            boolean due = subject.equals(HubPages.SERVICE) && HubSubjects.serviceDue(service);
            dots.get(i).setVisibility(due ? View.VISIBLE : View.GONE);
            ((View) value.getParent()).setVisibility(due || value.getVisibility() == View.VISIBLE ? View.VISIBLE : View.GONE);
            // TalkBack reads the name and the summary; while a check is due, the name says so, as the tab's dot does.
            names.get(i).setContentDescription(due ? HubText.dueDescription(HubText.title(subject)).render() : null);
        }
    }

    void close() { closed = true; }

    /**
     * The newest finished ride Clean's top area found (CleanHomeScreen.lookForRide, the one Just back shows), so the Ride
     * row shows it as soon as Just back does rather than at its own next look (HubSummaries.takeFoundRide).
     */
    void rideFound(RideStore.Summary found) {
        foundRide = found == null || found.active ? null : found;
    }

    /** A subject's line now, or null when its state isn't known. */
    private CharSequence summary(ScooterService service, String subject) {
        if (subject.equals(HubPages.DATA)) return HubText.dataSummary().render();
        if (service == null) return null;
        if (subject.equals(HubPages.RIDE)) {
            // The rule is HubSummaries': the running recording while a ride records, else the newest finished ride.
            boolean recording = service.isRecording();
            boolean ended = wasRecording && !recording;
            wasRecording = recording;
            long now = android.os.SystemClock.elapsedRealtime();
            if (HubSummaries.rescanRides(recording, ended, ridesKnown, now - rideAt, RIDE_EVERY_MS)) { scanRides(service); rideAt = now; }
            RideStore.Summary found = foundRide;
            if (HubSummaries.takeFoundRide(recording, ridesKnown && lastRide != null ? lastRide.shownEndWallMs : null,
                    found == null ? null : found.shownEndWallMs)) { lastRide = found; ridesKnown = true; }
            if (HubSummaries.rideLine(recording) == HubSummaries.RideLine.RECORDING) return recording(service);
            return ride();
        }
        // The scooter subjects: the same lines and the same rules as the Scooter tab's index (HubSubjects, HubSummaries).
        return HubSubjects.summary(service, subject);
    }

    /**
     * Ride: the newest finished ride's distance and when it ended (today, yesterday or its date), or "No rides yet".
     * Only rides already in memory (RideStore.listCached); with nothing cached yet the line waits for a later refresh.
     */
    private void scanRides(ScooterService service) {
        List<RideStore.Summary> rides = service.rides == null ? null : service.rides.listCached();
        ridesKnown = rides != null;
        lastRide = null;
        if (rides == null) return;
        for (RideStore.Summary ride : rides)
            if (!ride.active && (lastRide == null || ride.shownEndWallMs > lastRide.shownEndWallMs)) lastRide = ride;
    }

    /**
     * Ride's line while a ride records: "Recording · 12:40", its time so far as the widget dashboard's recorder card
     * shows it (0:00 until the recorder has its first summary).
     */
    private static CharSequence recording(ScooterService service) {
        RideStore.Summary summary = service.rides == null ? null : service.rides.activeSummary();
        long seconds = summary == null ? 0 : Math.max(0, summary.elapsedMs / 1000);
        String clock = seconds >= 3600
                ? String.format(Fmt.locale(Locale.US), "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
                : String.format(Fmt.locale(Locale.US), "%d:%02d", seconds / 60, seconds % 60);
        return HubText.rideRecording(clock).render();
    }

    /** Ride's line from the last scan, written now. */
    private CharSequence ride() {
        if (!ridesKnown) return null;
        RideStore.Summary last = lastRide;
        if (last == null) return HubText.rideNone().render();
        if (last.distanceKm == null || !Double.isFinite(last.distanceKm)) return null;
        CharSequence distance = distance(last.distanceKm, preferences.getString("units", "mi"));
        Calendar then = Calendar.getInstance(), day = Calendar.getInstance();
        then.setTimeInMillis(last.shownEndWallMs);
        if (sameDay(then, day)) return HubText.rideLastToday(distance).render();
        day.add(Calendar.DAY_OF_YEAR, -1);
        if (sameDay(then, day)) return HubText.rideLastYesterday(distance).render();
        Locale locale = Fmt.locale(Locale.getDefault());
        String date = new java.text.SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMd"), locale)
                .format(new java.util.Date(last.shownEndWallMs));
        return HubText.rideLastOn(distance, date).render();
    }

    /** A distance at one decimal in the "units" preference, as RideText writes it at two: "6.2 mi", "10.0 km" or both. */
    private static CharSequence distance(double km, String units) {
        Formatted metric = Fmt.quantity(Fmt.decimalNumber(Locale.US, km, 1), Fmt.unit(UnitCode.KM));
        Formatted miles = Fmt.quantity(Fmt.decimalNumber(Locale.US, km / RideStore.KM_PER_MILE, 1), Fmt.unit(UnitCode.MI));
        return "dual".equals(units) ? Fmt.dual(miles, metric) : "km".equals(units) ? metric : miles;
    }

    private static boolean sameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }
}
