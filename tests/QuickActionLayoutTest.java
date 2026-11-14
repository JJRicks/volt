package com.jjricks.volt;

/**
 * QuickActionLayout: Clean Home's four round quick actions as the Layout Plan's mock-up draws them (the contributor's
 * decisions on #383, 13 Nov 2026: one short name on one line under each circle, no value line), at the sizes the
 * maintainers check, 344x882 dp and 725x604 dp at 1x and 1.4x text, and 412 dp. The row's width is the window less
 * Home's 18 dp side padding (MainActivity's scroller), at most Clean's 560 dp column.
 *
 * <p>Text widths are measured with the app's own font (res/font/saira_regular.ttf, the name's typeface, passed as the
 * first argument) at the name's 13.5 sp times the text scale, so "Charge done by" is as wide here as on the emulator
 * (about 98 dp at 1x); the robustness checks run every case with text 15% wider. The names are the ones Clean shows
 * (HubText.quickActionRoundName, StatsLabels.row and the short rungs) in English and, for the long ones, in German and
 * Brazilian Portuguese (res/values-de, res/values-pt-rBR).
 *
 * <p>The ladder for narrow labels (council reviews 5904036506, finding 3, and 5904426594, findings 6 and 7): a shorter
 * word before smaller text. The first name whole at full size, else the largest size down to 75% at which one of them is
 * whole, else the icon alone; never a cut word.
 */
public final class QuickActionLayoutTest {
    private static int checks;
    private static java.awt.Font saira;

    /** The short names Clean shows under its circles that fit at full size on a 344 dp phone at 1x. */
    private static final String[] SHORT = {"Ride", "Record", "Alerts", "Cruise", "Lights", "Traction", "Recovery", "Max. speed"};

    private static QuickActionLayout.Width width(float sp, float wider) {
        java.awt.Font font = saira.deriveFont(sp);
        java.awt.font.FontRenderContext context = new java.awt.font.FontRenderContext(null, true, true);
        return text -> (float) font.getStringBounds(text, context).getWidth() * wider;
    }

