# High-speed charger voltage/current estimates

Estimator `ut5-fast-taper-v8` (introduced as `ut5-fast-taper-v7`), following [standard-charger
pack-model estimates](stock-pack-estimates.md) and [buffered scheduling](charge-done-by-buffer.md). v7
changed UT5 MAX **high-speed** estimates only where the bundled high-speed reference and personal curves
could not place a charge and Volt fell back to the current rate. v8 also lets a charge's own measured
taper replace both once it is established ([below](#v8-the-charges-own-taper)). Standard-charger,
unknown-charger and other-model estimates are unchanged.

## What went wrong

The high-speed reference and personal finishing curves find their place in a charge by the displayed
battery percentage, then check that live power matches what was seen at that percentage. On this
scooter the percentage at which the charger reaches its voltage limit (and current starts to fall)
varies a lot: 72% in the reference charge, about 86% in two later charges and 91% in one started near
full. At 91% that last charge drew 431 W, against 160-229 W in the reference charge at 91%, so every
check declined it and the estimate used the current rate: rated Ah × missing percentage ÷ live current.

Through the taper that rate is far too short. The charge from 91% showed about 10 minutes for its first
14 minutes while 27 down to 13 remained, rising while current fell and dropping at each percentage
step. The amount of charge was right (1.36 Ah delivered for 9%, against 1.38 Ah from the rated
capacity); the constant-current assumption was not. A charge from 73% showed 16-22 minutes for 24
minutes while 30-48 remained.

## Method

Only a high-speed **current-rate** time to 100% is replaced (`ChargingEstimate.withFastTaper`,
`FastChargeTaper`). The SOC-indexed reference and personal curves keep priority wherever they apply.

1. **Constant current until the voltage limit.** The charger's limit appears at the pack as
   54.53 V − 0.107 Ω × current (cable drop): 53.47 V at 10.09 A in the reference charge, 54.46 V at
   the 0.68 A end of every charge, and 53.69 V at 8 A in a later charge not used to set it. Below
   that voltage, the charge still to deliver at constant current comes from the volts of headroom,
   using the reference charge's constant-current stage (0 V → 0.055 Ah, 1 V → 1.06 Ah,
   5.8 V → 8.59 Ah; no extrapolation past 5.8 V). Bulk time is that charge ÷ live current. A
   current below 4 A below the limit is not a high-speed bulk stage and keeps the current rate.
2. **Exponential taper.** At the voltage limit, current decays close to exponentially to about
   0.68 A, when the scooter first shows 100%: remaining minutes = τ × ln(current ÷ 0.68). The
   per-charge median τ was 14.2, 13.2, 14.0 and 11.2 minutes; the reference value is 14.1.
3. **This charge's own τ.** While current falls at the voltage limit (at least 53.3 V and below 97%
   of the highest current seen there), Volt keeps the last ten minutes of ln(current) at 10-second
   spacing. After 3 minutes of decay the fitted slope starts to replace 14.1, fully by 6 minutes,
   limited to 8-20 minutes. A current step below the limit voltage (one charge dropped from 10 A to
   8 A at 80%) is not counted.
4. **Holding a personal finish.** A personal finishing curve matches live power stage by stage, so it
   can drop out when the gauge steps ahead of the saved charges. For up to 3 minutes after a personal
   estimate was shown in this charge, its countdown continues instead of switching to a fallback.

The runtime state (watched decay and the held finish) belongs to one scooter's charge, like the
standard charger's age tracking, and resets when charging stops, the scooter changes or readings stop
for 30 minutes. Nothing is stored. Pack capacity must be within 10% of 15.3 Ah.

**Lower cutoffs.** The model gives time to the displayed 100% only. A 100% limit uses the same time.
A lower cutoff keeps its own evidence, otherwise the current rate, but never more than the time to
100%, and its recorded source stays `CURRENT_RATE`.

**Charge done by.** Planning minutes equal the displayed estimate, and the schedule adds its single
20-minute buffer, as for every other estimate.

