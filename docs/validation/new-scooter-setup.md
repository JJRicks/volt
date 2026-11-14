# New scooter setup validation

## Behavior

Every setup route so far needs an owner identity from a NAVEE account that already owns the scooter: captured with a helper, typed in, or imported from a backup. A scooter that's new, or was removed from the NAVEE app, has no owner, so a computer and the NAVEE app were needed before Volt could connect to it at all.

**Make a new owner key** is a setup route for that case. Volt makes an owner key, stores it in a new scooter profile exactly like a captured or typed identity, and connects with it. The scooter takes it as its owner key, the same way it takes the first account that binds it in the NAVEE app.

## Evidence

This comes from reading the official Android app (NAVEE 2.1.9). None of its code, keys or server addresses are copied here.

- **The owner check carries no account or server proof.** Every connection, owner or shared, sends one `0x30` frame. It holds a random selector byte, a role byte and the six identity bytes. The scooter replies with a status. On models that answer with a challenge, the selector picks one of five fixed keys built into the app, so the challenge proves the app, not the account. Volt already handles both kinds.
- **The identity is the account's numeric user ID**, as six big-endian bytes. The app sets the top byte to `0x88` when the ID leaves it zero, which every ordinary account ID does.
- **The role byte is 0 for an owner and 1 for a shared scooter.** A shared scooter is sent the identity of the account that shared it, with role 1. (An earlier note in this project read the role as "no owner yet" against "has an owner". That reading was wrong.)
- **Binding is a server step followed by the same owner check.** Adding a scooter in the NAVEE app records the account and scooter on NAVEE's server first. Over Bluetooth, the app then sends only the usual owner check with the account's identity. No other command carries the identity. Any non-success status ends the bind as failed. So a scooter with no owner takes the identity from the first owner check it receives, and one that has an owner refuses a different identity.
- **Removing a scooter is a server step followed by a release command.** The app removes the record on the server, then sends `0x50` with a single `1` byte over a connection that passed the owner check, then disconnects. After that, the scooter has no owner until the next bind.
- **Nothing in the owner check refers to the server's record.** The server bind and the Bluetooth owner check are separate steps, and the frame carries only the selector, the role and the identity.

Volt's new key keeps the `0x88` top byte and fills the other five bytes from Android's `SecureRandom`, so it has the shape of any account's identity and can't be predicted. Nothing found shows the scooter checks that shape. Keeping it costs nothing and avoids relying on a guess that it doesn't.

## What isn't proven

- **No real scooter has taken a key Volt made.** All of the above is inferred from the official app. The first hardware check is the plan below.
- **How the NAVEE app treats a scooter that has Volt's key.** Its bind should reach the owner check and fail. Whether its server keeps a half-made record after that failure is unknown. Volt's copy tells people to release the scooter in Volt before adding it in the NAVEE app, which avoids the question.
- **Models other than the UT5 MAX.** The owner check is the same across the models Volt supports, but the release and the first-owner behavior have not been seen on any of them.

## Safety rules in the code

The risk is losing a key a scooter has taken. The scooter would then be owned by a key nobody holds. Setup keeps the key in these cases:

