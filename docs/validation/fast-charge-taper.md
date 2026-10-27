# High-speed charger voltage/current estimates

Estimator `ut5-fast-taper-v7`, following [standard-charger pack-model estimates](stock-pack-estimates.md)
and [buffered scheduling](charge-done-by-buffer.md). It changes UT5 MAX **high-speed** estimates only where
the bundled high-speed reference and personal curves could not place a charge and Volt fell back to the
current rate. Standard-charger, unknown-charger and other-model estimates are unchanged.

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

[`fast-charge-taper-v7`](data/fast-charge-taper-v7/) holds the owner's 13 UT5 MAX recordings (8
standard, 5 high-speed) reduced to numeric replay fields by `strip_export.py`: no IDs, dates or wall
clocks. `tests/FastChargeTaperReplay.java` replays each one through the production store and
estimator, learning only from the recordings before it. `expected-v6.csv` is the same replay of the
previous release (b21d3f6); it reproduces the predictions the app recorded live for all three charges
captured under `ut5-stock-pack-v6` (high-speed exactly, standard within 0.02 minutes).

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

All 5,457 standard-charger samples and the reference high-speed charge are identical to v6
(`FastChargeTaperTest` checks every one against `expected-v6.csv`).

## Limits

This is a small corpus: one scooter, four completed high-speed charges, and the constants come from
one of them (the reference charge). The other three are the only out-of-sample check. In a charge
that reaches the voltage limit early, τ differs between charges (11-14 minutes), so the first
estimate of the charge from 91% was 8.7 minutes (32%) long until a few minutes of decay were watched.
The bulk stage assumes the reference charge's volts per Ah at 10 A; 8 A charges fitted within about a
minute. Why the charger sometimes delivers 8 A instead of 10 A is unknown. Times are to the first
displayed 100%, not balancing or charger shutoff.

Rerun: `JAVA_HOME=... tests/run.sh` (includes the replay). To regenerate the baseline, compile
`tests/FastChargeTaperReplay.java` against the v6 source tree and run it on `sessions.csv`.
