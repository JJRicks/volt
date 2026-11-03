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
| `tools/l10n/` | Generator, round-trip, gates, the CI driver (`ci_gates.py`), the translation tools and their unit tests |
| `l10n/glossary/<tag>.tsv` | A language's approved glossary terms (same columns as `en.tsv`; see Running a language) |
| `l10n/style/<tag>.md` | A language's punctuation, capitalization and abbreviation rules for ladder rungs |
| `l10n/packs/<tag>/<area>.json` | Work packs from `pack.py` (generated, not committed) |
| `l10n/work/<tag>/<area>/` | Translation staging: drafts, checks, back-translations, judge requests and rulings |
| `l10n/decisions/<tag>/<area>.jsonl` | The audit trail of every translated or fallen-back key |
| `res/values-<qual>/strings_<area>.xml` | A language's text, written only by `merge.py` |

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
  a literal that moved needs a `stays-english` or `id-kept` row. Each new literal fails G1, except
  a lower-case key or id such as a preference name (`"app_language"`) or a `requireNonNull` message
  (`"msg"`): letters and digits joined by `_` or `.`, two characters or more. G1 lists every such
  literal with its statement for the reviewer, and it alone does not make a change "around text".
- Ledger files are append-only. Every ledger at head is checked against the catalog on every pull
  request, so rewording an extracted string fails unless `l10n/english-changes.tsv` records it.
- A row whose location is a test file must be `removed`, for a test of deleted code (G4 below).

### Pull requests that are not batches

CI (`tools/l10n/ci_gates.py`) decides what kind of change a pull request is from the paths it
changes since the merge base:

| Kind | When | Gates |
|---|---|---|
| `batch` | it changes `l10n/ledger/` or a `res/values*/strings_*.xml`, or a `src/` file gains a `T.`, `M.`, `R.string` or `R.plurals` reference (bare or spelled `com.jjricks.volt.T.`, and so on) | every gate, strict; proof tooling unchanged |
| `english-change` | it changes `l10n/english-changes.tsv` | G1 with the recorded changes, G8 shrink-only; G4 and the golden diff report only; no ledger rows may be added, and every recorded key must be in the catalog |
| `ordinary` | anything else | baselines list every finding; ledgers agree with the catalog |

Every kind also refuses a static import of a `T`, `M` or `R` member in `src/`: a bare call through one
looks like any other method, so CI could not tell a conversion from ordinary code. Call `T.x()`.

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

## Running a language

The translation tools follow plan 7.1-7.4. Drafts come from **three providers**: Claude (Claude Code
agents, which write draft files), OpenAI and Google Gemini (called by `council.py` over HTTPS). A
language runs area by area once the migration gate has passed, D3 has frozen the English terms and
the owner has confirmed the language's decisions below.

### Decisions per language

Owner decisions of 2026-11-02: the 7.4 table as proposed (German **du**), and **both** Portuguese
variants. Plural categories are CLDR's.

| Language | `tag` | `folder` | "You" | `plurals` |
|---|---|---|---|---|
| Spanish (pilot) | `es` | `values-es` | tú | one,many,other |
| German | `de` | `values-de` | du | one,other |
| French | `fr` | `values-fr` | vous | one,many,other |
| Italian | `it` | `values-it` | tu | one,many,other |
| Portuguese (Portugal), the base for every non-Brazil speaker | `pt-PT` | `values-pt` | tu | one,many,other |
| Portuguese (Brazil) | `pt-BR` | `values-pt-rBR` | você | one,many,other |
| Dutch | `nl` | `values-nl` | je | one,other |
| Polish | `pl` | `values-pl` | ty, impersonal for instructions | one,few,many,other |
| Swedish | `sv` | `values-sv` | du | one,other |
| Indonesian | `id` | `values-in` (legacy code; localeConfig says `id`) | Anda | other |

Later waves use the plan's 7.4 proposals once the owner confirms them; RTL languages stay `held`.
Defaults until the owner says otherwise: SI unit symbols stay (km/h, V, A, Wh) and words are
translated; drive-mode names stay as the scooter shows them; ScooterSimulation text stays English.

### One-time setup per language

