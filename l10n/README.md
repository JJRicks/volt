# Localization workflow

Volt moves its English UI text out of Java into Android string resources in small batches, then
translates it. Every batch must prove that **English did not change**. This folder holds the
committed data; `tools/l10n/` holds the tools; `tests/l10n/` holds the report goldens and,
from B0b on, the catalog tests.

The batch list, the architecture and the reasons behind each rule live in the localization plan of
record, which is kept with the owner's working notes rather than in this repository. This file is
the working manual.

## Layout

| Path | What it is |
|---|---|
| `res/values/strings_<area>.xml` | English source text with `volt:*` metadata, one file per batch area (from B0b) |
| `l10n/lang/<tag>.tsv` | One language's settings; `en.tsv` is the source language |
| `l10n/glossary/en.tsv` | English terms: id, term, definition, part of speech, do-not-translate, safety, source |
| `l10n/ledger/<batch>.tsv` | G1: one row per Java expression whose literals left `src/` in that batch |
| `l10n/baseline/<File>.txt` | G8: the hard-coded English each unconverted source file still has |
| `tests/l10n/GoldenReports.java`, `golden.sh`, `golden/` | G9: report outputs that must stay byte-identical |
| `tools/l10n/` | Generator, round-trip, gates and their unit tests |

Generated files (`T.java`, `M.java`, `EnglishText.java`, `AndroidText.java`, `AppLanguages.java`,
`en.expected.tsv`) go to the build output only and are never committed.

## Writing strings

```xml
<resources xmlns:volt="https://volt.jjricks.com/ns/l10n"
    xmlns:xliff="urn:oasis:names:tc:xliff:document:1.2">
    <!-- Shown under the lock switch while Volt waits for the scooter's answer. -->
    <string name="ble_lock_waiting" volt:screen="Home lock row" volt:purpose="status"
        volt:max="132dp@14sp" volt:ladder="full" volt:safety="lock">Waiting for the scooter</string>
    <string name="ble_lock_waiting_short" volt:ladder="short">Waiting</string>
    <string name="dash_move_widget">Move %1$s</string>
    <plurals name="backup_scooters_skipped" volt:english-fix="pending">
        <item quantity="one">%1$d scooters skipped</item>
        <item quantity="other">%1$d scooters skipped</item>
    </plurals>
</resources>
```

- **Keys** are `<area>_<screen>_<meaning>` in lower snake case. Shared words go in
  `strings_common.xml`. Each key becomes `T.camelCase(...)`, for example `T.dashMoveWidget(title)`.
- **Metadata** (`volt:*`, all optional): `screen`, `purpose`, `max` (`<dp>dp@<sp>sp`), `ladder`
  (`full`, `short`, `compact`), `safety` (lock, ride, alarm, charge, experimental, owner-key, pin,
  privacy, disclaimer; comma-separated), `dnt` (do-not-translate tokens separated by `|`; each
  must appear in the text), `english-fix="pending"` (a known English bug kept byte for byte).
  An XML comment right before the string becomes its note. Any other `volt:` attribute is an error.
- **Ladder rungs** are sibling keys: `<key>_short` with `volt:ladder="short"` and `<key>_compact`
  with `volt:ladder="compact"`. A rung may use only placeholders its full key has.
- **Placeholders** are positional only: `%1$s` (a `CharSequence`), `%1$d` (a `long`), `%1$.1f`
  (a `double`), and `%%` for a percent sign. Number them from `%1$` with no gaps. Any text with
  `%` always goes through `String.format(Locale.ROOT, ...)`; locale-aware numbers come from `Fmt`
  as `%n$s` arguments. `formatted="false"` turns this off for a string.
- **Plurals** take the quantity first: `T.backupScootersSkipped(long quantity, long n)`. English
  uses `one` for exactly 1 and `other` otherwise. `<string-array>` is banned for UI text.
- **Spelling rules.** `gen_catalog.py` refuses anything aapt would silently store differently:
  - leading or trailing whitespace, repeated spaces, raw tabs or line breaks: wrap the whole string
    in `"..."` (the quotes are not part of the text) or use `\n` and `\t`;
  - an apostrophe outside a wrapped string: write `\'`;
  - a double quote that is not the whole-string wrapper: write `\"`;
  - unknown escapes such as `\r` or `\ ` (aapt drops them); a leading `@` or `?` (write `\@`, `\?`);
  - markup other than `<xliff:g>` (styled spans are not supported).
  `\uXXXX` escapes are kept as written, including spaces. `tools/l10n/fixtures/aapt/` shows every
  case with what aapt 36.1.0 actually stored.
- `l10n_tag` (`<string name="l10n_tag">en</string>`) records which language folder resolved. It has
  no accessor; `AndroidText.language()` reads it and treats anything that is not exactly a shipped
  tag as English.

## The generated catalog

`tools/l10n/gen_catalog.py` reads `res/values/strings*.xml` and `l10n/lang/*.tsv`:

