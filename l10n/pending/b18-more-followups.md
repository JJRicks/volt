# B18 follow-ups: More screen text that stays English after the batch

B18 (MoreScreen, LanguageSheet) leaves these shown strings in English. Each one is either blocked by
G1 in an extraction batch, or kept English on purpose. Each
blocked item needs an ordinary (non-extraction) follow-up pull request, or a tooling pull request
where noted. The B4 consumer reads that stay are in
`l10n/pending/b4-controls-consumers.tsv` (MoreScreen rows). Delete an entry when it is done.

Repro for each G1 block: make the change in a scratch worktree of the B18 head, then run
`python3 tools/l10n/ledger_check.py --base f529f43 --head WORKTREE --ledger l10n/ledger/b18-more.tsv`.

## Blocked by G1

1. **Submenu titles** (`SECTION_TITLES`, used in `showSection`: `sectionTitle.setText(SECTION_TITLES[i])`).
   The menu rows themselves are translated (`SECTION_LABEL_IDS`). The title line sits in one changed
   region with the Units and Compatibility title lines, which B18 converted, so changing it to
   `T.render(...)` fails: "this change adds `T . render ( SECTION_TITLE_IDS )` and drops
   `SECTION_TITLES`; around text, an extraction batch only replaces a ledger row's expression with
   the call that serves it". Follow-up: add keys (`more_section_*` already exist for the rows; "PIN &
   proximity" needs `more_section_security_title`) and a `SECTION_TITLE_IDS` table.
2. **PIN, pairing and proximity toasts** (four `host.toast(service().ble.message())`: enable PIN,
   bond, proximity switch, set PIN). They show B7's English id, not `ble.displayMessage()`. Each
   sits in a statement with converted text: "this change adds `displayMessage ( ) . toString` and
   drops `message`; around text, ...". Follow-up: switch all four to
   `ble.displayMessage().toString()` (safety text: check every BleController message the
   enable/bond/set paths can leave).
3. **Scooter names** on the My scooters card (`card.name.setText(item.name)`) and the menu index
   (`String name = p == null ? T.moreIndexNoScooter() : p.name;`). They skip B6's
   `displayName()`, so a default-named scooter shows "Scooter" in English while its TalkBack labels
   (which use `displayName()`) are translated. G1: "adds `displayName ( )`" around text. Follow-up:
   use `displayName()` for display only; `name` stays the stored value (rename dialog, backups).
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
   keys.

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
