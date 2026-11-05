# B11 follow-up: Review page lines that stay English

`BackupArchive.plan` builds these lines as Strings and the Review page (BackupActivity) shows them as they
are, so they stay English in a translated build. Fix before a translated release.

| Lines at 811d1c9 | What | Why it waits |
|---|---|---|
| `BackupArchive.java:564` | Replace everything, one line per category: `BackupRegistry.label(...) + ": " + count(...)` | `count()` picks the noun by category, so `HELPER_MODELS` has no model for it |
| `BackupArchive.java:667-668` | Merge, Dashboards and Scooter preferences: `label + ": " + (labels.isEmpty() ? "nothing to replace" : "replaces " + what + " for " + ...)` | `what` is a choice set on an earlier line |
| `BackupArchive.java:721-725` | Skipped rides and charges: `plural(full, noun) + " skipped · " + ...` | `noun` is a choice set on an earlier line, and a whole sentence needs a plural inside a choice |

The second and third rows are the "Still blocked" case in `l10n/README.md`. All three need a tooling pull
request: a helper model for `count()`, and a local whose initializer is a ternary of literals with choice
branches that may be plural rows. The `BackupArchive.plural` counts on those lines wait with them.
