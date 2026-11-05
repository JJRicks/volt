# B19 follow-ups: MainActivity's neighbours and the checks that stay

B19 moved MainActivity's text to the catalog. These stay open. Only English ships, so none is visible
today. Delete an entry when it is done. Line numbers are on B19's head.

## Default scooter names on the More screen, owner: a follow-up after B19 merges

1. **`displayName()` answers only "Scooter".** `Profile.shownName()` (ScooterProfiles) also shows
   "Imported scooter" and "NAVEE scooter" in the current language, and MainActivity uses it. MoreScreen's
   scooter card labels use `displayName()` (`MoreScreen.java:929`, `:943`, `:2492`-`:2497`), which
   tests/l10n ServiceConsumersTest pins, so those two names stay English there. Fix: move these sites
   to `shownName()`. MoreScreen was closed to B19.
3. **Setup's import summary still shows default names in English before the import applies.**
   `OnboardingScreen` shows `summary[0]` in the sentence and also starts the editable name field from it, so
   B19 keeps it as the stored name, and that name stays English in other languages. Translating the array
   stored a translated name: Save and continue wrote it to `pending_name`, `applyPendingName` saw it differ
   from `Profile.name`, wrote it there and cleared `nameDefault`, so `BleController` stopped applying the model
   name. MainTextTest.pendingImportName pins the stored name. To show it translated, change only the sentence's
   argument to `ScooterProfiles.nameText(summary[0])`. B19 cannot: `tests/l10n/FollowupsTest.java` pins
   `T.onboardingImportSummary(applied && p != null ? p.displayName() : summary[0], summary[1])`, and G4 freezes
   that statement in a batch. Do it in an ordinary pull request that updates that test.

## Deferred from the plan, owner: the batches that clear each file

- **"The last baseline file is deleted" (plan B19) cannot happen yet.** `l10n/baseline/MainActivity.txt`
  still holds the literals that stay on purpose (ids, report and diagnostics English), and other files
  keep baselines of their own, some for open batches. Delete the last one when the final batch merges.

- **Two of plan 4.6's lower-casing sites keep a hole.** The test line (`main_test_trying`, through `inSentence`)
  and a control row's spoken state (`main_row_state_note`, through `lowerInEnglish`) take a word from many
  controls and many notes, so a whole sentence per word is not practical. The word is shown as translated in
  other languages. The lock's last known sentences are whole templates, one per state.

## Layout and unit choices to revisit, owner: the batch that adds a language needing them

- **`main_speed_unit_dual` spells `mph · %1$s km/h` with `dnt` tags.** The km and mi cards on the same line
  use `T.unitsKmh()` and `T.unitsMph()`, so a language that localizes `units_kmh` would show two forms. It
  matches today under the owner's keep-SI-symbols default, so nothing changes until a language does that.

## A read-back with no live input, owner: this file

4. **`MainActivity.valueText`** still runs `label.replace(" hours"," h")` for a `lock_expiry` value
   outside 1-3. `lockHours` answers 1-3, and every table (ScooterCommands, ModelRegistry) holds only those,
   so the line cannot run today. Delete it, with a test that English stays the same, if a table ever
   grows or when the rest of F3's read-backs go.

## Dead code G1 keeps while B19 is open, owner: an ordinary follow-up after B19 merges

G1 refuses items 5, 6 and 9 in an extraction batch ("around text, an extraction batch only replaces a
ledger row's expression with the call that serves it"). None of these changes what the app shows in English.

5. **`new ApplyCheck(apply,"Apply")`**: the constructor ignores its second parameter and always uses
   `M.mainEditApply()`. Drop the parameter and the literal.
6. **The owner import's catch** still has `e instanceof IllegalArgumentException?e.getMessage():...`, but the
   line before it returns for that exception when `onboarding==null`, so the arm cannot run. Reduce it to
   `toast(T.mainImportSetupUnreadable())`.
7. **The control list's search in a translated language.** `ControlRow.haystack` is built from
   `searchText()`, `shortLabel()` and `rowDetail()`, and the last two now render in the current language.
   English that exists only in those two ("limits speed", "scooter display units") matches in English but
   stops matching once a language ships, because `matchesShown` holds the rendered text too. Add their
   `.english()` forms to the haystack if English search should keep working in every language. English
   itself is unchanged.
8. **`profileTitle` is never assigned** (only `=null`, at `MainActivity.java:380`, `:452` and `:803`), so the
   `profileTitle!=null` branch beside `:944` never runs, including its raw `p.name`. `main_your_scooter` is
   shown nowhere else, so translators are asked for a string nobody sees. Delete the field, the branch and
   the key.
9. **The lock title's two `setText` calls** (`offlineTitle`, about `:921` and `:924`). `known` holds the state
   id now, because G1's id test needs that variable. G1 refuses `known` being replaced on the line that
   holds "Connecting", so the word is set in the next statement. Join them after B19 merges, and drop
   `main_lock_last_known_full` and `main_lock_last_known_word`, the branches G1's id test needs that cannot run.
