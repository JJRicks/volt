# Development and validation notes

Implementation invariants and native test guidance for contributors. Start with [CONTRIBUTING.md](../CONTRIBUTING.md) for the contribution workflow.

## Source map

| Location | Purpose |
| --- | --- |
| `src/com/jjricks/volt/` | Native screens, Bluetooth authentication/controls, telemetry, profiles, recording and storage |
| `res/`, `AndroidManifest.xml` | Android resources, permissions, app version and component declarations |
| `build.sh` | SDK-only APK build, signing and verification |
| `tests/` | Core Java/Python tests and runner |
| `android-tests/` | Separate guarded device/emulator instrumentation and fixture runners |
| `setup/capture-owner.py`, `setup/owner-capture-guard.sh` | Computer owner-capture helper and on-phone capture cleanup guard |
| `setup/web/` | Static Chrome/USB setup wizard, pinned browser ADB bundle, recovery and security tests |
| `res/values/strings_*.xml`, `l10n/`, `tools/l10n/`, `tests/l10n/` | The language catalog and its checks; start with the [localization guide](localization-for-contributors.md) |
| `docs/`, `FEATURES.txt`, `VERIFICATION.txt` | User/technical references, per-control evidence and dated validation history |

## Working on the UI

Read this before moving settings between screens, regrouping a menu, or adding another kind of dashboard. Every rule below was checked against the code and CI's gates when it was written.

### Screen map

All screens are native views built in code; there are no layout XML files. `MainActivity` shows one page at a time. Page ids, their parents and the rules about them are in `HubPages` (pure Java, tested by `tests/HubPagesTest.java`); `HubNav` is the stack of open pages, with Home at the bottom. There is no tab bar or side rail (Hub layout part 11): Home is the hub, and every other page opens on Home's page stack. Directly under Home are the seven subjects (`HubPages.SUBJECTS`, in Home's order), Settings (the header's gear) and Search (the header's search button):

| Page | Page id | Class | Notes |
|---|---|---|---|
| Home | `home` | `DashboardScreen` or `CleanHomeScreen` | `home_style` (`HomeStylePrefs.of`) picks one; absent means the widget dashboard. `DashboardScreen` is the widget dashboard and its editor: widgets are `DashboardConfig.TYPES`; Quick access is `QuickAccessGrid` and `QuickAccessActions`. Widgets Home has `HubDock` at the bottom, where the tab bar was and with its look (the floating bar's surface, outline, elevation and margins; owner's answer 8 on #383): the subjects' icons, each over its short name (`Ui.nav`; tags `hub-dock-<page id>`), and an **All** item (tag `hub-dock-handle`, the old grab handle's tag). Where seven don't fit at a comfortable width (`HubPages.dockSubjects`, 60 dp an item at normal text in `HubPages.dockRoom`, the window less the dock's margins and padding; a 344 dp phone shows four, or two at 1.3x text), it shows the first of `HubPages.DOCK_ORDER` (Ride, Battery & charging, Controls, Data, …) in Home's order; All or an upward swipe opens a sheet of every subject with its summary (`CleanSubjects`). Service's due dot sits on All while Service isn't shown. Hidden while the dashboard is edited. The old tab bar and side rail are gone (deleted in part 11 on the contributor's decision, #383); `Ui.nav`, the rail's old item, builds the dock's items. The old tab ids (`scooter`, `stats`, `rides`, `more`) still resolve through `HubPages.canonical`. `CleanHomeScreen` is the fixed Clean Home beside it: it borrows the header's status line and name, the battery card and the charging view from `DashboardScreen.CleanParts` (an instance with no `DashboardConfig`), shows its four from `home_quick_v1_<pid>` (`HomeQuickActions`) and its subjects with `CleanSubjects` (the scooter subjects' lines from `HubSubjects.summary`). Settings › Home style is page `settings.home` (`HomeStyleScreen`, reached from Settings and the Layout sheet). Every style choice (`HomeStylePicker`: setup's last step, Settings, Clean's Edit sheet; and the one-time `WhatsNewSheet`) draws each style with `HomeStylePreview`; the What's new sheet also shows, to an updating owner with Record rides automatically on (`HomeStyle.whatsNewBackgroundNotice`), the notice that background starts are now off with the `auto_record_background` switch and a link to `ride.recording`, and the installed version's `CHANGELOG.md` section, which `build.sh` bundles as the asset `whats-new/notes.md` (`WhatsNewNotes`) |
| Ride | `ride` | `RidesScreen` (`SUMMARY`) | The Riding mode and Record ride tiles (tags `ride-open-riding`, `ride-record`; `RideRecordTile`), the live recording card, the three latest rides and links. `ride.rides` is `RidesScreen` `ALL` (the whole history, Compare, the wide panes; `HubNav.Args.ride` opens one ride); `ride.recording` is a `HubPageScreen` of `MoreScreen`'s `Recording` section and `RidesScreen` `SENSORS` (the phone sensors card); `ride.riding` is `HubRidingSettings` (`MoreScreen.RidingSettingsMenu`, as riding mode's tune menu) |
| Battery & charging, Controls, Lights & sound, Security, Service | `battery`, `controls`, `lights`, `security`, `service` | `MainActivity.scooterPage` | `controls`, `lights` and their sub-pages and `battery.modes` are `ControlsScreen` with a `ControlsPages.Spec` (each page's rows in a fixed order); `battery.estimates` is a `HubPageScreen` of its `ControlsScreen` and `UsableEnergyPanel` (the capacity widget, tag `battery-usable-energy`, from `DashboardScreen.EnergyPanel`, an instance with no `DashboardConfig`); `battery` is a `HubPageScreen` of `HubBatterySummary` and its `ControlsScreen`, whose cards follow `ControlsPages.cards` (the CHARGING rows with one Charge done by row, `ControlsPages.CHARGE_DONE`, that opens a sheet with the switch and the time; the HISTORY card, `ChargeHistoryCard` with `ChargeHistoryBars`, tag `hub-link-battery.history`; then Range estimate, Usable ride energy, tag `battery-usable-energy-row`, and the sub-page links); `security` is a `HubPageScreen` of the lock bar, its `ControlsScreen` and `MoreScreen`'s `Security` section; `service` is a `HubListScreen` (`HubSubjects.service`); `service.maintenance` is `MaintenanceScreen`; `service.compatibility` is `HubCompatibilityPage`; `service.experimental`, `service.reports` and `battery.history` are `MoreScreen` sections shown as pages (`Lab`, `Reports`, `Charging`) |
| Data | `data` | `StatsScreen` | Live Stats: readings and charts, with its own search, and a `Data & history` row (tag `hub-row-data.storage`) under the sections. `data.storage` is `MoreScreen`'s `Data` section shown as a page, with the Live Stats detail density (`stats_density`) |
| Settings | `settings` | `MoreScreen` (no section) | One index as the Layout Plan's settings mock-up (part 11): no scooter card; one card of Home style, Appearance (a row with its value; its Dark / Follow system / Light selector opens over the page), Units, Text size, Language (`SettingsRows.languageIndex`; its value beside the title when it fits, `SettingsRows.valueBeside`) and Haptic feedback; then a labelled card with My scooters (the scooter's name and connection under it) and Back up & restore; then one with Updates (its automatic switch and Check for updates open over the page), About & permissions and Developer options. Rows carry no icon tiles. Every row that opened a section opens its page: `settings.scooters`, `settings.units`, `settings.about` and `settings.developer` are those sections shown as pages (`HubRoutes.moreSectionOf`). `Security`, `Lab` and `Data` are still built but Settings doesn't list them; they are the `security`, `service.experimental` and `data.storage` pages. Settings is one column on every width; `more_section` is no longer read |
| Search | `search` | `HubPageScreen` of `MainActivity.ControlsScreen` (spec `search`) and `CleanSubjects` | The search over every scooter row the Scooter tab's index had, with the seven subjects below while the field is empty. Hub layout part 12 replaces it with the full search |

