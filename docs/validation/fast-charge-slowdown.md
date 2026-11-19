# UT5 MAX fast charger: "Charging slowed" and the temperature-aware estimate

Estimator `ut5-fast-thermal-v10`, following [high-speed voltage/current estimates](fast-charge-taper.md).
It changes UT5 MAX **fast-charger** estimates only. Standard-charger, unknown-charger and other-model
estimates are unchanged in every field.

The owner, Nov 19 2026: "add battery temp into the algorithm as well … what the current temp is, how
quickly it rises when charging, how quickly we expect it to start throttling, how quickly it will
subsequently cool off … for the sake of 'charge done by' we probably stay conservative with it", and
"if any of this slows down app launch we should separate it out".

## What the battery does

On the fast charger the battery's BMS sets fixed current levels, and every change lands between two
readings:

- **Healthy:** about 10.05 A below a displayed 80%, then 8.0 A from 80% (every charge still below the
  charger's voltage limit takes that step; it is not temperature).
- **Cold:** 5.5 A below 15 °C (59 °F), 8.5 A from 15 °C, full current again from 20 °C (68 °F).
- **Hot:** 8, 6, 4 and then 3 A from somewhere between 38 and 41 °C (100–106 °F) up; it recovers in
  steps as the battery cools.
- **Temperature:** the battery warms about 0.25–0.3 °C a minute at full current and cools about
  0.1 °C a minute while throttled hot. Ambient temperature decides the rest, so these rates are rough.

A fixed nominal power cannot tell these apart: the cold 8.5 A stage reaches 450 W, 0.92 of a healthy
490 W. So the live current is compared with the current a healthy charge has at that battery level.

## The "Charging slowed" rule (`ChargeSlowdown`)

Fed once per fresh paired reading in `ScooterService.updateChargerDetection()`, beside the charge-age
tracker and the taper watcher:

- **Scope:** the UT5 MAX on the fast charger, detected or chosen. The standard charger (1.9–2.3 A) sits
  below every BMS limit.
- **Eligible:** outside the charger's voltage limit (V + 0.107 × I below 54.38 V, FastChargeTaper's
  CV_SET less 0.15 V); below the charge limit less 2% (an unknown limit counts as 100%); and at least
  60 s after charging starts or after a gap or pause in readings of 30 s or more.
- **Shows** once the current stays below 0.90 × the expected current (10.05 A below 80%, 8.0 A from 80%)
  for 60 s without a break. **Clears** once it stays at 0.95 × or more for 60 s, or at once when the
  charge leaves eligibility.
- **Cause:** cold at 21 °C (70 °F) or below, hot at 38 °C (100 °F) or above, otherwise (or with no fresh
  temperature) a plain "Charging slowed". The cause is set when it shows and kept while shown; a plain
  one takes cold or hot once a temperature names it.
- **State:** `ChargeSlowdown.State` (slowed, cause, battery temperature, and whether this charge was
  slowed cold), read through `ScooterService.chargeSlowdown()`. The charging status will show it; the
  estimate already uses the same state. Simulation never drives it.

On the maintainer's local recordings it shows only during real throttling (one cold charge, two hot
ones); healthy stretches stay at 0.997 × or more of the expected current.

## The estimate while slowed

`ChargingEstimate.forScooter` takes the state as its last argument (`null` keeps v9 exactly).

- **Time shown:** the estimate at the expected current (FastChargeTaper's voltage model with the pack
  voltage moved 0.1 Ω × the change in current; the v9 estimate re-run at that current where the model has
  no answer), plus the extra minutes the rest of the bulk stage (to 80%, to 85% inside the 8 A stage, or
  to a lower limit) takes at the current predicted for it:
  - **cold** (battery below 20 °C): the BMS's cold levels as the battery warms 0.25 °C a minute from the
    middle of its 1 °C reading, never below the live current;
  - **hot or unexplained:** the live current lasts to the end of the bulk stage; no recovery is assumed.
- **Charge done by:** the longer of the time shown and the same bulk calculation warming 0.15 °C a minute
  from the bottom of the reading, plus 6 minutes once this charge was slowed cold (a cold taper runs
  slower).
- **Hot onset is not predicted.** No recording shows one to tune on, and a guard fast enough to matter
  fired on healthy deep charges.

## Outside slowed stretches

The time shown is v9's. Charge done by changes in two places only:

- after a cold-slowed stretch, 6 more minutes for the cold taper, to the end of the charge;
- in the bulk stage at full current (below 72%, 9 A or more) where the bundled reference rather than
  past charges sets the time to 100%, it plans with the longer of that and the voltage model. The
  reference's percentage table runs short on charges that reach the voltage limit later.

## Cost

Nothing runs at launch and nothing is stored: the constants are bundled. Per reading the observer does a
few comparisons; while slowed, the estimate runs one more pure estimate (no gate, taper or history state
is touched). The cold bulk time is worked out per stage, not stepped.

## Validation

- `tests/ChargeSlowdownTest.java`: twelve synthesized charges (warm top-up, deep charge with an early
  voltage limit, cold start, the owner's 430 W at 67 °F example, hot start, a mid-band drop, the
  temperature edges, flicker, dropouts and ramps, a limit approach, the standard charger, the 80% step),
  the observer's edges, the per-stage bulk time against a stepped simulation, and the voltage planning.
- The same test replays `data/fast-charge-taper-v7` (no temperatures): only its one cold charge shows
  slowed (named plain, with no temperature to name it); every other time shown is unchanged, and every
  standard-charger row is unchanged in every field.
- On the maintainer's local replay set (kept private), the worst Charge done by margin across both
  scooters went from about +5 to about +17 minutes, and no standard-charger row changed.