**Recorded source.** New predictions use the existing `FAST_REFERENCE` source (shown as High-speed
reference): this is the bundled high-speed reference, now located by voltage and current. A held
finish is recorded as `PERSONAL` with its session count. No source value was added, so earlier app
versions read new recordings and backups unchanged; the estimator version separates them.

**Finishing curves and voltage.** In the same release, a saved finishing stage whose power is nearly
flat (a bulk stage) also needs live pack voltage within 0.35 V of that stage's recorded range. At the
same current a bulk stage's power matches any other bulk stage; without this check the 73% charge
above matched the reference charge's 73% stage at 53.5 V while reading 51.8 V, and showed 37 minutes
with 49.6 left.

## Replay

[`fast-charge-taper-v7`](data/fast-charge-taper-v7/) holds the owner's UT5 MAX recordings reduced to
numeric replay fields by `strip_export.py`: no IDs, dates or wall clocks. v7 was introduced with the
first 13 (8 standard, 5 high-speed, sessions 0-12); v8 appended three later complete high-speed charges
(sessions 13-15) recorded live under v7. `tests/FastChargeTaperReplay.java` replays each one through the
production store and estimator, learning only from the recordings before it. `expected-v6.csv` is the
same replay of the release before v7 (b21d3f6); it reproduces the predictions the app recorded live for
all three charges captured under `ut5-stock-pack-v6` (high-speed exactly, standard within 0.02 minutes).

Predicted / observed minutes to the first displayed 100% (error), at the start of the recording and
after 5 and 10 minutes; MAE over every sample:

| Charge | Start | v6 start | v7 start | v6 5 min | v7 5 min | v6 10 min | v7 10 min | v6 MAE | v7 MAE |
|---|---|---|---|---|---|---|---|---:|---:|
| Reference | 16% | 92.1/89.1 (+2.9) | same | 84.9/84.1 (+0.8) | same | 79.3/79.1 (+0.2) | same | 0.4 | 0.4 |
| 2 | 73%\* | 125.2/43.2 (+81.9) | same | 26.0/28.6 (−2.6) | 29.9/28.6 (+1.3) | 26.0/28.6 (−2.6) | 29.9/28.6 (+1.3) | 1.3 | 1.3 |
| 3 | 73% | 37.0/49.6 (−12.5) | 48.6/49.6 (−0.9) | 20.0/44.5 (−24.5) | 43.5/44.5 (−1.0) | 20.5/39.5 (−19.0) | 40.2/39.5 (+0.7) | 8.6 | 0.7 |
| 4 | 91% | 10.3/27.3 (−17.1) | 36.0/27.3 (+8.7) | 10.7/22.3 (−11.6) | 25.7/22.3 (+3.4) | 11.3/17.3 (−5.9) | 17.5/17.3 (+0.3) | 6.2 | 1.9 |

\* Charge 2 opened at 2 A and 51.4 V and then has a 15-minute recording gap; its first estimate is the
current rate in both versions, since 2 A is not a high-speed bulk current. A fifth high-speed
recording ended at 86% without a finish: at 84%, 8.06 A and 52.97 V its first estimate moves from 18.2
to 40.4 minutes (another charge needed 37.6 minutes from 84% at 8.06 A).

The largest jump in the displayed estimate between samples beyond the elapsed time fell from 7.8 to
0.9 minutes (charge 4) and from 13.0 to 4.5 (charge 3). Charge 4 never shows the current rate, and once
it shows a personal estimate it keeps one to the end.

All 5,457 standard-charger samples and the reference high-speed charge were identical to v6
(`FastChargeTaperTest` checks every one against `expected-v6.csv`; since v8, the reference charge only
until its own taper is established, after 57.5 of its 89.1 minutes).

## v8: the charge's own taper

