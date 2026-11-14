# Privacy and local data

Volt is an independent project, **not affiliated with or endorsed by NAVEE**.
This describes Volt and its included setup helpers, not the official NAVEE app
or external services you choose to use.

## What stays on your phone

Bluetooth controls, diagnostics, and recording do not use a Volt account or
server. The app has no analytics SDK, advertising SDK, automatic crash uploader,
or automatic ride upload (Auto-save rides writes to a folder you choose; it uploads nothing). It saves the following in Android's private app
storage:

| Data | Purpose |
| --- | --- |
| Owner identity, role, scooter address/serial, profile name, the model ID the scooter advertised (with when it was seen), and the model you said it is | Reconnect to your scooter locally and choose which controls its model supports; your model answer only names and pictures the scooter. Backups that include Scooters carry them |
| Preferences, widget layouts, saved slots, notes, favorites | Restore your chosen interface |
| Last-seen parameters and connection/command results | Show stale values and explain connection state |
| Connected history | Draw local historical charts and estimates |
| Ride readings and optional location, pressure, and motion samples | Analyze a ride you record |
| Charge samples and a manually selected charger label | Review charging and estimate remaining time |
| Battery alert choices per scooter, and for the charge in progress its first and highest battery level, the level already alerted and when it was last seen | Alert once per charge, also after reconnecting; the charge in progress is never in backups |
| Bounded connection events with timestamps, failure codes and recording state | Explain connection losses without saving credentials or packet contents |
| Bounded control events: time, control, change type (set, try, set back, read), the value sent, the scooter's answer or Volt's reason for refusing, the value read back, how long it took; once per connection, Volt's version, the model ID and layer, and meter and controller firmware | Explain "this control doesn't work on my model" reports; never the PIN, owner key, serial, address, names, locations or packet bytes |
| Try and guided test records (kind, setting, before and tried values, step outcomes with times relative to the try, your Did you see it change? answer, model prefix and layer, meter firmware, whether a sent report included it) | Keep an interrupted try or test visible until you review it, offer Set back, and put tries in your next compatibility report; at most 20, never in backups. Finished tries stay until a report you email, share or save includes them (a saved report once its file is written; then 30 days) or until you tap Clear try results, and a later Set back or review waits for the next report; finished guided tests go after 30 days |

Connected history is enabled by default. It does not start GPS or phone sensors.
It has a shared **50 MiB** budget: detailed samples for up to seven days, minute
summaries for up to 30 days, and daily summaries for up to two years. Older detail
can be removed sooner to meet the budget. Turning it off retains last-seen values;
clearing history also clears the selected last-seen cache. Controls are in
**Data → Data & history**.

Ride files are separate from connected history and remain until you delete them
or clear app storage. Charge recording retains up to 20 sessions across profiles
and charger labels; older sessions are pruned to stay within that limit. With
**Record charges automatically** on (off by default), a charge recording can start
by itself whenever Volt is connected and the scooter starts charging; it is stored
the same way, only on your phone, and nothing leaves the phone unless you send or
export it. It only replaces older automatic recordings, never ones you started. Phone
location and motion sensors run only during ride recording when enabled and
permitted. A foreground notification supports background recording; Android or
device restrictions can still interrupt it.

Connection diagnostics retain at most 200 coarse events in private device-local storage.
They exclude owner identities, PINs, names, serials, Bluetooth addresses and packet
contents. They are included only when you choose to export diagnostics or send a
report email with diagnostics, and are excluded from portable app backups. Clearing
app storage removes them.

The control log is always on and works the same way: about the last 1,000 events
(at most 256 KB) in private storage that is never backed up. Its storage accepts only
fixed names, numbers and version strings, so it cannot hold an owner key, PIN or PIN
digits, serial, Bluetooth address, profile or scooter name, location, route or raw
packet bytes. A value is never kept for the PIN. For a charge start time and for setting
the scooter's clock it keeps the time exactly as Volt sent it (the scooter's local time)
and the phone's UTC offset in seconds (never a time-zone name), so a report can show what
the scooter was told and the moment that stands for; a change cancelled before it was sent
keeps neither.

