# Auto-save rides

Unreleased, after Volt 1.11.1. Decisions: [issue #205](https://github.com/JJRicks/volt/issues/205).
Behaviour: [Auto-save rides](../recording-reference.md#auto-save-rides).

## Design

| Part | Where | Notes |
|---|---|---|
| Decisions, names, waiting list, row state, one pass | `RideAutoExport` | Pure Java, no catalog. The file is built by the sheet's own writer, `RideExportFiles.payload(EVERYTHING_JSON, …)`, and compared byte for byte with it |
| Folder (Storage Access Framework) and stored choice | `RideAutoExportFolder` | Persisted read/write tree grant; write `.<name>.partial`, then rename; never a `(1)` copy |
| Worker, wake lock | `RideAutoExportRunner` | One pass at a time; hands back a `RideAutoExport.Problem` id on the main thread |
| Folder picker | `RideAutoExportPickerActivity` | A view-less activity around `ACTION_OPEN_DOCUMENT_TREE`, like `BackupActivity`'s pickers; `MainActivity` is untouched |
| Hook, keep-alive, retry, notice | `ScooterService` | `stopRide` queues on its `return true` path; `rideFullySaved` runs after the phone journal finishes, or at once with no recorder; `workAllowed` keeps the service while a pass runs, separately from the idle deadline; retry at service start, at the next ride end and after a folder is chosen; `dataMaintenanceUnavailable` refuses backup and restore while a pass runs, and no pass starts during one |
| Rows | `MoreScreen.dataHistory()`, own **Ride auto-save** group between Save readings history and Local storage | One `toggleRow` whose sub-label names the sheet's file, and a **Folder** row whose sub-label comes from `RowState` (folder, failure, or greyed with no folder while off); a note that a folder change applies to later rides |

Stored values are ids only: the switch and the retired Route CSV switch, read as on (backed up), the tree URI, the waiting
ride ids and `folder`/`save` (excluded). Every shown sentence is in
`strings_more.xml` or `strings_service.xml` and rendered on the main thread when
shown or posted.

## Core tests

`tests/l10n/RideAutoExportTest.java` (via `ride-auto-export.sources`), with an
in-memory folder that names clashes like Android's local storage provider:

- the name equals the sheet's Everything name; the temporary name is hidden and ends in `.partial`;
- write-then-rename, an unchanged file left alone, a changed (longer or shorter) one replaced, a stale
  temporary removed, no `(1)` copy, the old switches' files left alone;
- either old switch on means on, in the settings and in a restored backup;
- every saved ride gets its file, a ride gone from Volt leaves the list, the cache copy is removed;
- failures: no folder, unreachable folder, storage full, refused or suffixed rename,
  an unreadable journal or existing file; retry on the next pass;
- the settings row state for every stored combination;
- real `RideStore` and `PhoneRideStore` files: bytes equal `RideExportFiles.payload` for
  Everything, with and without a phone recording, and a new Volt version replaces the file;
- the whole export path with a throwing `T.install` text source and a German default
  locale gives the same file.

`BackupRegistryTest`, `BackupPlanTest` and `BackupPlanLinesTest` pin the switches
travelling (an older backup with only Route CSV on restores on) and the folder, list and failure staying behind.

## Emulator check

`android-tests/RideAutoExportValidation`, run by `run-ride-auto-export.py` (disposable
emulator only; Bluetooth off; synthetic recordings as in `PhoneRideValidation`; it clears
Volt's data and deletes `Download/VoltRides` and `Download/ride-*`):

```sh
python3 android-tests/run-ride-auto-export.py --adb "$ADB" --serial emulator-5554 \
  --app dist/volt.apk --runner .local/build/parked-validation/parked-validation.apk --out <dir>
```

It drives the real system pickers through UiAutomation and checks:

1. Off on a fresh install; a cancelled pick stores nothing; the picker activity
   creates and keeps `Download/VoltRides` with a persisted grant, turns the switch on and
   writes the retired Route CSV switch off.
2. Stop from the recording notification with Volt hidden and the screen off: copied
   while the screen stays off; sampled every 5 ms, work stays allowed, the service stays
   in the foreground and backup is refused while copying; the hidden idle service stops
   afterwards.
3. One Everything file for a GPS ride, a motion-only ride and a ride with no phone
   samples, each byte for byte `RideExportFiles.payload`; the manual Everything in one
   JSON saved through the real sheet and save picker has the same SHA-256.
4. Re-export leaves the file alone. After the update: only the old Route CSV switch on
   (then only ride JSON) with a ride waiting saves it as Everything and leaves the old
   switches' `-route.csv` and `-combined.json` files as they were.
5. No pass while data maintenance runs; retried after.
6. Folder deleted (retried at the next ride end, and at app start after Volt closed),
   grant revoked (retried after choosing the folder again), restored setting with no
   folder, turned off (with the old switch on too) releasing the grant and the list.
7. Rows: the **Ride auto-save** heading and group between Save readings history and
   Local storage, the switch then Folder, with the note below; the old switches gone;
   the switch names the sheet's file, 48 dp, speaks its title; while off the Folder row
   is greyed out, says `No folder chosen yet` and opens nothing. The switch opens the
   picker (cancel keeps it off); the Folder row shows `Saving to Download/VoltRides`,
   and tapping it opens the picker at that folder and leaves the switch on.
8. The stop hook alone: notification Stop while hidden, the parked auto-stop
   (`AUTO_PARKED_DISCONNECT`) and a ride revived by a new service and stopped later.
9. Failure: the line on the Folder row (the switch keeps its caption), one notification on the `scooter` channel
   while hidden with catalog title and text, none for a second failure of the same kind,
   none while visible, then one when that same failure happens on a hidden ride and none
   on the next, with the service's posted kind read at each step; the line clears after
   the next ride end. The emulator can't fail a write, so a kind change while visible
   (folder, then a write failure, then folder again) is covered by
   `RideAutoExportNoticeTest`'s pass sequences, on the same functions the service calls.
10. Restored without a folder, from an older backup with only Route CSV on: the switch
    shows on, `Choose a folder to start saving`, the ride waits, and tapping the Folder
    row and choosing the folder copies it as Everything.
11. About 327 dp wide with Volt's text at 130%: heading, title, caption, folder line,
    note and switch fully shown, the title and caption never under the switch.
12. Off again from the switch: grant released, list cleared, and the Folder row
    greyed out in the same place and height (the layout does not jump).

Result on 2026-11-06 for the single switch, Medium_Phone_API_36.1 (Android 16, 1080×2340): steps 1 to 8 and 10 to 12
PASS, 489 checks, with step 9 skipped. The manual and automatic Everything files had the same SHA-256, and the old
switches' files were unchanged. Step 9 fails the same way on main (b0bfb148): after its `cmd uimode` screenshots,
`screenOnAndShow` times out on "Volt in front again", so the step is unchanged and still to be fixed.

Earlier result on 2026-11-05 (two switches), volt_a (Android 16, 1080×2340): PASS, 588 checks (the prep
branch's part alone: PASS, 264 checks). Screenshots in dark and light for each row
state, the picker from a switch and from the Folder row, and the notification. The
en-XA pseudo-locale build (`LocaleValidation`, 344 dp) shows the Ride auto-save group
with no fit, glued or unextracted finding on its text.

Not covered on an emulator: storage full and a cloud provider without rename (core tests
only), a real ride with a scooter, and manufacturer battery policies after a hidden stop.
