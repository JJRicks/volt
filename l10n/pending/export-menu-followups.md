# Export ride data: retired keys

Ride details' Export CSV, Export JSON and Export phone data buttons, Export phone data's dialog and the route
notice's raw-file dialog became one Export ride data sheet (owner, roadmap "New items from the owner" 1).
Nine English keys now have no app caller:

| Key | Still read by |
|---|---|
| `rides_export_csv` | `l10n/ledger/b15-rides.tsv:137` |
| `rides_export_csv_description` | `l10n/ledger/b15-rides.tsv:138` |
| `rides_export_json` | `l10n/ledger/b15-rides.tsv:139` |
| `rides_export_json_description` | `l10n/ledger/b15-rides.tsv:140` |
| `rides_export_raw` | `l10n/ledger/b15-rides.tsv:172` |
| `rides_export_phone_journal` | `l10n/ledger/b15-rides.tsv:173` |
| `rides_export_motion_csv` | `l10n/ledger/b15-rides.tsv:174` |
| `rides_export_phone` | `l10n/ledger/b15-rides.tsv:187`, `:220` |
| `rides_export_all_json` | `l10n/ledger/b15-rides.tsv:219` |

Deleting them fails `ledger_check.py --catalog-only` (the ledger rows must name catalog keys, and ledgers
are append-only). So they stay in `res/values/strings_rides.xml`, marked `translatable="false"`, and
`merge.py` leaves them out of every `res/values-<tag>/` folder the next time that folder is merged.
`rides_export_route_csv`, `rides_export_gpx`, `rides_export_phone_raw` and `rides_export_motion_raw` are
still shown, as the sheet's checkboxes; `rides_export_combined_json` replaced All (JSON).

Follow-up: the tooling pull request that retires B18's `more_lab_*` keys and the placement keys
(`b18-more-followups.md`, `placement-followups.md`) can delete these nine and their rows' checks.
