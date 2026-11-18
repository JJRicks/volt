package com.jjricks.volt;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** HubPages: the page tree, legacy ids, More's sections and the riding rule (hub layout plan, PRs 1 and 11). */
public final class HubPagesTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        tree();
        legacy();
        subjects();
        hub();
        dock();
        dockMorph();
        dockKeyboard();
        homeScrollAndTop();
        barAboveDock();
        riding();
        connectionLine();
        withoutService();
        moreSections(args.length > 0 ? args[0] : "src/com/jjricks/volt/MoreScreen.java");
        iconsDrawn(args.length > 1 ? args[1] : "src/com/jjricks/volt/Ui.java");
        System.out.println("HubPages: " + checks + " checks passed");
    }

    /**
     * The owner, 17 Nov 2026: no page scroll bar on Home (either style, either pane), every other page keeps #522's; and
     * Home's header right under the status bar, every other page keeping its scroller's top room.
     */
    private static void homeScrollAndTop() {
        require(!HubPages.pageScrollBar(HubPages.HOME), "Home has no page scroll bar");
        for (String page : HubPages.ALL) if (!page.equals(HubPages.HOME)) require(HubPages.pageScrollBar(page), page + " keeps its page scroll bar");
        require(HubPages.pageScrollBar(HubPages.SETTINGS) && HubPages.pageScrollBar(HubPages.SUBJECTS.get(0)), "Settings and a subject keep the bar");
        require(HubPages.pageTopDp(HubPages.HOME, false) == 0, "Home's header starts right under the status bar");
        require(HubPages.pageTopDp(HubPages.HOME, true) == HubPages.PAGE_TOP_DP, "In tabletop posture Home's lower pane keeps its room");
        for (String page : HubPages.ALL) if (!page.equals(HubPages.HOME)) {
            require(HubPages.pageTopDp(page, false) == HubPages.PAGE_TOP_DP && HubPages.pageTopDp(page, true) == HubPages.PAGE_TOP_DP, page + " keeps its top room");
        }
    }

    /**
     * The page scroll bar's thumb over the dock (HubDock.AboveDock, council P3 on #508): mapped onto the track above the
     * dock, it keeps its proportions, ends at the dock's top at the page's end, and stays put with no dock over its column.
     */
    private static void barAboveDock() {
        int length = 2000, cover = 400, start = 5000;
        // Nothing over the bar's column, or a cover the whole height: as the platform placed it.
        require(HubPages.barAboveDock(start + 1234, start, length, 0) == start + 1234, "No dock: the bar where it is");
        require(HubPages.barAboveDock(start + 1234, start, length, length) == start + 1234, "A cover the scroller's height: unchanged");
        require(HubPages.barAboveDock(start + 1234, start, length, -10) == start + 1234, "A negative cover counts as none");
        // The track's ends: its top stays, its bottom (the scroller's) lands on the dock's top.
        require(HubPages.barAboveDock(start, start, length, cover) == start, "The track's top stays");
        require(HubPages.barAboveDock(start + length, start, length, cover) == start + length - cover, "The track's end is the dock's top");
        // A thumb keeps its proportions: half way down the whole height is half way down the shortened track.
        int top = HubPages.barAboveDock(start + 900, start, length, cover), bottom = HubPages.barAboveDock(start + 1100, start, length, cover);
        require(top == start + 720 && bottom == start + 880, "The thumb scaled by the share above the dock: " + top + ".." + bottom);
        // At the page's end the thumb's bottom is the track's end: never under the dock.
        require(HubPages.barAboveDock(start + length, start, length, cover) <= start + length - cover, "At the end the thumb ends at the dock's top");
        // Monotonic: a lower edge never maps above a higher one.
        int last = Integer.MIN_VALUE;
        for (int edge = start; edge <= start + length; edge += 37) {
            int mapped = HubPages.barAboveDock(edge, start, length, cover);
            require(mapped >= last && mapped >= start && mapped <= start + length - cover, "Monotonic and inside the track: " + edge);
            last = mapped;
        }
    }

    private static void tree() {
        require(HubPages.ALL.get(0).equals(HubPages.HOME), "Home is the first page");
        require(new HashSet<>(HubPages.ALL).size() == HubPages.ALL.size(), "Page ids are unique");
        require(HubPages.parent(HubPages.HOME) == null && HubPages.depth(HubPages.HOME) == 0, "Home is the root");
        Pattern id = Pattern.compile("[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*)*");
        for (String page : HubPages.ALL) {
            require(id.matcher(page).matches(), "Lower-case id: " + page);
            require(HubPages.known(page) && HubPages.canonical(page).equals(page),
                    "A page is its own canonical id: " + page);
            // Acyclic and rooted: walking parents reaches Home within the page count.
            String at = page; int steps = 0;
            while (at != null && !at.equals(HubPages.HOME) && steps <= HubPages.ALL.size()) { at = HubPages.parent(at); steps++; }
            require(HubPages.HOME.equals(at), "Reaches Home: " + page);
            List<String> path = HubPages.path(page);
            require(path.get(0).equals(HubPages.HOME) && path.get(path.size() - 1).equals(page), "Path runs Home to the page: " + page);
            require(HubPages.depth(page) == path.size() - 1 && HubPages.depth(page) <= 2, "Depth matches the path: " + page);
            for (int i = 1; i < path.size(); i++) require(HubPages.parent(path.get(i)).equals(path.get(i - 1)), "Path follows parents: " + page);
            if (!page.equals(HubPages.HOME)) require(HubPages.known(HubPages.parent(page)), "Parent is a page: " + page);
            // Dotted ids sit under the page their prefix names.
            int dot = page.indexOf('.');
            if (dot > 0) require(HubPages.parent(page).equals(page.substring(0, dot)), "Sub-page under its prefix: " + page);
            require(HubPages.ALL.contains(HubPages.subjectOf(page)), "Subject is a page: " + page);
        }
        require(HubPages.subjectOf(HubPages.BATTERY_MODES).equals(HubPages.BATTERY), "A sub-page's subject");
        require(HubPages.subjectOf(HubPages.HOME).equals(HubPages.HOME), "Home's subject is Home");
        require(!HubPages.known(null) && !HubPages.known("") && !HubPages.known("Stats"), "Unknown ids");
        require(HubPages.path("nowhere").equals(java.util.Collections.singletonList(HubPages.HOME)) && HubPages.depth(null) == 0,
                "An unknown id's path is Home");
        require(HubPages.parent("nowhere") == null, "An unknown id has no parent");
    }

    private static void legacy() {
        // Ids saved before the Hub: Bundle "page", last_page and Home's navigate(page) calls.
        require(HubPages.canonical("home").equals(HubPages.HOME), "home stays Home");
        require(HubPages.canonical("stats").equals(HubPages.DATA), "Live Stats is Data");
        require(HubPages.canonical("rides").equals(HubPages.RIDE), "Rides is Ride");
        require(HubPages.canonical("more").equals(HubPages.SETTINGS), "More is Settings");
        // The Scooter tab's old id is the Controls page's (PR 3): an old Bundle or last_page opens Controls on that tab.
        require(HubPages.canonical("controls").equals(HubPages.CONTROLS), "controls is the Controls page");
        require(HubPages.canonical(null).equals(HubPages.HOME) && HubPages.canonical("bogus").equals(HubPages.HOME)
                && HubPages.canonical("").equals(HubPages.HOME), "Anything else opens Home");
        // The Scooter tab's index (parts 3-10) is gone (PR 11): its id opens Home, whose subjects it listed.
        require(HubPages.canonical("scooter").equals(HubPages.HOME) && !HubPages.known(HubPages.SCOOTER), "scooter opens Home");
        for (String legacy : new String[] {"home", "stats", "controls", "rides", "more", "scooter"})
            require(HubPages.depth(HubPages.canonical(legacy)) <= 1, "Each old tab opens Home or a page directly under it: " + legacy);
    }

    private static void subjects() {
        require(HubPages.SUBJECTS.size() == 7, "Seven subjects");
        Set<String> icons = new HashSet<>();
        for (String subject : HubPages.SUBJECTS) {
            require(HubPages.parent(subject).equals(HubPages.HOME), "A subject sits under Home: " + subject);
            String icon = HubPages.icon(subject);
            require(icon != null && !icon.isEmpty() && !icon.equals("home"), "Subject icon: " + subject);
            icons.add(icon);
        }
        require(icons.size() == HubPages.SUBJECTS.size(), "Subject icons differ");
        require(HubPages.icon(HubPages.SETTINGS).equals("gear") && HubPages.icon(HubPages.SEARCH).equals("search"), "Header icons");
        require(HubPages.icon(HubPages.SERVICE_MAINTENANCE).equals(HubPages.icon(HubPages.SERVICE)), "A sub-page has its subject's icon");
        // The Layout Plan's glyphs: Battery & charging a bolt, Controls rising bars, Lights & sound a bulb, Service a wrench (part 8b).
        require(HubPages.icon(HubPages.BATTERY).equals("bolt") && HubPages.icon(HubPages.CONTROLS).equals("bars")
                && HubPages.icon(HubPages.LIGHTS).equals("bulb") && HubPages.icon(HubPages.SERVICE).equals("wrench"), "The plan's subject icons");
    }

    private static void hub() {
        // The Hub has no tabs (PR 11): Home is the root of every stack, and every other page is under a page under Home.
        for (String page : HubPages.ALL)
            require(page.equals(HubPages.HOME) || HubPages.parent(HubPages.subjectOf(page)).equals(HubPages.HOME), "Under Home: " + page);
        for (String page : new String[] {HubPages.SETTINGS, HubPages.SEARCH})
            require(HubPages.parent(page).equals(HubPages.HOME) && !HubPages.SUBJECTS.contains(page), "Header page, not a subject: " + page);
    }

    /** The Widgets dock (owner's answer 8 on #383): never cramped, every subject one tap away through "All". */
    private static void dock() {
        require(new HashSet<>(HubPages.DOCK_ORDER).equals(new HashSet<>(HubPages.SUBJECTS)) && HubPages.DOCK_ORDER.size() == 7,
                "The dock's order lists each subject once");
        // A 344 dp phone less the dock's 14 dp margins and 4 dp padding (308 dp): four subjects and All, each at least
        // 60 dp (well over 48).
        require(HubPages.dockRoom(344) == 308, "A 344 dp phone's dock room: " + HubPages.dockRoom(344));
        List<String> phone = HubPages.dockSubjects(HubPages.dockRoom(344), 1f);
        require(phone.equals(java.util.Arrays.asList(HubPages.RIDE, HubPages.BATTERY, HubPages.CONTROLS, HubPages.DATA)), "344 dp: " + phone);
        require(HubPages.dockRoom(344) / (phone.size() + 1) >= 48, "Each item gets 48 dp at 344 dp");
        for (float scale : new float[] {1f, 1.15f, 1.3f, 1.6f, 2f})
            for (int width = 280; width <= 900; width += 4) {
                List<String> shown = HubPages.dockSubjects(width, scale);
                require(!shown.isEmpty(), "At least one subject: " + width + "/" + scale);
                require(width / (shown.size() + 1f) >= HubPages.DOCK_ITEM_DP * scale || shown.size() == 1,
                        "Comfortable items: " + width + "/" + scale);
                int last = -1;
                for (String subject : shown) {
                    int at = HubPages.SUBJECTS.indexOf(subject);
                    require(at > last, "Home's order: " + shown);
                    last = at;
                }
                require(HubPages.dockSubjects(width + 4, scale).containsAll(shown), "More room never hides a subject: " + width + "/" + scale);
            }
        // At 1.3x text a 344 dp phone shows Ride and Battery & charging beside All, each item about 100 dp.
        require(HubPages.dockSubjects(HubPages.dockRoom(344), 1.3f).equals(java.util.Arrays.asList(HubPages.RIDE, HubPages.BATTERY)),
                "Large text: fewer, still comfortable: " + HubPages.dockSubjects(HubPages.dockRoom(344), 1.3f));
        require(HubPages.dockSubjects(HubPages.dockRoom(600), 1f).size() == 7, "A wide phone shows all seven");
        // The dock HubValidation expects in each window of the sweep's hub steps (344x882 and 725x604 dp, at 1x and 1.4x
        // text): the 725 dp window gets the wide layout (all seven) at 1x, and the phone layout at 1.4x (725 / 1.4 < 650),
        // whose dock still holds all seven in 689 dp of room; a 344 dp phone never gets the wide layout.
        require(!WideLayout.wide(344, 1f) && !WideLayout.wide(344, 1.4f), "344 dp is a phone at any text size");
        require(WideLayout.wide(725, 1f) && !WideLayout.wide(725, 1.4f), "725 dp: wide at 1x, the phone layout at 1.4x");
        require(HubPages.dockRoom(725) == 689 && HubPages.dockSubjects(HubPages.dockRoom(725), 1.4f).equals(HubPages.SUBJECTS),
                "725 dp at 1.4x: the phone dock holds all seven: " + HubPages.dockSubjects(HubPages.dockRoom(725), 1.4f));
        require(HubPages.dockSubjects(HubPages.dockRoom(344), 1.4f).equals(java.util.Arrays.asList(HubPages.RIDE, HubPages.BATTERY)),
                "344 dp at 1.4x: " + HubPages.dockSubjects(HubPages.dockRoom(344), 1.4f));
        // A wide dock (#392 early review): full names only when every one fits on one line, else every short name.
        // 725 dp less 24 dp each side gives 677 dp, eight items of about 83.6 dp, captions of about 79.6 dp.
        float[] fits = {40, 70, 60, 79};
        require(HubPages.wideDockNames(fits, 677, 8, 96, 4), "Every name fits: full names");
        float[] oneWraps = {40, 90, 60, 50};
        require(!HubPages.wideDockNames(oneWraps, 677, 8, 96, 4), "One name would wrap (Battery & charging at 725 dp): short names");
        // With room to spare an item is capped at 96 dp: 92 dp captions.
        require(HubPages.wideDockNames(new float[] {92}, 2000, 8, 96, 4), "The cap's caption: 92 dp fits");
        require(!HubPages.wideDockNames(new float[] {92.5f}, 2000, 8, 96, 4), "Past the cap's caption: short names");
        require(!HubPages.wideDockNames(new float[] {10}, 677, 0, 96, 4), "No items: short names");
        require(!HubPages.wideDockNames(new float[] {Float.NaN}, 677, 8, 96, 4), "An unmeasured name: short names");
        // The dock's upward swipe: past twice the slop upward and more up than across; a quick flick counts on its first move.
        require(HubPages.dockSwipe(17, 0, 8), "17 dp up past a 8 dp slop: a swipe");
        require(!HubPages.dockSwipe(16, 0, 8), "Not past twice the slop: no swipe");
        require(!HubPages.dockSwipe(30, 31, 8), "More across than up: no swipe");
        // The dock is pinned to the bottom (the contributor's decision, 14 Nov, #383): it neither lifts above Widgets' Lock bar
        // nor steps aside for it. Its sheet runs down to the screen's bottom edge, under the navigation bar or gesture handle
        // (15 Nov, #383, as the Layout Plan's dock sheet), with its items lift above that bar, where the old floating bar's
        // items sat. Its place depends on the window, its own height and the navigation inset only: wherever the Lock bar is
        // (the old cases, in dp at 3 px per dp: 344x882 at 1x and 1.4x, 412x915, 725x604 at 1x and 1.4x, scrolled or not),
        // the dock's top is the same. In px. Each frame: window width, the page frame's height, the dock's own height
        // without the lift or the inset, and the navigation inset (gesture handle 24 dp, three buttons 48 dp, none).
        int px = 3, lift = HubPages.DOCK_LIFT_DP * px, padding = HubPages.DOCK_PADDING_DP * px, gap = 16 * px;
        require(HubPages.DOCK_LIFT_DP == 10, "The items keep the old floating bar's 10 dp above the bottom");
        int[][] frames = {{344, 847, 70, 24}, {344, 847, 98, 24}, {412, 880, 70, 48}, {412, 880, 98, 0}, {725, 569, 73, 24}, {725, 530, 70, 0}};
        int[][] bars = {{791, 843}, {820, 872}, {830, 882}, {860, 912}, {561, 615}, {431, 489}, {372, 422}, {-200, -10}, {150, 210}};
        for (int[] frame : frames) {
            String name = java.util.Arrays.toString(frame);
            int inset = frame[3] * px, frameBottom = (frame[1] + 10) * px, window = frameBottom + inset;
            int bottomPadding = HubPages.dockBottomPadding(padding, lift, inset);
            int height = frame[2] * px + lift + inset;
            int top = HubPages.dockTop(window, height);
            // The surface reaches the screen's bottom edge: nothing shows beneath it.
            require(top + height == window, "The dock's sheet runs to the screen's bottom edge: " + name);
            require(bottomPadding == padding + lift + inset, "The sheet's bottom padding: its padding, the lift and the inset: " + name);
            // Its items stay above the navigation bar, lift above it, exactly where the floating bar's items were (its bottom
            // margin above the frame's bottom, less its padding).
            int itemsBottom = top + height - bottomPadding;
            require(itemsBottom == frameBottom - lift - padding, "The dock's items keep their place above the navigation bar: " + name);
            require(itemsBottom <= window - inset, "The dock's items are never under the navigation bar: " + name);
            // Its top is where the floating bar's top was: the page shows no less above it.
            require(top == frameBottom - lift - frame[2] * px, "The dock's top did not move: " + name);
            for (int[] bar : bars) require(HubPages.dockTop(window, height) == top, "The Lock bar at " + bar[0] + "-" + bar[1] + " dp does not move the dock");
            // Scrolled to its end with the clearance as its bottom padding, the page's last widget (the Lock bar when the
            // rider puts it last) ends gap above the dock: every widget can be scrolled fully clear of it. The scroller
            // ends at the navigation bar (frameBottom).
            int clearance = HubPages.dockClearance(height, inset, gap);
            require(HubPages.scrolledEndBottom(frameBottom, clearance) == top - gap, "Scrolled to its end, the last widget clears the dock by the gap: " + name);
            require(HubPages.scrolledEndBottom(frameBottom, clearance - 1) > top - gap, "One pixel less and it would not");
            // The same dock with no navigation inset clears by the same gap: the inset the sheet covers is not page room.
            require(HubPages.dockClearance(height - inset, 0, gap) == clearance, "The inset does not change the room the page needs: " + name);
        }
        require(HubPages.dockClearance(-5, 72, 48) == 48, "An unmeasured dock: the gap");
        require(HubPages.dockClearance(30, 72, 48) == 48, "A dock not yet as tall as its inset: the gap");
        require(HubPages.dockClearance(100, -4, 48) == 148, "A negative inset counts as none");
        require(HubPages.dockBottomPadding(12, 30, -9) == 42, "A negative inset adds nothing to the padding");
        // A taller dock (larger text, or captions on two lines) needs more room, never less.
        require(HubPages.dockClearance(98 * px + 72, 72, gap) > HubPages.dockClearance(70 * px + 72, 72, gap), "A taller dock: more room");
        require(!HubPages.dockSwipe(-30, 0, 8), "Downward: no swipe");
    }

    /**
     * The dock as the closed state of main's morph into its All sheet (SheetMorph, DragSheet reading the live dock): the
     * surface DragSheet reads from this dock's background (its GradientDrawable's bounds inside HubPages.dockSheetInsets,
     * HubPages.dockSheetRadii) at 344, 412 and 725 dp, with the gesture handle (24 dp) and three-button navigation (48 dp).
     * At rest that box is the dock exactly on screen (its bottom past the screen's edge, its bottom corners square, its
     * outline along the top only on a phone, along the top and sides when wide); the morph starts on it and a close ends
     * on it with no jump; on the way the surface never leaves a gap under it and its bottom corners stay square.
     * In px at 3 px per dp; each case: window width and height, wide or not, the dock's body above its lift (dp, measured
     * at 1x and 1.4x on the emulator), the navigation inset.
     */
    private static void dockMorph() {
        int px = 3, line = px, radius = 26 * px, sheetRadius = 24 * px, cap = 18 * px, extension = cap + 6 * px;
        int[][] cases = {{344, 882, 0, 88, 24}, {344, 882, 0, 94, 24}, {344, 882, 0, 88, 48}, {412, 915, 0, 88, 24}, {412, 915, 0, 88, 48},
                {725, 604, 1, 88, 24}, {725, 604, 1, 88, 48}, {725, 604, 0, 94, 24}};
        for (int[] c : cases) {
            boolean wide = c[2] == 1;
            String name = c[0] + "x" + c[1] + (wide ? " wide" : " phone") + ", body " + c[3] + " dp, inset " + c[4] + " dp";
            int w = c[0] * px, h = c[1] * px, inset = c[4] * px;
            int height = c[3] * px + HubPages.DOCK_LIFT_DP * px + inset;
            // The dock's view: edge to edge on a phone, centred with 24 dp sides when wide (677 dp at 725), reaching the bottom.
            int left = wide ? 24 * px : 0, right = w - left, top = HubPages.dockTop(h, height), bottom = top + height;
            require(bottom == h, "The dock reaches the screen's bottom edge: " + name);
            int[] in = HubPages.dockSheetInsets(wide, line);
            float[] eight = HubPages.dockSheetRadii(radius);
            double[] radii = {eight[0], eight[2], eight[4], eight[6]};
            require(eight[0] == eight[1] && eight[2] == eight[3] && eight[4] == 0 && eight[5] == 0 && eight[6] == 0 && eight[7] == 0,
                    "Round top corners, square bottom ones: " + name);
            // InsetDrawable gives its drawable the view's box less the insets (negative: outward). DragSheet reads these bounds.
            SheetMorph.Box dock = new SheetMorph.Box(left + in[0], top + in[1], right - in[2], bottom - in[3], radii, 0xff1f201e, 0xff35362f, line, 8 * px);
            // On screen it is the dock exactly: clipped to the screen, the view's own box.
            require(Math.max(0, dock.left) == left && Math.min(w, dock.right) == right && dock.top == top && Math.min(h, dock.bottom) == bottom,
                    "The morph's closed box, on screen, is the dock's view: " + name);
            require(dock.bottom - line >= h, "Its outline's bottom edge lies past the screen's edge: " + name);
            if (wide) require(dock.left == left && dock.right == right, "Wide: its sides keep their outline, inside the window: " + name);
            else require(dock.left + line <= 0 && dock.right - line >= w, "Phone: its side outlines lie past the screen's sides: " + name);
            require(dock.radii[0] == radius && dock.radii[1] == radius && dock.radii[2] == 0 && dock.radii[3] == 0, "Top corners 26 dp, bottom square: " + name);
            // The open sheet as DragSheet builds it: full width, 24 dp top corners, reaching extension below the screen.
            SheetMorph.Box open = new SheetMorph.Box(0, Math.max(c[1] * px / 3, h - 600 * px), w, h + extension, new double[] {sheetRadius, sheetRadius, 0, 0}, 0xff111211, 0xff2a2b28, line, 0);
            double travel = dock.top - open.top;
            require(travel > 0, "The sheet opens above the dock: " + name);
            same(SheetMorph.at(dock, open, travel, travel), dock, "The morph starts on the dock exactly: " + name);
            same(SheetMorph.at(dock, open, 0, travel), open, "and ends on the open sheet as main draws it: " + name);
            SheetMorph.Box last = SheetMorph.at(dock, open, travel, travel);
            for (double offset = travel - .25; offset >= -cap; offset -= .25) {
                SheetMorph.Box now = SheetMorph.at(dock, open, offset, travel);
                require(now.bottom >= h, "No gap under the surface at offset " + offset + ": " + name);
                require(now.radii[2] == 0 && now.radii[3] == 0, "Its bottom corners stay square at offset " + offset + ": " + name);
                require(now.left <= Math.max(0, dock.left) + 1e-9 && now.right >= Math.min(w, dock.right) - 1e-9 && now.top <= dock.top,
                        "The surface covers the dock's place at offset " + offset + ": " + name);
                if (offset >= 0) require(Math.abs(now.left - last.left) < 3 && Math.abs(now.right - last.right) < 3 && Math.abs(now.bottom - last.bottom) < 3
                        && Math.abs(now.radii[0] - last.radii[0]) < 3, "No jump at offset " + offset + ": " + name);
                last = now;
            }
            // A close or snap-back ends on the dock with no jump: the last quarter pixel before rest is within a pixel of it.
            SheetMorph.Box end = SheetMorph.at(dock, open, travel - .25, travel);
            require(Math.abs(end.left - dock.left) < 1 && Math.abs(end.right - dock.right) < 1 && Math.abs(end.top - dock.top) < 1
                    && Math.abs(end.bottom - dock.bottom) < 1 && Math.abs(end.radii[0] - dock.radii[0]) < 1, "The close's last step lands on the dock: " + name);
        }
    }

    /**
     * The keyboard (HubPages.dockInset): the dock runs on under the navigation bar only. With the keyboard up, MainActivity's
     * root ends at the keyboard's top (its padding the larger inset) and the dock sits there with its items lift and
     * padding above it, exactly where main's dock (in the page frame) leaves its items.
     */
    private static void dockKeyboard() {
        int px = 3;
        require(HubPages.dockInset(24 * px, 0) == 24 * px, "No keyboard: the gesture handle's inset");
        require(HubPages.dockInset(48 * px, 0) == 48 * px, "No keyboard: three buttons' inset");
        require(HubPages.dockInset(24 * px, 300 * px) == 0, "Keyboard up: none (the keyboard covers the bar)");
        require(HubPages.dockInset(48 * px, 280 * px) == 0, "Keyboard up over three buttons: none");
        require(HubPages.dockInset(0, 0) == 0 && HubPages.dockInset(-5, 0) == 0 && HubPages.dockInset(24, -3) == 24, "Missing or negative insets");
        require(HubPages.dockInset(24 * px, 10 * px) == 14 * px, "A keyboard shorter than the bar: the part it leaves");
        int padding = HubPages.DOCK_PADDING_DP * px, lift = HubPages.DOCK_LIFT_DP * px, body = 88 * px;
        int[][] insets = {{24, 0}, {48, 0}, {24, 300}, {48, 280}, {24, 10}, {0, 0}, {0, 250}};
        for (int h : new int[] {882 * px, 604 * px}) for (int[] i : insets) {
            int nav = i[0] * px, ime = i[1] * px, rootBottom = h - Math.max(nav, ime);
            int inset = HubPages.dockInset(nav, ime);
            int height = body + lift + inset, top = HubPages.dockTop(rootBottom + inset, height);
            int dockBottom = top + height, items = dockBottom - HubPages.dockBottomPadding(padding, lift, inset);
            // main: the dock floated in the page frame (ending at rootBottom), lift above its bottom, its items padding inside.
            int mainItems = rootBottom - lift - padding;
            String name = "nav " + i[0] + " dp, keyboard " + i[1] + " dp, window " + h / px + " dp";
            require(items == mainItems, "The items sit where main's do: " + name);
            require(dockBottom == (ime > 0 ? h - ime : h), "The sheet reaches the keyboard's top, or the screen's bottom without one: " + name);
            require(dockBottom <= h - Math.max(0, ime), "Never under the keyboard: " + name);
        }
    }

    private static void same(SheetMorph.Box a, SheetMorph.Box b, String message) {
        boolean ok = a.left == b.left && a.top == b.top && a.right == b.right && a.bottom == b.bottom && a.fill == b.fill && a.stroke == b.stroke
                && a.strokeWidth == b.strokeWidth && a.elevation == b.elevation;
        for (int i = 0; i < 4; i++) ok &= a.radii[i] == b.radii[i];
        require(ok, message + " (" + a + " vs " + b + ")");
    }

    /**
     * The connection line in page headers (the contributor's decision, 14 Nov, #383): every page kind by every connection
     * state. Not connected covers disconnected, connecting, searching, Bluetooth off and a held release: only the plain
     * connected state (ready) counts as connected.
     */
    private static void connectionLine() {
        String[] subjects = {HubPages.RIDE, HubPages.BATTERY, HubPages.CONTROLS, HubPages.LIGHTS, HubPages.SECURITY, HubPages.SERVICE, HubPages.DATA, HubPages.SEARCH};
        String[] subPages = {HubPages.BATTERY_MODES, HubPages.BATTERY_HISTORY, HubPages.RIDE_RIDES, HubPages.RIDE_RECORDING, HubPages.SERVICE_COMPATIBILITY, HubPages.DATA_STORAGE};
        String[] appPages = {HubPages.SETTINGS, HubPages.SETTINGS_HOME, HubPages.SETTINGS_UNITS, HubPages.SETTINGS_SCOOTERS, HubPages.SETTINGS_DEVELOPER, HubPages.SETTINGS_ABOUT};
        String[] states = {"connected", "disconnected", "connecting", "searching", "Bluetooth off", "release held"};
        for (String state : states) {
            boolean connected = state.equals("connected");
            require(HubPages.headerConnectionLine(HubPages.HOME, connected) == HubPages.LINE_SHOWN, "Home keeps its own line: " + state);
            for (String[] kind : new String[][] {subjects, subPages})
                for (String page : kind) {
                    int want = connected ? HubPages.LINE_GONE : HubPages.LINE_SHOWN;
                    require(HubPages.headerConnectionLine(page, connected) == want, page + " " + state + ": " + want);
                }
            for (String page : appPages)
                require(HubPages.headerConnectionLine(page, connected) == HubPages.LINE_GONE, page + " never shows the line: " + state);
        }
        for (String page : HubPages.ALL) {
            require(HubPages.appPage(page) == HubPages.path(page).contains(HubPages.SETTINGS), "App pages are Settings and under it: " + page);
            if (HubPages.HOME.equals(page)) continue;
            // A connected page opened fresh has no gap where the line was; one not connected shows it.
            if (!HubPages.appPage(page)) {
                require(HubPages.headerConnectionLine(page, true) == HubPages.LINE_GONE, "Connected: no line and no gap: " + page);
                require(HubPages.headerConnectionLine(page, false) == HubPages.LINE_SHOWN, "Not connected: the line: " + page);
            }
        }
        // The old tab ids resolve as their pages do: More is Settings (never), Live Stats is Data (only when not connected).
        require(HubPages.headerConnectionLine("more", false) == HubPages.LINE_GONE, "More (Settings): never");
        require(HubPages.headerConnectionLine("stats", false) == HubPages.LINE_SHOWN, "Live Stats (Data): not connected");
        // While a page is open: shown (not connected), then connected takes the line and its room away (the page glides up
        // to the title; the owner's motion sweep pick, 15 Nov, in place of the blank room #383 kept), dropped shows it again.
        String page = HubPages.BATTERY;
        int[] seen = new int[4];
        boolean[] walk = {false, true, false, true};
        for (int i = 0; i < walk.length; i++) seen[i] = HubPages.headerConnectionLine(page, walk[i]);
        require(seen[0] == HubPages.LINE_SHOWN && seen[1] == HubPages.LINE_GONE && seen[2] == HubPages.LINE_SHOWN && seen[3] == HubPages.LINE_GONE,
                "Drop, connect, drop, connect on an open page: " + java.util.Arrays.toString(seen));
        serviceStates(subjects, subPages, appPages);
    }

    /**
     * pageConnectionLine: the same rule with the service's state (#410 review). Every page kind by service bound or not,
     * Nearby devices granted or not (a bind can come only with it), connected or not, and the line shown here already or not.
     */
    private static void serviceStates(String[] subjects, String[] subPages, String[] appPages) {
        for (boolean bound : new boolean[] {false, true})
            for (boolean permission : new boolean[] {false, true})
                for (boolean connected : new boolean[] {false, true})
                    for (boolean held : new boolean[] {false, true}) {
                        String state = (bound ? "bound" : "unbound") + (permission ? ", Nearby granted" : ", no Nearby permission")
                                + (connected ? ", connected" : ", not connected") + (held ? ", shown here" : "");
                        require(HubPages.pageConnectionLine(HubPages.HOME, bound, permission, connected, held) == HubPages.LINE_SHOWN, "Home keeps its own line: " + state);
                        for (String page : appPages)
                            require(HubPages.pageConnectionLine(page, bound, permission, connected, held) == HubPages.LINE_GONE, page + " never shows the line: " + state);
                        for (String[] kind : new String[][] {subjects, subPages})
                            for (String page : kind) {
                                int want;
                                // Bound: the page rule as it is (connected only counts here).
                                if (bound) want = connected ? HubPages.LINE_GONE : HubPages.LINE_SHOWN;
                                // Waiting for a bind that will come (a page rebuilt by a recreation): no line and no gap,
                                // unless the line already showed on this page, which stays shown (still not connected).
                                else if (permission) want = held ? HubPages.LINE_SHOWN : HubPages.LINE_GONE;
                                // No Nearby permission: no bind ever comes, the scooter is not connected, the line shows.
                                else want = HubPages.LINE_SHOWN;
                                require(HubPages.pageConnectionLine(page, bound, permission, connected, held) == want, page + " " + state + ": " + want);
                            }
                    }
        // The review's case: no permission, every scooter page and sub-page, opened fresh, shows the line (its tap leads to
        // the connection details and the button that grants the permission).
        for (String page : HubPages.ALL)
            if (!HubPages.HOME.equals(page) && !HubPages.appPage(page))
                require(HubPages.pageConnectionLine(page, false, false, false, false) == HubPages.LINE_SHOWN, "No Nearby permission: the line on " + page);
        // The rotation case: a connected page rebuilt before the bind has no line and no gap, and after the bind still none.
        require(HubPages.pageConnectionLine(HubPages.SERVICE_MAINTENANCE, false, true, false, false) == HubPages.LINE_GONE
                && HubPages.pageConnectionLine(HubPages.SERVICE_MAINTENANCE, true, true, true, false) == HubPages.LINE_GONE,
                "A connected page rebuilt before the service binds keeps no blank line");
    }

    private static void riding() {
        for (String page : new String[] {HubPages.HOME, HubPages.RIDE, HubPages.RIDE_RIDES, HubPages.DATA,
                HubPages.CONTROLS, HubPages.BATTERY, HubPages.LIGHTS})
            require(HubPages.clearForRiding(page), "Riding mode may open over " + page);
        for (String page : new String[] {HubPages.SETTINGS, HubPages.SETTINGS_SCOOTERS, HubPages.SECURITY, HubPages.SERVICE_EXPERIMENTAL,
                HubPages.SERVICE_COMPATIBILITY, HubPages.RIDE_RIDING, HubPages.RIDE_RECORDING, HubPages.DATA_STORAGE, HubPages.SEARCH})
            require(!HubPages.clearForRiding(page), "Riding mode waits on " + page);
        require(!HubPages.clearForRiding(null) && !HubPages.clearForRiding("more"), "Unknown ids are busy");
        // The owner's answer 21 (#383): of the scooter subjects only Controls, Battery & charging and Lights & sound let it
        // open; every other subject and every sub-page blocks it. Checked over every page so a new page can't slip in.
        Set<String> clear = new HashSet<>(java.util.Arrays.asList(HubPages.HOME, HubPages.RIDE, HubPages.RIDE_RIDES,
                HubPages.DATA, HubPages.CONTROLS, HubPages.BATTERY, HubPages.LIGHTS));
        for (String page : HubPages.ALL)
            require(HubPages.clearForRiding(page) == clear.contains(page), "Only the agreed pages let riding mode open: " + page);
        for (String page : HubPages.ALL)
            if (HubPages.depth(page) > HubPages.depth(HubPages.subjectOf(page)))
                require(!HubPages.clearForRiding(page) || page.equals(HubPages.RIDE_RIDES), "A sub-page blocks it: " + page);
        // The old tab roots keep their rule: only More (Settings) blocks by page; the Scooter tab's id is not a page now.
        for (String tab : new String[] {"home", "stats", "rides", "more"})
            require(HubPages.clearForRiding(HubPages.canonical(tab)) == !tab.equals("more"), "Tab rule: " + tab);
        require(!HubPages.clearForRiding(HubPages.SCOOTER), "scooter is not a page");
    }

    /**
     * Which pages MainActivity builds while ScooterService is not bound (no Nearby devices permission): Settings and its app
     * pages since the contributor's decision (15 Nov, #383), Home, Search and the scooter pages that read the service
     * null-safely; My scooters, the pages hosting More's scooter sections, Ride and Data keep the permission card. Checked
     * over every page, so a new page has to be placed on purpose.
     */
    private static void withoutService() {
        Set<String> built = new HashSet<>(java.util.Arrays.asList(HubPages.HOME, HubPages.SEARCH,
                HubPages.BATTERY, HubPages.BATTERY_MODES, HubPages.BATTERY_ESTIMATES,
                HubPages.CONTROLS, HubPages.CONTROLS_ASSISTS, HubPages.CONTROLS_LIMITS,
                HubPages.LIGHTS, HubPages.LIGHTS_LINE, HubPages.LIGHTS_STAR, HubPages.SERVICE, HubPages.SERVICE_MAINTENANCE,
                HubPages.SETTINGS, HubPages.SETTINGS_HOME, HubPages.SETTINGS_UNITS, HubPages.SETTINGS_ABOUT, HubPages.SETTINGS_DEVELOPER));
        for (String page : HubPages.ALL)
            require(HubPages.withoutService(page) == built.contains(page), "Built without the service or not, as decided: " + page);
        // Every app page but My scooters: the app's own choices need no scooter.
        for (String page : HubPages.ALL)
            if (HubPages.appPage(page)) require(HubPages.withoutService(page) == !page.equals(HubPages.SETTINGS_SCOOTERS), "Settings' app pages open without it: " + page);
        // The scooter pages with More's sections need the service (they were MainActivity's "moreHosted" pages).
        for (String page : HubPages.ALL) {
            String subject = HubPages.subjectOf(page);
            if (!subject.equals(HubPages.BATTERY) && !subject.equals(HubPages.CONTROLS) && !subject.equals(HubPages.LIGHTS)
                    && !subject.equals(HubPages.SECURITY) && !subject.equals(HubPages.SERVICE)) continue;
            boolean moreHosted = page.equals(HubPages.SECURITY) || page.equals(HubPages.BATTERY_HISTORY)
                    || page.startsWith(HubPages.SERVICE + ".") && !page.equals(HubPages.SERVICE_MAINTENANCE);
            require(HubPages.withoutService(page) == !moreHosted, "A scooter page needs the service only with More's sections: " + page);
        }
        for (String page : new String[] {HubPages.RIDE, HubPages.RIDE_RIDES, HubPages.RIDE_RECORDING, HubPages.RIDE_RIDING, HubPages.DATA, HubPages.DATA_STORAGE})
            require(!HubPages.withoutService(page), "Ride and Data keep the permission card: " + page);
        require(!HubPages.withoutService(null) && !HubPages.withoutService("more") && !HubPages.withoutService(HubPages.SCOOTER),
                "Unknown and legacy ids are not built without the service (MainActivity builds canonical ids)");
        // The quick-access shortcuts to Settings' app pages open them, not the card.
        for (String id : new String[] {"more:App", "more:About"})
            require(HubPages.withoutService(HubRoutes.pageFor(id)), "The shortcut " + id + " opens its page without the service");
        require(!HubPages.withoutService(HubRoutes.pageFor("more:Scooters")), "My scooters' shortcut shows the card without the service");
    }

    private static void moreSections(String moreScreen) throws Exception {
        String source = new String(Files.readAllBytes(Paths.get(moreScreen)), StandardCharsets.UTF_8);
        Matcher sections = Pattern.compile("String\\[\\]\\s+SECTIONS\\s*=\\s*\\{([^}]*)\\}").matcher(source);
        require(sections.find(), "MoreScreen.SECTIONS found");
        List<String> ids = new ArrayList<>();
        Matcher quoted = Pattern.compile("\"([^\"]+)\"").matcher(sections.group(1));
        while (quoted.find()) ids.add(quoted.group(1));
        require(ids.size() >= 7, "MoreScreen.SECTIONS read");
        Matcher recovery = Pattern.compile("COMPATIBILITY_RECOVERY\\s*=\\s*\"([^\"]+)\"").matcher(source);
        require(recovery.find(), "MoreScreen.COMPATIBILITY_RECOVERY found");
        ids.add("Units"); ids.add("Compatibility"); ids.add(recovery.group(1));
        for (String section : ids) {
            String page = HubPages.fromMoreSection(section);
            require(HubPages.known(page), "Section has a page: " + section);
            require(!page.equals(HubPages.SETTINGS) || section.equals("App"), "Only App is Settings itself: " + section);
        }
        require(HubPages.fromMoreSection("Compatibility").equals(HubPages.SERVICE_COMPATIBILITY)
                && HubPages.fromMoreSection(recovery.group(1)).equals(HubPages.SERVICE_COMPATIBILITY), "Both Compatibility ids");
        require(HubPages.fromMoreSection("Data").equals(HubPages.DATA_STORAGE) && HubPages.fromMoreSection("Lab").equals(HubPages.SERVICE_EXPERIMENTAL)
                && HubPages.fromMoreSection("Security").equals(HubPages.SECURITY), "Sections move to their subjects");
        require(HubPages.fromMoreSection("Charging").equals(HubPages.BATTERY_HISTORY)
                && HubPages.fromMoreSection("Reports").equals(HubPages.SERVICE_REPORTS), "Charging and Reports are subject pages");
        for (String section : new String[] {"Charging", "Reports"})
            require(source.contains("\"" + section + "\""), "MoreScreen builds the " + section + " section");
        require(HubPages.fromMoreSection(null).equals(HubPages.SETTINGS) && HubPages.fromMoreSection("").equals(HubPages.SETTINGS),
                "More's index is Settings");
    }

    /** Every icon key HubPages hands out is one Ui draws: a typo would show as a missing glyph. */
    private static void iconsDrawn(String ui) throws Exception {
        String source = new String(Files.readAllBytes(Paths.get(ui)), StandardCharsets.UTF_8);
        Set<String> pages = new HashSet<>(HubPages.ALL);
        pages.add(HubPages.SCOOTER); pages.add(HubPages.HOME);
        for (String page : pages) {
            String icon = HubPages.icon(page);
            require(source.contains("key.equals(\"" + icon + "\")"), "Ui draws the icon " + icon + " for " + page);
        }
        require(source.contains("key.equals(\"" + HubPages.DOCK_ALL_ICON + "\")"), "Ui draws the dock's All icon");
    }

    private static void require(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
}
