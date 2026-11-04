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
(`FastChargeTaper.ownTaper`, the first high-speed step in `ChargingEstimate.forScooter`). It now
replaces the SOC-indexed reference and personal curves too, not only the current rate. Those place the
charge by the battery percentage, which steps unevenly in the taper; the measured decay does not. The
result and its label are the existing high-speed estimate (High-speed reference, recorded as
`FAST_REFERENCE`, 0 learned sessions) instead of "From N past charges". A lower cutoff keeps v7's
time, source and Charge done by minutes, never longer than the time to 100%: past charges' time to
the cutoff where they have one (labelled "From N past charges", as in v7), otherwise the reference or
current rate. It applies only to the high-speed
charger on the UT5 MAX (product 2538) with a usable live estimate (at least 0.05 A, voltage and power
present), a valid rated capacity and battery below 100%. Nothing was fitted: the switch-over point is
the existing `FIT_FULL`, and the model, τ limits and 0.68 A end current are v7's.

**Replay.** `expected-v7.csv` is `FastChargeTaperReplay` compiled against v7 (f529f43) on all 16
recordings; for sessions 13-15 it reproduces the app's live v7 predictions within 0.1 minutes on 1,107
of 1,167 samples, with the same source on 1,166 (live, the app re-evaluates every frame, the journal
keeps one sample per 10 s). Against it, v8 changes 1,110 samples, every one a high-speed sample where
the charge's own taper is established, now `FAST_REFERENCE`. All 5,457 standard-charger samples and
every high-speed sample before the own taper are identical to v7. Replayed with a 95% or 80% charge
limit instead, every sample's time to the limit, its source and Charge done by minutes match v7
(compiled from f529f43) exactly; only the label changes, on 494 (95%) and 453 (80%) samples where v7
showed "From N past charges" for a personal time to 100% while the cutoff came from the reference or
current rate. `FastChargeTaperTest` replays the 95% limit and requires the 453 own-taper samples with
a personal time to 95% to keep it. Complete high-speed charges, v7 → v8
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
N past charges" while the own taper applies, unless a lower cutoff's time comes from past charges. This is stability polish, not an accuracy fix.

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
