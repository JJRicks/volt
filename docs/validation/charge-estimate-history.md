# Recorded charging predictions

Charge samples save the prediction made at capture time by the same service method
used by the dashboard. The foreground recording service evaluates it even when no
widget or Activity is visible. Opening saved history never re-runs the estimator.
This change does not alter the prediction algorithm or its training inputs.

## Stored data and compatibility

New journals use `charge-v2`; `charge-v1` journals remain readable and retain their
original measurements. Each v2 sample appends estimator version, remaining minutes
to full, remaining minutes to the configured limit, target percentage, full/limit
source and the number of learned sessions. Missing predictions have blank numeric
fields and still retain the estimator version. The sources are `CURRENT_RATE`,
`STOCK_REFERENCE`, `FAST_REFERENCE`, and `PERSONAL`; the two targets can have different sources.

The JSON export retains `navee-charge-recordings-v1` with additive fields:

- `samples[].estimate`: null for legacy samples, otherwise an object containing
  `estimator_version`, `remaining_minutes_to_full`, `remaining_minutes_to_limit`,
  `target_percent`, `full_source`, `limit_source`, and `learned_sessions`.
- `observed_complete_elapsed_ms`: the monotonic timestamp of a supported observed
  arrival at displayed 100%, or null. This uses the same time base as the existing
  `elapsed_ms` fields; it is not a duration since the session began.

The estimator version is independent of the APK version. Change
`ChargingEstimate.ESTIMATOR_VERSION` when the estimator/reference behavior changes.
It is at most 64 characters (every sample stores it) and is only ever compared whole;
to make room, a later version may shorten a part's name, never reuse one for something
else. Versions since v11 that main carried, newest first (each pull request that
changed it, and the release that shipped it; the others never shipped):

