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
| `tools/l10n/` | Generator, round-trip, gates, the CI drivers (`ci_gates.py`, `ship_check.py`), the translation tools and their unit tests |
| `l10n/glossary/<tag>.tsv` | A language's approved glossary terms (same columns as `en.tsv`; see Running a language) |
| `l10n/style/<tag>.md` | A language's style guide; `l10n/style/README.md` lists the sections every guide has |
| `l10n/packs/<tag>/<area>.json` | Work packs from `pack.py` (generated, not committed) |
| `l10n/work/<tag>/<area>/` | Translation staging: drafts, checks, back-translations, judge requests and rulings |
| `l10n/work/<tag>/<area>/audit/` | The safety audit of a Claude-only draft, a QA record only (see Drafting a whole language with Claude alone) |
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
  (a `double`), and `%%` for a percent sign. Number them from `%1$` with no gaps. A `%n$s` takes no
  width, precision or flag (`%1$-8s`, `%1$.2s`): `Fmt.compose` finds each argument by private-use
  marks at its ends, and a cut or padding would leak a mark into the text, so `gen_catalog.py`
  refuses them in the English and `check.py` and `merge.py` in every translation. Any text with
  `%` always goes through `String.format(Locale.ROOT, ...)`; locale-aware numbers come from `Fmt`
  as `%n$s` arguments. `formatted="false"` turns this off for a string.
- **Plurals** take the quantity first: `T.backupScootersSkipped(long quantity, long n)`. English
  uses `one` for exactly 1 and `other` otherwise, so an English plural holds only `one` and `other`
  items: `gen_catalog.py` refuses `zero`, `two`, `few` and `many` in `res/values/`, which
  `EnglishText` would never show but Android would on a phone whose first language has that category.
  `<string-array>` is banned for UI text.
- **Spelling rules.** `gen_catalog.py` refuses anything aapt would silently store differently:
  - leading or trailing whitespace, repeated spaces, raw tabs or line breaks: wrap the whole string
    in `"..."` (the quotes are not part of the text) or use `\n` and `\t`;
  - an apostrophe outside a wrapped string: write `\'`;
  - a double quote that is not the whole-string wrapper: write `\"`;
  - unknown escapes such as `\r` or `\ ` (aapt drops them); a leading `@` or `?` (write `\@`, `\?`);
  - markup other than `<xliff:g>` (styled spans are not supported).
  `\uXXXX` escapes are kept as written, including spaces. `tools/l10n/fixtures/aapt/` shows every
  case with what aapt 36.1.0 actually stored.
- **Symbols Saira lacks** may appear only from the list in "Glyph coverage" (below): ✓, ☆ and ★
  as they are, and ⚠ only as `⚠\uFE0E`. Any other character Saira lacks fails `GlyphCoverageTest`.
  A Java ⚠ without U+FE0E gets it in an ordinary pull request before a batch moves it (below).
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
  A wrong argument count or type is a compile error. `T.Plurals` is the pure plural-item selection
  that `AndroidText` uses (below).
- `M.java`: the same accessors returning a deferred `Msg`. Its contract with the hand-written
  `Msg` (B0b): `static Msg of(int id, Object... args)` and
  `static Msg plural(int id, long quantity, Object... args)`; `Msg.render()` calls `T.render` or
  `T.renderPlural`. If `Msg` implements `CharSequence`, a `Msg` can be an argument of another `Msg`.
- `EnglishText.java`: the English values; `T`'s default source and the fallback for a missing id.
- `AndroidText.java` (`--android`, app build only): ids mapped to `R.string` and `R.plurals`, and
  the plural folder table: for each plural, the `res/values-*` language folders that hold it, with
  their items (below).
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

