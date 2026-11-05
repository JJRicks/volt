# B18 follow-ups: More screen text that stays English after the batch

B18 (MoreScreen, LanguageSheet) leaves these shown strings in English. Each one is either blocked by
G1 in an extraction batch, blocked by GlyphCoverageTest (item 7), or kept English on purpose. F4 did
items 1-3 (the submenu titles, the PIN, pairing and proximity toasts, and the scooter names). Each
blocked item needs an ordinary (non-extraction) follow-up pull request, or a tooling pull request
where noted. The B4 consumer reads that stay are in
`l10n/pending/b4-controls-consumers.tsv` (MoreScreen rows). Delete an entry when it is done.

Repro for each G1 block: make the change in a scratch worktree of the B18 head, then run
`python3 tools/l10n/ledger_check.py --base f529f43 --head WORKTREE --ledger l10n/ledger/b18-more.tsv`.

## Blocked by G1

4. **"Other model(s)" in the try sheet** (`more_lab_why_one`, `more_lab_why_many`). This should be a
   `<plurals>` (Arabic needs the dual form, and Polish, Russian and Ukrainian need few/many). The base
   sentence is a choice with a shared tail (`(n > 0 ? ... : ...) + " It may do nothing here..."`),
   and a choice row's branches cannot be plurals: "choice key more_lab_why is a plural, but each
   branch is one string" (l10n/README.md, "Still blocked"). Follow-up: a tooling PR that lets a
   choice branch be a plural, or an ordinary PR that rewrites the line. Until then do not ship a
   language with dual or few forms without that fix.
5. **"Cleared N tries"** (`more_lab_cleared_one`, `more_lab_cleared_many`) should be one `<plurals>`
   (Polish, Russian and Ukrainian few/many). The base has its own literal for 1
   (`n == 1 ? "Cleared 1 try" : "Cleared " + n + " tries"`). G1 accepts a manual plural row whose
   `one` form is `["Cleared 1 try"]`, but the proof tests then refuse it either way.
   With `<item quantity="one">Cleared %1$d try</item>`, LedgerTest gives the hole-less `one` form
   a String sentinel and crashes: `IllegalFormatConversionException: d != java.lang.String` at
   `LedgerTest.render` (from `comparePlural`). With `<item quantity="one">Cleared 1 try</item>`,
   CatalogCheckTest fails: "more_lab_cleared is formatted only when its text has %". A `one` form
   with the hole fails G1: "literal \"Cleared 1 try\" left with no ledger row". Follow-up: a tooling PR
   (a quantity sentinel for a `one` form that wrote the 1 out), then the plural.
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
   Fmt.<method>(...)"), and a `choice` row would need twelve whole sentences. Follow-up: a tooling PR
   that orders a template row by its expression's start (or lets a template hole's `as` hold the
   calls of its own exact rows), then the template. Repro on 811d1c9: return
   `T.morePrefsUnitsSummary("km".equals(units) ? T.unitsKm() : "dual".equals(units) ?
   T.morePrefsUnitsDual(T.unitsMi(), T.unitsKm()) : T.unitsMi(), "f".equals(temperature) ?
   T.unitsFahrenheit() : T.unitsCelsius(), hours24 ? T.morePrefsUnits24h() : T.morePrefsUnits12h())`,
   add the four keys, a `manual:` template row whose three holes carry those ternaries as `as`, a
   `manual:` exact row for each unit literal and a `format` row for "mi + km", then run
   `ledger_check.py --base 811d1c9 --head WORKTREE --ledger l10n/ledger/f4.tsv`.

## Blocked by GlyphCoverageTest

7. **Battery row value** in App preferences
   (`background.value(unrestricted ? "Unrestricted ✓" : "Optimized ⚠")`, base :996). Its screen-reader
   text is translated (`more_prefs_battery_unrestricted`, `more_prefs_battery_optimized`); the value
   the row shows is English in every language. Moving the pair passes G1 (two `exact` rows at base
   :996), but English uses `font=saira` and none of the four Saira weights maps U+2713 ✓ or U+26A0 ⚠,
   so `tests/run.sh` fails: "en: saira_bold.ttf has no glyph for U+2713 '✓'; use font=system for this
   language". Keeping the mark in Java (`T.x() + " ✓"`) adds a literal, which G1 refuses in an
   extraction batch. It is the same block as B16's "☆ Star readings to pin here". G8 never listed it
   either: `Row.value(...)` is not a sink or text helper, and `is_multiword` counts "Unrestricted ✓"
   as one word, so `l10n/baseline/MoreScreen.txt` has no entry for it. Follow-up: a tooling PR that
   lets GlyphCoverageTest accept the symbol marks the English build already shows through Android's
   font fallback, then keys `more_prefs_battery_value_unrestricted` and
   `more_prefs_battery_value_optimized` with `volt:dnt="✓"` and `volt:dnt="⚠"` and a note that the
   mark is a status mark to keep. Repro: change that line to the two `T.` calls, add the keys and the
   two rows; `ledger_check.py` passes and `tests/run.sh` stops at GlyphCoverageTest. The PIN &
   proximity chips ("PIN ✓" and "Paired ✓" with their "—" forms, listed in
   `l10n/baseline/MoreScreen.txt`; their screen-reader text is translated) carry the same ✓, so they
   wait on the same fix.

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
