# App storage inventory

Every durable location Volt writes (baseline 1.3.1, with later additions noted), what it holds, which scooter profile it belongs to, and how a backup treats it. This is the source list for `BackupRegistry` ([unified backups](unified-backup.md)). When code adds a preference key, namespace, file or folder, add it here and to the registry in the same change; `BackupRegistryTest` fails on an unclassified key or root found in the source, and `BackupInventoryValidation` checks live emulator storage.

Paths are relative to the app's private data directory (`/data/data/com.jjricks.volt` or its per-user equivalent). `<pid>` is a scooter profile ID: the random UUID string stored in `profiles.items[].id`. Ride, phone and charge files are named by a recording UUID, written `<uuid>`.

Classes used below:

- **Durable**: user state worth restoring after **Clear storage**.
- **Device**: describes this phone, its window or boot session. It means nothing on another installation.
- **Pending**: a half-finished operation owned by this installation (cleanup journals, staged imports/exports, restore transactions). Never transferred or overwritten.
- **Cache**: disposable and rebuilt or re-downloaded on demand.
- **Diagnostic**: device-local troubleshooting, deliberately kept out of backups.

## Preferences (`shared_prefs/<name>.xml`)

Seven namespaces exist. Volt 1.3.1's full backup copies the first five whole (`AppDataArchive.PREFERENCES`); `pending_documents` and `owner_capture_cleanup` are never read by it.

### `app_settings`

The main settings file. It mixes global options, per-scooter entries (key ends in `<pid>`) and short-lived UI state, so it must be classified key by key.