**What changed.** Once the charge is in the taper (`FastChargeTaper.tapering`) and at least six
minutes (`FIT_FULL`) of its own decay are watched, which is when its own τ has fully replaced the
reference 14.1, the time to 100% comes from the voltage/current model with that τ
(`FastChargeTaper.ownTaper`, checked in `ChargingEstimate.forScooter` after v7's choice is made). Four
more conditions keep it to a charge that is still decaying at the pace its fit uses:

- **Usable.** The fit is falling and no slower than `FLAT_TAU`, 60 minutes, beyond which `tau()`
  already falls back to the reference.
- **Recent.** The newest decay point (a new low by at least 0.005 A) is no older than `RECENT_MS`,
  2 minutes.
- **At the estimate's pace.** The decay points of the last `RECENT_FIT`, 3 minutes, fitted alone (at
  least three of them), fall with a τ no more than `SLOWER`, 25%, longer than the `tau()` the estimate
  uses.
- **No step.** None of those recent points fell from the one before faster than a decay with τ
  `STEP_TAU`, 4 minutes, would.

Decay points are only added, and old ones pruned, at a new low. So a plateau keeps an old fit in place
(the recency check retires it), and a decay that stops and then creeps down 0.01 A at a time keeps an
old, steeper fit with fresh points: each creep step is a new low and passes the recency check. Within
3 minutes of the stop, though, the recent points are only that creep, far slower than the fit, and the
pace check declines. The same check declines a real taper slower than `tau()` can show: `tau()`
clamps at `TAU_HIGH`, 20, so a taper whose recent τ is above 25 is left to v7. A charger's sudden step
down steepens both fits, the recent one most, so it would pass the pace check while `tau()` reads far
too short. The step check declines while a fall between two decay points faster than a τ-4 decay is
in the 3-minute window: a fall from 4.3 to 2.5 A in a straight line within about 90 s from its first
reading, one over 2 to 2.75 minutes partway through. After that the pace check compares the slower
decay that follows. A slower fall, or a step down hidden in a gap of about 2.5 minutes in readings or
after a 2.5-minute plateau, gets past the step check: the own taper can then hold through the fall and
up to about 2 minutes after it, also when the fall began before the six minutes were reached (**Step
down**, below). When any check fails, the estimate is v7's for that sample, including v7's held
personal finish. It now replaces the SOC-indexed reference and personal curves too, not only the
current rate. Those place the
charge by the battery percentage, which steps unevenly in the taper; the measured decay does not. The
result and its label are the existing high-speed estimate (High-speed reference, recorded as
`FAST_REFERENCE`, 0 learned sessions) instead of "From N past charges". A lower cutoff keeps v7's
source: past charges' time to the cutoff where they have one (labelled "From N past charges", as in
v7), otherwise the reference or current rate. Its time is never longer than the time to 100%. v7
could show past charges' time to a cutoff very close to 100% as longer than its own time to 100%, so
there the time to the cutoff, and Charge done by, can now be a little shorter than v7's (by up to
about 2 minutes in the replay below); elsewhere they are v7's. It applies only to the high-speed
charger on the UT5 MAX (product 2538) with a usable live estimate (at least 0.05 A, voltage and power
present), a valid rated capacity and battery below 100%. Nothing was fitted: the switch-over point is
the existing `FIT_FULL`, and the model, τ limits and 0.68 A end current are v7's. `RECENT_MS` comes
from the slowest decay `tau()` still uses: at `FLAT_TAU` near 0.68 A, current falls one 0.01 A
reading step about every 0.9 minutes, and 2 minutes is about twice that. `SLOWER` bounds what the pace
check lets through only while the decay keeps its recent pace: then the time shown is short by at
most 25% of itself (tail = τ × ln(I / 0.68), with the true τ at most 1.25 times the τ used). The
longest tail the model can show in the taper, τ 20 from 11.6 A × 0.97 (the highest current that counts
as taper), is 56 minutes, so that shortfall is at most 14 minutes, inside Charge done by's 20-minute
buffer. A taper that keeps slowing passes the 3-minute check at every reading and can read shorter
than that; how much shorter depends on how fast it slows. In constructed tapers whose local τ grows
steadily (two synthetic past charges at 4 A):

