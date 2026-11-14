package com.jjricks.volt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** ControlsPages: every scooter control has exactly one page, in a fixed order (hub layout plan, PR 3). */
public final class ControlsPagesTest {
    private static int checks;

    /** Rows built by hand in MainActivity.ControlsScreen (ScooterMenuLayout ids and the live charging row). */
    private static final List<String> HAND_BUILT = Arrays.asList(ScooterMenuLayout.HEADLIGHT_INFO, ScooterMenuLayout.LIGHT_ZONES,
            ScooterMenuLayout.CLOCK_SYNC, ScooterMenuLayout.SCHEDULE_TEST, ScooterMenuLayout.CHARGER, ScooterMenuLayout.CHARGE_ALERT,
            ScooterMenuLayout.LOW_ALERT, ScooterMenuLayout.SECURITY_LINK, ScooterMenuLayout.SCOOTER_NAME, ControlsPages.CHARGE_LIVE,
            "maintenance_reminders", ControlsPages.CHARGE_DONE, ControlsPages.CHARGE_HISTORY, ControlsPages.USABLE_ENERGY);

    public static void main(String[] args) throws IOException {
        pages();
        features();
        handBuilt();
        links();
        doneBy(args.length > 0 ? args[0] : "src/com/jjricks/volt/MainActivity.java");
        order();
        experimental();
        linkVisibility();
        cards();
        System.out.println("ControlsPages: " + checks + " checks passed");
    }

    private static void pages() {
        for (String page : ControlsPages.pages()) {
            ControlsPages.Spec spec = ControlsPages.spec(page);
            require(HubPages.known(page) && spec.page.equals(page), "A spec names a Hub page: " + page);
            require(new HashSet<>(spec.rows).size() == spec.rows.size(), "No row twice on a page: " + page);
            require(!HubPages.subjectOf(page).equals(HubPages.SETTINGS) && !HubPages.subjectOf(page).equals(HubPages.RIDE)
                    && !HubPages.subjectOf(page).equals(HubPages.DATA), "Scooter rows stay on scooter subjects: " + page);
        }
        ControlsPages.Spec index = ControlsPages.spec(HubPages.SEARCH);
        require(index.search && index.rows.isEmpty() && !index.topSpeed, "The search page is search only");
        for (String page : ControlsPages.pages())
            require(page.equals(HubPages.SEARCH) || !ControlsPages.spec(page).search, "Only the search page searches: " + page);
        require(ControlsPages.spec(HubPages.CONTROLS).topSpeed, "The top speed card leads Controls");
        int topSpeed = 0;
        for (String page : ControlsPages.pages()) if (ControlsPages.spec(page).topSpeed) topSpeed++;
        require(topSpeed == 1, "The top speed card is on one page");
        require(ControlsPages.spec(HubPages.HOME) == null && ControlsPages.spec(null) == null && ControlsPages.spec("bogus") == null,
                "Other pages have no spec");
    }