| Key | Type | Profile | Class | Holds / written by |
|---|---|---|---|---|
| `theme` | String | global | Durable | `dark`/`system`/`light`. MoreScreen; read by every activity. |
| `units` | String | global | Durable | `mi`/`km`/`dual`. |
| `temperature_unit` | String | global | Durable | `c`/`f`. |
| `time_format` | String | global | Durable | `system`/`12h`/`24h`. DisplayTime. |
| `text_scale` | Float | global | Durable (device-sensitive) | In-app text size, 0.5–3. Users may want different values on a fold's two screens; it is still one value today. |
| `stats_density` | String | global | Durable | Live Stats `comfortable`/`dense`. |
| `haptics_enabled` | Boolean | global | Durable | `Haptics.PREFERENCE`. |
| `auto_connect` | Boolean | global | Durable | Connect automatically. |
| `background_connection` | Boolean | global | Durable (device-sensitive) | Stay connected in background. Battery behaviour differs between phones. |
| `keep_screen_awake` | Boolean | global | Durable | While recording. |
| `poll_interval_ms` | Integer | global | Durable | Telemetry refresh. |
| `history_enabled` | Boolean | global | Durable | Save connected history. |
| `phone_gps`, `phone_motion` | Boolean | global | Durable | Ride sidecar recording choices. |
| `phone_placement` | String | global | Durable | Retired: no longer read or written. Older Volt wrote `pocket`/`mounted`; the key stays registered so older backups that hold it restore it. |
| `map_basemap_enabled` | Boolean | global | Durable | Online basemap opt-in (privacy choice). MapPreferences. |
| `map_basemap_explained` | Boolean | global | Durable | First-enable privacy explanation acknowledged. |
| `map_tile_url`, `map_tile_attribution`, `map_tile_attribution_url` | String | global | Durable | Map provider. MapSource. Absent: OpenStreetMap, or USGS Topo when the basemap is on or explained (approved under the old USGS default; MapDefault). Approving the explanation saves the provider it named. |
| `map_usgs_switch_answered` | Boolean | global | Durable | The one-time offer to leave USGS was answered (either way). MapDefault. |
| `stats_favourites`, `stats_chart_favourites` | String set | global | Durable | Favourite readings/charts. |
| `stats_favourite_order` | String | global | Durable | Favourite order. StatsFavouriteOrder. |
| `stats_chart_window` | Integer | global | Durable | Chart time window. |
| `stats_group_<group>`, `stats_chart_group_<group>` | Boolean | global | Durable | Expanded reading/chart categories. |
| `ride_chart_key` | String | global | Durable | Ride chart metric. |
| `ride_chart_series`, `charge_chart_series` | String set | global | Durable | Chart series choices. |
| `last_ride_label` | String | global | Durable | Default name for the next ride. |
| `developer_options` | Boolean | global | Durable | Retired: no longer read or written; older installs and backups keep it, harmlessly. |
| `range_learned_intro_seen` | Boolean | global | Durable | Learned-range introduction shown. |
| `ride_auto_stop` | Boolean | global | Durable | Conservatively end forgotten rides (added by the ride auto-stop work; registered ahead of it). |
| `auto_record_background` | Boolean | global | Durable | More › App preferences › Connection & recording: Start recording in the background. Lets Record rides automatically (`auto_record_rides`) also start a ride while Volt is in the background; such a ride has no GPS route. Default off, and absent means off, so older installs and backups start rides automatically only while Volt is on screen. AutoStartPolicy.recordsNow. |
| `auto_record_background_notice` | String | global | Pending | The one-time What's new note about that switch: `due` for an installation updated with Record rides automatically on and the background switch off, `done` once shown or not owed; absent until a process start finds settings in the file. AutoStartPolicy.backgroundNoticeSeed (VoltApp). Belongs to this installation's update, so never backed up. |
| `ride_auto_export_json` | Boolean | global | Durable | Auto-save rides: save each finished ride's Everything in one JSON to the folder below. Default off. RideAutoExport. A restored choice with no folder on this phone waits until one is chosen. |
| `ride_auto_export_route_csv` | Boolean | global | Durable | Retired Route CSV switch from builds before 1.12.0, still read: on means auto-save is on. Moved onto `ride_auto_export_json` at app start, written off whenever the switch is set, and stored off in every backup. |
| `ride_auto_export_folder` | String (URI) | global | Device | The folder chosen for those copies, through Android's folder picker, with a persisted read and write grant. Android does not carry the grant to another phone, so it is never backed up. Removed, and the grant given up, when auto-save is turned off. |
| `ride_auto_export_pending` | String | global (ride UUIDs) | Pending | Finished rides still waiting for a copy, oldest first, comma-separated. Cleared when auto-save is turned off. |
| `ride_auto_export_problem` | String | global | Pending | Why rides are waiting: `folder` (no folder, or it or its grant is gone) or `save` (a write failed). Absent when nothing failed. |
| `update_auto_check` | Boolean | global | Durable | More › Updates: the opt-in automatic update check, at most once a day. Default off. UpdateCheckJob. Runs only with `update_check_consented` also set; a restored choice is scheduled the next time Volt's process starts. |
| `update_auto_last` | Long | global | Device | Wall-clock time of the last finished automatic check; the next one waits a day from it (UpdateSchedule). |
| `update_auto_told` | String | global | Device | The newest version the automatic check announced, or a manual check showed, so each release is announced once. |
| `ride_export_choice` | String | global | Durable | Ride details' Export ride data sheet: the formats checked last time, as ids (`everything_json`, `sensor_csv`, `analysis_json`, `route_csv`, `gpx`, `phone_raw`, `motion_raw`, `combined_json`), comma-separated, plus `everything_json_off` when Everything was left unchecked. Absent means Everything only. A value with neither `everything_json` nor `everything_json_off` was stored before Everything existed and gains it once, keeping its other formats; older versions ignore both ids. RideExportFiles. |
| `ride_export_folder_hint` | String (URI) | global | Device | The folder a several-file export last went to, used only as the folder picker's starting place. No grant is kept. |
| `dashboard_columns` | Integer | global | Durable | Column choice shared by every scooter's dashboard. DashboardConfig. |
| `dashboard_v1_<pid>` | String (JSON) | per scooter | Durable | Active dashboard: widgets, parameters, notes and a cell for every column count (folded and unfolded layouts live inside each widget). |
| `dashboard_slot_v1_<pid>_<0..2>` | String (JSON) | per scooter | Durable | Saved layout slots. |
| `dashboard_empty_example_v1_<pid>` | Boolean | per scooter | Durable | Slot 3 intentionally empty; stops the example being reseeded. |
| `dashboard_restore_undo_v1_<pid>` | String (JSON) | per scooter | Pending | Undo for a just-restored slot. Short-lived. |
| `riding_layout_v1_<pid>` | String (JSON) | per scooter | Durable | Riding mode layout: the chosen frame and each frame's spot and switch choices that differ from its defaults. RidingConfig. |
| `riding_power_scale_v1_<pid>` | String (JSON) | per scooter | Durable | Riding mode power bar scale: the observed peak draw and regen, rounded up to 250 W (`{"draw":1250,"regen":500}`). RidingConfig. |
| `riding_clock_seconds` | Boolean | global | Durable | Riding mode tune menu: the Clock reading shows seconds. Default off; absent (a 1.13.0 backup) or anything but true reads as off. RidingClockSeconds. |
| `dashboard_*_unconfigured` | as above | none | Durable | Home's layout while no scooter exists (after **Skip setup**; `<pid>` = `unconfigured`). Not a scooter: backup counts and merges ignore it; only Replace everything restores it. |
| `range_source_<pid>` | String | per scooter | Durable | `scooter`/`learned` range estimate. ScooterService. |
| `range_follow_mode_<pid>` | Boolean | per scooter | Durable | Range follows drive mode. |
| `charge_time_reading_<pid>` | String | per scooter | Durable | Charging view time tile left on `limit` or `full` (ChargeTimeReadings). |
| `charge_charger_<pid>` | String | per scooter | Durable | Manual charger choice (`STOCK`/`HIGH_SPEED`). |
| `charge_auto_record` | Boolean | global | Durable | Record charges automatically. Default off (absent means off); backed up with App preferences. ScooterService / ChargeAutoRecordPolicy. |
| `charge_auto_record_hold` | String | global (profile ids) | Device | Scooters whose charge recording the owner stopped by hand (an automatic one, or any while Record charges automatically is on), space-separated; each is cleared by that scooter's next fresh not-charging reading taken while Volt is not holding the current off itself (a charging schedule, Charge done by or schedule test pause is not an unplug). Never backed up: a restored hold could silence a later charge, and a missing one only lets the next charge record. |
| `charge_schedule_owned_<pid>` | String | per scooter | Durable | Confirmed one-shot charging intent, chosen finish time, actual scheduled start, target and expiry. ScooterService / ChargeScheduleController. Simulation and pending operations never persist; restoring this display state never sends or rearms a scooter command. |
| `charge_alerts_<pid>` | String | per scooter | Durable | Battery alert choices: charge alert on/level, low battery alert on/level, Notify me, Alarm sound (`charge=1;at=80;low=0;below=20;notify=1;alarm=0`). ScooterService / ChargeAlertPolicy. Everything off when absent or unreadable. |
| `scooter_menu_v1_<pid>` | String (JSON) | per scooter | Durable | Scooter tab menu layout: section order, each section's row order, hidden sections and rows, and every id seen (`known`). ScooterMenuLayout. Absent or unreadable means the default menu. Hiding never changes a scooter setting. |
| `charge_alert_state_<pid>` | String | per scooter | Device | The charge in progress for battery alerts: whether one is open, its first and best battery level, the level already alerted, when it was last seen, and the low-battery latch. Never backed up: a restored copy could silence the next alert. |
| `maintenance_v1_<pid>` | String (JSON) | per scooter | Durable | Maintenance reminders: the master switch, the forward-only maintenance odometer (last raw odometer value, its field width, the counted distance, the last reset) and up to 30 reminders, each with its task id or own name, distance (km) and/or time interval, notify choice, count start, snooze end and last 20 events. MaintenanceStore / MaintenancePolicy / MaintenanceDistance. Off with no reminders when absent or unreadable; fields a newer version added are kept. A merge onto another scooter drops the raw odometer value, so the next reading re-baselines without adding. Written by the Maintenance page and read by the notification checks (MaintenanceNotify). |
| `maintenance_seen_<pid>` | String (JSON) | per scooter | Device | Which due cycles this phone has already notified about: per reminder id, the count start (time and distance) and snooze end it notified for. MaintenanceNotify. Never backed up: a restored copy could repeat or silence the next reminder, so a restored phone notifies once for what is due. Unreadable means nothing notified yet; forgetting the scooter removes it. |
| `ride_comparison` | String set | global (ride UUIDs) | Durable | Rides selected for comparison. |
| `ride_selected` | String | global (ride UUID) | Durable | Last opened ride. |
| `notification_prompted` | Boolean | global | Device | Volt already asked for Android's notification permission on this installation. |
| `owner_import_applied` | String | global | Pending | Import ID of the last applied `pending-owner-import.json`; protects that file from being applied twice. |
| `last_page` | String | global | Device | Last tab. |
| `more_section`, `scooter_section`, `stats_section` | String | global | Device | Open sections. |
| `more_wide_section_scroll`, `more_wide_menu_scroll` | String / Integer | global | Device | Wide-window scroll positions; depend on window size. |
| `more_away_elapsed`, `stats_away_elapsed` | Long | global | Device | `SystemClock.elapsedRealtime()` when the tab was left. Meaningless after a reboot or on another phone. |
| `more_away_boot`, `stats_away_boot` | Integer | global | Device | Boot count paired with the above. |
| `stats_query`, `stats_chart_query` | String | global | Device | Search text. |
| `dashboard_editing` | Boolean | global | Device | Home edit mode is open. |
| `scooter_menu_editing` | Boolean | global | Device | The Scooter tab's menu editor is open. ScooterMenuLayout.EDITING. |

