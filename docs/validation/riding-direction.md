# Riding mode: power direction

Riding mode (`RidingValues`) needs to know whether power is draw (battery use) or regen. The scooter reports the current's sign separately, in 0x79. Some models send 254 there, or nothing, so the sign is not always known.

## Three levels

Each power reading keeps the direction it was measured under. A later sign never re-signs it.

- **Confirmed** (`DRAW`, `REGEN`): measured under a 0x79 sign of 0 or 1.
- **Inferred draw** (`DRAW_INFERRED`): no known sign, but at least the inferred-draw threshold. It counts as draw.
- **Unknown** (`UNKNOWN`): no known sign and below the threshold. Only its magnitude is known.

A same-time sign correction from Telemetry (after a 0x79 reply) is the reading's own sign, so it still replaces the direction. Regen is never inferred.

## Threshold

1,200 W, or 1.5 x the strongest confirmed regen so far in this ride if that's higher. It is decided once, when the reading arrives. A reading above the 6,000 W glitch cap is never inferred.

The owner's idea (Nov 6): "ive never seen regen go above like 100-200 watts. is there merit to... if we're kicking past 1kW its probably safe to say its sipping battery and log it like that".

Evidence, from the owner's 38 rides with a confirmed direction (aggregates only):

- strongest regen 863 W, at about 42 km/h; 99.9th percentile of regen 741 W; no regen sample over 1 kW;
- draw median 857 W, 90th percentile 1,784 W; 85 % of draw energy from samples over 1 kW;
- 238 samples had an unknown direction, 63 of them over 1 kW.

1 kW is only 16 % above that peak regen, so the floor is 1,200 W.

## How each level counts

| | Confirmed | Inferred draw | Unknown |
|---|---|---|---|
| Ride energy | signed | draw, also in `inferredDrawWh()` | magnitude, in `unconfirmedEnergyWh()` |
| Efficiency window | `directionConfirmed` and `directionKnownOrInferred` | `directionKnownOrInferred` only | neither |
| Draw peak, bar scale | yes | yes (draw) | scale only (magnitude) |
| Bar and trace | signed | positive | magnitude |

An energy stretch between two readings is classed by both ends:

- any unknown end: unconfirmed magnitude;
- inferred draw and confirmed regen: also unconfirmed magnitude. The direction changed at a moment nobody measured, and the regen can be stronger than the threshold the draw was inferred under (the threshold only knows the regen seen so far);
- inferred draw and inferred or confirmed draw: inferred draw. Both ends are positive, so `inferredDrawWh()` is never negative;
- two confirmed ends: signed and confirmed.

So ride energy = confirmed + `inferredDrawWh()` + `unconfirmedEnergyWh()`, and no confirmed stretch is lost.

## The bar follows the reading's sign

The power bar (and a ring showing power) shows the same signed watts as the number and the ride graphs: voltage x current from one report, negative for regen. The UT5 MAX sends the current's size in 0x72 and its sign in 0x79 just after, so a 0x72 reading first arrives with the previous sign and 0x79 then re-signs it in place. The bar follows that correction, not only new readings (before Nov 6 it kept the stale sign until the next 0x72, so a brake could show a red draw bar beside a green regen number). A change of sign restarts the bar from the zero line (`GaugeSmoother.follow`), so the bar is on the right side from the first frame and only the size glides. Tests: `RidingValuesTest.meterMatchesRecording` and `unknownUntilSign`, and `GaugeSmootherTest.follow`.

Not yet used by recordings (`RideStore`) or the live range estimator; either could adopt it later.

Tests: `tests/RidingValuesTest.java` (`inferredDraw`, `inferredThreshold`, `inferredWindow`, `inferredFromFrames`, `inferredThenStrongerRegen`).