    private static void features() {
        List<ScooterCommands.Feature> all = new ArrayList<>(ScooterCommands.catalog());
        all.addAll(ScooterCommands.oemOnly());
        for (ScooterCommands.Feature f : all) {
            int places = places(f.key);
            if (ControlsPages.NEVER.contains(f.key)) require(places == 0, "Never a row: " + f.key);
            else require(places == 1, "On exactly one page: " + f.key + " (" + places + ")");
            if (f.experimental) require(ControlsPages.pageOf(f.key) != null, "An experimental control sits on its subject page: " + f.key);
        }
        require(places("lock") == 0 && places("pin") == 0 && places("proximity_key") == 0, "Lock, PIN and proximity stay off these pages");
        // Set-once controls on sub-pages (owner's decision 3).
        for (String key : new String[] {"parking_assist", "hill_start_assist", "hill_descent_control", "start_speed"})
            require(HubPages.CONTROLS_ASSISTS.equals(ControlsPages.pageOf(key)), "Assist: " + key);
        for (String key : new String[] {"speed_limit", "drive_speed_limit", "overspeed_control"})
            require(HubPages.CONTROLS_LIMITS.equals(ControlsPages.pageOf(key)), "Limit: " + key);
        for (String key : new String[] {"low_power", "long_range_mode"})
            require(HubPages.BATTERY_MODES.equals(ControlsPages.pageOf(key)), "Battery mode: " + key);
        // Range estimate sits on Battery & charging, beside Usable ride energy (the Layout Plan, the contributor's pick on #383).
        require(HubPages.BATTERY.equals(ControlsPages.pageOf("range_algorithm")), "Range estimate source is set once, on Battery & charging");
        for (ScooterCommands.Feature f : ScooterCommands.catalog()) {
            if (f.key.startsWith("line_")) require(HubPages.LIGHTS_LINE.equals(ControlsPages.pageOf(f.key)), "Line light: " + f.key);
            if (f.key.startsWith("star_")) require(HubPages.LIGHTS_STAR.equals(ControlsPages.pageOf(f.key)), "Star light: " + f.key);
        }
        for (ScooterCommands.Feature f : ScooterCommands.oemOnly()) require(HubPages.LIGHTS.equals(ControlsPages.pageOf(f.key)), "Another model's light: " + f.key);
    }

    private static void handBuilt() {
        for (String id : HAND_BUILT) {
            int places = places(id);
            if (ControlsPages.RETIRED.contains(id)) require(places == 0, "Retired, on no page: " + id);
            else require(places == 1, "On exactly one page: " + id + " (" + places + ")");
        }
        for (String id : ControlsPages.RETIRED) require(HAND_BUILT.contains(id), "Retired ids are hand-built rows: " + id);
        // Every row id on a page is a control, a hand-built row or a link.
        Set<String> keys = new HashSet<>();
        for (ScooterCommands.Feature f : ScooterCommands.catalog()) keys.add(f.key);
        for (ScooterCommands.Feature f : ScooterCommands.oemOnly()) keys.add(f.key);
        for (String page : ControlsPages.pages())
            for (String id : ControlsPages.spec(page).rows)
                require(keys.contains(id) || HAND_BUILT.contains(id) || ControlsPages.isLink(id), "A known row: " + page + " " + id);
    }

    private static void links() {
        for (String page : ControlsPages.pages())
            for (String id : ControlsPages.spec(page).rows) {
                if (!ControlsPages.isLink(id)) continue;
                String target = ControlsPages.linkTarget(id);
                require(HubPages.known(target) && page.equals(HubPages.parent(target)), "A link opens one of the page's sub-pages: " + page + " " + target);
                require(ControlsPages.spec(target) != null || ControlsPages.HOSTED_ELSEWHERE.contains(target), "A linked page has rows: " + target);
            }
        // Every sub-page with rows is linked from its parent, so it is two taps from the Scooter tab.
        for (String page : ControlsPages.pages()) {
            if (HubPages.depth(page) < 2) continue;
            require(ControlsPages.spec(HubPages.parent(page)).includes(ControlsPages.link(page)), "Linked from its parent: " + page);
        }
        require(ControlsPages.linkTarget("charge_limit") == null && !ControlsPages.isLink(null), "Only links have targets");
        // Charge history & recordings is MoreScreen's page, opened from Battery & charging (hub layout plan, PR 4): by its
        // HISTORY card since the Layout Plan's rows (the contributor's pick on #383).
        for (String page : ControlsPages.HOSTED_ELSEWHERE)
            require(ControlsPages.spec(page) == null && ControlsPages.linksTo(ControlsPages.spec(HubPages.parent(page)), page),
                    "Opened from its parent, built elsewhere: " + page);
        require(HubPages.BATTERY_HISTORY.equals(ControlsPages.opens(ControlsPages.CHARGE_HISTORY))
                && HubPages.BATTERY_ESTIMATES.equals(ControlsPages.opens(ControlsPages.USABLE_ENERGY))
                && HubPages.BATTERY_MODES.equals(ControlsPages.opens(ControlsPages.link(HubPages.BATTERY_MODES)))
                && ControlsPages.opens("charge_limit") == null, "What each row opens");
        require(ControlsPages.linksTo(ControlsPages.spec(HubPages.BATTERY), HubPages.BATTERY_MODES)
                && !ControlsPages.linksTo(ControlsPages.spec(HubPages.CONTROLS), HubPages.BATTERY_HISTORY)
                && !ControlsPages.linksTo(null, HubPages.BATTERY_MODES), "linksTo");
        // The index's search finds the Line lights and Star lights links (they replaced the zones row and carry its words).
        require(ControlsPages.SEARCHED_LINKS.equals(Arrays.asList(HubPages.LIGHTS_LINE, HubPages.LIGHTS_STAR)), "Searched links");
        for (String page : ControlsPages.SEARCHED_LINKS) {
            ControlsPages.Spec spec = ControlsPages.spec(page);
            require(spec != null && !spec.rows.isEmpty() && ControlsPages.spec(HubPages.parent(page)).includes(ControlsPages.link(page)),
                    "A searched link opens a page with rows, linked from its parent: " + page);
            // Its search section is its controls' family: the page's first row is a light family control, never a link.
            require(!ControlsPages.isLink(spec.rows.get(0)) && spec.rows.get(0).startsWith(page.equals(HubPages.LIGHTS_LINE) ? "line_" : "star_"),
                    "A searched link's first row names its family: " + page);
        }
    }

