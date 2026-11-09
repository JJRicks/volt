# Phone placement: retired keys

Phone placement was removed from Rides (owner, roadmap Q7 A). Five English keys now have no app caller:

| Key | Still read by |
|---|---|
| `rides_placement` | `l10n/ledger/b15-rides.tsv:163`, `:164` |
| `rides_placement_mounted` | `l10n/ledger/b15-rides.tsv:165` |
| `rides_placement_pocket` | `l10n/ledger/b15-rides.tsv:166` |
| `rides_motion_note_mounted` | `l10n/ledger/b15-rides.tsv:277`; `tests/l10n/RidesTextTest.java:159`, `:318` |
| `rides_motion_note_pocket` | `l10n/ledger/b15-rides.tsv:277`; `tests/l10n/RidesTextTest.java:159`, `:319` |

Deleting them fails two gates: `ledger_check.py --catalog-only` (the ledger rows must name catalog keys,
and ledgers are append-only) and G4 (the `RidesTextTest` statements). So they stay in
`res/values/strings_rides.xml`, marked `translatable="false"`, and `merge.py` leaves them out of every
`res/values-<tag>/` folder. `rides_motion_samples` replaced the two motion notes.

Follow-up: the same tooling pull request that lets B18's four `more_lab_*` keys retire
(`b18-more-followups.md`) can delete these five, their rows' checks and those test lines.
