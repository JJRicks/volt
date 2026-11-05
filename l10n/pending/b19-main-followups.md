# B19 follow-ups: MainActivity's neighbours and the checks that stay

B19 moved MainActivity's text to the catalog. These stay open. Only English ships, so none is visible
today. Delete an entry when it is done. Line numbers are on B19's head.

## Default scooter names on the More screen, owner: a follow-up after B19 merges

1. **`displayName()` answers only "Scooter".** `Profile.shownName()` (ScooterProfiles) also shows
   "Imported scooter" and "NAVEE scooter" in the current language, and MainActivity uses it. MoreScreen's
   scooter card labels use `displayName()` (`MoreScreen.java:929`, `:943`, `:2492`-`:2497`), which
   tests/l10n ServiceConsumersTest pins, so those two names stay English there. Fix: move these sites
   to `shownName()`. MoreScreen was closed to B19.
2. **F4's `l10n/pending/b17-onboarding-followups.md`** is not on B19's branch. Whichever of F4 and B19
   merges second updates it: delete its MainActivity rows, which B19 did with `shownName()`, and add
   item 1 here to "The other default names".

## A read-back with no live input, owner: this file

3. **`MainActivity.valueText`** still runs `label.replace(" hours"," h")` for a `lock_expiry` value
   outside 1-3. `lockHours` answers 1-3, and every table (ScooterCommands, ModelRegistry) holds only those,
   so the line cannot run today. Delete it, with a test that English stays the same, if a table ever
   grows or when the rest of F3's read-backs go.

## Dead code G1 keeps while B19 is open, owner: an ordinary follow-up after B19 merges

G1 refuses both in an extraction batch ("around text, an extraction batch only replaces a ledger row's
expression with the call that serves it"). Neither changes what the app shows.

4. **`new ApplyCheck(apply,"Apply")`**: the constructor ignores its second parameter and always uses
   `M.mainEditApply()`. Drop the parameter and the literal.
5. **The owner import's catch** still has `e instanceof IllegalArgumentException?e.getMessage():...`, but the
   line before it returns for that exception when `onboarding==null`, so the arm cannot run. Reduce it to
   `toast(T.mainImportSetupUnreadable())`.
