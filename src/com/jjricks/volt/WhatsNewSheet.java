package com.jjricks.volt;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The one-time What's new sheet about Home styles (hub layout plan 1.8, PR 9; Joel 6): an owner who updated from a
 * Volt without Clean Home, and never chose a style, sees it once on the widget dashboard. Its title and one sentence,
 * the two styles drawn side by side (stacked on a narrow window or with large text), then "Switch to Clean" and
 * "Keep Widgets". Either button is an explicit choice (HomeStylePrefs.markChosen); closing the sheet any other way
 * keeps Widgets and records nothing, and MainActivity has already recorded that it was shown, so it never returns.
 * Below them, for an updating owner who records rides automatically, the notice that background starts are now off
 * with the switch to turn them on (owner's answer on #403), then this version's changelog with a link to the full
 * release notes (owner's answers 9, 10 and 12 on #383).
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

    /** Shows the sheet over the current screen and returns it, so the activity can close it when it goes away. */
    static AlertDialog show(ScreenHost host, SharedPreferences preferences) {
        Ui ui = host.ui();
        AlertDialog[] shown = new AlertDialog[1];
        Runnable close = () -> { if (shown[0] != null) shown[0].dismiss(); };
        LinearLayout content = host.sheetContent(HubText.whatsNewTitle(), HubText.whatsNewBody(), close);
        content.setTag(TAG);
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
        addBackgroundNotice(host, ui, content, preferences, close);
        addNotes(host, ui, content);
        shown[0] = host.showSheet(content);
        return shown[0];
    }

    /**
     * This version's changelog (owner's answers 9, 10 and 12 on #383): its heading, the line saying the notes are in
     * English while Volt is not, the notes as bullets with their bold leads, and a link to the release page with the
     * full notes. The notes are the section of CHANGELOG.md that build.sh bundled (WhatsNewNotes), so they need no
     * network; an APK built without a section for its version shows none of this.
     */
    private static void addNotes(ScreenHost host, Ui ui, LinearLayout content) {
        WhatsNewNotes.Notes notes = WhatsNewNotes.parse(bundled(host), AppVersion.name(host.activity()));
        if (notes == null) return;
        LinearLayout box = new LinearLayout(host.activity());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setTag(TAG_NOTES);
        View rule = new View(host.activity());
        rule.setBackgroundColor(ui.outline);
        LinearLayout.LayoutParams ruleSpace = new LinearLayout.LayoutParams(-1, Math.max(1, ui.dp(1)));
        ruleSpace.topMargin = ui.dp(18);
        ruleSpace.bottomMargin = ui.dp(14);
        box.addView(rule, ruleSpace);
        TextView heading = ui.text(HubText.whatsNewChanges(notes.version), 17, true);
        if (android.os.Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        box.addView(heading, new LinearLayout.LayoutParams(-1, -2));
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
        View rule = new View(host.activity());
        rule.setBackgroundColor(ui.outline);
        LinearLayout.LayoutParams ruleSpace = new LinearLayout.LayoutParams(-1, Math.max(1, ui.dp(1)));
        ruleSpace.topMargin = ui.dp(18);
        ruleSpace.bottomMargin = ui.dp(14);
        box.addView(rule, ruleSpace);
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