1. `l10n/lang/<tag>.tsv` with `status` `draft` (only `beta` and `stable` ship). For example:

   ```
   field	value
   tag	es
   folder	values-es
   endonym	Español
   wave	pilot
   status	draft
   formality	tú
   plurals	one,many,other
   quote_style	«…» “…”
   percent_style	%1$s %%
   decimal_style	1.234,5
   script	Latn
   font	saira
   rtl	false
   unit_symbols	si
   drive_mode_names	english
   ```

2. `l10n/style/<tag>.md`: punctuation, capitalization and how to abbreviate ladder rungs.
3. `l10n/glossary/<tag>.tsv`, drafted by the three providers and judged before any screen: the same
   columns as `en.tsv`, where `id` names the `en.tsv` term and `term` holds the approved
   translation, with inflected forms separated by ` | ` (`patinete | patinetes`). List the nominal
   forms too where the language makes nouns from verbs (`bloquear | bloqueo`, `verrouiller |
   verrouillage`): only the listed forms count where the English has the exact term, and the
   automatic -ing matching covers few of them (Indonesian pe(N)-...-an nouns such as "penguncian").
   Do-not-translate terms need no row.

### API keys

The owner keeps the keys in `~/.config/volt-council/keys.env`, readable only by the owner:

```sh
chmod 600 ~/.config/volt-council/keys.env     # two lines: OPENAI_API_KEY=... and GEMINI_API_KEY=...
```

The tools read the keys **only** from the environment variables `OPENAI_API_KEY` and `GEMINI_API_KEY`.
Source the file in a **subshell** for each command (`set -a` exports what it sets), so the keys
reach only that one command and never stay in the shell or its history:

```sh
( set -a && . ~/.config/volt-council/keys.env && set +a && python3 tools/l10n/council.py draft openai --tag es --area common )
```

Never print, `cat`, copy or commit the file, never run these commands with `set -x`, and never paste
a key into a chat or an issue. The tools never log, print or store a key or a header carrying one;
an HTTP error shows only the status code and the provider's message with anything key-shaped
replaced by `[redacted]`. Only `models`, `draft`, `redraft` and `backtranslate` with `openai` or
`google` need the keys; every other step works without them.

### Steps, per language and area

```sh
T=es A=common
python3 tools/l10n/pack.py --tag $T --area $A                       # 1. the work pack
( set -a && . ~/.config/volt-council/keys.env && set +a && python3 tools/l10n/council.py draft openai --tag $T --area $A )
( set -a && . ~/.config/volt-council/keys.env && set +a && python3 tools/l10n/council.py draft google --tag $T --area $A )
python3 tools/l10n/council.py prompt --tag $T --area $A --name opus-context --style context-first
#   2. give the prompt file to a fresh Claude Code agent; it writes its reply and runs import-claude
python3 tools/l10n/council.py check --tag $T --area $A               # 3. automated checks
( set -a && . ~/.config/volt-council/keys.env && set +a && python3 tools/l10n/council.py redraft openai --tag $T --area $A )
python3 tools/l10n/council.py prompt --tag $T --area $A --name opus-context --redraft   # Claude's redraft
python3 tools/l10n/council.py check --tag $T --area $A
( set -a && . ~/.config/volt-council/keys.env && set +a && python3 tools/l10n/council.py backtranslate google --tag $T --area $A )
python3 tools/l10n/council.py judge-requests --tag $T --area $A      # 4-5. then a separate Opus judge session
python3 tools/l10n/council.py decide --tag $T --area $A              #      writes judge.jsonl; then decide
python3 tools/l10n/merge.py --tag $T --area $A                       # 6. res/values-<qual>/ and validation
python3 tools/l10n/council.py beta --tag $T                          # the owner's beta bar
```

1. **Pack** (`pack.py`): for each UI string of `res/values/strings_<area>.xml` (never
   `translatable="false"` keys, `l10n_tag`, reports, diagnostics or user data, which never live
   there), the English, its metadata and note, the English width measured with Saira and its
   budget, glossary hits with the approved terms, two neighbours on each side, and any current
   translation with whether its English changed (`status`: `new`, `stale` or `current`). Only new
   and stale keys are drafted, plus every rung of their ladders. A ladder rung without its own
   `volt:safety` takes its full key's.
