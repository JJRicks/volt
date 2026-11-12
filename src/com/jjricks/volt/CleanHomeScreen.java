package com.jjricks.volt;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Clean Home (hub layout plan 1.5, PR 8): the fixed Home, beside the widget dashboard rather than inside it. Top to
 * bottom: the header (status line, search and Settings; the scooter's name, which switches scooter, and Edit), a top
 * area of fixed height whose content follows the moment (the battery card, the charging view while charging, or the
 * last ride for about an hour after it ends: JustBackView), the Lock bar, four quick actions and the seven subjects.
 *
 * <p>It never reads or writes the widget dashboard's storage: its four quick actions live in
 * {@code home_quick_v1_<profile id>} in app_settings (HomeQuickActions), its moments follow {@code home_moment_*} (HomeMoment). The
 * header's words, the battery card and the charging view are DashboardScreen's own (DashboardScreen.CleanParts); every
 * other word comes from HubText, so this file holds no catalog references.
 *
 * <p>On a wide window (hub layout plan 1.11, PR 13) the same Home is the scooter pane at the left of two (pane mode):
 * the subjects move to the page pane beside it (HubHomePane) with the search field, so the header leaves out its search
 * button, and the Service card (HubServiceCard) fills the room left below the quick actions. Page navigation happens in
 * the other pane; this one is built once per rebuild and never changes while browsing.
 */
final class CleanHomeScreen implements AppScreen {
    /** View tags: the header's buttons, the top area, the lock slot, the quick actions and the Edit sheet's slots. */
    static final String TAG_SEARCH = "home-search", TAG_SETTINGS = "home-settings", TAG_EDIT = "home-edit",
            TAG_NAME = "home-switch-scooter", TAG_TOP = "home-top-area", TAG_LOCK = "home-lock", TAG_QUICK = "home-quick-actions",
            TAG_SLOT = "home-edit-slot-";
    /** One column at most this wide on a wide window where the two-pane Home does not fit (plan 1.11). */
    private static final int MAX_WIDTH_DP = 560;
    private static final int CROSSFADE_MS = 180;

    private final ScreenHost host;
    private final DashboardScreen.Actions actions;
    private final Ui ui;
    private final SharedPreferences preferences;
    private final DashboardScreen.CleanParts parts;
    private final LinearLayout view, page, header;
    private final FrameLayout top;
    private final View overview, charging;
    private final JustBackView justBack;
    /** The three moments' views, by HomeMoment constant. */
    private final View[] moments;
    /** Reads the route of the last ride from its phone journal, off the UI thread; stopped on close. */
    private final java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
    /** The newest finished ride, or null (none, not read yet, or no ride store). */
    private RideStore.Summary lastRide;
    /** When Clean last looked at the saved rides (elapsed realtime), or -1; and whether recording was on then. */
    private long ridesLooked = -1;
    private boolean recordingWhenLooked, listing;
    /** The ride whose route was asked for, so each ride's route is read once. */
    private String routeAsked;
    private final QuickAccessGrid quick;
    /** The seven subjects (one-pane Home), or the Service card (the wide Home's scooter pane); the other is null. */
    private final CleanSubjects subjects;
    private final HubServiceCard serviceCard;
    /** The wide Home's scooter pane (pane mode). */
    private final boolean pane;
    private final List<String> quickIds;
    /** The scooter's model as known when this Home was built; the default quick actions follow it. */
    private final String modelIdentity;
    /**
     * The default four for the scooter as known when this Home was built (owner's answer 11 on #383: they follow
     * what the scooter supports, HomeQuickDefaults); untouched defaults follow when that changes.
     */
    private final List<String> defaults;
    private int moment = -1;
    private boolean closed;
    private android.app.AlertDialog editSheet;

    CleanHomeScreen(ScreenHost host, DashboardScreen.Actions actions) { this(host, actions, false); }

    /** pane: the wide Home's scooter pane (see the class comment); false is the one-pane Home. */
    CleanHomeScreen(ScreenHost host, DashboardScreen.Actions actions, boolean pane) {
        this.host = host;
        this.actions = actions;
        this.pane = pane;
        ui = host.ui();
        preferences = host.activity().getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        parts = DashboardScreen.cleanParts(host, actions);
        view = new MaxWidth(host.activity(), ui.dp(MAX_WIDTH_DP));
        page = column();
        // The scooter pane fills its pane's height, so the Service card can take the room left at its foot.
        view.addView(page, new LinearLayout.LayoutParams(-1, pane ? -1 : -2));

        header = column();
        header.setPadding(0, ui.dp(4), 0, ui.dp(10));
        LinearLayout first = row();
        first.setGravity(Gravity.CENTER_VERTICAL);
        TextView status = parts.status();
        first.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        // The wide Home's page pane has the search field (and this button's tag) instead.
        if (!pane) first.addView(round("search", HubText.searchDescription(), TAG_SEARCH, actions::openSearch), new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        first.addView(round("gear", HubText.settingsDescription(), TAG_SETTINGS, actions::openSettings), new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        header.addView(first, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout second = row();
        second.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = parts.name();
        name.setTag(TAG_NAME);
        name.setClickable(true);
        name.setFocusable(true);
        name.setMinHeight(ui.dp(48));
        name.setGravity(Gravity.CENTER_VERTICAL);
        name.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(ui.accent, 26)), null, ui.shape(Color.WHITE, 10, 0)));
        name.setOnClickListener(v -> actions.switchScooter());
        second.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams editSize = new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48));
        editSize.setMarginStart(ui.dp(8));
        second.addView(round("pencil", HubText.editTitle(), TAG_EDIT, this::showEdit), editSize);
        header.addView(second, new LinearLayout.LayoutParams(-1, -2));
        page.addView(header, new LinearLayout.LayoutParams(-1, -2));

        // The top area: one height for every moment, so the Lock bar and the quick actions never move.
        top = new FrameLayout(host.activity());
        top.setTag(TAG_TOP);
        overview = parts.overview();
        charging = parts.chargingView();
        justBack = new JustBackView(host, this::openRide, this::dismissRide);
        moments = new View[] {overview, charging, justBack};
        top.addView(overview, new FrameLayout.LayoutParams(-1, -1));
        top.addView(charging, new FrameLayout.LayoutParams(-1, -1));
        top.addView(justBack, new FrameLayout.LayoutParams(-1, -1));
        charging.setVisibility(View.GONE);
        justBack.setVisibility(View.GONE);
        page.addView(top, new LinearLayout.LayoutParams(-1, topHeight()));

        FrameLayout lock = new FrameLayout(host.activity());
        lock.setTag(TAG_LOCK);
        actions.clearLock();
        lock.addView(actions.lock(false, true), new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams lockSize = new LinearLayout.LayoutParams(-1, lockHeight());
        lockSize.topMargin = ui.dp(12);
        page.addView(lock, lockSize);

        ScooterService built = host.service();
        modelIdentity = parts.identity(built == null ? null : built.profiles.active());
        defaults = HomeQuickDefaults.forScooter(built);
        quickIds = loadQuick();
        quick = new QuickAccessGrid(host, quickIds, this::quickAction, HomeQuickActions::entry);
        quick.setTag(TAG_QUICK);
        LinearLayout.LayoutParams quickSize = new LinearLayout.LayoutParams(-1, -2);
        quickSize.topMargin = ui.dp(12);
        page.addView(quick, quickSize);

        if (pane) {
            subjects = null;
            serviceCard = new HubServiceCard(host);
            // Fills what the pane has left; with large text the pane scrolls and the card keeps its own height.
            LinearLayout.LayoutParams cardSize = new LinearLayout.LayoutParams(-1, -2, 1);
            cardSize.topMargin = ui.dp(16);
            page.addView(serviceCard.view(), cardSize);
        } else {
            serviceCard = null;
            subjects = new CleanSubjects(host);
            LinearLayout.LayoutParams subjectSize = new LinearLayout.LayoutParams(-1, -2);
            subjectSize.topMargin = ui.dp(16);
            page.addView(subjects.view(), subjectSize);
        }
        refresh();
    }

    @Override public View view() { return view; }

    /**
     * Tabletop posture: the header and the top area move to the pane above the hinge, as the widget dashboard's
     * header does; the Lock bar, quick actions and subjects stay below it. The same views keep updating.
     */
    View takeHeader() {
        LinearLayout pane = column();
        for (View part : new View[] {header, top}) {
            ViewGroup.LayoutParams params = part.getLayoutParams();
            page.removeView(part);
            pane.addView(part, params);
        }
        return pane;
    }

    @Override public void refresh() {
        if (closed) return;
        ScooterService service = host.service();
        ScooterProfiles.Profile current = service == null ? null : service.profiles.active();
        if (!parts.profileId().equals(current == null ? "unconfigured" : current.id)) { host.rebuild(); return; }
        // This scooter's model or what it offers became known (or changed): untouched default quick actions follow
        // it, as the widget dashboard's default tiles do. Not while Edit is open; the four stored by the owner never
        // change here.
        if (current != null && (editSheet == null || !editSheet.isShowing())
                && preferences.getString(HomeQuickActions.key(current.id), null) == null
                && (!modelIdentity.equals(parts.identity(current)) || !defaults.equals(HomeQuickDefaults.forScooter(service)))) {
            host.rebuild();
            return;
        }
        parts.refresh();
        boolean recording = service != null && service.isRecording();
        lookForRide(service, recording);
        long now = System.currentTimeMillis();
        RideStore.Summary ride = lastRide;
        Object dismissed = stored(HomeMoment.DISMISSED_KEY);
        int next = HomeMoment.choose(HomeMoment.on(stored(HomeMoment.CHARGING_KEY)), parts.charging(),
                HomeMoment.on(stored(HomeMoment.RIDE_KEY)), recording, ride == null ? null : ride.id,
                ride == null ? 0 : ride.shownEndWallMs, dismissed instanceof String ? (String) dismissed : null, now);
        if (next == HomeMoment.JUST_BACK) {
            justBack.bind(ride, preferences.getString("units", "mi"), now);
            loadRoute(service, ride);
        }
        show(next);
        String name = parts.name().getText().toString();
        parts.name().setContentDescription(HubText.switchScooterDescription(name).render());
        quick.refresh();
        if (subjects != null) subjects.refresh();
        if (serviceCard != null) serviceCard.refresh();
    }

    /** Swaps the top area's content: a short crossfade on a change only, never on a telemetry refresh. */
    private void show(int next) {
        if (next == moment) return;
        boolean first = moment < 0;
        int previous = moment;
        moment = next;
        parts.chargingShown(next == HomeMoment.CHARGING);
        View in = moments[next];
        for (View view : moments) view.animate().cancel();
        if (first || !Ui.motionEnabled() || !top.isAttachedToWindow()) {
            for (View view : moments) {
                view.setAlpha(1);
                view.setVisibility(view == in ? View.VISIBLE : View.GONE);
            }
            return;
        }
        // Only the moment leaving fades out; any other one (a crossfade cut short) goes at once.
        View out = moments[previous];
        for (View view : moments) if (view != in && view != out) { view.setVisibility(View.GONE); view.setAlpha(1); }
        in.setAlpha(0);
        in.setVisibility(View.VISIBLE);
        in.animate().alpha(1).setDuration(CROSSFADE_MS).setInterpolator(Motion.STANDARD).start();
        out.animate().alpha(0).setDuration(CROSSFADE_MS).setInterpolator(Motion.STANDARD).withEndAction(() -> {
            out.setVisibility(View.GONE);
            out.setAlpha(1);
        }).start();
    }

    // ===== Just back: the last ride =====

    /**
     * Keeps lastRide current: the newest finished ride, read from memory on the UI thread (RideStore.listCached); when
     * a journal would need reading, one list() at a time on the worker, never on the UI thread. Looks again when
     * recording starts or stops, and every half minute otherwise (HomeMoment.lookAgain).
     */
    private void lookForRide(ScooterService service, boolean recording) {
        RideStore store = service == null ? null : service.rides;
        if (store == null) { lastRide = null; return; }
        long now = android.os.SystemClock.elapsedRealtime();
        if (!HomeMoment.lookAgain(ridesLooked, now, recording != recordingWhenLooked)) return;
        ridesLooked = now;
        recordingWhenLooked = recording;
        java.util.List<RideStore.Summary> cached = store.listCached();
        if (cached != null) { lastRide = newestFinished(cached); return; }
        if (listing) return;
        listing = true;
        try {
            worker.execute(() -> {
                RideStore.Summary found;
                boolean read;
                try { found = newestFinished(store.list()); read = true; }
                catch (java.io.IOException | RuntimeException e) { found = null; read = false; }
                RideStore.Summary result = found;
                boolean ok = read;
                host.activity().runOnUiThread(() -> {
                    listing = false;
                    if (closed) return;
                    if (ok) lastRide = result;
                    refresh();
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException stopped) {
            listing = false;
        }
    }

    /** The newest ride that is not being recorded (the list is newest first), or null. */
    private static RideStore.Summary newestFinished(java.util.List<RideStore.Summary> rides) {
        for (RideStore.Summary ride : rides) if (!ride.active) return ride;
        return null;
    }

    /**
     * Reads the ride's route once, on the worker, from its phone journal's GPS fixes (RidesScreen.readThumb), only
     * after the phone has finished saving it. A ride without fixes keeps the plain panel.
     */
    private void loadRoute(ScooterService service, RideStore.Summary ride) {
        if (ride == null || ride.id.equals(routeAsked) || service == null || service.phoneSaving()) return;
        PhoneRideStore phone = service.phoneRides;
        if (phone == null) return;
        routeAsked = ride.id;
        String id = ride.id;
        try {
            worker.execute(() -> {
                float[] outline;
                try { outline = RidesScreen.readThumb(phone.rawCsv(id)); } catch (RuntimeException invalid) { outline = null; }
                if (outline == null) return;
                float[] shown = outline;
                host.activity().runOnUiThread(() -> { if (!closed) justBack.setRoute(id, shown); });
            });
        } catch (java.util.concurrent.RejectedExecutionException stopped) {
            routeAsked = null;
        }
    }

    /** Opens the ride on the Rides page (HubNav.Args.ride names it). */
    private void openRide(String id) {
        if (!closed) host.open(HubPages.RIDE_RIDES, HubNav.Args.ride(id));
    }

    /** The X: this ride's card stays closed (home_just_back_dismissed); a newer ride shows again. */
    private void dismissRide(String id) {
        if (closed) return;
        preferences.edit().putString(HomeMoment.DISMISSED_KEY, id).apply();
        refresh();
    }

    @Override public void close() {
        closed = true;
        for (View view : moments) view.animate().cancel();
        worker.shutdownNow();
        if (editSheet != null && editSheet.isShowing()) editSheet.dismiss();
        editSheet = null;
        parts.close();
        if (subjects != null) subjects.close();
        if (serviceCard != null) serviceCard.close();
    }

    private void quickAction(String id) {
        if (!closed) actions.quickAction(id);
    }

    // ===== Quick actions =====

    /** This scooter's four: the owner's from home_quick_v1_<profile id>, or the defaults while none are stored. */
    private List<String> loadQuick() {
        ScooterService service = host.service();
        ScooterProfiles.Profile profile = service == null ? null : service.profiles.active();
        String stored = profile == null ? null : preferences.getString(HomeQuickActions.key(profile.id), null);
        return new ArrayList<>(HomeQuickActions.load(stored, defaults));
    }

    private void saveQuick(List<String> ids) {
        ScooterService service = host.service();
        ScooterProfiles.Profile profile = service == null ? null : service.profiles.active();
        // Without a scooter there is nowhere to keep them; Edit is not offered then.
        if (profile == null) return;
        preferences.edit().putString(HomeQuickActions.key(profile.id), HomeQuickActions.encode(ids)).apply();
    }

    // ===== Edit sheet: the style, then the four quick actions =====

    private void showEdit() {
        if (closed) return;
        if (editSheet != null && editSheet.isShowing()) editSheet.dismiss();
        LinearLayout content = host.sheetContent(HubText.editTitle(), null, () -> { if (editSheet != null) editSheet.dismiss(); });
        content.addView(HomeStylePicker.build(host, preferences), new LinearLayout.LayoutParams(-1, -2));
        ScooterService service = host.service();
        if (service != null && service.profiles.active() != null) {
            TextView label = ui.sectionLabel(HubText.editQuickActions().render());
            content.addView(label);
            LinearLayout card = ui.groupCard();
            for (int i = 0; i < quickIds.size(); i++) {
                if (i > 0) ui.divider(card);
                card.addView(slot(i));
            }
            content.addView(card, new LinearLayout.LayoutParams(-1, -2));
        }
        editSheet = host.showSheet(content);
    }

    /** One of the four: its icon and name; a tap opens the picker for that position. */
    private View slot(int index) {
        String id = quickIds.get(index);
        QuickAccessActions.Entry entry = HomeQuickActions.entry(id);
        CharSequence label = entry == null ? id : entry.label().render();
        LinearLayout line = row();
        line.setTag(TAG_SLOT + (index + 1));
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setMinimumHeight(ui.dp(56));
        line.setPadding(ui.dp(4), ui.dp(8), ui.dp(4), ui.dp(8));
        line.addView(ui.icon(entry == null ? "tune" : entry.icon, 20, ui.accent), new LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)));
        TextView name = ui.text(label, 15, false);
        name.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams nameSize = new LinearLayout.LayoutParams(0, -2, 1);
        nameSize.setMarginStart(ui.dp(12));
        line.addView(name, nameSize);
        line.addView(ui.icon("chevron", 15, ui.muted), new LinearLayout.LayoutParams(ui.dp(15), ui.dp(15)));
        line.setContentDescription(HubText.editQuickSlot(index + 1, label).render());
        line.setClickable(true);
        line.setFocusable(true);
        line.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(ui.accent, 26)), null, ui.shape(Color.WHITE, 10, 0)));
        line.setOnClickListener(v -> parts.chooseQuickAction(quickIds, index, picked -> {
            ArrayList<String> next = new ArrayList<>(quickIds);
            next.set(index, picked);
            saveQuick(next);
            if (editSheet != null && editSheet.isShowing()) editSheet.dismiss();
            // The new four show on the rebuilt Home.
            host.rebuild();
        }));
        return line;
    }

    // ===== Sizes and small views =====

    /** TopArea's share of this window, with the app's and the system's text scale together. */
    private int topHeight() {
        Configuration configuration = host.activity().getResources().getConfiguration();
        float scale = ui.textScale * configuration.fontScale;
        return TopArea.heightPx(configuration.screenHeightDp, ui.wideLayout(), scale, host.activity().getResources().getDisplayMetrics().density);
    }

    /** One grid row of the widget dashboard's Lock bar: 64 dp grown with large text, less its 10 dp gap. */
    private int lockHeight() {
        float scale = Math.max(1, ui.textScale * host.activity().getResources().getConfiguration().fontScale);
        return Math.round(ui.dp(64) * scale) - ui.dp(10);
    }

    private Object stored(String key) {
        try { return preferences.getAll().get(key); } catch (RuntimeException e) { return null; }
    }

    /** A round icon button: a disc inside a 48 dp target, with its name for screen readers and a long-press tooltip. */
    private View round(String icon, Msg label, String tag, Runnable action) {
        FrameLayout target = new FrameLayout(host.activity());
        GradientDrawable disc = new GradientDrawable();
        disc.setShape(GradientDrawable.OVAL);
        disc.setColor(ui.dark ? ui.surface : ui.raised);
        target.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(ui.accent, 35)), new InsetDrawable(disc, ui.dp(6)), null));
        target.addView(ui.icon(icon, 18, ui.text), new FrameLayout.LayoutParams(ui.dp(18), ui.dp(18), Gravity.CENTER));
        String spoken = label.render();
        target.setContentDescription(spoken);
        target.setTooltipText(spoken);
        target.setTag(tag);
        target.setFocusable(true);
        target.setClickable(true);
        target.setMinimumWidth(ui.dp(48));
        target.setMinimumHeight(ui.dp(48));
        target.setOnClickListener(v -> { if (!closed) action.run(); });
        return target;
    }

    private LinearLayout column() {
        LinearLayout v = new LinearLayout(host.activity());
        v.setOrientation(LinearLayout.VERTICAL);
        return v;
    }

    private LinearLayout row() {
        LinearLayout v = column();
        v.setOrientation(LinearLayout.HORIZONTAL);
        return v;
    }

    /** A column no wider than its maximum, centred in what the page gives it. */
    private static final class MaxWidth extends LinearLayout {
        private final int maximum;

        MaxWidth(Context context, int maximum) {
            super(context);
            this.maximum = maximum;
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_HORIZONTAL);
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec);
            View child = getChildCount() == 0 ? null : getChildAt(0);
            if (child != null) {
                LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) child.getLayoutParams();
                int wanted = width > maximum ? maximum : -1;
                if (params.width != wanted) params.width = wanted;
            }
            super.onMeasure(widthSpec, heightSpec);
        }
    }
}
