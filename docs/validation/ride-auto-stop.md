# Ride auto-end

Unreleased, after Volt 1.3.1.

A ride left recording after the rider parks and walks away ends itself. The
owner's first example: the scooter shows zero speed, Bluetooth disconnects and
does not come back within ten seconds. That parked rule never matched a scooter
walked at 1–3 mph and switched off, so a second rule ends a ride whose scooter
went quiet (switched off or out of range) after a longer wait. Any reconnect
inside either wait keeps the same ride.

## Decision

`RideAutoStopPolicy` is a pure class driven by explicit events (`connected`,
`disconnected`, `reset`, `due`) and the caller's monotonic clock
(`SystemClock.elapsedRealtime`). `ScooterService` feeds it on every BLE change
and every recording tick, so the decision runs in the service with the screen
off. No Activity timer is involved. Charges keep `ChargeRecoveryPolicy` and never
pass through this policy.

Evidence comes only from the current authenticated transport: the raw `0x91` /
`0x92` motion push (speed, driving status, receive time), the last-frame time,
the transport serial and the connection generation. Cached or last-seen display
readings are never used. Missing speed (-1) never becomes zero.

The policy arms only when all of these hold at the moment the link is lost:

| Rule | Value | Why |
| --- | --- | --- |
| Motion seen earlier in this ride | at least one fresh push with speed > 0 or driving ≠ 0 | A scooter that just powered on, or a service that restarted mid-ride, cannot look parked |
| Stationary run | ≥ 2 distinct pushes, speed 0 and driving 0, spanning ≥ 3 s, with nothing moving, missing or out of order in between | One zero is a moment at a light; a few seconds of zeros is a stop |
| Push freshness | each push ≤ 6 s old when seen; newest zero ≤ 6 s before the last frame heard | The same window `BleController.stationary()` uses for parked-only controls; zeros that stopped updating while other packets arrived are stale |
| Loss timing | loss seen ≤ 15 s after the last frame | A recording declares a silent link lost after 12 s, checked every 2 s, so walking out of range lands inside it |
| Loss kind | recoverable transport loss, permissions present, Bluetooth on | Owner rejection, disabled Bluetooth or a background pause are not parking |
| Identity | ride's profile is selected, bound profile and transport serial match the profile's saved serial | Another scooter's zeros are not evidence |

Once armed, the ride ends after **10 s of one continuous disconnection**. Any
authenticated reconnect cancels it (even before the serial is read: the scooter
is near). Separate short gaps never add up: every loss needs a new stationary run
on a new connection. New motion, a rider's Stop, explicit Disconnect, a profile
change, turning the preference off, a storage error, and a new ride all cancel
it. The policy is not persisted, so after a process restart it starts unarmed
and needs fresh motion and a new stop.

A clock that moves backwards resets the policy. A decision fires once; if saving
fails the ride stays recording with the existing "Retry save" state and is not
retried automatically.

## Went quiet (switched off or out of range)

Owner answers (2026-11-05): 1 minute at walking pace or a stop, 5 minutes
otherwise (Q4 A); no phone-GPS "still moving" guard in v1 (Q30 B: he often
starts walking right after parking); the phone's Bluetooth switched off also
ends the ride after 5 minutes (Q31 B).

Every loss of this ride's scooter that the parked rule does not take starts a
countdown from the **last frame heard**, not from when the 12 s silent-link
watchdog notices, so "a minute" means about a minute after the scooter switched
off. In practice the save comes 60–62 s after power-off (1 s ticks).

| Last fresh motion push before the loss | Countdown |
| --- | --- |
| speed ≤ 6.4 km/h (`speedRaw ≤ 64`, about 4 mph), any driving status, ≤ 6 s before the last frame, loss seen ≤ 15 s after it | `QUIET_SLOW_MS` = 60 s |
| faster, missing, stale or unknown, or the loss seen later | `QUIET_LONG_MS` = 5 min |
| the phone's Bluetooth switched off (`Loss.PHONE_BLUETOOTH_OFF`) | 5 min; never shortens or restarts a running countdown |
| policy created while already disconnected (service restart mid-ride) | 5 min from creation |

`ScooterService` reports the loss with `RideAutoStopPolicy.loss(permissions,
bluetoothEnabled, recoverableConnectionLoss)`: no Bluetooth permission cancels,
Bluetooth off on the phone is its own loss, anything else follows the
transport's own retry decision (owner rejection and unsupported scooters
cancel). Only one continuous disconnection counts; repeated `disconnected`
calls and reconnect attempts never restart or clear the countdown, and any
authenticated reconnect cancels it. The preference off, a storage error, a
profile change and an explicit Disconnect reset it, as for the parked rule.

