# Volt USB setup helper

A static, browser-local owner-capture wizard at `https://pairscooter.jjricks.com/`,
recommended and selected by default in new Volt setup flows. The page shows one
step at a time, with connection help, data details and secondary export options
in expandable sections. USB access and temporary logging each retain a separate,
explicit approval; no capture/transport behavior changes with the visual refresh.
Publication and deployed security headers must be verified separately from a local build.

Validation limits: controlled phone checks exercised timeout and cleanup with the
scooter out of range. A tester later reported successful onboarding without
identifying which helper was used, so a successful nearby-scooter browser capture
has not been separately confirmed. The recommended label does not establish
additional hardware coverage.
Chrome or Edge on a computer talks directly to the selected Android phone over
WebUSB. No Python, installed ADB executable, account login endpoint, upload API,
analytics, CDN scripts, or server-side capture processing is involved. Some
Windows phones still need an Android USB driver. A running computer ADB server
holds the phone's ADB interface, so the browser cannot claim it; the page reports
that case and asks for `adb kill-server`. A refused USB open (on Linux, usually
missing udev rules or a Snap/Flatpak browser) gets its own guidance. Raw USB error
messages are still never shown.

The owner must already have activated/bound the scooter, signed into the official
NAVEE app, and be able to connect to the nearby scooter. This helper obtains the
same six-byte Bluetooth identity used by `setup/capture-owner.py`; it does not
recover NAVEE passwords, bypass ownership, or replace initial OEM activation.
Future OEM versions may stop emitting the required message. In that case capture
fails; it never guesses an identity.

## Build and local use

Node 20+ and npm are needed **only to build the website**. Dependency versions are
pinned; `package-lock.json` fixes their full transitive tree and integrity hashes.
Build from the full repository: the website reuses the app's Saira font files in
`res/font/` and bundles their SIL Open Font License from `licenses/Saira-OFL.txt`.
No font CDN is contacted. The dark palette, rounded controls and typography match
Volt for Android; reduced-motion preferences disable the capture pulse.

```sh
cd setup/web
npm ci --ignore-scripts
npm test
npm run build
python3 -m http.server 8080 --bind 127.0.0.1 --directory dist
```

Open `http://localhost:8080/` in desktop Chrome or Edge. Localhost is a secure
context for WebUSB. Remote hosting requires HTTPS. Do not open `index.html` as a
`file:` URL. The simple local server supports functional development; production
must additionally send the response headers emitted in `dist/headers.json`.

The optional `test/browser-check.mjs` uses an already-installed Playwright and
Chrome; it never downloads a browser or connects to hardware. Supply absolute
`PLAYWRIGHT_MODULE`, `CHROME_PATH`, and `QA_DIR` environment variables. It checks
320/344/390/768/1000/1280 px layouts, all panels, loaded local fonts, text contrast,
keyboard focus, reduced motion, initial consent gating and cancellation, the local
recovery guide, CSP upload/script rejection, and service-worker blocking.

## Consent and privacy boundaries

1. Before opening Chrome's USB picker, the user acknowledges that ADB grants
   **broad developer access**, beyond this helper's specific capture operation.
   Android separately prompts to authorize the temporary ADB key. The page asks
   users to leave “Always allow from this computer” unchecked.
2. A separate checkbox and Start action authorize temporary NAVEE logging,
   force-stopping/restarting NAVEE, bounded capture, and automatic cleanup.
   No NAVEE mutation happens merely by choosing a phone.
3. This first version supports only the main personal Android user (user 0).
   Secondary users and work-profile NAVEE installs are rejected before logging
   changes. Because the log property is device-wide, capture also refuses when
   NAVEE is installed in any other user/profile; profile IDs are checked locally
   and names are discarded. Unknown/unreadable enumeration fails closed.
   All NAVEE start/force-stop commands explicitly target user 0. The launcher is
   resolved to a validated component in the fixed NAVEE package before starting.
   The page
   identifies that user's exact installed package UID. Logcat is filtered **on the phone** by UID, NAVEE tag, authentication
   marker, and fresh phone epoch. Other apps cannot inject an accepted message by
   merely reusing the tag. Unsupported UID filtering fails closed. Each response
   is bounded to 1 MiB, and polling stops after 90 seconds.