No fold or window posture is persisted. `FoldMonitor` reads the live window state, and per-width arrangements are stored inside the dashboard JSON, so they travel with Dashboards.

### `profiles`

| Key | Type | Class | Holds |
|---|---|---|---|
| `items` | String (JSON array) | Durable, **secret** | Every profile: `id`, `name`, `identity` (12-hex owner credential), `role`, `mac`, `serial`, `lastConnected`. ScooterProfiles. Compatibility work adds `pid_advertised`, `pid_advertised_wall`, `name_default` and `fw_*` fields; restores keep unknown fields and type-check the known ones. |
| `active` | String | Durable | Selected profile ID. |
| `legacy_migrated` | Boolean | Durable | The legacy `scooter` setup was migrated, or deliberately not. Stops a second migration creating a duplicate profile. |

### `scooter` (legacy, pre-profiles)

`identity` (**secret**), `role`, `mac`, `serial`, `last_connected_mac`. Read only by the one-time migration in `ScooterProfiles` and cleared when its profile is forgotten. Durable for exact restores; never merged.

### `control_evidence`

One String per `<pid>`: JSON of the last command results. Since Volt 1.1.0 (`c7b695b`) nothing writes or reads it. Existing installations keep a dormant copy, which the 1.3.1 full backup transfers. It never authorizes a control.

