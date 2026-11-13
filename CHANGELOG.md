# Changelog

## Unreleased

- **No wrong drive mode for a moment after connecting.** On models with an Eco/Turbo acceleration setting (the ST3 Pro, the GT3 Max and GT5 Max, and others the official app offers it for), Volt no longer shows that setting (3 or 5) as the drive mode for about a second until the scooter first reports its mode; a named mode could read SPORT there. The drive mode now comes only from the scooter's own report of it, as in the official app. The UT5 MAX and models without the setting are unchanged.
- **Steadier connection when Volt is in the background or you come back to it.** Volt no longer drops and remakes a healthy connection 18 seconds after connecting while it's out of sight, and controls (and a Scheduled charging change on its way) no longer refuse or stop for a few seconds after you return to Volt: it asks the scooter for its status at the in-sight pace as soon as you're back. A phone that slept with Volt in the background no longer drops a healthy connection when it wakes: the time asleep doesn't count as the scooter going quiet, though controls still wait for a fresh reading. While connected in the background, Volt now reads the battery with each 15-second status check (a small extra request or two), so the background notification keeps showing the current level; it shows the level only while that reading is current. Just after the phone wakes, an edit to Charge done by's time is saved for the next charge instead of failing, and the Scheduled charging switch isn't shown from before the sleep. A sleep no longer makes Volt stop asking for readings the scooter does answer or upsets a compatibility check in progress.
- **Volt does less while nothing changes.** The connection notification is posted again only when something in it changes, such as the battery level, instead of every few seconds while Volt is on screen and every 15 seconds while it's away; an unchanged one is refreshed about once every 40 seconds so its time still reads **now**. Keeping the screen awake no longer makes Android lay out Volt's window again every second. With the developer simulator on, Volt no longer keeps running in the background after you leave it: about 10 seconds after you leave, the simulation stays only while Volt's screen is still open in the background, and it ends if Android closes that screen.
- **The line under Stay connected in background says what stays connected anyway:** "Recordings stay connected, and so do Live charging and charge alerts during a charge" (it said only recordings). The user guide lists every case. Other languages show this line in English until the next translation update.
- **Fuller report diagnostics for overnight charging.** A report with **Include diagnostics** now also shows, for the selected scooter, the last 10 Scheduled charging and Charge done by plans (start, deadline, battery when set, how each ended, and whether the scooter was locked when it was set, at its start and at its end), the last 5 schedule tests and why one was cut short, when charging started and stopped with the battery readings at those moments, a few minutes of readings around a start Volt set, how each schedule change ended, and changes to the schedule switch, start time, lock or charge limit that Volt didn't send. The control log now keeps the start time and clock Volt sent. It is all numbers and times kept on your phone, never backed up, and removed when you forget the scooter; **Preview** still shows the exact file. Downgrading to an earlier version clears the control log.
- **OpenStreetMap is now the default basemap**, instead of USGS Topo, which covers only the United States and left ride maps elsewhere empty with **Map tiles unavailable (HTTP 404)**. The basemap still starts off, and the one-time explanation before the first map tiles now names the provider you'll get. If you already turned the basemap on with USGS Topo as the default, you keep USGS Topo, and a provider you chose yourself never changes, also after restoring a backup. The first time you then open a map of a ride outside the United States, Volt asks once whether to **Switch** to OpenStreetMap or **Keep USGS**. In **Map settings**, OpenStreetMap now comes first.
- Name ST3 Pro (Model ID 23452) drive modes WALK, DRIVE and SPORT (codes 11, 2 and 3), from an owner's answer, instead of raw numbers in Stats, on Home, in riding mode's chips, in the Hub's controls summary and in new rides' names. The scooter's own display shows DRIVE as D. Other Model IDs in its group keep raw codes until an owner reports them.

## 1.13.1 — 2026-11-11

- **Download straight from the update check.** When **More › Updates** finds a newer version, its **Download** button now opens that release's `volt-<version>.apk` in your browser, which downloads it; open the file to install it over this version, and your data stays. If GitHub doesn't list the file yet (a release still uploading, say), the button is **Open release page**, as before. Volt itself still downloads and installs nothing, and the automatic check's notification opens the same dialog. The button is new in this version, so Volt 1.13.0 still offers this update through the release page.
- **Maintenance reminders can be added without a connection**, by distance, by time or **Either**. Before, **Distance** and **Either** stayed greyed out until Volt had read the odometer with reminders on, even for a scooter that had connected many times. A time reminder counts from now. A distance added before any reading says **Distance counts from the next connection** and starts there; after an older reading, it counts from that last known reading, so rides since then count once Volt connects again. Editing a reminder to add a distance works the same way. Nothing is sent to the scooter.
- **Riding mode's clock can show seconds.** **Show seconds on clock** in riding mode's tune menu, off by default, adds seconds to the Clock reading wherever you put it: a chip, a tile, the strip, the highlight or the big number. It follows your 12- or 24-hour choice, and where a spot is too narrow, the AM/PM goes before the seconds do. The seconds tick only while a clock is on screen and riding mode is in front. TalkBack reads the time to the second when it reaches the clock, without announcing every tick.
- **Riding mode's chips can show almost any reading.** In the layout editor, Halo's and Trace's chips can now show power, any of the three ranges, battery, trip, ride time, recording time, average and max speed, efficiency, ride energy, voltage, current and the odometer, as well as drive mode, battery temperature and the clock. Only speed is left out, since the big number shows it. A chip shows the same value as that reading's tile, in your units, with the reading's name beside it, such as **Avg 14.2 mph**. Where room runs out, the name shortens (**Average speed**, then **Avg**), then becomes the reading's icon if no other chip on show uses that icon, and only then does a chip step aside; names are worked out among the chips that actually show. A screen reader always hears the full name and value. The default chips don't change. In Trace, chips that show a name take at most 45% of the row, so a long name can't squeeze the speed. A layout using the new chips, restored into Volt 1.13.0, shows those chips' defaults instead.
- **Portuguese (Portugal):** wording corrections from a native Portuguese speaker. Connection and lock states shown on their own are now masculine (**Conectado**, **Desconectado**, **Bloqueado**, **Desbloqueado**); the feminine stays where the scooter (*trotinete*) is named. Top speed reads **Velocidade máxima** instead of *Velocidade de ponta* (**Vel. máx.** in riding mode), *Queda de bateria* is now **Consumo de bateria**, and the odometer is **Distância total** (**Dist. total** where narrow), including in Maintenance, since it can be shown in miles.
- **Languages:** this release's new text is translated in all ten beta languages, so none of it shows in English.

## 1.13.0 — 2026-11-09

- **Dutch, Turkish and Polish**, all beta. A phone set to Nederlands, Türkçe or Polski now shows Volt in that language. On Android 13 and later you can switch Volt alone back to English in **More › App preferences › Language** or in Android Settings.
- **The electronic lock's Quick access button** now shows the lock's last known state on its padlock, open or closed, in every language, and keeps it while a change is on its way. In Turkish and Polish, the lock's Setting tile and Quick access button now say locked or unlocked in that language, instead of the shared On and Off that read the wrong way round in Turkish; where the word doesn't fit, the padlock shows the state. Every other language, English included, keeps On and Off.
- **Riding mode's settings are in its tune menu now.** Screen brightness, screen rotation, **Hide status bar** and night vision move from **More › App preferences › Riding mode** into the tune menu, under the layout editor. Every change saves as you make it, layout changes and **Reset** included, so there's no **Done** button; close the menu with its close button, Back or a tap outside. The menu stays open until the scooter reaches 4 mph (6.4 km/h), so it no longer closes the moment you roll or a reading drops out. **Record rides automatically** and **Open automatically** are in the tune menu too, and also stay in **More › App preferences**.
- **Lock, Record and the tune button show while the scooter is disconnected**, as well as while it's connected and stopped, and hide together while it moves. Disconnected, **Record** can stop a recording that's still running but never starts a new one, and **Lock** shows its last known state without taking a tap.
- **Lock and Record in riding mode** now fade and grow in and out like the tune button, and stop taking taps the moment they start to hide.
- **Riding mode closes when a recording ends by itself** (no movement for 10 minutes, or the scooter switched off or out of reach), so you come back to Home rather than to a riding screen for a ride that's over. It does this with Volt in the background too. A recording you stop yourself leaves riding mode open.
- **Back to Home after a lost connection.** If the scooter's connection was lost while Volt was out of sight and no recording is running, Volt shows Home the next time you open it, instead of riding mode. Nothing closes while Volt stays on screen. Volt's own Bluetooth pause, about 10 seconds after you leave with **Stay connected in background** off, counts only once you've been away for 2 minutes. With **Open automatically** on and the scooter moving, a new riding mode opens, starting from zero, never the old one.
- **Riding mode's Max and Average speed** start from zero each time you open riding mode, like Ride time and distance, instead of showing the scooter's trip values. Average counts only time spent moving.
- **Regen estimate for scooters that don't report current direction**, such as a GT3 Max. When the speed falls and the battery voltage rises, Volt now estimates that the scooter is braking with regen, and shows it below zero on riding mode's power bar, ring and trace and on the saved ride's power graph. It's an estimate, not a measurement: riding mode draws it in the muted colour with **power (direction unknown)**, never as confirmed regen, and saved rides keep the **Direction ?** chip, with **Battery draw** and **Battery input** still **Not measured**. A long ride's small preview graph may not show it; the expanded graph does. Range and energy estimates don't use it, and scooters that report direction, such as the UT5 MAX, are unchanged.
- **Riding mode's battery gauge** now follows Battery to whichever Focus spot shows it: segments beside the number in the range and battery rows, a strip under the big number, or a thin bar under a bottom tile. Where the gauge would squeeze the number, it's left out, and spots without Battery no longer give up room to an empty gauge. Battery in Halo's range spot gets the thin bar too, where there's room.
- **Ride export file names** now start with the ride's date and start time, in the phone's time zone, for example `ride-2026-11-09_14-32-c5ba30b9-everything.json`. This covers every export format, saving several files to a folder, and ride auto-save. A ride with no usable start time keeps the old name.

## 1.12.1 — 2026-11-09