4. The 17-byte auth frame has its header, opcode fields, supported role, reserved
   byte, trailer, length, and checksum verified. Conflicting identities/roles are
   rejected. Arbitrary errors and raw phone logs are never printed or displayed.
5. Export is unavailable until NAVEE has been force-stopped, its **exact** original
   log property read back, and the session lock released. A capture is discarded
   on cancellation, disconnect, failed cleanup, or ambiguous result.
6. Saving to phone Downloads and downloading a computer backup are explicit,
   separate buttons. The phone path is generated internally, includes a random
   suffix, and is opened with shell noclobber; an existing file is never replaced.
   The identity travels over shell stdin rather than a shell command argument.
   The helper never imports or replaces a Volt profile. The existing native file
   picker is the final owner-approved import step.

The ADB RSA key and owner identity stay in page memory. No IndexedDB key store,
clipboard write, URL parameter, console message, or telemetry contains the owner
identity. The only persistent browser record contains the selected phone serial,
random capture token, capture stage and original log property. It is a recovery
journal, not an owner file. It is removed after verified cleanup. An explicit
Download recovery instructions button can save these non-credential details and
carefully quoted macOS/Linux recovery commands to a local text file.

Downloads files are sensitive. Android Downloads is shared storage and computer
downloads inherit browser/OS permissions; a website cannot promise mode 0600.
Users should remove the phone copy after import or keep backups somewhere private.
The official NAVEE process may itself emit broader verbose logs temporarily; this
helper reads only its filtered authentication messages, and does not clear the
phone's global log buffer.

After a verified phone save, a requested computer backup, or a safe terminal
capture failure, the helper disconnects USB and attempts `USBDevice.forget()`
without waiting for tab unload. Permission removal starts before awaiting transport
shutdown. Failed connection attempts also release the selected grant. Unverified
capture cleanup retains access for explicit recovery; successful recovery closes it.

A verified captured result remains in memory after saving, so the user can still
request a computer backup. Saving to the phone again opens the picker and requires
the same phone. Finish clears this result too. Page unload is only a best-effort
fallback, never the primary permission-removal mechanism. Revoking Chrome's USB permission **does not revoke Android's
trusted debugging keys**. The UI separately explains turning off USB debugging
and revoking Android authorizations if the user checked “Always allow.”

## Cleanup and interruption recovery

The browser and optional Shizuku helper share an exclusive shell-owned lock:

```text
/data/local/tmp/volt-owner-capture.lock/
  owner       webadb (Shizuku uses shizuku)
  token       random 64-character lowercase hex session ID
  original    exact pre-capture log property (bounded printable ASCII)
  started     phone epoch
```

Browser capture additionally writes `guard.sh`, `ready`, `stopping`, `status`,
`guard_done`, and a transient `cleaning` gate. No owner identity is stored here.
The original setting is read only after exclusive acquisition, so a second helper
cannot record the first helper's temporary VERBOSE value as its original.
Journal contents are always treated as data; recovery does not source them or
execute a script read from the phone.

Before enabling logging, the browser writes the recovery record, starts a
detached `nohup` watchdog, and requires its readiness acknowledgment. The phone
timer attempts cleanup after 120 seconds even if the browser vanishes. Start and
restoration share a gate, and a stopping marker blocks late starts. The signal
handler waits for a bounded in-flight child and exits rather than resuming later
mutations. Cleanup uses the same signal/EXIT discipline: a dropped browser shell
cannot strand its gate, and repeated signals cannot release that gate before an
in-flight restore finishes. SIGKILL or a reboot can still prevent traps from
running and require manual verification. A watchdog exiting after a restoration failure leaves an acknowledgment
so an explicit later recovery can restore and release the ended timer.