### `onboarding` (`OnboardingScreen.PREFS`)

| Key | Class | Holds |
|---|---|---|
| `onboarding_done` | Durable | Setup finished. |
| `rerun`, `step`, `path`, `profile_id`, `created_profile_id`, `pending_name`, `import_failed`, `nearby_asked`, `connect_requested`, `connect_tried`, `tick_<n>` | Pending | Wizard position and choices for a setup in progress on this installation. `nearby_asked` relates to an Android permission prompt. |

### `pending_documents` (Pending)

`path`, `name`, `mime`, `ready`, `picker`: one export waiting for the document picker (MainActivity `prepareSave`). Its file is `files/pending-export-<ms>`.

### `owner_capture_cleanup` (Pending)

`pending`, `token`, `original`: the Shizuku owner-capture cleanup journal (ShizukuCaptureClient). It records NAVEE logging state that must be restored on this phone. Never exported, cleared or replaced.

## Files

| Path | Class | Profile | Holds / notes |
|---|---|---|---|
| `files/rides/<uuid>.csv` | Durable | START record's profile field | Raw scooter ride journal: samples, names, notes, clocks, the time zone at START (newer rides), gaps, current-direction events. RideStore. |
| `files/rides/<uuid>.csv.repair` | Pending | — | Temporary tail repair. Skipped by backups. |
| `files/rides/<uuid>.csv.mapping` | Pending | — | Temporary profile reassignment; normally only in restore staging. |
| `files/phone-rides/<uuid>-phone.csv` | Durable | ride's | Phone GPS/pressure/telemetry sidecar. PhoneRideStore. |
| `files/phone-rides/<uuid>-motion.csv` | Durable | ride's | Raw motion sidecar. |
| `files/charge-recordings/<uuid>.csv` | Durable | header `charge-v2,<pid>,…` | Complete or interrupted charge journal, including saved estimate samples. ChargeSessionStore. The UUID's version digit (character 15) marks a recording Volt started by itself and how its charger label was chosen: `a` chosen, `b` detected, `c` best guess; `4` is a manual recording. Older Volt reads these names unchanged. A recording still marked `c` (detection never settled; a guess it confirms becomes `b`) teaches no charger curve; at the 20-recording limit a kept automatic recording replaces the oldest automatic one only after it is saved (startup finishes that if the process ended in between). |
| `files/charge-recordings/<uuid>.active` | Durable | header | Unfinished charge; startup recovery appends `end,INTERRUPTED` and renames it to `.csv`, then deletes a recovered automatic one too short to keep (under 10 minutes or 5 percentage points); nothing else is deleted for it. A journal whose stop already wrote its `end` line keeps that one line. |
| `files/charge-recordings/<uuid>.active.mapping` | Pending | — | Temporary header rewrite while a running automatic recording follows charger detection to the other label; renamed over the `.active` file. Startup deletes a leftover one. Skipped by backups. |
| `files/pending-owner-import.json`, `.tmp` | Pending | — | Normalized owner setup waiting to be applied. |
| `files/pending-export-<ms>` | Pending | — | Export waiting for the save picker. |
| `databases/telemetry-history.db` | Durable | rows keyed by `profiles.name` = `<pid>` | SQLite v1: `latest` (last-seen readings per profile), `profiles`, `keys`, `detail`, `minute`, `daily` rollups. Rollups outlive pruned detail, so they are data, not a recomputable cache. Delete-journal mode, 24 MiB maximum. TelemetryHistory. |
| `databases/telemetry-history.db-journal` | Pending | — | Must be absent or empty when closed. |
| `no_backup/connection-events` (+ `.tmp`) | Diagnostic | mixed | Last 200 connection phases/reasons, 128 KiB cap. ConnectionEventLog. |
| `no_backup/command-events` (+ `.tmp`) | Diagnostic | mixed | Last commands Volt sent or read and how each ended (typed keys, values, results; `charge_start` and `clock_sync` also keep the time as sent on the wire, the scooter's local seconds, and the UTC offset sent). Version 3 since fuller diagnostics; version 1 files still read, and a development-build version 2 file reads with its clock-sync times unknown (they were UTC seconds there). CommandLog. Never in a backup. |
| `no_backup/charge-evidence/<pid>` (+ `.tmp`) | Diagnostic | file name | Charge evidence for report diagnostics, one file per saved scooter, 256 KiB cap: the last 10 ended schedule intentions plus the open one (mode, deadline and target as armed, and the last change to them with when Volt saw it), 5 schedule tests, a 300-entry charging timeline, 3 dense windows of up to 180 samples, 100 schedule operations, 100 changes Volt did not make, the last 20 watched writes Volt had in flight on that scooter's own link (the only writes that can explain a change, each tagged with the run of that control's readings it was in flight during, and dropped once that run ends) and, per watched control, its last reading and when that value's run of readings began. Values at arming, a start or an end come only from a sample within 30 s after it on the connection that was up at it, with that delay kept; else unknown. At service start every saved scooter's file loads and its schedule record is handed over, so a plan armed before an update is known. Version 3; a development-build version 2 file reads with each run beginning at its last reading, an own write the old 5 s rule still counted tagged with that run, and no plan change known; a development-build version 1 file also reads with its at-moment values unknown. Typed numbers only; no names, serials, MACs, keys or places. Removed when the scooter is forgotten; files of scooters no longer saved are removed at service start. A damaged file reads as empty. ChargeEvidence. Never in a backup. |
| `no_backup/last-crash` (+ `.tmp`) | Diagnostic | — | The last uncaught crash, for report diagnostics only (CrashRecord, under 16 KiB): the exception's class and up to four causes' classes, Volt's own stack frames (class, method and line; at most 24 per exception and 48 in all) and a count of the others, the app version and the time. Never the exception's message or another package's frame. Written only when Volt crashes (a later crash replaces it); read only when a report's diagnostics are made. Never in a backup. |
| `no_backup/compat-tests` (+ `.tmp`) | Device | mixed | Guided setting test records (up to 20 entries, 256 KiB cap): feature, variant, model prefix, meter firmware, baseline/target values, phases and outcomes; no packets, serials, MACs, owner identities or PIN data. Kept on this phone only (D4) and never in a backup. TestJournal. |
| `files/missing-export` | — | — | Placeholder path MainActivity uses when no pending export is recorded; never created. |
| `no_backup/app-data-transfer/` | Pending | — | Restore stages, transaction (`scope.bin`, `additions.bin`, `previous/`, `incoming/`, state marker). |
| `no_backup/ride-transfer/` | Pending | — | Ride-restore stages and transaction. |
| `no_backup/rides-index/<uuid>.sum` (+ `<uuid>.<n>.sum.tmp`) | Cache | ride's | Saved summary and 600-point chart preview of one finished ride, so the first Rides open after a restart does not replay every journal. RideSummaryStore. Keyed by ride id, journal length, journal modified time and `ANALYSIS_VERSION`; a mismatch, a damaged entry or an unfinished journal is ignored and rebuilt from the journal. Entries without a journal are pruned at service start. Never in any backup, ride backup or export. |
| `cache/map-tiles/<sha256>/…` | Cache | — | Downloaded basemap tiles, `.meta`, `.part`, `network.meta`. MapTiles. |
| `cache/charge-exports/charges-*.json` | Cache | one scooter | Developer charge export; removed after 24 h. |
| `cache/ride-<uuid>.csv` | Cache | — | Ride sensor CSV export. |
| `cache/ride-export-<ms>/ride-<yyyy-MM-dd>_<HH-mm>-<id8>-everything.json` | Cache | — | Ride Everything (JSON) export, made before it is saved ([format](ride-export-everything.md)), in a staging folder of its own. Removed as soon as it is copied for the Save dialog or into the several-files folder, or when that fails; a folder left by a killed save is swept by the next ride export once it is a day old. |
| `cache/volt-encrypted-*.voltbackup`, `cache/volt-rides-*.voltrides` | Cache | — | Prepared backup waiting for a destination (`.voltrides` only from older versions); orphans removed at startup (BackupExportCache). |
| `/data/local/tmp/volt-owner-capture.lock/` | outside app storage | — | Shell-owned Shizuku capture lock. Not app data; never touched by backups. |

Volt does not use external app storage, other databases or other private folders. Instrumentation tests use `getExternalFilesDir()` for disposable fixtures only.

## Not stored on disk

- Saved ride summaries (`no_backup/rides-index`) are the one derived ride cache on disk. **Bump `RideSummaryStore.ANALYSIS_VERSION` whenever summary or preview maths changes** (anything that makes a replay of an unchanged journal give a different `RideStore.Summary` or `Point`), so old entries miss and are rebuilt. A restore needs nothing from it: restored journals have new modified times and are replayed.

- Learned range, battery capacity and charge-curve results are computed in memory from rides, history and charge journals (ScooterService caches, cleared on restart). A restore needs no derived-cache import: the next process recomputes them.
- Live readings, command results, connection feedback, simulation and fold posture are in memory only.
- Android owns permission grants, battery-optimization exemptions, notification settings, Bluetooth bonds, USB debugging and Shizuku authorization. No app file holds them.
- Physical scooter settings live in the scooter. History holds observations of them, not the settings.

Android cloud backup and device transfer are disabled (`allowBackup="false"`, `res/xml/data_extraction_rules.xml`).
