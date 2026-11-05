# B17 follow-up: default scooter names that still show in English

`Profile.displayName()` translates only `ScooterProfiles.DEFAULT_NAME` ("Scooter"), and only while
`nameDefault` is set. F4 uses it on the My scooters card, the menu index, the clear history confirmation, and
setup's connecting, done and import summary sentences. Everything below still shows English in a translated
build. Stored names stay English; only what is shown changes. Delete an entry when it is done.

## "Scooter" where `displayName()` is not used yet

| Site at 811d1c9 | What | Owner |
|---|---|---|
| `DashboardScreen.java:753` (`:755` on B13) | Profile name above the dashboard | follow-up after B13 merges |
| `DashboardScreen.java:5103` (`:5197` on B13) | "Scooter name" settings row value | follow-up after B13 merges |
| `MainActivity.java:889`, `:925`, `:931`, `:1551`, `:3284` | Status lines, title, "Scooter name" value, slot replace message | B19 |
| `MainActivity.java:1215` (`scanOption`) | Nearby scan row for a saved scooter | B19 |
| `BleController.java:589` | "This is %1$s. Select it in My scooters" failure | follow-up; the argument also reaches `Msg.english()` and so the connection log, which must keep the stored name |
| `AppDataBackup.java:71` (`localScooters`) | Destination spinner on the Backup screen's Scooters page | follow-up; `BackupProfiles.Profile` does not read `name_default`, so it needs that field too |
| `BackupArchive.java:457`, `:583`-`:586`, `BackupActivity.java:387` | Destination names in the Review line (`M.backupEnginePlanDestination`) and the Scooters page sentence (`M.backupDestinationData`) | same follow-up as `localScooters`; `:457` builds `Source` from the same `BackupProfiles.Profile`, and a `Source` has no default flag either |

## Editable name fields keep the stored name on purpose

The rename dialogs (`MoreScreen`, `MainActivity.java:775`) and setup's import name field
(`OnboardingScreen`, `nameInput(prefs.getString(KEY_PENDING_NAME, summary[0]))`) start from `p.name`, so a
default-named scooter shows "Scooter" in English there. Save and continue writes the field when it differs
from `target.name`; a field that started from `displayName()` would save the translated word as the scooter's
name and clear `nameDefault`, so `BleController.java:601` would stop applying the model name. Leave these
alone unless the save paths learn to tell an untouched default from a typed name.

## The other default names

`displayName()` answers neither of these, so both stay English on every screen above and on those F4 changed.

- `MainActivity.IMPORTED_NAME` ("Imported scooter"), written while importing a scooter without a name. It also
  shows directly in setup's import summary (`OnboardingScreen.pendingSummary`). The stored value must stay as
  it is (`MainActivity.java:3464` compares it).
- "NAVEE scooter", stored while `nameDefault` is set when the model has no name (`BleController.java:601`,
  `MainActivity.java:2634`).

Follow-up, with B19 (MainActivity): a catalog key for each name, `displayName()` answering it when
`nameDefault` is set and the stored name matches, and `pendingSummary` showing the key where it falls back to
`IMPORTED_NAME`. The English is the same.