Cleanup always force-stops NAVEE before restoring the exact previous property
because NAVEE caches logging state in its process. Normal cleanup also waits for
the watchdog's sole terminal acknowledgment before retiring the completed lock
with an atomic rename and deleting only this session's known files. Interrupted
deletion can leave a small retired metadata directory, but cannot leave the global
reservation without the token/original needed for recovery. Unknown files block
retirement and are preserved.
A stale token cannot operate on another helper's lock. A definite lock-busy reply
clears only this page's pre-acquisition browser journal and reports that another
setup owns the phone reservation. Recovery from an interrupted preparing stage
also clears its journal when a different valid token is observed; it never touches
that other lock. Missing, unreadable or partial tokens remain ambiguous and require
recovery verification. Armed journals are never discarded merely for a token mismatch.

Automatic takeover of orphaned completed locks is deliberately not implemented.
The originating helper must finish recovery, or a trusted developer must verify
its completion and ownership before retiring it. This shared cross-helper protocol
needs a separate concurrency design before safe automated takeover is possible.

If the final release reply
was lost after lock removal, recovery reacquires the lock exclusively and repeats
force-stop and restoration; absence alone is never proof of success.

No browser can guarantee background code survives a device reboot, OS kill, an
ADB daemon failure, or a broken transport. Unconfirmed cleanup keeps export blocked
and the journal intact. The bundled `recovery.html` is public/account-free and
explains recovery and the manual fallback. Missing/partial journals, unknown lock
owners, or a forcibly killed timer require verification; never blindly delete a
lock or replace an unknown original setting with an assumed empty value.

## Hosting requirements

Deploy **only `dist/`** (excluding `dist/debugger/`, see NAVEE debugger), atomically,
at the root of the dedicated `https://pairscooter.jjricks.com/` origin. All links/assets are relative and also
support the legacy `/pairscooter/` subpath. Do not expose node_modules, source maps, repository
metadata, or runtime captures. There is no upload route to deploy.
Include `saira_regular.ttf`, `saira_semibold.ttf`, and `saira_bold.ttf` alongside
the HTML/CSS/script files and `THIRD-PARTY-NOTICES.txt`. Hosts with an asset
allowlist need those three additions and the generated policy's `font-src 'self'`;
font responses should use `font/ttf`. This permits only local font loads and does
not change the exact-hash script or blocked-upload policies.

Apply every header in `dist/headers.json` to all responses in this location.
`script-src` allows **only the exact hash of the bundled script**, paired with its
HTML `integrity` value. Do not relax it to `'self'`: same-origin Cloudflare injected
scripts or other site apps must not run in this sensitive page. `connect-src 'none'`
blocks fetch/WebSocket/beacon uploads, `worker-src 'none'` blocks new workers, and
HTTP `frame-ancestors 'none'` plus `X-Frame-Options: DENY` prevents framing. WebUSB
needs `Permissions-Policy: usb=(self)` in this location. No-store avoids stale HTML
and hash mismatches across deployments. Do not add CSP reporting endpoints that
could accidentally become a side channel.

The dedicated subdomain uses a **DNS-only** record and HTTPS directly from the VPS,
so Cloudflare does not rewrite its HTML. Disable HTML rewriting, email obfuscation,
analytics insertion, Rocket Loader and challenge-script injection if a reverse proxy
is introduced later. The legacy `jjricks.com/pairscooter/` recovery route has one
documented exception: Cloudflare adds its identified JS-detection snippet and
Cloudflare Insights beacon, both of which the exact hash-only CSP blocks. This
exception tolerates blocked markup only, never execution
of an injected script. Do not loosen CSP to make that snippet run. Moving or
changing hosting settings is a separate owner-approved deployment action.

After publication, compare **both HTML files** (`index.html` and `recovery.html`),
the bundle, and styles against `dist/` at the origin and through the CDN. Record
any HTML differences explicitly; a matching bundle hash alone does not show that
the HTML was unchanged. The dedicated host requires byte-identical HTML. The legacy
recovery route's exception is limited to the identified, CSP-blocked Cloudflare
challenge and beacon templates. Unexpected markup, changed consent text, a relaxed
policy, or another executed script fails validation. Verify the response headers
and executed scripts in an isolated browser as well. HTTP CSP and the early HTML
meta policy should agree; `frame-ancestors` must be HTTP-only.