The tick checks `due()` (parked) first, then `dueQuiet()`. A quiet end saves
`RideStore.quietStopReason(code, phoneBluetoothOff)`; `code` is the native
status of the first connection event at or after the last frame when that
event is a native disconnect (`ConnectionEventLog.firstLossCode`), otherwise -1
(a silent link, Bluetooth off, or a reconnect attempt came first). After a few
real power-offs the code may show whether it alone separates a power-off from a
radio drop.

## No motion while connected

Owner (2026-11-06): "no speed sent say after like 10 minutes to account for
sitting at traffic lights. Maybe send a notification when that time-out
happens." A phone that stays connected keeps the scooter from switching itself
off, so the link never drops and neither rule above fires. In the owner's ride
the scooter stopped sending speed, trip, odometer and driving status about
12 s after the last speed above 0; only battery readings (voltage, current about
2 W, battery %, temperature) carried on at about 1 frame/s on a healthy link.

Rule 3 therefore counts **missing** motion, not only speed-0 pushes:

| Rule | Value | Why |
| --- | --- | --- |
| Motion | a fresh push (≤ 6 s old when seen) with speed above 0, or driving status 1; or the odometer or trip going up | The owner's walk-mode test pushed 0.2–3 km/h with driving status 0 throughout, so any speed above 0 counts (no noise floor, and not the 6.4 km/h walking line), and driving status 0 is not evidence of a stop. A held speed that is not pushed again never counts |
| Countdown | `NO_MOTION_MS` = 10 min from the last motion; before any motion, from the first observation of this ride's scooter on the connection (in practice the ride's start) | Long enough for traffic lights |
| Link | this ride's scooter authenticated and connected, with a frame in the last 15 s | A silent link is the loss watchdog's to report |
| Disconnect | stops the countdown and hands over to the parked and quiet rules unchanged | The link rules own a lost link |
| Reconnect | within `RECONNECT_CARRY_MS` = 1 min of the last frame heard: keeps counting from the motion before the drop, with the same odometer and trip baselines; later: counts afresh from the reconnect | The owner's ride of 2026-11-06 had a 4 s Bluetooth blip (native disconnect about 6 s after the last frame, authenticated again about 10 s after it) just after he parked, which restarted the 10 minutes; blips every few minutes indoors would have kept a parked ride open for good. Distance gained in the gap still counts at the first fresh reading, and a fresh speed push counts as before. A carried count never ends the ride before a fresh odometer or trip reading from after the drop (newer than the last frame plus its 3 s age) has been judged, or `CARRY_EVIDENCE_MS` = 30 s after the reconnect when none comes, so a rider who set off during the gap is not cut off (Codex, #303). After a longer gap the scooter may have moved |
| GPS | no phone GPS guard | As for the Bluetooth-off rule (Q30 B) |

The same switch (End rides automatically) turns it off: the service resets the
policy on every observation while it is off, and turning it back on counts from
then. A clock that moves backwards clears the countdown, which then restarts
from the next observation. Charges never reach the policy. Volt sends the
scooter nothing for this rule; the lock state is not read or changed.

The service tick checks it after the parked and quiet rules, while the same
policy is current. The odometer and trip come from the transport's fresh
readings (at most 3 s old), fed through `RideAutoStopPolicy.distance` right
after each connected observation.

The end is shown at the **last motion**, the way the quiet rule shows its last
reading: the journal's `STOP` row keeps the real time the rule fired, and
`RideStore` judges the last committed sample with motion from the journal
itself (a new speed or driving-status push with speed above 0 or status 1, or
the odometer or trip above its previous fresh value), so a reload, a backup and
an analysis export agree. The ten idle minutes never count in the shown
duration. Battery use in that tail (about 2 W, some 0.3 Wh) stays in the energy
totals, which use the raw values. A ride with no motion at all shows the stop
itself as its end.

## Ownership and the stop path

Before acting, the service checks that the decision's policy instance is still
the current one and its ride ID is still the active ride, and, for the link
rules, that the scooter is still disconnected. A late decision cannot close a
newer ride.

## Stop and save

