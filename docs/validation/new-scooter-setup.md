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
- **A new key goes to one scooter.** The offer record is the address of the scooter offered the key, written in the same synchronous write: the key is tied to that scooter. While it is tied, Volt offers it to no other scooter: a connection for that profile that reaches another address stops before the owner check, with no reconnect, and says "This new key went to another scooter first. Turn that scooter on nearby and try again." Without that, two unowned scooters in range could both take one key in one profile (the first one's link dropping before its address was saved), and releasing or forgetting the second would lock the first out. With no saved address, **Try again** and **Connect** scan and show the chooser, as for any profile not yet saved, so the pick goes through the scan that reads the scooter's advertised model ID; picking the tied scooter sends the key, and picking another stops before the owner check. The service's own reconnect needs a saved address, so it never connects anywhere by itself.
- **The tie lasts until the scooter is saved.** Once the scooter accepted the key and Volt saved its address and serial, the profile's saved address and serial govern, as for any key: the tie no longer holds the owner check back (another profile's saved scooter is still refused first), as for a typed key. Connect still goes only to the saved address. A scooter whose Bluetooth address changed after a repair is reached through **Find it nearby** (on Home after two timeouts, or **Find at a new address** in My scooters), which saves the new address only when the scooter's serial number matches the saved one; that holds for every key. The record stays, so it still marks a made key.
- **If the record can't be written, nothing is sent.** The owner check goes out only after the tie is on disk; if the write fails or anything goes wrong, the connection stops with no reconnect and says Volt couldn't save setup. Android changes its in-memory copy of a setting before the disk write, so after any failed write of the record (the tie, the untie, or a new key's first record) Volt puts the earlier value back in memory: a retry tries to tie again, and again sends nothing if that fails.
- **Only the tied scooter's refusal frees the key.** A refusal of the owner check from the tied scooter means it has an owner and doesn't hold the key, so the tie goes and the key may be offered to another scooter (and is tied to that one). The key stays marked as offered, so setup still never deletes it. Nothing else is offered the key while it is tied, so no other scooter's refusal can reach it.
- **Once a scooter has accepted it** (its profile has a saved scooter address, which Volt stores only after the owner check and the serial read), setup never deletes it, and the same holds for a typed key: setup never replaces a profile a scooter accepted.
- **A scooter already saved in Volt is refused before the owner check.** If the scooter's address belongs to another saved profile, Volt stops with "This is <name>. Select it in My scooters" before sending any key, so a reset scooter still saved in Volt never takes the new key. Release it from its saved profile first (**Release this scooter**, from the companion change).
- A key that was never offered (the chooser was cancelled, say) may be replaced when setup goes another way. That includes **Import an owner setup file**: on Import, a made key never offered goes, once no connection is using it; one that may be on a scooter stays in **My scooters**, and setup stops pointing at it, so a name typed while the picked file waits goes to the imported scooter, never to the made key.
- A kept key stays in **My scooters** like any other scooter, where **Forget scooter** removes it after the usual confirmation.

On a refusal, setup says the scooter already has an owner, and offers **Set up another way**. That returns to the route list and keeps the key; choosing **Make a new owner key** again reuses it.

The done page reminds the owner that the key is only on this phone and to back Volt up. Volt backups carry it like any owner identity, with its record, in **Scooters & setup keys**. A restore puts the record with its profile: under the same profile ID, or onto the chosen profile only when that holds the same key. Neither a merge nor **Replace everything** replaces a record that says the key may be on a scooter with one that says it never was, and an older backup without a record restores the key as a typed key, as before. Setup's own pointers to the key are never in backups, so setup never deletes a restored key.

## Checks run

- `bash tests/run.sh`: `NewOwnerKeyTest` (from `tests/l10n/new-owner-key.sources`)
  - 200 seeded keys are twelve upper-case hex digits starting `88`; all-zero and all-`FF` tails are kept as made;
  - 500 `SecureRandom` keys all differ, and each of the five random bytes varies;
  - the identity parser reads each key back unchanged as an owner (role 0), and the owner check carries it as bytes 7 to 12 of a 17-byte `0x30` frame with role 0;
  - the removal rules above: only a key never offered and never accepted may go;
  - the made key's record: never offered, tied to a scooter's address, or offered and untied; a key without a record (typed, imported, captured) is sent anywhere, as before;
  - before each owner check: a made key's first offer ties it; tied, it goes only to that scooter; with no address it is never sent; only the tied scooter's refusal unties it, and the key stays offered; once a scooter address is saved, the owner check goes to any address (a repaired scooter at a new one), and the record stays;
  - failed writes, through preferences that behave like Android's (memory changes even when the disk write fails): a failed or throwing tie sends nothing and leaves `made` in memory and on disk, and every retry ties again and sends nothing until the disk takes it; a failed untie leaves the tie in memory, so another scooter is still refused; a failed first record leaves no record; a restore only ever writes the value that was there (or removes it);
  - the council's lockout chains, as the record and the rules see them: a link dropped before the address was saved and Try again (the chooser, the same scooter picked: sent, kept), a second scooter picked and refused before the owner check, a refusal reported for that second scooter (the tie stands), the first scooter's refusal and a third scooter taking the key (kept throughout), that scooter saved and then at a new address (sent), a tie that can't be saved (nothing sent, and nothing on retries until it can), a cancelled chooser, a saved scooter refused before the owner check, and a typed key (untouched);
  - backups, through the real archive and restore (`BackupTestStore`): the record is in Scooters & setup keys and none of setup's pointers are; a merge onto a fresh phone brings the key with its tie, so a second scooter is still refused; onto the same profile with another record it never loosens this phone's (tied here and `made` in the backup stays tied, and the reverse becomes tied; a tie in the backup also wins over an untied `offered` here); an older backup without a record adds none and leaves this phone's; a record is never copied onto the same profile ID holding another key; a scooter merged onto this phone's profile for it follows with the record only when the key is the same; and **Replace everything** brings the backup's record but keeps this phone's tie over an older `made` or missing one;
  - in the sources: simulation returns first; the saved-scooter refusal, then the record (given the saved address), decide before the only owner check, and only SEND lets it go; a refusal unties before the link fails; every record write goes through the restoring write; connectLast and Connect's chooser are main's (the chooser whenever no address is saved), the chooser's pick keeps its advertised model ID, and the service's reconnect needs a saved address; the record is written before the made key's profile exists, lives outside setup's preferences and travels with its profile in backups; a pending name never renames a made key; every way into Import leaves the made key; and every removal in setup goes through the rule above, except undoing a key whose save failed before Connect.
- `bash build.sh`; `python3 tools/l10n/gen_catalog.py --check`; `python3 tools/l10n/ship_check.py --aapt required`.
- **Emulator:** `NewOwnerKeyValidation` (the follow-up harness, 142 assertions) passed against this change at each of the sizes below. The harness and its place in the release sweep land in a follow-up pull request, because a change that adds text can't also change the proof tooling.
  - 412 × 915 dp, dark;
  - 344 × 882 dp (Fold cover screen), dark;
  - 725 × 604 dp, light;
  - 725 × 604 dp, light, at 140% app text.

  It drives the real setup screens and stores on a disposable emulator with Bluetooth off:
  - the new route sits second, after the recommended one, and the new-key page makes nothing;
  - Continue makes one key of the right shape, with its record ("made") and setup's pointer to it, on disk before Connect, and Back and Continue reuse it;
  - a key never offered to a scooter may go when another route is chosen, and Import drops one too, with its record and setup's pointers;
  - the real connection controller's gate before the owner check ties the made key to the first scooter offered it, on disk, with nothing queued; another profile's check leaves the record alone; with no address saved, the controller's own retry connects nowhere by itself (Connect's chooser condition, as on main), picking the tied scooter again sends the key, and the tie never stops a typed key's setup of it. Once an address is saved, another profile's saved scooter is still refused first, and the owner check goes to the scooter at a new address with the record kept;
  - offered to a second scooter while tied, the key is refused before the owner check: nothing queued, no reconnect, the tie unchanged, and "This new key went to another scooter first…" fully on screen above **Try again**. The couldn't-save message is rendered the same way (its failed write is covered by `NewOwnerKeyTest`);
  - through the controller's refusal path, a refusal reported for another scooter keeps the tie; the tied scooter's refusal unties the key, keeps it, shows the refusal fully on screen above **Try again**, and **Set up another way** keeps the key;
  - after a timeout, the key stays, **Set up another way** is hidden, and **Import an owner setup file** still asks for a file. Import keeps the offered key with setup's pointers to it cleared; a pending typed name never renames it and goes to the imported profile;
  - a typed key a scooter accepted is kept;
  - the done page shows its heading, the backup note, **Back up Volt** and **Go to Home** fully on screen without scrolling. In a short window the hero gives up height so the heading stays above the pinned note;
  - a real Scooters & setup keys backup of the phone holds the made key's record and none of setup's pointers, and a merge of it onto a record that says "made" plans the backup's "offered" (planned, then discarded).

  Screenshots of the route list, the new-key page, both new messages, the refusal and the done page were inspected at every size. Nothing reaches a scooter, so the done page is entered directly once the profile has the scooter address a real binding saves; `FirstConnectionValidation` covers the binding. The saved-scooter refusal and the record decide before the owner check from the live link's address; with no Bluetooth link, the harness calls that gate with synthetic addresses, and the source-order test above covers its place in the connection.
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
