package com.jjricks.volt;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The one-time What's new sheet (hub layout plan 1.8, PR 9; Joel 6): an owner who updated from a Volt without Clean
 * Home sees it once on Home (HomeStyle.showWhatsNew), introducing Clean and asking whether to switch (the
 * contributor's decisions on #383); an owner who already chose a style sees it only for the background-recording
 * notice (HomeStyle.whatsNewShows). While
 * HomeStyle.offerSwitch holds (no style chosen yet and Home not already Clean) it holds the offer: its title and
 * one sentence, the two styles drawn side by side (stacked on a narrow window or with large text), then
 * "Switch to Clean" and "Keep Widgets". Either button is an explicit choice (HomeStylePrefs.markChosen); closing the
 * sheet any other way keeps Widgets and records nothing. Without the offer, the heading of this version's notes is the
 * sheet's title. For an updating owner who records rides automatically, the notice that background starts are now off
 * with the switch to turn them on (owner's answer on #403) comes first, above the offer, since either offer button
 * closes the sheet (council early review of part 9); then this version's changelog with a link to the
 * full release notes (owner's answers 9, 10 and 12 on #383). A sheet that would hold none of these is not shown.
 * It records itself as shown (committed) just before it appears, so a crash or rotation never shows it again.
 * Text from HubText only; the notes are the bundled CHANGELOG.md section, in English.
 */
final class WhatsNewSheet {
    /** View tags: the sheet's body and its two buttons. */
    static final String TAG = "home-whats-new", TAG_SWITCH = "home-whats-new-switch", TAG_KEEP = "home-whats-new-keep";
    /** View tags: this version's notes, and the link to its release page. */
    static final String TAG_NOTES = "home-whats-new-notes", TAG_RELEASE_PAGE = "home-whats-new-release-page";
    /**
     * View tags: the notice for owners who record rides automatically (owner's answer on #403), its Start recording in
     * the background switch, and its link to Ride › Recording settings.
     */
    static final String TAG_BACKGROUND = "home-whats-new-background", TAG_BACKGROUND_SWITCH = "home-whats-new-background-switch",
            TAG_RECORDING_SETTINGS = "home-whats-new-recording-settings";

    private WhatsNewSheet() { }

    /**
     * Shows the sheet over the current screen and returns it, so the activity can close it when it goes away; records
     * it as shown first. Returns null, showing and recording nothing, when it would hold nothing
     * (HomeStyle.whatsNewShows): no offer, no notice and no bundled notes, or a chosen style and no notice.
     */
    static AlertDialog show(ScreenHost host, SharedPreferences preferences) {
        Ui ui = host.ui();
        java.util.Map<String, ?> all;
        try { all = preferences.getAll(); } catch (RuntimeException e) { return null; }
        boolean offer = HomeStyle.offerSwitch(Boolean.TRUE.equals(all.get(HomeStyle.CHOSEN_KEY)), all.get(HomeStyle.KEY));
        Object origin = all.get(HomeStyle.ORIGIN_KEY);
        Object autoRecord = all.get(AutoStartPolicy.AUTO_RECORD_KEY);
        boolean notice = HomeStyle.whatsNewBackgroundNotice(origin instanceof String ? (String) origin : null, Boolean.TRUE.equals(autoRecord));
        boolean chosen = Boolean.TRUE.equals(all.get(HomeStyle.CHOSEN_KEY));
        // An owner who already chose a style sees nothing without the notice: no need to read the notes then.
        if (!HomeStyle.whatsNewShows(chosen, offer, notice, false) && !HomeStyle.whatsNewShows(chosen, offer, notice, true)) return null;
        WhatsNewNotes.Notes notes = WhatsNewNotes.parse(bundled(host), AppVersion.name(host.activity()));
        if (!HomeStyle.whatsNewShows(chosen, offer, notice, notes != null)) return null;
        AlertDialog[] shown = new AlertDialog[1];
        Runnable close = () -> { if (shown[0] != null) shown[0].dismiss(); };
        // Without the offer the notes' heading (this version) titles the sheet; their own heading is then left out.
        // With the notice above the offer, the offer's sentence moves down to the pictures it introduces.
        boolean bodyWithOffer = offer && notice;
        LinearLayout content = offer
                ? host.sheetContent(HubText.whatsNewTitle(), bodyWithOffer ? null : HubText.whatsNewBody(), close)
                : host.sheetContent(HubText.whatsNewChanges(notes != null ? notes.version : AppVersion.name(host.activity())), null, close);
        content.setTag(TAG);
        // The notice first: either offer button closes the sheet, so it must be read before a choice (HomeStyle.whatsNewSections).
        for (String section : HomeStyle.whatsNewSections(offer, notice, notes != null)) {
            if (HomeStyle.SECTION_NOTICE.equals(section)) addBackgroundNotice(host, ui, content, preferences, close);
            else if (HomeStyle.SECTION_OFFER.equals(section)) addOffer(host, ui, content, preferences, close, bodyWithOffer);
            else addNotes(host, ui, content, notes, offer || notice, offer);
        }
        preferences.edit().putBoolean(HomeStyle.WHATS_NEW_SHOWN_KEY, true).commit();
        shown[0] = host.showSheet(content);
        return shown[0];
    }

    /**
     * The offer to switch to Clean (HomeStyle.offerSwitch): the two styles, the two buttons and the note under them.
     * Under the notice (afterNotice) a rule sets it apart and the offer's sentence, not in the sheet's head, starts it.
     */
    private static void addOffer(ScreenHost host, Ui ui, LinearLayout content, SharedPreferences preferences, Runnable close,
                                 boolean afterNotice) {
        if (afterNotice) {
            content.addView(rule(host, ui), ruleSpace(ui));
            TextView body = ui.caption(HubText.whatsNewBody().render());
            body.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13 * ui.textScale);
            body.setPadding(0, 0, 0, ui.dp(14));
            content.addView(body, new LinearLayout.LayoutParams(-1, -2));
        }
        float scale = ui.textScale * host.activity().getResources().getConfiguration().fontScale;
        boolean stacked = host.activity().getResources().getConfiguration().screenWidthDp < 360 || scale > 1.15f;
        LinearLayout styles = new LinearLayout(host.activity());
        styles.setOrientation(stacked ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        for (boolean clean : new boolean[] {true, false}) {
            LinearLayout.LayoutParams space = stacked ? new LinearLayout.LayoutParams(-1, -2) : new LinearLayout.LayoutParams(0, -2, 1);
            if (clean) {
                if (stacked) space.bottomMargin = ui.dp(10);
                else space.setMarginEnd(ui.dp(10));
            }
            styles.addView(style(host, ui, clean, stacked), space);
        }
        content.addView(styles, new LinearLayout.LayoutParams(-1, -2));
        TextView switchButton = ui.button(HubText.whatsNewSwitch().render(), () -> {
            HomeStylePrefs.markChosen(preferences, HomeStyle.CLEAN);
            close.run();
        });
        switchButton.setTag(TAG_SWITCH);
        LinearLayout.LayoutParams first = new LinearLayout.LayoutParams(-1, -2);
        first.topMargin = ui.dp(16);
        content.addView(switchButton, first);
        TextView keep = ui.secondaryButton(HubText.whatsNewKeep().render(), () -> {
            HomeStylePrefs.markChosen(preferences, HomeStyle.WIDGETS);
            close.run();
        });
        keep.setTag(TAG_KEEP);
        LinearLayout.LayoutParams second = new LinearLayout.LayoutParams(-1, -2);
        second.topMargin = ui.dp(8);
        content.addView(keep, second);
        TextView later = ui.caption(HubText.whatsNewLater().render());
        later.setGravity(Gravity.CENTER_HORIZONTAL);
        later.setPadding(ui.dp(4), ui.dp(10), ui.dp(4), 0);
        content.addView(later, new LinearLayout.LayoutParams(-1, -2));
    }

    /**
     * This version's changelog (owner's answers 9, 10 and 12 on #383): its heading, the line saying the notes are in
     * English while Volt is not, the notes as bullets with their bold leads, and a link to the release page with the
     * full notes. The notes are the section of CHANGELOG.md that build.sh bundled (WhatsNewNotes), so they need no
     * network; an APK built without a section for its version shows none of this. Under the offer or the notice the
     * notes start with a rule (separated); under the offer they also start with their heading (headed). Without the
     * offer the sheet's title is that heading, so it is not repeated.
     */
    private static void addNotes(ScreenHost host, Ui ui, LinearLayout content, WhatsNewNotes.Notes notes, boolean separated,
                                 boolean headed) {
        LinearLayout box = new LinearLayout(host.activity());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setTag(TAG_NOTES);
        if (separated) box.addView(rule(host, ui), ruleSpace(ui));
        if (headed) {
            TextView heading = ui.text(HubText.whatsNewChanges(notes.version), 17, true);
            if (android.os.Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
            box.addView(heading, new LinearLayout.LayoutParams(-1, -2));
        }
        if (!Fmt.legacy()) {
            TextView english = ui.caption(HubText.whatsNewNotesInEnglish().render());
            english.setPadding(0, ui.dp(4), 0, 0);
            box.addView(english, new LinearLayout.LayoutParams(-1, -2));
        }
        for (WhatsNewNotes.Item item : notes.items) {
            TextView line = ui.text("", 14, false);
            line.setText(styled(ui, item));
            line.setLineSpacing(ui.dp(2), 1);
            // The notes are English whatever the app's language.
            line.setTextLocale(java.util.Locale.ENGLISH);
            int start = item.nested ? ui.dp(16) : 0;
            line.setPaddingRelative(start, ui.dp(item.bullet ? 8 : 12), 0, 0);
            box.addView(line, new LinearLayout.LayoutParams(-1, -2));
        }
        TextView page = ui.secondaryButton(HubText.whatsNewReleasePage().render(), () -> openPage(host, notes.page()));
        page.setTag(TAG_RELEASE_PAGE);
        LinearLayout.LayoutParams pageSpace = new LinearLayout.LayoutParams(-1, -2);
        pageSpace.topMargin = ui.dp(14);
        box.addView(page, pageSpace);
        content.addView(box, new LinearLayout.LayoutParams(-1, -2));
    }

    /**
     * The notice for an updating owner who has Record rides automatically on (owner's answer on #403; the rule is
     * HomeStyle.whatsNewBackgroundNotice): a recording now starts in the background only when they turn that on.
     * Right under it, Recording settings' own Start recording in the background switch, so turning it back on is one
     * tap, and a link to Ride › Recording settings, the page that holds it with its note. The switch writes the same
     * key as that page (AutoStartPolicy.AUTO_RECORD_BACKGROUND_KEY); it is never greyed here, because the notice only
     * shows while Record rides automatically is on.
     */
    private static void addBackgroundNotice(ScreenHost host, Ui ui, LinearLayout content, SharedPreferences preferences, Runnable close) {
        boolean autoRecord, background;
        try {
            autoRecord = preferences.getBoolean(AutoStartPolicy.AUTO_RECORD_KEY, false);
            background = preferences.getBoolean(AutoStartPolicy.AUTO_RECORD_BACKGROUND_KEY, false);
        } catch (ClassCastException wrongType) {
            return;
        }
        String origin;
        try {
            origin = preferences.getString(HomeStyle.ORIGIN_KEY, null);
        } catch (ClassCastException wrongType) {
            origin = null;
        }
        if (!HomeStyle.whatsNewBackgroundNotice(origin, autoRecord)) return;
        LinearLayout box = new LinearLayout(host.activity());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setTag(TAG_BACKGROUND);
        // The notice is the sheet's first section (HomeStyle.whatsNewSections), right under its head.
        TextView notice = ui.text(HubText.whatsNewBackgroundNotice(), 14, false);
        notice.setLineSpacing(ui.dp(2), 1);
        box.addView(notice, new LinearLayout.LayoutParams(-1, -2));
        View toggle = ui.toggleRow(HubText.whatsNewBackgroundSwitch().render(), "walk", null, background, true,
                enabled -> preferences.edit().putBoolean(AutoStartPolicy.AUTO_RECORD_BACKGROUND_KEY, enabled).apply());
        toggle.setTag(TAG_BACKGROUND_SWITCH);
        LinearLayout.LayoutParams toggleSpace = new LinearLayout.LayoutParams(-1, -2);
        toggleSpace.topMargin = ui.dp(10);
        box.addView(toggle, toggleSpace);
        TextView page = ui.secondaryButton(HubText.whatsNewRecordingSettings().render(), () -> {
            close.run();
            host.open(HubPages.RIDE_RECORDING);
        });
        page.setTag(TAG_RECORDING_SETTINGS);
        LinearLayout.LayoutParams pageSpace = new LinearLayout.LayoutParams(-1, -2);
        pageSpace.topMargin = ui.dp(8);
        box.addView(page, pageSpace);
        content.addView(box, new LinearLayout.LayoutParams(-1, -2));
    }

    /** The thin rule that sets one section of the sheet apart from the one above it. */
    private static View rule(ScreenHost host, Ui ui) {
        View rule = new View(host.activity());
        rule.setBackgroundColor(ui.outline);
        return rule;
    }

    private static LinearLayout.LayoutParams ruleSpace(Ui ui) {
        LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-1, Math.max(1, ui.dp(1)));
        space.topMargin = ui.dp(18);
        space.bottomMargin = ui.dp(14);
        return space;
    }

    /** The bundled section (WhatsNewNotes.ASSET), or null when the APK has none or it can't be read. */
    private static String bundled(ScreenHost host) {
        try (java.io.InputStream in = host.activity().getAssets().open(WhatsNewNotes.ASSET)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read, total = 0;
            while ((read = in.read(chunk)) > 0 && total < 64 * 1024) { out.write(chunk, 0, read); total += read; }
            return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException | RuntimeException none) {
            return null;
        }
    }

    /** One item: a bullet (indented under its lead line), bold leads bold, code in the monospace face. */
    private static CharSequence styled(Ui ui, WhatsNewNotes.Item item) {
        android.text.SpannableStringBuilder text = new android.text.SpannableStringBuilder();
        for (WhatsNewNotes.Span span : item.spans) {
            int start = text.length();
            text.append(span.text);
            if (span.bold) text.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, text.length(), 0);
            if (span.code) text.setSpan(new android.text.style.TypefaceSpan("monospace"), start, text.length(), 0);
        }
        if (item.bullet && text.length() > 0)
            text.setSpan(new android.text.style.BulletSpan(ui.dp(10), item.nested ? ui.muted : ui.accent), 0, text.length(), 0);
        return text;
    }

    /** The release page in the browser: only Volt's own release pages (UpdateCheck.safePage). */
    private static void openPage(ScreenHost host, String url) {
        if (!UpdateCheck.safePage(url)) return;
        try {
            host.activity().startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)));
        } catch (android.content.ActivityNotFoundException none) {
            host.toast(HubText.whatsNewNoBrowser());
        }
    }

    /** One style: its drawing (which names itself for screen readers), its name and its one line. Not a button. */
    private static View style(ScreenHost host, Ui ui, boolean clean, boolean stacked) {
        LinearLayout card = new LinearLayout(host.activity());
        card.setOrientation(stacked ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        card.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12));
        card.setBackground(ui.shape(ui.surface, 16, clean ? ui.accent : ui.outline));
        HomeStylePreview preview = new HomeStylePreview(host, clean);
        // Side by side the drawing takes the card's width and a phone's proportions (HomeStylePreview.onMeasure).
        if (stacked) card.addView(preview, new LinearLayout.LayoutParams(ui.dp(104), -2));
        else card.addView(preview, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout words = new LinearLayout(host.activity());
        words.setOrientation(LinearLayout.VERTICAL);
        words.addView(ui.text(HubText.homeStyleName(clean), 15, true), new LinearLayout.LayoutParams(-1, -2));
        TextView line = ui.caption(HubText.homeStyleDescription(clean).render());
        line.setPadding(0, ui.dp(4), 0, 0);
        words.addView(line, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams wordsSpace = stacked ? new LinearLayout.LayoutParams(0, -2, 1) : new LinearLayout.LayoutParams(-1, -2);
        if (stacked) wordsSpace.setMarginStart(ui.dp(12));
        else wordsSpace.topMargin = ui.dp(10);
        card.addView(words, wordsSpace);
        return card;
    }
}