The legacy subpath shares `jjricks.com`'s origin. WebUSB permission and localStorage
are origin-wide: another page on the origin may access the browser's retained USB
grant or recovery journal. The temporary Android ADB key is still memory-only, and
Android authorization is a separate boundary. CSP cannot protect against a server
replacing both the HTML policy and script. The owner selected the dedicated
`pairscooter.jjricks.com` origin to isolate new setup sessions' permissions and
storage. Keep the old route available for explicit recovery of sessions started
there, with new captures disabled and a link to the new site. Do not automatically
redirect users with unfinished recovery or copy journals between origins. Once
recovery finishes, remove the old origin's USB permission in browser site settings.
Setting `Permissions-Policy: usb=()` on all other main-site routes could further
restrict retained old grants, but does not isolate old localStorage; that broader
main-site change is outside this deployment.

The page refuses to capture if controlled by an existing service worker and cancels
if a controller appears. It does not unregister workers belonging to other apps.
Use a separate persistent browser profile if a root-scoped service worker controls
this page. Avoid Incognito/Guest windows and clearing site data during setup: losing
the recovery journal can leave a completed phone lock needing manual verification.

## NAVEE debugger

A second, separate page (`debugger.html`, `src/debugger.js`) for
`https://volt.jjricks.com/debugger/`. An owner describes a problem (model, a
description up to 4000 characters, optional contact), connects their phone with
the same USB consent and picker flow, and records what the official NAVEE app
logs about Bluetooth while they reproduce the problem (for example cycling RGB
light modes). They review every kept line, may delete lines, and only then send
the capture to the developer; a local JSON copy is a secondary option.

**Capture.** `src/debug-capture.js` subclasses the owner-capture session and
replaces only its collection step (`CaptureSession.collect()`). It takes the same
exclusive `volt-owner-capture.lock`, journal, user-0/profile/UID/launcher checks,
temporary VERBOSE property, detached watchdog, force-stop and exact restoration,
so it can never run concurrently with an owner capture or the Shizuku helper. The
lock format is unchanged; only the watchdog bound is a parameter
(`guardScript(token, watchSeconds = 120)`): 600 s here, 120 s for owner capture.
Recording is user-stopped and ends automatically after nine minutes, a minute
before the watchdog, with a visible countdown. Every ~2 s it reads
`logcat --uid=<NAVEE uid> -d -v epoch -t <last row time> -s com.navee.ucaret:V '*:S' | head -n <batch>` (logcat refuses `-m` with `-t`)
(1 MiB response bound; oversize replies shrink the batch). Rows at the boundary
timestamp are deduplicated by a 64-bit digest and occurrence count, so raw text
is never retained. Before cleanup it reads NAVEE's `versionName`, Android's
release and the phone model; these are the only device facts sent. Review, copy
and send are available only after verified cleanup and lock release; USB access
is then released and forgotten immediately. A failed poll, disconnect, page exit
or unverified cleanup discards the recording, exactly as owner capture discards
its result. Recovery uses the same journal and steps, with ten-minute timings.

**Folding.** NAVEE logs each frame twice (sent, then `Write success`), restates writes as
`sendCmd cmd=…, value=…`, and polls status (read 0x70) every few seconds. A `Write success` is
marked onto the command it confirms as ` (ok)`, so an unconfirmed command stands out; a `sendCmd`
that restates the write just logged, and repeats of an identical read (with their confirmations),
are folded. Folded rows are only counted; the count is sent with the hidden count. A real UT5 MAX
capture went from 172 kept lines to 39, every command confirmed.