    private static void doneBy(String mainActivity) throws IOException {
        // One Charge done by row (the Layout Plan), straight after the charging schedule's rows on Battery & charging.
        require(HubPages.BATTERY.equals(ControlsPages.pageOf("timed_charging")) && HubPages.BATTERY.equals(ControlsPages.pageOf("charge_start"))
                && HubPages.BATTERY.equals(ControlsPages.pageOf(ControlsPages.CHARGE_DONE)), "Charge done by sits with the schedule on Battery & charging");
        List<String> battery = ControlsPages.spec(HubPages.BATTERY).rows;
        require(battery.indexOf(ControlsPages.CHARGE_DONE) == battery.indexOf("charge_start") + 1
                && battery.indexOf("charge_start") == battery.indexOf("timed_charging") + 1, "Scheduled charging, Charge start, then Charge done by");
        require(places(ControlsPages.CHARGE_DONE) == 1, "One Charge done by row");
        String source = new String(Files.readAllBytes(Paths.get(mainActivity)), StandardCharsets.UTF_8).replaceAll("\\s+", "");
        // The row opens a sheet with both former rows (the switch and the time): every setting stays reachable.
        require(source.contains("doneBy=newChargeDoneRow(false,true);") && source.contains("rowId(ControlsPages.CHARGE_DONE,doneBy.row.card);"),
                "ControlsScreen builds the one Charge done by row under its id");
        require(source.contains("row.chevron(MainActivity.this::showChargeDoneSheet);")
                && source.contains("for(booleantime:newboolean[]{false,true})") && source.contains("ChargeDoneRowrow=newChargeDoneRow(time);"),
                "Its sheet holds the on/off switch and the finish time");
        require(source.contains("for(ChargeDoneRowrow:doneSheetRows)row.refresh();"), "The sheet's rows stay current while it is open");
        // Quick access keeps both shortcuts as they were.
        require(source.contains("if(id.equals(\"charge_done_by\")){toggleChargeDoneBy();return;}")
                && source.contains("if(id.equals(\"charge_done_time\")){showChargeDoneTime();return;}"), "Quick access ids still act directly");
        require(source.contains("place(body,card);Viewdone=gluedBelow.get(card);if(done!=null)place(body,done);"),
                "A page still places any glued row directly after its parent, in the same card");
        // The searched links carry the zones row's words, so searching "zones" or "ambient" still finds the light pages;
        // each link its own family's words with its title among them (#404 review), the words Hub search uses too.
        require(source.contains("for(Stringtarget:ControlsPages.SEARCHED_LINKS)") && source.contains("extras.put(card,newString[]{section,HubSearchTables.pageWords(target)});"),
                "The index's search finds Line lights and Star lights by the zones row's words");
        for (String target : ControlsPages.SEARCHED_LINKS) {
            String words = HubSearchTables.pageWords(target), family = target.equals(HubPages.LIGHTS_LINE) ? "line" : "star";
            require(words.contains("zones") && words.contains("ambient") && words.contains(family + " lights")
                    && !words.contains(target.equals(HubPages.LIGHTS_LINE) ? "star" : "line"),
                    "The " + family + " lights link has its own family's words, its title among them: " + words);
        }
        // A page's card hides when none of its rows shows (#387 review: Security's lone lock-duration row on a model
        // without one left an empty strip).
        require(source.contains("pageBody.setVisibility(hideDividers(pageBody)?View.VISIBLE:View.GONE);")
                && source.contains("privatebooleanhideDividers(LinearLayoutbody)") && source.contains("returnhasVisible;"),
                "A page's card shows only while one of its rows does");
        require(source.contains("linksBody.setVisibility(hideDividers(linksBody)?View.VISIBLE:View.GONE);"),
                "The links' own card shows only while one of its links does");
        // Disconnected, Security shows one Connect, the lock bar's (#387 review): the banner's pill and More's button hide there.
        require(ControlsPages.CONNECT_ABOVE.equals(Arrays.asList(HubPages.SECURITY)), "Security's lock bar has the page's Connect");
        require(source.contains("bannerAction.setVisibility(connecting||ControlsPages.CONNECT_ABOVE.contains(spec.page)?View.GONE:View.VISIBLE);"),
                "The last-seen banner leaves out its Connect under a lock bar");
        String more = new String(Files.readAllBytes(Paths.get(mainActivity).resolveSibling("MoreScreen.java")), StandardCharsets.UTF_8).replaceAll("\\s+", "");
        require(more.contains("securityConnect.setVisibility(!embedded&&link==BleController.State.DISCONNECTED?View.VISIBLE:View.GONE);"),
                "More's Security section on the Security page leaves out its Connect");
        // The Charger row on Charge history & recordings names the charger under its title, full width, never in the
        // 140 dp value beside it (German at large text broke inside words there; #387 review).
        require(more.contains("chargerLinkRow.sub((effective==null?HubText.chargerAutomatic()") && !more.contains("chargerLinkRow.value("),
                "The Charger row's charger is its sub-line");
        // Charge history & recordings' link draws the chart its recordings row uses, never the default star.
        require(source.contains("caseHubPages.BATTERY_HISTORY:return\"chart\";"), "Charge history & recordings has the chart icon");
        // Battery & charging's HISTORY card opens Charge history & recordings and carries that link's tag.
        require(source.contains("historyCard=newChargeHistoryCard(MainActivity.this,design,()->open(HubPages.BATTERY_HISTORY));")
                && source.contains("historyCard.card.setTag(\"hub-link-\"+HubPages.BATTERY_HISTORY);"), "The HISTORY card opens Charge history & recordings");
        require(source.contains("for(LinearLayoutbody:cardBodies)if(body!=pageBody&&body!=linksBody)body.setVisibility(hideDividers(body)?View.VISIBLE:View.GONE);"),
                "Every card of rows shows only while one of its rows does");
    }

