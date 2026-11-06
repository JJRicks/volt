# Live range: range at this pace (riding mode)

Status: estimator core only (`LiveRangeEstimator`, `RangeReadout`). No UI, no strings and no service wiring yet. Riding mode will show it as an option in the range spot. Home's range is unchanged.

## What it is

The owner's goal (Nov 5): a range number that "reacts more instantly to changes in power etc, rather than a solid estimator". It "shouldn't swing to infinity when they're stopped or slowing down, probably hold onto the max speed they seem to hover around".

So the number is **range at this pace**:

- **Range = battery energy left ÷ the energy per km of recent riding at the rider's cruising speed.**
- **Cruising speed** is the speed the rider hovers around. It follows speed quickly upward and slowly downward, and it holds while stopped.
- **Only riding near that speed teaches energy per km.** Stops, slow stretches and coasting don't. So the number holds through a red light or a slowdown instead of shooting up. It never divides by a near-zero speed.
- **It reacts within about 20 seconds to a climb or a faster pace.** An easier pace raises it more slowly: over about a minute.

The learned range model (learned-range.md) supplies two things: the table of battery energy left at each gauge reading, and the starting energy per km before this ride has taught anything.

This replaces the plan's blend of history with the current ride (capped at a 60 % live share). That design was calm but slow by intent, and the owner asked for the opposite.

## The maths

Inputs per telemetry update: monotonic time, speed (km/h), signed power (W, positive = draw), battery reading (%), odometer (km), charging. Any reading may be missing. Learned prior: `r0 = whPerKm` and `E(s) = energyLeftWh(s)` from `LearnedRangeEstimate.Result`. Defaults are in `LiveRangeEstimator.Params` and `RangeReadout.Params`.

**1. Intervals.** Use an interval only when `0 < dt ≤ 12 s`. A longer gap is skipped, and the state is kept. More than 10 minutes of silence, or charging, starts a new ride.