Charge evidence works the same way, one file per saved scooter (at most 256 KB) in private
storage that is never backed up: the last 10 Scheduled charging and Charge done by plans
as they were set, with their last change before they ended (such as a charge limit change
that turned Charge done by into a manual plan, and when) and how each ended, and the battery level, lock and schedule readings at the moment it
was set, at its start and at its end (each only from a reading taken within 30 seconds after
that moment while the scooter was connected, else left unknown); the last 5 schedule tests and why one was cut short; about 300 moments when
charging started or stopped, with the time, battery level, voltage, current, power,
temperature, schedule switch, start time, lock and whether Volt was on screen; a few
minutes of readings around a start Volt set and after a test; how each schedule change
ended; and changes to the schedule switch, start time, lock or charge limit that Volt did
not send. It holds only numbers, times and fixed codes: no names, serials, addresses, owner
keys, PINs, places, routes, time-zone names or profile ids. Forgetting a scooter deletes its
file. Like the logs above, it leaves your phone only in a report email you choose to send
with diagnostics.

Android cloud backup and device-transfer extraction are disabled in the manifest
and backup rules. Local profiles, including owner credentials, are not separately
encrypted by Volt. They rely on Android's app sandbox and device protection.

## Android permissions

| Permission | Why Volt uses it |
| --- | --- |
| Nearby devices (`BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`) on Android 12+ | Scan for and connect to the scooter over Bluetooth LE. Scanning is declared `neverForLocation`; Volt does not derive location from Bluetooth scans. |
| Legacy Bluetooth (`BLUETOOTH`, `BLUETOOTH_ADMIN`) and precise Location (`ACCESS_FINE_LOCATION`) on Android 8–11 | These Android versions require precise Location permission for Volt's Bluetooth scan/connect path, even when ride GPS is off. Grant it to connect; granting it alone does not start location recording. |
| Location (`ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION`) for optional ride GPS | Record a route only during a ride with phone GPS enabled. Approximate access is accepted, but precise access gives a more useful route. Denying ride GPS still allows scooter-only recording; Android 8–11 separately needs precise Location for Bluetooth as above. |
| Notifications (`POST_NOTIFICATIONS` on Android 13+) | Show connection and recording status, including the recording Stop action, the battery alerts you turn on and maintenance reminders that notify. Allow notifications to keep these controls visible; without them battery alerts work only while Volt is open. |
| Foreground service (`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `FOREGROUND_SERVICE_LOCATION`) | Keep an active Bluetooth connection or recording running outside the app. The location service type is used when permitted ride GPS is active. Volt does not request `ACCESS_BACKGROUND_LOCATION`; start a ride while the app is visible. |
| Vibration (`VIBRATE`) | Optional button, switch and connection haptics, respecting app and system settings. |
| Wake lock (`WAKE_LOCK`) | A renewed, time-bounded CPU wake lock supports active ride/charge recording, a charge alert waiting for its level (at most 12 hours) and pending phone-data writes with the screen off. It does not keep the display on. |
| Run after restart (`RECEIVE_BOOT_COMPLETED`) | Lets Android keep Volt's daily maintenance check planned after the phone restarts. The check exists only while maintenance reminders are on and a reminder that notifies can become due by time; it reads the reminders on the phone, may post one notification and ends. It does not start Bluetooth or keep Volt running. |
| Internet (`INTERNET`) | Fetch map tiles when Basemap is enabled, and ask GitHub for the latest release when you tap **Check for updates** or turn on **Automatically check for updates**. Bluetooth control and recording do not need this access. External links open in your browser. |
| Network state (`ACCESS_NETWORK_STATE`) | Android requires it for a background job that waits for a network. Volt uses it only for the optional daily update check, which starts off. Volt does not read your networks with it. |
| Shizuku API (`moe.shizuku.manager.permission.API_V23`) | Optional on-phone owner setup through Shizuku. Shizuku authorization grants broad Android debugging access; capture requires a separate consent step. Everyday Bluetooth use does not need it. |

Android only prompts for runtime permissions where applicable. Foreground-service,
internet, network-state, vibration, wake-lock and run-after-restart permissions are declared normal permissions,
not separate approval dialogs. Manage runtime permissions in Android's app settings;
manage Shizuku access in Shizuku. Device power restrictions and force stop can
still interrupt background work.

## When network access is used

**Basemap is optional and starts off if you have not saved a choice.** Existing
explicit choices are preserved. The **Basemap** switch directly below a ride map,
including the full-screen map, shares its remembered state with
**Rides → Recording settings → Map settings**. The first new enable explains that the
provider receives your IP address and viewed map area; choose **Enable basemap** or
**Cancel**. Cancel keeps tiles off. Once you approve, that acknowledgement is
remembered too. Backups with App preferences include both, and the restore preview says "Online map: on" when it will be on.

When enabled, the app requests visible map tiles from
OpenStreetMap by default, or from USGS Topo (United States only) or your chosen
HTTPS provider if selected. The first-enable explanation names the provider.
If you turned Basemap on before OpenStreetMap became the default (Volt 1.13 and
earlier used USGS Topo) and never chose a provider, Volt keeps using USGS Topo
for you rather than sending your map area to a provider you did not approve.
If such a map shows a ride outside USGS coverage, Volt asks once whether to
switch to OpenStreetMap, saying its servers would then receive your map area.
**Switch** saves OpenStreetMap as your choice; **Keep USGS** (or Back) saves USGS,
and Volt does not ask again.
Requests reveal your network address, the requested map area and zoom, and an
app/version User-Agent to that provider. A visible area can reveal where you
rode even though Volt does not upload a route file. Tiles are cached locally;
there is no bulk map download. GPS and the locally drawn route work without
tiles or an internet connection.

**Reports you choose to email.** Volt itself sends nothing. When it offers to share a compatibility report (after connecting a model it has none for) or a scooter's connection details (after two failed first connections), it opens your email app with the text in the message body, addressed to `volt-reports@jjricks.com`. You can read, edit or discard the message before sending. Reports contain the scooter's numeric Model ID, its read-only check or connection steps and error codes, Volt's version and, for connection details, your Android version and phone model. They also carry anything you add: the model you pick or type, and the names you give its drive modes. They never contain a serial, MAC address, owner key, PIN, scooter or owner names, routes or wall-clock times. **Report an issue** (at the bottom of **Settings**, and in **Service → Issue reports**) works the same way: it opens a draft to that address holding only a prompt, Volt's version, your Android version and phone model, and the selected scooter's model name. Your email provider handles the message like any other.

**Diagnostics in report emails.** Before **Report an issue** or the checker's **Email to Volt** opens your email app, a short sheet offers **Include diagnostics**, ticked by default. It attaches one file, `volt-diagnostics.json`: Volt's version, your Android version and phone model, the finished compatibility check's report if there is one, the connection events, the control log, the selected scooter's charging schedule record (its times, battery levels and outcome, and the last Scheduled charging test's time, result and measured offset), that scooter's charge evidence (its recent schedule plans and tests, when charging started and stopped with the battery readings at those moments, readings around a scheduled start, how each schedule change ended, and schedule or lock changes Volt didn't make; described under the control log above), the current readings without the scooter and battery serials or raw payloads, and, read from your phone as the file is made: Volt's version code and how it was installed (Play Store, package installer, adb or other, never the installer's name), your phone's and Volt's language, text size, dark mode, screen size, whether battery optimisation, power saving or background limits apply to Volt, notification, Bluetooth and location permissions and whether location and Bluetooth are on; a fixed list of Volt's settings (switches, numbers and choices such as **Stay connected in background**, units or the selected scooter's charge alerts; any text that isn't one of those shows only as "(set)"); how many ride and charge recordings there are, their size and age, and the newest ride's and charge's length, sample count and how it ended (never routes, places, names or notes); a free-space class; the last time Volt crashed, if it has, with the error's type and where in Volt's own code (never its message); clock changes seen in the connection events; and the drive mode codes the current connection reported with the top speed seen in each, and whether its current reports a direction. It never contains your owner key, PIN, serial, scooter address, location or routes, or scooter or profile names; Volt also withholds the file entirely if any saved scooter's serial, address or owner key would appear in it, or a scooter's chosen name in any of its values (a name that is one of the file's own words, such as "Scooter", or a model's name, says nothing about you and does not count), or anything shaped like a Bluetooth address, an email address, a coordinate or a path to your phone's storage. **Preview** shows the exact file. The diagnostics stay on your phone until you send such an email: sending them is opt-out per email. Untick **Include diagnostics** and the email goes without the file, as before; Volt remembers that choice for the next email. Each email's file is written once to Volt's private cache and never changed, and only the email app you send to is allowed to read it, for that email. Each time Volt prepares a new file, it first deletes earlier ones that are over a day old or beyond the ten most recent, so until your next report with diagnostics the last ones stay in that private cache (Android may also clear it).

**Charge recordings you choose to email.** **Battery & charging → Charge history & recordings → Email to Volt** opens your email app addressed to `volt-reports@jjricks.com`, with Volt's version and the scooter's model name in the message and the selected scooter's charge recordings attached: the same file **Export charge recordings** saves, with no owner credentials, names, MAC addresses, PINs or locations. It does hold the date and time of each charge: per charge, when it started, the charger and why it stopped, and per reading its time, battery level, voltage, current, power, temperature and estimate, with a random profile id. It is attached the way report diagnostics are (written once to Volt's private cache, readable only by the email app you send to, and pruned the same way), under its own name, `volt-charge-recordings.json`, so it is never mistaken for a diagnostics file. A recordings file over 25 MB, more than most email services accept, is compressed with gzip first and attached as `volt-charge-recordings.json.gz`; it holds exactly the same file, which any unzip tool restores. You can read, edit or discard the email before sending; Volt itself sends nothing.

**Update checks are in Settings → Updates.** **Check for updates** runs only when you tap it. **Automatically check for updates** is off unless you turn it on; then Volt asks once a day, in the background, through Android's job scheduler on any network. No push service or Firebase is used. The first time either one is used, Volt explains that GitHub receives your IP address and Volt's app/version User-Agent; choose **Check** or **Cancel**. Cancel sends nothing, and an approval is remembered (and included in backups with App preferences, as is the automatic check switch; when it last ran and which version it announced stay on the phone). Volt then requests one small JSON description of the latest Volt release from `api.github.com`. It shows whether that release is newer and offers **Download**, which hands that release's `volt-<version>.apk` from Volt's GitHub releases to your browser, or **Open release page** when the reply doesn't list that file. Your browser downloads it, not Volt; Volt never downloads or installs an update itself. The automatic check shows a newer release as a quiet notification, once per version.

Opening setup, help, attribution, or other external links uses your browser.
The destination and its host may receive normal web request data such as your
IP address and browser details. The pairing website has no analytics in its
source and no capture upload endpoint, but this is not a promise that hosting
access or error logs are disabled. The official NAVEE app's own networking and
logging are governed separately.

## Owner setup helpers

USB, Shizuku, and desktop capture briefly enable NAVEE logging and restart its
app after your approval. They parse fresh authentication messages locally to
obtain the Bluetooth owner identity. They do not ask for or retrieve your NAVEE
password. NAVEE may emit other verbose logs during that period; the helpers do
not clear the phone's global log buffer.

The browser helper keeps the temporary ADB key and captured identity in memory.
Its persistent recovery journal contains a phone serial, random session token,
capture stage, and original logging setting—not the owner identity. Verified
cleanup removes the journal. Keep browser site data intact if cleanup is
unfinished. Shizuku also keeps recovery metadata on the phone while a capture
needs cleanup. See the [setup guide](setup/web/README.md) for interruption and
permission-revocation details.

Saving to phone Downloads, downloading a computer copy, and importing into Volt
are explicit actions. Files in Downloads or a shared/cloud folder follow that
location's permissions and sync behavior. Delete temporary copies after import
if you do not want them retained there.

## Exports are different

| Export | Sensitive content |
| --- | --- |
| Private owner JSON | Plaintext Bluetooth credential, profile name, scooter address and serial |
| `.voltbackup` | Password-encrypted; the categories you choose: profiles/credentials, dashboards, settings, rides with routes, charge recordings, retained histories, app-side scooter choices. Unless only App preferences is chosen, it also lists every scooter's name and serial (no credentials) |
| Dashboard layout JSON (Home → Edit → Layout) | Layouts, widget options, notes and custom titles; no owner credentials or recorded readings |
| Ride CSV/JSON/GPX | Timestamps and measurements; phone/joined/route exports can include precise locations |
| Charge JSON | Electrical samples, times, gaps and charger label; excludes owner credentials, names, addresses, PINs and locations |
| Charge recordings email file (`volt-charge-recordings.json`, or `volt-charge-recordings.json.gz` compressed when over 25 MB) | The same content as Charge JSON |
| Ordinary diagnostics | Readings, diagnostic payloads and bounded connection events, potentially device serials in readings; excludes owner identity and PIN responses |
| Report email diagnostics file (`volt-diagnostics.json`) | Volt and Android versions, phone model, the compatibility report above when one is finished, connection events, control events (with the start time and clock Volt sent), the charging schedule record, the selected scooter's charge evidence (plans, tests, charging start and stop times with battery readings, readings around a start, and changes Volt didn't make), readings without serials or raw payloads, phone and app state, listed settings, recording counts and sizes, free space class, the last crash's type and Volt frames, clock changes, and the connection's drive modes and current direction; excludes owner identity, PINs, serials, addresses, names, locations and routes |
| Compatibility report | Model ID, region and SKU derived from the serial, firmware versions, read outcomes and lengths, status-field presence and setting values, and control decisions; excludes the serial, scooter address, owner identity, PINs, names, times of day, routes and raw payloads. A fresh random report ID is used each time; it is not stored. Guided tests and Experimental controls tries add the setting, its before and tried values, step outcomes with times relative to the try, your yes/no answer, whether it was set back or kept, and for tries the result and the model layer |

Exports only go to a destination you choose through the file picker or helper.
A destination may itself sync to a cloud service.

**Auto-save rides** (Rides → Recording settings, off by default) is the one export
that runs without a prompt: after each ride you end, Volt writes that ride's
Everything in one JSON (the same file as the Export ride data sheet's) into one
folder you chose through Android's folder picker. It holds the ride's analysis
(its name and notes, start and end times, odometer and distance, energy, speed,
battery, voltage, temperatures and coverage), its scooter readings, and when the
ride has them the phone's journal with every GPS fix's time, precise latitude and
longitude, accuracy, speed and altitude, the motion samples and the route. It
leaves out owner credentials, scooter addresses, scooter and battery serials and
Volt's scooter profile id ([details](docs/ride-export-everything.md#privacy)). Volt
keeps access to that folder only, and gives it back when the switch is turned off.
If the folder syncs to a cloud service, so do these files. Volt still uploads
nothing itself. The backup password is
not saved or recoverable. Use a strong password and keep it separately; see
[the backup format](docs/app-data-backup.md) for technical details and exclusions.
Older unencrypted `.voltrides` ride backups can still be restored; Volt no longer creates them.

Review files and screenshots before sharing. A redacted diagnostic file can
still reveal device identifiers, usage times, or other personal context.

## Removing data

Use the history controls, ride deletion, charge-history clearing, or profile
removal for individual categories. Removing a profile does not delete its saved
rides. Android **Clear storage** or uninstall removes Volt's private data; it
does not delete exports, browser recovery data, or copies synced elsewhere.
Finish setup recovery before clearing app or browser storage. Remove exported
copies separately, and revoke USB/Shizuku authorizations if no longer needed.
