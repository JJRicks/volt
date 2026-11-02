# Roadmap

What's planned for Volt and roughly in what order. It's a plan, not a promise: the order can change and items can be dropped. Suggest something or offer help in [Issues](https://github.com/JJRicks/volt/issues).

## Languages

Volt is English-only today. Its text is written directly into the code, so translating it starts with a rebuild of how the app stores text.

1. **Move every screen's text into Android string resources**, one screen at a time. English stays exactly the same. Afterwards, Volt follows the phone's language.
2. **Latin-script languages.** Volt's font already covers them.
   - First: Spanish, German, French, Italian, Portuguese, Dutch, Polish, Swedish and Indonesian.
   - Then more European languages as reviewers come forward: Danish, Norwegian, Finnish, Czech, Slovak, Romanian, Hungarian, Croatian and Turkish.
3. **Other scripts, left to right:** Russian, Ukrainian, Greek, Chinese (Simplified), Hindi and Bengali. These use the phone's own fonts.
4. **Right to left:** Arabic and Urdu. The screens have to be mirrored, so these wait if they break the layout.

Translations start as machine drafts. Text about the lock, riding, alarms and owner setup ships only after a native speaker reviews it. Diagnostics and report emails stay in English so reports remain readable. To review your language, open an issue.

## Investigating

- **Maximum speed on some EU models** reads 0 and can't be changed (GT5 Max, XT5 Pro). Diagnostics reports from these scooters help.
- **GT3 Max Top speed and Kick-start speed** are accepted by the scooter but keep their old values. They show **Unconfirmed here** until a report shows a change that holds.
- **Battery alerts** haven't been through a real charge on a scooter without a charge limit yet.

## Smaller items

- Builds from `main` include their commit in the version name (for example `1.11.1-dev+abc1234`), so they can't be mistaken for a release.
- The hardware check's read-only navigation step needs updating for the current screens.
- More models: reported scooters and photos are added as owners send them.

## Not planned

- Going beyond the speed limits that the scooter's firmware and the official NAVEE app allow. Volt never changes firmware.
