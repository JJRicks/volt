#!/usr/bin/env bash
# usage: emulator.sh install | create | boot LOG | kill
# The cloud twin of the local census AVD (Medium_Phone_API_36.1: 1080x2400 at 420 dpi, 2 GB RAM,
# hardware keyboard, 6 GB data), but x86_64 under KVM on system-images;android-36;google_apis (not the
# local 36.1 Play image, whose x86_64 build crash-loops surfaceflinger on GitHub runners; see
# README.md), booted headless with the local flags except -gpu swiftshader_indirect for -gpu host.
set -euo pipefail
SDK="${ANDROID_HOME:?}"; ADB="$SDK/platform-tools/adb"; SER=emulator-5554; AVD=census
SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"; AVDMANAGER="$SDK/cmdline-tools/latest/bin/avdmanager"
IMAGE_FILE="${RUNNER_TEMP:?}/census-image.txt"
# One known AVD home for avdmanager and the emulator (the runner image's default moved once).
export ANDROID_AVD_HOME="$RUNNER_TEMP/avd"; mkdir -p "$ANDROID_AVD_HOME"
case "${1:-}" in
install)
  yes | "$SDKMANAGER" --licenses > /dev/null 2>&1 || true
  "$SDKMANAGER" --install platform-tools emulator > /dev/null
  # Only images the boot-stability probe (census-probe.yml) found stable with plain
  # swiftshader_indirect on the latest emulator: android-36 (c2), then android-35 (c4).
  for image in "system-images;android-36;google_apis;x86_64" "system-images;android-35;google_apis;x86_64"; do
    if "$SDKMANAGER" --install "$image" > /dev/null; then echo "$image" > "$IMAGE_FILE"; echo "installed $image"; exit 0; fi
    echo "::warning::could not install $image"
  done
  exit 1 ;;
create)
  image="$(cat "$IMAGE_FILE")"
  echo no | "$AVDMANAGER" create avd -n "$AVD" -k "$image" -p "$ANDROID_AVD_HOME/$AVD.avd" --force > /dev/null
  config="$ANDROID_AVD_HOME/$AVD.avd/config.ini"
  [[ -f "$config" && -f "$ANDROID_AVD_HOME/$AVD.ini" ]] || { echo "AVD not where expected:" >&2; find "$ANDROID_AVD_HOME" "$HOME/.android" "$HOME/.config" -maxdepth 3 -name "$AVD*" 2>/dev/null >&2; exit 1; }
  playstore=false; [[ "$image" == *google_apis_playstore* ]] && playstore=true
  python3 -I - "$config" "$playstore" <<'PY'
import sys
path, playstore = sys.argv[1], sys.argv[2]
want = {'hw.lcd.width': '1080', 'hw.lcd.height': '2400', 'hw.lcd.density': '420', 'hw.ramSize': '2048',
        'vm.heapSize': '336', 'disk.dataPartition.size': '6G', 'hw.keyboard': 'yes', 'hw.mainKeys': 'no',
        'hw.dPad': 'no', 'hw.trackBall': 'no', 'hw.initialOrientation': 'portrait', 'hw.cpu.ncore': '2',
        'hw.gpu.enabled': 'yes', 'hw.gpu.mode': 'swiftshader_indirect', 'hw.audioInput': 'no',
        'hw.camera.back': 'none', 'hw.camera.front': 'none', 'hw.sdCard': 'yes', 'sdcard.size': '512M',
        'showDeviceFrame': 'no', 'PlayStore.enabled': playstore}
lines = [l.rstrip('\n') for l in open(path)]
keep = [l for l in lines if l.split('=', 1)[0].strip() not in want]
open(path, 'w').write('\n'.join(keep + [f'{k}={v}' for k, v in want.items()]) + '\n')
PY
  echo "AVD $AVD from $image ($("$AVDMANAGER" --version 2>/dev/null | tail -1)):"; cat "$ANDROID_AVD_HOME/$AVD.ini"; grep -E '^(hw\.lcd|hw\.ramSize|hw\.cpu|hw\.gpu|hw\.keyboard|PlayStore|image\.sysdir)' "$config" ;;
