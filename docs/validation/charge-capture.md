# Charger-specific recording and estimates

> Current behavior: Volt 1.0.1 adds bounded reconnect/resume and persistent diagnostics.
> See [charge recovery](charge-recovery.md). The dated sections below describe the
> original implementation and its validation; their immediate-disconnect behavior
> is historical.

Validated October 14, 2026. This change is stacked on
`codex/preferences-and-charge-labels` (`abc0dba4d8bc77c26a67ea5e146c8f6be7f9fa78`).

## User-visible behavior

- **Scooter → Battery & charging → Charge history & recordings** (formerly
  Developer options) has an explicit **Record this charge** action, whose
  confirmation asks Stock or High-speed. Developer options' charger selector is
  gone: the saved choice is made at **Battery & charging → Estimates & charger →
  Charger**, and the page's Charger row shows it. The selection describes the charger the user attached; no hardware
  identification is inferred. It is saved per scooter.
- Starting requires a genuine authenticated connection to the selected scooter,
  fresh charging/SOC/paired electrical data, developer options, and enabled
  notifications. Simulation and concurrent ride capture are refused. The start
  confirmation describes the data, background operation, limits and local storage.
- An explicit capture keeps Bluetooth polling and a foreground Stop notification
  alive when the app is hidden. Normal background-connection preferences still
  apply after capture stops. Charge capture alone never reconnects after a loss.
- Capture stops when the user stops it, charging ends, the scooter disconnects,
  the profile changes, readings stop for 30 seconds, the service ends, or the
  storage/time cap is reached. It never silently resumes after a process restart.
- At 100%, the final fresh paired sample is saved before completion. Charging may
  already have changed to off and current may have fallen to zero. At 99%, a
  charging-off packet gets one bounded five-second poll window for the final SOC
  packet; missing data remains missing. Completion means the reported SOC reached
  100%, not that balancing or the charger's own final stage was measured.
- Dashboard charging estimates consult the service's selected scooter/charger
  curve. The existing fresh paired-electrical and rated-capacity gates remain in
  force. Learned estimates also require compatible live power at the current SOC.
  A different, absent or inconsistent power reference preserves the current-rate
  estimate, with an explanation. This checks plausibility; it does not identify
  which charger is connected or change the manual selection.

## Data and qualification

The app-private `files/charge-recordings` directory contains UUID-named journals.
New journals start with `charge-v2,profile-id,charger,start-wall-ms,start-monotonic-ms`;
the original `charge-v1` journals remain readable. Sample rows retain
`s,wall-ms,monotonic-ms,SOC,volts,signed-amps,signed-watts,temp-C,gap`, followed
in v2 by the captured estimates and their version/source metadata, and end with
a stop reason. See [estimate history](charge-estimate-history.md). Missing
temperature is blank. No owner token, MAC,
location, or account data is copied into these files. Nothing is uploaded.

Samples are normally at most once every ten seconds. The final 100% sample may
be earlier. State, SOC, voltage and current must be finite and at most five
seconds old. Voltage/current must come from the same positive timestamp and
source opcode. Out-of-range values, electrical spikes and invalid samples are
rejected. Temperature is optional. SOC rollbacks, skipped percentages, gaps over
25 seconds and wall-clock discontinuities break the affected curve interval.

Retention (2.0.1) is at most 1,000 saved journals and approximately 150 MB on
disk, measured on the compressed files, with an 18-hour, 6,500-sample and
approximately 3.5 MB cap per plain journal. The byte cap accommodates the longest
versioned prediction rows; typical files are smaller. A saved journal is
gzip-compressed in place by the analysis worker, its summary (header, stop,
qualification, capacity evidence, list row) in the gzip header's comment field.
Non-qualifying captures (unreadable, failed or short, or a guessed label) are
removed first. If all remaining files qualify, pruning removes the oldest file
from the largest profile/charger bucket, balancing coverage instead of allowing
frequent stock captures to evict a minority high-speed curve. Two sessions per
bucket are protected while the limits can accommodate them. Room is made after a
recording is saved, automatic or manual alike, never at a start (the running
journal is one over), and never at the cost of the journal just saved or
recovered. The count is enforced at that stop and at startup; the byte budget by
the worker once the journal is compressed. The caps still take precedence when
too many buckets compete. Each file's summary is cached in memory against its
length and modification time; changed files invalidate that cache. The learner
reads the newest 20 qualifying journals per profile/charger, and capacity
evidence from the newest 20 completed charges per profile. Before 2.0.1:
20 journals, about 70 MB plain, and a manual start reserved one of the 20 slots. Abruptly interrupted journals
are finalized as interrupted on next launch and excluded from learning. A
per-scooter clear action removes that scooter's closed journals and derived curves.
Curve parsing runs on a dedicated worker; cached estimates are read on the UI
thread without disk parsing.

