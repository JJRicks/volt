# F6 follow-ups: what the language check left open

F6's emulator language check (en-XA, API 36, at 5abd8d8) found two things it could not close on this
branch. Neither changes English. Delete an entry when it is done.

1. **Charge schedule tile toast stays English until B19.** Tapping the tile while disconnected toasts
   "Connect the scooter first" in English. `MainActivity.toggleManualCharge` (`:2130`),
   `scheduleTestAction` (`:2071`) and the time action (`:2138`) pass the controller's reason id straight
   to `toast`. F6 does not touch MainActivity. B19 must show these through the catalog, and its language
   check must tap this tile while disconnected.
2. **Shown-name search cannot be tested in en-XA.** `SearchFold.holdsShown` requires `!Fmt.legacy()`, and
   en-XA resolves to English, so typing a shown pseudo name finds no row there. A scratch shim that made
   the language resolve as shipped found the rows, but that is not product behaviour and no real language
   has run it. The first device check of a real translated language (the test APK) must search the
   shortcuts and readings lists by a translated name.

Recorded elsewhere, listed so it isn't read as missed:

- A slot saved under a shown name keeps that name after a language change. This is decided behaviour;
  see `DashboardConfig.slotNameToSave` and the header of `strings_dashboard.xml`.
- The Charging check value ("12.4 Ah · 3 charges") stays English because G1 rejects the whole plural that
  would replace it. See the header of `strings_dashboard.xml`; T1 could model this form.