    private static void order() {
        // Most used first (owner's decision 3): drive mode then cruise on Controls; scheduled charging first on Battery.
        List<String> controls = ControlsPages.spec(HubPages.CONTROLS).rows;
        require(controls.indexOf("maximum_speed") == 0 && controls.indexOf("cruise_control") == 1, "Controls opens with drive mode and cruise");
        // Battery & charging in the Layout Plan's order (the contributor's pick on #383): Charge limit, Scheduled charging,
        // Charge done by, Battery alerts, Live charging; then HISTORY; then Range estimate and Usable ride energy.
        List<String> battery = ControlsPages.spec(HubPages.BATTERY).rows;
        require(battery.indexOf("charge_limit") == 0 && battery.indexOf("timed_charging") == 1, "Battery opens with the charge limit, then scheduled charging");
        require(battery.indexOf(ControlsPages.CHARGE_DONE) < battery.indexOf(ScooterMenuLayout.CHARGE_ALERT)
                && battery.indexOf(ScooterMenuLayout.LOW_ALERT) < battery.indexOf(ControlsPages.CHARGE_LIVE)
                && battery.indexOf(ControlsPages.CHARGE_LIVE) < battery.indexOf(ControlsPages.CHARGE_HISTORY)
                && battery.indexOf(ControlsPages.CHARGE_HISTORY) < battery.indexOf("range_algorithm")
                && battery.indexOf("range_algorithm") + 1 == battery.indexOf(ControlsPages.USABLE_ENERGY), "The plan's order");
        List<String> lights = ControlsPages.spec(HubPages.LIGHTS).rows;
        require(lights.indexOf(ScooterMenuLayout.HEADLIGHT_INFO) == 0, "Lights opens with the headlight's note");
        require(lights.indexOf(ControlsPages.link(HubPages.LIGHTS_STAR)) < lights.indexOf("turn_sound"), "Lights before sounds");
    }

