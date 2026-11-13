package com.jjricks.volt;

import android.animation.ValueAnimator;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * The Hub's search page (hub layout plan 1.12, PR 12), opened by Home's search button: one field that finds any page,
 * setting or control, live reading (with its value now) or ride by name, each result with the subject it lives in.
 * Tapping a result opens its page, which brings the row on screen and flashes it once (MainActivity); a reading opens
 * Data's Readings filtered to it, a ride opens it in All rides. While the field is empty the page lists Home's seven
 * subjects. HubSearch decides what matches and in what order; MainActivity's controls rules (HubSearch.Gates) decide
 * which controls the model offers, so search never lists one its page would not show. Every word comes from HubText
 * (or is the name the found row already shows), so this file holds no catalog references.
 */
final class HubSearchScreen implements AppScreen {
    /** View tags: the field, the result count, the results, and each result ("hub-search-" and HubSearch.Entry.id()). */
    static final String TAG_FIELD = "hub-search-field", TAG_COUNT = "hub-search-count", TAG_RESULTS = "hub-search-results";
    static String tag(HubSearch.Entry entry) { return "hub-search-" + entry.id(); }

    /** Opens a result's page (MainActivity: the page, its anchor, a ride, a reading or a control's try). */
    interface Opener { void open(HubSearch.Entry entry); }

    private final ScreenHost host;
    private final Ui ui;
    private final HubSearch.Gates gates;
    private final Consumer<String> remember;
    private final Opener opener;
    private final LinearLayout view, results;
    /** Named like the other search fields (validations find a screen's field by this name). */
    private final EditText search;
    private final TextView count;
    private final CleanSubjects subjects;
    private String query;
    /** The result rows shown, so a refresh writes their values again without rebuilding them. */
    private final List<ResultRow> values = new ArrayList<>();
    private final List<HubSearch.Entry> shown = new ArrayList<>();
    /** What the model offers as last searched; a change (connecting, another model) runs the search again. */
    private String offeredKey = "";
    /** The rides as last listed (data only; their lines are written when shown); null until listed. */
    private List<RideStore.Summary> rides;
    private boolean ridesLoading, closed;
    /** Whether a ride was recording when the rides were listed: a change lists them again (#393 review, note 5). */
    private boolean ridesRecording;

