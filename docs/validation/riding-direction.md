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

An energy stretch between two readings takes the weaker end: any unknown end makes it unconfirmed magnitude; otherwise any inferred end makes it inferred draw. Inferred draw is at least 1.5 x any confirmed regen, so an inferred stretch is never negative.

Not yet used by recordings (`RideStore`) or the live range estimator; either could adopt it later.

Tests: `tests/RidingValuesTest.java` (`inferredDraw`, `inferredThreshold`, `inferredWindow`, `inferredFromFrames`).