boot)
  LOG=${2:?}
  # A secure-adb image only answers an adb key it was booted with: the emulator hands the guest
  # ~/.android/adbkey.pub at launch, and a fresh runner has none ("device unauthorized"). Make one
  # (a throwaway adb key, not a signing key) and restart the adb server so it uses it. The
  # google_apis images used now don't need it (the Play image did); it is harmless.
  if [[ ! -s "$HOME/.android/adbkey.pub" ]]; then
    mkdir -p "$HOME/.android"; rm -f "$HOME/.android/adbkey"
    "$ADB" keygen "$HOME/.android/adbkey" > /dev/null 2>&1
    [[ -s "$HOME/.android/adbkey.pub" ]] || { echo "could not make an adb key" >&2; exit 1; }
    "$ADB" kill-server > /dev/null 2>&1 || true; "$ADB" start-server > /dev/null 2>&1
  fi
  # Software GPU, plain -gpu swiftshader_indirect (stable on the images above in the probe). A
  # display stack that keeps crashing still fails the boot: surfaceflinger must keep its pid for 75 s
  # after boot, or the shard stops here (loudly) instead of hanging in every census run.
  mode=swiftshader_indirect
  "$ADB" -s $SER emu kill > /dev/null 2>&1 || true; sleep 3; pkill -9 -f "qemu-system.*-avd $AVD" 2>/dev/null || true
  echo "=== boot with -gpu $mode" >> "$LOG"
  "$SDK/emulator/emulator" -avd "$AVD" -no-window -no-audio -no-boot-anim -read-only -no-snapshot \
      -gpu $mode -port 5554 >> "$LOG" 2>&1 &
  pid=$!; end=$((SECONDS + 900))
  until [[ "$("$ADB" -s $SER shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; do
    kill -0 $pid 2>/dev/null || { echo "::error::emulator exited during boot" >&2; exit 1; }
    (( SECONDS < end )) || { echo "::error::no boot in 900 s: $("$ADB" devices | tr '\n' ' ')" >&2; exit 1; }
    sleep 5
  done
  echo "boot completed after $((SECONDS + 900 - end)) s" | tee -a "$LOG"
  sf1="$("$ADB" -s $SER shell pidof surfaceflinger 2>/dev/null | tr -d '\r')"; sleep 75
  sf2="$("$ADB" -s $SER shell pidof surfaceflinger 2>/dev/null | tr -d '\r')"
  aborts="$("$ADB" -s $SER logcat -d -b crash 2>/dev/null | grep -c 'pid [0-9]* (surfaceflinger)' || true)"
  echo "-gpu $mode: surfaceflinger pid $sf1 -> $sf2, $aborts surfaceflinger crash(es) since boot" | tee -a "$LOG"
  if [[ -z "$sf1" || "$sf1" != "$sf2" || "$("$ADB" -s $SER shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != 1 ]]; then
    echo "::error::surfaceflinger not stable after boot (pid $sf1 -> $sf2, $aborts crash(es))" >&2; exit 1
  fi
  echo "booted $SER (-gpu $mode): $("$ADB" -s $SER shell getprop ro.build.fingerprint | tr -d '\r') locale=$("$ADB" -s $SER shell getprop persist.sys.locale | tr -d '\r')/$("$ADB" -s $SER shell getprop ro.product.locale | tr -d '\r') size=$("$ADB" -s $SER shell wm size | tr -d '\r' | tail -1) density=$("$ADB" -s $SER shell wm density | tr -d '\r' | head -1)" ;;
kill)
  "$ADB" -s $SER emu kill > /dev/null 2>&1 || true
  for _ in $(seq 30); do pgrep -f "qemu-system.*-avd $AVD|emulator.*-avd $AVD" > /dev/null || exit 0; sleep 2; done
  pkill -9 -f "qemu-system.*$AVD" || true ;;
*) echo "usage: emulator.sh install | create | boot LOG | kill" >&2; exit 2 ;;
esac