    /** A row the page would show on this model with no status yet: an ordinary row, or its Experimental entry. */
    private static boolean shownOn(DeviceDescriptor d, String id) {
        boolean feature = ScooterCommands.oemOnly(id);
        for (ScooterCommands.Feature f : ScooterCommands.catalog()) feature |= f.key.equals(id);
        if (!feature) return true; // hand-built rows
        CapabilityResolver.Runtime rt = new CapabilityResolver.Runtime();
        CapabilityResolver.Decision ordinary = CapabilityResolver.decide(id, d, StatusFields.NONE, rt);
        boolean row = ordinary.support == CapabilityResolver.Support.KNOWN_ENCODING && ordinary.variant != null && !ordinary.variant.lab;
        return row || ControlsPages.labEntryShown(row, ModelRegistry.experimental(d).contains(id),
                CapabilityResolver.decideExperimental(id, d, StatusFields.NONE, rt).experimental());
    }

    private static void experimental() {
        // The GT3 Max (24182): its Lab tries the three assists, Prompt tone and the custom speed cap, none an ordinary row.
        // Each gets an entry on its page, shown as More lists it.
        DeviceDescriptor gt3 = OemControlsTest.model("24182", "ES");
        List<String> lab = ModelRegistry.experimental(gt3);
        require(ControlsPages.labEntries(ControlsPages.spec(HubPages.CONTROLS_ASSISTS), lab)
                .equals(Arrays.asList("parking_assist", "hill_start_assist", "hill_descent_control")), "GT3 Max assists: " + lab);
        require(ControlsPages.labEntries(ControlsPages.spec(HubPages.CONTROLS_LIMITS), lab).equals(Arrays.asList("speed_limit")), "GT3 Max speed cap");
        require(ControlsPages.labEntries(ControlsPages.spec(HubPages.LIGHTS), lab).contains("prompt_tone"), "GT3 Max Prompt tone");
        for (String key : new String[] {"parking_assist", "hill_start_assist", "hill_descent_control", "prompt_tone", "speed_limit"}) {
            // The assists aren't flagged experimental in the catalogue (the old builder never made their entries); Prompt tone
            // and the speed cap are (UT5 MAX Lab controls), but the old check asked the ordinary decision and hid them.
            if (key.endsWith("assist") || key.equals("hill_descent_control")) require(!ScooterCommands.find(key).experimental, "Not a catalogue Lab control: " + key);
            CapabilityResolver.Runtime rt = new CapabilityResolver.Runtime();
            CapabilityResolver.Decision ordinary = CapabilityResolver.decide(key, gt3, StatusFields.NONE, rt);
            boolean row = ordinary.support == CapabilityResolver.Support.KNOWN_ENCODING && ordinary.variant != null && !ordinary.variant.lab;
            require(!row, "No ordinary row on the GT3 Max: " + key);
            // The ordinary decision has no Lab variant here (the old check hid the entry); the experimental one does.
            require(!(ordinary.support == CapabilityResolver.Support.KNOWN_ENCODING && ordinary.variant != null && ordinary.variant.lab),
                    "The ordinary decision is no Lab try: " + key);
            boolean tryNow = CapabilityResolver.decideExperimental(key, gt3, StatusFields.NONE, rt).experimental();
            require(tryNow && ControlsPages.labEntryShown(row, lab.contains(key), tryNow), "The GT3 Max entry shows: " + key);
        }
        // Traction control is an ordinary OEM-mapped row on the GT3 Max: no entry.
        require(!lab.contains("traction_control") && ControlsPages.labEntries(ControlsPages.spec(HubPages.CONTROLS), lab).stream().noneMatch("traction_control"::equals),
                "GT3 Max traction control is a row, not an entry");
        // The UT5 MAX keeps its catalogue Lab controls' entries (Acceleration profile, Dual motor drive).
        DeviceDescriptor ut5 = ModelRegistryTest.serial("Q2538ZZ9US7777777");
        List<String> ut5Lab = ModelRegistry.experimental(ut5);
        List<String> controls = ControlsPages.labEntries(ControlsPages.spec(HubPages.CONTROLS), ut5Lab);
        require(controls.contains("acceleration") && controls.contains("dual_drive"), "UT5 MAX entries on Controls: " + controls);
        for (ScooterCommands.Feature f : ScooterCommands.catalog())
            if (ut5Lab.contains(f.key) && ControlsPages.pageOf(f.key) != null)
                require(ControlsPages.labEntries(ControlsPages.spec(ControlsPages.pageOf(f.key)), ut5Lab).contains(f.key), "Every Lab control on its page: " + f.key);
        // The rule: never beside an ordinary row, never outside the model's Lab list, only with a Lab try decision.
        require(!ControlsPages.labEntryShown(true, true, true) && !ControlsPages.labEntryShown(false, false, true)
                && !ControlsPages.labEntryShown(false, true, false) && ControlsPages.labEntryShown(false, true, true), "labEntryShown");
        require(ControlsPages.labEntries(ControlsPages.spec(HubPages.CONTROLS), Arrays.asList(ControlsPages.link(HubPages.CONTROLS_ASSISTS))).isEmpty()
                && ControlsPages.labEntries(null, lab).isEmpty() && ControlsPages.labEntries(ControlsPages.spec(HubPages.CONTROLS), null).isEmpty(),
                "Links and missing input give no entries");
    }

