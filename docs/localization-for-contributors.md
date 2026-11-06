# Localization for contributors

You are adding or changing a feature. This guide is how to respect Volt's language system. Only English ships today, but every screen, notice and message must be ready for translation, and CI checks part of that.

Visible text lives in **the catalog**: the XML files `res/values/strings_<area>.xml`. The build generates two classes from them, `T` and `M`, with one method per string.

For depth, see the [localization workflow](../l10n/README.md). That manual is written for moving old text into the catalog. You don't need its proof steps for a feature.

## The rules that matter

1. **New visible text goes in the catalog.** A hard-coded string stays English for every user, in every language.
2. **Anything kept or shown later is a `Msg` or an id, not rendered text.** Rendered text keeps the old language after a language change.
3. **Never compare, parse, cut or re-case shown text.** Logic that reads shown text breaks at the first translation. Compare ids or enums.
4. **Files, file names, logs and stored values stay English and locale-independent.** Otherwise exports stop parsing in other tools and reports become unreadable to whoever receives them.
5. **Whole sentences only.** Each condition gets its own complete string, and only values are filled in. Glued pieces can't be reordered by a translator.
6. **Counted nouns use `<plurals>`.** `n + (n == 1 ? ...)` is wrong in languages with other plural forms.
7. **Numbers, dates and units on screen come from `Fmt`, `DisplayTime`, `DisplayUnits` and `UnitCode`.** A hand-made format shows the wrong digits and separators.
8. **Narrow labels step down to a shorter word, never a broken word or smaller text.** Translations are often longer, so an unfit label clips or wraps badly.
9. **Get text through `T` or `M` only, never `getString`.** `T` follows the language choice; a read through a `Context` can miss it, and CI refuses it.
10. **Know your pull request's kind before you write it.** A feature with catalog text gets the strict gates, and adding file names or MIME types in the same PR fails them. See [Pull request kinds](#pull-request-kinds).

No CI check catches a glued sentence (rule 5) or a hand-built count (rule 6), so check those yourself.

## Add a string

Add it to the area's file. This one is real, from `res/values/strings_service.xml`:

```xml
<!-- Volt ended a ride on its own after the scooter stayed out of reach while parked. -->
<string name="service_ride_saved_auto" volt:screen="Toast and notification"
    volt:purpose="title" volt:safety="ride">Ride saved automatically</string>
<!-- %1$s is the ride's name. -->
<string name="service_ride_bluetooth_lost_label" volt:screen="Ride saved notification"
    volt:purpose="message" volt:safety="ride">%1$s · Bluetooth lost while parked</string>
```

Then call it. Snake case becomes camel case, and a wrong argument count or type is a compile error:

```java
String text = ride == null || ride.label.isEmpty()
        ? T.serviceRideBluetoothLost()
        : T.serviceRideBluetoothLostLabel(ride.label);
```

There is no generator step to commit or run by hand. `tests/run.sh` and `build.sh` generate `T` and `M` into build output, and generated files are never committed. For a fast check of your XML, run `python3 tools/l10n/gen_catalog.py --check`.

- **File:** `res/values/strings_<area>.xml`. Words shared across screens go in `strings_common.xml`. English goes only in `res/values/`.
- **Key:** `<area>_<screen>_<meaning>`. Checks enforce only lower snake case; the rest is convention. `CompatKeyNamesTest` also refuses a key with a part that is `b` or `f` plus digits, or `wave`.
- **Translator note:** an XML comment right before the string. Write one whenever a placeholder or the context isn't obvious.

### Attributes

All are optional. `volt:english-fix` also exists, for a known English bug in moved text; a feature doesn't need it. Any other `volt:` attribute is an error.

