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

    /** The name an action shows under its round button on Clean ("Cruise" for cruise control), or its id if unknown. */
    static String roundName(String id) {
        QuickAccessActions.Entry entry = HomeQuickActions.entry(id);
        return entry == null ? id : QuickAccessGrid.roundName(entry);
    }

    /**
     * The four names as Clean shows them, in order: "Ride, Record, Charge done by, Cruise". The separator is a plain comma
     * until part 10 adds a translated list separator.
     */
    static String summary(List<String> ids) {
        List<String> names = new ArrayList<>();
        for (String id : ids) names.add(roundName(id));
        return String.join(SEPARATOR, names);
    }

    /** Between the names on Settings › Home style's Quick actions row (part 10 replaces it with catalog text). */
    static final String SEPARATOR = ", ";

    /**
     * One of the four in an editing list: its icon and full name, a chevron; a tap runs tapped (which opens the
     * picker for that position). Tagged {@code CleanHomeScreen.TAG_SLOT + (index + 1)}, as Clean's Edit sheet always was.
     */
    static View slot(ScreenHost host, List<String> ids, int index, Runnable tapped) {
        Ui ui = host.ui();
        String id = ids.get(index);
        QuickAccessActions.Entry entry = HomeQuickActions.entry(id);
        CharSequence label = entry == null ? id : entry.label().render();
        LinearLayout line = new LinearLayout(host.activity());
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setTag(CleanHomeScreen.TAG_SLOT + (index + 1));
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
