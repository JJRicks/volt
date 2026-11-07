# Everything (JSON) ride export

One UTF-8 JSON file with everything Volt recorded for one ride: the scooter sensor journal, the ride's analysis, the
phone journal (GPS fixes, pressure, telemetry, motion summaries, gaps), every motion sample, the phone track, the
current-direction setting and its changes, the time zone when the ride recorded one, and the clock pairs a reader needs
to put all of it on one timeline. It is a superset of the other ride exports, not a replacement: they stay as they are.

- File name: `ride-<first 8 of id>-everything.json`, type `application/json`.
- Written by `RideExportFiles.payload` through `RideExportEverything.write`; tested by
  `tests/l10n/RideExportEverythingTest.java`.
- The file holds GPS positions and notes, so Volt keeps no copy: it is written into a staging folder of its own in the
  app cache (`cache/ride-export-<ms>/`) and removed as soon as it has been copied for the Save dialog or the
  several-files folder, or the save failed. A folder a killed save left behind is removed by the next ride export
  once it is a day old.
- Nothing is resampled or rounded. Each value stays on the clock it was recorded with, and numbers are written exactly
  as the recording files hold them. The test rebuilds the scooter journal, the phone journal and the motion file from
  the JSON, byte for byte (less the identifiers below).
- English and locale-independent, like every ride file.

## Clocks

Volt stamps rows with two phone clocks:

- `wall_ms`: `System.currentTimeMillis()`, Unix milliseconds UTC. It can jump when the phone's clock is corrected.
- `monotonic_ms`: `SystemClock.elapsedRealtime()`, milliseconds since the phone started. It never jumps, and the scooter
  journal, the phone journal and the motion file share it. Use it as the ride's timeline.

Exceptions, all on the same monotonic base:

- **GPS fixes** keep the fix's own times: `fix_time_utc_ms` is `Location.getTime()` (satellite UTC) and
  `fix_monotonic_ms` is the fix's `getElapsedRealtimeNanos() / 1e6`, not when Volt received it. A straight-line fit of
  these pairs maps monotonic time to UTC to well under 100 ms.
- **Pressure and motion rows** use the sensor's own timestamp (`SensorEvent.timestamp / 1e6`).
- **Scooter readings** carry `sensor_updated_monotonic_ms`: when the scooter's report arrived, not the frame time.

`clock.anchors` lists the (wall, monotonic) pairs of each journal's START, GAP, RECOVER and STOP rows. Every scooter
frame and every phone row also holds a pair.

## Top level

| Key | Value |
|---|---|
| `format`, `formatVersion` | `"volt-ride-everything"`, `1`. A reader checks both first. A later version may add keys; a reader ignores keys it does not know |
| `rideId` | The ride's id |
| `app.version` | Volt's version name, or `"unknown"` |
| `timeZone.recorded` | The phone's time zone id (`"America/Boise"`) when the ride began, or `null` for rides recorded before Volt kept it |
| `timeZone.atExport` | The phone's time zone id when the file was made: a hint for older rides only |
| `scooter` | The scooter part below, or `null` for a phone recording without a scooter journal |
| `phone` | The phone part below, or `null` when the ride has no phone recording |
| `clock.anchors` | Table: `source` (`scooter` or `phone`), `event`, `wall_ms`, `monotonic_ms` |
| `privacy` | What this file leaves out (below) |

A **table** is `{"columns": [...], "rows": [[...], ...]}`. Each row holds one value per column. An empty field in the
recording is `null`. A field shaped like a JSON number is a number written exactly as recorded, and anything else is a
string.

## `scooter`

