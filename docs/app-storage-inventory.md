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
| `map_tile_url`, `map_tile_attribution`, `map_tile_attribution_url` | String | global | Durable | Map provider. MapSource. |
| `stats_favourites`, `stats_chart_favourites` | String set | global | Durable | Favourite readings/charts. |
| `stats_favourite_order` | String | global | Durable | Favourite order. StatsFavouriteOrder. |
| `stats_chart_window` | Integer | global | Durable | Chart time window. |
| `stats_group_<group>`, `stats_chart_group_<group>` | Boolean | global | Durable | Expanded reading/chart categories. |
| `ride_chart_key` | String | global | Durable | Ride chart metric. |
| `ride_chart_series`, `charge_chart_series` | String set | global | Durable | Chart series choices. |
| `last_ride_label` | String | global | Durable | Default name for the next ride. |
| `developer_options` | Boolean | global | Durable | Seven-tap unlock. |
| `range_learned_intro_seen` | Boolean | global | Durable | Learned-range introduction shown. |
| `ride_auto_stop` | Boolean | global | Durable | Conservatively end forgotten rides (added by the ride auto-stop work; registered ahead of it). |
| `ride_auto_export_route_csv`, `ride_auto_export_json` | Boolean | global | Durable | Copy each finished ride's Route CSV, and its ride JSON, to the folder below. Default off. RideAutoExport. A restored choice with no folder on this phone waits until one is chosen. |
| `ride_auto_export_folder` | String (URI) | global | Device | The folder chosen for those copies, through Android's folder picker, with a persisted read and write grant. Android does not carry the grant to another phone, so it is never backed up. Removed, and the grant given up, when both copies are turned off. |
| `ride_auto_export_pending` | String | global (ride UUIDs) | Pending | Finished rides still waiting for a copy, oldest first, comma-separated. Cleared when both copies are turned off. |
| `ride_auto_export_problem` | String | global | Pending | Why rides are waiting: `folder` (no folder, or it or its grant is gone) or `save` (a write failed). Absent when nothing failed. |
| `ride_export_choice` | String | global | Durable | Ride details' Export ride data sheet: the formats checked last time, as ids (`sensor_csv`, `analysis_json`, `route_csv`, `gpx`, `phone_raw`, `motion_raw`, `combined_json`), comma-separated. Absent means Sensor CSV and Analysis JSON. RideExportFiles. |
| `ride_export_folder_hint` | String (URI) | global | Device | The folder a several-file export last went to, used only as the folder picker's starting place. No grant is kept. |
| `dashboard_columns` | Integer | global | Durable | Column choice shared by every scooter's dashboard. DashboardConfig. |
| `dashboard_v1_<pid>` | String (JSON) | per scooter | Durable | Active dashboard: widgets, parameters, notes and a cell for every column count (folded and unfolded layouts live inside each widget). |
| `dashboard_slot_v1_<pid>_<0..2>` | String (JSON) | per scooter | Durable | Saved layout slots. |
| `dashboard_empty_example_v1_<pid>` | Boolean | per scooter | Durable | Slot 3 intentionally empty; stops the example being reseeded. |
| `dashboard_restore_undo_v1_<pid>` | String (JSON) | per scooter | Pending | Undo for a just-restored slot. Short-lived. |
| `riding_layout_v1_<pid>` | String (JSON) | per scooter | Durable | Riding mode layout: the chosen frame and each frame's spot and switch choices that differ from its defaults. RidingConfig. |
| `riding_power_scale_v1_<pid>` | String (JSON) | per scooter | Durable | Riding mode power bar scale: the observed peak draw and regen, rounded up to 250 W (`{"draw":1250,"regen":500}`). RidingConfig. |
| `dashboard_*_unconfigured` | as above | none | Durable | Home's layout while no scooter exists (after **Skip setup**; `<pid>` = `unconfigured`). Not a scooter: backup counts and merges ignore it; only Replace everything restores it. |
| `range_source_<pid>` | String | per scooter | Durable | `scooter`/`learned` range estimate. ScooterService. |
| `range_follow_mode_<pid>` | Boolean | per scooter | Durable | Range follows drive mode. |
| `charge_charger_<pid>` | String | per scooter | Durable | Manual charger choice (`STOCK`/`HIGH_SPEED`). |
| `charge_schedule_owned_<pid>` | String | per scooter | Durable | Confirmed one-shot charging intent, chosen finish time, actual scheduled start, target and expiry. ScooterService / ChargeScheduleController. Simulation and pending operations never persist; restoring this display state never sends or rearms a scooter command. |
| `charge_alerts_<pid>` | String | per scooter | Durable | Battery alert choices: charge alert on/level, low battery alert on/level, Notify me, Alarm sound (`charge=1;at=80;low=0;below=20;notify=1;alarm=0`). ScooterService / ChargeAlertPolicy. Everything off when absent or unreadable. |
| `scooter_menu_v1_<pid>` | String (JSON) | per scooter | Durable | Scooter tab menu layout: section order, each section's row order, hidden sections and rows, and every id seen (`known`). ScooterMenuLayout. Absent or unreadable means the default menu. Hiding never changes a scooter setting. |
| `charge_alert_state_<pid>` | String | per scooter | Device | The charge in progress for battery alerts: whether one is open, its first and best battery level, the level already alerted, when it was last seen, and the low-battery latch. Never backed up: a restored copy could silence the next alert. |
| `maintenance_v1_<pid>` | String (JSON) | per scooter | Durable | Maintenance reminders: the master switch, the forward-only maintenance odometer (last raw odometer value, its field width, the counted distance, the last reset) and up to 30 reminders, each with its task id or own name, distance (km) and/or time interval, notify choice, count start, snooze end and last 20 events. MaintenanceStore / MaintenancePolicy / MaintenanceDistance. Off with no reminders when absent or unreadable; fields a newer version added are kept. A merge onto another scooter drops the raw odometer value, so the next reading re-baselines without adding. Not written yet: no screen uses it. |
| `maintenance_seen_<pid>` | String | per scooter | Device | Which due reminders this phone has already notified about. Never backed up: a restored copy could repeat or silence the next reminder. Registered ahead of the notifications work; not written yet. |
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
| `files/rides/<uuid>.csv` | Durable | START record's profile field | Raw scooter ride journal: samples, names, notes, clocks, gaps, current-direction events. RideStore. |
| `files/rides/<uuid>.csv.repair` | Pending | — | Temporary tail repair. Skipped by backups. |
| `files/rides/<uuid>.csv.mapping` | Pending | — | Temporary profile reassignment; normally only in restore staging. |
| `files/phone-rides/<uuid>-phone.csv` | Durable | ride's | Phone GPS/pressure/telemetry sidecar. PhoneRideStore. |
| `files/phone-rides/<uuid>-motion.csv` | Durable | ride's | Raw motion sidecar. |
| `files/charge-recordings/<uuid>.csv` | Durable | header `charge-v2,<pid>,…` | Complete or interrupted charge journal, including saved estimate samples. ChargeSessionStore. |
| `files/charge-recordings/<uuid>.active` | Durable | header | Unfinished charge; startup recovery appends `end,INTERRUPTED` and renames it to `.csv`. |
| `files/pending-owner-import.json`, `.tmp` | Pending | — | Normalized owner setup waiting to be applied. |
| `files/pending-export-<ms>` | Pending | — | Export waiting for the save picker. |
| `databases/telemetry-history.db` | Durable | rows keyed by `profiles.name` = `<pid>` | SQLite v1: `latest` (last-seen readings per profile), `profiles`, `keys`, `detail`, `minute`, `daily` rollups. Rollups outlive pruned detail, so they are data, not a recomputable cache. Delete-journal mode, 24 MiB maximum. TelemetryHistory. |
| `databases/telemetry-history.db-journal` | Pending | — | Must be absent or empty when closed. |
| `no_backup/connection-events` (+ `.tmp`) | Diagnostic | mixed | Last 200 connection phases/reasons, 128 KiB cap. ConnectionEventLog. |
| `no_backup/compat-tests` (+ `.tmp`) | Device | mixed | Guided setting test records (up to 20 entries, 256 KiB cap): feature, variant, model prefix, meter firmware, baseline/target values, phases and outcomes; no packets, serials, MACs, owner identities or PIN data. Kept on this phone only (D4) and never in a backup. TestJournal. |
| `files/missing-export` | — | — | Placeholder path MainActivity uses when no pending export is recorded; never created. |
| `no_backup/app-data-transfer/` | Pending | — | Restore stages, transaction (`scope.bin`, `additions.bin`, `previous/`, `incoming/`, state marker). |
| `no_backup/ride-transfer/` | Pending | — | Ride-restore stages and transaction. |
| `no_backup/rides-index/<uuid>.sum` (+ `<uuid>.<n>.sum.tmp`) | Cache | ride's | Saved summary and 600-point chart preview of one finished ride, so the first Rides open after a restart does not replay every journal. RideSummaryStore. Keyed by ride id, journal length, journal modified time and `ANALYSIS_VERSION`; a mismatch, a damaged entry or an unfinished journal is ignored and rebuilt from the journal. Entries without a journal are pruned at service start. Never in any backup, ride backup or export. |
| `cache/map-tiles/<sha256>/…` | Cache | — | Downloaded basemap tiles, `.meta`, `.part`, `network.meta`. MapTiles. |
| `cache/charge-exports/charges-*.json` | Cache | one scooter | Developer charge export; removed after 24 h. |
| `cache/ride-<uuid>.csv` | Cache | — | Ride sensor CSV export. |
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