Every page but Home shows a back link (tag `hub-back`, "Back to <page>") to the page under it in the stack and its `HubText` title in the header; Data keeps Live Stats' title with its live dot. The ids used before, `stats`, `rides`, `more` and `scooter`, are still accepted: `HubPages.canonical` maps them to `data`, `ride`, `settings` and `home` (the Scooter tab's index is gone; its subjects are Home's), and maps an unknown id to `home`. `controls`, the Scooter tab's old id, is the Controls page. Android validations read `MainActivity.selectedPage`, which is always the top of the stack, and call `select(page)`; compare with `HubPages.canonical(page)`, not the id you passed. `android-tests/HubTestPages.java` reaches a page by id and finds the `ControlsScreen` or `MoreScreen` part of a `HubPageScreen`; use it rather than reading `MainActivity.screen` directly. `HubTestPages.reach` (from the instrumentation thread) goes the rider's way the first time an activity shows a page: from Home it taps on each page the row that opens the next one (on Home, Clean's subject rows `home-subject-<page>` and the header's `home-settings` and `home-search`; then `hub-row-<page>` on Service, the Ride page and Data, `hub-link-<page>` on a subject page), with a real touch once the row is scrolled on screen, and fails naming the row when it is missing, hidden, disabled, off screen or opens another page. A repeat visit opens the page directly (`HubTestPages.open`: Home, then each page on `HubPages.path`), which is also the call for setup. `tapRow` and `requireNoRow` check one row on the page shown, for rows that depend on the model.

Outside the pages: `RidingScreen` is riding mode (layout editor `RidingLayoutSheet`, settings `RidingConfig`), `OnboardingScreen` is first launch, and `BackupActivity` is backup and restore. `Ui` holds the shared design system (colors for both themes, navigation, rows), with `Motion`, `Haptics`, `ReorderList`, `SegmentedSelector` and `TileRung` beside it. At 650 text-dp and wider (`WideLayout`) pages take their wide layouts across the whole window (there is no rail): the dashboard's wide arrangements, two panes on Live Stats and Rides, two columns on the scooter pages; the dock is centred and holds all seven, each icon with its short label (`Ui.nav`). `ScreenHost.contentWidthDp()` is a page's width.

How Volt returns to a place:

