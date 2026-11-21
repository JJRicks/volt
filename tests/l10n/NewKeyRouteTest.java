package com.jjricks.volt;

import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.io.File;

/**
 * Setup's "Make a new owner key" page, version C (a scooter still in the NAVEE app refuses a made key):
 *   - NewKeyGate: Continue works only once the store is ready and "Scooter isn't in the NAVEE app" is ticked; the
 *     parked tick is no input. In source, newKey() enables Continue through the gate from the new0 tick alone, its
 *     ticks re-run the page's updaters on each tap (no other page's do), and makeNewKey() refuses through the same gate.
 *   - The English: the choice detail keeps its no-break spaces (a narrow card wraps after the dot and never splits
 *     "no computer"), and the new-key tick no longer says "new or reset".
 *   - The link's ladder: in source, the full rung or the _short rung picked by TileRung with the link's own paint and
 *     room, Chrome/USB chosen and Before you start opened. Measured with Saira at the link's 14.5 sp, the short rung
 *     fits the 344 dp phone's room (288 dp: 344 less the page's 2 x 20 dp and the link's 2 x 8 dp) and the full one
 *     a 411 dp phone's (355 dp), as on the emulator.
 * Runs from the repository root (it reads sources and res/font).
 */
public final class NewKeyRouteTest {
    private static int checks;
    private static final float LINK_SP = 14.5f;

    public static void main(String[] args) throws Exception {
        try {
            T.install(null);
            gate();
            english();
            ladder();
        } finally {
            T.install(null);
        }
        sources();
        System.out.println("NewKeyRouteTest: " + checks + " checks passed (gate, English, link ladder, sources)");
    }

    private static void gate() {
        check(!NewKeyGate.continueEnabled(true, false), "Continue waits for the NAVEE tick");
        check(NewKeyGate.continueEnabled(true, true), "the NAVEE tick enables Continue");
        check(!NewKeyGate.continueEnabled(false, true), "Continue still waits for the store while the service starts");
        check(!NewKeyGate.continueEnabled(false, false), "neither ready nor ticked");
    }

    private static void english() {
        String detail = T.onboardingPathNewKeyDetail();
        check(detail.equals("Not in the NAVEE app · no computer"), "the choice detail: " + detail);
        check(detail.indexOf("app ·") > 0 && detail.contains("· no computer"),
                "no-break spaces keep the dot with app and no computer whole; the only break is after the dot");
        check(T.onboardingNewUnowned().equals("Scooter isn't in the NAVEE app"), "the tick: " + T.onboardingNewUnowned());
        check(T.onboardingNewUnownedDetail().equals("Never added there, or removed from it"), "the tick's detail");
        for (String old : new String[]{detail, T.onboardingNewUnowned(), T.onboardingNewUnownedDetail()})
            check(!old.toLowerCase(java.util.Locale.ROOT).contains("reset"), "no new-key text says reset: " + old);
        check(T.onboardingNewCopyInstead().equals("Already in the NAVEE app? Copy its key instead"), "the link's full rung");
        check(T.onboardingNewCopyInsteadShort().equals("In the NAVEE app? Copy its key"), "the link's short rung");
    }

    private static void ladder() throws Exception {
        System.setProperty("java.awt.headless", "true");
        Font saira = Font.createFont(Font.TRUETYPE_FONT, new File(L10nFiles.root(), "res/font/saira_regular.ttf")).deriveFont(LINK_SP);
        FontRenderContext context = new FontRenderContext(null, true, true);
        String full = T.onboardingNewCopyInstead(), brief = T.onboardingNewCopyInsteadShort();
        float fullWidth = (float) saira.getStringBounds(full, context).getWidth();
        float briefWidth = (float) saira.getStringBounds(brief, context).getWidth();
        int narrow = 344 - 2 * 20 - 2 * 8, phone = 411 - 2 * 20 - 2 * 8;
        check(briefWidth < fullWidth, "the short rung is narrower");
        check(briefWidth <= narrow, "the short rung fits one line at 344 dp: " + briefWidth + " of " + narrow);
        check(TileRung.pick(full, fullWidth, brief, narrow) == brief, "344 dp shows the short rung (" + fullWidth + " of " + narrow + ")");
        check(TileRung.pick(full, fullWidth, brief, phone) == full, "411 dp shows the full rung (" + fullWidth + " of " + phone + ")");
        CharSequence shown = full;
        check(TileRung.needsSet(TileRung.pick(full, fullWidth, brief, narrow), shown), "narrowing sets the short rung");
        shown = brief;
        check(!TileRung.needsSet(TileRung.pick(full, fullWidth, brief, narrow), shown), "the same room sets nothing again (no loop)");
        check(TileRung.needsSet(TileRung.pick(full, fullWidth, brief, phone), shown), "widening sets the full rung again");
        System.out.println("  link full " + Math.round(fullWidth) + " dp, short " + Math.round(briefWidth) + " dp; rooms "
                + narrow + " (344) and " + phone + " (411)");
    }

    private static void sources() {
        String screen = L10nFiles.read(new File(L10nFiles.root(), "src/com/jjricks/volt/OnboardingScreen.java"));
        String page = body(screen, "private void newKey()");
        check(page.indexOf("tickChanged = this::runUpdaters;") >= 0
                && page.indexOf("tickChanged = this::runUpdaters;") < page.indexOf("tick(\"new0\", T.onboardingNewUnowned(), T.onboardingNewUnownedDetail())"),
                "the page's ticks re-run its updaters on each tap");
        check(page.contains("next.setEnabled(NewKeyGate.continueEnabled(storeReady, notInNaveeTicked()));"),
                "Continue is enabled through the gate");
        check(page.indexOf("copyKeyInstead();") > page.indexOf("T.onboardingNewNaveeNote()"), "the link comes under the NAVEE note");
        check(body(screen, "private boolean notInNaveeTicked()").contains("KEY_TICK + \"new0\""), "the gate reads the new0 tick alone");
        check(body(screen, "private void makeNewKey()").contains("if (!NewKeyGate.continueEnabled(store != null, notInNaveeTicked())) return;"),
                "makeNewKey refuses through the same gate");
        String link = body(screen, "private void copyKeyInstead()");
        check(link.contains("prefs.edit().putString(KEY_PATH, WEBADB).apply();") && link.contains("go(PREPARE);"),
                "the link chooses Chrome/USB and opens Before you start");
        check(link.contains("TileRung.pick(full, instead.getPaint().measureText(full, 0, full.length()), brief, room)")
                && link.contains("if (TileRung.needsSet(pick, instead.getText()))"), "the link picks its rung with its own paint");
        check(link.contains("instead.setContentDescription(full);"), "TalkBack reads the full rung");
        String tick = body(screen, "private View tick(String key, String heading, String detail)");
        check(tick.contains("if (tickChanged != null) tickChanged.run();"), "a tick tap runs the page's callback");
        check(body(screen, "private void render()").contains("tickChanged = null;"), "every other page starts with no tick callback");
    }

    /** The text of the method starting at signature, through its matching closing brace. */
    private static String body(String source, String signature) {
        int start = source.indexOf(signature);
        check(start >= 0, "found " + signature);
        int open = source.indexOf('{', start), depth = 0;
        for (int at = open; at < source.length(); at++) {
            char c = source.charAt(at);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return source.substring(start, at + 1);
        }
        throw new AssertionError("unclosed " + signature);
    }

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }
}
