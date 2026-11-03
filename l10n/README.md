# Localization workflow

Volt moves its English UI text out of Java into Android string resources in small batches, then
translates it. Every batch must prove that **English did not change**. This folder holds the
committed data; `tools/l10n/` holds the tools; `tests/l10n/` holds the report goldens,
characterization tests and, from B0b on, the catalog tests.

The batch list, the architecture and the reasons behind each rule live in the localization plan of
record, which is kept with the owner's working notes rather than in this repository. This file is
the working manual.

## Layout

| Path | What it is |
|---|---|
| `res/values/strings_<area>.xml` | English source text with `volt:*` metadata, one file per batch area (from B0b) |
| `l10n/lang/<tag>.tsv` | One language's settings; `en.tsv` is the source language |
| `l10n/glossary/en.tsv` | English terms, one per row: id, term, definition, part of speech, do-not-translate, safety, source |
| `l10n/ledger/<batch>.tsv` | G1: one row per Java expression whose literals left `src/` in that batch (append-only) |
| `l10n/english-changes.tsv` | Owner-approved changes to already-extracted English (plan part D); absent until the first one |
| `l10n/baseline/<File>.txt` | G8: the hard-coded English each unconverted source file still has |
| `tests/l10n/characterization/` | G5: tests that pin a batch's English output, committed first |
| `tests/l10n/GoldenReports.java`, `golden.sh`, `golden/` | G9: report outputs that must stay byte-identical |
| `tools/l10n/` | Generator, round-trip, gates, the CI driver (`ci_gates.py`) and their unit tests |

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
- **Only `res/values/` holds English.** Text (`string`, `plurals`, `string-array`) in any other
  `res/values-*` folder must be a non-English language folder listed in `l10n/lang/*.tsv`.
  `values-en`, `values-en-rGB`, `values-night` or `values-v31` text would replace the catalog
  English on the phones that match, while `EnglishText` and the tests still see the default, so
  `gen_catalog.py`, the round-trip and the release guard all refuse it.
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
byte-identical. Because `M.java` needs `Msg`, the first `strings_*.xml` arrives together with
`Msg.java` in B0b. The generator run in `tests/run.sh` lands before that, in the harness pull
request (see "Order of the first pull requests").

### Order of the first pull requests

A batch may not change the proof tooling (below), may not edit or delete existing test code (G4),
and may not let an existing test call into a file it adds. So the plan's B0b is two pull requests:

1. **B0b-harness**, an ordinary pull request (no `strings_*.xml`, ledger or `T`/`M` reference):
   the `tests/run.sh` hook (generator run, compiling the generated classes and every file listed in
   `tests/l10n/*.sources`, all skipped until a `res/values/strings_*.xml` exists, like `build.sh`),
   the `TextFit` helpers moved out of `ResponsiveDashboardValidation`, `TextCensus` with the
   screenshot helpers calling it, the `LocaleValidation` skeleton, `Ut5SimulationScreens` and the
   sweep changes. G4 does not run on an ordinary pull request, so moving test code is fine there.
2. **B0b**, the first batch: `Msg`, `AppLanguage`, `VoltApp`, `strings_common.xml`,
   `strings_fmt.xml`, `tests/l10n/b0b.sources`, and the catalog tests as **new** files
   (`CatalogCheckTest`, `LedgerTest`, `CatalogUsageTest`, `ReportsStayEnglishTest`,
   `GlyphCoverageTest`). A batch may add a `tests/l10n/<Name>.java`; it may not edit one.

`FmtLegacyTest` is not scaffolded in B0b: B1 adds it, whole, as a new file with `Fmt`. A later
change to any of these tests is a tooling pull request of its own.

## Doing a batch

1. **Branch** from `main`. If the batch changes a template or the output of a pure class, make the
   **first commit** add characterization tests under `tests/l10n/characterization/` (plus the
   `tests/l10n/<batch>.sources` line that makes `tests/run.sh` compile them) that pass on the base,
   and never touch them again (G5). That commit changes nothing under `src/` or `res/`. A batch with
   any `template` or `plural` ledger row must have one.
2. **Move the text.** Add the keys to the batch's `res/values/strings_<area>.xml`, and replace the
   Java literals with `T` (rendered now) or `M` (rendered later) calls. Keep every existing test
   statement that holds English exactly as it is (G4). Known English bugs stay byte for byte; tag
   those plurals `volt:english-fix="pending"`.