- from 13 by 0.1, 0.2 or 0.3 minutes per minute: inside the buffer (smallest margins +16.4, +12.1 and
  +6.9 minutes);
- from 16 by 0.4: 35.8 minutes after 7 minutes of decay where 60.0 remained, 40% short (v7: 60.0 from
  past charges); Charge done by up to 4.3 minutes past its buffer on 39 samples where v7 stayed inside
  it, and 4.7 past a minute earlier, where v7 showed the same time;
- from 14 by 0.5: up to 48% short (32.0 minutes where 61.7 remained; v7 60.0 from past charges), up to
  9.6 minutes past the buffer on 86 samples where v7 stayed inside it (v7 +18.3 there);
- from 12 by 0.6: up to 55% short (27.9 minutes where 61.5 remained; v7 60.0), up to 13.6 past on 98
  samples (v7 +18.5);
- by 0.8: about two-thirds short, past the buffer in v7 as well (worst 56.5 minutes past where the two
  differ, v7 35.5).

A knee slows the taper sharply instead: local τ 12 rising to 30 over four minutes kept the own taper
for 2.3 minutes into the slowdown, 8.8-10.1 minutes past the buffer (v7 +32 to +35), and rising to 45
kept it 1.8 minutes, about 35 past, as it already was before the knee, where no estimate can see the
slowdown coming (v7 +4.5 to +8.3, from slow past charges); rising to 20 stayed inside (+6.7). These
are constructed profiles. The owner's tapers stayed near exponential: in their high-speed charges the
recent τ was at most 1.12 × `tau()` while the own taper applied (3-minute fits; 2- to 5-minute fits
gave 6-14% longer), the newest decay point was never older than 0.8 minutes, and the full fit's raw τ
was 10.5-25.0 minutes. `RECENT_FIT` is half of `FIT_FULL`: long enough to hold at least six decay
points in the owner's charges, short enough that a creep replaces the old points within 3 minutes.
`STEP_TAU`, 4 minutes, is faster than any fall a taper showed: in the owner's charges no recent decay
point fell faster than a 5.62-minute τ from the one before while the own taper applied (a `STEP_TAU`
of 5.5 still leaves every owner sample unchanged, 5.8 changes 17), and ±0.03 A of reading noise on
constructed τ-13 and τ-22 decays gave 4.87 and 4.67. A charger stepping from 4.3 to 2.5 A falls at
0.8-1.1 within 30 s, and at 2.2 by the end of a 90-s straight line. A `STEP_TAU` of 2, as first
committed (c01a57d), caught a straight-line fall from its first reading only within about 45 s, a
step inside a gap in readings only up to 60 s; 3 let a 90-s fall through for 30-40 s and a 2-minute
one for 90 s. Every probe sample that 4 changes against 2 now shows v7's answer, and none of them is
past the buffer where 2 was inside it.

**Plateau and stopped decay (review of 4da6840).** Without the usable and recent checks, a 10 A ceiling
followed by 8.00, 7.99, 7.98 and 7.97 A over six minutes and then 20 minutes at 7.97 A kept the own
taper on with the reference τ: at 75% with two matching synthetic past charges, v7 shows 122.5 minutes
from past charges and 4da6840 showed 34.7 from the high-speed reference, a Charge done by about 88
minutes early. A recent but flat fit (a new 0.01 A low every minute) and a real τ-11 decay that
stopped for 10 minutes did the same (34.6 and 20.6 minutes). `FastChargeTaperTest.plateau` pins all
three to v7's time, source, label and Charge done by minutes at 100% and 95% limits, and checks that a
recent, usable decay still takes over. These are constructed cases, not recorded charges.