    /** query: the text to start with (kept across recreation and Back); remember hears every change. */
    HubSearchScreen(ScreenHost host, HubSearch.Gates gates, String query, Consumer<String> remember, Opener opener) {
        this.host = host;
        this.ui = host.ui();
        this.gates = gates;
        this.remember = remember;
        this.opener = opener;
        this.query = query == null ? "" : query;
        view = new LinearLayout(host.activity());
        view.setOrientation(LinearLayout.VERTICAL);
        search = new EditText(host.activity());
        search.setSingleLine(true);
        search.setHint(HubText.searchHint());
        ui.styleSearch(search);
        // TalkBack names the field as Home's search button does (the field it replaced had its own description).
        search.setContentDescription(HubText.searchDescription());
        search.setTag(TAG_FIELD);
        search.setText(this.query);
        view.addView(search, ui.searchLayout());
        SearchFocus.bind(search, host.activity().getWindow());
        count = ui.caption("");
        count.setTag(TAG_COUNT);
        count.setPadding(ui.dp(3), 0, 0, ui.dp(8));
        count.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        view.addView(count, new LinearLayout.LayoutParams(-1, -2));
        results = new LinearLayout(host.activity());
        results.setOrientation(LinearLayout.VERTICAL);
        results.setTag(TAG_RESULTS);
        view.addView(results, new LinearLayout.LayoutParams(-1, -2));
        subjects = new CleanSubjects(host);
        view.addView(subjects.view(), new LinearLayout.LayoutParams(-1, -2));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int before, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int after) {
                String now = s.toString();
                if (now.equals(HubSearchScreen.this.query)) return;
                HubSearchScreen.this.query = now;
                if (remember != null) remember.accept(now);
                applyFilter();
            }
            @Override public void afterTextChanged(Editable text) { }
        });
        applyFilter();
    }

    @Override public View view() { return view; }

    @Override public void refresh() {
        if (closed) return;
        subjects.refresh();
        if (HubSearch.normalize(query).isEmpty()) return;
        if (!offered().equals(offeredKey)) { applyFilter(); return; }
        // A ride that started or ended while the results show: list the rides again (the one now saved joins them).
        ScooterService service = host.service();
        if (rides != null && service != null && service.isRecording() != ridesRecording) { rides = null; applyFilter(); return; }
        writeValues();
    }

    @Override public void close() {
        closed = true;
        subjects.close();
    }

    /** Back first clears a search, as the other search fields do. */
    @Override public boolean back() {
        if (query.isEmpty()) return false;
        search.setText("");
        return true;
    }

    /** A search being typed keeps riding mode from opening over it. */
    @Override public boolean busy() { return !query.isEmpty(); }

    /** The pages, controls and rows the model offers now, as one key (a change re-runs the search). */
    private String offered() {
        StringBuilder key = new StringBuilder();
        for (HubSearch.Entry entry : HubSearch.pages(gates)) key.append(entry.page).append(';');
        for (HubSearch.Entry entry : HubSearch.controls(gates, HubText::experimentalBadge)) key.append(entry.page).append(entry.anchor).append(entry.value != null).append(';');
        for (HubSearch.Entry entry : HubSearch.rows(gates)) key.append(entry.anchor).append(';');
        return key.toString();
    }

    /** Everything search can find right now, in the index's order (the order inside a rank). */
    private List<HubSearch.Entry> index() {
        List<HubSearch.Entry> index = new ArrayList<>(HubSearch.pages(gates));
        index.addAll(HubSearch.controls(gates, HubText::experimentalBadge));
        index.addAll(HubSearch.rows(gates));
        index.addAll(HubSearch.settings());
        index.addAll(StatsScreen.readingEntries(host));
        index.addAll(rideEntries());
        return index;
    }

    private void applyFilter() {
        if (closed) return;
        results.removeAllViews();
        values.clear();
        shown.clear();
        boolean searching = !HubSearch.normalize(query).isEmpty();
        subjects.view().setVisibility(searching ? View.GONE : View.VISIBLE);
        results.setVisibility(searching ? View.VISIBLE : View.GONE);
        count.setVisibility(searching ? View.VISIBLE : View.GONE);
        if (!searching) { count.setText(""); return; }
        offeredKey = offered();
        List<HubSearch.Entry> found = HubSearch.search(index(), query);
        count.setText(found.isEmpty() ? HubText.searchNone() : HubText.searchCount(found.size()));
        HubSearch.Kind group = null;
        LinearLayout card = null;
        for (HubSearch.Entry entry : found) {
            if (entry.kind != group) {
                group = entry.kind;
                TextView label = ui.sectionLabel(groupTitle(group).render());
                if (android.os.Build.VERSION.SDK_INT >= 28) label.setAccessibilityHeading(true);
                results.addView(label);
                card = ui.groupCard();
                results.addView(card, new LinearLayout.LayoutParams(-1, -2));
            } else {
                ui.divider(card);
            }
            card.addView(row(entry), new LinearLayout.LayoutParams(-1, -2));
        }
        writeValues();
    }

    private static Msg groupTitle(HubSearch.Kind kind) {
        switch (kind) {
            case PAGE: return HubText.searchGroupPages();
            case READING: return HubText.searchGroupReadings();
            case RIDE: return HubText.searchGroupRides();
            default: return HubText.searchGroupSettings();
        }
    }

    /**
     * A result: its icon and name, the subject it lives in, its value at the end (or under the name when it is too wide
     * to sit beside it), a saved reading's age, and a chevron; it opens its page.
     */
    private View row(HubSearch.Entry entry) {
        ResultRow line = new ResultRow();
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setBaselineAligned(false);
        line.setMinimumHeight(ui.dp(56));
        line.setPadding(0, ui.dp(8), 0, ui.dp(8));
        line.setTag(tag(entry));
        LinearLayout words = new LinearLayout(host.activity());
        words.setOrientation(LinearLayout.VERTICAL);
        TextView name = ui.text(entry.shown, 15, false);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        ui.decorate(name, HubPages.icon(entry.page));
        words.addView(name, new LinearLayout.LayoutParams(-1, -2));
        Msg where = where(entry);
        if (where != null) {
            TextView caption = ui.caption(where.render());
            caption.setMaxLines(1);
            caption.setEllipsize(TextUtils.TruncateAt.END);
            caption.setPaddingRelative(ui.dp(42), ui.dp(2), 0, 0);
            words.addView(caption, new LinearLayout.LayoutParams(-1, -2));
        }
        // The value under the name: used when it is too wide to sit beside it (HubSearch.valueBelow).
        line.below = ui.caption("");
        line.below.setMaxLines(3);
        line.below.setEllipsize(TextUtils.TruncateAt.END);
        line.below.setPaddingRelative(ui.dp(42), ui.dp(2), 0, 0);
        line.below.setVisibility(View.GONE);
        words.addView(line.below, new LinearLayout.LayoutParams(-1, -2));
        // A saved reading's age, under the name and the value, as Live Stats writes it beside a saved value.
        line.note = ui.caption("");
        line.note.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11 * ui.textScale);
        line.note.setMaxLines(2);
        line.note.setPaddingRelative(ui.dp(42), ui.dp(2), 0, 0);
        line.note.setVisibility(View.GONE);
        words.addView(line.note, new LinearLayout.LayoutParams(-1, -2));
        line.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        line.beside = ui.caption("");
        line.beside.setMaxLines(2);
        line.beside.setGravity(Gravity.END);
        line.beside.setVisibility(View.GONE);
        LinearLayout.LayoutParams valueSize = new LinearLayout.LayoutParams(-2, -2);
        valueSize.setMarginStart(ui.dp(10));
        line.addView(line.beside, valueSize);
        LinearLayout.LayoutParams chevronSize = new LinearLayout.LayoutParams(ui.dp(15), ui.dp(15));
        chevronSize.setMarginStart(ui.dp(8));
        line.addView(ui.icon("chevron", 15, ui.muted), chevronSize);
        line.setClickable(true);
        line.setFocusable(true);
        line.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(ui.accent, 26)), null, ui.shape(Color.WHITE, 10, 0)));
        line.setOnClickListener(v -> {
            if (closed) return;
            Haptics.tick(v);
            // The field's keyboard would cover the page that opens.
            search.clearFocus();
            opener.open(entry);
        });
        values.add(line);
        shown.add(entry);
        return line;
    }

    /**
     * A result row whose value sits beside the name while it fits in HubSearch.VALUE_SHARE of the row, and under the name
     * otherwise, so the name always keeps its room (#393 review, finding 1: a raw serial answer, a ride's date and
     * distance at large text).
     */
    private final class ResultRow extends LinearLayout {
        TextView beside, below, note;
        /** The value as last written; null or empty for none. */
        CharSequence value;

        ResultRow() { super(host.activity()); }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            boolean has = value != null && value.length() > 0;
            boolean under = false;
            if (has && MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED) {
                // The row's content: its width less the chevron and the value's margin.
                int content = MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight() - ui.dp(15 + 8 + 10);
                under = HubSearch.valueBelow(android.text.Layout.getDesiredWidth(value, beside.getPaint()), content);
            }
            show(beside, has && !under);
            show(below, has && under);
            super.onMeasure(widthSpec, heightSpec);
        }

        private void show(TextView view, boolean on) {
            if (on && !TextUtils.equals(view.getText(), value)) view.setText(value);
            int state = on ? View.VISIBLE : View.GONE;
            if (view.getVisibility() != state) view.setVisibility(state);
        }

        void write(CharSequence now, Integer colour, CharSequence age) {
            boolean changed = !TextUtils.equals(now == null ? "" : now.toString(), value == null ? "" : value.toString());
            value = now;
            if (changed) { beside.setText(now); below.setText(now); }
            if (colour != null) { beside.setTextColor(colour); below.setTextColor(colour); }
            boolean dated = age != null && age.length() > 0;
            if (dated && !TextUtils.equals(note.getText(), age)) note.setText(age);
            note.setVisibility(dated ? View.VISIBLE : View.GONE);
            if (changed) requestLayout();
        }
    }

    /**
     * "In <subject>": the subject (or Settings) every result lives in, a row on a subject's own page included ("In
     * Controls", a reading "In Data"); null only for a page result that is that subject (or Settings) itself.
     */
    private static Msg where(HubSearch.Entry entry) {
        String subject = HubPages.subjectOf(entry.page);
        if (subject.equals(HubPages.HOME)) return null;
        if (entry.kind == HubSearch.Kind.PAGE && subject.equals(entry.page)) return null;
        Msg title = HubText.title(subject);
        return title == null ? null : HubText.searchIn(title);
    }

    /** Writes each shown result's value as it is now (a live reading's value changes while the results show). */
    private void writeValues() {
        for (int i = 0; i < shown.size(); i++) {
            HubSearch.Entry entry = shown.get(i);
            CharSequence now = entry.value == null ? null : entry.value.get();
            // As written (a raw reading keeps Live Stats' RAW chip); a reading's value as Live Stats colours it: live in the
            // text colour, a saved one muted, with its age under it (shown, so TalkBack reads it with the row).
            Integer colour = entry.live == null ? null : Boolean.TRUE.equals(entry.live.get()) ? ui.text : ui.muted;
            CharSequence age = entry.note == null || now == null || now.length() == 0 ? null : entry.note.get();
            values.get(i).write(now, colour, age);
        }
    }

    /**
     * The rides, newest first (RideStore.listCached; with nothing cached, one list() on a worker, and the search runs
     * again when it is done). A ride being recorded is left out. Its name, and when it was ridden with its distance.
     */
    private List<HubSearch.Entry> rideEntries() {
        List<HubSearch.Entry> entries = new ArrayList<>();
        ScooterService service = host.service();
        if (service == null || service.rides == null) return entries;
        boolean recording = service.isRecording();
        if (recording != ridesRecording) { ridesRecording = recording; rides = null; }
        if (rides == null) rides = service.rides.listCached();
        if (rides == null) { loadRides(service.rides); return entries; }
        String units = service.settings == null ? "mi" : service.settings.getString("units", "mi");
        Locale locale = Fmt.locale(Locale.getDefault());
        java.text.SimpleDateFormat format = new java.text.SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, "yMMMd"), locale);
        for (RideStore.Summary ride : rides) {
            if (ride.active) continue;
            String name = RidesScreen.nameOf(ride);
            String date = format.format(new java.util.Date(ride.startWallMs));
            String distance = ride.distanceKm == null || !Double.isFinite(ride.distanceKm) ? null : RideText.distance(ride.distanceKm, units).toString();
            String value = distance == null ? date : date + " · " + distance;
            entries.add(HubSearch.ride(ride.id, ride.label + " " + date, name, () -> value));
        }
        return entries;
    }

    private void loadRides(RideStore store) {
        if (ridesLoading) return;
        ridesLoading = true;
        Thread worker = new Thread(() -> {
            List<RideStore.Summary> listed;
            try { listed = store.list(); } catch (IOException | RuntimeException e) { listed = java.util.Collections.emptyList(); }
            List<RideStore.Summary> done = listed;
            host.activity().runOnUiThread(() -> {
                ridesLoading = false;
                if (closed) return;
                rides = done;
                if (!HubSearch.normalize(query).isEmpty()) applyFilter();
            });
        }, "hub-search-rides");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Brings attention to the row a result opened (MainActivity, once its page is laid out): one soft flash of the
     * accent over the row. None with reduced motion: the row is already scrolled into view.
     */
    static void flash(View target, Ui ui) {
        if (target == null || !Motion.enabled()) return;
        Drawable previous = target.getForeground();
        GradientDrawable glow = new GradientDrawable();
        glow.setCornerRadius(ui.dp(10));
        glow.setColor(Ui.withAlpha(ui.accent, 70));
        glow.setAlpha(0);
        target.setForeground(glow);
        ValueAnimator pulse = ValueAnimator.ofFloat(0f, 1f, 0f);
        pulse.setDuration(900);
        pulse.setInterpolator(Motion.FADE);
        pulse.addUpdateListener(a -> glow.setAlpha(Math.round(255 * (float) a.getAnimatedValue())));
        pulse.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) { if (target.getForeground() == glow) target.setForeground(previous); }
        });
        pulse.start();
    }
}