**Filter (trust boundary).** `src/navee-log.js` is pure and unit-tested. Raw rows
are never displayed, stored, logged or sent. A row is kept only if its message
(after NAVEE's `[thread] Class.method: ` prefix) matches an allowlist of NAVEE
2.1.x Bluetooth/device-control messages (`Send write/read command`, `Write
success/failure`, notify/MTU/reconnect, `sendCmd`, `sendAmbientCmd`, connect,
disconnect and bond rows, lock status, scans, and the fixed Exo/DG status rows).
Exo and DG raw payload rows (`[Exo] type=…, data=[…]`, `Exo notify raw`, `DG write
<label>: […]`, `DG notify raw`) are hidden: they can carry a serial or authentication
bytes that the `55 aa` checks cannot read. Frame rows must be well-formed `55 aa … fe fd` frames with a valid
checksum. BleHandler's notify-description rows are **not** kept: NAVEE logs them
as a Chinese description followed by separate `Error`/`Update Data`/`CheckSum`
rows whose payload carries no command byte, and some describe serial or PIN reads.
Dropped entirely: anything mentioning `authData`/`encryptData`; any NAVEE frame
header whose command is 0x30, 0x31, 0x63 or 0x7a (Volt's `sensitiveCommand`) in
any separator style, with or without brackets or `0x`; `cmd=0X30|31|63|7A`; email
addresses; phone-number-like digit runs. Redacted: Bluetooth addresses (also the
12-hex identifiers NAVEE prints) to `[MAC]`, the device in Connect/DisConnected/
Bond rows to `[scooter]`, serial-like tokens to `[serial]`. Kept rows are printable
ASCII, at most 512 characters, at most 3000 rows and a byte budget that keeps the
request under 256 KB. Everything else is only counted ("N other NAVEE log lines
were hidden"). User notes (≤ 200 characters, `[note] …`) pass the same rules.
Annotations (`0x52 Cruise control = 1`, from `src/navee-commands.js`, checked
against `ScooterCommands.java`) are display-only and not sent.

**Submission.** One `fetch` to `https://volt.jjricks.com/debugger/submit` with
`credentials: 'omit'`, `cache: 'no-store'`, `redirect: 'error'` and the
`volt-navee-capture-v1` JSON body built and re-validated by `submission()`. The
server's reference ID is shown; rate limits (429), refusals and network errors keep
the capture for another try. Only the server's short printable `error` text is shown.

**Hosting.** The build writes `dist/debugger/` (`index.html`, `app.js`, combined
`styles.css` = `styles.css` + `debugger.css`, the three Saira fonts,
`THIRD-PARTY-NOTICES.txt`, `headers.json`). Deploy that folder alone at
`https://volt.jjricks.com/debugger/` and **never** include `dist/debugger/` in the
pairscooter deployment (the page refuses to record on any host other than
`volt.jjricks.com` or localhost). Apply `dist/debugger/headers.json`. Its CSP has
the same shape as the setup page, exact script hash and all, except
`connect-src https://volt.jjricks.com/debugger/submit` (nothing else).
`Permissions-Policy: usb=(self)` is required. Unlike `pairscooter.jjricks.com`,
`volt.jjricks.com` is shared with the homepage and other pages: WebUSB grants and
localStorage are origin-wide, so every page on that origin must stay free of
third-party script. The debugger forgets its USB device after each session.
`test/debugger-browser-check.mjs` is the optional Playwright check for this page
(same environment variables as `browser-check.mjs`).

## Validation limits

Automated parser/state-machine tests cover strict frames, stale/malformed/conflicting
rows, output bounds, unsupported UID identity, consent-independent export gating,
timeouts/cancellation, each failure stage, unverified cleanup, damaged recovery
records and wrong phones. Transport tests cover late timeout rejections and
permission-removal ordering. UI event tests cover save, reconnect, safe/unsafe
failures and legacy-origin recovery without exercising real USB hardware.
Real POSIX-shell tests with fake Android commands exercise
exact restoration, independent watchdog cleanup, concurrent-helper refusal,
start/timeout races, a signal during a bounded child, stale tokens and failed cleanup.

An Android 16 emulator also passed normal capture with synthetic NAVEE messages,
UID filtering, exact property restoration, explicit cancellation, and independent
watchdog cleanup after transport loss followed by verified reconnect recovery.

These tests do not by themselves prove a real WebUSB handshake, a real NAVEE owner
capture, or all Samsung/Pixel firmware behavior. The app and page must still be
tested with user-approved hardware before claiming end-to-end compatibility.

Primary API references: [Tango USB](https://tangoadb.dev/tango/daemon/usb/),
[Tango custom credential stores](https://tangoadb.dev/tango/daemon/credential-store/),
[Chrome WebUSB](https://developer.chrome.com/docs/capabilities/usb).
