# B17 follow-up: "Imported scooter"

F4 moved the default name "Scooter" to `Profile.displayName()` on every screen that shows it. The other
default name, `MainActivity.IMPORTED_NAME` ("Imported scooter"), still shows in English in a translated
build, on any of those screens once a scooter is imported without a name, and directly in setup's import
summary (`OnboardingScreen.pendingSummary`).

`displayName()` translates only `ScooterProfiles.DEFAULT_NAME`. The name is stored and written to the setup
file in English, and that stored value must stay as it is (`MainActivity.java:3464` compares it).

Follow-up, with B19 (MainActivity): a catalog key for the name, `displayName()` answering it when
`nameDefault` is set and the stored name is `IMPORTED_NAME`, and `pendingSummary` showing the key where it
falls back to that name. The English is the same.
