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

- **Back or Continue again** on the new-key page reuses the key it already made. Each attempt sends the same key.
- **Once the key has been sent**, setup never deletes it, unless the scooter refused it outright (the owner check's "Owner identity rejected"). A timeout, a dropped connection or closing setup keeps it. The scooter may have taken the key before the link dropped.
- **Once a scooter has accepted it** (its profile has a saved scooter address, which Volt stores only after the owner check and the serial read), setup never deletes it, even if a later attempt on another scooter is refused.
- A kept key stays in **My scooters** like any other scooter, where **Forget scooter** removes it after the usual confirmation.

On a refusal, setup says the scooter already has an owner, and offers **Set up another way**. That returns to the route list and deletes the refused key.

The done page reminds the owner that the key is only on this phone and to back Volt up. Volt backups carry it like any owner identity.

## Checks run

- `bash tests/run.sh`: `NewOwnerKeyTest`
  - 200 seeded keys are twelve upper-case hex digits starting `88`; all-zero and all-`FF` tails are kept as made;
  - 500 `SecureRandom` keys all differ, and each of the five random bytes varies;
  - the identity parser reads each key back unchanged as an owner (role 0), and the owner check carries it as bytes 7 to 12 of a 17-byte `0x30` frame with role 0;
  - the removal rules above, including "accepted, later refused: kept".
- `bash build.sh`; `python3 tools/l10n/gen_catalog.py --check`; `python3 tools/l10n/ship_check.py --aapt required`.
- **Emulator:** renders of the route list, the new-key page, the refusal and the done note, at a normal phone size and on an unfolded Fold. A real owner check needs a scooter, so the emulator can't show acceptance.
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
   - **Refused:** "This scooter already has an owner" means the removal didn't clear the owner. Tap **Set up another way**, restore the backup, and stop here.
   - **Timed out:** move closer and try again. The same key is kept.
6. **Volt:** make a backup now. It's the only other copy of the new key.
7. **Volt:** close and reopen Volt, and check that it reconnects by itself with the new key.
8. **Volt:** **More → My scooters**, the pencil, **Edit scooter → Release this scooter**. It should release, disconnect and remove the profile.
9. **Put it back**, either way:
   - **NAVEE app:** add the scooter as usual. It should bind to the account again. Then restore the Volt backup from step 1, or capture again.
   - **Volt:** restore the backup from step 1 (or type the saved identity), and connect. The scooter takes the account's identity again, and the NAVEE app still lists it as removed until it's added there.

**Record:** the NAVEE app version and messages, each result above, and Volt's connection activity for steps 5, 7 and 8. Leave out the identity, the serial and any location.