3. **Record the ledger.** Commit or keep the changes in the working tree, then run

   ```sh
   python3 tools/l10n/extract.py draft --base origin/main --batch b2-ui
   ```

   It appends one draft row per original expression to `l10n/ledger/b2-ui.tsv`. Replace every
   `TODO`: set `key` and `kind`, and fix each hole's `arg` (placeholder index) and `type` (`s`, `d`
   or `f`). Keep the pieces as drafted: `ledger_check` compares them with the base expression. The
   draft follows `+` chains only; for a ternary, `String.format`, a `StringBuilder.append` chain or a
   numeric sum such as `a + b + "x"`, write the row by hand with a note starting `manual:` (the
   reviewer checks those by hand). The note does not excuse a mismatch with an expression the tool
   did parse on that line. `extract.py scan <files>` lists a file's literals, which helps
   plan a batch.
4. **Shrink the baselines.** `python3 tools/l10n/hardcoded_lint.py --write-baselines` rewrites
   `l10n/baseline/`; a converted file's baseline disappears. Baselines may only shrink.
5. **Build** with `bash build.sh` (catalog plus round-trip), then `bash android-tests/build.sh`.
6. **Prove it**: `tools/l10n/prove-english.sh origin/main` and paste its summary block into the PR
   description and the Codex handoff comment.
7. **Census (G7)** on an English-pinned emulator: run the batch's sweep steps with `--census` for
   the base and the head APK, then `python3 tools/l10n/census_diff.py base-dir head-dir`, and paste
   its last line.

**After a rebase** onto a `main` that moved lines, the ledger's base lines are stale. Run
`python3 tools/l10n/extract.py relocate --base origin/main --batch b2-ui`: it moves every row whose
pieces match exactly one expression in that file at the new merge base, and lists the rest for a
hand fix. Baselines carry no line numbers and need nothing.

**Keep the proof tooling out of batches.** A batch pull request must not change `tools/l10n/`,
`.github/workflows/`, anything under `tests/l10n/` except `characterization/`, `golden/` and the
batch's own `tests/l10n/<batch>.sources`, `tests/run.sh`, `build.sh` or `android-tests/build.sh`
(`ci_gates.is_tooling`); CI fails it. The one exception is a new `tests/l10n/<Name>.java`, which
only adds checks; editing or deleting an existing one is a tooling change. Change the tools in
their own pull request first.

### Ledger format

`l10n/ledger/<batch>.tsv`, tab-separated, with the header
`location	pieces	key	kind	note`:

| Column | Meaning |
|---|---|
| `location` | merge-base `file:line` of the expression's first literal, e.g. `src/com/jjricks/volt/Ui.java:812` |
| `pieces` | JSON: the expression as literal pieces and holes, e.g. `["Move ", {"hole": "title", "arg": 1, "type": "s"}]`. A char literal is `{"char": "x"}`; a plural's quantity hole adds `"quantity": true`; `"as": "<Java>"` names a hole's expression at head when the call passes it differently: `Fmt.<method>(..., <hole>, ...)` is accepted, anything else needs a `manual:` note, and every `as` is listed for the reviewer. For `plural`, an object of English forms: `{"one": [...], "other": [...]}` |
| `key` | the resource key, or `-` for `stays-english` and `removed` |
| `kind` | `exact`, `template`, `plural`, `id-kept`, `stays-english` or `removed` |
| `note` | free text; `manual: <why>` for a hand-written row |

How `ledger_check.py` (G1) reads it:

- It compares every changed `src/` file's string **and char** literals at the merge base and at
  head **by position**: lines are aligned first, then the literals inside each changed region. A
  literal is gone when it no longer sits in the same place in the same order, so changing,
  swapping or reordering literals removes them. Every gone literal needs exactly one row.
- `exact`, `template`, `plural`: the pieces must equal the base expression on that line (hole
  sources compared token by token), every literal of it must be gone, the key must exist with that
  kind, an exact row's English must equal its literal, and a template must render the original
  concatenation. A `T.<key>(...)`, `M.<key>(...)`, `Msg.of(T.<KEY>, ...)` or
  `T.render(T.<KEY>, ...)` must pass the holes in placeholder order (a plural's quantity first),
  in the head lines that replaced the literal; each call serves one row, in the base order, so
  keys swapped between converted sites fail.
- `removed`: the code was deleted. The changed lines that held the literal may only lose tokens:
  nothing, not even a constant's name, takes its place. The whole statement holding the literal
  (or the member or block around it) must go: deleting only `"Chart time window: " +` from a
  `setContentDescription(...)` call leaves shorter English running and fails. When the block around
  the statement (an `if`, a method) is deleted too, every other statement in that block must hold a
  `removed` row's literal itself, or the row's note must start with `whole block:` to ask for the
  whole block: a block that also held `label.setText(format(value))` cannot disappear behind one
  accessibility-label row. Every `removed` row is listed as a warning, with the statement it
  deletes (or the entire deleted block), for the reviewer.
- Nothing else changes around text. In a changed region that holds or held a literal, the head may
  differ from the base only where a row's expression became the call that serves it (plus a
  `.render()` on a `Msg`), or a `removed` row's statement was deleted. Wrapping a kept literal or
  the new call (`"Skip".toUpperCase()`, `T.statsWindowTitle().toUpperCase()`) or dropping code
  beside it fails. A `manual:` row frees its whole statement, so it is listed for a hand check.
  Changes in regions with no text, and around a `stays-english` or `id-kept` move, are listed as
  warnings.