- `T.java`: ids, one typed accessor per key, `T.Source` (`string(id)`, `plural(id, q)`,
  `language()`), `T.install(source)`, `T.render(id, args...)` and `T.renderPlural(id, q, args...)`.
  A wrong argument count or type is a compile error.
- `M.java`: the same accessors returning a deferred `Msg`. Its contract with the hand-written
  `Msg` (B0b): `static Msg of(int id, Object... args)` and
  `static Msg plural(int id, long quantity, Object... args)`; `Msg.render()` calls `T.render` or
  `T.renderPlural`. If `Msg` implements `CharSequence`, a `Msg` can be an argument of another `Msg`.
- `EnglishText.java`: the English values; `T`'s default source and the fallback for a missing id.
- `AndroidText.java` (`--android`, app build only): ids mapped to `R.string` and `R.plurals`.
- `AppLanguages.java`: the language table; `resolve(tag)` is the defensive `l10n_tag` parse.
- `en.expected.tsv`: the English the round-trip compares with aapt.

`build.sh` runs the generator before aapt and the round-trip after it, but only once a
`res/values/strings_*.xml` file exists; until then both steps are skipped and the build is
byte-identical. B0b adds the generator run to `tests/run.sh`.

## Doing a batch

1. **Branch** from `main`. If the batch changes a template or the output of a pure class, make the
   **first commit** add characterization tests under `tests/l10n/` that pass on the base, and never
   touch them again (G5). That commit changes nothing under `src/` or `res/`.
2. **Move the text.** Add the keys to the batch's `res/values/strings_<area>.xml`, and replace the
   Java literals with `T` (rendered now) or `M` (rendered later) calls. Keep every existing test's
   English literal exactly as it is (G4). Known English bugs stay byte for byte; tag those plurals
   `volt:english-fix="pending"`.
3. **Record the ledger.** Commit or keep the changes in the working tree, then run

   ```sh
   python3 tools/l10n/extract.py draft --base origin/main --batch b2-ui
   ```

   It appends one draft row per original expression to `l10n/ledger/b2-ui.tsv`. Replace every
   `TODO`: set `key` and `kind`, and fix each hole's `arg` (placeholder index) and `type` (`s`, `d`
   or `f`). The draft follows `+` chains only: ternaries, `String.format`, `StringBuilder.append`
   chains and numeric sums such as `a + b + "x"` must be written by hand. `extract.py scan <files>`
   lists a file's literals, which helps plan a batch.
4. **Shrink the baselines.** `python3 tools/l10n/hardcoded_lint.py --write-baselines` rewrites
   `l10n/baseline/`; a converted file's baseline disappears. Baselines may only shrink.
5. **Build** with `bash build.sh` (catalog plus round-trip), then `bash android-tests/build.sh`.
6. **Prove it**: `tools/l10n/prove-english.sh origin/main` and paste its summary block into the PR
   description and the Codex handoff comment.
7. **Census (G7)** on an English-pinned emulator: run the batch's sweep steps with `--census` for
   the base and the head APK, then `python3 tools/l10n/census_diff.py base-dir head-dir`, and paste
   its last line.

### Ledger format

`l10n/ledger/<batch>.tsv`, tab-separated, with the header
`location	pieces	key	kind	note`:

| Column | Meaning |
|---|---|
| `location` | base `file:line` of the original expression, e.g. `src/com/jjricks/volt/Ui.java:812` |
| `pieces` | JSON: the expression as literal pieces and holes, e.g. `["Move ", {"hole": "title", "arg": 1, "type": "s"}]`. A char literal is `{"char": "x"}`; a plural's quantity hole adds `"quantity": true`. For `plural`, an object of English forms: `{"one": [...], "other": [...]}` |
| `key` | the resource key, or `-` for `stays-english` and `removed` |
| `kind` | `exact`, `template`, `plural`, `id-kept`, `stays-english` or `removed` |
| `note` | free text |