All three rules end the ride through the same `stopRide(reason)` path as the Stop
button and the notification: storage error handling, phone GPS/sensor drain,
wake-lock release, work-policy and notification update, and the automatic ride
copy (`rideFullySaved` → `startRideAutoExport`) once the phone journal is
saved. The stop time is the real time it fired;
nothing is backdated and no samples are added. A separate, auto-cancelling
notification says **Ride saved automatically**; a Toast shows if Volt is open.
Ride details show a **Saved automatically** pill. After a quiet end the notice
reads "<ride> · Scooter turned off or out of range. Ended at 4:12 PM, its last
reading." (or "Phone Bluetooth stayed off. …", or "No movement for 10
minutes. Ended at 4:12 PM, its last movement."), and the pill's screen-reader
text names the same reason. The time is the shown end below, in the app's clock
format.

The journal's existing `STOP` row stores `AUTO_PARKED_DISCONNECT` in its details
column (empty for a rider's stop, as before). Readers before this change ignore
the column, so journals, ride backups and full app backups stay compatible both
ways. A quiet end stores `AUTO_QUIET_DISCONNECT;last=<mono>;code=<n>` (and
`;phone_bt=off`); older builds read it as a rider's stop. A no-motion end
stores the bare `automatic_no_motion`, which older builds also read as a
rider's stop. `RideStore.Summary`
exposes `stopReason` / `automaticStop` and, for automatic stops, the shown end
(`shownEndWallMs`, `shownElapsedMs`, `shownGapCount`): the last committed
sample that still held a fresh scooter value (at most 3 s old), so the silent
link, the gap and the wait never count. Raw `endWallMs` / `elapsedMs` stay for
integrity checks and exports. Analysis JSON adds `stopReason` (`rider`,
`automatic_parked_disconnect`, `automatic_quiet_disconnect`,
`automatic_no_motion`) and the shown end. `Summary.automaticStop` stays the
Bluetooth-loss stops; `Summary.noMotionStop` marks rule 3, and the pill shows
for either.
The rides list, the details Duration tile and the compare sheet read the shown
end (companion branch `claude/ride-autostop-shown-end`); the measurement
quality rows keep the raw values.

## Learned range

Learned range normally rejects any ride with a gap or a stale end. An
automatically ended ride always closes with the silent link, one disconnect gap
and the grace, so it would never train. For those rides only, the screening
judges the ride at its last complete reading (speed, battery and odometer fresh)
when the final disconnection is its only gap and that tail is at most 60 s. The
tail contains no distance or battery use. After a quiet end the tail may be up
to the 5 min wait plus 30 s (`QUIET_TAIL_MS`). Rides stopped by hand keep the
existing rules.

## Preference

**More → App preferences → End rides automatically** (`ride_auto_stop`,
default on), caption "When the scooter turns off, or stays out of reach for 5
min". One switch covers both rules. Included in full app backups as a boolean.

## Automated checks

`tests/RideAutoStopPolicyTest`: stationary-drop timeout, reconnect just before
the deadline, repeated drops, moving and non-recoverable losses, startup zeros,
stale zeros, missing speed, out-of-order and future pushes, clock rollback,
another scooter/profile, explicit reset, failed save without retry, late
decisions after a new ride, process restart.

`tests/RideAutoStopStoreTest`: stop reason round trip, legacy journal meaning,
real stop time, backup staging inspection, learned range on an automatically
ended ride versus the same tail stopped by hand, long tails and earlier gaps,
streaming parity, failed save keeps recording, the quiet token round trip,
shown end, learned range on a quiet tail.

`tests/l10n/RideAutoStopWireTest` drives the real policy and a real `RideStore`
through a scripted service loop (1 s ticks, the 12 s watchdog, the gap and
heartbeats, the tick order): a walking power-off ends exactly 60 s after the last
frame with the code, last reading and shown end saved; a reconnect at 40 s keeps
recording and a later loss gets its own 60 s; riding speed waits 5 min; phone
Bluetooth off ends after 5 min and never lengthens a running slow countdown; the
parked rule still wins; the setting off keeps recording. It checks the loss
mapping, the loss code, the notice and pill text, and, from source, that the tick
order holds and that an automatic end goes through `stopRide(reason)`, which
reaches the automatic ride copy.

`tests/l10n/RideNoMotionStopTest`: the owner's parked pattern (motion, then
battery frames only: due exactly 10 min after the last speed above 0, once),
speed-0 pushes for 9:59 then motion, traffic lights of 30 s to 3 min over 40
minutes, the owner's walk-mode pushes (0.2–3 km/h, driving status 0) for 15
minutes, odometer-only and trip-only motion (a trip reset is not motion), a
held speed, no motion at all, the switch off and back on, a disconnect
mid-countdown (the parked grace or the quiet countdown takes over; a reconnect
more than a minute later counts afresh), a short blip (the count carries over,
including a reconnect whose serial is read a moment later, and blips every 4
minutes no longer keep a parked ride open; distance gained or a push with speed
in the gap is motion; a count whose 10 minutes ran out in the gap waits for a
fresh distance reading or 30 s, and a reading that may predate the drop decides
nothing; another scooter, a reset or an unrecoverable loss drop
the held count), an unrecoverable loss, the clock going backwards, a silent
link, another scooter or an unread serial. Then the store (token, shown end at
the last motion and after a reload, walk-mode and odometer-only shown ends, JSON
name), the notice and pill text, and, from source, the tick order, the switch
check, the shared stop path and that the rule sends the scooter nothing.