- `stays-english`, `id-kept`: the literal moved within `src/` and is still English. The same value
  must be added somewhere else in `src/` (outside the changed region it left), and the lines it
  left must either only lose tokens (code moved out whole) or name the constant it moved to
  (`NAME`, or `Class.NAME` from another file).
- An extraction batch adds no literal to `src/` other than `""`: new text goes to the catalog, and
  a literal that moved needs a `stays-english` or `id-kept` row. Each new literal fails G1.
- Ledger files are append-only. Every ledger at head is checked against the catalog on every pull
  request, so rewording an extracted string fails unless `l10n/english-changes.tsv` records it.
- A row whose location is a test file must be `removed`, for a test of deleted code (G4 below).

### Pull requests that are not batches

CI (`tools/l10n/ci_gates.py`) decides what kind of change a pull request is from the paths it
changes since the merge base:

| Kind | When | Gates |
|---|---|---|
| `batch` | it changes `l10n/ledger/` or a `res/values*/strings_*.xml`, or a `src/` file gains a `T.`, `M.`, `R.string` or `R.plurals` reference | every gate, strict; proof tooling unchanged |
| `english-change` | it changes `l10n/english-changes.tsv` | G1 with the recorded changes, G8 shrink-only; G4 and the golden diff report only; no ledger rows may be added, and every recorded key must be in the catalog |
| `ordinary` | anything else | baselines list every finding; ledgers agree with the catalog |

- **An ordinary feature or fix** may change English, tests and report formats. If it adds UI text
  to `src/`, `hardcoded_lint.py --check` fails until the baselines list it: run
  `python3 tools/l10n/hardcoded_lint.py --write-baselines` and commit the result (from B0b on,
  prefer putting new text in the catalog). If it changes a report format, update the goldens with
  `bash tests/l10n/golden.sh --update` and say so in the PR.
- **An owner-approved English change** (plan part D, after the migration) edits the catalog text,
  adds one row per changed key to `l10n/english-changes.tsv`
  (`key	quantity	old	new	note`, quantity `-` for a string, the note naming the owner's
  approval), updates the tests that assert the old English, and shows the census diff.

## Threat model

The gates are a safety net for **honest but careless** batch authors: people and LLM agents
converting thousands of literals, who will sometimes drop a word, swap two keys, delete a line too
many or "tidy" a test. The rule is that every English change either fails a gate or shows up in a
reviewer-facing warning that prints the affected code. Codex and human review are the second line,
and they read those warnings.

Deliberate sabotage of the harness is out of scope for automation: reflection, process exits,
shadowing a class, generated-code tricks or decoys built to fool a tool. The gates refuse the easy
forms of these anyway, but closing every route is review's job. A route counts as a gate bug only
when an honest author could plausibly hit it by accident.

Known limits:

- A day/month swap where both numbers are 12 or under (`10/5` against `5/10`) is two valid dates,
  so the census (G7) cannot see it; G10's `Fmt` parity tests are what catch a changed date order.
- `manual:` ledger rows (ternaries, `String.format`, `StringBuilder` chains) are checked by hand:
  G1 only checks their literals' order and lists the row and its statement for the reviewer.
- `whole block:` removed rows, `as` holes and code changes away from any text are warnings, not
  failures: the reviewer must read them.
- G7 needs an English-pinned emulator and does not run in CI; its result is pasted into the PR.
- CI runs the pull request's own copy of the tools, so the "proof tooling unchanged" check and
  review are what stop a batch from weakening them.

## The gates