- **The key and its record are on disk before anything is sent.** Every made key has a record on this phone (`app_settings` `made_owner_key<profile ID>`), written before its profile exists and kept outside setup's own preferences, so it outlives setup: finished, skipped, or started again with **Add a scooter**. Continue then saves the new profile and setup's pointer to it with synchronous writes; if any fails (a full disk), the key is dropped unsent and the page says so.
- **Back or Continue again** on the new-key page reuses the key it already made. Each attempt sends the same key.
- **Once the key has been offered to a scooter**, setup never deletes it. The connection controller records the offer just before it queues the owner check, whatever started the connection (setup's Connect page, a permission result while the service was still starting, the service's own reconnect, **My scooters** or a scan). A timeout, a dropped connection, a refusal or closing setup all keep it.
- **A new key goes to one scooter.** The offer record is the address of the scooter offered the key, written in the same synchronous write: the key is tied to that scooter. While it is tied, Volt offers it to no other scooter: a connection for that profile that reaches another address stops before the owner check, with no reconnect, and says "This new key went to another scooter first. Turn that scooter on nearby and try again." Without that, two unowned scooters in range could both take one key in one profile (the first one's link dropping before its address was saved), and releasing or forgetting the second would lock the first out. **Try again**, the service's reconnect and **My scooters** go straight to the tied scooter by address, with no scan or chooser, so a link that drops before the address is saved reconnects to the same scooter and completes.
- **If the record can't be written, nothing is sent.** The owner check goes out only after the tie is on disk; if the write fails or anything goes wrong, the connection stops with no reconnect and says Volt couldn't save setup.
- **Only the tied scooter's refusal frees the key.** A refusal of the owner check from the tied scooter means it has an owner and doesn't hold the key, so the tie goes and the key may be offered to another scooter (and is tied to that one). The key stays marked as offered, so setup still never deletes it. Nothing else is offered the key while it is tied, so no other scooter's refusal can reach it.
- **Once a scooter has accepted it** (its profile has a saved scooter address, which Volt stores only after the owner check and the serial read), setup never deletes it, and the same holds for a typed key: setup never replaces a profile a scooter accepted.
- **A scooter already saved in Volt is refused before the owner check.** If the scooter's address belongs to another saved profile, Volt stops with "This is <name>. Select it in My scooters" before sending any key, so a reset scooter still saved in Volt never takes the new key. Release it from its saved profile first (**Release this scooter**, from the companion change).
- A key that was never offered (the chooser was cancelled, say) may be replaced when setup goes another way. That includes **Import an owner setup file**: on Import, a made key never offered goes, once no connection is using it; one that may be on a scooter stays in **My scooters**, and setup stops pointing at it, so a name typed while the picked file waits goes to the imported scooter, never to the made key.
- A kept key stays in **My scooters** like any other scooter, where **Forget scooter** removes it after the usual confirmation.

On a refusal, setup says the scooter already has an owner, and offers **Set up another way**. That returns to the route list and keeps the key; choosing **Make a new owner key** again reuses it.

The done page reminds the owner that the key is only on this phone and to back Volt up. Volt backups carry it like any owner identity.

## Checks run

- `bash tests/run.sh`: `NewOwnerKeyTest` (from `tests/l10n/new-owner-key.sources`)
  - 200 seeded keys are twelve upper-case hex digits starting `88`; all-zero and all-`FF` tails are kept as made;
  - 500 `SecureRandom` keys all differ, and each of the five random bytes varies;
  - the identity parser reads each key back unchanged as an owner (role 0), and the owner check carries it as bytes 7 to 12 of a 17-byte `0x30` frame with role 0;
  - the removal rules above: only a key never offered and never accepted may go;
  - the made key's record: never offered, tied to a scooter's address, or offered and untied; a key without a record (typed, imported, captured) is sent anywhere, as before;
  - before each owner check: a made key's first offer ties it; tied, it goes only to that scooter; with no address it is never sent; only the tied scooter's refusal unties it, and the key stays offered; a retry goes to the tied scooter with no scan;
  - the council's lockout chains, as the record and the rules see them: a link dropped before the address was saved and Try again (back to the same scooter, sent, kept), a second scooter refused before the owner check, a refusal reported for that second scooter (the tie stands), the first scooter's refusal and a third scooter taking the key (kept throughout), a tie that can't be saved (nothing sent), a cancelled chooser, a saved scooter refused before the owner check, and a typed key (untouched);
  - in the sources: simulation returns first; the saved-scooter refusal, then the record, decide before the only owner check, and only SEND lets it go; a refusal unties before the link fails; the tie and offer are one commit, and a failure or exception sends nothing; connectLast and Connect's chooser follow the tie; the record is written before the made key's profile exists, lives outside setup's preferences and stays on this phone in backups; a pending name never renames a made key; every way into Import leaves the made key; and every removal in setup goes through the rule above, except undoing a key whose save failed before Connect.
- `bash build.sh`; `python3 tools/l10n/gen_catalog.py --check`; `python3 tools/l10n/ship_check.py --aapt required`.
- **Emulator:** `NewOwnerKeyValidation` (the follow-up harness, 87 assertions) passed against this change at each of the sizes below. The harness and its place in the release sweep land in a follow-up pull request, because a change that adds text can't also change the proof tooling.
  - 412 × 915 dp, dark;
  - 344 × 882 dp (Fold cover screen), dark;
  - 725 × 604 dp, light;
  - 725 × 604 dp, light, at 140% app text.

  It drives the real setup screens and stores on a disposable emulator with Bluetooth off:
  - the new route sits second, after the recommended one, and the new-key page makes nothing;
  - Continue makes one key of the right shape, on disk before Connect, and Back and Continue reuse it;
  - a key never offered to a scooter may go when another route is chosen;
  - the offer is recorded where the connection controller sends the owner check: another profile's check leaves it unset, the made key's sets it on disk;
  - the owner check's rejection, injected into the real connection controller, shows the refusal fully on screen above **Try again**, and **Set up another way** keeps the key;
  - after a timeout, the key stays, **Set up another way** is hidden, and **Import an owner setup file** still asks for a file;
  - a typed key a scooter accepted is kept;
  - the done page shows its heading, the backup note, **Back up Volt** and **Go to Home** fully on screen without scrolling. In a short window the hero gives up height so the heading stays above the pinned note.

  Screenshots of the route list, the new-key page, the refusal and the done page were inspected at every size. Nothing reaches a scooter, so the done page is entered directly once the profile has the scooter address a real binding saves; `FirstConnectionValidation` covers the binding. The saved-scooter refusal before the owner check needs a live Bluetooth link, so the source-order test above covers it.
- **Physical:** none yet.

## Hardware test plan (UT5 MAX)

This needs the owner's scooter, phone and go-ahead. It also needs **Release this scooter** (the companion change), so a scooter set up this way can go back to the NAVEE app.

**Before starting**

1. Make a Volt backup and keep a copy off the phone. It holds the scooter's current owner identity, which is the NAVEE account's and doesn't change.
2. Park the scooter nearby and charged. Power off any other NAVEE scooter in range.
3. In Volt, **Disconnect**, then **Forget scooter** for the UT5 MAX. Otherwise Volt could reconnect with the account's identity after step 4 and give the scooter back to the NAVEE account.

**Steps**

4. **NAVEE app:** remove the scooter from the account. Note the app's version and its exact message. Close the NAVEE app.
5. **Volt:** **More → My scooters → Add a scooter → Make a new owner key → Continue**, then pick the scooter.
   - **Pass:** it connects, reads the serial and shows **You're connected** with the backup note. Lock and unlock once and check that live values arrive.
   - **Refused:** "This scooter already has an owner" means the removal didn't clear the owner. Tap **Set up another way**, restore the backup, and stop here. Volt keeps the new key in **My scooters**; forget it there once the scooter is back on its account.
   - **Timed out:** move closer and try again. The same key is kept.
6. **Volt:** make a backup now. It's the only other copy of the new key.
7. **Volt:** close and reopen Volt, and check that it reconnects by itself with the new key.
8. **Volt:** **More → My scooters**, the pencil, **Edit scooter → Release this scooter**. It should release, disconnect and remove the profile.
9. **Put it back**, either way:
   - **NAVEE app:** add the scooter as usual. It should bind to the account again. Then restore the Volt backup from step 1, or capture again.
   - **Volt:** restore the backup from step 1 (or type the saved identity), and connect. The scooter takes the account's identity again, and the NAVEE app still lists it as removed until it's added there.

**Record:** the NAVEE app version and messages, each result above, and Volt's connection activity for steps 5, 7 and 8. Leave out the identity, the serial and any location.