A qualifying session needs at least 61 samples, ten minutes, ten complete
one-percentage SOC intervals, and 90% contiguous time coverage. The initial
partial interval is excluded. The robust estimate needs, for each estimated
remaining percentage interval, at least two independently recorded qualifying
sessions for this exact profile and selected charger. Durations are averaged
after rejecting strong outliers; two strongly disagreeing sessions cannot
establish a curve, provisionally or not. Repeated intervals
within one recording do not count as independent evidence. A missing 99–100%
interval prevents a learned full-charge ETA; a covered lower cutoff may still
use its learned estimate. The remaining full-charge time then retains its stated
current-rate fallback, without inventing taper coverage.

Each completed one-percent interval also retains the median battery-side power
from at least three paired samples inside that interval. The next interval's
first sample, including a zero-current final cutoff packet, does not contaminate
the previous interval's power. At the current SOC, at least two duration-qualified
sessions must also provide consistent power (within 25% of their median). Live
power must then be within 35% of that reference. Taper is compared with recorded
power at the same SOC, rather than the charger's bulk power. Missing or conflicting
power fails closed to the current-rate estimate. These are conservative heuristic
tolerances pending hardware validation, not charger identification or an accuracy
guarantee; temperature and other changes can also cause a mismatch.

### Provisional and joined estimates (stitched-estimate-v1)

On a model and charger without a bundled reference (every pair except the UT5 MAX
on either charger), a reading that the evidence above leaves without a learned
time can use provisional evidence instead:

- **Single-recording percents.** A one-percent interval that only one qualifying
  session covers counts provisionally, with that session's interval power as the
  live-power check (the same 35% band). Once a second session agrees, the
  interval is robust again and the averaged path above applies unchanged.
- **Joined stretches.** Per-percent intervals from different sessions join when
  every interval from the current level to the target is covered (robust or
  single). Any uncovered or disagreeing interval in between leaves no estimate.
- **Joined finishes.** Per-percent times from the current level up to where a
  completed finish recording starts are joined to that recording's own time from
  its start to 100%, or to the limit (its first reading at the limit). With
  several finish recordings each joined time counts; they are combined like
  matched finishes, and two that disagree by more than 2.5 times give none. A
  finish recording that covers the current level but cannot place it still
  blocks provisional evidence there.
- **Reporting.** The status names what was joined and says the estimate is
  provisional; the short label stays "From N past charges", N counting the
  recordings used. A provisional time to a lower limit is never longer than a
  learned time to 100%.
- **Planning.** Charge done by plans with the longer of a provisional time and
  the estimate without past charges (the current rate on these models); the
  countdown shown is the provisional time. A provisional finish is never held
  through a high-speed power dropout.

The UT5 MAX keeps its bundled references, voltage models and history weighting
unchanged and gets no provisional evidence: a replay on private recordings
showed no gain there and one slightly shorter time to a level. `Curve.summary()`
exposes each percent's coverage (none, provisional, robust or disagreeing),
minutes, saved power and finish coverage for a chart of the saved curve.

### Learning only from normal battery temperatures (normal-temps-v1)

On every model, a high-speed (fast) charger's one-percent interval teaches no
curve (neither its minutes nor its power) when any of its readings, including
the reading that ends it, has the battery outside 20–38 °C (68–100 °F)
(`ChargeSessionStore.learnsAtTemperature`). The battery limits its charging
current outside that range, so such a percent says more about the weather than
about the charger. A completed fast charge's finish likewise starts after its
last such reading; one hot or cold at its end teaches no finish.

- **The recording stays whole:** every sample is kept, listed, charted and
  exported as recorded, and the recording still qualifies (and keeps its place
  in the folder) exactly as before. Only what it teaches changes.
- **The stock charger always learns:** its current stays below the battery's
  thermal limits (on the UT5 MAX, about 2.25 A against at least 3 A hot and
  5.5 A cold).
- **No temperature reported** excludes nothing.
- A replay on private UT5 MAX recordings changed one partial fast charge whose
  history held cold percents: its error fell from 1.72 to 0.60 minutes on
  average, with Charge done by's margin still about 20 minutes. Nothing else
  moved by more than 0.02 minutes.