## Emulator checks

`android-tests/RideAutoStopValidation` (`run-ride-auto-stop.py`, disposable
emulator only, Bluetooth off, synthetic journals) at 344×882 dp dark/100% and
light/140%, 725×604 dp light/140% and dark/100%, 99 checks each:

- Three journals ended with `AUTO_PARKED_DISCONNECT` and one with the same tail
  stopped by hand. Ride details show **Saved automatically** (fully visible,
  not ellipsized, spoken description) only on the automatic ones, in the
  narrow dialog and in the wide layout beside History.
- A real `.voltrides` export, delete, stage, plan and apply keeps every
  journal byte-identical and the stop reason; a reimport is idempotent.
- Learned range in the service: 3 rides used, the hand-stopped tail rejected,
  12 km over 24 battery points (50 km), so the tail adds nothing.
- Full app backup type check accepts a boolean `ride_auto_stop` and rejects a
  string or integer.
- Went quiet, on the service's own policy and tick (a live ride in the
  service's store, the policy fed its link events through reflection; the
  emulator has no scooter to reconnect):
  - an authenticated reconnect 40 s into a walking-pace quiet window cancels
    it: still recording at 66 s, and the rider's Stop ends it as before;
  - phone Bluetooth off at riding speed (its last frame placed 295 s back)
    ends at 5 min with `AUTO_QUIET_DISCONNECT`, the phone flag, no disconnect
    code and the "Phone Bluetooth stayed off" notice;
  - a walking-pace power-off is still recording at 58 s and ended by 64 s
    (56–64 s after the last frame), stores the token, the last reading and a
    shown end at the last reading before the raw end, and posts **Ride saved
    automatically** with "Scooter turned off or out of range. Ended at …, its
    last reading.";
  - the automatic copy ran from that stop (`stopRide` → `rideFullySaved` →
    `startRideAutoExport`): with a JSON copy on and no folder, the ride stays
    waiting with its problem stored;
  - the pill's spoken description names the quiet stop.
- **End rides automatically** is on by default, readable beside its switch, clear of
  the navigation bar; a tap turns it off and `app_settings.xml` stores it, and
  it survives leaving and reopening the section.
- The service's own notice method posts **Ride saved automatically** with the
  ride name in full (expanded text), auto-cancel and an open action (called through reflection from the
  test APK, so the app has no test hook); the runner captures it in the shade.

The existing ride, learned-range, charge capture/estimate, ride backup and
`PhoneRideValidation` (denied/granted, including its 11 s screen-off
recording) runners pass on the same build.

## Still to validate

The emulator has no scooter, so these remain covered only by
`RideAutoStopPolicyTest` and code review:

- A parked loss on a live transport ending the ride after about 10 s with the
  screen off, phone journals finishing and a following ride starting.
- Process interruption while disconnected: the recovered ride keeps recording
  and is not ended automatically.
- Reconnect inside the grace keeps the same ride.
- A real scooter switched off at walking pace: the disconnect code Android
  reports, and whether the 12 s silent-link watchdog or a native disconnect
  comes first.
- How often a stopped or walking UT5 MAX pushes speed. The owner's ride
  718c0766 has 140 s of voltage with no speed (about 2.3 W) before his own
  Stop, with at most 6.6 s uncovered, so the link never dropped. Both rules
  need a link loss, so this PR would not have ended that ride.

Physical, with the owner present: how often the UT5 MAX sends `0x91`/`0x92`
while parked (parked-only controls already depend on it within 6 s), and the
real delay between walking away and Volt seeing the loss.
