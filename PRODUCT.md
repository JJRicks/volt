# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Users

The primary users are NAVEE scooter owners on Android. They want to control their scooter without the official app's account, and they want deeper ride and battery data than that app shows. They're comfortable sideloading an APK from GitHub Releases and doing a one-time pairing capture with a computer.

They use Volt in three situations:

- **Parked:** to change settings, arrange the dashboard and make backups.
- **Riding:** with the phone mounted on the handlebars, reading riding mode at a glance.
- **Charging:** following the estimate and the live charging card on the lock screen.

On the web, the same owners arrive at three different moments:

- **Homepage:** deciding whether to install.
- **Setup helper:** doing the one-time pairing capture on a computer.
- **Debugger:** recording what the NAVEE app sends their scooter, to show the developer a problem.

## Product Purpose

Volt is an independent, open-source Android companion for NAVEE electric scooters. It connects over Bluetooth LE to:

- lock and unlock the scooter, and change supported riding, lighting and charging settings;
- show live readings in a dashboard the owner arranges;
- record rides;
- estimate range and charging time.

After the one-time setup, everyday use needs no Volt account, server, computer, ADB or Shizuku.

Success means an owner can install Volt, pair once, and then use it as their daily app to lock, ride, record and charge. The official NAVEE app remains their fallback.

The web surfaces serve the app:

- **Homepage** (volt.jjricks.com, source in `site/`): explains what Volt does, which models it works with, how to download and set it up, and how privacy works, with a short FAQ. Download buttons link to GitHub Releases; the site doesn't host the APK.
- **USB setup helper** (pairscooter.jjricks.com, source in `setup/web/`): a browser-local WebUSB wizard for the one-time owner capture, with a recovery page for interrupted setups.
- **NAVEE debugger** (volt.jjricks.com/debugger/, source in `setup/web/debugger.html`): records what the NAVEE app sends the scooter while the owner shows a problem. The owner checks every line, then sends the recording to Volt's developer.

## Positioning

The owner confirmed three differences from the official NAVEE app:

- **Local and account-free.** Bluetooth control and recording work offline, with no Volt account or server. Data stays on the phone unless the owner exports it or makes a backup. Online map tiles are off until the owner turns them on, after Volt explains what the map provider sees.
- **Yours to arrange.** The dashboard has movable, resizable widgets and saved layouts for folded and unfolded screens. The owner can also arrange the Scooter tab and edit riding mode's layouts.
- **Deeper data.** Rides can record a GPS route, phone sensors and energy readings. Volt learns range from the owner's own rides, estimates charging time, shows a live charging card on the lock screen, and keeps searchable readings with charts.

## Operating Context

- **Getting the app:** an APK from GitHub Releases (JJRicks/volt), sideloaded. It needs Android 8.0 or later with Bluetooth LE. Every release keeps the same app ID and signing key, so it installs over the previous version and keeps the owner's data.
- **One-time setup:** the owner pairs the scooter in the NAVEE app first. The recommended capture uses Chrome or Edge with a USB cable through pairscooter.jjricks.com; `docs/getting-started.md` lists the other routes. An existing setup or full backup can be imported instead. The capture needs the owner's working NAVEE account and Android debugging, with explicit approval. The owner never enters a NAVEE or Google password into Volt.
- **Riding:** riding mode is a full-screen view with big speed, range at the current pace and power, and night vision after sunset.
- **Screens:** foldable phones, folded and unfolded, including narrow cover screens of about 344 dp.
- **Web hosting:** the homepage and setup pages are static files served from the developer's own server.

## Capabilities and Constraints

- **The app itself is native Android** (Java, native views). This record's platform is `web` because Impeccable work here targets the web surfaces. Design work on app screens should load Impeccable's Android guidance.
- **No third-party requests on the web.** The homepage serves its fonts, images and scripts from its own origin. The setup helper uses no CDN scripts, analytics, upload API or server-side processing.
- **Compatibility claims must match the evidence.** The US UT5 MAX is physically verified. Other NAVEE models use mappings taken from the official app and count as untested unless marked otherwise. Don't present Volt as UT5-only, and don't imply that every model has been tested. Feature claims on the web must match `README.md` and `FEATURES.txt`.
- **Safety limits stay put.** Volt never goes beyond the speed limits that the scooter's firmware and the official NAVEE app allow, and it never changes firmware. Experimental controls are marked as experimental.
- **Estimates say they're estimates.** This covers range, charging time, usable capacity, regen on scooters that don't report current direction, and phone motion. Reported battery health isn't an independent capacity test.
- **Languages:** the app is in English plus ten beta translations, all Latin-script: Spanish, Portuguese (Portugal), Portuguese (Brazil), Swedish, German, French, Italian, Dutch, Turkish and Polish. Right-to-left languages are on hold. App layouts must leave room for longer translated text. The web pages are in English.
- **Terminology in use:** riding mode, Record ride, Lock bar, Setting tiles, Quick access, Back up & restore, Basemap, Safe to disconnect.

## Brand Commitments

- **Name:** Volt, previously Navee Offline. The README tagline is "Your scooter. Your connection."
- **Not NAVEE:** Volt is an independent community project and must never present itself as NAVEE. Every surface keeps the non-affiliation notice: Volt is not affiliated with, endorsed by or sponsored by NAVEE. Don't use NAVEE logos or branding. NAVEE's name appears only to identify compatible hardware.
- **Homepage copy the owner approved:**
  - the summary wording "independent, open source Android app";
  - the privacy headline "Nothing leaves your phone", with its short asterisk note about map backgrounds;
  - the link to the GitHub repository.

## Evidence on Hand

- **App screenshots:** `site/src/img/` (WebP). They were captured on an emulator in simulation mode with sample data, and the homepage footer says so. Their capacity values are simulated.
- **Test evidence:** `VERIFICATION.txt` keeps the per-release record, including hardware checks on the owner's UT5 MAX. `FEATURES.txt` lists which controls each model has.
- **Link preview and icons:** `site/src/og.png` and the icons, rendered by `site/tools/`.
- **Not on hand:** testimonials, reviews, user counts, download figures, press and partner logos. Never invent them.

## Product Principles

1. **The phone is the whole product.** After setup, nothing needs an account, a server or a computer. Anything that goes online stays off until the owner turns it on, knowing what it shares.
2. **The owner arranges it.** The owner chooses what's shown, where and in what order, and saved arrangements survive updates.
3. **Show the data, and say what it is.** Readings and history are available in depth, with estimates, beta translations and untested paths labelled as such.
4. **Independent and truthful.** Volt never borrows NAVEE's identity, and every compatibility or feature claim matches tested evidence.

## Accessibility & Inclusion

- **App:**
  - Narrow screens and large text must keep working.
  - At very high text sizes, cutting text off is acceptable where the alternative would break the layout. The owner decides whether widespread cut-offs are acceptable.
  - Controls keep accessible names.
  - Colour-coded states also keep their names, so colour is never the only signal.
  - Animations respect reduced motion.
- **Web:**
  - Decorative animations are hidden from screen readers.
  - Reduced-motion and no-JavaScript fallbacks exist.
  - Animations pause when the page is hidden or off screen.