    public static void main(String[] args) throws Exception {
        saira = java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, new java.io.File(args[0]));
        for (int window : new int[]{344, 412, 725})
            for (float text : new float[]{1f, 1.4f})
                for (float wider : new float[]{1f, 1.15f}) size(window, text, wider);
        ladder();
        languages();
        oneWord();
        order();
        narrow();
        states();
        System.out.println("QuickActionLayout: " + checks + " checks passed");
    }

    private static float room(int window) {
        float row = Math.min(window - 36, 560);
        return QuickActionLayout.room(QuickActionLayout.column(row, 4));
    }

    /** A fitted name is never cut: it is whole at its size or a little smaller, else the icon alone. */
    private static void whole(QuickActionLayout.Name fit, float room, QuickActionLayout.Width names, String at) {
        if (fit.iconOnly()) return;
        require(fit.scale >= QuickActionLayout.MIN_SCALE && fit.scale <= 1, at + ": no smaller than 75%: " + fit);
        require(names.of(fit.text) * fit.scale <= room, at + ": " + fit.text + " whole in its column: " + fit);
    }

    /** One of the maintainers' sizes: four equal circles in a row, every name whole on one line or the icon alone. */
    private static void size(int window, float text, float wider) {
        String at = window + " dp at " + text + "x" + (wider > 1 ? ", wider text" : "");
        float row = Math.min(window - 36, 560);
        require(QuickActionLayout.columns(row, 4) == 4, at + ": four in a row");
        float column = QuickActionLayout.column(row, 4);
        require(column >= 48, at + ": each column is a full touch target");
        require(QuickActionLayout.circleDp(column) == QuickActionLayout.CIRCLE_DP, at + ": the plan's 54 dp circle");
        float room = QuickActionLayout.room(column);
        QuickActionLayout.Width names = width(QuickActionLayout.NAME_SP * text, wider);
        for (String name : SHORT) {
            QuickActionLayout.Name fit = QuickActionLayout.name(room, names, name);
            whole(fit, room, names, at);
            require(fit.iconOnly() || fit.text.equals(name), at + ": " + name + " is shown as it is, or the icon alone: " + fit);
            if (wider == 1 && text == 1) require(!fit.iconOnly(), at + ": " + name + " whole at 1x: " + fit);
            System.out.println("  " + at + ": " + name + " -> " + fit);
        }
        // The four names the mock-up shows, and the defaults' short names, whole at full size on every phone at 1x.
        if (wider == 1 && text == 1)
            for (String name : new String[]{"Ride", "Record", "Cruise", "Alerts"})
                require(QuickActionLayout.name(room, names, name).scale == 1 && !QuickActionLayout.name(room, names, name).iconOnly(),
                        at + ": " + name + " at its full size");
        // At 1.4x the mock-up's four stay whole on a 344 dp phone (a little smaller where they must).
        if (wider == 1)
            for (String name : new String[]{"Ride", "Record", "Cruise", "Alerts"})
                require(!QuickActionLayout.name(room, names, name).iconOnly(), at + ": " + name + " whole");
        // The electronic lock's round name is the lock reading's one-word compact name (review of 98be0a02): "Lock" at
        // full size on one line, as the names beside it, with "Electronic lock" only as a rung it never needs.
        if (wider == 1) {
            QuickActionLayout.Name lock = QuickActionLayout.name(room, names, "Lock", "Electronic lock", "Electronic lock");
            require("Lock".equals(lock.text) && lock.scale == 1, at + ": Lock at its full size: " + lock);
            // German "Sperre" and pt-BR "Bloqueio" stay whole on one line (a little smaller where they must).
            for (String translated : new String[]{"Sperre", "Bloqueio"}) {
                QuickActionLayout.Name other = QuickActionLayout.name(room, names, translated);
                whole(other, room, names, at);
                require(translated.equals(other.text), at + ": " + translated + " whole: " + other);
            }
            System.out.println("  " + at + ": Lock -> " + lock);
        }
        // Charge done by has no shorter English name yet (part 10's "Charge by"): whole where it fits at 75% or more,
        // else the icon alone, never "Charge do…".
        QuickActionLayout.Name done = QuickActionLayout.name(room, names, "Charge done by");
        whole(done, room, names, at);
        if (window == 344) require(done.iconOnly(), at + ": Charge done by does not fit whole: the icon alone: " + done);
        if (window == 725 && wider == 1) require("Charge done by".equals(done.text), at + ": Charge done by whole: " + done);
        System.out.println("  " + at + ": Charge done by -> " + done);
    }

    /** The ladder: the whole name, then a shorter existing name, then the icon alone. */
    private static void ladder() {
        QuickActionLayout.Width w = text -> text.length() * 10f;
        QuickActionLayout.Name name = QuickActionLayout.name(60, w, "Abcdefghijkl", "Abcde");
        require("Abcde".equals(name.text) && name.scale == 1, "too wide even at 75%: the shorter name: " + name);
        name = QuickActionLayout.name(60, w, "Abcdefg", "Abc");
        require("Abc".equals(name.text) && name.scale == 1, "a shorter word before smaller text: the shorter name at full size: " + name);
        name = QuickActionLayout.name(60, w, "Abcdefg", "Abcdefgh");
        require("Abcdefg".equals(name.text) && name.scale < 1, "no shorter name: the name a little smaller: " + name);
        name = QuickActionLayout.name(60, w, "Abcdefgh", "Abcdefg");
        require("Abcdefg".equals(name.text) && name.scale == .85f, "none fits at full size: the largest size at which one fits: " + name);
        name = QuickActionLayout.name(60, w, "Abcdefghijkl", "Abcdefghijk");
        require(name.iconOnly(), "no name fits whole: the icon alone: " + name);
        name = QuickActionLayout.name(60, w, "Abcdefghijkl", null, "", "Abcdefghijklmn", "Abcdef");
        require("Abcdef".equals(name.text), "empty names and names no narrower are skipped: " + name);
        // Each size is checked as it is drawn: a smaller size a few pixels wider than in proportion takes the next step.
        QuickActionLayout.ScaledWidth drawn = (text, scale) -> text.length() * 10f * scale + (scale < 1 ? 3 : 0);
        name = QuickActionLayout.name(60, drawn, "Abcdefg");
        require(name.scale < .85f && 70 * name.scale + 3 <= 60, "a size wider than in proportion takes the next step down: " + name);
        require(QuickActionLayout.name(60, drawn, "Abcdefgh").iconOnly(), "no size that fits as drawn: the icon alone");
        require(QuickActionLayout.name(60, w).iconOnly() && QuickActionLayout.name(60, w, (String) null).iconOnly()
                && QuickActionLayout.name(60, w, "").iconOnly(), "no name: the icon alone");
    }

    /**
     * Long names in other languages at 344 dp, 1x and 1.4x: German "Traktionskontrolle" (its tile rung is the same word)
     * falls to the icon alone; pt-BR cruise control's Live Stats name "Controle de cruzeiro" gives way to the existing
     * Setting tile rung "Cruzeiro" (finding 12), whole; German "Tempomat" stays whole.
     */
    private static void languages() {
        for (float text : new float[]{1f, 1.4f}) {
            float room = room(344);
            QuickActionLayout.Width names = width(QuickActionLayout.NAME_SP * text, 1);
            String at = "344 dp at " + text + "x";
            QuickActionLayout.Name traction = QuickActionLayout.name(room, names, "Traktionskontrolle", "Traktionskontrolle", "Traktionskontrolle");
            require(traction.iconOnly(), at + ": de Traktionskontrolle: the icon alone: " + traction);
            QuickActionLayout.Name cruise = QuickActionLayout.name(room, names, "Controle de cruzeiro", "Cruzeiro", "Cruzeiro");
            whole(cruise, room, names, at);
            require("Cruzeiro".equals(cruise.text), at + ": pt-BR cruise control: Cruzeiro, whole: " + cruise);
            // The long Live Stats name is tried first but the short Setting tile name wins at full size, never the long
            // one shrunk (council review 5904426594, finding 6), at every width that has to choose.
            for (int window : new int[]{344, 412}) {
                QuickActionLayout.Name pt = QuickActionLayout.name(room(window), names, "Controle de cruzeiro", "Cruzeiro", "Cruzeiro");
                require("Cruzeiro".equals(pt.text) && pt.scale == 1, window + " dp at " + text + "x: Cruzeiro at full size: " + pt);
            }
            // The electronic lock's round name is the lock reading's compact name (council review of 98be0a02,
            // findings 1 and 3): "Lock" at full size, never "Electronic lock" shrunk or the icon alone.
            QuickActionLayout.Name lock = QuickActionLayout.name(room, names, "Lock", "Electronic lock", "Electronic lock");
            require("Lock".equals(lock.text) && lock.scale == 1, at + ": Lock at full size: " + lock);
            // de "Sperre", fr "Verrou", pt-BR "Bloqueio" (readings_lock_status_compact) are shown whole too (pt-BR a
            // little smaller at 1.4x), never the long name.
            for (String word : new String[]{"Sperre", "Verrou", "Bloqueio"}) {
                QuickActionLayout.Name fit = QuickActionLayout.name(room, names, word, "Elektronische Sperre");
                whole(fit, room, names, at);
                require(word.equals(fit.text) && (fit.scale == 1 || text > 1), at + ": " + word + " whole: " + fit);
            }
            QuickActionLayout.Name tempomat = QuickActionLayout.name(room, names, "Tempomat", "Tempomat", "Tempomat");
            require("Tempomat".equals(tempomat.text), at + ": de Tempomat whole: " + tempomat);
            System.out.println("  " + at + ": Traktionskontrolle -> " + traction + ", Controle de cruzeiro -> " + cruise + ", Tempomat -> " + tempomat);
            // On the wide window the long pt-BR name has room: it is shown whole.
            QuickActionLayout.Name wide = QuickActionLayout.name(room(725), names, "Controle de cruzeiro", "Cruzeiro");
            whole(wide, room(725), names, "725 dp at " + text + "x");
            require(!wide.iconOnly(), "725 dp at " + text + "x: pt-BR cruise control whole: " + wide);
            // Review of #449 (f5540133), finding 3: the one-word name comes first (oneWordFirst), so pt-BR cruise
            // control reads "Cruzeiro" at full size in every column, wide ones included, never "Controle de cruzeiro".
            String[] ptCruise = QuickActionLayout.oneWordFirst("Controle de cruzeiro", "Cruzeiro", "Cruzeiro");
            for (int window : new int[]{344, 412, 725}) {
                QuickActionLayout.Name pt = QuickActionLayout.name(room(window), names, ptCruise);
                require("Cruzeiro".equals(pt.text) && pt.scale == 1, window + " dp at " + text + "x: one word first, Cruzeiro at full size: " + pt);
            }
        }
    }

    /** oneWordFirst: a one-word name moves to the front only when the first has more than one word. */
    private static void oneWord() {
        require(java.util.Arrays.equals(QuickActionLayout.oneWordFirst("Controle de cruzeiro", "Cruzeiro", "Cruzeiro"),
                new String[]{"Cruzeiro", "Controle de cruzeiro", "Cruzeiro"}), "pt-BR: Cruzeiro first, the rest in order");
        require(java.util.Arrays.equals(QuickActionLayout.oneWordFirst("Cruise", "Cruise ctrl", "Cruise"),
                new String[]{"Cruise", "Cruise ctrl", "Cruise"}), "en: Cruise is one word already: unchanged");
        require(java.util.Arrays.equals(QuickActionLayout.oneWordFirst("Contrôle traction", null, "Antipatinage"),
                new String[]{"Antipatinage", "Contrôle traction", null}), "a null rung is kept in place, the word moves first");
        require(java.util.Arrays.equals(QuickActionLayout.oneWordFirst("Max. speed", "Max speed"),
                new String[]{"Max. speed", "Max speed"}), "no one-word name: unchanged");
        require(QuickActionLayout.oneWordFirst().length == 0, "no names: none");
        require(java.util.Arrays.equals(QuickActionLayout.oneWordFirst("Controle\u00a0de cruzeiro", "Cruzeiro"),
                new String[]{"Cruzeiro", "Controle\u00a0de cruzeiro"}), "a no-break space counts as a space");
    }

    /** Full size when it fits; smaller (never below the least share) while that keeps it whole; else the next rung. */
    private static void order() {
        QuickActionLayout.Width w = text -> text.length() * 10f;
        QuickActionLayout.Name name = QuickActionLayout.name(60, w, "Abcdef");
        require(name.scale == 1 && "Abcdef".equals(name.text), "the name at its size when it fits: " + name);
        name = QuickActionLayout.name(60, w, "Abcdefg");
        require(name.scale < 1 && name.scale >= QuickActionLayout.MIN_SCALE && 70 * name.scale <= 60,
                "a little smaller when it is a little too wide: " + name);
        name = QuickActionLayout.name(60, w, "Abcdefghijkl");
        require(name.iconOnly(), "failing that, with no shorter name, the icon alone (never a cut word): " + name);
        // Each step is the largest that fits.
        for (int length = 6; length <= 8; length++) {
            String text = "Abcdefghij".substring(0, length);
            QuickActionLayout.Name fit = QuickActionLayout.name(60, w, text);
            if (!fit.iconOnly() && fit.scale < 1) require(length * 10 * (fit.scale + .05f) > 60, "the largest step that fits for " + text + ": " + fit);
        }
    }

    /** A narrow column (a split screen, a small phone): the circle gets smaller, then two rows of two. */
    private static void narrow() {
        require(QuickActionLayout.columns(280, 4) == 4 && QuickActionLayout.circleDp(QuickActionLayout.column(280, 4)) == 54, "280 dp: four, at 54 dp");
        require(QuickActionLayout.columns(240, 4) == 2, "240 dp: two rows of two");
        require(QuickActionLayout.circleDp(40) == QuickActionLayout.MIN_CIRCLE_DP && QuickActionLayout.circleDp(50) == 50, "the circle never gets smaller than 44 dp");
        require(QuickActionLayout.columns(400, 3) == 3 && QuickActionLayout.columns(400, 1) == 1 && QuickActionLayout.columns(400, 0) == 1, "fewer actions, fewer columns");
    }

    /**
     * How a circle shows its state (council review 5904036506, findings 1, 2 and 11): the lock is never shown locked or
     * unlocked when Volt has not read it, connected or not; a read state shows as locked or unlocked, dimmed while it is
     * the last known one; Battery alerts and an armed Charge done by show on from Volt's saved settings, offline too.
     */
    private static void states() {
        for (boolean live : new boolean[]{true, false}) {
            String at = live ? "connected" : "not connected";
            QuickActionLayout.State unknown = QuickActionLayout.lock(null, live);
            require(unknown.look == QuickActionLayout.Look.UNKNOWN && !unknown.open, at + ": an unread lock has its own look: " + unknown);
            require(QuickActionLayout.lock(2, live).look == QuickActionLayout.Look.UNKNOWN, at + ": an unreadable lock value is not known: " + QuickActionLayout.lock(2, live));
            QuickActionLayout.State locked = QuickActionLayout.lock(1, live), unlocked = QuickActionLayout.lock(0, live);
            require(locked.look == QuickActionLayout.Look.ON && !locked.open && locked.dimmed == !live, at + ": locked: filled, closed padlock: " + locked);
            require(unlocked.look == QuickActionLayout.Look.OFF && unlocked.open && unlocked.dimmed == !live, at + ": unlocked: the open padlock: " + unlocked);
            require(unknown.look != locked.look && unknown.look != unlocked.look, at + ": unknown looks like neither state");

            QuickActionLayout.State charge = QuickActionLayout.alerts(80, null), low = QuickActionLayout.alerts(0, 20),
                    both = QuickActionLayout.alerts(80, null), off = QuickActionLayout.alerts(0, null), none = QuickActionLayout.alerts(null, null);
            require(charge.look == QuickActionLayout.Look.ON && !charge.dimmed, "the charge alert at 80%: on");
            require(low.look == QuickActionLayout.Look.ON && !low.dimmed, "the low-battery alert at 20%: on");
            require(both.look == QuickActionLayout.Look.ON, "both alerts: on");
            require(off.look == QuickActionLayout.Look.OFF && !off.dimmed, "both off: off, not dimmed (a setting Volt has)");
            require(none.look == QuickActionLayout.Look.OFF && none.dimmed, "no battery alerts on this model: off, dimmed");

            // A setting with an Off among others (council review 5904426594, finding 1): energy recovery is on at Low,
            // Medium and High, from the setting read now or last known (dimmed), off at Off; a model with no Off is on
            // at every level; an unread setting is plain and dimmed.
            Integer offRaw = 0; // ScooterCommands.Feature.offValue for energy recovery (ScooterCommandsTest)
            for (int level : new int[]{30, 60, 90}) {
                QuickActionLayout.State on = QuickActionLayout.setting(level, offRaw, live);
                require(on.look == QuickActionLayout.Look.ON && on.dimmed == !live, at + ": recovery at " + level + ": on: " + on);
                require(QuickActionLayout.setting(level, null, live).look == QuickActionLayout.Look.ON, at + ": no Off on the model: on at " + level);
            }
            QuickActionLayout.State recoveryOff = QuickActionLayout.setting(0, offRaw, live);
            require(recoveryOff.look == QuickActionLayout.Look.OFF && recoveryOff.dimmed == !live, at + ": recovery Off: off: " + recoveryOff);
            QuickActionLayout.State unread = QuickActionLayout.setting(null, offRaw, live);
            require(unread.look == QuickActionLayout.Look.OFF && unread.dimmed, at + ": recovery never read: plain, dimmed: " + unread);
            // A light's Off / On / Follow headlight: on unless Off.
            Integer lightOff = 0;
            require(QuickActionLayout.setting(2, lightOff, live).look == QuickActionLayout.Look.ON
                    && QuickActionLayout.setting(0, lightOff, live).look == QuickActionLayout.Look.OFF, at + ": a light following the headlight is on");

            QuickActionLayout.State armed = QuickActionLayout.schedule(true, live), idle = QuickActionLayout.schedule(false, live);
            require(armed.look == QuickActionLayout.Look.ON && armed.dimmed == !live, at + ": an armed Charge done by is on: " + armed);
            require(idle.look == QuickActionLayout.Look.OFF && idle.dimmed == !live, at + ": Charge done by off: " + idle);
        }
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