2. **Drafts**, independently. `council.py draft openai|google` asks each provider's model-list
   endpoint which models exist and picks one per `tools/l10n/council_models.json` (first matching
   pattern, newest version; `--model` or `--prefer` overrides), then drafts in JSON mode
   (OpenAI's Responses API, `"store": false`; Gemini's `generateContent`). Each draft records the
   exact model id, and the id the API reported. Claude drafts come from Claude Code agents, each
   in its own session with a different configuration (`--style` `context-first`,
   `glossary-first` or `draft-review`, on different models): `council.py prompt` writes the
   agent's instructions; the agent writes its JSON reply and runs `council.py import-claude ...
   --from <reply> --model <its model id>`, which writes `drafts/claude-<name>.xml` with the right
   escaping. An agent may write that XML itself; `import-claude` without `--from` validates it.
   **Every draft entry records the English it translated** (`volt:src`, the source hash below):
   API drafts are stamped from the pack they were made from, and each string in a Claude prompt
   carries a `src` that the agent copies unchanged into its reply (`"src": {"<key>": "<src>"}`)
   or onto its XML element. An entry with no `volt:src`, or another English's, is stale: `check`
   fails it (`source`), `import-claude` reports it, and every later step ignores it, so it is
   redrafted, never relabelled with the current hash. A key whose drafts are all stale falls back
   like any undecided key, and its decision lists them in `stale_drafts`.
3. **Checks** (`check.py`, run by `council.py check`): placeholders, numbers, plural categories,
   escapes, dnt tokens and markup, glossary, whitespace, Saira glyph coverage, ladder rungs and width
   budgets (`java.awt` with `res/font/saira_medium.ttf`, headless, kerning on; without a Java
   runtime, the summed advance widths from the font's `hmtx` table, which errs a little wide), polarity
   words and mixed-script leakage. Glossary terms match as whole words ("bloquear" is not found
   in "desbloquear"), with the endings an inflecting language adds; a lock term next to words that
   make it unlock ("lås upp", 解除锁定) or remove the lock ("Quitar el bloqueo") is not found. A plural category that covers more than one number (Russian "one" also
   covers 21; Brazilian "one" covers 0) keeps the count placeholder. Polarity runs both ways: a
   negation the English lacks warns, and is an error in a safety key; in a safety key an English
   un-word needs a negation word or the glossary's term, not just a prefix like "in-". A Latin
   word that is not in the English is an error in a non-Latin language, except an acronym or a
   name in `LATIN_LOANS`, which warns. `checks.jsonl` records each finding; a failed key goes back to
   its drafter once (`redraft`, or `prompt --redraft` for Claude) and is dropped if it fails again.
4. **Agreement**: after normalizing (NFC, whitespace, quote style), two or more passing drafts that
   all agree are accepted. Otherwise `judge-requests` lists the key for a separate Opus judge
   session, which picks or merges; a merged text must pass the checks again. A pick names the draft
   and copies its text, and counts only while that draft still has that text: a redrafted
   candidate needs a new ruling and falls back until it has one. A `fallback` ruling is not bound
   to the drafts it saw (it keeps English, so it can never ship a wrong text): after redrafting a
   key the judge sent to English, delete that ruling or judge the key again, or it stays English.
5. **Safety keys** (any `volt:safety`): **every drafter must agree**, with drafts from all three
   providers that all pass the checks; a model that **drafted nothing** back-translates the agreed
   text (`backtranslate`, or `prompt --role backtranslate` and `import-backtranslation` for a
   Claude agent on a model that did not draft: each such prompt gets a request id, the hash of
   exactly what it showed, recorded in `prompts/backtranslation.request.<id>.json` and never
   overwritten; the agent copies the id and each text's `target_sha1` into its reply, and
   `import-backtranslation` refuses a reply without a known id or with a text hash its request
   did not show, and drops a key whose English or agreed text changed since that request, so a
   later prompt cannot adopt an earlier agent's answer); and the judge, shown that text and its
   back-translation and naming both in its ruling, confirms every item: locked and
   unlocked stay distinct, on/off and never/always are not inverted, "unverified" or "unknown"
   never reads as confirmed, numbers, units and the named action are unchanged, "not affiliated
   with NAVEE" is not softened, and the back-translation keeps the meaning. Anything less **falls
   back to English**: the key is left out of `values-<qual>`, which is safe. A ladder falls back
   as a whole when any rung does.
6. **Decide and merge**: `decide` writes `l10n/decisions/<tag>/<area>.jsonl` and refuses while a
   judge ruling or back-translation is missing (`--final` lets those keys fall back). `merge.py`
   writes `res/values-<qual>/strings_<area>.xml`, each string after a `<!-- src:<sha1> -->` comment,
   plus `l10n_tag` in the area that holds the default one, then validates the whole folder (every
   key in the default and translatable, placeholders, plural categories, current sha comments,
   complete ladders, `l10n_tag`, the folder listed in `l10n/lang/`) and compiles it with aapt
   36.1.0 when the SDK is installed, comparing what aapt stored with the decisions' text.
7. **The beta bar** (owner, 2026-11-02): a language may ship as `beta` only when at most **2%** of
   its keys fall back to English and **no lock or alarm** key falls back. `council.py beta` checks
   it across every area (a key whose English changed after its decision counts as a fallback).
   Releases still need the owner's explicit authorization.

The language pull request then runs the local build, LocaleValidation in that language at both
sizes, and a screenshot set for the owner (plan 7.2 step 6). Native spot-check edits go into the
decisions (and merge again), never straight into `res/values-<qual>/`.

### Limits of the automated checks

`check.py` and `lexicon.py` are a **first filter**. They reject the common ways a draft goes wrong
cheaply, before anyone pays for a judge, but word lists cannot read a sentence, so they are not
the guarantee that a lock, alarm, power or speed key means what the English means. That guarantee
is step 5: every drafter agrees, a model that drafted nothing back-translates the agreed text,
the judge rules on that exact text and its back-translation, and anything less falls back to
English. The checks' job for a safety key is to fail closed: a false alarm sends a correct key
back to English (a coverage cost), and a miss is left to step 5.

What they catch reliably: broken placeholders, numbers, plural categories, escapes, markup,
protected tokens, glyphs and widths, which are mechanical; a glossary term that is missing
(Unlock is a term of its own, so "Unlock scooter" drafted as "Bloquear el patinete" lacks
"desbloquear"); the lock word inside its own unlock word (desbloquear, entsperren,
разблокировать, 解锁, ロック解除);
unlock written as the lock word plus the common separate word (sv "lås upp", id "buka kunci", ar
"فتح القفل", zh 解除锁定, ja ロックを解除, ko 잠금 해제); the lock said to be removed or turned off in
the first-wave languages and ru, uk, el, ja and zh ("Quitar el bloqueo", "Zdejmij blokadę",
"Sperre aufheben", "Vergrendeling uitgeschakeld", "Снять блокировку"), also after a status colon,
dash or parenthesis ("Sperre: aus"); a negation dropped or added where the language has a negation
list; and, in a safety key of a first-wave language, an English on or off state drafted with only
the other state's words ("Lock: on" as "Bloqueo: desactivado", "Alarm sound off" as "Sonido de
alarma activado"; `lexicon.STATE_WORDS`). A state word right after a negation word counts as the
other state ("nicht aktiv" and "tidak aktif" are off, "nicht ausgeschaltet" is on), and so does
one after a negation with only a copula, auxiliary or reflexive or clitic particle between ("no
está activada", "nie jest włączony", "nicht mehr aktiv", French "ne sera plus activée"). Any other
word between, such as a verb that takes an infinitive, takes the negation instead ("No olvides
apagar la alarma", "N'oubliez pas d'éteindre", "Jangan lupa matikan", "Glöm inte att stänga av"),
and French "ne ... que" (only) and Italian "non appena" (as soon as) negate nothing. Lock and Unlock
also match their English -s and -ing forms ("before locking", "This file unlocks your scooter",
"Unlocking…"); there the target may take any form that starts like an approved one ("bloqueando",
"Blokowanie", "zablokowaniem"), or the Locked or Unlocked target ("Wird gesperrt…"), still never
inside the opposite term. In Indonesian the target's first word may also take its pe(N)-...-an
noun ("Penguncian gagal" for "Locking failed", "Pembukaan kunci gagal" for "Unlocking failed").

Two rules keep correct drafts from failing. A term found only inside a longer one ("Lock" in "Lock
duration", "Low" in "Low battery alert") is not required on its own. A one-word adjective whose id
is another term's id plus a suffix (`energy-recovery-off`) is that setting's value, required only
where the text is the value alone or names the setting, so "Turn off charge alerts" does not need
the Energy recovery level "Desactivada". In German, Dutch and Swedish a lock target may start a
compound ("Sperrdauer", "Vergrendelstatus", "Låstid"), never after ent-, ont- or upp- and not when
the rest of the compound removes the lock ("Sperraufhebung").

Known gaps, by language family:

- **Romance (es, pt, fr, it) and Germanic (de, nl, sv, nb, da)**: the removal and on/off state
  words are lists; an on or off said another way, or a target with both states' words, is left to
  step 5. The state of a whole sentence is one bag of words, so a long sentence that names one
  state and turns another thing off must use a listed verb form (clitic forms such as "apagarla",
  "spegnerlo", "desligá-lo", "mematikannya", "auszuschalten" and "uit te zetten" are listed). A lock compound is found only with the lock word first ("Rollersperre" is not), and the
  other terms match as whole words, so their compound forms go in the glossary. The removal words
  are common verbs, nouns and participles, with articles and one genitive ("del patinete") allowed
  between. A rarer verb, a longer gap, or the verb far from the lock word ("Quita ahora mismo el
  bloqueo") passes. German and Dutch separable verbs are caught only at the end of the text
  ("Hebe die Sperre auf"). A prefix like "des-" or "dé-" is only a guess for polarity.
- **Slavic (pl, ru, uk, cs, sk, hr)**: inflected glossary targets match any ending of up to four
  letters after a stem of five or more letters, so a short stem must be listed in every form.
  The removal lists cover pl, ru and uk only; cs, sk and hr have none yet.
- **Greek**: matched without accents; the removal list is short.
- **Indonesian and Malay**: word order is free and affixes are many; only the listed buka-,
  lepas-, nonaktif- and di- forms are caught.
- **Arabic and Urdu**: written without short vowels, so غيّر (change) and غير (not) can be the same
  letters; a bare غير counts as a negation. Only the listed unlock verbs are caught.
- **Hindi and Bengali**: unlock is caught only with खोल/খুল after the lock word.
- **Chinese, Japanese, Korean** (no word spaces, or particles): a curated word list stands in for
  word boundaries; an ordinary word that contains a negation character and is not in the list
  reads as a negation (fails closed), and a context word in another role can hide or reject the
  lock term. zh 打开…的锁 and 关闭…锁定 count as unlock only when the lock word ends the clause.
- **Languages with no list yet** (fi, hu, ro, tr and others in the later waves): negation and
  removal are not checked at all; polarity only warns, and the judge decides.
- **Negated states**: a negation further from the state word than the allowed distance (the word
  right before it, or one listed copula or particle between) is not read, so the state counts as written ("no se encuentra activa", "non sarà più attivo" are
  read as on); the lists of words allowed between are short (`lexicon.STATE_NEGATION_GAP`).
- **Lock verb forms**: when the English has the exact term inside a longer term ("Lock duration:
  time before locking"), the -s or -ing form of the inner term is not required, so "antes de
  desbloquear" there passes. Where only an -s or -ing form is required (loose mode), any word
  that begins with an approved target's opening letters counts, so a different word with the same
  start passes (the opening "bloqu" of "bloquear" accepts any "bloqu-" word, and "kunc" any "kunc-"
  word).
- **Everywhere**: the removal context is skipped when the English itself says off, remove,
  disable, release, clear, cancel, delete, open or end, so an inversion in such a key is left to
  step 5. Removal word lists apply to the lock terms only (an alarm or cruise-control term keeps
  its ordinary verbs); the on/off state lists apply to every safety key. A term needs an approved
  target in the language before it is required (Unlock included). Polarity cannot see an inversion
  made with another antonym, an on/off inversion in a later-wave language (no state list yet), nor
  a sentence that is fluent and wrong.

Later-wave languages (ru, uk, el, zh, ja, hi, bn, ar, ur, da, nb, fi, cs, sk, ro, hu, hr, tr) need
their lists reviewed before their wave starts.

### Formats

- **Source hash**: SHA-1 of the UTF-8 bytes of `kind NUL (quantity NUL text NUL)*`, where `kind` is
  `string` or `plurals`, a string has the single quantity `other`, plural quantities go in CLDR
  order (zero, one, two, few, many, other), and the text is the decoded English the user sees.
  Metadata does not count. CatalogCheckTest (B0b) computes the same hash to find stale translations.
- **Drafts** (`l10n/work/<tag>/<area>/drafts/<name>.xml`): Android resource XML, names
  `claude-<config>`, `openai`, `google`. The root carries `volt:provider`, `volt:model` (exact id)
  and, when different, `volt:model-reported`. Every `<string>` and `<plurals>` carries
  `volt:src="<sha1>"`, the source hash of the English it translated (copied from the prompt's
  `src` by a Claude agent); an entry without it, or with another hash than the current English's,
  is stale and ignored. A redrafted key carries `volt:attempt="2"`.
- **`checks.jsonl`**: `{"draft", "provider", "model", "attempt", "key", "source_sha1", "draft_src",
  "ok", "findings": [{"check", "severity", "quantity", "message"}], "width_method"}`; `draft_src`
  is the entry's `volt:src`, and a stale one fails the `source` check.
- **`backtranslation.jsonl`**: `{"key", "source_sha1", "target", "back", "provider", "model",
  "model_reported"}`; a text is a string or `{quantity: text}`.
- **`prompts/backtranslation.request.<id>.json`** (written by `prompt --role backtranslate`, one per
  distinct prompt, never overwritten): `{"format": "volt-l10n-backtranslation-request-v1", "id",
  "tag", "area", "keys": {<key>: {"kind", "source_sha1", "target", "target_sha1"}}}`. `id` is the
  SHA-1 of the compact sorted JSON of the format, tag, area and each key's
  `[kind, source_sha1, target]`; `target_sha1` is the SHA-1 of the target's compact sorted JSON
  (a string or `{quantity: text}`). A Claude agent's reply is `{"request": <id>, "strings":
  {<key>: <back-translation>}, "target_sha1": {<key>: <target_sha1>}}`, both copied from its
  prompt. `import-backtranslation` loads the request by that id and checks it against its id;
  it refuses the whole reply (nothing stored) when the id is missing or unknown, or a key is not
  in that request or its `target_sha1` is not that request's, and stores a key only while the
  request's `source_sha1` and `target` are still the current English and agreed text.
- **`judge.jsonl`** (written by the judge): `{"key", "source_sha1", "model", "verdict", "pick",
  "text", "back", "reason", "confirm", "backtranslation_meaning_kept"}`, verdict `pick`, `merge`,
  `confirm` (safety keys) or `fallback`; `confirm` maps each of `lock_states_distinct`,
  `on_off_never_always_not_inverted`, `unverified_never_confirmed`,
  `numbers_units_action_unchanged` and `disclaimer_not_softened` to `true`. A pick copies the
  picked candidate's text into `text` and counts only while that draft still has it (after
  normalizing); a pick without it, or of a changed draft, waits for a new ruling. A confirm also copies
  the request's `text` (the agreed text) and `backtranslation.back` into `text` and `back`; it
  counts only while both still match the current agreed text and back-translation, so a changed
  draft needs a new ruling. A safety key reaches `judge-requests` only after a current
  back-translation exists. A ruling whose `source_sha1` is not the current English is ignored.
  A ruling carries what it is bound to (English hash, picked or confirmed text, back-translation),
  and `decide` checks those against the current drafts, never against `judge-requests.jsonl`,
  so rewriting the requests while a judge works cannot rebind an earlier ruling.
- **`decisions/<tag>/<area>.jsonl`**: one line per key: `format`
  (`volt-l10n-decision-v1`), `key`, `kind`, `source_sha1`, `english`, `safety`, `outcome`
  (`agreed`, `judged`, `safety-agreed` or `fallback-english`), `text`, `reason`, `alternatives`
  (every current draft with its model ids, attempt, text and findings), `stale_drafts` (each
  ignored entry: `draft`, `provider`, `model`, `src`, `why`), `models` (`drafters`, `judge`,
  `backtranslator`), `checks`, `judge` and `backtranslation`.
- `runs.jsonl` logs each provider call and import (stage, provider, model ids, key counts; never a
  key). `prompts/` (with its request records) and `replies/` are regenerated and not committed
  (`l10n/.gitignore`); a reply whose request record is gone is refused, so prompt again.

The B0b-harness pull request still has to add the `tests/run.sh` hook and
`android-tests/TextCensus.java`; B0b adds `Msg`, `VoltApp` and the catalog tests (including
CatalogCheckTest, which mirrors `merge.py`'s validation).
