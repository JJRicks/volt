# High-speed taper replay data

Evidence for [high-speed voltage/current estimates](../../fast-charge-taper.md) (`ut5-fast-taper-v7`).

- `sessions.csv`: the owner's 13 UT5 MAX charge recordings in order (8 standard, 5 high-speed),
  reduced by `strip_export.py` to charger, stop reason, milliseconds since the recording started,
  battery %, pack volts, signed amps, gap flag and the prediction recorded at the time. No recording
  or scooter IDs, dates, wall clocks or temperatures.
- `expected-v6.csv`: `tests/FastChargeTaperReplay.java` compiled against the previous release
  (b21d3f6, `ut5-stock-pack-v6`) and run on `sessions.csv`. `FastChargeTaperTest` requires every
  standard-charger and reference-charge sample to match it.

`tests/run.sh` runs the replay. Each recording learns only from the recordings before it.