### Learned usable capacity (usable-capacity-v1)

The current-rate estimate assumes the rated capacity fills the battery gauge's
0–100%. A battery controller can map a smaller share of the pack there; then
every current-rate time reads long by the same factor (about 14% on one
scooter whose gauge holds 87.5% of its rating). On every model, Volt learns the
charge the scooter's own gauge takes per percent (`UsableCapacity`):

- **Per recording:** its whole-percent steps below 80%, each entered by a step up
  from the level below and left by a step up to the next, continuous (no gap,
  clock jump, rollback or skipped percent), each step's charge the integral of
  |current| over it. The partly observed first level never counts. A recording
  counts with at least 10 steps; its value is its charge over its steps. A
  high-speed charger's steps with the battery outside 20–38 °C don't count, as
  for the learned curve.
- **Across recordings** (the same recordings that teach curves, any charger): a
  recording agrees when within 5% of the recordings' median (two recordings:
  within 5% of each other). Two or more that agree give a robust value (their
  mean); one recording gives a provisional value; two or more without two that
  agree give none. 5%: one step's charge varies about 14% around its level's,
  so a 10-step recording's own value is good to about 4.5%.
- **Use:** below 80%, in place of the rated capacity, clamped to 0.8–1.0 times
  rated, and only when below 0.97 times rated (a gauge that holds its rating
  keeps the rated time exactly). Above 80% the rated capacity stays, the taper's
  cushion. It is a display-only last pass over the finished estimate
  (`ChargingEstimate.withUsableCapacity`): every time shown that is still the
  rated-capacity current-rate time (time to 100%, to a limit or a charge alert's
  level, and any part that past charges, a reference or a model don't set) is
  shown with the learned capacity instead. A learned curve or bundled reference
  that covers a target keeps its time. The label stays the current rate's; the
  status names the capacity used.
- **Charge done by** plans with exactly the minutes the estimate has without
  this pass, on every path (held finishes, references, provisional fill-ins and
  limits below 100% included), so it never plans with a shorter time than before.

Replays: on a private set of stock-charger recordings from a model whose gauge
holds about 87.5% of its rating, leave-one-out, the time to the level each
charge reached went from 11.4 to 2.7 minutes average error (16.8 to 2.3 at the
first reading), with about 5% of rows shown more than 5 minutes short and
Charge done by unchanged. On private UT5 MAX recordings the gauge learns 99.7–
100.8% of the rating, inside the dead band: no estimate changes.

## Validation

- `bash tests/run.sh`: all 16 suites passed, 46,296 assertions/checks including 77
  charge-journal/curve checks. Existing charging estimates retain their 135 checks.
- The new checks cover freshness/source pairing, invalid values, cadence,
  retention, process interruption, profile/charger separation, gaps, clock jumps,
  rollback, outlier curves, insufficient data and missing taper. Two full
  synthetic 40–100% sessions with different durations yield an averaged 75-minute
  50–100% estimate. Two completed sessions with charging-off/zero-current final
  packets retain a learned one-minute 99–100% interval.
- Review regression: two 52 V / 2.75 A, three-minute-per-percent stock sessions
  cannot override the approximately 63-minute current-rate estimate at 60% with
  twice the live current. Matching stock power retains its learned 120-minute
  estimate. Recorded taper uses the same-SOC reference successfully; missing,
  zero, conflicting and out-of-tolerance power preserve the fallback. Tests also
  cover zero-current completion and the inclusive tolerance boundaries.
- Retention regressions preserve minority scooter/charger curves through 25
  qualifying stock captures and 30 short/failed captures while enforcing the
  same global file/byte bounds.
- App and separate instrumentation APK built and signature-verified.
- `ChargeCaptureValidation` passed 49 assertions per case on disposable emulator
  5588 initially and 5586 after the review fixes: 344×882 dp dark/default font and
  725×604 dp light/140% font. Screenshots
  were inspected for readable controls and wrapping.
- Native checks cover disconnected and simulation start refusal without creating
  files; per-profile charger selection; background-work admission only for active
  explicit capture; ride/simulation/charger-change exclusions; the notification's
  real Stop `PendingIntent`; disconnect/profile/service-end cleanup; no restart
  resumption; no GATT/scanner/phone-sensor use; and per-profile clearing.

The native lifecycle test intentionally seeds a low-level synthetic journal below
the authenticated-start guard. For the Stop notification assertion it temporarily
postpones the unrelated disconnected-frame handler, so only the notification's
manual-stop path can satisfy the assertion. It does not pretend a Bluetooth
connection or qualify synthetic data as real capture. The runner refuses physical
devices and pre-existing user data; Bluetooth stays disabled on the emulator.

Reproduction:

```sh
bash tests/run.sh
bash build.sh
bash android-tests/build.sh
python3 android-tests/run-charge-capture.py \
  --adb "$ANDROID_HOME/platform-tools/adb" --serial emulator-5588 \
  --app /path/to/test-app.apk --runner /path/to/parked-validation.apk \
  --out /path/to/disposable-evidence
```

Local evidence is under `work/charge-capture-validation/final-native` and
`work/charge-capture-validation/review-native`, with build and core logs alongside.
APKs/screenshots are not committed.

## Integration and remaining hardware validation

`ScooterService.chargingEstimate(Map<String, TelemetryHistory.Reading>)` returns
`ChargingEstimate.Result`. The result adds `learnedToLimit`, `learnedToFull`, and
`learnedSessions`; existing fields remain. The per-profile selection key is
`charge_charger_<profile-id>`, containing `STOCK` or `HIGH_SPEED`; missing means
unknown to the estimator. `chargeCharger()` / `setChargeCharger(...)` expose it.

No owner's phone or scooter was used. Successful authenticated capture, actual
stock/high-speed curves, radio behavior during a full screen-off charge, and
real-world ETA accuracy still require hardware testing. The conservative fallback
remains until the required data exists. This is an experimental observed-SOC
estimate, not a battery-health or charger-identification measurement.

The optional reconnect grace from review is deliberately deferred: disconnect
still ends an explicit capture immediately, and charge capture does not enable
automatic reconnection. This preserves the bounded, already-tested lifecycle
until full screen-off hardware captures can establish appropriate reconnect
behavior. Existing gap handling remains for data interruptions within a connection.


## Pixel feedback follow-up: background capture and JSON export

The recorder now acquires its partial CPU wake lock synchronously when recording
work is admitted, renews its bounded ten-minute lease every five minutes, and
releases it when capture/finalization ends. This closes the initial timer gap
and the former twelve-hour timeout gap. Ordinary idle Bluetooth still expires
after ten seconds when background connection is off. Ride start reissues the
service start so Android records its active-ride START_STICKY policy rather than
the idle start's START_NOT_STICKY result. Charge process recovery remains explicit:
an interrupted charge is preserved and closed; it is not silently restarted.

The connected-device/location foreground-service types remain scoped to the work
being done. Removing the task does not request service shutdown. Recording
notifications request immediate display, use the ongoing service category and
retain their immutable Stop action. Android 14+ can allow individual dismissal
while unlocked; this is platform behavior, not a reason to spoof a different
notification category or repeatedly reopen a dismissed notification. App
preferences now exposes a scrollable Background recording explanation and
shortcuts to Android notification/app battery settings. Nothing changes those
system settings without user action. Force stop, permission revocation, OEM
battery controls and Bluetooth loss can still interrupt work.

Scooter → Battery & charging → Charge history & recordings → Export charge
recordings (formerly in Developer options) streams all saved valid journals
for the selected scooter into a versioned JSON file using Android's save picker.
It includes manually selected charger type, start clocks, stop reason, learning
qualification and original signed current/power, voltage, SOC, nullable
Celsius temperature and gap markers. Short/interrupted recordings are exportable
without being promoted into learned curves. Active capture must stop first;
export work runs off the UI thread and new capture waits until its snapshot is
finished. The profile is captured at request time. Other scooters, owner setup,
PINs, MACs, locations and account information are excluded. Private staging files
use unique names and old staging files expire on the next export after 24 hours.
Existing charger buckets and same-SOC live-power gates are unchanged.

Validation: all 16 core suites pass (including 87 ChargeSessionStore checks).
Native charge lifecycle/export checks pass at compact/default and unfolded/140%
text. Screen-off cases cross the idle timeout, verify the held/renewed wake lock,
ongoing notification, Stop action, disconnect/profile-change/process cleanup,
and valid profile-scoped JSON. PhoneRideValidation passes with GPS permission
both denied and granted, with the display off beyond the idle deadline, sensor
cleanup, journal gaps and exports. These are guarded synthetic fixtures on a
disposable emulator with Bluetooth disabled; they do not claim actual overnight
Bluetooth reception, force-stop recovery, or full stock/high-speed charging.
Evidence is in local `work/round2-charge-evidence` (not shipped in the APK).

Platform reference: https://developer.android.com/about/versions/14/behavior-changes-all#non-dismissable-notifications
