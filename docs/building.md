# Build and test Volt

Volt uses **Java and native Android views**, built with Android SDK tools and shell scripts. There is no Gradle project. These instructions are for contributors; to use the app, [install a release APK](getting-started.md).

## Requirements

| Tool | Version / purpose |
| --- | --- |
| JDK | 17 or newer |
| Python | 3 |
| Android SDK platform | **36** |
| Android SDK build-tools | **36.1.0** |
| Android platform-tools | Needed to install on a device or emulator |
| Android emulator and a system image | Optional: for trying your build without a phone. These steps were tested on Android Studio's Medium Phone virtual device, API 36.1 |
| Shell utilities | Bash, Git, `zip` and standard Unix utilities |

Android Studio's SDK Manager installs the SDK packages: **Android 16 (API 36)** platform, build-tools **36.1.0**, platform-tools and, if you want one, the emulator. Android Studio also bundles a suitable JDK.

The optional browser helper has a separate Node 20+/npm build. Node is not needed to build the Android app.

## Build the Android app

```sh
git clone https://github.com/JJRicks/volt.git
cd volt
export ANDROID_SDK_ROOT=/path/to/Android/sdk
export JAVA_HOME=/path/to/jdk
python3 setup/shizuku-dependencies.py --fetch
bash build.sh
```

Replace the SDK and JDK paths with your installed locations. If you leave `ANDROID_SDK_ROOT` unset, `build.sh` uses `ANDROID_HOME` when that is set, and otherwise looks for an SDK with platform 36 in `~/Library/Android/sdk`, `~/Android/Sdk` and `/opt/android-sdk`. If you leave `JAVA_HOME` unset, it looks for a JDK in Android Studio's bundled runtime, then `/usr/libexec/java_home`, then your `PATH`. The `adb` commands below run in your own shell, so they still need `ANDROID_SDK_ROOT` exported; see [Install your build](#install-your-build). The dependency fetch is explicit and verifies pinned hashes; subsequent Android builds verify and use the cached dependencies offline. [Shizuku build details](../setup/SHIZUKU.md#dependencies-and-build) describe the included components.

The build signs the APK and verifies its signature and alignment. Its default output is:

```text
dist/volt.apk
```

### Install your build

The `adb` commands in this guide run in your own shell and read `ANDROID_SDK_ROOT`, so export it now if it isn't already. Use the same SDK the build used. `build.sh` takes `ANDROID_SDK_ROOT` if it's set, then `ANDROID_HOME`, then the first of `~/Library/Android/sdk`, `~/Android/Sdk` and `/opt/android-sdk` that contains `platforms/android-36`.

```sh
export ANDROID_SDK_ROOT=/path/to/Android/sdk
"$ANDROID_SDK_ROOT/platform-tools/adb" version
```

Replace the path with that SDK's folder; skip the `export` line if you already exported it for the build. The second line should print adb's version. Then install:

```sh
"$ANDROID_SDK_ROOT/platform-tools/adb" -s emulator-5554 install -r dist/volt.apk
```

`emulator-5554` is the first running emulator. To install on your own phone instead, use the phone's serial in place of it; that needs the phone connected with USB debugging on.

**A fresh clone creates its own local signing key.** It cannot update a project release signed with a different key. Export a backup before uninstalling or replacing an existing installation. Keep the same private key for future local updates; the project release key is not needed to contribute.

Your build's version name marks it as a development build of the release it's based on, with its commit, such as `1.13.1-dev+55c14f3`, plus `-dirty` when you have uncommitted changes. Don't set `VOLT_RELEASE=1`: that is the maintainers' release build, which refuses uncommitted changes and refuses any shipped language that still shows English.

## Build configuration

| Variable | Default |
| --- | --- |
| `NAVEE_BUILD_DIR` | `.local/build` |
| `NAVEE_KEY_DIR` | `.local/keys` |
| `NAVEE_APK_PATH` | `dist/volt.apk` |