    private static void linkVisibility() {
        // A link shows only while its page has a row or an entry to show.
        for (String page : ControlsPages.pages()) {
            if (HubPages.depth(page) < 2) continue;
            require(!ControlsPages.linkShown(page, id -> false), "Nothing to show hides the link: " + page);
            require(ControlsPages.linkShown(page, id -> true), "Anything to show keeps the link: " + page);
        }
        require(ControlsPages.linkShown(HubPages.BATTERY_MODES, "long_range_mode"::equals), "One row is enough");
        require(!ControlsPages.linkShown(HubPages.BATTERY_MODES, "charge_limit"::equals), "A row of another page doesn't count");
        require(ControlsPages.linkShown(HubPages.HOME, id -> false), "A page these rows don't build keeps its link");
        for (String page : ControlsPages.HOSTED_ELSEWHERE)
            require(ControlsPages.linkShown(page, id -> false), "A page built elsewhere keeps its link: " + page);
        // The GT3 Max's Assists page shows its three Experimental entries and kick-start speed's row or entry, so its link shows.
        DeviceDescriptor gt3 = OemControlsTest.model("24182", "ES");
        require(ControlsPages.linkShown(HubPages.CONTROLS_ASSISTS, id -> shownOn(gt3, id)), "GT3 Max Assists link");
        require(ControlsPages.linkShown(HubPages.CONTROLS_LIMITS, id -> shownOn(gt3, id)), "GT3 Max Speed limits link (the cap's entry)");
        require(ControlsPages.linkShown(HubPages.BATTERY_ESTIMATES, id -> shownOn(gt3, id)), "Estimates & charger always has its own rows");
    }

