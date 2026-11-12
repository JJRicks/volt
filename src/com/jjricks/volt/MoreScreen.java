package com.jjricks.volt;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.text.style.ForegroundColorSpan;
import android.view.ActionMode;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Local preferences, owner setup, security and explained experimental controls. */
public final class MoreScreen implements AppScreen {
    private final ScreenHost host;
    private final Ui ui;
    private final ScrollView scroll;
    private final LinearLayout content;
    /** Unfolded/tablet width: the menu stays on the left and the chosen submenu opens on the right. */
    private final boolean wide;
    private final boolean standalone;
    /**
     * A Hub page shows one section as its body (hub layout plan): standalone, inside the page's own scroller, with no
     * section header (the page header has the title); a link to another section opens that section's page.
     */
    private final boolean embedded;
    /** Wide mode only: each pane scrolls on its own. */
    private ScrollView menuScroll, sectionScroll;
    private final Row[] menuRows = new Row[SECTIONS.length];
    /** Wide layout: every row in the left (menu) pane, whose longest title words Panes checks. */
    private final List<RowLine> menuPaneRows = new ArrayList<>();
    /** The window size, text size and language at which a menu word could not fit: More uses one pane there. */
    private static String onePaneAt = "";
    private String layoutKey() {
        android.content.res.Configuration c = host.activity().getResources().getConfiguration();
        return c.screenWidthDp + "x" + c.screenHeightDp + "@" + ui.textScale * c.fontScale + "/" + Fmt.displayLocale().toLanguageTag();
    }
    private LinearLayout cardDestination, onboardingCard, sectionMenu, indexView, sectionHeader;
    private View sectionBack;
    private TextView sectionTitle, sectionBackLabel;
    private static final String[] SECTIONS = {"App", "Scooters", "Security", "Lab", "Data", "About", "Developer"};
    private static final String[] SECTION_LABELS = {"App preferences", "My scooters", "PIN & proximity unlock",
            "Experimental controls", "Data & history", "About & permissions", "Developer options"};
    private static final String[] SECTION_TITLES = {"App preferences", "My scooters", "PIN & proximity",
            "Experimental controls", "Data & history", "About & permissions", "Developer options"};
    /** SECTION_LABELS as the menu shows them, from the catalog, by index (MoreTextTest checks the English). */
    private static final int[] SECTION_LABEL_IDS = {T.MORE_SECTION_APP, T.MORE_SECTION_SCOOTERS, T.MORE_SECTION_SECURITY,
            T.MORE_SECTION_LAB, T.MORE_SECTION_DATA, T.MORE_SECTION_ABOUT, T.MORE_SECTION_DEVELOPER};
    /** SECTION_TITLES as the submenu title shows them, from the catalog, by the same index (FollowupsTest checks the English
     * against SECTION_TITLES, which stays as that reference, as SECTION_LABELS does for the menu rows). */
    private static final int[] SECTION_TITLE_IDS = {T.MORE_SECTION_APP, T.MORE_SECTION_SCOOTERS, T.MORE_SECTION_SECURITY_TITLE,
            T.MORE_SECTION_LAB, T.MORE_SECTION_DATA, T.MORE_SECTION_ABOUT, T.MORE_SECTION_DEVELOPER};
    /** The Units subpage's section id (selectSection, sections). */
    private static final String UNITS_SECTION = "Units";
    /**
     * Sections only Hub pages show (hub layout plan, PR 4): charge history and charge recordings, cut out of Developer
     * options (Battery & charging › Charge history & recordings), and the issue report (Service › Issue reports).
     */
    static final String CHARGING_SECTION = "Charging", REPORTS_SECTION = "Reports";
    /** Sections that moved to their subject pages (Security, Service › Experimental controls): More's menu no longer lists them. */
    private static boolean subjectSection(String section) { return "Security".equals(section) || "Lab".equals(section); }
    private static final String[] SECTION_ICONS = {"settings", "scooter", "shield", "lab", "history", "info", "lab"};
    /** More › Developer options › Compatibility; the Scooter page's Review opens it as the recovery-only route. */
    private CompatibilityPanel compatibilityPanel;
    private boolean compatibilityRecovery;
    /** Saved more_section value for the recovery-only route; it is shown as the Compatibility section. */
    static final String COMPATIBILITY_RECOVERY = "CompatibilityRecovery";
    /** TelemetryHistory's database file; only its size on disk is read here, never its contents. */
    private static final String HISTORY_DATABASE = "telemetry-history.db";
    private final Map<String,LinearLayout> sections = new LinkedHashMap<>();
    private final Map<String,Integer> sectionScrollPositions = new LinkedHashMap<>();
    private String selectedSection;
    private boolean closed;
    private final List<Runnable> valueRefreshers = new ArrayList<>();
    private final List<SegmentedSelector> preferenceSelectors = new ArrayList<>();
    private final List<AlertDialog> dialogs = new ArrayList<>();
    private final List<Row> profileEditActions = new ArrayList<>();
    private LinearLayout scooterList;
    private TextView addScooterButton, scooterListNotice;
    private final Map<String, ScooterCard> scooterCards = new LinkedHashMap<>();
    private Row scootersIndexRow, dataIndexRow;
    private Row setPinRow, bondRow, pinRow, proximityRow;
    private View pinRefresh, pinSpinner, proximitySpinner;
    private TextView proximityPinChip, proximityPairedChip, securityConnect;
    private Row onboardingImport, onboardingNearby, onboardingConnect;
    private final List<Row> lockedProfileRows = new ArrayList<>();
    private Row nearbyRow, locationRow, notificationRow;
    private Row developerIndexRow, simulationRow, simulationModelRow, liveUpdateRow;
    /** Developer simulator models: "" is the UT5 MAX simulator; the others are synthetic advertised IDs. */
    private static final String[] SIMULATION_MODELS = {"", "24431", "39991"};
    private static final String[] SIMULATION_MODEL_NAMES = {"UT5 MAX", "Mapped model (synthetic 24431)", "Unknown model (synthetic 39991)"};
    private TextView simulationStatus, chargeCaptureStatus, chargeCurveValue, chargeCurveScope;
    private LinearLayout chargeCurve;
    private Row chargeCaptureRow, clearChargeRow, exportChargeRow, chargeHistoryRow;
    private ChargeHistoryDialog chargeHistoryDialog;
    /** Charge history & recordings: the charger row (a link to Estimates & charger) and Email to Volt. */
    private Row chargerLinkRow, sendChargeRow;
    private TextView securityNotice;
    private LinearLayout indexProfileCard;
    private ImageView indexPhoto;
    private View indexPhotoFallback;
    private TextView indexProfileName, indexProfileState;
    private TextView labConnect, labTitle, labIntro, labFoot, labNotice, historyUsage, historyStatus;
    private LinearLayout labList;
    private String labSignature = "", labModelKey = "";
    private List<String> labKeys = new ArrayList<>(), verifyKeys = new ArrayList<>();
    private LabSheet labSheet;
    private TextView[] historyCounts;
    private LinearLayout historyBar;
    private View historyBarFill, historyBarRest;
    private LinearLayout historyStorageCard;
    private boolean historyStoragePending;
    private long historyStorageChecked;
    private final List<LabRow> labRows = new ArrayList<>();
    /** An Experimental controls row; status, now and action are null for a control this scooter doesn't report. */
    private static final class LabRow {
        final ScooterCommands.Feature feature;
        final LinearLayout line; final LadderPill status; final TextView now, action;
        LabRow(ScooterCommands.Feature feature, LinearLayout line, LadderPill status, TextView now, TextView action) {
            this.feature = feature; this.line = line; this.status = status; this.now = now; this.action = action;
        }
    }

    public MoreScreen(ScreenHost host) { this(host, null); }

    /** One section on its own, without changing More's saved navigation state. */
    MoreScreen(ScreenHost host, String initialSection) { this(host, initialSection, false); }

