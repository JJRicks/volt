# UT5 MAX fast charger: "Charging slowed" and the temperature-aware estimate

Estimator `ut5-fast-thermal-v10`, following [high-speed voltage/current estimates](fast-charge-taper.md).
It changes UT5 MAX **fast-charger** estimates only. Standard-charger, unknown-charger and other-model
estimates are unchanged in every field. Since 2.0.6 the "Charging slowed" status itself also shows on any
charger when the current is far below the standard charger's ([Slowed sooner](#slowed-sooner-206)).

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

- **Scope:** the UT5 MAX with the pack these current levels were measured on (rated capacity within 10%
  of 15.3 Ah, as the voltage model requires; unknown or other packs get no state and no layer).
- **Which current is slowed** (2.0.6, see [Slowed sooner](#slowed-sooner-206)):
  - **any charger**, known or not: below **1.6 A**. The standard charger's own current is 1.9–2.3 A
    (1.94–2.30 A in the committed recordings) and only falls below that in its finish at its voltage limit,
    so a current this low is the battery holding the charge back whichever charger is plugged in;
  - **the fast charger, detected or chosen:** below 0.90 × the expected current (10.05 A below 80%,
    8.0 A from 80%). Between 1.6 A and the fast charger's levels nothing shows without the fast charger
    known, since the standard charger's current sits there.
- **Eligible:** outside the charger's voltage limit (V + 0.107 × I below 54.38 V, FastChargeTaper's
  CV_SET less 0.15 V; without the fast charger known, also V + 0.247 × I below 54.36 V, the standard
  charger's finish line less 0.15 V); below the charge limit less 2% (an unknown limit counts as 100%);
  and at least 20 s after charging starts or after a gap or pause in readings of 30 s or more. A
  not-charging reading (an unplug, a charger pause) ends the charge, as before.
- **Shows** once the current stays below the level above for 20 s without a break and without rising
  (a reading 0.3 A or more above the one that began the 20 s begins it again, at that reading: a
  charger ramping up is not held low, at any spacing of readings), and no sooner than 70 s after charging starts with the battery at 35 °C or more, 90 s otherwise
  (the fast charger's soft start, below). **Clears** once it stays at 0.95 × the expected current (any
  charger: 1.7 A) or more for 60 s; at once on the reading that reaches the charger's normal level within
  30 s of a reading below the show level (fast charger: 0.95 × its expected current after one below
  0.90 × it; standard charger: its 1.9 A after one below 1.6 A, or below 1.7 A while shown; no charger
  known: either), whether the
  current jumps or rises over several readings; and at once when the charge leaves eligibility or another
  charger is chosen or detected. The expected current falling at the BMS's 80% step is no rise. A charger that is unknown for a moment changes which level applies, nothing else.
- **Cause:** cold at 21 °C (70 °F) or below, hot at 35 °C (95 °F) or above, otherwise (or with no fresh
  temperature) a plain "Charging slowed". The cause is set when it shows and kept while shown; a plain
  one takes cold or hot once a temperature names it.
- **State:** `ChargeSlowdown.State` (slowed, cause, battery temperature, and whether this charge was
  slowed cold), read through `ScooterService.chargeSlowdown()`. The charging pill shows it, and the
  fast-charger estimate uses the same state. Simulation never drives it.

On the maintainer's local recordings (with 2.0.5's 60 s timing) it showed only during real throttling
(one cold charge, two hot ones); healthy stretches stay at 0.997 × or more of the expected current.

## Slowed sooner (2.0.6)

The owner, Nov 24 2026, after a charge right after a ride: "the 'charge speed degraded' text should pop up
way faster, I was staring at the 62 W plugged into the high speed charger imagining myself as a new user
going huhhh?" The fast charger was plugged in with no charger chosen and none detected yet. The BMS held
the charge at about 1.04 A (50 W) for over 4 minutes with the battery at 34–36 °C. The owner unplugged and
replugged it twice: 1.98 A (96 W) for about 2 minutes, then 1.28 A (63 W) for under 20 s before the full 10 A.
Nothing showed: the rule needed the fast charger known, waited 60 s plus 60 s after each plug-in, and named
heat from 38 °C.

The owner's framing: the UT5 MAX's two chargers, standard and high-speed, both charge at a predictable
power around or below 80% (about 100 W and 500 W), so 62 W is room to say the charge is slowed; information
sooner rather than later, within the bounds of accuracy.

What changed:

- **Any charger below 1.6 A** (above). 1.04 A and 1.28 A (50 and 63 W) are slowed with no charger
  known. 1.98 A (96 W) is not: with no charger known that is the standard charger's own current; with the
  fast charger known it is.
- **Sooner, by temperature.** With the battery at 35 °C or more: 70 s after plug-in (75 s out of sight,
  where status comes every 15 s). Heat explains a low current, and that is the owner's case; 70 s is past
  the longest soft start in the owner's data (55 s) with 15 s to spare. Below 35 °C, or with no
  temperature: 90 s (2.0.5: 120 s). Either way the current must sit low for 20 s without rising 0.3 A above
  where the 20 s began (whatever the spacing of readings), so a soft start that ramps to full power
  (1.28 A for 55 s, then 30 or 60 s up to 10 A) shows nothing even at 36 °C; a level that wobbles by
  0.2 A restarts the 20 s once at most. Readings come every 2 s with Home open, so 20 s is many readings; a single low reading never
  holds it.
- **Cleared at once when full power arrives**, in one reading or over several (a soft start or a hot hold
  ending), so the pill doesn't say "slowed" beside 500 W; with no charger known, also when the current
  comes back to the standard charger's own level (a standard charger at 88% and 36 °C, where detection
  can't name it). A slow drift back across the band (9.3 to 9.6 A, 1.5 to 1.75 A) still takes 60 s, so a
  current wobbling around the line doesn't flap it.
- **A charging stop seen only in the background** (the live card's reports, between status reads) ends
  the watched charge too, so a quick replug in a background pause starts afresh.
- **Hot from 35 °C (95 °F),** from 38 °C. The BMS's own hot steps start at 38–41 °C on a long charge, but
  right after a ride it held the current low at 34–36 °C. Cold naming is unchanged.

Replaying the owner's timeline (its charging-state changes, held at 2 s readings; kept private): the pill
reads **Charging slowed · battery hot (97°)** from 70 s after plug-in (75 s with Home out of sight, where
status comes every 15 s) to the first unplug, over 3 minutes of the 4½ at 50 W. After the replug, 1.98 A
with no charger known shows nothing; the second replug's 1.28 A lasted under 20 s. With High-speed chosen,
the 1.98 A stretch (34 °C) would show it too, 90 s after its replug.

**The fast charger's soft start.** A healthy fast charge starts at about 1.3 A (63 W) before its full
current. That is not a slowdown, so below 35 °C nothing shows for 90 s. In the owner's recordings and
diagnostics the fast charger reached full current within 4 s, 19 s and 30 s of plug-in at 23–35 °C; a cold
charge (8 °C, 1.8 A) reached its cold level (7.5 A) within 30 s; the longest soft start held 1.28 A for
55 s at 25 °C before its charging state dropped for 5 s, and full current came 91 s after the first
plug-in. The standard charger starts at its full 2.2–2.3 A. The committed recordings start at bulk. Replayed
through the rule (kept private), none of the owner's soft starts shows anything, with or without the
charger known; the cold charge shows "battery cold" at 90 s, its BMS's real cold limit.

**The time left with no charger known.** The estimate uses the slowed state on the fast charger only
(`ChargingEstimate.withSlowdown`), so with no charger known nothing claims a fast-charger estimate. The
plug-in hold (`ChargeEstimateHold`, [Estimating first](charge-estimating-first.md)) shows **Estimating time
left** until the charger is known and, on the fast charger, its full current has started, for at most
3 minutes; an unplug starts that hold again. After the cap, a charge still unknown and
slowed shows the plain current-rate time (the live current lasting to 100%), and Charge done by plans
with it, as before: long, and on the safe side.

## The estimate while slowed

`ChargingEstimate.forScooter` takes the state as its last argument (`null` keeps v9 exactly).

- **Time shown:** the estimate at the expected current, plus the extra minutes the rest of the bulk stage
  takes at the current predicted for it. The bulk stage runs to 80% below the BMS's step; in the 8 A
  stage, to where this reading meets the charger's voltage limit at the live current (the headroom
  table); or to a lower limit. The estimate at the expected current is the v9 estimate re-run there
  (pack voltage moved 0.1 Ω × the change in current) where past charges still set the time, labelled as
  past charges; otherwise FastChargeTaper's voltage model (depth-aware since
  [v11](fast-charge-taper.md#v11-where-the-voltage-limit-comes-ut5-fast-cvpoint-v11)), labelled and
  recorded as the bundled reference. The predicted current is:
  - **cold**, only where a BMS cold level explains the live current (battery below 20 °C, the current within
    10% of that temperature's level, 5.5 A below 15 °C or 8.5 A to 20 °C, and that level below the stage's
    expected current): the BMS's cold levels as the battery warms 0.25 °C a minute from the middle of its
    1 °C reading, never below the live current; from 10 minutes of this charge's temperatures, at its own
    measured rate (below);
  - **anything else** (hot, plain, or cold but far from a cold level, such as 3 A at 19 °C or 8.5 A in the
    8 A stage): the live current lasts to the end of the bulk stage; no recovery is assumed.
- **Charge done by:** the longer of the time shown and the same bulk calculation warming 0.15 °C a minute
  (or the charge's own slower rate) from the bottom of the reading, plus 6 minutes once this charge was slowed cold (a cold taper runs
  slower).
- **Never less conservative** (review of #561): Charge done by is never shorter than the same reading's
  estimate without a slowdown, to 100% or to a limit. Neither is the time shown, except where a cold level
  explains the current and the cold model predicts the warm-up: there the estimate without a slowdown assumes the live current lasts and
  drops by up to 25 minutes at each BMS step up, which the cold model avoids; Charge done by keeps the
  longer time.
- **Hot onset is not predicted.** No recording shows one to tune on, and a guard fast enough to matter
  fired on healthy deep charges.

## The charge's own warming rate (`ut5-fast-warming-v12`)

A UT5 MAX fast charge from 8 °C at the BMS's 5.5 A cold level warmed about 0.13 °C a minute, half the
0.25 the cold model assumed, so the time shown ran 12–24 minutes short for most of the charge. A charge
from 13 °C warmed about 0.25. The rate depends on the room and the current, so the charge measures its own:

- `ChargeSlowdown` keeps, for the watched fast charge, each minute's average battery temperature (from the
  charge's first fresh temperature) as running least-squares sums: a few numbers, not the readings.
- Once 10 minutes hold a temperature, `State.warmRate` is the least-squares slope through those minute
  averages, so a whole-degree reading that steps early or late moves it a little, not the way two
  endpoints would. Before that it is 0.25, exactly as before.
- **Held to 0.10–0.25 °C a minute.** Never faster than 0.25 (the earlier assumption, and what the 13 °C
  charge measured). The floor, 0.10, sits below every rate the maintainer's cold recordings measured
  (0.12 at 10 minutes on the coldest, 0.13–0.15 after; 0.19–0.30 on the 13 °C one), so it never bound on
  them. It stops a pack whose whole-degree reading has not moved yet (a 1 °C step at 0.13 °C a minute
  takes almost 8 minutes) from reading as not warming at all. At any rate the cold model stays within the
  live current lasting to the end of the bulk stage, the bound every other slowdown uses.
- Charge done by warms at the slower of 0.15 and this rate.
- A new charge (charging stops, 30 minutes of silence, another charger or another scooter) measures again.
- Warm and hot charges never use it: the rate is read only where a BMS cold level explains the current.

Not modelled yet: the cold pack's slower taper. The same 8 °C charge reached the charger's voltage limit at
77% and tapered more slowly than a warm one; one recording is too few to fit that. Charge done by's six
extra minutes after a cold stretch still stand for it.

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
- Since 2.0.6 it also holds a charge shaped like the owner's after-ride one (no charger known, 1.04 A,
  then two replugs at 1.98 and 1.28 A, 34–36 °C, then 10 A; synthesized), the any-charger rule at
  and around 1.6 A on each charger, the standard charger's finish at a 95% gauge, the fast charger's soft
  start at 25, 34 and 8 °C and with no temperature (nothing shows), a 55 s soft start rising over 30 or 60 s at
  36 °C (nothing shows, at 1, 2, 10 and 15 s readings), a hot hold wobbling 0.2 A (shows at 70 s) and one
  0.3 A upswing (20 s later, once), a stretch at 1.65 A before 2.3 A (clears at once), a hot throttle held low (shows at 70 s, clears on the
  reading that reaches full power, in one step or over 30 s), the standard charger back to 2.3 A at 88%
  with no charger known (clears at once), drifts back (60 s), the background pause wiring, a charger detected or lost mid-charge, a replug, and readings every 15 s.
- The same test replays `data/fast-charge-taper-v7` (no temperatures): only its one cold charge shows
  slowed (named plain, with no temperature to name it); every other time shown is unchanged, and every
  standard-charger row is unchanged in every field. Fed again with no charger known, no sample of any
  session shows slowed.
- On the maintainer's local replay set (kept private), the worst Charge done by margin across both
  scooters went from about +5 to about +17 minutes, and no standard-charger row changed.
