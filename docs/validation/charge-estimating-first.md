# "Estimating time left" first: the plug-in hold

The owner, Nov 23 2026: "I'd rather show an 'estimating' first instead of an inaccurate estimate."

It changes the UT5 MAX's shown time and Charge done by's minutes, for at most three minutes after plugging in. The
estimator and its numbers are unchanged, and so are charge recordings.

## What was wrong

Right after plugging in, the first time a UT5 MAX charge showed was often far off:

- **The fast charger's soft start.** It starts at about 1.3 A, for up to about a minute (55 s at most in the recorded charges), before its bulk current; full current came within about 90 s of plug-in.
  - The only time those readings give is the plain current rate (battery left ÷ current): 80 to 300 minutes for a
    charge that takes 35 to 60.
  - A 2 A ramp reading on one public replay charge (session 6) read 82 minutes long.
- **Before charger detection settles.** Under Auto, the current rate stands in for a few seconds. On a fast charge
  that read 8 to 25 minutes short.
- **The first minute at bulk current.** The pack voltage is still settling, so the fast-charge model starts a few
  minutes long and comes down quickly.

## The rule (`ChargeEstimateHold`)

It is fed once per fresh frame in `ScooterService.updateChargerDetection()`, after detection, beside the other charge
observers. That runs only while status is under about 7 s old, and out of sight status comes every 15 s, so the hold is
also fed the charging state alone from `updateChargeLive()`, the reports the live card follows. A pause and resume
between background status reads then still starts a new hold. A not-charging state seen there also ends charger
detection's charge, so a charger swapped in the pause isn't taken for the old one.

- **Scope:** models with charger detection (`ChargerDetection.supports`: the UT5 MAX).
  - On other models the charger is never identified. The hold would be a flat three minutes on every charge, followed
    by the same current-rate time it held back.
- **Ready:** the charger in effect is known (chosen, or detected under Auto), and on the fast charger either:
  - the bulk current has started (4 A or more, FastChargeTaper's bulk floor); or
  - the pack is already at the charger's voltage limit (V + 0.107 × I at least 54.38 V, ChargeSlowdown's edge). A
    charge plugged in near full has no bulk stage; the taper model times it.

  The standard charger's current at plug-in is its bulk.

  Only readings taken after the last not-charging state count. A charging report that omits voltage and current keeps
  the old values, so a 10 A reading from before a pause can't make the resumed soft start ready.
- **Held:** from the first charging reading until ready. If Volt saw the charge begin (a not-charging reading within
  5 minutes before), it is held one more minute (`SETTLE_MS`) while the voltage settles. A charge Volt joins midway
  (a reconnect, a new process) is held only until it is ready.
- **Hard cap:** `MAX_HOLD_MS` = 3 minutes from the first charging reading Volt sees. After it, a time shows whatever
  the readings say. This covers a charger stuck in its soft start, a standard charger read as the fast one, a charger
  never identified, and readings stopping after plug-in.
- **Another charger:** a different charger chosen or detected than the one in effect when the charge became ready (Standard,
  then High-speed during the soft start) makes it unready again, within the cap. So does going from a chosen charger to
  none in effect (Standard, then Auto while it identifies the charger). Detection losing its own charger for a moment
  doesn't.
- **Ends for the charge:** once shown, the time is not held again in that charge, except after another charger. A charger pause (not charging) or
  30 minutes without readings begins a new charge, as in `ChargeProgressTracker`.
- **Simulation:** never held. The emulator validation sets the service's test-only `testEstimateHold` instead.

## What the hold changes

`ScooterService.chargingEstimate()` and `chargeAlertEstimate()` return `ChargingEstimate.estimating(...)` while held.
That is the same charging state, power, current and limit, with:

- no time to the limit or to 100%;
- no planning minutes;
- the state "Estimating time left", with an empty status (the time tiles speak the state and then any status, so TalkBack would say it twice; empty rather than null because the widgets' descriptions take the status as text).

The words are the live card's existing string, through `ChargeLiveState.estimatingText()`, so the change adds no new
text and no new catalog reference.

Every reader of the estimate gets it:

- Widgets' and Clean Home's charging view and time tile, including the 100% reading beside a limit and the charge
  alert's countdown;
- the Battery overview's charging rows and the Charging widget;
- the live charging card and its lock-screen version (no minutes: "Estimating time left");
- Charge done by. With no minutes it refuses to plan ("Wait for a charging time estimate"), so it can't plan from the
  guess. The measuring step still sees the stock pack model flag.

**Recordings are unchanged.** `chargeRecordingEstimate()` saves `computedChargingEstimate()`: the estimator's own result
before the hold, with its source. Saved charges, the charge graph's saved estimates and any later scoring still see
what the estimator computed.

## Validation

- **`tests/ChargeEstimateHoldTest.java`:** synthesized plug-ins.
  - **The plug-in phases:**
    - a fast charger's soft start, then bulk and the settling minute;
    - Auto with the real `ChargerDetection` (1.28 A identifies no charger; held until detection settles, then a
      minute);
    - a charge plugged in near full.
  - **Never ready, the hard cap:**
    - a charger stuck in its soft start;
    - a standard charger read as the fast one;
    - a charger never identified;
    - silence after plug-in;
    - a settling minute running past the cap.
  - **Other charge situations:**
    - the standard charger;
    - a charge joined midway;
    - a charger pause;
    - 30 minutes of silence;
    - another scooter;
    - other models.
  - **What the held estimate keeps:** the held form's fields, and the recording snapshot keeping the computed source.
- **Replay of `data/fast-charge-taper-v7`** (each charge as a plug-in Volt saw, charger as labelled):
  - no reading is held past three minutes;
  - standard charges hold exactly their first minute;
  - on the seven complete fast charges, the first time shown moves from a mean error of 16.7 minutes to 3.5;
  - session 6's 82-minute ramp reading is no longer the first time shown.
- **`android-tests/ChargeEstimatingFirstValidation.java`** (sweep steps `charge-estimating-first-412` and
  `charge-estimating-first-fold`), on Widgets and Clean, dark and light:
  - the tile says "Estimating time left" in the muted colour while held, and a time after, and TalkBack reads it once;
  - on Widgets Home, the Battery overview's and the Charging widget's time rows say the same, read once;
  - the live card says the same;
  - Charge done by has no minutes while held.