**Slow taper and creep (rechecks of a30493a).** With only the usable and recent checks, two more
constructed cases still took over from the same past charges. A real τ-40 decay read after 7.5
minutes, at 6.63 A, showed 45.5 minutes (τ clamped to 20) where a pure exponential needs about 91 and
v7 shows 122.5 from past charges; a raw τ of 28 showed 43.9. A τ-11 decay that stopped and then crept
down 0.01 A every 1.5 to 2 minutes kept the own taper for about 10 minutes after the stop, 44-60
minutes shorter than v7's answer from past charges. `FastChargeTaperTest.plateau` now pins both slow tapers, creep every
0.5, 1, 1.5, 2, 3 and 5 minutes (read 2-5 minutes after the stop) and a plateau followed by 90 s of
decay (whose fit over six minutes is still flat) to v7's answer at 100% and 95% limits. It also checks
that a raw τ of 24 still takes over (43.0 minutes, clamped; a pure exponential needs 51.6) and that one
minute after a stop the creep's first low still counts.

Constructed timelines read every 10 s (scratch probes, not committed; histories are synthetic) show
the same:

- **Creep.** After a stopped τ-11, τ-20 or τ-25 decay, with creep every 10 s to 5 minutes, the own
  taper hands back within about 3 minutes of the stop, bounded by `RECENT_FIT`: once the newest low is
  3 minutes past the stop, the recent fit holds only creep. Its last reading came at most 2.0 minutes
  after the stop, and 2.8 minutes when the first creep low came a minute after the stop and the next
  ones every 2 minutes; a creep of 0.01 A every 15 to 30 s from 0.7 A (after a τ-11 decay from 1.6 A)
  last read 2.5-3.0 minutes after the stop. A creep every 10 or 20 s takes over again only once, at a
  lower current, that steady fall is itself as fast as the estimate's pace; the time shown then is at
  most 0.2 minutes short of that fall's own finish (0.10 and 0.21 minutes, on two readings at 0.69 A
  in each of two timelines, after a τ-11 decay stopped at minute 20). A pure plateau, or current that
  rises again, hands back after `RECENT_MS`, 2 minutes.
- **Slow tapers.** A raw τ of 26 takes over only for its last three readings, at 0.70 A (0.58 minutes
  shown where 0.5-0.8 remained), and 28 or more never does; otherwise they show v7's answer. 20-24
  takes over and reads at most 2 minutes shorter than v7 does against a pure exponential. At about 25,
  right at the limit, the source can switch back and forth (12 to 16 switches, against v7's 2 to 4;
  table below).
- **Gaps and pauses.** A decay that continues through a 3- to 8-minute gap in readings takes its own
  taper back 20 s after the gap; after a 12-minute gap, which leaves no point from before it in the
  10-minute fit window, it takes over once `FIT_FULL`, 6 minutes, of new decay is watched. After a 6-
  or 8-minute gap, as in a30493a, it can later step back for 2-4 minutes while the points before the
  gap leave the fit window. A decay that pauses for 2 minutes or more hands back `RECENT_MS` into each
  pause and takes over again 0.5-1.2 minutes after it resumes (6.2 after a single 10-minute pause);
  after a 5- or 6-minute pause it can step back once more for 1-2 minutes in the same way.
  A decay reported in 2.5-minute steps never takes over: `RECENT_FIT` never holds three of its points
  (`FastChargeTaperTest.plateau` checks it).