- **Riding mode's power bar** now reads direction the same way your ride recordings do. On a UT5 MAX, which reports signed current, the bar shows draw and regen right away instead of staying muted with "direction unknown". On scooters that report only unsigned current, such as a GT3 Max, the bar stays muted with its direction unknown, except that a reading from about 1,200 W up shows as draw. That direction is inferred from the reading's strength, not measured. Saved rides on those scooters now show Draw and Input as unconfirmed instead of claiming a measured direction, including rides you've already saved.
- **Riding mode's Ride time and distance** start from zero every time you open riding mode. They survive rotating the phone, and reset when you back out and open it again.
- **Riding mode's chip row** tightens before it gives anything up, so **SPORT** or **DRIVE** no longer pushes the clock off a narrow screen.
- **Lock and Record in riding mode, while you're stopped.** When the scooter is stationary and under 1 km/h, two buttons show over the power readout. They work like Home's lock and recording controls, and they disappear the moment the scooter moves.
- **Start automatically, off by default.** **More › App preferences › Record rides automatically** starts a recording when the connected scooter moves, and **More › App preferences › Riding mode › Open automatically** opens riding mode when it moves with Volt on screen. Stop a recording by hand, or back out of riding mode, and that switch waits until the scooter reconnects. A ride that ends by itself (no movement for 10 minutes, or the scooter going away) doesn't, so moving again starts a new recording. With Volt in the background, recording needs **Stay connected in background**, and a ride started that way has no GPS route, because Android doesn't let an app start location from the background.
- **Charging hero:** on scooters with no charge limit of their own, a charge alert now shows the time to its level beside the time to full.
- **Live charging card** (Android 16 and later): the battery level moves to a dial on the right of the card, and the title says what it's charging to.
- **Live charging card:** it now clears about 15 seconds after the charger is unplugged, without opening Volt. It used to be able to say **Charging** for up to 3 minutes.
- **Charge done by** and **Scheduled charging** now tell you when the scooter has confirmed the start time, for example **6:10 AM charge start sent to scooter. Safe to disconnect.** That means Volt can disconnect from the scooter. Keep the charger plugged in so the charge can start.
- **Developer options** are now always in the **More** menu, so charge recording is easy to find; the seven taps on the version line are gone. The user guide has a new section on which charges help improve time estimates.
- **Home Setting tiles:** most now use a short label instead of ending in an ellipsis.
- **Maintenance:** the UT5 Max now lists its manual's service intervals (from its printed manual, pp. 14–15).
- **Portuguese (Portugal):** "Cruise Control" stays in English, as riders there say it.

## 1.12.0 — 2026-11-07

