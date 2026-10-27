# Charge done by: one scheduling buffer

## Policy (2026-10-27)

The owner reported a standard-charger session finishing around 08:10 for a 09:10 deadline and approved using the displayed estimate plus the existing twenty-minute buffer. Previously, the UT5 MAX standard-charger bulk model supplied a second, pessimistic duration and the scheduler subtracted twenty additional minutes. Its replay already showed up to 39.75 + 20 minutes early.

Newly programmed plans now use `remainingMinutesToLimit` as their planning duration, followed by the scheduler's unchanged twenty-minute reserve and rounding down to a minute. For example, a 09:10 deadline with a 90-minute displayed estimate starts at 07:20, aiming to reach the target at 08:50. This is a scheduling-policy change, not a recalibration of the displayed estimate.

The change is at the stock-pack-model composition boundary in `ChargingEstimate`, so schedule previews, direct enable, connected edits and the optional measurement flow all receive the same duration. High-speed charging, other models, the finishing reference from 89%, fitted constants and displayed estimator version remain unchanged. The internal historical cautious estimates remain reproducible for comparison.

Existing schedules are not rewritten on reconnect or upgrade. A paused active plan still reuses its captured duration under the existing SOC/target checks. To replace an old plan using the new estimate, charge briefly and explicitly enable a new plan. No background connection or automatic schedule adjustment is added.

## Replay

Reproduce the existing evaluation with:

```sh
python3 docs/validation/data/stock-pack-v6/fit_pack_model.py --check
```

The check refits from the five published standard-charge traces (two scooters), reproduces `evaluation.json`, and verifies the Java constants/parity fixtures. The new scheduling policy uses its **display** errors; the historical `planning` column describes the former policy. Add twenty minutes to predicted-minus-observed errors to obtain modeled finish lead. Minute rounding can add less than one additional minute.

| Scenario, target | Replay points | Smallest finish lead | Largest finish lead | Late finishes |
|---|---:|---:|---:|---:|
| Charge start known, 100% | 5,129 | 10.47 min | 42.88 min | 0 |
| Charge start known, 95% | 5,129 | 13.45 min | 41.02 min | 0 |
| Charge start known, 80% | 4,590 | 14.26 min | 40.80 min | 0 |
| First seen mid-charge, 100% | 496 | 13.07 min | 74.46 min | 0 |
| After ten minutes observing current, 100% | 483 | 11.27 min | 40.44 min | 0 |

These are leave-one-charge-out estimates: each trace is evaluated with constants fitted without that trace. Points within a recording are correlated, not independent trials. First-sight and ten-minute scenarios also have zero late finishes for the 80% and 95% targets in the evaluation.

The unknown-charge-age assumption is deliberately retained: until Volt sees the start or has enough current trend, it assumes charging just began. This can still make a plan early, even with the extra pessimistic model removed. The optional measurement flow remains available. This small dataset is not a deadline guarantee, and the unrecorded 08:10 session cannot be reconstructed. Replays model uninterrupted charging; they do not physically validate a charger cooling and restarting after a scheduled pause.

## Regression checks

- The actual stock-model Result feeds the scheduler at 80%, 95% and 100%, with observed and unknown charge age: duration equals the displayed estimate; only twenty minutes plus sub-minute rounding is added.
- Unknown targets remain unavailable; stale readings, unidentified chargers, reference admission and schedule mutation guards retain their existing tests.
- Old persisted cautious schedules retain their original start with zero radio writes after an estimator change.
- Existing first-estimate and warm-session tests assess deadline coverage with the explicit scheduling buffer, rather than requiring the unbuffered estimate itself to overpredict.
- Full core suite and Android build validate the service integration; generated model/evaluation checks confirm no coefficient changes.
