# Standard-charger 99% stage by charging current (`ut5-stock-finish-v9`)

This follows [standard-charger pack-model estimates](stock-pack-estimates.md) (`ut5-stock-pack-v6`) and
[high-speed voltage/current estimates](fast-charge-taper.md) (`ut5-fast-taper-v8`). It changes one thing:
how the bundled UT5 MAX standard-charger reference places a reading in the displayed 99% stage. Everything
else, including the high-speed charger, the pack model below 89% and the 89-98% finishing table, is unchanged.

## Why

A UT5 MAX on its standard charger spends 25 to 32 minutes at a displayed 99%. The bundled reference located
progress there by matching the stored pack voltage of two owner finishes within 0.10 V
(`ChargeTaperMatcher`). Pack voltage differs between packs and chargers by tenths of a volt. On a scooter
whose pack reads differently, the match is refused, or it matches the wrong point:

- **Refused:** the estimate falls back to the current rate, a few minutes for a stage that still has half
  an hour to run.
- **Matched wrongly:** a reading lands on the wrong part of the stored finish.

The high-speed reference already avoided this ("falling power locates progress"). The standard charger's
reference did not.

The replay below used the owner's recordings with every pack voltage read 0.15 V or 0.3 V off, standing in
for another UT5 MAX:

- the 99% stage read up to **26.8 minutes short**;
- Charge done by would have finished up to **6.8 minutes late**, after its 20-minute buffer.

## The model

Five complete owner finishes and another UT5 MAX on its own standard charger (Scooter B in
[stock-startup-v5](data/stock-startup-v5/README.md)) share one shape at 99%:

| | Constant-current part at 99% | Then current decays, tau | 100% shows at | Constant-voltage line |
|---|---|---|---|---|
| Owner, 5 finishes | 7.0-8.2 min at 1.91-2.03 A | 21.2-23.8 min (17.4 in the oldest) | 0.68 A | 54.51-54.52 V - 0.22-0.27 x I |
| Scooter B | 4.5 min at 2.14 A | 23.5 min | 0.69 A | 54.48 V - 0.25 x I |

So the time is placed mainly by the current, which does not depend on how a pack's voltage reads:

    remaining = 0.7254 + 21.9386 x ln(max(I, 0.68) / 0.68)
              + w(I) x 0.8557 Ah/V x clamp(54.5118 - 0.2470 x I - V, 0, 0.4 V) / I x 60
    w(I) = clamp((I - 1.3) / (1.7 - 1.3), 0, 1)

- **Voltage only sizes the short constant-current part.** It is the Ah between the live voltage and the
  charger's constant-voltage line. It is capped at 0.4 V of headroom, and it fades out once the current
  falls below 1.7 A, which only happens in the decay. Below 1.3 A the current alone decides.
- **The voltage term is bounded:** it can add at most about 10.5 minutes at 1.95 A, and only in the first
  few minutes of the stage.
- **Contradictory readings get no finish.** That means more than 1.0 V below the line, or more than
  0.6 V above 54.51 V. The finish was refused before for those; it is refused now too.
- **Fitting:**
  - Fitted by least squares on the five owner finishes, from `sessions.csv` already published with
    [fast-charge-taper-v7](data/fast-charge-taper-v7/README.md).
  - The headroom cap and the two fade currents were set by hand, before any scoring.
  - Scooter B was never used for fitting.
  - [`fit_stock_finish.py`](data/stock-finish-v9/fit_stock_finish.py) reproduces the constants;
    `--check` (in CI) verifies the Java.