Relative override paths resolve from the repository root. An optional, ignored `local-build.env` can hold trusted local shell assignments; explicit environment values take precedence. Build output, local keys and this configuration file are ignored by Git. Keep custom output paths outside tracked source, and review staged files before pushing. Custom signing configuration is described in [Contributing](../CONTRIBUTING.md#signing-and-private-files).

## Run core tests

These tests need the JDK and Python but no Android SDK, device, internet connection or signing key:

```sh
bash tests/run.sh
python3 tests/owner-capture-guard-test.py
python3 tests/capture-owner-test.py
```

They exercise protocol, storage, calculations and owner-capture logic with synthetic data; `capture-owner-test.py` runs the computer helper against a fake adb and fake phone tools. Passing them does not establish physical scooter behavior or compatibility with every phone and firmware.

These are three of the checks CI runs. [CONTRIBUTING](../CONTRIBUTING.md#build-and-test) has the full list to run before you mark a pull request ready.

## Try your build without a scooter

You can try most screens on an emulator or your own phone with no scooter. Developer simulation fills them with fake readings, sends nothing over Bluetooth and records nothing. It needs a saved scooter profile first, and without one the switch doesn't turn on. The repository has a synthetic backup with one made-up scooter:

1. [Install your build](#install-your-build), then copy `tests/fixtures/legacy/full-v1.voltbackup` to the device's Downloads folder. For the first emulator, with `ANDROID_SDK_ROOT` exported as above:

   ```sh
   "$ANDROID_SDK_ROOT/platform-tools/adb" -s emulator-5554 push tests/fixtures/legacy/full-v1.voltbackup /sdcard/Download/
   ```

2. In Volt, open Settings (the gear on Home) › Back up & restore › Restore, choose that file and enter the password `synthetic fixture password`.
3. Review the contents, choose Replace everything, then Close Volt and reopen it. Home shows **Synthetic UT5**, disconnected.
4. Open Settings › Developer options and turn on **Simulate scooter as connected**. A red **Simulation · Exit** bar appears, and Home shows readings.

Restoring replaces the app's data on that device, so do it only in a build or emulator you don't use for riding. The backup also turns the online map on.

## Running an emulator check yourself

**This is optional.** You are not required to run Android validations; the maintainers run the affected ones on every UI pull request. If you'd like to see one pass, or to check a fix to a validation, this section is how.

You need the emulator and a virtual device (see [Requirements](#requirements)). Start it from Android Studio's Device Manager, with its system language set to **English (United States)**: the checks run Volt in English and stop otherwise. The first emulator's serial is `emulator-5554`. These commands need `ANDROID_SDK_ROOT` exported, as in [Install your build](#install-your-build). Check the emulator is running:

```sh
"$ANDROID_SDK_ROOT/platform-tools/adb" -s emulator-5554 get-state
```

It prints `device` when ready. Build the app, then the separate instrumentation APK, with the same local configuration and signing key:

```sh
bash build.sh
bash android-tests/build.sh
```

The runner output is `.local/build/parked-validation/parked-validation.apk`. Run one dashboard validation:

```sh
python3 android-tests/run-release-sweep.py --adb "$ANDROID_SDK_ROOT/platform-tools/adb" \
    --serial emulator-5554 --app dist/volt.apk \
    --runner .local/build/parked-validation/parked-validation.apk \
    --out .local/evidence/dashboard --only dashboard
```

It ends with `dashboard: PASS` and `Gating steps: 1 passed, 0 failed`. For settings screens, `--only more-menu-phone` walks More's menu and sections, and `--only compat-menu-edit` drove the Scooter tab's menu editor (removed in Hub layout part 3, so that step fails until the maintainers retire it); `--only hub-344` walks the Hub on the simulator (both Homes and their headers' search and gear, every subject page and sub-page, the page stack, the dock, Settings and search), and `--only hub-widgets-header-344` checks Widgets Home's search and gear on their own; `--only` can be repeated. `python3 android-tests/run-release-sweep.py --list` prints every step.

What the runner does to the emulator:

- It refuses anything but a booted emulator with an `emulator-` serial.
- It uninstalls and reinstalls Volt for each step and uninstalls it at the end, so anything you set up by hand on that emulator is gone afterwards.
- It disables the Play Store on that emulator to keep pop-ups out of the checks.
- Evidence goes to `--out`; keep it under `.local/`, which Git ignores.

A step takes a minute or two. The full list has over 150 steps, which is why the maintainers run it. Read the [development and validation notes](development-notes.md#device-checks) and the selected runner before running anything else. Some parked validation modes deliberately change real scooter settings; never run them on someone else's device or an unattended scooter.

## Optional browser helper

The static USB setup website is built separately:

```sh
cd setup/web
npm ci --ignore-scripts
npm test
npm run build
```

Its [README](../setup/web/README.md#build-and-local-use) covers local serving, browser checks, required security headers and deployment boundaries. Building it does not deploy it.

For source layout and implementation invariants, see [Development notes](development-notes.md). For contribution expectations, see [Contributing](../CONTRIBUTING.md); release maintainers should also read [Releasing](../RELEASING.md).
