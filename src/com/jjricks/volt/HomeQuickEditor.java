package com.jjricks.volt;

import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Clean Home's four quick actions outside Clean Home itself: what Clean's Edit sheet and Settings › Home style's
 * Quick actions row share. The four live in {@code home_quick_v1_<profile id>} (HomeQuickActions), with the scooter's
 * defaults while none are stored (HomeQuickDefaults); a slot row opens the quick-action picker (DashboardScreen's,
 * through CleanParts or QuickChooser). Text from HubText and the actions' own names; no catalog references here.
 */
final class HomeQuickEditor {
    private HomeQuickEditor() { }

    /** The active scooter's four: the owner's, or the scooter's defaults while none are stored. Empty with no scooter. */
    static List<String> load(ScooterService service, SharedPreferences preferences) {
        ScooterProfiles.Profile profile = service == null ? null : service.profiles.active();
        if (profile == null) return new ArrayList<>();
        String stored;
        try { stored = preferences.getString(HomeQuickActions.key(profile.id), null); } catch (ClassCastException wrongType) { stored = null; }
        return new ArrayList<>(HomeQuickActions.load(stored, HomeQuickDefaults.forScooter(service)));
    }

    /** Stores the active scooter's four; without a scooter there is nowhere to keep them (nothing offers it then). */
    static void save(ScooterService service, SharedPreferences preferences, List<String> ids) {
        ScooterProfiles.Profile profile = service == null ? null : service.profiles.active();
        if (profile == null) return;
        preferences.edit().putString(HomeQuickActions.key(profile.id), HomeQuickActions.encode(ids)).apply();
    }

    /**
     * An action's round-button ladder (QuickAccessGrid.roundNames, the names Clean's button tries in order), or its id
     * alone if unknown.
     */
    static String[] roundNames(String id) {
        QuickAccessActions.Entry entry = HomeQuickActions.entry(id);
        return entry == null ? new String[] {id} : QuickAccessGrid.roundNames(entry);
    }

    /**
     * The four names in order as Clean's round buttons show them (the contributor's decision, 15 Nov, #383): "Ride,
     * Record, Charge by, Cruise", each button's first-choice name (QuickActionLayout.firstNames), joined by the language's
     * list separator (HubText.listSeparator).
     */
    static String summary(List<String> ids) {
        List<String[]> ladders = new ArrayList<>();
        for (String id : ids) ladders.add(roundNames(id));
        return QuickActionLayout.firstNames(HubText.listSeparator().render(), ladders);
    }

    /**
     * The four names TalkBack reads for the line: each action's full name, as its round button and the editing list
     * speak it ("Charge done by" rather than "Charge by", "Cruise control" rather than "Cruise"), so a short name is never
     * all a listener hears. Riding mode is read by the name the line and its round button show (spokenName). Joined by the
     * language's list separator.
     */
    static String spokenSummary(List<String> ids) {
        List<String> names = new ArrayList<>();
        for (String id : ids) names.add(spokenName(id).toString());
        return String.join(HubText.listSeparator().render(), names);
    }

    /**
     * An action's name in the line's spoken summary: its full name (fullName), except riding mode, read by its round
     * button's name (HubText.quickActionRoundName: "Ride" as riding, "Condução", "Conducción"). Its entry's own label is
     * the Widgets RIDE pill's, which some languages give as a trip ("VIAGEM", "VIAJE"), next to Rides ("Viagens").
     */
    private static CharSequence spokenName(String id) {
        Msg round = HomeQuickActions.RIDING.equals(id) ? HubText.quickActionRoundName(id) : null;
        return round != null ? round.render() : fullName(id);
    }

    /**
     * An action's full name as the editing list shows and speaks it: the entry's own name, with the Widgets pill's
     * capitals ("RIDE") read as the name the Home style row and Clean show ("Ride"); its id if unknown.
     */
    private static CharSequence fullName(String id) {
        QuickAccessActions.Entry entry = HomeQuickActions.entry(id);
        CharSequence label = entry == null ? id : entry.label().render();
        String round = entry == null ? null : QuickAccessGrid.roundName(entry);
        if (round != null && !round.contentEquals(label) && round.equalsIgnoreCase(label.toString())) label = round;
        return label;
    }

    /**
     * One of the four in an editing list: its icon and full name, a chevron; a tap runs tapped (which opens the
     * picker for that position). Tagged {@code CleanHomeScreen.TAG_SLOT + (index + 1)}, as Clean's Edit sheet always was.
     */
    static View slot(ScreenHost host, List<String> ids, int index, Runnable tapped) {
        Ui ui = host.ui();
        String id = ids.get(index);
        QuickAccessActions.Entry entry = HomeQuickActions.entry(id);
        // The Widgets pill's capitals ("RIDE") read as the same name the Home style row and Clean show ("Ride").
        CharSequence label = fullName(id);
        LinearLayout line = new LinearLayout(host.activity());
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setTag(CleanHomeScreen.TAG_SLOT + (index + 1));
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setMinimumHeight(ui.dp(56));
        line.setPadding(ui.dp(4), ui.dp(8), ui.dp(4), ui.dp(8));
        line.addView(ui.icon(entry == null ? "tune" : QuickAccessGrid.roundGlyph(id, entry.icon), 20, ui.accent), new LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)));
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
        line.setOnClickListener(v -> tapped.run());
        return line;
    }

    /** The four slots in one card, for a sheet; each slot runs tapped with its position. */
    static LinearLayout slots(ScreenHost host, List<String> ids, java.util.function.IntConsumer tapped) {
        Ui ui = host.ui();
        LinearLayout card = ui.groupCard();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) ui.divider(card);
            int index = i;
            card.addView(slot(host, ids, i, () -> tapped.accept(index)));
        }
        return card;
    }
}
