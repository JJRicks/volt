# B18 follow-ups: More screen text that stays English after the batch

B18 (MoreScreen, LanguageSheet) leaves these shown strings in English. Each one is either blocked by
G1 in an extraction batch, or kept English on purpose. F4 did items 1-5 (the submenu titles, the PIN,
pairing and proximity toasts, the scooter names, and the two count plurals). Item 6 needs a prep pull
request first. The B4 consumer reads that stay are in `l10n/pending/b4-controls-consumers.tsv`
(MoreScreen rows). Delete an entry when it is done.

Repro for each G1 block: make the change in a scratch worktree of the B18 head, then run
`python3 tools/l10n/ledger_check.py --base f529f43 --head WORKTREE --ledger l10n/ledger/b18-more.tsv`.

## Blocked by G1

6. **Units summary** row value (`unitsSummary()`: "mi + km · °C · 24h"). Its separators and
   "mi + km" sit beside the unit ids it tests, which stay data. Follow-up: whole template with unit
   keys (`more_prefs_units_summary` "%1$s · %2$s · %3$s", `more_prefs_units_dual` "%1$s + %2$s" over
   `units_mi`/`units_km`, `units_celsius`/`units_fahrenheit`, and `more_prefs_units_24h`/`_12h`).
   F4 (an ordinary follow-up on 811d1c9) found it still blocked: the template's first own literal
   (`" · "`) comes after the first hole, whose literals (`"km"`, `"mi + km"`, `"mi"`) become calls
   inside the template's arguments, and G1 checks the calls of a line's rows in the base order of
   their first literal, so the template's call, which comes first at head, fails: "the call of
   morePrefsUnitsSummary in head lines 1055-1056 of src/com/jjricks/volt/MoreScreen.java is already
   used by another row, or comes before the call of an earlier row; each converted site needs its
   own call, in the base order". A `format` row refuses the holes' `as` ("a format row's "as" must be
   Fmt.<method>(...)"), and a `choice` row would need twelve whole sentences. Moving the pieces into
   locals in the same pull request fails too ("adds String distance = ...; around text").
   Follow-up, no tooling change needed: an ordinary prep pull request first, which only moves the
   summary into locals, id tests included (keeping them beside the literals fails with "adds "km" and
   drops "km""):
   `boolean km = "km".equals(units), dual = "dual".equals(units), fahrenheit = "f".equals(temperature);`
   `String distance = km ? "km" : dual ? "mi + km" : "mi";`
   `String degrees = fahrenheit ? "°F" : "°C";` `String clock = hours24 ? "24h" : "12h";`
   `return distance + " · " + degrees + " · " + clock;`
   Then a second pull request replaces the literals with T calls, ends in
   `T.morePrefsUnitsSummary(distance, degrees, clock)`, and adds the four keys and 8 ledger rows.
   Checked for G1 only, on 811d1c9 plus such a prep commit:
   `ledger_check.py --base <prep commit> --head WORKTREE --ledger l10n/ledger/f4.tsv` passes. The other
   gates have not run on this route.

## Keys no screen shows since F4

`more_lab_cleared_one`, `more_lab_cleared_many`, `more_lab_why_one` and `more_lab_why_many` were replaced
by the plurals `more_lab_cleared` and `more_lab_why_offered`. They stay in the catalog, marked
`translatable="false"` so no language is asked for them, because deleting them fails two gates:

- `ledger_check.py --catalog-only`: "l10n/ledger/b18-more.tsv:179: key more_lab_cleared_one is not in
  res/values/strings*.xml" (also :180 and :279);
- `frozen_literals.py`, once `tests/l10n/MoreTextTest.java` stops calling them: ":74: existing test code
  changed (...); existing tests only gain new statements" (also :73 and :155).

Follow-up: a tooling pull request that lets a key retire, then delete the four keys, their rows and those
test lines.

## Kept English on purpose

- **Developer simulator model names** (`SIMULATION_MODEL_NAMES` and the row value
  `model == 0 ? "UT5 MAX" : model == 1 ? "Mapped" : "Unknown"`). Developer options only. They name
  the ScooterSimulation models, and the owner's default is that simulation text stays English.
  "UT5 MAX" is a model name.
- **The lab's model sentences** ("the " + model, "your model"): see the `model-line` row in
  `b4-controls-consumers.tsv`.

## Done in B18 after review

- Storage status (`Storage.statusMsg()`, handoff item B3) is shown in the current language. The
  English id stays in `storage.status`.
