# F5 follow-ups: Rides screen items left after the batch

F5 (Rides follow-ups from B15 #188 and B14 #179) leaves these. Each needs an ordinary (non-extraction)
pull request or a tooling pull request where noted; none changes English.

1. **Date language read twice.** `shortDate`, `longDate` and the compare date read the language once for
   `datePattern` and again for `Fmt.locale`. A language change between the two reads could mix a pattern
   and a locale for one redraw. G1 blocks the change: the two statements hold `" · "` and share a hunk with
   the value methods F5 moved, so `removed` rows there report "puts rideDate in its place". Repro: replace
   those expressions with one helper and run `python3 tools/l10n/ledger_check.py --base <base> --head WORKTREE
   --ledger l10n/ledger/f5.tsv`. Do it in an ordinary pull request.

2. **Ascent tile `" / "`.** RidesScreen builds the ascent / descent tile with a literal `" / "` (the row's
   pieces have three holes and fmt_dual takes two, so no manual row fits). `ascentParts` already reads
   fmt_dual; once a language's fmt_dual is not `" / "`, the tile finds no parts and shows plain bold text
   until this literal also goes through `T.fmtDual`. Do it in an ordinary pull request.

3. **`PhoneRideStore.Track.status`.** Saved files do not hold it and no screen reads it, but
   `tests/l10n/RideMapsTextTest.java` (proof tooling) and `tests/l10n/characterization/RideMapsEnglishTest.java`
   (frozen) do. Removing it needs those tests edited, which ci_gates refuses in a batch ("proof tooling
   unchanged"). Remove all three in a tooling pull request.

4. **Stray `;` in `LegendView`.** `private int[] colors = {0, 0};;` keeps a token G1 counts as the deleted
   labels statement's. Delete the extra `;` in any ordinary pull request.

5. **Legend text direction.** The legend's right-to-left mirror stays out of scope, as the plan says.

6. **Grade legend ticks.** `LegendView.show` still hard-codes `{"−12%", "0", "+12%"}`, so a language that
   writes the sign first (Turkish `%12`) shows the percent sign on the wrong side. It should read
   `"−" + T.fmtPercent(Fmt.integer(12))`, and the same with `"+"`; English stays `−12%` and `+12%`. G1 blocks
   this in a batch: one literal becoming a kept sign plus a call fits no row, and the sign is a new literal.
   Repro: make that change, add `template` rows for `fmt_percent`, and run `python3 tools/l10n/ledger_check.py
   --base <base> --head WORKTREE --ledger l10n/ledger/f5.tsv`. It reports `no call of fmtPercent passes the
   holes in placeholder order` and `new literal "−"`. Reusing `change(12.0)` is refused too (`this change adds
   change ( 12.0 )`). Do it in an ordinary pull request. The accel ticks are numbers and a symbol and may stay.