| Gate | Tool | Runs in |
|---|---|---|
| G1 ledger | `tools/l10n/ledger_check.py --base origin/main` (drafts: `extract.py`) | CI (batches), prove-english |
| G2 template equivalence | `tests/l10n/LedgerTest.java` (B0b) | CI via `tests/run.sh` |
| G3 aapt round-trip | `tools/l10n/roundtrip.py`, run by `build.sh` | every local build |
| G4 frozen test statements | `tools/l10n/frozen_literals.py --base origin/main` | CI (batches), prove-english |
| G5 characterization first | `tools/l10n/char_first.py --base origin/main` | CI (batch pull requests), prove-english |
| G6 existing suites | `tests/run.sh` and the sweep's `--only` steps | CI, emulator |
| G7 screen text census | `android-tests/TextCensus.java` (B0b), `tools/l10n/census_diff.py` | emulator |
| G8 hard-coded text lint | `tools/l10n/hardcoded_lint.py --check --base origin/main --tight` | CI (batches; others: `--check`), prove-english |
| G9 reports stay English | `bash tests/l10n/golden.sh`, goldens unchanged since the merge base (and `ReportsStayEnglishTest`, B0b) | CI (the diff: batches), prove-english |
| G10 format legacy parity | `tests/l10n/FmtLegacyTest.java` (B1) | CI via `tests/run.sh` |

The tools' own tests: `python3 -m unittest discover -s tools/l10n` (standard library only). CI
runs them, `gen_catalog.py --check` and `tests/l10n/golden.sh` on every push and pull request, then
`ci_gates.py`. CI runs the pull request's own copy of these tools; the batch rule above (no tooling
changes in a batch) and review are what keep a batch from weakening them.

**G3.** The round-trip reads the default-configuration strings and plurals straight from the
`resources.arsc` aapt wrote and compares them with `en.expected.tsv`. It also fails on compiled
strings the catalog does not know, on styled strings, and on text in any English or non-locale
configuration. CI cannot run aapt, so CI runs the unit tests against fixtures captured from the
real aapt (`fixtures/aapt/`, `fixtures/aapt-qualified/`); refresh them with
`tools/l10n/fixtures/aapt/capture.sh` after changing a fixture's `res/`.

**G4.** A test *statement* (the tokens between `;`, `{` and `}`) that holds an English-looking
literal must stay token for token as it was; new statements may be added. So an assertion cannot be
weakened, swapped, or have its expected text changed while a decoy keeps the old literal. Spacing,
comments and a class qualifier in front of a call (`TextFit.assertText(...)`) do not count.
Every existing test file also keeps all of its code, English or not: its tokens stay in order with
nothing changed between them, so an assertion cannot be wrapped in `if (false) { ... }` and a
helper such as `check()` cannot be edited. Code may only be added as whole statements or members,
and added code may not jump out of the code around it (`return`, `break`, `continue` inside a
method; `throw` anywhere, since a new helper that throws skips the code that called it), assign to
a variable it did not declare, declare a member (or a local hiding a field or type) named like
anything in the file, declare a type or initializer block, import a name the file uses, or call
`exit`, `halt`, `Runtime`, uncaught-exception handlers, `setOut`/`setErr`, `setDefault`,
`setProperty` or `setSecurityManager`. Nor may it reach them indirectly: reflection (`getMethod`,
`invoke`, `forName`, `getClass`, a `Class` literal such as `int.class`), method handles, class
loaders, threads, processes and script engines are refused, because a method name can hide in a
string (`"ex" + "it"`). Added code also may not use a type declared in a file the change adds (in
`src/` or the tests): new files are not limited, so a helper there could exit before the first
check. A new test file may not declare a type named like a `src/` class, nor a top-level type named
like a `java.lang` type (`AssertionError`, `String`, `System`) or like a top-level type an existing
file under `tests/` or `android-tests/` declares: compiled together, the new one would replace it in
every existing test. Nested types are not limited. A statement cannot move
out of an existing file. A statement may disappear only for a deleted test of deleted code:
a `removed` ledger row naming that test file and a line of the statement, where the change adds no
English in its place and the English does not come back in a new statement. "English-looking"
means a literal with two letters in a row (or a non-ASCII letter) that is not a lower-case key,
path or id.

**G5.** Characterization tests are the files under `tests/l10n/characterization/`. Every one the
pull request adds or changes must be added by its first commit and left alone afterwards, and that
commit changes nothing under `src/` or `res/`. A batch with a `template` or `plural` ledger row must
have them. `prove-english.sh` runs `tests/run.sh` and `tests/l10n/golden.sh` at the first commit,
and fails when a characterization test is not listed in a `tests/l10n/*.sources` file.