`ledger_check.py` lexes every changed `src/` file at the merge base and at head and fails when a
literal disappears without enough `exact`, `template`, `plural` or `removed` rows. It also checks
that each row's line holds one of its literals, that keys exist with the right kind, that an exact
row's English equals its literal, and that a template with only `%n$s` holes renders the original
concatenation (`tests/l10n/LedgerTest`, B0b, covers the rest with Java's formatter). A test file's
literal may be removed only for a test of deleted code, recorded as a `removed` row whose location
is that test file.

## The gates

| Gate | Tool | Runs in |
|---|---|---|
| G1 ledger | `tools/l10n/ledger_check.py --base origin/main` (drafts: `extract.py`) | CI, prove-english |
| G2 template equivalence | `tests/l10n/LedgerTest.java` (B0b) | CI via `tests/run.sh` |
| G3 aapt round-trip | `tools/l10n/roundtrip.py`, run by `build.sh` | every local build |
| G4 frozen test literals | `tools/l10n/frozen_literals.py --base origin/main` | CI, prove-english |
| G5 characterization first | `tools/l10n/char_first.py --base origin/main` | CI (pull requests), prove-english |
| G6 existing suites | `tests/run.sh` and the sweep's `--only` steps | CI, emulator |
| G7 screen text census | `android-tests/TextCensus.java` (B0b), `tools/l10n/census_diff.py` | emulator |
| G8 hard-coded text lint | `tools/l10n/hardcoded_lint.py --check --base origin/main` | CI, prove-english |
| G9 reports stay English | `bash tests/l10n/golden.sh` (and `ReportsStayEnglishTest`, B0b) | CI, prove-english |
| G10 format legacy parity | `tests/l10n/FmtLegacyTest.java` (B0b, B1) | CI via `tests/run.sh` |

The tools' own tests: `python3 -m unittest discover -s tools/l10n` (standard library only).

**G3.** The round-trip reads the default-configuration strings and plurals straight from the
`resources.arsc` aapt wrote and compares them with `en.expected.tsv`. It also fails on compiled
strings the catalog does not know and on styled strings. CI cannot run aapt, so CI runs the unit
tests against fixtures captured from the real aapt; refresh them with
`tools/l10n/fixtures/aapt/capture.sh` after changing `fixtures/aapt/res/`.

**G4.** "English-looking" means a literal with two letters in a row (or a non-ASCII letter) that is
not a lower-case key, path or id. Literals are compared across all changed test files together,
so moving a helper between files is fine (`--per-file` forbids that too).

**G8** is a Python tool run in CI, instead of the plan's `tests/l10n/HardcodedTextLint.java`, so it
shares the Java lexer with the other gates. It flags any literal with letters inside a UI sink
call (`setText`, `setContentDescription`, `setHint`, `setTooltipText`, `setStateDescription`,
`setError`, `setTitle`, `setMessage`, `set*Button`, `toast`, `makeText`, `notice`, notification
titles, texts and actions, `announceForAccessibility`, `setAccessibilityPaneTitle`,
`setChartTitle`, `drawText`) and any other literal of two or more words. Baselines list findings
without line numbers, so a rebase only needs `--write-baselines` when text really changed. A file
with no findings has no baseline, so any new English in it fails. The allowlist for converted files
that must keep English (reports, tags, keys) arrives with the first batch that needs one.

**G9.** `tests/l10n/golden.sh` compiles `GoldenReports.java` against the pure classes, runs it under
en-US, de-DE, tr-TR and ar-EG with different time zones, and compares the diagnostics JSON, the ride
journal CSV, the phone ride CSV and the GPX with `tests/l10n/golden/`. `--update` rewrites them;
use it only for an intended, reviewed format change.

**B0a extra check.** `prove-english.sh <base> --same-apk` builds the merge base and head in clean
worktrees and requires byte-identical `classes.dex` and `resources.arsc`.

## Pseudo-locales

`VOLT_PSEUDO_LOCALES=1 bash build.sh` also packages the same `classes.dex` with
`aapt --pseudo-localize` into `build/pseudo/volt-pseudo.apk` (never `dist/`), with en-XA (accented,
bracketed, longer) and ar-XB (right-to-left). The build checks that the resource ids are unchanged
and that both configurations are present. Every build runs a release guard: `aapt dump
configurations` on `dist/volt.apk` must not list en-XA or ar-XB. Known aapt1 quirk: a trailing `%`
is mangled (`75%` becomes `[75»%`).

## Census format (G7)

One UTF-8 file per captured screen, `<variant>-<label>.census.tsv`. The first line is
`volt-text-census-v1`; then one record per line: `field`, `locator`, `bounds`, `value`, tab-separated.
Fields: `text`, `desc`, `hint`, `tooltip`, `state`, `error`, `title`, `message`, `button`, `item`,
`toast`, `notif-title`, `notif-text`, `notif-subtext`, `notif-action`, `notif-channel`. The locator
is `tag=<tag>` when the view has a String tag, else the class path with child indexes; bounds are
`x,y,width,height` or `-`. Backslash, tab, line feed and carriage return are escaped as `\\`, `\t`,
`\n`, `\r`. `census_diff.py` masks clock times, durations and dates (keeping their shape, such as
`<date:MMM d>` or `<n> min`) and reports bounds changes without failing.

## Languages

`l10n/lang/<tag>.tsv` has a `field	value` header and these fields: `tag` (BCP 47, e.g. `pt-BR`),
`folder` (`values`, `values-es`, `values-b+zh+Hans`), `endonym`, `wave`, `status` (`held`, `draft`,
`beta`, `stable`; only beta and stable ship), `formality`, `plurals` (CLDR categories),
`quote_style`, `percent_style`, `decimal_style`, `script`, `font` (`saira` or `system`), `rtl`,
`unit_symbols`, `drive_mode_names`.

## Not built yet

The translation tools (`pack.py`, `merge.py`, `check.py`) arrive with the Spanish pilot, after the
three-provider drafting pipeline is designed. B0b adds `Msg`, `VoltApp`, the `tests/run.sh` hook,
the catalog tests and `android-tests/TextCensus.java`.
