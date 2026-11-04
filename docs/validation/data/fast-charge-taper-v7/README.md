# High-speed taper replay data

Evidence for [high-speed voltage/current estimates](../../fast-charge-taper.md) (`ut5-fast-taper-v7`,
and `ut5-fast-taper-v8`, which uses the same data).

- `sessions.csv`: the owner's 16 UT5 MAX charge recordings in order (8 standard, 8 high-speed),
  reduced by `strip_export.py` to charger, stop reason, milliseconds since the recording started,
  battery %, pack volts, signed amps, gap flag and the prediction recorded at the time. No recording
  or scooter IDs, dates, wall clocks or temperatures. Sessions 0-12 were committed with v7; sessions
  13-15 (three complete high-speed charges recorded live under v7) were added with v8 by running
  `strip_export.py` on a later export of the same recordings, whose first 13 sessions reproduced the
  earlier file byte for byte.
- `expected-v6.csv`: `tests/FastChargeTaperReplay.java` compiled against b21d3f6 (`ut5-stock-pack-v6`)
  and run on sessions 0-12. `FastChargeTaperTest` requires every standard-charger sample, and the
  reference charge (session 4) until its own taper is established, to match it.
- `expected-v7.csv`: the same replay compiled against f529f43 (`ut5-fast-taper-v7`) on all 16
  sessions. For sessions 13-15 it reproduces the app's live predictions within 0.1 minutes on 1,107 of
  1,167 samples, with the same source on 1,166: the app re-evaluates every frame, the journal keeps
  one sample per 10 s, so a held countdown or taper fit can start a sample apart. `FastChargeTaperTest`
  requires every v8 sample to match it except high-speed samples where the charge's own taper is
  established (`FastChargeTaper.ownTaper`), which must be `FAST_REFERENCE`.

`tests/run.sh` runs the replay. Each recording learns only from the recordings before it.