- `ut5-fast-warming-v12.stitch-v1.temps-v1.ucap-v1.match-v1.iavg-v1` (2.0.5):
  the slowed estimate's cold model warms the battery at this charge's own
  measured rate once it has 10 minutes of temperatures, never faster than
  before ([details](fast-charge-slowdown.md#the-charges-own-warming-rate-ut5-fast-warming-v12)).
  The base estimator is v11 otherwise; the layers are unchanged. Charges
  without a cold-slowed stretch give the same numbers as before.
- `ut5-fast-cvpoint-v11.stitch-v1.temps-v1.ucap-v1.match-v1.iavg-v1` (#586,
  2.0.2): every estimate uses the last minute's averaged charging current
  (`iavg-v1`, [details](stock-pack-estimates.md#live-current-202)). `stitch-v1`
  and `match-v1` are `stitched-v1` and `chg-match-v1` shortened to make room;
  nothing else changed.
- `ut5-fast-cvpoint-v11.stitched-v1.temps-v1.ucap-v1.chg-match-v1` (#559,
  2.0.1): adds the learned charger match (`chg-match-v1`); `normal-temps-v1`
  and `usable-cap-v1` shortened to `temps-v1` and `ucap-v1`.
- `ut5-fast-cvpoint-v11.stitched-v1.normal-temps-v1.usable-cap-v1` (#567):
  adds learned usable capacity (`usable-cap-v1`); `stitched-estimate-v1`
  shortened to `stitched-v1`.
- `ut5-fast-cvpoint-v11.stitched-estimate-v1.normal-temps-v1` (#566): adds
  learning only from normal battery temperatures (`normal-temps-v1`).
- `ut5-fast-cvpoint-v11.stitched-estimate-v1` (#562): adds provisional and
  joined estimates (`stitched-estimate-v1`).
- `ut5-fast-cvpoint-v11` (#561): the base estimator alone. 2.0.0 shipped
  `ut5-stock-finish-v9`, the version before it.

Every part name ever used, with its shorter forms:

| Part (every name it has had) | Meaning |
|---|---|
| `ut5-fast-cvpoint-v11` | The base estimator: [where the voltage limit comes](fast-charge-taper.md#v11-where-the-voltage-limit-comes-ut5-fast-cvpoint-v11) |
| `ut5-fast-warming-v12` | The base estimator, v11 with [the charge's own warming rate](fast-charge-slowdown.md#the-charges-own-warming-rate-ut5-fast-warming-v12) |
| `stitched-estimate-v1`, `stitched-v1`, `stitch-v1` | [Provisional and joined estimates](charge-capture.md#provisional-and-joined-estimates-stitched-estimate-v1) |
| `normal-temps-v1`, `temps-v1` | [Learning only from normal battery temperatures](charge-capture.md#learning-only-from-normal-battery-temperatures-normal-temps-v1) |
| `usable-cap-v1`, `ucap-v1` | [Learned usable capacity](charge-capture.md#learned-usable-capacity-usable-capacity-v1) |
| `chg-match-v1`, `match-v1` | [The learned charger match](charge-capture.md#automatic-picks-a-learned-charger-chg-match-v1) |
| `iavg-v1` | [The averaged charging current](stock-pack-estimates.md#live-current-202) |

Personal curves can change between recordings even when this identifier stays the
same; saved values, sources, and learned-session count preserve what was predicted.
No predictions feed back into learned charging curves: learning still uses measured
SOC, current, voltage and timing only.

Bounds are 18 hours and 6,500 samples per journal, and (2.0.1) up to 1,000
journals or about 150 MB compressed (charge-capture.md). The byte cap is 3.5 MB
per plain journal to accommodate the longest allowed prediction rows; typical
files are smaller. History parses closed journals on the analysis worker without
holding the capture lock during disk reads: the list reads its newest 20 in full
and the rest as summaries from their compressed files' headers, and opening an
older one reads it then. A saved estimate claims at most 20 learned sessions. Results are immutable and profile
scoped. App backups preserve the new files without a separate migration.

## Graph interpretation

Scooter → Battery & charging → Charge history & recordings → Charge recordings
(formerly in More → Developer options) lists saved charges. Each recording
uses the same multi-series chart renderer as ride recordings: colored category
toggles, fullscreen, pinch zoom, timeline panning, Fit and touch/drag inspection.
Drawing uses pixel buckets that retain spikes; inspection keeps every raw sample.
Selections are remembered separately from ride-chart selections. Opening/closing
fullscreen preserves the chosen categories, zoom and inspected sample.

Power, battery percentage, estimated finish and estimated countdown start enabled.
When the finish was observed, its horizontal reference line also starts enabled.
Estimated and observed finish times share an axis range, as do estimated and actual
countdowns. Other quantities use independent colored scales, as in ride graphs.
The axis strip can be scrolled horizontally when the scales do not all fit.

Optional categories include voltage, signed current, temperature, actual countdown,
and saved charging-limit percentage and predictions when a lower limit was used.
Clock labels respect the app's time format, showing seconds for short ranges and
dates across midnight; temperature respects °C/°F. Short fullscreen windows
scroll vertically rather than flattening the plot. Accessible previous/next
actions step through real recorded samples. Raw sample
inspection shows recorded estimator version and source beside saved predictions.
An accurate countdown falls one minute per minute; a stable finish prediction is
horizontal and converges toward the observed finish.

The reference is the **observed arrival at displayed 100%**, not a claim that
balancing or all charging current has ended. It requires `COMPLETE`, a terminal
100% sample, and a valid preceding below-100% sample within 25 seconds without a
gap. A stopped/interrupted recording or a reconnect already at 100% has no finish
reference. A lower configured cutoff is never compared against a full-charge
finish. It remains available through separate saved-limit categories.

Elapsed time is monotonic. Dates and finish clocks use the session's starting
wall-clock anchor plus elapsed time, so later phone-clock corrections do not look
like estimator drift. Original wall timestamps are still exported unchanged.
Missing predictions and connection gaps break prediction lines; missing estimates
alone do not erase a valid observed reference. Historical predictions are never
invented for legacy recordings.

## Validation

Pure tests cover v1 compatibility, exact v2 persistence/export, unavailable
estimates, mixed sources, malformed optional metadata, clock jumps, target changes,
connection gaps, conservative completion, profile isolation, immutable results,
6,500-sample retention, and independence of learning from saved predictions.

The isolated native runner `ChargeEstimateValidation` exercises the actual service
snapshot method without an Activity-dependent calculation, offline list/detail,
category toggles, raw inspection, fullscreen zoom/state retention, saved limits, legacy records, and
profile-switch dismissal. Run it with `android-tests/run-charge-capture.py --suite
estimates` on a disposable emulator. The default `capture` suite retains existing
screen-off/recovery/notification checks. Neither runner admits owner phones.

These tests validate the recording and comparison implementation with synthetic
samples. They do not establish physical charging accuracy.

Original v1.2.0 validation, October 15, 2026:

- All 30 core suites passed, including 6,591 new prediction-history checks
  (70,671 total assertions/checks).
- Prediction UI: 85 assertions each at 344×882 dp dark/100% system font,
  725×604 dp light/140%, and 600×344 dp dark/140%. Includes finite chart geometry
  for both `Double.MIN_VALUE` and `Double.MAX_VALUE`, safe system-bar insets,
  and legacy missing-prediction labels distinct from connection gaps.
- Existing capture UI: 174 assertions each at 344×882 dp dark/100% and
  725×604 dp light/140%.
- Focused dashboard validation: both widths passed, including real tile actions
  against the built-in simulation for Off/On, pending and edit-mode guards, plus
  disconnected appearance. The runner reports zero real scooter mutations.
- App and native runner built and passed APK signature/alignment verification.
- Independent reviews completed, with numeric-range
  follow-up fixes verified before handoff.

Unified-chart follow-up, October 15, 2026:

- All core suites pass: 77,300 checks, including 6,605 adapter checks for
  all 6,500 recorded samples, gap separation, observed comparisons and extreme
  finite bounds; backup preference type rejection is covered.
- Combined charge UI: 226 assertions each at 344×882 dp dark/100% system font,
  725×604 dp light/140%, and 600×344 dp dark/140%. Covers the actual plot area,
  category selection, fullscreen state retention, pinch zoom, raw inspection,
  accessible sample stepping, midnight labels, 12/24-hour clocks and Fahrenheit.
- Independent reviews completed; verified feedback
  about accessibility, backup validation and clock labels was addressed.
- Existing ride analysis: 181 assertions each at 344×882 dp dark/100% and
  725×604 dp light/140%, including full-detail loading, raw inspection, axis
  scrolling and fullscreen interactions.
- Emulator fixtures are synthetic. No owner phone or scooter was involved.
