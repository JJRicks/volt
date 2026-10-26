# GT3 Max report follow-ups (2026-10-26)

An owner identified their scooter as a GT3 Max and supplied a compatibility report plus a diagnostics bundle from Volt 1.8.0 (registry 14). The bundle contains the same compatibility report, so these are one observation, not two independent confirmations. This note records model-level facts only; the original files remain private and are not committed.

## Supported changes

- Advertised model ID **24182**, prefix **2418**, agrees with the serial-derived prefix. The report says standard transport, EUR SKU and ES region. Meter firmware is **2.0.4.6**, controller **0.0.1.7**, BMS **1.0.0.4**. Register the exact advertised ID as GT3 Max and use the already bundled `gt3max` photo. No sibling ID, serial-only identity, conflicting identity or unverified transport gains this identification.
- The report contains no setting-change tests, and the command log has no events. Keep all existing group-C control mappings and evidence levels. This identifies the model; it does not verify its controls.
- Live battery readings include plausible voltage, current and power, but no exported battery temperature. A local reproduction with the actual telemetry decoder shows why: its temperature string includes `°`, which the diagnostics ASCII filter rejected. Permit only a numeric Celsius temperature in that field, retaining the existing identity/payload filters and final saved-secret check.
- The saved check rejected the sign-state query (`0x79`, status 255); the later live diagnostics show a successful two-byte response with temperature sign **0** and current sign **254**. The meaning of 254 is unknown. Keep it as raw diagnostic evidence, but do not let any value outside the known 0/1 domain overwrite an established sign. With no known sign, retain the existing inline-sign interpretation. Preserve sensor timestamps and ordering of sign responses.

## Evidence still needed

- The reported maximum-speed field is **32**, and the custom lower-cap field is **0**. These are stored readings, not proof of actual speed or of a successful speed-setting change. Do not change speed options, command selection or region behavior from this report. A capture of the official app lowering its supported speed setting, paired with before/after Volt readbacks, would distinguish command choice from readback interpretation.
- A TCS-looking field does not prove the control works. Do not promote it to verified or alter its availability without an owner-observed test.
- A connection-log timeout occurred with status about 12 seconds old while another frame had arrived about 0.4 seconds earlier. Authentication resumed about three seconds later. These events are not model-tagged, and raw packets are absent: they do not establish why status stopped arriving or justify weakening status freshness checks.
- The saved rejection and later success of `0x79` do not establish that the opcode is permanently unsupported. More packet-level evidence is needed to interpret the unusual sign flag.

## Local regression coverage

- Exact model identification/photo, existing owner-selected photo, sibling/identity/transport guards, and unchanged ordinary control rules across the catalog.
- Positive, zero and negative temperatures survive diagnostics with their original age and source; arbitrary Unicode, newline, serial and owner-key values remain rejected.
- All unknown sign bytes (2–255) preserve the last known sign while staying visible as diagnostics; old flags cannot overwrite newer responses, later valid flags still work, inline negatives still win and clearing telemetry removes session state.

No scooter commands were sent for these changes, and no external implementation code was copied.