- **Step down.** A τ-13 decay falls from 4.3 to 2.5 A at minute 8 and then decays with τ 30 or 40.
  5648b59, without the step check, kept its own taper through the 3 minutes after a 30-s fall with
  `tau()` falling to its `TAU_LOW` clamp of 8, showing 21.5 down to 9.9 minutes where about 50 remained:
  Charge done by up to 19.8 minutes past its buffer (τ 40; 7.2 for τ 30), where v7's past charges had
  +32 (+44.8). A fall to a plateau at 2.5 A did the same for 2.5 minutes from the start of the fall
  (12.2 past, v7 +37). With a `STEP_TAU` of 2 (c01a57d) the 30-s fall handed back, but the same fall
  over 90 s kept the own taper for 3.5 minutes, down to 10.4 minutes where 50 remained (19.6 past, v7
  +32), and so did straight-line falls over 60 s to 4 minutes, a step inside a 70- to 130-s gap in
  readings, an exponential fall with τ 2.1-4, a 4% drop every 30 s, a fall from 5.6 to 3.5 A over 90 s
  after a τ-20 decay (26.5 past) and a 90-s fall from 5.9 to 3.5 A ending at minute 5.5, still in the
  window when `FIT_FULL` was reached (31.8 past, v7 +27.2). With `STEP_TAU` 4, a straight-line fall
  within 90 s, an exponential fall with τ up to 4 (τ 4 after one reading), the 4% drops, the 5.6 to 3.5
  A fall and the early 90-s fall above, and a step inside a gap of up to 130 s or after a plateau of up
  to 2 minutes all hand back from the first reading of the fall and show v7's answer. A straight-line
  fall over 2, 2.25, 2.5 or 2.75 minutes is caught 40 s, 70 s, 1.8 and 2.5 minutes into it (by then
  11.9, 13.7, 15.7 and 17.5 minutes past the buffer; v7 +31). Slower falls are not caught: over 3 to 5
  minutes in a straight line, or exponential with τ 4.5-6, the own taper holds through the fall and
  1.5-1.8 minutes after it, 17.4-19.5 minutes past the buffer, where v7 has +29 to +32. A step inside a
  150-s gap holds it for 4 readings after the gap (17.2 past, v7 +32.2), after a 2.5-minute plateau for
  3 readings (18.1 past); a gap of 3 minutes or more, or a 3-minute plateau, leaves too few or too old
  decay points and hands back. A fall from 5.9 to 3.5 A over 3 minutes ending at minute 7 holds it from
  `FIT_FULL` for 17 readings, up to 30.7 past (v7 +25.7 to +28.3); over 2.5 minutes, for 2 readings
  (29.9 past). Before any of these falls, the own taper reads the τ-13 decay it sees; with τ 40 after
  the fall that is 8.3-12.8 minutes past the buffer, a later change no estimate can see (v7 happened to
  have about +30 from slow past charges). Once the fall has left the 3-minute window, the pace check
  hands the slower decay to v7, whose own fallback for this timeline (the high-speed reference with the
  same short τ, up to 19 minutes past the buffer) is unchanged. `FastChargeTaperTest.plateau` pins the
  30-s and 90-s falls (τ 40) to v7's answer; no two readings of the 90-s fall drop faster than a τ-2.2
  decay, so it fails with a `STEP_TAU` of 2.

Source switches in these constructed timelines (10-s readings, two synthetic past charges unless
noted; switches are changes of recorded source):

| Timeline | Minutes | v7 | a30493a | v8 |
|---|---:|---:|---:|---:|
| Raw τ 25 from 8 A | 40 | 2 | 2 | 12 |
| Raw τ 25 from 8 / 3 / 1.5 A | 70 | 2 / 2 / 4 | 2 / 2 / 6 | 16 / 16 / 16 |
| Raw τ 26 from 8 / 3 / 1.5 A | 70 | 2 / 2 / 4 | 2 / 2 / 6 | 2 / 4 / 6 |
| Gap of 3 / 8 minutes | 25 | 2 / 2 | 2 / 2 | 4 / 2 |
| Gap of 4 / 6 / 12 minutes | 35 | 2 / 2 / 2 | 2 / 4 / 2 | 4 / 6 / 2 |
| Pause of 2 / 3 minutes after each 7 minutes | 40 | 2 / 2 | 2 / 6 | 6 / 6 |
| Pause of 5 minutes after each 7 minutes | 40 | 2 | 8 | 8 |
| One pause of 4 / 6 / 10 minutes | 45 | 2 / 2 / 2 | 4 / 6 / 4 | 4 / 6 / 4 |
| Decay in 2.5-minute steps | 30 | 2 | 10 | 2 |
| Creep every 3 minutes after a stop, 6 A / 1.8 A start | 40 | 1 / 3 | 7 / 9 | 3 / 5 |
| Step down, then τ 30 or 40 / a plateau | 70 | 2 / 2 | 2 / 4 | 4 / 4 |

