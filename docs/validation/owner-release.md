# Release this scooter (owner release) validation

**Untested on hardware.** Nothing here has been tried on a real scooter. Until the owner tests it, treat the release as following the official app's sequence on paper only.

## The official app's unbind, in words

When an owner unbinds a scooter in the NAVEE app, the app first asks NAVEE's server to drop the scooter from the account. Once the server accepts, and while the app still holds the owner-authenticated Bluetooth link, it sends one frame to the scooter: command 0x50 with a single data byte of 1, framed as usual (`55 AA 00 50 01 01 51 FE FD`, where 51 is the low byte of the sum of the six bytes before it). The scooter answers with a status; the app then disconnects. After that the scooter behaves like a new or reset one and accepts a fresh owner binding from anyone nearby.

Volt has no server step. It sends only the scooter frame, so a NAVEE account that lists the scooter keeps listing it.

## Volt's handling

- **Entry.** More → My scooters → Edit scooter → **Release this scooter…**, a destructive-styled button (the same style as Forget scooter) after Forget scooter, shown only for owner profiles (role 0). A confirmation dialog explains the effect; its button is **Release**.
- **Gate.** The tap, the dialog's confirm and the send each recheck. It needs: no ride or charge recording, ride saving, backup, schedule test or pending scooter change (the same `scooterChangeUnavailable` check Forget uses); a ready, authenticated link to that very profile; not simulation; and the mutation gate's release decision: owner, fresh status (cached status never authorises it), parked with a motion report, no compatibility check, guided test or other change in flight, no recording, and a supported product and transport. Only a user tap with value 1 for the `owner_release` feature is admitted.
- **Sending.** The frame is built by `Protocol.ownerRelease()`; the generic `Protocol.frame` still refuses 0x50. It goes out as the admitted mutation through `BleController`, so `MutationGate.mayTransmit` passes only those bytes. 0x50 is a sensitive command: kept out of the event log and raw-frame views. The command log records `owner_release` with its outcome and never a value.
- **Status 0.** Volt disconnects (which cancels any pending reconnect), then at once removes the profile, its maintenance reminders and its model evidence, before anything could reconnect: reconnecting with the same profile would claim the scooter again. Saved rides and charge recordings stay. Toast: "Scooter released".
- **Anything else keeps the profile.** A nonzero status ("The scooter refused the release…"), no reply within the usual 6.5-second mutation timeout ("…didn't confirm the release…"), or a link that ends before the reply. After a timeout Volt's usual retry reconnects with the saved profile; if the scooter had in fact released, that reconnect claims it again, so Volt and the scooter still agree.

## Checks run

- `tests/l10n/OwnerReleaseTest.java` (synthesized fixtures only): the exact frame bytes and checksum; the generic builder's refusal; the gate's conditions (owner, ready, parked, motion report, real scooter, fresh status, idle, product and transport); origins and values other than a user's 1; the radio boundary; the command log's value-free record; and that only status 0 removes the profile.
- The full JVM suite, the localization checks and a build; see the pull request for results.

## To test on hardware

With a spare or second scooter the owner is willing to rebind: connect as owner, parked, release, then confirm that the NAVEE app (or Volt's setup) can add it as a new scooter, and that Volt doesn't reconnect afterwards. Also try a release while moving or as a shared user (both should be refused before sending).
