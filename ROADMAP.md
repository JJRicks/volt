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

### How translations are made and checked

A council of AI models from different companies translates and reviews the text:

1. **Each model translates on its own.** It gets notes on where each piece of text appears, what it does and how much room it has, plus a glossary of scooter terms that matches the official app.
2. **Automated checks** confirm that numbers, placeholders and plural forms survive and that the text fits.
3. **Where the models agree,** that translation is used. Where they don't, a judge model chooses one and records why.
4. **Text about the lock, riding, alarms and owner setup** needs every model to agree, and its translation back into English must keep the original meaning.

New languages ship as **beta**. On Android 13 and later, you can switch Volt alone back to English in the phone's settings. Native speakers are welcome to spot-check a language, especially its safety text: open an issue. Diagnostics and report emails stay in English, so reports remain readable.

## Investigating

- **Battery alerts** haven't been through a real charge on a scooter without a charge limit yet.

## Smaller items

- Builds from `main` include their commit in the version name (for example `1.11.1-dev+abc1234`), so they can't be mistaken for a release.
- The hardware check's read-only navigation step needs updating for the current screens.
- More models: reported scooters and photos are added as owners send them.

## Not planned

- Going beyond the speed limits that the scooter's firmware and the official NAVEE app allow. Volt never changes firmware.