- A fresh start opens Home. Within a session, and when Android recreates the activity, the open pages are kept: the saved state holds the top page in `page` and the whole stack in `page_stack`, and `HubNav.restore` checks every saved id, falling back to the top page and its parents. Each page change writes the top page's id to `last_page`; nothing reads it to choose a page.
- Every route opens its page on top of the current one: `open(page)` (`ScreenHost`), `select(page)` (the same, kept for validations), Home's subject rows and dock icons, the gear and search (Clean's header; on Widgets, the top of the dock's All sheet), Quick access shortcuts (`openRoute`) and the scanner's and Review's links. A page already in the stack closes the pages above it, so `select("home")` returns to Home. `open` can carry one-shot arguments (`HubNav.Args`) that are dropped on the next page change. Tapping the page already shown scrolls it to the top.
- Back first goes to onboarding or riding mode when they are showing, then to the screen's own `back()` (ending dashboard editing, clearing a search), then to the page below in the stack, and closes the app from Home. The page header's link goes to the page it names: it skips only Search's own step (`HubNav.linkSkipsPageBack`).
- After a minute or more away from the app, or a reboot (`hub_away_elapsed`, `hub_away_boot`, `HubNav.awayExpired`), open sub-pages close back to their subject (`HubNav.closeSubPages`); Home is never forced. The clock does not start when Volt left while one of its own dialogs or sheets was showing, or within two seconds of starting another app itself (an email, a file picker): `HubNav.awayClockStarts`, fed by `OwnDialogs` (every dialog that goes through `Motion.present` or `MainActivity.styleDialog`) and `startActivityForResult`. A window that is not Volt's, such as the notification shade or the other app in split screen, does not hold the clock. A fresh start, a maintenance notice's tap and the update check's notice clear that clock first, so their route is never cut back. Data's pages keep Live Stats' own ten-second `stats_section` reset. `more_section` is retired and no longer written; `scooter_section` only holds a maintenance notice's tap still waiting for riding mode or setup to close (below).
- Quick access ids never change: `HubRoutes.pageFor` gives the page a shortcut to a whole settings page opens (`controls`, the legacy Settings shortcut that opened the Scooter tab, now opens Search, which holds what that tab's index did; `section:Security`; `more:*`), and `HubRoutes.sheetFor` the quick-control sheet that `lights` and the scooter groups (`section:Ride`, `section:Battery`, `section:Line lights`, `section:Star lights`, `section:Dashboard & sound`) keep over Home (`MainActivity.showQuickSettings`, owner's answer 3 on #383); and a control's shortcut that the Lab tries, or the PIN or proximity unlock, opens `service.experimental` (with the control as `HubNav.Args.inspect`) or `security`. The Settings sections and Data & history are pages (`HubRoutes.moreSectionOf` names the section each shows); `more:App` opens Settings. `tests/HubRoutesTest.java` checks every catalog id.
- A `MoreScreen` built with `embedded` shows one section as a page's body: no section header, no scroller of its own, Back left to the page stack, and a link to another section opens that section's page (`HubPages.fromMoreSection`). Settings' own `MoreScreen` does the same for its rows.
- Notification taps route by intent: a maintenance notice opens Service › Maintenance reminders with Service and Home below it (`MaintenanceNotices.tapped`, `MaintenanceScreen.PAGE`; during riding mode, setup or a riding-mode restore still waiting for the service, or at a cold start that lands in unfinished setup, as soon as they close: the pending tap is kept across recreation and, in `scooter_section` (`MaintenanceScreen.PENDING_PREF`), across a restart), the update notice opens Settings and its update check (`UpdateCheckJob.opened`), and the connection notification just opens the app. Connecting with no scooter opens `settings.scooters` (Add scooter); connecting with a scooter that is not set up yet opens Settings, whose checklist imports the owner setup.

Page and section ids are stored values, not text. Keep them stable, or handle the old id where it is read: a changed page id needs an entry in `HubPages.canonical`'s legacy map, and `tests/HubNavTest.java` checks that saved pages with old ids still restore.

### Moving or regrouping a setting

Check each of these in the same pull request:

1. **The row's text.** Keep the string key in the `strings_<area>.xml` file it is in now, even if the row moves to another screen. `ship_check.py` requires each translated key to sit in the same area file as its English, so moving an English key to another file fails CI for every shipped language. Update the `volt:screen` attribute, and `volt:max` if the room changes: that metadata doesn't change the text, so CI still counts the translations as current.
2. **Search.** The search page (Clean header's search button or the Widgets dock's All sheet; the Scooter tab's search until Hub layout part 11) matches each row's shown title plus extra words from `main_search_*` keys (`volt:purpose="search keywords"`), attached in `ControlsScreen` through `shownExtras` and `extras`. A row moved off the scooter pages takes its search words out of that search; if it moves onto them, give it words. Live Stats has its own search. Settings has none.
3. **Remembered places.** If a page id, More section id or `stats_section` value changes or disappears, decide where a saved old value now leads. Check notification routes that open the old place.
4. **The Scooter pages.** `ControlsPages` lists each Scooter page's rows in a fixed order (feature keys, `ScooterMenuLayout`'s hand-built row ids, links to sub-pages); `tests/ControlsPagesTest.java` checks that every control is on exactly one page. A control the model's Lab list holds (`ModelRegistry.experimental`) and doesn't offer as an ordinary row gets an "Experimental" entry in its row's place, shown by `BleController.experimentalDecision` exactly as More's Experimental controls lists it (`ControlsPages.labEntries`, `labEntryShown`); a link to a sub-page hides while that page has no row and no entry to show (`ControlsPages.linkShown`). The index's summaries (`HubSubjects`, rules in `HubSummaries`) read only controls the model offers as ordinary rows, and show nothing while a value they need is unknown. The arrange-and-hide editor was removed in Hub layout part 3 (owner's decision 5 in #383, approved in his answers 1 and 5 there): `scooter_menu_v1_<pid>` is no longer read but stays registered so older backups restore.
5. **TalkBack.** Keep content descriptions and headings; a moved row should read the same with TalkBack. Validations and TalkBack both find rows by view tags, IDs and content descriptions, so keep those stable where you can, and list any that changed in the pull request.
6. **Docs with menu paths.** Update every doc that spells out the old path: `docs/user-guide.md`, `docs/getting-started.md`, `FEATURES.txt`, `SETUP.txt`, `PRIVACY.md`, `README.md` and the homepage under `site/` all carry menu paths, written with `›`, `→` or `>`. Search them for the old name, for example:

   ```sh
   git grep -n "Developer options" -- docs FEATURES.txt SETUP.txt PRIVACY.md README.md site
   ```

   Translated style notes under `l10n/` are the maintainers'.

### Stored state

Volt must keep working with what earlier versions saved, and must not break an older Volt that restores a newer backup.

- **Every new preference key or file needs a backup class.** `BackupRegistry` classifies each key as durable, device, pending, cache or diagnostic. `tests/BackupRegistryTest.java` (in `tests/run.sh`) fails when source writes a key it doesn't classify, naming the key. Add the key there and a row to [docs/app-storage-inventory.md](app-storage-inventory.md).
- **Prefer a new key to a changed format.** An older Volt reads the dashboard store leniently (unknown widget types skipped, unknown fields ignored) but checks saved slots and dashboard transfers strictly: a slot or transfer with a new widget type or layout field is refused there ("Unknown widget type", "Unexpected layout fields"). An older Volt restores keys it doesn't know only with Replace everything. Riding mode's layout falls back to defaults on a newer version number.
- **Known cost: the Battery alerts shortcut (`charge_alerts`).** A new Quick access id behaves like a new widget type there. Volt 1.13.1 refuses whole a dashboard transfer file holding it, in the live dashboard or any slot (`parseTransfer` → `validateSlot`, "Invalid shortcut"), and can't restore a full backup made by a later version at all (`BackupArchive.extract` refuses an app version code above its own). Two cases still drop the id quietly from the live dashboard (refilling a widget that falls under its minimum) and show a saved slot holding it as unreadable (it can be overwritten, not restored): a downgrade that keeps app data, and 1.13.1 restoring a backup made by a development build of main from before its version code went above 58, since those builds already had the shortcut. No form that 1.13.1 skips quietly exists for slots or transfers. The owner accepted this on Nov 12 2026 (#383, issuecomment-6845477304) and will fix an affected backup file by hand if someone writes in.
- **A view choice is saved state too.** A dashboard "mode", a new default tab or a remembered toggle is a preference key. Decide whether it is durable (backed up) or device-only, register it, and give it a default that matches today's behavior when the key is absent.
- **Never overwrite a saved custom or empty dashboard** with defaults. Reorganizing must keep every scooter's saved layouts, menu order, hidden rows and riding layout.

### Adding another dashboard beside the widget dashboard

The widget dashboard stays, and an alternative goes beside it, not in its place.

- The widget dashboard's state is per scooter: `dashboard_v1_<pid>` (the live dashboard), `dashboard_slot_v1_<pid>_<n>` (saved slots), `dashboard_empty_example_v1_<pid>` and the global `dashboard_columns`. Don't change their formats for the new dashboard; store its state in new keys.
- `DashboardConfig` owns the format and its validation, `DefaultDashboard` the defaults, `DashboardLayout` the pure grid logic, and `DashboardScreen` the rendering and editor. `DashboardScreen` is large; add a new class for a new dashboard instead of growing it.
- Reuse the existing reading helpers (`ReadingSpan`, `ReadingUnits`, `ScooterReadingLabels`, `DashboardWords`, `LiveStatsTitle`) so units, missing values and spoken labels stay consistent.
- The switch between dashboards is a saved choice (see [Stored state](#stored-state)), and its absence means the widget dashboard.
- Many tests read `DashboardScreen.java` and `QuickAccessGrid.java` as text (`DashboardScreenTextTest`, `DashboardFollowupsTest`, `ConnectingLabelTest`, `RecorderTapTest`, `NarrowLabelLadderTest`, `DashboardModelTileTest` and others under `tests/l10n/`). Restructuring those files can fail them even when the app works; such a test edit goes in an ordinary pull request (see below).

### Tests that navigate the UI, and the order CI forces

CI runs the JVM and Python suites and builds the app and the validation APK, but does not run the Android validations under `android-tests/` (they need an emulator). Those validations walk the real screens: about thirty find views by tag and more by visible text or content description. A move can break them without failing CI. The maintainers run the affected ones on every UI pull request, and update validations that fail only because something moved. [CONTRIBUTING](../CONTRIBUTING.md#build-and-test) has the contributor's side.

The localization gates decide what one pull request can hold. Its kind comes from what it changes ([Pull request kinds](localization-for-contributors.md#pull-request-kinds)):

- **Moving code within one file** keeps that file's catalog references the same, so the pull request stays `ordinary` unless it changes other things.
- **Moving code that calls `T.` or `M.` into another file** makes it a `batch`, because the new file gained catalog references. That passes if the moved code has no text or data literals other than lower-case ids.
- **Moving code that has both catalog calls and other literals** (such as `"Locked"` or a section id like `"Lights"`) into another file fails `G1`: the literal counts as removed from one file and new in the other. Move only the catalog calls in that pull request, and ask on the issue about the literals; the maintainers record them.
- **A `batch` may not edit the tests directly under `tests/l10n/` at all**, not even by adding a statement: they count as proof tooling, like `tests/run.sh`, `android-tests/build.sh` and `tools/l10n/`. That includes the dashboard text tests named above. Other existing tests (`tests/*.java`, `android-tests/*.java`) may only gain whole statements. A `batch` also may not change goldens, and may only shrink `l10n/baseline/`. So a test that reads a file you restructured, or a validation that needs more than added statements, can't be fixed in the same `batch`.

That gives a large move this order:

1. **Ordinary prep pull requests**: pure logic, new classes with no catalog references, new preference keys and their backup classes, and any edit to existing tests.
2. **The move itself**, with the catalog calls. Keep it small: one screen or section at a time.
3. **Validation updates** by the maintainers, as a follow-up or stacked pull request, after their emulator pass.

Run `python3 tools/l10n/ci_gates.py --base origin/main --print-mode` to see a pull request's kind before you push it. Never delete or disable a validation, test or golden to get past a failure.

### Text when it moves or changes

- A moved row keeps its key, its file and its translations. Only update `volt:screen`.
- New text is English only, in a `batch`. The maintainers translate it before the next release.
- Rewording an existing key changes every language: most keys have ledger rows, and rewording one fails `G1` unless it goes through the owner-approved English-change route in its own pull request. See [Changing existing English](localization-for-contributors.md#changing-existing-english).
- A label that now has less room needs a shorter rung ([Narrow labels](localization-for-contributors.md#narrow-labels)), not smaller text. Say so in the pull request, because translations are usually longer.
- A key that is no longer used doesn't fail CI. Say in the pull request which keys you stopped using; the maintainers retire them.

### Running a large overhaul

1. **Open an issue first.** Describe the target layout and the series of pull requests. Removing a setting, changing a default or changing safety wording (lock, alarm, charging, experimental controls, PIN) needs the project owner's yes in that issue before any code.
2. **Small pull requests that keep `main` shippable.** Each one leaves every setting reachable and every saved layout intact. Old and new layouts may coexist for a while.
3. **Screenshots before and after**, at least on a phone width. The maintainers' standard set is 344 dp and 725 dp wide, large text, light and dark themes, and a foldable's cover screen where it applies; attach whichever you can.
4. **List what moved** in each pull request as "old place → new place".

## Device checks

The Android validations in `android-tests/` are run by the maintainers, not CI, and contributors aren't required to run them. [Running an emulator check yourself](building.md#running-an-emulator-check-yourself) shows how to run one if you want to. `android-tests/run-release-sweep.py` runs them on a disposable emulator; `--list` prints its steps, and `--only <step>` runs one. The rest of this section is reference for the individual runners.

### Adding a validation

This is optional reference. A new validation class must be registered in three places, or it doesn't run:

1. `android-tests/AndroidManifest.xml`: an `<instrumentation>` element naming the class.
2. `android-tests/build.sh`: the class's source file in its compile list. Without it, the run fails with `ClassNotFoundException`.
3. `android-tests/run-release-sweep.py`: a step in `STEPS`, or an entry in `EXCLUDED` or `SUBSUMED` with the reason. Otherwise the sweep refuses to start (`Not covered by the release sweep`), and `tests/release-sweep-summary-test.py` fails in CI.

Editing `android-tests/build.sh` is refused in a pull request with catalog text (a `batch`), so a new validation goes in an ordinary pull request, before or after the one that adds the text. Edits to existing validations in a `batch` may only add whole statements.

### Individual runners

`android-tests/ParkedValidation.java` is a separate, explicitly invoked instrumentation runner. Its modes include real lock/settings writes and stationary recording sessions; choose a mode only when those actions are intended and the owner is present with a safely parked scooter. Navigation/background modes perform different checks and have documented guards. Read the chosen mode before running it. `sweep` takes `-e exclude key,key` (recorded, never written); `schedule` runs Test scheduled charging end to end and passes only on an on-time resume with Scheduled charging confirmed off.

`android-tests/run-hardware-sweep.py` runs those modes as one owner-present plan on a physical phone (`--list` prints it): read-only snapshot, navigation and background checks, then clock, reversible settings, charging start time and, while charging, the schedule test. It refuses emulators and runs without `--parked`, never installs or removes Volt, excludes speed caps, maximum speed, dual motor drive and the bare Scheduled charging toggle, keeps lock and the PIN state read behind `--include`, and skips later writes after a failed one. `tests/hardware-sweep-test.py` checks those rules against a fake adb.

`android-tests/DeveloperSettingsValidation.java` validates rounded preferences and
the Developer options simulation on a disposable emulator with Bluetooth disabled.
Its default mode tests simulation isolation; `-e mode preferences` limits it to
the selectors and unlock gate. It refuses physical phones or existing profiles.
Compare the real telemetry database, profile/control-evidence preferences and
private ride files before/after the preview; require the real GATT, scanner,
command queue and recorder to stay idle. Include normal/reduced motion and a
compact large-text case when these controls change.

`android-tests/LaunchValidation.java` checks fresh launch, permissions, themes, and recreation. It refuses physical phones or existing app data and requires a fresh emulator. Run denied and granted-permission modes separately; granted mode requires Bluetooth off.

Preserve the app's confirmed-state lock behavior, permission/lifecycle guards, fixed ten-second idle background grace, and recording exception. Firmware settings use canonical raw units; mph/km display conversion must not change command values. Missing/stale samples must remain unavailable rather than becoming zeros or invented energy.

## Model compatibility rules

`ModelRegistry` (versioned) resolves each scooter to one layer: `UT5_MAX`, `OEM_GROUP`, `SHARED`, `UNSUPPORTED_PRODUCT` or `UNSUPPORTED_TRANSPORT`. `CapabilityResolver` turns that into one decision per feature, and every UI gate and every write uses it.

- **PID provenance:** OEM group rules test only the advertised model ID (parsed from the raw advertisement by `AdvertisedIdentity`). The serial-derived PID (`Profile.pid()`) is Volt's adapter: it selects only the UT5 MAX layer (2538) or the shared layer. After excluded-product and transport checks, a serial-2538 profile stays `UT5_MAX` even if another scooter model is advertised (the conflict is diagnostic only), and keeps today's choices in every region; regional OEM rules for it are notes.
- **Advertised IDs** are written to a profile only after authentication and a serial match on that MAC (`BleController.remember()`); **Identify** captures one without a write.
- **One admission point:** every write goes through `MutationGate.check` when requested and again just before queueing; `BleController.send()` drops any frame that is not a read, 0x30/0x31 or the admitted mutation bytes. Unlock is the explicit recovery exception (fresh ready session, lock field present, no pending change). `AUTOMATIC_RANGE_FOLLOW` writes only on the UT5 MAX layer.
- **Model assumptions:** UT5 labels via `ModelRegistry.driveModeLabelProduct`; the UT5 charge reference, 716.04 Wh, photo and range following come from the same registry helpers. Measured telemetry stays universal.
- **Adding a mapping:** add a `FeatureVariant` (keys never change; a new domain is a new variant id), a registry row with its evidence, a `validPayload` change only with a paired negative test, and bump `ModelRegistry.VERSION`. Keep the US UT5 MAX golden tests green.
- **Reads:** `ReadPolicy` drives the bundle and battery poll. UT5 MAX (and a profile with no identity yet) keeps `ScooterCommands.diagnosticReads()` and {0x72, 0x79}; other layers send each probe (0x71/0x75/0x79) once and drop an optional read after a reject or two 2.5 s silences. 0x7A is never polled.
- **Compatibility check:** `DiscoveryCoordinator` sends only its fixed `ORDER` of nine reads (one outstanding, 300 ms spacing, 2.5 s attempts, 10 s passive window, 30 s cap). While it runs `Runtime.discovery` refuses every write except a user lock change, which stops it first. `CompatibilityReport` is the single whitelist for built and imported reports; add a key there and to its test, never a free-text field. Packet evidence and the owner's observation stay in separate objects.
- **Guided tests:** `ModelRegistry.testRule` limits tests to `TESTABLE` (units, tail light, turn sound, automatic lights, range estimate; `display_units.shared` for unidentified scooters, which maps nothing) and refuses Lab rows; `CapabilityResolver.decideTest` adds the preconditions (real connection, fresh status with the field present, no pending change, check or recording, rule conditions, stationary or the owner's parked tick when no motion report exists). `GuidedTest` is the state machine: a baseline from a 0x70 requested after the start; `approve()` commits `TARGET_PENDING` to `TestJournal` before `writeNow()` hands out the value; before a baseline or post-ACK confirmation probe is transmitted, earlier in-flight 0x70 requests are drained; only the subsequent requested reply can confirm, and a missing earlier reply fails closed; the restore is journalled the same way. Any interruption makes it `OUTSTANDING`; `recover()` at service start writes nothing, and restore after review is an explicit `restoreFrom()`. `MutationGate` admits `GUIDED_TEST`/`GUIDED_RESTORE` only for the running test's feature, and ordinary changes report `TEST_ACTIVE` meanwhile. The journal (`getNoBackupFilesDir()/compat-tests`, synced temp file + rename) holds semantic values only and is never backed up. Results are report-only: no decision reads the journal.
- **Registry rows** live in `ModelRegistry.oemRule`: the prefix tables are Volt constants (`Q`, `O`, `S` … plus per-rule literal arrays), and each row returns `oemMapped(variant, note, unlocked)`, `excluded()` (the official app hides it for that model), `null` (not mapped: the evidence is partial) or a mapped row refused for a model condition through `gated(…)`/`firmware(…)` (`FIRMWARE_OLD`/`FIRMWARE_UNKNOWN`, `FULL_PID_NEEDED`). Firmware gates use the meter version read in this session, compared like the official app (string order). Registry version 7 offers the union of mapped regional choices for the same model. Version 17 makes two exceptions on every layer: Top speed and kick-start's 0–2 km/h follow the official app's own region test (`ModelRegistry.officialSpeedRegion`: serial SKU USA; its 2620 server-location branch counts as not shown), and Top speed outside it returns `REGION_RULE`. Version 19 adds one try beside an offered row: outside that region, kick-start held to 3–5 km/h (group M and an EU-serial UT5 MAX included) is also listed in Experimental controls as `START_LOW_TRY` (Standing start to 3 km/h, Lab); nothing that can raise speed is tryable. Otherwise region and OEM visibility flags remain provenance only; model membership, fifth-digit requirements, firmware, field presence, known values and mutation safety still gate writes. No model inherits another model's table, and device firmware may reject a choice.
- **Variants:** use `EXACT` readback unless the field is a bit (`MASKED`); a raw value with a known meaning outside the domain gets `withSentinel` (shown, never offered or sent). A real setting the variant doesn't offer here (kick-start 0–2 km/h on a 3–5 km/h variant) gets `withShown`: shown as the current value, the row stays usable, and it is never offered or sent. An Experimental controls try (`CapabilityResolver.tryNow`, `GuidedTest.capture`) may start from it toward an offered value or set back to an offered one, but Set back never returns to it (`GuidedTest.setBackValue`). Any other value outside the domain is `VALUE_UNKNOWN`. `ModelRegistry.caution` holds confirmations the official app asks for; `chargeStartAllowed` holds the non-UT5 whole-hour start rule. The UT5 MAX never gets either.
- **UI:** another model's variant supplies values and labels (`MainActivity.featureLabel`); a control that is Lab on the UT5 MAX becomes an ordinary Scooter row when its variant is not Lab (`decision.variant.lab`), and its Lab entry says so.
- **Allowlist:** `ScooterCommands.validPayload` admits the union of the mapped domains (0x6e selector 1: 25/30/32/35/40/45/50/55/60/65/70 km/h; 0x53: 1 for group M). Each model still offers only its own table, and the UT5 MAX catalogue still refuses the extra values; paired negative tests cover the values just outside.
- **Speed family:** maximum speed is a fixed per-model table (`ModelRegistry.maxSpeedTable`), shown as a normal Scooter row instead of the UT5 MAX top speed card; Volt never sweeps or probes speeds.
- **New keys:** a control with no UT5 MAX counterpart (group S's `ambient_light_mode`, `logo_light`, `daytime_light`) lives in `ScooterCommands.oemOnly()`, never in `catalog()`, so UT5 MAX screens, saved layouts and the golden tests do not see it; the UT5 MAX layer resolves it as excluded, and a profile without identity evidence does not show it.
- `tests/OemControlsTest.java` holds the member/non-member tables, firmware edges, region-independent domains and the official Top speed and kick-start region rules (`officialSpeedRules`), truncated 0x70 fixtures and frozen frame hex for each mapped family.
- `android-tests/CompatibilityGatingValidation.java` checks the UI gates with the developer simulator models (UT5 MAX, mapped 24431, unknown 39991) on a disposable emulator.
- `android-tests/CompatControlsValidation.java` (`run-compat-gating.py --mode controls`) drives synthetic advertised IDs and a memory-only simulated serial region (`BleController.setSimulationRegion`; never applied to the UT5 MAX simulator) through the mapped families: rows and captions, choice and confirm sheets, equal choices across regions (except Top speed and kick-start's 0–2 km/h) and unchanged firmware refusals, the Lights page and Quick access tiles. It sends nothing over Bluetooth.
- `android-tests/ScooterMenuEditValidation.java` (`run-compat-gating.py --mode menu-edit`) drives the Scooter tab's menu editor on the UT5 MAX simulator: the header pencil, a section's Move up action, fold while a grip is held, the eye with its Undo, Done and the live page's order and hidden row, rotation and an Activity rebuild while editing, Reset with Undo, Add back and Back. The editor sends no scooter command. The editor was removed in Hub layout part 3 (#383), so this validation now fails; it is left for the maintainers to retire.
  After Done it also checks the live page's edges (PR 3): the "1 setting hidden · Edit" footer under the whole page (not on Lights, during a search or while editing) opens the editor, and search finds the hidden row and a hidden section's rows with a **Hidden** caption.

## Reports and local data

For a bug report, include app version, phone model, Android version, the relevant screen, expected behavior, and the minimal exception or steps needed to reproduce it. Remove owner identities, serials, addresses, account credentials, and unrelated phone data before attaching logs. Full Android bug reports and ride journals contain private data and are not source files.

Keep signing keys, passwords, owner setup/backup files, captures, local paths, and recordings outside Git. `.gitignore` covers common filenames, but review staged files before pushing. The optional `local-build.env` is a local shell configuration file, not a shared project requirement.

OEM decompiled files, phone reports, private recordings, owner setup files and
release signing keys are excluded from distributed source. Use synthetic fixtures
when contributing protocol, recording or recovery tests.

Historical verification is in `VERIFICATION.txt`. Release changes are listed in `CHANGELOG.md`; do not describe a feature as hardware-tested merely because it compiles or receives a Bluetooth acknowledgment.

## Phone sensor changes

Keep phone callbacks out of `RideStore.sample()`: a phone-only frame would break
paired electrical coverage. `PhoneRideRecorder` owns background callbacks and
batched writes; `PhoneRideStore` analyzes its sidecar journals. Preserve source
monotonic timestamps, uncertainty, unavailable readings, and segment boundaries.
Don't use accelerometer integration as route distance. Never add a map request to
the Bluetooth or recording path. Map tiles must remain independent of recording.
An absent `map_basemap_enabled` preference means false; preserve existing explicit
values. The Basemap switches below inline/full-screen maps and in Map settings
share this preference. A first new enable must explain provider access to the
IP address and viewed area, then wait for Enable basemap or Cancel before loading tiles.
Cancel leaves tiles off; acknowledgement persists only after approval. Full app
backup/restore includes the choice and acknowledgement. Changing provider alone
must not enable tiles, and refreshing a view must not count as consent.
OpenStreetMap is the default provider. The explanation names the provider, and
approval saves it with the acknowledgement, so a later default change never moves
an approved basemap. Settings with no provider saved but the basemap on or
explained were approved under the old USGS default and keep USGS (`MapDefault`);
explicit choices never change. A ride map follows a provider change while open,
tiles and attribution together.

`PhoneRideValidation` and `UiOrganizationValidation` are fresh-emulator-only. They
refuse owner phones and existing app data; synthetic routes are test fixtures, not
physical sensor validation. Grant test permissions externally only on the emulator.
Maintainers only: an in-place update of an installed release needs the release
signing key, which is never shared.

## Dashboard and connected history

`DashboardValidation` exercises the actual widget palette, edit controls, cached
readings, and saved layout. Run it on a fresh emulator at narrow, compact, and
expanded widths. Besides its default mode it has `-e mode layout-slots`,
`home-widgets` and `offline-lock` (sweep steps `dashboard`,
`dashboard-layout-slots`, `dashboard-home-widgets` and
`dashboard-offline-lock-<width>`). `HistoryValidation` uses isolated synthetic databases to check
profile separation, retention, query bounds, and storage pressure. Neither runner
belongs on an owner's phone; both reject physical devices.

Keep cached values separate from fresh authenticated state. History must not
enable scooter controls, extend the background grace, start phone sensors, or
change ride-energy integration. Preserve gaps between connections and do not
store owner identity or PIN traffic. Dashboard placement is per scooter,
compact/expanded window class and column count. The window class follows the
navigation breakpoint (`Ui.wideLayout()`: the window width over the text
scale, Text size times the system font scale, reaches 650 dp), independently
of the selected columns. Capture it when constructing the dashboard so a fold
cancels any in-flight gesture into its original layout. Legacy placements are
deep-copied into both window classes before editing; widget settings and membership
remain shared. New/reset dashboards prefer five columns and seed explicit supported-grid
rectangles from `DefaultDashboard`; never overwrite saved custom or empty dashboards.
Adaptive keeps its existing one- through four-column breakpoints; explicit
five/six-column choices are available only when the measured width permits.
Derive missing finer-grid positions from an available saved arrangement,
leaving its source untouched. Enforce each widget's minimum
width at the current measured width and combined app/system text scale. Temporary
window or font changes must not persist expanded rectangles until an actual edit
is committed. The grid, resize corners and accessible size dialog must use the same limits.
Widget scale values are stored in canonical units and converted for display.

Dashboard slots keep both window classes, all one- through six-column layouts
and widget options together per scooter. The optional `expandedLayouts` field
uses the same bounded rectangle schema as compact `layouts`; old files without
it seed an independent copy when read. Continue accepting existing slots with
only one- through four-column layouts and without the optional showDisconnect
field (default true).
Editing or resetting the live dashboard must never mutate a saved slot. Validate
a snapshot before restoring it, and preserve intentionally empty dashboards.
`DashboardSlotsValidation` checks persistence, isolation and malformed snapshots
in an isolated emulator fixture. `DashboardValidation` with `-e mode layout-slots`
checks the real Save/Replace/Restore/default-reset dialogs, cancellation,
recreation, and large-text/light-theme actions on a fresh emulator. Both runners
refuse physical phones. Keep screenshot and width details in VERIFICATION.txt.

`ResponsiveDashboardValidation` checks the rendered default dashboard, control and
text bounds, and flat/half-open OEM fold callbacks. Its connected lock fixture only
changes views; it never puts Bluetooth into READY or sends a command. The companion
`android-tests/run-responsive.py` installs both APKs and clears app data on an
explicitly selected disposable emulator, then records screenshots and results at
multiple widths and text sizes. It refuses physical phones. Build both APKs with
the same key, start a disposable emulator, and pass `--adb`, `--serial`, `--app`,
`--runner`, and `--out`; use `--help` for individual cases. This is layout validation,
not a substitute for Samsung hardware or live Bluetooth testing.

Focused cases include `--case 344x882:1:editor` and
`--case 725x604:1:editor` for editor actions, plus `--case 360x800:1.4:gauge`
for the Gauge with larger app text. Gauge checks draw live, dual-unit, unknown,
stale and long negative readings at minimum/full widths and short/tall heights;
the number/unit block must remain inside the ring and range labels below its
endpoints with padding. Keep its visible and spoken Last seen state.

The editor uses dedicated top-left move grips and bottom-right resize corners,
with tap-accessible Move/Size dialogs. The grip, options gear, copy and red trash
actions are icon-only, matching centered squares. At least 144 dp of available
width uses one row; narrower cards use two columns or one column. Short cards
and the narrowest Quick access cards use Move and an Options menu that retains
Edit, Duplicate and Remove without overlapping Resize. Targets are up to 52 dp
on one row and up to 48 dp when stacked; the resize corner remains 48 dp.
Keep accessibility names and glyph centers correct at every size. Options
have one fixed ✓ Save footer and retain the top-right X; edits are not a new
transactional cancel/rollback flow. Preserve the one-lock-widget restriction and
32-widget limit when duplicating. The connected lock's Disconnect action defaults
on and may be hidden per widget; Connect/Cancel must remain available.

Undo/Redo are curved arrows in one rounded group immediately left of Done, with
the header action row below the scooter name on compact editing screens. History
holds up to 40 committed structural grid changes in memory: membership/order and
per-width geometry, retaining current widget settings. New real grid edits clear
Redo; no-op, cancelled gestures and settings-only edits must not. Done/Back,
leaving Home, screen recreation, default reset and slot restoration end the
current history. Never use layout Undo to affect scooter controls or recorded data.

Edit-mode transitions must change edit state synchronously, then animate only the
presentation. The 210 ms grid/header slide and control pop use at most one million
snapshot pixels for disappearing controls; recycle them on finish or interruption.
Consume grid taps spanning the transition so disappearing editor icons cannot hit
newly enabled scooter actions. Preserve disabled-control alpha. One shared clock
wiggles only visible cards by at most 0.2 degrees, without changing hit targets;
pause the gesture card and stop on focus/visibility loss, detach, close or disabled
motion. Preference selectors use a 190 ms slide and coalesced 210 ms theme/unit
rebuild so the animation can finish. Keep their labels readable and targets >=48 dp.

Developer simulation is an explicit runtime-only UI preview, not a BLE test mode.
Guard the service recording/history boundary AND the transport mutation boundary.
Close/cancel the real connection before sampling; never synthesize a GATT, owner
identity or raw BLE packets. Keep fake readings, settings and chart series in memory,
mark the UI and diagnostics as simulated, and clear them when simulation ends.
Do not persist them to profiles, control evidence, last-seen cache, telemetry/ride
history or capacity analysis. Background connection preferences must not keep the
simulation running. Preserve the real last-seen data and require an inactive ride
before entry. Native validation should compare storage and transport state, not
merely report a hard-coded mutation count.

The removal effect is decorative and must not delay deletion or neighbor reflow.
It uses a bounded snapshot (at most 2 megapixels and 2048 px per side) for a 200 ms
shrink/fade and soft puffs. Cancel it on another action or dashboard close, remove
the overlay on detach/completion and recycle the snapshot. Disabled animation or
snapshot failure must leave immediate removal functional.

Gesture previews must use an untouched start snapshot, pin the full requested
rectangle, compact neighbors without overlap, save only on release and restore
on cancellation or teardown. Preserve scrolling outside the handles. Animate
position and size from the currently rendered rectangle when a gesture interrupts
an earlier transition, then reset transforms on completion or cancellation.
Current timings are 140 ms for the active card, 210 ms for neighbors, 150 ms for
the destination outline and 180 ms for release. Respect system animation
settings. Lock motion is visual only: value and accessibility state must retain
the last confirmed report. Navigation arrival effects must not replay on telemetry
refreshes. Check direct drag, long press, corner resize, cancellation, theme
contrast and interrupted animations at each tested width. Executed results
belong in `VERIFICATION.txt`, not inferred from an implementation change.

Edit controls float over unchanged card content. Compare card, title, footer and
body-text geometry before/after entering Edit. The responsive matrix covers every
widget at minimum/default heights and narrow/default/full widths, including 140%
text. Keep scooter readings stacked vertically beside the photo: newly added
cards start with odometer and drive mode, while the reset dashboard also includes
estimated range. Preserve scrolling from within longer custom content. Keep saved
positions/sizes and genuinely custom reading lists; the 0.6.3 migration changes
only the former default lists. Fit text from current available space rather than
a previously measured text height, so shrinking and growing a card is repeatable.
Balance short content within its viewport without forcing overflowing content
outside the reachable scroll area.

Your scooter's selected readings and local rename are presentation features.
Keep the owner-observed WALK/ECO/SPORT labels restricted to PID 2538 and the
confirmed mode codes (11/2/3). Unknown codes/models and raw diagnostics/exports
must retain their original values. Reported charge/throughput counters are not
measured capacity or current-ride consumption.

The battery cell should use the space remaining after its readings, preserving
readable percentage/range text and balanced visual placement. Check readings
in one row where they fit, then wrapping at narrow widths or large text. Preserve charging context switches, missing/stale semantics and spoken
labels for icon-only readings. Scooter Overview and Reading tiles omit visual age
footers even while connecting; keep cached values muted and their timestamps in
accessible descriptions. The disconnected header still shows Last seen, and
classic data widgets retain their existing age footers.

Connection status remains concise; errors stay reachable by tapping it. Reuse the
profile's verified lastConnected timestamp rather than launch/attempt times.
DisplayTime applies time_format (system/12h/24h) to user-facing clocks. Keep
unknown timestamps empty and include dates for older connections. Dashboard
validation covers the taller centered, slightly inset slider, its full-width
narrow-card fallback, connection states and time formats using isolated synthetic
state; it must never issue a scooter command.

`SearchFocusValidation` uses a real visible software keyboard on a fresh emulator
to verify outside taps, scrolling, system Back, query preservation, and ordinary
form focus in both themes. Enable the emulator software keyboard; this runner
refuses physical phones and existing app data. Search bindings must pass touches
through, release listeners with the field, and leave ordinary form editors alone.

`BackupUiValidation` and the `FullBackupJourneyValidation` restore journey also
use a real software keyboard. They tap and type into each backup password field,
verify the entire editor stays above the keyboard, switch between new/repeated
passwords, and reopen the keyboard. `run-backups.py` enables the emulator software
keyboard for these checks and restores that setting afterward. The backup UI
fixture checks the recommended Chrome/USB default on first setup; `MyScootersValidation`
checks the same default when adding a scooter. The chooser also preserves an
unfinished default ADB route from older versions that had not saved a path key.
The backup consent and encryption flow remains the same.

Quick access uses stable catalog IDs (QuickAccessActions) and a normalized 2–8
item list in each widget. Never accept arbitrary commands or navigation routes.
The dashboard invokes existing MainActivity editors and BleController gates;
experimental/security shortcuts must keep their explanatory approval flow: they
open their page (Service › Experimental controls, Security; HubRoutes), where the
same Lab try sheet and PIN forms apply. Only the quick-control groups keep a
quick-access sheet (HubRoutes.sheetFor); whole settings pages open as pages.
QuickAccessGrid measures real label heights; do not clip them or shrink targets
below 48 dp to hide a scrollbar. The widget options no longer duplicate Size.
WidgetReveal is bounded and drawing-only: cancel on touch, render, focus loss,
reflow or close, and never move a card that is already visible.
The quick native mode exercises configuration, safe simulation actions, sheets,
responsive content and the new-widget cue.
