# Live range: range at this pace (riding mode)

Status: estimator core only (`LiveRangeEstimator`, `RangeReadout`). No UI, no strings and no service wiring yet. Riding mode will show it as an option in the range spot. Home's range is unchanged.

## What it is

The owner's goal (Nov 5): a range number that "reacts more instantly to changes in power etc, rather than a solid estimator". It "shouldn't swing to infinity when they're stopped or slowing down, probably hold onto the max speed they seem to hover around".

So the number is **range at this pace**:

- **Range = battery energy left ÷ the energy per km of recent riding at the rider's cruising speed.**
- **Cruising speed** is the speed the rider hovers around. It follows speed quickly upward and slowly downward, and it holds while stopped.
- **Only riding near that speed teaches energy per km.** Stops, slow stretches and coasting don't. So the number holds through a red light or a slowdown instead of shooting up. It never divides by a near-zero speed.
- **It reacts within about 20 seconds to a climb or a faster pace.** An easier pace raises it more slowly: over a minute or two.

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
f   = exp(−dt / 20 s)                         only for teaching intervals
W   = W·f + ΔWh,  K = K·f + Δkm               start: W = r0·K0, K = K0 (20 s at 20 km/h)
live = clamp(W / K, 0.5·r0, 3·r0)
r  += (live − r) · (1 − exp(−dt / τ)),  τ = 5 s if live > r, else 45 s
```

`W/K` is a ratio of sums, not an average of per-sample `P/v`, so a slow sample can't blow up. The prior fades in about 20 s of riding at pace.

The asymmetric follow is deliberate:

- a harder pace (the range falls) shows at once;
- an easier one (the range rises) shows gradually.

Chasing rises instantly would bias the rate upward on noisy power, so the rise side also smooths a little (5 s).

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

- **Display filter:** τ = 2 s on `R`.
- **Hysteresis:** the shown value moves only when the filtered one is a full step away (1.0 step; the plan had 0.6).
- **Movement limits:**
  - it may fall by the larger of 0.5 steps/s and 3 % of the value per second;
  - it may rise by the larger of 0.25 steps/s and 1 % per second;
  - up to 3 s of unused allowance may be saved.

A steady countdown at cruising speed (about one tenth every 12 s) is never throttled.

## Tuning

### Real rides: not run yet

The plan's replay on the Oct 22 owner backup (`work/owner-data/volt-2026-10-22-*.voltbackup`) needs the backup's password, and it wasn't available for this PR.

- **Tool:** `work/claude-range-replay/LiveRangeReplay.java` is written and is not a repo file. It trains the learned model only on earlier rides and replays each ride at 2, 5 and 10 s polls. It prints aggregates only:
  - next-2-km and rest-of-ride `|ln(rate / actual)|` for the live rate against the learned rate;
  - reversals and changes per minute, and the largest step;
  - the share of time LIVE;
  - the reaction to sustained 40 %+ changes in the energy per km actually ridden.
- **Smoke test:** synthetic ride journals, end to end.
- **The owner will export a fresh backup (Q9 A). Tuning will be redone on that fresh export,** and its results will replace this section. Until then, the defaults below come from synthetic rides only.

### Synthetic rides

`tests/LiveRangeScenarios.java` has the synthetic rides. Run `java -cp .local/build/core-tests/classes com.jjricks.volt.LiveRangeScenarios` to print the table.

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

| Defaults | Climb (63 % / 90 %) | Climb over | Slower | Faster | Steady reversals / 10 min | Steady ±mi (p95) | Stops | Coast | Descent |
|---|---|---|---|---|---|---|---|---|---|
| 2 s polls | 20 s / 44 s | 96 s | 98 s | 6 s | 10.8 | 2.2 | +0.5 % | 0 % | +3.5 % |
| 5 s polls | 30 s / 45 s | 115 s | 95 s | 10 s | 10.0 | 3.0 | 0 % | +0.9 % | +4.9 % |
| 10 s polls | 50 s / 80 s | 220 s | 120 s | 20 s | 6.9 | 3.3 | +0.5 % | +1.3 % | +9.3 % |

**Steady accuracy at 2 s.** Shown ÷ truth is −4 % at the median (p5 −10 %, p95 +3 %), on a true range of about 25 mi. The bias leans cautious: dropping coasting intervals and following rises faster both lean toward a higher rate. The number changes about 10 times a minute: the countdown plus noise.

**The trade-off.** The sweep (`work/claude-range-replay/LiveRangeSweep.java`) covers the memory τ, the rise and relax τ, the display τ and the hysteresis. Its front: no row both reacts faster to the climb and jitters less. At 2 s polls:

| Setting | Climb 63 % | Steady reversals / 10 min | Steady ±mi |
|---|---|---|---|
| τ 10, rise 0, relax 20 (most reactive) | 14 s | 23.9 | 2.9 |
| **τ 20, rise 5, relax 45 (default)** | **20 s** | **10.8** | **2.1** |
| τ 30, rise 5, relax 45 | 26 s | 9.2 | 1.9 |
| τ 45, rise 15, relax 45 | 50 s | 6.9 | 1.6 |
| τ 30, rise 15, relax 90 (calmest) | 46 s | 5.4 | 2.1 |

The default sits at the knee: going from 20 s to 14 s doubles the reversals. Going calmer than 26 s costs climb reaction for little gain.

**Without the coasting rule**, the 2-minute descent raised the number by about +70 % and a 20 s coast by +8 %. With it, they're +3.5 % and 0 %.

## Limits and follow-ups

- **Poll rate.** At 10 s polls (riding mode open without recording), reaction is about 2.5× slower and single steps are up to about 2 mi. The service-wiring PR should poll battery and power every 2 s while riding mode is open, as recording already does.
- **Route.** Route dominance is now intended, not a risk: "at this pace" is meant to follow a climb. A rider who wants a solid estimate keeps the learned range (the owner allows Volt's or the scooter's range in that spot).
- **Below the lowest band seen.** The number shows "≈". Recordings have never gone below 17 %, and the scooter limits power at low charge.
- **Other models.** Without confirmed power direction there's no ENERGY prior: `NO_HISTORY`, with the distance model's figure as "≈", or nothing.
- **Real-ride tuning:** pending the fresh backup (see above).