- **Volt now opens instantly.** It starts connecting to your scooter first and loads your rides in the background: about 0.3 s to the first screen, from about 4 s with a long ride history (emulator measurements). Opening **Rides** no longer freezes the app while it reads them; the list shows placeholder rows until they arrive.
- **Riding mode:** the red **RIDE** button on Home, left of the pencil, opens a full-screen view for riding that keeps the screen on. **Focus**, the default, shows big speed, **Range at this pace** (a range that follows how you're riding right now), a ten-segment battery, Trip, Ride time, battery temperature and a power bar for draw and regen; **Halo** puts speed in a speedometer ring, and **Trace** charts the last minute of speed and power. While stopped, the tune button beside the status pill opens the layout editor (below), and **More › App preferences › Riding mode** sets screen brightness (Phone, Volt auto, which learns your − and + taps for each light level, or Fixed), rotation lock and an optional **Hide status bar**. Riding mode only reads: it sends no scooter command and has no lock control.
- **Riding mode's power bar** now shows regen as regen. When you braked, the bar could stay red (draw) while the number turned green (regen), because it skipped the scooter's sign correction that arrives just after each battery reading; it now follows that correction, uses the same signed watts as the ride graphs, and switches side at once while only its size glides.
- **Riding mode layout editor:** the tune button opens **Layout**, where you pick Focus, Halo or Trace from drawn previews, choose what each spot shows (the ring, for example, can show speed, battery or power) and turn the power bar, chips and **Tenths** on or off for each layout. Nothing is saved until **Done**, each scooter keeps its own layouts, and **Reset** returns the layout on show to its default; with **Tenths** off, speed and range show whole numbers. The tune button now slides in and out smoothly instead of popping, and **Volt's range** in riding mode is now the range learned from your past rides.
- **Night vision in riding mode:** the moon button in the top bar turns riding mode deep red on black and, unless brightness follows the phone, much dimmer, so your eyes stay used to the dark; a tap switches it until the next scheduled change. **More › App preferences › Riding mode › Night vision** can be Off, On or Scheduled, from sunset to sunrise or at times you choose. Sunset and sunrise are worked out on the phone from the area of your last recorded ride (rounded to about 10 km, after asking once) or from your time zone, and nothing is sent anywhere. Night vision fades in and out over about half a second, and the status bar hides while it shows.
- **Riding mode's status pill** turns red while a ride records, with a recording dot and, for example, **REC 12:48**: the recording's own time, counted from when it started and separate from **Ride time**, the scooter's trip time. If the connection drops, the pill stays red, since the ride is still recording, and adds an amber edge, a hollow dot and **Not connected** or **Reconnecting…**; where both don't fit, it shows the time and the problem in turn every 3 seconds, or only the problem with Android's animations off. When a ride ends, the pill reads **Ride saved** for 4 seconds, or **Ride saved automatically** for 10, then shows the connection again. The pill takes no taps: start and stop a recording on Home's Ride recorder card or in **Rides**, as before.
- **Live charging** (**Scooter › Battery › Live charging**, on by default): when you leave Volt while the scooter is connected and charging, or a Charge done by or Scheduled charging start is set, Volt's notification becomes a live card with the level, the finish time and the charging power, also on the lock screen (**Details**, **Level** or **Hidden**). Volt stays connected until the charge reaches its limit, charging stops for 3 minutes or 12 hours pass; while it does, other apps, the official one included, can't connect. On Android 16 and later, where Android allows it, the card is a Live Update with the level as a status-bar chip, and **More › Developer options › Live Update status** says whether it was promoted. The card only reads the battery, as Volt already does; it sends no scooter command.
- **Live charging card:** its button now says **Disconnect**, since it never stops the charge. Disconnect ends the card for this scooter's charge (and, with Background connection on, the connection too), even if Android restarts Volt; open Volt and leave it again and the card comes back as usual, and the sheet's switch turns it off for good. The card is simpler, with no scooter icon or countdown, just **Done 10:42 PM · 412 W**, and its power reading stays up to date; the sheet's text is shorter.
- **Maintenance reminders** (**Scooter › Maintenance › Reminders**, off until you turn them on): add reminders after a distance, a time or whichever comes first, from suggested tasks or your own words, then **Mark done** or **Snooze** them. Distance counts from the odometer readings the scooter already reports, and nothing is sent to the scooter. A due reminder puts a red dot on the Scooter tab and, with **Notify me**, posts one quiet notification; for time reminders Android runs one short check about once a day, for which Volt declares Android's no-prompt permission to keep that check after a restart. Where a NAVEE manual states a schedule (for example the GT3 and ST3 series, but not the UT5 MAX), the page lists its intervals with the manual and page, and **Add** turns one into a reminder.
- **Arrange the Scooter tab:** tap the pencil beside the **Scooter** title to reorder its sections and settings, hide any of them (with **Undo**), add them back or **Reset**. Each scooter keeps its own arrangement, and hiding never changes a setting on the scooter. While something is hidden, the tab ends with, for example, **3 settings hidden · Edit**, and search still finds hidden settings, marked **Hidden**. On wide screens the two columns now balance by their number of rows.
- **Spanish (beta):** Volt's first translation. With the phone set to Spanish, Volt shows Spanish, and **More › App preferences › Language** (on Android 13 and later, also Android Settings) can switch Volt alone back to English. The **Language** row now sits with Units and Text size and shows the current language under its name. Its sheet lists the languages still in progress, greyed out, and has **Report a translation error** and **Request a language** buttons that open an email draft with nothing attached; reports and diagnostics stay in English.
- **Portuguese (Portugal) (beta):** Volt's second translation, from the same translation council. With the phone set to Portuguese (Portugal), Volt shows European Portuguese, and **More › App preferences › Language** can switch Volt alone back to English.
- **Portuguese (Brazil) (beta):** with the phone set to Portuguese (Brazil), Volt shows Brazilian Portuguese instead of the Portugal text, and **More › App preferences › Language** can switch Volt alone back to English.
- **Swedish, German, French and Italian (beta):** from the same translation council. With the phone set to one of them, Volt shows that language, and **More › App preferences › Language** can switch Volt alone back to English. Every shipped language has every string translated, with none left in English.
- **Ride recorder card:** tap anywhere on the card to start a ride, and it now starts right there on Home instead of switching to Rides. The card shows **Starting…** at once, and a start that can't happen says why (for example **Connect first**). It can now be one row tall at half width, where its label steps down from **Record ride** to **Record** to the disc alone.
- **More → Updates:** **Check for updates** moves out of About & permissions into its own section in the More menu, above the version line, beside a new **Automatically check for updates** switch. It is off by default. When on, Volt asks GitHub once a day in the background, with the same single request as the manual check, and posts a quiet notification once for each newer release; tapping it opens the update check. Volt declares Android's no-prompt network-state permission for this job.
- **Rides › ride details** has one **Export ride data** button instead of Export CSV, Export JSON and Export phone data. It opens a sheet with a checkbox for each of the ride's files: Sensor CSV and Analysis JSON, and for a ride with phone data Route CSV, GPX, Phone raw CSV, Motion raw CSV and Scooter and phone JSON (formerly All (JSON)). Files that can't be made now are greyed out with the reason. One checked file opens Android's Save dialog as before; several open Android's folder picker once and Volt saves each file there under its usual name, keeping no access to the folder. The sheet remembers your choice. Every file is byte for byte what the old buttons saved. Ride auto-save saves the sheet's Everything in one JSON (below).
- A ride now also ends on its own when the phone stays connected to a parked scooter. A connected phone keeps the scooter from switching itself off, so the link never dropped and the ride kept recording. Once the scooter has shown no movement for 10 minutes (no speed above 0, no driving status, no odometer or trip going up; a scooter that stops reporting speed counts as still), Volt saves the ride. Stops at traffic lights are shorter, and walking the scooter, even at 0.2 km/h in walk mode, counts as movement. The **Ride saved automatically** notice says **No movement for 10 minutes** with the time of the last movement, which the saved ride shows as its end, so the idle minutes don't count. A Bluetooth blip of up to a minute (the scooter reconnecting on its own) doesn't restart the 10 minutes. **End rides automatically** turns it off too. No scooter command is sent. See [ride auto-end](docs/validation/ride-auto-stop.md#no-motion-while-connected).
- **Rides › Phone sensors** no longer asks whether the phone is mounted or in a pocket. The choice only labelled phone recordings; nothing read it. New phone recordings leave the placement out, and their JSON export gives `"placement": null`. Rides recorded earlier keep their placement in their files and exports, and older backups still restore. Phone & route's motion caption now reads, for example, **1,200 motion samples** for every ride, without **Mounted** or **Pocket**.
- A ride now ends on its own when you switch the scooter off or walk away from it, not only after a clean stop. If the scooter goes quiet at walking pace or a stop, Volt saves the ride about 1 minute later; at riding speed, or if the phone's own Bluetooth is switched off, after 5 minutes. A reconnect inside that time keeps the same ride. The **Ride saved automatically** notice names the reason and the time of the scooter's last reading, which the saved ride shows as its end. The switch is now called **End rides automatically** (More › App preferences) and covers both cases. No scooter command or connection step changed. See [ride auto-end](docs/validation/ride-auto-stop.md#went-quiet-switched-off-or-out-of-range).
- **Kick-start speed** can now be tried lower as an experiment where the official NAVEE app offers only 3–5 km/h because of the scooter's region, such as on a European GT5 Max, on 2504–2506 models and on a UT5 MAX whose serial number is from outside the US, China or Russia. Scooter settings still offer 3–5 km/h there; **More › Experimental controls** now also lists Kick-start speed with Standing start, 1, 2 and 3 km/h, with the usual try, read-back and Set back. Choosing below 3 km/h says first that some scooters then wake up stuck in P after a lock or unlock until restarted, and that 3 km/h fixes it; the user guide's Troubleshooting says the same. Where the official app hides kick-start outside that region, the try now offers Standing start to 3 km/h instead of 3–5 km/h. Kick-start is the speed at which the motor starts helping; nothing that raises speed changed. A kick-start try kept before this version can't be set back; choose a value and try again.
- **Auto-save rides:** **More → Data & history** has a new **Ride auto-save** group: one switch, **Auto-save rides**, off by default, and a **Folder** row. Turning it on asks for a folder through Android's folder picker; the **Folder** row shows where files go (such as `Download/VoltRides`) and changes it. A new folder applies to rides saved from then on; files already saved stay where they are. After every ride you end (Stop in Volt or in the notification, the automatic end for parked rides, or a ride stopped after Volt restarted), Volt saves one file there with no prompt, `ride-<id>-everything.json`: the same **Everything in one JSON** the ride's **Export ride data** sheet saves, with the ride's scooter data and, when it has them, its phone GPS and motion recordings. Files are written under a hidden name and then renamed; a copy that is already there is left alone, a changed one is replaced, and Volt never makes a `(1)` copy. Older rides aren't copied. If the folder is gone or a write fails, the ride stays saved in Volt and waits: Volt tries again after your next ride, when it opens and when you choose the folder again (tap **Folder**). The **Folder** row shows what went wrong, and while Volt is in the background it posts one notification for that problem. The switch travels in an app backup, but the folder doesn't: on a restored phone the **Folder** row says **Choose a folder to start saving** and rides wait until you do. Turning it off releases Volt's access to the folder and forgets the rides still waiting; the **Folder** row stays in place, greyed out. A copy in progress keeps Volt running until it finishes, and backup and restore wait for it. Earlier test builds had two switches (Route CSV and ride JSON): if either was on, auto-save is on, in the same folder, and rides still waiting are saved as Everything files. A backup from those builds with either switch on restores it on. Files they already saved stay in the folder untouched. A backup from this version restored into one of those builds turns on its ride JSON switch when auto-save was on. See [Ride exports](docs/recording-reference.md#auto-save-rides).
- UT5 MAX high-speed charges: once Volt has watched about six minutes of the charger's taper, and while the current is still falling at the pace that taper sets, the time to full comes from that charge's own measured taper instead of switching between saved charges and the built-in curve as the battery percentage steps. In replays of the owner's charges this cut source switches from 9 to 5 and, on a charge whose taper was slower than usual, ended a late shortfall of up to 10 minutes; the last 15 minutes can now read up to about a minute short. The label shows **High-speed reference** instead of **From N past charges** during that time, unless a lower charge limit's time comes from saved charges. If the current levels off, only creeps down in small steps after its decay stops, or drops suddenly (as when a charger steps down), the estimate goes back to saved charges and the built-in curve: from the first reading of a drop faster than a decay with a 4-minute time constant and of at least 0.08 A between readings (for example 4.3 to 2.5 A within about 90 seconds), otherwise within about three minutes. A smaller fall between readings is treated as reading noise, so noise of about ±0.03 A mostly no longer hands the estimate back near the end of the taper. That 0.08 A includes the taper's own fall between readings, so there a single reading about 0.06 A low, or larger noise, still can, for about three minutes each time. A slower drop, one hidden in a gap of a couple of minutes in readings, or one that began just before those six minutes were up can keep the charge's own taper through the drop and for up to about 2 minutes after it. A steady drop below about 2 A that is faster than a 4-minute decay but falls less than 0.08 A between readings (for example 1.8 to 1.0 A over about 2 minutes, or 1.2 to 0.9 A over 50-60 seconds) is treated like reading noise: the charge's own taper holds through it and for up to about 3 minutes after it. In constructed test profiles, which are examples rather than limits, the time shown can then read far short: enough to put a start set from it past Charge done by's buffer, where saved charges stayed inside it, by about 26 minutes for a slower drop (about 45 when an even slower decay follows it), about 22 for a drop in small steps, about 31 for a drop taken as reading noise when a much slower decay follows it (about 25 before the drop), about 31 for the early drop, up to about 65 in the minutes before a creep is recognized, and up to about 44 in the decay just before a later stop, drop or slowdown, which no estimate can see coming. Charge done by no longer uses that shorter time where saved charges still match the charge (below). A taper slower than the model can show (one that would read more than a quarter short) never takes over before its last minute. While the current keeps its recent pace, the time shown is at most a quarter short (up to about 14 minutes, inside Charge done by's 20-minute buffer); a taper that keeps slowing down can read shorter: in constructed profiles, about half short (a start set from it about 14 minutes past Charge done by's buffer, where saved charges stayed inside it), and at the fastest slowing tried two-thirds short (about 56 minutes past, where saved charges were about 36 past). The owner's recorded tapers stayed close to the model's shape (within 1.12 times its time constant, and never falling faster than a decay with a 5.6-minute one), showed none of these cases, and the checks that hand back to saved charges change none of their replayed estimates. First estimates are unchanged. Once the charge's own taper takes over, the time shown to a lower charge limit, including one from saved charges, is never longer than the time to 100%; that can shorten a limit very close to 100% by up to about 2 minutes (at 99% in those replays), and otherwise it is unchanged. While the charge's own taper sets the time shown, **Charge done by** plans with saved charges' time instead when they match the charge, or did within the last few minutes, and take longer (at a lower limit, their time to that limit, even where the time shown is capped at the time to 100%), because the own taper can take up to about three minutes to notice that a decay has stopped and Charge done by sets its start only once. In replays of the owner's charges this starts Charge done by up to 4 minutes earlier (1 minute at the median, on 459 of 1,532 own-taper samples at a 100% limit, and up to 2 minutes on 18 samples at 99%), never later; at 95% and lower limits it is unchanged, and the smallest margin over those charges is unchanged. In the constructed profiles, of about 8,300 samples where a start set from the time shown was past the buffer while saved charges or the built-in curve stayed inside it, 15 still are (about 4 minutes past, where no saved charge matched and the built-in curve was longer; other constructed profiles reach about 8); without saved charges that match or recently matched, Charge done by uses the time shown, as before, even where the built-in curve is longer. New predictions are recorded as estimator `ut5-fast-taper-v8`. See [high-speed voltage/current estimates](docs/validation/fast-charge-taper.md#v8-the-charges-own-taper).
- **Traction control** is now an ordinary control (Scooter settings, with Off asking first) on every model whose official NAVEE app shows it, starting with the GT3 Max, instead of only on the UT5 MAX and the 2547/2585, 2619/2620/2646 and 2701/2745 models. An owner's recording showed the official app sending it from a GT3 Max's settings, and Volt's Experimental tries worked there. The official app shows it on every model with its More screen except 2314, 2417, 2422, 2573 and 2634; those, 2504–2506 and models without that screen (such as the N65i) can still try it from Experimental controls. It shows **Untested here** except on the ST3 Pro, where owners verified it. Because most models now offer it, traction control moves to the top of Experimental controls where it is still listed (for example on the N65i, ahead of kick-start speed and turn signal sound).
- A build made from the source rather than downloaded from a release now names its commit in its version, for example **Volt 1.11.1-dev+abc1234** in More › About, Android's App info and reports (with **-dirty** when built from uncommitted changes), so it's no longer mistaken for the release it's based on. Check for updates treats it as that release: it offers newer releases but never the same one. Release builds (`VOLT_RELEASE=1 bash build.sh`) keep the plain version and refuse uncommitted changes. A source ZIP is marked **-dev** without a commit, even when unpacked inside another git repository.
- Proximity unlock: some scooters, such as the GT3 Max, restart their Bluetooth right after proximity unlock is turned on. Volt used to call that change cancelled even when the scooter took it. It now says the scooter may have restarted Bluetooth (or that the connection dropped), reconnects, and from the first reading confirms the change or says it wasn't confirmed (also if the scooter doesn't come back within a minute); report diagnostics record the reading. A sent change that loses its connection with no reconnect to follow, such as when Bluetooth is turned off, now says it wasn't confirmed instead of cancelled. When proximity unlock is on but this phone isn't paired, or a pairing attempt ends without pairing, the Proximity section says so and offers to pair again or turn proximity unlock off. Turning it off no longer waits for a PIN read. Volt never turns proximity unlock off by itself.
- **Maximum speed** and **Kick-start speed** now follow the official NAVEE app's region rules on every model, instead of offering every region's choices. Maximum speed appears only when the scooter's serial number is from the US, China or Russia, and kick-start speeds below 3 km/h (Standing start, 1 and 2 km/h) only there too; elsewhere, and on 2504–2506 models, kick-start offers 3–5 km/h. A Spanish GT3 Max acknowledged those changes but kept its old values, so it now shows what its official app shows instead of **Unconfirmed here**. A scooter already set below 3 km/h shows that speed and can be moved to 3–5 km/h, and a Maximum speed tile Volt put on Home earlier is swapped for a control the scooter offers. A US UT5 MAX is unchanged, and every other control keeps its current choices (cruise control stays on the European GT5 Max).
- NAVEE debugger: on Android 11 and older, recordings now read each log line with the app that wrote it, as Android records it, and keep only NAVEE's, as owner setup already does. Following NAVEE's process could occasionally take in another app's lines when Android reused NAVEE's process number. Lines another app writes under NAVEE's name are dropped on the phone, so they're never kept or sent and can't hold back NAVEE's own; when they're all that arrives, the status line says another app is writing under NAVEE's name and its lines are ignored.
- Computer setup helper (`setup/capture-owner.py`): it now accepts only log lines Android records as written by NAVEE itself, as the setup page and the app already do, so another app logging under NAVEE's name can't hand it a fake owner identity. On Android 11 and older, which can't filter by app, it reads each line with the app that wrote it and keeps only NAVEE's. Like the other two routes, it now supports only the phone's main Android user and stops before changing anything if NAVEE is also installed in a work profile or another user. If the phone refuses the log read, it says it couldn't read NAVEE's log instead of reporting that nothing arrived.
- **Charge done by** and **Scheduled charging** keep seeing the scooter between Volt's background readings (every 15 seconds while Volt is out of sight). A schedule set on the scooter no longer seems to switch off and on, and the live charging card no longer briefly says **Estimating time left** just before a reading. Volt still sends schedule changes only right after a fresh reading.
- With **Background connection** on, the connection notice now stays **Scooter connected** while Volt is out of sight, instead of switching to the Bluetooth message for a few seconds between Volt's background readings.
- **Rides** also opens fast after Android has closed Volt. Volt now keeps a small summary of each finished ride on the phone, so the list doesn't reread every ride after a restart. The summaries stay out of backups, and Volt rebuilds them when needed.
- **Rides:** a ride that ended on its own counts its time only up to the scooter's last reading, in the list, ride details' **Duration** and the compare sheet, so the quiet wait before it ended no longer adds to it.
- **Home › Reading tiles:** when the last row isn't full, as with three readings in two columns on an unfolded phone, the last tile spans the rest of the row instead of leaving an empty square. Reorder the readings in the widget editor to choose which one.
- **Wide screens with large text** now use the phone layout, so long words no longer break in the middle of narrow columns. A 725 dp unfolded phone switches to it from about 1.12× text; a wider screen keeps two columns longer. With two columns, a **More** list row whose longest word doesn't fit now sets its icon aside, and if a word still doesn't fit, More switches to one column, so its titles never break mid-word in any language. The gap between the columns stays centred, where a folding phone's hinge is.
- **Narrow labels** on status lines, the wide-screen side rail, Quick access, dashboard captions and the lock switch now step down to a shorter word and then an icon, instead of shrinking to tiny text, breaking a word or ending in an ellipsis. Screen readers still hear the full label, and English looks the same wherever it already fit.
- **Wording:** owner-approved English fixes across the app: plainer refusals and messages, correct singulars such as **1 fix** and **1 recording stored**, one term for each idea, and the **End rides automatically** caption now names the 10-minute no-movement rule. The Scooter tab's section for the Maximum speed setting is now called **Maximum speed** instead of Top speed, which now always means a measured speed, as in Rides.

## 1.11.1 — 2026-11-02

- Owner setup now works on Android 11 and older, from both the setup page and the app's **Use Shizuku on this phone** (which needs Android 11). Their log reader can't filter by app, so capture failed there. It now reads each log line with the app that wrote it, as Android records it, and keeps only NAVEE's. If the phone refuses the read or the read fails partway, both say Volt couldn't read NAVEE's log, instead of reporting that nothing arrived.
- Battery alerts: Volt now spaces its notification updates, so the burst when an alert fires no longer loses the alarm's **Stop** button. Android limits how quickly an app may update its notifications. When an alert fired while Volt's connection notice was also changing, Android could drop the update that added Stop, and the shade could keep showing an older alert. Swiping a sounding alert away now also stays dismissed.
- NAVEE debugger: phones on Android 11 and older are now recorded. Their log reader can't filter by app, so recordings came back empty; the debugger now follows NAVEE's own process instead, after checking it really belongs to NAVEE, and keeps following it if NAVEE restarts.
- NAVEE debugger: while recording, a status line says whether NAVEE's Bluetooth lines are arriving and, if not, why and what to try: NAVEE's log setting didn't take, the phone's log reader is refusing the request, another app is using NAVEE's name, the phone isn't keeping app logs, or NAVEE isn't running or isn't writing logs the page can read. A review with no NAVEE lines repeats the last finding. A capture that can't be sent or copied now names the field to fix, or says there are no NAVEE lines, instead of a general "details are missing" message.

## 1.11.0 — 2026-10-30

- **Battery alerts** (Scooter › Battery) for scooters without a charge limit setting, such as the GT3 Max: a **Charge alert** when charging reaches a level you choose (50–100%, or Full, which also counts a charge that stops at 99%) and a **Low battery alert** below a level you choose (5–50%). Pick **Notify me**, **Alarm sound** (your alarm sound for up to a minute, with Stop), or both; everything starts off. Alerts stay on for every later charge until you turn them off, and alert once per charge. While a charge alert waits, Volt stays connected in the background with a notification showing the battery level (for example **42% now · alert at 80%**), for up to 12 hours; if the connection is lost it says the alert is paused. The low battery alert works only while Volt is already connected. Nothing leaves your phone, and the UT5 MAX, which has its own charge limit, doesn't show these rows.
- GT3 Max: Top speed and Kick-start speed now say **Unconfirmed here**. An owner's scooter accepted changes to both but kept its old value (top speed stayed 32 km/h, kick-start stayed 3). They stay available in case the scooter needs conditions Volt can't see, until a report shows a change kept.
- Drive mode no longer flickers on scooters whose settings read carries another value in the mode's place, such as the GT3 Max, where it alternated between the real mode and 3. Volt now follows the scooter's live home report for the mode, as the official app does; the settings byte stays in diagnostics as its own reading. The GT3 Max's modes are named Drive, Sport and Walk, from an owner's report.
- The lock switch moves the moment you tap or slide it, once Volt is connected, instead of waiting for the scooter. A tap made in the first moments after connecting, while the scooter is still sending its first readings or Volt is finishing an automatic setting, is now kept and sent as soon as the scooter accepts it, instead of being silently ignored. If the scooter refuses, doesn't confirm, or isn't ready within a few seconds, the switch goes back with a short message and a distinct vibration. Locking still waits for the scooter to report it is standing still.

## 1.10.0 — 2026-10-28

- If Scheduled charging or Charge done by passes its start time and the battery hasn't charged, Volt now says so, with the time and battery level, and offers to turn the schedule off so charging starts now. This includes a schedule whose On confirmation was lost but which the scooter applied. Out of sight, it posts one notification. Report diagnostics now include the schedule's times, battery levels and outcome, with no identifying details.
- UT5 MAX: **Test scheduled charging** (Scooter › Battery) checks the scooter's clock and schedule while it charges. Volt pauses charging, sets the scooter to resume about 2 minutes later, and watches for the pause and the resume (about 3–5 minutes). It then reports whether charging resumed on time, early or late, or never paused or resumed, and always ends with Scheduled charging off and your Charge start time restored. Report diagnostics include the last test's time, result, measured offset in seconds and whether charging paused.

## 1.9.0 — 2026-10-28

- UT5 MAX high-speed charges: when the charger reaches its voltage limit at a different battery percentage than Volt's saved charges, the time to full now comes from pack voltage and charging current instead of the live rate, which in the charger's taper read 2-3 times too short (10 minutes with 27 left, in an owner's charge from 91%). The taper's pace is refined from the charge itself after a few minutes, and a personal estimate no longer flickers to a fallback for a moment when the battery percentage steps. Saved finishing curves also check pack voltage before matching. New predictions are recorded as estimator `ut5-fast-taper-v7`; standard-charger estimates are unchanged. See [high-speed voltage/current estimates](docs/validation/fast-charge-taper.md).
- Charge done by now uses the displayed standard-charger estimate plus the existing 20-minute buffer, instead of adding the buffer to a second, more pessimistic estimate. Existing scooter schedules stay as programmed until explicitly changed.
- Recognize and picture the GT3 Max (Model ID 24182) from an owner's report. Its controls keep their existing compatibility rules; this report did not test settings changes.
- Include battery temperature in diagnostic exports; the degree symbol previously caused it to be omitted.
- Keep unknown current/temperature sign flags in diagnostics without letting them overwrite a known sign. Sensor timestamps and inline negative signs are preserved.
- Owner file setup helper: if browser privacy settings block site storage, the page now explains how to allow it instead of failing to start or offering a recovery that cannot run. New captures stay disabled until storage is allowed, because the helper needs its temporary recovery record.
- Fix the NAVEE debugger's Send and Download buttons becoming unresponsive if browser privacy settings block a storage-related check. Completed recordings can still be saved or sent, and blocked browser checks show guidance instead of crashing the page.

## 1.8.1 — 2026-10-26

- ST3 Pro (Model ID 23452): a second owner's report verified dashboard units, turn signal sound and range estimate, so those rows no longer say **Untested here**, and traction control, which the official app doesn't map there, worked in three tries. Traction control is now an ordinary, verified control on the ST3 Pro instead of an Experimental controls try.
- When your scooter refuses an Experimental controls try, its sheet now says so plainly: the scooter answered with an error, so it most likely doesn't support that setting, and trying again usually gets the same answer. The button reads **Try again**.

## 1.8.0 — 2026-10-25

- A charge recording no longer ends the moment the charger stops by itself below 99%, as an overheating charger can. It waits up to 30 minutes, marked as a charger stop (the notification says so), and continues the same recording when charging resumes; time spent waiting doesn't count against the recording or its learned curve. Charge recordings shows how often and how long the charger stopped, and the export lists the stops as `charger_pauses`. A charger still off after 30 minutes ends the recording as before.
- A phone ride's elevation no longer drops back to its starting height after a GPS dropout, such as under an overpass or through a short tunnel, which broke the elevation chart and painted false steep grades on the map. The route still splits at the dropout, but elevation keeps the ride's own baseline: the barometer's, or GPS height's when there is no barometer reading, as an owner reported.
- Color a ride's route power from 0 to that ride's own peak, rounded up to 500 W (at least 1,000 W), instead of a fixed 2,000 W that turned a dual-motor ride solid yellow. As with speed, a lone spike doesn't stretch the scale; the legend shows the top.
- Name the XT5 Pro (Model ID 24162), from an owner's compatibility report, and stop asking its owners for one. Its drive modes read WALK, DRIVE and SPORT (codes 11, 2 and 3, a full cycle the owner reported). Its controls are not verified; they follow the official app's map for its model group as before.
- Report emails can carry a diagnostics file. **More → Report an issue** and the compatibility checker's **Email to Volt** now end on a short **Email Volt** sheet with **Include diagnostics**, ticked unless you unticked it last time: it attaches `volt-diagnostics.json` with Volt's and Android's versions, your phone model, the finished check's report if there is one, recent connection events, recent control changes and their outcomes, and the current readings. It never holds your owner key, PIN, serial, scooter address, location or names. **Preview** shows the exact file. Untick it to send the email as before.
- Volt now keeps a control log on the phone, always on, like its connection log: each change, Try and Set back with the value sent, what the scooter answered (accepted, rejected with its code, no answer, or read back as that value, another value or unconfirmed) and how long it took, changes Volt refused before sending with the reason, and readings outside your model's choices (such as a Top speed of 0). It keeps about the last 1,000 events, is never backed up, and leaves the phone only in a report email you send with diagnostics included.
- An ST3 Pro owner's compatibility report (Model ID 23452, US) confirmed the model, so its owners are no longer asked for one.
- Experimental controls also list a control Volt hasn't mapped for your model (it can't tell whether the official app offers it there), when your scooter reports it. On the ST3 Pro, which reports traction control on, and on Group S models and the GT5 Max, traction control can now be tried.

## 1.7.0 — 2026-10-23

- The browser setup site (pairscooter.jjricks.com) now names the two common USB failures instead of one generic message: another program, usually a computer ADB server still running after `adb devices`, holding the phone (run `adb kill-server`), and a browser that may not open the phone (Linux udev rules, or a Snap or Flatpak sandbox). It also lets you choose again after picking the wrong phone during recovery.
- Offer the custom speed cap in **Experimental controls** on models whose official app hides it, such as the UT5 Ultra X and the GT5 Max: Off or 8–32 km/h, the official app's widest cap range. A cap only lowers speed below your Top speed, every try is read back, and **Set back** restores your previous setting. Top speed, the drive-mode cap and overspeed control are still never added.
- Experimental controls tries are now saved on the phone and sent with your compatibility report: each try reads the current value, saves a record, sends one change, reads it back and asks **Did you see it change?** (**Yes**, **No**, **Skip**). The record keeps the control, the value before and the value tried, the result, your answer and whether you kept it, and goes in the checker's report and **Email to Volt** draft (no serial, address, owner key, PIN, names or times of day). Records stay until a report you share, email or save includes them (a saved report once its file is written; a later **Set back** waits for the next report), or until **Clear try results**, at most 20.
- A try interrupted by a dropped connection or by Volt closing is never finished on its own: it shows as **Unfinished try** on the Scooter page and in Experimental controls, and **Review** offers **Restore**, **Keep** or **Dismiss**. **Set back** also works after Volt restarts. A try that was sent but never confirmed is never called **No change seen** or **Not sent**.
- Move the compatibility checker's **Try a setting** into Experimental controls as **Help verify**: dashboard units, tail light, turn signal sound, automatic lights and range estimate, where the official app offers them for your model but Volt hasn't tested them there. Their Scooter settings rows are unchanged. The checker still reviews unfinished tests, including ones from earlier versions.
- Mark cruise control verified on the GT5 Max (Model ID 25462): its owner reports it works, though the official app hides it there by region. The row no longer says **Untested here**; the GT5 Max's other controls still do.
- Bundle an XT5 Ultra photo, ready for its Model ID. Until a compatibility report brings that ID, it shows only when an owner answers **Which scooter is this?** with XT5 Ultra.
- Name GT5 Max drive-mode code 5 SPORT as well as 3: an owner's Sport reading alternates between them, so it no longer flickers to **5 (raw)**. The N65i, assumed to match the GT5 Max, follows.
- The computer (ADB) setup checklist now includes the extracted Volt source, which holds `setup/capture-owner.py`, and the capture step says to run the command from that folder, as an owner suggested.
- Name and picture the UT5 Ultra X (Model ID 25852), from an owner's compatibility report. Its controls are not verified; they follow the official app's map for its model group as before. Volt now has a report for it, so its owners aren't asked for one.
- Line up **Back up & restore** with the other rows in More's menu on wide and unfolded screens; its icon and title no longer sit further left.
- On models other than the UT5 MAX, a long Charge start value such as **Not scheduled** moves under its label with the **Untested here** caption, like other long values, instead of staying beside the label.

## 1.6.0 — 2026-10-23

- Name N65i drive modes WALK, DRIVE and SPORT (codes 11, 2 and 3), assuming they match the GT5 Max until an N65i owner reports otherwise.
- Try controls the official app doesn't offer for your model in **More → Experimental controls**. On an identified model it lists any of kick-start speed (including Standing start), cruise control, traction control, tail light, automatic lights, turn signal sound, the three assists and prompt sound that the official app hides for it, most likely to work first; the UT5 MAX keeps its Lab controls. Everything the official app offers stays in Scooter settings as before. Each row says **Untested on your model** until you try it, then **Worked here**, **No change seen** or **Refused by scooter** from what the scooter reads back, and **Set back** returns the earlier value in one tap. A try needs the scooter stopped, unlocked and connected as the owner, with nothing else changing or recording. Speed limits, charging, the lock, PIN, proximity unlock, the clock and firmware are never added there. When the scooter accepts a try but keeps its value, Volt says so and stays connected.
- Redesign Experimental controls (formerly Firmware lab) in Volt's style: one card per group, a Try sheet with the current value, radio choices and the result, a single note when something holds every control, and labels that shorten instead of clipping at large text sizes.
- Fit Home to the scooter's model. A new dashboard's Setting tiles swap a control the official app doesn't offer that model for one it does: the GT5 Max gets Top speed and Cruise control, since its official app has no scheduled charging. Tiles you chose yourself never change. GT5 Max drive modes read WALK, DRIVE and SPORT (codes 11, 2 and 3, from an owner's report). A Top speed or other choice the scooter reports outside its model's choices shows "—" instead of a number.
- Remember which model you said a scooter is. Your answer to **Which scooter is this?**, in a report or under **More → My scooters → Edit scooter → Which model is it?**, gives a scooter Volt can't identify yet that model's name (unless you renamed it) and its photo, when Volt has one. It never changes which controls Volt offers, and Volt's own identification always wins. **Change model** picks another; **Not sure** clears it. Backups of Scooters carry it.
- Name and picture the GT5 Max (Model ID 25462), from an owner's compatibility report. Its controls are not verified; they follow the official app's map for its model group as before. Volt now has a report for it, so its owners aren't asked for one.
- While the check runs, **Help Volt support this scooter** asks which scooter it is: search NAVEE's models or type a name. It then offers an optional, skippable step to name each drive mode. You press the scooter's own mode button, and Volt only watches the mode it reports, so it never changes a setting. **Volt couldn't connect to this scooter** asks for the model too. The answers travel in the email as owner notes, so you no longer type the model name into the message.
- Simplify USB pairing into one focused card per step, with expandable help and the same explicit access and capture approvals.
- Recommend Chrome + USB and select it by default when adding a scooter or starting setup; retain routes already chosen in an unfinished setup.
- Keep the backup password field visible above the on-screen keyboard.
- Add **Report an issue** to More, under the version line. It opens your email app with a draft to volt-reports@jjricks.com. The draft holds a prompt for you to fill in, then Volt's version, your Android version and phone model, and the selected scooter's model name. Nothing is sent until you send it.
- Put **Email to Volt** right under **Run again** in the compatibility checker, so a finished check no longer needs a scroll to the preview. It asks **Which scooter is this?** and offers the optional drive-mode step, like the offer for unreported models. Then your email app opens with the report addressed to volt-reports@jjricks.com. The preview's **Share** still opens Android's share sheet.
- Name and picture the ST3 Pro (Model ID 23452, the German-market model in the protocol reference Volt credits). Its controls are not verified, and it still gets the offer to share a compatibility report.
- After connecting to a model Volt has no compatibility report for, offer once to help: one tap runs the 30-second read-only check, and your email app opens with the redacted report addressed to volt-reports@jjricks.com, so you can read it before sending. **Not now** asks again after three days; **Don't ask again** ends the offer for that model. Volt ships the list of models it already has reports for. If a scooter that has never connected fails twice, Volt offers to email its redacted connection details the same way. Both emails ask for the model name, which scooters do not report.
- Recognize the N65i (Model ID 22131): My scooters names it, Home and My scooters show its photo, and its compatibility check accepts its ten-byte settings read. Controls a tester confirmed on a parked N65i (lock, display units, headlight, auto light sensor) no longer say **Untested here**; its other mapped controls still do.
- Replace Volt's old default name **My UT5 MAX** on a scooter identified as another model, the first time it connects. New Shizuku owner captures start with Volt's default name, so the model name replaces it.
- Add **Check for updates** to More → About & permissions. It runs only when you tap it: the first time, Volt explains that GitHub sees your IP address and Volt version, and asks before any request. It then compares the latest GitHub release with this version and offers **Open release page**. Volt never downloads or installs anything itself.
- Connect to the N65i. Its replies leave the leading 0x55 out of their checksum, so Volt discarded every one, including the owner-check success, and stayed on **Checking owner identity** until it timed out. Volt now accepts either checksum style, fixed per connection by the first valid reply, and finishes connecting with the N65i's shorter settings read. The UT5 MAX handshake is unchanged. Connection activity now notes when a reply is ignored.
- Reopen Rides instantly on wide screens with a long ride selected. A finished ride's chart preview now comes from the analysis cache instead of replaying its whole recording on the main thread every time the tab is shown; the first view of a ride after Volt starts is unchanged.

## 1.5.0 — 2026-10-22

- New Android app ID: `com.jjricks.volt` replaces `local.navee.offlinelock`. Android treats this as a different app, so it installs beside Volt 1.4.x instead of updating it. To move: in the old app, More → About & permissions → Your data → Back up & restore → Back up (everything selected). In the new app, choose **Restore Volt backup** on the welcome screen, check your scooters, rides and charges, then uninstall the old app. Grant permissions again; Bluetooth pairing belongs to the phone and carries over.
- The APK is now `volt.apk`. Legacy owner setup files and backup formats still import.
- Make uncertain UT5 MAX standard-charger estimates to full more conservative, while preserving voltage matching and the final taper.
- Prevent shorter percentage-only charge history from overriding uncertain early full-time estimates; preserve existing lower-cutoff calculations.
- Wait for automatic charger identification or manual selection before enabling Charge done by during UT5 MAX charging.
- Increase the Charge done by scheduling buffer from ten to twenty minutes for newly enabled or explicitly updated plans.
- Estimate UT5 MAX standard-charger time below 89% from pack voltage, live current and charge age, instead of the battery percentage alone. The gauge's late catch-up and the charger's first-hour current decline no longer make early estimates, especially for lower limits such as 80%, optimistic.
- Give Charge done by a separate cautious estimate (more charge still needed than the gauge's observed catch-up implies, strongest observed current decline, longest observed finish) before its twenty-minute buffer; the dashboard shows the most likely time.
- Track when charging starts, or infer it from about ten minutes of current readings, without saving anything.
- Record new predictions as estimator `ut5-stock-pack-v6`; preserve earlier recordings and saved predictions.
- Offer to measure a UT5 MAX standard charge for up to ten minutes before arming Charge done by when Volt did not see the charge begin. Volt asks first, stays connected with a notification offering **Schedule now**, then arms the existing one-shot schedule with the charge's inferred age. If measuring cannot finish, it falls back to the cautious plan or explains why nothing was set.
- Keep a charge recording open through a pause that Volt's own charging schedule creates, marking the paused interval in the journal and export (`schedule_pauses`) so a scheduled charge can be recorded end to end.
- Show the last confirmed lock state while disconnected or reconnecting, clearly marked as last known.
- Move the compatibility checker into Developer options; keep unfinished test recovery accessible.
- Put Back up & restore directly in More, replacing the separate owner-only export. New backups include all saved app data by default and clearly identify partial selections.
- Make full backup restore prominent during onboarding. Older owner setup files import through the same restore flow with an explicit scooter-access-only notice.
- Rename Capacity experiment to Usable ride energy; an untouched old default title follows the rename and custom titles are kept. It is not a battery-health test.
- Require 60 battery points across six rides before estimating, set aside rides whose gauge corrected itself mid-ride, and show a 90% range.
- Show charge per full gauge (Ah) and a charging check from completed charge recordings.
- Make Volt's learned range follow the battery gauge: energy per percentage point is learned across the gauge, so low-battery forecasts are no longer optimistic, and levels below any recorded ride are treated cautiously.
- Separate riding efficiency from battery energy; rides with a short recording gap now count toward energy per km.
- Show the learned forecast and its likely range in the Range estimate picker.

## 1.4.8 — 2026-10-21

- Refine the experimental UT5 MAX stock-charger time to full below 89% by matching pack voltage and charging current to earlier completed charges, reducing dependence on the displayed battery percentage.
- Keep the existing estimate from 89% through the final 99% taper, and retain the existing fast-charger and unknown-charger behavior. Incompatible readings fall back to the existing estimate.
- Record new predictions as estimator `ut5-stock-voltage-v4`, while preserving earlier saved predictions and recordings.
- Run public-repository CI on GitHub-hosted workers; keep disposable private workers restricted to private-repository runs.

## 1.4.7 — 2026-10-19

- Edit the Charge done by time without a connected scooter or charger; changes apply when next enabled.
- Editing the time while connected and active recalculates the scooter's start once, using a live estimate or the saved estimate during an unchanged scheduled pause.
- Preserve the manual Charge start time while done-by is active, and restore it when done-by is disabled without enabling scheduled charging.

## 1.4.6 — 2026-10-19

- Add **Charge done by** in Scooter settings and dashboard shortcuts. Use the current charge estimate plus a ten-minute buffer to save a one-time start on the scooter; no ongoing phone connection is needed.
- Remember confirmed charging schedules across reconnects and reconcile fresh completion or override evidence without automatically rearming them.
- Drag along recorded map routes to inspect readings, retaining the gesture when your finger leaves the line; keep off-route panning and two-finger zoom.
- Make the default Capacity experiment card one row shorter while preserving saved dashboard layouts.

## 1.4.5 — 2026-10-18

- Recover charge recording after a sustained, valid change in charging power instead of repeatedly rejecting readings against an old baseline. Isolated spikes remain excluded; discarded readings stay marked as a gap.
- Use one shared Y axis for estimated and observed finish times, and for estimated and actual countdowns. Keep both traces and their individual readouts without duplicate, clipped axis columns.
- Preserve existing recordings and their genuine missing intervals; this update does not invent historical samples.

## 1.4.4 — 2026-10-18

- Return default Reading tiles to Power, Temp and Health for compact screens. Existing saved dashboards keep their selected readings and layout.
- Add Capacity experiment below Record ride in folded and unfolded defaults, and refresh the unfolded website dashboard capture.
- Show Processing while the capacity estimate loads; keep a completed estimate visible during refreshes instead of briefly claiming there is not enough data.

## 1.4.3 — 2026-10-18

- Arrange the unfolded default with the scooter overview beside live readings, followed by full-width lock, settings and ride controls. Preserve the folded geometry and existing custom dashboards; add Charge limit to default readings on both screens.
- Refresh the unfolded website dashboard with the same sample readings and Charge limit; keep the phone screenshot’s three-reading example.
- Support two bounded, disposable CI workers so core and setup checks can run together.

## 1.4.2 — 2026-10-18

- Make switches clearer in both states, with solid Volt red for On and a brighter grey for Off.
- Align Quick access and Setting tiles fill, ripple and clipping corners across screen sizes.
- Inspect ride-map readings only near the route; tap elsewhere to dismiss them.
- Use a transparent lightning bolt for Android notification icons.
- Apply the selected range estimate consistently across current-value widgets and the Stats summary; identify stored scooter range separately.
- Show battery percentage consumed in each ride's overview.

## 1.4.1 — 2026-10-18

- Keep folded and unfolded dashboard positions and sizes independent, including when both use the same column count. Preserve both layouts in saved slots, exports, backups and Undo/Redo.

## 1.4.0 — 2026-10-17

- Add model-aware controls, local compatibility reports, read-only discovery, and guarded developer tests for mapped NAVEE scooters. Physical validation still covers the UT5 MAX only.
- Unify backup and restore with selectable categories, profile mapping, and recovery over damaged app data.
- Colour recorded ride routes by speed and optionally end parked rides after a lost connection.
- Start rides immediately with date, time and available drive mode; keep manual renaming afterward.
- Add automatic stock/high-speed charger selection and a provisional UT5 MAX fast-charge reference, with steadier reconnect/taper handling and matching learned curves.
- Require an explicit charger label when recording under Automatic; show which family estimates actually use.
- Fix battery-fill clipping, add lock-slider motion trails and a subtle charging glow, and improve narrow-screen map labels and enlarged-text settings.
- Simplify the homepage and alternate its lock/unlock demo, while retaining the repository link and non-affiliation notice.

- Recheck queued setting changes immediately before transmission, bound stalled guided-test preparation, and preserve outside changes during a test.
- Fix the homepage compatibility link hit area and add disposable CI workers with isolated retries and bounded cleanup.

## 1.3.1 — 2026-10-16

- Simplify **Scooter → Range estimate** to **Scooter's estimate** (default, following drive mode) and **Volt's estimate (learned)**. Remove the duplicate App preferences entry; keep existing quick-access shortcuts working offline.
- Grey out Volt's option until enough usable rides exist, with a brief explanation when tapped. Show a short introduction only on the first usable selection.
- Stream recorded rides for analysis to avoid temporary memory-limit failures. Distinguish checking, insufficient history and read failures; allow an immediate retry after a read error.
- Update an open picker when a ride finishes and guard against stale choices after history or scooter changes. Keep simulation separate from real ride learning.

Install over your existing Volt APK to preserve setup and recordings. Range learning remains local and experimental; the calculation and eligibility thresholds are unchanged. Volt is an independent community project, not affiliated with, endorsed by or sponsored by NAVEE.

## 1.3.0 — 2026-10-16

- Back up and restore complete ride recordings from Rides, including any recorded routes and phone motion, names and notes.
- Preview a safe merge: skip identical rides, retain local conflicts, and explicitly choose the destination scooter for unknown profiles. Validate archives and recover interrupted transfers before reopening the recording stores.
- Add an optional per-scooter Learned range source in App preferences. Use recent usable rides to refine Scooter overview, Your scooter and Battery overview, with the scooter's estimate as the fallback and default.
- Keep learning local to each scooter, rebuild estimates from restored rides, and preserve the original range in raw readings, generic widgets, charts and exports.

Install over your existing Volt APK to preserve setup and recordings. Ride-only backups are unencrypted and may contain private routes; the existing full app backup is password-protected. Learned range is a preliminary estimate for similar riding, not a capacity measurement or guaranteed distance. Volt is an independent community project, not affiliated with, endorsed by or sponsored by NAVEE.

## 1.2.4 — 2026-10-16

- Remove the clock/age footers from Scooter Overview and Reading tiles, including while connecting. Keep cached values muted and the disconnected header's Last seen time.

Install over your existing Volt APK to preserve setup and recordings. Volt is an independent community project, not affiliated with, endorsed by or sponsored by NAVEE.

## 1.2.3 — 2026-10-16

- Refine the UT5 MAX default charging estimate with a second observed finish and paired voltage/power matching for the long 99% stage. Keep the live-current bulk model and rated capacity.
- Let a completed charge learn from a sufficiently long, uninterrupted finishing segment after an earlier gap, without joining missing data or qualifying unreliable earlier intervals.
- Simplify charging on Home by removing estimate-source badges and the charger row; keep power, estimated time and meaningful unavailable/low-current states. Charger selection remains in Scooter settings and charge capture.
- Save the updated estimator version with future predictions; preserve existing recordings.

Install over your existing Volt APK to preserve setup and recordings. Charging estimates remain experimental; another physical charge will help validate the refinement. Volt is an independent community project, not affiliated with, endorsed by or sponsored by NAVEE.

## 1.2.2 — 2026-10-15

- Restore horizontal padding around the Lock bar's Connect and Cancel buttons, including after connection-state changes.

Install over the previous Volt APK to preserve setup and recordings. Volt is an independent community project, not affiliated with, endorsed by or sponsored by NAVEE.

## 1.2.1 — 2026-10-15

- Show charge recordings in one ride-style chart with colored category toggles, fullscreen, pinch zoom, panning and raw-sample inspection.
- Compare saved finish predictions against the observed finish on a shared scale. Show power, battery level and countdown by default, with voltage, current, temperature and saved charging limits available alongside them.
- Keep graph selections, zoom and inspected samples when opening or closing fullscreen; preserve missing-data gaps and older recordings.

Install over the previous Volt APK to preserve setup and recordings. Older charge recordings retain their saved readings; estimates are shown only when recorded. Volt is an independent community project, not affiliated with, endorsed by or sponsored by NAVEE.

## 1.2.0 — 2026-10-15

- Save charging-time predictions, estimator version and estimate sources with charge samples, including while recording in the background.
- Inspect saved charges with Time remaining and Predicted finish graphs, raw sample values and an observed-100% comparison when the finish was captured. Older recordings remain readable without invented historical estimates.
- Give direct-toggle setting tiles a power-button cue instead of a navigation arrow; keep arrows for settings that open another view.

Install over the previous Volt APK to preserve setup and recordings. Saved predictions are available for new charge samples; older recordings remain readable. Volt is an independent community project, not affiliated with, endorsed by or sponsored by NAVEE.

## 1.1.0 — 2026-10-15

- Improve UT5 MAX stock-charger time estimates by default, combining live charging current with an experimental recorded finishing curve. Personal recordings can refine estimates; other models and incompatible charging power keep the live-rate fallback.
- Simplify Home, Scooter, Rides, preferences and setup screens with concise labels, status chips, icons and tooltips. Keep stale-data ages, charging assumptions, capture consent and privacy choices visible where they matter.
- Keep charger choices unchanged when cancelling charge capture, restore Close after cancelling a backup export, and route dashboard layout imports to their own confirmation.
- Keep navigation labels and expanded ride charts usable with larger text sizes.
- Base low-speed ride-chart shading on wheel speed, and limit hatch drawing to the visible chart when zoomed.
- Keep lock-slider thumbs inside short or tall cards, preserve separate edit/resize touch targets in one-row widgets, and wrap reading tiles when their numbers need more space.
- Keep widget content stationary when entering edit mode and use the recorder’s icon-only action when a compact card cannot fit its label.

- New default Home for fresh installs, new scooter profiles and **Restore default widgets**: Scooter overview, Lock bar directly beneath it, Reading tiles, Setting tiles and Ride recorder. Existing dashboards and saved slots are unchanged.
- Add four widgets: **Lock bar** (one-row lock with Connect; connected, the slider is the card), **Scooter overview** (charge, range, photo, charge bar and readings), **Reading tiles** and **Setting tiles** (one to four small cards). Every earlier widget stays available in Add widget, and the All widgets example includes the new ones.
- The Home status line now shows the last-seen time (newer of the last connection and the newest reading); the separate "Last seen" line under the header is gone.
- While editing Home, card content dims behind solid, high-contrast editor handles. The change animates in a short staggered fade (handles first when leaving), reverses smoothly if toggled mid-way and applies instantly with system animations off. A card being moved or resized returns to full opacity until it is dropped.

Install over the previous Volt APK to preserve setup, dashboards, saved slots and recordings. Charging estimates remain experimental. Volt is an independent community project, not affiliated with, endorsed by or sponsored by NAVEE.

## 1.0.1 — 2026-10-15

- Keep an explicitly started charge recording alive through a temporary Bluetooth drop, retrying for up to five minutes while retaining its Stop notification and background protection.
- Resume the same recording only after the same scooter authenticates and supplies fresh charging readings. Mark missing intervals as gaps and exclude them from learned charging curves.
- Respect Stop, explicit Disconnect, profile changes, disabled Bluetooth, revoked permissions and the existing recording limits during recovery. Interrupted app processes do not silently resume capture.
- Retain a small private connection-event history across app restarts and include it in manually exported diagnostics, without recording owner credentials, PINs, Bluetooth addresses or packet contents in that history.

Upgrade over the previous APK to preserve setup and recordings. Recovery is tested with simulated faults; another real overnight charge is needed to validate physical Bluetooth recovery.

## 1.0.0 — 2026-10-14

- Use singular chart windows (1 minute/hour/day) and consistent Odo. abbreviation.
- Fit Trip overview, Readings and Charging values to short-card height, trimming noninteractive padding before reducing values within their existing readable limits. Keep scrolling when all readings cannot fit, and preserve saved card dimensions.
- Keep the first connection/disconnect haptics immediate while suppressing rapid repeats for five seconds after each drop. Preserve the quiet period across focus changes, and never replay suppressed events or delay Bluetooth.
- Include reproducible emulator-only Quick access and connection haptic regression checks in the repository, including dialog focus, preferences, guarded actions and lifecycle cancellation.

- Add a shared Basemap switch below ride maps and in map settings. Online tiles start off for new setups; first use explains the tile provider connection and requires consent. Preserve an existing explicit choice and keep recorded routes usable without tiles.
- Introduce the Volt project homepage and refresh the pairing site's independent-project notice.
- Simplify the README with quick installation steps and move detailed setup/build guidance into dedicated docs. Add clearer privacy, support, contribution and third-party notices.

Install over the previous project APK to preserve setup, dashboards and recordings. Volt is an independent community project, not affiliated with, endorsed by or sponsored by NAVEE. Experimental estimates, physical-device testing limits and preview setup-helper limits remain documented in VERIFICATION.txt.

## 0.13.0 — 2026-10-14

- Rename the app to Volt in the launcher, onboarding, About, notifications and user documentation. Keep the existing Android package, setup, saved data and repository identity.
- Give the dashboard equal left/right margins. Retain five-column layouts on narrow cover screens, fit compact lock text and editor controls, and preserve the saved placement instead of silently switching to two columns.
- Focus and select the ride name and show the keyboard when starting a recording.
- Expand ride maps into a full-screen view with zoom, route-color and reading controls. Keep a separate recenter button and preserve the inspected position and selection when opening or closing the expanded map.
- Make the Location permission row open Volt's Android app settings, with directions to Permissions → Location.
- Match the USB setup and recovery website to Volt's dark/red theme, rounded controls and locally bundled Saira fonts.
- Keep narrow widget captions readable with whole-word labels, abbreviations and accessible icons. Give readings space before hiding the scooter image; keep compact recorder/editor targets separate and restore right-edge grid dots.
- Add optional directional haptics to accepted Quick access on/off commands, respecting app and Android preferences.
- Give the launcher a stylized lightning-bolt icon with adaptive and monochrome variants.
- Add a two-part connection thunk and fading disconnect ticks while Volt is focused. Background events, returning to the app, and simulated connections stay silent; unsupported hardware uses a short fallback.

Install over the previous official APK to keep setup, dashboards and recordings. Physical haptic feel and Galaxy Fold hardware checks remain for owner testing. Existing experimental battery/sensor and setup-capture validation limits still apply; see VERIFICATION.txt.

## 0.12.1 — 2026-10-14

- Add subtle haptic feedback for tabs, choices, switches, favourites, dashboard editing and confirmed lock/ride actions. Haptic feedback is on by default and can be disabled in App preferences; Android’s Touch feedback setting is respected.
- Make committed switch-thumb swipes save their changes, with one callback in the requested direction. Keep canceled gestures and programmatic updates silent.
- Play favourite-star feedback before rebuilding the card, including when removing a chart from Overview.
- Keep the current layout steady when a foldable crease moves between flat and half-open; continue avoiding physical hinges and adapting to actual window-size changes.
- Stop More’s split width from shifting after its entrance animation.
- Make the Live Stats validation fixture robust to continuous live-indicator animation.

Install over the previous official APK to preserve setup, dashboards and recordings. Physical haptic feel, fold transitions and scooter-confirmed lock/ride feedback still need owner testing; see VERIFICATION.txt.

## 0.12.0 — 2026-10-14

- Add password-protected full app backup/restore in Developer options, including private scooter setup, history, rides/routes, charges, preferences, layouts and widget parameters. Offer restore on the welcome screen after clearing storage.
- Pause the connection for a consistent backup, validate imports before confirmation, and recover the previous data if replacement is interrupted.
- Synchronize restore recovery markers and directory changes to strengthen interruption recovery; reclaim temporary encrypted exports left by an interrupted save.
- Export/import shareable dashboard JSON with the active arrangement, all three slots, column choice and widget options. Add an explicit Developer action to replace slot 3 with the latest All widgets example.

- Add consistent, short motion across tabs, submenus, dialogs, settings rebuilds and ride maps. Respect disabled Android animations.
- Prevent scrollbar flashes and scroll-position jumps when restoring pages or changing settings; bring in map content and tiles smoothly.
- Drag Stats favourites by their grab handles from an icon-only Reorder control. Save on drop, restore the starting order on cancellation, and retain screen-reader Move up/Move down actions.
- Add an **All widgets** example to saved-layout slot 3 only when absent for that scooter. Include all 15 widget types with useful options and one- through six-column arrangements. Preserve the active dashboard, current column choice, existing slot 3 and other saved layouts; restoring the example remains optional.
- Keep the active dashboard safe if optional example creation fails. Give Quick access a full-width row in the narrow example so enlarged text does not strand it beside an empty space.

Install over the previous official APK to keep setup and recordings. Existing battery-estimate and physical sensor/recording limitations remain; see VERIFICATION.txt for current validation evidence.

## 0.11.0 — 2026-10-14

- Use the owner's ten-widget Home preset and saved options for new/reset dashboards, including maximum-speed and scheduled-charging Quick access. The five-column choice adapts to narrower screens, and one- through four-column defaults keep Connect near the top. Captured widget positions, sizes and options are preserved, along with existing current layouts and saved slots.
- Browse searchable, categorized charts for every recorded numerical parameter. One compact 2-minute-through-All picker controls live and expanded graphs; star and manually reorder a mix of reading/chart favourites in Overview.
- Keep the Readings count and spacing while removing its redundant favourites filter; enlarge and bring the Live Stats pulse closer to its title.
- Inspect original ride values and sample timestamps by tapping or sliding inline/fullscreen graphs. Expanded views load the complete committed recording asynchronously, preserve short peaks, support multiple sensor overlays and independent scales, and retain a usable plot with large text. Add ride renaming, retry after failed detail loads and clear fractional time ticks.
- Move basemap/provider controls into App preferences, enable USGS for new preferences, add elevation route coloring, and reject implausible altitude excursions from derived metrics while retaining raw data. New rides use positive current as draw; existing journals retain their original interpretation.
- Add breathing room between the ride map and its controls.
- Add explicit charge recording with manual Stock/High-speed labels, qualified per-scooter charging curves and versioned JSON export. Learned ETA requires matching live power at the current battery level; otherwise use the current-rate estimate.
- Protect active ride/charge recording with immediate ongoing Stop notifications, appropriate foreground-service behavior and a renewed bounded CPU wake lock. Add Background recording guidance and Android settings shortcuts; the ordinary ten-second idle timeout remains.
- Let narrow Quick access rails use three rows, with separate Move/Options targets on the thinnest cards. Keep new widgets as drafts until Save, improve shortcut reordering/removal and duplicate animation, compact empty grid rows, and allow a wide recorder to use one row.
- Refine battery/health/capacity cards, fit short reading values with their units, separate charging power/time tiles, and distinguish draw/charge power colors. Chart widgets gain Relative/Absolute scaling with bounded percentage axes and responsive time labels.
- Group distance/speed, temperature and time format under Units, add preference spacing, simplify charging labels and use confirmed-state sliding scooter selectors with wrapping light patterns.
- Follow the current riding mode's supported firmware range estimator while stationary, with fixed choices available and bounded attempts across reconnects. Range remains scooter-reported; automatic Boost detection is not included.

Upgrade over the previous official APK to retain setup and recordings. Battery capacity, charging ETA and phone-sensor results remain estimates. Real overnight Bluetooth/charger behavior, physical range/control readback and outdoor sensor accuracy still need owner testing. See VERIFICATION.txt for the tested integration scope and separate release checks.

## 0.10.0 — 2026-10-13

- Limit speed-gauge scale summaries and editor prefills to tenths while preserving exact stored bounds on unchanged saves.

- Clean up floating-point noise in gauge scale editors while preserving the exact stored limit when saving unchanged text.

- Add a Celsius/Fahrenheit selector under App preferences for temperature readings, dashboard gauges and charts, and ride summaries. Stored telemetry and gauge bounds remain in Celsius; Fahrenheit scale edits convert back before saving.
- Let My Scooter values grow larger while retaining width/height fitting, the readable minimum and long-value wrapping.

- Modernize the shared widget-readings picker with a themed searchable sheet, icons, selection counter and pinned Save button. Preserve filtered selections and enforce the 12-reading limit. My Scooter uses compact captions for all parameters while retaining full names for search, accessibility and tooltips.

- Make the battery overview's empty capacity easier to distinguish with a dim red track, faint diagonal texture and thin outline; stale and unavailable readings remain neutral grey.

- Configure two to eight ordered Quick access items, including direct scooter toggles and settings panels that open above Home. Preserve existing safety/readback checks and legacy shortcut defaults; saved layouts and duplicates retain the selection. Fit compact buttons to the available height without clipping labels.
- Enlarge editor icons, keep move/options/duplicate together at the left, remove the redundant Size row from options, and double the subtle wiggle speed. Saving a new off-screen widget scrolls it into view with a short white outline; user touch cancels the cue and reduced motion uses immediate positioning.

- Use rounded sliding selectors for appearance, distance/speed units and time format.
- Add a hidden Developer options menu, unlocked by seven taps on the About version. Its temporary simulated connection supplies sample readings, local-only controls and chart previews, visibly marked with an Exit banner. Real Bluetooth is closed, recording is blocked and samples never enter stored telemetry or battery history.
- Animate entering/leaving dashboard edit mode with a short slide and control pop, plus a barely visible widget wiggle that pauses during gestures, focus loss and disabled system animations.

- Refresh Gauge with a 270° ring, a larger centered value and unit, and padded minimum/maximum labels; existing value formatting and stale-state presentation are retained.
- Put each widget's move grip, options gear, copy and red trash icons in one row of matching squares. Keep glyphs centered as targets shrink from 52 to 36 dp on minimum-width cards; the resize corner stays 48 dp. Widget options have only a fixed ✓ Save button below the settings and retain the top-right X.
- Add grouped Undo/Redo arrows immediately left of Done, with the action row below the scooter name on compact screens. Keep up to 40 layout changes in the current editing session, preserving widget settings; new grid edits clear Redo, while no-op, cancelled and settings-only changes leave it intact. Defaults and slot restores start fresh history.
- Offer up to six dashboard columns on sufficiently wide screens, with a rounded scrolling selector and animated selection highlight. Adaptive keeps its existing one- through four-column behavior. New five/six-column arrangements derive from saved layouts; existing arrangements and slots remain intact.
- Give each widget a minimum usable width based on its contents and text size. Saved slots support all one- through six-column arrangements, and a six-column grid allows a four/two split between the scooter and lock cards.
- Show Disconnect in the connected lock widget by default, with a widget option to hide it. Connect and Cancel remain available when needed.
- Add short size/position transitions during dashboard editing, a 200 ms soft-puff removal effect, confirmed-state lock movement, page transitions and button feedback, respecting Android's disabled-animation setting. Lock commands and confirmation requirements are unchanged.

Install over the preceding official APK to retain profiles, dashboards, saved slots and recordings. The new sample-data mode never adds fake recordings or battery history. Existing battery-capacity and phone-sensor estimates remain experimental; see VERIFICATION.txt for executed checks and physical validation limits.

## 0.9.1 — 2026-10-13

- Dashboard lock controls adapt to the width of their card: narrow cards stack the lock heading and keep the action within reach. Short slider labels preserve the full-height touch target, and Connecting no longer repeats the scooter name already shown in Home's header.
- The battery graphic yields height to wrapped readings using its readable text size. Single-column readings put icons beside values, avoiding unnecessary inner scrolling in narrow and large-text charging cards.
- Dashboard row height follows Android's font-size preference as well as the app's text setting, preserving saved grid positions while allowing larger text more space.
- A fully opened fold crease no longer reserves an empty upper pane. Tabletop folds and physical hinges still split the layout; Home, setup and ride details use the same separation rule.
- Center the dashboard editor's move and options icons in their translucent squares while retaining their 48 dp touch targets.
- Add emulator-only dashboard layout regressions for different window widths, larger text, visible control bounds and OEM fold-state changes.

## 0.9.0 — 2026-10-13

### Dashboard and setup

- Home's Layout sheet has three named save slots per scooter. Each keeps the widget list and options, column choice, and folded/unfolded arrangements together. Restore defaults and current edits preserve the saved slots; replacing or restoring a slot asks for confirmation. Restoring a slot also restores the phone's shared column choice without replacing other scooters' widgets.
- **First run, unfolded:** on foldables' inner screens and other wide screens, the numbered setup steps show a step list beside the current step: the scooter, how long setup takes and each step marked done, current, to do or not needed. On a book-posture hinge the split falls on the fold. Welcome, the path choice and the finish screen stay single-column.
- Optional **Use Shizuku on this phone** owner capture on Android 11+, using shell-backed Shizuku 13+. Debugging permission, capture consent and importing the result are separate steps. The captured identity stays in memory until import.
- A **Chrome/USB testing preview** at [pairscooter.jjricks.com](https://pairscooter.jjricks.com/) guides computer users through USB authorization, a short capture, and saving their private setup. The dedicated origin keeps its USB permission and recovery state separate from the rest of the website. Capture processing stays in the browser; there is no account sign-in or capture upload service.
- Both new helpers restore logging and stop NAVEE before making a captured result available. Bounded commands, a shared session lock and interruption recovery protect cleanup. The browser forgets USB permission after safely completed sessions where the browser supports it. Existing computer capture, backup import and manual identity entry remain available.

### Upgrade and limits

Install over the preceding official APK to keep profiles, dashboards, saved slots and recordings. Initial capture still requires Android debugging access and an already signed-in, owner-bound NAVEE app. Everyday Bluetooth control remains local.

The browser helper remains a testing preview in this app release. A successful real-scooter capture through either new helper is still awaiting validation; the available phone checks were performed with the scooter out of range. Browser capture supports the main personal Android user only. See VERIFICATION.txt and the setup documentation for test scope and recovery limitations. Existing experimental battery and phone-sensor estimates retain their limitations.

## 0.8.0 — 2026-10-13

A full visual redesign of every screen. Bluetooth, commands, storage, exports and preference keys are unchanged.

### Design

- New "Volt" look: near-black screens, soft rounded cards, Saira type (bundled, SIL Open Font License) and a single scooter-red accent. Red marks things you can change or act on; grey cards are information; last-seen values stay grey with their times. A light variant follows the Appearance setting.
- Floating tab bar with Scooter as the raised centre button. Tabs are now Home, Stats, Scooter, Rides, More.
- Dialogs use rounded Volt surfaces with red buttons; pickers open as bottom sheets.

### Screens

- **First run:** a step-by-step onboarding replaces the first-time setup guide's wall of text: choose capture, backup file or 12-character identity; a checklist; USB debugging; running the capture helper (with Copy); import; Nearby devices; connecting; done. The tab bar stays hidden until it is finished. **More › My scooters › Run setup again** reopens it.
- **Home:** one header with the connection line, scooter name and edit pencil. The lock is a full-width bar: tap anywhere on it or slide it (locked: knob on the right; unlocked: red knob on the left). Ride recording is its own card. The battery overview is a tall red cell; while charging, a charging card with the scooter photo, a progress line to the charge limit, time remaining and battery-side power appears above the widgets. All widgets, sheets and edit controls are restyled.
- **Scooter:** one scrolling page grouped into Ride, Battery, Lights, Dashboard & sound, Security and Profile, with search. Top speed is a red card with the scooter's four choices shown in your units (the scooter still receives km/h). Choices open a sheet that waits for the scooter's confirmation. Line and star lights have their own page.
- **Stats:** red section tabs, battery overview with favourites, readings with tappable favourite stars, charts and diagnostics.
- **Rides:** record card, route thumbnails in history, a live recording card with Notes and End, full-page ride details, route map and comparison, and bottom-sheet sensor and map-source pickers.
- **More:** profile card and grouped menu, restyled submenus, and a two-step handlebar PIN entry (enter, then repeat).

### Layout and upgrade

- Expanded screens use two-column Scooter settings and separate menu/detail panes in Stats, Rides and More. More preserves both scroll positions during quick returns and resets them after one minute away from the tab or app.
- Chart choices wrap as whole labels, and diagnostic actions stack when needed. Narrow battery-health cards keep their percentage visible, and the raised Scooter tab has one accessible target.
- Setup correctly opens the scooter chooser after scanning has begun, and a newly typed owner identity replaces any abandoned pending import and name. Quick access commands remain disabled while editing the dashboard.
- Install over the previous official APK to retain profiles, layouts and rides. Initial owner capture still requires ADB; battery and phone-sensor estimates remain experimental. See VERIFICATION.txt for validation scope.

## 0.7.2 — 2026-10-12

- More keeps its submenu for one minute away from the tab or app, then returns to the settings index. Active use, quick returns, rebuilds and explicit Security shortcuts retain their expected behavior.
- USGS Topo is the default optional U.S. basemap. Choose USGS, OpenStreetMap or a custom provider directly in a ride preview or Phone sensors settings.
- Route maps support smooth pinch-to-zoom and two-finger panning, anchored under your fingers. Zoom buttons and Fit remain available.
- Map errors stay out of the imagery: policy-block images are rejected, denied providers pause requests, and local routes remain visible. Old unverified tile caches refresh before reuse. USGS close-ups enlarge available tiles instead of requesting unsupported levels.

## 0.7.1 — 2026-10-12

- Cached readings now say “Last seen.” Dates outside today use a compact uppercase day/month before the time, such as `05 MAR · 13:05`, retaining the selected system/12/24-hour clock.
- Compact connection status and dashboard timestamps shrink to stay on one line when space is tight.

## 0.7.0 — 2026-10-12

This release brings the phone recording and customizable dashboard work together after the 0.4.1 release.

### Added

- A customizable Home with 15 widget types, drag and resize previews, per-scooter layouts, and separate folded/unfolded defaults. Existing custom dashboards remain intact.
- Last-known readings and settings, visibly stale after disconnect, plus connected telemetry history with a 50 MiB storage budget.
- Optional GPS, barometer and experimental motion journals for explicit ride recordings. Saved rides include local routes, estimated grade, acceleration views, and CSV/JSON/GPX exports.
- Optional OpenStreetMap tiles and a replaceable HTTPS map provider. Bluetooth control and recording continue to work without map services.
- Battery and charging widgets with reported health, live power, and a rough remaining charging time. The capacity experiment compares qualified recorded energy with percentage drop and advertised capacity.
- Scooter naming, a UT5 MAX image, concise connection status, system/12/24-hour clocks, and a two-minute live chart window.

### Improved

- Grouped scooter settings with all lighting controls together, clearer icons, less repeated text, consistent units, and search fields that release keyboard focus.
- Responsive cards, accessible edit controls, smooth layout previews, and matching card dimensions inside and outside edit mode.
- Correct Tail light naming for the verified command; automatic headlight behavior remains firmware-controlled.
- Reopening a widget search no longer inherits the previous keyboard session and immediately loses focus; normal Back and outside-tap dismissal are retained.
- Diagnostic exports and map requests identify the installed application version instead of a stale hardcoded version.

### Upgrade and limits

Install the signed release APK over the existing official build. The package and signing identity are unchanged; no uninstall or data reset is needed. Builds signed with a contributor's local key require that same key for in-place updates.

This is an independent app for an already activated, owner-bound UT5 MAX. Initial owner setup still uses the documented ADB capture/import flow. No owner credentials are included in the release.

Battery capacity, charging time, hill grade and pocket motion are estimates. Reported battery health is not a measured capacity test. Outdoor sensor accuracy and the newest features on Samsung still need field validation. The observed Boost toggles did not reveal a reliable active-state flag in the captured status packets; automatic Boost detection is not included. See VERIFICATION.txt for the exact validation scope.

## 0.4.1 — 2026-10-12

Initial repository release with offline Bluetooth controls, owner setup, telemetry, ride recording, portable builds, and the confirmed Samsung startup fix.
