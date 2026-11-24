package com.jjricks.volt;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * 2.0.5 Home / Widgets / navigation P3s whose fix lives in Android-only code, checked in that code (the emulator steps
 * see them on screen: dock-sheet-*, responsive, widgets-edit-*, my-scooters-*):
 * <ul>
 *   <li>Motion's Refade names the values it is kept for, so a later change on the same view cancels a fade still running
 *   (#529 review, F01: a quick tab A, B, A drew the page foot's Echo off its place).</li>
 *   <li>HubPanes measures its gutter too when it re-splits at layout (#532 review, F02).</li>
 *   <li>Tabletop posture: the pane above the hinge follows HubPages.pageScrollBar, so Home shows no page scroll bar there
 *   either (#551 review).</li>
 *   <li>DragSheet: while a sheet settles closed, key focus waits on the overlay and goes back to the page view only once
 *   the sheet has gone (#517 review, round 2: Enter right after Back reached Home's lock under the closing sheet); and the
 *   status bar's icons stay dark while the sheet's light surface is under the bar (#510 review, round 5).</li>
 *   <li>Reading tiles search their label mode again when a value grew past the row (ReadingSpan.refit; #514, F02).</li>
 * </ul>
 */
public final class HomeWidgetsNavTest {
    private static int checks;

    public static void main(String[] args) {
        String motion = read("src/com/jjricks/volt/Motion.java");
        int refade = motion.indexOf("private static final class Refade extends Transition");
        int echo = motion.indexOf("private static final class Echo", refade);
        check(refade > 0 && echo > refade, "Motion has its Refade and Echo");
        String body = motion.substring(refade, echo);
        check(body.contains("@Override public String[] getTransitionProperties() { return PROPERTIES; }")
                && body.contains("private static final String[] PROPERTIES = {TOP, SHOWN};"),
                "Refade names its values (top and shown), so a later change cancels a fade still running");

        String panes = read("src/com/jjricks/volt/HubPanes.java");
        int layout = panes.indexOf("@Override protected void onLayout");
        String onLayout = panes.substring(layout, panes.indexOf("super.onLayout", layout));
        check(onLayout.contains("first.measure(") && onLayout.contains("gutter.measure(MeasureSpec.makeMeasureSpec(wanted.gap, MeasureSpec.EXACTLY), height);")
                && onLayout.contains("second.measure("), "HubPanes re-measures the gutter with the panes when it re-splits at layout");

        String main = read("src/com/jjricks/volt/MainActivity.java");
        int upper = main.indexOf("ScrollView upper=new ScrollView(this);");
        check(upper > 0 && main.indexOf("upper.setVerticalScrollBarEnabled(HubPages.pageScrollBar(selectedPage));", upper) > upper
                && main.indexOf("upper.setVerticalScrollBarEnabled(HubPages.pageScrollBar(selectedPage));", upper) < main.indexOf("content.addView(upper", upper),
                "the tabletop pane above the hinge follows the page's scroll bar rule");
        check(!HubPages.pageScrollBar(HubPages.HOME) && HubPages.pageScrollBar(HubPages.SETTINGS), "Home has no page scroll bar; other pages do");

        String sheet = read("src/com/jjricks/volt/DragSheet.java");
        int give = sheet.indexOf("private void giveFocus(SheetCover<View> windowCover, boolean closing)");
        String giveFocus = sheet.substring(give, sheet.indexOf("\n    }\n", give));
        check(giveFocus.contains("if (!closing && back != null && back.isAttachedToWindow()"),
                "a closing sheet never hands key focus back to the page view before it has gone");
        check(giveFocus.contains("} else if (inMe && closing) {"), "while it closes the overlay stands in for the page view");
        check(sheet.contains("statusIcons((float) (dim * showing), SheetSpring.coversStatusIcons(box.top, statusBarBottom));")
                && sheet.contains("SheetSpring.whiteIcons(ui.bg, alpha, underBar)") && sheet.contains("statusBarBottom = top;"),
                "the status icons know when the sheet's surface is under the status bar");

        String dashboard = read("src/com/jjricks/volt/DashboardScreen.java");
        check(dashboard.contains("if (ReadingSpan.refit(sameSpace, natural, perRow, mode == VALUE)) {")
                && dashboard.contains("int from = sameSpace ? mode : cramped ? VALUE : FULL;"),
                "Reading tiles search again, from their kept mode, when a value grew past the row");
        check(!dashboard.contains("if (width == fittedWidth && budget == fittedBudget && columns == fittedColumns) natural = measureCells(-1);"),
                "the tiles no longer keep a mode that stopped fitting");
        System.out.println("HomeWidgetsNavTest: " + checks + " checks passed");
    }

    private static String read(String path) {
        try {
            return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
}
