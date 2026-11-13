package com.jjricks.volt;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/** Map privacy/provider preferences shared by App preferences and ride rendering. */
public final class MapPreferences {
    private MapPreferences() { }
    static boolean enabled(SharedPreferences settings) { return settings.getBoolean("map_basemap_enabled", false); }

    /** One remembered choice, shared by inline maps, expanded maps and app preferences. */
    static View basemapControl(ScreenHost host, Consumer<Boolean> changed) {
        return new BasemapControl(host, changed);
    }

    private static final class BasemapControl extends LinearLayout {
        private final ScreenHost host;
        private final SharedPreferences settings;
        private final Consumer<Boolean> changed;
        private final Switch toggle;
        private boolean updating, listening;
        private Boolean shown;
        private AlertDialog prompt;
        private final SharedPreferences.OnSharedPreferenceChangeListener preferenceChanged = (p, key) -> {
            if (key == null || key.equals("map_basemap_enabled")) refresh();
        };

        BasemapControl(ScreenHost host, Consumer<Boolean> changed) {
            super(host.activity());
            this.host = host; this.settings = host.service().settings; this.changed = changed;
            Ui ui = host.ui();
            setOrientation(HORIZONTAL); setGravity(Gravity.CENTER_VERTICAL);
            setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4));
            toggle = new Haptics.FeedbackSwitch(host.activity()); toggle.setText(T.mapsBasemap());
            toggle.setTextColor(ui.text); toggle.setTextSize(15 * ui.textScale);
            toggle.setMinHeight(ui.dp(48)); toggle.setContentDescription(T.mapsBasemap());
            ui.styleSwitch(toggle); addView(toggle, new LinearLayout.LayoutParams(-1, -2));
            // The old-default USGS pin is written down before anything here can turn the basemap off (MapSource.keepPin).
            MapSource.keepPin(settings);
            refresh();
            toggle.setOnCheckedChangeListener((button, on) -> {
                if (updating) return;
                if (!on) {
                    MapSource.keepPin(settings);
                    settings.edit().putBoolean("map_basemap_enabled", false).apply();
                    refresh();
                } else if (settings.getBoolean("map_basemap_explained", false)) {
                    settings.edit().putBoolean("map_basemap_enabled", true).apply();
                    refresh();
                } else {
                    // Do not show an enabled state or start tile requests before approval.
                    refresh();
                    explain();
                }
            });
        }

        private void refresh() {
            boolean on = enabled(settings);
            updating = true; toggle.setChecked(on); updating = false;
            if (shown == null || shown != on) {
                shown = on;
                if (changed != null) changed.accept(on);
            }
        }

        private void explain() {
            if (!isAttachedToWindow() || (prompt != null && prompt.isShowing())) return;
            prompt = consent(host, this::refresh, dialog -> { if (prompt == dialog) prompt = null; refresh(); });
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            settings.registerOnSharedPreferenceChangeListener(preferenceChanged); listening = true;
            refresh();
        }

        @Override protected void onDetachedFromWindow() {
            if (listening) { settings.unregisterOnSharedPreferenceChangeListener(preferenceChanged); listening = false; }
            if (prompt != null) prompt.dismiss();
            super.onDetachedFromWindow();
        }
    }

    /**
     * The one copy of the map privacy fact; approval enables tiles and is remembered. It names the provider
     * the basemap will use, and approval saves that provider when none is saved, so a later change of
     * Volt's default never moves an approved basemap to a provider that was not named (MapDefault).
     */
    private static AlertDialog consent(ScreenHost host, Runnable approved, Consumer<AlertDialog> closed) {
        Ui ui = host.ui(); SharedPreferences settings = host.service().settings;
        MapSource named = MapSource.load(settings);
        LinearLayout body = new LinearLayout(host.activity()); body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(ui.dp(24), ui.dp(20), ui.dp(24), ui.dp(12));
        body.addView(ui.text(T.mapsConsentTitle(), 21, true));
        TextView provider = ui.text(named.displayName(), 15, true);
        provider.setPadding(0, ui.dp(8), 0, 0); body.addView(provider);
        TextView message = ui.text(T.mapsConsentMessage(), 15, false);
        message.setPadding(0, ui.dp(12), 0, 0); body.addView(message);
        ScrollView scroll = new ScrollView(host.activity()); scroll.addView(body);
        AlertDialog dialog = new AlertDialog.Builder(host.activity()).setView(scroll)
                .setNegativeButton(T.commonCancel(), null)
                .setPositiveButton(T.mapsConsentEnable(), (d, which) -> {
                    SharedPreferences.Editor approval = settings.edit().putBoolean("map_basemap_enabled", true)
                            .putBoolean("map_basemap_explained", true);
                    if (!settings.contains(MapDefault.URL_KEY)) named.put(approval);
                    approval.apply();
                    approved.run();
                }).create();
        dialog.setOnDismissListener(d -> closed.accept(dialog));
        dialog.getWindow().setBackgroundDrawable(ui.shape(ui.surface, 22, ui.outline));
        Motion.present(dialog, Motion.DIALOG); dialog.show();
        for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE}) {
            dialog.getButton(which).setTextColor(ui.accent);
            dialog.getButton(which).setTypeface(ui.semibold());
        }
        return dialog;
    }

    /**
     * Map settings: one segmented control for Off and each provider, the default (OpenStreetMap) first.
     * Picking a provider turns the basemap on (after the one-time consent); Off turns it off. Custom
     * shows its editor button.
     */
    private static final class ProviderControl extends LinearLayout {
        // Unused since B14: the constructor's local LABELS shows these options from the catalog. It stays
        // only because G1 cannot yet delete a field whose array initializer ends in "};" (B14 report).
        private static final String[] LABELS = {"Off", "USGS (US)", "OSM", "Custom"};
        private final ScreenHost host;
        private final SharedPreferences settings;
        private final SegmentedSelector selector;
        private final TextView edit;
        private AlertDialog prompt;
        private boolean listening;
        private final SharedPreferences.OnSharedPreferenceChangeListener preferenceChanged = (p, key) -> {
            if (key == null || key.startsWith("map_")) refresh();
        };

        ProviderControl(ScreenHost host) {
            super(host.activity());
            this.host = host; this.settings = host.service().settings;
            Ui ui = host.ui();
            // The option labels by choice index, rendered for this control in the current language (R5: a
            // static array cannot hold catalog text). It shadows the unused English field of the same name: G1
            // refuses a rename on the line that holds "Online map" (B14 report).
            String[] LABELS = {T.commonOff(), T.mapsProviderOsm(), T.mapsProviderUsgs(), T.mapsProviderCustom()};
            setOrientation(VERTICAL);
            selector = new SegmentedSelector(ui, T.mapsOnlineMap(), LABELS, this::chose);
            // Single-line labels of one size share the track's height, so every value sits centred;
            // at very large text the track scrolls rather than wrapping one label.
            LinearLayout.LayoutParams selectorParams = new LinearLayout.LayoutParams(-1, -2); selectorParams.topMargin = ui.dp(12);
            addView(selector, selectorParams);
            edit = ui.secondaryButton(T.mapsEditCustom(), () -> custom(host, this::customClosed));
            addView(edit);
            MapSource.keepPin(settings);
            refresh();
        }

        private int current() {
            if (!enabled(settings)) return 0;
            String url = MapSource.load(settings).url;
            return MapSource.OSM_URL.equals(url) ? 1 : MapSource.USGS_URL.equals(url) ? 2 : 3;
        }

        private boolean customSaved() {
            String url = MapSource.load(settings).url;
            return settings.contains("map_tile_url") && !MapSource.USGS_URL.equals(url) && !MapSource.OSM_URL.equals(url);
        }

        void refresh() {
            int chosen = current();
            selector.select(chosen, isAttachedToWindow());
            edit.setVisibility(chosen == 3 ? View.VISIBLE : View.GONE);
        }

        private void chose(int choice) {
            if (choice == 0) MapSource.keepPin(settings);
            if (choice == 0) { settings.edit().putBoolean("map_basemap_enabled", false).apply(); refresh(); return; }
            if (choice == 3 && !customSaved()) { custom(host, this::customClosed); return; }
            if (choice == 1) MapSource.OSM.save(settings);
            else if (choice == 2) MapSource.USGS.save(settings);
            enable();
        }

        private void customClosed() { if (customSaved()) enable(); else refresh(); }

        private void enable() {
            if (settings.getBoolean("map_basemap_explained", false)) {
                settings.edit().putBoolean("map_basemap_enabled", true).apply(); refresh(); return;
            }
            // Do not show an enabled state or start tile requests before approval.
            if (!isAttachedToWindow() || (prompt != null && prompt.isShowing())) { refresh(); return; }
            prompt = consent(host, this::refresh, dialog -> { if (prompt == dialog) prompt = null; refresh(); });
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            settings.registerOnSharedPreferenceChangeListener(preferenceChanged); listening = true;
            refresh();
        }

        @Override protected void onDetachedFromWindow() {
            if (listening) { settings.unregisterOnSharedPreferenceChangeListener(preferenceChanged); listening = false; }
            if (prompt != null) prompt.dismiss();
            super.onDetachedFromWindow();
        }
    }

    /**
     * Saves the answer to the one-time offer to leave USGS (MapDefault.offersSwitch) as the person's own
     * choice, in one commit with the mark that it was answered, so it is never asked again, also after a
     * restore of a backup made afterwards. Switching saves OpenStreetMap; keeping saves USGS. An answer
     * already saved stays: a later one changes nothing.
     */
    static void answerUsgsSwitch(SharedPreferences settings, boolean switchToOsm) {
        if (settings.getBoolean(MapDefault.SWITCH_ANSWERED_KEY, false)) return;
        SharedPreferences.Editor answer = settings.edit().putBoolean(MapDefault.SWITCH_ANSWERED_KEY, true);
        (switchToOsm ? MapSource.OSM : MapSource.USGS).put(answer);
        answer.apply();
    }

    /** The open offer to leave USGS, at most one per activity, and the maps that asked for it (OneQuestion). */
    private static final OneQuestion<AlertDialog> SWITCH_QUESTION = new OneQuestion<>();
    /** Each asking map's watch on its window, so the offer closes quietly once every asking map has left. */
    private static final Map<View, View.OnAttachStateChangeListener> SWITCH_ASKERS = new WeakHashMap<>();

    /**
     * Asks once whether someone on USGS by the old default (MapSource.usgsByDefault) wants OpenStreetMap, when
     * the basemap shows a ride lying wholly outside USGS coverage (MapDefault.asksToLeaveUsgs). map is the map
     * that asks: the offer is checked again when it would show, once map is in its window, and it lives only as
     * long as some asking map stays there. Switch or Keep USGS, or Back on the offer itself, is saved as the
     * person's own choice, and only the first answer counts (OneQuestion, answerUsgsSwitch); open maps follow
     * through RidesScreen.mapPanel's provider listener. Closed with its screen, it records nothing and asks again.
     */
    static void offerOsmSwitch(ScreenHost host, View map, List<PhoneRideStore.Point> points) {
        SharedPreferences settings = host.service().settings;
        double[] latitudes = new double[points.size()], longitudes = new double[points.size()];
        for (int i = 0; i < points.size(); i++) { latitudes[i] = points.get(i).latitude; longitudes[i] = points.get(i).longitude; }
        // A view's post waits until it is in a window, and never runs if it never gets there.
        map.post(() -> {
            Activity activity = host.activity();
            if (activity.isFinishing() || activity.isDestroyed() || !map.isAttachedToWindow()) return;
            AlertDialog open = SWITCH_QUESTION.current(activity);
            if (open != null && !open.isShowing()) { SWITCH_QUESTION.closed(open); open = null; }
            if (open != null) { SWITCH_QUESTION.join(activity, map); watchSwitchAsker(map); return; }
            if (!MapDefault.asksToLeaveUsgs(MapSource.usgsByDefault(settings), enabled(settings),
                    settings.getBoolean(MapDefault.SWITCH_ANSWERED_KEY, false), MapDefault.anyInUsgsArea(latitudes, longitudes))) return;
            Ui ui = host.ui();
            LinearLayout body = new LinearLayout(host.activity()); body.setOrientation(LinearLayout.VERTICAL);
            body.setPadding(ui.dp(24), ui.dp(20), ui.dp(24), ui.dp(12));
            body.addView(ui.text(T.mapsSwitchOsmTitle(), 21, true));
            TextView message = ui.text(T.mapsSwitchOsmMessage(), 15, false);
            message.setPadding(0, ui.dp(12), 0, 0); body.addView(message);
            ScrollView scroll = new ScrollView(host.activity()); scroll.addView(body);
            AlertDialog dialog = new AlertDialog.Builder(host.activity()).setView(scroll)
                    .setNegativeButton(T.mapsSwitchOsmKeep(), (d, which) -> answerSwitchQuestion((AlertDialog) d, settings, false))
                    .setPositiveButton(T.mapsSwitchOsmSwitch(), (d, which) -> answerSwitchQuestion((AlertDialog) d, settings, true)).create();
            // Back answers Keep USGS; a stray tap beside the dialog answers nothing. Volt itself only ever
            // dismisses it (closeSwitchQuestion), which never calls this.
            dialog.setOnCancelListener(d -> answerSwitchQuestion(dialog, settings, false));
            dialog.setCanceledOnTouchOutside(false);
            dialog.setOnDismissListener(d -> switchQuestionClosed(dialog));
            dialog.getWindow().setBackgroundDrawable(ui.shape(ui.surface, 22, ui.outline));
            closeSwitchQuestion(SWITCH_QUESTION.opened(activity, map, dialog));
            watchSwitchAsker(map);
            Motion.present(dialog, Motion.DIALOG); dialog.show();
            for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE}) {
                dialog.getButton(which).setTextColor(ui.accent);
                dialog.getButton(which).setTypeface(ui.semibold());
            }
        });
    }

    /** An answer on question: saved only when it is the first (OneQuestion.answer); every other open copy closes quietly. */
    private static void answerSwitchQuestion(AlertDialog question, SharedPreferences settings, boolean switchToOsm) {
        List<AlertDialog> others = new ArrayList<>();
        if (SWITCH_QUESTION.answer(question, others)) answerUsgsSwitch(settings, switchToOsm);
        for (AlertDialog other : others) closeSwitchQuestion(other);
    }

    private static void switchQuestionClosed(AlertDialog question) { SWITCH_QUESTION.closed(question); }

    /** Closes the offer without an answer (dismiss, never cancel), so it is asked again the next time it applies. */
    private static void closeSwitchQuestion(AlertDialog question) {
        if (question == null) return;
        SWITCH_QUESTION.closed(question);
        // The window may already be gone with a destroyed activity; then there is nothing left to close.
        try { if (question.isShowing()) question.dismiss(); } catch (RuntimeException gone) { }
    }

    /** Once map leaves its window, it no longer asks; the offer closes quietly when no asking map is left. */
    private static void watchSwitchAsker(View map) {
        if (SWITCH_ASKERS.containsKey(map)) return;
        View.OnAttachStateChangeListener watch = new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) { }
            @Override public void onViewDetachedFromWindow(View view) {
                view.removeOnAttachStateChangeListener(this); SWITCH_ASKERS.remove(view);
                closeSwitchQuestion(SWITCH_QUESTION.left(view));
            }
        };
        SWITCH_ASKERS.put(map, watch); map.addOnAttachStateChangeListener(watch);
    }

    public static AlertDialog show(ScreenHost host) {
        Ui ui = host.ui();
        LinearLayout body = new LinearLayout(host.activity()); body.setOrientation(1);
        body.setPadding(ui.dp(20), ui.dp(16), ui.dp(20), ui.dp(20));
        body.addView(ui.text(T.mapsSettingsTitle(), 22, true));
        body.addView(new ProviderControl(host));
        ScrollView scroll = new ScrollView(host.activity()); scroll.addView(body);
        AlertDialog dialog = new AlertDialog.Builder(host.activity()).setView(scroll).setPositiveButton(T.commonDone(), null).create();
        dialog.getWindow().setBackgroundDrawable(ui.shape(ui.surface, 22, ui.outline)); Motion.present(dialog, Motion.DIALOG); dialog.show();
        return dialog;
    }
    private static void custom(ScreenHost host, Runnable saved) {
        Ui ui = host.ui(); SharedPreferences settings = host.service().settings; MapSource source = MapSource.load(settings);
        LinearLayout body = new LinearLayout(host.activity()); body.setOrientation(1); body.setPadding(ui.dp(20), ui.dp(16), ui.dp(20), ui.dp(16));
        body.addView(ui.text(T.mapsCustomTitle(), 20, true));
        EditText url = field(host, body, T.mapsCustomTileUrl(), source.url); url.setHint(T.mapsCustomTileUrlHint());
        EditText credit = field(host, body, T.mapsCustomAttribution(), source.attribution);
        EditText link = field(host, body, T.mapsCustomAttributionLink(), source.attributionUrl);
        ScrollView scroll = new ScrollView(host.activity()); scroll.addView(body);
        AlertDialog dialog = new AlertDialog.Builder(host.activity()).setView(scroll).setNegativeButton(T.commonCancel(), null).setPositiveButton(T.mapsCustomSave(), null).create();
        dialog.setOnDismissListener(d -> saved.run());
        dialog.getWindow().setBackgroundDrawable(ui.shape(ui.surface, 22, ui.outline)); Motion.present(dialog, Motion.DIALOG); dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = url.getText().toString().trim(), text = credit.getText().toString().trim(), address = link.getText().toString().trim();
            if (!MapTiles.validUrl(value)) { url.setError(T.mapsCustomErrorUrl()); return; }
            if (text.isEmpty()) { credit.setError(T.mapsCustomErrorAttribution()); return; }
            try { java.net.URI uri = new java.net.URI(address); if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) throw new IllegalArgumentException(); }
            catch (Exception e) { link.setError(T.mapsCustomErrorLink()); return; }
            new MapSource("Custom map", value, text, address).save(settings); dialog.dismiss();
        });
    }
    private static EditText field(ScreenHost host, LinearLayout parent, String label, String value) {
        Ui ui = host.ui(); TextView caption = ui.caption(label); caption.setPadding(0, ui.dp(12), 0, ui.dp(6)); parent.addView(caption);
        EditText field = new EditText(host.activity()); field.setText(value); field.setTextColor(ui.text); field.setHintTextColor(ui.muted); field.setTextSize(14 * ui.textScale);
        field.setBackground(ui.shape(ui.bg, 12, ui.outline)); field.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12)); field.setMinHeight(ui.dp(48)); parent.addView(field); return field;
    }
}