**G8** is a Python tool run in CI, instead of the plan's `tests/l10n/HardcodedTextLint.java`, so it
shares the Java lexer with the other gates. It flags any literal with letters inside a platform UI
sink call (`setText`, `setContentDescription`, `setHint`, `setTooltipText`, `setStateDescription`,
`setError`, `setTitle`, `setMessage`, `set*Button`, `toast`, `makeText`, `notice`, notification
titles, texts and actions, `announceForAccessibility`, `setAccessibilityPaneTitle`,
`setChartTitle`, `drawText`); any English-looking literal inside a call of one of Volt's own text
helpers (`HELPER_SINKS` in the tool: `ui.text`, `button`, `secondaryButton`, `caption`,
`sectionLabel`, `row`, `detailRow`, `dialogButtons`, `new SegmentedSelector`, and more), so
single-word labels count; and any other literal of two or more words. Baselines list findings
without line numbers, so a rebase only needs `--write-baselines` when text really changed. A file
with no findings has no baseline, so any new English in it fails. The shrink-only check counts from
the first merge base that has the tool, so deleting every baseline does not switch it off. The
allowlist for converted files that must keep English (reports, tags, keys) arrives with the first
batch that needs one.

**G9.** `tests/l10n/golden.sh` compiles `GoldenReports.java` against the pure classes, runs it under
en-US, de-DE, tr-TR and ar-EG with different time zones, and compares the diagnostics JSON, the ride
journal CSV, the phone ride CSV and the GPX with `tests/l10n/golden/`. `--update` rewrites them;
use it only for an intended, reviewed format change, never in a batch: CI fails a batch whose
goldens differ from the merge base.

**B0a extra check.** `prove-english.sh <base> --same-apk` builds the merge base and head in clean
worktrees and requires byte-identical `classes.dex` and `resources.arsc`.

## Pseudo-locales

`VOLT_PSEUDO_LOCALES=1 bash build.sh` also packages the same `classes.dex` with
`aapt --pseudo-localize` into `build/pseudo/volt-pseudo.apk` (never `dist/`), with en-XA (accented,
bracketed, longer) and ar-XB (right-to-left). The build checks that the resource ids are unchanged
and that both configurations are present. Every build runs a release guard on `dist/volt.apk`:
`aapt dump configurations` must not list en-XA or ar-XB, and no text may sit in an English or
non-locale configuration. Known aapt1 quirk: a trailing `%` is mangled (`75%` becomes `[75»%`).

## Census format (G7)

One UTF-8 file per captured screen, `<variant>-<label>.census.tsv`. The first line is
`volt-text-census-v1`; then one record per line: `field`, `locator`, `bounds`, `value`, tab-separated.
Fields: `text`, `desc`, `hint`, `tooltip`, `state`, `error`, `title`, `message`, `button`, `item`,
`toast`, `notif-title`, `notif-text`, `notif-subtext`, `notif-action`, `notif-channel`. The locator
is `tag=<tag>` when the view has a String tag, else the class path with child indexes; bounds are
`x,y,width,height` or `-`. Backslash, tab, line feed and carriage return are escaped as `\\`, `\t`,
`\n`, `\r`.

`census_diff.py` masks the *values* of clock times, durations and dates and keeps their form: each
number becomes `<n>`, a month or weekday becomes its form (`<MMM>`, `<MMMM>`, `<MMM:upper>`,
`<EEE>`, `<EEEE>`), AM/PM keeps its case, and unit words stay as written (`<n> min`, `<n> hrs`).
Singular and plural unit words may differ only when the number moves between 1 and not 1
(`1 minute ago` then `2 minutes ago`); otherwise they must match, so `1 seconds left` cannot become
`1 second left` and `2 seconds` cannot become `3 second` unseen. A zero-padded number cannot turn
unpadded (`05 min` against `5 min`), a numeric date keeps its year width, and a day above 12 cannot
move between a numeric date's first two groups. Bounds changes are reported without failing. A
day/month swap with both groups 12 or under (`10/5` against `5/10`) is two valid dates and cannot
be told apart from the text; that order is G10's to catch (Fmt keeps each call site's pattern).

## Languages

`l10n/lang/<tag>.tsv` has a `field	value` header and these fields: `tag` (BCP 47, e.g. `pt-BR`),
`folder` (`values`, `values-es`, `values-b+zh+Hans`), `endonym`, `wave`, `status` (`held`, `draft`,
`beta`, `stable`; only beta and stable ship), `formality`, `plurals` (CLDR categories),
`quote_style`, `percent_style`, `decimal_style`, `script`, `font` (`saira` or `system`), `rtl`,
`unit_symbols`, `drive_mode_names`.

## Not built yet

The translation tools (`pack.py`, `merge.py`, `check.py`) arrive with the Spanish pilot, after the
three-provider drafting pipeline is designed. The B0b-harness pull request adds the `tests/run.sh`
hook and `android-tests/TextCensus.java`; B0b adds `Msg`, `VoltApp` and the catalog tests.