- **Past charges still come first.** Where a scooter's own past finishes match the reading, their time
  is used, exactly as before (`ChargeSessionStore.FinishingCurve`, which still uses the voltage matcher
  on that scooter's own voltages).
- **Nothing persisted changes.** Personal curves are rebuilt from saved recordings at each snapshot, so
  no migration applies. New recordings carry `ut5-stock-finish-v9`.

## Results

All values are minutes at displayed-99% readings. Error is the estimate minus the observed time to the first
displayed 100%. "No history" replays each charge as a UT5 MAX with no recordings of its own.

Leave one charge out (the constants are refitted without the charge being scored):

| Charge (no history) | Before (voltage match) | After |
|---|---:|---:|
| Owner finish 1 (oldest, quickest taper) | 1.12 | 3.38 (always long: +0.4 to +7.1) |
| Owner finish 2 | 1.15 | 1.01 |
| Owner finish 3 | 1.17 | 0.89 |
| Owner finish 4 | 1.11 | 0.92 |
| Owner finish 5 | 0.38 | 0.62 |
| Scooter B (never fitted) | 1.78 | 0.41 |

Every owner 99% reading (888, no history), all five finishes, with pack voltage offset to stand in for
another pack. These use the shipped constants; the zero-offset row is therefore in-sample, and the table
above is the held-out comparison:

| Voltage offset | MAE before → after | p90 before → after | Most short before → after |
|---|---|---|---|
| 0 | 0.99 → 1.07 | 2.01 → 2.32 | -2.7 → -1.7 |
| -0.15 V | 6.02 → 2.22 | 10.71 → 4.95 | -26.8 → -1.7 |
| +0.15 V | 5.39 → 1.49 | 7.55 → 4.06 | -14.5 → -5.4 |
| -0.30 V | 9.89 → 3.64 | 22.20 → 8.86 | -26.8 → -1.7 |
| +0.30 V | 9.28 → 1.58 | 17.15 → 4.32 | -21.5 → -7.8 |

Whole charges (standard charger, no history, five complete owner charges; Charge done by margin is the
smallest estimate + 20 - actual over the charge):

| Case | MAE before → after | Smallest Charge done by margin before → after |
|---|---|---|
| As recorded | 2.71 → 2.73 | +14.9 → +14.9 |
| Pack voltage -0.30 V | 8.66 → 7.40 | -6.8 → +16.8 |
| Pack voltage -0.15 V | 5.05 → 4.29 | -6.8 → +16.8 |
| Pack voltage +0.15 V | 4.74 → 3.95 | +5.5 → +10.7 |
| Pack voltage +0.30 V | 7.77 → 6.21 | -1.5 → +6.4 |
| Gauge 3 points ahead | 5.48 → 4.06 | -17.8 → +3.7 |
| Scooter B | 2.04 → 1.94 | +13.5 → +13.5 |

With each charge learning only from the charges before it, as the app does, the owner MAE is
2.86 → 2.92. Later finishes there mostly use the scooter's own past finishes at 99%: only 157 of the
replayed samples change, all in the first finish.

## Limits

- **What it was fitted and checked on:** six finishes from two scooters, one standard charger each.
  A charger that holds a much lower current, or a pack with a much slower decay, has not been observed.
- **The oldest owner finish** tapered faster (tau 17) than every other. With the shared tau it reads up
  to about 7 minutes long, on the safe side.
- **Still sensitive to another pack below 99%:**
  - the pack model below 89% moves about 10 minutes for 0.3 V;
  - the 89-98% table reads 5 to 9 minutes short when the gauge runs 3 points ahead.

  Neither can be refitted without recordings from another scooter.
- The offsets are a stand-in for another pack, not a measurement of one.

## Tests

- **`StockChargeReferenceTest.finishV9`** builds synthetic 99% stages for an owner-like shape and a
  Scooter-B-like shape, each at 0, ±0.15 and ±0.3 V offsets, through the production `forScooter` path. It
  requires:
  - never more than 9.5 minutes short;
  - a bounded long side;
  - a countdown that never climbs more than half a minute;
  - current-only behaviour below 1.3 A;
  - the voltage term within its cap;
  - no finish for contradictory voltages.
- **`FastChargeTaperTest`** keeps full parity with the earlier baselines for every other replayed sample.
  It allows exactly the 157 changed 99% samples, each of which must equal `finishMinutes`.
