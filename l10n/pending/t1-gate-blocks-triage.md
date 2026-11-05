# Round T1: what became of each recorded gate block

Every entry of the wave 2 and 3 gate-blocks list, as of round T1. Lines are at T1's base, 37713be.

- **fix now**: T1 changed the tools, and the honest conversion passes G1 on the real source.
- **moot**: the block is gone without a G1 change, or another merged or open batch handles it. Where it
  says "passes", a one-line batch of that conversion passed G1 on the real source in this round.
- **refuse**: G1 is right to refuse this in an extraction batch, and T1 keeps that. The path is an ordinary
  pull request first, or a batch later.

"Run" says how this was checked: **run** is a G1 run on the real source (the conversion and its row
committed on a throwaway checkout of this round's head, then `ledger_check.py --base` that head; the T1 pull
request reports the results), and **rule** means no run: G1's rule that refuses it did not change.

| Recorded in | Block | Outcome | Run | What happens next |
| --- | --- | --- | --- | --- |
| F1 | `T.serviceChargeEnableDeveloper()` to `M.`, ScooterService:380 | refuse | run: "adds `M` and drops `T`" | An ordinary pull request. The line holds the preference key beside the text. |
| F1 | `alertTitle=""`, `alertText=""` declarators, ScooterService:86 | refuse | run: "only part of the statement" | Part of a declaration is deleted, and the rest still runs. An ordinary pull request removes them. |
| B12 | A display accessor swapped inside a text region; `NO_STATE` exists only for the gate | refuse | run: "adds `""` and drops `NO_STATE`" | Argument changes need an ordinary pull request. Once that lands, `NO_STATE` can go. |
| B12 | G4 forbids added test code from declaring a type or using reflection | refuse | rule | An ordinary pull request can add the marked `T.Source` to DashboardSlotsValidation. |
| B16 | Section tab labels, StatsScreen:126 | moot | run: passes | The statement's own text is already a catalog call, so the swap is no longer around text. A batch can make it. |
| B16 | `WINDOW_LABELS` could not be deleted | fix now | run | The `;` of a one-declarator table goes with it. |
| B16 | "☆ Star readings to pin here" | moot | rule | The glyph tooling (#187) allows the symbol. S1 moves it. |
| B16 | Append chains ("Pack ", "Health ") | refuse | rule | Each literal of a chain is its own expression. Those literals are no longer in Stats sources at this head; a new one starts as one expression, in an ordinary pull request. |
| B16 | Charge-history "MMM d" to a locale skeleton | refuse | run: "new literal "MMMd"" | A new literal and a new call. An ordinary pull request or an English change. |
| B11 | The owner-key note, BackupActivity:430 | moot | run: passes | The hunk beside it merged. A batch can make it. |
| B11 | Row counts through `BackupArchive.plural` from another file | refuse | rule | G1 reads a helper's body in the edited file only (`HELPER_MODELS`). A batch in BackupArchive itself can convert them. |
| B11 | CharSequence overloads, the unused `NO_SPACE`, `String detail` with relabelCounts | refuse | rule | Retypes and unused code around text. An ordinary clean-up pull request. |
| Cross-batch | ControlsConsumersTest floor | moot | run: no such floor in the test at this head | Merged earlier. |
| B17 | `STEP_NAMES` could not be deleted | fix now | run | As `WINDOW_LABELS`. |
| B17 | A choice's holes must match the base token for token, with no manual note | refuse | rule | A choice row is checked whole. That is its proof. |
| B17 | A literal repeated across branches counts as new text | refuse | rule | Move the repeated part to a local in an ordinary pull request first. |
| B17 | One literal split into constant arguments | refuse | rule | `volt:dnt` protects those tokens inside the sentence. |
| B17 | `active.name` to `displayName()` in a template | refuse | rule | An argument change is not an extraction. An ordinary pull request. |
| F2 | `CompatibilityPanel.question()` generic sentences | refuse | rule | A prep pull request moves "On" and "Off" to constants, as the entry says. |
| F2 | Reason text at CompatibilityPanel:191 and :288, kept for the floor | moot | rule | The floor is gone. A batch can convert them. |
| F2 | `GuidedTest.trial` and TestJournal exception text | refuse | rule | Defensive paths. Exceptions that carry a `Msg` are a batch of their own. |
| B16 fix | A choice row with a checked non-Fmt `as` | refuse | rule | A choice row takes no manual note, so nothing would check what the call prints. |
| B16 fix | Choice branches as plurals | fix now | run | A branch may be `{"one": ..., "other": ...}`. |
| B12 fix | A manual row folding a second statement | refuse | run: "drops `;`" | T1 first allowed this, then took it out. No `src/` line needs it: the real one has an `if`, and G1 could not show what the two statements print. An ordinary pull request can merge the lines. |
| B13 | Header status, a choice set on an earlier line | moot | rule | F6 (branch `claude/l10n-f6-dashboard-followups`) converts it as an ordinary change, with whole-block rows. G1 refuses it as a choice row. |
| B13 | A plural in place of a `String.format` line | fix now | run | A `format` row's `%d` may carry `"quantity": true`. |
| B13 | Argument swaps beside converted text | refuse | run: "adds `+ ""`" | An ordinary pull request. |
| B13 | Saira glyphs: "✓ Save" | moot | rule | The glyph tooling (#187) allows ✓. S1 moves it. |
| B13 | Saira glyphs: U+FFFC in the connecting spinner | refuse | rule | Not on the reviewed list: Saira never draws it. Set the span's place in an ordinary pull request. |
| B13 | "Close widget options" | moot | run: passes | F6 can make it. |
| B13 | "Scooter range" | moot | rule | As above: the title test beside it has merged. |
| B13 | Window value, column choices, saved slot summary | fix now | run | A plural branch, or a spelled-one plural row. |
| B13 | Charging check `%s` plural ending, capacity `%s` ternary | refuse | rule | A format's `%s` that holds a choice has no row. Move the choice out in an ordinary pull request. |
| B13 | `windowName` unit word on the line above | refuse | rule | G1 reads no choice from a line above. An ordinary pull request first. |
| B13 | Recording button's `TextUtils.join` inside an optional part | refuse | rule | A literal repeated in a hole. A local first. |
| B18 | Language card attached one refresh late | moot | rule | Not a gate block. No gate changes it. |
| B13 follow-up | The `exampleSlot` test case's name | fix now | run: `tests/run.sh` | Renamed here, with the Javadoc. |
| B13 follow-up | QuickAccessGrid:86, the save-slot prefill | moot | rule | Recorded as not blocked. |
| B13 follow-up | `dialogSlotNames` compiled but not run | moot | rule | Not a gate block. The census runs it. |
| B18 | Battery row ✓ and ⚠, "PIN ✓", "Paired ✓" | moot | rule | The glyph tooling (#187) allows them. S1 moves them. |
| B18 | Lint: `Row.value(String)`, a word beside a symbol | fix now | run: `hardcoded_lint --check` | G8 sees both. New findings are in the baselines. |
| B14 | MapPreferences `LABELS` could not be deleted | fix now | run: passes | As `WINDOW_LABELS`. |
| B14 | `%+` signed formats, RouteMapView:383, :458, :462 | refuse | run: refused | No signed `Fmt` method exists. It needs a `src/` change first, then a tooling pull request adds its `FMT_SPECS` row. |
| B14 | A ternary format string, RouteMapView:313 | refuse | rule | No row form. A local or two format rows, in an ordinary pull request. |
| B14 | ". At " + time holes, RideChartView:192 and :199 | refuse | rule | A time inside a phrase is one template, so an ordinary pull request first. |
| B14 | MapPreferences:141 local rename | refuse | rule | A rename is not an extraction. |
| B14 | RideStore and RideAutoStopPolicy exceptions | refuse | rule | B14b: exceptions that carry a `Msg`. |
| B11 relabel | `what` and `noun` lines in BackupArchive | refuse | run: "neither a choice of the base expression" | A choice on an earlier line. An ordinary pull request first writes each choice in its sentence, and for `what` moves the join to a local. A batch then passes with plural branches (also run). |
| B11 relabel | `label()` to `labelMsg()`, BackupArchive:564 | refuse | rule | An argument change, and `count()` picks its noun by category, so no helper model fits it. |

## After merging main

T1's base is older than main. F4 (#191) already retired items 4 and 5 of `b18-more-followups.md`, so main's
copy of that file stands. S0 (#190) writes "Optimized ⚠" with U+FE0E, so `MoreScreen`'s lint baseline must be
written again with `hardcoded_lint.py --write-baselines` after the merge.