Once a `res/values/strings_*.xml` exists, `tests/run.sh` generates the catalog (without
`--android`), compiles it with `Msg`, then reads every `tests/l10n/*.sources` file: each line is a
repository path to compile (`#` starts a comment; a hand-written `src/` class the batch's tests need,
such as `UserFacingException` or B1's `Fmt`, is listed there too), and every listed
`tests/l10n/**Test.java` runs from the repository root as `com.jjricks.volt.<Name>`. A batch adds
its own `.sources` file (its characterization tests, its new suites, and any new `src/` class they
need, as B10 lists `BackupException`) and never edits `tests/run.sh`.

## The runtime (B0b)

- `VoltApp` (the manifest's Application) installs `AndroidText` as `T`'s source in
  `attachBaseContext`, before any Activity, Service or ContentProvider starts, and again on every
  configuration change. Until then, and if that fails, `T` reads `EnglishText`.
- `AppLanguage` holds the language choice (`app_settings/app_language`, `""` for System default,
  excluded from backups). With System default, which is every installation until the Language row
  ships, `wrap` returns its argument and `resources` returns the application's own Resources.
- `Msg` is a deferred message (`M.<key>(...)`, `Msg.verbatim(name)` for text that is not from the
  catalog). It is a `CharSequence`, renders when read, and `english()` gives the English whatever is
  installed. `UserFacingException(Msg)` keeps English in `getMessage()`; screens show `msg()`.
- `ScreenHost.toast(Msg)` and `sheetContent(Msg, Msg, close)` render at display time.
- **Plural items follow the folder Android serves.** `Resources.getQuantityString` picks a plural's
  item by the plural rules of the configuration's *first* locale, even when it serves another
  folder's text (`ResourcesImpl.getPluralRule`): on a phone whose only language is Thai, Japanese,
  Chinese, Korean, Vietnamese or Indonesian, Volt in English would say "1 minutes left", and on a
  Russian or Latvian one "21 minute left". `AndroidText.plural` therefore chooses the item with the
  rules of the folder Android serves that key from.
  - **Which folder.** `gen_catalog.py` records, for every plural, the language folders that hold it
    and their items. At run time `AndroidText.folder` takes the configuration's first locale and
    picks among those folders as `ResTable_config::match` and `isLocaleBetterThan` do. The language
    must be the locale's (`in`/`id`, `iw`/`he`, `ji`/`yi`, `tl`/`fil` count as one). The scripts
    (given, or ICU's likely one) must agree; when either is unknown, a folder's region must be the
    locale's. Then the folder of the locale's region wins, then the folder without a region, then
    any other region. So a pt-PT phone is served `values-pt-rBR`'s text when `values-pt` lacks the
    key, and takes Brazilian rules for it. No matching folder means `res/values`.
  - **Several languages.** When the phone lists several languages, `ResourcesImpl.updateConfiguration`
    moves the first one Volt has resources for (or English) to the front
    (`LocaleList.getFirstMatchWithEnglishSupported`). Every resource then resolves with that one
    locale, which is also the one whose rules Android selects by. A key its folders lack comes from
    `res/values`, never from a later language of the list: on an "es, fr" phone a key that
    `values-es` lacks shows English with English rules, even when `values-fr` has it.
  - **Which rules.** `res/values` takes `T.Plurals.ENGLISH` (`EnglishText`'s rule, "one" only for 1).
    A language folder takes the `android.icu.text.PluralRules` of its `l10n/lang` tag, shipped or not,
    and the items that folder really has. Provenance never comes from comparing text, so a French
    "%1$d watt" / "%1$d watts" that equals English's still takes French rules ("0 watt"). The same
    goes for a shipped language that leaves a key out under the beta bar: its English fallback takes
    English rules.
  - **How.** `AndroidText` asks Android for a stand-in quantity whose item under the first locale's
    rules is the wanted one (`T.Plurals.standIn`). That is the quantity itself when it already is, so
    an English phone passes exactly what it passed before; otherwise 0 to 199 and powers of ten. The
    text is then formatted with the real quantity. When no stand-in exists (the first locale has no
    such category, as Thai has no "one"), the English default comes from `EnglishText` (G3 proves it
    equal to `res/values/`), and a folder's text keeps Android's choice.
  - **Check.** The served text must be one of the chosen source's items (`EnglishText`'s, or the
    folder's text as `gen_catalog.py` decoded it). If it is not, the table does not describe these
    resources, and Android's choice stands. That covers a pseudo-locale build, a folder text aapt
    stores differently from how it is written, or an Android that resolves differently.
  - **Gates.** The table can't rank every folder layout, so `gen_catalog.py` refuses two kinds.
    One is a language folder for a region that other regions inherit from in CLDR's parentLocales:
    a numeric region such as `values-b+es+419`, or a parent region such as `values-pt-rPT` or
    `values-zh-rHK`. The other is a
    plural held by two or more region folders of one language and script, but not by that
    language's folder without a region.
  - **Known limits.**
    - The resolution model is Android 7 to 14's, and Android 15's while `locales_config.xml` names no
      `android:defaultLocale` (it names none). With `android:defaultLocale`, Android 15 resolves each
      key across every listed language, and `folder` would need to walk the list.
    - Variants and numbering systems are not modelled (Volt has none).
    - ICU's likely scripts stand in for the native `LocaleDataTables`.
    - If the model is wrong for some phone *and* a translation's text equals an item of the source
      the model chose, the check passes with the wrong rules. Otherwise a wrong model falls back to
      Android's own choice.
    - On an English phone the only change is a negative quantity of -1, which ICU reads as "one" and
      `EnglishText` as "other" (no caller passes one). Every quantity from 0 to 1,000,000 asks
      Android for the same quantity, once, as before (`test_gen_catalog.py`).
- The Shizuku shell process (`OwnerCaptureUserService` and everything it reaches) never uses any of
  these; `tests/l10n/CatalogUsageTest` checks that, and rules R2 to R6.
- R2 (no rendered text kept in a field) looks at the field's declared type: a field of an Android view
  type (`TextView`, `LinearLayout`, ... `ANDROID_VIEWS` in the test) or of one of Volt's view classes
  (a class extending one, found in `src/`) keeps the view built from the text, so
  `title = ui.text(T.uiLiveStatsTitle(), 24, true)` passes; the activity rebuilds its views when the
  language changes. A `String`, `CharSequence`, generic (`List<TextView>`) or any other type still
  counts, as does a name a file declares as its own data type (`ChargeScheduleController.View`) and a
  field name declared both ways.

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
   reviewer checks those by hand). A `String.format` literal or `+` chain that mixes numbers and unit
   text (`"%.1f mph"`, `n + " km/h"`) becomes a `format` row instead, which G1 checks whole (below). The note does not excuse a mismatch with an expression the tool
   did parse on that line. `extract.py scan <files>` lists a file's literals, which helps
   plan a batch.
4. **Shrink the baselines.** `python3 tools/l10n/hardcoded_lint.py --write-baselines` rewrites
   `l10n/baseline/`; a converted file's baseline disappears. Baselines may only shrink.
5. **Build** with `bash build.sh` (catalog plus round-trip), then `bash android-tests/build.sh`.
6. **Prove it**: `tools/l10n/prove-english.sh origin/main` and paste its summary block into the PR
   description and the Codex handoff comment.
7. **Census (G7)** on an English-pinned emulator: run the batch's sweep steps with `--census` twice
   for the base APK and once for the head APK, with the head's test APK every time, then
   `python3 tools/l10n/census_diff.py --base base-out/census --base base2-out/census head-out/census`,
   and paste its last two lines (the volatile count by step and the summary). For example:

   ```sh
   for run in base base2; do
     python3 android-tests/run-release-sweep.py --adb "$ADB" --serial emulator-5554 --census \
         --app base.apk --runner head-runner.apk --out evidence/$run --only live-stats --only dashboard
   done
   python3 android-tests/run-release-sweep.py --adb "$ADB" --serial emulator-5554 --census \
       --app dist/volt.apk --runner head-runner.apk --out evidence/head --only live-stats --only dashboard
   python3 tools/l10n/census_diff.py --base evidence/base/census --base evidence/base2/census evidence/head/census
   ```

   Use a new, empty `--out` each time, and sign both APKs with the same key as the test APK. The
   test APK only uses public APIs and app classes that older builds have, so it censuses the base
   APK too. The two base runs show which records change from run to run on their own: a reading
   whose number drifts is compared by its form only, and any other change (a capture that races the
   app) makes the record *volatile*: listed by step, counted in the summary, and not compared.
   Review that count: G7 covers nothing there, so a batch that converts text only a volatile record
   shows needs another proof (its unit tests, or a steadier screenshot). Every other record must
   match exactly, apart from the masks below and the values of its numbers (see "numbers differ"
   below). With one base run (`census_diff.py BASE HEAD`) a race or a reordered list cannot be told
   from a text change, so it fails, and a warning says so.

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
| `pieces` | JSON: the expression as literal pieces and holes, e.g. `["Move ", {"hole": "title", "arg": 1, "type": "s"}]`. A char literal is `{"char": "x"}`; a plural's quantity hole adds `"quantity": true`; `"as": "<Java>"` names a hole's expression at head when the call passes it differently: `Fmt.<method>(..., <hole>, ...)` is accepted, anything else needs a `manual:` note, and every `as` is listed for the reviewer. For `plural`, an object of English forms: `{"one": [...], "other": [...]}`, whose pieces may hold a modelled helper call (below). For `format`, the split (below). For `choice`, a tree of conditions (below) |
| `key` | the resource key (for `format`, the template; for `choice`, the branches' keys, then before else, joined by commas), or `-` for `stays-english` and `removed` |
| `kind` | `exact`, `template`, `plural`, `format`, `choice`, `id-kept`, `stays-english` or `removed` |
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
- `choice`: a `+` chain that holds a parenthesized ternary, such as
  `(measured ? "Measured this charge. " : "Planned for a fresh charge. ") + "Charging resumes at " + t`,
  becoming a ternary between whole templates, `measured ? T.serviceMeasured(t) : T.servicePlanned(t)`
  (two templates cannot share the `"Charging resumes at "` literal as two rows). The pieces are
  `{"if": "<condition>", "then": <branch>, "else": <branch>}`, each branch a pieces array (the
  whole sentence that branch prints) or another such object, and the key column lists one key per
  branch, then before else: `service_measured,service_planned`. G1 finds the chain (or the
  parenthesized ternary) whose first text literal is on the row's line, forces each condition true
  and false and requires each branch's pieces to equal the base expression with its conditions forced
  (adjacent strings may be joined; holes compared token by token), with no ternary of the base left
  unresolved in any branch. A condition is either the condition of a ternary in the base expression,
  token for token, or a test of a data id, `"On".equals(value)` with `value` a plain name: where it
  holds, a hole that prints `value`, or `value.toLowerCase(Locale.ROOT)` or `toUpperCase` with
  `Locale.ROOT`, `US` or `ENGLISH` (ASCII only), prints the literal or its case mapping, so
  `"Is the tail light " + value.toLowerCase(Locale.ROOT) + "?"` can become
  `"On".equals(value) ? T.qOn() : "Off".equals(value) ? T.qOff() : T.q(value.toLowerCase(Locale.ROOT))`.
  The tested literal must already be a literal somewhere in `src/` at the merge base (a data id the
  code has), and each one is listed with where. Every string and char of the chain outside the
  conditions is the row's and must be gone; the conditions' own literals stay. Head must hold, where
  the chain was, the same conditions in the same nesting (optionally in parentheses) with each branch
  one `T.<key>(...)` or `M.<key>(...)` passing that branch's holes in placeholder order, standing
  where an expression can start and end (after `(`, `=`, `,`, `?`, `:`, `return` and the like; before
  `)`, `,`, `;`, `:`). Swapped calls, swapped holes, a negated condition, a shared piece kept outside
  or a branch missing a piece fail. Each key must be a string whose English renders its branch
  (G1 for plain `%n$s`, G2 for every branch), and every choice row is listed with the base code, the
  head code and its branches. A numeric sum before the chain's first string is not modelled. Java
  types matter: a ternary whose branches hold no string or char literal picks a value, not words,
  so `(snapshot == null ? 0 : snapshot.sessions)` is one hole, passed to each call as it is (B8's
  charge learning summary takes 5 keys, not 10). A ternary with text needs a String branch: one
  between chars or numbers (`n + (c ? '*' : '-')`, which Java adds as 5 + 42, or `"Rate " + (fast ?
  2 : 1.5)`, which prints 2.0) fails.
- A `plural` row's pieces may hold a **modelled helper call**, for a count phrase a helper builds:
  `"Readings for " + plural(skipped, "scooter") + " skipped · not restored"`, where
  `BackupArchive.plural(n, noun)` prints `n + " " + noun + (n == 1 ? "" : "s")`, becomes
  `T.backupReadingsSkipped(skipped, skipped)` with the forms `{"one": ["Readings for ", H,
  " skipped · not restored"], "other": [the same]}` and `H` =
  `{"helper": "plural", "model": "count_noun", "args": [{"hole": "skipped", "arg": 1, "type": "d",
  "quantity": true}, "scooter"]}`. `ledger_check.HELPER_MODELS` names each model's parameter types
  and its body token for token (`count_noun`: `(int|long, String)`, `return c + " " + w + (c == 1 ?
  "" : "s");`); G1 requires the file at the merge base to declare that helper once with that arity,
  exactly so, and the helper call in the base expression to pass the row's arguments; the call's
  literal arguments (`"scooter"`) belong to the row. A helper piece belongs only in a `plural` row,
  or a plural branch of a `choice` row (below), with `one` and `other` forms, each the same base
  expression, whose count argument is the row's one quantity hole (`"quantity": true`), so the call's
  quantity argument is checked too; anything else fails. G1 (for `%n$s` and `%n$d`) and G2 (`LedgerTest.helper`, every placeholder) evaluate
  the model with the sentinels, so `one` is checked at 1 and `other` at 0, 2 and 21 against what the
  helper really printed. Each modelled call is listed with the key, its quantity and what the base
  prints for each form. A helper with any other body (`count()` in
  BackupArchive, which picks the noun by category) needs a model added in a tooling pull request,
  with a test, or stays English.
- **Plural keys whose English items differ need the runtime fix.** Android's
  `resources.getQuantityString` picks the item by the plural rules of the phone's first locale, so
  without help a Russian phone at 21, or a French or Portuguese phone at 0, would print `one`
  ("Readings for 21 scooter") where the base printed "21 scooters". Gates round 3A made
  AndroidText pick the item by the rules of the folder Android serves the key from, so default English
  text takes English rules (see "Plural items follow the folder Android serves" above), and
  `tools/l10n/gen_catalog.py` declares that with `PLURAL_ITEMS_FOLLOW_SERVED_TEXT = True`.
  G1 accepts a `plural` row whose key's English `one` and `other` items differ (every helper row, and
  a `manual:` row for `n + (n == 1 ? " ride" : " rides")`) only while that flag is exactly True.
- **A choice set on an earlier line is refused.** A choice row's conditions must sit in the row's own
  expression. G1 reads nothing from a line above or from around the sentence: not a local holding a
  ternary, not the `if` or `else` around it, not a local that stays null unless something held. Such a
  row fails with "neither a choice of the base expression on this branch nor a test of a data id".
  G1 tried this in round T1 and took it out: each review found a new way to pass an English change
  through a home-grown data-flow analysis, which is the wrong thing to base this proof on.
  BackupArchive's restore summary is the live case. `perScooter` sets
  `String what = ... ? "layouts" : "settings";` and `recordings` sets
  `String noun = charges ? "charge" : "ride";`, and later lines read them. These lines stay English
  until an ordinary pull request, merged first, writes each choice in its sentence:
  `(charges ? plural(full, "charge") : plural(full, "ride")) + " skipped · " + ...`. G1 compares with the
  merge base, so a commit in the batch's own pull request does not do. For `what`, the same pull
  request moves `String.join(", ", labels)` to a local, because the join would otherwise be in two
  branches, and a literal repeated in a hole is a new literal. A choice row with plural branches
  (below) then converts them. An ordinary change can also convert such a sentence whole, as F6 did
  for the Dashboard header status, with `removed` rows for the whole block.
- A choice branch may be a plural: `{"one": [...], "other": [...]}` in place of a pieces array, with
  one quantity. `plural(n, "charge")` is a helper piece in both forms (held to its model). The base's
  own `n == 1 ? ... : ...` is settled by the form: `one` at 1, `other` otherwise, and an `other`-only
  branch at every count. Forms the base does not print fail, so a correction of `n + " rides"` to
  "1 ride" through a branch fails: that is an English change. G2 renders the forms like a plural row.
  Head reads the quantity twice, to pick the item and to print it, so a new row's quantity must read
  the same both times: names, fields, array elements and `ids.length`, joined by operators. Any call
  fails, whatever its name (`size()` may be the class's own method that counts its calls, and G1 does
  not resolve the receiver of `rides.size()` or `Math.round(x)`). Set a count from a call on a local
  in an ordinary pull request merged first. The same holds for a `format` row's quantity below.
- A `one` form that spells its count (`"Cleared 1 try"`, the base's own branch for 1) may print that
  count through the quantity's placeholder (`Cleared %1$d try`), which is what CatalogCheckTest wants of
  a formatted plural. G1 and G2 fill only the row's own quantity hole, only in that form.
- A `format` row's `%d` may carry `"quantity": true` when its key is a plural: head passes the base
  argument first (`T.ridesRange(rides, Fmt.integer(rides), ...)`), and every English item must print
  the one sentence `String.format` printed at every count, so `one` cannot say "1 ride" here either.
- A `removed` row takes the `;` of an array table it deletes whole (`String[] LABELS = {...};`, every
  string with a `removed` row). The statement must be one declarator, with only literals, numbers,
  names, dots and commas between the braces: a call, an assignment or a step could run, so it keeps
  its `;` and the row fails.
- A manual row covers one statement. It cannot fold `state += " • " + clock(t);` into the template of
  the statement before: G1 cannot show that the call prints what the two statements did. No line in
  `src/` needs that today: QuickAccessGrid.java's manual charge tile (B12) has an `if`, so it keeps
  `"On"` and `" • %1$s"` as two keys.
- `format`: a literal that mixes numbers and unit text, either a `String.format` format string or a
  `+` chain, moved into a number template (`fmt_quantity`, `fmt_range`) whose unit text comes from
  catalog keys. For `String.format(Locale.US, "%.1f mph", kmh / KM_PER_MILE)` becoming
  `T.fmtQuantity(Fmt.decimal(Locale.US, kmh / KM_PER_MILE, 1), T.unitsMph())` the pieces are

  ```json
  [{"spec": "%.1f", "arg": 1, "value": "kmh / KM_PER_MILE", "as": "Fmt.decimal(Locale.US, kmh / KM_PER_MILE, 1)"},
   " ", {"text": "mph", "key": "units_mph", "arg": 2}]
  ```

  and for `value + " km/h"` becoming `T.fmtQuantity(Fmt.integer(value), T.unitsKmh())` they are
  `[{"hole": "value", "arg": 1, "type": "s", "as": "Fmt.integer(value)"}, " ", {"text": "km/h",
  "key": "units_kmh", "arg": 2}]`. A plain string is text the template keeps; a `spec` item is one
  format specifier with the base argument it formats (`value`) and the `Fmt` call head passes
  (`as`); a `text` item is text its `key` now holds; holes are the chain's. `arg` is the template
  placeholder each item fills, from 1 with no gaps. G1 checks that:
  - the split, joined in order, spells the base literal (or chain) exactly, so a dropped space, a
    piece left out, swapped pieces or another specifier fail;
  - spec items come only from a `String.format` format string, whose specifiers must all be split
    out, and each `value` is the argument that specifier formats at the merge base;
  - each specifier's `as` is the `Fmt` call `FMT_SPECS` in `ledger_check.py` pairs with it, with the
    `String.format` call's locale: `%.Nf` is `Fmt.decimal(<locale>, <value>, N)`, `%,.Nf` is
    `Fmt.grouped(<locale>, <value>, N)` and `%d` is `Fmt.integer(<value>)` (only with `Locale.US`,
    `Locale.ROOT` or `Locale.ENGLISH`, since `%d` prints the locale's digits). A plain `%s` (or
    `%n$s`) prints its argument as it is, so its `as` is the `value` itself, with only string
    literals in it turned into `T.<key>()` calls: `"%.2f %s"` with `metric ? "mph" : "mi"` becomes
    `T.fmtQuantity(Fmt.decimal(Locale.US, amount, 2), metric ? T.unitsMph() : T.unitsMi())`, and
    each replaced literal (`"mph"`, `"mi"`) needs an exact row of its own, `manual:` for a ternary,
    which checks its key's English and that its call comes in the base order. Another pairing
    (`%S`, a width, `%.3s`) needs a tooling pull request that adds it, with a G10 test;
  - the head call `T.<template>(...)` (or `M.`, `Msg.of`) replaces the whole `String.format(...)`
    call (or chain) where it was, passing the `as` calls, holes and `T.<key>()` calls in placeholder
    order, so a wrong unit key or swapped arguments fail;
  - every `text` item's key is a string holding exactly that text (undoing recorded English changes),
    and the template, which takes only `%n$s` placeholders, filled with sentinels for the numbers and
    holes and with the keys' English, prints the original (`%%` in a format string is `%`);
  - every `Fmt` method the row passes is called by a test that `tests/run.sh` compiles under
    `tests/l10n/` (one a `tests/l10n/*.sources` list names): G10 `FmtLegacyTest` is what shows it
    prints like the specifier `FMT_SPECS` pairs it with.

  Every `format` row is listed for the reviewer with the base code, the call that replaced it, each
  specifier and its replacement, and the compiled tests that call a method of the same name as the
  one that held the literal. A method no plain JVM test can call (a private or instance method of
  a screen, such as `RidesScreen.speed`) does not fail: its warning says no test calls it, so the
  reviewer checks that its values are ones G10 covers. A batch with a `format` row
  needs characterization tests first (G5), as one with a `template` row does. `LedgerTest` (G2)
  skips `format` rows: their templates are plain `%n$s`, which G1 renders itself.
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
  Whole new members (a method or field declared next to converted text, such as B1's
  `labelQuantity` beside `label`) do not run where the text is; they are listed with their code.
  Changes in regions with no text, and around a `stays-english` or `id-kept` move, are listed as
  warnings. Five more changes are allowed around text, and each is listed:
  - **String to CharSequence** on the declaration whose initializer is a row's expression and becomes
    the call that serves it: `private String subtitle = "Not reported";` becoming
    `private CharSequence subtitle = M.commonNotReported();` (a deferred `Msg` needs it). The same
    modifiers and name, that one type token, nothing else. For one declarator of several
    (`private String unit = "", empty = "No data", axis = "";`), either the whole declaration changes
    type, or that declarator moves into a declaration of its own right before or after the rest, which
    stays exactly as it was (`private String unit = "", axis = "";` and
    `private CharSequence empty = M.uiChartEmpty();`). Only a declaration no other file can name
    may change type: a local (in a method, lambda, initializer or anonymous class), a `private`
    field, a field declared directly in a `private` type (a package-private field of a
    `private static class`), or any field of a type declared in a method, however deeply nested.
    javac checks only the access of the field's own class, not of the classes around it, so a
    field of a non-private class nested in a private one fails: `static final Base.In IN` or
    `static Base.In in()` hands it to other files as `Ui.IN.subtitle`. A private type counts only
    when no `extends` or `implements` clause in the file names it
    (`static final class Ring extends Base`, `class Shown implements Labels`, `<T extends Base>`):
    a subclass or implementing class that other files can name inherits the field, so
    `Ui.Ring r; r.subtitle` or `Ui.Shown.UNIT` reaches it (javac allows it; a field declared in the
    private type itself, reached only through a return value or a field of that type, is refused
    by javac: `Ui.get().s` is "defined in an inaccessible class"). G1 reads one file, and a field
    other files can name (package-private, `protected` or `public` in a type they can reach, an
    interface field of such a type, a field a reachable type inherits) fails, since
    `r.subtitle.equals("Not reported")` elsewhere would compile on and turn false. Make such a field private first, in a commit of its own, or keep the String. The
    warning prints the declaration and, for every use of the variable in the file, the code around
    it (up to 12 tokens each side within its statement; a use already inside an earlier window is
    not printed twice, so two uses in one long statement get a window each, and nothing is cut to
    a count): an overload chosen by type (`text(String)` against `text(CharSequence)`), `equals` or
    a cast now sees a `CharSequence`, which the reviewer checks. A use that needs the
    String back may add `.toString()` (`: subtitle` becoming `: subtitle.toString()`, the String it
    was), listed too; any other change at a use, and `.toString()` on another variable, fails. Any
    other type change (to `Object` or `Msg`, CharSequence to String, a neighbouring declaration, a
    modifier) fails.
  - **A throw switching to an exception that keeps its English** (plan section 1.6):
    `throw new IOException("Not enough free storage")` becoming
    `throw new BackupException(BackupException.Code.NO_SPACE, M.backupEngineNoSpace())`, and likewise
    `IllegalArgumentException` to a `SetupException` or `RuntimeException` to `UserFacingException`.
    Only around an `exact` or `template` row whose expression was a whole argument of `new X(...)`:
    the new type `Y` must be one class declared in `src/` at head (a new file is fine) that extends
    `X`, directly or through other `src/` classes (`extends` clauses by simple name), so every
    `catch (X e)` still catches it, and `X` must be a JDK type named like an exception (`...Exception`,
    `...Error`, `Throwable`), not one declared in `src/`, whose own constructor could change the text.
    The catalog call must be a deferred `M.<key>(...)` (a `Msg`); the other
    arguments stay as they were, in order (a cause `e` too), and the only arguments that may join the
    call are constants of `Y`: an enum constant of an enum `Y` declares (`Code.NO_SPACE`,
    `BackupException.Code.NO_SPACE`) or a static final field of `Y`. `getMessage()` must stay the
    English text: every constructor of `Y` the call can pick (same arity, a `Msg` parameter where the
    call is) must start with `super(msg.english(), ...)` (or `super(english(msg))`, where `english` is
    a method of the class whose body is `return msg.english();`, optionally after a null check) when
    `Y`'s superclass is outside `src/`, or pass the `Msg` on whole through `this(...)` or to a
    `src/` superclass constructor that does the same, as `UserFacingException` does. The old call's
    other arguments must reach `X` unchanged, so a cause `e` stays the new exception's `getCause()`
    and its stack trace stays in logs and reports. Each `this(...)` or `src/` superclass call passes
    every parameter that holds one on as a whole argument. The JDK constructor at the end receives
    exactly the old call's arguments, in order, with the text replaced by `msg.english()` and each
    other argument as the parameter that holds it. No argument of those calls may assign a
    constructor parameter (`=`, a compound assignment, `++` or `--`), since Java runs the arguments
    left to right: `this(pick(cause = null), msg, cause)` hands on null. No class from `Y` up to `X`
    may declare `getMessage`, `getLocalizedMessage`, `toString`, `getCause`, `initCause`,
    `fillInStackTrace`, `getStackTrace` or `printStackTrace` (annotations such as
    `@TargetApi(24) @Override` in front do not hide one), or call `initCause`, `setStackTrace` or
    `fillInStackTrace` anywhere in its body, or name one in a method reference (`this::initCause`):
    G1 reads only the call that starts each constructor, not the statements after it, initializers
    or field initializers. The ban goes by name, so a call on another object in the class fails too;
    move such a helper out of the class. A problem found through a `this(...)`
    or `src/` superclass call also names the constructor that made the call, with its arguments.
    The warning prints both calls, the chain and the
    constructors; check by hand that nothing shows or logs the exception itself, since `toString()`,
    `"" + e` and stack traces now print the new class name. These fail:
    - a `T.<key>()` (rendered) argument;
    - a type that is not a subclass;
    - a constructor passing `msg.render()` or nothing;
    - a cause dropped at the call, or by a constructor that takes it and calls
      `super(msg.english())`, `super(msg.english(), null)` or `this(code, msg)`;
    - a parameter assigned inside a delegation's arguments;
    - an override of `getCause()` or another of the methods above, annotated or not, or a call of
      `setStackTrace(...)` or `initCause(...)` in a constructor, an initializer `{ ... }` or a field's
      initializer;
    - a non-constant extra argument.
  - **A private method's String parameter becoming `Msg` or `CharSequence`** when its callers now pass
    catalog calls (B7): `private void simulationUnavailable(String action)` becoming
    `simulationUnavailable(Msg action)` while each `simulationUnavailable("Bluetooth pairing")` becomes
    `simulationUnavailable(M.bleSimPairing())`. The method must be `private` (or package-private with
    no other `src/` file naming it), declared once with that name and arity in the file, and every call
    of it in the file must pass, at that parameter, a catalog call that serves a ledger row (an `M`
    call for a `Msg`; `T` or `M` for a `CharSequence`). The warning prints the method, every call site
    and every use of the parameter in its body (an overload chosen by type, `equals`, or `+` with a
    String, which renders the `Msg`). A `public` or `protected` method, an overload of the same arity, a
    caller passing anything else, or another type (`Object`) fails. Away from text such a change is
    listed as before.
  - **`Locale.US` becoming `Locale.ROOT` in a `String.format` that prints the same** (plan section
    4.1 moves data sites such as Telemetry's `String.format(Locale.US, "response_%02x", cmd)` to
    ROOT): only that one token, in a call head keeps token for token otherwise, whose pattern is a
    literal with only `%x`, `%X`, `%o`, `%d` and `%s` (flags `-`, `0` or `#`, a width, no precision).
    `%f`, `%e`, `%g`, a grouping `,`, `%S`, `%c` and the `%t` dates fail with the specifiers named:
    G1 does not prove what they print in either locale. Each swap is listed; check that every `%s`
    argument is an id or a string.
  - **A legacy locale wrapped for Fmt** (plan section 4.1): `Locale.US`, `java.util.Locale.US` or
    `Locale.getDefault()` becoming `Fmt.locale(<the same>)`, as in
    `String.format(Fmt.locale(Locale.US), "%.1f", v)` or `toUpperCase(Fmt.locale(Locale.ROOT))`.
    `Fmt.locale` returns its argument while Volt is English, which G10 proves, so a compiled test
    under `tests/l10n/` must call `Fmt.locale` (else G1 fails), and each wrap is listed with its
    statement and the compiled tests that call the method holding it (its characterization), or a
    note that none does. A `Locale.ROOT` wrap's warning also says that ROOT is the data tier: the
    reviewer checks that the text is shown on a screen (decorative upper-casing), not an id, enum
    token or stored value, which a shipped language would case-map by its own rules. A wrap whose
    statement names `Telemetry`, `TelemetryHistory` or `putSimulationSample` (a display file building
    a reading, such as ScooterService's synthetic `remaining_range_km` value, which plan section 4.3
    keeps canonical) gets the data-tier note too: the reviewer checks that the wrapped text is only
    shown and never becomes a reading's stored, sent or logged value. A wrap in a
    file of `CatalogUsageTest.ENGLISH_ONLY` (reports, logs, the shell process) fails, and so does a
    `CatalogUsageTest` whose `ENGLISH_ONLY` list G1 cannot read. So does a wrap in a data-tier
    file of plan section 4.1 (`ledger_check.DATA_TIER`: Telemetry, RideStore, RideArchive,
    PhoneRideStore, ChargeSessionStore, BackupArchive, BackupJson, AppDataBackup, TestJournal,
    ChargeScheduleController), whatever the locale: they write stored records, JSON, journals and
    the Telemetry labels and values, where a shipped language's decimal comma or date would be
    stored or sent, and plan section 4.3 names no display call site in them. A display site found
    there moves out in a commit of its own first. Another locale, another method
    (`Fmt.displayLocale()`), extra arguments or a call on the result fail; so does unwrapping
    (`Fmt.locale(Locale.US)` becoming `Locale.US`).
- `stays-english`, `id-kept`: the literal moved within `src/` and is still English. The same value
  must be added somewhere else in `src/` (outside the changed region it left), and the lines it
  left must either only lose tokens (code moved out whole) or name the constant it moved to
  (`NAME`, or `Class.NAME` from another file). The constant is a `final` field of a class, enum or
  record body (or an interface field), `static final String NAME = "V";`, or an enum constant
  whose argument the literal is, `VOLT(T.UNITS_VOLT, "V")`. A local variable, an assignment
  (`source = "GPS";` in a method) or a field that is not `final` is never a constant, for an
  arrival or for a constant already in `src/`. Several occurrences of one
  literal may move into the same constant, one row each (ChartScale's three `unit.equals("V")`
  becoming `UnitCode.VOLT.matches(unit)`); then every changed region they left must name, in the
  base order of its literals, the constant each one moved to, so `"V"` then `"W"` cannot become
  `WATT` then `VOLT`, and two `"V"` sites need two `VOLT`s. A constant several sites share is listed
  with each site; a constant holding another value, or a site naming another constant, fails.
  When code moved out whole and several new copies of the value arrived, G1 takes the one that
  arrived as the same expression (all of the row's literals together, B1's `"%." + precision + "f"`
  moving into `Fmt.java`), then one in a class or method the lines it left name; so a new `"f"`
  elsewhere fails as the new literal it is, not the real copy. When nothing tells two copies apart,
  the one that then fails as a new literal says which copy G1 took for the row.
  A later batch may also move a literal into a constant already in `src/` (B1's `UnitCode`, whose
  doc asks each batch that owns unit data to compare through it): the region it left names that
  constant (`UnitCode.VOLT.code`), and the constant's whole value is the same literal. Each such
  site is listed with the existing constant's code.
- An extraction batch adds no literal to `src/` other than `""`: new text goes to the catalog, and
  a literal that moved needs a `stays-english` or `id-kept` row. Each new literal fails G1, except
  a lower-case key or id such as a preference name (`"app_language"`) or a `requireNonNull` message
  (`"msg"`): letters and digits joined by `_` or `.`, two characters or more; and a format-only
  literal such as `"%.1f"`, `"%d"` or `"%,.0f"` (number specifiers, `%d %o %x %X %f %e %E %g %G`,
  with `%%` and `%n`, digits and punctuation, no letter outside a specifier; `%s`, `%S`, `%b`, `%c`
  and `%t` can print words, so they do not count). G1 lists every such literal with its statement for the reviewer, and it
  alone does not make a change "around text".
- Ledger files are append-only. Every ledger at head is checked against the catalog on every pull
  request, so rewording an extracted string fails unless `l10n/english-changes.tsv` records it.
- A row whose location is a test file must be `removed`, for a test of deleted code (G4 below).

### Pull requests that are not batches

CI (`tools/l10n/ci_gates.py`) decides what kind of change a pull request is from the paths it
changes since the merge base:

| Kind | When | Gates |
|---|---|---|
| `batch` | it changes `l10n/ledger/` or a `res/values*/strings_*.xml`, or a `src/` file gains a `T.`, `M.`, `R.string` or `R.plurals` reference (bare or spelled `com.jjricks.volt.T.`, and so on) | every gate, strict; proof tooling unchanged |
| `english-change` | it changes `l10n/english-changes.tsv` | G1 with the recorded changes, G8 shrink-only, G5; G4 and the golden diff report only; no ledger rows may be added, and every recorded key must be in the catalog; proof tooling unchanged, except that edits to existing `tests/l10n/<Name>Test.java` files that are not gate tests are reported |
| `ordinary` | anything else | baselines list every finding; ledgers agree with the catalog |

Every kind also refuses a static import of a `T`, `M` or `R` member in `src/`: a bare call through one
looks like any other method, so CI could not tell a conversion from ordinary code. Call `T.x()`.

- **An ordinary feature or fix** may change English, tests and report formats. If it adds UI text
  to `src/`, `hardcoded_lint.py --check` fails until the baselines list it: run
  `python3 tools/l10n/hardcoded_lint.py --write-baselines` and commit the result (from B0b on,
  prefer putting new text in the catalog). If it changes a report format, update the goldens with
  `bash tests/l10n/golden.sh --update` and say so in the PR. Before opening it, run CI's localization steps
  (`python3 -m unittest discover -s tools/l10n`, `python3 tools/l10n/gen_catalog.py --check`,
  `bash tests/l10n/golden.sh`, `python3 tools/l10n/ci_gates.py --base origin/main`), then, as for any
  change, `bash tests/run.sh` and `bash build.sh`. CI does not run `prove-english.sh`, and an ordinary
  pull request does not need it.
  If you do run it, it applies the same rules: G1 reads `not applied`, never PASS, because such a
  change may change a literal, and a golden change reads REPORT, so its report is not a proof that
  English is unchanged.
- **An owner-approved English change** (plan part D, after the migration) edits the catalog text,
  adds one row per changed key to `l10n/english-changes.tsv`
  (`key	quantity	old	new	note`, quantity `-` for a string, the note naming the owner's
  approval), updates the tests that assert the old English, and shows the census diff.
  Its characterization changes (`tests/l10n/characterization/`) all go in the first commit, which
  changes nothing under `src/` or `res/` (G5). It may edit an existing top-level
  `tests/l10n/<Name>Test.java`: CI lists each such edit in a REPORT line, and the reviewer checks
  that each one only follows a row of `l10n/english-changes.tsv`. The gate tests stay strict:
  `LedgerTest`, `FmtLegacyTest`, `ReportsStayEnglishTest`, `GlyphCoverageTest`, `CatalogCheckTest`
  and `CatalogUsageTest`. So do deleting a test, the other `tests/l10n/` files (`GoldenReports.java`,
  `L10nFiles.java`, the scripts, subdirectories) and the rest of the proof tooling. Change those in a
  tooling pull request of their own.

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
- `manual:` ledger rows (ternaries, `String.format` outside `format` rows, `StringBuilder` chains)
  are checked by hand: G1 only checks their literals' order and lists the row and its statement for
  the reviewer.
- `format` rows: G1 proves the text around each number (split, keys, template, call), not the number
  itself. That `Fmt.decimal` prints like `%.1f` is G10's (`FmtLegacyTest`), and that a site's
  arguments were in range for it is the characterization tests'; G1 pairs each specifier with one
  `Fmt` call and requires a compiled G10 test of each `Fmt` method. A test of the converted method
  itself is listed when one exists (matched by simple name, so an overload counts), not required.
  A `%s` passthrough assumes its argument is text (a template's arguments are `CharSequence`).
- A literal shared through one constant is checked for the constant's name and the order of the
  sites in each region, not for what the code around each site does with it; the shared constant
  and every changed region are listed. Whole new members next to text are listed, not checked.
- `whole block:` removed rows, `as` holes and code changes away from any text are warnings, not
  failures: the reviewer must read them.
- A String declaration turned CharSequence for an `M` call is listed with every line naming the
  variable; G1 does not check what those uses do with a `CharSequence` (an overload picked by type).
- A locale wrapped into `Fmt.locale` is checked for the wrap only: that it keeps the legacy locale in
  English is G10's, and that the site's values format the same is its characterization test's (listed
  when one calls the method). Data-tier wraps fail by file (`DATA_TIER`, `ENGLISH_ONLY`); in any
  other file, a data-tier value is found by name only (`Telemetry`, `TelemetryHistory`,
  `putSimulationSample` in the statement) and gets a note, not a failure. A value stored through a
  variable or another helper gets the ordinary display warning, and the reviewer judges it.
- An exception switched to a subclass is checked for its `extends` chain and constructors by simple
  name, in `src/` at head; that nothing prints the exception itself (whose `toString()` now names the
  new class) is the reviewer's to check, from the listing. The old call's other arguments are
  followed to the JDK constructor by parameter name, not by declared type. The `(String)` and
  `(String, Throwable)` constructors that exceptions have leave no other overload to pick, but a JDK
  type with same-arity constructors of other types is the reviewer's to check. G1 does not model
  Java's overload resolution either: it checks every constructor of `Y` with the call's arity and a
  `Msg` at the call's position, even one Java would never pick by type. So a class with both
  `(Msg, Code)` and `(Msg, Throwable)` refuses switches in both directions, because each call is also
  checked against the other constructor. Give such constructors different shapes instead, as
  `BackupException` does by putting `Code` first: `(Code, Msg)` and `(Code, Msg, Throwable)`.
  The ban on `initCause`, `setStackTrace` and `fillInStackTrace` reads the chain's own classes by
  name: code elsewhere that the class hands `this` to (`{ Util.strip(this); }`) is the reviewer's to
  check. An initializer is not printed in the listing, so read the chain's classes in the diff.
- A `choice` row's branches are proved against the base expression with each condition forced; that
  the condition reads the same value before the holes run (head evaluates it first) is not checked,
  and an id test assumes the variable holds exactly the tested literal where `equals` is true.
- A modelled helper is proved equal to its model token for token at the merge base; a model's Java
  in `LedgerTest.helper` and its tokens in `HELPER_MODELS` must agree, which their tests check.
- A private type's field may be retyped only when the private type is the field's own class and no
  `extends` or `implements` clause in its file names it, matched by simple name: a same-named type
  elsewhere in the file is refused too, and so is a field of a non-private class nested in a private
  one even when nothing in the file exposes that class.
- G7 needs an English-pinned emulator and does not run in CI; its result is pasted into the PR.
- G7 does not compare volatile records (values whose text, not only their numbers, changed between
  the two base runs), and it checks the form of numbers, not their values: a number whose value
  alone differs is reported ("numbers differ"), never failed. The summary counts both; number values
  and precision rules are G10's to check (`FmtLegacyTest`), and the reviewer judges whether the
  batch's text in a volatile record is proven another way.
- CI runs the pull request's own copy of the tools, so the "proof tooling unchanged" check and
  review are what stop a batch from weakening them.

## The gates

| Gate | Tool | Runs in |
|---|---|---|
| G1 ledger | `tools/l10n/ledger_check.py --base origin/main` (drafts: `extract.py`) | CI (batches and English changes), prove-english (the same kinds; an ordinary change reads `not applied`) |
| G2 template equivalence | `tests/l10n/LedgerTest.java` (B0b) | CI via `tests/run.sh` |
| G3 aapt round-trip | `tools/l10n/roundtrip.py`, run by `build.sh` | every local build |
| G4 frozen test statements | `tools/l10n/frozen_literals.py --base origin/main` | CI (batches; English changes: `--report`), prove-english (batches; other kinds: listed as REPORT, never FAIL) |
| G5 characterization first | `tools/l10n/char_first.py --base origin/main` | CI (batch and English change pull requests), prove-english |
| G6 existing suites | `tests/run.sh` and the sweep's `--only` steps | CI, emulator |
| G7 screen text census | `android-tests/TextCensus.java` (B0b), `tools/l10n/census_diff.py` | emulator |
| G8 hard-coded text lint | `tools/l10n/hardcoded_lint.py --check --base origin/main --tight` | CI (batches; others: `--check`), prove-english |
| G9 reports stay English | `bash tests/l10n/golden.sh`, goldens unchanged since the merge base (and `ReportsStayEnglishTest`, B0b) | CI (the diff: batches), prove-english (other kinds: a golden change is REPORT, never FAIL) |
| G10 format legacy parity | `tests/l10n/FmtLegacyTest.java` (B1); G1 requires a compiled `tests/l10n/` test calling each `Fmt` method a `format` row passes | CI via `tests/run.sh` |

The tools' own tests: `python3 -m unittest discover -s tools/l10n` (standard library only). CI
runs them, `gen_catalog.py --check`, `ship_check.py` and `tests/l10n/golden.sh` on every push and pull
request, then `ci_gates.py`. CI runs the pull request's own copy of these tools; the batch rule above (no tooling
changes in a batch) and review are what keep a batch from weakening them.

**G2.** `LedgerTest` renders every `exact`, `template` and `plural` row through `EnglishText` with
sentinel arguments and compares the result with the base expression evaluated with the same
sentinels. A plural is rendered at every quantity of `LedgerTest.QUANTITIES` (0, 1, 2, 3, 4, 5, 11,
12, 21, 22, 25, 100, 101, 111 and 1000000), in the form `EnglishText` serves for it ("one" for exactly
1 when the entry has a "one" item, "other" otherwise), and must print what the base printed for that
quantity. The base expression is the row's "other" pieces, the ones G1 matched with the base line;
only a `manual:` row (a base ternary with its own branch for exactly 1, such as
`n == 1 ? "1 ride" : n + " rides"`) has its "one" pieces stand for that branch at 1. So a "one" item
holding English the base never printed fails, whether or not the row lists a "one" form: a batch
that turns `n + " minutes"` into a plural keeps "1 minutes" in its "one" item, and tags it
`volt:english-fix="pending"`. For a pending plural every item must print the base output at every
quantity. Each form a row lists is also checked against its own item, and a listed form the entry
lacks fails.

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
and fails when a characterization test is not an active entry of a `tests/l10n/*.sources` list,
read as `tests/run.sh` reads it (from `#` on is a comment, the rest is trimmed, and the entry must be
exactly the test's path). An English change is the exception: its characterization tests pin the
new English, which the base code does not print yet, so that run is `n/a` there. Its order is still
checked, each test must still be an active entry at head (with or without `--skip-tests`), and G6
runs them there.

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

**G9.** `tests/l10n/golden.sh` generates the catalog as `tests/run.sh` does (`gen_catalog.py`
without `--android`) and compiles `GoldenReports.java` against the pure classes in `src/` and that
catalog, so a class the reports reach (Telemetry, ModelRegistry, CapabilityResolver,
ChargeScheduleController, GuidedTest, ...) may use `T`, `M` and `Msg` for its display text. It runs the
reports under en-US, de-DE, tr-TR and ar-EG with different time zones and the built-in English, then
twice more under en-US: with a non-English source that answers every id with its marked key, and with
one that throws on any use. All of them must write the same files, which must equal
`tests/l10n/golden/` (the diagnostics JSON, the ride journal CSV, the phone ride CSV and the GPX). So a
report field that moves to the catalog fails even when its English is the same (`"group": "Battery"`
becoming `T.readingsGroupBattery()`), and so does a report path that only reads catalog text, as
`ReportsStayEnglishTest` already requires of the report builders. The display text of those classes
stays off the report paths: keep an English id beside a display accessor (plan section 1.5). `--update`
rewrites the goldens; use it only for an intended, reviewed format change, never in a batch: CI fails
a batch whose goldens differ from the merge base.

**G10.** `FmtLegacyTest` pins each `UnitCode` that exists by name, data code and English symbol, so
changing or removing one fails. A batch that owns unit data may add a code (B3's chart "Time"): a
new code needs a non-empty data code no other code has and its own plain catalog string as its
symbol, never another code's.

**B0a extra check.** `prove-english.sh <base> --same-apk` builds the merge base and head in clean
worktrees and requires byte-identical `classes.dex` and `resources.arsc`.

## Pseudo-locales

`VOLT_PSEUDO_LOCALES=1 bash build.sh` also packages the same `classes.dex` with
`aapt --pseudo-localize` into `build/pseudo/volt-pseudo.apk` (never `dist/`), with en-XA (accented,
bracketed, longer) and ar-XB (right-to-left). The build checks that the resource ids are unchanged
and that both configurations are present. Every build runs a release guard on `dist/volt.apk`:
`aapt dump configurations` must not list en-XA or ar-XB, and no text may sit in an English or
non-locale configuration. aapt1 cuts en-XA text at a `%` with no letter after it (`Battery %`,
`Battery charge (%)`, only possible with `formatted="false"`): `[βåţţéŕý »%` and NUL characters, no
closing bracket. aapt refuses a `values-en-rXA` folder next to `--pseudo-localize`, so the pseudo
build runs `tools/l10n/pseudo_fix.py` on its output, which rewrites those strings in place as the
text aapt meant (`[βåţţéŕý % one two]`) and fails if any text still holds a NUL. A letter soon after
the `%` is not cut, only marked: `90% range` becomes `[90»% ra«ñĝé one]`, which the repair leaves alone.
After the repair, the pseudo build's round trip still names any en-XA text that opens with `[` and has
no closing `]` as a `roundtrip: note:` line, so a bracket the repair missed, or one lost for another
reason, shows up in the build log. None is expected.

## Census format (G7)

One UTF-8 file per captured screen, `<variant>-<label>.census.tsv`. The first line is
`volt-text-census-v1`; then one record per line: `field`, `locator`, `bounds`, `value`, tab-separated.
Fields: `text`, `desc`, `hint`, `tooltip`, `state`, `error`, `title`, `message`, `button`, `item`,
`toast`, `notif-title`, `notif-text`, `notif-subtext`, `notif-action`, `notif-channel`. The locator
is `tag=<tag>` when the view has a String tag, else the class path with child indexes; bounds are
`x,y,width,height` or `-`. Backslash, tab, line feed and carriage return are escaped as `\\`, `\t`,
`\n`, `\r`.

Every validation's screenshot helper writes one next to its screenshot (`TextCensus.write`), and
every runner script pulls it with the screenshot, so every sweep step that takes screenshots has a
census; `run-release-sweep.py --census` copies them to `<out>/census/<step>/`. A census that cannot be
taken leaves `<name>.census-error.txt` instead, which the sweep reports, as it does a screenshot with
no census beside it. A PNG drawn from one view rather than taken of the windows
(`ResponsiveDashboardValidation`'s card and gauge renders) is censused by `TextCensus.writeView`,
which walks that view's tree (locators start at `render/`, bounds are relative to the view). Only a
render that holds no text (`DeveloperSettingsValidation`'s switch thumbs) calls `TextCensus.skip`,
which leaves `<name>.census-skip.txt` with the reason; the sweep accepts that and counts no screen
for it. A runner's own `screencap` of the notification shade (`run-ride-auto-stop.py`) gets a
census of the app's notifications only, which the validation takes with
`TextCensus.writeNotifications` and the runner moves beside the PNG.
A screen's census holds the app's notifications only in the validations whose checks are about
Volt's notification text, which call `TextCensus.writeWithNotifications`: `ChargeAlertValidation`,
`ChargeCaptureValidation`, `ChargeScheduleValidation` and `RideAutoStopValidation`. Everywhere else
`TextCensus.write` leaves notifications out: the connection notice there is incidental and races
the screen (a simulated link that is still up in one run is already down in the next).
`tools/l10n/test_census_coverage.py` fails when an `android-tests` method or runner script writes a
PNG file without a census, calls `TextCensus.skip` outside its list of text-free renders, or calls
`TextCensus.writeWithNotifications` outside its list of notification validations. Steps that take
no screenshots (FirstConnection, Launch, PhoneRide and others) have no census, and the sweep names them: G7 proves nothing for those screens, so a batch that
changes them needs a screenshot step first, and a `census_diff.py` result of `0 screen(s)` is not a
pass. The report-only `locale-*` steps never join the census.

Windows come from `WindowInspector` (API 29+): the activity's window first, then
dialogs and popups (`dialog[<n>]/...`), whose framework title, message and button views are `title`,
`message` and `button`, and whose list rows are `item`. Toasts are drawn by the system and are not in
the census.

**Generated identifiers** are masked first, in locators and values: a UUID becomes `<uuid>`
(`tag=scooter-edit-<uuid>`), the hex value of a JSON id field (`"report_id": "1458068b7d6e6467"`)
and a free-standing hex id (16 or more hex digits, or 12 to 15 mixing digits and letters, in one
case, with no letter or digit beside it: `Report 1458068b7d6e6467`) become `<hex-id>`, and the
number of a JSON field whose name ends in `_ms` (a report preview's `wall_ms` or `first_ms`) becomes
`<ms>`. Report JSON stays English and has its own goldens (G9). Nothing else about numbers is masked:
`18.7 W` against `18,7 W`, `137 W` against `137W`, a changed unit, plural, capital or zero padding
all fail.

**Volatile records.** Each record is keyed by its screen file (path under the census folder),
field, locator with identifiers masked, and occurrence (the how-manyth record with that field and
masked locator, counted in tree order), so the rows of a list whose views share a tag keep their own
keys. A key whose values differ between the base runs (once masked), or that only some base runs
have, is volatile: `--show-volatile` lists each one with its base values. A key every base run
agrees on is stable and must be in HEAD with a matching value; a key only HEAD has fails. Between
the two sits a key whose base values differ only in their numbers (`137 W` and `145 W`, drifting
telemetry): it is *compared by form*. The form writes each digit run as `<n>`, except a run right
after a digit and a decimal point or comma, which keeps its length (`18.7 W` is `<n>.<n:1> W`), so
precision is part of it; a zero-padded run must stay padded. HEAD's `150 W` passes but `150W`,
`15,0 W`, `150.25 W`, `150 kW`, `08:00` against `8:00` or a changed plural fails. A key whose form
also changes between the base runs (`9.9 W` and `10 W`) is volatile.

**Numbers differ.** Two base runs cannot prove a reading stable: `23 °C` can come up in both by
chance and `24 °C` in HEAD. So a stable record whose HEAD value differs from the base value only in
the values of its numbers (the same form, as above) is reported as "numbers differ", counted in
the summary and listed with `--show-numbers`, never failed. A different form still fails. Number
values and precision rules are G10's to check (`FmtLegacyTest`), not G7's. A screen
only some base runs have is volatile as a whole. Fallback for a list that reorders between runs
(the charge history once listed recordings by file time and random name): the keys of one field
and one locator with child indexes dropped (`.../LinearLayout[]/TextView[]`) whose values are not
identical in every base run, when there are two or more and every base run holds the same values
there in some order, are a reordered list. HEAD must hold those values there, in any order, and
the summary counts them as compared without order. `census_diff.py` also fails when HEAD is given
as one of two or more base runs, when a base is given twice, when the base runs' `sweep.json`
(written by `run-release-sweep.py --census`) names different app APKs, or when the runs used
different test APKs; a run without `sweep.json` gets a warning.

The validations keep their screenshots steady where they can, so that even one base run is mostly
clean: `ChargeEstimateValidation` gives its history fixture fixed file times,
`CompatibilityGatingValidation` holds the simulated check at its first read for the "running"
screenshot, and incidental notifications stay out of the census (above).

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

## Pseudo-locale checks

`android-tests/LocaleValidation.java` sets en-XA or ar-XB from inside the app process
(`LocaleManager.setApplicationLocales`, API 33+), walks the main pages and the More sections, and
counts text that was not extracted (plain letters outside aapt's `[..]` groups), glued pieces (two
`[..]` groups in one view), unfilled `%1$s`, ar-XB windows that are not right to left, and
`TextFit`'s fit problems. During the migration it reports only. The sweep runs it as the `locale-*`
steps when given the pseudo APK, whose `classes.dex` must equal the release APK's:

```sh
VOLT_PSEUDO_LOCALES=1 bash build.sh && bash android-tests/build.sh
python3 android-tests/run-release-sweep.py --adb "$ADB" --serial emulator-5554 --app dist/volt.apk \
    --pseudo-app build/pseudo/volt-pseudo.apk --runner "$RUNNER" --out evidence/pseudo \
    --only locale-en-XA-344 --only locale-ar-XB-344
```

Every other step runs in English: the sweep aborts unless the emulator's system language is en-US,
and clears any per-app language after each fresh install.

## Languages

`l10n/lang/<tag>.tsv` has a `field	value` header and these fields: `tag` (BCP 47, e.g. `pt-BR`),
`folder` (`values`, `values-es`, `values-b+zh+Hans`), `endonym`, `wave`, `status` (`held`, `draft`,
`beta`, `stable`; only beta and stable ship), `formality`, `plurals` (CLDR categories),
`quote_style`, `percent_style`, `decimal_style`, `script`, `font` (`saira` or `system`), `rtl`,
`unit_symbols`, `drive_mode_names`.

### Glyph coverage

`tests/l10n/GlyphCoverageTest` (B0b) reads the cmap tables of the four `res/font/saira_*.ttf`
weights. Every character of every string and plural item (decoded as aapt reads it) of a
`font=saira` language, English included, must be in all four. A language whose letters Saira lacks
(Cyrillic Ж, for one) uses `font=system`. Line breaks, tabs and the pseudo-locales are not checked.
The test reads its language list against `l10n/lang/*.tsv`, requires English text, and proves its
own rules and its loop on synthetic text and a synthetic `res/` tree before it reads the catalog.

The exception is `GlyphCoverageTest.FALLBACK_SYMBOLS`, a short reviewed list of symbols that no
Saira weight has but Android's own fallback font draws as plain text. Volt showed each of them
from Java literals before the catalog held it. The owner chose this list on 2026-11-04 over two alternatives: bundling a
symbol font (`Typeface.CustomFallbackBuilder` needs API 29, and Volt supports API 26) and replacing
the marks with icons (a visible design change).

| Symbol | Code point | Where Volt shows it | `emoji-data.txt` | Catalog text |
|---|---|---|---|---|
| ✓ | U+2713 CHECK MARK | "✓ Save" buttons; "PIN ✓", "Paired ✓" and drive-mode chips; step pills; "Unrestricted ✓" (More > App preferences > Background recording); "Direction ✓"; "Check finished ✓"; a compatibility read's "✓ N bytes"; the Rides compare row's "2 ✓" | not an emoji (U+2714 ✔ is) | `✓` |
| ⚠ | U+26A0 WARNING SIGN | "Optimized ⚠" (More > App preferences > Background recording), the catalog string `more_prefs_battery_value_optimized` (below) | `Emoji`, not `Emoji_Presentation` | `⚠\uFE0E` |
| ☆ | U+2606 WHITE STAR | Live Stats: a reading that is not starred, "☆ Star readings to pin here" | not an emoji | `☆` |
| ★ | U+2605 BLACK STAR | Live Stats: a starred reading | not an emoji (U+2B50 ⭐ is) | `★` |

- **U+FE0E after an emoji-capable symbol.** A symbol with `Emoji=Yes` and `Emoji_Presentation=No`
  is text by default, but a phone may still draw it as a colour emoji. VARIATION SELECTOR-15
  (U+FE0E) directly after it asks for the text form (the standardized sequence `26A0 FE0E`, "text
  style"). The test requires U+FE0E directly after ⚠ and refuses it anywhere else, in every
  language, `font=system` included. Write it as the escape `\uFE0E`: the raw character is
  invisible in review.
- **Moving ⚠ out of Java took two pull requests.** Volt's only ⚠ was MoreScreen's literal
  `"Optimized ⚠"`, which had no U+FE0E. A batch's `exact` row needs the catalog English to equal
  that literal byte for byte (G1), and this test needs `⚠\uFE0E`, so one batch could not do both.
  `l10n/english-changes.tsv` does not help: it records changes to keys already in the catalog, and
  that kind of pull request adds no ledger rows. So:
  1. an ordinary pull request (S0) wrote the literal as `"Optimized ⚠\uFE0E"` (the precaution chosen
     with this list; the English reads the same). `hardcoded_lint.py --check` passed as is: G8 had no
     baseline entry for that row;
  2. a batch (S1) then moved it byte for byte: catalog text `Optimized ⚠\uFE0E`, ledger pieces
     `["Optimized ⚠\ufe0e"]`.

  On 2026-11-04 a simulation of both steps passed `ci_gates.py` (ordinary, then batch) and this
  test; the batch alone fails G1. A new ⚠ in Java needs the same two steps. ✓, ☆ and ★ need no first
  step: they move as they are.
- **The list stays short.** Only symbols go on it: Unicode general category Sm, Sc, Sk or So, never a
  letter, mark, digit, space or format character (so not U+FE0E or U+2060). An entry that every
  Saira weight covers fails the test (delete it). Adding a symbol is a tooling pull request with
  review and evidence:
  - a screenshot of Volt showing it as text, drawn by Android's fallback font;
  - its `emoji-data.txt` properties, with U+FE0E required if it is emoji-capable;
  - its absence from the four Saira cmaps.

  Other characters Saira lacks in Volt's Java display text are not on the list and stay in Java
  until a reviewed change handles them: the symbols ▾, ⤢ and the chart legend's ━ ┄ ⋯, and two
  invisible ones. U+2060 WORD JOINER is what `CompatibilityPanel.unbroken()` puts into "km/h" at
  run time; U+FFFC OBJECT REPLACEMENT CHARACTER marks the spinner's place in "Connecting…  ￼"
  (DashboardScreen, MainActivity). The test refuses both in catalog text, while `check.py` lets
  both through (`fontmetrics` skips format characters such as U+2060, and `check.py` skips U+FFFC).
  U+FEFF and U+FFFD appear only in parsing code.

## Running a language

The translation tools follow plan 7.1-7.4. Drafts come from **three providers**: Claude (Claude Code
agents, which write draft files), OpenAI and Google Gemini (called by `council.py` over HTTPS). A
language runs area by area once the migration gate has passed, D3 has frozen the English terms and
the owner has confirmed the language's decisions below.

The Language row (plan section 5) stays hidden, and `AndroidManifest.xml` names
`res/xml/locales_config.xml` only from the first language pull request.

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

2. `l10n/style/<tag>.md`, with the sections `l10n/style/README.md` requires. A translator's
   `council.py prompt` refuses without it (`--no-style-guide` waives that) and refuses a pack older
   than it, so write the guide before `pack.py`, and pack again after editing it. `draft
   openai|google`, `prompt --role judge` and `judge-requests` do not check this.
3. `l10n/glossary/<tag>.tsv`, drafted by the three providers and judged before any screen (until
   the keys arrive, see "The glossary before the keys" below): the same
   columns as `en.tsv`, where `id` names the `en.tsv` term and `term` holds the approved
   translation, with inflected forms separated by ` | ` (`patinete | patinetes`). List the nominal
   forms too where the language makes nouns from verbs (`bloquear | bloqueo`, `verrouiller |
   verrouillage`): only the listed forms count where the English has the exact term, and the
   automatic -ing matching covers few of them (Indonesian pe(N)-...-an nouns such as "penguncian").
   Do-not-translate terms need no row, and may not have one. `council.py glossary-check --tag <tag>`
   exits non-zero on an id en.tsv lacks (`load_glossary` ignores such a row, so the term would
   quietly have no target), a missing row for a term that is not do-not-translate, a row for one
   that is, a repeated id, a wrong column count, an empty term or form, a term with a placeholder,
   `;` or `(`, U+202F in a form (Saira has no glyph for it, so French writes U+00A0), an empty
   definition, part of speech or source, a bad id, `dnt` or `safety`, and a file that is not UTF-8.
   `pack.py` and `prompt` refuse the same problems when the file exists; with no file they go on
   without targets. CI runs
   the same check on every committed glossary (`test_repository_files.py`), so a glossary goes in
   whole, and a pull request that adds a term to `en.tsv` adds its row to each language's glossary.
   `glossary-check --english` validates `en.tsv` the same way, and lists the rows no English string
   matches as information, which does not fail it.

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
( set -a && . ~/.config/volt-council/keys.env && set +a && python3 tools/l10n/council.py backtranslate openai --tag $T --area $A )
python3 tools/l10n/council.py judge-requests --tag $T --area $A      # 4-5. then a separate Opus judge session
#      writes judge.jsonl; for a safety key whose drafts disagree, back-translate the judge's text
#      (backtranslate openai again) and run judge-requests again for the judge's confirm
python3 tools/l10n/council.py decide --tag $T --area $A
python3 tools/l10n/merge.py --tag $T --area $A                       # 6. res/values-<qual>/ and validation
python3 tools/l10n/council.py beta --tag $T                          # the owner's beta bar
python3 tools/l10n/council.py usage --tag $T                         # the tokens each model was billed for
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
   pattern, newest version; `--model` or `--prefer` overrides). The owner chose modest current
   models, not the top end (2026-11-05): drafts come from `gpt-6.1-terra` and `gemini-3.8-flash`,
   and back-translation from `gpt-6.1-sol`, which drafts nothing (with Google, the newest plain
   flash that did not draft, then flash-lite). The patterns name each line, so a newer version
   of the same line is picked when it appears, and never a top-end line (pro, argon). It then drafts in JSON mode
   (OpenAI's Responses API, `"store": false`; Gemini's `generateContent`). Each draft records the
   exact model id, and the id the API reported. Each provider response (draft, redraft,
   back-translation, glossary draft, retries included) gets its own `api-call` row in `runs.jsonl`
   the moment it arrives, before its reply is parsed or checked, with the token counts the provider
   reported (`usage`, flattened: OpenAI's `input_tokens`, `input_tokens_details.cached_tokens`,
   `output_tokens` and `output_tokens_details.reasoning_tokens`; Gemini's `promptTokenCount`,
   `candidatesTokenCount` and `thoughtsTokenCount`, which Google bills as output). So a step that
   fails on a later chunk, or on a reply it cannot use, still counts what it was billed; the step's
   own row (written only when it succeeds) records how many `calls` it made and carries no usage, so
   nothing is summed twice. A 2xx reply that is not JSON at all cannot report usage. `council.py
   usage --tag <tag> [--area A] [--since <UTC time>] [--json]` sums the calls per model over the
   language's areas, the glossary round included, and counts the calls that logged none (rows from
   before usage logging count as one such call each). Claude drafts come from Claude Code agents, each
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
   words and mixed-script leakage. A translation keeps each fallback symbol of "Glyph coverage", and
   the U+FE0E after ⚠, exactly as the English has them: `check.py` (`FALLBACK_SYMBOLS`, which a test
   keeps equal to the Java list) lets those symbols past Saira's cmap and fails a draft that changes
   their count, drops the U+FE0E or puts one anywhere else, and the drafting prompt's "Keep
   unchanged" rule names them. Glossary terms match as whole words ("bloquear" is not found
   in "desbloquear"), with the endings an inflecting language adds; a lock term next to words that
   make it unlock ("lås upp", 解除锁定) or remove the lock ("Quitar el bloqueo") is not found. A plural category that covers more than one number (Russian "one" also
   covers 21; Brazilian "one" covers 0) keeps the count placeholder. Polarity runs both ways: a
   negation the English lacks warns, and is an error in a safety key; in a safety key an English
   un-word needs a negation word or the glossary's term, not just a prefix like "in-". A Latin
   word that is not in the English is an error in a non-Latin language, except an acronym or a
   name in `LATIN_LOANS`, which warns. `checks.jsonl` records each finding; a failed key goes back to
   its drafter once (`redraft`, or `prompt --redraft` for Claude) and is dropped if it fails again.
   `redraft openai|google` also sends back, in the same calls and also as attempt 2, each needed key
   the provider left out of its draft; a key the redraft still gets no usable text of is listed in
   that redraft's `runs.jsonl` row (`missing`) and is never sent again (a new full `draft` of that
   provider starts over). A provider that has had both attempts at a key, and whose draft of it is
   still missing or still failing, is **excused**: it no longer blocks the key (owner, 2026-11-05:
   the council lifts quality; it does not chase one stubborn translator). See step 5.
4. **Agreement**: after normalizing (NFC, whitespace, quote style), two or more passing drafts that
   all agree are accepted. Otherwise `judge-requests` lists the key for a separate Opus judge
   session, which picks or merges; a merged text must pass the checks again. A pick names the draft
   and copies its text, and counts only while that draft still has that text: a redrafted
   candidate needs a new ruling and falls back until it has one. A `fallback` ruling is not bound
   to the drafts it saw (it keeps English, so it can never ship a wrong text): after redrafting a
   key the judge sent to English, delete that ruling or judge the key again, or it stays English.
5. **Safety keys** (any `volt:safety`): a safety key is translated in one of two ways. **When
   every drafter agrees**, with drafts from all three providers that all pass the checks, a model
   that **drafted nothing** back-translates the agreed text and the judge confirms it, as below
   (`safety-agreed`); `backtranslate` sends such a key only while it has no current back-translation
   of exactly the agreed text by a model that drafted nothing, so a later run does not reword one the
   judge already confirmed. **When the drafts disagree** (the owner's decision of 2026-11-05; before it,
   such a key always fell back), with drafts from all three providers and at least one passing,
   `judge-requests` lists the key as `safety-disagreement` and the judge may pick a passing draft
   or merge a better text from them (a merge must pass the checks again), or rule `fallback`.
   That judged text is not used yet: `backtranslate` (and the Claude back-translation prompt)
   then covers each judged text that has no back-translation of exactly that text, by a model
   that drafted nothing; the next `judge-requests` lists the key as `safety-judged-confirmation`
   with the judged text and its back-translation, and the judge confirms it on a later line of
   `judge.jsonl` exactly as for an agreed text (`safety-judged`). The latest pick or merge is the
   judged text: a new one, or a picked draft that changed, needs a new back-translation and a new
   confirm, and the judge can never confirm a text it has not seen back-translated. A key with no
   passing draft still falls back, and so does one with a provider's draft missing while that
   provider has its retry left. **Once that provider is excused** (step 3: still no draft after its
   draft and its redraft), the key is judged in the same way from the other two providers' drafts,
   which must include at least two passing drafts from different providers (with fewer, English
   stays); the judge request and the decision name the excused provider and why in `excused`, and
   the decision's reason says so. A provider whose draft still fails after its redraft is also listed
   in `excused`; its failing draft never blocked a judged key. Either way the back-translation
   is of the current English and of exactly the confirmed text (`backtranslate`, or `prompt --role
   backtranslate` and `import-backtranslation` for a Claude agent on a model that did not draft:
   each such prompt gets a request id, the hash of exactly what it showed, recorded in
   `prompts/backtranslation.request.<id>.json` and never overwritten; the agent copies the id and
   each text's `target_sha1` into its reply, and `import-backtranslation` refuses a reply without
   a known id or with a text hash its request did not show, and drops a key whose English or
   agreed or judged text changed since that request, so a later prompt cannot adopt an earlier
   agent's answer); and the judge, shown that text and its back-translation and naming both in
   its ruling, confirms every item: locked and
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
   it across every area. It counts what `merge.py` writes, so a key whose English changed after its
   decision, or whose text is blank, the wrong shape or missing a plural form, is a fallback. A lock
   or alarm key must also be `safety-agreed` or `safety-judged`, which Claude's drafts alone never reach.
   Releases still need the owner's explicit authorization.

The language pull request then runs the local build, LocaleValidation in that language at both
sizes, and a screenshot set for the owner (plan 7.2 step 6). Native spot-check edits go into the
decisions (and merge again), never straight into `res/values-<qual>/`.

### What CI checks

The `core-tests` job's "Localization proof gates" step runs `python3 tools/l10n/ship_check.py` on every
push and pull request.
It looks only at committed files and never needs a key or the network; the beta bar comes from the
recorded decisions. It fails when:

- a language with status `beta` or `stable` has no text in its `res/values-<qual>/`;
- that text fails `merge.py`'s validation (above), including a stale `src` hash, a blank text or a
  missing `l10n_tag`;
- that text is not byte for byte what `merge.py --all` writes from `l10n/decisions/` (a hand edit,
  a deleted file, a file no decisions write). `merge.py --check` makes the same comparison;
- that language is under the beta bar (`council.py beta`);
- that language has no line in `res/xml/locales_config.xml`, or `AndroidManifest.xml` has no
  `android:localeConfig`;
- a `res/values-*` folder holds text and its language is not listed or does not have status `beta` or
  `stable`, `locales_config.xml` lists a language that does not ship, or the manifest names
  `localeConfig` while only English ships.

A language at `draft` or `held` is not read, so it can't fail the step unless its text has reached
`res/`. While nothing ships, the step says that it checked no translated text and passes.

An English change therefore fails CI for every shipped language that translates that key. Run
`merge.py --tag <tag> --all`, which drops the key to English, and check the language is still within
the bar; re-run the council for the changed keys when you can (plan 7.2 step 8). `CatalogCheckTest`
(in `tests/run.sh`) keeps the same folder rules for the real tree's `strings*.xml` files; `ship_check.py`
also reads a language folder's other XML files.

What CI does not run: `check.py` and the council's drafting or judging (they need the drafts, and the
providers need keys), aapt (no SDK on the CI workers, so `ship_check.py` prints a note and the local
build compiles the text), and LocaleValidation (it needs an emulator).

### Drafting a whole language with Claude alone

Until the OpenAI and Google keys arrive, a language can be drafted by Claude agents alone. **Such a
draft stays a draft:** `l10n/lang/<tag>.tsv` keeps status `draft`, none of the commands in this
section writes `res/`, and no safety key can pass, since step 5 needs drafts from all three providers.
The file is an ordinary `drafts/claude-<name>.xml` with `volt:provider="claude"` and the agent's
exact model id. When the keys arrive, `draft openai|google` adds the other two drafts beside it, and
the council runs from step 3 with this draft as Claude's. It is not redone or relabelled.

**Keep one Claude draft per area, and do not run `decide` or `merge.py` until the debate.** Two
`claude-*` drafts that agree on a key that is not a safety key are enough for `decide` to write
`agreed`, and `merge.py` would then write that text to `res/values-<qual>/`, whatever the language's
status. `tests/run.sh` fails on the result (`CatalogCheckTest`), but nothing before that stops it.

**The glossary before the keys.** Step 3 needs the three providers, so until then one Claude agent
writes `l10n/glossary/<tag>.tsv`, and every row's `source` starts with `claude-only`, then the
agent's model id (`claude-only claude-opus-5-5`). Without a glossary, parallel chunk agents each
pick their own word for "lock". It is committed whole like any glossary, and it stays what it is:
Claude's proposal, not an approved term list, whatever the prompts call it. `glossary-check` counts
those rows as information, and a row keeps the label until the council has judged it.

**At the debate the glossary comes first.** `draft openai|google` refuses while any row says
`claude-only`, because `check` would hold the other vendors' safety keys to Claude's word and
`decide` would then send them to fallback. So:

1. Step 3 runs with this file as Claude's proposal, on equal terms with the other two, and the
   judged result replaces it, with no `claude-only` row left. That is the glossary round below.
2. `pack.py` runs again for every area (prompts refuse an older pack). Claude's draft stays current,
   since `volt:src` hashes only the English, and nothing rewrites or relabels it.
3. `check` runs. Where the judged word differs, a safety key that used the old word now fails, and
   any other key gets only a warning and keeps its text.
4. Those failed keys take the ordinary `prompt --redraft`: only those keys, in the same file under
   the same name and model. A key already at `volt:attempt="2"` has no redraft left and falls back
   to English in `decide`, which is safe.

**The glossary round** (`tools/l10n/glossary_council.py`), one language at a time:

```sh
T=es
python3 tools/l10n/glossary_council.py relabel --tag $T          # 1. label Claude's rows claude-only <model>
( set -a && . ~/.config/volt-council/keys.env && set +a && python3 tools/l10n/glossary_council.py draft openai --tag $T )
( set -a && . ~/.config/volt-council/keys.env && set +a && python3 tools/l10n/glossary_council.py draft google --tag $T )
python3 tools/l10n/glossary_council.py judge-requests --tag $T   # 3. the rows to judge, and the judge's prompts
#   give l10n/work/$T/glossary/prompts/judge.chunk-<i>-of-<n>.md to one fresh Claude Code judge agent;
#   it writes each chunk's reply where the prompt says
python3 tools/l10n/glossary_council.py decide --tag $T           # 4. the judged l10n/glossary/$T.tsv
python3 tools/l10n/council.py glossary-check --tag $T
```

1. **`relabel`** writes `claude-only <model>; ` before the source of every unlabelled row when the
   file's header says it is a Claude-only proposal (`--model`, default `claude-opus-5-5`; it must
   start with `claude`). The glossaries written before the labelling rule need it, or the `draft
   openai|google` guard never fires. It is idempotent, never touches a row whose source starts with
   `council `, and refuses a file whose header does not say it is a Claude-only proposal. The round's
   other commands refuse such a proposal while any row is unlabelled.
2. **`draft openai|google`** sends the provider the English rows of the round (every `en.tsv` row
   that is not do-not-translate and whose `$T.tsv` row is `claude-only` or missing: id, term,
   definition, part of speech, `dnt`, safety, and up to 3 example texts), `l10n/lang/$T.tsv` and
   `l10n/style/$T.md` whole, and asks for its own term per row, with the forms separated by ` | `.
   The examples are real English texts from `res/values/` that contain the term, each with its
   string key, so the drafter sees how the term is used: every form of a translatable string is
   matched as `glossary_orphans` matches, and the pick is fixed (the shortest label, the shortest
   full sentence, then the shortest other text of a new key; a text over about 200 characters only
   when nothing shorter has the term). A term in no text goes with an empty list, and the prompt
   says so. Drafting never sees Claude's terms, the rows of `l10n/glossary/$T.tsv`. Sending the
   whole guide, to the vendors and to the judge, is the owner's decision of 2026-11-05: the guide is
   Claude-written and its "Safety wording" and "Scooter vocabulary" sections name some of Claude's
   proposed terms, which the owner accepted for the sake of context. The models are the owner's, `gpt-6.1-terra`
   and `gemini-3.8-flash`, or `--model`; either must be in the provider's model list. About 80 rows
   go in each call. `drafts/<provider>.json` records the exact model id, the reported one, the token
   usage, and per row the term and `en_sha1`, the hash of the English row it was drafted from (its
   own fields, not the examples: a string elsewhere changing its wording does not make a term
   stale); a term `glossary-check` would refuse is left out and listed under `problems`.
3. **`judge-requests`** needs both drafts and refuses one drafted from other English. A row whose three
   candidates (Claude's row and the two drafts) are identical after the normalisation `council.py`
   uses (NFC, whitespace, quote style, form by form) is agreed; every other row, and every safety row
   even when all three agree, goes to the judge. `judge-requests.json` holds the whole round. The judge
   sees each row's English, definition, part of speech, safety value and examples, and the distinct
   candidates as options A to C, without names, in an order shuffled per row, with how many drafters
   proposed each, plus the language settings and the whole guide. It picks one or writes a better
   term, with a one-line reason. The prompts are about 80 rows each and name the `set`, a hash of
   the requests and the prompt text; replies go to `replies/judge.<set>/chunk-<i>-of-<n>.json`.
4. **`decide`** refuses, writing nothing, when the English or a candidate changed since
   `judge-requests` (run it, and the judge, again), when a reply is for another set or model family, or
   when a ruling names an id the judge was not asked about, a pick whose term is not that option's, or
   a term `glossary-check` would refuse. Otherwise agreed rows take that term, judged rows the judge's,
   and a row with no ruling stays `claude-only`, so `draft openai|google` keeps refusing. A decided
   row's source becomes `council claude:<model> openai:<model> google:<model> judge:<model>` (or
   `agreed` in place of the judge), then the old source. The new file must pass `glossary-check`, or
   the old one stays, and so does `decisions.jsonl`. `decisions.jsonl` records each row's
   `en_sha1`, outcome, candidates and ruling, one line per id in `en.tsv` order, and `decide`
   merges into it by id: rows decided in earlier runs or rounds keep their records, and every row
   of the current round gets a new record that replaces its earlier one, even when that earlier one
   was a council decision for other English (the row is `claude-only` again, so the old record no
   longer describes it); `runs.jsonl` and the output name each replaced id. Running `decide` again
   changes nothing, `decisions.jsonl` included, byte for byte; a later reply for the same set
   finishes the rows it rules. `judge-requests.json`, the prompts and the drafts hold the current
   round only, by design: a decided row's evidence is its `decisions.jsonl` record.

Files, under `l10n/work/<tag>/glossary/`: `drafts/openai.json`, `drafts/google.json`, `runs.jsonl`
(an `api-call` row per provider response, with its token usage, and a row per draft), `judge-requests.json`, `prompts/judge.chunk-<i>-of-<n>.md`,
`replies/judge.<set>/` and `decisions.jsonl`. When no `claude-only` row is left, the string debate
starts as above: `pack.py` again for every area, `check`, and the redrafts.

```sh
T=fr A=more
#   first: l10n/lang/$T.tsv, l10n/style/$T.md and the claude-only l10n/glossary/$T.tsv
python3 tools/l10n/council.py glossary-check --tag $T
python3 tools/l10n/pack.py --tag $T --area $A
python3 tools/l10n/council.py prompt --tag $T --area $A --name opus --style context-first --chunk-keys 80
#   one fresh Claude Code agent per prompts/claude-opus.chunk-<i>-of-<n>.md; each writes its reply
python3 tools/l10n/council.py import-claude --tag $T --area $A --name opus --model <exact model id> --from-chunks
python3 tools/l10n/council.py check --tag $T --area $A
python3 tools/l10n/council.py prompt --tag $T --area $A --name opus --redraft --chunk-keys 80   # failed keys, once
python3 tools/l10n/council.py import-claude --tag $T --area $A --name opus --model <exact model id> --from-chunks --redraft
python3 tools/l10n/council.py drafts-status --tag $T
```

**Chunked prompts.** `prompt --chunk-keys K` writes `prompts/claude-<name>.chunk-<i>-of-<n>.md`,
keeping each ladder group in one chunk (a group larger than K makes a larger chunk). Each has the same
rules as a single prompt, and says where its reply goes and the exact import command. The reply is
`{"strings": ..., "notes": ...}`, with no `src`, which would only cost output and invite copy
errors. The `notes` are not stored, as in the single-file flow.

- **The sidecar** `prompts/claude-<name>.chunks.<set>.json` records each chunk's keys and the source
  hash of the English it showed. `<set>` is a hash of that, the text of the prompts (rules, style
  guide, glossary hits, findings) and the draft file as it stood. So rerunning the same command keeps
  the same set and the replies so far, but anything that changes a prompt, or any import since, makes a
  new set. `prompts/claude-<name>.chunks.current` names the latest set. The prompt refuses a pack
  older than the English, the `volt:safety` marks, `l10n/lang/<tag>.tsv` or the glossary, including
  a new term the pack never matched.
- **Replies** go to `replies/claude-<name>.<set>/chunk-<i>-of-<n>.json`. The folder keeps a later
  set from adopting an earlier set's replies, which were written under other rules, or are already
  filed and would undo a redraft. New prompts delete the earlier set's prompt files, but not its
  sidecar or replies: `--set <set>` still imports them, deliberately.
- **`import-claude --from-chunks`** takes each `volt:src` from the sidecar, whatever a reply says.
  It compares that with `res/values/`, not with a pack, and a key whose English changed since its
  prompt is reported and left out, so it can't be filed against the new English. A chunk with no
  usable reply (none, not JSON, no `strings`) refuses the whole import with nothing written, unless
  `--partial`. A key missing from a reply, in the wrong shape or changed exits 1, and the rest is still
  written; so does `--partial` while any chunk has no usable reply, even with no draft to write. A
  text with a lone surrogate (half a character, which JSON can spell) is such a key and is left out.
- **It merges**: keys the set covers are replaced, the rest stay, including stale ones (`check` and
  `drafts-status` show those), and the same import run twice writes the same file. A key no longer
  in the English is dropped and reported. It refuses a model id that does not start with `claude`,
  and a model other than the one the draft names, since a draft names one model. Another `--name`
takes another model, but read the warning above first.
- **Top-ups and redrafts.** `--top-up` prompts only the keys the draft lacks or has stale, with the
  rest of their ladders, which is how a `--partial` import or an English change is finished. `--redraft`
  prompts the keys `checks.jsonl` lists as failed first attempts and marks them `volt:attempt="2"`,
  which a later top-up of the same English keeps, so a key gets one redraft, even if `check` has not
  run since. A redraft set must be imported with `--redraft` and only a redraft set may be. Both use
  the style the draft records, and refuse another `--style`.
- **One style per draft.** A set in another style relabels the draft only if it replaces every key
  the draft holds; an import that would leave some in the old style refuses, with nothing written.

**Status.** `drafts-status --tag <tag> [--name N] [--json]` prints, per area and in total: `total`
keys, `drafted` (a `claude-*` draft has a text of the current English), `stale` (only of other
English), `missing` (none, and no current translation in `res/`), `failing` (a current text fails
`check.py`'s errors, recomputed rather than read from `checks.jsonl`), `safety` keys, `audited` and
`flagged`. It writes nothing. Several `claude-*` drafts count together unless `--name` picks one. An
area it can't read (a malformed draft, say) is named with the error and left out of the total, the
other areas still print, and it exits 1.

**Safety audit.** A Claude-only draft has no council back-translation, so an audit records what each
safety key's text really says. It is a **QA record only**: it never counts as step 5's back-translation.
`decide`, `beta` and the judge do not read `audit/`, and it writes nothing to `backtranslation.jsonl`
or `judge.jsonl`. The later debate starts as if it did not exist.

```sh
python3 tools/l10n/council.py audit-prompt --tag $T --area $A --name opus --chunk-keys 80
#   a fresh agent per audit/prompts/claude-opus.chunk-<i>-of-<n>.md
python3 tools/l10n/council.py audit-compare --tag $T --area $A --name opus --from-chunks --model <its model id>
#   a separate reader judges audit/compare.tsv and writes verdicts.json
python3 tools/l10n/council.py audit-record --tag $T --area $A --name opus --from verdicts.json --model <its model id>
```

- `audit-prompt` holds only the texts of the keys `pack.py` marks safety, each with its `volt:screen`
  (its ladder's, or else the area). There is no English, and keys appear under opaque ids, since a
  name like `ble_lock_waiting` says what the English means. A safety key the draft lacks or has
  stale is reported and not audited. `--top-up` lists only keys without a `compare.tsv` row of the
  current text. It refuses a stale pack, as `prompt --chunk-keys` does. The set's hash covers the
  shown texts and this draft's `compare.tsv` rows: rerunning before anything is filed keeps the set
  and its replies, and once something is filed a new `audit-prompt` gets a new reply folder.
- `audit-compare` writes `audit/compare.tsv`: `key`, `english`, `translation`, `back-translation`,
  then `draft`, `model` and the two hashes. A key whose English or draft text changed since its prompt
  is reported and left out. A back-translation by the model that drafted the text is noted, not
  refused, and the `model` column shows it; a fresh session is the minimum, another model is
  better. One set is filed under one model: another `--model` on the same replies refuses, and a new
  reader starts from a new `audit-prompt`. `--partial` exits 1 while a part has no usable reply. A
  blank back-translation, a lone surrogate or a plural that does not cover exactly the text's
  categories is reported and left out, so nothing unread can count as audited.
  Tabs, line breaks and backslashes in a cell are escaped, and a plural is compact JSON.
- `audit-record --from <json>` takes `{"verdicts": [{"key", "ok", "problem"}, ...]}` and stores
  `audit/verdicts.tsv`: those, then `draft`, `reader`, the row's two hashes and `back_sha1`, the
  hash of the back-translation judged. An unknown key, a
  repeated one, a non-boolean `ok`, or `ok: false` with no `problem` refuses the file whole.
  Optional `compare_sha1`, which `audit-compare` prints, refuses verdicts on an older `compare.tsv`.
  A verdict stops counting in `audited` and `flagged` once the English, the draft's text or the
  back-translation changes, and `audit-compare` says so.

**What git keeps:** `drafts/`, `audit/compare.tsv`, `audit/verdicts.tsv`, `checks.jsonl` and
`runs.jsonl`. Prompts, replies and sidecars stay ignored, under `prompts/`, `replies/` and `audit/`
(`l10n/.gitignore`), so a reply whose sidecar is gone means prompting again.

### Seeing draft languages in a running app

```sh
python3 tools/l10n/draft_apk.py --tags es,de --workdir <scratch dir> --out <dir>
```

`draft_apk.py` builds `<out>/volt-draft-languages.apk` from the Claude drafts of `draft` languages,
for a look on an emulator or test phone. Claude-only text stays `draft`, out of `res/` and out of
every release; the drafts are only read, so they stay usable as the Claude provider's draft in the
council.

- **Where it works.** It exports HEAD into a fresh copy under `--workdir`, deleted at the end unless
  `--keep`. Only that copy gets `res/values-<qual>/`, status `beta`, `locales_config.xml` and
  `android:localeConfig`. Nothing else is written outside `--workdir` and `--out`, and it refuses
  either path inside the worktree or another git checkout (its `.git` directory included), any `res/`
  or `dist/` path, and a checkout whose `res/` has uncommitted changes (the build would not include
  them). `--root` exports another checkout than the one the tool is in.
- **Which text.** `l10n/work/<tag>/<area>/drafts/claude-<config>.xml`, chosen with `--name` (default:
  the only one). `--drafts <root>` reads the drafts and the language's `l10n/` files from another
  folder. Only keys whose `volt:src` still matches and that pass `check.py` are written, with
  `merge.py`'s writer; every other key shows English, and a ladder goes whole or not at all. A language
  with no drafts is an error, and so is one none of whose draft text can be shown.
- **Safety terms.** No judge stands behind this tool, so a safety key shows English (state `unchecked`)
  when `check.py` could not notice an inversion in it. One cause is a safety term with no approved
  target in `l10n/glossary/<tag>.tsv` ("Unlocked" for "Locked"); a language with no glossary file gets a
  warning and every such key in English. The other is a language without a word list in `lexicon.py`
  that the key needs, since a glossary target alone does not catch these: `NEGATION` for any safety key
  ("No bloquejat"), `REMOVAL_CONTEXT` for a key with a lock term ("Odstranit zámek", "remove lock", for
  "Lock status": today da, nb, fi, cs, sk, ro, hu, hr and tr lack it), and `STATE_WORDS` where the
  English says on or off. The run names the missing lists. Add the glossary or the list to see those
  keys translated.
- **What is still unchecked.** Passing these checks does not prove the meaning. A written safety key with
  no safety term that has a target (a charge alarm's "Full" as "Empty"), and a key that names a lock word
  but has no `volt:safety`, are counted as `unverified` and listed for a person to read.
- **Checks.** `merge.py`'s validation, the catalog generator, `CatalogCheckTest` and
  `GlyphCoverageTest` run on the copy. A failure means no APK. `--no-build` stops after the Python
  checks and writes the report. Once a run has accepted its paths and found its drafts, it removes the
  APK, its `.idsig` and the reports of an earlier run from `--out`, and a run that then fails leaves no
  APK. A refusal before that (bad paths, `res/` with uncommitted changes, no drafts) leaves `--out` as
  it was, so what is there still describes the earlier run.
- **Not a release.** The versionName ends in `-draft-languages` (`1.11.1-dev-draft-languages`: the
  app's version pattern has room for three suffix parts, so the commit is in the report instead), and
  the copy's `build.sh` refuses `VOLT_RELEASE`. The APK is signed with a key made for that copy
  and deleted with it, and the caller's `NAVEE_*` and `VOLT_*` settings are dropped. So it cannot update
  an installed Volt: uninstall Volt first, which removes its data.
- **Report.** `<out>/report.tsv` has one row per language and area and a `TOTAL` row: `translated`,
  `english_fallback` (every key shown in English), `failing`, `stale` and `unchecked` (drafted keys
  refused, also counted in `english_fallback`), `unknown` (unused draft keys: the English no longer has
  them, has them in another area, or no longer has the area), `warned` (written keys with `check.py`
  warnings, for a person to read, since no judge does), `unverified` (written keys whose meaning nothing
  checked), the safety and lock or alarm counts with `safety_unverified` and `lock_alarm_unverified`,
  and `commit`. `<out>/report-keys.tsv` gives the reason for each refused, unknown, warned or unverified
  key.

The app's own Language row appears (More > App preferences), since the copy's language is `beta`.
Text still in Java, such as the bottom bar's labels, stays English whatever the draft says.

### Limits of the automated checks

`check.py` and `lexicon.py` are a **first filter**. They reject the common ways a draft goes wrong
cheaply, before anyone pays for a judge, but word lists cannot read a sentence, so they are not
the guarantee that a lock, alarm, power or speed key means what the English means. That guarantee
is step 5: every drafter agrees, or (owner, 2026-11-05) the judge picks or merges a text from
drafts that disagree; a model that drafted nothing back-translates that exact text; the judge
rules on that exact text and its back-translation; and anything less falls back to English. The checks' job for a safety key is to fail closed: a false alarm sends a correct key
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

Three rules keep correct drafts from failing. A term found only inside a longer one ("Lock" in "Lock
duration", "Low" in "Low battery alert") is not required on its own. A one-word adjective whose id
is another term's id plus a suffix (`energy-recovery-off`) is that setting's value, required only
where the text is the value alone or names the setting, so "Turn off charge alerts" does not need
the Energy recovery level "Desactivada". An ordinary "try again" is not the experimental Try term
(`check.NOT_THE_TERM`), so a retry hint needs no Try target. In German, Dutch and Swedish a lock
target may start a compound ("Sperrdauer", "Vergrendelstatus", "Låstid"), never after ent-, ont-
or upp- and not when the rest of the compound removes the lock ("Sperraufhebung").

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
  The removal lists cover pl, ru, uk and hr; cs and sk have none yet. cs and sk join "ne" to the
  verb ("nepotvrdila"), so there a native ne- word keeps an English "not"
  (`lexicon.JOINED_VERB_NEGATION`); a ne- word the English lacks only warns.
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
- **Languages with no list yet** (the later waves without a `lexicon.NEGATION` or
  `REMOVAL_CONTEXT` row): negation and removal are not checked at all; polarity only warns, and
  the judge decides.
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

**When a safety key is flagged.** Read the finding against the English first: most are real (a
lost "not", the lock word inside the unlock word, "on" drafted as off). Fix those in the text. If
the text is right and the check is wrong, reword to a form the lists know where that reads as
well (a separate negation word, the glossary's own target), and leave a note in the language's
review notes. If no natural wording passes, the key falls back to English for now; the fix is a
tooling pull request that adds the word to `lexicon.py` with a test in
`test_check_languages.py` showing both the correct wording passing and the inverted one still
failing. Never add a glossary target or an allow-list entry only to silence a finding, and never
change a check inside a language or batch pull request.

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
  request's `source_sha1` and `target` are still the current English and agreed (or judged) text.
- **`judge.jsonl`** (written by the judge): `{"key", "source_sha1", "model", "verdict", "pick",
  "text", "back", "reason", "confirm", "backtranslation_meaning_kept"}`, verdict `pick`, `merge`,
  `confirm` (safety keys) or `fallback`; `confirm` maps each of `lock_states_distinct`,
  `on_off_never_always_not_inverted`, `unverified_never_confirmed`,
  `numbers_units_action_unchanged` and `disclaimer_not_softened` to `true`. A pick copies the
  picked candidate's text into `text` and counts only while that draft still has it (after
  normalizing); a pick without it, or of a changed draft, waits for a new ruling. A confirm also copies
  the request's `text` (the agreed or judged text) and `backtranslation.back` into `text` and `back`; it
  counts only while both still match the current agreed or judged text and back-translation, so a changed
  draft needs a new ruling. A safety key reaches `judge-requests` for a confirm only after a current
  back-translation exists. For a safety key whose drafts disagree, the latest `pick` or `merge`
  line is the judged text and the latest line must be the `confirm` (a later `fallback` keeps
  English). A ruling whose `source_sha1` is not the current English is ignored.
  A ruling carries what it is bound to (English hash, picked or confirmed text, back-translation),
  and `decide` checks those against the current drafts, never against `judge-requests.jsonl`,
  so rewriting the requests while a judge works cannot rebind an earlier ruling.
- **`decisions/<tag>/<area>.jsonl`**: one line per key: `format`
  (`volt-l10n-decision-v1`), `key`, `kind`, `source_sha1`, `english`, `safety`, `outcome`
  (`agreed`, `judged`, `safety-agreed`, `safety-judged` or `fallback-english`; the first four are
  translated, for `merge.py` and the beta bar), `text`, `reason`, `alternatives`
  (every current draft with its model ids, attempt, text and findings), `stale_drafts` (each
  ignored entry: `draft`, `provider`, `model`, `src`, `why`), `excused` (for a safety key whose drafts
  are not unanimous: `{<provider>: <why>}` for each provider excused after both attempts, else `{}`),
  `models` (`drafters`, `judge`,
  `backtranslator`), `checks`, `judge` and `backtranslation`.
- `runs.jsonl` logs each provider call and import (stage, provider, model ids, key counts; never a
  key). A `redraft` row also carries `failed` and `lacking` (how many failed and missing keys it sent)
  and `missing` (`{<key>: <source_sha1>}`, the keys it got no usable text of: they have had both
  attempts). `prompts/` (with its request records) and `replies/` are regenerated and not committed
  (`l10n/.gitignore`); a reply whose request record is gone is refused, so prompt again.

The B0b-harness pull request still has to add the `tests/run.sh` hook and
`android-tests/TextCensus.java`; B0b adds `Msg`, `VoltApp` and the catalog tests (including
CatalogCheckTest, which mirrors `merge.py`'s validation).
