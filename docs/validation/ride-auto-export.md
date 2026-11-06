# Auto-save rides

Unreleased, after Volt 1.11.1. Decisions: [issue #205](https://github.com/JJRicks/volt/issues/205).
Behaviour: [Auto-save rides](../recording-reference.md#auto-save-rides).

## Design

| Part | Where | Notes |
|---|---|---|
| Decisions, names, waiting list, row state, one pass | `RideAutoExport` | Pure Java, no catalog. Files are built by the manual exports' writers: `Track.toCsv()`, `RideAutoExport.combinedJson` (which `RidesScreen.exportPhone` now calls too) and `Summary.toJson()` |
| Folder (Storage Access Framework) and stored choice | `RideAutoExportFolder` | Persisted read/write tree grant; write `.<name>.partial`, then rename; never a `(1)` copy |
| Worker, wake lock | `RideAutoExportRunner` | One pass at a time; hands back a `RideAutoExport.Problem` id on the main thread |
| Folder picker | `RideAutoExportPickerActivity` | A view-less activity around `ACTION_OPEN_DOCUMENT_TREE`, like `BackupActivity`'s pickers; `MainActivity` is untouched |
| Hook, keep-alive, retry, notice | `ScooterService` | `stopRide` queues on its `return true` path; `rideFullySaved` runs after the phone journal finishes, or at once with no recorder; `workAllowed` keeps the service while a pass runs, separately from the idle deadline; retry at service start, at the next ride end and after a folder is chosen; `dataMaintenanceUnavailable` refuses backup and restore while a pass runs, and no pass starts during one |
| Rows | `MoreScreen.dataHistory()`, recording group | Two `toggleRow`s; sub-label from `RowState` |

Stored values are ids only: two booleans (backed up), the tree URI, the waiting
ride ids and `folder`/`save` (excluded). Every shown sentence is in
`strings_more.xml` or `strings_service.xml` and rendered on the main thread when
shown or posted.

## Core tests

`tests/l10n/RideAutoExportTest.java` (via `ride-auto-export.sources`), with an
in-memory folder that names clashes like Android's local storage provider:

- names equal the manual exports'; the temporary name is hidden and ends in `.partial`;
- write-then-rename, an unchanged file left alone, a changed one replaced, a stale
  temporary removed, no `(1)` copy;
- both types: a ride leaves the list only when every switched-on file is written; a
  retry after a JSON failure writes only the JSON;
- rides without GPS fixes get no Route CSV; rides without a phone recording get Export JSON;
- failures: no folder, unreachable folder, storage full, refused or suffixed rename,
  an unreadable journal or existing file; retry on the next pass;
- the settings row state for every stored combination;
- real `RideStore` and `PhoneRideStore` files: bytes equal `Track.toCsv()`, the old inline
  All (JSON) expression and `Summary.toJson()`;
- the whole export path with a throwing `T.install` text source and a German default
  locale gives the same files.

`BackupRegistryTest`, `BackupPlanTest` and `BackupPlanLinesTest` pin the two booleans
travelling and the folder, list and failure staying behind.

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
   creates and keeps `Download/VoltRides` with a persisted grant and turns on only the
   type that asked.
2. Stop from the recording notification with Volt hidden and the screen off: copied
   while the screen stays off; sampled every 5 ms, work stays allowed, the service stays
   in the foreground and backup is refused while copying; the hidden idle service stops
   afterwards.
3. Both types: Route CSV plus All (JSON) for a GPS ride, All (JSON) only for a
   motion-only ride, Export JSON for a ride with no phone samples; the manual Route CSV
   and All (JSON) saved through the real save picker have the same SHA-256.
4. Re-export leaves files alone; a ride that ended with JSON off gains only its JSON.
5. No pass while data maintenance runs; retried after.
6. Folder deleted (retried at the next ride end, and at app start after Volt closed),
   grant revoked (retried after choosing the folder again), restored setting with no
   folder, one type off keeping the folder, both off releasing the grant and the list.
7. Rows: off captions, below Save readings history, 48 dp switches, each switch speaks
   its title; the first switch opens the picker (cancel keeps it off), the second needs
   none; both show `Saving to Download/VoltRides`.
8. The stop hook alone: notification Stop while hidden, the parked auto-stop
   (`AUTO_PARKED_DISCONNECT`) and a ride revived by a new service and stopped later.
9. Failure: the row's line on both rows, one notification on the `scooter` channel
   while hidden with catalog title and text, none for a second failure of the same kind,
   none while visible; the line clears after the next ride end.
10. Restored without a folder: `Choose a folder to start saving`, the ride waits, and
    tapping the row and choosing the folder copies it.
11. About 327 dp wide with Volt's text at 130%: titles, folder line and switches fully
    shown, the title never under the switch.

Result on 2026-11-05, volt_a (Android 16, 1080×2340): PASS, 514 checks (the prep
branch's part alone: PASS, 240 checks). Screenshots in dark and light for each row
state, the picker and the notification. The en-XA pseudo-locale build was checked by
hand on the same emulator.

Not covered on an emulator: storage full and a cloud provider without rename (core tests
only), a real ride with a scooter, and manufacturer battery policies after a hidden stop.
