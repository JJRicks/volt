# Roadmap

What's planned for Volt and roughly in what order. It's a plan, not a promise: the order can change and items can be dropped. Suggest something or offer help in [Issues](https://github.com/JJRicks/volt/issues).

## Languages

Volt is English-only today. Its text is written directly into the code, so translating it starts with a rebuild of how the app stores text.

1. **Move every screen's text into Android string resources**, one screen at a time. English stays exactly the same. Afterwards, Volt follows the phone's language.
2. **Latin-script languages.** Volt's font already covers them.
   - First: Spanish, German, French, Italian, Portuguese, Dutch, Polish, Swedish and Indonesian.
   - Then more European languages as reviewers come forward: Danish, Norwegian, Finnish, Czech, Slovak, Romanian, Hungarian, Croatian and Turkish.
3. **Other scripts, left to right,** only if they fit without disrupting the app: Russian, Ukrainian and Greek first, then Chinese (Simplified), Hindi and Bengali. These use the phone's own fonts.
4. **Right to left: on hold.** Arabic and Urdu need every screen mirrored, so they wait for now.

### How translations are made and checked

A council of AI models from different companies translates and reviews the text:

1. **Each model translates on its own.** It gets notes on where each piece of text appears, what it does and how much room it has, plus a glossary of scooter terms that matches the official app.
2. **Automated checks** confirm that numbers, placeholders and plural forms survive and that the text fits.
3. **Where the models agree,** that translation is used. Where they don't, a judge model chooses one and records why.
4. **Text about the lock, riding, alarms and owner setup** needs every model to agree, and its translation back into English must keep the original meaning.

New languages ship as **beta**. On Android 13 and later, you can switch Volt alone back to English in the phone's settings. Native speakers are welcome to spot-check a language, especially its safety text: open an issue. Diagnostics and report emails stay in English, so reports remain readable.

## Features

- **Riding mode.** While a ride is recording, a full-screen view with a few large stats you can read at a glance. You choose the stats and their order.
- **A smaller Record ride widget:** one row tall even when it's narrow, like the Lock bar and the Setting tiles.
- **Tap anywhere on the Record ride widget to start recording,** not just on the button and its label, the way the whole Lock bar is its control.
- **Reading tiles on unfolded screens.** When three readings wrap onto two rows, the last one stretches across the row instead of leaving an empty space.
- **Reorder every menu, not just the dashboard.** For example, put the scooter settings you use most at the top.
- **Maintenance reminders** after a distance or a length of time you choose, for checks such as tire pressure and brakes.

## Investigating

- **Battery alerts** haven't been through a real charge on a scooter without a charge limit yet.
- **Rides that keep recording after the scooter is turned off.** Volt should end and save the ride by itself when the scooter stays off or out of reach for a few minutes, without ending rides over a short Bluetooth drop.
- **Opening Rides can still take a few seconds** with many long recordings, because Volt rereads them before it shows the list. The list should appear right away.
- **Cruise control changes that come back "Not confirmed"** on some scooters. Volt should say more than that, but only once something the scooter reports shows the actual cause.

## Smaller items

- **Phone placement** (Mounted or Pocket) only labels phone-sensor recordings today. Either make it improve how those recordings are read, or remove it.
- The hardware check's read-only navigation step needs updating for the current screens.
- More models: reported scooters and photos are added as owners send them.

## Not planned

- Going beyond the speed limits that the scooter's firmware and the official NAVEE app allow. Volt never changes firmware.
