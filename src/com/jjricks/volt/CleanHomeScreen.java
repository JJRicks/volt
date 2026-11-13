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
 * bottom: the header (the status line; the scooter's name, which switches scooter, with Edit, search and Settings), a top
 * area of fixed height whose content follows the moment (the battery card, or the charging view while charging), the
 * Lock bar, four quick actions and the seven subjects.
 *
 * <p>It never reads or writes the widget dashboard's storage: its four quick actions live in
 * {@code home_quick_v1_<profile id>} in app_settings (HomeQuickActions), its moments follow {@code home_moment_*} (HomeMoment). The
 * header's words, the battery card and the charging view are DashboardScreen's own (DashboardScreen.CleanParts); every
 * other word comes from HubText, so this file holds no catalog references.
 */
final class CleanHomeScreen implements AppScreen {
    /** View tags: the header's buttons, the top area, the lock slot, the quick actions and the Edit sheet's slots. */
    static final String TAG_SEARCH = "home-search", TAG_SETTINGS = "home-settings", TAG_EDIT = "home-edit",
            TAG_NAME = "home-switch-scooter", TAG_TOP = "home-top-area", TAG_LOCK = "home-lock", TAG_QUICK = "home-quick-actions",
            TAG_SLOT = "home-edit-slot-";
    /** One column at most this wide on a wide window, until the wide two-pane Home arrives (plan 1.11). */
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
    private final QuickAccessGrid quick;
    private final CleanSubjects subjects;
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

    CleanHomeScreen(ScreenHost host, DashboardScreen.Actions actions) {
        this.host = host;
        this.actions = actions;
        ui = host.ui();
        preferences = host.activity().getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        parts = DashboardScreen.cleanParts(host, actions);
        view = new MaxWidth(host.activity(), ui.dp(MAX_WIDTH_DP));
        page = column();
        view.addView(page, new LinearLayout.LayoutParams(-1, -2));

        // The plan's header: the status line, then the scooter's name with Edit, search and Settings beside it, level
        // with the name (CleanHeaderFit). Both are 48 dp targets that nothing covers: the status line's band runs from
        // the header's top down to the name row, its text centred in it (at its foot, the band's empty top read as a
        // blank band of about 44 dp under the window's top); the name row is a 48 dp band. Each button keeps its 48 dp target around a 40 dp disc (the
        // mock-up's ~39 dp). The name gets smaller before it wraps, so a usual name stays on one line beside the three
        // buttons.
        header = column();
        header.setPadding(0, 0, 0, ui.dp(CleanHeaderFit.BOTTOM_DP));
        TextView status = parts.status();
        status.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        status.setPadding(0, ui.dp(CleanHeaderFit.TOP_DP / 2), 0, ui.dp(CleanHeaderFit.TOP_DP / 2));
        status.setMinHeight(ui.dp(CleanHeaderFit.TARGET_DP));
        header.addView(status, new LinearLayout.LayoutParams(-1, -2));
        TextView name = parts.name();
        float nameFull = name.getTextSize();
        android.graphics.Paint namePaint = new android.graphics.Paint(name.getPaint());
        namePaint.setTextSize(nameFull);
        int buttons = ui.dp(CleanHeaderFit.BUTTONS * CleanHeaderFit.TARGET_DP + CleanHeaderFit.BUTTON_GAP_DP);
        LinearLayout second = new LinearLayout(host.activity()) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                // Fit the name to the room the buttons leave, at its full size first (CleanHeaderFit.name).
                float room = Math.max(0, MeasureSpec.getSize(widthSpec) - buttons);
                CleanHeaderFit.Name fit = CleanHeaderFit.name(String.valueOf(name.getText()), room, namePaint::measureText);
                float size = nameFull * fit.scale;
                if (Math.abs(name.getTextSize() - size) > .5f) name.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size);
                super.onMeasure(widthSpec, heightSpec);
            }
        };
        second.setOrientation(LinearLayout.HORIZONTAL);
        second.setGravity(Gravity.BOTTOM);
        name.setTag(TAG_NAME);
        name.setClickable(true);
        name.setFocusable(true);
        name.setMinHeight(ui.dp(CleanHeaderFit.TARGET_DP));
        name.setGravity(Gravity.CENTER_VERTICAL);
        name.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(ui.accent, 26)), null, ui.shape(Color.WHITE, 10, 0)));
        name.setOnClickListener(v -> actions.switchScooter());
        second.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams editSize = new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48));
        editSize.setMarginStart(ui.dp(CleanHeaderFit.BUTTON_GAP_DP));
        second.addView(round("pencil", HubText.editTitle(), TAG_EDIT, this::showEdit), editSize);
        second.addView(round("search", HubText.searchDescription(), TAG_SEARCH, actions::openSearch), new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        second.addView(round("gear", HubText.settingsDescription(), TAG_SETTINGS, actions::openSettings), new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        header.addView(second, new LinearLayout.LayoutParams(-1, -2));
        page.addView(header, new LinearLayout.LayoutParams(-1, -2));

        // The top area: one height for every moment, so the Lock bar and the quick actions never move.
        top = new FrameLayout(host.activity());
        top.setTag(TAG_TOP);
        overview = parts.overview();
        charging = parts.chargingView();
        top.addView(overview, new FrameLayout.LayoutParams(-1, -1));
        top.addView(charging, new FrameLayout.LayoutParams(-1, -1));
        charging.setVisibility(View.GONE);
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

        subjects = new CleanSubjects(host);
        LinearLayout.LayoutParams subjectSize = new LinearLayout.LayoutParams(-1, -2);
        subjectSize.topMargin = ui.dp(16);
        page.addView(subjects.view(), subjectSize);
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
        // Just back (the last ride) arrives with the next part; until then Clean shows parked or charging.
        int next = HomeMoment.choose(HomeMoment.on(stored(HomeMoment.CHARGING_KEY)), parts.charging(),
                false, false, null, 0, null, System.currentTimeMillis());
        show(next);
        String name = parts.name().getText().toString();
        parts.name().setContentDescription(HubText.switchScooterDescription(name).render());
        quick.refresh();
        subjects.refresh();
    }

    /** Swaps the top area's content: a short crossfade on a change only, never on a telemetry refresh. */
    private void show(int next) {
        if (next == moment) return;
        boolean first = moment < 0;
        moment = next;
        boolean chargingNow = next == HomeMoment.CHARGING;
        parts.chargingShown(chargingNow);
        View in = chargingNow ? charging : overview, out = chargingNow ? overview : charging;
        in.animate().cancel();
        out.animate().cancel();
        if (first || !Ui.motionEnabled() || !top.isAttachedToWindow()) {
            in.setAlpha(1);
            in.setVisibility(View.VISIBLE);
            out.setVisibility(View.GONE);
            out.setAlpha(1);
            return;
        }
        in.setAlpha(0);
        in.setVisibility(View.VISIBLE);
        in.animate().alpha(1).setDuration(CROSSFADE_MS).setInterpolator(Motion.STANDARD).start();
        out.animate().alpha(0).setDuration(CROSSFADE_MS).setInterpolator(Motion.STANDARD).withEndAction(() -> {
            out.setVisibility(View.GONE);
            out.setAlpha(1);
        }).start();
    }

    @Override public void close() {
        closed = true;
        overview.animate().cancel();
        charging.animate().cancel();
        if (editSheet != null && editSheet.isShowing()) editSheet.dismiss();
        editSheet = null;
        parts.close();
        subjects.close();
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

    /** A round icon button: a 40 dp disc inside a 48 dp target, with its name for screen readers and a long-press tooltip. */
    private View round(String icon, Msg label, String tag, Runnable action) {
        FrameLayout target = new FrameLayout(host.activity());
        GradientDrawable disc = new GradientDrawable();
        disc.setShape(GradientDrawable.OVAL);
        disc.setColor(ui.dark ? ui.surface : ui.raised);
        target.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(ui.accent, 35)), new InsetDrawable(disc, ui.dp((48 - CleanHeaderFit.DISC_DP) / 2)), null));
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