    /** embedded: the section is a Hub page's body (see {@link #embedded}); it needs an initialSection. */
    MoreScreen(ScreenHost host, String initialSection, boolean embedded) {
        this.host = host; ui = host.ui();
        standalone = initialSection != null;
        this.embedded = embedded && standalone;
        wide = !standalone && ui.wideLayout() && !layoutKey().equals(onePaneAt);
        scroll = new PageScroll();
        scroll.setFillViewport(true); scroll.setClipToPadding(false);
        content = column();
        if (this.embedded) content.setPadding(0, 0, 0, 0);
        else if (wide) content.setPadding(ui.dp(24), ui.dp(6), ui.dp(24), 0);
        else content.setPadding(ui.dp(18), ui.dp(6), ui.dp(18), ui.dp(24));
        // A Hub page scrolls the whole page itself, so an embedded section is never put in a scroller of its own.
        if (!this.embedded) scroll.addView(content, new ScrollView.LayoutParams(-1, wide ? -1 : -2));
        String savedSection = standalone ? initialSection : settings().getString("more_section", "");
        compatibilityRecovery = COMPATIBILITY_RECOVERY.equals(savedSection);
        selectedSection = compatibilityRecovery ? "Compatibility" : savedSection;
        // A More tab saved on Security or Experimental controls opens on More's menu: both are subject pages now.
        if (!knownSection(selectedSection) || !standalone && subjectSection(selectedSection)) selectedSection = "";

        LinearLayout menuParent = content, sectionParent = content;
        if (wide) {
            menuParent = column(); sectionParent = column();
            menuScroll = paneScroll(menuParent); sectionScroll = paneScroll(sectionParent);
            content.addView(new Panes(menuScroll, sectionScroll), new LinearLayout.LayoutParams(-1, -1));
        }
        indexView = column(); menuParent.addView(indexView, new LinearLayout.LayoutParams(-1, -2));
        cardDestination = indexView;
        indexProfile();
        onboarding();
        sectionMenu = group();
        if (wide) sectionMenu.setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4));
        Row backup = row(sectionMenu, "download", T.moreBackupRestore(), null, () -> host.dataBackup(null, null));
        backup.view.setTag("data-backup");
        // The wide menu pads every row alike, so this one lines up with the section rows below it.
        if (wide) backup.view.setPadding(ui.dp(8), ui.dp(9), ui.dp(8), ui.dp(9));
        for (int i = 0; i < SECTIONS.length; i++) {
            final String section = SECTIONS[i];
            if (!knownSection(section) || subjectSection(section)) continue;
            Row row = row(sectionMenu, SECTION_ICONS[i], T.render(SECTION_LABEL_IDS[i]), null, () -> selectSection(section));
            // Room for the selected row's red tint to frame its icon in the wide menu.
            if (wide) row.view.setPadding(ui.dp(8), ui.dp(9), ui.dp(8), ui.dp(9));
            menuRows[i] = row;
            if (section.equals("Scooters")) scootersIndexRow = row;
            else if (section.equals("Data")) dataIndexRow = row;
            else if (section.equals("Lab")) badge(row.value, T.moreLabBadge());
            else if (section.equals("Developer")) {
                developerIndexRow = row;
                row.view.setTag("developer-options-menu");
            }
        }
        updates();
        TextView footer = ui.text(T.moreFooter(AppVersion.name(host.activity())), 11.5f, false);
        footer.setTextColor(ui.muted); footer.setGravity(Gravity.CENTER);
        footer.setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(8));
        indexView.addView(footer, new LinearLayout.LayoutParams(-1, -2));
        reportLink(indexView);

        sectionHeader = column(); sectionParent.addView(sectionHeader, new LinearLayout.LayoutParams(-1, -2));
        sectionBack = backLink(); sectionHeader.addView(sectionBack, new LinearLayout.LayoutParams(-2, -2));
        // The menu beside the submenu is the way back, so the wide layout has no "‹ More" link.
        if (wide) sectionBack.setVisibility(View.GONE);
        sectionTitle = ui.text("", 24, true); sectionTitle.setPadding(ui.dp(2), 0, 0, ui.dp(4));
        if (Build.VERSION.SDK_INT >= 28) sectionTitle.setAccessibilityHeading(true);
        // Picking a row on the left changes the right pane in place; announce its new title.
        if (wide) sectionTitle.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        sectionHeader.addView(sectionTitle);

        for (String section : SECTIONS) {
            if (!knownSection(section)) continue;
            cardDestination = column(); sections.put(section, cardDestination);
            sectionParent.addView(cardDestination, new LinearLayout.LayoutParams(-1, -2));
            if (section.equals("App")) preferences();
            else if (section.equals("Scooters")) profiles();
            else if (section.equals("Security")) security();
            else if (section.equals("Lab")) lab();
            else if (section.equals("Data")) dataHistory();
            else if (section.equals("Developer")) developerOptions();
            else privacy();
        }
        cardDestination = column(); sections.put("Units", cardDestination);
        sectionParent.addView(cardDestination, new LinearLayout.LayoutParams(-1, -2));
        units();
        // Each is built only for the Hub page that shows it, so its rows' tags stay unique (not on Security or Lab).
        if (CHARGING_SECTION.equals(initialSection)) {
            cardDestination = column(); sections.put(CHARGING_SECTION, cardDestination);
            sectionParent.addView(cardDestination, new LinearLayout.LayoutParams(-1, -2));
            charging();
        }
        if (REPORTS_SECTION.equals(initialSection)) {
            cardDestination = column(); sections.put(REPORTS_SECTION, cardDestination);
            sectionParent.addView(cardDestination, new LinearLayout.LayoutParams(-1, -2));
            reportLink(cardDestination);
        }
        if (knownSection("Compatibility")) {
            cardDestination = column(); sections.put("Compatibility", cardDestination);
            sectionParent.addView(cardDestination, new LinearLayout.LayoutParams(-1, -2));
            compatibilityPanel = new CompatibilityPanel(host, cardDestination, compatibilityRecovery);
        }
        cardDestination = content;
        showSelectedSection();
        refresh();
        restoreWideScroll();
    }
    @Override public View view() { return embedded ? content : scroll; }
    @Override public void close() {
        saveWideScroll();
        closed = true;
        if (chargeHistoryDialog != null) chargeHistoryDialog.close();
        for (SegmentedSelector selector : preferenceSelectors) selector.finishMotion();
        for (AlertDialog dialog : new ArrayList<>(dialogs)) if (dialog.isShowing()) dialog.dismiss();
        dialogs.clear();
    }

    /**
     * Wide mode: MainActivity only remembers the outer page scroller, which stays put while the two
     * panes scroll. Keep the panes' positions across a brief tab return or rebuild, including the
     * default App preferences pane shown with no submenu chosen. MainActivity drops the saved
     * positions when the submenu expires or a shortcut opens one, so nothing stale is restored.
     */
    private static final String WIDE_SECTION_SCROLL = "more_wide_section_scroll", WIDE_MENU_SCROLL = "more_wide_menu_scroll";
    private void saveWideScroll() {
        if (!wide || closed || service() == null || sectionScroll == null || menuScroll == null) return;
        settings().edit().putString(WIDE_SECTION_SCROLL, shownSection() + ":" + sectionScroll.getScrollY())
                .putInt(WIDE_MENU_SCROLL, menuScroll.getScrollY()).apply();
    }
    private void restoreWideScroll() {
        if (!wide || service() == null || sectionScroll == null || menuScroll == null) return;
        SharedPreferences prefs = settings();
        String saved = prefs.getString(WIDE_SECTION_SCROLL, "");
        int split = saved.lastIndexOf(':');
        final int section = split > 0 && saved.substring(0, split).equals(shownSection()) ? parseScroll(saved.substring(split + 1)) : 0;
        final int menu = prefs.getInt(WIDE_MENU_SCROLL, 0);
        Motion.scrollBeforeDraw(sectionScroll, section); Motion.scrollBeforeDraw(menuScroll, menu);
    }
    /**
     * The submenu expired while this screen stayed alive (the app was in the background for more
     * than a minute). Put the live panes back at the top so neither this screen nor the close()
     * of an expiry rebuild carries the old offsets forward.
     */
    public void resetScroll() {
        sectionScrollPositions.clear();
        if (sectionScroll != null) sectionScroll.scrollTo(0, 0);
        if (menuScroll != null) menuScroll.scrollTo(0, 0);
    }
    private static int parseScroll(String value) {
        try { return Math.max(0, Integer.parseInt(value)); } catch (NumberFormatException e) { return 0; }
    }

    /** Units returns to its parent; otherwise wide Back leaves the tab, keeping its selected pane. */
    @Override public boolean back() {
        // A Hub page's Back is the page stack's.
        if (embedded) return false;
        if ("Units".equals(selectedSection)) { selectSection("App"); return true; }
        if ("Compatibility".equals(selectedSection) && !standalone) {
            selectSection(compatibilityRecovery ? "" : "Developer"); return true;
        }
        if (standalone || wide || selectedSection.isEmpty()) return false;
        selectSection(""); return true;
    }

    /** More holds Volt's setup flows, PIN forms, Lab tries and text fields: riding mode never opens over it by itself. */
    @Override public boolean busy() { return true; }

    private boolean knownSection(String section) {
        if ("Units".equals(section) || "Compatibility".equals(section)) return true;
        if (standalone && (CHARGING_SECTION.equals(section) || REPORTS_SECTION.equals(section))) return true;
        for (String candidate : SECTIONS) if (candidate.equals(section)) return true;
        return false;
    }
    /**
     * The submenu on screen. Wide mode always shows one: with none chosen (first visit, or one expired
     * after a minute away) it opens App preferences without storing it, so the retention rules are unchanged.
     */
    private String shownSection() { return wide && selectedSection.isEmpty() ? SECTIONS[0] : selectedSection; }
    private ScrollView sectionScroller() { return wide ? sectionScroll : scroll; }
    private void showSelectedSection() {
        String shown = shownSection();
        boolean index = shown.isEmpty();
        indexView.setVisibility(index || wide ? View.VISIBLE : View.GONE);
        boolean units = "Units".equals(shown);
        boolean compatibility = "Compatibility".equals(shown);
        sectionHeader.setVisibility(index || embedded || (standalone && !units) ? View.GONE : View.VISIBLE);
        sectionBack.setVisibility(units || compatibility || !wide ? View.VISIBLE : View.GONE);
        String parent = units ? T.moreSectionApp() : compatibility && !compatibilityRecovery ? T.moreSectionDeveloper() : T.moreTitle();
        sectionBackLabel.setText(parent);
        sectionBack.setContentDescription(T.moreBackTo(parent));
        if (units) sectionTitle.setText(T.moreUnitsTitle());
        if (compatibility) sectionTitle.setText(compatibilityRecovery ? T.moreUnfinishedTestTitle() : T.moreCompatibilityTitle());
        for (int i = 0; i < SECTIONS.length; i++) if (SECTIONS[i].equals(shown)) sectionTitle.setText(T.render(SECTION_TITLE_IDS[i]));
        for (Map.Entry<String,LinearLayout> section : sections.entrySet())
            section.getValue().setVisibility(section.getKey().equals(shown) ? View.VISIBLE : View.GONE);
        if (index || wide) dataIndexRow.value(historyFileSize());
        if (wide) for (int i = 0; i < SECTIONS.length; i++)
            if (menuRows[i] != null) markMenuRow(menuRows[i], SECTIONS[i].equals(units ? "App" : compatibility ? "Developer" : shown));
    }
    /** Wide menu: the row whose submenu is open on the right gets a red tint and the selected state. */
    private void markMenuRow(Row row, boolean selected) {
        row.view.setSelected(selected);
        row.view.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(ui.accent, 26)),
                selected ? ui.shape(Ui.withAlpha(ui.accent, ui.dark ? 46 : 30), 14, 0) : null, ui.shape(Color.WHITE, 14, 0)));
    }
    private void selectSection(String section) {
        if (closed || section.equals(selectedSection)) return;
        if (embedded) {
            // Another section is another Hub page; the compatibility check keeps its recovery-only view as a page argument.
            String page = HubPages.fromMoreSection(section);
            if (page.equals(HubPages.SERVICE_COMPATIBILITY)) host.open(page, compatibilityRecovery ? HubNav.Args.recovery() : null);
            else host.open(page);
            return;
        }
        if (!section.isEmpty() && !knownSection(section)) return;
        final ScrollView pane = sectionScroller();
        String leaving = shownSection();
        sectionScrollPositions.put(leaving, pane.getScrollY());
        Motion.page(pane, sectionDirection(leaving, section), () -> {
            selectedSection = section;
            // The recovery-only route is saved as its own value, so a rebuild comes back to it.
            String saved = "Compatibility".equals(section) && compatibilityRecovery ? COMPATIBILITY_RECOVERY : section;
            if (!standalone) settings().edit().putString("more_section", saved).apply();
            showSelectedSection(); refresh();
            String shown = shownSection();
            Motion.scrollBeforeDraw(pane, sectionScrollPositions.containsKey(shown) ? sectionScrollPositions.get(shown) : 0);
        });
    }
    /** Deeper levels arrive from the right and leave the same way back; wide panes simply crossfade. */
    private int sectionDirection(String from, String to) {
        if ("Units".equals(to) || "Compatibility".equals(to)) return 1;
        if ("Units".equals(from) || "Compatibility".equals(from)) return -1;
        if (wide) return 0;
        return to.isEmpty() ? -1 : 1;
    }

    private ScooterService service() { return host.service(); }
    private ScooterProfiles.Profile active() { return service() == null ? null : service().profiles.active(); }
    private SharedPreferences settings() { return service().settings; }
    private LinearLayout column() {
        LinearLayout view = new LinearLayout(host.activity()); view.setOrientation(LinearLayout.VERTICAL); return view;
    }
    private LinearLayout row() {
        LinearLayout view = new LinearLayout(host.activity()); view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL); return view;
    }

    // ---- Wide (unfolded) layout ------------------------------------------------------------

    /**
     * The page's scroller. On a phone it scrolls the whole page as before. In wide mode it holds its
     * content at exactly its own height so the two panes inside can each scroll on their own.
     */
    private final class PageScroll extends ScrollView {
        PageScroll() { super(host.activity()); }
        @Override protected void measureChildWithMargins(View child, int widthSpec, int widthUsed, int heightSpec, int heightUsed) {
            if (!wide || MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED) {
                super.measureChildWithMargins(child, widthSpec, widthUsed, heightSpec, heightUsed); return;
            }
            MarginLayoutParams lp = (MarginLayoutParams) child.getLayoutParams();
            int childWidth = getChildMeasureSpec(widthSpec,
                    getPaddingLeft() + getPaddingRight() + lp.leftMargin + lp.rightMargin + widthUsed, lp.width);
            int height = Math.max(0, MeasureSpec.getSize(heightSpec) - getPaddingTop() - getPaddingBottom()
                    - lp.topMargin - lp.bottomMargin - heightUsed);
            child.measure(childWidth, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        }
    }
    private ScrollView paneScroll(LinearLayout column) {
        ScrollView pane = new ScrollView(host.activity());
        pane.setClipToPadding(false); pane.setPadding(0, 0, 0, ui.dp(24));
        pane.addView(column, new ScrollView.LayoutParams(-1, -2));
        return pane;
    }
    /**
     * Menu on the left, submenu on the right, 20dp apart. The gap sits on the window's centre line, where
     * a book-posture hinge falls, so no row lies under the fold; this also gives the menu the narrower
     * column of the approved mockup. Each pane keeps a usable minimum width.
     * The narrow-label ladder keeps menu titles whole without moving that gap: when a row's longest title
     * word cannot fit beside its icon, the decorative icon steps aside (RowLine); when it still cannot fit,
     * More takes its one-pane (phone) layout at this window size, text size and language (onePaneAt).
     */
    private final class Panes extends LinearLayout {
        final View first, gutter, second;
        int split = -1;
        private boolean onePaneAsked;
        Panes(View left, View right) {
            super(host.activity()); setOrientation(HORIZONTAL); setBaselineAligned(false);
            first = left; second = right; gutter = new View(host.activity());
            addView(first, new LinearLayout.LayoutParams(0, -1));
            addView(gutter, new LinearLayout.LayoutParams(ui.dp(20), -1));
            addView(second, new LinearLayout.LayoutParams(0, -1));
        }
        private int split(int size) {
            int gap = ui.dp(20), min = ui.dp(220), max = size - gap - ui.dp(260);
            if (max < min) return Math.max(0, (size - gap) / 2);
            int want = Math.round((size - gap) * 0.42f);
            View window = getRootView();
            if (isAttachedToWindow() && window != null && window.getWidth() > 0)
                // Layout position, not on-screen position: a tab transition slides this page in,
                // and measuring mid-slide used to leave the menu narrow until the next layout.
                want = window.getWidth() / 2 - gap / 2 - Ui.layoutLeftInWindow(this);
            return Math.max(min, Math.min(max, want));
        }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int size = MeasureSpec.getSize(widthSpec), gap = ui.dp(20);
            split = split(size);
            ((LinearLayout.LayoutParams) first.getLayoutParams()).width = split;
            ((LinearLayout.LayoutParams) second.getLayoutParams()).width = Math.max(0, size - split - gap);
            super.onMeasure(widthSpec, heightSpec);
        }
        /**
         * The ladder's last rung: a menu title word too wide for its row even without the icon. Remember this
         * window size, text size and language and rebuild once with More in one pane. The phone layout never
         * checks again, so the two layouts can't alternate; another size, text size or language tries two panes.
         */
        private void onePaneIfWordsBreak() {
            if (onePaneAsked || closed) return;
            for (RowLine line : menuPaneRows) {
                if (!shownWithin(line, this) || line.getMeasuredWidth() == 0) continue;
                if (line.longestWord() <= line.titleRoomAtMost()) continue;
                onePaneAsked = true; onePaneAt = layoutKey();
                post(() -> { if (!closed) host.rebuild(); });
                return;
            }
        }
        /** Visible all the way up to root, so this measure pass sized it. */
        private boolean shownWithin(View view, View root) {
            for (View v = view; v != null && v != root; v = v.getParent() instanceof View ? (View) v.getParent() : null)
                if (v.getVisibility() != VISIBLE) return false;
            return true;
        }
        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            // The window position is final only now. Re-split before this frame draws rather than
            // posting another layout, so the first frame already shows the resting widths.
            int width = r - l, wanted = split(width);
            if (wanted != split) {
                split = wanted;
                int gap = ui.dp(20), height = MeasureSpec.makeMeasureSpec(b - t, MeasureSpec.EXACTLY);
                ((LinearLayout.LayoutParams) first.getLayoutParams()).width = split;
                ((LinearLayout.LayoutParams) second.getLayoutParams()).width = Math.max(0, width - split - gap);
                first.measure(MeasureSpec.makeMeasureSpec(split, MeasureSpec.EXACTLY), height);
                second.measure(MeasureSpec.makeMeasureSpec(Math.max(0, width - split - gap), MeasureSpec.EXACTLY), height);
            }
            super.onLayout(changed, l, t, r, b);
            onePaneIfWordsBreak();
        }
    }

    // ---- Volt building blocks -------------------------------------------------------------

    /** Small red "‹ More" link above a submenu title. */
    private View backLink() {
        LinearLayout link = row();
        link.setMinimumHeight(ui.dp(48)); link.setPadding(0, 0, ui.dp(14), 0);
        link.addView(ui.icon("back", 16, ui.accent), new LinearLayout.LayoutParams(ui.dp(16), ui.dp(16)));
        TextView label = sectionBackLabel = ui.text(T.moreTitle(), 14, false); label.setTypeface(ui.medium()); label.setTextColor(ui.accent);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.leftMargin = ui.dp(4);
        link.addView(label, lp);
        link.setContentDescription(T.moreBackToMore()); link.setFocusable(true);
        link.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(ui.accent, 30)), null, ui.shape(Color.WHITE, 10, 0)));
        link.setOnClickListener(v -> selectSection("Units".equals(selectedSection) ? "App"
                : "Compatibility".equals(selectedSection) && !compatibilityRecovery ? "Developer" : ""));
        return link;
    }
    /** Group label; keeps natural case in its text so screen readers say words, not letters. */
    private TextView label(String text) {
        TextView view = ui.sectionLabel(text); view.setText(text); view.setAllCaps(true);
        cardDestination.addView(view); return view;
    }
    private LinearLayout group() { LinearLayout g = ui.groupCard(); cardDestination.addView(g); return g; }
    private TextView footnote(String value) {
        TextView text = ui.caption(value); text.setPadding(ui.dp(4), 0, ui.dp(4), ui.dp(12));
        cardDestination.addView(text); return text;
    }
    private void badge(TextView value, String text) {
        value.setText(text); value.setVisibility(View.VISIBLE);
        value.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10.5f * ui.textScale);
        value.setTypeface(ui.bold()); value.setLetterSpacing(0.08f); value.setTextColor(ui.orange);
        value.setPadding(ui.dp(7), ui.dp(2), ui.dp(7), ui.dp(2)); value.setMaxLines(1);
        value.setBackground(ui.shape(Color.TRANSPARENT, 6, Ui.withAlpha(ui.orange, 120)));
        value.setContentDescription(T.moreLabBadgeDescription());
    }

    /**
     * Boxed row glyph: red when actionable, danger-red for destructive rows, grey with its disabled row.
     * A step row shows its number instead: green once done, red for the next step, grey after that.
     */
    private final class RowIcon extends View {
        final String key; boolean danger;
        String step; int stepState;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        RowIcon(String key) {
            super(host.activity()); this.key = key;
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        void step(int state) { if (state != stepState) { stepState = state; invalidate(); } }
        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            paint.setStyle(Paint.Style.FILL); paint.setColor(ui.dark ? ui.bg : ui.raised);
            canvas.drawRoundRect(new RectF(0, 0, w, h), ui.dp(10), ui.dp(10), paint);
            if (step == null) {
                ui.drawIcon(canvas, key, !isEnabled() ? ui.dim : danger ? ui.danger : ui.accent, w / 2, h / 2, Math.min(w, h) * .54f);
                return;
            }
            float r = Math.min(w, h) * .34f;
            int fill = stepState == 2 ? ui.ok : stepState == 1 ? ui.accent : Color.TRANSPARENT;
            paint.setColor(fill); canvas.drawCircle(w / 2, h / 2, r, paint);
            if (stepState == 0) {
                paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(ui.dp(1.2f)); paint.setColor(ui.dim);
                canvas.drawCircle(w / 2, h / 2, r, paint); paint.setStyle(Paint.Style.FILL);
            }
            paint.setColor(stepState == 0 ? ui.muted : ui.onAccent); paint.setTypeface(ui.bold());
            paint.setTextAlign(Paint.Align.CENTER); paint.setTextSize(r * 1.05f);
            canvas.drawText(step, w / 2, h / 2 - (paint.descent() + paint.ascent()) / 2, paint);
        }
    }

    /**
     * A row's line. The narrow-label ladder's decorative rung: when the title's longest word (of its last,
     * shortest rung when it has a ladder) cannot fit beside the boxed icon, the icon steps aside so the word
     * never breaks mid-way; it returns once the word fits beside it. A numbered step icon carries meaning
     * and always stays. The title's text, and so its spoken text, is unchanged.
     */
    private final class RowLine extends LinearLayout {
        final Row row;
        /** The title's last ladder rung, the text that must fit whole; null when the title has no ladder. */
        CharSequence fallback;
        private boolean iconAside;
        RowLine(Row row) {
            super(host.activity()); this.row = row;
            setOrientation(HORIZONTAL); setGravity(Gravity.CENTER_VERTICAL);
        }
        /** The icon's box and its gap to the title. */
        private int iconRoom() { return ui.dp(34) + ui.dp(12); }
        /** Width of the widest piece of the title a line break may not split, in px; 0 for no title. */
        int longestWord() {
            if (row.title == null) return 0;
            CharSequence text = fallback != null ? fallback : row.title.getText();
            return longestUnbreakable(text, row.title.getPaint());
        }
        /** Room the title can get at most: without the icon, unless it is a numbered step that always stays. */
        int titleRoomAtMost() {
            return titleRoomWithIcon() + (row.icon != null && row.icon.step == null ? iconRoom() : 0);
        }
        /** Room the title gets with the icon beside it, from the last measure. */
        int titleRoomWithIcon() {
            View words = (View) row.title.getParent();
            int room = words.getMeasuredWidth() - row.title.getCompoundPaddingLeft() - row.title.getCompoundPaddingRight();
            return iconAside ? room - iconRoom() : room;
        }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            super.onMeasure(widthSpec, heightSpec);
            if (row.icon == null || row.icon.step != null || row.title == null) return;
            boolean aside = longestWord() > titleRoomWithIcon();
            if (aside == iconAside) return;
            iconAside = aside;
            // Set the icon's size in place, as Panes does: no layout request from inside a measure pass.
            LayoutParams lp = (LayoutParams) row.icon.getLayoutParams();
            lp.width = lp.height = aside ? 0 : ui.dp(34); lp.rightMargin = aside ? 0 : ui.dp(12);
            super.onMeasure(widthSpec, heightSpec);
        }
    }
    /** The widest run of text a line may not break inside (a word, with any glued punctuation), in px. */
    private static int longestUnbreakable(CharSequence text, android.text.TextPaint paint) {
        if (text == null || text.length() == 0) return 0;
        String s = text.toString();
        java.text.BreakIterator breaks = java.text.BreakIterator.getLineInstance(Fmt.displayLocale());
        breaks.setText(s);
        float widest = 0;
        for (int start = breaks.first(), end = breaks.next(); end != java.text.BreakIterator.DONE; start = end, end = breaks.next()) {
            int stop = end;
            while (stop > start && Character.isWhitespace(s.charAt(stop - 1))) stop--;
            if (stop > start) widest = Math.max(widest, paint.measureText(s, start, stop));
        }
        return (int) Math.ceil(widest) + 1;
    }

    /** One divided row in a group card: boxed icon, title, optional sub-text, trailing value and chevron. */
    private final class Row {
        LinearLayout view; RowIcon icon; TextView title, sub, value; View chevron, lock; Switch toggle; boolean danger;
        void value(String text) {
            value.setText(text == null ? "" : text);
            value.setVisibility(text == null || text.isEmpty() ? View.GONE : View.VISIBLE);
        }
        void sub(CharSequence text) {
            sub.setText(text == null ? "" : text);
            sub.setVisibility(text == null || text.length() == 0 ? View.GONE : View.VISIBLE);
        }
        void enabled(boolean on) {
            view.setEnabled(on);
            if (icon != null) { icon.setEnabled(on); icon.invalidate(); }
            title.setTextColor(on ? (danger ? ui.danger : ui.text) : ui.muted);
            if (chevron != null) chevron.setAlpha(on ? 1f : .4f);
        }
        /** A padlock replaces the chevron while profile changes are blocked by the connection or a ride. */
        void locked(boolean on) {
            if (lock != null) lock.setVisibility(on ? View.VISIBLE : View.GONE);
            if (chevron != null) chevron.setVisibility(on ? View.GONE : View.VISIBLE);
        }
    }
    private Row lockable(Row r) {
        r.lock = ui.icon("lock", 15, ui.dim);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ui.dp(15), ui.dp(15)); lp.leftMargin = ui.dp(8);
        r.view.addView(r.lock, lp); r.lock.setVisibility(View.GONE);
        lockedProfileRows.add(r); return r;
    }
    /** Small trailing pill: a step number, a check or a short state such as "PIN ✓". */
    private void pill(TextView value, String text, int color, boolean filled) {
        value.setText(text); value.setVisibility(View.VISIBLE);
        value.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11.5f * ui.textScale);
        value.setTypeface(ui.semibold()); value.setMaxLines(1);
        value.setTextColor(filled ? color : ui.muted);
        value.setPadding(ui.dp(8), ui.dp(2), ui.dp(8), ui.dp(2));
        value.setBackground(ui.shape(filled ? Ui.withAlpha(color, ui.dark ? 46 : 34) : ui.raised, 100, 0));
    }
    /** row() with catalog messages, rendered now: a field may keep the Row (its views), never the text (R2). */
    private Row row(LinearLayout group, String iconKey, Msg title, Msg sub, Runnable action) {
        return row(group, iconKey, title.render(), sub == null ? null : sub.render(), action);
    }
    private Row row(LinearLayout group, String iconKey, String title, String sub, Runnable action) {
        if (group.getChildCount() > 0) ui.divider(group);
        Row r = new Row();
        RowLine line = new RowLine(r);
        r.view = line; r.view.setMinimumHeight(ui.dp(58)); r.view.setPadding(0, ui.dp(9), 0, ui.dp(9));
        if (wide && cardDestination == indexView) menuPaneRows.add(line);
        if (iconKey != null) {
            r.icon = new RowIcon(iconKey);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(ui.dp(34), ui.dp(34)); ip.rightMargin = ui.dp(12);
            r.view.addView(r.icon, ip);
        }
        LinearLayout words = column();
        r.title = ui.text(title, 15, false); words.addView(r.title);
        r.sub = ui.text("", 11.5f, false); r.sub.setTextColor(ui.muted); r.sub.setPadding(0, ui.dp(3), 0, 0);
        words.addView(r.sub); r.sub(sub);
        r.view.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        r.value = ui.text("", 13.5f, false); r.value.setTextColor(ui.muted); r.value.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        r.value.setMaxLines(2); r.value.setMaxWidth(ui.dp(140));
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(-2, -2); vp.leftMargin = ui.dp(10);
        r.view.addView(r.value, vp); r.value(null);
        if (action != null) {
            r.chevron = ui.icon("chevron", 15, ui.muted);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ui.dp(15), ui.dp(15)); cp.leftMargin = ui.dp(8);
            r.view.addView(r.chevron, cp);
            clickable(r.view, action);
        }
        group.addView(r.view, new LinearLayout.LayoutParams(-1, -2));
        return r;
    }
    private void clickable(View view, Runnable action) {
        view.setFocusable(true);
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(ui.accent, 26)), null, ui.shape(Color.WHITE, 10, 0)));
        view.setOnClickListener(v -> { if (!closed && action != null) action.run(); });
    }
    /** Destructive row: red label, red glyph, no chevron; it always opens a confirmation first. */
    private Row dangerRow(LinearLayout group, String title, Runnable action) {
        Row r = row(group, "trash", title, null, null);
        r.danger = true; r.icon.danger = true; r.title.setTextColor(ui.danger);
        clickable(r.view, action);
        return r;
    }
    /** Only a performed click dispatches a change; programmatic setChecked never sends commands. */
    private Row toggleRow(LinearLayout group, String iconKey, String title, String sub, Consumer<Boolean> onChange) {
        Row r = row(group, iconKey, title, sub, null);
        Switch toggle = new Haptics.FeedbackSwitch(host.activity());
        toggle.setShowText(false); toggle.setTextOn(""); toggle.setTextOff("");
        toggle.setMinimumWidth(ui.dp(56)); toggle.setMinimumHeight(ui.dp(48));
        ui.styleSwitch(toggle); toggle.setEnabled(false);
        toggle.setContentDescription(title);
        toggle.setOnClickListener(v -> { if (toggle.isEnabled() && onChange != null) onChange.accept(toggle.isChecked()); });
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2); tp.leftMargin = ui.dp(12);
        r.view.addView(toggle, tp); r.toggle = toggle;
        r.view.setOnClickListener(v -> { if (toggle.isEnabled()) toggle.performClick(); });
        r.view.setFocusable(false);
        return r;
    }
    /** The custom On/Off state TalkBack reads; a Switch doesn't replace one by itself when it changes. */
    private static void describeSwitch(Switch toggle, Boolean state) {
        if (Build.VERSION.SDK_INT >= 30) toggle.setStateDescription(state == null ? T.commonNotReported() : state ? T.commonOn() : T.commonOff());
    }
    private void refreshSwitch(Row row, Boolean state, boolean enabled) {
        Switch toggle = row.toggle;
        toggle.setChecked(Boolean.TRUE.equals(state));
        toggle.setEnabled(enabled && state != null);
        describeSwitch(toggle, state);
        boolean live = enabled && state != null;
        row.title.setTextColor(live ? ui.text : ui.muted);
        if (row.icon != null) { row.icon.setEnabled(live); row.icon.invalidate(); }
        toggle.setContentDescription(state == null ? T.moreToggleNotReported(row.title.getText()) : T.morePlain(row.title.getText()));
    }
    /** Wrapping row of small views (a status pill and its value): 8dp apart, 4dp between lines, centred on each line. */
    private final class Flow extends ViewGroup {
        Flow() { super(host.activity()); }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec), available = Math.max(1, width - getPaddingLeft() - getPaddingRight());
            int x = 0, y = getPaddingTop(), rowHeight = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (child.getVisibility() == GONE) continue;
                child.measure(MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                if (x > 0 && x + child.getMeasuredWidth() > available) { y += rowHeight + ui.dp(4); x = 0; rowHeight = 0; }
                x += child.getMeasuredWidth() + ui.dp(8); rowHeight = Math.max(rowHeight, child.getMeasuredHeight());
            }
            setMeasuredDimension(resolveSize(width, widthSpec), resolveSize(y + rowHeight + getPaddingBottom(), heightSpec));
        }
        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int available = getWidth() - getPaddingLeft() - getPaddingRight(), y = getPaddingTop(), start = 0;
            while (start < getChildCount()) {
                // One line: as many children as fit, then each centred on the line's height.
                int end = start, x = 0, height = 0;
                for (; end < getChildCount(); end++) {
                    View child = getChildAt(end); if (child.getVisibility() == GONE) continue;
                    if (x > 0 && x + child.getMeasuredWidth() > available) break;
                    x += child.getMeasuredWidth() + ui.dp(8); height = Math.max(height, child.getMeasuredHeight());
                }
                if (end == start) end++;
                x = 0;
                for (int i = start; i < end; i++) {
                    View child = getChildAt(i); if (child.getVisibility() == GONE) continue;
                    int w = child.getMeasuredWidth(), h = child.getMeasuredHeight(), top = y + (height - h) / 2;
                    child.layout(getPaddingLeft() + x, top, getPaddingLeft() + x + w, top + h);
                    x += w + ui.dp(8);
                }
                y += height + ui.dp(4); start = end;
            }
        }
    }
    private void styleChip(TextView chip, boolean selected) {
        ui.styleChip(chip, selected);
        Drawable pill = chip.getBackground();
        chip.setBackground(new InsetDrawable(pill, 0, ui.dp(5), 0, ui.dp(5)));
    }
    /** An empty photo slot; ScooterPhotos.show fills it with the saved model's photo on refresh. */
    private ImageView photo() {
        ImageView view = new ImageView(host.activity());
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return view;
    }
    private String historyFileSize() {
        try {
            File base = host.activity().getDatabasePath(HISTORY_DATABASE); long total = 0;
            for (String suffix : new String[] {"", "-journal", "-wal", "-shm"}) {
                File file = new File(base.getPath() + suffix); if (file.isFile()) total += file.length();
            }
            return mib(total);
        } catch (RuntimeException unavailable) { return ""; }
    }
    private static String mib(long bytes) {
        return bytes <= 0 ? "" : T.fmtQuantity(Fmt.decimal(Locale.US, bytes / 1048576.0, 1), T.moreUnitMib());
    }

    // ---- Index --------------------------------------------------------------------------

    private void indexProfile() {
        LinearLayout c = ui.card(); c.setOrientation(LinearLayout.HORIZONTAL); c.setGravity(Gravity.CENTER_VERTICAL);
        c.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12)); c.setMinimumHeight(ui.dp(80));
        indexPhoto = photo();
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(ui.dp(58), ui.dp(64)); pp.rightMargin = ui.dp(12);
        c.addView(indexPhoto, pp);
        indexPhotoFallback = new RowIcon("scooter");
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)); fp.rightMargin = ui.dp(14);
        c.addView(indexPhotoFallback, fp);
        LinearLayout words = column();
        indexProfileName = ui.text("", 19, true); indexProfileName.setMaxLines(2); words.addView(indexProfileName);
        indexProfileState = ui.text("", 13, false); indexProfileState.setTextColor(ui.muted);
        indexProfileState.setPadding(0, ui.dp(3), 0, 0); words.addView(indexProfileState);
        c.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        View chevron = ui.icon("chevron", 16, ui.text);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ui.dp(16), ui.dp(16)); cp.leftMargin = ui.dp(10);
        c.addView(chevron, cp);
        c.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(ui.accent, 26)), ui.shape(ui.surface, 20, 0), null));
        c.setFocusable(true); c.setOnClickListener(v -> selectSection("Scooters"));
        indexProfileCard = c; cardDestination.addView(c);
    }

    private void onboarding() {
        LinearLayout c = ui.card();
        TextView title = ui.text(T.moreOnboardingTitle(), 19, true); title.setPadding(0, 0, 0, ui.dp(4)); c.addView(title);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        onboardingCard = c; cardDestination.addView(c);
        // The setup checklist: each step shows its number until it is done, then a check.
        LinearLayout actions = ui.groupCard(); actions.setBackground(null); actions.setPadding(0, 0, 0, 0);
        c.addView(actions);
        onboardingImport = row(actions, "download", T.moreOnboardingImport(), null, () -> { if (canEditProfiles()) host.importOwner(); });
        profileEditActions.add(onboardingImport);
        onboardingNearby = row(actions, "bluetooth", M.moreOnboardingNearby(), null, host::requestBluetooth);
        onboardingConnect = row(actions, "scooter", T.moreConnect(), null, () -> {
            ScooterProfiles.Profile p = active();
            if (p == null || !p.configured()) { host.toast(T.moreOnboardingImportFirst()); return; }
            host.connect();
        });
        row(actions, "info", T.moreOnboardingGuide(), null, () -> { OnboardingScreen.restart(host.activity()); host.rebuild(); });
    }
    private void checklistStep(Row row, int number, boolean done) {
        pill(row.value, done ? "✓" : String.valueOf(number), done ? ui.ok : ui.muted, done);
        row.value.setContentDescription(done ? T.commonDone() : T.moreStep(number));
    }

    // ---- Shared dialog pieces -----------------------------------------------------------

    private void note(LinearLayout box, String value) {
        TextView text = ui.caption(value); text.setPadding(0, ui.dp(7), 0, ui.dp(7)); box.addView(text);
    }
    private TextView value(LinearLayout box, String label) {
        TextView text = ui.text(label, 15, true); text.setTextColor(ui.accent);
        text.setPadding(0, ui.dp(10), 0, ui.dp(10)); box.addView(text); return text;
    }

    private boolean canEditProfiles() {
        ScooterService s = service();
        if (s == null) { host.toast(T.moreServiceStarting()); return false; }
        String unavailable = s.scooterChangeUnavailable();
        if (unavailable != null) { host.toast(unavailable); return false; }
        if (s.ble.state() != BleController.State.DISCONNECTED || s.ble.pending() != null) {
            host.toast(T.moreDisconnectFirst()); return false;
        }
        return true;
    }

    private EditText input(String hint, int type, boolean multiline) {
        EditText edit = new EditText(host.activity()); edit.setHint(hint); edit.setTextColor(ui.text);
        edit.setHintTextColor(ui.muted); edit.setTextSize(15 * ui.textScale); edit.setInputType(type);
        edit.setSingleLine(!multiline); edit.setMinHeight(ui.dp(52));
        edit.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
        edit.setBackground(ui.shape(ui.raised, 13, ui.outline));
        edit.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = ui.dp(8); params.bottomMargin = ui.dp(8); edit.setLayoutParams(params); return edit;
    }
    private LinearLayout dialogBox(String title, String detail) {
        LinearLayout box = column(); box.setPadding(ui.dp(22), ui.dp(22), ui.dp(22), ui.dp(10));
        TextView heading = ui.text(title, 22, true);
        if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        box.addView(heading);
        if (detail != null && !detail.isEmpty()) note(box, detail);
        else heading.setPadding(0, 0, 0, ui.dp(6));
        return box;
    }
    /** Two equal buttons at the foot of a custom dialog: a quiet one and the red main action. */
    private TextView[] dialogButtons(LinearLayout box, String quiet, Runnable quietAction, String main, Runnable mainAction) {
        LinearLayout buttons = row(); buttons.setPadding(0, ui.dp(10), 0, ui.dp(12));
        TextView left = ui.secondaryButton(quiet, quietAction), right = ui.button(main, mainAction);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1); lp.rightMargin = ui.dp(6);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, -2, 1); rp.leftMargin = ui.dp(6);
        buttons.addView(left, lp); buttons.addView(right, rp); box.addView(buttons);
        return new TextView[] {left, right};
    }
    /** Destructive confirmation: short title, the subject named in the body, "Cancel" and a red action. */
    private void confirm(String title, String body, String action, Runnable onConfirm) {
        LinearLayout box = dialogBox(title, body);
        AlertDialog dialog = new AlertDialog.Builder(host.activity()).setView(box).create();
        dialogButtons(box, T.commonCancel(), dialog::dismiss, action, () -> { dialog.dismiss(); onConfirm.run(); });
        show(dialog);
    }
    private void show(AlertDialog dialog) {
        show(dialog, null);
    }
    private void show(AlertDialog dialog, Runnable onDismiss) {
        dialogs.add(dialog);
        dialog.setOnDismissListener(d -> {
            dialogs.remove(dialog);
            if (onDismiss != null) onDismiss.run();
        });
        if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(ui.shape(ui.surface, 22, ui.outline));
        Motion.present(dialog, Motion.DIALOG);
        dialog.show();
        if (dialog.getButton(AlertDialog.BUTTON_POSITIVE) != null) dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(ui.accent);
        if (dialog.getButton(AlertDialog.BUTTON_NEGATIVE) != null) dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(ui.muted);
    }

    // ---- My scooters --------------------------------------------------------------------

    private void profiles() {
        addScooterButton = ui.secondaryButton(T.moreScootersAdd(), this::addScooter);
        ui.decorate(addScooterButton, "plus");
        addScooterButton.setMinHeight(ui.dp(56));
        addScooterButton.setTag("scooters-add");
        LinearLayout.LayoutParams add = new LinearLayout.LayoutParams(-1, -2);
        add.bottomMargin = ui.dp(18);
        cardDestination.addView(addScooterButton, add);
        scooterListNotice = ui.caption("");
        scooterListNotice.setPadding(ui.dp(2), 0, ui.dp(2), ui.dp(12));
        scooterListNotice.setTag("scooters-notice");
        cardDestination.addView(scooterListNotice);
        scooterList = column(); scooterList.setTag("scooters-list");
        cardDestination.addView(scooterList);
    }

    private final class ScooterCard {
        final LinearLayout card, stateLine;
        final TextView name, current, status, model, lastSeen, connect, use;
        final View edit, photoFallback;
        final ImageView photo;
        ScooterCard(String id) {
            card = ui.card(); card.setTag("scooter-card-" + id);
            card.setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(14));
            LinearLayout top = row(); top.setGravity(Gravity.CENTER_VERTICAL);
            FrameLayout picture = new FrameLayout(host.activity());
            photo = photo(); picture.addView(photo, new FrameLayout.LayoutParams(-1, -1));
            photoFallback = ui.icon("scooter", 28, ui.muted);
            picture.addView(photoFallback, new FrameLayout.LayoutParams(ui.dp(28), ui.dp(28), Gravity.CENTER));
            top.addView(picture, new LinearLayout.LayoutParams(ui.dp(52), ui.dp(58)));
            LinearLayout words = column();
            name = ui.text("", 19, true); name.setMaxLines(3); words.addView(name);
            LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(0, -2, 1); wp.leftMargin = ui.dp(10);
            top.addView(words, wp);
            FrameLayout pencil = new FrameLayout(host.activity());
            pencil.addView(ui.icon("pencil", 18, ui.muted), new FrameLayout.LayoutParams(ui.dp(18), ui.dp(18), Gravity.CENTER));
            pencil.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(ui.accent, 30)), ui.shape(ui.raised, 14, 0), null));
            pencil.setFocusable(true); pencil.setClickable(true); pencil.setTag("scooter-edit-" + id);
            pencil.setOnClickListener(v -> editScooter(id));
            LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)); pp.leftMargin = ui.dp(8);
            top.addView(pencil, pp); edit = pencil;
            card.addView(top);
            stateLine = row(); stateLine.setPadding(0, ui.dp(8), 0, 0);
            current = ui.text(T.moreScootersCurrent(), 11, true); current.setTextColor(ui.text);
            current.setPadding(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(4));
            current.setBackground(ui.shape(ui.raised, 100, ui.outline));
            LinearLayout.LayoutParams chip = new LinearLayout.LayoutParams(-2, -2); chip.rightMargin = ui.dp(8);
            stateLine.addView(current, chip);
            status = ui.caption(""); stateLine.addView(status, new LinearLayout.LayoutParams(0, -2, 1)); card.addView(stateLine);
            model = ui.caption(""); model.setPadding(0, ui.dp(8), 0, 0); card.addView(model);
            lastSeen = ui.caption(""); lastSeen.setPadding(0, ui.dp(5), 0, 0); card.addView(lastSeen);
            LinearLayout actions = row(); actions.setPadding(0, ui.dp(12), 0, 0);
            connect = ui.button(T.moreConnect(), () -> connectScooter(id)); connect.setTag("scooter-connect-" + id);
            use = ui.secondaryButton(T.moreScootersUse(), () -> useScooter(id)); use.setTag("scooter-use-" + id);
            actions.addView(connect, new LinearLayout.LayoutParams(-1, -2));
            actions.addView(use, new LinearLayout.LayoutParams(-1, -2));
            card.addView(actions);
        }
    }

    private ScooterProfiles.Profile findScooter(String id) {
        if (service() != null) for (ScooterProfiles.Profile p : service().profiles.all()) if (p.id.equals(id)) return p;
        return null;
    }
    /** One guard for switching, adding and forgetting; none can silently end a recording. */
    private boolean canChangeScooter() {
        ScooterService s = service();
        String unavailable = s == null ? T.moreServiceStarting() : s.scooterChangeUnavailable();
        if (unavailable != null) { host.toast(unavailable); return false; }
        return !closed;
    }
    private void addScooter() {
        if (!canChangeScooter()) return;
        if (service().simulating()) service().setSimulation(false);
        service().ble.disconnect();
        OnboardingScreen.restart(host.activity());
        host.rebuild();
    }
    private void connectScooter(String id) {
        if (!canChangeScooter()) return;
        ScooterService s = service(); ScooterProfiles.Profile p = findScooter(id), selected = active();
        if (p == null) { host.redraw(); return; }
        boolean current = selected != null && id.equals(selected.id);
        // A stale button must never connect a scooter that is no longer current.
        if (!current) { refresh(); return; }
        if (s.ble.state() != BleController.State.DISCONNECTED) {
            s.ble.disconnect(); refresh(); return;
        }
        host.connect(); host.redraw();
    }
    private void useScooter(String id) {
        if (!canChangeScooter()) return;
        ScooterService s = service(); ScooterProfiles.Profile p = findScooter(id), selected = active();
        if (p == null) { host.redraw(); return; }
        if (selected != null && id.equals(selected.id)) { refresh(); return; }
        // End the old transport before changing its selected identity or cached readings.
        // Selection also works offline: only the current scooter's Connect button starts Bluetooth.
        if (s.simulating()) s.setSimulation(false);
        s.ble.disconnect(); s.profiles.select(id); s.ble.profileSelected();
        host.redraw();
    }
    private void editScooter(String id) {
        ScooterProfiles.Profile p = findScooter(id); if (closed || p == null) return;
        LinearLayout box = dialogBox(T.moreScootersEdit(), null);
        EditText name = input(T.moreScootersName(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, false);
        name.setContentDescription(T.moreScootersName());
        name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(60)}); name.setText(p.name); name.selectAll(); box.addView(name);
        CharSequence modelDetail = ModelRegistry.modelLineText(p.savedDescriptor());
        if (modelDetail.length() > 0) note(box, modelDetail.toString());
        // Until Volt can name this scooter itself, the owner can say which model it is (its name and photo, nothing else).
        boolean volt = !ModelRegistry.modelName(p.savedDescriptor().withOwnerModel("")).isEmpty();
        TextView model = null;
        if (!volt) {
            if (!p.ownerModel.isEmpty()) note(box, T.moreScootersOwnerAnswer(p.ownerModel));
            model = ui.secondaryButton(p.ownerModel.isEmpty() ? T.moreScootersWhichModel() : T.moreScootersChangeModel(), null); model.setTag("scooter-model-" + id);
            box.addView(model);
        }
        AlertDialog dialog = new AlertDialog.Builder(host.activity()).setView(box).setNegativeButton(T.commonCancel(), null)
                .setPositiveButton(T.moreScootersSaveName(), null).create();
        if (model != null) model.setOnClickListener(v -> { dialog.dismiss(); host.chooseScooterModel(id); });
        TextView forget = ui.dangerButton(T.moreScootersForget(), () -> {
            if (!canChangeScooter()) return;
            dialog.dismiss(); forgetScooter(id);
        });
        forget.setTag("scooter-forget-" + id); forget.setContentDescription(T.moreScootersForgetNamed(p.displayName()));
        ui.decorate(forget, "trash"); box.addView(forget);
        show(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            ScooterProfiles.Profile target = findScooter(id); if (closed || target == null) { dialog.dismiss(); return; }
            String value = name.getText().toString().trim(); if (value.isEmpty()) { name.setError(T.moreScootersEnterName()); return; }
            target.name = value; target.nameDefault = false; service().profiles.update(target); dialog.dismiss(); host.redraw();
        });
        name.requestFocus();
        if (dialog.getWindow() != null) dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
    }
    private void forgetScooter(String id) {
        if (!canChangeScooter()) return;
        ScooterProfiles.Profile p = findScooter(id); if (p == null) return;
        LinearLayout box = dialogBox(T.moreScootersForgetTitle(p.displayName()), T.moreScootersForgetBody());
        AlertDialog dialog = new AlertDialog.Builder(host.activity()).setView(box).create();
        TextView backup = ui.secondaryButton(T.moreScootersSaveBackup(), () -> { dialog.dismiss(); host.dataBackup(false, null); });
        box.addView(backup);
        dialogButtons(box, T.commonCancel(), dialog::dismiss, T.moreScootersForgetAction(), () -> {
            if (!canChangeScooter()) return;
            ScooterService s = service(); ScooterProfiles.Profile target = findScooter(id), selected = active();
            if (target == null) { dialog.dismiss(); return; }
            boolean current = selected != null && selected.id.equals(id);
            if (current && s.simulating()) s.setSimulation(false);
            if (current) s.ble.disconnect();
            s.profiles.remove(id);
            if (s.maintenance != null) s.maintenance.forget(id);
            if (current) s.ble.profileSelected();
            dialog.dismiss(); host.redraw();
        });
        show(dialog);
    }

    // ---- App preferences ----------------------------------------------------------------

    /**
     * The Language row (plan 5.1), first in the display card with Units and Text size, offered only once a
     * language other than English ships, or with the pseudo APK. The row is
     * built here, in a holder of its own that is never attached, and stays out of the card until offered:
     * a GONE child would still take a child slot and shift every later sibling's index, so view trees
     * without the row must not hold it, or its divider, at all.
     */
    private void languageRow(LinearLayout display) {
        LinearLayout holder = column();
        Row row = row(holder, "globe", T.moreLanguageTitle(), null,
                () -> LanguageSheet.show(host, this::refresh));
        View language = row.view;
        language.setTag("preference_language");
        holder.removeView(language);
        View divider = new View(host.activity());
        divider.setBackgroundColor(ui.raised);
        valueRefreshers.add(() -> {
            boolean offered = LanguageSheet.offered(host.activity());
            // The current language goes under the title, as Android's own language setting shows it: the
            // trailing value column is capped at 140 dp and two lines, which cut "System default (English)"
            // at large text sizes.
            if (offered) row.sub(LanguageSheet.value(host.activity()));
            placeLanguageRow(display, language, divider, offered, ui.dp(1));
        });
    }

    /**
     * Attaches the Language row, then a divider, at the top of the display card once, and detaches both
     * when not offered. The divider matches Ui.divider: the card's raised colour, 1 dp tall.
     */
    private static void placeLanguageRow(LinearLayout display, View language, View divider, boolean offered, int line) {
        boolean attached = language.getParent() == display;
        if (!offered) { if (attached) { display.removeView(divider); display.removeView(language); } return; }
        if (attached) return;
        display.addView(language, 0, new LinearLayout.LayoutParams(-1, -2));
        display.addView(divider, 1, new LinearLayout.LayoutParams(-1, line));
    }

    private void preferences() {
        segmentedField(T.morePrefsAppearance(), "theme", new String[]{T.morePrefsDark(), T.morePrefsFollowSystem(), T.morePrefsLight()},
                new String[]{"dark", "system", "light"}, "dark");
        segmentedField(T.morePrefsStatsDetail(), "stats_density", new String[]{T.morePrefsComfortable(), T.morePrefsDense()},
                new String[]{"comfortable", "dense"}, "comfortable");
        View spacer = new View(host.activity());
        cardDestination.addView(spacer, new LinearLayout.LayoutParams(-1, ui.dp(16)));
        LinearLayout display = group();
        display.setTag("preference-display-options");
        languageRow(display);
        Row units = row(display, "ruler", T.morePrefsUnits(), null, () -> selectSection(UNITS_SECTION));
        units.view.setTag("preference-units-menu");
        valueRefreshers.add(() -> units.value(unitsSummary()));
        Row text = row(display, "text", T.morePrefsTextSize(), null, this::textSize);
        valueRefreshers.add(() -> text.value(T.fmtPercent(Fmt.integer(Math.round(settings().getFloat("text_scale", 1f) * 100)))));
        preferenceToggle(display, "vibrate", T.morePrefsHaptics(), null, Haptics.PREFERENCE, true);
        Row map = row(display, "map", T.morePrefsMap(), null, this::mapSettings);
        valueRefreshers.add(() -> map.value(basemapSummary()));
        label(T.morePrefsConnection());
        LinearLayout connection = group();
        Row rate = row(connection, "clock", T.morePrefsRefresh(), null, this::telemetryRate);
        valueRefreshers.add(() -> rate.value(T.fmtQuantity(Fmt.integer(settings().getInt("poll_interval_ms", 2000) / 1000), T.moreUnitSecond())));
        preferenceToggle(connection, "bluetooth", T.morePrefsAutoConnect(), null, "auto_connect", true);
        preferenceToggle(connection, "bluetooth", T.morePrefsBackground(), T.morePrefsBackgroundSub(), "background_connection", false);
        preferenceToggle(connection, "screen", T.morePrefsAwake(), T.morePrefsAwakeSub(), "keep_screen_awake", true);
        // Default on. ScooterService/RideAutoStopPolicy decide; this only stores the choice.
        preferenceToggle(connection, "walk", T.morePrefsAutoStop(), T.morePrefsAutoStopSub(), "ride_auto_stop", true);
        // Off by default. ScooterService/AutoStartPolicy decide; turning it on offers the ride location prompt first.
        preferenceToggle(connection, "walk", RidingSettingsMenu.autoRecordTitle(), RidingSettingsMenu.autoRecordSub(), AutoStartPolicy.AUTO_RECORD_KEY, false, this::offerRideLocation);
        // Battery use is the one setting that decides whether long background recordings survive:
        // show it as the value and open Android's page for this app directly.
        Row background = row(connection, "battery", T.morePrefsBattery(), null,
                () -> openAndroidSettings(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS));
        valueRefreshers.add(() -> {
            boolean unrestricted = batteryUnrestricted();
            background.value(unrestricted ? T.morePrefsBatteryValueUnrestricted() : T.morePrefsBatteryValueOptimized());
            background.value.setTextColor(unrestricted ? ui.ok : ui.orange);
            background.value.setContentDescription(unrestricted ? T.morePrefsBatteryUnrestricted()
                    : T.morePrefsBatteryOptimized());
        });
        ridingPreferences();
    }
    /** Riding mode's settings now live in its own tune menu (RidingSettingsMenu); this one stays here too. */
    private void ridingPreferences() {
        label(RidingSettingsMenu.section());
        LinearLayout box = group();
        box.setTag("preference_riding_display");
        // Off by default. MainActivity/AutoStartPolicy decide; this only stores the choice.
        preferenceToggle(box, "speed", RidingSettingsMenu.autoOpenTitle(), RidingSettingsMenu.autoOpenSub(), AutoStartPolicy.AUTO_RIDE_MODE_KEY, false)
                .view.setTag("preference_riding_auto_open");
    }
    private boolean batteryUnrestricted() {
        try {
            android.os.PowerManager power = host.activity().getSystemService(android.os.PowerManager.class);
            return power != null && power.isIgnoringBatteryOptimizations(host.activity().getPackageName());
        } catch (RuntimeException unavailable) { return false; }
    }
    /** Units row value, e.g. "mi · °C · 12h"; "Follow system" resolves to the phone's clock. */
    private String unitsSummary() {
        String units = settings().getString("units", "mi"), temperature = settings().getString("temperature_unit", "c");
        String time = settings().getString("time_format", "system");
        boolean hours24 = "24h".equals(time) || (!"12h".equals(time) && android.text.format.DateFormat.is24HourFormat(host.activity()));
        return ("km".equals(units) ? "km" : "dual".equals(units) ? "mi + km" : "mi") + " · "
                + ("f".equals(temperature) ? "°F" : "°C") + " · " + (hours24 ? "24h" : "12h");
    }
    /** Current basemap as a row value: Off, USGS, OSM or Custom. */
    private String basemapSummary() {
        SharedPreferences settings = settings();
        if (!MapPreferences.enabled(settings)) return T.commonOff();
        String url = MapSource.load(settings).url;
        return MapSource.USGS_URL.equals(url) ? "USGS" : MapSource.OSM_URL.equals(url) ? "OSM" : T.morePrefsMapCustom();
    }
    private void mapSettings() {
        AlertDialog dialog = MapPreferences.show(host);
        if (dialog != null) dialog.setOnDismissListener(d -> { if (!closed) refresh(); });
    }
    /** Android 16's per-app Live Updates setting; Volt's notification settings where that page is missing. */
    private void openLiveUpdateSettings() {
        if (Build.VERSION.SDK_INT >= ChargeLiveUpdate.SDK) {
            android.content.Intent intent = new android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS);
            intent.putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, host.activity().getPackageName());
            if (startAndroidSettings(intent)) return;
        }
        openAndroidSettings(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS);
    }
    private boolean liveUpdateAllowed() {
        if (Build.VERSION.SDK_INT < ChargeLiveUpdate.SDK) return false;
        try { return host.activity().getSystemService(android.app.NotificationManager.class).canPostPromotedNotifications(); }
        catch (RuntimeException | LinkageError unknown) { return false; }
    }
    private void openAndroidSettings(String action){
        android.content.Intent intent=new android.content.Intent(action);
        if(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS.equals(action))intent.putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,host.activity().getPackageName());
        else intent.setData(android.net.Uri.parse("package:"+host.activity().getPackageName()));
        if (startAndroidSettings(intent)) return;
        if (!android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS.equals(action)
                && startAndroidSettings(new android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:"+host.activity().getPackageName())))) return;
        // Some manufacturers omit a settings activity. Fall back to the public
        // app list instead of relying on private permission-controller routes.
        if (startAndroidSettings(new android.content.Intent(android.provider.Settings.ACTION_APPLICATION_SETTINGS))) {
            host.toast(M.morePrefsChooseVolt());
            return;
        }
        host.toast(T.morePrefsOpenSettings());
    }
    private boolean startAndroidSettings(android.content.Intent intent) {
        try {
            host.activity().startActivity(intent);
            return true;
        } catch (android.content.ActivityNotFoundException | SecurityException unavailable) {
            return false;
        }
    }
    private void openLocationSettings() {
        // Android exposes app details publicly, but not a portable direct link
        // to one app's Location permission page.
        openAndroidSettings(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
    }
    private void units() {
        segmentedField(T.moreUnitsDistance(), "units", new String[]{T.moreUnitsMiles(), T.moreUnitsKm(), T.moreUnitsBoth()},
                new String[]{"mi", "km", "dual"}, "mi");
        segmentedField(T.moreUnitsTemperature(), "temperature_unit", new String[]{T.moreUnitsCelsius(), T.moreUnitsFahrenheit()},
                new String[]{"c", "f"}, "c");
        segmentedField(T.moreUnitsTime(), "time_format", new String[]{T.morePrefsFollowSystem(), T.moreUnits12Hour(), T.moreUnits24Hour()},
                new String[]{"system", "12h", "24h"}, "system");
    }
    private void segmentedField(String title, String key, String[] labels, String[] values, String fallback) {
        segmentedField(title, key, labels, values, fallback, null);
    }
    /** changed runs after the rider picks a different value. */
    private void segmentedField(String title, String key, String[] labels, String[] values, String fallback, Runnable changed) {
        TextView caption = ui.text(title, 12.5f, false); caption.setTextColor(ui.muted);
        caption.setPadding(ui.dp(2), ui.dp(12), ui.dp(2), ui.dp(6));
        cardDestination.addView(caption);
        SegmentedSelector selector = new SegmentedSelector(ui, title, labels, choice -> {
            if (closed || values[choice].equals(settings().getString(key, fallback))) return;
            settings().edit().putString(key, values[choice]).apply();
            // MainActivity coalesces theme and display-unit rebuilds after the 190ms slide. A second
            // immediate rebuild here would destroy the animation (and the user's scroll position).
            if (key.equals("time_format")) host.redraw();
            if (changed != null) changed.run();
        });
        selector.setTag("preference-selector-" + key);
        Runnable restyle = () -> {
            String stored = settings().getString(key, fallback); int selected = -1;
            for (int i = 0; i < values.length; i++) if (stored.equals(values[i])) selected = i;
            if (selected < 0) for (int i = 0; i < values.length; i++) if (fallback.equals(values[i])) selected = i;
            selector.select(Math.max(0, selected), false);
        };
        cardDestination.addView(selector, new LinearLayout.LayoutParams(-1, -2));
        preferenceSelectors.add(selector);
        restyle.run(); valueRefreshers.add(restyle);
    }
    private Row preferenceToggle(LinearLayout box, String icon, String title, String detail, String key, boolean fallback) {
        return preferenceToggle(box, icon, title, detail, key, fallback, null);
    }

    /** beforeOn runs ahead of saving a turn-on, and is given the save to run; turning off saves at once. */
    private Row preferenceToggle(LinearLayout box, String icon, String title, String detail, String key, boolean fallback, Consumer<Runnable> beforeOn) {
        Row row = toggleRow(box, icon, title, detail, enabled -> {
            Runnable save = () -> { settings().edit().putBoolean(key, enabled).apply(); host.redraw(); };
            if (enabled && beforeOn != null) beforeOn.accept(save); else save.run();
        });
        valueRefreshers.add(() -> refreshSwitch(row, settings().getBoolean(key, fallback), true));
        return row;
    }

    /**
     * The same optional prompt a manual recording uses, answered before the switch saves (shared with the tune
     * menu's copy of this switch, RidingSettingsMenu). Either answer turns it on; a restored switch never comes
     * through here, the activity offers that one on screen.
     */
    private void offerRideLocation(Runnable save) {
        RidingSettingsMenu.offerRideLocation(host, save);
    }

    // ---- Data & history -----------------------------------------------------------------

    private void dataHistory() {
        label(T.moreHistoryConnected());
        LinearLayout recording = group();
        Row history = preferenceToggle(recording, "history", T.moreHistorySave(), T.moreHistoryWhileConnected(), "history_enabled", true);
        valueRefreshers.add(() -> history.sub(settings().getBoolean("history_enabled", true) ? T.moreHistoryWhileConnected() : T.moreHistoryOffKept()));
        autoSaveGroup();

        LinearLayout storage = ui.card(); historyStorageCard = storage; cardDestination.addView(storage);
        LinearLayout head = row();
        TextView title = ui.text(T.moreHistoryLocalStorage(), 14, false); title.setTextColor(ui.muted);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        historyUsage = ui.text(T.moreHistoryChecking(), 17, true); historyUsage.setGravity(Gravity.END); head.addView(historyUsage);
        storage.addView(head);
        historyBar = row(); historyBar.setBackground(ui.shape(ui.raised, 4, 0)); historyBar.setClipToOutline(true);
        historyBarFill = new View(host.activity()); historyBarFill.setBackground(ui.shape(ui.accent, 4, 0));
        historyBarRest = new View(host.activity());
        historyBar.addView(historyBarFill, new LinearLayout.LayoutParams(0, -1, 0f));
        historyBar.addView(historyBarRest, new LinearLayout.LayoutParams(0, -1, 1f));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, ui.dp(8)); bp.topMargin = ui.dp(12); bp.bottomMargin = ui.dp(6);
        storage.addView(historyBar, bp);
        historyBar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        String[][] retention = {{T.moreHistoryDetailed(), T.moreHistory7Days()}, {T.moreHistoryMinutes(), T.moreHistory30Days()}, {T.moreHistoryDaily(), T.moreHistory2Years()}};
        historyCounts = new TextView[retention.length];
        for (int i = 0; i < retention.length; i++) {
            if (i > 0) ui.divider(storage);
            LinearLayout line = row(); line.setPadding(0, ui.dp(8), 0, ui.dp(8));
            LinearLayout words = column();
            TextView name = ui.text(retention[i][0], 13.5f, false); name.setTextColor(ui.muted); words.addView(name);
            historyCounts[i] = ui.text("", 11.5f, false); historyCounts[i].setTextColor(ui.dim); words.addView(historyCounts[i]);
            line.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
            TextView limit = ui.text(T.moreHistoryUpTo(retention[i][1]), 13.5f, true); line.addView(limit);
            storage.addView(line);
        }
        historyStatus = ui.caption(""); historyStatus.setPadding(0, ui.dp(6), 0, 0); storage.addView(historyStatus);

        LinearLayout clear = group();
        dangerRow(clear, T.moreHistoryClearScooter(), () -> clearHistory(false));
        dangerRow(clear, T.moreHistoryClearAll(), () -> clearHistory(true));
    }
    /**
     * Ride auto-save (RideAutoExport), off by default: one switch, saving each ride's Everything in one JSON file, and
     * its folder row. Turning it on with no usable folder opens the folder picker (RideAutoExportPickerActivity); the
     * switch shows on only once a folder is kept. The folder row shows where files go, or the one stored failure, and
     * opens the picker to change the folder without touching the switch. While it is off the row stays in place,
     * greyed out. ScooterService does the copying.
     */
    private void autoSaveGroup() {
        label(T.moreAutoSaveHeading());
        LinearLayout box = group();
        autoSaveSwitch(box);
        Row folder = row(box, "export", T.moreAutoSaveFolder(), T.moreAutoSaveNoFolder(), () -> {
            if (RideAutoExportFolder.on(settings())) chooseAutoSaveFolder(false);
        });
        valueRefreshers.add(() -> {
            RideAutoExport.RowState state = autoSaveFolderState();
            folder.enabled(state != RideAutoExport.RowState.OFF);
            folder.view.setClickable(state != RideAutoExport.RowState.OFF);
            folder.sub(autoSaveFolderLine(state));
            boolean warn = state == RideAutoExport.RowState.CHOOSE_FOLDER || state == RideAutoExport.RowState.FOLDER_PROBLEM || state == RideAutoExport.RowState.SAVE_PROBLEM;
            folder.sub.setTextColor(warn ? ui.orange : state == RideAutoExport.RowState.OFF ? ui.dim : ui.muted);
        });
        footnote(T.moreAutoSaveFolderNote());
    }
    private void autoSaveSwitch(LinearLayout box) {
        Row[] self = new Row[1];
        self[0] = toggleRow(box, "download", T.moreAutoSaveJson(), T.moreAutoSaveJsonSub(), on -> {
            if (on && !RideAutoExportFolder.ready(host.activity(), settings())) { refreshSwitch(self[0], false, true); chooseAutoSaveFolder(true); }
            else { RideAutoExportFolder.set(host.activity(), settings(), on); host.redraw(); }
        });
        valueRefreshers.add(() -> refreshSwitch(self[0], RideAutoExportFolder.on(settings()), true));
    }
    /** The folder row's state: the switch, the stored folder, its grant and the failure id. */
    private RideAutoExport.RowState autoSaveFolderState() {
        return RideAutoExportFolder.rowState(host.activity(), settings());
    }
    /** The folder row's sub-label for a state; the folder's own path is a value, shown as the phone names it. */
    private String autoSaveFolderLine(RideAutoExport.RowState state) {
        switch (state) {
            case CHOOSE_FOLDER: return T.moreAutoSaveChooseFolder();
            case FOLDER_PROBLEM: return T.moreAutoSaveFolderProblem();
            case SAVE_PROBLEM: return T.moreAutoSaveSaveProblem();
            case SAVING:
                String folder = RideAutoExportFolder.label(RideAutoExportFolder.chosen(settings()));
                return folder == null ? T.moreAutoSaveSavingChosen() : T.moreAutoSaveSavingTo(folder);
            default: return T.moreAutoSaveNoFolder();
        }
    }
    /** turnOn: the switch asked, so auto-save turns on once a folder is kept; false changes the folder only. */
    private void chooseAutoSaveFolder(boolean turnOn) {
        try { host.activity().startActivity(RideAutoExportPickerActivity.intent(host.activity(), turnOn)); }
        catch (RuntimeException missing) { host.toast(T.moreAutoSaveNoPicker()); }
    }
    private void refreshHistoryStorage() {
        if (historyUsage == null || service() == null || service().history == null || historyStoragePending) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (historyStorageChecked > 0 && now - historyStorageChecked < 10000) return;
        historyStorageChecked = now; historyStoragePending = true;
        service().history.storage(storage -> {
            historyStoragePending = false; if (closed) return;
            String used = T.moreHistoryUsed(Fmt.decimal(Locale.US, storage.bytes / 1048576.0, 1), Fmt.decimal(Locale.US, storage.limitBytes / 1048576.0, 0), T.moreUnitMib());
            historyUsage.setText(used);
            float share = storage.limitBytes <= 0 ? 0f : Math.max(0f, Math.min(1f, storage.bytes / (float) storage.limitBytes));
            // A visible sliver shows that something is stored even when it rounds to nothing.
            if (storage.bytes > 0) share = Math.max(share, 0.02f);
            ((LinearLayout.LayoutParams) historyBarFill.getLayoutParams()).weight = share;
            ((LinearLayout.LayoutParams) historyBarRest.getLayoutParams()).weight = 1f - share;
            historyBar.requestLayout();
            long[] rows = {storage.detailedRows, storage.minuteRows, storage.dailyRows};
            for (int i = 0; i < rows.length; i++) historyCounts[i].setText(T.moreHistorySamplesSaved(rows[i], Fmt.groupedInteger(Locale.US, rows[i])));
            historyStatus.setText(storage.status == null ? "" : storage.status);
            // Shown in the current language; storage.status stays TelemetryHistory's English id (statusMsg's English is the id).
            if (storage.status != null) historyStatus.setText(storage.statusMsg().toString());
            historyStatus.setVisibility(storage.status == null || storage.status.isEmpty() ? View.GONE : View.VISIBLE);
            historyStorageCard.setContentDescription(T.moreHistoryStorageDescription(used,
                    T.moreHistorySamplesDescription(Fmt.groupedInteger(Locale.US, rows[0]), Fmt.groupedInteger(Locale.US, rows[1]), Fmt.groupedInteger(Locale.US, rows[2]))));
            dataIndexRow.value(mib(storage.bytes));
        });
    }
    private void clearHistory(boolean all) {
        ScooterProfiles.Profile profile = active();
        if (!all && profile == null) { host.toast(T.moreHistoryChooseScooter()); return; }
        final String profileId = all ? null : profile.id;
        confirm(T.moreHistoryClearTitle(), all ? T.moreHistoryClearAllBody() : T.moreHistoryClearOneBody(profile.displayName()),
                T.moreClear(), () -> {
                    if (service() == null || service().history == null) return;
                    service().clearHistory(profileId, () -> {
                        if (closed) return;
                        historyStorageChecked = 0; refreshHistoryStorage(); host.redraw();
                        host.toast(T.moreHistoryCleared());
                    });
                });
    }

    private void textSize() {
        LinearLayout box = dialogBox(T.morePrefsTextSize(), null);
        TextView sample = ui.text(T.moreTextSizeSample(), 18, true); box.addView(sample);
        SeekBar seek = new SeekBar(host.activity()); seek.setMax(12);
        int initial = Math.round((settings().getFloat("text_scale", 1f) - 0.8f) / 0.05f);
        seek.setProgress(Math.max(0, Math.min(12, initial))); box.addView(seek);
        TextView percent = value(box, "");
        Runnable preview = () -> {
            float scale = 0.8f + seek.getProgress() * 0.05f;
            percent.setText(T.fmtPercent(Fmt.integer(Math.round(scale * 100)))); sample.setTextSize(18 * scale);
        };
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int value, boolean user) { preview.run(); }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        }); preview.run();
        AlertDialog dialog = new AlertDialog.Builder(host.activity()).setView(box).setNegativeButton(T.commonCancel(), null)
                .setPositiveButton(T.moreApply(), (d, which) -> {
                    settings().edit().putFloat("text_scale", 0.8f + seek.getProgress() * 0.05f).apply(); host.rebuild();
                }).create(); show(dialog);
    }
    private void telemetryRate() {
        final int[] intervals = {2000, 5000, 10000};
        int stored = settings().getInt("poll_interval_ms", 2000), selected = 0;
        for (int i = 0; i < intervals.length; i++) if (stored == intervals[i]) selected = i;
        LinearLayout box = dialogBox(T.morePrefsRefresh(), T.moreRefreshRides());
        SegmentedSelector selector = new SegmentedSelector(ui, T.morePrefsRefresh(),
                new String[]{T.moreRefresh2(), T.moreRefresh5(), T.moreRefresh10()}, choice -> {
                    settings().edit().putInt("poll_interval_ms", intervals[choice]).apply();
                    refresh();
                });
        selector.select(selected, false); box.addView(selector, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog dialog = new AlertDialog.Builder(host.activity()).setView(box)
                .setPositiveButton(T.commonDone(), null).create(); show(dialog);
    }

    // ---- PIN & proximity ----------------------------------------------------------------

    private void security() {
        // Steps 1-3 (PIN, pairing, proximity) are numbered on the rows themselves.
        label(T.morePinHandlebar());
        LinearLayout pin = group();
        pinRow = toggleRow(pin, "lock", T.morePinFallback(), null,
                enabled -> { service().ble.enablePin(enabled); host.toast(service().ble.displayText()); refresh(); });
        pinRow.icon.step = "1";
        pinRefresh = rowButton(pinRow, "history", T.morePinRead(), () -> { service().ble.readPin(); refresh(); });
        pinSpinner = rowSpinner(pinRow);
        setPinRow = row(pin, "pencil", M.morePinSetRow(), null, this::setPin);
        label(T.moreProximityLabel());
        LinearLayout near = group();
        bondRow = row(near, "bluetooth", T.moreProximityPair(), null,
                () -> { service().ble.bond(); host.toast(service().ble.displayText()); refresh(); });
        bondRow.icon.step = "2";
        proximityRow = toggleRow(near, "bluetooth", T.moreProximityLabel(), null,
                enabled -> { service().ble.set(ProximityFollowUp.KEY, enabled ? 1 : 0); host.toast(service().ble.displayText()); refresh(); });
        proximityRow.icon.step = "3";
        proximitySpinner = rowSpinner(proximityRow);
        LinearLayout chips = row(); chips.setPadding(0, ui.dp(5), 0, 0);
        proximityPinChip = ui.text("", 11.5f, false); chips.addView(proximityPinChip);
        proximityPairedChip = ui.text("", 11.5f, false);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2); cp.leftMargin = ui.dp(6);
        chips.addView(proximityPairedChip, cp);
        ((LinearLayout) proximityRow.title.getParent()).addView(chips);
        // A disabled switch explains itself: tapping the row says what is still missing.
        Switch proximityToggle = proximityRow.toggle;
        proximityRow.view.setOnClickListener(v -> {
            if (proximityToggle.isEnabled()) proximityToggle.performClick();
            else if (proximityReason != null) host.toast(proximityReason.toString());
        });
        securityConnect = ui.button(T.moreConnect(), host::connect);
        cardDestination.addView(securityConnect);
        securityNotice = footnote("");
        LinearLayout guide = group();
        row(guide, "info", T.morePinGuideRow(), null, this::pinGuide);
    }
    private CharSequence proximityReason;
    /** A 48dp icon button placed just before a row's switch. */
    private View rowButton(Row r, String iconKey, String description, Runnable action) {
        FrameLayout button = new FrameLayout(host.activity());
        button.addView(ui.icon(iconKey, 18, ui.accent), new FrameLayout.LayoutParams(ui.dp(18), ui.dp(18), Gravity.CENTER));
        button.setContentDescription(description);
        clickable(button, action);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)); lp.leftMargin = ui.dp(4);
        r.view.addView(button, r.view.indexOfChild(r.toggle), lp);
        return button;
    }
    /** Small spinner shown on a row while the scooter confirms its change. */
    private View rowSpinner(Row r) {
        android.widget.ProgressBar bar = new android.widget.ProgressBar(host.activity(), null, android.R.attr.progressBarStyleSmall);
        bar.setIndeterminate(true); bar.setIndeterminateTintList(ColorStateList.valueOf(ui.accent));
        bar.setContentDescription(T.moreWaitingConfirmation());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)); lp.leftMargin = ui.dp(10);
        r.view.addView(bar, r.view.indexOfChild(r.toggle), lp);
        bar.setVisibility(View.GONE);
        return bar;
    }
    private boolean ownerReady() {
        ScooterProfiles.Profile p = active();
        if (service() == null || !service().ble.ready() || p == null || p.role != 0) return false;
        // The resolver decides whether this model has the PIN; riding or a pending change only disable the rows.
        CapabilityResolver.Decision pin = service().ble.decision("pin");
        return pin.available() || pin.blockedByRide() || pin.reason == CapabilityResolver.Reason.BUSY;
    }
    /** One of the four PIN boxes; it only knows whether it is filled, never which digit. */
    private final class PinBox extends View {
        boolean filled, active;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        PinBox() { super(host.activity()); setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); }
        void set(boolean filled, boolean active) {
            if (filled == this.filled && active == this.active) return;
            this.filled = filled; this.active = active; invalidate();
        }
        @Override protected void onDraw(Canvas canvas) {
            float glow = ui.dp(3), radius = ui.dp(12);
            RectF box = new RectF(glow, glow, getWidth() - glow, getHeight() - glow);
            if (active) {
                paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(glow); paint.setColor(Ui.withAlpha(ui.accent, 70));
                RectF ring = new RectF(box); ring.inset(-glow / 2, -glow / 2);
                canvas.drawRoundRect(ring, radius + glow / 2, radius + glow / 2, paint);
            }
            paint.setStyle(Paint.Style.FILL); paint.setColor(ui.dark ? ui.bg : ui.raised);
            canvas.drawRoundRect(box, radius, radius, paint);
            float stroke = active ? ui.dp(1.5f) : ui.dp(1);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(stroke); paint.setColor(active ? ui.accent : ui.outline);
            RectF edge = new RectF(box); edge.inset(stroke / 2, stroke / 2);
            canvas.drawRoundRect(edge, radius, radius, paint);
            if (filled) {
                paint.setStyle(Paint.Style.FILL); paint.setColor(ui.text);
                canvas.drawCircle(box.centerX(), box.centerY(), ui.dp(6), paint);
            }
        }
    }
    private static boolean fourDigits(CharSequence value) {
        if (value.length() != 4) return false;
        for (int i = 0; i < 4; i++) { char c = value.charAt(i); if (c < '0' || c > '9') return false; }
        return true;
    }
    /** A short horizontal shake, skipped when the phone has animations turned off. */
    private void shake(View view) {
        if (!ValueAnimator.areAnimatorsEnabled()) return;
        float d = ui.dp(10);
        ObjectAnimator animator = ObjectAnimator.ofFloat(view, View.TRANSLATION_X, 0, d, -d, d * .6f, -d * .6f, d * .3f, 0);
        animator.setDuration(320); animator.start();
    }
    private void setPin() {
        if (!ownerReady()) { host.toast(T.morePinOwnerFirst()); return; }
        LinearLayout box = dialogBox(T.morePinTitle(), T.morePinBody());
        LinearLayout header = row(); header.setPadding(0, ui.dp(8), 0, ui.dp(8));
        TextView stepLabel = ui.text(T.morePinEnter(), 13.5f, true); stepLabel.setTypeface(ui.semibold());
        stepLabel.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        header.addView(stepLabel, new LinearLayout.LayoutParams(0, -2, 1));
        View[] dashes = new View[2];
        for (int i = 0; i < 2; i++) {
            dashes[i] = new View(host.activity()); dashes[i].setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(ui.dp(18), ui.dp(4)); dp.leftMargin = ui.dp(5);
            header.addView(dashes[i], dp);
        }
        box.addView(header);

        FrameLayout entry = new FrameLayout(host.activity());
        LinearLayout boxes = row();
        PinBox[] cells = new PinBox[4];
        for (int i = 0; i < 4; i++) {
            cells[i] = new PinBox();
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, ui.dp(60), 1);
            cp.leftMargin = i == 0 ? 0 : ui.dp(2); cp.rightMargin = i == 3 ? 0 : ui.dp(2);
            boxes.addView(cells[i], cp);
        }
        entry.addView(boxes, new FrameLayout.LayoutParams(-1, ui.dp(60)));
        // The real field sits over the boxes: it takes taps and the keyboard, but draws nothing.
        EditText field = new EditText(host.activity());
        field.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4)});
        field.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        field.setTextColor(Color.TRANSPARENT); field.setHintTextColor(Color.TRANSPARENT); field.setHighlightColor(Color.TRANSPARENT);
        field.setHint(T.morePinEnterHint()); field.setBackground(null); field.setPadding(0, 0, 0, 0);
        field.setCursorVisible(false); field.setLongClickable(false); field.setSaveEnabled(false);
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        ActionMode.Callback noMenu = new ActionMode.Callback() {
            @Override public boolean onCreateActionMode(ActionMode mode, Menu menu) { return false; }
            @Override public boolean onPrepareActionMode(ActionMode mode, Menu menu) { return false; }
            @Override public boolean onActionItemClicked(ActionMode mode, MenuItem item) { return false; }
            @Override public void onDestroyActionMode(ActionMode mode) { }
        };
        field.setCustomSelectionActionModeCallback(noMenu); field.setCustomInsertionActionModeCallback(noMenu);
        entry.addView(field, new FrameLayout.LayoutParams(-1, ui.dp(60)));
        box.addView(entry, new LinearLayout.LayoutParams(-1, -2));
        TextView error = ui.caption(""); error.setTextColor(ui.danger); error.setPadding(0, ui.dp(8), 0, 0);
        error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE); box.addView(error);

        AlertDialog dialog = new AlertDialog.Builder(host.activity()).setView(box).create();
        final char[][] first = {null};
        final int[] step = {1};
        final TextView[] buttons = dialogButtons(box, T.commonCancel(), dialog::dismiss, T.moreNext(), null);
        TextView next = buttons[1];
        Runnable update = () -> {
            int filled = field.length(); boolean focused = field.hasFocus();
            for (int i = 0; i < 4; i++) cells[i].set(i < filled, focused && i == filled);
            next.setEnabled(filled == 4);
        };
        Runnable stepOne = () -> {
            if (first[0] != null) Arrays.fill(first[0], '\0');
            first[0] = null; step[0] = 1; field.setText("");
            stepLabel.setText(T.morePinEnter()); stepLabel.setContentDescription(T.morePinStepOne());
            field.setHint(T.morePinEnterHint());
            next.setText(T.moreNext()); next.setContentDescription(T.moreNext());
            dashes[0].setBackground(ui.shape(ui.accent, 2, 0)); dashes[1].setBackground(ui.shape(ui.raised, 2, 0));
            update.run();
        };
        next.setOnClickListener(v -> {
            Editable typed = field.getText();
            if (!fourDigits(typed)) { error.setText(T.morePinDigits()); return; }
            if (step[0] == 1) {
                first[0] = new char[4]; typed.getChars(0, 4, first[0], 0);
                step[0] = 2; field.setText(""); error.setText("");
                stepLabel.setText(T.morePinRepeat()); stepLabel.setContentDescription(T.morePinStepTwo());
                field.setHint(T.morePinRepeatHint());
                next.setText(T.morePinSet()); next.setContentDescription(T.morePinSet());
                dashes[1].setBackground(ui.shape(ui.accent, 2, 0));
                update.run(); return;
            }
            char[] repeated = new char[4]; typed.getChars(0, 4, repeated, 0);
            boolean same = Arrays.equals(first[0], repeated); Arrays.fill(repeated, '\0');
            if (!same) {
                shake(boxes); error.setText(T.morePinMismatch()); stepOne.run(); return;
            }
            if (!ownerReady()) { error.setText(T.morePinReconnect()); return; }
            if (service().ble.pending() != null) { error.setText(T.morePinWait()); return; }
            service().ble.setPin(new String(first[0]));
            if ("pin".equals(service().ble.pending())) {
                host.toast(service().ble.displayText()); dialog.dismiss();
            } else error.setText(T.morePinNotStarted(service().ble.displayMessage()));
            refresh();
        });
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) { if (s.length() > 0) error.setText(""); update.run(); }
        });
        field.setOnFocusChangeListener((v, focused) -> update.run());
        field.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_DONE && next.isEnabled()) { next.performClick(); return true; }
            return action == EditorInfo.IME_ACTION_DONE;
        });
        stepOne.run();
        if (dialog.getWindow() != null) dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        show(dialog, () -> {
            field.setText("");
            if (first[0] != null) Arrays.fill(first[0], '\0');
            first[0] = null;
        });
        field.requestFocus();
    }
    private void pinGuide() {
        LinearLayout box = dialogBox(T.morePinGuideRow(), T.morePinGuideBody());
        box.addView(new HandlebarDiagram(), new LinearLayout.LayoutParams(-1, ui.dp(116)));
        String[] steps = {T.morePinGuideSignal(), T.morePinGuideDigit(), T.morePinGuidePower()};
        for (int i = 0; i < steps.length; i++) {
            LinearLayout line = row(); line.setPadding(0, ui.dp(5), 0, ui.dp(5));
            TextView number = ui.text(String.valueOf(i + 1), 12, true); number.setGravity(Gravity.CENTER);
            number.setTextColor(ui.onAccent); number.setBackground(ui.shape(ui.accent, 100, 0));
            number.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)); np.rightMargin = ui.dp(10);
            line.addView(number, np);
            TextView words = ui.text(steps[i], 14, false); words.setContentDescription(T.morePinGuideStep((i + 1), steps[i]));
            line.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
            box.addView(line);
        }
        note(box, T.morePinGuideNote());
        ScrollView inner = new ScrollView(host.activity()); inner.addView(box);
        AlertDialog dialog = new AlertDialog.Builder(host.activity()).setView(inner).setPositiveButton(T.moreGotIt(), null).create(); show(dialog);
    }
    /** Schematic handlebar: 1 turn-signal button, 2 brake lever, 3 power button. */
    private final class HandlebarDiagram extends View {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        HandlebarDiagram() {
            super(host.activity());
            setContentDescription(T.morePinDiagram());
        }
        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight(), bar = h * .5f;
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(ui.dim); paint.setStrokeWidth(ui.dp(6));
            canvas.drawLine(w * .1f, bar, w * .9f, bar, paint);
            paint.setColor(ui.muted); paint.setStrokeWidth(ui.dp(13));
            canvas.drawLine(w * .04f, bar, w * .17f, bar, paint); canvas.drawLine(w * .83f, bar, w * .96f, bar, paint);
            // Brake lever under the left grip.
            paint.setStrokeWidth(ui.dp(4)); canvas.drawLine(w * .22f, bar + ui.dp(6), w * .06f, bar + ui.dp(20), paint);
            // Turn-signal switch pod beside the left grip.
            paint.setStyle(Paint.Style.FILL); paint.setColor(ui.raised);
            RectF pod = new RectF(w * .21f, bar - ui.dp(15), w * .21f + ui.dp(22), bar + ui.dp(4));
            canvas.drawRoundRect(pod, ui.dp(5), ui.dp(5), paint);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(ui.dp(1.2f)); paint.setColor(ui.muted);
            canvas.drawRoundRect(pod, ui.dp(5), ui.dp(5), paint);
            // Centre display with its power button.
            paint.setStyle(Paint.Style.FILL); paint.setColor(ui.raised);
            canvas.drawCircle(w / 2, bar, ui.dp(15), paint);
            paint.setStyle(Paint.Style.STROKE); paint.setColor(ui.muted); canvas.drawCircle(w / 2, bar, ui.dp(15), paint);
            ui.drawIcon(canvas, "power", ui.text, w / 2, bar, ui.dp(16));
            marker(canvas, "1", pod.centerX(), pod.top - ui.dp(14));
            marker(canvas, "2", w * .1f, bar + ui.dp(36));
            marker(canvas, "3", w / 2, bar - ui.dp(32));
        }
        private void marker(Canvas canvas, String number, float x, float y) {
            float r = ui.dp(10);
            paint.setStyle(Paint.Style.FILL); paint.setColor(ui.accent); canvas.drawCircle(x, y, r, paint);
            paint.setColor(ui.onAccent); paint.setTypeface(ui.bold()); paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(r * 1.15f);
            canvas.drawText(number, x, y - (paint.descent() + paint.ascent()) / 2, paint);
        }
    }

    // ---- Experimental controls ------------------------------------------------------------

    /**
     * Controls the official app doesn't offer for this model (ModelRegistry.experimental), most likely to work first,
     * then Help verify: controls it does offer that Volt hasn't verified here (ModelRegistry.verifiable). A row says
     * whether the control is untested or what its last try read back (journalled, so it survives restarts); Try opens
     * its sheet (try, readback, "Did you see it change?") and Set back returns a kept try's earlier value in one tap.
     * Ordinary Scooter rows are unchanged. Rows rebuild when the model or the fields this scooter reports change.
     */
    private void lab() {
        LinearLayout intro = ui.card(); intro.setOrientation(LinearLayout.HORIZONTAL); intro.setGravity(Gravity.TOP);
        intro.setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16)); intro.setTag("lab-intro");
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(ui.dp(34), ui.dp(34)); ip.rightMargin = ui.dp(14);
        intro.addView(ui.iconSquare("lab", ui.orange), ip);
        LinearLayout words = column();
        TextView title = labTitle = ui.text(T.moreLabUntested(), 16, true);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        words.addView(title);
        labIntro = ui.text("", 13.5f, false); labIntro.setTextColor(ui.muted); labIntro.setPadding(0, ui.dp(5), 0, 0);
        words.addView(labIntro);
        intro.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        cardDestination.addView(intro);
        labConnect = ui.button(T.moreConnect(), host::connect);
        cardDestination.addView(labConnect);
        // What holds every row right now (moving, locked, recording…), said once instead of on each row.
        labNotice = ui.text("", 14, false); labNotice.setTag("lab-notice"); labNotice.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12));
        labNotice.setBackground(ui.shape(Ui.withAlpha(ui.accent, ui.dark ? 30 : 18), 16, Ui.withAlpha(ui.accent, ui.dark ? 90 : 70)));
        labNotice.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-1, -2); np.bottomMargin = ui.dp(4);
        cardDestination.addView(labNotice, np);
        labList = column(); cardDestination.addView(labList, new LinearLayout.LayoutParams(-1, -2));
        labFoot = footnote("");
        LinearLayout tools = group();
        row(tools, "stats", T.moreLabEvidence(), null, this::commandEvidence).view.setTag("lab-evidence");
        dangerRow(tools, T.moreLabClearTries(), this::clearTries).view.setTag("lab-clear");
    }
    /** The owner clears this scooter's finished tries: their results, report entries and Set back. Unfinished ones stay. */
    private void clearTries() {
        confirm(T.moreLabClearTitle(), T.moreLabClearBody(),
                T.moreClear(), () -> { int n = service().ble.clearTries(); host.toast(n == 0 ? T.moreLabClearedNone() : T.moreLabCleared(n, n)); refresh(); });
    }
    private void refreshLab(ScooterService s) {
        BleController ble = s.ble; boolean ready = ble.ready();
        DeviceDescriptor d = ble.descriptor(); ModelRegistry.Resolution r = ModelRegistry.resolve(d);
        String model = r.layer + "|" + r.matchedPrefix + "|" + r.conflict + "|" + d.rulePid() + "|" + d.pidSource();
        if (!model.equals(labModelKey)) { labModelKey = model; labKeys = ModelRegistry.experimental(d); verifyKeys = ModelRegistry.verifiable(d); }
        // Split into controls to try, to help verify, and this scooter doesn't report; rebuild only when that changes.
        List<String> tryable = new ArrayList<>(), verify = new ArrayList<>(), absent = new ArrayList<>();
        for (String key : labKeys) {
            CapabilityResolver.Decision decision = ble.experimentalDecision(key);
            if (!decision.experimental()) continue;
            (ready && decision.reason == CapabilityResolver.Reason.FIELD_ABSENT ? absent : tryable).add(key);
        }
        for (String key : verifyKeys) {
            CapabilityResolver.Decision decision = ble.verifyDecision(key);
            if (decision.support != CapabilityResolver.Support.KNOWN_ENCODING) continue;
            (ready && decision.reason == CapabilityResolver.Reason.FIELD_ABSENT ? absent : verify).add(key);
        }
        String signature = model + tryable + verify + absent;
        if (!signature.equals(labSignature)) { labSignature = signature; buildLab(r, d, tryable, verify, absent); }
        boolean experimental = !tryable.isEmpty() || absent.stream().anyMatch(labKeys::contains), rows = experimental || !verify.isEmpty() || !absent.isEmpty();
        labTitle.setText(experimental ? T.moreLabUntested() : rows ? T.moreLabHelpVerify() : T.moreLabNothing());
        labIntro.setText(experimental ? T.moreLabIntroUntested(labModel(d))
                : rows ? T.moreLabIntroVerify(labModel(d)) : labEmpty(r, d));
        boolean offeredElsewhere = r.layer == ModelRegistry.Layer.OEM_GROUP || r.layer == ModelRegistry.Layer.UT5_MAX;
        String kept = rows ? T.moreLabKept() : "";
        labFoot.setText((r.layer == ModelRegistry.Layer.OEM_GROUP
                ? T.moreLabFootOem()
                : offeredElsewhere ? T.moreLabFootElsewhere() : T.moreLabFoot()) + kept);
        labConnect.setVisibility(ble.state() == BleController.State.DISCONNECTED ? View.VISIBLE : View.GONE);
        CapabilityResolver.Reason held = null;
        List<String> live = new ArrayList<>(tryable); live.addAll(verify);
        for (String key : live) { CapabilityResolver.Reason reason = ble.tryDecision(key).reason; if (held == null && LAB_HELD.contains(reason)) held = reason; }
        // Disconnected, the Connect button says it; while connecting, the notice does.
        String notice = held == null || held == CapabilityResolver.Reason.NOT_CONNECTED && labConnect.getVisibility() == View.VISIBLE ? ""
                : held == CapabilityResolver.Reason.NOT_CONNECTED ? T.moreConnecting() : labHeld(held);
        labNotice.setText(notice); labNotice.setVisibility(notice.isEmpty() ? View.GONE : View.VISIBLE);
        for (LabRow row : labRows) refreshLabRow(ble, row);
        if (labSheet != null) labSheet.refresh();
    }
    private void buildLab(ModelRegistry.Resolution r, DeviceDescriptor d, List<String> tryable, List<String> verify, List<String> absent) {
        labList.removeAllViews(); labRows.clear();
        LinearLayout destination = cardDestination; cardDestination = labList;
        try {
            if (!tryable.isEmpty()) {
                label(T.moreLabTryHere());
                LinearLayout list = group(); list.setTag("lab-try");
                for (String key : tryable) labRows.add(labRow(list, ScooterCommands.find(key), true));
            }
            if (!verify.isEmpty()) {
                label(T.moreLabHelpVerify());
                LinearLayout list = group(); list.setTag("lab-verify");
                for (String key : verify) labRows.add(labRow(list, ScooterCommands.find(key), true));
                footnote(T.moreLabVerifyNote());
            }
            if (!absent.isEmpty()) {
                label(T.moreLabAbsent());
                LinearLayout list = group(); list.setTag("lab-absent");
                for (String key : absent) labRows.add(labRow(list, ScooterCommands.find(key), false));
                footnote(T.moreLabAbsentNote());
            }
        } finally { cardDestination = destination; }
    }
    private static String labEmpty(ModelRegistry.Resolution r, DeviceDescriptor d) {
        switch (r.layer) {
            case UNSUPPORTED_PRODUCT: case UNSUPPORTED_TRANSPORT: return T.moreLabEmptyProduct();
            case OEM_GROUP: return T.moreLabEmptyOem(labModel(d));
            case UT5_MAX: return T.moreLabEmptyUt5();
            default:
                if (r.conflict) return T.moreLabEmptyConflict();
                if (d.pidSource() == DeviceDescriptor.PidSource.ADVERTISED) return T.moreLabEmptyUnlisted();
                if (d.pidSource() == DeviceDescriptor.PidSource.SERIAL) return T.moreLabEmptyIdentify();
                return T.moreLabEmptyConnect();
        }
    }
    /** "the UT5 MAX", "the N65i", "Model 24431" or "your model". */
    private static String labModel(DeviceDescriptor d) {
        String name = ModelRegistry.modelName(d); if (!name.isEmpty()) return T.moreLabModelNamed(name);
        String line = ModelRegistry.modelLine(d); return line.isEmpty() ? T.moreLabModelYours() : line;
    }
    /** The value as the rider reads it: another model's labels, speeds in the rider's units, 0 km/h kick-start as Standing start. */
    private String labValue(ScooterCommands.Feature f, CapabilityResolver.Decision d, int value) {
        if (f.key.equals("start_speed") && value == 0) return T.moreLabStandingStart();
        Msg oem = d.oemText(value);
        return oem != null ? oem.toString() : DisplayUnits.featureValueText(f, value, settings().getString("units", "mi"));
    }
    /** labValue's English id (oemLabel, or featureValue), which CompatibilityPanel.question chooses its sentence by; never shown. */
    private String labId(ScooterCommands.Feature f, CapabilityResolver.Decision d, int value) {
        String oem = d.oemLabel(value);
        return oem != null ? oem : DisplayUnits.featureValue(f, value, settings().getString("units", "mi"));
    }
    /** One experimental row: icon, name, status pill and current value, and a Try (or Set back) pill. */
    private LabRow labRow(LinearLayout group, ScooterCommands.Feature f, boolean reported) {
        if (group.getChildCount() > 0) ui.divider(group);
        LinearLayout line = row(); line.setMinimumHeight(ui.dp(reported ? 64 : 52)); line.setPadding(0, ui.dp(10), 0, ui.dp(10));
        line.setTag("lab-row-" + f.key);
        RowIcon icon = new RowIcon(ui.featureIcon(f.key)); icon.setEnabled(reported);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(ui.dp(34), ui.dp(34)); ip.rightMargin = ui.dp(12);
        line.addView(icon, ip);
        LinearLayout words = column();
        TextView title = ui.text(f.label(), 15, false); if (!reported) title.setTextColor(ui.muted);
        title.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); words.addView(title);
        LadderPill status = null; TextView now = null, action = null;
        if (reported) {
            Flow meta = new Flow(); meta.setPadding(0, ui.dp(6), 0, 0);
            status = new LadderPill(); now = ui.text("", 12.5f, false); now.setTextColor(ui.muted);
            meta.addView(status); meta.addView(now); words.addView(meta);
            meta.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        }
        line.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        if (reported) {
            action = actionPill();
            LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-2, -2); ap.leftMargin = ui.dp(10);
            line.addView(action, ap);
            clickable(line, () -> openTry(f));
        } else { line.setFocusable(true); line.setContentDescription(T.moreLabAbsentRow(f.label())); }
        group.addView(line, new LinearLayout.LayoutParams(-1, -2));
        LabRow row = new LabRow(f, line, status, now, action);
        if (action != null) action.setOnClickListener(v -> labAction(row));
        return row;
    }
    /** Red action pill (Try / Set back); grey while unavailable. The touch target stays 48dp tall. */
    private TextView actionPill() {
        TextView pill = ui.text(T.moreLabTry(), 13, true); pill.setTypeface(ui.semibold());
        pill.setGravity(Gravity.CENTER); pill.setMinHeight(ui.dp(48)); pill.setMinWidth(ui.dp(64)); pill.setMaxLines(1);
        pill.setTextColor(new ColorStateList(new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}}, new int[] {ui.dim, ui.onAccent}));
        StateListDrawable fill = new StateListDrawable();
        fill.addState(new int[] {-android.R.attr.state_enabled}, ui.shape(ui.raised, 100, 0));
        fill.addState(new int[] {}, ui.shape(ui.accent, 100, 0));
        pill.setBackground(new InsetDrawable(new RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(Color.WHITE, 45)), fill, null), 0, ui.dp(7), 0, ui.dp(7)));
        // After the background: an inset background replaces the view's padding with its own.
        pill.setPadding(ui.dp(16), 0, ui.dp(16), 0);
        pill.setFocusable(true); pill.setEnabled(false);
        return pill;
    }
    private void refreshLabRow(BleController ble, LabRow row) {
        if (row.status == null) return;
        String key = row.feature.key; CapabilityResolver.Decision d = ble.tryDecision(key);
        Integer current = ble.ready() ? ble.featureValue(key) : null, before = ble.experimentBaseline(key);
        labStatus(ble, key, row.status, false);
        boolean back = labBack(d, current, before), review = labUnfinished(ble, key) != null;
        String text = current == null ? "" : back ? T.moreLabNowWas(labValue(row.feature, d, current), labValue(row.feature, d, before)) : T.moreLabNow(labValue(row.feature, d, current));
        if (!d.available() && d.reason != CapabilityResolver.Reason.BUSY && !LAB_HELD.contains(d.reason)) text = text.isEmpty() ? T.morePlain(d.reasonText()) : T.moreLabValueReason(text, d.reasonText());
        row.now.setText(text); row.now.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
        // Ride-blocked stays tappable so the tap can say why; the write gate still refuses it.
        boolean usable = review || (d.available() || d.blockedByRide()) && ble.pending() == null;
        row.action.setText(review ? T.moreLabReview() : back ? T.moreLabSetBack() : T.moreLabTry()); row.action.setEnabled(usable); row.action.setClickable(usable);
        row.action.setContentDescription(review ? T.moreLabReviewRow(row.feature.label.toLowerCase(Locale.ROOT))
                : back ? T.moreLabSetBackRow(row.feature.label.toLowerCase(Locale.ROOT), labValue(row.feature, d, before))
                : T.moreLabTryRow(row.feature.label.toLowerCase(Locale.ROOT)));
        row.line.setContentDescription(text.isEmpty() ? T.moreLabRow(row.feature.label(), row.status.getContentDescription()) : T.moreLabRowValue(row.feature.label(), row.status.getContentDescription(), text));
    }
    /** Conditions that hold every experimental row at once; the section says them once (labHeld). */
    private static final java.util.EnumSet<CapabilityResolver.Reason> LAB_HELD = java.util.EnumSet.of(CapabilityResolver.Reason.NOT_CONNECTED,
            CapabilityResolver.Reason.STATUS_STALE, CapabilityResolver.Reason.MOVING, CapabilityResolver.Reason.NO_MOTION_REPORT, CapabilityResolver.Reason.WARNING_ACTIVE,
            CapabilityResolver.Reason.LOCKED, CapabilityResolver.Reason.OWNER_ONLY, CapabilityResolver.Reason.DISCOVERY_ACTIVE, CapabilityResolver.Reason.TEST_ACTIVE,
            CapabilityResolver.Reason.RECORDING);
    private static String labHeld(CapabilityResolver.Reason reason) {
        switch (reason) {
            case NOT_CONNECTED: return T.moreLabHeldConnect();
            case STATUS_STALE: return T.moreLabHeldStale();
            case MOVING: return T.moreLabHeldMoving();
            case NO_MOTION_REPORT: return T.moreLabHeldParked();
            case WARNING_ACTIVE: return T.moreLabHeldWarning();
            case LOCKED: return T.moreLabHeldLocked();
            case OWNER_ONLY: return T.moreLabHeldOwner();
            case DISCOVERY_ACTIVE: return T.moreLabHeldDiscovery();
            case TEST_ACTIVE: return T.moreLabHeldTest();
            case RECORDING: return T.moreLabHeldRecording();
            default: return CapabilityResolver.text(reason);
        }
    }
    /** Set back is offered once a try left the control on another valid value than before the first try. */
    private static boolean labBack(CapabilityResolver.Decision d, Integer current, Integer before) {
        return before != null && current != null && !before.equals(current) && d.variant != null && d.variant.allows(before);
    }
    /**
     * The running try of this control, or what its last journalled try read back, or that it is untested (experimental)
     * or unverified (Help verify) here, as quiet orange words rather than a pill.
     */
    private void labStatus(BleController ble, String key, LadderPill pill, boolean sheet) {
        GuidedTest live = labLive(ble, key);
        TestJournal.Entry last = ble.simulating() ? null : ble.latestTry(key);
        boolean verify = !ble.experimentalDecision(key).experimental();
        if (live != null) {
            switch (live.phase()) {
                // The sheet asks the question itself; its fact line just says what the try waits for.
                case OBSERVE: if (sheet) pill.show(ui.orange, true, T.moreLabStatusWaiting(), T.moreLabStatusWaitingShort()); else pill.show(ui.orange, true, T.moreLabStatusAsk(), T.moreLabStatusAskShort()); return;
                case RESTORE_PENDING: case RESTORE_AWAIT: pill.show(ui.muted, false, T.moreLabStatusSettingBack()); return;
                default: pill.show(ui.muted, false, T.moreLabStatusChecking()); return;
            }
        }
        // The simulator changes only its own memory and reads nothing back: no scooter evidence either way.
        if (ble.simulating() && ble.experimentBaseline(key) != null) { pill.quiet(ui.muted, T.moreLabStatusSimulated(), T.moreLabStatusSimulatedShort()); return; }
        if (last != null && !last.resolved) { pill.show(ui.danger, true, T.moreLabStatusUnfinished(), T.moreLabStatusUnfinishedShort()); return; }
        if (last == null) {
            if (sheet) pill.quiet(ui.muted, T.moreLabStatusNotTried());
            else if (verify) pill.quiet(ui.orange, T.moreLabStatusUnverified(), T.moreLabStatusUnverifiedShort(), T.moreLabStatusUnverifiedCompact());
            else pill.quiet(ui.orange, T.moreLabStatusUntested(), T.moreLabStatusUntestedShort(), T.moreLabStatusUntestedCompact());
            return;
        }
        switch (CompatibilityReport.tryResult(last)) {
            case "worked_here": pill.show(ui.ok, true, T.moreLabStatusWorked(), T.moreLabStatusWorkedShort()); return;
            case "refused": pill.show(ui.danger, true, T.moreLabStatusRefused(), T.moreLabStatusRefusedShort()); return;
            case "other_value": pill.show(ui.orange, true, T.moreLabStatusOtherValue(), T.moreLabStatusOtherValueShort()); return;
            case "not_sent": pill.show(ui.muted, false, T.moreLabStatusNotSent()); return;
            case "no_change_seen": pill.show(ui.muted, false, T.moreLabStatusNoChange(), T.moreLabStatusNoChangeShort()); return;
            // Sent, but no conclusive readback (or interrupted): never called no change or not sent.
            default: pill.show(ui.orange, true, sheet ? T.moreLabStatusUnconfirmedSheet() : T.moreLabStatusUnconfirmed(), T.moreLabStatusUnconfirmed());
        }
    }
    /** The running (not finished) try of this control, or null. */
    private static GuidedTest labLive(BleController ble, String key) {
        GuidedTest t = ble.guidedTest();
        return t != null && !t.terminal() && t.keeps() && t.feature.equals(key) ? t : null;
    }
    /** This control's latest try when it didn't finish (it waits for the owner's review), or null. */
    private static TestJournal.Entry labUnfinished(BleController ble, String key) {
        if (ble.simulating() || labLive(ble, key) != null) return null;
        TestJournal.Entry e = ble.latestTry(key);
        return e != null && !e.resolved ? e : null;
    }
    /** An unfinished try is reviewed where every unfinished test is: Restore, Keep or Dismiss. */
    private void reviewTry() {
        if (labSheet != null) labSheet.dialog.dismiss();
        openCompatibility(true);
    }
    private void labAction(LabRow row) {
        BleController ble = service() == null ? null : service().ble; if (ble == null) return;
        if (labUnfinished(ble, row.feature.key) != null) { reviewTry(); return; }
        CapabilityResolver.Decision d = ble.tryDecision(row.feature.key);
        Integer current = ble.ready() ? ble.featureValue(row.feature.key) : null, before = ble.experimentBaseline(row.feature.key);
        if (labBack(d, current, before)) labSetBack(row.feature); else openTry(row.feature);
    }
    private boolean labSend(ScooterCommands.Feature f, int value) {
        BleController ble = service().ble; boolean sent = ble.setExperimental(f.key, value);
        if (!ble.message().isEmpty() && !sent) host.toast(ble.displayMessage().toString());
        refresh(); return sent;
    }
    private boolean labSetBack(ScooterCommands.Feature f) {
        BleController ble = service().ble; boolean sent = ble.setBack(f.key);
        if (!ble.message().isEmpty() && !sent) host.toast(ble.displayMessage().toString());
        refresh(); return sent;
    }
    void inspectQuickFeature(ScooterCommands.Feature feature) {
        if (!closed && feature != null && service() != null && service().ble.tryDecision(feature.key).support == CapabilityResolver.Support.KNOWN_ENCODING) openTry(feature);
    }
    private void openTry(ScooterCommands.Feature feature) {
        if (closed || service() == null) return;
        if (labSheet != null) labSheet.dialog.dismiss();
        labSheet = new LabSheet(feature);
    }
    /** A status pill that shortens its wording (full, then shorter) instead of wrapping or clipping when space is tight. */
    private final class LadderPill extends TextView {
        private String[] ladder = {""}; private String style = "";
        LadderPill() {
            super(host.activity());
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11.5f * ui.textScale); setTypeface(ui.semibold());
            setMaxLines(1); setIncludeFontPadding(false); setGravity(Gravity.CENTER_VERTICAL);
            setPadding(ui.dp(9), ui.dp(4), ui.dp(9), ui.dp(4));
        }
        void show(int color, boolean filled, String... words) {
            if (restyle("pill" + color + filled)) {
                setTextColor(filled ? color : ui.muted);
                setBackground(ui.shape(filled ? Ui.withAlpha(color, ui.dark ? 46 : 34) : ui.raised, 100, 0));
                setPadding(ui.dp(9), ui.dp(4), ui.dp(9), ui.dp(4));
            }
            words(words);
        }
        /** Words only, for the resting state, so the result pills stand out. */
        void quiet(int color, String... words) {
            if (restyle("quiet" + color)) { setTextColor(color); setBackground(null); setPadding(0, ui.dp(4), 0, ui.dp(4)); }
            words(words);
        }
        private boolean restyle(String next) { if (next.equals(style)) return false; style = next; return true; }
        private void words(String... words) {
            if (Arrays.equals(ladder, words)) return;
            ladder = words; setContentDescription(words[0]); setText(words[0]); requestLayout();
        }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            if (MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED) {
                int room = MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
                String pick = ladder[ladder.length - 1];
                for (String word : ladder) if (getPaint().measureText(word) <= room) { pick = word; break; }
                if (!pick.contentEquals(getText())) setText(pick);
            }
            super.onMeasure(widthSpec, heightSpec);
        }
    }
    /**
     * One control's Try sheet: why it is untested, the current value and last result, Volt-style radio rows (or a
     * slider for a range), the reason it can't be sent now, Try and Set back. The sheet stays open to show the result.
     */
    private final class LabSheet {
        final ScooterCommands.Feature feature; final AlertDialog dialog; final boolean verify;
        final TextView now, warn, reason, go, back, progress, question, unfinishedText, refusedNote; final LadderPill status;
        final LinearLayout controls, observe, unfinished;
        final List<Ui.RadioDot> dots = new ArrayList<>(); final List<Integer> values = new ArrayList<>(); final List<View> nowTags = new ArrayList<>();
        SeekBar slider; TextView sliderValue; final List<Integer> range = new ArrayList<>();
        Integer selected;
        LabSheet(ScooterCommands.Feature f) {
            feature = f; BleController ble = service().ble; CapabilityResolver.Decision d = ble.tryDecision(f.key);
            verify = !d.experimental();
            final AlertDialog[] self = new AlertDialog[1];
            LinearLayout body = host.sheetContent(f.label(), null, () -> { if (self[0] != null) self[0].dismiss(); });
            body.setTag("lab-sheet");
            LinearLayout caution = row(); caution.setGravity(Gravity.TOP); caution.setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14));
            caution.setBackground(ui.shape(Ui.withAlpha(ui.orange, ui.dark ? 26 : 18), 18, Ui.withAlpha(ui.orange, ui.dark ? 80 : 70)));
            caution.addView(ui.icon("lab", 22, ui.orange), new LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)));
            LinearLayout cw = column();
            cw.addView(ui.text(verify ? T.moreLabSheetUnverified(labModel(ble.descriptor())) : T.moreLabSheetUntested(labModel(ble.descriptor())), 15, true));
            TextView why = ui.text(verify ? T.moreLabVerifyWhy()
                    : labWhy(f.key, ble.descriptor()), 13.5f, false);
            why.setTextColor(ui.muted); why.setPadding(0, ui.dp(4), 0, 0); cw.addView(why);
            LinearLayout.LayoutParams cwp = new LinearLayout.LayoutParams(0, -2, 1); cwp.leftMargin = ui.dp(12); caution.addView(cw, cwp);
            body.addView(caution);
            LinearLayout facts = ui.groupCard(); LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, -2); fp.topMargin = ui.dp(12);
            body.addView(facts, fp);
            now = fact(facts, T.moreLabNowLabel(), ui.text("", 15, true));
            ui.divider(facts);
            status = new LadderPill(); fact(facts, T.moreLabLastTry(), status);
            // A refusal is the scooter's own answer: say so plainly, so the same try isn't repeated in hope.
            refusedNote = ui.caption(T.moreLabRefusedNote());
            refusedNote.setPadding(ui.dp(4), ui.dp(10), ui.dp(4), 0); refusedNote.setTag("lab-refused"); refusedNote.setVisibility(View.GONE); body.addView(refusedNote);
            // While a try runs: what Volt is doing, then the owner's answer.
            progress = ui.text("", 14, false); progress.setTextColor(ui.muted); progress.setPadding(ui.dp(4), ui.dp(12), ui.dp(4), ui.dp(4));
            progress.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); progress.setTag("lab-progress"); body.addView(progress);
            observe = column(); observe.setTag("lab-observe"); body.addView(observe);
            observe.addView(ui.sectionLabel(T.moreLabYourAnswer()));
            LinearLayout ask = ui.card(); observe.addView(ask);
            question = ui.text("", 16, true); question.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); ask.addView(question);
            TextView hint = ui.caption(T.moreLabLookHint());
            hint.setPadding(0, ui.dp(6), 0, ui.dp(4)); ask.addView(hint);
            TextView yes = ui.button(T.moreYes(), () -> answer(true)); yes.setTag("lab-observe-yes"); ask.addView(yes);
            LinearLayout split = row(); ask.addView(split);
            TextView no = ui.secondaryButton(T.moreNo(), () -> answer(false)), skip = ui.secondaryButton(T.moreSkip(), () -> answer(null));
            no.setTag("lab-observe-no"); skip.setTag("lab-observe-skip");
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1); lp.rightMargin = ui.dp(6);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, -2, 1); rp.leftMargin = ui.dp(6);
            split.addView(no, lp); split.addView(skip, rp);
            // An interrupted try is reviewed where every unfinished one is.
            unfinished = column(); unfinished.setTag("lab-unfinished"); body.addView(unfinished);
            unfinishedText = ui.text(T.moreLabUnfinishedText(), 14, false);
            unfinishedText.setPadding(ui.dp(4), ui.dp(12), ui.dp(4), ui.dp(4)); unfinished.addView(unfinishedText);
            TextView review = ui.button(T.moreLabReview(), MoreScreen.this::reviewTry); review.setTag("lab-review"); unfinished.addView(review);
            controls = column(); body.addView(controls);
            // A range without listed values (the UT5 MAX speed caps) is a slider; anything else is radio rows.
            boolean sliding = d.variant != null && d.variant.values.length == 0;
            controls.addView(ui.sectionLabel(sliding ? T.moreLabChooseLimit() : T.moreLabChooseValue()));
            if (sliding) {
                LinearLayout card = ui.card(); controls.addView(card);
                for (int n = d.variant.min; n <= d.variant.max; n += d.variant.step) if (d.variant.allows(n)) range.add(n);
                sliderValue = ui.text("", 16, true); card.addView(sliderValue);
                slider = new SeekBar(host.activity()); slider.setMax(Math.max(0, range.size() - 1));
                slider.setProgressTintList(ColorStateList.valueOf(ui.accent)); slider.setThumbTintList(ColorStateList.valueOf(ui.accent));
                slider.setContentDescription(f.label); slider.setTag("lab-slider");
                LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, ui.dp(48)); card.addView(slider, sp);
                slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override public void onProgressChanged(SeekBar s, int n, boolean user) { if (user && n < range.size()) { selected = range.get(n); refresh(); } }
                    @Override public void onStartTrackingTouch(SeekBar s) { }
                    @Override public void onStopTrackingTouch(SeekBar s) { }
                });
            } else {
                LinearLayout list = ui.groupCard(); controls.addView(list);
                for (int value : d.values()) { if (list.getChildCount() > 0) ui.divider(list); list.addView(option(d, value)); }
            }
            // The ordinary rows' cautions (kick-start below 3 km/h, traction control or motor braking off) apply here too.
            warn = ui.text("", 13.5f, false); warn.setTextColor(ui.orange); warn.setPadding(ui.dp(4), ui.dp(8), ui.dp(4), 0); warn.setTag("lab-caution");
            warn.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); controls.addView(warn);
            reason = ui.text("", 13.5f, false); reason.setTextColor(ui.danger); reason.setPadding(ui.dp(4), ui.dp(4), ui.dp(4), 0);
            reason.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); controls.addView(reason);
            go = ui.button(T.moreLabTry(), this::send); go.setTag("lab-try-button"); controls.addView(go);
            back = ui.secondaryButton(T.moreLabSetBack(), this::setBack); back.setTag("lab-back-button"); controls.addView(back);
            TextView note = ui.caption(T.moreLabSheetNote());
            note.setPadding(ui.dp(4), ui.dp(8), ui.dp(4), 0); body.addView(note);
            if (d.variant != null) {
                TextView source = ui.caption(String.format(Locale.ROOT, "Command 0x%02X%s · status byte %d", d.variant.cmd,
                        d.variant.selector.length == 0 ? "" : ", selector " + d.variant.selector[0], d.variant.offset));
                source.setTextColor(ui.dim); source.setPadding(ui.dp(4), ui.dp(6), ui.dp(4), 0); body.addView(source);
            }
            dialog = host.showSheet(body); self[0] = dialog; dialogs.add(dialog);
            dialog.setOnDismissListener(x -> {
                dialogs.remove(dialog); if (labSheet == this) labSheet = null;
                // Leaving the question unanswered counts as Skip, so the try doesn't hold every other change.
                BleController b = service() == null ? null : service().ble;
                GuidedTest t = b == null ? null : labLive(b, feature.key);
                if (t != null && t.phase() == GuidedTest.Phase.OBSERVE) { b.testEffect(null); refresh(); }
            });
            refresh();
        }
        private TextView fact(LinearLayout card, String label, TextView value) {
            LinearLayout line = row(); line.setMinimumHeight(ui.dp(50)); line.setPadding(ui.dp(2), ui.dp(8), ui.dp(2), ui.dp(8));
            TextView name = ui.text(label, 14, false); name.setTextColor(ui.muted);
            line.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            value.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(-2, -2); vp.leftMargin = ui.dp(12); line.addView(value, vp);
            card.addView(line); return value;
        }
        /** A radio row like Volt's other choice sheets: the value, "Now" beside the current one, then the ring. */
        private View option(CapabilityResolver.Decision d, int value) {
            LinearLayout line = row(); line.setMinimumHeight(ui.dp(56)); line.setPadding(ui.dp(4), ui.dp(10), ui.dp(4), ui.dp(10));
            line.setTag("lab-option-" + value);
            String label = labValue(feature, d, value);
            TextView name = ui.text(label, 16, false); name.setMaxLines(2); name.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            line.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            TextView tag = ui.text(T.moreLabNowLabel(), 12, false); tag.setTextColor(ui.muted); tag.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2); tp.leftMargin = ui.dp(10); line.addView(tag, tp);
            Ui.RadioDot dot = ui.radioDot();
            LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)); dp.leftMargin = ui.dp(14); line.addView(dot, dp);
            line.setClickable(true); line.setFocusable(true); line.setContentDescription(label);
            line.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(ui.accent, 26)), null, ui.shape(Color.WHITE, 10, 0)));
            line.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View v, android.view.accessibility.AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(v, info);
                    info.setClassName(android.widget.RadioButton.class.getName()); info.setCheckable(true); info.setChecked(dot.checked);
                }
            });
            line.setOnClickListener(v -> { selected = value; refresh(); });
            dots.add(dot); values.add(value); nowTags.add(tag);
            return line;
        }
        void refresh() {
            BleController ble = service() == null || closed ? null : service().ble;
            CapabilityResolver.Decision d = ble == null ? null : ble.tryDecision(feature.key);
            if (d == null || d.support != CapabilityResolver.Support.KNOWN_ENCODING) { dialog.dismiss(); return; }
            Integer current = ble.ready() ? ble.featureValue(feature.key) : null, before = ble.experimentBaseline(feature.key);
            now.setText(current == null ? "—" : labValue(feature, d, current));
            labStatus(ble, feature.key, status, true);
            GuidedTest live = labLive(ble, feature.key), shown = ble.guidedTest();
            boolean asking = live != null && live.phase() == GuidedTest.Phase.OBSERVE, held = labUnfinished(ble, feature.key) != null;
            String doing = live == null ? "" : asking ? "" : live.restoring() ? (live.verifying() ? T.moreLabCheckingUnchanged() : T.moreLabSettingBackTo(labValue(feature, d, live.baseline())))
                    : live.phase() == GuidedTest.Phase.PRECHECK || live.phase() == GuidedTest.Phase.BASELINE ? T.moreLabReadingCurrent()
                    : T.moreLabSending(labValue(feature, d, live.target));
            // A try that ended without writing (the value changed, or a condition failed) says why once.
            if (live == null && shown != null && shown.terminal() && shown.keeps() && shown.feature.equals(feature.key) && shown.nothingWritten() && !shown.note().isEmpty()) doing = shown.noteMsg().toString();
            progress.setText(doing); progress.setVisibility(doing.isEmpty() ? View.GONE : View.VISIBLE);
            observe.setVisibility(asking ? View.VISIBLE : View.GONE);
            if (asking) question.setText(verify ? CompatibilityPanel.question(feature.key, labId(feature, d, live.target), labValue(feature, d, live.target)) : T.moreLabDidYouSee());
            unfinished.setVisibility(held ? View.VISIBLE : View.GONE);
            controls.setVisibility(live == null && !held ? View.VISIBLE : View.GONE);
            if (selected == null && current != null) {
                for (int v : values) if (v != current) { selected = v; break; }
                if (selected == null && range.size() > 1) selected = range.get(range.indexOf(current) == 0 ? 1 : 0);
            }
            for (int i = 0; i < values.size(); i++) {
                dots.get(i).set(values.get(i).equals(selected));
                nowTags.get(i).setVisibility(values.get(i).equals(current) ? View.VISIBLE : View.GONE);
            }
            if (slider != null) {
                int at = selected == null ? (current == null ? -1 : range.indexOf(current)) : range.indexOf(selected);
                if (at >= 0 && slider.getProgress() != at) slider.setProgress(at);
                sliderValue.setText(selected == null ? "—" : labValue(feature, d, selected));
            }
            boolean busy = ble.pending() != null || ble.testing();
            boolean change = selected != null && current != null && !selected.equals(current) && d.allows(selected);
            TestJournal.Entry last = ble.simulating() || live != null ? null : ble.latestTry(feature.key);
            boolean refused = last != null && last.resolved && "refused".equals(CompatibilityReport.tryResult(last));
            refusedNote.setVisibility(refused && !held ? View.VISIBLE : View.GONE);
            go.setText(selected == null ? (refused ? T.commonTryAgain() : T.moreLabTry()) : current != null && selected.equals(current) ? T.moreLabAlready(labValue(feature, d, selected))
                    : (refused ? T.moreLabTryAgainValue(labValue(feature, d, selected)) : T.moreLabTryValue(labValue(feature, d, selected))));
            go.setContentDescription(go.getText()); go.setEnabled(d.available() && !busy && change);
            String caution = change ? ModelRegistry.experimentalCaution(feature.key, selected, ble.descriptor()) : null;
            // From a setting shown but not offered here (kick-start below 3 km/h), a try moves to an offered value for good.
            if (caution == null && change && d.variant != null && !d.variant.allows(current) && d.variant.shows(current))
                caution = T.moreLabCantSetBack(labValue(feature, d, current));
            warn.setText(caution == null ? "" : caution); warn.setVisibility(caution == null ? View.GONE : View.VISIBLE);
            String why = d.available() || d.reason == CapabilityResolver.Reason.BUSY ? "" : d.reasonText();
            reason.setText(why); reason.setVisibility(why.isEmpty() ? View.GONE : View.VISIBLE);
            boolean showBack = labBack(d, current, before);
            // After a try, Set back is the one action unless another value is chosen.
            go.setVisibility(showBack && (selected == null || selected.equals(current) || selected.equals(before)) ? View.GONE : View.VISIBLE);
            back.setVisibility(showBack ? View.VISIBLE : View.GONE);
            if (showBack) { back.setText(T.moreLabSetBackTo(labValue(feature, d, before))); back.setContentDescription(back.getText()); }
            back.setEnabled(d.available() && !busy);
        }
        void answer(Boolean saw) {
            BleController ble = service() == null ? null : service().ble;
            if (ble == null || labLive(ble, feature.key) == null) return;
            ble.testEffect(saw); MoreScreen.this.refresh();
        }
        void send() { if (selected != null) labSend(feature, selected); }
        void setBack() { labSetBack(feature); }
    }
    /** Where the official app offers this control instead (OEM tables), in plain words. */
    private static String labWhy(String key, DeviceDescriptor d) {
        // A lower kick-start try beside the region's 3–5 km/h Scooter settings row: the official app does offer it here.
        if (key.equals("start_speed") && ModelRegistry.lowStartBesideOrdinary(d)) return T.moreLabWhyRegionStart();
        int[] n = ModelRegistry.offeredElsewhere(key, d);
        return n[2] > 0 ? T.moreLabWhyOffered(n[2], n[2])
                : T.moreLabWhyNone();
    }
    private void commandEvidence() {
        LinearLayout box = dialogBox(T.moreLabEvidence(), T.moreLabEvidenceHidden());
        Map<String,String> results = service().ble.commandResults();
        if (results.isEmpty()) note(box, T.moreLabNoChanges());
        for (Map.Entry<String,String> e : results.entrySet()) {
            if (e.getKey().equals("clock_sync")) { note(box, T.moreLabEvidenceClock(service().ble.displayResult(e.getKey()))); continue; }
            ScooterCommands.Feature feature;
            try { feature = ScooterCommands.find(e.getKey()); } catch (IllegalArgumentException ignored) { continue; }
            note(box, T.moreLabEvidenceLine(feature.label(), service().ble.displayResult(e.getKey())));
        }
        List<String> events = service().ble.events();
        TextView heading = ui.text(T.moreLabActivity(), 16, true); heading.setPadding(0, ui.dp(14), 0, ui.dp(4)); box.addView(heading);
        StringBuilder lines = new StringBuilder();
        for (int i = Math.max(0, events.size() - 35); i < events.size(); i++) lines.append(events.get(i)).append('\n');
        TextView activity = ui.caption(lines.length() == 0 ? T.moreLabNoActivity() : lines.toString());
        activity.setTextIsSelectable(true); box.addView(activity);
        ScrollView inner = new ScrollView(host.activity()); inner.addView(box);
        AlertDialog dialog = new AlertDialog.Builder(host.activity()).setView(inner).setPositiveButton(T.commonClose(), null).create(); show(dialog);
    }

    // ---- About & permissions ------------------------------------------------------------

    private void privacy() {
        LinearLayout c = ui.card();
        TextView version = ui.text(T.moreAboutVersion(AppVersion.name(host.activity())), 17, true);
        version.setTag("about-version");
        version.setMinHeight(ui.dp(48)); version.setGravity(Gravity.CENTER_VERTICAL);
        c.addView(version);
        TextView about = ui.caption(T.moreAboutTagline());
        about.setPadding(0, ui.dp(6), 0, 0); c.addView(about);
        cardDestination.addView(c);
        label(T.moreAboutPermissions());
        LinearLayout permissions = group();
        nearbyRow = row(permissions, "bluetooth", M.moreAboutNearby(), M.moreAboutNearbySub(), host::requestBluetooth);
        locationRow = row(permissions, "route", M.moreAboutLocation(),
                Build.VERSION.SDK_INT < 31 ? M.moreAboutLocationOld() : M.moreAboutLocationSub(), this::openLocationSettings);
        notificationRow = row(permissions, "sound", M.moreAboutNotifications(), M.moreAboutNotificationsSub(), ()->openAndroidSettings(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS));
        label(T.moreAboutYourData());
        LinearLayout data = ui.card(); cardDestination.addView(data);
        note(data, T.moreAboutDataNote());
        LinearLayout maps = group();
        Row online = row(maps, "map", T.moreAboutOnlineMap(), null, this::mapSettings);
        valueRefreshers.add(() -> online.value(basemapSummary()));
        LinearLayout more = group();
        row(more, "stats", T.moreAboutActivity(), null, this::commandEvidence);
        row(more, "copy", T.moreAboutLicenses(), null, this::licenses);
        row(more, "bluetooth", T.moreAboutConnect(), null, host::connect);
    }

    // ---- Updates ------------------------------------------------------------------------

    /**
     * More's own Updates section, in the menu above the version line (owner, Nov 5): the opt-in automatic check,
     * at most once a day in the background (UpdateCheckJob), and the manual check.
     */
    private void updates() {
        label(T.moreUpdatesTitle());
        LinearLayout box = group();
        if (wide) box.setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4));
        Row[] auto = new Row[1];
        auto[0] = toggleRow(box, "clock", T.moreUpdatesAuto(), T.moreUpdatesAutoSub(), on -> automaticUpdates(auto[0], on));
        auto[0].view.setTag("update_auto");
        // The narrow-label ladder: the full title in up to two lines, else the short one under the Updates header;
        // the title and the switch keep the full wording as their spoken text.
        StatusRungs ladder = new StatusRungs(auto[0].title); ladder.maxLines = 2;
        auto[0].title.addOnLayoutChangeListener(ladder);
        CharSequence[] rungs = {T.moreUpdatesAuto(), T.moreUpdatesAutoShort()};
        ladder.show(rungs);
        // The menu makes room for the last rung's longest word, so the ladder always has a whole-word rung.
        ((RowLine) auto[0].view).fallback = rungs[rungs.length - 1];
        valueRefreshers.add(() -> {
            refreshSwitch(auto[0], UpdateCheckJob.enabled(settings()), true);
            auto[0].toggle.setContentDescription(T.morePlain(T.moreUpdatesAuto()));
        });
        Row check = row(box, "download", T.moreAboutCheckUpdates(), null, this::checkForUpdates);
        check.view.setTag("check-updates");
        // The wide menu pads every row alike, as the section rows above.
        if (wide) for (Row row : new Row[]{auto[0], check}) row.view.setPadding(ui.dp(8), ui.dp(9), ui.dp(8), ui.dp(9));
    }
    /** The switch: on asks GitHub's consent first (once, as the manual check does), then schedules the daily job. */
    private void automaticUpdates(Row row, boolean on) {
        if (closed) return;
        SharedPreferences settings = settings();
        if (!on) {
            settings.edit().putBoolean(UpdateSchedule.AUTO, false).apply();
            UpdateCheckJob.cancel(host.activity()); refreshSwitch(row, false, true); return;
        }
        Runnable enable = () -> {
            settings.edit().putBoolean(UpdateCheck.CONSENT, true).putBoolean(UpdateSchedule.AUTO, true).apply();
            UpdateCheckJob.schedule(host.activity(), settings); refreshSwitch(row, true, true);
            askNotificationPermission();
        };
        if (settings.getBoolean(UpdateCheck.CONSENT, false)) { enable.run(); return; }
        refreshSwitch(row, false, true);
        LinearLayout box = dialogBox(T.moreUpdateAskTitle(),
                T.moreUpdateAskBody());
        AlertDialog dialog = new AlertDialog.Builder(host.activity()).setView(box).create();
        dialogButtons(box, T.commonCancel(), dialog::dismiss, T.moreUpdateCheck(), () -> { dialog.dismiss(); enable.run(); });
        show(dialog);
    }
    /** Android 13 and later: the notice needs the notification permission; Android asks only if it may still ask. */
    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (host.activity().checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        host.activity().requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, UPDATE_NOTICE_PERMISSION);
    }
    /** Its own request code; MainActivity ignores the answer, and the job checks the permission when it posts. */
    private static final int UPDATE_NOTICE_PERMISSION = 5626;

    /** Manual update check. The first time, say what asking GitHub reveals; Volt never downloads or installs (the browser does). */
    void checkForUpdates() {
        if (closed) return;
        SharedPreferences settings = service().settings;
        if (settings.getBoolean(UpdateCheck.CONSENT, false)) { runUpdateCheck(); return; }
        LinearLayout box = dialogBox(T.moreUpdateAskTitle(),
                T.moreUpdateAskBody());
        AlertDialog dialog = new AlertDialog.Builder(host.activity()).setView(box).create();
        dialogButtons(box, T.commonCancel(), dialog::dismiss, T.moreUpdateCheck(), () -> {
            settings.edit().putBoolean(UpdateCheck.CONSENT, true).apply(); dialog.dismiss(); runUpdateCheck();
        });
        show(dialog);
    }
    private void runUpdateCheck() {
        String installed = AppVersion.name(host.activity());
        LinearLayout box = dialogBox(T.moreUpdateChecking(), T.moreUpdateAsking());
        AlertDialog checking = new AlertDialog.Builder(host.activity()).setView(box).setNegativeButton(T.commonCancel(), null).create();
        show(checking);
        Thread worker = new Thread(() -> {
            UpdateCheck.Result result;
            try { result = UpdateCheck.evaluate(installed, UpdateCheck.fetcher.get(UpdateCheck.ENDPOINT, UpdateCheck.userAgent(installed))); }
            catch (java.io.IOException | RuntimeException unreachable) { result = null; }
            UpdateCheck.Result done = result;
            host.activity().runOnUiThread(() -> {
                if (closed || !checking.isShowing()) return;
                checking.dismiss(); updateResult(installed, done);
            });
        }, "volt-update-check");
        worker.setDaemon(true); worker.start();
    }
    private void updateResult(String installed, UpdateCheck.Result r) {
        LinearLayout box;
        AlertDialog.Builder builder = new AlertDialog.Builder(host.activity());
        boolean available = r != null && r.outcome == UpdateCheck.Outcome.AVAILABLE;
        if (available) {
            // Seen here: the automatic check does not announce this version again.
            UpdateCheckJob.seen(settings(), r.latest);
            // Download hands the browser the release's APK; without one in the reply, the release page as before.
            box = dialogBox(T.moreUpdateAvailable(r.latest), r.direct()
                    ? T.moreUpdateAvailableBodyDownload(installed)
                    : T.moreUpdateAvailableBody(installed));
            if (!r.notes.isEmpty()) {
                // The notes with their headings, lists and emphasis (ReleaseNotesView), on a card of their own.
                TextView notes = ReleaseNotesView.of(ui, r.markdown, r.notes); notes.setTextIsSelectable(true);
                notes.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12)); notes.setBackground(ui.shape(ui.bg, 14, 0));
                String language = UpdateCheck.notesLanguageNote();
                if (!language.isEmpty()) { TextView english = ui.caption(language); english.setPadding(0, ui.dp(10), 0, 0); box.addView(english); }
                LinearLayout.LayoutParams card = new LinearLayout.LayoutParams(-1, -2); card.topMargin = ui.dp(12); card.bottomMargin = ui.dp(4);
                notes.setTag("update-notes"); box.addView(notes, card);
            }
        } else if (r != null && r.outcome == UpdateCheck.Outcome.CURRENT) {
            box = dialogBox(T.moreUpdateCurrent(), VersionName.dev(installed)
                    ? T.moreUpdateDev(installed, r.latest)
                    : T.moreUpdateLatest(installed));
        } else {
            box = dialogBox(T.moreUpdateFailed(), r == null
                    ? T.moreUpdateNoAnswer()
                    : T.moreUpdateUnreadable());
        }
        ScrollView inner = new ScrollView(host.activity());
        // A new release: the title, wording and notes scroll above the buttons, which stay in view however long
        // the notes are (the scroll area takes what the dialog has left after them). In a window too short for both
        // (PinnedFooter) the buttons scroll with the notes instead, so the title and notes can always be reached.
        LinearLayout frame = column(), foot = column(), content = column();
        content.addView(box); inner.addView(content);
        if (available) {
            frame.addView(inner, new LinearLayout.LayoutParams(-1, 0, 1));
            foot.setPadding(ui.dp(22), 0, ui.dp(22), ui.dp(10)); frame.addView(foot, new LinearLayout.LayoutParams(-1, -2));
            frame.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                if (footerMisplaced(frame, foot, box, content)) frame.post(() -> {
                    if (!footerMisplaced(frame, foot, box, content)) return;
                    boolean scrolls = foot.getParent() == frame;
                    // Moving the buttons drops their focus (keyboard, D-pad or TalkBack): give it back, in view.
                    View focused = foot.findFocus();
                    View held = accessibilityFocused(foot);
                    ((ViewGroup) foot.getParent()).removeView(foot);
                    (scrolls ? content : frame).addView(foot, new LinearLayout.LayoutParams(-1, -2));
                    if (focused != null) focused.requestFocus();
                    if (held != null) held.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null);
                    if (scrolls && (focused != null || held != null)) foot.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                        @Override public void onLayoutChange(View f, int l, int t, int r2, int b, int ol, int ot, int or, int ob) {
                            foot.removeOnLayoutChangeListener(this);
                            inner.requestChildRectangleOnScreen(foot, new Rect(0, 0, foot.getWidth(), foot.getHeight()), true);
                        }
                    });
                });
            });
        }
        AlertDialog dialog = builder.setView(available ? frame : inner).create();
        if (available)
            dialogButtons(foot, T.commonClose(), dialog::dismiss, r.direct()
                    ? T.moreUpdateDownload()
                    : T.moreUpdateOpen(), () -> { dialog.dismiss(); openRelease(r); });
        else dialog.setButton(AlertDialog.BUTTON_POSITIVE, T.commonOk(), (d, which) -> d.dismiss());
        show(dialog);
    }
    /** The view in this group that holds TalkBack's focus, or null. */
    private static View accessibilityFocused(View view) {
        if (view.createAccessibilityNodeInfo().isAccessibilityFocused()) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = accessibilityFocused(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }
    /** Whether the update dialog's buttons sit on the wrong side of its scroller for the window's height now. */
    private boolean footerMisplaced(LinearLayout frame, LinearLayout foot, View body, LinearLayout content) {
        if (closed || foot.getParent() == null) return false;
        boolean scrolls = PinnedFooter.scrolls(frame.getHeight(), foot.getHeight(), body.getHeight(), ui.dp(PinnedFooter.MIN_BODY_DP));
        return scrolls != (foot.getParent() == content);
    }
    /** The browser gets the APK (safeDownload) or the release page (safePage), never anything else; Volt downloads nothing. */
    private void openRelease(UpdateCheck.Result r) {
        boolean direct = r.direct();
        String url = r.target();
        if (direct ? !UpdateCheck.safeDownload(url) : !UpdateCheck.safePage(url)) return;
        try { host.activity().startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))); }
        catch (android.content.ActivityNotFoundException none) {
            if (direct) host.toast(T.moreUpdateNoBrowserDownload());
            else host.toast(T.moreUpdateNoBrowser());
        }
    }

    /** An email draft to Volt's report address: a prompt, then Volt's and Android's versions, the phone model and the scooter's model name. */
    private void reportIssue() {
        ScooterService s = service(); DeviceDescriptor d = s == null ? null : s.ble.descriptor();
        String version = AppVersion.name(host.activity());
        String body = IssueReport.body(version, Build.VERSION.RELEASE, Build.VERSION.SDK_INT, Build.MANUFACTURER + " " + Build.MODEL,
                ModelRegistry.modelLine(d), d != null && d.simulated);
        if (!host.emailVolt(IssueReport.subject(version), body)) host.toast(T.moreReportNoEmail());
    }

    private void developerOptions() {
        LinearLayout diagnostics = group();
        row(diagnostics, "check", T.moreDevCompat(), T.moreDevCompatSub(),
                () -> openCompatibility(false)).view.setTag("developer-compatibility-check");
        LinearLayout controls = group();
        simulationRow = toggleRow(controls, "scooter", T.moreDevSimulate(), null, enabled -> {
            ScooterService s = service();
            if (closed || s == null) return;
            s.setSimulation(enabled);
            // The service may refuse while a ride or phone recording is active. Always show
            // its actual state, never the tentative state of a performed switch click.
            refreshSimulation(); host.redraw();
        });
        simulationRow.view.setTag("developer-simulation-row");
        simulationRow.toggle.setTag("developer-simulation-toggle");
        simulationModelRow = row(controls, "scooter", M.moreDevSimulatedModel(), null, this::chooseSimulationModel);
        simulationModelRow.view.setTag("developer-simulation-model");
        simulationStatus = footnote(T.moreDevSimulationActive());
        simulationStatus.setTag("developer-simulation-status");
        simulationStatus.setTextColor(ui.orange);
        simulationStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        label(T.moreDevTestLayouts());
        LinearLayout backups = group();
        row(backups, "grid", T.moreDevSlot3(), null, () -> {
            ScooterProfiles.Profile profile = active();
            if (profile == null) { host.toast(T.moreHistoryChooseScooter()); return; }
            confirm(T.moreDevSlot3Title(), T.moreDevSlot3Body(), T.moreDevSlot3Action(), () -> {
                boolean saved = new DashboardConfig(settings(), profile.id).forceExampleSlot();
                host.toast(saved ? T.moreDevSlot3Saved() : T.moreDevSlot3Failed());
            });
        }).view.setTag("developer-example-slot");
    }

    /**
     * Battery & charging › Charge history & recordings (hub layout plan, PR 4): charge recordings, the learned charge
     * curves, the recorded charges, their export and their email to Volt, and the live charging card's Live Update
     * status. Cut out of Developer options with their tags. The charger has one home, Estimates & charger › Charger
     * (owner, #383): the row here only shows it and opens that page, which refuses a change while a charge is recorded.
     */
    private void charging() {
        label(T.moreDevChargeRecording());
        // A recording is only useful with the right charger chosen: show it, and link to its one home (not a second control).
        chargerLinkRow=row(group(),"battery",HubText.chargerRow(),null,()->host.open(HubPages.BATTERY_ESTIMATES));
        chargerLinkRow.view.setTag("charge-charger-selector");
        // Count qualified evidence without claiming every range is ready at a fixed session count.
        chargeCurve=ui.card();chargeCurve.setTag("charge-learning-status");
        LinearLayout curveHead=row();
        TextView curveTitle=ui.text(T.moreDevCurves(),14,false);curveHead.addView(curveTitle,new LinearLayout.LayoutParams(0,-2,1));
        chargeCurveValue=ui.text("",14,true);curveHead.addView(chargeCurveValue);
        chargeCurve.addView(curveHead);
        chargeCurveScope=ui.text("",12.5f,false);chargeCurveScope.setTextColor(ui.muted);chargeCurveScope.setTag("charge-learning-scope");
        chargeCurve.addView(chargeCurveScope);
        cardDestination.addView(chargeCurve);
        LinearLayout capture=group();
        chargeCaptureRow=row(capture,"battery",T.moreDevRecord(),null,()->{
            ScooterService s=service();if(s==null)return;
            if(s.isChargeRecording()){s.stopChargeRecording();refreshCharging();return;}
            String unavailable=s.chargeCaptureUnavailable();if(unavailable!=null){host.toast(unavailable);return;}
            recordChargeDialog(s);
        });chargeCaptureRow.view.setTag("charge-capture-action");
        chargeCaptureStatus=footnote("");chargeCaptureStatus.setTag("charge-capture-status");
        LinearLayout chargeData=group();
        chargeHistoryRow=row(chargeData,"chart",T.moreDevRecordings(), T.moreDevRecordingsSub(),()->{
            if(chargeHistoryDialog!=null)chargeHistoryDialog.close();
            chargeHistoryDialog=new ChargeHistoryDialog(host);chargeHistoryDialog.show();
        });chargeHistoryRow.view.setTag("charge-history-action");
        exportChargeRow=row(chargeData,"download",T.moreDevExport(), T.moreDevExportSub(),()->{
            ScooterService s=service();if(s==null)return;
            if(!s.exportChargeRecordings(file->{
                if(closed||host.activity().isDestroyed())return;
                if(file==null){host.toast(s.chargeCaptureStatus());return;}
                host.saveFile("charge-recordings.json","application/json",file);
            }))exportBusy();
        });exportChargeRow.view.setTag("charge-export-action");
        clearChargeRow=row(chargeData,"trash",T.moreDevClearCharges(),null,()->{
            ScooterService s=service();if(s==null||s.isChargeRecording())return;
            AlertDialog dialog=new AlertDialog.Builder(host.activity()).setTitle(T.moreDevClearChargesTitle())
                    .setMessage(T.moreDevClearChargesBody())
                    .setNegativeButton(T.commonCancel(),null).setPositiveButton(T.moreDevClearRecordings(),(d,which)->s.clearChargeRecordings(this::refreshCharging)).create();show(dialog);
        });clearChargeRow.view.setTag("charge-clear-action");
        // Recordings tune the charging time estimates for this model (owner, #383): say so, and send them in one tap.
        sendChargeRow=row(group(),"export",HubText.chargeSendRow(),null,this::sendChargeRecordings);
        sendChargeRow.view.setTag("charge-send-action");
        footnote(HubText.chargeSendNote().render()).setTag("charge-send-note");
        // Android 16 Live Updates: whether the live charging card is (or last was) promoted; opens the setting.
        liveUpdateRow = row(group(), "bolt", M.chargeLiveUpdateRow(), null, this::openLiveUpdateSettings);
        liveUpdateRow.view.setTag("developer_live_update");
    }

    /**
     * Email to Volt: this scooter's charge recordings, exported as Export charge recordings does, attached to an email
     * to Volt's report address (the report emails' attachment flow). Refused while a charge is being recorded (said in a
     * toast) or while an earlier export is still being written (quiet: a double tap; ChargeSend.refusal).
     */
    private void sendChargeRecordings() {
        ScooterService s = service(); if (s == null) return;
        if (s.exportChargeRecordings(file -> {
            if (closed || host.activity().isDestroyed()) { if (file != null) file.delete(); return; }
            if (file == null) { host.toast(s.chargeCaptureStatus()); return; }
            host.emailChargeRecordings(file);
        })) return;
        // Refused: tell the two reasons apart from the service's state.
        if (ChargeSend.refusal(s.isChargeRecording(), s.chargeExporting()) == ChargeSend.Refusal.STOP_RECORDING)
            host.toast(HubText.chargeSendBusy());
    }

    /** Export charge recordings is refused: a charge is being recorded, or another export is running. */
    private void exportBusy() { host.toast(T.moreDevExportBusy()); }

    /** "Report an issue": More's menu ends with it, and it is Service › Issue reports. */
    private void reportLink(LinearLayout parent) {
        TextView report = ui.text(T.moreReportIssue(), 13.5f, false);
        report.setTypeface(ui.medium()); report.setTextColor(ui.accent); report.setGravity(Gravity.CENTER);
        report.setMinHeight(ui.dp(48)); report.setPadding(ui.dp(16), 0, ui.dp(16), 0);
        report.setFocusable(true); report.setClickable(true); report.setTag("more-report-issue");
        report.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Ui.withAlpha(ui.accent, 30)), null, ui.shape(0xFFFFFFFF, 14, 0)));
        report.setOnClickListener(v -> reportIssue());
        LinearLayout.LayoutParams reportParams = new LinearLayout.LayoutParams(-2, -2);
        reportParams.gravity = Gravity.CENTER_HORIZONTAL; reportParams.bottomMargin = ui.dp(8);
        parent.addView(report, reportParams);
    }

    private void openCompatibility(boolean recovery) {
        compatibilityRecovery = recovery;
        if (compatibilityPanel != null) compatibilityPanel.setRecoveryOnly(recovery);
        selectSection("Compatibility");
    }

    private void chooseSimulationModel() {
        ScooterService s = service();
        if (closed || s == null) return;
        int selected = Math.max(0, java.util.Arrays.asList(SIMULATION_MODELS).indexOf(s.ble.simulationModel()));
        AlertDialog dialog = new AlertDialog.Builder(host.activity()).setTitle(T.moreDevSimulatedModel())
                .setSingleChoiceItems(SIMULATION_MODEL_NAMES, selected, (d, which) -> {
                    ScooterService current = service();
                    if (closed || current == null) return;
                    current.ble.setSimulationModel(SIMULATION_MODELS[which]); d.dismiss();
                    refreshSimulation(); host.redraw();
                }).setNegativeButton(T.commonClose(), null).create(); show(dialog);
    }

    /** Developer options' simulation rows and the simulating note on More's menu. */
    private void refreshSimulation() {
        ScooterService s = service();
        boolean simulating = s != null && s.simulating();
        if (developerIndexRow != null) {
            developerIndexRow.value(simulating ? T.moreDevSimulating() : null);
            developerIndexRow.value.setTextColor(ui.orange);
        }
        if (simulationRow != null) {
            refreshSwitch(simulationRow, simulating, s != null);
            simulationStatus.setVisibility(simulating ? View.VISIBLE : View.GONE);
            int model = s == null ? 0 : Math.max(0, java.util.Arrays.asList(SIMULATION_MODELS).indexOf(s.ble.simulationModel()));
            simulationModelRow.value(model == 0 ? "UT5 MAX" : model == 1 ? "Mapped" : "Unknown");
            simulationModelRow.enabled(s != null);
        }
    }

    /** The Charging section's rows (charging()); nothing while this screen has no Charging section. */
    private void refreshCharging() {
        ScooterService s = service();
        if (liveUpdateRow != null) {
            ChargeLiveUpdate.Status live = s != null ? s.liveUpdateStatus()
                    : ChargeLiveUpdate.status(Build.VERSION.SDK_INT, ScooterService.liveUpdateRequestAvailable(), liveUpdateAllowed(), false, false, false, null);
            liveUpdateRow.sub(ChargeLiveUpdate.label(live));
        }
        if(chargeCaptureRow!=null){
            refreshChargerLink(s);
            boolean capturing=s!=null&&s.isChargeRecording();
            String unavailable=s==null?T.moreDevConnectFirst():s.chargeCaptureUnavailable();
            chargeCaptureRow.title.setText(capturing?T.moreDevStopRecord():T.moreDevRecord());
            chargeCaptureRow.enabled(s!=null&&(capturing||unavailable==null));
            chargeCaptureStatus.setText(capturing||unavailable==null?s.chargeCaptureStatus():unavailable);
            refreshChargeCurve(s);
            clearChargeRow.enabled(s!=null&&!capturing&&!s.chargeExporting());
            exportChargeRow.enabled(s!=null&&!capturing&&!s.chargeExporting());
            chargeHistoryRow.enabled(s!=null&&s.profiles!=null&&s.profiles.active()!=null);
            sendChargeRow.enabled(s!=null&&!s.chargeExporting());
        }
    }

    /** The charger row: the family estimates use and why, as Estimates & charger › Charger shows it (HubText). */
    private void refreshChargerLink(ScooterService s) {
        ScooterService.ChargerBasis basis = s == null ? ScooterService.ChargerBasis.UNKNOWN : s.chargerBasis();
        ChargeSessionStore.Charger effective = s == null ? null : s.effectiveChargeCharger();
        boolean detected = basis == ScooterService.ChargerBasis.DETECTED;
        chargerLinkRow.value((effective == null ? HubText.chargerAutomatic()
                : HubText.chargerValue(effective == ChargeSessionStore.Charger.HIGH_SPEED, detected)).render());
        Msg detail = HubText.chargerDetail(basis == ScooterService.ChargerBasis.RECORDING, detected, s != null && s.isChargeRecording());
        chargerLinkRow.sub(detail == null ? null : detail.render());
    }

    /**
     * "Record with Stock charger?": the charger is picked inside the dialog, and the title follows it.
     * Older recordings beyond the 20 kept are dropped.
     */
    private void recordChargeDialog(ScooterService s) {
        ScooterProfiles.Profile profile = active();
        if (profile == null) { host.toast(T.moreHistoryChooseScooter()); return; }
        final String profileId = profile.id;
        // Capture labels train curves, so only an explicit preference is pre-selected; an
        // Automatic guess must never become a label in one tap. An open panel must never start
        // a different scooter's recording.
        final ChargeSessionStore.Charger[] selected = {s.chargeCharger()};
        LinearLayout box = dialogBox("", T.moreDevRecordBody());
        TextView heading = (TextView) box.getChildAt(0);
        final TextView[] start = new TextView[1];
        Runnable title = () -> {
            heading.setText(selected[0] == null ? T.moreDevWhichCharger()
                    : (selected[0] == ChargeSessionStore.Charger.STOCK ? T.moreDevRecordStock() : T.moreDevRecordFast()));
            if (start[0] != null) { start[0].setEnabled(selected[0] != null); start[0].setAlpha(selected[0] != null ? 1f : .5f); }
        };
        SegmentedSelector picker = new SegmentedSelector(ui, T.moreDevAttached(), new String[]{T.moreDevStock(), T.moreDevHighSpeed()}, choice -> {
            selected[0] = choice == 0 ? ChargeSessionStore.Charger.STOCK : ChargeSessionStore.Charger.HIGH_SPEED;
            title.run();
        });
        picker.setTag("charge-confirmation-selector");
        picker.select(selected[0] == null ? -1 : selected[0] == ChargeSessionStore.Charger.STOCK ? 0 : 1, false);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, -2); pp.topMargin = ui.dp(6); pp.bottomMargin = ui.dp(6);
        box.addView(picker, pp);
        AlertDialog dialog = new AlertDialog.Builder(host.activity()).setView(box).create();
        start[0] = dialogButtons(box, T.commonCancel(), dialog::dismiss, T.moreDevStartRecording(), () -> {
            if (selected[0] == null) return;
            dialog.dismiss();
            ScooterProfiles.Profile current = active();
            if (closed || service() != s || current == null || !profileId.equals(current.id)) {
                host.toast(T.moreDevScooterChanged()); return;
            }
            if (!s.startChargeRecording(selected[0])) host.toast(s.chargeCaptureStatus());
            refreshCharging();
        })[1];
        start[0].setTag("charge-confirmation-start");
        title.run();
        show(dialog, picker::finishMotion);
    }
    /** One completed finish can be useful; broad ranges can need more evidence. */
    private void refreshChargeCurve(ScooterService s) {
        if (chargeCurve == null) return;
        int qualified = s == null ? -1 : s.qualifiedChargeCurves();
        // Count only the family estimates use; while Automatic is undecided, none applies.
        ChargeSessionStore.Charger effective = s == null ? null : s.effectiveChargeCharger();
        String scope = qualified < 0 ? "" : effective == null ? T.moreDevNotApplied()
                : effective == ChargeSessionStore.Charger.STOCK ? T.moreDevStockCharges() : T.moreDevFastCharges();
        chargeCurveValue.setText(qualified < 0 ? "…" : effective == null ? "—" : Integer.toString(qualified));
        chargeCurveValue.setTextColor(qualified > 0 ? ui.accent : ui.muted);
        chargeCurveScope.setText(scope);chargeCurveScope.setVisibility(scope.isEmpty() ? View.GONE : View.VISIBLE);
        chargeCurve.setContentDescription(qualified < 0 ? T.moreDevCurvesLoading() : effective == null ? T.moreDevCurvesNotApplied()
                : (effective == ChargeSessionStore.Charger.STOCK ? T.moreDevCurvesStockCount(qualified, Fmt.integer(qualified)) : T.moreDevCurvesFastCount(qualified, Fmt.integer(qualified))));
        chargeCurve.setTooltipText(T.moreDevCurvesTooltip());
    }
    private void setChildrenEnabled(View view,boolean enabled){
        view.setEnabled(enabled);if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)setChildrenEnabled(((ViewGroup)view).getChildAt(i),enabled);
    }

    private void licenses() {
        LinearLayout box = dialogBox(T.moreAboutLicenses(), null);
        note(box, "Saira typeface\nCopyright 2020 The Saira Project Authors. SIL Open Font License 1.1; not covered by the app's MIT licence.");
        note(box, "Shizuku API 13.1.5\nCopyright (c) 2021 RikkaW. MIT License. Optional phone-only owner setup.");
        note(box, "AndroidX annotations 1.3.0\nCopyright The Android Open Source Project. Apache License 2.0.");
        note(box, "Protocol research reference\npepperonas/navee-st3-pro, MIT License.");
        note(box, "UT5 MAX, N65i and ST3 Pro photos\n© NAVEE; not covered by app licence.");
        note(box, "Maps\nMap data and tiles belong to the selected provider. USGS Topo: U.S. Geological Survey, National Geospatial Program. OpenStreetMap: © OpenStreetMap contributors.");
        LinearLayout notices = ui.groupCard(); notices.setBackground(null); notices.setPadding(0, 0, 0, 0); box.addView(notices);
        row(notices, "copy", T.moreNoticesView(), null, this::fullNotices);
        ScrollView inner = new ScrollView(host.activity()); inner.addView(box);
        AlertDialog dialog = new AlertDialog.Builder(host.activity()).setView(inner).setPositiveButton(T.commonClose(), null).create(); show(dialog);
    }
    /** The licence texts bundled in the APK assets, shown as they ship. */
    private void fullNotices() {
        LinearLayout box = dialogBox(T.moreNoticesFull(), null);
        try {
            android.content.res.AssetManager assets = host.activity().getAssets();
            String[] files = assets.list("");
            if (files != null) {
                Arrays.sort(files);
                for (String file : files) {
                    if (!file.endsWith(".txt")) continue;
                    StringBuilder text = new StringBuilder();
                    try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                            assets.open(file), java.nio.charset.StandardCharsets.UTF_8))) {
                        String line; while ((line = reader.readLine()) != null) text.append(line).append('\n');
                    }
                    TextView name = ui.text(file.substring(0, file.length() - 4).replace('-', ' '), 15, true);
                    name.setPadding(0, ui.dp(12), 0, ui.dp(4)); box.addView(name);
                    TextView body = ui.caption(text.toString().trim()); body.setTextIsSelectable(true); box.addView(body);
                }
            }
        } catch (java.io.IOException unreadable) { note(box, T.moreNoticesUnreadable()); }
        ScrollView inner = new ScrollView(host.activity()); inner.addView(box);
        AlertDialog dialog = new AlertDialog.Builder(host.activity()).setView(inner).setPositiveButton(T.commonClose(), null).create(); show(dialog);
    }
    private boolean granted(String permission) {
        return host.activity().checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }
    private void permissionValue(Row row, String text, boolean good) {
        row.value(text); row.value.setTextColor(good ? ui.ok : ui.muted);
        row.value.setTypeface(good ? ui.semibold() : ui.regular());
    }

    // ---- Refresh ------------------------------------------------------------------------

    private void refreshProfiles(ScooterService s, ScooterProfiles.Profile p) {
        DeviceDescriptor saved = p == null ? null : p.savedDescriptor();
        boolean photo = ScooterPhotos.show(indexPhoto, saved, 4);
        indexPhoto.setVisibility(photo ? View.VISIBLE : View.GONE);
        indexPhotoFallback.setVisibility(photo ? View.GONE : View.VISIBLE);
        String role = p == null ? "" : p.role == 0 ? T.moreRoleOwner() : T.moreRoleShared();
        BleController.State state = s.ble.state();
        String link = s.ble.ready() ? T.moreLinkConnected()
                : state == BleController.State.DISCONNECTED ? T.moreLinkDisconnected() : T.moreLinkConnecting();
        String name = p == null ? T.moreIndexNoScooter() : p.displayName();
        String status = p == null ? T.moreIndexAdd() : T.moreIndexStatus(role, link);
        indexProfileName.setText(name); indexProfileState.setText(status);
        indexProfileCard.setContentDescription(T.moreIndexDescription(name, status));
        List<ScooterProfiles.Profile> all = s.profiles.all();
        scootersIndexRow.value(all.isEmpty() ? "" : String.valueOf(all.size()));
        String unavailable = s.scooterChangeUnavailable();
        scooterListNotice.setText(unavailable != null ? unavailable : all.isEmpty() ? T.moreScootersEmpty() : "");
        scooterListNotice.setVisibility(scooterListNotice.getText().length() == 0 ? View.GONE : View.VISIBLE);
        // Keep blocked actions tappable so their reason is available, including to screen readers.
        addScooterButton.setAlpha(unavailable == null ? 1f : .5f);
        List<String> ids = new ArrayList<>(); for (ScooterProfiles.Profile item : all) ids.add(item.id);
        if (!ids.equals(new ArrayList<>(scooterCards.keySet()))) {
            scooterList.removeAllViews(); scooterCards.clear();
            for (String id : ids) { ScooterCard card = new ScooterCard(id); scooterCards.put(id, card); scooterList.addView(card.card); }
        }
        for (ScooterProfiles.Profile item : all) {
            ScooterCard card = scooterCards.get(item.id);
            boolean selected = p != null && p.id.equals(item.id);
            boolean connected = selected && s.ble.ready();
            boolean connecting = selected && state != BleController.State.DISCONNECTED && !connected;
            card.name.setText(item.displayName());
            String detail = connected ? T.moreScootersConnected() : connecting ? T.moreConnecting() : T.moreScootersOffline();
            card.stateLine.setVisibility(selected ? View.VISIBLE : View.GONE);
            card.status.setText(detail); card.status.setTextColor(ui.muted);
            DeviceDescriptor descriptor = item.savedDescriptor();
            boolean hasPhoto = ScooterPhotos.show(card.photo, descriptor, 4);
            card.photo.setVisibility(hasPhoto ? View.VISIBLE : View.GONE);
            card.photoFallback.setVisibility(hasPhoto ? View.GONE : View.VISIBLE);
            String model = ModelRegistry.modelName(descriptor);
            if (model.isEmpty()) model = T.moreScootersModelUnknown();
            if (item.role == 1) model += T.moreScootersSharedSuffix();
            card.model.setText(model);
            card.lastSeen.setVisibility(item.lastConnected > 0 && !connected ? View.VISIBLE : View.GONE);
            if (item.lastConnected > 0) card.lastSeen.setText(T.moreScootersLastConnected(DateUtils.getRelativeTimeSpanString(
                    item.lastConnected, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)));
            card.card.setBackground(ui.shape(ui.surface, 22, ui.outline));
            card.edit.setContentDescription(T.moreScootersEditNamed(item.displayName())); card.edit.setTooltipText(T.moreScootersEdit());
            String action = connected ? T.moreScootersDisconnect() : connecting ? T.commonCancel() : T.moreConnect();
            card.connect.setText(action); card.connect.setContentDescription(T.moreScootersActionNamed(action, item.displayName()));
            card.connect.setVisibility(selected ? View.VISIBLE : View.GONE);
            card.use.setVisibility(selected ? View.GONE : View.VISIBLE);
            card.use.setContentDescription(T.moreScootersUseNamed(item.displayName()));
            card.connect.setAlpha(unavailable == null ? 1f : .5f); card.use.setAlpha(unavailable == null ? 1f : .5f);
        }
    }
    private void refreshPermissions(ScooterService s) {
        boolean nearby = s.ble.permissions();
        permissionValue(nearbyRow, nearby ? T.morePermissionAllowed() : T.morePermissionNotAllowed(), nearby);
        boolean fine = granted(Manifest.permission.ACCESS_FINE_LOCATION), coarse = granted(Manifest.permission.ACCESS_COARSE_LOCATION);
        permissionValue(locationRow, fine ? T.morePermissionAllowed() : coarse ? T.morePermissionApproximate() : T.morePermissionNotAllowed(), fine || coarse);
        NotificationManager notifications = host.activity().getSystemService(NotificationManager.class);
        boolean notify = notifications != null && notifications.areNotificationsEnabled();
        permissionValue(notificationRow, notify ? T.morePermissionAllowed() : T.commonOff(), notify);
    }
    private CharSequence pinStatus(ScooterCommands.PinState pin) {
        if (pin == null) return T.morePinNotRead();
        if (pin.status != 0) return T.morePinRejected();
        if (!pin.configured) return T.morePinNotSet();
        if (!Boolean.TRUE.equals(pin.enabled)) return T.morePinDisabled();
        SpannableString enabled = new SpannableString(T.morePinEnabled());
        enabled.setSpan(new ForegroundColorSpan(ui.ok), 0, M.morePinEnabled().length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return enabled;
    }

    @Override public void refresh() {
        if(chargeHistoryDialog!=null)chargeHistoryDialog.refresh();
        ScooterService s = service(); if (s == null) return;
        refreshSimulation(); refreshCharging();
        if (shownSection().equals("Data")) refreshHistoryStorage();
        if (compatibilityPanel != null) compatibilityPanel.refresh();
        for (Runnable refresh : valueRefreshers) refresh.run();
        ScooterProfiles.Profile p = active();
        boolean configured = p != null && p.configured();
        onboardingCard.setVisibility(configured ? View.GONE : View.VISIBLE);
        boolean profilesEditable = s.scooterChangeUnavailable() == null && s.ble.state() == BleController.State.DISCONNECTED;
        for (Row action : profileEditActions) action.enabled(profilesEditable);
        for (Row row : lockedProfileRows) row.locked(!profilesEditable);
        checklistStep(onboardingImport, 1, configured);
        checklistStep(onboardingNearby, 2, s.ble.permissions());
        checklistStep(onboardingConnect, 3, s.ble.ready());
        // Connect needs the owner setup first; the row stays tappable to say so.
        onboardingConnect.title.setTextColor(configured ? ui.text : ui.muted);
        onboardingConnect.icon.setEnabled(configured); onboardingConnect.icon.invalidate();
        refreshProfiles(s, p);
        refreshPermissions(s);
        ScooterCommands.PinState pin = s.ble.pinState(); boolean owner = ownerReady(), busy = s.ble.pending() != null;
        String pending = s.ble.pending();
        pinRow.sub(pinStatus(pin));
        setPinRow.enabled(owner && !busy);
        pinRefresh.setEnabled(owner && !busy); pinRefresh.setAlpha(owner && !busy ? 1f : .35f);
        boolean pinPending = "pin".equals(pending);
        pinSpinner.setVisibility(pinPending ? View.VISIBLE : View.GONE);
        pinRefresh.setVisibility(pinPending ? View.GONE : View.VISIBLE);
        refreshSwitch(pinRow, pin == null || pin.status != 0 ? null : pin.enabled,
                owner && !busy && pin != null && pin.status == 0 && pin.configured);
        Integer proximity = s.ble.featureValue("proximity_key"); boolean paired = s.ble.bonded();
        boolean pinOn = pin != null && pin.status == 0 && pin.configured && Boolean.TRUE.equals(pin.enabled);
        boolean enablePrerequisites = paired && pinOn;
        boolean confirming = s.ble.confirmingProximity();
        // Turning proximity off never waits for the pairing (or the PIN); turning it on needs both.
        refreshSwitch(proximityRow, proximity == null ? null : proximity == 1,
                ProximityFollowUp.switchEnabled(owner, busy, proximity, paired, pinOn));
        proximitySpinner.setVisibility("proximity_key".equals(pending) || confirming ? View.VISIBLE : View.GONE);
        // Proximity on without this phone's pairing is said plainly; Volt only offers Pair or Off, it never writes by itself.
        proximityRow.sub(ProximityFollowUp.sectionNote(owner, s.ble.ready(), proximity, paired, confirming, s.ble.pairingEndedWithoutBond()));
        pill(proximityPinChip, pinOn ? T.moreChipPinOnValue() : T.moreChipPinOffValue(), ui.ok, pinOn);
        proximityPinChip.setContentDescription(pinOn ? T.moreChipPinOn() : T.moreChipPinOff());
        pill(proximityPairedChip, paired ? T.moreChipPairedValue() : T.moreChipNotPairedValue(), ui.ok, paired);
        proximityPairedChip.setContentDescription(paired ? T.moreChipPaired() : T.moreChipNotPaired());
        BleController.State link = s.ble.state();
        CapabilityResolver.Decision near = s.ble.decision("proximity_key");
        // The UT5 MAX keeps its wording; other models say what the resolver decided.
        String modelReason = near.layer == ModelRegistry.Layer.UT5_MAX ? T.moreProximityUt5Owner() : p != null && p.role != 0 ? T.moreProximityOwnerOnly()
                : near.support != CapabilityResolver.Support.KNOWN_ENCODING ? near.reasonText() : s.ble.decision("pin").reasonText();
        proximityReason = proximityRow.toggle.isEnabled() ? null
                : !owner ? (link == BleController.State.READY ? modelReason : M.moreProximityConnectFirst())
                : busy ? M.moreProximityWaiting()
                : proximity == null ? M.moreProximityNotReported()
                : !pinOn ? M.moreProximityPinFirst()
                : !paired ? M.moreProximityPairFirst() : null;
        if (proximityReason != null) proximityRow.toggle.setContentDescription(T.moreProximityDescription(proximityRow.title.getText(), proximityReason));
        bondRow.enabled(ProximityFollowUp.pairEnabled(owner, busy, paired, pinOn, proximity));
        permissionValue(bondRow, paired ? T.morePaired() : "", paired);
        // Step badges: green once done, red for the next step.
        pinRow.icon.step(pinOn ? 2 : 1);
        bondRow.icon.step(paired ? 2 : pinOn || proximity != null && proximity == 1 ? 1 : 0);
        proximityRow.icon.step(proximity != null && proximity == 1 ? 2 : enablePrerequisites ? 1 : 0);
        securityConnect.setVisibility(link == BleController.State.DISCONNECTED ? View.VISIBLE : View.GONE);
        String notice = owner ? T.moreSecurityNotice() : link == BleController.State.READY ? modelReason : "";
        securityNotice.setText(notice);
        securityNotice.setVisibility(notice.isEmpty() ? View.GONE : View.VISIBLE);
        refreshLab(s);
    }

    /**
     * Riding mode's own settings, under the layout editor in the tune menu (owner, Nov 9): brightness,
     * rotation, night vision with its schedule and consent, the status bar, and the two automatic
     * switches (the same preferences as More's copies of those two). Everything saves as it changes.
     * The screen behind is told through {@code changed} and rebuilds once the menu closes, so a change
     * never closes the menu mid-edit. The dialogs it opens (time picker, consent) close with it.
     */
    static final class RidingSettingsMenu {
        /** More's section label and the two automatic switches' words, shared with More's copies of them. */
        static String section() { return T.ridingDisplaySection(); }
        static String autoOpenTitle() { return T.ridingDisplayAutoOpen(); }
        static String autoOpenSub() { return T.ridingDisplayAutoOpenSub(); }
        static String autoRecordTitle() { return T.morePrefsAutoRecord(); }
        static String autoRecordSub() { return T.morePrefsAutoRecordSub(); }

        private final ScreenHost host;
        private final Ui ui;
        private final SharedPreferences preferences;
        private final Context context;
        private final Runnable changed;
        private final BooleanSupplier mayChange;
        private final List<Dialog> dialogs = new ArrayList<>();
        private boolean closed;
        private LinearLayout content;
        private Runnable refreshNight = () -> { };

        /** @param mayChange asked before each change saves: false at riding speed, or once the menu has gone. */
        RidingSettingsMenu(ScreenHost host, Ui ui, SharedPreferences preferences, Runnable changed, BooleanSupplier mayChange) {
            this.host = host;
            this.ui = ui;
            this.preferences = preferences;
            this.changed = changed;
            this.mayChange = mayChange;
            context = host.activity();
        }

        /** Builds the settings into the menu's body. */
        void fill(LinearLayout body) {
            content = body;
            body.addView(ui.sectionLabel(section()));
            boolean sensor = RidingDisplay.hasLightSensor(context);
            String[] labels = sensor ? new String[]{T.ridingDisplayPhone(), T.ridingDisplayAuto(), T.ridingDisplayFixed()}
                    : new String[]{T.ridingDisplayPhone(), T.ridingDisplayFixed()};
            String[] values = sensor ? new String[]{RidingBrightness.PHONE, RidingBrightness.AUTO, RidingBrightness.FIXED}
                    : new String[]{RidingBrightness.PHONE, RidingBrightness.FIXED};
            selector(T.ridingDisplayBrightness(), RidingBrightness.MODE_KEY, labels, values, RidingBrightness.PHONE, null);
            selector(T.ridingDisplayRotation(), RidingRotation.KEY, new String[]{T.ridingDisplayRotationLock(), T.ridingDisplayRotationFollow()},
                    new String[]{RidingRotation.LOCK, RidingRotation.FOLLOW}, RidingRotation.LOCK, null);
            nightPreferences();
            gap();
            content.addView(toggle(autoOpenTitle(), autoOpenSub(), AutoStartPolicy.AUTO_RIDE_MODE_KEY, "preference_riding_auto_open", false, null));
            content.addView(toggle(autoRecordTitle(), autoRecordSub(), AutoStartPolicy.AUTO_RECORD_KEY, "preference_riding_auto_record", false, save -> offerRideLocation(host, save)));
            // Off by default: the status bar keeps the phone's clock and battery (#254).
            content.addView(toggle(T.ridingDisplayHideStatusBar(), T.ridingDisplayHideStatusBarSub(), RidingBars.HIDE_STATUS_BAR_KEY, "preference_riding_hide_status_bar", true, null));
            // Off by default: the clock reading shows seconds, ticking only while one is on screen (a tester's request).
            content.addView(toggle(T.ridingDisplayClockSeconds(), T.ridingDisplayClockSecondsSub(), RidingClockSeconds.KEY, "preference_riding_clock_seconds", true, null));
            gap();
            LinearLayout box = ui.groupCard();
            box.setTag("preference_riding_brightness");
            RidingBrightness.Store store = RidingDisplay.settings(preferences);
            Runnable[] showReset = new Runnable[1];
            Row reset = row(box, T.ridingDisplayReset(), T.ridingDisplayResetSub(), () -> {
                if (!may()) return;
                RidingBrightness.forget(store);
                host.toast(T.ridingDisplayResetDone());
                changed.run();
                showReset[0].run();
            });
            reset.view.setTag("preference_riding_brightness_reset");
            showReset[0] = () -> reset.view.setVisibility(RidingBrightness.learned(store) ? View.VISIBLE : View.GONE);
            showReset[0].run();
            note(box, sensor ? T.ridingDisplayNote() : T.ridingDisplayNoteNoSensor());
            content.addView(box);
        }

        /** Whether a change may save right now; the single check every save below goes through. */
        private boolean may() { return !closed && mayChange.getAsBoolean(); }

        /** The menu closed: the dialogs it opened go with it, and a late answer changes nothing. */
        void close() {
            closed = true;
            for (Dialog dialog : new ArrayList<>(dialogs)) dialog.dismiss();
            dialogs.clear();
        }

        /**
         * Turning on Record rides automatically: the same optional prompt a manual recording uses, answered
         * before the switch saves, so a ride can't start behind the prompt. Either answer turns it on.
         * More's copy of the switch comes through here too.
         */
        static void offerRideLocation(ScreenHost host, Runnable save) {
            ScooterService service = host.service();
            if (service != null && AutoStartPolicy.asksLocationFirst(service.settings.getBoolean("phone_gps", true), service.simulating(), service.ridingNow()))
                host.requestAutoRecordLocation(save);
            else save.run();
        }

        /** A switch's stored state: only a stored true is on, so a missing or malformed value shows off rather than failing. */
        private boolean stored(String key) { return RidingClockSeconds.on(preferences.getAll().get(key)); }

        /** One switch; applies: riding mode needs rebuilding with it. beforeOn runs ahead of a turn-on and is given the save to run, as in More. */
        private View toggle(String title, String detail, String key, String tag, boolean applies, Consumer<Runnable> beforeOn) {
            View[] row = new View[1];
            row[0] = ui.toggleRow(title, Ui.NO_ICON, detail, stored(key), true, enabled -> {
                // Whatever happened, the switch and TalkBack say what's now stored; the row outlives the save here.
                Runnable showStored = () -> {
                    ViewGroup parent = (ViewGroup) row[0];
                    Switch toggle = (Switch) parent.getChildAt(parent.getChildCount() - 1);
                    boolean stored = stored(key);
                    toggle.setChecked(stored);
                    describeSwitch(toggle, stored);
                };
                Runnable save = () -> {
                    if (may()) {
                        preferences.edit().putBoolean(key, enabled).apply();
                        if (applies) changed.run();
                    }
                    showStored.run();
                };
                if (enabled && beforeOn != null) RidingFrames.askBeforeSave(this::may, beforeOn, save, showStored); else save.run();
            });
            row[0].setTag(tag);
            return row[0];
        }

        /**
         * Night vision: Off · On · Scheduled, then sunset to sunrise or custom times. Sunset from the last
         * ride's area needs the rider's yes first (owner Q21 A); without it the time zone's city is used.
         */
        private void nightPreferences() {
            selector(T.ridingNightVision(), NightSchedule.MODE_KEY, new String[]{T.ridingNightOff(), T.ridingNightOn(), T.ridingNightScheduled()},
                    new String[]{NightSchedule.OFF, NightSchedule.ON, NightSchedule.SCHEDULED}, NightSchedule.OFF, this::nightChanged);
            View[] schedule = selector(T.ridingNightSchedule(), NightSchedule.SCHEDULE_KEY, new String[]{T.ridingNightSun(), T.ridingNightCustom()},
                    new String[]{NightSchedule.SUN, NightSchedule.CUSTOM}, NightSchedule.SUN, this::nightChanged);
            gap();
            LinearLayout box = ui.groupCard();
            box.setTag("preference_riding_night");
            Row from = row(box, T.ridingNightFrom(), null, this::consent);
            from.view.setTag("preference_riding_night_from");
            Row starts = row(box, T.ridingNightStarts(), null, () -> time(NightSchedule.START_KEY, NightSchedule.START_DEFAULT, T.ridingNightStarts()));
            starts.view.setTag("preference_riding_night_starts");
            Row ends = row(box, T.ridingNightEnds(), null, () -> time(NightSchedule.END_KEY, NightSchedule.END_DEFAULT, T.ridingNightEnds()));
            ends.view.setTag("preference_riding_night_ends");
            TextView next = ui.caption("");
            next.setPadding(0, ui.dp(7), 0, 0);
            next.setTag("preference_riding_night_next");
            box.addView(next);
            note(box, T.ridingNightNote());
            content.addView(box);
            refreshNight = () -> {
                boolean scheduled = NightSchedule.SCHEDULED.equals(NightSchedule.mode(preferences.getString(NightSchedule.MODE_KEY, NightSchedule.OFF)));
                boolean sun = NightSchedule.SUN.equals(NightSchedule.schedule(preferences.getString(NightSchedule.SCHEDULE_KEY, NightSchedule.SUN)));
                schedule[0].setVisibility(scheduled ? View.VISIBLE : View.GONE);
                schedule[1].setVisibility(scheduled ? View.VISIBLE : View.GONE);
                long now = System.currentTimeMillis();
                NightSchedule.State state = RidingNight.state(context, preferences, now);
                boolean custom = scheduled && (!sun || state.source == NightSchedule.Source.NONE);
                shownRows(box, new View[]{from.view, starts.view, ends.view}, new boolean[]{scheduled && sun, custom, custom});
                boolean ride = RidingNight.usesRideArea(preferences);
                from.value(ride ? T.ridingNightFromRide() : T.ridingNightFromZone());
                from.sub(state.source == NightSchedule.Source.RIDE ? T.ridingNightSourceRide()
                        : state.source == NightSchedule.Source.NONE ? T.ridingNightSourceNone()
                        : ride ? T.ridingNightSourceRideNone() : T.ridingNightSourceZone());
                starts.value(clock(preferences.getInt(NightSchedule.START_KEY, NightSchedule.START_DEFAULT), NightSchedule.START_DEFAULT));
                ends.value(clock(preferences.getInt(NightSchedule.END_KEY, NightSchedule.END_DEFAULT), NightSchedule.END_DEFAULT));
                String text = !scheduled ? "" : state.nextMs > 0 && state.nextMs - now < 86_400_000L
                        ? (state.night ? T.ridingNightNextOff(DisplayTime.clock(context, preferences, state.nextMs))
                                : T.ridingNightNextOn(DisplayTime.clock(context, preferences, state.nextMs)))
                        : state.today == SunTimes.Day.Kind.ALWAYS_DAY ? T.ridingNightNoSunset()
                        : state.today == SunTimes.Day.Kind.ALWAYS_NIGHT ? T.ridingNightNoSunrise() : "";
                next.setText(text);
                next.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
            };
            refreshNight.run();
            // The ride's area is read in the background (it's never stored); the caption follows once it's known.
            if (RidingNight.usesRideArea(preferences)) RidingNight.readRideArea(context, () -> { if (!closed) refreshNight.run(); });
        }

        private void nightChanged() {
            refreshNight.run();
            boolean scheduled = NightSchedule.SCHEDULED.equals(NightSchedule.mode(preferences.getString(NightSchedule.MODE_KEY, NightSchedule.OFF)));
            boolean sun = NightSchedule.SUN.equals(NightSchedule.schedule(preferences.getString(NightSchedule.SCHEDULE_KEY, NightSchedule.SUN)));
            if (scheduled && sun && !preferences.contains(NightSchedule.AREA_KEY)) consent();
        }

        /** The brief consent sheet: use the last ride's area (rounded, on the phone) or the time zone. */
        private void consent() {
            if (!may()) return;
            android.app.AlertDialog[] self = new android.app.AlertDialog[1];
            LinearLayout body = host.sheetContent(T.ridingNightConsentTitle(), T.ridingNightConsentBody(), () -> { if (self[0] != null) self[0].dismiss(); });
            body.setTag("riding_night_consent");
            TextView allow = ui.button(T.ridingNightConsentAllow(), () -> {
                if (!may()) return;
                preferences.edit().putString(NightSchedule.AREA_KEY, NightSchedule.AREA_ALLOW).apply();
                changed.run();
                if (self[0] != null) self[0].dismiss();
                RidingNight.readRideArea(context, () -> { if (!closed) refreshNight.run(); });
            });
            allow.setTag("riding_night_consent_allow");
            LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-1, -2);
            space.topMargin = ui.dp(16);
            body.addView(allow, space);
            TextView zone = ui.secondaryButton(T.ridingNightConsentZone(), () -> {
                if (!may()) return;
                preferences.edit().putString(NightSchedule.AREA_KEY, NightSchedule.AREA_DECLINE).apply();
                changed.run();
                if (self[0] != null) self[0].dismiss();
                refreshNight.run();
            });
            zone.setTag("riding_night_consent_zone");
            LinearLayout.LayoutParams under = new LinearLayout.LayoutParams(-1, -2);
            under.topMargin = ui.dp(8);
            body.addView(zone, under);
            android.app.AlertDialog dialog = host.showSheet(body);
            if (dialog == null) return;
            self[0] = dialog;
            track(dialog);
        }

        /** A custom time's picker, in the phone's 12 or 24 hour clock. */
        private void time(String key, int fallback, String title) {
            if (!may()) return;
            int minutes = NightSchedule.minutes(preferences.getInt(key, fallback), fallback);
            android.app.TimePickerDialog picker = new android.app.TimePickerDialog(context, (view, hour, minute) -> {
                if (!may()) return;
                preferences.edit().putInt(key, hour * 60 + minute).apply();
                changed.run();
                refreshNight.run();
            }, minutes / 60, minutes % 60, DisplayTime.is24Hour(context, preferences));
            picker.setTitle(title);
            track(picker);
            picker.show();
        }

        private void track(Dialog dialog) {
            dialogs.add(dialog);
            dialog.setOnDismissListener(x -> dialogs.remove(dialog));
        }

        /** A custom time as the phone's clock shows it today. */
        private String clock(int stored, int fallback) {
            int minutes = NightSchedule.minutes(stored, fallback);
            long wall = java.time.LocalDate.now().atTime(minutes / 60, minutes % 60).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
            return DisplayTime.clock(context, preferences, wall);
        }

        /** Shows the chosen rows of a group, in order; each row's divider shows only below another shown row. */
        private static void shownRows(LinearLayout box, View[] rows, boolean[] shown) {
            boolean above = false;
            for (int i = 0; i < rows.length; i++) {
                rows[i].setVisibility(shown[i] ? View.VISIBLE : View.GONE);
                int at = box.indexOfChild(rows[i]);
                if (at > 0 && i > 0) box.getChildAt(at - 1).setVisibility(shown[i] && above ? View.VISIBLE : View.GONE);
                above |= shown[i];
            }
        }

        /** A caption and its selector, saved on change; returns {caption, selector} so a row can hide them together. */
        private View[] selector(String title, String key, String[] labels, String[] values, String fallback, Runnable after) {
            TextView caption = ui.text(title, 12.5f, false);
            caption.setTextColor(ui.muted);
            caption.setPadding(ui.dp(2), ui.dp(12), ui.dp(2), ui.dp(6));
            content.addView(caption);
            SegmentedSelector selector = new SegmentedSelector(ui, title, labels, choice -> {
                if (values[choice].equals(preferences.getString(key, fallback)) || !may()) return;
                preferences.edit().putString(key, values[choice]).apply();
                changed.run();
                if (after != null) after.run();
            });
            selector.setTag("preference-selector-" + key);
            String stored = preferences.getString(key, fallback);
            int selected = -1;
            for (int i = 0; i < values.length; i++) if (values[i].equals(stored)) selected = i;
            if (selected < 0) for (int i = 0; i < values.length; i++) if (fallback.equals(values[i])) selected = i;
            content.addView(selector, new LinearLayout.LayoutParams(-1, -2));
            selector.select(Math.max(0, selected), false);
            return new View[]{caption, selector};
        }

        private void gap() { content.addView(new View(context), new LinearLayout.LayoutParams(-1, ui.dp(16))); }

        private void note(LinearLayout box, String value) {
            TextView text = ui.caption(value);
            text.setPadding(0, ui.dp(7), 0, ui.dp(7));
            box.addView(text);
        }

        /** One divided row in a group card: title, optional sub-text, trailing value and chevron. */
        private final class Row {
            LinearLayout view;
            TextView sub, value;
            void value(String text) {
                value.setText(text == null ? "" : text);
                value.setVisibility(text == null || text.isEmpty() ? View.GONE : View.VISIBLE);
            }
            void sub(CharSequence text) {
                sub.setText(text == null ? "" : text);
                sub.setVisibility(text == null || text.length() == 0 ? View.GONE : View.VISIBLE);
            }
        }

        private Row row(LinearLayout group, String title, String sub, Runnable action) {
            if (group.getChildCount() > 0) ui.divider(group);
            Row r = new Row();
            r.view = new LinearLayout(context);
            r.view.setOrientation(LinearLayout.HORIZONTAL);
            r.view.setGravity(Gravity.CENTER_VERTICAL);
            r.view.setMinimumHeight(ui.dp(58));
            r.view.setPadding(0, ui.dp(9), 0, ui.dp(9));
            LinearLayout words = new LinearLayout(context);
            words.setOrientation(LinearLayout.VERTICAL);
            words.addView(ui.text(title, 15, false));
            r.sub = ui.text("", 11.5f, false);
            r.sub.setTextColor(ui.muted);
            r.sub.setPadding(0, ui.dp(3), 0, 0);
            words.addView(r.sub);
            r.sub(sub);
            r.view.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
            r.value = ui.text("", 13.5f, false);
            r.value.setTextColor(ui.muted);
            r.value.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            r.value.setMaxLines(2);
            r.value.setMaxWidth(ui.dp(140));
            LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(-2, -2);
            vp.leftMargin = ui.dp(10);
            r.view.addView(r.value, vp);
            r.value(null);
            View chevron = ui.icon("chevron", 15, ui.muted);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ui.dp(15), ui.dp(15));
            cp.leftMargin = ui.dp(8);
            r.view.addView(chevron, cp);
            r.view.setFocusable(true);
            r.view.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(ui.accent, 26)), null, ui.shape(Color.WHITE, 10, 0)));
            r.view.setOnClickListener(v -> { if (!closed) action.run(); });
            group.addView(r.view, new LinearLayout.LayoutParams(-1, -2));
            return r;
        }
    }
}
