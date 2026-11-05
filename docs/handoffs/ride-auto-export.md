# Handoff: automatic ride export

## What's being asked

Add a user option to automatically export ride recording data when a ride recording ends. The owner approved the idea, with this condition: it "needs to respect our new language system (any architectural changes, etc)". Everything below marked "recommendation" is advice, not a requirement.

Read [Localization for contributors](../localization-for-contributors.md) first. **The catalog** means the strings in `res/values/strings_<area>.xml`. A bare class name means `src/com/jjricks/volt/<Class>.java`.

## Language requirements for this feature

### Every new visible string is in the catalog

| Text | Where | Notes |
|---|---|---|
| Setting row title and sub-label | `res/values/strings_more.xml` | `preferenceToggle(box, icon, T.title(), T.sub(), key, fallback)`. It already speaks the title for the switch, so no separate spoken string is needed |
| Notification title and text | `res/values/strings_service.xml`, Rides section | Render with `T` at each post |
| Toasts and failure messages | Same | See [no screen open](#background-text-with-no-screen-open) |
| Any folder choice or dialog text | `strings_more.xml` or `res/values/strings_rides.xml` | Depends on the destination decision |
| Count of rides or files | `<plurals>` | Quantity first, `T.x(n, n)`, with `%1$d` |

Don't take the `--write-baselines` route for shown text. The lint permits it on some PRs, but the owner's condition rules it out. A log line or other text that is never shown does take that route, in the ordinary PR.

### What stays English

- **File content.** Reuse the existing writers. English that ships in files today: CSV headers and event names, details like `Bluetooth disconnected` and `Recorder process restarted`, JSON keys, `Summary.energyInterpretation` and `efficiencyStatus` sentences in analysis JSON, and GPX `<name>Ride <id>`. These look like interface text. Don't convert them.
- **File names and MIME types.** Today's names are built from the ride id: `ride-<first 8 of id>-sensors.csv`, `-analysis.json`, `-route.csv`, `.gpx`, `-phone.csv`, `-motion.csv`, `-combined.json`. A ride's label may be user text or a date in the language at recording time, so keep it out. Recommendation: stay id-based.
- **Logs and stored status or failure values.** Store an enum or id. Show it through an accessor.
- **Stored preference values.** A boolean, or a lower-case id if it needs more values.

### Background text with no screen open

`T` works without a `Context` or Activity, in the service and on any thread. Never use `getString`.

- A failure on a worker thread hands back an enum or `Msg`. Render on the main thread when posting.
- Toast only when `visible`, otherwise post a notification. `ScooterService.autoStopRide` and `showAutoStopNotice` are the model.
- Use the existing `scooter` channel and `post(id, ...)`, which is paced by `NotificationPacer`. A new channel must be added to `applyLocale`.
- A new notice needs a new id. `AUTO_STOP_NOTICE` and its neighbours use 72 to 77. Declare yours on a new line, because their line also holds a string literal and editing it fails `around text` in a catalog PR.
- A ride's name in a notice is a `%1$s`, with a separate string for an unnamed ride. `serviceRideBluetoothLost` and `serviceRideBluetoothLostLabel` show the pattern.
- Notifications can be denied. Then a hidden rider never hears about a failure, unless it is also stored as an id and shown in the app. See [Decisions](#decisions-to-settle-with-the-owner-first).

### Dates and numbers

- **Shown:** `Fmt`, `DisplayTime.clock`, `DisplayTime.dateTime`, plurals. Nothing in CI catches a default-locale `String.format`, so check this yourself.
- **In the file:** never `Fmt`, `DisplayTime` or `Locale.getDefault()`. `DisplayTime.rideName` goes through the display locale, so don't use it in a name or file. Existing writers already use fixed locales.

### No comparing shown text

Decide from booleans, enums and constants such as `RideStore.STOP_AUTO_PARKED`. Don't decide from a label, a rendered message or `recordingError`. CI only catches direct calls on a `T` call or with one as the argument, so a rendered local variable compared with a plain variable passes and is still wrong.

### Safety check: how the existing export text works now

The ride export text has moved, and some of it has not:

| Text | State on main |
|---|---|
| Export buttons, their spoken descriptions, phone-export dialog options | Catalog, `res/values/strings_rides.xml` (`rides_export_*`) |
| `rides_toast_export_failed`, `rides_toast_export_csv_failed` | Catalog |
| File-name parts and MIME types in `RidesScreen.exportCsv` and `exportPhone` | Plain code literals, correctly |
| Save pipeline toasts in `MainActivity.prepareSave` and `maybeLaunchSave`, such as `Could not prepare the export.` | Still hard-coded on main, being moved on another branch. Don't copy them |

Before relying on any of it, check these:

- `RideStore` has no catalog references. `PhoneRideStore` has only `statusText`, which its file writers don't use. Keep it that way.
- Goldens cover `ride-journal.csv`, `phone-track.csv` and `phone-track.gpx` under several locales. **`Summary.toJson` and any new export class aren't covered.**
- Recommendation: add a test that installs a throwing text source with `T.install` and runs the export path, as `tests/l10n/ReportsStayEnglishTest.java` does. Adding to the existing lists would be a tooling change.

### Expect two pull requests

Any `T.` or `M.` reference or catalog string makes the PR kind `batch`. That kind refuses file-name parts and MIME types such as `"text/csv"` and `"ride-"`, edits to `tests/run.sh` and edits to existing test statements. See [Pull request kinds](../localization-for-contributors.md#pull-request-kinds).

A trial against main when this was written passed the real `ci_gates.py` with this split. It's a recommendation, and the owner should confirm it:

| PR | Holds |
|---|---|
| 1, ordinary | A new pure class with decisions, file names and MIME types; the headless writer; preference constant and `BackupRegistry` line; new and edited tests; native test wiring. No `T.` or `M.`, not even a call to an existing key |
| 2, catalog, after 1 merges | Strings; the setting row; notices and toasts; the hook calls that use them |

The trial's PR 2 also added the row to `MoreScreen.preferences()` and a new statement in `ScooterService.stopRide`, and `tests/run.sh` passed, including `MoreTreeTest` and `ServiceConsumersTest`. It used stub code with no working export, and ran neither `build.sh` nor an emulator, so don't read it as proof of the feature.

## Where it touches the code

### The hook

`ScooterService.stopRide(String reason)` is private and documented as "the one stop/save path". Fire only when it returns `true`. On `false` (storage full) the ride stays active and the rider sees Retry save.

| Ride end path | Caller | Screen in foreground? |
|---|---|---|
| Rides, End | `RidesScreen` to `service.stopRide()` | Usually, at that moment |
| Dashboard recorder widget | `DashboardScreen` to `service.stopRide()` | Usually, at that moment |
| Notification Stop | `ScooterService.onStartCommand`, `STOP_RECORDING` | **No, usually hidden** |
| Auto-stop, parked and Bluetooth lost | `ScooterService.autoStopRide` | **No guarantee** |
| Any of the above after process death | Ride revived by `RideStore`'s constructor, stopped later from the restored notification | **No** |

These do not end a ride: Bluetooth loss without the auto-stop rule, service death, process death and crash. The ride continues, then the eventual stop runs in a process with no memory of its earlier part. The roadmap also plans another automatic end path, which a hook in `stopRide` would inherit.

Three traps:

1. **Sensor files finish later.** When `rides.stop` returns, the scooter journal is complete, but `-phone.csv` and `-motion.csv` are not. `stopPhoneRecording` finishes them on the recorder's thread, then posts a completion to the main thread. An export that needs phone data must wait for that. The completion never runs if `phoneRecorder` was null at stop, and its body is skipped if the service is destroyed. There is no existing "ride fully saved" callback, so you need one with both cases.
2. **Nothing keeps the process alive after a hidden stop.** The wake lock is released and `applyWorkPolicy` can stop the service. `workAllowed` knows about `phoneSaving` but not an export. Add a keep-alive state the same way, and keep it separate from the idle timeout. Android also restricts foreground services started from the background; that's general platform knowledge, so verify it.
3. **Process death after the stop loses the export.** A stopped journal isn't revisited at startup, and no pending marker exists. The closest precedent is the `pending_documents` namespace, which belongs to `MainActivity`.

Don't edit a line in `stopRide` that holds a literal, such as `"Storage full · free space, then Retry save"`. In a catalog PR that fails with `around text`. Add a new statement on the `return true` path. That string and its raw toast are unconverted code, not a pattern.

`stopRide` is crammed onto a few long lines, so a new statement means breaking one. A trial passed `G1` doing that, away from the literal. `rides.activeId()` is null once `rides.stop` returns, so read the id into a local first, on a new line before the `try`.

### The existing export code

| Part | Where | Usable with no screen? |
|---|---|---|
| Sensor CSV | `RideStore.exportCsv(id, File)` | Yes |
| Analysis JSON | `RideStore.get(id).toJson()` | Yes |
| Route CSV, GPX, track JSON | `PhoneRideStore.loadSnapshot(id)`, `Track.toCsv`, `toGpx`, `toJson` | Yes |
| Raw phone and motion files | `PhoneRideStore.rawCsv(id)`, `motionCsv(id)` | Yes; may not exist |
| Save to a destination | `ScreenHost.saveFile` and `saveText`, `MainActivity.prepareSave` | **No** |
| Export buttons and dialog | `RidesScreen` details, `exportCsv`, `exportPhone` | No |

`prepareSave` stages the file, then opens `ACTION_CREATE_DOCUMENT` only while resumed. It has one global slot in `pending_documents`, and a pending entry blocks backup maintenance and a second export. So no existing path saves to a user-visible place without a prompt. The destination is a design decision.

`ScooterService.exportChargeRecordings` is the service-side precedent: a worker, a flag, a cache file and a main-thread completion. Its flag is checked by `dataMaintenanceUnavailable`, and a running automatic export needs a decision there too.

### The preference

| Step | Where |
|---|---|
| Row | `MoreScreen.preferences()` via `preferenceToggle`, or `MoreScreen.dataHistory()` (`recording` group) |
| Constant and read | Like `ScooterService.RIDE_AUTO_STOP`, on a new line. The fallback at each read must match the row's |
| Classification | `BackupRegistry` static block |
| Inventory | A row in `docs/app-storage-inventory.md`, in the same change |
| Pins to copy | `ride_auto_stop` in `tests/BackupRegistryTest.java`, `tests/BackupTestStore.java`, `tests/BackupPlanTest.java`, and `android-tests/RideAutoStopValidation.java`. Adding to `BackupTestStore` changes the `replaces N settings` counts pinned in `BackupPlanTest`, and editing those fails `G4` in a catalog PR, so make that pin in PR 1 |

Register a boolean with its own line. A trial passed `ci_gates.py` and `tests/run.sh` in a catalog PR this way, because the key is a lower-case id on a new line:

```java
add(app, "ride_auto_export", Boolean.class, PREFERENCES);
```

Don't add it to the long key list beside it. That changes a multi-word literal, which fails `G1` and `G8` in a catalog PR and needs `--write-baselines` in an ordinary one.

`BackupRegistryTest` fails with `preference key <key> is unclassified; assign it in BackupRegistry` when it sees an unregistered key. It can't see a key used only through `preferenceToggle`, so read the value by literal or `UPPER_CASE` constant somewhere in the service, and add a pin.

A stored document-tree URI, if chosen, is device-bound: register it with `exclude(...)`. Android permission grants don't travel in backups.

## Other repo rules this will hit

**Behavior to preserve** (CONTRIBUTING):

- Recording lifecycle and the idle background timeout remain separate. Export keep-alive must not blur them.
- Simulation can't contaminate recorded history.
- Existing recordings stay compatible.
- Layout changes preserve accessible controls.

**Statements that need rechecking** once the destination is chosen:

| File | Statement |
|---|---|
| `PRIVACY.md` | No "automatic ride upload"; "Exports only go to a destination you choose through the file picker or helper"; a destination may sync to a cloud service |
| `docs/recording-reference.md`, Ride exports | "CSV/JSON exports use Android's file picker; you choose the destination" |
| `docs/ride-backups.md` | Transfers request no broad storage permissions |
| `docs/app-storage-inventory.md` | Volt does not use external app storage |

Phone route, GPX, raw phone and combined exports can hold precise coordinates, and the scooter CSV holds no location.

**Docs to update:**

- `CHANGELOG.md`, Unreleased
- `docs/user-guide.md`, Phone recordings and maps
- `docs/recording-reference.md`, Recording integrity and Ride exports
- `FEATURES.txt`
- `PRIVACY.md`
- `docs/app-storage-inventory.md`
- `docs/app-data-backup.md`, only if a category's scope changes
- `docs/validation/ride-auto-stop.md` is a good model for a validation note

**Tests:**

- Keep decisions in a pure class, like `RideAutoStopPolicy`. `ScooterService` is an Android class, and the JVM tests can't run it.
- Add `tests/l10n/<Name>Test.java` plus a `tests/l10n/<x>.sources` listing it and the pure class. This avoids editing `tests/run.sh`.
- A new native validation class must be registered in `android-tests/AndroidManifest.xml` and get a step or an `EXCLUDED` reason in `android-tests/run-release-sweep.py`. No existing test exercises the real ride-export path end to end.
- Use synthetic data. Don't commit real recordings or routes.

## Decisions to settle with the owner first

CONTRIBUTING asks for an issue describing the intended behavior before a substantial feature. Open one covering these:

1. **Destination.** The code has no background-writable destination. Android facts here are general knowledge, so verify them:
   - A folder granted once through the picker (persisted URI). Works on API 26 to 36 with no permission, but can fail later.
   - Public Downloads via MediaStore. API 29+ only, and `minSdk` is 26.
   - App-specific external storage. Not user-friendly.
   - Stage the file and offer the picker at next app open. The picker can't appear at a hidden ride end.
2. **What gets exported.** Scooter CSV, analysis JSON, route CSV, GPX, raw phone and motion, combined JSON: one, several or a bundle? Precise location by default, given a folder may sync to a cloud? What about rides with no phone data?
3. **Failure and success.** Folder or grant gone, storage full, provider error: retry when, what does the rider see if notifications are denied, does a failed export stay pending across restarts, does the setting turn itself off? Does a success post a notice at all, and does it while the app is visible?
4. **Which rides.** Auto-ended, recovered and very short rides? Older rides retroactively? Per scooter or global? Default off is implied by "option"; confirm it.
5. **Naming.** Overwrite or never overwrite, including a repeated export? English id-based names only, or the rider's label as well?
6. **Backup and restore.** Does the setting travel when the folder grant can't? How does a restored setting with no folder behave? Does a running export block maintenance the way `chargeExporting` does?
7. **PR shape and timing.** Confirm the two-PR split, and whether to start now or wait for the work below to merge.

## What's moving under you

Other unmerged work is in progress. Merge `main` often, and re-run `ci_gates.py` after each merge, since your kind and diff can shift.

| File | What's moving | Advice |
|---|---|---|
| `MainActivity.java` | Heavy rewrite, including the save pipeline toasts | Avoid until merged |
| `MoreScreen.java` | The battery row right below `ride_auto_stop`; other regions too | A new row there will likely conflict. Recommendation: `dataHistory()` is farther away |
| `res/values/strings_more.xml` | Keys added around that row | Add yours elsewhere in the file |
| `RidesScreen.java`, `res/values/strings_rides.xml` | Large changes, not on the export lines | Avoid ride-details additions if you can |
| `DashboardScreen.java` | Follow-ups | No need to touch it |
| `l10n/baseline/*.txt` | Several files shrink | Conflicts are likely. Take theirs, run `--write-baselines` where the kind allows |
| `ScooterService.java` | A few lines, in charge schedule test text | Low risk |
| `RideStore.java`, `BackupRegistry.java`, `res/values/strings_service.xml`, `PRIVACY.md` | Nothing | Low risk |
| `PhoneRideStore.java` | Comments only | Low risk |
| `tools/l10n/`, `l10n/README.md` | Tool changes. `Row.value(...)` becomes a lint sink, so English passed to it gets flagged. `prove-english.sh` also changes for ordinary PRs | Don't edit them |

Recommendation: put the work in a new class such as `RideAutoExport`, with thin call sites in `ScooterService` and `MoreScreen`. It avoids most of these conflicts, keeps the logic testable and makes the second PR small.

## Definition of done

Run the first list in [Check your work](../localization-for-contributors.md#check-your-work) on each PR, committed, with `origin/main` fetched. It needs a JDK 17+ and Python, and no Android SDK. All must pass. On the catalog PR, `ci_gates.py` should print `batch change, 0 gate(s) failed`, with `0 new ledger row(s)`. Read its `warning:` lines: each new statement and id is listed there for the reviewer, and no id may appear on screen.

With the Android SDK, also run these. CI can't:

```sh
bash build.sh
bash android-tests/build.sh
VOLT_PSEUDO_LOCALES=1 bash build.sh
```

`prove-english.sh` isn't required; see the [caveat](../localization-for-contributors.md#the-prove-englishsh-caveat).

With an emulator, per CONTRIBUTING, check lifecycle and UI, including narrow screens and large text, with a hidden stop from the notification. Don't run parked validation modes on someone else's scooter.

**State in the PR:**

- The kind `ci_gates.py` printed.
- Which language checks you ran and which you didn't, including `build.sh`, the pseudo-locale build and any emulator or physical checks.
- Any baseline or golden change, and why. There should be none for shown text.
- That the export path was tested with a non-English or throwing text source, or that it wasn't.
- What still needs a physical phone and scooter.

**Review:** an independent reviewer on our side runs the emulator text census and the English-unchanged comparison. You don't need those tools.

Delete this file in the pull request that builds the feature.