| Key | Value |
|---|---|
| `summary` | The ride's analysis, as the Analysis JSON export writes it, with `scooterId` set to `null` |
| `frames` | Table, one row per committed frame (about 1 Hz): `frame`, `wall_ms`, `monotonic_ms`, `sensor_rows` (the frame's row count as recorded, identifiers included) |
| `readings.columns` | `sensor_updated_monotonic_ms`, `numeric`, `text`, `unit`, `source_command`, `first_frame`, `last_frame` |
| `readings.keys` | One array per sensor key (`speed_kmh`, `voltage_v`, `current_a`, `power_w`, `battery_percent`, ...), in first-seen order |
| `events` | Table of every row outside a frame, in journal order: `after_frame` (index of the last frame before it, `-1` before the first), `event`, then the journal's columns from `wall_ms` to `details` |
| `directions` | Table: `monotonic_ms`, `current_direction` for START and each DIRECTION row |
| `uncommittedRows` | Frame rows left out because their frame never committed (a torn write) |
| `unreadableRows` | Rows left out because they are not a schema-1 journal row |

**Readings.** A frame repeats every known reading, so each reading appears once per run of consecutive frames that
repeated it. `first_frame` and `last_frame` are 0-based indexes into `frames.rows`. A reading that changes, or a key
missing from a frame, starts a new row. For a value as measured, use `sensor_updated_monotonic_ms`. A frame's
`sensor_age_ms` is that frame's `monotonic_ms` minus it (empty when the update time is `-1` or later than the frame),
and `recorded_utc` is `wall_ms` as UTC, so neither is stored again. Units are in the key names, for example `_kmh`,
`_v`, `_a`, `_w` and `_percent`.

**Events** are `START` (its `current_direction` is the starting direction, `details` holds
`time_zone=<id>` on newer rides), `GAP`, `HEARTBEAT`, `RECOVER`, `DIRECTION`, `NOTE`, `RENAME` and `STOP` (see
[Recording reference](recording-reference.md#ride-exports-and-journal-format) for the stop reasons).

**Power sign.** The ride charts plot power as `voltage_v × current_a` from the same report (equal
`sensor_updated_monotonic_ms` and `source_command`). The current direction at that moment says what the sign means:
under `POSITIVE_IS_DRAW` positive is draw, under `NEGATIVE_IS_DRAW` negative is draw, and under `UNCONFIRMED` only the
magnitude can be trusted.

## `phone`

| Key | Value |
|---|---|
| `journal.events` | One table per phone journal event, below. Each row starts with `line`, its line number in the phone journal (the header is line 1), so rows of all events can be put back in file order |
| `journal.skippedLines` | Lines left out because they are too short or too long |
| `motion` | Table with the motion file's own header as columns (`monotonic_ms`, `raw_*_mps2`, `linear_*_mps2`, `earth_*_mps2`, `rotation_x/y/z/w`, `accuracy`), plus `skippedLines`; `null` when motion was not recorded |
| `track` | The phone track as the Route CSV and the Scooter and phone JSON exports compute it (`PhoneRideStore.Track.toJson`): accepted fixes joined with scooter readings, distance, ascent and rejected counts; `null` when it could not be read |

| Event | Columns after `line` | Rate |
|---|---|---|
| `START` | `wall_ms`, `monotonic_ms`, `placement` (older rides only) | once |
| `GPS` | `fix_time_utc_ms`, `fix_monotonic_ms`, `latitude`, `longitude`, `accuracy_m`, `speed_mps`, `speed_accuracy_mps`, `altitude_m` (WGS84 ellipsoid), `vertical_accuracy_m` | 1 Hz, every fix, including ones the track rejects |
| `PRESSURE` | `wall_ms`, `sensor_monotonic_ms`, `pressure_hpa` | about 1 Hz |
| `TELEMETRY` | `wall_ms`, `monotonic_ms`, `speed_kmh`, `power_w`, `battery_percent`, and the three readings' update times | 1 Hz |
| `MOTION` | `wall_ms`, `monotonic_ms`, `motion_rows` (motion samples in the last second), `peak_linear_mps2` | 1 Hz |
| `GAP`, `STOP` | `wall_ms`, `monotonic_ms` | |

An event Volt does not write today gets its own table with columns `a` to `j`. A row with more values than its
columns keeps them at the end.

## `privacy`

| Key | Value |
|---|---|
| `withheldKeys` | Scooter readings in this ride that the file leaves out: `scooter_serial`, `battery_serial_raw`, `ride_read_raw` and `fault_history_raw` (`DiagnosticsBundle.WITHHELD`: they identify the scooter or battery, or are raw payload bytes) |
| `withheldRows` | How many reading rows that left out |
| `withheldFields` | Always `scooter.summary.scooterId` and `scooter.events.START.key`: Volt's scooter profile id, left out of both places |

The journal never holds owner keys, PINs or tokens (`RideStore` drops secret-looking keys when it records), and no
Bluetooth address is recorded with a ride. The file does hold the ride's name and notes, and precise GPS positions:
see [Privacy](../PRIVACY.md#exports-are-different) before sharing it.

## Size

Repeated readings are kept once, so the file is much smaller than the raw files together; the motion samples are most
of it. A synthetic one-hour ride at Volt's rates (94 keys at 1 Hz, 1 Hz GPS, pressure and telemetry, 20 Hz motion)
gives about 15 MB from 47 MB of recording files, written in under a second on a laptop. The writer streams: the phone
files are copied row by row, and only the scooter readings (once per change) are held until the scooter part is written.