- `ΔWh = (P_prev + P)/2 · dt`, the same trapezoid RideStore uses.
- `Δkm = (v_prev + v)/2 · dt · c`. Once the odometer has gone 1 km this ride, `c = clamp(Δodo / Σspeed-km, 0.75, 1.25)` corrects the speed sensor (the learned model's 25 % tolerance).

**2. Energy left (smooth staircase).** This step is unchanged from the plan.

- First reading `s`: `Ê = E(s)`, the middle of its gauge point.
- A one-point drop to `L`: `Ê = E(L + 0.5)`, the boundary just crossed.
- A bigger drop, or a rise: `Ê = E(s)`.
- Between drops: `Ê −= ΔWh`, held within `[E(L − 0.5), E(L + 0.5)]`.

Idle draw while parked still lowers `Ê`; it's real energy.

**3. Cruising speed `vc`.**

- `v̄` is speed smoothed with τ = 4 s.
- When `v̄ > vc`, `vc` follows it with τ = 8 s.
- When slower but still at pace, it falls with τ = 90 s.
- Below the pace gate (braking, a slow stretch) it falls with τ = 300 s.
- It holds when stopped (`v < 2 km/h`).

A lasting slower pace is still learned within a minute or two; a stop or a slowdown barely moves it.

**4. Which intervals teach energy per km.** An interval teaches only when both of these hold:

- **At pace:** both ends are at `≥ max(8 km/h, 0.6 · vc)`;
- **Not coasting:** `ΔWh ≥ 0.25 · r0 · Δkm`. Rolling downhill or easing off says little about the pace to come.

**5. Energy per km.**

```
f   = exp(−dt / 16 s)                         only for teaching intervals
W   = W·f + ΔWh,  K = K·f + Δkm               start: W = r0·K0, K = K0 (20 s at 20 km/h)
live = clamp(W / K, 0.5·r0, 3·r0)
r  += (live − r) · (1 − exp(−dt / τ)),  τ = 3 s if live > r, else 20 s
```

`W/K` is a ratio of sums, not an average of per-sample `P/v`, so a slow sample can't blow up. The prior fades in about 16 s of riding at pace.

The asymmetric follow is deliberate:

- a harder pace (the range falls) shows at once;
- an easier one (the range rises) shows gradually.

Chasing rises instantly would bias the rate upward on noisy power, so the rise side also smooths a little (3 s). The slower easing side is what keeps stops, coasts and descents from lifting the number.

**6. Raw range.** `R = Ê / r` (km). The snapshot also reports:

- `held`: no teaching interval for 5 s;
- `settling`: under 30 s at pace, or the prior still over half the weight;
- `belowSeen`: the reading is below the lowest band the learned model has evidence for, `lowestSeenPercent()`;
- `stale`: no battery or power reading for 15 s.

**Uncertainty stays internal (Q24).** There's no likely-spread readout. The only signals are `settling` and `belowSeen`, both shown as "≈".

## Readout

`RangeReadout.update(snapshot, units, tenths)` returns a state and a calm number in the rider's unit. Formatting and words belong to the widget, which isn't in this PR.

| State | When | Number |
|---|---|---|
| `HIDDEN` | Charging, or no battery reading | none |
| `ALMOST_EMPTY` | Below 2 % (Q26 C) | none ("Almost empty") |
| `NO_HISTORY` | No learned energy model | The distance model's figure as ≈ whole numbers, or none |
| `LOW` | 2–5 % (Q26 C) | Amber ≈, tenths when on |
| `SETTLING` | Under 30 s at pace | ≈ whole numbers |
| `LIVE` | Otherwise. Once live, it stays live until reset, so the tenths never flicker | Tenths when on (Q23 B: on by default, toggle later); ≈ if below the lowest band seen |

Flags: `held` (the widget may dim it) and `stale` (the last value, frozen, as the dashboard does).

**Units.** The units are `mi`, `km` or `dual`. Dual shows miles first, with km at the same precision. A step is 0.1 in the shown unit, or 1 when tenths are off, settling, or the value is 100 or more. The number is never "−0.0".

**Calm.** The readout keeps the parts of the plan's calm display that still fit, retuned for a reactive number:

- **Display filter:** τ = 6 s on `R`. It absorbs the power noise that the shorter estimator memory lets through.
- **Hysteresis:** the shown value moves only when the filtered one is a full step away (1.0 step; the plan had 0.6).
- **Movement limits:**
  - it may fall by the larger of 0.5 steps/s and 3 % of the value per second;
  - it may rise by the larger of 0.25 steps/s and 1 % per second;
  - up to 3 s of unused allowance may be saved.

A steady countdown at cruising speed (about one tenth every 12 s) is never throttled.

## Tuning

The defaults are tuned on the owner's recorded rides (the Nov 5 backup). The synthetic rides stay as regression guards. Only aggregates appear here: no rides, places or identifiers.

### What counts as success

The owner's goal (Nov 5): "just what would have happened if we kept going at that pace (at this speed and this power usage and this much battery drain every minute how far could we go)". So the main scores are reaction and calm, measured against the pace actually ridden. Forecast accuracy comes second.

- **Pace oracle.** At each moment, the actual Wh per km over the riding seconds within ±30 s (speed 8 km/h or more, coasting included, distance corrected by the ride's odometer). The score is `|ln(rate ÷ oracle)|` for the display-implied rate (energy left ÷ shown km). It's scored only in powered windows, where coasting is under 30 % of the km. In coast-heavy windows (descents, stop-and-go) the number holds by design, so those windows are only counted.
- **Lag.** The time shift that best lines the estimator up with the oracle.
- **Reaction.** Sustained changes only: 45 s of riding before, then two consecutive 45 s windows of riding, both 30 %+ away from before in the same direction, each with coasting under 30 % of its km. The score is the time until the display-implied rate closes 63 % of its gap to the new level, or 0 s if it's already within 10 %.
- **Calm.** Reversals of the shown number per 10 minutes, and the largest single step. Holds: the largest rise of the shown number over its value at the start of a stop (under 2 km/h for 10 s+) or a coast (no draw while moving for 10 s+), up to 10 s after it ends.
- **Forecast, secondary.** Next 2 km and rest of the ride (1 km or more), `|ln(rate ÷ actual)|`, next to the learned rate.

### The rides

- 41 rides recorded, 36 replayed.
- Skipped: 3 without a confirmed power direction (the estimator gets no energy model), and 2 with under 60 s at riding speed.
- Phone-only recordings are motion-sensor files with no scooter telemetry, so they can't be replayed.
- Each ride's prior is trained only on earlier rides. The first 4 rides come before the learned model is ready, so they borrow the first ready prior; they're in the tuning split only.
- The recordings are 1 s samples, down-sampled to the poll cadence.
- Split: chronological. The first 22 rides (46 km) tune; the last 14 (29 km) are held out.

**Why the earlier replay used only 8 rides.** It needed the learned model's strict per-ride evidence (no gaps, 2 km or more), which the live estimator doesn't need: it skips long intervals by itself. Most of the owner's rides are 1–2 km.

**Why its reaction metric misled.** It compared raw 60 s windows, stops included. Of its 22 events on rides of 2 km or more:

- 14 had a stop or a slow stretch inside a window;
- 4 were blips gone within the next minute;
- only 4 were sustained changes.

Two more effects mattered:

- the speed sensor reads about 8 % high (odometer ÷ speed-km: median 0.92), which the estimator corrects and the old reference didn't;
- coasting is a median 17 % of riding km.

### Held-out results (14 later rides)

**2 s polls (recording, and riding mode as wired):**

| | Before (τ 20, rise 5, relax 45, display 2 s) | After (τ 16, rise 3, relax 20, display 6 s) |
|---|---|---|
| Pace oracle, shown: median / p90 | 0.120 / 0.489 | 0.111 / 0.412 |
| Rate bias against the oracle (cautious > 0) | +0.121 | +0.096 |
| Next 2 km: live / learned | 0.123 / 0.510 · 0.247 / 0.333 | 0.110 / 0.496 · 0.247 / 0.333 |
| Rest of ride: live / learned | 0.249 / 0.726 · 0.242 / 0.502 | 0.260 / 0.593 · 0.242 / 0.502 |
| Lag against the oracle | 18 s | 20 s |
| Sustained 30 %+ changes (6): median / p90 | 91 s / 147 s | 62 s / 105 s |
| Harder / easier: median | 72 s / 147 s | 60 s / 105 s |
| Within 30 s / missed in 3 min | 17 % / 1 | 17 % / 1 |
| Reversals per 10 min: median / p90 | 6.6 / 8.4 | 6.6 / 8.4 |
| Largest step | 1.0 mi | 1.0 mi |
| Holds (13 stops and coasts): p90 / max rise | 0.0 % / 0.8 % | 3.1 % / 3.8 % |

**On the tuning rides at 2 s:**

- lag fell from 54 s to 6 s;
- the pace-oracle error fell from 0.127 / 0.405 to 0.100 / 0.392;
- reversals rose from 3.7 to 6.6 per 10 minutes;
- the largest step fell from 1.2 to 1.0 mi.

**5 s and 10 s polls, held out:**

| | 5 s before | 5 s after | 10 s before | 10 s after |
|---|---|---|---|---|
| Pace oracle, shown: median / p90 | 0.166 / 0.565 | 0.156 / 0.488 | 0.173 / 0.515 | 0.189 / 0.475 |
| Lag | 48 s | 28 s | 46 s | 36 s |
| Reversals per 10 min | 6.5 | 9.0 | 7.4 | 8.8 |
| Largest step | 1.4 mi | 1.5 mi | 3.2 mi | 3.8 mi |
| Holds: max rise | 4.0 % | 17.6 % (one coast) | 11.1 % | 10.6 % |

**In plain terms (2 s polls):**

- A harder pace on steady riding (a climb, speeding up) shows in about 20 s, and speeding up in about 6 s.
- An easier pace shows over about a minute.
- On the owner's real rides, a sustained change takes about a minute at the median. In most of those events, the rider slows below the pace gate or stops within the minute after the change, and the number holds there by design.
- Steady riding reverses about 6–7 times per 10 minutes, with no step over 1 mi.
- Stops and coasts lift it by under 4 %.
- The bias stays cautious: the rate runs about 10 % above the pace actually ridden, mostly because coasting doesn't teach.

**The trade-off.** It is noisier than the learned figure on the forecast scores, by design. It answers "if I keep riding like this", not "how far will this ride go". Faster easing was the main lever left. On the tuning rides, relax 6 s cut the oracle error to 0.082 / 0.355, but stops and coasts then lifted the number by up to 22 % and reversals rose to 9 per 10 minutes. Relax 20 s keeps holds under 4 %.

| Tuning rides, 2 s | Pace oracle median / p90 | Lag | Reversals / 10 min | Largest step | Largest hold rise |
|---|---|---|---|---|---|
| τ 20, rise 5, relax 45, display 2 s (before) | 0.127 / 0.405 | 54 s | 3.7 | 1.2 mi | 2.6 % |
| **τ 16, rise 3, relax 20, display 6 s (default)** | **0.100 / 0.392** | **6 s** | **6.6** | **1.0 mi** | **3.7 %** |
| τ 16, rise 3, relax 20, display 2 s | 0.092 / 0.397 | 6 s | 6.6 | 1.2 mi | 2.2 % |
| τ 16, rise 3, relax 12, display 6 s | 0.087 / 0.375 | 8 s | 9.2 | 1.0 mi | 8.8 % |
| τ 16, rise 3, relax 6, display 6 s | 0.082 / 0.355 | 8 s | 9.3 | 1.0 mi | 21.6 % |

Two structural changes were tried and left out because the held-out rides didn't improve:

- letting the first few seconds of a coast teach (energy floored at zero);
- letting steady, harder stretches below the pace gate teach.

The first also lifted the synthetic coast and descent. The display filter went from 2 s to 6 s; at 2 s, steps on the tuning rides passed 1 mi.

**Tools.** The replay tools aren't repo files: `work/claude-range-replay/LiveRangeReal.java` (scores), `LiveRangeRealSweep.java` (sweeps the tuning split) and `LiveRangeBeforeAfter.java` (the tables above). They print aggregates only.

### Synthetic rides (regression guards)

`tests/LiveRangeScenarios.java` has the synthetic rides. Run `java -cp .local/build/core-tests/classes com.jjricks.volt.LiveRangeScenarios` to print the table. `LiveRangeEstimatorTest` checks the guards at 2 s:

- a climb within 30 s;
- a faster pace within 20 s;
- a slower pace and the end of a climb within 90 s;
- at most 16 reversals per 10 minutes;
- no step over about 1 mi;
- stops and coasting at most 3 % up;
- a 2-minute descent at most 10 % up.

**The model.** A 120 kg scooter and rider, with rolling, aero, grade, acceleration and regen.

**The noise:**

- power readings carry ±12 % correlated noise plus 15 W;
- speed carries ±0.8 km/h, and the sensor reads 5 % high;
- the odometer has 0.1 km resolution.

**The prior:** 18 Wh/km, against a true flat-cruise rate of 15.6.

**What's scored.** Medians over 15 seeds (9 in the test):

- **Reaction:** the seconds until the shown range closes 63 % of its gap to the new pace's true range;
  - climb: 5 %, 15.6 → 33.9 Wh/km;
  - climb over;
  - slower: 32 → 22 km/h;
  - faster: 22 → 32 km/h.
- **Jitter on a steady 15-minute cruise:**
  - reversals of the shown number per 10 minutes;
  - the 95th-percentile distance from the noise-free truth.
- **Holding:** the largest rise over the starting value during 6 stops at lights, a 20 s coast, and a 2-minute 4 % descent.

| Defaults | Climb (63 % / 90 %) | Climb over | Slower | Faster | Steady reversals / 10 min | Steady ±mi (p95) | Stops | Coast | Descent | Largest step |
|---|---|---|---|---|---|---|---|---|---|---|
| 2 s polls | 20 s / 40 s | 64 s | 64 s | 6 s | 13.9 | 2.2 | +1.6 % | 0 % | +5.3 % | 1.0 mi |
| 5 s polls | 30 s / 45 s | 110 s | 70 s | 10 s | 12.3 | 3.1 | +0.5 % | +2.2 % | +8.6 % | 2.2 mi |
| 10 s polls | 50 s / 80 s | 210 s | 100 s | 20 s | 10.0 | 3.5 | +0.7 % | +6.7 % | +12.8 % | 2.3 mi |

Against the earlier synthetic tuning at 2 s (τ 20, rise 5, relax 45, display 2 s):

- the end of a climb and a slower pace now show in 64 s, down from 96 s and 98 s;
- steady reversals rose from 10.8 to 13.9 per 10 minutes;
- stops went from +0.5 % to +1.6 %, and the descent from +3.5 % to +5.3 %.

**Without the coasting rule**, the 2-minute descent raised the number by about +70 % and a 20 s coast by +8 %.

## Limits and follow-ups

- **Poll rate.** At 10 s polls (riding mode open without recording), reaction is about 2.5× slower, and single steps reach about 2.3 mi on the synthetic rides and 3.8 mi on the real ones. At 5 s they reach about 1.5 mi. The service-wiring PR should poll battery and power every 2 s while riding mode is open, as recording already does.
- **Route.** Route dominance is now intended, not a risk: "at this pace" is meant to follow a climb. A rider who wants a solid estimate keeps the learned range (the owner allows Volt's or the scooter's range in that spot).
- **Below the lowest band seen.** The number shows "≈". Recordings have never gone below 17 %, and the scooter limits power at low charge.
- **Other models.** Without confirmed power direction there's no ENERGY prior: `NO_HISTORY`, with the distance model's figure as "≈", or nothing.
- **Slow climbs.** Below the pace gate the number holds, even when the slow stretch costs more per km. A rule that let steady, harder slow stretches teach helped the tuning rides but not the held-out ones, so it was left out.