    private static int places(String id) {
        int n = 0;
        for (String page : ControlsPages.pages()) if (ControlsPages.spec(page).includes(id)) n++;
        return n;
    }

    /** The Layout Plan's two cards: the rows, then the trailing links in a card of their own; no icon tiles on Battery. */
    private static void cards() {
        ControlsPages.Spec controls = ControlsPages.spec(HubPages.CONTROLS);
        int from = ControlsPages.linksCardFrom(controls);
        require(controls.rows.get(from).equals(ControlsPages.link(HubPages.CONTROLS_ASSISTS)) && from == controls.rows.size() - 2,
                "Controls: Assists and Speed limits in a card of their own");
        require(ControlsPages.cards(controls).size() == 2 && ControlsPages.cards(controls).get(1).equals(controls.rows.subList(from, controls.rows.size())),
                "cards() keeps linksCardFrom's split on a page without a card of its own");
        // Battery & charging: CHARGING rows, the HISTORY card alone, then Range estimate, Usable ride energy and the links.
        ControlsPages.Spec battery = ControlsPages.spec(HubPages.BATTERY);
        List<List<String>> batteryCards = ControlsPages.cards(battery);
        require(batteryCards.size() == 3, "Battery & charging: three cards");
        require(batteryCards.get(1).equals(Arrays.asList(ControlsPages.CHARGE_HISTORY)), "HISTORY is a card of its own");
        for (String id : batteryCards.get(0)) require(!ControlsPages.isLink(id), "No link among the charging rows");
        require(batteryCards.get(0).get(0).equals("charge_limit") && batteryCards.get(0).get(batteryCards.get(0).size() - 1).equals(ControlsPages.CHARGE_LIVE),
                "CHARGING runs from Charge limit to Live charging");
        require(batteryCards.get(2).equals(Arrays.asList("range_algorithm", ControlsPages.USABLE_ENERGY,
                ControlsPages.link(HubPages.BATTERY_MODES), ControlsPages.link(HubPages.BATTERY_ESTIMATES))), "Range estimate, Usable ride energy, then the sub-pages");
        int total = 0; for (List<String> card : batteryCards) total += card.size();
        require(total == battery.rows.size(), "Every row in exactly one card");
        require(ControlsPages.cards(ControlsPages.spec(HubPages.SCOOTER)).isEmpty() && ControlsPages.cards(null).isEmpty(), "No cards on the index");
        // Charge history & recordings stays reachable: the HISTORY card opens it, and More's Charging section still leads there.
        require(!battery.rows.contains(ControlsPages.link(HubPages.BATTERY_HISTORY)) && battery.rows.contains(ControlsPages.CHARGE_HISTORY),
                "The HISTORY card stands in for the Charge history link");
        ControlsPages.Spec lights = ControlsPages.spec(HubPages.LIGHTS);
        require(ControlsPages.linksCardFrom(lights) == lights.rows.size(), "Lights: links between rows keep one card");
        ControlsPages.Spec assists = ControlsPages.spec(HubPages.CONTROLS_ASSISTS);
        require(ControlsPages.linksCardFrom(assists) == assists.rows.size(), "A page without links keeps one card");
        require(ControlsPages.linksCardFrom(ControlsPages.spec(HubPages.SCOOTER)) == 0 && ControlsPages.linksCardFrom(null) == 0,
                "The index (no rows) and no spec: nothing to split");
        require(!ControlsPages.iconTiles(HubPages.BATTERY), "No icon tiles on Battery & charging");
        for (String page : new String[]{HubPages.CONTROLS, HubPages.CONTROLS_ASSISTS, HubPages.LIGHTS, HubPages.BATTERY_MODES, HubPages.SECURITY})
            require(ControlsPages.iconTiles(page), "Icon tiles stay on " + page);
    }

    private static void require(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
}