Every remaining switch is towards v7's longer answer, and none of these occur in the owner's charges.

None of these checks changes the owner's replayed estimates: all 7,813 samples, at 100%, 99%, 95% and
80% limits, are identical to 4da6840, so the 1,110 changed samples and every figure below stand (the
step check, added after 5648b59, changes none of them either, with a `STEP_TAU` of 2 or 4). Only
the own-taper flag itself, which the branch never uses on a standard charger, clears on 204
standard-charger samples (59 of session 2 by the recency check, 145 of sessions 3 and 8-10 by the pace
check); all 1,197 high-speed own-taper samples keep it.

**Replay.** `expected-v7.csv` is `FastChargeTaperReplay` compiled against v7 (f529f43) on all 16
recordings; for sessions 13-15 it reproduces the app's live v7 predictions within 0.1 minutes on 1,107
of 1,167 samples, with the same source on 1,166 (live, the app re-evaluates every frame, the journal
keeps one sample per 10 s). Against it, v8 changes 1,110 samples, every one a high-speed sample where
the charge's own taper is established, now `FAST_REFERENCE`. All 5,457 standard-charger samples and
every high-speed sample before the own taper are identical to v7. Replayed with a lower charge limit
instead (50%, 80%, 90%, 95%, 96%, 97% and 98% checked), every sample's time to the limit, its source
and Charge done by minutes match v7 (compiled from f529f43) exactly; only the label changes, on 453
to 524 samples where v7 showed "From N past charges" for a personal time to 100% while the cutoff came
from the reference or current rate. At a 99% limit the source still matches everywhere, but on 18
samples of session 15 (at 80-82%) past charges' time to 99% was longer than the charge's own time to
100%. v7 showed it anyway; v8 shows the time to 100% instead, so the time to 99% and Charge done by
are 0.3-2.0 minutes shorter there, never longer than the time to 100%. `expected-v7-limits.csv` pins v7's time,
source, Charge done by minutes and past-charge count at 95% and 80% for every sample;
`FastChargeTaperTest` requires v8 to match it, allowing only that label change, and checks the 99%
cap. Complete high-speed charges, v7 → v8
(minutes; error is displayed minus actual time to the first displayed 100%; t0-t10 are the errors at
the start and after 5 and 10 minutes, identical in both; jump is the largest change between samples
beyond the elapsed time; switches are changes of source; the Charge done by margin is the smallest
displayed + 20 − actual):

| Session | MAE | t0 / t5 / t10 | Biggest jump | Switches | Done-by margin |
|---|---|---|---|---|---|
| 4 reference, 16% | 0.41 → 0.46 | +2.9 / +0.8 / +0.2 | 2.4 → 2.4 | 0 → 0 | +19.7 → +19.0 |
| 6, 73%\* | 1.33 → 1.15 | +81.9 / +1.3 / +1.3 | 80.6 → 80.6\* | 2 → 3 | +19.4 → +19.4 |
| 11, 73% | 0.75 → 0.68 | −0.9 / −1.0 / +0.7 | 4.5 → 1.0 | 1 → 0 | +16.7 → +19.0 |
| 12, 91% | 1.90 → 1.69 | +8.7 / +3.4 / +0.3 | 0.9 → 0.5 | 1 → 0 | +19.4 → +19.2 |
| 13, 58%, current-limited start | 6.03 → 4.22 | +13.7 / +10.4 / −6.7 | 25.0 → 25.0 | 1 → 0 | +4.9 → +4.9 |
| 14, 80% | 0.69 → 0.70 | +2.8 / +0.7 / +0.3 | 3.2 → 0.4 | 1 → 0 | +19.8 → +19.2 |
| 15, 27% | 1.38 → 1.29 | −2.0 / −1.9 / −1.6 | 4.4 → 2.1 | 3 → 2 | +17.6 → +17.6 |
| All | 1.88 → 1.51 | unchanged | | 9 → 5 | +4.9 → +4.9 |

