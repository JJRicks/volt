package com.jjricks.volt;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.*;
import android.widget.*;

import java.util.*;

/** A local, editable dashboard. Widget movement never shares gestures with scooter controls. */
public final class DashboardScreen implements AppScreen {
    /**
     * What an idle recorder disc says: Home's, and riding mode's Record, which never connects, so its
     * disabled "Connect to scooter" is only the reason it can't start.
     */
    static String recordIdleSpoken(boolean saving, boolean ready, boolean connecting) {
        return saving ? T.dashboardRecordSaving() : ready ? T.dashboardStartRecording() : connecting ? T.dashboardRecordConnecting()
                : T.dashboardRecordConnect();
    }

    public interface Actions {
        View lock(boolean showDisconnect);

        /** The lock bar's compact variant; one lock control exists per dashboard. */
        default View lock(boolean showDisconnect, boolean compact) { return lock(showDisconnect); }

        void clearLock();

        void navigate(String page);

        default void quickAction(String id) { navigate(id); }

        /** Starts a ride from Home, staying on Home (RideRecordingControl.start). */
        void startRide();

        void rename();

        /** Opens the connection dialog (details plus Connect or Disconnect). */
        void connectionDetails();

        /** Clean Home's search button. */
        default void openSearch() { }

        /** Clean Home's Settings button. */
        default void openSettings() { }

        /** Clean Home's scooter name: switch scooter. */
        default void switchScooter() { }

        /**
         * The charging bar's dragged charge limit: the Charge limit control's gates, confirmation and write.
         * accepted(true) once the scooter was sent the change; false when refused or cancelled (said in its words).
         */
        default void chargeLimitDrag(int value, java.util.function.Consumer<Boolean> accepted) { accepted.accept(false); }
        /** The charging bar's words (ChargeBar.Words); null leaves the bar without a mark. */
        default ChargeBar.Words chargeBarWords() { return null; }
    }

    private final ScreenHost host;
    private final Actions actions;
    private final Ui ui;
    private final ScooterService service;
    private final SharedPreferences preferences;
    private final DashboardConfig config;
    private final String profileId;
    private final LinearLayout view, toolbar, tools, headerActions, historyActions;
    private final View undo, redo;
    private final DashboardEditHistory editHistory = new DashboardEditHistory();
    private WidgetRemovalEffect removalEffect;
    private EditTransition editTransition;
    private WidgetReveal widgetReveal;
    private String pendingAddedWidgetId, revealWidgetId;
    private final FrameLayout edit, renameControl;
    /** Home's RIDE button, left of the pencil; hidden while editing. */
    private final View ride;
    /** Home header: connection status line (hidden while editing) and the scooter name. */
    private final TextView status, profileName;
    private final View add, layoutButton;
    private final ChargingHero chargingHero;
    private final Context dialogContext;
    private final Grid grid;
    private final ArrayList<Tile> tiles = new ArrayList<>();
    private boolean editing, closed;
    /** A tapped recorder start that has not run yet: its frame or close() runs it, or its timeout cancels it, exactly once. */
    private final RecorderTap.PendingStart recorderStart = new RecorderTap.PendingStart();
    /** The header's "last seen" time, or 0 while connected or connecting; cards with the same age omit theirs. */
    private long headerSeenMs;
    /** Card content opacity while editing, so the editor handles read clearly on top. */
    private static final float EDIT_DIM = .4f;
    private static final int WIDGET_LIMIT = 32;
    /** Tag for card content that edits the widget itself, so it stays enabled while editing. */
    private static final String EDIT_ACTION = "dashboard-edit-action";
    private static final CharSequence WIDGET_LIMIT_TEXT = M.dashboardWidgetLimit();
    /** The marker Telemetry's English name of a signed reading ends with, which the cards leave out (data). */
    private static final String TELEMETRY_SIGNED = " (signed)";
    private AlertDialog dialog;
    /**
     * Holds the layout from before Restore defaults while that step is still the latest edit, so
     * its Undo survives a rebuild in edit mode (tab switch, rotation, fold).
     */
    private final String restoreUndoKey;
    /** The bottom notice (e.g. "Defaults restored · Undo"), if one is showing. */
    private PopupWindow notice;
    /**
     * The shortcut picker as it was when Home was rebuilt around it (a turn or fold of the phone, a theme change): the
     * next Home for the same scooter, made in the same step, reopens it with its search text, open sections and scroll.
     * Anything else (closing the picker, another page, another scooter) leaves it closed.
     */
    private static PickerMemo pickerMemo;
    /** The open picker's sheet and how to read its state, for close(). */
    private AlertDialog pickerDialog;
    private java.util.function.Supplier<PickerMemo> pickerState;

    public DashboardScreen(ScreenHost host, Actions actions) {
        this.host = host;
        this.actions = actions;
        ui = host.ui();
        dialogContext =
                new ContextThemeWrapper(
                        host.activity(),
                        ui.dark
                                ? R.style.VoltDialog
                                : R.style.VoltDialogLight);
        service = host.service();
        preferences = host.activity().getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        ScooterProfiles.Profile profile = service == null ? null : service.profiles.active();
        profileId = profile == null ? "unconfigured" : profile.id;
        // Freeze the window class for this screen, including any gesture canceled during a fold.
        // Default Setting tiles follow the model once Volt knows it (a GT5 Max has no scheduled charging).
        modelIdentity = identity(profile);
        java.util.List<String> modelTiles = profile == null ? ModelTiles.DEFAULT : ModelTiles.settingTiles(profile.savedDescriptor());
        config = new DashboardConfig(preferences, profileId, ui.wideLayout(), modelTiles);
        restoreUndoKey = "dashboard_restore_undo_v1_" + profileId;
        reconcileModelTiles(modelTiles, profile == null ? null : ModelTiles.previousSettingTiles(profile.savedDescriptor()));
        editing = preferences.getBoolean("dashboard_editing", false);
        view = column();
        PickerMemo reopen = pickerMemo;
        pickerMemo = null;
        if (reopen != null && reopen.profileId.equals(profileId)) view.post(() -> { if (!closed) reopenPicker(reopen); });
        // Home header: a muted status line, the scooter name and a round edit button.
        toolbar = new LinearLayout(host.activity()) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                boolean stacked = editing && MeasureSpec.getSize(widthSpec) < ui.dp(520 * ui.textScale);
                setOrientation(stacked ? VERTICAL : HORIZONTAL);
                for (int i = 0; i < getChildCount(); i++) {
                    LinearLayout.LayoutParams p = (LinearLayout.LayoutParams)getChildAt(i).getLayoutParams();
                    p.width = stacked ? -1 : i == 0 ? 0 : -2;
                    p.weight = !stacked && i == 0 ? 1 : 0;
                    p.topMargin = stacked && i == 1 ? ui.dp(6) : 0;
                }
                super.onMeasure(widthSpec, heightSpec);
            }
        };
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(0, ui.dp(8), 0, ui.dp(10));
        LinearLayout words = column();
        status = statusLine();
        LinearLayout.LayoutParams statusSize = new LinearLayout.LayoutParams(-1, -2);
        statusSize.topMargin = -ui.dp(15);
        statusSize.bottomMargin = -ui.dp(15);
        words.addView(status, statusSize);
        profileName = nameLine();
        renameControl = roundAction("pencil", T.dashboardRenameScooter(), 24, 12, actions::rename);
        LinearLayout nameRow =
                new LinearLayout(host.activity()) {
                    @Override
                    protected void onMeasure(int widthSpec, int heightSpec) {
                        // The rename pencil sits right after the name, never pushed off the edge.
                        int width = MeasureSpec.getSize(widthSpec);
                        int reserve = renameControl.getVisibility() == View.GONE ? 0 : ui.dp(48);
                        int limit = Math.max(ui.dp(48), width - reserve);
                        if (profileName.getMaxWidth() != limit) profileName.setMaxWidth(limit);
                        super.onMeasure(widthSpec, heightSpec);
                    }
                };
        nameRow.setOrientation(LinearLayout.HORIZONTAL);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);
        nameRow.addView(profileName, new LinearLayout.LayoutParams(-2, -2));
        nameRow.addView(renameControl, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        words.addView(nameRow, new LinearLayout.LayoutParams(-1, -2));
        toolbar.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        edit = new FrameLayout(host.activity());
        edit.setFocusable(true);
        edit.setClickable(true);
        edit.setMinimumWidth(ui.dp(48));
        edit.setMinimumHeight(ui.dp(48));
        edit.setOnClickListener(v -> toggleEditing());
        headerActions = row();
        headerActions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        historyActions = row();
        historyActions.setGravity(Gravity.CENTER_VERTICAL);
        historyActions.setTag("dashboard-history-controls");
        historyActions.setBackground(ui.shape(ui.surface, 14, ui.outline));
        historyActions.setClipToOutline(true);
        undo = historyAction("undo", T.dashboardUndoEdit(), () -> restoreEdit(false));
        redo = historyAction("redo", T.dashboardRedoEdit(), () -> restoreEdit(true));
        historyActions.addView(undo, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        View divider = new View(host.activity()); divider.setBackgroundColor(ui.outline);
        historyActions.addView(divider, new LinearLayout.LayoutParams(ui.dp(1), ui.dp(20)));
        historyActions.addView(redo, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        headerActions.addView(historyActions, new LinearLayout.LayoutParams(-2, ui.dp(48)));
        ride = RidingScreen.entry(host, host::openRiding);
        headerActions.addView(ride, new LinearLayout.LayoutParams(-2, ui.dp(48)));
        LinearLayout.LayoutParams editSize = new LinearLayout.LayoutParams(-2, ui.dp(48));
        editSize.setMarginStart(ui.dp(8));
        headerActions.addView(edit, editSize);
        toolbar.addView(headerActions, new LinearLayout.LayoutParams(-2, -2));
        view.addView(toolbar);
        tools = row();
        add = toolButton(T.dashboardAddWidget(), "plus", this::palette);
        LinearLayout.LayoutParams addSpace = new LinearLayout.LayoutParams(0, -2, 1);
        addSpace.setMarginEnd(ui.dp(8));
        tools.addView(add, addSpace);
        layoutButton = toolButton(T.dashboardLayout(), "grid", this::layoutOptions);
        tools.addView(layoutButton, new LinearLayout.LayoutParams(0, -2, 1));
        // The grip and corner marks show how to move and resize; no caption repeats it.
        tools.setPadding(0, ui.dp(2), 0, ui.dp(12));
        view.addView(tools);
        chargingHero = new ChargingHero(false);
        view.addView(chargingHero, new LinearLayout.LayoutParams(-1, -2));
        grid = new Grid();
        view.addView(grid, new LinearLayout.LayoutParams(-1, -2));
        render();
        resumeRestoreUndo();
    }

    /**
     * Home's parts for Clean Home (hub layout plan, PR 8), which is its own screen (CleanHomeScreen) beside this
     * widget dashboard. This instance only lends the header's status line and name, the battery overview card and
     * the charging view; it has no DashboardConfig, no grid and no editor, and never reads or writes the widget
     * dashboard's storage (dashboard_v1_*, saved slots, dashboard_columns, dashboard_editing). Nothing calls render()
     * or refresh() on it; CleanParts drives the parts.
     */
    private DashboardScreen(ScreenHost host, Actions actions, ScooterProfiles.Profile profile) {
        this.host = host;
        this.actions = actions;
        ui = host.ui();
        dialogContext = new ContextThemeWrapper(host.activity(), ui.dark ? R.style.VoltDialog : R.style.VoltDialogLight);
        service = host.service();
        preferences = host.activity().getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        profileId = profile == null ? "unconfigured" : profile.id;
        modelIdentity = identity(profile);
        config = null;
        restoreUndoKey = null;
        view = column();
        toolbar = tools = headerActions = historyActions = null;
        undo = redo = ride = add = layoutButton = null;
        edit = renameControl = null;
        grid = null;
        status = statusLine();
        profileName = nameLine();
        chargingHero = new ChargingHero(true);
    }

    /** Clean Home's parts, from a parts-only instance (see the constructor above). */
    static CleanParts cleanParts(ScreenHost host, Actions actions) {
        ScooterService service = host.service();
        ScooterProfiles.Profile profile = service == null ? null : service.profiles.active();
        return new DashboardScreen(host, actions, profile).new CleanParts();
    }

    /**
     * Usable ride energy for Battery & charging › Estimates & charger (hub layout plan 1.5's PANEL, deferred there
     * from part 4), from a parts-only instance like Clean Home's: one transient capacity widget, never added to a
     * dashboard. The widget dashboard's own Usable ride energy widget stays as it is.
     */
    static EnergyPanel energyPanel(ScreenHost host, Actions actions) {
        ScooterService service = host.service();
        ScooterProfiles.Profile profile = service == null ? null : service.profiles.active();
        return new DashboardScreen(host, actions, profile).new EnergyPanel();
    }

    /**
     * The capacity widget alone (its title, value, comparison, rides and details), with the same 90-day window and
     * model rating as the default dashboard's. Its "Set reference" pill stays hidden: a reference is stored on a
     * dashboard widget, and this one belongs to no dashboard.
     */
    final class EnergyPanel {
        private final Tile tile;

        EnergyPanel() {
            DashboardConfig.Widget widget = new DashboardConfig.Widget("capacity");
            tile = new Tile(widget);
            tile.panel = true;
            tile.setTag("battery-usable-energy");
        }

        View view() { return tile; }

        /** The scooter this panel was built for; another one being active means the page rebuilds. */
        String profileId() { return profileId; }

        void refresh() {
            if (closed) return;
            tile.refresh(service == null ? readings() : service.dashboardReadings());
        }

        void close() {
            closed = true;
            tile.active = false;
            tile.capacityRequest++;
            dismiss();
        }
    }

    /**
     * What Clean Home borrows from Home: the status line and the scooter's name (refreshed like this header's), the
     * battery overview card (a transient overview widget like DefaultDashboard's, never added to any dashboard) and the
     * charging view in its Clean style. Clean places them; their text stays here.
     */
    final class CleanParts {
        private final Tile overview;

        CleanParts() {
            DashboardConfig.Widget widget = new DashboardConfig.Widget("overview");
            widget.keys.clear();
            widget.keys.addAll(Arrays.asList("odometer_km", "driving_mode"));
            overview = new Tile(widget);
            overview.setTag("clean-home-overview");
            // Clean's battery card fills its fixed-height area (readings along the bottom) and draws Sport in the
            // accent's text colour, as the Layout Plan does; the widget dashboard's card is unchanged.
            overview.clean = true;
            if (overview.overview != null) overview.overview.fill = true;
            chargingHero.setTag("clean-home-charging");
        }

        TextView status() { return status; }
        TextView name() { return profileName; }
        View overview() { return overview; }
        View chargingView() { return chargingHero; }

        /** The scooter this Home was built for; another one being active means Clean rebuilds. */
        String profileId() { return profileId; }

        /** A scooter's model identity, as the widget dashboard compares it to let default tiles follow the model. */
        String identity(ScooterProfiles.Profile profile) { return DashboardScreen.identity(profile); }

        /** Charging is freshly reported: the same predicate as the charging takeover; stale readings never count. */
        boolean charging() { return !closed && chargingEstimate(readings()).charging; }

        /** Whether the charging view is the one showing (Clean's top area says so); it breathes only then. */
        void chargingShown(boolean shown) {
            if (chargingHero.shown == shown) return;
            chargingHero.shown = shown;
            chargingHero.updatePulse();
        }

        /** Refreshes the status line, the name, the overview card and (while charging) the charging view. */
        void refresh() {
            if (closed) return;
            ScooterProfiles.Profile current = service == null ? null : service.profiles.active();
            Map<String, TelemetryHistory.Reading> readings = readings();
            refreshHeader(current, readings);
            boolean shown = chargingHero.shown;
            chargingHero.refresh(readings);
            chargingHero.shown = shown;
            chargingHero.updatePulse();
            overview.refresh(service == null ? readings : service.dashboardReadings());
        }

        /** The shortcut picker over Clean's own actions and the catalog; picked gets the chosen id. */
        void chooseQuickAction(List<String> chosen, int index, java.util.function.Consumer<String> picked) {
            if (closed) return;
            DashboardScreen.this.chooseQuickAction(chosen, index,
                    HomeQuickActions.ownEntries(service != null && service.chargeAlertsOffered()), id -> {
                dismiss();
                picked.accept(id);
            }, null, null);
        }

        void close() {
            finishPendingStart();
            closed = true;
            overview.active = false;
            chargingHero.shown = false;
            chargingHero.stopReveal();
            dismiss();
        }
    }

    @Override
    public View view() {
        return view;
    }

    /** Home header's muted status line (Connected · Sport, Connecting…, last seen); a tap shows the connection. */
    private TextView statusLine() {
        TextView line = ui.text("", 12.5f, false);
        line.setTextColor(ui.muted);
        ui.fitStatusLine(line);
        // Extra padding gives the one-line status a full touch target without moving the name.
        line.setPadding(0, ui.dp(15), 0, ui.dp(15));
        line.setTooltipText(T.dashboardConnectionDetails());
        line.setOnClickListener(
                v -> {
                    if (!editing) actions.connectionDetails();
                });
        return line;
    }

    /** Home header's scooter name: a heading of up to two lines. */
    private TextView nameLine() {
        TextView name = ui.text(T.dashboardYourScooter(), 23, true);
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if (android.os.Build.VERSION.SDK_INT >= 28) name.setAccessibilityHeading(true);
        return name;
    }

    /**
     * Tabletop posture: hands Home's header (status, name, edit tools and the charging card) to
     * MainActivity's upper pane above the hinge, leaving the widgets below it.
     * The same views keep updating; only their parent changes.
     */
    public View takeHeader() {
        LinearLayout pane = column();
        for (View part : new View[] {toolbar, tools, chargingHero}) {
            ViewGroup.LayoutParams params = part.getLayoutParams();
            view.removeView(part);
            pane.addView(part, params);
        }
        return pane;
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

    /** Mixes two colours; used for tints derived from the Volt tokens. */
    private static int blend(int from, int to, float amount) {
        float a = Math.max(0, Math.min(1, amount));
        return Color.rgb(
                Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * a),
                Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * a),
                Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * a));
    }

    private static android.graphics.drawable.Drawable ripple(
            int color, android.graphics.drawable.Drawable content, android.graphics.drawable.Drawable mask) {
        return new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(color), content, mask);
    }

    /** Dark outlined button with a leading glyph, centred together (Add widget, Layout). */
    private LinearLayout toolButton(String label, String glyph, Runnable action) {
        LinearLayout button = row();
        button.setGravity(Gravity.CENTER);
        button.setMinimumHeight(ui.dp(48));
        button.setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8));
        button.setBackground(
                ripple(Ui.withAlpha(ui.accent, 30), ui.shape(ui.surface, 14, ui.outline), null));
        button.setClickable(true);
        button.setFocusable(true);
        button.addView(new GlyphView(glyph, ui.text), new LinearLayout.LayoutParams(ui.dp(17), ui.dp(17)));
        TextView text = ui.text(label, 13.5f, false);
        text.setTypeface(ui.semibold());
        LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-2, -2);
        space.setMarginStart(ui.dp(8));
        button.addView(text, space);
        button.setOnClickListener(v -> action.run());
        return button;
    }

    /** A round control (a visual disc inside a 48 dp target), e.g. the rename pencil or a sheet close. */
    private FrameLayout roundAction(String key, String label, int discDp, int iconDp, Runnable action) {
        FrameLayout target = new FrameLayout(host.activity());
        int inset = Math.max(0, (48 - discDp) / 2);
        GradientDrawable disc = new GradientDrawable();
        disc.setShape(GradientDrawable.OVAL);
        disc.setColor(ui.dark ? ui.surface : ui.raised);
        target.setBackground(
                ripple(
                        Ui.withAlpha(ui.accent, 35),
                        new android.graphics.drawable.InsetDrawable(disc, ui.dp(inset)),
                        null));
        target.addView(
                new GlyphView(key, ui.text),
                new FrameLayout.LayoutParams(ui.dp(iconDp), ui.dp(iconDp), Gravity.CENTER));
        target.setContentDescription(label);
        target.setFocusable(true);
        target.setClickable(true);
        target.setMinimumWidth(ui.dp(48));
        target.setMinimumHeight(ui.dp(48));
        if (action != null) target.setOnClickListener(v -> action.run());
        return target;
    }

    private void toggleEditing() {
        cancelWidgetReveal();
        pendingAddedWidgetId = null;
        grid.finishGesture(false);
        stopRemovalEffect();
        // Capture before stopping a running transition, so a quick second tap reverses from
        // what is on screen (content opacity, handle opacity, grid offset) instead of snapping.
        HashMap<String, Float> shownDim = new HashMap<>();
        for (Tile tile : tiles) {
            tile.content.animate().cancel();
            shownDim.put(tile.widget.id, tile.content.getAlpha());
        }
        EditTransition transition = Ui.motionEnabled() && grid.isAttachedToWindow()
                && grid.hasWindowFocus() ? new EditTransition(editing, shownDim) : null;
        stopEditTransition();
        grid.stopJiggle();
        editHistory.clear();
        clearRestoreUndo();
        editing = !editing;
        preferences.edit().putBoolean("dashboard_editing", editing).apply();
        render();
        if (transition != null) {
            editTransition = transition;
            transition.start();
        }
    }

    private void stopEditTransition() {
        if (editTransition != null) editTransition.cancel();
    }

    /** Visible editor controls only; offscreen cards do not need decorative work. */
    private ArrayList<View> editorControls() {
        ArrayList<View> result = new ArrayList<>();
        Rect visible = new Rect();
        for (View control : new View[] {edit, historyActions, renameControl, add, layoutButton})
            if (control.getVisibility() == View.VISIBLE && control.getGlobalVisibleRect(visible))
                result.add(control);
        for (Tile tile : tiles) {
            if (!tile.getGlobalVisibleRect(visible)) continue;
            View bar = tile.findViewWithTag("widget-editor-bar");
            if (bar instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) bar;
                for (int i = 0; i < group.getChildCount(); i++)
                    if (group.getChildAt(i).getGlobalVisibleRect(visible)) result.add(group.getChildAt(i));
            }
            if (tile.resizeControl != null && tile.resizeControl.getGlobalVisibleRect(visible))
                result.add(tile.resizeControl);
        }
        return result;
    }

    /** One short visual transition; editing/permissions/history change synchronously. */
    private final class EditTransition implements ViewTreeObserver.OnPreDrawListener {
        private static final int PIXEL_BUDGET = 1_000_000;
        final boolean leaving;
        final View overlayOwner;
        final int oldGridY, oldActionsY;
        final ArrayList<ExitControlGhost> ghosts = new ArrayList<>();
        final ArrayList<View> controls = new ArrayList<>();
        final Map<View, Float> controlAlphas = new IdentityHashMap<>();
        final Map<View, Long> controlDelays = new IdentityHashMap<>();
        final Map<String, Float> startDim;
        final ArrayList<Tile> dimmed = new ArrayList<>();
        final Map<Tile, Long> dimDelays = new IdentityHashMap<>();
        final Map<Tile, Float> dimFrom = new IdentityHashMap<>();
        long duration;
        android.animation.ValueAnimator animator;
        ViewTreeObserver observer;
        boolean finished;
        float gridOffset, actionsOffset;

        EditTransition(boolean leaving, Map<String, Float> startDim) {
            this.leaving = leaving;
            this.startDim = startDim;
            overlayOwner = view.getRootView();
            oldGridY = screenY(grid);
            oldActionsY = screenY(headerActions);
            if (leaving) captureExitControls();
        }

        private int screenY(View target) {
            int[] at = new int[2]; target.getLocationOnScreen(at); return at[1];
        }

        private void captureExitControls() {
            int[] ownerAt = new int[2]; overlayOwner.getLocationOnScreen(ownerAt);
            int remaining = PIXEL_BUDGET;
            for (View control : editorControls()) {
                if (control.getWidth() <= 0 || control.getHeight() <= 0 || remaining < 64) continue;
                Rect visible = new Rect();
                if (!control.getGlobalVisibleRect(visible)) continue;
                Bitmap bitmap = null;
                try {
                    double pixels = (double) control.getWidth() * control.getHeight();
                    double scale = Math.min(1, Math.sqrt(Math.min(120_000, remaining) / pixels));
                    scale = Math.min(scale, Math.min(1024d / control.getWidth(), 1024d / control.getHeight()));
                    int w = Math.max(1, (int) (control.getWidth() * scale));
                    int h = Math.max(1, (int) (control.getHeight() * scale));
                    bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    Canvas capture = new Canvas(bitmap);
                    capture.scale(w / (float) control.getWidth(), h / (float) control.getHeight());
                    control.draw(capture);
                    int[] at = new int[2]; control.getLocationOnScreen(at);
                    RectF bounds = new RectF(at[0] - ownerAt[0], at[1] - ownerAt[1],
                            at[0] - ownerAt[0] + control.getWidth(), at[1] - ownerAt[1] + control.getHeight());
                    RectF clip = new RectF(visible);
                    clip.offset(-ownerAt[0], -ownerAt[1]);
                    boolean inGrid = false;
                    for (ViewParent parent = control.getParent(); parent instanceof View; parent = parent.getParent())
                        if (parent == grid) { inGrid = true; break; }
                    ExitControlGhost ghost = new ExitControlGhost(bitmap, bounds, clip, inGrid, control.getAlpha());
                    ghost.setBounds(0, 0, overlayOwner.getWidth(), overlayOwner.getHeight());
                    ghosts.add(ghost);
                    remaining -= w * h;
                } catch (RuntimeException | OutOfMemoryError ignored) {
                    if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
                    break;
                }
            }
        }

        void start() {
            if (closed || !Ui.motionEnabled() || !grid.isAttachedToWindow()) { cancel(); return; }
            observer = grid.getViewTreeObserver();
            observer.addOnPreDrawListener(this);
            grid.requestLayout();
        }

        @Override public boolean onPreDraw() {
            removePreDraw();
            if (finished || closed || !Ui.motionEnabled() || !grid.hasWindowFocus()
                    || !grid.isAttachedToWindow()) { cancel(); return true; }
            gridOffset = oldGridY - screenY(grid);
            actionsOffset = oldActionsY - screenY(headerActions);
            grid.setTranslationY(gridOffset);
            headerActions.setTranslationY(actionsOffset);
            // Enter: content dims while handles pop in, staggered by position so the grid wakes up.
            // Leave: handles go first, then content brightens. Durations follow Motion's conventions.
            ArrayList<Tile> ordered = new ArrayList<>(tiles);
            Collections.sort(ordered, (a, b) -> a.getTop() != b.getTop()
                    ? Integer.compare(a.getTop(), b.getTop()) : Integer.compare(a.getLeft(), b.getLeft()));
            long content = leaving ? 180 : 240, pop = 200, lead = leaving ? 70 : 0;
            long stagger = leaving || ordered.size() < 2 ? 0 : Math.min(20, (350 - content) / (ordered.size() - 1));
            duration = Math.max(210, lead + content + stagger * Math.max(0, ordered.size() - 1));
            Rect visible = new Rect();
            if (!leaving) {
                for (View control : new View[] {edit, historyActions, renameControl, add, layoutButton})
                    if (control.getVisibility() == View.VISIBLE && control.getGlobalVisibleRect(visible)) {
                        controls.add(control);
                        controlDelays.put(control, 0L);
                    }
            } else {
                controls.add(edit);
                controlDelays.put(edit, 0L);
            }
            for (int i = 0; i < ordered.size(); i++) {
                Tile tile = ordered.get(i);
                long delay = lead + stagger * i;
                Float from = startDim.get(tile.widget.id);
                dimmed.add(tile);
                dimDelays.put(tile, delay);
                dimFrom.put(tile, from != null ? from : leaving ? EDIT_DIM : 1f);
                tile.content.animate().cancel();
                tile.content.setAlpha(dimFrom.get(tile));
                if (leaving || !tile.getGlobalVisibleRect(visible)) continue;
                View bar = tile.findViewWithTag("widget-editor-bar");
                if (bar instanceof ViewGroup)
                    for (int c = 0; c < ((ViewGroup) bar).getChildCount(); c++) {
                        controls.add(((ViewGroup) bar).getChildAt(c));
                        controlDelays.put(((ViewGroup) bar).getChildAt(c), stagger * i);
                    }
                if (tile.resizeControl != null) {
                    controls.add(tile.resizeControl);
                    controlDelays.put(tile.resizeControl, stagger * i);
                }
            }
            for (View control : controls) {
                controlAlphas.put(control, control.getAlpha());
                control.setAlpha(0);
                control.setScaleX(.85f); control.setScaleY(.85f);
            }
            try {
                for (ExitControlGhost ghost : ghosts) overlayOwner.getOverlay().add(ghost);
            } catch (RuntimeException | OutOfMemoryError ignored) {
                cancel();
                return true;
            }
            final long contentMs = content, popMs = pop;
            final android.animation.TimeInterpolator fadeOut = new android.view.animation.PathInterpolator(.4f, 0, 1, 1);
            animator = android.animation.ValueAnimator.ofFloat(0, 1);
            animator.setDuration(duration);
            // Each part applies its own curve and delay to the shared clock.
            animator.setInterpolator(new android.view.animation.LinearInterpolator());
            animator.addUpdateListener(a -> {
                if (!Ui.motionEnabled() || closed || !grid.hasWindowFocus()) { cancel(); return; }
                float elapsed = a.getAnimatedFraction() * duration;
                float travel = Motion.STANDARD.getInterpolation(Math.min(1, elapsed / 210f));
                grid.setTranslationY(gridOffset * (1 - travel));
                headerActions.setTranslationY(actionsOffset * (1 - travel));
                for (View control : controls) {
                    float local = Math.max(0, Math.min(1, (elapsed - controlDelays.get(control)) / popMs));
                    float shown = Motion.STANDARD.getInterpolation(local);
                    control.setAlpha(controlAlphas.get(control) * shown);
                    control.setScaleX(.85f + .15f * shown); control.setScaleY(.85f + .15f * shown);
                }
                float target = leaving ? 1f : EDIT_DIM;
                for (Tile tile : dimmed) {
                    float local = Math.max(0, Math.min(1, (elapsed - dimDelays.get(tile)) / contentMs));
                    float from = dimFrom.get(tile);
                    tile.content.setAlpha(from + (target - from) * Motion.STANDARD.getInterpolation(local));
                }
                for (ExitControlGhost ghost : ghosts) {
                    ghost.progress = fadeOut.getInterpolation(Math.min(1, elapsed / 160f));
                    ghost.offset = ghost.inGrid ? -gridOffset * travel : 0;
                    ghost.invalidateSelf();
                }
            });
            animator.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator animation) { finish(); }
                @Override public void onAnimationCancel(android.animation.Animator animation) { finish(); }
            });
            animator.start();
            return true;
        }

        private void removePreDraw() {
            if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(this);
            observer = null;
        }

        void cancel() {
            if (finished) return;
            if (animator != null) animator.cancel();
            finish();
        }

        private void finish() {
            if (finished) return;
            finished = true;
            removePreDraw();
            if (animator != null) { animator.removeAllUpdateListeners(); animator.removeAllListeners(); }
            grid.setTranslationY(0); headerActions.setTranslationY(0);
            for (View control : controls) {
                control.setAlpha(controlAlphas.get(control)); control.setScaleX(1); control.setScaleY(1);
            }
            controls.clear();
            controlAlphas.clear();
            controlDelays.clear();
            // Any other interruption settles at the mode's opacity; a new toggle captured it first.
            for (Tile tile : dimmed) if (tile.active)
                tile.content.setAlpha(editing ? EDIT_DIM : 1f);
            dimmed.clear();
            for (ExitControlGhost ghost : ghosts) {
                try { overlayOwner.getOverlay().remove(ghost); }
                catch (RuntimeException | OutOfMemoryError ignored) { }
                finally { ghost.release(); }
            }
            ghosts.clear();
            if (editTransition == this) editTransition = null;
            grid.updateEditorMotion();
        }
    }

    /** Exit controls are only a fading image; no stale action survives the state change. */
    private static final class ExitControlGhost extends android.graphics.drawable.Drawable {
        Bitmap bitmap;
        final RectF bounds, clip;
        final boolean inGrid;
        final float alpha;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        float progress, offset;

        ExitControlGhost(Bitmap bitmap, RectF bounds, RectF clip, boolean inGrid, float alpha) {
            this.bitmap = bitmap; this.bounds = bounds; this.clip = clip;
            this.inGrid = inGrid; this.alpha = alpha;
        }

        @Override public void draw(Canvas canvas) {
            if (bitmap == null || bitmap.isRecycled()) return;
            paint.setAlpha(Math.round(255 * alpha * (1 - progress)));
            int save = canvas.save();
            canvas.translate(0, offset);
            canvas.clipRect(clip);
            canvas.scale(1 - .15f * progress, 1 - .15f * progress, bounds.centerX(), bounds.centerY());
            canvas.drawBitmap(bitmap, null, bounds, paint);
            canvas.restoreToCount(save);
        }

        void release() {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
            bitmap = null;
        }
        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(ColorFilter filter) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private View historyAction(String icon, String label, Runnable action) {
        FrameLayout control = new FrameLayout(host.activity());
        control.addView(new GlyphView(icon, ui.text),
                new FrameLayout.LayoutParams(ui.dp(21), ui.dp(21), Gravity.CENTER));
        control.setBackground(ripple(Ui.withAlpha(ui.accent, 45), null, ui.shape(Color.WHITE, 10, 0)));
        control.setContentDescription(label);
        control.setTooltipText(icon.equals("undo") ? T.dashboardUndo() : T.dashboardRedo());
        control.setFocusable(true); control.setClickable(true);
        control.setOnClickListener(v -> action.run());
        return control;
    }

    private void updateHistoryControls() {
        historyActions.setVisibility(editing ? View.VISIBLE : View.GONE);
        undo.setEnabled(editing && editHistory.canUndo());
        redo.setEnabled(editing && editHistory.canRedo());
        undo.setAlpha(undo.isEnabled() ? 1 : .3f);
        redo.setAlpha(redo.isEnabled() ? 1 : .3f);
    }

    private boolean recordEdit(DashboardEditHistory.Snapshot before) {
        boolean recorded = editHistory.record(before, config);
        if (recorded) {
            // A newer step ends any Undo offer; it would no longer undo the step it named.
            hideNotice();
            clearRestoreUndo();
        }
        updateHistoryControls();
        return recorded;
    }

    private void restoreEdit(boolean forward) {
        if (!editing || closed) return;
        grid.finishGesture(false);
        stopRemovalEffect();
        dismiss();
        clearRestoreUndo();
        if (forward ? editHistory.redo(config) : editHistory.undo(config)) {
            config.save();
            render();
        }
    }

    private void stopRemovalEffect() {
        if (removalEffect != null) { removalEffect.cancel(); removalEffect = null; }
    }

    private void cancelWidgetReveal() {
        if (widgetReveal != null) { widgetReveal.cancel(); widgetReveal = null; }
    }

    private void revealAddedWidget() {
        if (revealWidgetId == null || closed || !editing || dialog != null
                || !grid.hasWindowFocus() || !grid.isLaidOut()) return;
        Tile target = null;
        for (Tile tile : tiles) if (tile.widget.id.equals(revealWidgetId)) { target = tile; break; }
        if (target == null) { revealWidgetId = null; return; }
        if (!target.isLaidOut() || target.isLayoutRequested()) {
            grid.postOnAnimation(this::revealAddedWidget);
            return;
        }
        revealWidgetId = null;
        cancelWidgetReveal();
        widgetReveal = WidgetReveal.start(target, grid.outerScroll(), ui);
    }

    private void revealIfAdded(DashboardConfig.Widget widget) {
        if (!widget.id.equals(pendingAddedWidgetId)) return;
        pendingAddedWidgetId = null;
        revealWidgetId = widget.id;
        grid.post(this::revealAddedWidget);
    }

    private void render() {
        if (closed) return;
        // A later change ends any Undo offer; it would no longer undo the step it named.
        hideNotice();
        cancelWidgetReveal();
        stopEditTransition();
        grid.stopJiggle();
        grid.finishGesture(false);
        grid.rememberFrames();
        for (Tile tile : tiles) tile.active = false;
        tiles.clear();
        grid.removeAllViews();
        actions.clearLock();
        // A red Done pill replaces the round pencil.
        ui.editToggle(edit, editing, T.commonDone());
        edit.setContentDescription(editing ? T.dashboardFinishEditing() : T.dashboardEditDashboard());
        ride.setVisibility(editing ? View.GONE : View.VISIBLE);
        updateHistoryControls();
        renameControl.setVisibility(
                editing && service != null && service.profiles.active() != null ? View.VISIBLE : View.GONE);
        tools.setVisibility(editing ? View.VISIBLE : View.GONE);
        // At the limit Add widget looks disabled; a tap shows the limit as its tooltip.
        boolean full = config.widgets.size() >= WIDGET_LIMIT;
        add.setAlpha(full ? .42f : 1f);
        add.setTooltipText(full ? WIDGET_LIMIT_TEXT : null);
        add.setContentDescription(full ? T.dashboardAddWidgetLimit(WIDGET_LIMIT_TEXT) : T.dashboardAddWidget());
        status.setClickable(!editing);
        for (DashboardConfig.Widget widget : config.widgets) {
            Tile tile = new Tile(widget);
            tiles.add(tile);
            grid.addView(tile, new ViewGroup.LayoutParams(-1, -2));
        }
        grid.requestLayout();
        grid.invalidate();
        refresh();
    }

    private GradientDrawable roundDisc() {
        GradientDrawable disc = new GradientDrawable();
        disc.setShape(GradientDrawable.OVAL);
        disc.setColor(ui.dark ? ui.surface : ui.raised);
        return disc;
    }

    @Override
    public void refresh() {
        if (closed) return;
        ScooterProfiles.Profile current = service == null ? null : service.profiles.active();
        String id = current == null ? "unconfigured" : current.id;
        if (!profileId.equals(id)) {
            host.rebuild();
            return;
        }
        // This scooter's model became known (or changed): its untouched default tiles follow, but never mid-edit.
        if (!editing && !modelIdentity.equals(identity(current))) {
            modelIdentity = identity(current);
            java.util.List<String> tiles = current == null ? ModelTiles.DEFAULT : ModelTiles.settingTiles(current.savedDescriptor());
            config.modelTiles(tiles);
            if (reconcileModelTiles(tiles, current == null ? null : ModelTiles.previousSettingTiles(current.savedDescriptor()))) { render(); return; }
        }
        boolean live = service != null && service.ble.ready();
        long last = 0;
        Map<String, TelemetryHistory.Reading> readings = readings();
        for (TelemetryHistory.Reading r : readings.values())
            last = Math.max(last, r.capturedWallMs);
        refreshHeader(current, readings);
        chargingHero.refresh(readings);
        Map<String, TelemetryHistory.Reading> forecast = service == null ? readings : service.dashboardReadings();
        // Every current-value widget follows the chosen range source. Recorded trends keep
        // the scooter's observations; substituting just their live tail would mix two series.
        for (Tile tile : tiles) tile.refresh(tile.widget.type.equals("live") || tile.widget.type.equals("history")
                ? readings : forecast);
        grid.updateEditorMotion();
    }

    /** Home status line: Connected · drive mode, Connecting… or Disconnected · last seen. */
    private void refreshHeader(
            ScooterProfiles.Profile profile, Map<String, TelemetryHistory.Reading> readings) {
        BleController ble = service == null ? null : service.ble;
        // While simulating, the simulated model's name (display only; the profile's stored name is untouched).
        String simulated = ble == null ? null : ble.simulationName();
        profileName.setText(simulated != null ? simulated : profile == null ? T.dashboardYourScooter() : profile.name);
        boolean ready = ble != null && ble.ready();
        BleController.State state = ble == null ? null : ble.state();
        boolean connecting =
                ble != null
                        && !ready
                        && (ble.connected()
                                || state == BleController.State.CONNECTING
                                || state == BleController.State.AUTHENTICATING
                                || state == BleController.State.SCANNING
                                || state == BleController.State.WAITING);
        long lastConnected = profile == null ? 0 : profile.lastConnected;
        // Last seen is the newer of the last connection and the newest captured reading.
        for (TelemetryHistory.Reading r : readings.values())
            if (r != null && r.value != null) lastConnected = Math.max(lastConnected, r.capturedWallMs);
        // Keep the same age reference while editing even though its status line is hidden.
        // Revealing duplicate card footers would resize their content beneath the edit handles.
        headerSeenMs = !ready && !connecting ? lastConnected : 0;
        // Done, undo/redo, the jiggle and the edit tools already show edit mode.
        status.setVisibility(editing ? View.GONE : View.VISIBLE);
        if (editing) {
            status.setClickable(false);
            return;
        }
        String mode = null;
        TelemetryHistory.Reading drive = readings.get("driving_mode");
        if (ready && drive != null && drive.live) {
            String value = format(drive);
            if (value.matches("[A-Z]+"))
                mode = value.charAt(0) + value.substring(1).toLowerCase(Locale.ROOT);
        }
        if (connecting) ui.statusRungs(status, connectingLabel());
        status.setClickable(true);
        boolean seen = !ready && lastConnected > 0;
        if (!connecting)
            ui.statusRungs(status, DashboardWords.statusRungs(ready, mode, seen
                    ? DisplayTime.recent(host.activity(), preferences, lastConnected, System.currentTimeMillis()) : null));
        status.setContentDescription(DashboardWords.statusSpoken(ready, connecting, mode, seen && !connecting
                ? DisplayTime.dateTime(host.activity(), preferences, lastConnected) : null));
    }

    /** An inline spinner keeps "Connecting…" on its single status line. */
    private CharSequence connectingLabel() {
        android.text.SpannableString text = new android.text.SpannableString("Connecting…  ￼");
        // English keeps the line above; U+FFFC, the spinner's place, cannot be catalog text (GlyphCoverageTest).
        if (!Fmt.legacy()) text = new android.text.SpannableString(DashboardWords.connectingText());
        TextView owner = status;
        android.graphics.drawable.Drawable spinner =
                new android.graphics.drawable.Drawable() {
                    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

                    @Override
                    public void draw(Canvas canvas) {
                        RectF arc = new RectF(getBounds());
                        arc.inset(ui.dp(2), ui.dp(2));
                        paint.setStyle(Paint.Style.STROKE);
                        paint.setStrokeWidth(ui.dp(2));
                        paint.setStrokeCap(Paint.Cap.ROUND);
                        paint.setColor(ui.raised);
                        canvas.drawOval(arc, paint);
                        paint.setColor(ui.accent);
                        boolean motion = android.animation.ValueAnimator.areAnimatorsEnabled();
                        float start = motion ? (android.os.SystemClock.uptimeMillis() % 1000) * .36f : -90;
                        canvas.drawArc(arc, start, 90, false, paint);
                        if (motion && owner.isShown()) owner.postInvalidateDelayed(32);
                    }

                    @Override
                    public void setAlpha(int alpha) {
                        paint.setAlpha(alpha);
                    }

                    @Override
                    public void setColorFilter(ColorFilter filter) {
                        paint.setColorFilter(filter);
                    }

                    @Override
                    public int getOpacity() {
                        return PixelFormat.TRANSLUCENT;
                    }
                };
        int size = Math.round(owner.getTextSize());
        spinner.setBounds(0, 0, size, size);
        text.setSpan(
                new android.text.style.ImageSpan(
                        spinner, android.text.style.DynamicDrawableSpan.ALIGN_BASELINE),
                text.length() - 1,
                text.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return text;
    }

    private Map<String, TelemetryHistory.Reading> readings() {
        return service == null ? Collections.emptyMap() : service.displayReadings();
    }

    private String when(long time) {
        return DisplayTime.recent(host.activity(), preferences, time, System.currentTimeMillis());
    }

    /**
     * The battery overview card's description: the level (or "not reported"), then the spoken line (charging or the
     * range left) when there is one, then "last seen" with its time when {@code seen} is given (the level is not live).
     * Scooter › Battery & charging speaks a last-seen level the same way (HubBatterySummary).
     */
    static Msg overviewSpoken(Double level, String amount, String said, String seen) {
        return level == null
                ? (said.isEmpty()
                        ? (seen != null ? M.dashboardOverviewNotReportedSeen(seen) : M.dashboardOverviewNotReported())
                        : (seen != null ? M.dashboardOverviewNotReportedLineSeen(said, seen) : M.dashboardOverviewNotReportedLine(said)))
                : (said.isEmpty()
                        ? (seen != null ? M.dashboardOverviewPercentSeenCount(Math.round(level), amount, seen) : M.dashboardOverviewPercentCount(Math.round(level), amount))
                        : (seen != null ? M.dashboardOverviewPercentLineSeenCount(Math.round(level), amount, said, seen) : M.dashboardOverviewPercentLineCount(Math.round(level), amount, said)));
    }

    /** A card's compact age beside its clock glyph: now, 5 min, 3 h or 2 d. */
    private static String shortAge(long time) {
        long minutes = Math.max(0, System.currentTimeMillis() - time) / 60000;
        if (minutes < 1) return T.dashboardAgeNow();
        if (minutes < 60) return T.dashboardAgeMinutes(Fmt.integer(minutes));
        if (minutes < 24 * 60) return T.dashboardAgeHours(Fmt.integer(minutes / 60));
        return T.dashboardAgeDays(Fmt.integer(minutes / (24 * 60)));
    }

    private int color(DashboardConfig.Widget widget) {
        return new int[] {ui.accent, ui.blue, ui.orange, ui.danger}[widget.color];
    }

    private String units() {
        return preferences.getString("units", "mi");
    }

    private String temperatureUnit() {
        return preferences.getString("temperature_unit", "c");
    }

    private String format(TelemetryHistory.Reading r) {
        if (r == null || r.value == null) return "—";
        if (r.key.equals("driving_mode") && r.number != null) {
            ScooterProfiles.Profile profile = service == null ? null : service.profiles.active();
            return Telemetry.drivingModeLabel(ModelRegistry.driveModeLabelProduct(service == null ? null : service.modelDescriptor(profile)), r.number);
        }
        if (r.number != null) {
            if (DisplayTemperature.isTemperature(r.key))
                return DisplayTemperature.format(r.number, temperatureUnit(), 0);
            if (r.key.endsWith(SPEED_KEY)) return DisplayUnits.speed(r.number, units());
            if (r.key.endsWith("_km"))
                return DisplayUnits.metricText(
                        String.format(Locale.US, "%.2f km", r.number), units());
        }
        return DisplayUnits.metricText(r.value, units());
    }

    /** "about 31 mi": the range reading rounded, in the rider's distance units. */
    private String rangeText(TelemetryHistory.Reading r) {
        if (r == null || r.value == null) return "";
        if (r.number == null) return aboutRange(format(r));
        String km = T.fmtQuantity(Fmt.decimal(Locale.US, r.number, 0), T.unitsKm());
        String miles = T.fmtQuantity(Fmt.decimal(Locale.US, r.number / 1.609344, 0), T.unitsMi());
        String units = units();
        return aboutRange((units.equals("km") ? km : units.equals("dual") ? T.fmtDual(miles, km) : miles));
    }

    /**
     * A range figure as the battery cards give it, an estimate: "about 31 mi". Battery & charging's summary
     * (HubBatterySummary) speaks its range in this form too, so its spoken and drawn lines agree with Home's card.
     */
    static String aboutRange(CharSequence range) { return T.dashboardAbout(range); }

    private double chartNumber(String key, double value) {
        if (DisplayTemperature.isTemperature(key))
            return DisplayTemperature.fromCelsius(value, temperatureUnit());
        return !units().equals("km") && (key.endsWith("_km") || key.endsWith("_kmh"))
                ? value / 1.609344
                : value;
    }

    /** How a speed reading's key ends. */
    private static final String SPEED_KEY = "_kmh";

    /**
     * format(r) with number and unit parts where the reading has them (speed and temperature, whose text comes from a
     * language's fmt_quantity), so withUnit need not search the text for them; else plain, its text split at a space.
     */
    private Formatted shown(TelemetryHistory.Reading r) {
        Formatted parts = parts(r);
        return parts != null ? parts : Formatted.plain(format(r));
    }

    private Formatted parts(TelemetryHistory.Reading r) {
        if (r == null || r.value == null || r.number == null) return null;
        if (DisplayTemperature.isTemperature(r.key)) return DisplayTemperature.quantity(r.number, temperatureUnit(), 0);
        return r.key.endsWith(SPEED_KEY) ? DisplayUnits.speedQuantity(r.number, units()) : null;
    }

    /** Large value with its unit drawn smaller (and muted when live), e.g. 412 W: ReadingUnits.smallPart says where. */
    private CharSequence withUnit(Formatted value, boolean live) {
        String shown = value.toString();
        int[] small = ReadingUnits.smallPart(shown, value.numbers() == 0 && value.units() == 0 ? null : value);
        if (small == null) return shown;
        android.text.SpannableString text = new android.text.SpannableString(shown);
        text.setSpan(new android.text.style.RelativeSizeSpan(.52f), small[0], small[1],
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (live)
            text.setSpan(new android.text.style.ForegroundColorSpan(ui.muted), small[0], small[1],
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return text;
    }

    /**
     * The chart's unit as data ("°C", "km/h"): Ui.ChartView passes it to ChartScale.range, which matches
     * UnitCode data codes, and also draws it. So it stays data (not DisplayTemperature.symbol()) until
     * Ui.ChartView takes a data unit and a shown symbol apart. The scale rows show {@link #shownUnit}.
     */
    private String chartUnit(String key, Map<String, TelemetryHistory.Reading> values) {
        if (DisplayTemperature.isTemperature(key)) return DisplayTemperature.unit(temperatureUnit());
        if (key.endsWith("_kmh")) return units().equals("km") ? "km/h" : "mph";
        if (key.endsWith("_km")) return units().equals("km") ? "km" : "mi";
        TelemetryHistory.Reading r = values.get(key);
        return r == null ? "" : r.unit;
    }

    /** chartUnit's unit as a person reads it: a known data unit's symbol in the current language, else as reported. */
    private static String shownUnit(String data) {
        for (UnitCode code : UnitCode.values()) if (code.matches(data)) return code.symbol();
        return data;
    }

    /** The capacity widget's summary without a range: how many rides and how many battery points they used. */
    private static String capacityPoints(int rides, double points) {
        return T.dashboardCapacitySummary(T.dashboardCapacityRides(rides, Fmt.integer(rides)),
                T.dashboardCapacityPointsUsed(Math.round(points), Fmt.decimal(Locale.US, points, 0)));
    }

    private String label(String key) {
        if (key.equals("remaining_range_km")) return T.dashboardEstimatedRange();
        if (key.equals("driving_mode")) return T.dashboardDriveMode();
        for (Telemetry.Metric m : new Telemetry().metrics())
            if (m.key.equals(key)) return m.label.endsWith(TELEMETRY_SIGNED) ? ScooterReadingLabels.label(key, m.label.replace(TELEMETRY_SIGNED, "")) : ScooterReadingLabels.full(key, m.label);
        return key;
    }

    /**
     * The English of what label(key) shows, for comparing a saved widget title (stored English) by
     * id: a title equal to its reading's name hides the repeated subtitle in every language.
     */
    private static String englishLabel(String key) {
        if (key.equals("remaining_range_km")) return M.dashboardEstimatedRange().english();
        if (key.equals("driving_mode")) return M.dashboardDriveMode().english();
        for (Telemetry.Metric m : new Telemetry().metrics())
            if (m.key.equals(key)) return m.label.replace(TELEMETRY_SIGNED, "");
        return key;
    }

    private String widgetIcon(String type) {
        switch (type) {
            case "lock":
                return "lock";
            case "scooter":
                return "scooter";
            case "battery":
                return "battery";
            case "metric":
                return "text";
            case "gauge":
                return "speed";
            case "live":
                return "trend";
            case "history":
                return "stats";
            case "readings":
                return "list";
            case "trip":
                return "rides";
            case "health":
                return "heart";
            case "capacity":
                return "lab";
            case "charging":
                return "bolt";
            case "quick":
                return "grid";
            case "ride":
                return "record";
            case "note":
                return "note";
            case "lockbar":
                return "lock";
            case "overview":
                return "battery";
            case "tiles":
                return "grid";
            case "shortcuts":
                return "bolt";
            default:
                return "stats";
        }
    }

    private String readingIcon(String key) {
        if (key.equals("remaining_range_km")) return "route";
        if (key.equals("power_w")) return "bolt";
        if (key.equals("battery_health_percent")) return "heart";
        if (key.equals("temperature_c")) return "temperature";
        if (key.equals("odometer_km") || key.equals("trip_km")) return "route";
        return ui.featureIcon(key);
    }

    private DashboardCaption readingCaption(String key) {
        String full = label(key), normal = ScooterReadingLabels.label(key, full);
        return new DashboardCaption(host.activity(), ui, full, normal,
                ScooterReadingLabels.compact(key, normal), glyphDrawable(readingIcon(key), ui.muted, 18));
    }

    /** The active scooter's saved model (the simulated one while simulating), which decides its photo independent of live readings. */
    private DeviceDescriptor activeModel() {
        ScooterProfiles.Profile p = service == null ? null : service.profiles.active();
        return service == null ? null : service.modelDescriptor(p);
    }

    private String duration(Double minutes) {
        if (minutes == null || !Double.isFinite(minutes)) return "—";
        long value = Math.max(0, Math.round(minutes));
        return value == 0
                ? T.dashboardAtTarget()
                : (value >= 60 ? T.dashboardDurationHours(Fmt.integer(value / 60), Fmt.integer(value % 60)) : T.dashboardDurationMinutes(Fmt.integer(value % 60)));
    }

    private ChargingEstimate.Value chargeValue(
            Map<String, TelemetryHistory.Reading> values, String key) {
        TelemetryHistory.Reading r = values.get(key);
        return new ChargingEstimate.Value(
                r == null ? null : r.number, r != null && r.live, r == null ? 0 : r.capturedWallMs);
    }

    /** Time to the charge alert's level for the charging hero, or null where there is none to show. */
    private ChargingEstimate.Result chargeAlertEstimate(Map<String, TelemetryHistory.Reading> values) {
        ScooterService service = host.service();
        return service == null ? null : service.chargeAlertEstimate(values);
    }

    private ChargingEstimate.Result chargingEstimate(Map<String, TelemetryHistory.Reading> values) {
        ScooterService service = host.service();
        if (service != null) return service.chargingEstimate(values);
        return ChargingEstimate.calculate(
                chargeValue(values, "charging_state"),
                chargeValue(values, "voltage_v"),
                chargeValue(values, "current_a"),
                chargeValue(values, "battery_percent"),
                chargeValue(values, "rated_capacity_ah"),
                chargeValue(values, "charge_limit_percent"));
    }

    /** Line glyphs: the shared Ui set plus a few dashboard-only shapes. */
    private void drawGlyph(Canvas c, String key, int color, float cx, float cy, float size) {
        if (!Arrays.asList("grid", "note", "record", "stop", "trend", "restore", "list", "undo", "redo", "toggle").contains(key)) {
            ui.drawIcon(c, key, color, cx, cy, size);
            return;
        }
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.8f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        c.save();
        c.translate(cx - size / 2, cy - size / 2);
        c.scale(size / 24f, size / 24f);
        Path p = new Path();
        switch (key) {
            case "undo":
            case "redo":
                if (key.equals("redo")) c.scale(-1, 1, 12, 12);
                p.moveTo(9, 4); p.lineTo(4, 9); p.lineTo(9, 14);
                p.moveTo(4, 9); p.lineTo(13, 9); p.cubicTo(22, 9, 22, 20, 13, 20);
                c.drawPath(p, paint);
                break;
            case "grid":
                for (int x = 3; x <= 14; x += 11)
                    for (int y = 3; y <= 14; y += 11)
                        c.drawRoundRect(new RectF(x, y, x + 7, y + 7), 1.5f, 1.5f, paint);
                break;
            case "note":
                c.drawRoundRect(new RectF(5, 3, 19, 21), 2, 2, paint);
                c.drawLine(8.5f, 8.5f, 15.5f, 8.5f, paint);
                c.drawLine(8.5f, 12.5f, 15.5f, 12.5f, paint);
                c.drawLine(8.5f, 16.5f, 13, 16.5f, paint);
                break;
            case "record":
                c.drawCircle(12, 12, 8, paint);
                paint.setStyle(Paint.Style.FILL);
                c.drawCircle(12, 12, 3.6f, paint);
                break;
            case "stop":
                paint.setStyle(Paint.Style.FILL);
                c.drawRoundRect(new RectF(6, 6, 18, 18), 2.5f, 2.5f, paint);
                break;
            case "trend":
                p.moveTo(2, 13);
                p.lineTo(6, 13);
                p.lineTo(9, 6);
                p.lineTo(13, 18);
                p.lineTo(16, 10);
                p.lineTo(18, 13);
                p.lineTo(22, 13);
                c.drawPath(p, paint);
                break;
            case "restore":
                c.drawArc(new RectF(4, 4, 20, 20), -150, 150, false, paint);
                c.drawArc(new RectF(4, 4, 20, 20), 30, 150, false, paint);
                p.moveTo(20, 3.5f);
                p.lineTo(20, 9);
                p.lineTo(14.5f, 9);
                p.moveTo(4, 20.5f);
                p.lineTo(4, 15);
                p.lineTo(9.5f, 15);
                c.drawPath(p, paint);
                break;
            case "toggle":
                c.drawRoundRect(new RectF(2.5f, 7, 21.5f, 17), 5, 5, paint);
                paint.setStyle(Paint.Style.FILL);
                c.drawCircle(16.5f, 12, 2.8f, paint);
                paint.setStyle(Paint.Style.STROKE);
                break;
            case "list":
                for (int y = 6; y <= 18; y += 6) {
                    c.drawLine(9, y, 20, y, paint);
                    paint.setStyle(Paint.Style.FILL);
                    c.drawCircle(4.5f, y, 1.3f, paint);
                    paint.setStyle(Paint.Style.STROKE);
                }
                break;
        }
        c.restore();
    }

    /** A non-interactive icon view sized by its layout parameters. */
    private final class GlyphView extends View {
        String key;
        int color;

        GlyphView(String key, int color) {
            super(host.activity());
            this.key = key;
            this.color = color;
            setContentDescription(key);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void set(String key, int color) {
            if (this.key.equals(key) && this.color == color) return;
            this.key = key;
            this.color = color;
            setContentDescription(key);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float size = Math.min(getWidth(), getHeight());
            if (size > 0) drawGlyph(canvas, key, color, getWidth() / 2f, getHeight() / 2f, size);
        }
    }

    /** A rounded square holding a glyph, at the start of rows (palette, capacity, layout). */
    private FrameLayout square(String key, int color, int sizeDp) {
        FrameLayout box = new FrameLayout(host.activity());
        box.setBackground(ui.shape(ui.dark ? ui.bg : ui.raised, 10, 0));
        int glyph = Math.round(sizeDp * .5f);
        box.addView(
                new GlyphView(key, color),
                new FrameLayout.LayoutParams(ui.dp(glyph), ui.dp(glyph), Gravity.CENTER));
        box.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return box;
    }

    /** A plain (unboxed) glyph as a TextView compound drawable. */
    private android.graphics.drawable.Drawable glyphDrawable(String key, int color, int sizeDp) {
        android.graphics.drawable.Drawable icon =
                new android.graphics.drawable.Drawable() {
                    @Override
                    public void draw(Canvas canvas) {
                        Rect b = getBounds();
                        drawGlyph(canvas, key, color, b.exactCenterX(), b.exactCenterY(),
                                Math.min(b.width(), b.height()));
                    }

                    @Override
                    public void setAlpha(int alpha) {}

                    @Override
                    public void setColorFilter(ColorFilter filter) {}

                    @Override
                    public int getOpacity() {
                        return PixelFormat.TRANSLUCENT;
                    }
                };
        icon.setBounds(0, 0, ui.dp(sizeDp), ui.dp(sizeDp));
        return icon;
    }

    private final class CapacityBar extends View {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Double percent;
        CapacityBar() { super(host.activity()); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
        void set(Double value) { percent = value; invalidate(); }
        @Override protected void onDraw(Canvas canvas) {
            float radius = getHeight() / 2f;
            paint.setColor(ui.raised);
            canvas.drawRoundRect(0, 0, getWidth(), getHeight(), radius, radius, paint);
            if (percent == null || !Double.isFinite(percent)) return;
            paint.setColor(percent > 100 ? ui.orange : ui.accent);
            float width = (float)(getWidth() * Math.max(0, Math.min(100, percent)) / 100);
            canvas.drawRoundRect(0, 0, width, getHeight(), radius, radius, paint);
        }
    }

    /** Fit from the available width, never from a previous WRAP_CONTENT height. */
    private final class FittedValue extends TextView {
        final int minimumSp, maximumSp, targetLines;
        int fittedWidth = -1;
        String fittedText;
        float fittedPixels;
        int heightBudget = Integer.MAX_VALUE;
        int sharedMaximumSp = Integer.MAX_VALUE;

        FittedValue(String text, int minimum, int maximum, int lines) {
            super(host.activity());
            minimumSp = (int) Math.ceil(minimum * ui.textScale);
            maximumSp = Math.max(minimumSp, Math.round(maximum * ui.textScale));
            targetLines = lines;
            setText(text);
            setTextColor(ui.text);
            setTypeface(ui.bold());
            setIncludeFontPadding(false);
            setLineSpacing(ui.dp(3), 1);
            setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, maximumSp);
            // At the readable floor, long custom text wraps into the card's
            // scroller instead of disappearing behind a maximum-line limit.
        }

        void fitHeight(int pixels) {
            int next = Math.max(1, pixels);
            if (heightBudget == next) return;
            heightBudget = next;
            fittedWidth = -1;
            requestLayout();
        }

        void capAt(int sizeSp) {
            if (sharedMaximumSp == sizeSp) return;
            sharedMaximumSp = sizeSp;
            fittedWidth = -1;
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec)
                    - getCompoundPaddingLeft() - getCompoundPaddingRight();
            String text = getText().toString();
            float density = getResources().getDisplayMetrics().scaledDensity;
            if (width != fittedWidth || !text.equals(fittedText)) {
                fittedWidth = width;
                fittedText = text;
                int ceiling = Math.min(maximumSp, sharedMaximumSp);
                fittedPixels = ceiling * density;
                if (width > 0 && MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED) {
                    for (int size = ceiling; size >= minimumSp; size--) {
                        fittedPixels = size * density;
                        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, fittedPixels);
                        // Probe this TextView's actual layout, including styled units,
                        // font padding and platform fallback-line spacing. A separate
                        // StaticLayout can be a few pixels shorter than BoringLayout.
                        // An unconstrained height also avoids mistaking clipping for fit.
                        super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                        android.text.Layout layout = getLayout();
                        int count = layout == null ? 0 : layout.getLineCount();
                        boolean fits = layout != null && count <= targetLines
                                && getMeasuredHeight() <= heightBudget
                                && (count == 0 || layout.getLineEnd(count - 1) == text.length());
                        for (int line = 0; fits && line < count; line++)
                            fits = layout.getLineMax(line) <= width;
                        if (fits) break;
                    }
                }
            }
            if (getTextSize() != fittedPixels)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, fittedPixels);
            super.onMeasure(widthSpec, heightSpec);
        }
    }


    /** A small pill beside a value for a short state such as Low current. */
    private TextView tagChip() {
        TextView chip = ui.text("", 11, false);
        chip.setTypeface(ui.medium());
        chip.setGravity(Gravity.CENTER);
        chip.setMaxLines(1);
        chip.setPadding(ui.dp(8), ui.dp(3), ui.dp(8), ui.dp(3));
        // The value's description carries the same state for TalkBack.
        chip.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        chip.setVisibility(View.GONE);
        return chip;
    }

    /** A tappable pill (a 48 dp target around a smaller visible shape), e.g. Set reference. */
    private TextView actionPill(String label, String glyph, int color, Runnable action) {
        TextView chip = ui.text(label, 12.5f, false);
        chip.setTypeface(ui.medium());
        chip.setTextColor(color);
        chip.setGravity(Gravity.CENTER);
        chip.setMaxLines(1);
        chip.setMinHeight(ui.dp(48));
        chip.setPadding(ui.dp(12), 0, ui.dp(12), 0);
        chip.setCompoundDrawablesRelative(glyphDrawable(glyph, color, 14), null, null, null);
        chip.setCompoundDrawablePadding(ui.dp(5));
        chip.setBackground(ripple(Ui.withAlpha(color, 40), new android.graphics.drawable.InsetDrawable(
                ui.shape(Ui.withAlpha(color, ui.dark ? 40 : 26), 100, 0), 0, ui.dp(7), 0, ui.dp(7)), null));
        chip.setClickable(true);
        chip.setFocusable(true);
        chip.setOnClickListener(v -> action.run());
        return chip;
    }

    private void styleTag(TextView chip, String text, boolean warning) {
        boolean shown = text != null && !text.isEmpty();
        if (!(shown ? text : "").contentEquals(chip.getText())) chip.setText(shown ? text : "");
        chip.setVisibility(shown ? View.VISIBLE : View.GONE);
        int ink = warning ? ui.orange : ui.muted;
        chip.setTextColor(ink);
        chip.setBackground(ui.shape(Ui.withAlpha(ink, ui.dark ? 44 : 30), 100, 0));
    }

    /** Full, normal and compact names for the charging time; an unknown cutoff never claims "full". */
    private String[] chargeTimeLabels(ChargingEstimate.Result estimate, boolean widget) {
        return chargeTimeLabels(estimate.targetPercent, widget);
    }

    /** The same names for a cutoff alone (null: unknown, so "100% if uncapped"). */
    private String[] chargeTimeLabels(Double targetPercent, boolean widget) {
        return chargeTimeLabels(targetPercent, widget, false);
    }

    /**
     * plainHundred names a 100% cutoff as a plain percentage ("Time to 100%", "To 100%"): the charging time tile's
     * second reading beside a limit or an alert, where the charging view already says what it charges to (owner,
     * Nov 12 2026: "if uncapped" is redundant there).
     */
    private String[] chargeTimeLabels(Double targetPercent, boolean widget, boolean plainHundred) {
        if (targetPercent != null && (targetPercent < 100 || plainHundred)) {
            String target = T.fmtPercent(Fmt.decimal(Locale.US, targetPercent, 0));
            String name = widget ? T.dashboardChargeToLimit(target) : T.dashboardChargeTimeTo(target);
            return new String[] {name, name, T.dashboardChargeTimeToCompact(target)};
        }
        if (targetPercent == null)
            return new String[] {T.dashboardChargeTimeUncapped(), T.dashboardChargeTimeUncappedShort(), T.dashboardChargeTimeUncappedCompact()};
        return new String[] {T.dashboardChargeTimeFull(), T.dashboardChargeTimeFullShort(), T.dashboardChargeTimeFullCompact()};
    }

    /**
     * The time slot: "≈ 1h 20m", otherwise a short state. Low current
     * keeps the dash with an orange chip beside it. Without a tag slot, the state replaces the dash.
     */
    private void showChargeTime(TextView value, TextView tag, ChargingEstimate.Result estimate) {
        boolean limit = estimate.targetPercent != null && estimate.targetPercent < 100;
        showChargeTime(value, tag, estimate, limit ? estimate.remainingMinutesToLimit : estimate.remainingMinutesToFull);
    }

    /** The same slot for a chosen time (the charging view's limit, alert or 100% reading), with the estimate's state. */
    private void showChargeTime(TextView value, TextView tag, ChargingEstimate.Result estimate, Double minutes) {
        boolean low = ChargingEstimate.LOW_CURRENT.equals(estimate.label);
        String state = estimate.label == null || estimate.label.isEmpty() ? "—" : estimate.label;
        // The state as shown: Low current in the current language, any other state its own display message.
        if (state == estimate.label) state = low ? T.dashboardLowCurrent() : estimate.labelMsg.render();
        value.setText(minutes != null ? duration(minutes) : low && tag != null ? "—" : state);
        value.setTextColor(minutes == null ? ui.muted : ui.text);
        if (tag != null)
            styleTag(tag, minutes == null && low ? state : null,
                    minutes == null && low);
    }

    /** Compact, labeled metric cards shared by charging and the reading-list widget. */
    /** A metric card's label row (icon and caption) and its value: an InfoCell's body, and each charge time reading. */
    private final class InfoFace {
        final LinearLayout labelRow;
        final View icon;
        final DashboardCaption label;
        final FittedValue value;
        InfoFace(String iconName, String full, String normal, String compact) {
            labelRow = row();
            labelRow.setGravity(Gravity.CENTER_VERTICAL);
            icon = new GlyphView(iconName, ui.muted);
            LinearLayout.LayoutParams iconSpace = new LinearLayout.LayoutParams(ui.dp(15), ui.dp(15));
            iconSpace.setMarginEnd(ui.dp(5));
            labelRow.addView(icon, iconSpace);
            label = new DashboardCaption(host.activity(), ui, full, normal, compact,
                    glyphDrawable(iconName, ui.muted, 18));
            labelRow.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
            // Keep ordinary number/unit pairs together before permitting a
            // readable-floor wrap for unusually long diagnostic values.
            value = new FittedValue("—", 12, 26, 1);
            value.setIncludeFontPadding(true);
            value.setGravity(Gravity.CENTER_VERTICAL);
            value.setMinHeight(ui.dp(48 * ui.textScale));
        }
        LinearLayout.LayoutParams valueSpace() {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
            p.topMargin = ui.dp(2);
            return p;
        }
    }

    private class InfoCell extends LinearLayout {
        final TextView label, value;
        final View labelIcon;
        /** Optional pill beside the value for a short state. */
        TextView tag;
        int heightBudget = Integer.MAX_VALUE;
        /**
         * Clean's two charging tiles (ChargingFit.tile): no icon, the Layout Plan's 12 dp inset at every width (their
         * value gets smaller instead). Other tiles lose the icon and half the inset when narrow.
         */
        boolean cleanCharging;
        InfoCell(String icon, String full, String normal, String compact) {
            super(host.activity());
            setOrientation(VERTICAL);
            setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));
            setBackground(ui.shape(ui.raised, 14, 0));
            InfoFace face = new InfoFace(icon, full, normal, compact);
            labelIcon = face.icon;
            label = face.label;
            addView(face.labelRow, new LinearLayout.LayoutParams(-1, -2));
            value = face.value;
            addView(value, face.valueSpace());
        }
        /** A cell made with one title gets a shorter narrow label (the charging power's "Power"). */
        InfoCell narrowLabel(String title, String compact) {
            ((DashboardCaption) label).setLabels(title, title, compact);
            return this;
        }
        /** Puts a pill at the end of the value line; it wraps below a long value in a narrow cell. */
        TextView addTag() {
            if (tag != null) return tag;
            LinearLayout.LayoutParams old = (LinearLayout.LayoutParams) value.getLayoutParams();
            removeView(value);
            tag = tagChip();
            addView(tagLine(value, tag), old);
            return tag;
        }
        /** A value with its state pill at the end of the line; the pill wraps below a long value in a narrow cell. */
        LinearLayout tagLine(final TextView value, final TextView chip) {
            LinearLayout line = new LinearLayout(host.activity()) {
                @Override protected void onMeasure(int widthSpec, int heightSpec) {
                    int width = MeasureSpec.getSize(widthSpec);
                    // "—" with Low current always fits; a time keeps ~96 dp before its tag wraps.
                    boolean beside = chip.getVisibility() == GONE || value.length() <= 2;
                    if (!beside) {
                        chip.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                        float scale = Math.max(1, ui.textScale * getResources().getConfiguration().fontScale);
                        beside = width - chip.getMeasuredWidth() - ui.dp(6) >= ui.dp(96 * scale);
                    }
                    if (beside != (getOrientation() == HORIZONTAL)) {
                        setOrientation(beside ? HORIZONTAL : VERTICAL);
                        LinearLayout.LayoutParams v = (LinearLayout.LayoutParams) value.getLayoutParams();
                        v.width = beside ? 0 : -1;
                        v.weight = beside ? 1 : 0;
                        LinearLayout.LayoutParams c = (LinearLayout.LayoutParams) chip.getLayoutParams();
                        c.setMarginStart(beside ? ui.dp(6) : 0);
                        c.bottomMargin = beside ? 0 : ui.dp(4);
                        c.gravity = beside ? Gravity.CENTER_VERTICAL : Gravity.START;
                    }
                    super.onMeasure(widthSpec, heightSpec);
                }
            };
            line.setOrientation(HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setBaselineAligned(false);
            line.addView(value, new LinearLayout.LayoutParams(0, -2, 1));
            LinearLayout.LayoutParams chipSpace = new LinearLayout.LayoutParams(-2, -2);
            chipSpace.setMarginStart(ui.dp(6));
            chipSpace.gravity = Gravity.CENTER_VERTICAL;
            line.addView(chip, chipSpace);
            return line;
        }
        void fitHeight(int pixels) {
            heightBudget = pixels;
            value.setMinHeight(pixels == Integer.MAX_VALUE ? ui.dp(48 * ui.textScale) : 0);
            ((FittedValue) value).fitHeight(pixels == Integer.MAX_VALUE ? pixels
                    : pixels - getChildAt(0).getMeasuredHeight() - ui.dp(10));
        }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            ChargingFit.Tile fit = ChargingFit.tile(cleanCharging,
                    MeasureSpec.getSize(widthSpec) / getResources().getDisplayMetrics().density,
                    ui.textScale * getResources().getConfiguration().fontScale);
            int inset = ui.dp(fit.insetDp);
            int vertical = ui.dp(heightBudget == Integer.MAX_VALUE ? 10 : 4);
            setPadding(inset, vertical, inset, vertical);
            showIcons(fit.icon);
            super.onMeasure(widthSpec, heightSpec);
        }
        /** The label icon hides in a narrow cell, where the caption needs the room. */
        void showIcons(boolean shown) { labelIcon.setVisibility(shown ? VISIBLE : GONE); }
        /** The captions this cell can show (one; the time tile has one per face). */
        TextView[] captions() { return new TextView[] {label}; }
    }

    private final class InfoTiles extends LinearLayout {
        final ArrayList<InfoCell> cells = new ArrayList<>();
        int columns;
        /** An odd tile out sits alone on the first row rather than the last, keeping the later pair together. */
        boolean loneFirst;
        /** Two columns from this row width (dp) at any text scale; 0 keeps the usual 280 dp times the text scale. */
        int pairDp;
        InfoTiles() { super(host.activity()); setOrientation(VERTICAL); }
        /** Shows or hides one tile; the rest close up. */
        void show(InfoCell cell, boolean shown) {
            int visibility = shown ? VISIBLE : GONE;
            if (cell.getVisibility() == visibility) return;
            cell.setVisibility(visibility); columns = 0; requestLayout();
        }
        InfoCell add(String icon, String title) {
            return add(new InfoCell(icon, title, title, title));
        }
        InfoCell add(InfoCell cell) {
            cells.add(cell); columns = 0;
            requestLayout(); return cell;
        }
        InfoCell addReading(String key) {
            String full = label(key), normal = ScooterReadingLabels.label(key, full);
            InfoCell cell = new InfoCell(readingIcon(key), full, normal,
                    ScooterReadingLabels.compact(key, normal));
            cells.add(cell); columns = 0; requestLayout(); return cell;
        }
        void fitHeight(int budget) {
            if (cells.isEmpty() || columns == 0) return;
            for (InfoCell cell : cells) {
                cell.fitHeight(Integer.MAX_VALUE);
                measureCell(cell);
            }
            if (readingRowsHeight(cells, columns, ui.dp(8)) <= budget) return;
            // These are informational values, not touch targets: their usual 48dp
            // minimum must not force a half-visible first reading in a short card.
            for (InfoCell cell : cells) { cell.fitHeight(1); measureCell(cell); }
            int rows = (cells.size() + columns - 1) / columns;
            int minimum = readingRowsHeight(cells, columns, ui.dp(8));
            int extra = minimum <= budget ? (budget - minimum) / rows : -1;
            for (int i = 0; i < cells.size(); i += columns) {
                int height = extra < 0 ? budget : readingRowHeight(cells, i, columns) + extra;
                for (int j = i; j < Math.min(i + columns, cells.size()); j++) cells.get(j).fitHeight(height);
            }
        }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            ArrayList<InfoCell> shown = new ArrayList<>();
            for (InfoCell cell : cells) if (cell.getVisibility() != GONE) shown.add(cell);
            int next = MeasureSpec.getSize(widthSpec) >= ui.dp(pairDp > 0 ? pairDp : 280 * ui.textScale) ? 2 : 1;
            next = Math.min(next, Math.max(1, shown.size()));
            if (next != columns) {
                columns = next;
                for (InfoCell cell : cells) if (cell.getParent() instanceof ViewGroup)
                    ((ViewGroup)cell.getParent()).removeView(cell);
                removeAllViews();
                int inRow = loneFirst && shown.size() % columns == 1 ? 1 : columns;
                for (int i = 0; i < shown.size(); i += inRow, inRow = columns) {
                    LinearLayout line = row(); line.setBaselineAligned(false);
                    for (int j = 0; j < inRow && i + j < shown.size(); j++) {
                        // loneFirst's pair stays even when only one label wraps to two lines.
                        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, loneFirst ? -1 : -2, 1);
                        if (j > 0) p.setMarginStart(ui.dp(8));
                        line.addView(shown.get(i + j), p);
                    }
                    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
                    if (i > 0) p.topMargin = ui.dp(8);
                    addView(line, p);
                }
            }
            super.onMeasure(widthSpec, heightSpec);
            if (evenLabels()) super.onMeasure(widthSpec, heightSpec);
        }
        /**
         * Tiles side by side give their captions the same number of lines (the most any of them wraps to), so the
         * values under them share one line when only one label wraps. The line count is the text's own, whatever the
         * minimum, so this settles in one pass. True when a minimum changed and the row needs measuring again.
         */
        private boolean evenLabels() {
            boolean changed = false;
            for (int r = 0; r < getChildCount(); r++) {
                if (!(getChildAt(r) instanceof ViewGroup)) continue;
                ViewGroup line = (ViewGroup) getChildAt(r);
                ArrayList<TextView> shown = new ArrayList<>();
                int most = 1;
                for (int c = 0; c < line.getChildCount(); c++) {
                    if (!(line.getChildAt(c) instanceof InfoCell) || line.getChildAt(c).getVisibility() == GONE) continue;
                    for (TextView caption : ((InfoCell) line.getChildAt(c)).captions()) {
                        shown.add(caption);
                        if (caption.getLayout() != null) most = Math.max(most, caption.getLineCount());
                    }
                }
                int lines = shown.size() > 1 ? most : 0;
                for (TextView caption : shown)
                    // getMinLines is -1 while a minimum height is set instead; nothing to undo then.
                    if (caption.getMinLines() != lines && (lines > 0 || caption.getMinLines() > 0)) {
                        caption.setMinLines(lines);
                        changed = true;
                    }
            }
            return changed;
        }
    }

    private void measureCell(View cell) {
        cell.measure(View.MeasureSpec.makeMeasureSpec(Math.max(1, cell.getMeasuredWidth()), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
    }

    private int readingRowHeight(List<? extends View> cells, int first, int columns) {
        int height = 0;
        for (int i = first; i < Math.min(first + columns, cells.size()); i++)
            height = Math.max(height, cells.get(i).getMeasuredHeight());
        return height;
    }

    private int readingRowsHeight(List<? extends View> cells, int columns, int gap) {
        int height = 0;
        for (int i = 0; i < cells.size(); i += columns)
            height += readingRowHeight(cells, i, columns) + (i == 0 ? 0 : gap);
        return height;
    }

    /** What TalkBack and the tooltip say for a charging time: its name, the value, a shown state pill and the estimate's status. */
    private String spokenChargeTime(String name, CharSequence value, TextView tag, String status) {
        // Deferred (B5 follow-up): ChargingEstimate.Result.status has no Msg yet, so the spoken
        // charge status stays English inside these templates (here and in describeCharging below).
        boolean plain = status == null || status.isEmpty();
        if (tag != null && tag.getVisibility() == View.VISIBLE)
            return plain ? T.dashboardHeroTimeTag(name, value, tag.getText()) : T.dashboardHeroTimeTagStatus(name, value, tag.getText(), status);
        return plain ? T.dashboardHeroTime(name, value) : T.dashboardHeroTimeStatus(name, value, status);
    }

    /**
     * The charging view's one time tile (owner's design, Nov 12 2026; ChargeTimeReadings holds the rules). With two
     * readings, the time to the limit (the scooter's own, or the charge alert's level) and the time to 100%, two
     * dots in the upper end corner show which is up, and a tap or a sideways swipe brings the other. Each reading is
     * one face (label row, value and state pill), so a label never moves without its value. Both faces are always
     * measured, so the tile keeps one height whichever shows. The choice is saved per scooter as it is made; with one
     * reading this is the single time tile it replaced: no dots, no switching, the same speech.
     */
    private final class ChargeTimeTile extends InfoCell {
        static final int LIMIT = ChargeTimeReadings.LIMIT, FULL = ChargeTimeReadings.FULL;
        final InfoFace limitFace;
        final LinearLayout[] faces = new LinearLayout[2];
        final View[] rows = new View[2];
        final DashboardCaption[] captions = new DashboardCaption[2];
        final TextView[] values = new TextView[2], tags = new TextView[2];
        final String[] spoken = {"", ""};
        final FrameLayout stack;
        final ChargeTimeReadings.Switch state = new ChargeTimeReadings.Switch();
        final Paint dots = new Paint(Paint.ANTI_ALIAS_FLAG);
        final int slop;
        final float minFling;
        int preferred;
        boolean two;
        /**
         * Clean's charging view: one fixed reading, as the Layout Plan's mock-up has it (no dots, no switching): the time
         * to the scooter's own limit below 100% ("Time to 90%"), else the time to full (ChargingFit.timeToLimit).
         */
        boolean fixed;
        android.animation.ValueAnimator motion;
        VelocityTracker velocity;
        float downX, downY, dragFromX;
        /** dragging: a sideways drag owns the gesture; passing: it moved up or down first, so it is no tap. */
        boolean dragging, passing;
        /** owning: this gesture began while switchable, so the tile takes it whatever child is under the finger;
         *  holding: the parents (Home's scroller) are held off until the gesture's direction is known. */
        boolean owning, holding;
        /** The switch under way began as a drag: its faces keep following the finger's scale to the end. */
        boolean fromDrag;
        /** The touch under way: the tile's own, the plain tile's, or cut off by a reset until the next one begins. */
        final ChargeTimeReadings.Touch touch = new ChargeTimeReadings.Touch();

        ChargeTimeTile(String title) {
            super("clock", title, title, title);
            addTag();
            // InfoCell built the 100% face (label row, value line); the limit face is built the same way beside it.
            rows[FULL] = getChildAt(0);
            View fullLine = getChildAt(1);
            removeAllViews();
            captions[FULL] = (DashboardCaption) label; values[FULL] = value; tags[FULL] = tag;
            limitFace = new InfoFace("clock", title, title, title);
            rows[LIMIT] = limitFace.labelRow;
            captions[LIMIT] = limitFace.label; values[LIMIT] = limitFace.value; tags[LIMIT] = tagChip();
            stack = new FrameLayout(host.activity());
            for (int r : new int[] {LIMIT, FULL}) {
                LinearLayout face = column();
                face.addView(rows[r]);
                face.addView(r == FULL ? fullLine : tagLine(values[r], tags[r]), r == FULL ? fullLine.getLayoutParams() : limitFace.valueSpace());
                faces[r] = face;
                stack.addView(face, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
            }
            addView(stack, new LinearLayout.LayoutParams(-1, -2));
            // Faces slide out to the card's rounded edge, not to its padding.
            setBackground(ripple(Ui.withAlpha(ui.accent, 30), ui.shape(ui.raised, 14, 0), null));
            setClipToOutline(true);
            setClipChildren(false); setClipToPadding(false);
            stack.setClipChildren(false); stack.setClipToPadding(false);
            ViewConfiguration touch = ViewConfiguration.get(host.activity());
            slop = touch.getScaledTouchSlop();
            minFling = Math.max(touch.getScaledMinimumFlingVelocity(), ui.dp(300));
            setOnClickListener(v -> tapSwitch());
            String saved = null;
            try {
                if (ChargeTimeReadings.key(profileId) != null)
                    saved = preferences.getString(ChargeTimeReadings.KEY + profileId, null);
            } catch (ClassCastException damaged) { saved = null; }
            preferred = ChargeTimeReadings.preferred(saved);
            two = true; setTwo(false);
        }

        /**
         * Fresh values for both faces, also mid-switch. With two readings the 100% face is the plain time to 100%
         * (beside the scooter's own limit, the uncapped time; owner, Nov 12 2026: no "if uncapped", the charging
         * view already says what it charges to; never shorter than the time to the limit beside it,
         * ChargingEstimate.minutesToFullBesideLimit); with one it is the estimate's own time, exactly as the single
         * tile showed it, named for 100% where the guard took no limit (ChargeTimeReadings.oneReadingTarget). The alert face shares the estimate's state (pill, or its words where no time is known)
         * and speaks no status, as the alert tile did. level is the battery percent shown above the tile (NaN when
         * unknown), which minutesToFullBesideLimit needs for the rate learned to the limit.
         */
        void update(ChargingEstimate.Result estimate, ChargingEstimate.Result alert, double level) {
            ScooterService service = host.service();
            if (fixed) {
                if (ChargingFit.timeToLimit(estimate.targetPercent, service != null && service.chargeAlertsOffered()))
                    fill(FULL, chargeTimeLabels(estimate, false), estimate, estimate.remainingMinutesToLimit, estimate.status);
                else fill(FULL, chargeTimeLabels(ChargeTimeReadings.oneReadingTarget(estimate.targetPercent, ChargeTimeReadings.NO_LIMIT), false),
                        estimate, estimate.remainingMinutesToFull, estimate.status);
                setTwo(false);
                describe();
                return;
            }
            int source = ChargeTimeReadings.limitSource(estimate.targetPercent, service != null && service.chargeAlertsOffered(), alert != null);
            boolean scooterLimit = source == ChargeTimeReadings.SCOOTER_LIMIT;
            Double soc = Double.isNaN(level) ? null : level;
            fill(FULL, ChargeTimeReadings.two(source) ? chargeTimeLabels(100d, false, true) : chargeTimeLabels(ChargeTimeReadings.oneReadingTarget(estimate.targetPercent, source), false),
                    estimate, scooterLimit ? ChargingEstimate.minutesToFullBesideLimit(estimate, soc) : estimate.remainingMinutesToFull,
                    scooterLimit ? ChargingEstimate.statusBesideLimit(estimate, soc) : estimate.status);
            if (scooterLimit)
                fill(LIMIT, chargeTimeLabels(estimate, false), estimate, estimate.remainingMinutesToLimit, estimate.status);
            else if (alert != null)
                fill(LIMIT, chargeTimeLabels(alert, false), estimate, ChargingEstimate.minutesToAlert(alert, estimate), null);
            setTwo(ChargeTimeReadings.two(source));
            describe();
        }

        private void fill(int reading, String[] names, ChargingEstimate.Result estimate, Double minutes, String status) {
            captions[reading].setLabels(names[0], names[1], names[2]);
            showChargeTime(values[reading], tags[reading], estimate, minutes);
            spoken[reading] = spokenChargeTime(names[0], values[reading].getText(), tags[reading], status);
            values[reading].setContentDescription(spoken[reading]);
        }

        @Override TextView[] captions() { return captions; }

        /** The reading the user has chosen (or the only one), which the tile shows at rest. */
        int chosen() { return two ? state.target : FULL; }

        /**
         * Two readings make one switchable control for TalkBack; one reading reads as the single tile did. The state
         * names the other time (its name only: a state that changed with every estimate would be read out each time).
         */
        private void describe() {
            String text = spoken[chosen()];
            setTooltipText(text);
            setContentDescription(two ? text : null);
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                CharSequence other = captions[ChargeTimeReadings.other(chosen())].getContentDescription();
                setStateDescription(two && other != null ? T.dashboardChargeTimeOther(other.toString()) : null);
            }
        }

        /** The switch, named: double-tap says "Switch time", and the same is in the actions menu. */
        @Override public void onInitializeAccessibilityNodeInfo(android.view.accessibility.AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            if (!switchable()) return;
            String name = T.dashboardChargeTimeSwitch();
            info.addAction(new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, name));
            info.addAction(new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(R.id.charge_time_switch, name));
        }

        @Override public boolean performAccessibilityAction(int action, android.os.Bundle arguments) {
            if (action != R.id.charge_time_switch) return super.performAccessibilityAction(action, arguments);
            if (!switchable()) return false;
            tapSwitch();
            return true;
        }

        private void setTwo(boolean next) {
            if (next == two) return;
            two = next;
            stopMotion(); endDrag(); cutTouch();
            state.rest(ChargeTimeReadings.shown(two, preferred));
            setClickable(two); setFocusable(two);
            setImportantForAccessibility(two ? IMPORTANT_FOR_ACCESSIBILITY_YES : IMPORTANT_FOR_ACCESSIBILITY_AUTO);
            int reserve = two ? dotsWidth() + ui.dp(6) : 0;
            for (int r : new int[] {LIMIT, FULL}) {
                faces[r].setImportantForAccessibility(two ? IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS : IMPORTANT_FOR_ACCESSIBILITY_AUTO);
                rows[r].setPaddingRelative(0, 0, reserve, 0);
            }
            render();
            requestLayout();
        }

        boolean switchable() { return two && !editing && !closed; }

        private void tapSwitch() {
            if (!switchable() || dragging) return;
            int before = state.target;
            if (state.atRest()) fromDrag = false;
            state.tap(getLayoutDirection() == LAYOUT_DIRECTION_RTL);
            decided(before);
        }

        /** After a tap or a lift: save the choice, say it once if it changed, then run to it. */
        private void decided(int before) {
            int target = state.target;
            if (target != preferred) {
                preferred = target;
                if (ChargeTimeReadings.key(profileId) != null)
                    preferences.edit().putString(ChargeTimeReadings.KEY + profileId, ChargeTimeReadings.encode(target)).apply();
            }
            describe();
            if (target != before) announceForAccessibility(spoken[target]);
            animateToGoal();
        }

        private void animateToGoal() {
            stopMotion();
            if (!Ui.motionEnabled() || closed || !isAttachedToWindow() || !isShown()) { state.settle(); render(); return; }
            final float from = state.progress, to = state.goal();
            if (from == to) { state.step(to); render(); return; }
            final android.animation.ValueAnimator run = android.animation.ValueAnimator.ofFloat(0, 1);
            motion = run;
            // A whole switch takes Motion.CHANGE_MS; the rest of a drag or a turn back takes its share, never under ~100 ms.
            run.setDuration(Math.round(Motion.CHANGE_MS * Math.max(.45f, Math.abs(to - from))));
            run.setInterpolator(Motion.STANDARD);
            run.addUpdateListener(a -> {
                if (motion != run) return;
                if (!Ui.motionEnabled() || closed) { settle(); return; }
                state.step(from + (to - from) * a.getAnimatedFraction());
                render();
            });
            run.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator animation) {
                    if (motion != run) return;
                    motion = null; state.settle(); render();
                }
            });
            run.start();
        }

        private void stopMotion() {
            android.animation.ValueAnimator old = motion;
            motion = null;
            if (old != null) old.cancel();
        }

        /** Ends any switch where it was heading: hidden, detached, rotated, folded or motion turned off. */
        void settle() {
            if (stack == null) return;
            stopMotion(); endDrag(); cutTouch();
            state.settle(); render();
        }

        private void endDrag() {
            if (dragging) state.cancelDrag();
            dragging = false;
            if (velocity != null) { velocity.recycle(); velocity = null; }
        }

        /**
         * A reset (or a change in whether the tile can switch) with a finger down: the rest of that touch does
         * nothing, no drag, no switch, no tap and no long press, until the next touch begins, and Home's scroller is
         * let go so an up or down move still scrolls.
         */
        private void cutTouch() {
            if (!touch.cut()) return;
            endDrag();
            passing = false;
            release();
            // The plain view's press goes too (pressed look, pending tap, long-press tooltip), so the lift cannot click.
            long now = android.os.SystemClock.uptimeMillis();
            MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0, 0, 0);
            super.onTouchEvent(cancel);
            cancel.recycle();
        }

        /** How far a drag goes to switch fully; the faces follow the finger one to one. */
        private float travel() { return Math.max(ui.dp(24), Math.min(ui.dp(96), stack.getWidth() * .5f)); }
        /** A tap's slide is short, a shared-axis step: the faces hint at the direction, the fade does the rest. */
        private float tapTravel() { return Math.max(ui.dp(16), Math.min(ui.dp(30), stack.getWidth() * .25f)); }

        /** Lays the faces out for the switch's state: at rest only the chosen one is drawn. */
        private void render() {
            if (stack == null) return;
            if (!two) {
                place(faces[FULL], VISIBLE, 1, 0);
                place(faces[LIMIT], GONE, 1, 0);
            } else {
                int resting = state.resting, other = ChargeTimeReadings.other(resting);
                float p = state.progress, distance = fromDrag ? travel() : tapTravel();
                boolean moving = !state.atRest() && p > 0;
                place(faces[resting], VISIBLE, ChargeTimeReadings.outgoingAlpha(p), ChargeTimeReadings.outgoingShift(p, state.direction) * distance);
                place(faces[other], moving ? VISIBLE : INVISIBLE, moving ? ChargeTimeReadings.incomingAlpha(p) : 1,
                        moving ? ChargeTimeReadings.incomingShift(p, state.direction) * distance : 0);
            }
            invalidate();
        }

        private void place(View face, int visibility, float alpha, float shift) {
            if (face.getVisibility() != visibility) face.setVisibility(visibility);
            face.setAlpha(alpha);
            face.setTranslationX(shift);
        }

        private float dotRadius() { return ui.dp(6) / 2f; }
        private int dotGap() { return ui.dp(9); }
        private int dotsWidth() { return Math.round(dotRadius() * 2) + dotGap(); }

        /** The two dots, like a page indicator: the first is the limit, mirrored right to left. They stay still. */
        @Override protected void dispatchDraw(Canvas canvas) {
            super.dispatchDraw(canvas);
            if (!two || stack == null) return;
            DashboardCaption caption = captions[state.resting];
            float y = stack.getTop() + faces[state.resting].getTop() + rows[state.resting].getTop() + caption.getTop();
            android.text.Layout layout = caption.getLayout();
            if (layout == null || layout.getLineCount() == 0) y += caption.getHeight() / 2f;
            else {
                Paint.FontMetrics metrics = caption.getPaint().getFontMetrics();
                y += caption.getTotalPaddingTop() + layout.getLineBaseline(0) + (metrics.ascent + metrics.descent) / 2f;
            }
            boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
            float r = dotRadius(), first = rtl ? getPaddingLeft() + dotsWidth() - r : getWidth() - getPaddingRight() - dotsWidth() + r;
            for (int reading : new int[] {LIMIT, FULL}) {
                float x = first + (rtl ? -1 : 1) * reading * dotGap();
                dots.setColor(blend(Ui.withAlpha(ui.muted, 110), ui.text, ChargeTimeReadings.dotFill(state, reading)));
                canvas.drawCircle(x, y, r, dots);
            }
        }

        private int blend(int from, int to, float share) {
            float s = Math.max(0, Math.min(1, share));
            return Color.argb(Math.round(Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * s),
                    Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * s),
                    Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * s),
                    Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * s));
        }

        @Override void showIcons(boolean shown) {
            super.showIcons(shown);
            if (limitFace != null) limitFace.icon.setVisibility(shown ? VISIBLE : GONE);
        }

        /**
         * With two readings the tile is one control: every touch inside it is the tile's, whichever child is under
         * the finger (a caption sets a tooltip, and a view with a tooltip takes the touches that start on it). The
         * choice is made as the gesture starts and kept to its end. With one reading, or while editing, nothing is
         * intercepted: the captions keep their own tooltips and long-press, as the single tile had.
         */
        @Override public boolean onInterceptTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) owning = switchable();
            return owning || super.onInterceptTouchEvent(event);
        }

        /** Follows every touch from its first contact to its lift or cancel, whichever view in the tile takes it. */
        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) touch.begin(switchable());
            boolean handled = super.dispatchTouchEvent(event);
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL || (action == MotionEvent.ACTION_DOWN && !handled)) touch.end();
            return handled;
        }

        /** Lets the parents (Home's scroller) take the gesture again, once it is known to go up or down. */
        private void release() {
            if (!holding) return;
            holding = false;
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        }

        /**
         * A tap is the click (TalkBack's double-tap too); a long press shows the shown reading's tooltip and never
         * switches. From the first touch the parents are held off, so a sideways swipe that drifts up or down at
         * the start is still the tile's. A sideways move past the touch slop that is more sideways than up or down
         * becomes a drag, and the outgoing face follows the finger. Up or down first, the parents are let go and
         * Home's scroller takes it from the next move (the tile only sees a cancel); where nothing scrolls, the
         * press is dropped all the same, so moving past the tile is never a tap. A touch that began with one reading
         * stays the plain tile's; one cut off by a reset, or whose switchability changed under it, does nothing more.
         */
        @Override public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action != MotionEvent.ACTION_DOWN && touch.changed(switchable())) cutTouch();
            if (touch.dead()) {
                // The press was cancelled when the touch was cut, so the lift only lets a tooltip go; it cannot click.
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) super.onTouchEvent(event);
                return true;
            }
            if (!touch.own()) return super.onTouchEvent(event);
            if (action == MotionEvent.ACTION_DOWN) {
                if (velocity == null) velocity = VelocityTracker.obtain(); else velocity.clear();
                downX = event.getX(); downY = event.getY(); dragging = false; passing = false;
                holding = true;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) holding = false;
            if (passing) {
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) { passing = false; endDrag(); }
                return true;
            }
            if (velocity != null) velocity.addMovement(event);
            if (action == MotionEvent.ACTION_MOVE) {
                float dx = event.getX() - downX, dy = event.getY() - downY;
                boolean sideways = Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy);
                if (!dragging && (sideways || Math.abs(dy) > slop)) {
                    MotionEvent cancel = MotionEvent.obtain(event);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    super.onTouchEvent(cancel);
                    cancel.recycle();
                    if (!sideways) { passing = true; release(); return true; }
                    dragging = true;
                    stopMotion();
                    state.startDrag();
                    fromDrag = true;
                    dragFromX = downX + Math.signum(dx) * slop;
                }
                if (dragging) { state.drag((event.getX() - dragFromX) / travel()); render(); return true; }
            } else if (action == MotionEvent.ACTION_UP && dragging) {
                // A live drag always has its tracker (a reset cuts the touch first); no tracker reads as no fling.
                float vx = 0;
                if (velocity != null) { velocity.computeCurrentVelocity(1000); vx = velocity.getXVelocity(); }
                int before = state.resting;
                dragging = false;
                state.release(Math.abs(vx) >= minFling ? (vx > 0 ? 1 : -1) : 0);
                if (velocity != null) { velocity.recycle(); velocity = null; }
                decided(before);
                return true;
            } else if (action == MotionEvent.ACTION_CANCEL && dragging) {
                dragging = false;
                state.cancelDrag();
                if (velocity != null) { velocity.recycle(); velocity = null; }
                animateToGoal();
                return true;
            }
            return super.onTouchEvent(event);
        }

        @Override protected void onDetachedFromWindow() { settle(); super.onDetachedFromWindow(); }

        @Override protected void onVisibilityChanged(View changedView, int visibility) {
            super.onVisibilityChanged(changedView, visibility);
            if (visibility != VISIBLE) settle();
        }

        @Override protected void onWindowVisibilityChanged(int visibility) {
            super.onWindowVisibilityChanged(visibility);
            if (visibility != VISIBLE) settle();
        }
    }

    /**
     * Charging takeover, kept as a hero above the grid: shown only while charging is freshly
     * reported, with the percentage, the scooter photo over a red glow and a floor line that
     * fills to the charge and then, in grey, to the charge limit.
     */
    private final class ChargingHero extends LinearLayout {
        final TextView chip, percent, power;
        /** Clean: the line's one-line short form ("Charging"), shown in its place where the slot is short (ChargingFit). */
        final TextView chipShort;
        final ChargeTimeTile timeTile;
        final InfoTiles tiles;
        final ImageView photo;
        final Stage stage;
        /** Widgets: the floor line with the draggable charge limit or charge alert mark (null on Clean). */
        final ChargeBar bar;
        double level = Double.NaN, limit = 100;
        boolean shown;
        android.animation.ValueAnimator reveal, pulse;
        int revealGeneration;
        /** Glow breathing, 0 = dimmest and narrowest, 1 = the static glow. */
        float glow = 1;
        /**
         * Clean Home's charging view, as the Layout Plan's mock-up draws it (the contributor's decisions on #383, 13 Nov
         * 2026): on the page background, a plain muted line, centred, instead of the chip; the percentage centred over
         * a soft dark fade, with the scooter's photo large under it and reaching up behind the number, the red glow
         * under the wheels and a full track with a tick at the limit; then two fixed tiles, the charging power and the
         * time to the limit (or to full). Clean's top area decides when it shows; Widgets keeps the takeover above the
         * grid, its switching time tile and its charge bar unchanged.
         */
        final boolean clean;
        final LinearLayout rows;
        /** The model has a photo; there is room for it (always on Widgets; Clean's slot can be too small). */
        boolean photoKnown, photoRoom = true;
        /** Clean: the percentage's full text size in pixels (ChargingFit shrinks it from there). */
        float percentFullPx;
        /** Clean: the photo's side in the stage (the square picture, centred under the percentage); 0 while it does not show. */
        int photoSide;
        /**
         * Clean: the model's charging picture (ChargePicture: the animated wire or the folded still; the contributor's
         * charging pictures, 14 Nov 2026), drawn in the photo's place under the percentage; null for every other
         * model, which keeps its photo. The photo view stays (it keeps its place for TalkBack) but draws nothing while
         * the picture shows.
         */
        ChargePicture picture;
        /** The last photo key that has no charging picture (not looked up again). */
        String missingPicture;
        /** The percentage's shade over the picture (the wire's halo or the folded picture's cut-off). */
        final ChargePicture.Shade shade = new ChargePicture.Shade();

        ChargingHero(boolean clean) {
            super(host.activity());
            this.clean = clean;
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_HORIZONTAL);
            setPadding(0, ui.dp(2), 0, ui.dp(14));
            setVisibility(View.GONE);
            chip = ui.text(T.dashboardCharging(), 12.5f, false);
            chip.setTypeface(ui.semibold());
            chip.setTextColor(ui.accent);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setPadding(ui.dp(10), ui.dp(4), ui.dp(12), ui.dp(4));
            chip.setBackground(ui.shape(Ui.withAlpha(ui.accent, ui.dark ? 34 : 24), 100, 0));
            chip.setCompoundDrawablesRelative(glyphDrawable("bolt", ui.accent, 14), null, null, null);
            chip.setCompoundDrawablePadding(ui.dp(5));
            chipShort = clean ? ui.text(HubText.charging().render(), 12.5f, false) : null;
            if (clean) {
                // On the page background, no card: a plain muted line (the mock-up's 15 sp), centred, no chip, no bolt.
                setPadding(0, 0, 0, 0);
                setClipChildren(false);
                setClipToPadding(false);
                for (TextView line : new TextView[] {chip, chipShort}) {
                    line.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, line.getTextSize() * ChargingFit.LINE_SP / 12.5f);
                    line.setTypeface(ui.regular());
                    line.setTextColor(ui.muted);
                    line.setGravity(Gravity.CENTER);
                    line.setPadding(0, 0, 0, 0);
                    line.setBackground(null);
                    line.setCompoundDrawablesRelative(null, null, null, null);
                }
                chip.setMaxLines(2);
                chipShort.setMaxLines(1);
                chipShort.setVisibility(View.GONE);
            }
            addView(chip, new LinearLayout.LayoutParams(clean ? -1 : -2, -2));
            if (clean) addView(chipShort, new LinearLayout.LayoutParams(-1, -2));
            percent = ui.text("—", 72, true);
            percent.setGravity(Gravity.CENTER);
            percent.setLineSpacing(0, 1);
            LinearLayout.LayoutParams percentSize = new LinearLayout.LayoutParams(-2, -2);
            percentSize.topMargin = ui.dp(6);
            if (clean) {
                // The Layout Plan's 60 sp (the battery card's size), at the stage's top over the photo (added there below).
                percent.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, percent.getTextSize() * ChargingFit.PERCENT_SP / 72f);
                percent.setPadding(0, 0, 0, 0);
                percentFullPx = percent.getTextSize();
            } else addView(percent, percentSize);
            stage = new Stage();
            photo = new ImageView(host.activity());
            photo.setScaleType(ImageView.ScaleType.FIT_CENTER);
            photo.setContentDescription(T.dashboardHeroPhoto());
            FrameLayout.LayoutParams photoSize =
                    new FrameLayout.LayoutParams(-1, ui.dp(196), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            photoSize.bottomMargin = ui.dp(20);
            if (clean) {
                // The square picture under the percentage, from the stage's top down to the wheels, sized while measuring.
                photoSize.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                photoSize.bottomMargin = 0;
                stage.setClipChildren(false);
                stage.setClipToPadding(false);
            }
            stage.addView(photo, photoSize);
            // Clean: the number in front of the photo (its fade is drawn just before it, Stage.drawChild).
            if (clean) stage.addView(percent, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
            bar = clean ? null : new ChargeBar(host.activity(), ui, barHost(), .06f, 3, 18);
            if (bar != null) stage.addView(bar, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
            // Clean's stage gets its height and its reach behind the percentage from ChargingFit (onMeasure).
            LinearLayout.LayoutParams stageSize = clean ? new LinearLayout.LayoutParams(-1, 0) : new LinearLayout.LayoutParams(-1, -2);
            addView(stage, stageSize);
            LinearLayout rows = column();
            this.rows = rows;
            tiles = new InfoTiles();
            // One row: power beside the one time tile, the pair kept at one height (each tile matches the row).
            tiles.loneFirst = true;
            InfoCell powerCell = tiles.add("bolt", T.dashboardChargingPower());
            String timeToFull = T.dashboardTimeToFull();
            timeTile = new ChargeTimeTile(timeToFull);
            tiles.add(timeTile);
            power = powerCell.value;
            powerCell.narrowLabel(T.dashboardChargingPower(), T.dashboardChargingPowerCompact());
            if (clean) {
                // The Layout Plan's tiles (152 x 66 dp, 21 sp values, no icon): smaller values and a minimum, and the
                // pair kept side by side at large text, where a stacked pair would take the percentage's room
                // (ChargingFit). The time tile is fixed (no switching).
                timeTile.fixed = true;
                tiles.pairDp = ChargingFit.PAIR_DP;
                // The mock-up's tiles (ChargingFit.tile): no icon, the 12 dp inset at large text, and a label too wide
                // takes its shorter rung, then a little smaller text, before it wraps.
                for (InfoCell cell : new InfoCell[] {powerCell, timeTile}) {
                    cell.cleanCharging = true;
                    for (TextView caption : cell.captions())
                        ((DashboardCaption) caption).shrinkToOneLine(ChargingFit.tile(true, 0, 1).labelMinScale);
                }
                for (TextView value : new TextView[] {power, timeTile.values[0], timeTile.values[1]}) {
                    ((FittedValue) value).capAt(Math.round(ChargingFit.VALUE_SP * ui.textScale));
                    value.setMinHeight(ui.dp(ChargingFit.VALUE_MIN_DP * ui.textScale));
                }
            }
            rows.addView(tiles, new LinearLayout.LayoutParams(-1, -2));
            addView(rows, new LinearLayout.LayoutParams(-1, -2));
        }

        private ChargeBar.Host barHost() {
            return new ChargeBar.Host() {
                @Override public ScooterService service() { return service; }
                @Override public boolean inert() { return editing || closed || reveal != null; }
                @Override public void chargeLimit(int value, java.util.function.Consumer<Boolean> accepted) { actions.chargeLimitDrag(value, accepted); }
                @Override public void toast(String text) { host.toast(text); }
                @Override public ChargeBar.Words words() { return actions.chargeBarWords(); }
            };
        }

        void stopReveal() {
            revealGeneration++;
            if (reveal != null) { reveal.cancel(); reveal = null; }
            // Clean's top area owns the view's size and visibility.
            if (clean) { timeTile.settle(); updatePulse(); return; }
            if (getLayoutParams() != null) { getLayoutParams().height = -2; requestLayout(); }
            setAlpha(1); setTranslationY(0);
            setVisibility(shown ? View.VISIBLE : View.GONE);
            timeTile.settle();
            updatePulse();
        }

        /**
         * One slow loop for the glow while fresh charging is on screen; anything else (hidden,
         * editing, stale, backgrounded, detached, closed, reduced motion) leaves the static glow.
         */
        void updatePulse() {
            boolean run = shown && !closed && !editing && Ui.motionEnabled() && stage.onScreen
                    && photo.getVisibility() == View.VISIBLE && stage.isAttachedToWindow() && stage.intersectsViewport();
            if (run == (pulse != null)) return;
            if (!run) {
                android.animation.ValueAnimator old = pulse;
                pulse = null; old.cancel();
                if (picture != null) picture.stop();
                if (glow != 1) { glow = 1; stage.invalidate(); }
                return;
            }
            if (picture != null) picture.start();
            pulse = android.animation.ValueAnimator.ofFloat(0, 1);
            pulse.setDuration(picture != null ? 4400 : 3200);
            pulse.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            pulse.setInterpolator(new android.view.animation.LinearInterpolator());
            final android.animation.ValueAnimator owner = pulse;
            pulse.addUpdateListener(a -> {
                if (owner != pulse) return;
                if (!Ui.motionEnabled() || closed) { updatePulse(); return; }
                glow = (float) (.5 + .5 * Math.cos(2 * Math.PI * a.getAnimatedFraction()));
                stage.invalidate();
                if (picture != null) picture.invalidateLive();
            });
            pulse.start();
        }

        private void showAnimated(boolean show) {
            if (clean) { shown = show; updatePulse(); return; }
            if (show == shown) return;
            int current = getVisibility() == View.VISIBLE ? getHeight() : 0;
            shown = show; stopReveal();
            View parent = getParent() instanceof View ? (View)getParent() : null;
            if (!Ui.motionEnabled() || closed || parent == null || parent.getWidth() == 0) return;
            setVisibility(View.VISIBLE);
            int width = parent.getWidth() - parent.getPaddingLeft() - parent.getPaddingRight();
            measure(MeasureSpec.makeMeasureSpec(Math.max(1, width), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            int full = Math.max(1, getMeasuredHeight());
            int start = Math.max(0, Math.min(full, current)), end = show ? full : 0;
            final int generation = revealGeneration;
            reveal = android.animation.ValueAnimator.ofInt(start, end);
            reveal.setDuration(240);
            reveal.setInterpolator(new android.view.animation.DecelerateInterpolator());
            reveal.addUpdateListener(a -> {
                if (generation != revealGeneration) return;
                if (!Ui.motionEnabled() || closed) { stopReveal(); return; }
                int h = (Integer)a.getAnimatedValue();
                getLayoutParams().height = h; requestLayout();
                float fraction = Math.max(0, Math.min(1, h / (float)full));
                setAlpha(fraction); setTranslationY(-ui.dp(16) * (1 - fraction));
            });
            reveal.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator animation) {
                    if (generation == revealGeneration) stopReveal();
                }
            });
            getLayoutParams().height = start; requestLayout();
            reveal.start();
        }

        @Override protected void onDetachedFromWindow() { stopReveal(); super.onDetachedFromWindow(); }

        /**
         * Clean's fixed slot (council review 5886927849; the contributor's decisions on #383): the line (as it wraps),
         * its short form, the full-size percentage and the readings are measured at the slot's width, and ChargingFit
         * decides what shows: the line shortens first, then the gap over the readings closes, the photo shrinks with
         * the stage and the percentage gets smaller; the readings go last. The parts stack top to bottom inside the
         * slot, the stage taking what is left, so nothing is left empty under the readings.
         */
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            if (!clean || MeasureSpec.getMode(heightSpec) != MeasureSpec.EXACTLY) { super.onMeasure(widthSpec, heightSpec); return; }
            int width = Math.max(0, MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight());
            int inner = MeasureSpec.getSize(heightSpec) - getPaddingTop() - getPaddingBottom();
            int across = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), free = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
            percentScale(1);
            chip.measure(across, free);
            chipShort.measure(across, free);
            percent.measure(free, free);
            rows.measure(across, free);
            int fullPercent = percent.getMeasuredHeight();
            ChargingFit.Plan plan = ChargingFit.plan(inner, chip.getMeasuredHeight(), chipShort.getMeasuredHeight(), fullPercent,
                    rows.getMeasuredHeight(), getResources().getDisplayMetrics().density);
            shown(chip, plan.line && !plan.shortLine);
            shown(chipShort, plan.line && plan.shortLine);
            shown(percent, plan.percent);
            percentScale(plan.percentScale);
            LinearLayout.LayoutParams stageSize = (LinearLayout.LayoutParams) stage.getLayoutParams();
            stageSize.weight = 0;
            stageSize.height = plan.stageHeight;
            stageSize.topMargin = plan.line ? ui.dp(ChargingFit.LINE_GAP_DP) : 0;
            shown(stage, plan.stageHeight > 0);
            shown(rows, plan.readings);
            ((LinearLayout.LayoutParams) rows.getLayoutParams()).topMargin = plan.readingsGap;
            // The photo: a square picture from the stage's top down to the wheels, no wider than the slot.
            photoSide = Math.min(plan.photoHeight, width);
            photoRoom = photoSide > 0;
            if (photoRoom) {
                FrameLayout.LayoutParams size = (FrameLayout.LayoutParams) photo.getLayoutParams();
                size.width = size.height = photoSide;
                size.topMargin = plan.photoHeight - photoSide;
            }
            showPhoto();
            if (picture != null) {
                // The picture's percentage sits a little higher (into the line's gap; only while the line shows above it).
                ((FrameLayout.LayoutParams) percent.getLayoutParams()).topMargin = plan.line ? -ui.dp(ChargePicture.PERCENT_LIFT_DP) : 0;
                // And a little left of the centre, as the prototype's (its halo stays centred).
                percent.setTranslationX(-ChargePercentShade.percentLeft(percent.getTextSize()));
                // Its tyres stand the picture's gap above the track (ChargingFit's track and its tick at the stage's foot).
                picture.size(width, pictureFloor(plan.stageHeight));
            } else {
                ((FrameLayout.LayoutParams) percent.getLayoutParams()).topMargin = 0;
                percent.setTranslationX(0);
            }
            super.onMeasure(widthSpec, heightSpec);
        }

        /** Clean: the percentage at a share of its full size. */
        private void percentScale(float scale) {
            float size = percentFullPx * scale;
            if (percentFullPx > 0 && percent.getTextSize() != size) percent.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size);
        }

        private void shown(View view, boolean shown) {
            int visibility = shown ? VISIBLE : GONE;
            if (view.getVisibility() != visibility) view.setVisibility(visibility);
        }

        /** The photo shows when the model has one and (in Clean's slot) there is room for it. */
        private void showPhoto() {
            int visibility = photoKnown && photoRoom ? View.VISIBLE : View.GONE;
            if (photo.getVisibility() != visibility) photo.setVisibility(visibility);
            if (picture != null) for (View layer : picture.layers()) if (layer.getVisibility() != visibility) layer.setVisibility(visibility);
        }

        /**
         * Clean: where the picture's tyres stand in a stage this tall: the prototype's gap above its 8 dp track, measured
         * from the track's foot (the app's track is 7 dp, its tick reaching below it to the stage's foot).
         */
        private int pictureFloor(int stageHeight) {
            return Math.round(stageHeight - ui.dp(ChargingFit.TICK_DP) - ui.dp(ChargePicture.TRACK_DP + ChargePicture.tyreGapDp()));
        }

        /**
         * Clean: shows the charging picture for this photo key in the photo's place (ChargePicture; null: the photo, as
         * before). The percentage moves up a little over it and gets its shade, and the floor glow follows its tyres;
         * the photo view keeps its place and description but draws nothing.
         */
        private void showPicture(String key) {
            String shown = key == null ? null : ChargePicture.shownKey(key);
            if (picture == null ? shown == null || shown.equals(missingPicture) : picture.key.equals(shown)) return;
            ChargePicture next = shown == null ? null : ChargePicture.create(host.activity(), ui, key);
            // A key with no picture (or one that cannot be decoded) is not tried again on every refresh.
            missingPicture = next == null ? shown : null;
            if (picture == null && next == null) return;
            if (picture != null) {
                picture.stop();
                for (View layer : picture.layers()) stage.removeView(layer);
            }
            picture = next;
            if (picture != null) {
                View[] layers = picture.layers();
                for (int i = 0; i < layers.length; i++) stage.addView(layers[i], i, new FrameLayout.LayoutParams(-1, -1));
            }
            photo.setImageAlpha(picture != null ? 0 : 255);
            // The glow's breathing follows the picture's (4.4 s) or the photo's (3.2 s): restart it.
            if (pulse != null) { android.animation.ValueAnimator old = pulse; pulse = null; old.cancel(); }
            requestLayout();
        }

        void refresh(Map<String, TelemetryHistory.Reading> values) {
            ChargingEstimate.Result estimate = chargingEstimate(values);
            boolean show = estimate.charging && !editing;
            if (!show) { if (bar != null) bar.stop(); showAnimated(false); return; }
            TelemetryHistory.Reading charge = values.get("battery_percent");
            level = charge == null || charge.number == null ? Double.NaN
                    : Math.max(0, Math.min(100, charge.number));
            boolean hasLimit = estimate.targetPercent != null && estimate.targetPercent < 100;
            limit = hasLimit ? estimate.targetPercent : 100;
            if (bar != null) bar.update(level, limit, estimate.targetPercent);
            chip.setText(clean ? cleanLine(hasLimit, limit) : hasLimit
                    ? T.dashboardChargingLimit(Fmt.decimal(Locale.US, estimate.targetPercent, 0))
                    : T.dashboardCharging());
            if (Double.isNaN(level)) {
                percent.setText("—");
                percent.setContentDescription(T.dashboardBatteryNotReported());
            } else {
                String number = Long.toString(Math.round(level));
                android.text.SpannableString text = new android.text.SpannableString(number + "%");
                text.setSpan(new android.text.style.RelativeSizeSpan(.4f), number.length(), number.length() + 1,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                percent.setText(text);
                percent.setContentDescription(T.dashboardBatteryPercent(Math.round(level), Math.round(level)));
            }
            boolean known = ScooterPhotos.show(photo, activeModel(), 2);
            photoKnown = known;
            if (clean) showPicture(known ? ModelRegistry.photo(activeModel()) : null);
            if (picture != null) { picture.watts(estimate.inputWatts); picture.invalidateLive(); }
            showPhoto();
            stage.setMinimumHeight(clean ? 0 : ui.dp(known ? 236 : 44));
            updatePulse();
            timeTile.update(estimate, chargeAlertEstimate(values), level);
            String watts = estimate.inputWatts == null ? null
                    : T.fmtQuantity(Fmt.decimal(Locale.US, estimate.inputWatts, 0), T.unitsWatt());
            power.setText(watts == null ? "—" : watts);
            power.setTextColor(watts == null ? ui.muted : ui.ok);
            stage.setContentDescription(
                    Double.isNaN(level)
                            ? (hasLimit ? T.dashboardHeroChargeNotReportedLimitCount(Math.round(limit), Fmt.integer(Math.round(limit))) : T.dashboardHeroChargeNotReported())
                            : (hasLimit ? T.dashboardHeroChargeLimitCount(Math.round(level), Fmt.integer(Math.round(level)), T.dashboardHeroLimitPercent(Math.round(limit), Fmt.integer(Math.round(limit)))) : T.dashboardHeroChargeCount(Math.round(level), Fmt.integer(Math.round(level)))));
            stage.invalidate();
            showAnimated(true);
        }

        /** Clean's muted line: "Charging to your 90% limit", or "Charging" with no limit below 100%. */
        private CharSequence cleanLine(boolean hasLimit, double limit) {
            return (hasLimit ? HubText.chargingToLimit(Fmt.percent(Fmt.integerNumber(Math.round(limit)))) : HubText.charging()).render();
        }

        /** Glow and floor line under the photo. */
        private final class Stage extends FrameLayout {
            final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            RadialGradient glowShader, fadeShader;
            float glowRadius = -1, fadeRadius = -1;
            int fadeBg;
            boolean fadeDark;
            int glowColor;
            /** This view, its ancestors and the window are all visible (false while backgrounded or the screen is off). */
            boolean onScreen;
            final Rect viewport = new Rect();
            ViewTreeObserver observedTree;
            final ViewTreeObserver.OnScrollChangedListener viewportScroll = () -> updatePulse();
            final ViewTreeObserver.OnGlobalLayoutListener viewportLayout = () -> updatePulse();

            Stage() {
                super(host.activity());
                setWillNotDraw(false);
                setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
            }

            @Override public void onVisibilityAggregated(boolean visible) {
                super.onVisibilityAggregated(visible);
                onScreen = visible;
                updatePulse();
            }

            boolean intersectsViewport() {
                return getWidth() > 0 && getHeight() > 0 && getGlobalVisibleRect(viewport) && !viewport.isEmpty();
            }

            @Override protected void onAttachedToWindow() {
                super.onAttachedToWindow();
                observedTree = getViewTreeObserver();
                // Aggregated visibility ignores clipping by a scrolling parent. These callbacks
                // stop the loop as it leaves the viewport and restart it without polling on return.
                observedTree.addOnScrollChangedListener(viewportScroll);
                observedTree.addOnGlobalLayoutListener(viewportLayout);
                updatePulse();
            }

            @Override protected void onDetachedFromWindow() {
                onScreen = false; updatePulse();
                if (observedTree != null && observedTree.isAlive()) {
                    observedTree.removeOnScrollChangedListener(viewportScroll);
                    observedTree.removeOnGlobalLayoutListener(viewportLayout);
                }
                observedTree = null;
                super.onDetachedFromWindow();
            }

            @Override
            protected void onDraw(Canvas canvas) {
                float w = getWidth(), floor = getHeight() - ui.dp(18);
                if (clean) {
                    if (photo.getVisibility() == View.VISIBLE && photoSide > 0 && w > 0) drawCleanGlow(canvas, w);
                    drawTrack(canvas, w, getHeight());
                    return;
                }
                if (photo.getVisibility() == View.VISIBLE && w > 0) {
                    // A soft red glow on the floor under the wheels.
                    // While charging it breathes slowly in brightness and width only; its centre,
                    // height and the floor line stay put. glow == 1 is the static glow.
                    float rx = w * .42f, ry = ui.dp(34);
                    int color = ui.accent;
                    if (glowShader == null || rx != glowRadius || color != glowColor) {
                        glowShader = new RadialGradient(0, 0, rx,
                                new int[] {Ui.withAlpha(color, ui.dark ? 140 : 90), Ui.withAlpha(color, 0)},
                                null, Shader.TileMode.CLAMP);
                        glowRadius = rx; glowColor = color;
                    }
                    canvas.save();
                    // Clean anchors the photo right, so its glow follows the wheels there.
                    canvas.translate(clean ? w * .6f : w / 2, floor);
                    canvas.scale(.94f + .06f * glow, ry / rx);
                    paint.setStyle(Paint.Style.FILL);
                    paint.setShader(glowShader);
                    paint.setAlpha(Math.round(255 * (.72f + .28f * glow)));
                    canvas.drawCircle(0, 0, rx, paint);
                    paint.setShader(null);
                    paint.setAlpha(255);
                    canvas.restore();
                }
                // Widgets: the floor line is the bar's (ChargeBar), drawn with its mark.
            }

            /**
             * Clean: the mock-up's red glow, a wide soft ellipse under the scooter's wheels (about 286 x 60 dp on a 344 dp
             * phone, at most 1.7 times the photo's side on a wide window), centred under the photo; it breathes with
             * {@link #glow} while charging is on screen. With a charging picture it is the picture's.
             */
            private void drawCleanGlow(Canvas canvas, float w) {
                float cx = w / 2, floor = photo.getBottom() - photoSide * .04f;
                // As wide as the mock-up's on a phone (0.46 of the width), never much wider than the scooter itself.
                float rx = Math.min(w * .46f, photoSide * .85f);
                float ry = Math.max(ui.dp(10), Math.min(ui.dp(30), photoSide * .2f));
                float alpha = .72f + .28f * glow;
                if (picture != null) {
                    // The charging picture's floor glow, as the prototype draws it under the tyres for every model (129 x
                    // 34 dp round a point 10 dp left of the 283.6 x 136.3 dp wire picture's centre, at its foot),
                    // breathing a little (0.85 at rest, 0.74 to 0.88 while it runs).
                    ChargeWireModels.Placement at = picture.glowPlacement((int) w, pictureFloor(getHeight()));
                    float pw = at.width(ChargeWireModels.UT5_MAX), ph = at.height(ChargeWireModels.UT5_MAX);
                    if (pw <= 0) return;
                    cx = at.left + pw * .4644f; floor = at.top + ph * .9956f;
                    rx = pw * .4549f; ry = ph * .2494f;
                    alpha = pulse == null ? .85f : .81f + .07f * (2 * glow - 1);
                }
                if (rx <= 0) return;
                int color = ui.accent;
                if (glowShader == null || rx != glowRadius || color != glowColor) {
                    glowShader = new RadialGradient(0, 0, rx,
                            new int[] {Ui.withAlpha(color, ui.dark ? 140 : 90), Ui.withAlpha(color, 0)},
                            null, Shader.TileMode.CLAMP);
                    glowRadius = rx; glowColor = color;
                }
                canvas.save();
                canvas.translate(cx, floor);
                canvas.scale(picture != null ? 1 : .94f + .06f * glow, ry / rx);
                paint.setStyle(Paint.Style.FILL);
                paint.setShader(glowShader);
                paint.setAlpha(Math.round(255 * alpha));
                canvas.drawCircle(0, 0, rx, paint);
                paint.setShader(null);
                paint.setAlpha(255);
                canvas.restore();
            }

            /** Clean: a soft dark fade behind the number, drawn over the photo and under the number (the mock-up's). */
            @Override protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
                if (clean && child == percent && picture != null && photo.getVisibility() == View.VISIBLE) {
                    // Over a charging picture: its own shade (the wire's halo, the folded picture's cut-off).
                    shade.draw(canvas, percent, picture.shade(), ui.bg, ui.dark);
                    return super.drawChild(canvas, child, drawingTime);
                }
                if (clean && child == percent && percent.getVisibility() == View.VISIBLE && photo.getVisibility() == View.VISIBLE
                        && percent.getWidth() > 0) {
                    float cx = (percent.getLeft() + percent.getRight()) / 2f, cy = (percent.getTop() + percent.getBottom()) / 2f;
                    float rx = percent.getWidth() * .85f, ry = percent.getHeight() * .7f;
                    float baseline = Float.NaN;
                    if (percent.getLayout() != null) {
                        // The fade stays on the digits' own box (cap height above the baseline, the text's width) and
                        // stops at the baseline, in both themes: where the photo is small beside the number, a fade the
                        // size of the whole view would dim the scooter; the deck and wheels under the number stay solid.
                        android.graphics.Rect digits = new android.graphics.Rect();
                        percent.getPaint().getTextBounds("8", 0, 1, digits);
                        baseline = percent.getTop() + percent.getTotalPaddingTop() + percent.getLayout().getLineBaseline(0);
                        float cap = Math.max(1, -digits.top);
                        cy = baseline - cap / 2f;
                        rx = Math.max(1, percent.getLayout().getLineWidth(0) * .62f);
                        ry = cap * .7f;
                    }
                    if (fadeShader == null || rx != fadeRadius || ui.bg != fadeBg || ui.dark != fadeDark) {
                        int[] colors = ui.dark
                                ? new int[] {Ui.withAlpha(ui.bg, 225), Ui.withAlpha(ui.bg, 160), Ui.withAlpha(ui.bg, 0)}
                                : new int[] {Ui.withAlpha(ui.bg, 190), Ui.withAlpha(ui.bg, 110), Ui.withAlpha(ui.bg, 0)};
                        fadeShader = new RadialGradient(0, 0, rx, colors, new float[] {0, .55f, 1}, Shader.TileMode.CLAMP);
                        fadeRadius = rx; fadeBg = ui.bg; fadeDark = ui.dark;
                    }
                    canvas.save();
                    if (!Float.isNaN(baseline)) canvas.clipRect(0, 0, getWidth(), baseline);
                    canvas.translate(cx, cy);
                    canvas.scale(1, ry / rx);
                    paint.setStyle(Paint.Style.FILL);
                    paint.setShader(fadeShader);
                    paint.setAlpha(255);
                    canvas.drawCircle(0, 0, rx, paint);
                    paint.setShader(null);
                    canvas.restore();
                }
                return super.drawChild(canvas, child, drawingTime);
            }

            /**
             * A full rounded track along the bottom, red to the charge, with a light tick at the limit. Clean's
             * charging view draws it; nothing here is Clean's own, so the widget dashboard can use it too.
             */
            private void drawTrack(Canvas canvas, float w, float h) {
                // The track sits above its tick's reach at the stage's bottom (ChargingFit.TICK_DP).
                float thick = ui.dp(ChargingFit.TRACK_DP), r = thick / 2, reach = ui.dp(ChargingFit.TICK_DP);
                h -= reach;
                float top = h - thick;
                paint.setShader(null);
                paint.setAlpha(255);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(ui.raised);
                canvas.drawRoundRect(0, top, w, h, r, r, paint);
                if (!Double.isNaN(level) && level > 0) {
                    paint.setColor(ui.accent);
                    canvas.drawRoundRect(0, top, Math.max(thick, w * (float) (level / 100)), h, r, r, paint);
                }
                if (limit < 100) {
                    float x = w * (float) (limit / 100);
                    paint.setColor(Ui.withAlpha(Color.WHITE, ui.dark ? 220 : 255));
                    canvas.drawRect(x - ui.dp(1), top - reach, x + ui.dp(1), h + reach, paint);
                }
            }
        }
    }

    /** Types whose content carries its own heading, so the card shows no title line. */
    private static boolean untitled(String type) {
        return type.equals("lock") || type.equals("battery") || type.equals("scooter")
                || type.equals("ride") || type.equals("capacity") || newHome(type);
    }

    /** The Home concept's widgets: no title line and no per-card last-seen footer. */
    private static boolean newHome(String type) {
        return type.equals("lockbar") || type.equals("overview") || type.equals("tiles")
                || type.equals("shortcuts");
    }

    /**
     * Separate small cards with no outer card while not editing. The Lock bar is its own card:
     * the slider fills the cell when connected and the offline row draws its own surface.
     */
    private static boolean frameless(String type) {
        return type.equals("tiles") || type.equals("shortcuts") || type.equals("lockbar");
    }

    /** One lock control per dashboard: the full lock card or the lock bar. */
    static boolean lockType(String type) {
        return type.equals("lock") || type.equals("lockbar");
    }

    private final class Tile extends FrameLayout {
        final DashboardConfig.Widget widget;
        final LinearLayout body, heading, content;
        final TextView footer, title;
        final LinkedHashMap<String, TextView> amounts = new LinkedHashMap<>();
        /** Readings that decide the last-seen footer besides the visible amounts. */
        final ArrayList<String> watched = new ArrayList<>();
        BatteryCell battery;
        Ui.ChartView chart;
        Gauge gauge;
        GlyphView headingIcon, recordGlyph;
        TextView big, subtitle, healthDetail, rideTitle, rideDetail;
        View rideButton, recordDisc;
        /** capacityMethod is a chip shown only for the proxy or sample cases; the title already says experiment. */
        TextView capacityValue, capacitySummary, capacityCompare, capacityMethod, capacityReference, capacityDetails;
        CapacityBar capacityBar;
        /** The latest analysis behind the details sheet; null while checking or in simulation. */
        BatteryCapacityEstimate.Result capacityResult;
        CharSequence capacityUnavailable;
        TextView chargingWatts,
                chargingEta,
                chargingEtaLabel,
                chargingEtaTag,
                chargingTemperature;
        LinearLayout batteryData, chargingData;
        boolean capacityPending, capacitySample, dragging, showsRange;
        /** Usable ride energy outside any dashboard (EnergyPanel): no reference can be stored, so no Set reference pill. */
        boolean panel;
        /** Clean Home's battery card (CleanParts): Sport in the accent's text colour. */
        boolean clean;
        View resizeControl;
        QuickAccessGrid quickAccess, settingTiles;
        OverviewCard overview;
        ReadingTiles readingTiles;
        IconReadingStrip tripStats;
        InfoTiles infoTiles;
        float jiggleAngle;
        Boolean recordingShown, startingShown;
        long capacityChecked, capacityRequest;
        ImageView photo;
        TextView photoHint;
        ScrollView viewport;
        LinearLayout scooterRow, scooterInfo;
        RecordLead recorder;
        boolean compactRecorder;
        /** The recorder card's own start target is installed (not editing), and whether it is live now. */
        boolean recorderCard, wideTap;
        /** A start was tapped and waits for its first frame to draw: the card shows "Starting…". */
        boolean recorderStarting;
        /** Room (px) for the recorder's one-line title on a short card, or -1 while it may wrap. */
        int recorderRoom = -1;
        /** The title rung the last measure laid out: 0 full, 1 short, -1 the disc alone. */
        int recorderRung;
        /** The recording's elapsed time as shown ("12:48"), or null when not recording. */
        String recorderClock;
        FrameLayout photoPane;
        final ArrayList<LinearLayout> scooterStats = new ArrayList<>();
        int scooterColumns;
        final ArrayList<Ui.Point> livePoints = new ArrayList<>();
        boolean wasLive;
        boolean active = true, querying;
        long queriedAt;
        String queryKey = "";
        List<TelemetryHistory.Point> history = Collections.emptyList();

        Tile(DashboardConfig.Widget widget) {
            super(host.activity());
            this.widget = widget;
            setClipChildren(false);
            setClipToPadding(false);
            setBackground(chrome());
            content = column();
            int pad = widget.type.equals("battery") ? 8 : 14;
            content.setPadding(ui.dp(pad), ui.dp(widget.type.equals("battery") ? 8 : 12), ui.dp(pad),
                    ui.dp(widget.type.equals("battery") ? 8 : 12));
            addView(content, new FrameLayout.LayoutParams(-1, -1));
            heading = row();
            heading.setGravity(Gravity.CENTER_VERTICAL);
            title = ui.text(DashboardConfig.displayTitle(widget), 13, false);
            title.setTypeface(ui.medium());
            title.setTextColor(ui.muted);
            title.setMaxLines(2);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            if (widget.type.equals("note")) {
                title.setCompoundDrawablesRelative(glyphDrawable("note", ui.muted, 15), null, null, null);
                title.setCompoundDrawablePadding(ui.dp(6));
            }
            heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
            if (widget.type.equals("metric")) {
                headingIcon = new GlyphView(readingIcon(widget.metric), ui.accent);
                LinearLayout.LayoutParams iconSize = new LinearLayout.LayoutParams(ui.dp(19), ui.dp(19));
                iconSize.setMarginStart(ui.dp(8));
                heading.addView(headingIcon, iconSize);
            }
            heading.setVisibility(untitled(widget.type) ? View.GONE : View.VISIBLE);
            content.addView(heading, new LinearLayout.LayoutParams(-1, -2));
            body = new LinearLayout(host.activity()) {
                @Override protected void dispatchSetPressed(boolean pressed) {
                    super.dispatchSetPressed(pressed);
                    pressRecorder(pressed);
                }
            };
            body.setOrientation(LinearLayout.VERTICAL);
            if (lockType(widget.type) || widget.type.equals("metric")
                    || widget.type.equals("gauge") || widget.type.equals("ride"))
                body.setGravity(Gravity.CENTER_VERTICAL);
            body.setPadding(0, untitled(widget.type) ? 0 : ui.dp(6), 0, 0);
            viewport = new WidgetScrollView();
            viewport.setVerticalScrollBarEnabled(true);
            viewport.setScrollbarFadingEnabled(false);
            viewport.setFillViewport(true);
            viewport.setClipToPadding(false);
            viewport.addView(body, new ScrollView.LayoutParams(-1, -1));
            content.addView(viewport, new LinearLayout.LayoutParams(-1, 0, 1));
            footer = ui.caption("");
            footer.setTextColor(ui.dim);
            ui.fitStatusLine(footer);
            footer.setPadding(0, ui.dp(4), 0, 0);
            footer.setCompoundDrawablesRelative(glyphDrawable("clock", ui.dim, 13), null, null, null);
            footer.setCompoundDrawablePadding(ui.dp(4));
            footer.setGravity(Gravity.CENTER_VERTICAL);
            footer.setVisibility(View.GONE);
            // fitStatusLine reserves the original text height before padding. Include
            // this gap too, so auto-size cannot steal body space when Edit rebuilds it.
            footer.setMinHeight(footer.getMinHeight() + footer.getPaddingTop() + footer.getPaddingBottom());
            content.addView(footer);
            // Paint only: editing dims the card's content, never its editor handles or geometry.
            content.setAlpha(editing ? EDIT_DIM : 1f);
            build();
            if (editing) {
                FrameLayout grip =
                        overlay("grip", T.dashboardMoveWidgetNamed(DashboardConfig.displayTitle(widget)), () -> position(widget));
                EditorTouch touch = new EditorTouch(this, false);
                grip.setTag(touch);
                grip.setOnTouchListener(touch);
                grip.setOnLongClickListener(
                        v -> {
                            touch.start();
                            return true;
                        });
                EditorBar editor = new EditorBar(grip);
                editor.setTag("widget-editor-bar");
                addView(editor, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
                FrameLayout resize = cornerHandle(T.dashboardResizeWidgetNamed(DashboardConfig.displayTitle(widget)), () -> size(widget));
                resizeControl = resize;
                resize.setOnTouchListener(new EditorTouch(this, true));
                addFloating(resize, Gravity.BOTTOM | Gravity.RIGHT);
            }
        }

        @Override public void draw(Canvas canvas) {
            if (jiggleAngle == 0) { super.draw(canvas); return; }
            // A drawing cue only: card layout, native rotation and hit targets stay fixed.
            int saved = canvas.save();
            canvas.rotate(jiggleAngle, getWidth() / 2f, getHeight() / 2f);
            super.draw(canvas);
            canvas.restoreToCount(saved);
        }

        /** Animates the content opacity from its current value; instant when motion is off. */
        void fadeContent(float target, long durationMs) {
            content.animate().cancel();
            if (!Ui.motionEnabled() || !isAttachedToWindow()) { content.setAlpha(target); return; }
            content.animate().alpha(target).setDuration(durationMs).setInterpolator(Motion.STANDARD).start();
        }

        /** Volt card: surface, 20 dp corners; a faint edge while editing, red while dragged or recording. */
        android.graphics.drawable.Drawable chrome() {
            boolean recording = widget.type.equals("ride") && service != null && service.isRecording();
            boolean starting = widget.type.equals("ride") && recorderStarting && !recording;
            // Reading and setting tiles are separate small cards; the widget card returns for editing.
            if (frameless(widget.type) && !editing && !dragging) return null;
            GradientDrawable card = new GradientDrawable();
            card.setCornerRadius(ui.dp(20));
            if (recording) {
                card.setOrientation(GradientDrawable.Orientation.TL_BR);
                card.setColors(new int[] {blend(ui.surface, ui.accent, ui.dark ? .14f : .08f), ui.surface});
            } else if (starting) card.setColor(blend(ui.surface, ui.accent, ui.dark ? .12f : .07f));
            else card.setColor(ui.surface);
            if (dragging) card.setStroke(ui.dp(1.5f), ui.accent);
            else if (recording) card.setStroke(ui.dp(1), blend(ui.surface, ui.accent, .32f));
            else if (editing) card.setStroke(ui.dp(1), Ui.withAlpha(ui.text, ui.dark ? 22 : 30));
            return card;
        }

        /** Slightly larger icon squares; four separate targets still fit compact cards. */
        private FrameLayout overlay(String icon, String label, Runnable action) {
            FrameLayout control = new FrameLayout(host.activity());
            // Solid, high-contrast squares (text-coloured fill, surface glyph) over the dimmed card.
            android.graphics.drawable.LayerDrawable square = new android.graphics.drawable.LayerDrawable(
                    new android.graphics.drawable.Drawable[] {ui.shape(ui.text, 8, 0)});
            square.setLayerSize(0, ui.dp(30), ui.dp(30));
            square.setLayerGravity(0, Gravity.CENTER);
            control.setBackground(ripple(Ui.withAlpha(ui.accent, 60), square, ui.shape(Color.WHITE, 14, 0)));
            control.setPadding(0, 0, 0, 0);
            int ink = icon.equals("trash")
                    ? (ui.dark ? ui.accentDeep : blend(ui.danger, Color.WHITE, .4f)) : ui.surface;
            control.addView(new GlyphView(icon, ink),
                    new FrameLayout.LayoutParams(ui.dp(17), ui.dp(17), Gravity.CENTER));
            control.setContentDescription(label);
            if (!icon.equals("grip")) control.setTooltipText(label);
            control.setFocusable(true);
            control.setClickable(true);
            control.setOnClickListener(v -> action.run());
            return control;
        }

        /** Overlay actions keep their touch targets; tiny rails move secondary actions into a popup. */
        private final class EditorBar extends ViewGroup {
            final View grip, editButton, duplicateButton, removeButton;
            int target, controlColumns, resizeInset;
            boolean compactRail, singleAction;
            PopupMenu actionsMenu;

            EditorBar(View grip) {
                super(host.activity());
                this.grip = grip;
                grip.setOnClickListener(v -> { if (singleAction) showActions(); else position(widget); });
                editButton = overlay("gear", T.dashboardOptionsFor(DashboardConfig.displayTitle(widget)), () -> options(widget));
                editButton.setOnClickListener(v -> {
                    if (compactRail) showActions(); else options(widget);
                });
                duplicateButton = overlay("copy", T.dashboardDuplicateNamed(DashboardConfig.displayTitle(widget)), () -> duplicateWidget(widget));
                removeButton = overlay("trash", T.dashboardRemoveNamed(DashboardConfig.displayTitle(widget)), () -> removeWidget(widget));
                duplicateButton.setEnabled(!lockType(widget.type) && config.widgets.size() < WIDGET_LIMIT);
                duplicateButton.setAlpha(duplicateButton.isEnabled() ? 1f : .42f);
                duplicateButton.setTooltipText(lockType(widget.type) ? T.dashboardOneLockWidget()
                        : config.widgets.size() >= WIDGET_LIMIT ? WIDGET_LIMIT_TEXT : T.dashboardDuplicateWidget());
                for (View control : new View[] {grip, editButton, duplicateButton, removeButton}) addView(control);
            }

            private void showActions() {
                if (actionsMenu != null) actionsMenu.dismiss();
                actionsMenu = new PopupMenu(host.activity(), singleAction ? grip : editButton);
                if (singleAction) actionsMenu.getMenu().add(0, 4, 0, T.dashboardMoveWidget()).setIcon(glyphDrawable("grip", ui.text, 20));
                actionsMenu.getMenu().add(0, 1, 0, T.dashboardEditWidget()).setIcon(glyphDrawable("gear", ui.text, 20));
                actionsMenu.getMenu().add(0, 2, 1, T.dashboardDuplicateWidget()).setIcon(glyphDrawable("copy", ui.text, 20))
                        .setEnabled(!lockType(widget.type) && config.widgets.size() < WIDGET_LIMIT);
                android.text.SpannableString removeLabel = new android.text.SpannableString(T.dashboardRemoveWidget());
                removeLabel.setSpan(new android.text.style.ForegroundColorSpan(ui.danger), 0,
                        removeLabel.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                actionsMenu.getMenu().add(0, 3, 2, removeLabel).setIcon(glyphDrawable("trash", ui.danger, 20));
                if (android.os.Build.VERSION.SDK_INT >= 29) actionsMenu.setForceShowIcon(true);
                actionsMenu.setOnMenuItemClickListener(item -> {
                    if (!editing) return false;
                    if (item.getItemId() == 4) position(widget);
                    else if (item.getItemId() == 1) options(widget);
                    else if (item.getItemId() == 2) duplicateWidget(widget);
                    else if (item.getItemId() == 3) removeWidget(widget);
                    else return false;
                    return true;
                });
                actionsMenu.show();
            }

            @Override protected void onDetachedFromWindow() {
                if (actionsMenu != null) { actionsMenu.dismiss(); actionsMenu = null; }
                super.onDetachedFromWindow();
            }

            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int width = MeasureSpec.getSize(widthSpec);
                // Any physically short card needs a dedicated corner lane. Lock bars can
                // be one row high too; a content-specific condition made Options overlap Resize.
                // Taller cards retain the full width for their normal editor controls.
                resizeInset = MeasureSpec.getSize(heightSpec) < ui.dp(96) ? ui.dp(48) : 0;
                int available = Math.max(1, width - resizeInset);
                singleAction = resizeInset > 0 && available < ui.dp(96);
                editButton.setVisibility(singleAction ? GONE : VISIBLE);
                grip.setTooltipText(singleAction ? T.dashboardMoveOrEditWidget() : T.dashboardMoveWidget());
                controlColumns = available < ui.dp(144) ? (available >= ui.dp(96) ? 2 : 1) : 4;
                target = Math.min(ui.dp(controlColumns < 4 ? 48 : 52), available / controlColumns);
                // A short two-column card cannot fit two action rows above Resize.
                // Use the existing named action menu instead of overlapping tap targets.
                compactRail = singleAction || (resizeInset > 0 && controlColumns < 4)
                        || (widget.type.equals("quick") && available < ui.dp(96))
                        || (resizeInset == 0 && target * (4 / controlColumns)
                                > MeasureSpec.getSize(heightSpec) - ui.dp(48));
                duplicateButton.setVisibility(compactRail ? GONE : VISIBLE);
                removeButton.setVisibility(compactRail ? GONE : VISIBLE);
                for (int i = 0; i < getChildCount(); i++)
                    getChildAt(i).measure(MeasureSpec.makeMeasureSpec(target, MeasureSpec.EXACTLY),
                            MeasureSpec.makeMeasureSpec(target, MeasureSpec.EXACTLY));
                setMeasuredDimension(width, singleAction ? target : target * (((compactRail ? 2 : 4) + controlColumns - 1) / controlColumns));
            }

            @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
                if (singleAction) { grip.layout(0, 0, target, target); return; }
                if (controlColumns < 4) {
                    for (int i = 0; i < (compactRail ? 2 : 4); i++) {
                        int x = (i % controlColumns) * target, y = (i / controlColumns) * target;
                        if (i == 3) x = getWidth() - resizeInset - target;
                        getChildAt(i).layout(x, y, x + target, y + target);
                    }
                    return;
                }
                grip.layout(0, 0, target, target);
                editButton.layout(target, 0, target * 2, target);
                duplicateButton.layout(target * 2, 0, target * 3, target);
                int end = getWidth() - resizeInset;
                removeButton.layout(end - target, 0, end, target);
            }
        }

        /** The resize corner: a white L mark at the lower-right edge of the card. */
        private FrameLayout cornerHandle(String label, Runnable action) {
            FrameLayout control = new FrameLayout(host.activity());
            control.addView(new CornerMark(), new FrameLayout.LayoutParams(-1, -1));
            control.setBackground(ripple(Ui.withAlpha(ui.accent, 60), null, ui.shape(Color.WHITE, 14, 0)));
            control.setContentDescription(label);
            control.setFocusable(true);
            control.setClickable(true);
            control.setOnClickListener(v -> action.run());
            return control;
        }

        private void addFloating(View control, int gravity) {
            addView(control, new FrameLayout.LayoutParams(ui.dp(48), ui.dp(48), gravity));
        }

        private void build() {
            switch (widget.type) {
                case "lock":
                case "lockbar":
                    if (widget.type.equals("lockbar"))
                        // The row fills the frameless cell, so the slider is the whole card.
                        body.addView(actions.lock(widget.showDisconnect, true),
                                new LinearLayout.LayoutParams(-1, -1));
                    else body.addView(actions.lock(widget.showDisconnect));
                    break;
                case "overview":
                    watched.add("battery_percent");
                    watched.add("remaining_range_km");
                    overview = new OverviewCard(limited(widget.keys, 0));
                    body.addView(overview, new LinearLayout.LayoutParams(-1, -2));
                    break;
                case "tiles":
                    readingTiles = new ReadingTiles(limited(widget.keys, 1));
                    body.addView(readingTiles, new LinearLayout.LayoutParams(-1, -2));
                    break;
                case "shortcuts":
                    settingTiles = new QuickAccessGrid(host,
                            QuickAccessActions.normalize(widget.quickActions,
                                    DashboardConfig.minimumShortcuts(widget.type),
                                    DashboardConfig.maximumShortcuts(widget.type)),
                            editing, id -> { if (!editing) actions.quickAction(id); }, true);
                    body.addView(settingTiles, new LinearLayout.LayoutParams(-1, -2));
                    break;
                case "scooter":
                    scooterRow = row();
                    scooterRow.setGravity(Gravity.CENTER_VERTICAL);
                    photoPane = new FrameLayout(host.activity());
                    photo = new ImageView(host.activity());
                    photo.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    photo.setContentDescription(T.uiPhotoScooter());
                    photoPane.addView(photo, new FrameLayout.LayoutParams(-1, -1));
                    // Without a model photo: a generic scooter glyph, not a word.
                    photoHint = ui.caption("");
                    photoHint.setGravity(Gravity.CENTER);
                    photoHint.setCompoundDrawablesRelative(null, glyphDrawable("scooter", ui.dim, 40), null, null);
                    photoHint.setContentDescription(T.dashboardScooter());
                    photoPane.addView(photoHint, new FrameLayout.LayoutParams(-1, -1));
                    scooterRow.addView(
                            photoPane, new LinearLayout.LayoutParams(ui.dp(88), ui.dp(100)));
                    scooterInfo = column();
                    LinearLayout.LayoutParams info = new LinearLayout.LayoutParams(0, -2, 1);
                    info.leftMargin = ui.dp(10);
                    scooterRow.addView(scooterInfo, info);
                    body.addView(scooterRow);
                    for (String key : widget.keys) {
                        LinearLayout item = column();
                        item.setPadding(0, ui.dp(3), ui.dp(4), ui.dp(3));
                        TextView label = readingCaption(key);
                        item.setTooltipText(label(key));
                        label.setTextSize(12.5f * ui.textScale);
                        item.addView(label, new LinearLayout.LayoutParams(-1, -2));
                        // Short captions leave more room for prominent readings. The
                        // fitter still uses actual width and each row's height budget,
                        // keeping narrow/short cards and long readings readable.
                        TextView value = new FittedValue(
                                "—", 14, widget.keys.size() <= 2 ? 32 : 28,
                                key.equals("driving_mode") ? 1 : 2);
                        item.addView(value, new LinearLayout.LayoutParams(-1, -2));
                        scooterStats.add(item);
                        amounts.put(key, value);
                    }
                    break;
                case "battery":
                    battery = new BatteryCell();
                    body.addView(battery, new LinearLayout.LayoutParams(-1, ui.dp(150)));
                    IconReadingStrip readings = new IconReadingStrip(IconReadingStrip.READINGS);
                    batteryData = readings;
                    // Range is spoken inside the battery cell; other readings sit underneath.
                    for (String key : widget.keys) {
                        if (key.equals("remaining_range_km")) {
                            showsRange = true;
                            watched.add(key);
                        } else amounts.put(key, readings.addReading(readingIcon(key), label(key)));
                    }
                    LinearLayout.LayoutParams stripSpace = new LinearLayout.LayoutParams(-1, -2);
                    stripSpace.topMargin = ui.dp(8);
                    body.addView(batteryData, stripSpace);
                    IconReadingStrip charging = new IconReadingStrip(IconReadingStrip.READINGS);
                    chargingData = charging;
                    chargingWatts = charging.addReading("bolt", T.dashboardChargingPower());
                    chargingEta = charging.addReading("clock", T.dashboardTimeToFull());
                    chargingTemperature = charging.addReading("temperature", T.dashboardBatteryTemperature());
                    // Compact status is announced and available on long press, without
                    // adding a second block of explanatory text to the glanceable card.
                    chargingEtaLabel = ui.caption(T.dashboardTimeToFull());
                    LinearLayout.LayoutParams chargingSpace = new LinearLayout.LayoutParams(-1, -2);
                    chargingSpace.topMargin = ui.dp(8);
                    body.addView(chargingData, chargingSpace);
                    break;
                case "metric":
                    big = new FittedValue("—", 14, 34, 2);
                    body.addView(big, new LinearLayout.LayoutParams(-1, -2));
                    subtitle = readingCaption(widget.metric);
                    subtitle.setPadding(0, ui.dp(2), 0, 0);
                    body.addView(subtitle);
                    break;
                case "gauge":
                    gauge = new Gauge(widget);
                    body.addView(gauge, new LinearLayout.LayoutParams(-1, ui.dp(140)));
                    subtitle = readingCaption(widget.metric);
                    subtitle.setGravity(Gravity.CENTER);
                    body.addView(subtitle);
                    break;
                case "live":
                case "history":
                    chart = ui.new ChartView();
                    chart.setHistoryMode(true);
                    body.addView(chart, new LinearLayout.LayoutParams(-1, ui.dp(140)));
                    subtitle = readingCaption(widget.metric);
                    body.addView(subtitle);
                    break;
                case "health":
                    final TextView health = new FittedValue("—", 14, 34, 1);
                    health.setIncludeFontPadding(true);
                    health.setPadding(0, ui.dp(3), 0, ui.dp(2));
                    healthDetail = ui.caption("");
                    final TextView detail = healthDetail;
                    LinearLayout top =
                            new LinearLayout(host.activity()) {
                                @Override
                                protected void onMeasure(int widthSpec, int heightSpec) {
                                    // The percentage is the card's main reading: in a narrow card
                                    // (or with large text) the details stack underneath it instead
                                    // of taking the row, and side by side they never take over half.
                                    int width = MeasureSpec.getSize(widthSpec);
                                    float scale = Math.max(1f, ui.textScale
                                            * getResources().getConfiguration().fontScale);
                                    boolean stack = width < ui.dp(220 * scale);
                                    if (stack != (getOrientation() == VERTICAL)) layoutHealth(this, health, detail, stack);
                                    int limit = stack ? Integer.MAX_VALUE : Math.max(ui.dp(48), (int) (width * .5f));
                                    // setMaxWidth requests layout, so only change it when it differs.
                                    if (detail.getMaxWidth() != limit) detail.setMaxWidth(limit);
                                    super.onMeasure(widthSpec, heightSpec);
                                }
                            };
                    amounts.put("battery_health_percent", health);
                    top.addView(health);
                    top.addView(healthDetail);
                    layoutHealth(top, health, detail, false);
                    body.addView(top, new LinearLayout.LayoutParams(-1, -2));
                    watched.add("charge_cycles");
                    watched.add("rated_capacity_ah");
                    chart = ui.new ChartView();
                    chart.setHistoryMode(true);
                    body.addView(chart, new LinearLayout.LayoutParams(-1, ui.dp(190)));
                    // The default title says "Reported health"; the controller caveat stays on long press.
                    String reported = T.dashboardControllerReported();
                    title.setTooltipText(reported);
                    chart.setTooltipText(reported);
                    health.setTooltipText(reported);
                    break;
                case "capacity":
                    TextView name = ui.text(DashboardConfig.displayTitle(widget), 14, false);
                    name.setTextColor(ui.muted);
                    name.setCompoundDrawablesRelative(glyphDrawable("lab", ui.accent, 18), null, null, null);
                    name.setCompoundDrawablePadding(ui.dp(6));
                    body.addView(name);
                    // Only the proxy ("Proxy · not health") and sample cases carry a chip.
                    capacityMethod = tagChip();
                    LinearLayout.LayoutParams methodSpace = new LinearLayout.LayoutParams(-2, -2);
                    methodSpace.topMargin = ui.dp(6);
                    body.addView(capacityMethod, methodSpace);
                    capacityValue = new FittedValue(T.dashboardProcessing(), 16, 34, 2);
                    capacityValue.setTextColor(ui.muted);
                    capacityValue.setPadding(0, ui.dp(8), 0, ui.dp(4));
                    body.addView(capacityValue, new LinearLayout.LayoutParams(-1, -2));
                    capacityCompare = ui.text("", 14, true);
                    capacityCompare.setVisibility(View.GONE);
                    body.addView(capacityCompare);
                    capacityReference = actionPill(T.dashboardSetReference(), "plus", ui.blue, () -> advertisedCapacity(widget));
                    capacityReference.setContentDescription(T.dashboardSetReferenceCapacity());
                    capacityReference.setVisibility(View.GONE);
                    body.addView(capacityReference, new LinearLayout.LayoutParams(-2, -2));
                    // The bar compares with the reference, or fills toward the evidence minimum.
                    capacityBar = new CapacityBar();
                    capacityBar.setVisibility(View.GONE);
                    LinearLayout.LayoutParams barSpace = new LinearLayout.LayoutParams(-1, ui.dp(10));
                    barSpace.topMargin = barSpace.bottomMargin = ui.dp(10);
                    body.addView(capacityBar, barSpace);
                    capacitySummary = ui.caption(T.dashboardCheckingRides());
                    body.addView(capacitySummary);
                    capacityDetails = ui.secondaryButton(T.dashboardEstimateDetails(), this::showCapacityDetails);
                    LinearLayout.LayoutParams evidenceSpace = new LinearLayout.LayoutParams(-1, -2);
                    evidenceSpace.topMargin = ui.dp(8);
                    body.addView(capacityDetails, evidenceSpace);
                    break;
                case "charging":
                    buildCharging(body, true);
                    break;
                case "readings":
                    InfoTiles readingTiles = new InfoTiles();
                    infoTiles = readingTiles;
                    for (String key : widget.keys) {
                        InfoCell cell = readingTiles.addReading(key);
                        amounts.put(key, cell.value);
                    }
                    body.addView(readingTiles, new LinearLayout.LayoutParams(-1, -2));
                    break;
                case "trip":
                    IconReadingStrip stats = new IconReadingStrip(IconReadingStrip.STATS);
                    tripStats = stats;
                    for (String key :
                            new String[] {
                                "speed_kmh",
                                "trip_km",
                                "trip_minutes",
                                "maximum_speed_kmh",
                                "odometer_km"
                            }) amounts.put(key, stats.addReading(null, label(key), key));
                    body.addView(stats, new LinearLayout.LayoutParams(-1, -2));
                    break;
                case "quick":
                    quickAccess = new QuickAccessGrid(host, widget.quickActions, editing,
                            id -> { if (!editing) actions.quickAction(id); });
                    body.addView(quickAccess, new LinearLayout.LayoutParams(-1, -2));
                    break;
                case "ride":
                    buildRecorder();
                    break;
                case "note":
                    subtitle = ui.text(widget.note.isEmpty() ? T.dashboardEmptyNote() : widget.note, 15, false);
                    // Reserve the edit tap target in both modes so short notes never move.
                    subtitle.setMinHeight(ui.dp(48));
                    // The shipped example's note shows in the current language; the saved note stays as it is.
                    if (!widget.note.isEmpty()) subtitle.setText(DashboardConfig.displayNote(widget));
                    if (widget.note.isEmpty()) {
                        subtitle.setTextColor(ui.muted);
                        subtitle.setCompoundDrawablesRelative(glyphDrawable("note", ui.muted, 18), null, null, null);
                        subtitle.setCompoundDrawablePadding(ui.dp(8));
                        subtitle.setGravity(Gravity.CENTER_VERTICAL);
                    }
                    if (editing) {
                        // While editing, tapping the note opens its editor directly.
                        subtitle.setTag(EDIT_ACTION);
                        subtitle.setClickable(true);
                        subtitle.setFocusable(true);
                        subtitle.setContentDescription((widget.note.isEmpty() ? T.dashboardEmptyNoteEdit() : T.dashboardEditNote(widget.note)));
                        subtitle.setOnClickListener(v -> editText(widget, M.dashboardNote(), widget.note,
                                value -> widget.note = value));
                        // The shipped example's note is announced as shown, in the current language.
                        if (!widget.note.isEmpty()) subtitle.setContentDescription(T.dashboardEditNote(DashboardConfig.displayNote(widget)));
                    }
                    body.addView(subtitle);
                    break;
            }
        }

        /** Record card: a red circle that starts or stops, the state beside it, and a way to Rides. */
        private void buildRecorder() {
            LinearLayout line = new LinearLayout(host.activity()) {
                @Override protected void onMeasure(int widthSpec, int heightSpec) {
                    boolean stack = MeasureSpec.getSize(widthSpec) < ui.dp(96);
                    setOrientation(stack ? VERTICAL : HORIZONTAL);
                    if (getChildCount() == 2) {
                        LinearLayout.LayoutParams first = (LinearLayout.LayoutParams)getChildAt(0).getLayoutParams();
                        first.width = stack ? -1 : 0; first.weight = stack ? 0 : 1;
                        LinearLayout.LayoutParams last = (LinearLayout.LayoutParams)getChildAt(1).getLayoutParams();
                        last.gravity = stack ? Gravity.CENTER_HORIZONTAL : Gravity.CENTER_VERTICAL;
                    }
                    super.onMeasure(widthSpec, heightSpec);
                }
            };
            line.setGravity(Gravity.CENTER_VERTICAL);
            FrameLayout disc = new FrameLayout(host.activity());
            recordDisc = disc;
            recordGlyph = new GlyphView("record", ui.onAccent);
            disc.addView(recordGlyph, new FrameLayout.LayoutParams(ui.dp(20), ui.dp(20), Gravity.CENTER));
            LinearLayout words = column();
            rideTitle = ui.text(T.dashboardRecordRide(), 15, false);
            rideTitle.setTypeface(ui.medium());
            words.addView(rideTitle);
            rideDetail = ui.caption("");
            rideDetail.setPadding(0, ui.dp(2), 0, 0);
            rideDetail.setVisibility(View.GONE);
            words.addView(rideDetail);
            RecordLead lead = new RecordLead(disc, words);
            recorder = lead;
            rideButton = lead;
            // The grey disc starts a connection instead of explaining that one is needed.
            lead.setOnClickListener(v -> recorderAction(RecorderTap.lead(service != null && service.isRecording(),
                    service == null || service.ble.ready(), recorderStarting)));
            line.addView(lead, new LinearLayout.LayoutParams(0, -2, 1));
            FrameLayout more = new FrameLayout(host.activity());
            more.addView(new GlyphView("chevron", ui.muted),
                    new FrameLayout.LayoutParams(ui.dp(16), ui.dp(16), Gravity.CENTER));
            more.setContentDescription(T.dashboardViewRides());
            more.setFocusable(true);
            more.setClickable(true);
            more.setBackground(ripple(Ui.withAlpha(ui.accent, 30), null, ui.shape(Color.WHITE, 14, 0)));
            more.setOnClickListener(v -> actions.navigate("rides"));
            line.addView(more, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
            body.addView(line, new LinearLayout.LayoutParams(-1, -2));
            if (!editing) {
                // Like the Lock bar, the whole card is the start target: the card covers its padding
                // and the body covers the empty space inside the scroll viewport, which takes its own
                // touches. The disc and the arrow keep theirs. refreshRecorder decides when it is live,
                // and the listener asks RecorderTap again, so it never stops a ride or acts in edit mode.
                View.OnClickListener wide = v -> recorderAction(RecorderTap.card(editing, rideButton.isEnabled(),
                        service != null && service.isRecording(), service == null || service.ble.ready(),
                        recorderStarting));
                setOnClickListener(wide);
                body.setOnClickListener(wide);
                body.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
                recorderCard = true;
            }
        }

        /** A press anywhere on a live recorder card lights the disc, the control it stands for. */
        private void pressRecorder(boolean pressed) {
            if (recorder != null && (wideTap || !pressed)) recorder.setPressed(pressed);
        }

        @Override protected void dispatchSetPressed(boolean pressed) {
            super.dispatchSetPressed(pressed);
            pressRecorder(pressed);
        }

        private void recorderAction(int action) {
            if (action == RecorderTap.STOP) { RideRecordingApp.of(host).stop(); return; }
            else if (action == RecorderTap.CONNECT) host.connect();
            else if (action == RecorderTap.START) { startRecorder(); return; }
            else return;
            host.redraw();
        }

        /**
         * Draws "Starting…" first, then starts: the ring, the wash, the word and a tick reach the
         * screen before the start. postOnAnimation runs at the next frame and the inner post after
         * that frame is drawn. The ride starts right here on Home (owner Q1 B), through the same
         * RideRecordingControl checks as the Rides tab; a refused start has said why in a toast, so
         * the card leaves "Starting…" at once. While the location prompt is open it stays.
         */
        private void startRecorder() {
            recorderStarting = true;
            Haptics.tick(recorder);
            refreshRecorder();
            View anchor = recorder;
            int token = recorderStart.arm();
            Context app = host.activity().getApplicationContext();
            // The card leaving the window alone (a redraw) does not drop the start; closing Home
            // takes it first and hands it on (close()), so this runs it only while Home is open.
            anchor.postOnAnimation(() -> anchor.post(() -> {
                if (closed || !recorderStart.take(token)) return;
                if (RideRecordingApp.of(host).start() != RideRecordingControl.STARTING) recorderStarting = false;
                host.redraw();
            }));
            // Safety net: a start that never got going never sticks. Still waiting for its frame:
            // cancelled for good, and said so, since the card goes back to idle. Already run (and
            // refused, or the screen stayed): the card just leaves "Starting…". A newer tap owns the card.
            anchor.postDelayed(() -> {
                if (!recorderStart.latest(token)) return;
                if (RecorderTap.timeoutCancels(recorderStart, token)) recorderNotStarted(app);
                if (!recorderStarting) return;
                recorderStarting = false;
                if (!closed && anchor.isAttachedToWindow()) refreshRecorder();
            }, 3000);
        }

        /**
         * Shows the recorder's title, on a short card the first rung that fits on one line: the full
         * label, then a shorter one (the bare time while recording). False when neither fits, and
         * the disc stands alone. The spoken label stays the full one. The title fills its column, so
         * a new text alone never re-measures the card: outside a measure, a change of rung asks for one.
         */
        private boolean showRecorderTitle() { return showRecorderTitle(false); }

        private boolean showRecorderTitle(boolean measuring) {
            String full = recorderClock != null ? T.dashboardRecordingClock(recorderClock)
                    : recorderStarting ? T.dashboardRecorderStarting() : T.dashboardRecordRide();
            // "Starting…" too wide for the card keeps the short label, so the word never vanishes on a tap.
            String brief = recorderClock != null ? recorderClock : T.dashboardRecordShort();
            int rung = 0;
            if (recorderRoom >= 0) {
                android.graphics.Paint paint = rideTitle.getPaint();
                rung = TileRung.first(new float[] {paint.measureText(full), paint.measureText(brief)}, recorderRoom);
            }
            CharSequence shown = rung == 1 ? brief : full;
            if (TileRung.needsSet(shown, rideTitle.getText())) rideTitle.setText(shown);
            if (measuring) recorderRung = rung;
            else if (rung != recorderRung && recorder != null) recorder.requestLayout();
            return rung >= 0;
        }

        /** Disc and words side by side, stacking on narrow cards so the words stay readable. */
        private final class RecordLead extends LinearLayout {
            final View disc, words;

            RecordLead(View disc, View words) {
                super(host.activity());
                this.disc = disc;
                this.words = words;
                setMinimumHeight(ui.dp(56));
                setPadding(0, ui.dp(4), ui.dp(4), ui.dp(4));
                setClickable(true);
                setFocusable(true);
                setBackground(ripple(Ui.withAlpha(ui.accent, 30), null, ui.shape(Color.WHITE, 14, 0)));
                addView(disc, new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)));
                addView(words, new LinearLayout.LayoutParams(0, -2, 1));
            }

            @Override
            protected void onMeasure(int widthSpec, int heightSpec) {
                int availableWidth = MeasureSpec.getSize(widthSpec);
                // A short card keeps one line: the title's ladder picks a rung that fits beside the
                // 28 dp disc (12 dp gap, 4 dp end padding; 1 px for rounding), else the disc alone.
                // A taller card may wrap the full title, and needs 92 dp for words beside the disc.
                recorderRoom = compactRecorder ? Math.max(0, availableWidth - ui.dp(44) - 1) : -1;
                boolean fits = showRecorderTitle(true);
                boolean iconOnly = compactRecorder ? !fits : availableWidth < ui.dp(92 * ui.textScale
                        * getResources().getConfiguration().fontScale);
                boolean narrow = !compactRecorder && MeasureSpec.getSize(widthSpec) < ui.dp(170 * ui.textScale);
                setMinimumHeight(ui.dp(compactRecorder ? 48 : 56));
                setPadding(0, compactRecorder ? 0 : ui.dp(4), ui.dp(4), compactRecorder ? 0 : ui.dp(4));
                disc.getLayoutParams().width = disc.getLayoutParams().height = ui.dp(compactRecorder ? 28 : 40);
                rideDetail.setVisibility(compactRecorder || rideDetail.length() == 0 ? GONE : VISIBLE);
                rideTitle.setMaxLines(compactRecorder ? 1 : Integer.MAX_VALUE);
                rideTitle.setEllipsize(compactRecorder ? android.text.TextUtils.TruncateAt.END : null);
                words.setVisibility(iconOnly ? GONE : VISIBLE);
                setTooltipText(getContentDescription());
                setOrientation(narrow ? VERTICAL : HORIZONTAL);
                setGravity(iconOnly ? Gravity.CENTER : narrow ? Gravity.START : Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) words.getLayoutParams();
                int width = narrow ? -1 : 0, start = narrow ? 0 : ui.dp(12), top = narrow ? ui.dp(8) : 0;
                float weight = narrow ? 0 : 1;
                if (p.width != width || p.weight != weight || p.getMarginStart() != start || p.topMargin != top) {
                    p.width = width;
                    p.weight = weight;
                    p.setMarginStart(start);
                    p.topMargin = top;
                    words.setLayoutParams(p);
                }
                super.onMeasure(widthSpec, heightSpec);
            }
        }

        /** Capacity row: flask square, name and evidence, with the Wh at the end (or below when narrow). */
        private final class CapacityRow extends LinearLayout {
            final LinearLayout words;
            final TextView value;
            Boolean narrow;

            CapacityRow(View square, LinearLayout words, TextView value) {
                super(host.activity());
                this.words = words;
                this.value = value;
                setOrientation(HORIZONTAL);
                setGravity(Gravity.CENTER_VERTICAL);
                addView(square, new LinearLayout.LayoutParams(ui.dp(38), ui.dp(38)));
                LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(0, -2, 1);
                space.setMarginStart(ui.dp(12));
                addView(words, space);
            }

            @Override
            protected void onMeasure(int widthSpec, int heightSpec) {
                boolean next = MeasureSpec.getSize(widthSpec) < ui.dp(240 * ui.textScale);
                if (narrow == null || narrow != next) {
                    narrow = next;
                    if (value.getParent() instanceof ViewGroup) ((ViewGroup) value.getParent()).removeView(value);
                    value.setGravity(next ? Gravity.START : Gravity.END);
                    if (next) {
                        LinearLayout.LayoutParams below = new LinearLayout.LayoutParams(-1, -2);
                        below.topMargin = ui.dp(4);
                        words.addView(value, below);
                    } else {
                        LinearLayout.LayoutParams end = new LinearLayout.LayoutParams(-2, -2);
                        end.setMarginStart(ui.dp(10));
                        addView(value, end);
                    }
                }
                int limit = next ? Integer.MAX_VALUE : Math.round(MeasureSpec.getSize(widthSpec) * .42f);
                if (value.getMaxWidth() != limit) value.setMaxWidth(limit);
                super.onMeasure(widthSpec, heightSpec);
            }
        }

        /** Let a swipe anywhere inside overflowing content scroll that widget first. */
        private final class WidgetScrollView extends ScrollView {
            float startY;

            WidgetScrollView() {
                super(host.activity());
            }

            @Override
            public boolean onInterceptTouchEvent(MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startY = event.getY();
                        if (!editing && (canScrollVertically(-1) || canScrollVertically(1)))
                            getParent().requestDisallowInterceptTouchEvent(true);
                        break;
                    case MotionEvent.ACTION_MOVE:
                        float delta = event.getY() - startY;
                        if (Math.abs(delta) > ViewConfiguration.get(getContext()).getScaledTouchSlop()
                                && !canScrollVertically(delta > 0 ? -1 : 1))
                            getParent().requestDisallowInterceptTouchEvent(false);
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        getParent().requestDisallowInterceptTouchEvent(false);
                        break;
                }
                return super.onInterceptTouchEvent(event);
            }
        }

        /**
         * Cells that wrap into as many columns as fit: icon readings (battery), stat cells (trip)
         * or shortcut tiles (quick access). One glanceable row at ordinary widths.
         */
        private final class IconReadingStrip extends LinearLayout {
            static final int READINGS = 0, STATS = 1, TILES = 2;
            final ArrayList<LinearLayout> cells = new ArrayList<>();
            final int mode;
            int columns;

            IconReadingStrip(int mode) {
                super(host.activity());
                this.mode = mode;
                setOrientation(VERTICAL);
            }

            TextView addReading(String iconKey, String label) {
                return addReading(iconKey, label, null);
            }
            TextView addReading(String iconKey, String label, String key) {
                LinearLayout cell = column();
                TextView value;
                if (mode == READINGS) {
                    cell.setGravity(Gravity.CENTER);
                    cell.setPadding(ui.dp(3), ui.dp(4), ui.dp(3), ui.dp(4));
                    cell.setTooltipText(label);
                    cell.addView(new GlyphView(iconKey, ui.muted),
                            new LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)));
                    value = new FittedValue("—", 12, 15, 2);
                    value.setGravity(Gravity.CENTER);
                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
                    params.topMargin = ui.dp(3);
                    cell.addView(value, params);
                } else {
                    cell.setPadding(0, ui.dp(4), ui.dp(8), ui.dp(4));
                    value = new FittedValue("—", 12, 20, 2);
                    cell.addView(value, new LinearLayout.LayoutParams(-1, -2));
                    TextView name = key == null ? ui.caption(label) : readingCaption(key);
                    cell.addView(name, new LinearLayout.LayoutParams(-1, -2));
                }
                value.setContentDescription(T.dashboardNotReportedNamed(label));
                cells.add(cell);
                return value;
            }

            void fitHeight(int budget) {
                if (mode != STATS || cells.isEmpty() || columns == 0) return;
                for (LinearLayout cell : cells) {
                    cell.setPadding(0, ui.dp(4), ui.dp(8), ui.dp(4));
                    ((FittedValue) cell.getChildAt(0)).fitHeight(Integer.MAX_VALUE);
                    measureCell(cell);
                }
                if (readingRowsHeight(cells, columns, 0) <= budget) return;
                for (LinearLayout cell : cells) {
                    cell.setPadding(0, ui.dp(1), ui.dp(8), ui.dp(1));
                    ((FittedValue) cell.getChildAt(0)).fitHeight(1);
                    measureCell(cell);
                }
                int rows = (cells.size() + columns - 1) / columns;
                int minimum = readingRowsHeight(cells, columns, 0);
                int extra = minimum <= budget ? (budget - minimum) / rows : -1;
                for (int i = 0; i < cells.size(); i += columns) {
                    // If all readings cannot fit at readable sizes, preserve scrolling
                    // but show at least a complete value + label before the first swipe.
                    int height = extra < 0 ? budget : readingRowHeight(cells, i, columns) + extra;
                    for (int j = i; j < Math.min(i + columns, cells.size()); j++) {
                        LinearLayout cell = cells.get(j);
                        ((FittedValue) cell.getChildAt(0)).fitHeight(height
                                - cell.getChildAt(1).getMeasuredHeight() - cell.getPaddingTop() - cell.getPaddingBottom());
                    }
                }
            }

            void addShortcut(String label, String spoken, String page, String icon) {
                LinearLayout cell = column();
                cell.setGravity(Gravity.CENTER);
                cell.setMinimumHeight(ui.dp(64));
                cell.setPadding(ui.dp(4), ui.dp(10), ui.dp(4), ui.dp(10));
                cell.setBackground(ripple(Ui.withAlpha(ui.accent, 40), ui.shape(ui.raised, 12, 0), null));
                cell.addView(new GlyphView(icon, ui.accent), new LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)));
                TextView name = ui.text(label, 12, false);
                name.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-2, -2);
                space.topMargin = ui.dp(5);
                cell.addView(name, space);
                cell.setContentDescription(spoken);
                cell.setTooltipText(spoken);
                cell.setClickable(true);
                cell.setFocusable(true);
                // Cells attach during measure, after disableInputs() ran, so they set their own
                // edit-mode state and the listener refuses to navigate while arranging widgets.
                cell.setEnabled(!editing);
                cell.setOnClickListener(v -> { if (!editing) actions.navigate(page); });
                cells.add(cell);
            }

            @Override
            protected void onMeasure(int widthSpec, int heightSpec) {
                int width = MeasureSpec.getSize(widthSpec);
                float cellDp = mode == READINGS ? 62 : mode == STATS ? 92 : 72;
                int most = mode == STATS ? 3 : 4;
                int next = Math.min(Math.min(most, cells.size()),
                        Math.max(1, width / Math.max(1, ui.dp(cellDp * ui.textScale))));
                if (cells.size() == 4 && next == 3) next = 2;
                if (next != columns) {
                    columns = next;
                    if (mode == READINGS) for (LinearLayout cell : cells) {
                        // A one-column list reads naturally as icon + value. Repeating
                        // a separate icon row wastes height in narrow, large-text cards.
                        boolean inline = columns == 1;
                        cell.setOrientation(inline ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
                        cell.setGravity(inline ? Gravity.CENTER_VERTICAL : Gravity.CENTER);
                        View value = cell.getChildAt(1);
                        LinearLayout.LayoutParams valueSize = (LinearLayout.LayoutParams)value.getLayoutParams();
                        valueSize.width = inline ? 0 : -1;
                        valueSize.weight = inline ? 1 : 0;
                        valueSize.topMargin = inline ? 0 : ui.dp(3);
                        valueSize.setMarginStart(inline ? ui.dp(8) : 0);
                        value.setLayoutParams(valueSize);
                    }
                    for (LinearLayout cell : cells)
                        if (cell.getParent() instanceof ViewGroup)
                            ((ViewGroup) cell.getParent()).removeView(cell);
                    removeAllViews();
                    int gap = mode == TILES ? ui.dp(3) : 0;
                    for (int i = 0; i < cells.size(); i += columns) {
                        LinearLayout line = row();
                        line.setGravity(mode == READINGS ? Gravity.BOTTOM : Gravity.TOP);
                        line.setBaselineAligned(false);
                        for (int j = i; j < i + columns; j++) {
                            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, j < cells.size() ? -2 : 1, 1);
                            p.leftMargin = p.rightMargin = gap;
                            p.topMargin = p.bottomMargin = gap;
                            line.addView(j < cells.size() ? cells.get(j) : new View(host.activity()), p);
                        }
                        addView(line, new LinearLayout.LayoutParams(-1, -2));
                    }
                }
                super.onMeasure(widthSpec, heightSpec);
            }
        }

        /** Up to four readings; an empty list falls back to the type's defaults when one is required. */
        private List<String> limited(List<String> keys, int least) {
            ArrayList<String> result = new ArrayList<>();
            for (String key : keys) if (result.size() < 4 && !result.contains(key)) result.add(key);
            if (result.size() < least) {
                result.clear();
                result.addAll(new DashboardConfig.Widget(widget.type).keys);
            }
            return result;
        }

        /** Scale used for width thresholds: app text size and Android's font preference. */
        private float textFactor() {
            return Math.max(1, ui.textScale * getResources().getConfiguration().fontScale);
        }

        /** Battery overview: charge, range and photo, a full-width bar, then small readings. */
        private final class OverviewCard extends LinearLayout {
            final LinearLayout top, left, readingsBox;
            final TextView caption, range;
            final FittedValue percent;
            final ImageView picture;
            final BatteryBar bar;
            /** Spoken battery text, a Msg rendered when spoken; readings dropped to fit the height are appended to it. */
            CharSequence spoken = "";
            final ArrayList<LinearLayout> items = new ArrayList<>();
            int budget, readingColumns, fittedWidth = -1, fittedBudget = -1;
            boolean photoAllowed, fittedPhoto, fittedRange, rangeWanted, rangeFits = true;
            /**
             * Clean Home's card (CleanParts): it fills its fixed-height area as the Layout Plan's mock-up does, the
             * readings along the bottom and the spare height spread above the top row, under it and over the readings
             * (TopArea.parkedGaps), never one blank band. The photo takes the mock-up's size and place, right of the
             * percentage and the range line (TopArea.parkedPhotoDp, the contributor's decisions on #383), growing into
             * the spare height first. The widget dashboard's card keeps its own height.
             */
            boolean fill;
            /** Clean: the photo's side in pixels (TopArea.parkedPhotoDp), 0 while it does not show. */
            int parkedSide;

            OverviewCard(List<String> keys) {
                super(host.activity());
                setOrientation(VERTICAL);
                top = row();
                top.setBaselineAligned(false);
                left = column();
                left.setGravity(Gravity.CENTER_VERTICAL);
                caption = ui.text(T.dashboardBattery(), 13, false);
                caption.setTextColor(ui.muted);
                caption.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
                left.addView(caption, new LinearLayout.LayoutParams(-1, -2));
                percent = new FittedValue("—", 24, 58, 1);
                left.addView(percent, new LinearLayout.LayoutParams(-1, -2));
                range = ui.text("", 15, false);
                range.setTextColor(ui.muted);
                range.setMaxLines(1);
                range.setEllipsize(android.text.TextUtils.TruncateAt.END);
                range.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
                left.addView(range, new LinearLayout.LayoutParams(-1, -2));
                top.addView(left, new LinearLayout.LayoutParams(0, -2, 1));
                picture = new ImageView(host.activity()) {
                    @Override protected void onMeasure(int widthSpec, int heightSpec) {
                        // Take the row's height from the words; never the bitmap's own height.
                        setMeasuredDimension(MeasureSpec.getSize(widthSpec),
                                MeasureSpec.getMode(heightSpec) == MeasureSpec.EXACTLY
                                        ? MeasureSpec.getSize(heightSpec) : 0);
                    }
                };
                picture.setScaleType(ImageView.ScaleType.FIT_CENTER);
                picture.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
                LinearLayout.LayoutParams photoSize = new LinearLayout.LayoutParams(ui.dp(120), -1);
                photoSize.setMarginStart(ui.dp(10));
                top.addView(picture, photoSize);
                addView(top, new LinearLayout.LayoutParams(-1, -2));
                bar = new BatteryBar();
                LinearLayout.LayoutParams barSize = new LinearLayout.LayoutParams(-1, ui.dp(8));
                barSize.topMargin = ui.dp(12);
                addView(bar, barSize);
                readingsBox = column();
                for (String key : keys) {
                    LinearLayout item = column();
                    item.setPadding(0, 0, ui.dp(6), 0);
                    TextView name = readingCaption(key);
                    name.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12.5f * ui.textScale);
                    // The value speaks "label, value"; the caption would repeat it.
                    name.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
                    item.addView(name, new LinearLayout.LayoutParams(-1, -2));
                    FittedValue value = new FittedValue("—", 13, 20, 1);
                    item.addView(value, new LinearLayout.LayoutParams(-1, -2));
                    item.setTooltipText(label(key));
                    amounts.put(key, value);
                    items.add(item);
                }
                LinearLayout.LayoutParams readingsSpace = new LinearLayout.LayoutParams(-1, -2);
                readingsSpace.topMargin = ui.dp(12);
                addView(readingsBox, readingsSpace);
                readingsBox.setVisibility(items.isEmpty() ? GONE : VISIBLE);
            }

            void fitHeight(int height) {
                if (budget == height) return;
                budget = height;
                requestLayout();
            }

            private void arrangeReadings(int width) {
                int next = width >= ui.dp(2 * 96 * textFactor()) ? 2 : 1;
                if (next == readingColumns) return;
                readingColumns = next;
                for (LinearLayout item : items)
                    if (item.getParent() instanceof ViewGroup) ((ViewGroup) item.getParent()).removeView(item);
                readingsBox.removeAllViews();
                for (int i = 0; i < items.size(); i += next) {
                    LinearLayout line = row();
                    line.setBaselineAligned(false);
                    for (int j = i; j < i + next; j++)
                        line.addView(j < items.size() ? items.get(j) : new View(host.activity()),
                                new LinearLayout.LayoutParams(0, j < items.size() ? -2 : 1, 1));
                    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
                    if (i > 0) p.topMargin = ui.dp(8);
                    readingsBox.addView(line, p);
                }
            }

            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int width = MeasureSpec.getSize(widthSpec);
                // Refit only when the space changes: repeated budget changes would request layout every frame.
                if (width == fittedWidth && budget == fittedBudget && photoAllowed == fittedPhoto
                        && rangeWanted == fittedRange) {
                    spread(widthSpec, heightSpec);
                    return;
                }
                fittedWidth = width;
                fittedBudget = budget;
                fittedPhoto = photoAllowed;
                fittedRange = rangeWanted;
                // Fit with no spread gaps: they are what is left over afterwards.
                margins(new int[3]);
                arrangeReadings(width);
                // The photo only takes width the percentage and range can spare (Clean: the mock-up's size, TopArea).
                int photoWidth = Math.min(ui.dp(150), Math.round(width * .42f));
                boolean photo = photoAllowed && width - photoWidth - ui.dp(10) >= ui.dp(116 * textFactor());
                if (fill) {
                    float density = getResources().getDisplayMetrics().density;
                    parkedSide = Math.round(TopArea.parkedPhotoDp(width / density, textFactor()) * density);
                    photoWidth = parkedSide;
                    photo = photoAllowed && parkedSide > 0;
                    LinearLayout.LayoutParams photoSize = (LinearLayout.LayoutParams) picture.getLayoutParams();
                    photoSize.height = -1;
                    photoSize.setMarginStart(ui.dp(TopArea.PARKED_PHOTO_GAP_DP));
                }
                picture.setVisibility(photo ? VISIBLE : GONE);
                LinearLayout.LayoutParams photoSize = (LinearLayout.LayoutParams) picture.getLayoutParams();
                if (photoSize.width != photoWidth) photoSize.width = photoWidth;
                rangeFits = true;
                range.setVisibility(rangeWanted ? VISIBLE : GONE);
                for (int i = 0; i < readingsBox.getChildCount(); i++) readingsBox.getChildAt(i).setVisibility(VISIBLE);
                readingsBox.setVisibility(items.isEmpty() ? GONE : VISIBLE);
                int lines = readingsBox.getChildCount();
                // Give up height from the big percentage first; once it sits at its floor, hide
                // the range line, then whole rows of readings from the end. Never scroll inside.
                while (!fitPercent(widthSpec) && (range.getVisibility() == VISIBLE || lines > 0)) {
                    if (range.getVisibility() == VISIBLE) {
                        rangeFits = false;
                        range.setVisibility(GONE);
                    } else {
                        readingsBox.getChildAt(--lines).setVisibility(GONE);
                        if (lines == 0) readingsBox.setVisibility(GONE);
                    }
                }
                // Hiding a whole row can free more height than the percentage needed: bring back what fits again, the
                // range line first, then the rows from the first, so the spare height goes to the percentage (which
                // shrinks for it) rather than to blank gaps.
                if (rangeWanted && range.getVisibility() == GONE) {
                    range.setVisibility(VISIBLE);
                    rangeFits = true;
                    if (!fitPercent(widthSpec)) { range.setVisibility(GONE); rangeFits = false; }
                }
                while (lines < readingsBox.getChildCount()) {
                    readingsBox.setVisibility(VISIBLE);
                    readingsBox.getChildAt(lines).setVisibility(VISIBLE);
                    if (fitPercent(widthSpec)) { lines++; continue; }
                    readingsBox.getChildAt(lines).setVisibility(GONE);
                    if (lines == 0) readingsBox.setVisibility(GONE);
                    break;
                }
                fitPercent(widthSpec);
                speak();
                spread(widthSpec, heightSpec);
            }

            /** Clean: the spare height under the budget goes to three gaps (TopArea.parkedGaps); otherwise none. */
            private void spread(int widthSpec, int heightSpec) {
                int[] gaps = {0, 0, 0};
                if (fill && budget > 0) {
                    margins(gaps);
                    int unbounded = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
                    LinearLayout.LayoutParams photoSize = (LinearLayout.LayoutParams) picture.getLayoutParams();
                    photoSize.height = -1;
                    super.onMeasure(widthSpec, unbounded);
                    // The photo grows into the spare height first, up to the mock-up's size; the gaps share what is left.
                    if (picture.getVisibility() == VISIBLE && parkedSide > 0) {
                        int row = top.getMeasuredHeight();
                        int height = TopArea.parkedPhotoHeight(row, budget - getMeasuredHeight(), parkedSide);
                        if (height > row) { photoSize.height = height; super.onMeasure(widthSpec, unbounded); }
                    }
                    gaps = TopArea.parkedGaps(budget - getMeasuredHeight());
                }
                margins(gaps);
                super.onMeasure(widthSpec, heightSpec);
            }

            private void margins(int[] gaps) {
                int[] base = {0, ui.dp(12), ui.dp(12)};
                View[] views = {top, bar, readingsBox};
                for (int i = 0; i < 3; i++) {
                    LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) views[i].getLayoutParams();
                    if (params.topMargin != base[i] + gaps[i]) params.topMargin = base[i] + gaps[i];
                }
            }

            /** Fits the percentage to what the other lines leave; false when it is over budget at its floor. */
            private boolean fitPercent(int widthSpec) {
                percent.fitHeight(Integer.MAX_VALUE);
                // Probe the natural height; an AT_MOST spec would clamp it and hide an overflow.
                int unbounded = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
                super.onMeasure(widthSpec, unbounded);
                if (budget <= 0 || getMeasuredHeight() <= budget) return true;
                int other = getMeasuredHeight() - percent.getMeasuredHeight();
                percent.fitHeight(Math.max(1, budget - other));
                super.onMeasure(widthSpec, unbounded);
                return getMeasuredHeight() <= budget;
            }

            /** Battery description plus any readings hidden for height, so they stay reachable. */
            private void speak() {
                StringBuilder text = new StringBuilder(spoken);
                for (LinearLayout item : items) {
                    if (item.getParent() instanceof View && ((View) item.getParent()).getVisibility() == VISIBLE
                            && readingsBox.getVisibility() == VISIBLE) continue;
                    CharSequence reading = item.getChildAt(1).getContentDescription();
                    if (reading != null && reading.length() > 0) text.append(". ").append(reading);
                }
                if (!text.toString().contentEquals(percent.getContentDescription() == null
                        ? "" : percent.getContentDescription()))
                    percent.setContentDescription(text.toString());
            }

            void refresh(Map<String, TelemetryHistory.Reading> values) {
                TelemetryHistory.Reading charge = values.get("battery_percent"),
                        state = values.get("charging_state");
                boolean live = charge != null && charge.live;
                Double level = charge == null || charge.number == null || !Double.isFinite(charge.number)
                        ? null : Math.max(0, Math.min(100, charge.number));
                boolean charging = state != null && state.live && state.number != null && state.number == 1;
                String amount = level == null ? "—" : Long.toString(Math.round(level));
                android.text.SpannableString shown = new android.text.SpannableString(level == null ? amount : amount + "%");
                if (level != null)
                    shown.setSpan(new android.text.style.RelativeSizeSpan(.42f), amount.length(), shown.length(),
                            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                if (!shown.toString().contentEquals(percent.getText())) percent.setText(shown);
                percent.setTextColor(live ? ui.text : ui.muted);
                String distance = rangeText(values.get("remaining_range_km"));
                String line = charging ? T.dashboardCharging() : distance.isEmpty() ? "" : T.dashboardRangeLeft(distance);
                if (!line.contentEquals(range.getText())) range.setText(line);
                if (rangeWanted != !line.isEmpty()) { rangeWanted = !line.isEmpty(); requestLayout(); }
                range.setVisibility(rangeWanted && rangeFits ? VISIBLE : GONE);
                bar.set(level, live, charging);
                String said = BatteryOverviewSpeech.line(charging, distance);
                spoken = overviewSpoken(level, amount, said,
                        charge != null && charge.value != null && !live ? when(charge.capturedWallMs) : null);
                speak();
                boolean allowed = ScooterPhotos.show(picture, activeModel(), 2);
                if (allowed != photoAllowed) { photoAllowed = allowed; requestLayout(); }
            }
        }

        /** Full-width rounded charge bar: accent live, orange when low, grey when last seen. */
        private final class BatteryBar extends View {
            final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            Double level;
            boolean live, charging;

            BatteryBar() {
                super(host.activity());
                setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            }

            void set(Double value, boolean live, boolean charging) {
                if (Objects.equals(value, level) && live == this.live && charging == this.charging) return;
                level = value;
                this.live = live;
                this.charging = charging;
                invalidate();
            }

            @Override protected void onDraw(Canvas canvas) {
                float w = getWidth(), h = getHeight(), r = h / 2f;
                if (w <= 0 || h <= 0) return;
                paint.setColor(ui.raised);
                canvas.drawRoundRect(0, 0, w, h, r, r, paint);
                if (level == null || level <= 0) return;
                // Same thresholds as the battery cell: orange below 15% unless charging.
                paint.setColor(!live ? ui.dim : level < 15 && !charging ? ui.orange : ui.accent);
                canvas.drawRoundRect(0, 0, Math.max(h, (float) (w * level / 100)), h, r, r, paint);
            }
        }

        /** Readings as separate small cards that wrap, then drop icons and labels to fit short rows. */
        private final class ReadingTiles extends ViewGroup {
            static final int FULL = 0, INLINE = 1, PLAIN = 2, VALUE = 3;
            final ArrayList<LinearLayout> cells = new ArrayList<>();
            final ArrayList<GlyphView> icons = new ArrayList<>();
            final ArrayList<LinearLayout> heads = new ArrayList<>();
            final ArrayList<FittedValue> values = new ArrayList<>();
            final ArrayList<String> keys = new ArrayList<>();
            int budget, columns = 1, rowHeight, cellWidth = 1, lastWidth = 1, mode = -1, fittedWidth = -1, fittedBudget = -1, fittedColumns = -1;

            ReadingTiles(List<String> readings) {
                super(host.activity());
                for (String key : readings) {
                    LinearLayout cell = column();
                    cell.setGravity(Gravity.CENTER_VERTICAL);
                    cell.setBackground(ui.shape(editing ? ui.raised : ui.surface, 20, 0));
                    cell.setTooltipText(label(key));
                    LinearLayout head = column();
                    GlyphView icon = new GlyphView(readingIcon(key), ui.muted);
                    head.addView(icon, new LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)));
                    DashboardCaption name = readingCaption(key);
                    name.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12.5f * ui.textScale);
                    // One line: "Battery power" becomes "Power" rather than being cut to "Battery".
                    name.fitOneLine();
                    name.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
                    head.addView(name, new LinearLayout.LayoutParams(-1, -2));
                    cell.addView(head, new LinearLayout.LayoutParams(-1, -2));
                    FittedValue value = new FittedValue("—", 12, 19, 1);
                    LinearLayout.LayoutParams valueSpace = new LinearLayout.LayoutParams(-1, -2);
                    valueSpace.topMargin = ui.dp(2);
                    cell.addView(value, valueSpace);
                    amounts.put(key, value);
                    cells.add(cell); icons.add(icon); heads.add(head); values.add(value); keys.add(key);
                    addView(cell);
                }
            }

            void fitHeight(int height) {
                if (budget == height) return;
                budget = height;
                requestLayout();
            }

            private void apply(int next) {
                if (next == mode) return;
                mode = next;
                for (int i = 0; i < cells.size(); i++) {
                    LinearLayout head = heads.get(i);
                    GlyphView icon = icons.get(i);
                    View name = head.getChildAt(1);
                    boolean inline = next == INLINE;
                    head.setOrientation(inline ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
                    head.setGravity(inline ? Gravity.CENTER_VERTICAL : Gravity.START);
                    head.setVisibility(next == VALUE ? GONE : VISIBLE);
                    icon.setVisibility(next <= INLINE ? VISIBLE : GONE);
                    // A name that cannot fit leaves this icon alone; with the icon hidden it shows its own.
                    if (name instanceof DashboardCaption) ((DashboardCaption) name).iconBeside(next <= INLINE);
                    LinearLayout.LayoutParams iconSize = (LinearLayout.LayoutParams) icon.getLayoutParams();
                    iconSize.width = iconSize.height = ui.dp(inline ? 15 : 18);
                    iconSize.setMarginEnd(inline ? ui.dp(6) : 0);
                    LinearLayout.LayoutParams nameSize = (LinearLayout.LayoutParams) name.getLayoutParams();
                    nameSize.width = inline ? 0 : -1;
                    nameSize.weight = inline ? 1 : 0;
                    nameSize.topMargin = next == FULL ? ui.dp(6) : 0;
                    int vertical = ui.dp(next == FULL ? 12 : 4);
                    LinearLayout cell = cells.get(i);
                    cell.setPadding(cell.getPaddingLeft(), vertical, cell.getPaddingRight(), vertical);
                    icon.requestLayout();
                    name.requestLayout();
                }
            }

            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int width = MeasureSpec.getSize(widthSpec), gap = ui.dp(10), count = cells.size();
                if (count == 0) { setMeasuredDimension(width, 0); return; }
                // Wrap to two per row (or one) before a tile becomes narrower than ~88 dp of text.
                // Wrap only into rows the height can hold (a ~44 dp label + value tile each);
                // a one-row card keeps every tile on one row and drops icons and labels instead.
                int minimum = ui.dp(88 * textFactor());
                int maxRows = budget > 0 ? Math.max(1, (budget + gap) / (ui.dp(44 * textFactor()) + gap)) : count;
                columns = count;
                while (columns > 1 && (width - (columns - 1) * gap) / columns < minimum) {
                    int next = columns == 4 ? 2 : columns - 1;
                    if ((count + next - 1) / next > maxRows) break;
                    columns = next;
                }
                // Height may force values-only cells, but must never force a numeric token
                // into fewer pixels than it needs at the readable floor. Allow another row
                // (and normal card scrolling) rather than clipping digits or hiding readings.
                float required = 0;
                for (FittedValue value : values) {
                    android.text.TextPaint probe = new android.text.TextPaint(value.getPaint());
                    probe.setTextSize(value.minimumSp * getResources().getDisplayMetrics().scaledDensity);
                    for (String token : value.getText().toString().split("\\s+"))
                        required = Math.max(required, probe.measureText(token));
                }
                required += ui.dp(12);
                while (columns > 1 && (width - (columns - 1) * gap) / columns < required)
                    columns = columns == 4 ? 2 : columns - 1;
                int rows = (count + columns - 1) / columns;
                cellWidth = Math.max(1, (width - (columns - 1) * gap) / columns);
                // A part-filled last row: the last tile takes the empty cells (3 in 2 columns wrap 2 + 1, wide).
                // Label mode and padding still follow one cell, so every tile shares them.
                lastWidth = ReadingSpan.width(ReadingSpan.last(count, columns), cellWidth, gap);
                boolean cramped = cellWidth < minimum;
                int horizontal = Math.min(ui.dp(cramped ? 6 : 14),
                        Math.max(0, (cellWidth - (int)Math.ceil(required - ui.dp(12))) / 2));
                for (LinearLayout cell : cells)
                    cell.setPadding(horizontal, cell.getPaddingTop(), horizontal, cell.getPaddingBottom());
                int perRow = budget > 0 ? (budget - (rows - 1) * gap) / rows : Integer.MAX_VALUE;
                int natural;
                if (width == fittedWidth && budget == fittedBudget && columns == fittedColumns) natural = measureCells(-1);
                else {
                    // Search only when the space changes, so value budgets settle instead of re-requesting layout.
                    fittedWidth = width;
                    fittedBudget = budget;
                    fittedColumns = columns;
                    natural = 0;
                    // Too narrow for a label: values only (each value's description keeps its label).
                    for (int next = cramped ? VALUE : FULL; next <= VALUE; next++) {
                        apply(next);
                        natural = measureCells(perRow);
                        if (natural <= perRow) break;
                    }
                }
                rowHeight = perRow != Integer.MAX_VALUE && perRow >= natural ? perRow : natural;
                for (int i = 0; i < count; i++)
                    cells.get(i).measure(MeasureSpec.makeMeasureSpec(tileWidth(i), MeasureSpec.EXACTLY),
                            MeasureSpec.makeMeasureSpec(rowHeight, MeasureSpec.EXACTLY));
                setMeasuredDimension(width, rows * rowHeight + (rows - 1) * gap);
            }

            /** One cell, or the last tile's spanned width. */
            private int tileWidth(int i) {
                return i == cells.size() - 1 ? lastWidth : cellWidth;
            }

            /** Tallest tile at this mode, letting each value shrink toward its floor to fit the row. */
            private int measureCells(int perRow) {
                int tallest = 0;
                for (int i = 0; i < cells.size(); i++) {
                    LinearLayout cell = cells.get(i);
                    int tile = tileWidth(i);
                    LinearLayout head = heads.get(i);
                    int other = cell.getPaddingTop() + cell.getPaddingBottom() + ui.dp(2);
                    if (head.getVisibility() != GONE) {
                        head.measure(MeasureSpec.makeMeasureSpec(Math.max(1, tile - cell.getPaddingLeft()
                                        - cell.getPaddingRight()), MeasureSpec.EXACTLY),
                                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                        other += head.getMeasuredHeight();
                    }
                    // A negative row keeps the budgets chosen by the last search.
                    if (perRow >= 0)
                        values.get(i).fitHeight(perRow == Integer.MAX_VALUE ? Integer.MAX_VALUE
                                : Math.max(1, perRow - other));
                    cell.measure(MeasureSpec.makeMeasureSpec(tile, MeasureSpec.EXACTLY),
                            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                    tallest = Math.max(tallest, cell.getMeasuredHeight());
                }
                return tallest;
            }

            @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
                int gap = ui.dp(10);
                boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
                for (int i = 0; i < cells.size(); i++) {
                    View cell = cells.get(i);
                    int w = cell.getMeasuredWidth();
                    // Step by one cell, not the tile's own width: the last tile may span several.
                    int x = (i % columns) * (cellWidth + gap), y = (i / columns) * (rowHeight + gap);
                    if (rtl) x = getWidth() - x - w;
                    cell.layout(x, y, x + w, y + rowHeight);
                }
            }

            void refresh(Map<String, TelemetryHistory.Reading> readings) {
                for (int i = 0; i < keys.size(); i++) {
                    TelemetryHistory.Reading r = readings.get(keys.get(i));
                    icons.get(i).set(readingIcon(keys.get(i)), r != null && r.live ? ui.accent : ui.muted);
                }
            }
        }

        private void viewSize(View view, int width, int height) {
            ViewGroup.LayoutParams params = view.getLayoutParams();
            if (params.width != width || params.height != height) {
                params.width = width;
                params.height = height;
                view.setLayoutParams(params);
            }
        }

        private void linearSize(View v, int width, int height, float weight, int margin) {
            LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) v.getLayoutParams();
            if (p.width != width
                    || p.height != height
                    || p.weight != weight
                    || p.leftMargin != margin) {
                p.width = width;
                p.height = height;
                p.weight = weight;
                p.leftMargin = margin;
                v.setLayoutParams(p);
            }
        }

        private void arrangeScooter(int width) {
            float scale = Math.max(1, ui.textScale * getResources().getConfiguration().fontScale);
            // Let the readings use the full card before abbreviating a caption. A tiny
            // photo must never consume the last readable word/number column.
            boolean showPhoto = width >= ui.dp((36 + 10 + 106) * scale);
            photoPane.setVisibility(showPhoto ? VISIBLE : GONE);
            int photoWidth = Math.max(ui.dp(36), Math.min(Math.round(width * .45f),
                    width - ui.dp(10) - ui.dp(106 * scale)));
            viewSize(photoPane, photoWidth, photoPane.getLayoutParams().height);
            linearSize(scooterInfo, 0, -2, 1, showPhoto ? ui.dp(10) : 0);
            int available = showPhoto ? width - photoWidth - ui.dp(10) : width;
            boolean glanceable = widget.keys.size() <= 2;
            int columns = !glanceable && available >= ui.dp(180 * scale) ? 2 : 1;
            if (columns == scooterColumns) return;
            scooterColumns = columns;
            for (LinearLayout item : scooterStats)
                if (item.getParent() instanceof ViewGroup)
                    ((ViewGroup) item.getParent()).removeView(item);
            scooterInfo.removeAllViews();
            for (int i = 0; i < scooterStats.size(); i += columns) {
                LinearLayout line = row();
                for (int j = i; j < Math.min(i + columns, scooterStats.size()); j++)
                    line.addView(scooterStats.get(j), new LinearLayout.LayoutParams(0, -2, 1));
                if (columns == 2 && i + 1 == scooterStats.size())
                    line.addView(new View(host.activity()), new LinearLayout.LayoutParams(0, 1, 1));
                scooterInfo.addView(line, new LinearLayout.LayoutParams(-1, -2));
            }
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            boolean narrowQuick = quickAccess != null && MeasureSpec.getSize(widthSpec) < ui.dp(144);
            compactRecorder = recorder != null && MeasureSpec.getSize(heightSpec) < ui.dp(100
                    * Math.max(1, ui.textScale * getResources().getConfiguration().fontScale));
            int pad = narrowQuick ? 10 : widget.type.equals("battery") ? 16
                    : MeasureSpec.getSize(widthSpec) < ui.dp(144) ? 16 : 28;
            int vertical = compactRecorder ? 2 : narrowQuick ? 5 : widget.type.equals("battery") ? 8 : 12;
            if (frameless(widget.type)) {
                // The tiles are the cards; editing adds a thin frame of the widget card around them.
                // A one-row card (54 dp at 1.0 text scale) keeps 48 dp for its tiles: 3 dp above and below.
                boolean oneRow = MeasureSpec.getSize(heightSpec) < ui.dp(86 * ui.textScale);
                pad = editing ? 12 : 0;
                vertical = editing ? oneRow ? 3 : 6 : 0;
            }
            content.setPadding(ui.dp(pad / 2), ui.dp(vertical), ui.dp(pad / 2), ui.dp(vertical));
            if (quickAccess != null) heading.setVisibility(narrowQuick ? GONE : VISIBLE);
            int width = Math.max(1, MeasureSpec.getSize(widthSpec) - ui.dp(pad));
            if (photo != null) {
                arrangeScooter(width);
                for (LinearLayout item : scooterStats) ((FittedValue)item.getChildAt(1)).capAt(Integer.MAX_VALUE);
            }
            if (capacityValue != null) {
                // Four-row cards need less decorative spacing, not smaller values or touch targets.
                float scale = Math.max(1, ui.textScale * getResources().getConfiguration().fontScale);
                boolean compact = MeasureSpec.getSize(heightSpec) < ui.dp(280 * scale);
                capacitySpacing(capacityMethod, compact ? 2 : 6, 0);
                capacitySpacing(capacityBar, compact ? 4 : 10, compact ? 4 : 10);
                capacitySpacing(capacityDetails, compact ? 4 : 8, 0);
                int top = ui.dp(compact ? 4 : 8), bottom = ui.dp(compact ? 2 : 4);
                if (capacityValue.getPaddingTop() != top || capacityValue.getPaddingBottom() != bottom)
                    capacityValue.setPadding(0, top, 0, bottom);
            }
            super.onMeasure(widthSpec, heightSpec);
            int budget =
                    Math.max(
                            1,
                            viewport.getMeasuredHeight()
                                    - body.getPaddingTop()
                                    - body.getPaddingBottom());
            if (photo != null) {
                int rows = Math.max(1, scooterInfo.getChildCount());
                int rowBudget = Math.max(1, budget / rows);
                for (LinearLayout item : scooterStats) {
                    int inset = rowBudget < ui.dp(56) ? ui.dp(1) : ui.dp(3);
                    item.setPadding(0, inset, ui.dp(4), inset);
                    TextView label = (TextView) item.getChildAt(0);
                    label.setTextSize((rowBudget < ui.dp(56) ? 11.5f : 12.5f) * ui.textScale);
                    label.measure(MeasureSpec.makeMeasureSpec(Math.max(1, item.getMeasuredWidth()
                                    - item.getPaddingLeft() - item.getPaddingRight()), MeasureSpec.EXACTLY),
                            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                    ((FittedValue) item.getChildAt(1)).fitHeight(
                            rowBudget - label.getMeasuredHeight()
                                    - item.getPaddingTop() - item.getPaddingBottom());
                }
                // Fit every number against the same row budget, then share the smallest
                // resulting size. No stale previous measurement can make one value dominate.
                int common = Integer.MAX_VALUE;
                float density = getResources().getDisplayMetrics().scaledDensity;
                for (LinearLayout item : scooterStats) {
                    FittedValue value = (FittedValue)item.getChildAt(1);
                    value.measure(MeasureSpec.makeMeasureSpec(Math.max(1, item.getMeasuredWidth()
                            - item.getPaddingLeft() - item.getPaddingRight()), MeasureSpec.EXACTLY),
                            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                    common = Math.min(common, Math.round(value.fittedPixels / density));
                }
                for (LinearLayout item : scooterStats) ((FittedValue)item.getChildAt(1)).capAt(common);
                viewSize(photoPane, photoPane.getLayoutParams().width, Math.max(ui.dp(40), budget));
            }
            if (gauge != null) {
                viewSize(
                        gauge,
                        -1,
                        Math.max(1, budget - (subtitle.getVisibility() == View.GONE
                                ? 0 : subtitle.getMeasuredHeight())));
            }
            if (quickAccess != null) quickAccess.fitHeight(budget);
            if (settingTiles != null) settingTiles.fitHeight(budget);
            if (overview != null) overview.fitHeight(budget);
            if (readingTiles != null) readingTiles.fitHeight(budget);
            if (tripStats != null) tripStats.fitHeight(budget);
            if (infoTiles != null) {
                int other = 0;
                for (int i = 0; i < body.getChildCount(); i++) {
                    View child = body.getChildAt(i);
                    if (child == infoTiles || child.getVisibility() == View.GONE) continue;
                    LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) child.getLayoutParams();
                    other += child.getMeasuredHeight() + params.topMargin + params.bottomMargin;
                }
                infoTiles.fitHeight(Math.max(1, budget - other));
            }
            if (chart != null) {
                // The chart takes whatever its card has left after the other lines.
                int other = 0;
                for (int i = 0; i < body.getChildCount(); i++) {
                    View child = body.getChildAt(i);
                    if (child == chart || child.getVisibility() == View.GONE) continue;
                    LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) child.getLayoutParams();
                    other += child.getMeasuredHeight() + p.topMargin + p.bottomMargin;
                }
                viewSize(chart, -1, Math.max(ui.dp(120), budget - other));
            }
            if (battery != null) {
                // The readings stay at the bottom; the battery cell fills the rest of the card.
                View strip = chargingData.getVisibility() == View.VISIBLE ? chargingData : batteryData;
                int stripHeight = strip.getVisibility() == View.GONE ? 0
                        : strip.getMeasuredHeight() + ((LinearLayout.LayoutParams) strip.getLayoutParams()).topMargin;
                // The percentage cell can give a little height back to wrapped readings.
                // A fixed 96 dp floor made otherwise readable narrow cards overflow by
                // only a few pixels; reserve the actual readable text block instead.
                viewSize(battery, -1, Math.max(battery.minimumContentHeight(), budget - stripHeight));
            }
            super.onMeasure(widthSpec, heightSpec);
        }

        private void capacitySpacing(View view, int top, int bottom) {
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams)view.getLayoutParams();
            int topPx = ui.dp(top), bottomPx = ui.dp(bottom);
            if (params.topMargin != topPx || params.bottomMargin != bottomPx) {
                params.topMargin = topPx; params.bottomMargin = bottomPx;
                view.setLayoutParams(params);
            }
        }

        private void addReading(String key) {
            addReading(body, key);
        }

        private void addReading(LinearLayout container, String key) {
            ReadingRow line = new ReadingRow(label(key));
            container.addView(line, new LinearLayout.LayoutParams(-1, -2));
            amounts.put(key, line.value);
        }

        /** A value never takes the entire width away from its label; rows are divided by a hairline. */
        private final class ReadingRow extends LinearLayout {
            final TextView name, value;
            final Paint rule = new Paint();
            boolean narrow;

            ReadingRow(String label) {
                super(host.activity());
                setGravity(Gravity.CENTER_VERTICAL);
                setWillNotDraw(false);
                name = ui.text(label, 13.5f, false);
                name.setTextColor(ui.muted);
                value = new FittedValue("—", 12, 15, 2);
                addView(name);
                addView(value);
                rule.setColor(ui.raised);
            }

            @Override
            protected void onMeasure(int widthSpec, int heightSpec) {
                narrow = MeasureSpec.getSize(widthSpec) < ui.dp(220 * ui.textScale);
                setOrientation(narrow ? VERTICAL : HORIZONTAL);
                setPadding(0, ui.dp(narrow ? 5 : 8), 0, ui.dp(narrow ? 5 : 8));
                linearSize(name, narrow ? -1 : 0, -2, narrow ? 0 : 1, 0);
                linearSize(value, narrow ? -1 : 0, -2, narrow ? 0 : 1, narrow ? 0 : ui.dp(8));
                value.setGravity(narrow ? Gravity.START : Gravity.END);
                super.onMeasure(widthSpec, heightSpec);
            }

            @Override
            protected void onDraw(Canvas canvas) {
                ViewParent parent = getParent();
                if (parent instanceof ViewGroup && ((ViewGroup) parent).indexOfChild(this) > 0
                        && ((ViewGroup) parent).getChildAt(((ViewGroup) parent).indexOfChild(this) - 1) instanceof ReadingRow)
                    canvas.drawRect(0, 0, getWidth(), Math.max(1, ui.dp(1)), rule);
            }
        }

        private void buildCharging(LinearLayout container, boolean detailed) {
            InfoTiles tiles = new InfoTiles();
            infoTiles = tiles;
            chargingWatts = tiles.add("bolt", T.dashboardChargingPower()).value;
            InfoCell time = tiles.add("clock", T.dashboardTimeToFull());
            chargingEta = time.value;
            tiles.cells.get(0).narrowLabel(T.dashboardChargingPower(), T.dashboardChargingPowerCompact());
            chargingEtaLabel = time.label;
            chargingEtaTag = time.addTag();
            container.addView(tiles, new LinearLayout.LayoutParams(-1, -2));
            // Muted dashes and the time slot's short state replace explanatory notes;
            // the full status is the time tile's long-press tooltip.
        }

        private void refreshCharging(Map<String, TelemetryHistory.Reading> values) {
            ChargingEstimate.Result estimate = chargingEstimate(values);
            if (batteryData != null) {
                batteryData.setVisibility(estimate.charging ? View.GONE : View.VISIBLE);
                chargingData.setVisibility(estimate.charging ? View.VISIBLE : View.GONE);
                battery.setCharging(estimate.charging);
            }
            chargingWatts.setText(
                    estimate.inputWatts == null
                            ? "—"
                            : T.fmtQuantity(Fmt.decimal(Locale.US, estimate.inputWatts, 0), T.unitsWatt()));
            chargingWatts.setTextColor(estimate.inputWatts == null ? ui.muted : ui.ok);
            String[] etaLabels = chargeTimeLabels(estimate, true);
            String etaLabel = etaLabels[1];
            if (chargingEtaLabel instanceof DashboardCaption)
                ((DashboardCaption) chargingEtaLabel).setLabels(etaLabels[0], etaLabel, etaLabels[2]);
            else chargingEtaLabel.setText(etaLabel);
            showChargeTime(chargingEta, chargingEtaTag, estimate);
            describeCharging(chargingWatts, T.dashboardChargingPower(), T.dashboardBatterySidePower());
            describeCharging(chargingEta, (chargingEtaTag != null && chargingEtaTag.getVisibility() == View.VISIBLE
                                    ? T.dashboardChargingTimeTag(etaLabels[0], chargingEtaTag.getText()) : etaLabels[0]),
                    estimate.status);
            if (chargingTemperature != null) {
                TelemetryHistory.Reading temperature = values.get("temperature_c");
                chargingTemperature.setText(format(temperature));
                chargingTemperature.setTextColor(
                        temperature != null && temperature.live ? ui.text : ui.muted);
                describeCharging(chargingTemperature, T.dashboardBatteryTemperature(), "");
            }
        }

        private void describeCharging(TextView value, String label, String detail) {
            String description = detail.isEmpty() ? T.dashboardChargingDescription(label, value.getText())
                    : T.dashboardChargingDescriptionDetail(label, value.getText(), detail);
            value.setContentDescription(description);
            // The long-press tooltip sits on the whole tile, also when a tag shares the value line.
            View holder = value.getParent() instanceof View ? (View) value.getParent() : null;
            if (holder != null && !(holder instanceof InfoCell) && holder.getParent() instanceof InfoCell)
                holder = (View) holder.getParent();
            if (holder != null) holder.setTooltipText(description);
        }

        void refresh(Map<String, TelemetryHistory.Reading> values) {
            if (!active) return;
            if (quickAccess != null) quickAccess.refresh();
            if (settingTiles != null) settingTiles.refresh();
            if (capacityValue != null) refreshCapacity();
            if (chargingWatts != null) refreshCharging(values);
            boolean anyStale = false;
            long oldest = Long.MAX_VALUE;
            boolean styled = widget.type.equals("scooter") || widget.type.equals("trip")
                    || widget.type.equals("health") || widget.type.equals("overview")
                    || widget.type.equals("tiles");
            ArrayList<String> shown = new ArrayList<>(amounts.keySet());
            shown.addAll(watched);
            if (big != null || gauge != null) shown.add(widget.metric);
            if (battery != null) shown.add("battery_percent");
            for (String key : shown) {
                TelemetryHistory.Reading r = values.get(key);
                boolean live = r != null && r.live;
                anyStale |= r != null && r.value != null && !live;
                if (r != null && r.value != null && !live) oldest = Math.min(oldest, r.capturedWallMs);
                TextView amount = amounts.get(key);
                if (amount != null) {
                    Formatted text = shown(r);
                    amount.setText(styled && !key.equals("driving_mode") ? withUnit(text, live) : text);
                    amount.setTextColor(live ? ui.text : ui.muted);
                    if (widget.type.equals("battery") && key.equals("power_w") && live && r.number != null)
                        amount.setTextColor(r.number < 0 ? ui.ok : r.number > 0 ? ui.danger : ui.text);
                    amount.setContentDescription(
                            r != null && !live
                                    ? T.dashboardReadingSeen(label(key), text, when(r.capturedWallMs))
                                    : T.dashboardReading(label(key), text));
                    if (batteryData != null && amount.getParent() instanceof View)
                        ((View) amount.getParent()).setTooltipText(amount.getContentDescription());
                    if (key.equals("driving_mode")) {
                        ui.styleDriveMode(amount, text.toString(), live);
                        if (clean && amount.getCurrentTextColor() == ui.accent) amount.setTextColor(ui.accentText);
                    }
                }
            }
            TelemetryHistory.Reading selected = values.get(widget.metric);
            boolean selectedLive = selected != null && selected.live;
            if (big != null) {
                Formatted text = shown(selected);
                big.setText(widget.metric.equals("driving_mode") ? text : withUnit(text, selectedLive));
                big.setTextColor(selectedLive ? ui.text : ui.muted);
                big.setContentDescription(selected != null && !selectedLive ? T.dashboardReadingSeen(label(widget.metric), text, when(selected.capturedWallMs)) : T.dashboardReading(label(widget.metric), text));
                if (widget.metric.equals("driving_mode")) {
                    big.setContentDescription(selected != null && !selectedLive
                                    ? T.dashboardDriveModeReadingSeen(text, when(selected.capturedWallMs)) : T.dashboardDriveModeReading(text));
                    ui.styleDriveMode(big, text.toString(), selectedLive);
                }
                if (subtitle != null && widget.type.equals("metric"))
                    subtitle.setVisibility(widget.title.equalsIgnoreCase(englishLabel(widget.metric))
                            ? View.GONE : View.VISIBLE);
            }
            if (headingIcon != null) headingIcon.set(readingIcon(widget.metric), selectedLive ? color(widget) : ui.dim);
            if (gauge != null) {
                gauge.set(selected);
                subtitle.setVisibility(widget.title.equalsIgnoreCase(englishLabel(widget.metric))
                        ? View.GONE : View.VISIBLE);
            }
            if (battery != null) {
                TelemetryHistory.Reading charge = values.get("battery_percent"),
                        charging = values.get("charging_state");
                boolean isCharging =
                        charging != null
                                && charging.live
                                && charging.number != null
                                && charging.number == 1;
                battery.set(charge == null ? null : charge.number,
                        showsRange ? rangeText(values.get("remaining_range_km")) : "",
                        isCharging, charge != null && charge.live);
            }
            if (healthDetail != null) {
                TelemetryHistory.Reading cycles = values.get("charge_cycles"),
                        rated = values.get("rated_capacity_ah");
                ArrayList<String> parts = new ArrayList<>();
                if (cycles != null && cycles.value != null)
                    parts.add(T.dashboardReportedCharges(cycles.number != null ? String.format(Locale.US, "%.0f", cycles.number)
                            : format(cycles)));
                if (rated != null && rated.value != null) parts.add(T.dashboardRated(format(rated)));
                healthDetail.setText(android.text.TextUtils.join("\n", parts));
                healthDetail.setVisibility(parts.isEmpty() ? View.GONE : View.VISIBLE);
            }
            if (photo != null) {
                // Model identity is saved with the profile, independent of live readings.
                boolean show = ScooterPhotos.show(photo, activeModel(), 2);
                photo.setVisibility(show ? View.VISIBLE : View.GONE);
                photoHint.setVisibility(show ? View.GONE : View.VISIBLE);
            }
            if (rideButton != null) refreshRecorder();
            // Home overview/tiles keep cached values muted without a separate age footer,
            // including while the header shows Connecting. Classic data cards retain their age.
            boolean sameAsHeader = anyStale && headerSeenMs > 0 && when(oldest).equals(when(headerSeenMs));
            boolean aged = !newHome(widget.type) && anyStale && !sameAsHeader;
            footer.setText(aged ? shortAge(oldest) : "");
            // A card too narrow for its age keeps the clock alone; the description keeps the age.
            ui.statusRungs(footer, footer.getText());
            footer.setContentDescription(aged ? T.dashboardLastSeen(when(oldest)) : "");
            footer.setTooltipText(aged ? T.dashboardLastSeen(when(oldest)) : null);
            footer.setVisibility(aged ? View.VISIBLE : View.GONE);
            if (chargingData != null && chargingData.getVisibility() == View.VISIBLE)
                footer.setVisibility(View.GONE);
            if (overview != null) overview.refresh(values);
            if (readingTiles != null) readingTiles.refresh(values);
            if (chart != null) refreshChart(values);
            // Editing is a layout operation; data controls stay inert until Done.
            if (editing) disableInputs(body);
        }

        private void refreshRecorder() {
            boolean recording = service != null && service.isRecording();
            boolean ready = service != null && service.ble.ready();
            boolean saving = service != null && service.phoneSaving();
            BleController.State state = service == null ? null : service.ble.state();
            boolean connecting = service != null && !ready && (service.ble.connected()
                    || state == BleController.State.CONNECTING || state == BleController.State.AUTHENTICATING
                    || state == BleController.State.SCANNING || state == BleController.State.WAITING);
            boolean enabled = service != null && (recording || ready) && !saving;
            // The ride is recording: "Starting…" has done its job.
            if (recording) recorderStarting = false;
            boolean starting = recorderStarting;
            // Disconnected, the grey disc stays tappable and starts a connection.
            rideButton.setEnabled(enabled || (service != null && !recording && !saving && !connecting));
            recordDisc.setBackground(oval(enabled || recording ? ui.accent : ui.raised));
            recordGlyph.set(recording ? "stop" : "record", enabled || recording ? ui.onAccent : ui.dim);
            if (recordingShown == null || recordingShown != recording || startingShown != starting) {
                recordingShown = recording;
                startingShown = starting;
                setBackground(chrome());
            }
            RideStore.Summary summary = recording ? service.rides.activeSummary() : null;
            if (recording) {
                long seconds = summary == null ? 0 : summary.elapsedMs / 1000;
                String clock = seconds >= 3600
                        ? String.format(Fmt.locale(Locale.US), "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
                        : String.format(Fmt.locale(Locale.US), "%d:%02d", seconds / 60, seconds % 60);
                recorderClock = clock;
                rideTitle.setTextColor(ui.danger);
                rideTitle.setTypeface(ui.semibold());
                showRecorderTitle();
                ArrayList<String> parts = new ArrayList<>();
                if (summary != null && summary.distanceKm != null) parts.add(distance(summary.distanceKm));
                if (summary != null && summary.measuredThroughputWh != null)
                    parts.add(T.fmtQuantity(Fmt.decimal(Locale.US, summary.measuredThroughputWh, 0), T.unitsWattHour()));
                rideButton.setContentDescription(DashboardWords.recordingSpoken(clock, parts));
                rideDetail.setText(parts.isEmpty() ? T.dashboardMeasuring() : android.text.TextUtils.join(" · ", parts));
            } else {
                recorderClock = null;
                rideTitle.setTextColor(enabled ? ui.text : ui.muted);
                rideTitle.setTypeface(ui.medium());
                showRecorderTitle();
                rideDetail.setText(saving ? T.dashboardSavingRide() : connecting ? T.dashboardConnecting() : "");
                rideButton.setContentDescription(recordIdleSpoken(saving, ready, connecting));
            }
            // A hidden caption is never re-measured by setText alone; show it when it has words.
            int detail = rideDetail.length() > 0 && !compactRecorder ? View.VISIBLE : View.GONE;
            if (rideDetail.getVisibility() != detail) rideDetail.setVisibility(detail);
            // Starting (only ever from a ready disc, so no caption shows): the disc looks pressed,
            // with a soft red ring, and says what is happening.
            if (starting) startingRecorder();
            // While a card tap would start (or connect), the card is the one spoken control with the
            // disc's label, and the disc steps out of focus so it is not announced twice. Recording,
            // starting, saving or editing, the card goes inert and the disc is the control again.
            boolean wide = recorderCard && RecorderTap.card(editing, rideButton.isEnabled(), recording,
                    service == null || ready, starting) != RecorderTap.NONE;
            if (wideTap && !wide && !starting) recorder.setPressed(false);
            wideTap = wide;
            setClickable(wide);
            body.setClickable(wide);
            setFocusable(wide);
            setImportantForAccessibility(wide ? IMPORTANT_FOR_ACCESSIBILITY_YES : IMPORTANT_FOR_ACCESSIBILITY_AUTO);
            setContentDescription(wide ? rideButton.getContentDescription() : null);
            rideButton.setFocusable(!wide);
            rideButton.setImportantForAccessibility(wide ? IMPORTANT_FOR_ACCESSIBILITY_NO : IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        }

        private void startingRecorder() {
            GradientDrawable ring = oval(ui.accent);
            ring.setStroke(ui.dp(3), blend(ui.accent, ui.surface, .45f));
            recordDisc.setBackground(ring);
            rideButton.setContentDescription(T.dashboardRecorderStarting());
        }

        private GradientDrawable oval(int fill) {
            GradientDrawable disc = new GradientDrawable();
            disc.setShape(GradientDrawable.OVAL);
            disc.setColor(fill);
            return disc;
        }

        private String distance(double km) {
            String metric = T.fmtQuantity(Fmt.decimal(Locale.US, km, 1), T.unitsKm()),
                    miles = T.fmtQuantity(Fmt.decimal(Locale.US, km / 1.609344, 1), T.unitsMi());
            String units = units();
            return units.equals("km") ? metric : units.equals("dual") ? T.fmtDual(miles, metric) : miles;
        }

        /** Side by side, or the details stacked under the percentage for narrow cards. */
        private void layoutHealth(LinearLayout top, TextView health, TextView detail, boolean stack) {
            top.setOrientation(stack ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
            top.setGravity(stack ? Gravity.START : Gravity.BOTTOM);
            health.setLayoutParams(stack ? new LinearLayout.LayoutParams(-1, -2) : new LinearLayout.LayoutParams(0, -2, 1));
            LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-2, -2);
            if (stack) space.topMargin = ui.dp(2);
            else { space.setMarginStart(ui.dp(8)); space.bottomMargin = ui.dp(4); }
            detail.setGravity(stack ? Gravity.START : Gravity.END);
            detail.setLayoutParams(space);
        }

        private void disableInputs(View v) {
            if (v instanceof ViewGroup) {
                for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++)
                    disableInputs(((ViewGroup) v).getChildAt(i));
            }
            // Edit-mode shortcuts (the note's editor) stay enabled; data controls go inert.
            if (v.isClickable() && !EDIT_ACTION.equals(v.getTag())) v.setEnabled(false);
        }

        private void refreshCapacity() {
            if (service == null) return;
            if (service.simulating()) {
                // Supersede real-history callbacks even when their worker is still running.
                if (!capacitySample) capacityRequest++;
                capacityPending = false;
                capacityChecked = 0;
                capacitySample = true;
                // One "Sample" chip marks the preview; the app-wide banner already says sample data.
                Double advertised = rating(widget);
                double sample = advertised == null ? 610 : advertised * .95;
                capacityResult = null;
                capacityUnavailable = null;
                capacityValue.setText(T.dashboardApproxEnergy(Fmt.decimal(Locale.US, sample, 0), T.unitsWattHour()));
                capacityValue.setTextColor(ui.text);
                styleTag(capacityMethod, T.dashboardSample(), false);
                capacitySummary.setText(T.dashboardSampleRides(Fmt.decimal(Locale.US, sample * .96, 0), Fmt.decimal(Locale.US, sample * 1.04, 0), T.unitsWattHour()));
                showComparison(advertised == null ? null : 95d, advertised, false, true);
                return;
            }
            if (capacitySample) {
                capacitySample = false;
                capacityMessage(T.dashboardProcessing(), T.dashboardCheckingRides());
            }
            ScooterProfiles.Profile current = service.profiles.active();
            if (current == null || !profileId.equals(current.id)) {
                capacityRequest++;
                capacityPending = false;
                capacityChecked = 0;
                capacityResult = null;
                capacityUnavailable = current == null ? M.dashboardNoScooter() : null;
                capacityMessage(current == null ? capacityUnavailable : T.dashboardProcessing(),
                        current == null ? capacityUnavailable : T.dashboardCheckingRides());
                return;
            }
            if (capacityPending) return;
            long now = android.os.SystemClock.elapsedRealtime();
            if (capacityChecked > 0 && now - capacityChecked < 10000) return;
            capacityChecked = now;
            capacityPending = true;
            long request = ++capacityRequest;
            capacityUnavailable = null;
            // Keep a genuine last estimate during refresh; no completed evidence means processing.
            if (!hasCapacityValue()) {
                capacityResult = null;
                capacityMessage(T.dashboardProcessing(), T.dashboardCheckingRides());
            } else {
                capacitySummary.setText(T.dashboardUpdatingRides());
            }
            service.requestCapacityEstimate(
                    rating(widget),
                    (int) (widget.windowMs / 86400000L),
                    result -> {
                        if (request != capacityRequest) return;
                        capacityPending = false;
                        if (closed || !active) return;
                        ScooterProfiles.Profile selected = service.profiles.active();
                        if (service.simulating() || selected == null || !profileId.equals(selected.id)) {
                            refreshCapacity();
                            return;
                        }
                        if (!profileId.equals(result.profileId)) return;
                        // Deferred (F1): ScooterService's unavailableReason is an English String with no
                        // Msg yet, so it shows in English; only the local "no scooter" case is a Msg here.
                        if (result.unavailableReason != null) {
                            capacityUnavailable = result.unavailableReason;
                            if (hasCapacityValue()) {
                                capacitySummary.setText(T.dashboardRefreshUnavailable());
                            } else {
                                capacityResult = null;
                                capacityMessage(T.dashboardEstimateUnavailable(), capacityUnavailable);
                            }
                            return;
                        }
                        capacityResult = result;
                        capacityUnavailable = null;
                        boolean estimated = result.estimatedUsableWh != null;
                        boolean proxy = !estimated && result.throughputEquivalentWh != null;
                        Double value =
                                estimated
                                        ? result.estimatedUsableWh
                                        : result.throughputEquivalentWh;
                        Double percent =
                                estimated
                                        ? result.percentOfAdvertised
                                        : result.throughputPercentOfAdvertised;
                        capacityValue.setText(
                                value == null
                                        ? T.dashboardNotEnoughData()
                                        : T.dashboardApproxEnergy(Fmt.decimal(Locale.US, value, 0), T.unitsWattHour()));
                        capacityValue.setTextColor(
                                value == null ? ui.muted : proxy ? ui.orange : ui.text);
                        // The title and lab glyph say "experiment"; only the proxy keeps an honesty chip.
                        // Deferred (B5 follow-up): BatteryCapacityEstimate.Result.label has no Msg yet, so
                        // the chip, summary, bar description and details caveat show it in English.
                        styleTag(capacityMethod, proxy ? result.label : null, true);
                        if (value == null) {
                            // The bar becomes progress toward the points and rides one evidence group needs.
                            capacitySummary.setText(result.label);
                            capacityCompare.setVisibility(View.GONE);
                            capacityReference.setVisibility(View.GONE);
                            boolean progress = result.qualifiedRides + result.proxyRides > 0;
                            int groupRides = result.batteryDropPoints >= result.proxyBatteryDropPoints
                                    ? result.qualifiedRides : result.proxyRides;
                            capacityBar.set(progress
                                    ? Math.min(100, 100 * Math.min(result.progressPoints / BatteryCapacityEstimate.MIN_TOTAL_DROP,
                                            groupRides / (double) BatteryCapacityEstimate.MIN_RIDES))
                                    : 0d);
                            capacityBar.setContentDescription(result.label);
                            capacityBar.setVisibility(progress ? View.VISIBLE : View.GONE);
                            return;
                        }
                        int rides = estimated ? result.qualifiedRides : result.proxyRides;
                        Double low = estimated ? result.estimatedLowWh : result.throughputLowWh;
                        Double high = estimated ? result.estimatedHighWh : result.throughputHighWh;
                        capacitySummary.setText(low != null && high != null
                                ? T.dashboardCapacitySummary(T.dashboardCapacityRides(rides, Fmt.integer(rides)), T.fmtRange(Fmt.decimal(Locale.US, low, 0), Fmt.decimal(Locale.US, high, 0), T.unitsWattHour()))
                                : capacityPoints(rides, estimated ? result.batteryDropPoints : result.proxyBatteryDropPoints));
                        showComparison(percent, result.advertisedWh, proxy, false);
                    });
        }

        private boolean hasCapacityValue() {
            return capacityResult != null && (capacityResult.estimatedUsableWh != null
                    || capacityResult.throughputEquivalentWh != null);
        }

        private void capacityMessage(CharSequence value, CharSequence summary) {
            capacityValue.setText(value);
            capacityValue.setTextColor(ui.muted);
            capacitySummary.setText(summary);
            styleTag(capacityMethod, null, false);
            capacityCompare.setVisibility(View.GONE);
            capacityReference.setVisibility(View.GONE);
            capacityBar.setVisibility(View.GONE);
        }

        /** "95% of 716 Wh" with its bar; without a reference, a Set reference chip instead. */
        private void showComparison(Double percent, Double reference, boolean proxy, boolean sample) {
            boolean compared = percent != null && reference != null;
            capacityCompare.setText(compared
                    ? T.dashboardPercentOf(Fmt.decimal(Locale.US, percent, 0), Fmt.decimal(Locale.US, reference, 0), T.unitsWattHour()) : "");
            capacityCompare.setTextColor(proxy ? ui.orange : ui.muted);
            capacityCompare.setVisibility(compared ? View.VISIBLE : View.GONE);
            capacityReference.setVisibility(reference == null && !sample && !panel ? View.VISIBLE : View.GONE);
            capacityBar.set(compared ? percent : null);
            capacityBar.setContentDescription(compared ? capacityCompare.getText() : null);
            capacityBar.setVisibility(compared ? View.VISIBLE : View.GONE);
        }

        /** One sheet of label and value rows plus a single caveat line; no nested method dialog. */
        private void showCapacityDetails() {
            LinearLayout details = column();
            BatteryCapacityEstimate.Result r = capacityResult;
            boolean sample = service != null && service.simulating();
            if (sample) {
                details.addView(detailRow(T.dashboardRides(), "8"));
                details.addView(detailRow(T.dashboardPoints(), "72"));
                details.addView(detailRow(T.dashboardWindow(), shortWindow(widget.windowMs)));
                details.addView(caveat(T.dashboardSample()));
            } else if (r == null) {
                details.addView(caveat(capacityUnavailable != null ? capacityUnavailable : T.dashboardCheckingRides()));
            } else {
                boolean estimated = r.estimatedUsableWh != null;
                boolean proxy = !estimated && r.throughputEquivalentWh != null;
                boolean confirmedGroup = estimated || (!proxy && r.batteryDropPoints >= r.proxyBatteryDropPoints);
                int rides = confirmedGroup ? r.qualifiedRides : r.proxyRides;
                double points = confirmedGroup ? r.batteryDropPoints : r.proxyBatteryDropPoints;
                details.addView(detailRow(T.dashboardRides(), Integer.toString(rides)));
                details.addView(detailRow(T.dashboardPoints(), estimated || proxy
                        ? String.format(Fmt.locale(Locale.US), "%.0f", points)
                        : String.format(Fmt.locale(Locale.US), "%.0f / %.0f", Math.floor(r.progressPoints),
                                BatteryCapacityEstimate.MIN_TOTAL_DROP)));
                if (!estimated && !proxy && rides > 0 && rides < BatteryCapacityEstimate.MIN_RIDES)
                    details.addView(detailRow(T.dashboardRidesNeeded(), Integer.toString(BatteryCapacityEstimate.MIN_RIDES)));
                Double low = estimated ? r.estimatedLowWh : proxy ? r.throughputLowWh : null;
                Double high = estimated ? r.estimatedHighWh : proxy ? r.throughputHighWh : null;
                if (low != null && high != null)
                    details.addView(detailRow(T.dashboardRange90(), T.fmtRange(Fmt.decimal(Locale.US, low, 0), Fmt.decimal(Locale.US, high, 0), T.unitsWattHour())));
                if (r.ahPerGauge != null)
                    details.addView(detailRow(T.dashboardChargePerGauge(), r.ahPerGaugeLow != null && r.ahPerGaugeHigh != null
                            ? T.dashboardAhRange(Fmt.decimal(Locale.US, r.ahPerGauge, 1), T.unitsAmpHour(), Fmt.decimal(Locale.US, r.ahPerGaugeLow, 1), Fmt.decimal(Locale.US, r.ahPerGaugeHigh, 1))
                            : T.fmtQuantity(Fmt.decimal(Locale.US, r.ahPerGauge, 1), T.unitsAmpHour())));
                if (r.chargingAhPerGauge != null)
                    details.addView(detailRow(T.dashboardChargingCheck(), String.format(Locale.US, "%.1f Ah · %d charge%s",
                            r.chargingAhPerGauge, r.chargingSessions, r.chargingSessions == 1 ? "" : "s")));
                if (rides > 0)
                    details.addView(detailRow(T.dashboardCoverage(),
                            T.fmtPercent(Fmt.decimal(Locale.US, r.electricalCoverage * 100, 0))));
                if (r.minimumTemperatureC != null && r.maximumTemperatureC != null)
                    details.addView(detailRow(T.dashboardPackTemp(), DisplayTemperature.range(
                            r.minimumTemperatureC, r.maximumTemperatureC, temperatureUnit(), 0)));
                details.addView(detailRow(T.dashboardWindow(), !r.includesActive
                        ? T.dashboardEstimateWindowDays(r.windowDays, r.windowDays)
                        : T.dashboardEstimateWindowLive(T.dashboardEstimateWindowDays(r.windowDays, r.windowDays))));
                if (r.rejectedRides > 0)
                    details.addView(detailRow(T.dashboardRejected(), r.reason == null
                            ? Fmt.integer(r.rejectedRides)
                            : T.dashboardRejectedReason(Fmt.integer(r.rejectedRides), r.reasonMsg)));
                if (!estimated && !proxy && r.qualifiedRides + r.proxyRides == 0 && r.rejectedRides == 0)
                    details.addView(caveat(r.label));
                details.addView(caveat(proxy ? T.dashboardProxyCaveat()
                        : T.dashboardEnergyCaveat()));
                if (capacityUnavailable != null) details.addView(caveat(capacityUnavailable));
            }
            show(T.dashboardUsableEnergy(), details);
        }

        private LinearLayout detailRow(String label, String value) {
            LinearLayout line = row();
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setMinimumHeight(ui.dp(40));
            TextView name = ui.text(label, 14, false);
            name.setTextColor(ui.muted);
            line.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            TextView amount = ui.text(value, 14, true);
            amount.setGravity(Gravity.END);
            LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-2, -2);
            space.setMarginStart(ui.dp(12));
            line.addView(amount, space);
            line.setContentDescription(T.dashboardReading(label, value));
            line.setFocusable(true);
            return line;
        }

        private TextView caveat(CharSequence text) {
            TextView line = ui.caption(text.toString());
            line.setPadding(0, ui.dp(10), 0, 0);
            return line;
        }

        private void refreshChart(Map<String, TelemetryHistory.Reading> values) {
            long now = System.currentTimeMillis();
            String key = widget.type.equals("health") ? "battery_health_percent" : widget.metric;
            if (service != null && service.simulating()) {
                history = service.simulationHistory(key, now - widget.windowMs, now);
                livePoints.clear(); wasLive = service.ble.connected();
                drawChart(values, key);
                if (!widget.type.equals("health")) {
                    String sample = T.dashboardSampleWindow(DashboardWords.windowName(widget.windowMs));
                    ((DashboardCaption) subtitle).setLabels(label(key) + " · " + sample, sample, sample);
                }
                return;
            }
            if (widget.type.equals("live")) {
                TelemetryHistory.Reading reading = values.get(key);
                boolean live = reading != null && reading.live && reading.number != null;
                if (live
                        && (livePoints.isEmpty()
                                || reading.capturedWallMs
                                        > livePoints.get(livePoints.size() - 1).millis)) {
                    boolean gap =
                            !wasLive
                                    || (!livePoints.isEmpty()
                                            && reading.capturedWallMs
                                                            - livePoints.get(livePoints.size() - 1)
                                                                    .millis
                                                    > 20000);
                    livePoints.add(new Ui.Point(reading.capturedWallMs, reading.number, gap));
                }
                wasLive = live;
                while (!livePoints.isEmpty()
                        && (livePoints.get(0).millis < now - widget.windowMs
                                || livePoints.size() > 900)) livePoints.remove(0);
            }
            if (service != null
                    && service.history != null
                    && !querying
                    && (now - queriedAt > 10000 || !queryKey.equals(key))) {
                querying = true;
                queriedAt = now;
                queryKey = key;
                service.history.query(
                        profileId,
                        key,
                        now - widget.windowMs,
                        now,
                        points -> {
                            if (closed || !active || service.simulating()) return;
                            querying = false;
                            history = points;
                            drawChart(readings(), key);
                        });
            }
            drawChart(values, key);
        }

        private void drawChart(Map<String, TelemetryHistory.Reading> values, String key) {
            ArrayList<Ui.Point> points = new ArrayList<>();
            long tailStart = livePoints.isEmpty() ? Long.MAX_VALUE : livePoints.get(0).millis;
            for (TelemetryHistory.Point p : history)
                if (p.wallMs < tailStart)
                    points.add(new Ui.Point(p.wallMs, chartNumber(key, p.value), p.gapBefore));
            for (Ui.Point p : livePoints)
                points.add(new Ui.Point(p.millis, chartNumber(key, p.value), p.gapBefore));
            chart.setAxis(key, widget.absoluteAxis);
            // After a disconnect the live trace turns grey instead of adding a "Last seen" footer.
            boolean frozen = widget.type.equals("live") && !wasLive && !points.isEmpty();
            chart.set(points, chartUnit(key, values), frozen ? ui.dim : color(widget), T.dashboardNoReadings());
            if (!widget.type.equals("health")) {
                boolean reportedRange = key.equals("remaining_range_km");
                String metricLabel = reportedRange ? T.dashboardRangeReported() : label(key);
                String window = DashboardWords.windowName(widget.windowMs);
                boolean named = !reportedRange && widget.title.equalsIgnoreCase(englishLabel(key));
                String normal = (named ? "" : (reportedRange ? T.dashboardScooterRange() : ScooterReadingLabels.label(key, metricLabel)) + " · ") + window;
                String compact = (named ? "" : (reportedRange ? T.dashboardScooterRangeCompact() : ScooterReadingLabels.compact(key, metricLabel)) + " · ") + window;
                ((DashboardCaption) subtitle).setLabels(metricLabel + " · " + window, normal, compact);
            }
        }
    }

    /** White L at the lower-right corner of a card being edited: drag it to resize. */
    private final class CornerMark extends View {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        CornerMark() {
            super(host.activity());
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float inset = ui.dp(9), arm = ui.dp(13), radius = ui.dp(4);
            float right = getWidth() - inset, bottom = getHeight() - inset;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(ui.dp(2.5f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(ui.dark ? Ui.withAlpha(Color.WHITE, 220) : ui.text);
            Path p = new Path();
            p.moveTo(right - arm, bottom);
            p.lineTo(right - radius, bottom);
            p.quadTo(right, bottom, right, bottom - radius);
            p.lineTo(right, bottom - arm);
            canvas.drawPath(p, paint);
        }
    }

    /**
     * Tall battery cell: a dark rounded track that fills from the bottom to the charge level,
     * with the percentage and range in white on the red. Last-seen charge turns the fill grey.
     */
    private final class BatteryCell extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint measurementPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Rect measurementBounds = new Rect();
        private final Paint.FontMetrics measurementMetrics = new Paint.FontMetrics();
        private final Path trackClip = new Path();
        private final Path fillShape = new Path();
        private final Path fillBand = new Path();
        private final RectF trackBounds = new RectF();
        private final Rect digits = new Rect();
        private final android.text.TextPaint linePaint = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
        private float shapeW = -1, shapeH = -1, shapeCorner = -1, shapeEdge = -1, shapeTop = -1;
        private boolean shapeClipped;
        private double percent = Double.NaN;
        private String detail = "";
        private boolean charging, live = true;

        BatteryCell() {
            super(host.activity());
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
            describe();
        }

        void set(Double value, String detail, boolean charging, boolean live) {
            double next = value != null && Double.isFinite(value) && value >= 0 && value <= 100
                    ? value : Double.NaN;
            String text = detail == null ? "" : detail;
            if (Double.compare(next, percent) == 0 && text.equals(this.detail)
                    && charging == this.charging && live == this.live) return;
            percent = next;
            this.detail = text;
            this.charging = charging;
            this.live = live;
            describe();
            invalidate();
        }

        void setCharging(boolean value) {
            if (value == charging) return;
            charging = value;
            describe();
            invalidate();
        }

        /** Enough room for a readable percentage, optional range/status and their padding. */
        int minimumContentHeight() {
            measurementPaint.setTypeface(ui.bold());
            measurementPaint.setTextSize(ui.sp(24));
            measurementPaint.getTextBounds("100", 0, 3, measurementBounds);
            float height = measurementBounds.height() + ui.dp(24);
            if (charging || !detail.isEmpty()) {
                measurementPaint.setTypeface(ui.medium());
                measurementPaint.setTextSize(ui.sp(12));
                measurementPaint.getFontMetrics(measurementMetrics);
                height += ui.dp(5) + measurementMetrics.descent - measurementMetrics.ascent;
            }
            return Math.max(ui.dp(48), (int)Math.ceil(height));
        }

        private void describe() {
            setContentDescription(
                    Double.isNaN(percent)
                    ? (detail.isEmpty()
                        ? (charging
                            ? (live
                                ? T.dashboardCellNotReportedCharging()
                                : T.dashboardCellNotReportedChargingStale())
                            : (live
                                ? T.dashboardCellNotReported()
                                : T.dashboardCellNotReportedStale()))
                        : (charging
                            ? (live
                                ? T.dashboardCellNotReportedLineCharging(detail)
                                : T.dashboardCellNotReportedLineChargingStale(detail))
                            : (live
                                ? T.dashboardCellNotReportedLine(detail)
                                : T.dashboardCellNotReportedLineStale(detail))))
                    : (detail.isEmpty()
                        ? (charging
                            ? (live
                                ? T.dashboardCellPercentChargingCount(Math.round(percent), Fmt.integer(Math.round(percent)))
                                : T.dashboardCellPercentChargingStaleCount(Math.round(percent), Fmt.integer(Math.round(percent))))
                            : (live
                                ? T.dashboardCellPercentCount(Math.round(percent), Fmt.integer(Math.round(percent)))
                                : T.dashboardCellPercentStaleCount(Math.round(percent), Fmt.integer(Math.round(percent)))))
                        : (charging
                            ? (live
                                ? T.dashboardCellPercentLineChargingCount(Math.round(percent), Fmt.integer(Math.round(percent)), detail)
                                : T.dashboardCellPercentLineChargingStaleCount(Math.round(percent), Fmt.integer(Math.round(percent)), detail))
                            : (live
                                ? T.dashboardCellPercentLineCount(Math.round(percent), Fmt.integer(Math.round(percent)), detail)
                                : T.dashboardCellPercentLineStaleCount(Math.round(percent), Fmt.integer(Math.round(percent)), detail)))));
        }

        @Override
        protected void onMeasure(int width, int height) {
            setMeasuredDimension(resolveSize(ui.dp(120), width), resolveSize(ui.dp(150), height));
        }

        /** The text with spaces (also no-break ones) taken off both ends. */
        private String withoutSpaces(CharSequence text) {
            int from = 0, to = text.length();
            while (from < to && Character.isSpaceChar(text.charAt(from))) from++;
            while (to > from && Character.isSpaceChar(text.charAt(to - 1))) to--;
            return text.subSequence(from, to).toString();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight(), corner = ui.dp(14);
            if (w <= 0 || h <= 0) return;
            boolean knownLive = live && !Double.isNaN(percent);
            int track = knownLive
                    ? blend(ui.dark ? ui.bg : ui.surface, ui.accent, ui.dark ? .26f : .12f)
                    : ui.raised;
            trackBounds.set(0, 0, w, h);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(track);
            canvas.drawRoundRect(trackBounds, corner, corner, paint);
            float edge = Math.max(1, ui.dp(1));
            float top = !Double.isNaN(percent) && percent > 0 ? h - (float) (h * percent / 100) : h;
            // All interior paint stays inside one silhouette that ends at the border's centre line,
            // so neither hardware clip antialiasing nor a shallow fill can put a fill pixel outside
            // the border's outer edge (a Samsung report showed fill outside the corners; the old
            // fill rounded its own, smaller corners).
            if (w != shapeW || h != shapeH || corner != shapeCorner || edge != shapeEdge) {
                trackBounds.inset(edge / 2, edge / 2);
                trackClip.reset();
                float inner = Math.max(0, corner - edge / 2);
                trackClip.addRoundRect(trackBounds, inner, inner, Path.Direction.CW);
                trackBounds.set(0, 0, w, h);
                shapeW = w; shapeH = h; shapeCorner = corner; shapeEdge = edge; shapeTop = -1;
            }
            // Quiet diagonal texture marks unused capacity even when the track and card
            // have similar brightness. Unknown and last-seen readings remain neutral.
            int saved = canvas.save();
            canvas.clipPath(trackClip);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(ui.dp(1));
            paint.setColor(blend(track, knownLive ? ui.accent : ui.dim, .25f));
            for (float x = -h; x < w; x += ui.dp(12))
                canvas.drawLine(x, h, x + h, 0, paint);
            canvas.restoreToCount(saved);
            paint.setStyle(Paint.Style.FILL);
            int fill = !live ? blend(ui.raised, ui.dim, ui.dark ? .45f : .6f)
                    : !Double.isNaN(percent) && percent < 15 && !charging ? ui.orange : ui.accent;
            if (top < h) {
                // A square-topped band intersected with the silhouette: the level edge stays
                // flat and the bottom follows the track's own corners at every height.
                if (top != shapeTop) {
                    fillBand.reset();
                    fillBand.addRect(0, top, w, h, Path.Direction.CW);
                    shapeClipped = fillShape.op(fillBand, trackClip, Path.Op.INTERSECT);
                    if (!shapeClipped) fillShape.set(fillBand);
                    shapeTop = top;
                }
                paint.setColor(fill);
                // If path ops reject a degenerate band, the canvas clip still keeps it inside.
                if (shapeClipped) canvas.drawPath(fillShape, paint);
                else {
                    saved = canvas.save();
                    canvas.clipPath(trackClip);
                    canvas.drawPath(fillShape, paint);
                    canvas.restoreToCount(saved);
                }
            }
            // Keep the full-capacity silhouette visible against the surrounding card.
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(edge);
            paint.setColor(knownLive ? blend(track, ui.accent, .6f) : ui.outline);
            trackBounds.inset(edge / 2, edge / 2);
            canvas.drawRoundRect(trackBounds, Math.max(0, corner - edge / 2), Math.max(0, corner - edge / 2), paint);
            paint.setStyle(Paint.Style.FILL);
            String amount = Double.isNaN(percent) ? "—" : Long.toString(Math.round(percent));
            String unit = Double.isNaN(percent) ? "" : "%";
            String line = charging ? T.dashboardCharging() : detail;
            float pad = ui.dp(12), available = Math.max(1, w - 2 * pad);
            float amountSize = ui.sp(34), unitSize = ui.sp(17), lineSize = ui.sp(12);
            // The language's percent template (fmt_percent) says where the sign sits: "50%" in English,
            // "%50" in Turkish, "50 %" in French. In English amount and unit stay "50" and "%".
            String before = null;
            if (!Double.isNaN(percent)) {
                Formatted shaped = Fmt.percent(Fmt.integerNumber(Math.round(percent)));
                amount = shaped.subSequence(shaped.numberStart(0), shaped.numberEnd(0)).toString();
                before = withoutSpaces(shaped.subSequence(0, shaped.numberStart(0)));
                unit = withoutSpaces(shaped.subSequence(shaped.numberEnd(0), shaped.length()));
            }
            paint.setTypeface(ui.bold());
            paint.setTextSize(amountSize);
            float amountWidth = paint.measureText(amount);
            paint.setTextSize(unitSize);
            float unitWidth = unit.isEmpty() ? 0 : paint.measureText(unit) + ui.dp(2);
            float beforeWidth = before == null || before.isEmpty() ? 0 : paint.measureText(before) + ui.dp(2);
            float fit = Math.min(1, available / Math.max(1, beforeWidth + amountWidth + unitWidth));
            // Keep the block inside short cells, too.
            fit = Math.min(fit, (h - pad) / Math.max(1, (amountSize + lineSize) * 1.1f));
            amountSize *= fit;
            unitSize *= fit;
            lineSize = Math.max(Math.min(lineSize, ui.sp(12) * fit), Math.min(ui.sp(10), lineSize));
            paint.setTextSize(amountSize);
            paint.getTextBounds("0", 0, 1, digits);
            float capHeight = digits.height();
            float gap = line.isEmpty() ? 0 : ui.dp(5);
            float lineHeight = line.isEmpty() ? 0 : lineSize;
            float block = capHeight + gap + lineHeight;
            float fillHeight = h - top;
            boolean inside = fillHeight >= block + ui.dp(18);
            float blockTop = inside ? top + (fillHeight - block) / 2
                    : Math.max(pad, top - ui.dp(10) - block);
            int ink = inside
                    ? (live ? ui.onAccent : (ui.dark ? blend(ui.muted, ui.text, .45f) : ui.text))
                    : (live ? ui.text : ui.muted);
            float baseline = blockTop + capHeight;
            paint.setTextAlign(Paint.Align.LEFT);
            paint.setColor(ink);
            // Right to left, the block keeps its reading order but sits against the right edge.
            boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
            float start = rtl ? w - pad - (beforeWidth + amountWidth + unitWidth) * fit : pad;
            if (beforeWidth > 0) {
                paint.setTypeface(ui.bold());
                paint.setTextSize(unitSize);
                canvas.drawText(before, start, baseline, paint);
            }
            paint.setTypeface(ui.bold());
            paint.setTextSize(amountSize);
            canvas.drawText(amount, start + beforeWidth * fit, baseline, paint);
            if (!unit.isEmpty()) {
                paint.setTextSize(unitSize);
                canvas.drawText(unit, start + (beforeWidth + amountWidth + ui.dp(2)) * fit, baseline, paint);
            }
            if (!line.isEmpty()) {
                paint.setTypeface(ui.medium());
                paint.setTextSize(lineSize);
                linePaint.set(paint);
                String shown = android.text.TextUtils.ellipsize(line, linePaint,
                        available, android.text.TextUtils.TruncateAt.END).toString();
                if (rtl) paint.setTextAlign(Paint.Align.RIGHT);
                canvas.drawText(shown, rtl ? w - pad : pad, baseline + gap + lineHeight * .9f, paint);
            }
        }
    }

    private final class Gauge extends View {
        private static final float START_ANGLE = 135f, SWEEP_ANGLE = 270f;
        private static final float FOOT = .70710678f;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint.FontMetrics fontMetrics = new Paint.FontMetrics();
        private final Rect glyphBounds = new Rect();
        private final RectF arcBounds = new RectF(), valueBounds = new RectF(),
                unitBounds = new RectF(), secondaryBounds = new RectF(),
                minimumLabelBounds = new RectF(), maximumLabelBounds = new RectF();
        private final DashboardConfig.Widget widget;
        private TelemetryHistory.Reading reading;
        private String amount = "—", unit = "", secondary = "";
        private float arcStroke;

        Gauge(DashboardConfig.Widget widget) {
            super(host.activity());
            this.widget = widget;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
            set(null);
        }

        void set(TelemetryHistory.Reading reading) {
            this.reading = reading;
            String formatted = format(reading);
            amount = formatted;
            unit = secondary = "";
            setContentDescription(reading != null && !reading.live
                            ? T.dashboardReadingSeen(label(widget.metric), formatted, when(reading.capturedWallMs)) : T.dashboardReading(label(widget.metric), formatted));
            splitReading(formatted);
            invalidate();
        }

        // Preserve the app's value precision and unit preference, including
        // both readings in dual-unit mode, rather than rounding gauge values.
        private void splitReading(String formatted) {
            if (reading == null || reading.number == null) return;
            String[] shown = ReadingUnits.gaugeParts(formatted, parts(reading));
            amount = shown[0];
            unit = shown[1];
            secondary = shown[2];
        }

        @Override
        protected void onDraw(Canvas canvas) {
            arcBounds.setEmpty();
            valueBounds.setEmpty();
            unitBounds.setEmpty();
            secondaryBounds.setEmpty();
            minimumLabelBounds.setEmpty();
            maximumLabelBounds.setEmpty();
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float pad = Math.min(ui.dp(6), Math.min(w, h) * .06f);
            arcStroke = Math.min(ui.dp(12), Math.min(w, h) * .06f);
            float labelGap = Math.min(ui.dp(8), h * .06f);
            String minimum = String.format(Locale.US, "%.0f", chartNumber(widget.metric, widget.minimum));
            String maximum = String.format(Locale.US, "%.0f", chartNumber(widget.metric, widget.maximum));
            float labelSize = Math.min(ui.sp(11), ui.dp(15));
            paint.setTypeface(ui.regular());
            paint.setTextSize(labelSize);
            paint.getFontMetrics(fontMetrics);
            float labelHeight = fontMetrics.descent - fontMetrics.ascent;
            // A 270-degree ring ends at cy + sqrt(.5)*r. Reserve a separate,
            // padded label row below those feet and center the complete graphic.
            float radius = Math.min((w - 2 * pad - arcStroke) / 2f,
                    (h - 2 * pad - arcStroke - labelGap - labelHeight) / (1 + FOOT));
            if (radius <= ui.dp(4)) return;
            float cx = w / 2f, footX = radius * FOOT;
            float labelWidth = Math.max(1, Math.min(2 * (cx - footX - pad),
                    2 * footX - ui.dp(8)));
            float measuredLabel = Math.max(paint.measureText(minimum), paint.measureText(maximum));
            labelSize *= Math.min(1, labelWidth / Math.max(1, measuredLabel));
            paint.setTextSize(labelSize);
            paint.getFontMetrics(fontMetrics);
            labelHeight = fontMetrics.descent - fontMetrics.ascent;
            float graphicHeight = (1 + FOOT) * radius + arcStroke + labelGap + labelHeight;
            float top = (h - graphicHeight) / 2f;
            float cy = top + arcStroke / 2f + radius;
            arcBounds.set(cx - radius, cy - radius, cx + radius, cy + radius);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(arcStroke);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(ui.raised);
            canvas.drawArc(arcBounds, START_ANGLE, SWEEP_ANGLE, false, paint);
            if (reading != null && reading.number != null) {
                double fraction = Math.max(0, Math.min(1,
                        (reading.number - widget.minimum) / (widget.maximum - widget.minimum)));
                paint.setColor(reading.live ? color(widget) : ui.muted);
                canvas.drawArc(arcBounds, START_ANGLE, (float) (SWEEP_ANGLE * fraction), false, paint);
            }

            paint.setStyle(Paint.Style.FILL);
            paint.setTextAlign(Paint.Align.CENTER);
            float growth = Math.max(1, radius / ui.dp(54));
            float valueSize = Math.min(ui.sp(34) * growth, radius * .82f);
            float unitSize = Math.min(ui.sp(13) * growth, radius * .30f);
            float secondarySize = Math.min(ui.sp(11) * growth, radius * .25f);
            float valueHeight = textHeight(amount, valueSize, ui.bold());
            float textWidth = paint.measureText(amount);
            float unitHeight = textHeight(unit, unitSize, ui.medium());
            textWidth = Math.max(textWidth, paint.measureText(unit));
            float secondaryHeight = textHeight(secondary, secondarySize, ui.regular());
            textWidth = Math.max(textWidth, paint.measureText(secondary));
            float unitGap = unit.isEmpty() ? 0 : Math.min(ui.dp(5), radius * .1f);
            float secondaryGap = secondary.isEmpty() ? 0 : Math.min(ui.dp(3), radius * .06f);
            // Fit the entire reading block in a padded square inscribed inside
            // the ring, so long/negative values and dual units cannot hit the bar.
            float safeSide = Math.max(1, (radius - arcStroke / 2f - pad) * 1.41421356f);
            float blockHeight = valueHeight + unitGap + unitHeight + secondaryGap + secondaryHeight;
            float fit = Math.min(1, Math.min(safeSide / Math.max(1, textWidth),
                    safeSide / Math.max(1, blockHeight)));
            valueSize *= fit;
            unitSize *= fit;
            secondarySize *= fit;
            unitGap *= fit;
            secondaryGap *= fit;
            valueHeight = textHeight(amount, valueSize, ui.bold());
            unitHeight = textHeight(unit, unitSize, ui.medium());
            secondaryHeight = textHeight(secondary, secondarySize, ui.regular());
            blockHeight = valueHeight + unitGap + unitHeight + secondaryGap + secondaryHeight;
            float textTop = cy - blockHeight / 2f;
            int ink = reading != null && reading.live ? ui.text : ui.muted;
            drawTextTop(canvas, amount, cx, textTop, valueSize, ui.bold(), ink, valueBounds);
            textTop += valueHeight + unitGap;
            if (!unit.isEmpty())
                drawTextTop(canvas, unit, cx, textTop, unitSize, ui.medium(), ui.muted, unitBounds);
            textTop += unitHeight + secondaryGap;
            if (!secondary.isEmpty())
                drawTextTop(canvas, secondary, cx, textTop, secondarySize, ui.regular(), ui.muted, secondaryBounds);

            paint.setTypeface(ui.regular());
            paint.setTextSize(labelSize);
            paint.getFontMetrics(fontMetrics);
            paint.setColor(ui.muted);
            float labelBaseline = cy + radius * FOOT + arcStroke / 2f + labelGap - fontMetrics.ascent;
            drawTextBaseline(canvas, minimum, cx - footX, labelBaseline, minimumLabelBounds);
            drawTextBaseline(canvas, maximum, cx + footX, labelBaseline, maximumLabelBounds);
        }

        private float textHeight(String text, float size, Typeface face) {
            paint.setTypeface(face);
            paint.setTextSize(size);
            if (text.isEmpty()) {
                glyphBounds.setEmpty();
                return 0;
            }
            paint.getTextBounds(text, 0, text.length(), glyphBounds);
            return glyphBounds.height();
        }

        private void drawTextTop(Canvas canvas, String text, float x, float top,
                float size, Typeface face, int color, RectF bounds) {
            paint.setTypeface(face);
            paint.setTextSize(size);
            paint.setColor(color);
            paint.getTextBounds(text, 0, text.length(), glyphBounds);
            drawTextBaseline(canvas, text, x, top - glyphBounds.top, bounds);
        }

        private void drawTextBaseline(Canvas canvas, String text, float x, float baseline, RectF bounds) {
            paint.getTextBounds(text, 0, text.length(), glyphBounds);
            float left = x - paint.measureText(text) / 2f;
            bounds.set(left + glyphBounds.left, baseline + glyphBounds.top,
                    left + glyphBounds.right, baseline + glyphBounds.bottom);
            canvas.drawText(text, x, baseline, paint);
        }
    }


    /** Editing gestures own their hit targets; content scrolling never starts a move. */
    private final class EditorTouch implements View.OnTouchListener {
        final Tile tile;
        final boolean resize;
        float downX, downY;
        boolean started;
        View handle;
        final Runnable hold = () -> start();

        EditorTouch(Tile tile, boolean resize) {
            this.tile = tile;
            this.resize = resize;
        }

        void start() {
            if (started || handle == null || !editing || !tile.active || !tile.hasWindowFocus())
                return;
            started = true;
            handle.removeCallbacks(hold);
            grid.beginGesture(tile, resize, downX, downY);
            Haptics.lift(handle);
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    handle = v;
                    started = false;
                    downX = event.getRawX();
                    downY = event.getRawY();
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    if (!resize) v.postDelayed(hold, ViewConfiguration.getLongPressTimeout());
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!started
                            && Math.hypot(event.getRawX() - downX, event.getRawY() - downY)
                                    > ViewConfiguration.get(host.activity()).getScaledTouchSlop())
                        start();
                    if (started) grid.updateGesture(event.getRawX(), event.getRawY());
                    return true;
                case MotionEvent.ACTION_POINTER_UP:
                    if (event.getActionIndex() != 0) return true;
                    // End cleanly if the primary finger leaves during a multi-touch gesture.
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.removeCallbacks(hold);
                    v.getParent().requestDisallowInterceptTouchEvent(false);
                    if (started)
                        grid.finishGesture(event.getActionMasked() != MotionEvent.ACTION_CANCEL);
                    else if (handle != null && event.getActionMasked() == MotionEvent.ACTION_UP)
                        v.performClick();
                    handle = null;
                    started = false;
                    return true;
                default:
                    return true;
            }
        }
    }

    private final class Grid extends ViewGroup {
        int columns = 2;
        private int measuredWidth;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Tile gestureTile;
        List<DashboardLayout.Cell> gestureSnapshot;
        private List<DashboardLayout.Cell> storedGestureSnapshot;
        private DashboardEditHistory.Snapshot gestureHistory;
        private List<DashboardLayout.Cell> displayed = Collections.emptyList();
        private final Map<String, RectF> previousFrames = new HashMap<>();
        private DashboardLayout.Cell gestureOrigin, requestedCell;
        private boolean resizing;
        private float rawX, rawY, startRawX, startRawY, anchorX, anchorY, startGridY;
        private int gestureColumns, gestureMinimumHeight;
        private RectF previewBounds;
        private android.animation.ValueAnimator previewAnimation;
        private boolean jigglePosted, suppressTransitionTouch;
        private long jiggleStarted;
        private final Rect jiggleVisible = new Rect();
        private ViewTreeObserver jiggleObserver;
        private final ViewTreeObserver.OnScrollChangedListener jiggleScroll = this::updateEditorMotion;
        private final Runnable jiggleFrame = new Runnable() {
            @Override public void run() {
                jigglePosted = false;
                if (!canJiggle()) { stopJiggle(); return; }
                long elapsed = android.os.SystemClock.uptimeMillis() - jiggleStarted;
                float ramp = Math.min(1, elapsed / 160f);
                boolean visible = false;
                for (Tile tile : tiles) {
                    boolean shown = tile != gestureTile && tile.isShown()
                            && tile.getGlobalVisibleRect(jiggleVisible);
                    float angle = shown ? .2f * ramp * (float) Math.sin(elapsed * Math.PI / 310d
                            + (tile.widget.id.hashCode() & 255) * Math.PI / 128d) : 0;
                    if (Math.abs(tile.jiggleAngle - angle) > .001f) {
                        tile.jiggleAngle = angle;
                        tile.invalidate();
                    }
                    visible |= shown;
                }
                if (visible) {
                    jigglePosted = true;
                    postDelayed(this, 32);
                }
            }
        };
        private final Runnable scrollFrame =
                new Runnable() {
                    @Override
                    public void run() {
                        if (gestureTile == null) return;
                        ScrollView scroll = outerScroll();
                        if (scroll != null) {
                            int[] p = new int[2];
                            scroll.getLocationOnScreen(p);
                            float y = rawY - p[1];
                            int speed =
                                    y < ui.dp(64)
                                            ? -ui.dp(9)
                                            : y > scroll.getHeight() - ui.dp(64) ? ui.dp(9) : 0;
                            if (speed != 0) {
                                scroll.scrollBy(0, speed);
                                updateGesture(rawX, rawY);
                            }
                        }
                        postOnAnimation(this);
                    }
                };

        Grid() {
            super(host.activity());
            setWillNotDraw(false);
            setClipChildren(false);
            setClipToPadding(false);
        }

        @Override
        public void onWindowFocusChanged(boolean focused) {
            super.onWindowFocusChanged(focused);
            if (!focused) {
                cancelWidgetReveal();
                finishGesture(false);
                stopEditTransition();
                stopJiggle();
                suppressTransitionTouch = false;
            } else { updateEditorMotion(); revealAddedWidget(); }
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            jiggleObserver = getViewTreeObserver();
            jiggleObserver.addOnScrollChangedListener(jiggleScroll);
            updateEditorMotion();
        }

        @Override protected void onWindowVisibilityChanged(int visibility) {
            super.onWindowVisibilityChanged(visibility);
            if (visibility != View.VISIBLE) {
                stopEditTransition(); stopJiggle(); suppressTransitionTouch = false;
            }
            else updateEditorMotion();
        }

        @Override protected void onVisibilityChanged(View changedView, int visibility) {
            super.onVisibilityChanged(changedView, visibility);
            if (visibility != View.VISIBLE) {
                stopEditTransition(); stopJiggle(); suppressTransitionTouch = false;
            }
            else updateEditorMotion();
        }

        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                cancelWidgetReveal(); revealWidgetId = null;
            }
            // Old decorative controls must never redirect a quick tap into live scooter controls.
            if (editTransition != null || suppressTransitionTouch) {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) suppressTransitionTouch = true;
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
                    suppressTransitionTouch = false;
                return true;
            }
            return super.dispatchTouchEvent(event);
        }

        private boolean canJiggle() {
            return editing && !closed && editTransition == null && Ui.motionEnabled()
                    && isAttachedToWindow() && hasWindowFocus()
                    && getWindowVisibility() == View.VISIBLE && isShown();
        }

        void updateEditorMotion() {
            if (!canJiggle()) { stopJiggle(); return; }
            if (jigglePosted) return;
            boolean visible = false;
            for (Tile tile : tiles)
                if (tile != gestureTile && tile.getGlobalVisibleRect(jiggleVisible)) { visible = true; break; }
            if (!visible) return;
            jiggleStarted = android.os.SystemClock.uptimeMillis();
            jigglePosted = true;
            postOnAnimation(jiggleFrame);
        }

        void stopJiggle() {
            removeCallbacks(jiggleFrame);
            jigglePosted = false;
            for (Tile tile : tiles) if (tile.jiggleAngle != 0) {
                tile.jiggleAngle = 0;
                tile.invalidate();
            }
        }

        float columnWidth() {
            return Math.max(1, (measuredWidth + ui.dp(10)) / (float) columns);
        }

        /** Explicit five-column layouts remain available on narrow cover screens. */
        int maximumColumns() {
            return DashboardLayout.maximumColumns(measuredWidth / getResources().getDisplayMetrics().density);
        }

        private int adaptiveColumns() {
            return Math.max(1, Math.min(4, measuredWidth / ui.dp(150)));
        }

        private float textScale() {
            return ui.textScale * getResources().getConfiguration().fontScale;
        }

        int minimumWidth(DashboardConfig.Widget widget) {
            return widget.minimumWidth(columns, measuredWidth / getResources().getDisplayMetrics().density,
                    10, textScale());
        }

        DashboardLayout.Cell currentCell(DashboardConfig.Widget widget) {
            for (DashboardLayout.Cell cell : displayed) if (cell.id.equals(widget.id)) return cell;
            return config.widgetLayouts(widget).get(columns);
        }

        void prepareEdit() {
            apply(snapshot(), columns);
        }

        void rememberFrames() {
            previousFrames.clear();
            for (Tile tile : tiles) if (tile.getWidth() > 0) {
                float x = tile.getLeft() + tile.getTranslationX(), y = tile.getTop() + tile.getTranslationY();
                previousFrames.put(tile.widget.id, new RectF(x, y,
                        x + tile.getWidth() * tile.getScaleX(), y + tile.getHeight() * tile.getScaleY()));
                tile.animate().cancel();
            }
        }

        float rowHeight() {
            // Stored rectangles use grid units. Give those units enough physical height
            // for both app text sizing and Android's accessibility font preference.
            float systemTextScale = host.activity().getResources().getConfiguration().fontScale;
            return ui.dp(64 * Math.max(1, ui.textScale * systemTextScale));
        }

        private boolean motionEnabled() {
            return android.animation.ValueAnimator.areAnimatorsEnabled();
        }

        void stopMotion() {
            if (previewAnimation != null) previewAnimation.cancel();
            stopJiggle();
            for (Tile tile : tiles) {
                tile.animate().cancel();
                tile.setTranslationX(0); tile.setTranslationY(0);
                tile.setScaleX(1); tile.setScaleY(1);
            }
            previousFrames.clear();
        }

        @Override protected void onDetachedFromWindow() {
            cancelWidgetReveal();
            finishGesture(false);
            stopEditTransition();
            stopMotion();
            suppressTransitionTouch = false;
            if (jiggleObserver != null && jiggleObserver.isAlive())
                jiggleObserver.removeOnScrollChangedListener(jiggleScroll);
            jiggleObserver = null;
            super.onDetachedFromWindow();
        }

        private List<DashboardLayout.Cell> snapshot() {
            ArrayList<DashboardLayout.Cell> result = new ArrayList<>();
            for (DashboardConfig.Widget widget : config.widgets)
                result.add(currentCell(widget).copy());
            return result;
        }

        private void apply(List<DashboardLayout.Cell> cells, int layoutColumns) {
            for (DashboardLayout.Cell cell : cells)
                for (DashboardConfig.Widget widget : config.widgets)
                    if (widget.id.equals(cell.id)) config.widgetLayouts(widget).put(layoutColumns, cell.copy());
        }

        void beginGesture(Tile tile, boolean resize, float x, float y) {
            finishGesture(false);
            stopEditTransition();
            stopRemovalEffect();
            config.layout(columns, null);
            gestureHistory = DashboardEditHistory.capture(config);
            storedGestureSnapshot = new ArrayList<>();
            for (DashboardConfig.Widget widget : config.widgets)
                storedGestureSnapshot.add(config.widgetLayouts(widget).get(columns).copy());
            gestureSnapshot = snapshot();
            gestureColumns = columns;
            gestureTile = tile;
            tile.jiggleAngle = 0;
            tile.invalidate();
            resizing = resize;
            gestureOrigin = currentCell(tile.widget).copy();
            gestureMinimumHeight =
                    Math.max(
                            gestureOrigin.y + gestureOrigin.h,
                            Math.round(getHeight() / rowHeight()));
            requestedCell = null;
            startRawX = rawX = x;
            startRawY = rawY = y;
            int[] p = new int[2];
            tile.getLocationOnScreen(p);
            anchorX = x - p[0];
            anchorY = y - p[1];
            getLocationOnScreen(p);
            startGridY = p[1];
            tile.animate().cancel();
            tile.setTranslationX(0);
            tile.setTranslationY(0);
            tile.setScaleX(1);
            tile.setScaleY(1);
            tile.setElevation(ui.dp(12));
            tile.setAlpha(.94f);
            tile.dragging = true;
            tile.setBackground(tile.chrome());
            // The card being placed shows its content; the others stay dimmed.
            tile.fadeContent(1f, 140);
            updateGesture(x, y);
            postOnAnimation(scrollFrame);
        }

        void updateGesture(float x, float y) {
            if (gestureTile == null) return;
            rawX = x;
            rawY = y;
            int[] p = new int[2];
            getLocationOnScreen(p);
            DashboardLayout.Cell target = gestureOrigin.copy();
            if (resizing) {
                target.w =
                        Math.max(
                                minimumWidth(gestureTile.widget),
                                Math.min(
                                        columns - target.x,
                                        gestureOrigin.w
                                                + Math.round((x - startRawX) / columnWidth())));
                target.h =
                        Math.max(
                                gestureTile.widget.minimumHeight((target.w * columnWidth() - ui.dp(10))
                                        / getResources().getDisplayMetrics().density),
                                Math.min(
                                        16,
                                        gestureOrigin.h
                                                + Math.round(
                                                        (y - startRawY + startGridY - p[1])
                                                                / rowHeight())));
            } else {
                target.x =
                        Math.max(
                                0,
                                Math.min(
                                        columns - target.w,
                                        Math.round((x - p[0] - anchorX) / columnWidth())));
                target.y =
                        Math.max(0, Math.min(300, Math.round((y - p[1] - anchorY) / rowHeight())));
            }
            if (requestedCell == null
                    || target.x != requestedCell.x
                    || target.y != requestedCell.y
                    || target.w != requestedCell.w
                    || target.h != requestedCell.h) {
                if (requestedCell != null) Haptics.tick(this);
                requestedCell = target;
                ArrayList<DashboardLayout.Cell> next = new ArrayList<>();
                for (DashboardLayout.Cell original : gestureSnapshot)
                    next.add(original.id.equals(target.id) ? target.copy() : original.copy());
                apply(DashboardLayout.preview(next, columns, target.id), columns);
                animatePreview(target);
                requestLayout();
            }
            followFinger();
            invalidate();
        }

        DashboardLayout.Cell previewCell() {
            return requestedCell == null ? null : requestedCell.copy();
        }

        private void followFinger() {
            if (gestureTile == null || resizing) return;
            int[] p = new int[2];
            getLocationOnScreen(p);
            gestureTile.setTranslationX(rawX - p[0] - anchorX - gestureTile.getLeft());
            gestureTile.setTranslationY(rawY - p[1] - anchorY - gestureTile.getTop());
        }

        private RectF bounds(DashboardLayout.Cell cell) {
            return new RectF(
                    cell.x * columnWidth(),
                    cell.y * rowHeight(),
                    (cell.x + cell.w) * columnWidth() - ui.dp(10),
                    (cell.y + cell.h) * rowHeight() - ui.dp(10));
        }

        private void animatePreview(DashboardLayout.Cell cell) {
            RectF end = bounds(cell);
            if (previewAnimation != null) previewAnimation.cancel();
            if (previewBounds == null || !motionEnabled()) {
                previewBounds = end;
                return;
            }
            RectF start = new RectF(previewBounds);
            previewAnimation = android.animation.ValueAnimator.ofFloat(0, 1);
            previewAnimation.setDuration(150);
            previewAnimation.setInterpolator(new android.view.animation.PathInterpolator(.2f, 0, 0, 1));
            previewAnimation.addUpdateListener(
                    a -> {
                        float f = (float) a.getAnimatedValue();
                        previewBounds =
                                new RectF(
                                        start.left + (end.left - start.left) * f,
                                        start.top + (end.top - start.top) * f,
                                        start.right + (end.right - start.right) * f,
                                        start.bottom + (end.bottom - start.bottom) * f);
                        invalidate();
                    });
            previewAnimation.start();
        }

        void finishGesture(boolean commit) {
            if (gestureTile == null) return;
            removeCallbacks(scrollFrame);
            if (!commit) apply(storedGestureSnapshot, gestureColumns);
            else {
                apply(DashboardLayout.withoutLeadingRows(config.layout(gestureColumns, gestureTile.widget.id)), gestureColumns);
                config.save();
                recordEdit(gestureHistory);
            }
            gestureHistory = null;
            Tile tile = gestureTile;
            gestureTile = null;
            gestureSnapshot = null;
            storedGestureSnapshot = null;
            gestureOrigin = requestedCell = null;
            if (previewAnimation != null) previewAnimation.cancel();
            previewBounds = null;
            tile.setAlpha(1);
            tile.setElevation(0);
            tile.dragging = false;
            tile.setBackground(tile.chrome());
            tile.fadeContent(editing ? EDIT_DIM : 1f, 200);
            if (motionEnabled())
                tile.animate()
                        .translationX(0)
                        .translationY(0)
                        .scaleX(1)
                        .scaleY(1)
                        .setDuration(180)
                        .setInterpolator(new android.view.animation.PathInterpolator(.2f, 0, 0, 1))
                        .start();
            else {
                tile.setTranslationX(0);
                tile.setTranslationY(0);
            }
            requestLayout();
            invalidate();
            updateEditorMotion();
        }

        void settle(String priority) {
            apply(config.layout(columns, priority,
                    measuredWidth / getResources().getDisplayMetrics().density, 10, textScale()), columns);
            requestLayout();
            invalidate();
        }

        private ScrollView outerScroll() {
            ViewParent parent = getParent();
            while (parent != null && !(parent instanceof ScrollView)) parent = parent.getParent();
            return parent instanceof ScrollView ? (ScrollView) parent : null;
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            measuredWidth = MeasureSpec.getSize(widthSpec);
            int maximum = maximumColumns();
            int requested = preferences.getInt("dashboard_columns", 0);
            int next = requested == 0 ? adaptiveColumns() : Math.max(1, Math.min(requested, maximum));
            // Parents can probe several widths in one measurement pass. Keep the
            // active layout stable until an actual laid-out width changes.
            columns = gestureTile == null ? next : gestureColumns;
            List<DashboardLayout.Cell> cells = gestureTile == null
                    ? config.layout(columns, null, measuredWidth / getResources().getDisplayMetrics().density,
                            10, textScale())
                    : config.layout(columns, gestureTile.widget.id);
            displayed = cells;
            int end = gestureTile == null ? 0 : gestureMinimumHeight;
            for (int i = 0; i < tiles.size(); i++) {
                DashboardLayout.Cell cell = cells.get(i);
                int width = Math.max(1, Math.round((cell.x + cell.w) * columnWidth())
                        - Math.round(cell.x * columnWidth()) - ui.dp(10));
                int height = Math.round(cell.h * rowHeight()) - ui.dp(10);
                tiles.get(i)
                        .measure(
                                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
                end = Math.max(end, cell.y + cell.h);
            }
            setMeasuredDimension(
                    measuredWidth,
                    Math.max(
                            ui.dp(100),
                            Math.round(end * rowHeight()) + (editing ? ui.dp(140) : 0)));
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            if (gestureTile != null && w != oldw) {
                finishGesture(false);
                requestLayout();
            }
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            for (Tile tile : tiles) {
                DashboardLayout.Cell cell = currentCell(tile.widget);
                int x = Math.round(cell.x * columnWidth()), y = Math.round(cell.y * rowHeight());
                int w = tile.getMeasuredWidth(), h = tile.getMeasuredHeight();
                boolean moved =
                        tile.getLeft() != x
                                || tile.getTop() != y
                                || tile.getWidth() != w
                                || tile.getHeight() != h;
                float oldX = tile.getLeft() + tile.getTranslationX(),
                        oldY = tile.getTop() + tile.getTranslationY();
                float oldW = tile.getWidth() * tile.getScaleX(),
                        oldH = tile.getHeight() * tile.getScaleY();
                RectF remembered = previousFrames.get(tile.widget.id);
                if (oldW == 0 && remembered != null) {
                    oldX = remembered.left; oldY = remembered.top;
                    oldW = remembered.width(); oldH = remembered.height();
                    moved = oldX != x || oldY != y || oldW != w || oldH != h;
                }
                tile.layout(x, y, x + w, y + h);
                if (tile == gestureTile) {
                    if (!resizing) { followFinger(); continue; }
                }
                if (moved && oldW > 0 && oldH > 0 && motionEnabled()) {
                    tile.animate().cancel();
                    tile.setPivotX(0);
                    tile.setPivotY(0);
                    tile.setTranslationX(oldX - x);
                    tile.setTranslationY(oldY - y);
                    tile.setScaleX(oldW / w);
                    tile.setScaleY(oldH / h);
                    tile.animate()
                            .translationX(0)
                            .translationY(0)
                            .scaleX(1)
                            .scaleY(1)
                            .setDuration(tile == gestureTile ? 140 : 210)
                            .setInterpolator(new android.view.animation.PathInterpolator(.2f, 0, 0, 1))
                            .start();
                } else if (!motionEnabled()) {
                    tile.animate().cancel();
                    tile.setTranslationX(0); tile.setTranslationY(0);
                    tile.setScaleX(1); tile.setScaleY(1);
                }
            }
            previousFrames.clear();
            updateEditorMotion();
        }

        @Override
        protected void onDraw(Canvas c) {
            if (!editing) return;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Ui.withAlpha(ui.muted, 45));
            for (int col = 0; col <= columns; col++)
                for (float y = 0; y < getHeight(); y += rowHeight())
                    c.drawCircle(col == columns ? getWidth() : col * columnWidth(), y, ui.dp(1.5f), paint);
            if (previewBounds != null) {
                paint.setColor(Ui.withAlpha(ui.accent, ui.dark ? 18 : 14));
                c.drawRoundRect(previewBounds, ui.dp(20), ui.dp(20), paint);
                paint.setColor(Ui.withAlpha(ui.accent, 180));
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(ui.dp(2));
                paint.setPathEffect(new DashPathEffect(new float[] {ui.dp(7), ui.dp(5)}, 0));
                c.drawRoundRect(previewBounds, ui.dp(20), ui.dp(20), paint);
                paint.setPathEffect(null);
                paint.setStyle(Paint.Style.FILL);
            }
        }
    }

    /** Rows in a sheet sit on a raised card so they read as one group. */
    private LinearLayout sheetCard() {
        LinearLayout card = column();
        card.setPadding(ui.dp(12), ui.dp(2), ui.dp(12), ui.dp(2));
        card.setBackground(ui.shape(ui.dark ? ui.raised : ui.bg, 16, 0));
        return card;
    }

    /** A Volt chip with a full-height touch target for the option sheets. */
    private TextView sheetChip(String label, boolean selected) {
        TextView chip = ui.chip(label, selected, null);
        chip.setMinHeight(ui.dp(44));
        chip.setMinWidth(ui.dp(48));
        return chip;
    }

    private void sheetDivider(LinearLayout parent) {
        View line = new View(host.activity());
        line.setBackgroundColor(ui.outline);
        parent.addView(line, new LinearLayout.LayoutParams(-1, Math.max(1, ui.dp(1))));
    }

    private TextView sheetLabel(String text) {
        TextView label = ui.text(text, 13, false);
        label.setTextColor(ui.muted);
        label.setPadding(ui.dp(2), ui.dp(14), ui.dp(2), ui.dp(8));
        return label;
    }

    /** Chips and swatches wrap onto as many lines as they need. */
    private final class Flow extends ViewGroup {
        Flow() {
            super(host.activity());
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec), gap = ui.dp(8);
            int x = 0, y = 0, line = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                child.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST),
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                if (x > 0 && x + child.getMeasuredWidth() > width) {
                    y += line + gap;
                    x = 0;
                    line = 0;
                }
                x += child.getMeasuredWidth() + gap;
                line = Math.max(line, child.getMeasuredHeight());
            }
            setMeasuredDimension(resolveSize(width, widthSpec), resolveSize(y + line, heightSpec));
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int width = getWidth(), gap = ui.dp(8), x = 0, y = 0, line = 0;
            boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                int w = child.getMeasuredWidth(), h = child.getMeasuredHeight();
                if (x > 0 && x + w > width) {
                    y += line + gap;
                    x = 0;
                    line = 0;
                }
                int left = rtl ? width - x - w : x;
                child.layout(left, y, left + w, y + h);
                x += w + gap;
                line = Math.max(line, h);
            }
        }
    }

    private void palette() {
        if (config.widgets.size() >= WIDGET_LIMIT) {
            add.performLongClick(add.getWidth() / 2f, add.getHeight() / 2f);
            return;
        }
        boolean hasLock = false;
        for (DashboardConfig.Widget item : config.widgets) hasLock |= lockType(item.type);
        LinearLayout list = sheetCard();
        for (int i = 0; i < DashboardConfig.TYPES.length; i++) {
            final String type = DashboardConfig.TYPES[i];
            boolean present = lockType(type) && hasLock;
            if (i > 0) sheetDivider(list);
            LinearLayout row = row();
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(ui.dp(64));
            row.setPadding(0, ui.dp(10), 0, ui.dp(10));
            row.setBackground(ripple(Ui.withAlpha(ui.accent, 26), null, ui.shape(Color.WHITE, 10, 0)));
            row.setClickable(true);
            row.setFocusable(true);
            row.setTag("add-widget:" + type);
            row.setContentDescription(type.equals("capacity") ? T.dashboardAddTypeExperimental(DashboardConfig.NAMES[i])
                    : T.dashboardAddType(DashboardConfig.NAMES[i]));
            row.addView(square(widgetIcon(type), present ? ui.dim : ui.accent, 38),
                    new LinearLayout.LayoutParams(ui.dp(38), ui.dp(38)));
            if (type.equals("capacity")) row.setContentDescription(T.dashboardAddTypeExperimental(DashboardConfig.typeName(i)));
            else row.setContentDescription(T.dashboardAddType(DashboardConfig.typeName(i)));
            // Names only: the icon and name identify each widget without a description line.
            LinearLayout words = row();
            words.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = ui.text(DashboardConfig.typeName(i), 15, false);
            name.setTypeface(ui.medium());
            if (present) name.setTextColor(ui.muted);
            words.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            if (type.equals("capacity")) {
                TextView experimental = tagChip();
                styleTag(experimental, T.dashboardExperimental(), false);
                LinearLayout.LayoutParams chipSpace = new LinearLayout.LayoutParams(-2, -2);
                chipSpace.setMarginStart(ui.dp(8));
                words.addView(experimental, chipSpace);
            }
            LinearLayout.LayoutParams wordsSpace = new LinearLayout.LayoutParams(0, -2, 1);
            wordsSpace.setMarginStart(ui.dp(12));
            wordsSpace.setMarginEnd(ui.dp(10));
            row.addView(words, wordsSpace);
            if (present) {
                TextView on = ui.text(T.dashboardOnHome(), 13.5f, false);
                on.setTextColor(ui.muted);
                row.addView(on);
            } else {
                FrameLayout plus = new FrameLayout(host.activity());
                GradientDrawable disc = new GradientDrawable();
                disc.setShape(GradientDrawable.OVAL);
                disc.setColor(ui.accent);
                plus.setBackground(disc);
                plus.addView(new GlyphView("plus", ui.onAccent),
                        new FrameLayout.LayoutParams(ui.dp(16), ui.dp(16), Gravity.CENTER));
                plus.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                row.addView(plus, new LinearLayout.LayoutParams(ui.dp(34), ui.dp(34)));
            }
            if (android.os.Build.VERSION.SDK_INT >= 30)
                if (present) row.setStateDescription(T.dashboardAlreadyOnHome());
            // One lock control per dashboard: its row is shown "On Home" and cannot be added again.
            row.setEnabled(!present);
            row.setOnClickListener(
                    v -> {
                        if (lockType(type))
                            for (DashboardConfig.Widget item : config.widgets)
                                if (lockType(item.type)) return;
                        DashboardConfig.Widget widget = new DashboardConfig.Widget(type);
                        int end = 0;
                        for (DashboardConfig.Widget item : config.widgets) {
                            DashboardLayout.Cell cell = config.widgetLayouts(item).get(grid.columns);
                            if (cell != null) end = Math.max(end, cell.y + cell.h);
                        }
                        config.widgetLayouts(widget).put(
                                grid.columns,
                                new DashboardLayout.Cell(
                                        widget.id,
                                        0,
                                        end,
                                        widget.defaultWidth(grid.columns),
                                        widget.defaultHeight()));
                        // Keep a new card out of saved layouts and Undo until its own Save.
                        pendingAddedWidgetId = widget.id;
                        dismiss();
                        options(widget);
                    });
            list.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
        showSheet(T.dashboardAddWidget(), list, null, T.dashboardCloseAddWidget(), null);
    }

    private void options(DashboardConfig.Widget widget) {
        if (!editing) return;
        LinearLayout list = column();
        // Title is edited in place and saved when the sheet closes.
        list.addView(sheetLabel(T.dashboardTitle()));
        EditText titleInput = new EditText(dialogContext);
        String shownTitle = DashboardConfig.displayTitle(widget);
        titleInput.setText(shownTitle);
        titleInput.setSingleLine(true);
        titleInput.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        titleInput.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(80)});
        titleInput.setTextColor(ui.text);
        titleInput.setHintTextColor(ui.muted);
        titleInput.setTypeface(ui.regular());
        titleInput.setTextSize(15 * ui.textScale);
        titleInput.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12));
        titleInput.setMinHeight(ui.dp(52));
        titleInput.setBackground(ui.shape(ui.dark ? ui.bg : ui.surface, 14, ui.outline));
        titleInput.setContentDescription(T.dashboardWidgetTitle());
        list.addView(titleInput, new LinearLayout.LayoutParams(-1, -2));
        Runnable applyTitle =
                () -> {
                    String value = titleInput.getText().toString().trim();
                    // A title left as shown (a default in the current language) keeps its saved English.
                    if (!value.isEmpty() && !value.equals(widget.title) && !value.equals(shownTitle)) {
                        widget.title = value;
                        config.save();
                        render();
                    }
                };
        LinearLayout settings = sheetCard();
        if (widget.type.equals("quick") || widget.type.equals("shortcuts"))
            addSettingRow(settings, ui.settingRow(T.dashboardQuickSettings(), T.dashboardSelectedCount(widget.quickActions.size(), widget.quickActions.size()),
                    () -> quickOptions(widget)), "grid");
        if (lockType(widget.type)) {
            Switch disconnect = new Haptics.FeedbackSwitch(dialogContext);
            ui.styleSwitch(disconnect);
            disconnect.setText(T.dashboardShowDisconnect());
            disconnect.setTextColor(ui.text);
            disconnect.setTextSize(14 * ui.textScale);
            disconnect.setMinHeight(ui.dp(56));
            disconnect.setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8));
            disconnect.setChecked(widget.showDisconnect);
            disconnect.setContentDescription(T.dashboardShowDisconnectConnected());
            disconnect.setOnCheckedChangeListener((button, checked) -> {
                widget.showDisconnect = checked;
                config.save();
                render();
            });
            settings.addView(disconnect);
        }
        if (widget.type.equals("scooter"))
            addSettingRow(settings,
                    ui.settingRow(
                            T.dashboardScooterName(),
                            service == null || service.profiles.active() == null
                                    ? ""
                                    : service.profiles.active().name,
                            () -> {
                                dismiss();
                                actions.rename();
                            }), "scooter");
        if (Arrays.asList("metric", "gauge", "live", "history").contains(widget.type))
            addSettingRow(settings,
                    ui.settingRow(T.dashboardParameter(), label(widget.metric), () -> chooseMetric(widget)),
                    readingIcon(widget.metric));
        if (widget.type.equals("readings")
                || widget.type.equals("battery")
                || widget.type.equals("scooter")
                || widget.type.equals("overview")
                || widget.type.equals("tiles"))
            addSettingRow(settings,
                    ui.settingRow(
                            T.dashboardReadings(),
                            T.dashboardSelectedCount(widget.keys.size(), widget.keys.size()),
                            () -> chooseReadings(widget)), "stats");
        if (widget.type.equals("note"))
            addSettingRow(settings,
                    ui.settingRow(
                            T.dashboardNote(),
                            DashboardConfig.displayNote(widget),
                            () ->
                                    editText(
                                            widget,
                                            M.dashboardNote(),
                                            widget.note,
                                            value -> widget.note = value)), "text");
        if (widget.type.equals("capacity"))
            addSettingRow(settings,
                    ui.settingRow(
                            T.dashboardAdvertisedCapacity(),
                            ratingLabel(widget),
                            () -> advertisedCapacity(widget)), "battery");
        if (widget.type.equals("gauge")) {
            addSettingRow(settings,
                    ui.settingRow(
                            T.dashboardScaleMinimum(),
                            scaleSummary(widget, widget.minimum),
                            () -> scale(widget, false)), "speed");
            addSettingRow(settings,
                    ui.settingRow(
                            T.dashboardScaleMaximum(),
                            scaleSummary(widget, widget.maximum),
                            () -> scale(widget, true)), "speed");
        }
        if (settings.getChildCount() > 0) {
            LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-1, -2);
            space.topMargin = ui.dp(12);
            list.addView(settings, space);
        }
        if (Arrays.asList("live", "history", "health", "capacity").contains(widget.type)) {
            list.addView(sheetLabel(widget.type.equals("capacity") ? T.dashboardRideDataWindow() : T.dashboardTimeWindow()));
            long[] options = windowOptions(widget);
            String[] windowLabels = new String[options.length]; int selectedWindow = 0;
            for (int i = 0; i < options.length; i++) {
                windowLabels[i] = shortWindow(options[i]);
                if (options[i] == widget.windowMs) selectedWindow = i;
            }
            SegmentedSelector windows = new SegmentedSelector(ui, T.dashboardTimeWindow(), windowLabels, choice -> {
                widget.windowMs = options[choice]; config.save(); render();
            });
            windows.select(selectedWindow, false);
            list.addView(windows, new LinearLayout.LayoutParams(-1, -2));
        }
        if (Arrays.asList("live", "history", "health").contains(widget.type)) {
            list.addView(sheetLabel(T.dashboardYAxis()));
            SegmentedSelector axis = new SegmentedSelector(ui, T.dashboardYAxis(),
                    new String[]{T.dashboardRelative(), T.dashboardAbsolute()}, choice -> {
                        widget.absoluteAxis = choice == 1; config.save(); render();
                    });
            axis.select(widget.absoluteAxis ? 1 : 0, false);
            list.addView(axis, new LinearLayout.LayoutParams(-1, -2));
        }
        list.addView(sheetLabel(T.dashboardColor()));
        Flow swatches = new Flow();
        String[] names = {T.dashboardColorRed(), T.dashboardColorBlue(), T.dashboardColorAmber(), T.dashboardColorRose()};
        int[] colors = {ui.accent, ui.blue, ui.orange, ui.danger};
        ArrayList<Swatch> all = new ArrayList<>();
        for (int i = 0; i < colors.length; i++) {
            final int index = i;
            Swatch swatch = new Swatch(colors[i], names[i]);
            swatch.setOnClickListener(
                    v -> {
                        widget.color = index;
                        config.save();
                        for (Swatch other : all) other.select(other == swatch);
                        render();
                    });
            swatch.select(widget.color == i);
            all.add(swatch);
            swatches.addView(swatch, new ViewGroup.LayoutParams(ui.dp(48), ui.dp(48)));
        }
        list.addView(swatches, new LinearLayout.LayoutParams(-1, -2));
        TextView save = ui.button(T.dashboardSaveChecked(), () -> {
            applyTitle.run();
            if (!config.widgets.contains(widget)) {
                DashboardEditHistory.Snapshot before = DashboardEditHistory.capture(config);
                config.widgets.add(widget);
                grid.settle(widget.id);
                recordEdit(before);
            }
            config.save();
            dismiss();
            render();
            revealIfAdded(widget);
        });
        save.setContentDescription(T.dashboardSaveWidgetOptions());
        LinearLayout buttons = row();
        buttons.setGravity(Gravity.CENTER);
        buttons.setPadding(0, ui.dp(12), 0, 0);
        buttons.addView(save, new LinearLayout.LayoutParams(-1, -2));
        String index = DashboardConfig.typeName(Math.max(0, Arrays.asList(DashboardConfig.TYPES).indexOf(widget.type)));
        showSheet(index, list, buttons, T.dashboardCloseWidgetOptions(), applyTitle);
    }

    /** Ordered, named actions. Configuration never executes a scooter command. */
    private void quickOptions(DashboardConfig.Widget widget) {
        final int least = DashboardConfig.minimumShortcuts(widget.type);
        final int most = DashboardConfig.maximumShortcuts(widget.type);
        LinearLayout list = column();
        TextView count = ui.caption("");
        count.setPadding(0, ui.dp(8), 0, ui.dp(10));
        list.addView(count);
        LinearLayout rows = column();
        rows.setTag("quick-options-rows");
        list.addView(rows);
        Runnable[] fill = new Runnable[1];
        fill[0] = () -> {
            Map<String, Float> old = childPositions(rows);
            rows.removeAllViews();
            count.setText(T.dashboardCountOf(Fmt.integer(widget.quickActions.size()), Fmt.integer(most)));
            count.setContentDescription(T.dashboardCountOfShortcuts(most, widget.quickActions.size(), most));
            for (String id : new ArrayList<>(widget.quickActions)) {
                QuickAccessActions.Entry entry = QuickAccessActions.find(id);
                LinearLayout line = row(); line.setGravity(Gravity.CENTER_VERTICAL);
                line.setTag("quick-option:" + id);
                line.setPadding(ui.dp(8), ui.dp(6), 0, ui.dp(6));
                line.setBackground(ui.shape(ui.raised, 14, 0));
                LinearLayout select = row(); select.setGravity(Gravity.CENTER_VERTICAL);
                select.setMinimumHeight(ui.dp(48)); select.setClickable(true); select.setFocusable(true);
                int index = widget.quickActions.indexOf(id);
                select.setContentDescription(M.dashboardChangeShortcut(Fmt.integer(index + 1), entry.label()));
                select.setTag("quick-select:" + index);
                select.addView(new GlyphView(entry.icon, ui.accent), new LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)));
                TextView name = ui.text(entry.label(), 14, false);
                name.setPadding(ui.dp(10), 0, ui.dp(4), 0);
                select.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
                select.setOnClickListener(v -> chooseQuickAction(widget, widget.quickActions.indexOf(id)));
                line.addView(select, new LinearLayout.LayoutParams(0, -2, 1));
                FrameLayout up = roundAction("back", T.dashboardMoveEarlier(entry.label()), 30, 15, () -> {
                    int at = widget.quickActions.indexOf(id);
                    if (at <= 0) return;
                    Collections.swap(widget.quickActions, at, at - 1);
                    config.save(); render(); fill[0].run();
                });
                up.getChildAt(0).setRotation(90);
                up.setEnabled(index > 0); up.setAlpha(index > 0 ? 1 : .3f);
                line.addView(up, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
                FrameLayout remove = roundAction("trash", T.dashboardRemoveShortcut(entry.label()), 30, 15, () -> {
                    if (widget.quickActions.size() <= least) return;
                    stopRemovalEffect();
                    removalEffect = WidgetRemovalEffect.play(list, line, ui);
                    widget.quickActions.remove(id);
                    config.save(); render(); fill[0].run();
                });
                remove.setEnabled(widget.quickActions.size() > least);
                remove.setAlpha(remove.isEnabled() ? 1 : .3f);
                line.addView(remove, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
                LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-1, -2); space.bottomMargin = ui.dp(8);
                rows.addView(line, space);
            }
            if (widget.quickActions.size() < most) {
                View addShortcut = toolButton(T.dashboardAddShortcut(), "plus", () -> chooseQuickAction(widget, -1));
                addShortcut.setTag("add-quick-shortcut");
                addShortcut.setContentDescription(T.dashboardAddQuickShortcut());
                rows.addView(addShortcut, new LinearLayout.LayoutParams(-1, ui.dp(52)));
            }
            animateRows(rows, old);
        };
        fill[0].run();
        TextView save = ui.button(T.dashboardSaveChecked(), () -> options(widget));
        showSheet(T.dashboardQuickSettings(), list, save, T.dashboardCloseQuickSettings(), null);
    }

    private Map<String, Float> childPositions(ViewGroup parent) {
        Map<String, Float> positions = new HashMap<>();
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child.getTag() instanceof String && child.isLaidOut())
                positions.put((String)child.getTag(), child.getY());
        }
        return positions;
    }

    /** FLIP the changed list in its existing sheet; no dialog recreation or scroll reset. */
    private void animateRows(ViewGroup parent, Map<String, Float> old) {
        if (!Ui.motionEnabled() || old.isEmpty()) return;
        parent.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                if (parent.getViewTreeObserver().isAlive()) parent.getViewTreeObserver().removeOnPreDrawListener(this);
                if (!parent.isAttachedToWindow() || !Ui.motionEnabled()) return true;
                for (int i = 0; i < parent.getChildCount(); i++) {
                    View child = parent.getChildAt(i);
                    Float previous = old.get(child.getTag());
                    child.animate().cancel();
                    child.setTranslationY(previous == null ? -ui.dp(12) : previous - child.getTop());
                    child.setAlpha(previous == null ? 0 : 1);
                    child.animate().translationY(0).alpha(1).setDuration(210)
                            .setInterpolator(new android.view.animation.PathInterpolator(.2f, 0, 0, 1)).start();
                }
                return true;
            }
        });
    }

    private void saveQuickActions(DashboardConfig.Widget widget) {
        config.save(); render(); quickOptions(widget);
    }

    /**
     * Choose shortcut: every shortcut in a collapsible section named for its category (ShortcutPicker), and the search
     * box above them. A search lists its matches flat, each captioned with its category; clearing it returns to the
     * sections as they were. Rows keep their tags (quick-choice:<id>) and exist while their section is closed.
     */
    private void chooseQuickAction(DashboardConfig.Widget widget, int index) { chooseQuickAction(widget, index, null); }

    /** Opens the picker; {@code memo}, when Home was rebuilt around it, restores its search, open sections and scroll. */
    private void chooseQuickAction(DashboardConfig.Widget widget, int index, PickerMemo memo) {
        chooseQuickAction(widget.quickActions, index, Collections.<QuickAccessActions.Entry>emptyList(), id -> {
            if (index < 0) {
                if (widget.quickActions.size() >= DashboardConfig.maximumShortcuts(widget.type)) return;
                widget.quickActions.add(id);
            } else widget.quickActions.set(index, id);
            saveQuickActions(widget);
        }, widget.id, memo);
    }

    /**
     * The shortcut picker. chosen holds the ids already picked, index the slot being changed (-1 adds one), own the
     * entries offered beside the catalog (Clean Home's own actions, first in their sections; none for a widget) and
     * picked gets the id tapped. widgetId names the widget whose picker a rebuilt Home reopens (null: not reopened).
     */
    private void chooseQuickAction(List<String> chosen, int index, List<QuickAccessActions.Entry> own,
                                   java.util.function.Consumer<String> picked, String widgetId, PickerMemo memo) {
        String current = index >= 0 && index < chosen.size() ? chosen.get(index) : null;
        ArrayList<QuickAccessActions.Entry> entries = new ArrayList<>(own);
        for (QuickAccessActions.Entry entry : QuickAccessActions.catalog()) {
            boolean listed = false;
            for (QuickAccessActions.Entry mine : own) listed |= mine.id.equals(entry.id);
            if (!listed) entries.add(entry);
        }
        LinearLayout header = column();
        EditText search = new EditText(dialogContext);
        search.setSingleLine(true); search.setHint(T.dashboardSearchSettings());
        search.setContentDescription(T.dashboardSearchQuickSettings()); ui.styleSearch(search);
        header.addView(search, ui.searchLayout());
        LinearLayout choices = column(); choices.setTag("quick-choices");
        choices.setPadding(0, 0, 0, ui.dp(8));
        // The scooter's model decides which controls it offers, judged once as the picker opens (as Home's tiles judge them).
        BleController ble = service == null ? null : service.ble;
        ShortcutPicker.Scooter scooter = new ShortcutPicker.Scooter() {
            @Override public boolean connected() { return ble != null && ble.connected(); }
            @Override public CapabilityResolver.Decision decision(String key) { return ble == null ? null : ble.decision(key); }
            @Override public boolean controlOffered(String key) { return ble != null && ble.controlOffered(key); }
            @Override public CapabilityResolver.Reason chargeAlertsMissing() {
                return service == null ? CapabilityResolver.Reason.NOT_CONNECTED : service.chargeAlertsMissing();
            }
            @Override public CapabilityResolver.Reason chargeScheduleMissing() { return service == null ? null : service.chargeScheduleMissing(); }
        };
        // Why each shortcut the scooter can't use can't be used, in the resolver's words (its section's name, its search caption).
        Map<String, CapabilityResolver.Reason> unavailable = new HashMap<>();
        List<ShortcutPicker.Section> sections = ShortcutPicker.sections(entries, entry -> {
            CapabilityResolver.Reason reason = ShortcutPicker.missing(entry, scooter);
            if (reason != null) unavailable.put(entry.id, ShortcutPicker.canonical(reason));
            return reason;
        });
        Set<String> open = new HashSet<>();
        String first = ShortcutPicker.firstOpen(sections, current, chosen);
        if (memo != null) open.addAll(memo.open);
        else if (first != null) open.add(first);
        if (memo != null) search.setText(memo.query);
        List<PickerSection> shown = new ArrayList<>();
        Runnable filter = () -> {
            for (PickerSection section : shown) section.stop();
            shown.clear();
            choices.removeAllViews();
            String query = SearchFold.fold(search.getText().toString()).trim();
            if (query.isEmpty()) {
                for (ShortcutPicker.Section section : sections) {
                    PickerSection view = new PickerSection(section, open, choices);
                    // A row the scooter can't use keeps its category as a caption; its section's name says why.
                    for (QuickAccessActions.Entry entry : section.entries)
                        view.body.addView(quickChoice(chosen, current, entry,
                                ShortcutPicker.unavailableSection(section.id) ? entry.groupLabel() : null, null, picked),
                                new LinearLayout.LayoutParams(-1, -2));
                    view.place(open.contains(section.id), false);
                    shown.add(view);
                    choices.addView(view.block, new LinearLayout.LayoutParams(-1, -2));
                }
            } else {
                // The English name and group still match, beside the shown ones.
                for (QuickAccessActions.Entry entry : ShortcutPicker.matches(sections, entry ->
                        SearchFold.holds(entry.label, entry.group, query)
                                || SearchFold.holdsShown(entry.label().render(), entry.groupLabel().render(), query))) {
                    // Its category always (Experimental controls included), and why the scooter can't use it below that.
                    CapabilityResolver.Reason reason = unavailable.get(entry.id);
                    choices.addView(quickChoice(chosen, current, entry, entry.groupLabel(),
                            reason == null ? null : CapabilityResolver.message(reason), picked), new LinearLayout.LayoutParams(-1, -2));
                }
                if (choices.getChildCount() == 0) {
                    TextView none = ui.caption(T.dashboardNoMatchingSettings());
                    none.setPadding(ui.dp(4), ui.dp(12), ui.dp(4), ui.dp(12));
                    choices.addView(none);
                }
            }
        };
        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                filter.run();
                if (choices.getParent() instanceof ScrollView) ((ScrollView) choices.getParent()).scrollTo(0, 0);
            }
            public void afterTextChanged(android.text.Editable text) {}
        });
        filter.run();
        showSheet(T.dashboardChooseShortcut(), header, choices, null, T.dashboardCloseShortcutPicker(), null);
        if (dialog != null) SearchFocus.bind(search, dialog.getWindow());
        // Only a widget's picker comes back after a rebuild; Clean's edit sheet is closed by one.
        pickerDialog = widgetId == null ? null : dialog;
        pickerState = widgetId == null ? null : () -> new PickerMemo(profileId, widgetId, index, current, search.getText().toString(), open,
                choices.getParent() instanceof View ? ((View) choices.getParent()).getScrollY() : 0);
        if (memo != null && memo.scrollY > 0)
            Motion.beforeNextDraw(choices, () -> { if (choices.getParent() instanceof ScrollView) ((ScrollView) choices.getParent()).scrollTo(0, memo.scrollY); }, null);
    }

    /** One open picker's state (see pickerMemo). */
    private static final class PickerMemo {
        final String profileId, widgetId, current, query;
        final int index, scrollY;
        final Set<String> open;
        PickerMemo(String profileId, String widgetId, int index, String current, String query, Set<String> open, int scrollY) {
            this.profileId = profileId; this.widgetId = widgetId; this.index = index; this.current = current;
            this.query = query; this.open = new HashSet<>(open); this.scrollY = scrollY;
        }
    }

    /** close(): keeps the picker's state when it is the open sheet, for a Home made in this same step only. */
    private void rememberPicker() {
        PickerMemo memo = dialog != null && dialog == pickerDialog && dialog.isShowing() && pickerState != null ? pickerState.get() : null;
        pickerDialog = null; pickerState = null;
        pickerMemo = memo;
        if (memo != null) new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> { if (pickerMemo == memo) pickerMemo = null; });
    }

    /** Reopens a remembered picker when its widget, and the shortcut it was replacing, are still there. */
    private void reopenPicker(PickerMemo memo) {
        for (DashboardConfig.Widget widget : config.widgets) {
            if (!widget.id.equals(memo.widgetId)) continue;
            boolean same = memo.index < 0 ? widget.quickActions.size() < DashboardConfig.maximumShortcuts(widget.type)
                    : memo.index < widget.quickActions.size() && widget.quickActions.get(memo.index).equals(memo.current);
            if (same) chooseQuickAction(widget, memo.index, memo);
            return;
        }
    }

    /**
     * One choice: its icon, name, an optional caption (null inside its own section), why the scooter can't use it (a
     * search match only; null otherwise) and the chosen or toggle marker.
     */
    private View quickChoice(List<String> chosen, String current, QuickAccessActions.Entry entry,
            CharSequence caption, CharSequence reason, java.util.function.Consumer<String> picked) {
        boolean selected = chosen.contains(entry.id);
        boolean replacing = entry.id.equals(current);
        CharSequence group = caption == null ? entry.groupLabel() : caption;
        LinearLayout item = row(); item.setGravity(Gravity.CENTER_VERTICAL);
        item.setMinimumHeight(ui.dp(caption == null ? 52 : 60));
        item.setPadding(ui.dp(4), ui.dp(8), ui.dp(4), ui.dp(8));
        item.addView(new GlyphView(entry.icon, ui.accent), new LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)));
        LinearLayout words = column();
        words.addView(ui.text(entry.label(), 14, false));
        if (caption != null) words.addView(ui.caption(caption.toString()));
        if (reason != null) { TextView why = ui.caption(reason.toString()); why.setTag("quick-choice-reason"); words.addView(why); }
        LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(0, -2, 1); space.setMarginStart(ui.dp(12));
        item.addView(words, space);
        // A check marks a chosen shortcut; a switch marks one that toggles directly.
        if (selected || entry.directToggle()) {
            GlyphView kind = new GlyphView(selected ? "check" : "toggle", selected ? ui.accent : ui.muted);
            LinearLayout.LayoutParams kindSize = new LinearLayout.LayoutParams(ui.dp(20), ui.dp(20));
            kindSize.setMarginStart(ui.dp(8));
            item.addView(kind, kindSize);
        }
        item.setTag("quick-choice:" + entry.id);
        item.setClickable(true); item.setFocusable(true); item.setEnabled(!selected || replacing);
        // A search result the scooter can't use is spoken with its category and why, on every Android version.
        if (reason != null) group = T.dashboardPickerCategoryReason(group, reason);
        item.setContentDescription(selected ? T.dashboardShortcutSelected(entry.label(), group)
                : entry.directToggle() ? T.dashboardShortcutToggle(entry.label(), group) : T.dashboardShortcut(entry.label(), group));
        item.setAlpha(item.isEnabled() ? 1 : .4f);
        item.setBackground(ripple(Ui.withAlpha(ui.accent, 30), null, ui.shape(Color.WHITE, 12, 0)));
        item.setOnClickListener(v -> {
            if (selected && !replacing) return;
            picked.accept(entry.id);
        });
        return item;
    }

    /**
     * A collapsible section of the shortcut picker: a header (name, count, a chevron that turns) over its rows. It
     * opens and closes by animating its rows' height (Motion.CHANGE_MS, instant with animations off); opening one low
     * on the sheet scrolls just enough to show its first rows, never past its header. The sheet keeps its height.
     */
    private final class PickerSection {
        final ShortcutPicker.Section section;
        final Set<String> open;
        final LinearLayout block, head, body;
        final View chevron;
        android.animation.ValueAnimator animator;
        boolean expanded;

        PickerSection(ShortcutPicker.Section section, Set<String> open, ViewGroup list) {
            this.section = section; this.open = open;
            block = column(); block.setTag("quick-section:" + section.id);
            if (list.getChildCount() > 0) {
                View rule = new View(host.activity());
                rule.setBackgroundColor(ui.outline);
                block.addView(rule, new LinearLayout.LayoutParams(-1, Math.max(1, ui.dp(.75f))));
            }
            head = row(); head.setGravity(Gravity.CENTER_VERTICAL); head.setTag("quick-section-header:" + section.id);
            head.setMinimumHeight(ui.dp(56)); head.setPadding(ui.dp(4), ui.dp(10), ui.dp(2), ui.dp(10));
            head.setClickable(true); head.setFocusable(true);
            head.setBackground(ripple(Ui.withAlpha(ui.accent, 30), null, ui.shape(Color.WHITE, 12, 0)));
            TextView name = ui.text(sectionName(section), 15, true);
            name.setTag("quick-section-name:" + section.id);
            name.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_SIMPLE);
            name.setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE);
            head.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            TextView count = ui.text(Fmt.integer(section.entries.size()), 12, false);
            count.setTag("quick-section-count:" + section.id);
            count.setTypeface(ui.medium()); count.setTextColor(ui.muted); count.setGravity(Gravity.CENTER);
            count.setMinWidth(ui.dp(26)); count.setPadding(ui.dp(8), ui.dp(3), ui.dp(8), ui.dp(3));
            count.setBackground(ui.shape(ui.dark ? ui.bg : ui.raised, 10, 0));
            LinearLayout.LayoutParams countSpace = new LinearLayout.LayoutParams(-2, -2); countSpace.setMarginStart(ui.dp(10));
            head.addView(count, countSpace);
            chevron = ui.icon("chevron", 16, ui.muted);
            chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams chevronSize = new LinearLayout.LayoutParams(ui.dp(40), ui.dp(24));
            head.addView(chevron, chevronSize);
            if (android.os.Build.VERSION.SDK_INT >= 28) head.setAccessibilityHeading(true);
            // Spoken as one heading, "Ride, 8 shortcuts", with its open or closed state: place() sets both (pickerHeaderSaid).
            head.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View v, android.view.accessibility.AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(v, info);
                    info.addAction(expanded ? android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_COLLAPSE
                            : android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND);
                    if (android.os.Build.VERSION.SDK_INT >= 36)
                        info.setExpandedState(expanded ? android.view.accessibility.AccessibilityNodeInfo.EXPANDED_STATE_FULL
                                : android.view.accessibility.AccessibilityNodeInfo.EXPANDED_STATE_COLLAPSED);
                }
                @Override public boolean performAccessibilityAction(View v, int action, android.os.Bundle arguments) {
                    if (action == android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND.getId() && !expanded
                            || action == android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_COLLAPSE.getId() && expanded) {
                        toggle(); return true;
                    }
                    return super.performAccessibilityAction(v, action, arguments);
                }
            });
            head.setOnClickListener(v -> toggle());
            block.addView(head, new LinearLayout.LayoutParams(-1, -2));
            body = column(); body.setTag("quick-section-rows:" + section.id);
            body.setPadding(0, 0, 0, ui.dp(6));
            block.addView(body, new LinearLayout.LayoutParams(-1, -2));
        }

        void toggle() {
            place(!expanded, true);
            if (expanded) open.add(section.id); else open.remove(section.id);
        }

        /** Shows the section open or closed; animated only when asked, laid out, and Android animations are on. */
        void place(boolean next, boolean animate) {
            stop();
            expanded = next;
            float turned = next ? -90 : 90;
            head.setContentDescription(pickerHeaderSaid(sectionName(section), section.entries.size(), next, android.os.Build.VERSION.SDK_INT));
            if (android.os.Build.VERSION.SDK_INT >= 30) head.setStateDescription(pickerHeaderState(next, android.os.Build.VERSION.SDK_INT));
            ViewGroup.LayoutParams size = body.getLayoutParams();
            int width = body.getWidth() > 0 ? body.getWidth() : block.getWidth();
            if (!animate || !Ui.motionEnabled() || !block.isLaidOut() || width <= 0) {
                size.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                body.setVisibility(next ? View.VISIBLE : View.GONE); body.setAlpha(1); chevron.setRotation(turned);
                body.requestLayout();
                if (animate && next) Motion.beforeNextDraw(block, () -> reveal(body.getHeight()), null);
                announceState();
                return;
            }
            int from = body.getVisibility() == View.VISIBLE ? body.getHeight() : 0;
            body.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int to = next ? body.getMeasuredHeight() : 0;
            float fromTurn = chevron.getRotation(), fromAlpha = body.getVisibility() == View.VISIBLE ? body.getAlpha() : 0;
            body.setVisibility(View.VISIBLE);
            size.height = from; body.requestLayout();
            android.animation.ValueAnimator run = android.animation.ValueAnimator.ofFloat(0f, 1f);
            run.setDuration(Motion.CHANGE_MS);
            run.setInterpolator(Motion.STANDARD);
            run.addUpdateListener(a -> {
                float t = a.getAnimatedFraction(), eased = (float) a.getAnimatedValue();
                size.height = Math.round(from + (to - from) * eased); body.requestLayout();
                // Rows fade in over the opening and out over the first half of the closing.
                body.setAlpha(next ? fromAlpha + (1 - fromAlpha) * Math.min(1f, t * 1.4f) : fromAlpha * Math.max(0f, 1 - t * 2));
                chevron.setRotation(fromTurn + (turned - fromTurn) * eased);
                if (next) reveal(size.height);
            });
            run.addListener(new android.animation.AnimatorListenerAdapter() {
                boolean cancelled;
                @Override public void onAnimationCancel(android.animation.Animator a) { cancelled = true; }
                @Override public void onAnimationEnd(android.animation.Animator a) {
                    if (animator == run) animator = null;
                    if (cancelled) return;
                    settle();
                    // Each frame's reveal ran before that frame's layout, so the scroll range lagged a frame; reveal once
                    // more on the settled height (a slow device's last frame can be tens of pixels).
                    if (next) Motion.beforeNextDraw(block, () -> reveal(body.getHeight()), null);
                }
            });
            animator = run;
            run.start();
            announceState();
        }

        /** Ends any opening or closing at once on the chosen state: a settled section is wholly open or wholly closed. */
        void stop() {
            android.animation.ValueAnimator running = animator;
            animator = null;
            if (running != null) { running.cancel(); settle(); }
        }

        private void settle() {
            body.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
            body.setVisibility(expanded ? View.VISIBLE : View.GONE);
            body.setAlpha(1); chevron.setRotation(expanded ? -90 : 90);
            body.requestLayout();
        }

        /** Scrolls the sheet so the header and up to its first three rows show, without moving the header above the top. */
        private void reveal(int bodyHeight) {
            if (!(block.getParent() instanceof View) || !(((View) block.getParent()).getParent() instanceof ScrollView)) return;
            View list = (View) block.getParent();
            ScrollView scroll = (ScrollView) list.getParent();
            int top = list.getTop() + block.getTop() + head.getTop();
            int goal = Math.min(bodyHeight, ui.dp(52) * 3 + ui.dp(6));
            int viewport = scroll.getHeight() - scroll.getPaddingTop() - scroll.getPaddingBottom();
            int want = Math.min(top, top + head.getHeight() + goal - viewport);
            if (want > scroll.getScrollY()) scroll.scrollTo(0, want);
        }

        /** The header's node changed state (its action, and its expanded state from Android 16): tell an accessibility service. */
        private void announceState() {
            android.view.accessibility.AccessibilityManager manager = (android.view.accessibility.AccessibilityManager)
                    host.activity().getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (!head.isAttachedToWindow() || manager == null || !manager.isEnabled()) return;
            android.view.accessibility.AccessibilityEvent event = android.view.accessibility.AccessibilityEvent.obtain(
                    android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
            event.setContentChangeTypes(android.os.Build.VERSION.SDK_INT >= 36
                    ? android.view.accessibility.AccessibilityEvent.CONTENT_CHANGE_TYPE_EXPANDED
                    : android.view.accessibility.AccessibilityEvent.CONTENT_CHANGE_TYPE_UNDEFINED);
            head.sendAccessibilityEventUnchecked(event);
        }
    }

    /**
     * A shortcut picker section header's spoken description: its name and count ("Ride, 8 shortcuts"), and before
     * Android 11, which has no state of its own to speak, whether it is open ("Ride, 8 shortcuts, Expanded").
     */
    public static CharSequence pickerHeaderSaid(CharSequence name, int count, boolean open, int sdk) {
        return sdk >= 30 ? T.dashboardPickerSection(count, count, name)
                : T.dashboardPickerSectionState(count, count, name, open ? T.statsStateExpanded() : T.statsStateCollapsed());
    }

    /**
     * Its spoken state from Android 11 to 15 (Expanded or Collapsed); null before (said in the description) and from
     * Android 16, which speaks the node's expanded state instead.
     */
    public static CharSequence pickerHeaderState(boolean open, int sdk) {
        return sdk >= 30 && sdk < 36 ? (open ? T.statsStateExpanded() : T.statsStateCollapsed()) : null;
    }

    /**
     * A section's shown name: its catalog group's (Ride, Battery, Pages, Menus, Experimental), the on/off control's
     * name for the line and star lights (Line lights, Star lights), and the resolver's message for a reason the scooter
     * can't use what a last section holds ("Not on this model", "Not mapped for this model", ...).
     */
    private static CharSequence sectionName(ShortcutPicker.Section section) {
        CapabilityResolver.Reason reason = ShortcutPicker.reasonOf(section.id);
        if (reason != null) return CapabilityResolver.message(reason);
        switch (section.id) {
            case ShortcutPicker.LINE_LIGHTS: return ScooterCommands.find("line_light").label();
            case ShortcutPicker.STAR_LIGHTS: return ScooterCommands.find("star_light").label();
            default:
                // Clean Home's own actions lead their sections; the catalog's group still names the section.
                for (QuickAccessActions.Entry entry : section.entries)
                    if (!HomeQuickActions.cleanOnly(entry.id)) return entry.groupLabel();
                return section.entries.get(0).groupLabel();
        }
    }

    private void duplicateWidget(DashboardConfig.Widget widget) {
        if (!editing) return;
        grid.finishGesture(false);
        // Unreachable from the disabled Duplicate control, whose tooltip gives the reason.
        if (lockType(widget.type) || config.widgets.size() >= WIDGET_LIMIT) return;
        DashboardEditHistory.Snapshot before = DashboardEditHistory.capture(config);
        DashboardConfig.Widget copy = AllWidgetsDashboard.emptyCopy(widget);
        copy.title = widget.title;
        copy.metric = widget.metric;
        copy.note = widget.note;
        copy.keys.clear();
        copy.keys.addAll(widget.keys);
        copy.quickActions.clear();
        copy.quickActions.addAll(widget.quickActions);
        copy.color = widget.color;
        copy.windowMs = widget.windowMs;
        copy.minimum = widget.minimum;
        copy.maximum = widget.maximum;
        copy.advertisedWh = widget.advertisedWh;
        copy.showDisconnect = widget.showDisconnect;
        copy.absoluteAxis = widget.absoluteAxis;
        for (boolean expanded : new boolean[] {false, true}) {
            Map<Integer, DashboardLayout.Cell> target = copy.layouts(expanded);
            for (Map.Entry<Integer, DashboardLayout.Cell> entry : widget.layouts(expanded).entrySet()) {
                DashboardLayout.Cell original = entry.getValue();
                target.put(entry.getKey(), new DashboardLayout.Cell(copy.id, original.x,
                        original.y + original.h, original.w, original.h));
            }
        }
        config.widgets.add(copy);
        grid.settle(copy.id);
        recordEdit(before);
        RectF origin = null;
        for (Tile tile : tiles) if (tile.widget == widget)
            origin = new RectF(tile.getLeft(), tile.getTop(), tile.getRight(), tile.getBottom());
        config.save();
        dismiss();
        render();
        // The copy starts at its parent’s rectangle and separates into its settled cell.
        if (origin != null && Ui.motionEnabled()) grid.previousFrames.put(copy.id, origin);
    }

    private void removeWidget(DashboardConfig.Widget widget) {
        if (!editing) return;
        grid.finishGesture(false);
        if (!config.widgets.contains(widget)) return;
        DashboardEditHistory.Snapshot before = DashboardEditHistory.capture(config);
        stopRemovalEffect();
        for (Tile tile : tiles) if (tile.widget == widget) {
            removalEffect = WidgetRemovalEffect.play(view, tile, ui);
            break;
        }
        config.widgets.remove(widget);
        config.save();
        recordEdit(before);
        dismiss();
        render();
    }

    private void addSettingRow(LinearLayout card, LinearLayout row, String icon) {
        if (card.getChildCount() > 0) sheetDivider(card);
        ui.decorate((TextView) row.getChildAt(0), icon);
        card.addView(row);
    }

    /** A round colour swatch inside a 48 dp target; the selected one gets a ring. */
    private final class Swatch extends View {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final int color;
        boolean selected;

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            // A fixed 48dp target; the flow layout measures children without their LayoutParams.
            setMeasuredDimension(resolveSize(ui.dp(48), widthSpec), resolveSize(ui.dp(48), heightSpec));
        }

        Swatch(int color, String name) {
            super(host.activity());
            this.color = color;
            setContentDescription(name);
            setFocusable(true);
            setClickable(true);
            setBackground(ripple(Ui.withAlpha(color, 50), null, roundDisc()));
        }

        void select(boolean value) {
            selected = value;
            setSelected(value);
            if (android.os.Build.VERSION.SDK_INT >= 30) setStateDescription(value ? T.commonSelected() : "");
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            canvas.drawCircle(cx, cy, ui.dp(selected ? 14 : 17), paint);
            if (selected) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(ui.dp(2.5f));
                paint.setColor(ui.text);
                canvas.drawCircle(cx, cy, ui.dp(19.5f), paint);
            }
        }
    }

    private long[] windowOptions(DashboardConfig.Widget widget) {
        if (widget.type.equals("capacity"))
            return new long[] {
                7 * 86400000L, 30 * 86400000L, 90 * 86400000L, 180 * 86400000L, 365 * 86400000L, 730 * 86400000L
            };
        return new long[] {
            2 * 60000L,
            5 * 60000L,
            15 * 60000L,
            3600000L,
            6 * 3600000L,
            86400000L,
            7 * 86400000L,
            30 * 86400000L,
            90 * 86400000L,
            365 * 86400000L,
            730 * 86400000L
        };
    }

    /** Chip labels: 2 min, 1 h, 7 days, 1 year. */
    private String shortWindow(long ms) {
        if (ms < 3600000) return T.dashboardWindowMinutes(Fmt.integer(ms / 60000));
        if (ms < 86400000) return T.dashboardWindowHours(Fmt.integer(ms / 3600000));
        long days = ms / 86400000;
        if (days == 365) return T.dashboardWindowOneYear();
        if (days == 730) return T.dashboardWindowTwoYears();
        return days == 1 ? T.dashboardWindowOneDay() : T.dashboardWindowDays(days, days);
    }

    /**
     * Bottom sheet with a heading and an X close; the content scrolls and an optional footer
     * stays pinned underneath it.
     */
    private void showSheet(String title, View content, View footer, String closeLabel, Runnable onClose) {
        showSheet(title, null, content, footer, closeLabel, onClose);
    }

    /** A fixed search header keeps filtering available while a long choice list scrolls. */
    private void showSheet(String title, View fixedHeader, View content, View footer,
            String closeLabel, Runnable onClose) {
        dismiss();
        int maximum = (int) (host.activity().getResources().getDisplayMetrics().heightPixels * .86);
        LinearLayout sheet =
                new LinearLayout(host.activity()) {
                    @Override
                    protected void onMeasure(int widthSpec, int heightSpec) {
                        int limit = MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED
                                ? maximum : Math.min(maximum, MeasureSpec.getSize(heightSpec));
                        super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(limit,
                                fixedHeader == null ? MeasureSpec.AT_MOST : MeasureSpec.EXACTLY));
                    }
                };
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(ui.dp(18), ui.dp(8), ui.dp(18), ui.dp(18));
        View grab = new View(host.activity());
        grab.setBackground(ui.shape(ui.outline, 2, 0));
        LinearLayout.LayoutParams grabSize = new LinearLayout.LayoutParams(ui.dp(36), ui.dp(4));
        grabSize.gravity = Gravity.CENTER_HORIZONTAL;
        grabSize.bottomMargin = ui.dp(4);
        sheet.addView(grab, grabSize);
        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView heading = ui.text(title, 20, true);
        heading.setTag("widget-options-title");
        if (android.os.Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        FrameLayout close = roundAction("close", closeLabel, 34, 14, this::dismiss);
        LinearLayout.LayoutParams closeSize = new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48));
        closeSize.setMarginStart(ui.dp(8));
        header.addView(close, closeSize);
        sheet.addView(header, new LinearLayout.LayoutParams(-1, -2));
        if (fixedHeader != null) sheet.addView(fixedHeader, new LinearLayout.LayoutParams(-1, -2));
        ScrollView scroll = new ScrollView(host.activity());
        scroll.setClipToPadding(false);
        scroll.setPadding(0, ui.dp(4), 0, ui.dp(4));
        scroll.addView(content);
        sheet.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        if (footer != null) sheet.addView(footer, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog created = new AlertDialog.Builder(dialogContext).create();
        dialog = created;
        created.setOnDismissListener(d -> {
            if (onClose != null) onClose.run();
            if (dialog == created) dialog = null;
            // Child sheets replace this one synchronously. A real dismissal abandons
            // the unpersisted draft; Save already consumed its arrival marker.
            view.post(() -> { if (dialog == null) pendingAddedWidgetId = null; });
        });
        // Build the sheet completely before it is shown: replacing AlertDialog's wrapping panel
        // after show() drew one centred, empty frame before the sheet jumped to the bottom.
        // The custom panel keeps the settings scrolling and the footer pinned to the sheet's edge.
        created.create();
        created.setContentView(sheet);
        Window window = created.getWindow();
        Motion.present(created, Motion.SHEET);
        if (window != null) {
            GradientDrawable shape = new GradientDrawable();
            shape.setColor(ui.surface);
            float r = ui.dp(24);
            shape.setCornerRadii(new float[] {r, r, r, r, 0, 0, 0, 0});
            window.setBackgroundDrawable(shape);
            window.setGravity(Gravity.BOTTOM);
            window.setLayout(-1, -2);
            // The panel was installed without an editor; let the title field take the keyboard.
            window.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                    | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        }
        created.show();
        if (window != null) window.setLayout(-1, -2);
    }

    private void changedWidget(DashboardConfig.Widget widget) {
        if (!config.widgets.contains(widget)) {
            options(widget);
            return;
        }
        config.save();
        dismiss();
        render();
        revealIfAdded(widget);
    }

    private Double rating(DashboardConfig.Widget widget) {
        if (widget.advertisedWh > 0) return widget.advertisedWh;
        ScooterProfiles.Profile profile = service == null ? null : service.profiles.active();
        // NAVEE US UT5 MAX specification: 46.8 V nominal × 15.3 Ah.
        return ModelRegistry.ratedWh(service == null ? null : service.modelDescriptor(profile));
    }

    private String ratingLabel(DashboardConfig.Widget widget) {
        Double value = rating(widget);
        return value == null
                ? T.dashboardSetComparison()
                : T.dashboardRatingValue(Fmt.decimal(Locale.US, value, 2), T.unitsWattHour(), widget.advertisedWh == 0 ? T.dashboardRatingModel() : "");
    }

    private void advertisedCapacity(DashboardConfig.Widget widget) {
        dismiss();
        ScooterProfiles.Profile profile = service == null ? null : service.profiles.active();
        // NAVEE US UT5 MAX specification: 46.8 V nominal × 15.3 Ah (see rating()).
        boolean modelKnown = ModelRegistry.ratedWh(service == null ? null : service.modelDescriptor(profile)) != null;
        LinearLayout content = column();
        content.setPadding(ui.dp(24), ui.dp(8), ui.dp(24), 0);
        LinearLayout field = row();
        field.setGravity(Gravity.CENTER_VERTICAL);
        EditText input = new EditText(dialogContext);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        double stored = widget.advertisedWh;
        input.setText(stored <= 0 ? "" : stored == Math.rint(stored)
                ? String.format(Locale.US, "%.0f", stored) : Double.toString(stored));
        // Empty uses the model rating when it is known.
        input.setHint(modelKnown ? T.dashboardCapacityHint() : T.unitsWattHour());
        input.setContentDescription(T.dashboardCapacityInput());
        field.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
        TextView unit = ui.text(T.unitsWattHour(), 15, false);
        unit.setTextColor(ui.muted);
        unit.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams unitSpace = new LinearLayout.LayoutParams(-2, -2);
        unitSpace.setMarginStart(ui.dp(8));
        field.addView(unit, unitSpace);
        content.addView(field, new LinearLayout.LayoutParams(-1, -2));
        if (modelKnown)
            content.addView(ui.secondaryButton(T.dashboardUseModelRating(), () -> {
                widget.advertisedWh = 0;
                changedWidget(widget);
            }));
        dialog =
                new AlertDialog.Builder(dialogContext)
                        .setTitle(M.dashboardAdvertisedCapacity())
                        .setView(content)
                        .setNegativeButton(M.commonCancel(), null)
                        .setPositiveButton(M.dashboardSave(), null)
                        .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(
                        v -> {
                            try {
                                String text = input.getText().toString().trim();
                                double n = text.isEmpty() ? 0 : Double.parseDouble(text);
                                if (!Double.isFinite(n) || n < 0 || n > 10000)
                                    throw new IllegalArgumentException();
                                widget.advertisedWh = n;
                                changedWidget(widget);
                            } catch (Exception e) {
                                input.setError(T.dashboardCapacityLimit());
                            }
                        });
    }

    private void editText(
            DashboardConfig.Widget widget,
            CharSequence label,
            String value,
            java.util.function.Consumer<String> apply) {
        dismiss();
        EditText input = new EditText(dialogContext);
        // A note edits as shown (the shipped example's note in the current language); left unchanged, it keeps its saved text.
        String shown = value != null && value.equals(widget.note) ? DashboardConfig.displayNote(widget) : value;
        input.setText(shown);
        input.setSelectAllOnFocus(true);
        input.setMaxLines(label.equals(M.dashboardNote()) ? 6 : 2);
        input.setFilters(
                new android.text.InputFilter[] {
                    new android.text.InputFilter.LengthFilter(label.equals(M.dashboardNote()) ? 1200 : 80)
                });
        dialog =
                new AlertDialog.Builder(dialogContext)
                        .setTitle(label)
                        .setView(input)
                        .setNegativeButton(M.commonCancel(), null)
                        .setPositiveButton(
                                M.dashboardSave(),
                                (d, w) -> {
                                    apply.accept(input.getText().toString().equals(shown) ? value : input.getText().toString());
                                    changedWidget(widget);
                                })
                        .show();
    }

    private void chooseMetric(DashboardConfig.Widget widget) {
        List<Telemetry.Metric> metrics = new Telemetry().metrics();
        LinearLayout content = column();
        EditText search = new EditText(dialogContext);
        search.setHint(T.dashboardSearchParameters());
        search.setSingleLine(true);
        ui.styleSearch(search);
        content.addView(search, ui.searchLayout());
        LinearLayout rows = column();
        content.addView(rows);
        Runnable fill =
                () -> {
                    rows.removeAllViews();
                    String q = SearchFold.fold(search.getText().toString());
                    for (Telemetry.Metric metric : metrics)
                        // The English name and group still match, beside the shown ones.
                        if (SearchFold.holds(metric.label + metric.group + metric.key, q)
                                || SearchFold.holds(ScooterReadingLabels.full(metric.key, metric.label)
                                        + ScooterReadingLabels.group(metric.group) + metric.key, q)) {
                            LinearLayout row =
                                    ui.settingRow(
                                            ScooterReadingLabels.full(metric.key, metric.label),
                                            ScooterReadingLabels.group(metric.group),
                                            () -> {
                                                widget.metric = metric.key;
                                                changedWidget(widget);
                                            });
                            ui.decorate((TextView) row.getChildAt(0), ui.featureIcon(metric.key));
                            rows.addView(row);
                        }
                };
        search.addTextChangedListener(
                new android.text.TextWatcher() {
                    public void beforeTextChanged(CharSequence s, int st, int c, int a) {}

                    public void onTextChanged(CharSequence s, int st, int b, int c) {
                        fill.run();
                    }

                    public void afterTextChanged(android.text.Editable e) {}
                });
        fill.run();
        show(T.dashboardChooseParameter(), content);
        SearchFocus.bind(search, dialog.getWindow());
    }

    private void chooseReadings(DashboardConfig.Widget widget) {
        List<Telemetry.Metric> metrics = new Telemetry().metrics();
        // Keep the draft independent of the filtered views. Closing discards it;
        // saving retains selection order, including readings hidden by the query.
        LinkedHashSet<String> chosen = new LinkedHashSet<>(widget.keys);
        // The Home cards show up to four readings; Reading tiles needs at least one.
        final boolean compactCard = widget.type.equals("overview") || widget.type.equals("tiles");
        final int most = compactCard ? 4 : 12, least = widget.type.equals("tiles") ? 1 : 0;
        LinearLayout header = column();
        EditText search = new EditText(dialogContext);
        search.setSingleLine(true);
        search.setHint(T.dashboardSearchReadings());
        search.setContentDescription(T.dashboardSearchReadings());
        ui.styleSearch(search);
        search.setBackground(ui.shape(ui.dark ? ui.bg : ui.raised, 14, ui.outline));
        header.addView(search, ui.searchLayout());
        TextView count = ui.caption(T.dashboardSelectedOfMost(chosen.size(), Fmt.integer(chosen.size()), Fmt.integer(most)));
        count.setPadding(ui.dp(2), 0, ui.dp(2), ui.dp(10));
        count.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        header.addView(count);
        LinearLayout choices = column();
        TextView selectedHeading = sheetLabel(T.commonSelected());
        selectedHeading.setTag("selected-readings-heading");
        choices.addView(selectedHeading);
        LinearLayout selectedRows = column();
        selectedRows.setTag("selected-readings");
        choices.addView(selectedRows);
        TextView allHeading = sheetLabel(T.dashboardAllReadings());
        allHeading.setTag("all-readings-heading");
        choices.addView(allHeading);
        ArrayList<View> items = new ArrayList<>();
        Map<String, View> originals = new LinkedHashMap<>();
        Runnable[] refreshSelected = new Runnable[1];
        for (Telemetry.Metric metric : metrics) {
            LinearLayout item = row();
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setMinimumHeight(ui.dp(64));
            item.setPadding(ui.dp(12), ui.dp(12), ui.dp(8), ui.dp(12));
            item.setClickable(true);
            item.setFocusable(true);
            item.setTag("reading-choice:" + metric.key);
            item.setContentDescription(T.dashboardReadingGroup(ScooterReadingLabels.full(metric.key, metric.label), ScooterReadingLabels.group(metric.group)));
            GlyphView icon = new GlyphView(readingIcon(metric.key), ui.accent);
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            item.addView(icon, new LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)));
            LinearLayout words = column();
            words.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            words.addView(ui.text(ScooterReadingLabels.full(metric.key, metric.label), 14, false));
            TextView group = ui.caption(ScooterReadingLabels.group(metric.group));
            group.setPadding(0, ui.dp(3), 0, 0);
            words.addView(group);
            LinearLayout.LayoutParams wordsSize = new LinearLayout.LayoutParams(0, -2, 1);
            wordsSize.setMargins(ui.dp(12), 0, ui.dp(8), 0);
            item.addView(words, wordsSize);
            CheckBox check = new CheckBox(dialogContext);
            check.setButtonTintList(new android.content.res.ColorStateList(
                    new int[][] {new int[] {android.R.attr.state_checked}, new int[] {}},
                    new int[] {ui.accent, ui.muted}));
            check.setClickable(false);
            check.setFocusable(false);
            check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            item.addView(check, new LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)));
            Runnable style = () -> {
                boolean selected = chosen.contains(metric.key);
                check.setChecked(selected);
                item.setSelected(selected);
                item.setBackground(ripple(Ui.withAlpha(ui.accent, 30),
                        ui.shape(selected ? blend(ui.raised, ui.accent, ui.dark ? .14f : .08f) : ui.raised,
                                14, selected ? Ui.withAlpha(ui.accent, 110) : 0), null));
            };
            item.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View view,
                        android.view.accessibility.AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(view, info);
                    info.setClassName("android.widget.CheckBox");
                    info.setCheckable(true);
                    info.setChecked(chosen.contains(metric.key));
                }
            });
            item.setOnClickListener(v -> {
                if (chosen.contains(metric.key)) chosen.remove(metric.key);
                else if (chosen.size() < most) chosen.add(metric.key);
                else return;
                style.run();
                count.setText(T.dashboardSelectedOfMost(chosen.size(), Fmt.integer(chosen.size()), Fmt.integer(most)));
                refreshSelected[0].run();
            });
            style.run();
            LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-1, -2);
            gap.bottomMargin = ui.dp(8);
            choices.addView(item, gap);
            items.add(item);
            originals.put(metric.key, item);
        }
        refreshSelected[0] = () -> {
            // At the limit, unchecked rows grey out; the "N of N selected" count says why.
            boolean full = chosen.size() >= most;
            for (Map.Entry<String, View> entry : originals.entrySet()) {
                boolean available = !full || chosen.contains(entry.getKey());
                entry.getValue().setEnabled(available);
                entry.getValue().setAlpha(available ? 1f : .4f);
            }
            Map<String, Float> old = childPositions(choices);
            Map<String, Float> pinned = childPositions(selectedRows);
            selectedRows.removeAllViews();
            selectedHeading.setVisibility(chosen.isEmpty() ? View.GONE : View.VISIBLE);
            for (String key : chosen) {
                View original = originals.get(key);
                if (original == null) continue;
                LinearLayout selected = row(); selected.setGravity(Gravity.CENTER_VERTICAL);
                selected.setPadding(ui.dp(12), ui.dp(8), ui.dp(8), ui.dp(8));
                selected.setMinimumHeight(ui.dp(56));
                selected.setTag("selected-reading:" + key);
                selected.setContentDescription(T.dashboardReadingUncheck(label(key)));
                selected.setClickable(true); selected.setFocusable(true);
                selected.setBackground(ripple(Ui.withAlpha(ui.accent, 30),
                        ui.shape(blend(ui.raised, ui.accent, ui.dark ? .14f : .08f), 14,
                                Ui.withAlpha(ui.accent, 110)), null));
                selected.addView(new GlyphView(readingIcon(key), ui.accent),
                        new LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)));
                TextView name = ui.text(ScooterReadingLabels.label(key, label(key)), 14, false);
                name.setPadding(ui.dp(12), 0, ui.dp(8), 0);
                selected.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
                CheckBox check = new CheckBox(dialogContext); check.setChecked(true);
                check.setButtonTintList(android.content.res.ColorStateList.valueOf(ui.accent));
                check.setClickable(false); check.setFocusable(false);
                check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                selected.addView(check, new LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)));
                selected.setOnClickListener(v -> original.performClick());
                selected.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                    @Override public void onInitializeAccessibilityNodeInfo(View v,
                            android.view.accessibility.AccessibilityNodeInfo info) {
                        super.onInitializeAccessibilityNodeInfo(v, info);
                        info.setClassName("android.widget.CheckBox"); info.setCheckable(true); info.setChecked(true);
                    }
                });
                LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-1, -2); gap.bottomMargin = ui.dp(8);
                selectedRows.addView(selected, gap);
            }
            animateRows(choices, old);
            animateRows(selectedRows, pinned);
        };
        refreshSelected[0].run();
        TextView empty = ui.caption(T.dashboardNoMatchingReadings());
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(ui.dp(12), ui.dp(24), ui.dp(12), ui.dp(24));
        choices.addView(empty, new LinearLayout.LayoutParams(-1, -2));
        Runnable filter = () -> {
            String query = SearchFold.fold(search.getText().toString().trim());
            int matches = 0;
            for (int i = 0; i < metrics.size(); i++) {
                Telemetry.Metric metric = metrics.get(i);
                boolean visible = (metric.label + " " + metric.group + " " + metric.key
                        + " " + ScooterReadingLabels.label(metric.key, metric.label))
                        .toLowerCase(Locale.ROOT).contains(query);
                // The shown names match too, folded as the query is, and so do the English ones. In English that finds nothing more.
                if (!visible)
                    visible = SearchFold.holds(metric.label, metric.group, query)
                            || SearchFold.holdsShown(ScooterReadingLabels.label(metric.key, metric.label), ScooterReadingLabels.group(metric.group), query)
                            || SearchFold.holdsShown(ScooterReadingLabels.full(metric.key, metric.label), ScooterReadingLabels.group(metric.group), query);
                items.get(i).setVisibility(visible ? View.VISIBLE : View.GONE);
                if (visible) matches++;
            }
            empty.setVisibility(matches == 0 ? View.VISIBLE : View.GONE);
            if (choices.getParent() instanceof ScrollView)
                ((ScrollView) choices.getParent()).scrollTo(0, 0);
        };
        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { filter.run(); }
            public void afterTextChanged(android.text.Editable text) {}
        });
        filter.run();
        TextView save = ui.button(T.dashboardSaveChecked(), () -> {
            if (chosen.size() > most) return;
            if (chosen.size() < least) {
                host.toast(T.dashboardChooseOneReading());
                return;
            }
            widget.keys.clear();
            widget.keys.addAll(chosen);
            config.save();
            render();
            options(widget);
        });
        save.setContentDescription(T.dashboardSaveReadings());
        LinearLayout footer = column();
        footer.setPadding(0, ui.dp(12), 0, 0);
        footer.addView(save, new LinearLayout.LayoutParams(-1, -2));
        showSheet(T.dashboardChooseReadings(), header, choices, footer, T.dashboardCancelReadings(), null);
        if (dialog != null) SearchFocus.bind(search, dialog.getWindow());
    }

    private String scaleSummary(DashboardConfig.Widget widget, double value) {
        double displayed = chartNumber(widget.metric, value);
        String amount = widget.metric.endsWith("_kmh")
                ? new GaugeScaleInput(value, displayed, 1).text
                : String.format(Fmt.locale(Locale.US), "%.2f", displayed);
        return T.fmtQuantity(amount, shownUnit(chartUnit(widget.metric, readings())));
    }

    private void scale(DashboardConfig.Widget widget, boolean maximum) {
        dismiss();
        EditText input = new EditText(dialogContext);
        input.setInputType(
                InputType.TYPE_CLASS_NUMBER
                        | InputType.TYPE_NUMBER_FLAG_DECIMAL
                        | InputType.TYPE_NUMBER_FLAG_SIGNED);
        double original = maximum ? widget.maximum : widget.minimum;
        GaugeScaleInput scaleInput = new GaugeScaleInput(original, chartNumber(widget.metric, original),
                widget.metric.endsWith("_kmh") ? 1 : 6);
        input.setText(scaleInput.text);
        String unitName = chartUnit(widget.metric, readings());
        input.setContentDescription(maximum ? (unitName.isEmpty() ? T.dashboardScaleMaximum() : T.dashboardScaleMaximumIn(unitName))
                : unitName.isEmpty() ? T.dashboardScaleMinimum() : T.dashboardScaleMinimumIn(unitName));
        LinearLayout content = row();
        content.setGravity(Gravity.CENTER_VERTICAL);
        content.setPadding(ui.dp(24), ui.dp(8), ui.dp(24), 0);
        content.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
        if (!unitName.isEmpty()) {
            // Said with the unit's shown symbol, as beside the field; G1's choice row above holds the data unit.
            input.setContentDescription(maximum ? T.dashboardScaleMaximumIn(shownUnit(unitName)) : T.dashboardScaleMinimumIn(shownUnit(unitName)));
            TextView unit = ui.text(shownUnit(unitName), 15, false);
            unit.setTextColor(ui.muted);
            unit.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams unitSpace = new LinearLayout.LayoutParams(-2, -2);
            unitSpace.setMarginStart(ui.dp(8));
            content.addView(unit, unitSpace);
        }
        dialog =
                new AlertDialog.Builder(dialogContext)
                        .setTitle(maximum ? M.dashboardScaleMaximum() : M.dashboardScaleMinimum())
                        .setView(content)
                        .setNegativeButton(M.commonCancel(), null)
                        .setPositiveButton(M.dashboardSave(), null)
                        .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(
                        v -> {
                            try {
                                double n = scaleInput.resolve(input.getText().toString(), value -> {
                                    if (DisplayTemperature.isTemperature(widget.metric))
                                        return DisplayTemperature.toCelsius(value, temperatureUnit());
                                    if (!units().equals("km") && (widget.metric.endsWith("_km")
                                            || widget.metric.endsWith("_kmh"))) return value * 1.609344;
                                    return value;
                                });
                                if (!Double.isFinite(n)
                                        || (maximum ? n <= widget.minimum : n >= widget.maximum))
                                    throw new IllegalArgumentException();
                                if (maximum) widget.maximum = n;
                                else widget.minimum = n;
                                changedWidget(widget);
                            } catch (Exception e) {
                                input.setError(maximum
                                        ? T.dashboardMustBeAbove(scaleSummary(widget, widget.minimum))
                                        : T.dashboardMustBeBelow(scaleSummary(widget, widget.maximum)));
                            }
                        });
    }

    private void size(DashboardConfig.Widget widget) {
        dismiss();
        DashboardLayout.Cell cell = grid.currentCell(widget);
        LinearLayout controls = column();
        NumberPicker width = new NumberPicker(dialogContext);
        width.setMinValue(grid.minimumWidth(widget));
        width.setMaxValue(grid.columns);
        width.setValue(cell.w);
        NumberPicker height = new NumberPicker(dialogContext);
        int minimumHeight = widget.minimumHeight((cell.w * grid.columnWidth() - ui.dp(10))
                / host.activity().getResources().getDisplayMetrics().density);
        height.setMinValue(minimumHeight);
        height.setMaxValue(16);
        height.setValue(Math.max(minimumHeight, cell.h));
        controls.addView(ui.caption(T.dashboardColumns()));
        controls.addView(width);
        controls.addView(ui.caption(T.dashboardRows()));
        controls.addView(height);
        dialog =
                new AlertDialog.Builder(dialogContext)
                        .setTitle(M.dashboardWidgetSize())
                        .setView(controls)
                        .setNegativeButton(M.commonCancel(), null)
                        .setPositiveButton(
                                M.dashboardApply(),
                                (d, n) -> {
                                    DashboardEditHistory.Snapshot before = DashboardEditHistory.capture(config);
                                    grid.prepareEdit();
                                    DashboardLayout.Cell latest = config.widgetLayouts(widget).get(grid.columns);
                                    latest.w = width.getValue();
                                    latest.h = height.getValue();
                                    grid.settle(widget.id);
                                    recordEdit(before);
                                    changedWidget(widget);
                                })
                        .show();
    }

    private void position(DashboardConfig.Widget widget) {
        dismiss();
        DashboardLayout.Cell cell = grid.currentCell(widget);
        // Limited-range pickers, like the size dialog, so an out-of-range position cannot be entered.
        LinearLayout controls = column();
        NumberPicker x = new NumberPicker(dialogContext);
        x.setMinValue(1);
        x.setMaxValue(Math.max(1, grid.columns));
        x.setValue(Math.max(1, Math.min(grid.columns, cell.x + 1)));
        x.setContentDescription(T.dashboardColumn());
        NumberPicker y = new NumberPicker(dialogContext);
        y.setMinValue(1);
        y.setMaxValue(301);
        y.setValue(Math.max(1, Math.min(301, cell.y + 1)));
        y.setContentDescription(T.dashboardRow());
        controls.addView(ui.caption(T.dashboardColumn()));
        controls.addView(x);
        controls.addView(ui.caption(T.dashboardRow()));
        controls.addView(y);
        dialog =
                new AlertDialog.Builder(dialogContext)
                        .setTitle(M.dashboardMoveWidget())
                        .setView(controls)
                        .setNegativeButton(M.commonCancel(), null)
                        .setPositiveButton(
                                M.dashboardMove(),
                                (d, n) -> {
                                    DashboardEditHistory.Snapshot before = DashboardEditHistory.capture(config);
                                    grid.prepareEdit();
                                    DashboardLayout.Cell latest = config.widgetLayouts(widget).get(grid.columns);
                                    latest.x = Math.max(0, Math.min(grid.columns - 1, x.getValue() - 1));
                                    latest.y = Math.max(0, Math.min(300, y.getValue() - 1));
                                    grid.settle(widget.id);
                                    recordEdit(before);
                                    changedWidget(widget);
                                })
                        .show();
    }

    /** One recessed segmented control; its highlight retargets from the current visual position. */
    private final class ColumnSelector extends HorizontalScrollView {
        final ColumnTrack track;

        ColumnSelector(int usable, int selected) {
            super(host.activity());
            setTag("dashboard-column-selector");
            setFillViewport(true);
            setHorizontalScrollBarEnabled(false);
            setClipToOutline(true);
            setBackground(ui.shape(ui.dark ? ui.bg : ui.raised, 14, ui.outline));
            track = new ColumnTrack(usable, selected);
            addView(track, new HorizontalScrollView.LayoutParams(-2, -2));
        }

        private final class ColumnTrack extends LinearLayout {
            final ArrayList<TextView> choices = new ArrayList<>();
            final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            final RectF indicator = new RectF();
            android.animation.ValueAnimator animation;
            int selected;

            ColumnTrack(int usable, int selected) {
                super(host.activity());
                this.selected = Math.max(0, Math.min(DashboardConfig.MAX_COLUMNS, selected));
                setOrientation(HORIZONTAL);
                setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4));
                setWillNotDraw(false);
                for (int i = 0; i <= DashboardConfig.MAX_COLUMNS; i++) {
                    final int choice = i;
                    TextView button = ui.text(i == 0 ? T.dashboardAdaptive() : Integer.toString(i), 13, true);
                    button.setGravity(Gravity.CENTER);
                    button.setMinWidth(ui.dp(i == 0 ? 90 : 48));
                    button.setPadding(ui.dp(12), 0, ui.dp(12), 0);
                    button.setFocusable(true);
                    button.setClickable(true);
                    // Too many columns for this screen: shown greyed; a tap explains at the bottom of the screen.
                    final boolean available = i == 0 || i <= usable;
                    button.setAlpha(available ? 1f : .4f);
                    button.setContentDescription(i == 0 ? T.dashboardAdaptiveColumns()
                            : available ? T.dashboardColumnCount(i, i) : T.dashboardColumnCountNeedsWider(T.dashboardColumnCount(i, i)));
                    button.setOnClickListener(v -> {
                        if (!available) {
                            showNotice(button, T.dashboardNeedsWiderScreen(), null, null);
                            return;
                        }
                        this.selected = choice;
                        preferences.edit().putInt("dashboard_columns", choice).apply();
                        updateSelection(true);
                        grid.finishGesture(false);
                        grid.requestLayout();
                        int target = button.getLeft() - (ColumnSelector.this.getWidth() - button.getWidth()) / 2;
                        if (android.animation.ValueAnimator.areAnimatorsEnabled())
                            ColumnSelector.this.smoothScrollTo(Math.max(0, target), 0);
                        else ColumnSelector.this.scrollTo(Math.max(0, target), 0);
                    });
                    choices.add(button);
                    addView(button, new LinearLayout.LayoutParams(-2, ui.dp(48), 1));
                }
            }

            private void updateSelection(boolean animate) {
                for (int i = 0; i < choices.size(); i++) {
                    TextView button = choices.get(i);
                    button.setSelected(i == selected);
                    button.setTextColor(i == selected ? ui.onAccent : ui.text);
                    if (android.os.Build.VERSION.SDK_INT >= 30)
                        button.setStateDescription(i == selected ? T.commonSelected() : "");
                }
                TextView button = choices.get(selected);
                if (button.getWidth() == 0) return;
                RectF end = new RectF(button.getLeft(), button.getTop(), button.getRight(), button.getBottom());
                if (animation != null) animation.cancel();
                if (!animate || indicator.isEmpty() || !android.animation.ValueAnimator.areAnimatorsEnabled()) {
                    indicator.set(end);
                    invalidate();
                    return;
                }
                RectF start = new RectF(indicator);
                animation = android.animation.ValueAnimator.ofFloat(0, 1);
                animation.setDuration(190);
                animation.setInterpolator(new android.view.animation.PathInterpolator(.2f, 0, 0, 1));
                animation.addUpdateListener(a -> {
                    float f = (float) a.getAnimatedValue();
                    indicator.set(start.left + (end.left - start.left) * f,
                            start.top + (end.top - start.top) * f,
                            start.right + (end.right - start.right) * f,
                            start.bottom + (end.bottom - start.bottom) * f);
                    invalidate();
                });
                animation.start();
            }

            @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
                super.onLayout(changed, l, t, r, b);
                if (changed || indicator.isEmpty()) updateSelection(false);
            }

            @Override protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                // A restrained inner shade gives the shared track a recessed edge.
                paint.setShader(new LinearGradient(0, 0, 0, ui.dp(10),
                        Color.argb(ui.dark ? 80 : 24, 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP));
                canvas.drawRoundRect(0, 0, getWidth(), getHeight(), ui.dp(14), ui.dp(14), paint);
                paint.setShader(null);
                paint.setColor(ui.accent);
                canvas.drawRoundRect(indicator, ui.dp(14), ui.dp(14), paint);
            }

            @Override protected void onDetachedFromWindow() {
                if (animation != null) animation.cancel();
                super.onDetachedFromWindow();
            }
        }
    }

    private void layoutOptions() {
        grid.finishGesture(false);
        LinearLayout content = column();
        content.addView(homeStyleRow());
        content.addView(sheetLabel(T.dashboardColumns()));
        int usable = grid.maximumColumns();
        int selected = preferences.getInt("dashboard_columns", 0);
        LinearLayout.LayoutParams selectorSpace = new LinearLayout.LayoutParams(-1, -2);
        selectorSpace.bottomMargin = ui.dp(6);
        content.addView(new ColumnSelector(usable, selected), selectorSpace);
        content.addView(sheetLabel(T.dashboardSavedLayouts()));
        for (int i = 0; i < DashboardConfig.SLOT_COUNT; i++) addLayoutSlot(content, i);
        LinearLayout transfers = row();
        View export = toolButton(T.dashboardExport(), "download", () -> { dismiss(); host.exportDashboardLayouts(); });
        View importButton = toolButton(T.dashboardImport(), "restore", () -> { dismiss(); host.importDashboardLayouts(); });
        LinearLayout.LayoutParams exportSpace = new LinearLayout.LayoutParams(0, -2, 1);
        exportSpace.setMarginEnd(ui.dp(10));
        transfers.addView(export, exportSpace);
        transfers.addView(importButton, new LinearLayout.LayoutParams(0, -2, 1));
        content.addView(transfers);
        content.addView(sheetLabel(T.dashboardDefaultLayout()));
        LinearLayout restore = row();
        restore.setGravity(Gravity.CENTER_VERTICAL);
        restore.setMinimumHeight(ui.dp(64));
        restore.setPadding(ui.dp(12), ui.dp(10), ui.dp(8), ui.dp(10));
        restore.setBackground(ripple(Ui.withAlpha(ui.accent, 26),
                ui.shape(ui.dark ? ui.raised : ui.bg, 16, 0), null));
        restore.setClickable(true);
        restore.setFocusable(true);
        restore.addView(square("restore", ui.accent, 38), new LinearLayout.LayoutParams(ui.dp(38), ui.dp(38)));
        LinearLayout words = column();
        TextView name = ui.text(T.dashboardRestoreDefaults(), 15, false);
        name.setTypeface(ui.medium());
        words.addView(name);
        LinearLayout.LayoutParams wordsSpace = new LinearLayout.LayoutParams(0, -2, 1);
        wordsSpace.setMarginStart(ui.dp(12));
        restore.addView(words, wordsSpace);
        restore.setOnClickListener(v -> restoreDefaults());
        content.addView(restore, new LinearLayout.LayoutParams(-1, -2));
        showSheet(T.dashboardLayoutTitle(), content, null, T.dashboardCloseLayout(), null);
    }

    /**
     * The Layout sheet's first row: Home style (Widgets now). It ends editing and opens Settings › Home style, where
     * the owner can switch to Clean; switching keeps this dashboard and its saved layouts.
     */
    private View homeStyleRow() {
        LinearLayout line = row();
        line.setTag("dashboard-home-style");
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setMinimumHeight(ui.dp(64));
        line.setPadding(ui.dp(12), ui.dp(10), ui.dp(8), ui.dp(10));
        line.setBackground(ripple(Ui.withAlpha(ui.accent, 26), ui.shape(ui.dark ? ui.raised : ui.bg, 16, 0), null));
        line.setClickable(true);
        line.setFocusable(true);
        line.addView(square("home", ui.accent, 38), new LinearLayout.LayoutParams(ui.dp(38), ui.dp(38)));
        LinearLayout words = column();
        TextView name = ui.text(HubText.title(HubPages.SETTINGS_HOME), 15, false);
        name.setTypeface(ui.medium());
        words.addView(name);
        words.addView(ui.caption(HubText.homeStyleName(false).render()));
        LinearLayout.LayoutParams wordsSpace = new LinearLayout.LayoutParams(0, -2, 1);
        wordsSpace.setMarginStart(ui.dp(12));
        line.addView(words, wordsSpace);
        line.addView(new GlyphView("chevron", ui.muted), new LinearLayout.LayoutParams(ui.dp(15), ui.dp(15)));
        line.setOnClickListener(v -> {
            dismiss();
            if (editing) toggleEditing();
            host.open(HubPages.SETTINGS_HOME);
        });
        LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-1, -2);
        space.bottomMargin = ui.dp(6);
        line.setLayoutParams(space);
        return line;
    }

    /** Restores the defaults at once; Undo in the notice (or the header) brings the old layout back. */
    private void restoreDefaults() {
        if (closed || !editing) return;
        grid.finishGesture(false);
        stopRemovalEffect();
        DashboardEditHistory.Snapshot before = DashboardEditHistory.captureWithColumns(config);
        String previous = config.widgetsJson();
        int columns = config.columnPreference();
        config.reset();
        boolean recorded = recordEdit(before);
        if (recorded) {
            try {
                preferences.edit().putString(restoreUndoKey, new org.json.JSONObject()
                        .put("widgets", previous)
                        .put("columns", columns)
                        .put("restored", config.widgetsJson())
                        .toString()).apply();
            } catch (org.json.JSONException ignored) {
                // Undo still works from this screen's history.
            }
        }
        dismiss();
        render();
        if (recorded) offerRestoreUndo();
        else showNotice(view, T.dashboardDefaultsRestored(), null, null);
    }

    private void offerRestoreUndo() {
        showNotice(view, T.dashboardDefaultsRestored(), T.dashboardUndo(), () -> {
            if (closed || !editing || !editHistory.canUndo()) return;
            restoreEdit(false);
        });
    }

    /** The identity evidence model tiles depend on: the advertised Model ID and the serial. */
    private static String identity(ScooterProfiles.Profile p) { return p == null ? "" : p.advertisedPid + "|" + p.serial; }
    private String modelIdentity = "";

    /**
     * Untouched default Setting tiles, or an untouched earlier pick for this model (previous: one with a Top speed tile from
     * before registry version 17), become the model's own and are saved. A pending Restore defaults Undo that points at
     * exactly those tiles is moved along with them, so adapting never ends the Undo offer.
     */
    private boolean reconcileModelTiles(java.util.List<String> tiles, java.util.List<String> previous) {
        String before = config.storedWidgetsJson();
        if (!config.adaptSettingTiles(tiles, previous)) return false;
        config.save();
        if (before != null && preferences.contains(restoreUndoKey)) {
            try {
                org.json.JSONObject saved = new org.json.JSONObject(preferences.getString(restoreUndoKey, ""));
                if (before.equals(saved.optString("restored", null)))
                    preferences.edit().putString(restoreUndoKey, saved.put("restored", config.storedWidgetsJson()).toString()).apply();
            } catch (org.json.JSONException unreadable) {
                // An unreadable offer ends as before.
            }
        }
        return true;
    }

    private void clearRestoreUndo() {
        if (preferences.contains(restoreUndoKey)) preferences.edit().remove(restoreUndoKey).apply();
    }

    /** After a rebuild in edit mode, puts a pending Restore defaults back into Undo and offers it again. */
    private void resumeRestoreUndo() {
        if (!preferences.contains(restoreUndoKey)) return;
        ArrayList<DashboardConfig.Widget> previous = null;
        int columns = -1;
        try {
            org.json.JSONObject saved = new org.json.JSONObject(preferences.getString(restoreUndoKey, ""));
            // Anything that replaced the restored defaults since (an import, a backup restore) ends the offer.
            String restored = saved.getString("restored");
            String stored = config.storedWidgetsJson();
            if (editing && stored != null && stored.equals(restored)) {
                previous = DashboardConfig.parseWidgets(saved.getString("widgets"));
                columns = saved.optInt("columns", -1);
            }
        } catch (org.json.JSONException | RuntimeException unreadable) {
            previous = null;
        }
        if (previous == null) {
            clearRestoreUndo();
            return;
        }
        ArrayList<DashboardConfig.Widget> current = new ArrayList<>(config.widgets);
        config.widgets.clear();
        config.widgets.addAll(previous);
        DashboardEditHistory.Snapshot before = DashboardEditHistory.captureWithColumns(config, columns);
        config.widgets.clear();
        config.widgets.addAll(current);
        if (!editHistory.record(before, config)) {
            clearRestoreUndo();
            return;
        }
        updateHistoryControls();
        Runnable offer = () -> view.post(() -> { if (!closed && editing && editHistory.canUndo()) offerRestoreUndo(); });
        if (view.isAttachedToWindow()) offer.run();
        else view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) {
                v.removeOnAttachStateChangeListener(this);
                offer.run();
            }
            @Override public void onViewDetachedFromWindow(View v) { }
        });
    }

    /**
     * A short message at the bottom of the screen, above every window including open sheets, with an
     * optional action. It slides in, then hides itself; nothing is shown otherwise.
     */
    private void showNotice(View anchor, String message, String actionLabel, Runnable action) {
        hideNotice();
        if (closed || anchor == null || !anchor.isAttachedToWindow() || anchor.getWindowToken() == null) return;
        LinearLayout pill = row();
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setMinimumHeight(ui.dp(48));
        pill.setPadding(ui.dp(16), 0, ui.dp(action == null ? 16 : 4), 0);
        pill.setBackground(ui.shape(ui.dark ? ui.raised : ui.text, 14, 0));
        pill.setElevation(ui.dp(6));
        TextView text = ui.text(message, 14, false);
        text.setTextColor(ui.dark ? ui.text : ui.bg);
        pill.addView(text, new LinearLayout.LayoutParams(-2, -2));
        if (action != null) {
            TextView button = ui.text(actionLabel, 14, true);
            button.setTextColor(ui.dark ? ui.accent : blend(ui.accent, Color.WHITE, .35f));
            button.setGravity(Gravity.CENTER);
            button.setMinHeight(ui.dp(48));
            button.setMinWidth(ui.dp(64));
            button.setPadding(ui.dp(12), 0, ui.dp(12), 0);
            button.setBackground(ripple(Ui.withAlpha(ui.accent, 50), null, ui.shape(Color.WHITE, 12, 0)));
            button.setClickable(true);
            button.setFocusable(true);
            button.setTag("dashboard-notice-action");
            button.setOnClickListener(v -> {
                hideNotice();
                action.run();
            });
            LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-2, -2);
            space.setMarginStart(ui.dp(8));
            pill.addView(button, space);
        }
        FrameLayout holder = new FrameLayout(host.activity());
        holder.setClipToPadding(false);
        holder.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12));
        holder.addView(pill, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        PopupWindow window = new PopupWindow(holder, -2, -2, false);
        window.setTouchable(action != null);
        window.setOutsideTouchable(false);
        window.setAnimationStyle(0);
        int inset = 0;
        WindowInsets insets = anchor.getRootWindowInsets();
        if (insets != null) inset = insets.getSystemWindowInsetBottom();
        try {
            window.showAtLocation(anchor.getRootView(), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0,
                    ui.dp(12) + inset);
        } catch (RuntimeException unavailable) {
            return;
        }
        notice = window;
        anchor.announceForAccessibility(message);
        if (Ui.motionEnabled()) {
            pill.setAlpha(0);
            pill.setTranslationY(ui.dp(24));
            pill.animate().alpha(1).translationY(0).setDuration(220).setInterpolator(Motion.STANDARD).start();
        }
        holder.postDelayed(() -> { if (notice == window) hideNotice(); }, action == null ? 2500 : 5000);
    }

    private void hideNotice() {
        PopupWindow window = notice;
        notice = null;
        if (window == null) return;
        View holder = window.getContentView();
        View pill = holder instanceof ViewGroup && ((ViewGroup) holder).getChildCount() > 0
                ? ((ViewGroup) holder).getChildAt(0) : null;
        Runnable close = () -> {
            try { window.dismiss(); } catch (RuntimeException ignored) { }
        };
        if (pill != null && Ui.motionEnabled() && !closed && holder.isAttachedToWindow()) {
            pill.animate().cancel();
            pill.animate().alpha(0).translationY(ui.dp(24)).setDuration(160)
                    .setInterpolator(Motion.STANDARD).withEndAction(close).start();
        } else close.run();
    }

    private void addLayoutSlot(LinearLayout parent, int index) {
        DashboardConfig.SavedSlot saved = config.slot(index);
        boolean occupied = config.slotExists(index);
        String slotName = "Slot " + (index + 1);
        LinearLayout card = sheetCard();
        card.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12));
        TextView heading = ui.text(saved == null || saved.name.equals(slotName) ? DashboardConfig.defaultSlotName(index)
                : T.dashboardSlotTitle(DashboardConfig.defaultSlotName(index), DashboardConfig.displaySlotName(saved, index)), 15, true);
        if (android.os.Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        card.addView(heading);
        String detail = saved == null
                ? (occupied ? T.dashboardSlotUnreadable() : T.dashboardSlotEmpty())
                : T.dashboardSlotSummary(saved.widgetCount, saved.widgetCount, DisplayTime.recent(host.activity(), preferences,
                                saved.savedAtMs, System.currentTimeMillis()));
        TextView summary = ui.caption(detail);
        summary.setPadding(0, ui.dp(3), 0, ui.dp(8));
        card.addView(summary);
        LinearLayout buttons = row();
        TextView save = ui.secondaryButton(T.dashboardSave(), () -> saveLayoutSlot(index));
        save.setContentDescription(T.dashboardSaveToSlotDescription(Fmt.integer(index + 1)));
        save.setTag("dashboard-slot-save-" + index);
        TextView load = ui.button(T.dashboardRestore(), () -> restoreLayoutSlot(index));
        load.setContentDescription(saved == null ? T.dashboardRestoreSlot(Fmt.integer(index + 1))
                : T.dashboardRestoreSlotNamed(Fmt.integer(index + 1), saved.name));
        load.setTag("dashboard-slot-restore-" + index);
        if (saved != null) load.setContentDescription(T.dashboardRestoreSlotNamed(Fmt.integer(index + 1), DashboardConfig.displaySlotName(saved, index)));
        load.setEnabled(saved != null);
        LinearLayout.LayoutParams saveSize = new LinearLayout.LayoutParams(0, -2, 1);
        saveSize.setMarginEnd(ui.dp(8));
        buttons.addView(save, saveSize);
        buttons.addView(load, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(buttons);
        LinearLayout.LayoutParams cardSize = new LinearLayout.LayoutParams(-1, -2);
        cardSize.bottomMargin = ui.dp(10);
        parent.addView(card, cardSize);
    }

    private void saveLayoutSlot(int index) {
        DashboardConfig.SavedSlot saved = config.slot(index);
        boolean replacing = config.slotExists(index);
        dismiss();
        LinearLayout content = column();
        // The title ("Replace slot N?") and the Replace button carry the overwrite warning.
        content.setPadding(ui.dp(24), ui.dp(8), ui.dp(24), 0);
        EditText name = new EditText(dialogContext);
        name.setSingleLine(true);
        name.setHint(T.dashboardLayoutName());
        name.setContentDescription(T.dashboardSavedLayoutName());
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        name.setFilters(new android.text.InputFilter[] {
            new android.text.InputFilter.LengthFilter(DashboardConfig.SLOT_NAME_LIMIT)
        });
        name.setText(saved == null ? T.dashboardModelSlotDefault(Fmt.integer(index + 1)) : saved.name);
        name.setSelectAllOnFocus(true);
        // A saved slot's name edits as shown (Volt's names in the current language). DashboardConfig's
        // slotNameToSave keeps a name of Volt's the save leaves Volt's; other saves store the field's text.
        String shown = saved == null ? DashboardConfig.defaultSlotName(index) : DashboardConfig.displaySlotName(saved, index);
        if (saved != null) name.setText(shown);
        content.addView(name, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog created = new AlertDialog.Builder(dialogContext)
                .setTitle(replacing ? T.dashboardReplaceSlotTitle(Fmt.integer(index + 1)) : T.dashboardSaveSlotTitle(Fmt.integer(index + 1)))
                .setView(content)
                .setNegativeButton(M.commonCancel(), (d, which) -> returnToLayoutOptions(d))
                .setOnCancelListener(this::returnToLayoutOptions)
                .setPositiveButton(replacing ? T.dashboardReplace() : T.dashboardSave(), null)
                .create();
        dialog = created;
        created.show();
        created.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (closed || !editing) return;
            grid.finishGesture(false);
            boolean stored = config.saveSlot(index, config.slotNameToSave(name.getText().toString(), shown, saved, index),
                    preferences.getInt("dashboard_columns", 0));
            if (!stored) {
                host.toast(T.dashboardSaveLayoutFailed());
                return;
            }
            host.toast(T.dashboardSavedToSlot(Fmt.integer(index + 1)));
            layoutOptions();
        });
    }

    private void restoreLayoutSlot(int index) {
        DashboardConfig.SavedSlot saved = config.slot(index);
        // Restore is disabled for an empty or unreadable slot.
        if (saved == null) {
            layoutOptions();
            return;
        }
        dismiss();
        AlertDialog created = new AlertDialog.Builder(dialogContext)
                .setTitle(T.dashboardRestoreTitle(DashboardConfig.displaySlotName(saved, index)))
                .setMessage(T.dashboardRestoreMessage())
                .setNegativeButton(T.commonCancel(), (d, which) -> returnToLayoutOptions(d))
                .setOnCancelListener(this::returnToLayoutOptions)
                .setPositiveButton(T.dashboardRestore(), null)
                .create();
        dialog = created;
        created.show();
        created.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (closed || !editing) return;
            grid.finishGesture(false);
            if (!config.restoreSlot(index)) {
                host.toast(T.dashboardRestoreFailed());
                return;
            }
            stopRemovalEffect();
            editHistory.clear();
            clearRestoreUndo();
            dismiss();
            render();
            host.toast(T.dashboardRestored(DashboardConfig.displaySlotName(saved, index)));
        });
    }

    private void returnToLayoutOptions(android.content.DialogInterface source) {
        if (!closed && editing && dialog == source) layoutOptions();
    }

    private void show(String title, View content) {
        dismiss();
        ScrollView scroll = new ScrollView(host.activity());
        scroll.setFillViewport(false);
        int p = ui.dp(20);
        scroll.setPadding(p, 0, p, p);
        scroll.addView(content);
        dialog =
                new AlertDialog.Builder(dialogContext)
                        .setTitle(title)
                        .setView(scroll)
                        .setNegativeButton(M.commonClose(), null)
                        .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(ui.shape(ui.surface, 24, 0));
            dialog.getWindow().setLayout(-1, (int) (host.activity().getResources().getDisplayMetrics().heightPixels * .86));
        }
        Motion.present(dialog, Motion.DIALOG);
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(ui.accent);
        if (dialog.getWindow() != null)
            dialog.getWindow().setLayout(-1, (int) (host.activity().getResources().getDisplayMetrics().heightPixels * .86));
    }

    private void dismiss() {
        // A notice shown over a sheet goes with it.
        if (notice != null && dialog != null) hideNotice();
        if (dialog != null) {
            dialog.dismiss();
            dialog = null;
        }
    }

    public boolean isEditing() {
        return editing;
    }

    /** Stop decorative scrolling immediately when the user takes over anywhere on the page. */
    public void onUserTouch() {
        cancelWidgetReveal(); revealWidgetId = null;
    }

    boolean editing() { return editing; }

    /** The layout editor is open: riding mode waits. */
    @Override public boolean busy() { return editing; }

    @Override public boolean back() {
        if (editing) {
            toggleEditing();
            return true;
        }
        return false;
    }

    /**
     * A start tapped just before Home closes (a tab switch, a rebuild, the app going away) is not
     * lost: while the app stays open it runs right after the screen change, through the same
     * RideRecordingControl start and its checks, on whatever screen is then showing; when the app is going away Volt says it did not start.
     */
    private void finishPendingStart() {
        Activity activity = host.activity();
        boolean staying = !activity.isFinishing() && !activity.isDestroyed()
                && !activity.isChangingConfigurations() && !DataMaintenance.active();
        int action = RecorderTap.onClose(recorderStart.pending(), staying);
        if (action == RecorderTap.CLOSE_NOTHING || !recorderStart.take()) return;
        Context app = activity.getApplicationContext();
        if (action == RecorderTap.CLOSE_HAND_OFF) {
            Actions start = actions;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) recorderNotStarted(app);
                else start.startRide();
            });
        } else {
            recorderNotStarted(app);
        }
    }

    private static void recorderNotStarted(Context app) {
        Toast.makeText(app, T.dashboardRecorderNotStarted(), Toast.LENGTH_LONG).show();
    }

    @Override
    public void close() {
        hideNotice();
        finishPendingStart();
        closed = true;
        chargingHero.stopReveal();
        cancelWidgetReveal();
        pendingAddedWidgetId = revealWidgetId = null;
        stopRemovalEffect();
        stopEditTransition();
        editHistory.clear();
        for (Tile tile : tiles) tile.active = false;
        rememberPicker();
        dismiss();
        if (grid != null) {
            grid.finishGesture(false);
            grid.stopMotion();
        }
    }
}