| Attribute | Value |
|---|---|
| `volt:screen`, `volt:purpose` | Where the text appears and what it is, for translators |
| `volt:max` | Width budget, `<dp>dp@<sp>sp` |
| `volt:ladder` | `full`, `short` or `compact`; see [Narrow labels](#narrow-labels) |
| `volt:safety` | `lock`, `ride`, `alarm`, `charge`, `experimental`, `owner-key`, `pin`, `privacy`, `disclaimer`, comma-separated |
| `volt:dnt` | Do-not-translate tokens separated by `\|`; each must appear in the text |
| `translatable="false"`, `formatted="false"` | Plain Android attributes |

A safety key gets stricter translation: all drafters must agree and pass a back-translation, or it falls back to English. Use one when a wrong translation could mislead a rider about the scooter's state. No rule requires it for other text.

### Placeholders

Placeholders are positional and numbered from 1 with no gaps:

| Form | Argument |
|---|---|
| `%1$s` | `CharSequence`, including anything from `Fmt` |
| `%1$d` | `long` |
| `%1$.1f` | `double` |
| `%%` | A literal percent sign |

`%n$s` takes no width, precision or flag.

### Plurals

```xml
<plurals name="service_test_about" volt:screen="Scheduled charging test result"
    volt:purpose="message part" volt:safety="charge">
    <item quantity="one">about %1$d minute</item>
    <item quantity="other">about %1$d minutes</item>
</plurals>
```

The quantity comes first, then the arguments: `T.serviceTestAbout(minutes, minutes)`. Pass the count as a local, a parameter or a field, not a method call: the batch check refuses `T.sRides(rides.size(), …)`, because it can't tell that the call returns the same number twice. Set the count on a local in an ordinary pull request first. A whole count stays `%1$d`, which prints the same digits as `Fmt.integer` today. English holds only `one` and `other`. `<string-array>` is banned. Don't build counts with helpers such as `BackupArchive.plural`.

### Spelling

`gen_catalog.py` refuses anything Android's resource tool would store differently:

- A bare apostrophe: write `\'`.
- A raw double quote: write `\"`.
- Leading, trailing or repeated whitespace: wrap the whole string in `"..."`.
- Unknown escapes, or a leading `@` or `?`.
- Markup other than `<xliff:g>`.

### Symbols

Every catalog character must exist in all four Saira font weights. The exceptions are ✓, ☆, ★ and ⚠, written `⚠\uFE0E`. Anything else, such as ▾, fails `GlyphCoverageTest`. Adding a symbol is a separate tooling PR.

## Which class to use

| Use | When | Returns |
|---|---|---|
| `T.key(args)` | The text goes straight to a view or notification, now | `String` |
| `M.key(args)` | You keep it, hand it to another thread or show it later | `Msg`, which renders when read, in the language current then |
| `T.KEY_NAME` | You store a choice of text, even in a static or enum (`UnitCode.KMH(T.UNITS_KMH, "km/h")`) | `int` id |
| `Msg.verbatim(text)` | A `Msg` holding non-catalog text, such as a ride name | `Msg` |
| `msg.english()` | You need the English of a `Msg` for a log or stored reason | `String` |

`ScreenHost.toast(Msg)` renders at display time. `UserFacingException(Msg)` keeps English in `getMessage()` for logs, and screens show `msg()`.

Call `T.x()` and `M.x()` by name. CI refuses static imports of `T`, `M` or `R` members on every PR, because a bare call hides a catalog use.

CI rejects rendered text in these places, with these reasons:

- **A field, static or enum constant.** The text would outlive a language change. A field typed as a view is fine, because it holds the view.
- **A `.equals`, `.contains`, `.toUpperCase`, `.substring` or similar call on a `T` call, or a comparison with one as the argument.** The scan sees only direct calls. `String s = T.x(); if (s.equals(y))` passes CI when `y` is a plain variable, and is still wrong.

## What stays English, and why

| Stays English | Why |
|---|---|
| Ride journal CSV, phone track CSV, GPX, analysis JSON, column names, event names | Other tools read them; decimals must stay dots |
| Diagnostics, compatibility, connection and issue reports, invites, owner notes | Whoever reads a report must be able to read it |
| File names and MIME types | Stable across languages |
| Logs and stored reasons | Support reads them; use `Msg.english()` |
| Stored preference values, status ids, default scooter names | Code reads them back; shown text would break after a language change |

The pattern is an English id or enum beside a display accessor. Code and files keep the id, and screens call the accessor. Examples:

- `RouteSpeedScale.source` is English, with `sourceLabel` for display.
- `ScooterProfiles.DEFAULT_NAME` is stored in English, with `Profile.displayName()` for display.

Three traps:

- **A ride's label isn't an id.** It holds user text or a date formatted in the language at recording time. Key file names and logic on the ride id.
- **An editable field starts from the stored name, not the display name.** Otherwise opening and saving writes the translated name into storage.
- **New export code isn't automatically protected.** Golden files pin four outputs byte for byte under several locales (`tests/l10n/golden/`), and `ReportsStayEnglishTest` covers the reports. A new class is in none of those lists. Reuse the covered writers (`RideStore.exportCsv`, `PhoneRideStore.Track.toCsv` and `toGpx`) and add your own test. To prove a writer never reads the catalog, install a throwing text source with `T.install`, as `ReportsStayEnglishTest` does.

Files that must never reference `T`, `M`, `Msg` or `R`: `CompatibilityReport`, `ConnectionReport`, `IssueReport`, `ReportInvite`, `OwnerNotes`, `DiagnosticsBundle`, `CommandLog`, `ConnectionEventLog`, `OwnerCaptureUserService`, `OwnerCaptureProcess`. Code in the Shizuku shell process also can't reach catalog rendering, `Fmt` or `AppLanguage`.

## Background and service text

`T` needs no `Context`. `VoltApp` installs its text source before any Activity, Service or ContentProvider starts, and re-installs it on every configuration or language change. A service or worker with no screen open reads the current language the same way a screen does.

- **Render at each post, not when the event happens.** Keep what the text is built from and render when posting. `ScooterService`'s battery alert does this with `AlertShown`.
- **On another thread, hand over a `Msg`,** or better, an id or enum result. Otherwise the text can be stale in the language it was rendered in.
- **Toast only when visible, otherwise a notification.** A toast from the background may never be seen. `ScooterService.autoStopRide` is the model.
- **Posted notices keep their language until they post again.** That is accepted. `ScooterService.applyLocale` re-labels the channels and the connection notice. A new channel must be added there or it keeps its old name.
- **A time in a notice** uses `DisplayTime.clock(this, settings, wall)`.

Screens work like this:

- `MainActivity` doesn't declare `locale` in `configChanges`, so Android recreates it on a language change and its screens rebuild. A view built from `T` in a rebuild path is fine.
- An activity that declares `locale` must relabel itself. `BackupActivity` keeps a `Msg` per view for this, because recreating it would end its session.

**Don't copy old neighbours.** Some code still holds hard-coded text, such as `recordingError = "Storage full..."` in `ScooterService.stopRide`. That text hasn't been converted, and it isn't a pattern to follow. Leave it alone in a catalog PR too: changing it there fails `G1`. Deleting old text belongs in an ordinary PR, and converting it follows the [ledger process](../l10n/README.md). `l10n/baseline/<File>.txt` lists what each file still has.

## Numbers, dates and units

Any catalog string with `%` is formatted with `Locale.ROOT`, so decimals, grouped numbers and anything else locale-aware must arrive already formatted, as `%n$s`:

```java
T.fmtQuantity(Fmt.integer(seconds), T.moreUnitSecond())
```

| Need | Use |
|---|---|
| Number on screen | `Fmt.integer(n)`, `Fmt.decimal(Locale.US, v, places)`, `Fmt.grouped(Locale.US, v, places)`, `Fmt.groupedInteger(Locale.US, n)` for a grouped whole number (`%,d`). `Locale.US` applies only in English; other languages use their own |
| Number with unit, range, percent | `T.fmtQuantity`, `T.fmtRange`, `T.fmtPercent` |
| Clock or date on screen | `DisplayTime.clock`, `DisplayTime.dateTime`, `Fmt.datePattern` |
| Speed | `DisplayUnits.speed` |
| Unit logic | `UnitCode.matches(dataUnit)`, never the shown symbol |
| Number or date in a file | `Locale.ROOT` or the writer's fixed locale, never `Fmt` |

No CI check refuses a new default-locale `String.format("%.1f", v)`. A `batch` lists it as a warning for the reviewer, and an `ordinary` PR doesn't mention it, so check this yourself. Don't model code on `DisplayTime.recent`, whose `Locale.ENGLISH` upper-casing is old behaviour.

## Narrow labels

Use the full label, then a short one, then a compact one. Never break a word or shrink text. Rungs are sibling keys:

```xml
<string name="more_lab_status_waiting" volt:ladder="full">Waiting for your answer</string>
<string name="more_lab_status_waiting_short" volt:ladder="short">Your answer</string>
```

A rung may use only its full key's placeholders. Code measures with the view's paint and picks the first rung that fits (`TileRung.pick`, or `LadderPill`, which is private to `MoreScreen`). No plain-JVM check requires a new row to use a ladder, so check narrow screens and large text yourself, as [CONTRIBUTING](../CONTRIBUTING.md#build-and-test) asks.

Right-to-left languages are on hold. The manifest has `supportsRtl="true"`, but no check requires right-to-left layout yet.

## Pull request kinds

CI (`tools/l10n/ci_gates.py`) picks a kind from what changed since the merge base. See yours with `python3 tools/l10n/ci_gates.py --base origin/main --print-mode`.

| Kind | Trigger |
|---|---|
| `english-change` | `l10n/english-changes.tsv` changed |
| `batch` | `l10n/ledger/` or any `res/values*/strings_*.xml` changed, or a `src/` file gained a `T.`, `M.`, `R.string` or `R.plurals` reference |
| `ordinary` | Anything else |

"Batch" means a batch of moved text, and the messages are worded for that work. **A feature that adds one catalog string, or one more `T.` call to an existing key, is also a `batch`.** It needs no ledger rows, since the ledger only records moved text. The strict rules still apply.

| | `ordinary` | `batch` |
|---|---|---|
| New `src/` literal | Allowed; some need [a baseline entry](#hard-coded-text-and-baselines) | Only `""`, lower-case ids (letters and digits joined by `_` or `.`, starting with a letter, two characters or more, such as `"ride_auto_export"`), and number-only formats such as `"%.1f"` (`%s` doesn't count) |
| Code on a line holding a literal | Allowed | Refused; add a new statement on its own line |
| Lint baselines (`l10n/baseline/`) | May grow | Shrink only |
| Goldens | May change with `golden.sh --update`; say so in the PR | Must not change |
| `tests/run.sh`, `build.sh`, `android-tests/build.sh`, `.github/workflows/`, `tools/l10n/` and existing tests under `tests/l10n/` | May change | Must not change, with one exception: `tests/l10n/<Name>.java` may be added |
| Existing tests | May change | May only gain whole statements |
| `T.` or `M.` calls, catalog strings | Not possible; adding them makes it a `batch` | Yes |

MIME types, file-name parts, log tags, separators and char literals, such as `"text/csv"`, `"-sensors.csv"`, `"Volt"`, `","` and `','`, are not lower-case ids, so they fail a `batch`.

### Split a feature into two PRs when it needs both

1. **Ordinary prep PR.** Logic and data with no catalog references: MIME types, file-name parts, a change to a long key list (with `--write-baselines`), report-format or golden changes, test registration, edits to existing tests.
2. **Catalog PR**, after the first merges. Strings, `T` and `M` calls, and new statements calling the prep code.

This split was tried against the real gates when this guide was written, and both PRs passed. If your feature adds no non-id literals, one PR is enough.

### Registering a new test

`tests/run.sh` names its core suites, so a `tests/<Name>Test.java` needs an edit that a `batch` refuses. This route works in either kind:

- Add `tests/l10n/<Name>Test.java`, in package `com.jjricks.volt`: a class with `main` that throws on failure and prints a pass line, like `ReportsStayEnglishTest`.
- Add `tests/l10n/<x>.sources` listing it, plus any pure `src/` class it needs. Listing a file that is already compiled is harmless.

Tests compile without Android, so keep decisions in a pure class such as `RideAutoStopPolicy`.

### Hard-coded text and baselines

`hardcoded_lint.py --check` fails on three kinds of new literal in `src/`, shown or not:

- `(sink)`: in `setText`, a toast, a notification or similar.
- `(helper)`: an English-looking literal in a call named like a text helper, such as `label`, `status`, `event`, `text` or `group`.
- `(multiword)`: any other literal with two or more words, including log lines, exception text and long key lists.

Shown text goes in the catalog. Text that is never shown, such as a log line or English file text, takes `--write-baselines` in an ordinary PR, and the gate then passes. A `batch` can't grow baselines, so that text goes in the prep PR.

A passing lint doesn't prove text is localized: a single word outside the text helpers goes unseen.

### Changing existing English

- **Text you just added and has no ledger row:** edit it freely.
- **A key that has a ledger row:** rewording fails on every kind unless `l10n/english-changes.tsv` records it. That route needs the project owner's approval and a screen-text comparison, so put it in its own PR.
- **Retiring a key that has a ledger row:** that takes a tooling PR. Removing the key fails with `key ... is not in res/values/strings*.xml`, and removing its row fails with `ledger files are append-only`.

## What gets refused, and how it reads

CI prints the check name and the message. `G1` to `G9` come from `ci_gates.py`, and `R2` to `R6` from `CatalogUsageTest` in `tests/run.sh`. These messages are worded for moving old text, so "extraction batch" includes your feature PR.

| Message contains | Meaning | Fix |
|---|---|---|
| `G1`: `new literal "..."` or `new char "..."`; `an extraction batch adds no text to src` | A `batch` PR added a text or data literal | Move it to a prep PR, or into the catalog if it is shown |
| `G1`: `this change adds ...; around text` | You added a literal in that part of the file, or changed a statement near an old literal | Clear any `new literal` line first, which also clears this. Otherwise put your code on a new line, away from literals |
| `G1`: `literal "..." left with no ledger row` | A `batch` removed or changed old hard-coded text | Restore it. See [Don't copy old neighbours](#background-and-service-text) |
| `G1`: `exact row: <key> is '...' but the literal was '...'` | You reworded a key that has a ledger row | Revert, or use `english-changes.tsv` |
| `G8`: `new hard-coded English (sink) "..."`, or `(helper)` or `(multiword)` | See [Hard-coded text](#hard-coded-text-and-baselines) | Shown: move it to the catalog. Not shown: `--write-baselines` in an ordinary PR |
| `G8`: `<file>.txt can shrink by N entries; run --write-baselines` | You removed hard-coded text. Only a note in an `ordinary` PR | Run that command and commit |
| `G4`: `a test statement with "..." was changed or removed` | You edited an existing test in a `batch` | Add whole statements, or move the edit to a prep PR |
| `G9`: `<file> changed since <sha>` | A golden changed in a `batch` | Move the format change to a prep PR |
| `proof tooling unchanged: tests/run.sh changed` | A `batch` touched proof tooling | Move it to a prep PR, or use a `.sources` file |
| `import static ...; call T.x(), M.x() or R.string.x instead` | A static import of a catalog member | Call by the class name |
| `gen_catalog: ...: unescaped apostrophe` | Spelling | Write `\'` |
| `gen_catalog: ...: placeholders must be numbered %1$ to %2$ with no gaps` | Placeholder numbering | Renumber |
| `R2 rendered text ...` | Rendered text in a field | Store `M.x()` or `T.X` |
| `R3 ... compare ids or enums` | Shown text compared or cut | Compare the id or enum |
| `R4 an English-only file references the catalog` | Catalog use in a report or log file | Add a display accessor elsewhere |
| `R5 rendered text in a static ...; hold an id` | Rendered text in a static | Hold `T.KEY`; render when shown |
| `R6 read catalog text through T` | `getString(R...)` | Use `T` |
| `no glyph for U+...` | The font lacks that character | Choose another character |

`ledger_check: warning:` lines are for your reviewer and don't fail anything. Read them anyway: they list each new statement and id. An id must never be shown on screen.

`ControlsConsumersTest` pins how many times each file reads certain accessors, such as `feature.label`. A new read can fail it. Check `l10n/pending/b4-controls-consumers.tsv`.

## Check your work

Commit first, because the gates read `HEAD`. Run `git fetch origin` so `origin/main` is current. To try uncommitted work, pass `--head WORKTREE` to `ci_gates.py`, also with `--print-mode`, and leave out `--pr-head`. Without it they judge your last commit, and the kind printed can be wrong.

**No Android SDK needed, JDK 17+ and Python only:**

```sh
python3 tools/l10n/gen_catalog.py --check
python3 tools/l10n/hardcoded_lint.py --check
python3 tools/l10n/ci_gates.py --base origin/main --pr-head "$(git rev-parse HEAD)"
python3 tools/l10n/ship_check.py
bash tests/l10n/golden.sh
bash tests/run.sh
python3 -m unittest discover -s tools/l10n
python3 tests/owner-capture-guard-test.py
python3 tests/capture-owner-test.py
python3 tests/hardware-sweep-test.py
python3 docs/validation/data/stock-pack-v6/fit_pack_model.py --check
python3 -m unittest discover -s ops/ci -p 'test_*.py' -q
```

`tests/run.sh` and the `tools/l10n` unit tests took a few minutes each on one Mac. The others take under a minute. `tests/run.sh` includes the catalog usage, glyph and catalog form checks.

**Android SDK 36 and build-tools 36.1.0 needed.** See the [build guide](building.md) for the one-time dependency fetch:

```sh
bash build.sh
bash android-tests/build.sh
```

`build.sh` also compares the catalog with what the resource tool stored and guards the release APK. CI can't run this.

| | Runs |
|---|---|
| CI | Everything in that first list; the website job separately |
| Not in CI | `build.sh`, `android-tests`, pseudo-locale checks, the screen-text census, `prove-english.sh` |

Say in your PR what you ran, and what you couldn't run.

### The `prove-english.sh` caveat

`tools/l10n/prove-english.sh` is a local proof for moving old text. It isn't in CI, and a feature PR doesn't need it. If you run it on an ordinary PR, expect false alarms: at this writing it fails `G1` on any new or changed non-id literal and `G8` on grown baselines, even when `ci_gates.py` passes. `ci_gates.py` is the authority. A catalog-only PR with no new data literals passes it.

## See your text in the pseudo-locale build

```sh
VOLT_PSEUDO_LOCALES=1 bash build.sh
```

This also writes `build/pseudo/volt-pseudo.apk`, with the same code and two fake languages: en-XA (accented, bracketed, longer) and ar-XB (right-to-left). Catalog text appears bracketed, and hard-coded text stays as plain letters. It is never released. A trailing `%` looks mangled; that's a tool quirk.

Viewing it needs an emulator on API 33 or later with its system language set to en-US. The runner checks unextracted text, glued pieces, unfilled `%1$s` and fit problems, and it only reports them:

```sh
bash android-tests/build.sh
python3 android-tests/run-release-sweep.py --adb "$ADB" --serial emulator-5554 \
    --app dist/volt.apk --pseudo-app build/pseudo/volt-pseudo.apk \
    --runner .local/build/parked-validation/parked-validation.apk \
    --out .local/pseudo-evidence --only locale-en-XA-344 --only locale-ar-XB-344
```

`$ADB` is your `adb` path. Use a disposable emulator. Read the runner first, and keep `--out` out of Git (`.local/` and `build/` are ignored). It walks the main pages and More sections, so a new sheet may need checking by hand. If you have no emulator, say so in the PR so your reviewer knows.