\* Session 6's first sample and its 80.6-minute jump are the 2 A ramp sample and recording gap described
above. Its extra switch is the hand-over from personal history to its own taper.

- **Steadier, not earlier.** Source switches fall from 9 to 5 and the largest jumps in sessions 11, 14
  and 15 fall by 2-3.5 minutes. The first estimates (t0 to t10) and the smallest Charge done by margin
  are unchanged; v8 does nothing about a charge's start.
- **Session 13.** Its charger started at a limited current and rose later, which no estimate could see
  at the start (that is its +13.7, its 25-minute drop and its +4.9 margin, all unchanged). Its final
  taper was also slower than past charges, so v7's personal history read up to 10 minutes short
  through the taper (about 5 minutes short halfway). Its own taper reads within about ±1 minute
  there; the hand-over itself starts 7.3 minutes long (the safe side) and settles within about 5
  minutes.
- **Warm charges** are about as accurate as before (MAE within ±0.2 minutes).

**Costs.** In the last 15 minutes every charge now reads short, by 0.2-0.6 minutes on average per
charge; the worst sample is −1.0 minutes, against v7's −3.9. In the taper the estimate rests on the
fixed 0.68 A end current and the charger's voltage limit; every complete high-speed charge ended at
0.68 A, but they are one charger and one pack. The label reads High-speed reference instead of "From
N past charges" while the own taper applies, unless a lower cutoff's time comes from past charges.
In constructed timelines (synthetic profiles and past charges) the own taper can also take Charge done
by well past its buffer where v7 stayed inside it. The probes above found, among others:

- a decay that stops and creeps on: until the hand-back, the own taper can stay past the buffer for up
  to 2 minutes after the stop, about 65 minutes past on 3 readings when a τ-11 decay stopped at minute
  12 and crept every 30 s (v7 +0.0), and about 35 past for 2 minutes after a stop at minute 20 with
  creep every 60 s (v7 +38);
- a step down slower than the step check catches, one inside a 150-s gap in readings, or one still in
  the window when `FIT_FULL` is reached: 12-20 minutes past through the fall and up to 2 minutes after
  it, 31 for the early fall (v7 +26 to +32);
- a taper that keeps slowing: up to 4.3, 9.6 and 13.6 minutes past for τ growing 0.4, 0.5 and 0.6
  minutes per minute (reading 40%, 48% and 55% short), and 10 past for 2.3 minutes into a knee to τ 30;
- the decay before a later stop, step down or knee, which no estimate can see coming: up to 44 past
  (8.3-12.8 before the step downs).

None of these shapes shows in the owner's charges: while the own taper applied, their recent τ stayed
within 1.12 × `tau()` and no decay point fell faster than a 5.62-minute τ. This is stability polish,
not an accuracy fix.

## Limits

This is a small corpus: one scooter, four completed high-speed charges, and the constants come from
one of them (the reference charge). The other three are the only out-of-sample check. In a charge
that reaches the voltage limit early, τ differs between charges (11-14 minutes), so the first
estimate of the charge from 91% was 8.7 minutes (32%) long until a few minutes of decay were watched.
The bulk stage assumes the reference charge's volts per Ah at 10 A; 8 A charges fitted within about a
minute. Why the charger sometimes delivers 8 A instead of 10 A is unknown. Times are to the first
displayed 100%, not balancing or charger shutoff.

Rerun: `JAVA_HOME=... tests/run.sh` (includes the replay). To regenerate a baseline, compile
`tests/FastChargeTaperReplay.java` against that release's source tree (v6: b21d3f6, v7: f529f43, with
its generated text catalog) and run it on `sessions.csv`; `expected-v6.csv` covers sessions 0-12.
