#!/usr/bin/env bash
# usage: probe.sh OUT_DIR   (configuration from the environment, set by census-probe.yml)
#   PROBE_ID      c1..c99
#   PROBE_EMU     latest | pin35   (pin35: the newest 35.x emulator build, downloaded by exact name)
#   PROBE_IMAGE   system-images;android-NN[.N|-extNN];google_apis[_playstore];x86_64
#   PROBE_GPU     the -gpu mode, optionally followed by "-feature -Name" pairs
#   PROBE_HOSTGL  1 to install Mesa software GL and run the emulator on an Xvfb display
#   PROBE_AVDTOOLS runner | new  (new: create the AVD with cmdline-tools 23.0's avdmanager, not the
#                 runner's older one, which may write target=android-0 for an NN.N image)
# Boots one census-shaped AVD headless, watches the display stack for 180 s after boot, then checks
# that Settings opens, uiautomator dumps and the screen is not black, and that the image's
# BluetoothGatt has every field the synthetic-link validations set by reflection (gatt_missing; guided,
# guided-queue and charge-schedule-wire need mClientRegistered). Writes OUT_DIR/result.json
# (one line) and always exits 0, so a matrix of probes completes; the verdict is in the JSON.
set -uo pipefail
OUT=${1:?}; mkdir -p "$OUT"
SDK="${ANDROID_HOME:?}"; ADB="$SDK/platform-tools/adb"; SER=emulator-5554; AVD=probe
SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"; AVDMANAGER="$SDK/cmdline-tools/latest/bin/avdmanager"
export ANDROID_AVD_HOME="${RUNNER_TEMP:?}/avd"; mkdir -p "$ANDROID_AVD_HOME"
# The newest 35.x emulator for Linux (35.6.11). The SDK repository XML no longer lists 35.x, so the
# zip is pinned by name, size and SHA-1 (hashed when this probe was written), and its
# source.properties must say 35.x.
PIN_ZIP=emulator-linux_x64-13610412.zip; PIN_SIZE=319086252; PIN_SHA1=3b656b511e53d116f51885ddc4b34653614e35d7

# Validate every input before it is used.
[[ "${PROBE_ID:-}" =~ ^c[0-9]{1,2}$ ]] || { echo "bad PROBE_ID" >&2; exit 2; }
[[ "${PROBE_EMU:-}" =~ ^(latest|pin35)$ ]] || { echo "bad PROBE_EMU" >&2; exit 2; }
[[ "${PROBE_IMAGE:-}" =~ ^system-images\;android-[0-9]{2}(\.[0-9]|-ext[0-9]{2})?\;google_apis(_playstore)?\;x86_64$ ]] || { echo "bad PROBE_IMAGE" >&2; exit 2; }
[[ "${PROBE_GPU:-}" =~ ^[a-z_]+(\ -feature\ -[A-Za-z]+)*$ ]] || { echo "bad PROBE_GPU" >&2; exit 2; }
[[ "${PROBE_HOSTGL:-0}" =~ ^[01]$ ]] || { echo "bad PROBE_HOSTGL" >&2; exit 2; }
[[ "${PROBE_AVDTOOLS:-runner}" =~ ^(runner|new)$ ]] || { echo "bad PROBE_AVDTOOLS" >&2; exit 2; }

ELOG="$OUT/emulator.log"; : > "$ELOG"
declare -A R=( [id]="$PROBE_ID" [emu]="$PROBE_EMU" [image]="$PROBE_IMAGE" [gpu]="$PROBE_GPU" [hostgl]="$PROBE_HOSTGL" [avdtools]="${PROBE_AVDTOOLS:-runner}" )
R[host_cpu]="$(lscpu | sed -n 's/^Model name: *//p' | head -1)"
note() { echo "$*"; R[notes]="${R[notes]:-}${R[notes]:+; }$*"; }
sh_() { timeout 30 "$ADB" -s $SER shell "$@" 2>/dev/null | tr -d '\r'; }

finish() {
  # Everything below is best effort: the probe always leaves a result.
  if [[ -x "$ADB" ]] && "$ADB" -s $SER get-state > /dev/null 2>&1; then
    timeout 30 "$ADB" -s $SER logcat -d -b crash > "$OUT/crash.txt" 2>&1 || true
  fi
  tail -n 300 "$ELOG" > "$OUT/emulator-tail.log" 2>/dev/null || true; rm -f "$ELOG"
  for k in "${!R[@]}"; do printf '%s\t%s\n' "$k" "${R[$k]//$'\n'/ }"; done > "$OUT/fields.tsv"
  python3 -I - "$OUT/fields.tsv" "$OUT/result.json" <<'PY'
import json, sys
ints = {'boot_s', 'sf_pid_changes', 'sf_fatal_in_window', 'sf_fatal_total', 'assert_total', 'sf_tombstones',
        'ss_pid_changes', 'sysui_pid_changes', 'samples', 'sf_down_samples'}
r = {}
for line in open(sys.argv[1], encoding='utf-8', errors='replace'):
    k, _, v = line.rstrip('\n').partition('\t')
    r[k] = int(v) if k in ints and v.lstrip('-').isdigit() else v
for k in ('booted', 'uiautomator_ok', 'screenshot_ok', 'settings_focused', 'stable', 'gatt_ok'):
    if k in r: r[k] = r[k] == '1'
for k in ('shot_mean', 'shot_var'):
    if k in r:
        try: r[k] = float(r[k])
        except ValueError: pass
open(sys.argv[2], 'w').write(json.dumps(r, sort_keys=True) + '\n')
print(json.dumps(r, sort_keys=True))
PY
  rm -f "$OUT/fields.tsv"
  "$ADB" -s $SER emu kill > /dev/null 2>&1 || true
  exit 0
}

R[stable]=0; R[booted]=0; R[verdict]=install_failed

# --- install -------------------------------------------------------------------------------
yes | "$SDKMANAGER" --licenses > /dev/null 2>&1 || true
"$SDKMANAGER" --install platform-tools emulator "$PROBE_IMAGE" > "$OUT/sdkmanager.log" 2>&1 || { note "sdkmanager failed: $(tail -3 "$OUT/sdkmanager.log" | tr '\n' ' ')"; finish; }
rm -f "$OUT/sdkmanager.log"
if [[ ${PROBE_AVDTOOLS:-runner} == new ]]; then
  "$SDKMANAGER" --install "cmdline-tools;23.0" > "$OUT/sdkmanager.log" 2>&1 && [[ -x "$SDK/cmdline-tools/23.0/bin/avdmanager" ]] \
    || { note "cmdline-tools 23.0 install failed: $(tail -3 "$OUT/sdkmanager.log" | tr '\n' ' ')"; finish; }
  rm -f "$OUT/sdkmanager.log"; AVDMANAGER="$SDK/cmdline-tools/23.0/bin/avdmanager"
fi
R[avdmanager]="$(sed -n 's/^Pkg.Revision=//p' "$(dirname "$(dirname "$AVDMANAGER")")/source.properties" | head -1)"
if [[ $PROBE_HOSTGL == 1 ]]; then
  sudo apt-get update -qq > /dev/null 2>&1
  sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq xvfb mesa-utils libgl1-mesa-dri libglx-mesa0 \
      libegl1 libegl-mesa0 libgles2 libgbm1 > "$OUT/apt.log" 2>&1 || { note "apt-get failed: $(tail -3 "$OUT/apt.log" | tr '\n' ' ')"; finish; }
  rm -f "$OUT/apt.log"
  Xvfb :99 -screen 0 1920x1080x24 -nolisten tcp > "$OUT/xvfb.log" 2>&1 &
  export DISPLAY=:99; sleep 3
  R[host_gl]="$(glxinfo -B 2>&1 | grep -E 'OpenGL (renderer|version) string' | tr '\n' ' ')"
fi

# --- create (the census AVD hardware, as emulator.sh create) --------------------------------
R[verdict]=create_failed
echo no | "$AVDMANAGER" create avd -n "$AVD" -k "$PROBE_IMAGE" -p "$ANDROID_AVD_HOME/$AVD.avd" --force > "$OUT/avdmanager.log" 2>&1 || { note "avdmanager failed: $(grep -v '^ *$' "$OUT/avdmanager.log" | tail -3 | tr '\n' ' ' | cut -c1-300)"; finish; }
rm -f "$OUT/avdmanager.log"
R[avd_target]="$(grep -h '^target=' "$ANDROID_AVD_HOME/$AVD.ini" "$ANDROID_AVD_HOME/$AVD.avd/config.ini" 2>/dev/null | tr '\n' ' ')"
playstore=false; [[ "$PROBE_IMAGE" == *google_apis_playstore* ]] && playstore=true
gpumode=${PROBE_GPU%% *}
python3 -I - "$ANDROID_AVD_HOME/$AVD.avd/config.ini" "$playstore" "$gpumode" <<'PY' || { note "config.ini edit failed"; finish; }
import sys
path, playstore, gpu = sys.argv[1:4]
want = {'hw.lcd.width': '1080', 'hw.lcd.height': '2400', 'hw.lcd.density': '420', 'hw.ramSize': '2048',
        'vm.heapSize': '336', 'disk.dataPartition.size': '6G', 'hw.keyboard': 'yes', 'hw.mainKeys': 'no',
        'hw.dPad': 'no', 'hw.trackBall': 'no', 'hw.initialOrientation': 'portrait', 'hw.cpu.ncore': '2',
        'hw.gpu.enabled': 'yes', 'hw.gpu.mode': gpu, 'hw.audioInput': 'no',
        'hw.camera.back': 'none', 'hw.camera.front': 'none', 'hw.sdCard': 'yes', 'sdcard.size': '512M',
        'showDeviceFrame': 'no', 'PlayStore.enabled': playstore}
lines = [l.rstrip('\n') for l in open(path)]
keep = [l for l in lines if l.split('=', 1)[0].strip() not in want]
open(path, 'w').write('\n'.join(keep + [f'{k}={v}' for k, v in want.items()]) + '\n')
PY

# avdmanager checks the SDK-installed emulator package, so the pinned build replaces it only now.
if [[ $PROBE_EMU == pin35 ]]; then
  R[verdict]=install_failed
  z="$RUNNER_TEMP/$PIN_ZIP"
  curl -sfL --retry 3 -o "$z" "https://dl.google.com/android/repository/$PIN_ZIP" || { note "pinned emulator download failed"; finish; }
  size=$(stat -c %s "$z"); sha=$(sha1sum "$z" | cut -d' ' -f1); R[pin_sha1]=$sha
  [[ $size == "$PIN_SIZE" ]] || { note "pinned emulator size $size != $PIN_SIZE"; finish; }
  [[ $sha == "$PIN_SHA1" ]] || { note "pinned emulator sha1 $sha != $PIN_SHA1"; finish; }
  rm -rf "$SDK/emulator" && unzip -q "$z" -d "$SDK" && rm -f "$z" || { note "pinned emulator unzip failed"; finish; }
  rev=$(sed -n 's/^Pkg.Revision=//p' "$SDK/emulator/source.properties" | head -1)
  [[ $rev == 35.* ]] || { note "pinned emulator is $rev, not 35.x"; finish; }
fi
R[emulator_version]="$("$SDK/emulator/emulator" -version 2>&1 | grep -m1 -i 'emulator version' | sed 's/^.*Android emulator version //')"

# A throwaway adb key (not a signing key): the Play image only answers a key it was booted with.
if [[ ! -s "$HOME/.android/adbkey.pub" ]]; then
  mkdir -p "$HOME/.android"; rm -f "$HOME/.android/adbkey"
  "$ADB" keygen "$HOME/.android/adbkey" > /dev/null 2>&1
fi
"$ADB" kill-server > /dev/null 2>&1 || true; "$ADB" start-server > /dev/null 2>&1

# --- boot ----------------------------------------------------------------------------------
R[verdict]=boot_failed
read -r -a gpuargs <<< "$PROBE_GPU"
t0=$SECONDS
"$SDK/emulator/emulator" -avd "$AVD" -no-window -no-audio -no-boot-anim -no-snapshot -port 5554 \
    -gpu "${gpuargs[@]}" >> "$ELOG" 2>&1 &
epid=$!
until [[ "$(sh_ getprop sys.boot_completed)" == 1 ]]; do
  kill -0 $epid 2>/dev/null || { note "emulator exited during boot"; finish; }
  (( SECONDS - t0 < 600 )) || { note "no boot in 600 s"; finish; }
  sleep 5
done
R[booted]=1; R[boot_s]=$((SECONDS - t0))
logver="$(grep -m1 'Android emulator version' "$ELOG" | sed 's/^.*Android emulator version //')"
[[ -n $logver ]] && R[emulator_version]=$logver
R[fingerprint]="$(sh_ getprop ro.build.fingerprint)"
R[sf_fatal_at_boot]="$(sh_ logcat -d -b crash | grep -c 'Fatal signal.*(surfaceflinger)')"

# --- watch the display stack for 180 s -----------------------------------------------------
R[verdict]=unstable
declare -A seen_sf=() seen_ss=() seen_ui=()
samples=0; sf_down=0
for _ in $(seq 36); do
  sf=$(sh_ pidof surfaceflinger); ss=$(sh_ pidof system_server); ui=$(sh_ pidof com.android.systemui)
  samples=$((samples + 1))
  if [[ -n $sf ]]; then seen_sf[$sf]=1; else sf_down=$((sf_down + 1)); fi
  [[ -n $ss ]] && seen_ss[$ss]=1; [[ -n $ui ]] && seen_ui[$ui]=1
  kill -0 $epid 2>/dev/null || { note "emulator exited during the watch"; break; }
  sleep 5
done
R[samples]=$samples; R[sf_down_samples]=$sf_down
R[sf_pid_changes]=$(( ${#seen_sf[@]} > 0 ? ${#seen_sf[@]} - 1 : 0 ))
R[ss_pid_changes]=$(( ${#seen_ss[@]} > 0 ? ${#seen_ss[@]} - 1 : 0 ))
R[sysui_pid_changes]=$(( ${#seen_ui[@]} > 0 ? ${#seen_ui[@]} - 1 : 0 ))
(( ${#seen_ss[@]} > 0 )) || note "system_server never seen"
(( ${#seen_ui[@]} > 0 )) || note "SystemUI never seen"
crash="$(sh_ logcat -d -b crash)"
R[sf_fatal_total]=$(grep -c 'Fatal signal.*(surfaceflinger)' <<< "$crash")
R[sf_fatal_in_window]=$(( R[sf_fatal_total] - ${R[sf_fatal_at_boot]:-0} ))
R[assert_total]=$(grep -c 'Assertion failed' <<< "$crash")
R[first_assert]="$(grep -m1 -o 'Assertion failed.*' <<< "$crash" | cut -c1-200)"
R[sf_tombstones]=$(sh_ dumpsys dropbox --print SYSTEM_TOMBSTONE | grep -c '>>> /system/bin/surfaceflinger <<<')
R[renderer]="$(sh_ dumpsys SurfaceFlinger | grep -i -m2 'GLES' | tr '\n' ' ' | cut -c1-240)"

# --- BluetoothGatt fields (android-tests' synthetic GATT link sets these by reflection) ---------
# A dex string_data item is its uleb128 length (one byte below 128), the MUTF-8 bytes, then NUL.
R[gatt_ok]=0; R[gatt_missing]="framework-bluetooth.jar not found"
jar="$(sh_ 'echo $BOOTCLASSPATH' | tr ':' '\n' | grep -m1 '/framework-bluetooth\.jar$')"
R[bt_jar]=$jar
if [[ -n $jar ]] && timeout 60 "$ADB" -s $SER pull "$jar" "$RUNNER_TEMP/framework-bluetooth.jar" > /dev/null 2>&1; then
  R[gatt_missing]="$(python3 -I - "$RUNNER_TEMP/framework-bluetooth.jar" <<'PY'
import sys, zipfile
z = zipfile.ZipFile(sys.argv[1]); dex = b''.join(z.read(n) for n in z.namelist() if n.startswith('classes') and n.endswith('.dex'))
names = ['mClientRegistered', 'mDeviceBusy', 'mDeviceBusyLock', 'mStateLock', 'mService', 'mServices', 'mDevice']
print(' '.join(n for n in names if bytes([len(n)]) + n.encode() + b'\0' not in dex) or 'none')
PY
)"
  rm -f "$RUNNER_TEMP/framework-bluetooth.jar"
  [[ ${R[gatt_missing]} == none ]] && R[gatt_ok]=1
fi

# --- Settings, uiautomator, screenshot ----------------------------------------------------
sh_ input keyevent KEYCODE_WAKEUP > /dev/null; sh_ wm dismiss-keyguard > /dev/null
sh_ am start -a android.settings.SETTINGS > /dev/null; sleep 5
R[focus]="$(sh_ dumpsys window | grep -m1 mCurrentFocus | sed 's/^ *//')"
[[ ${R[focus]} == *settings* || ${R[focus]} == *Settings* ]] && R[settings_focused]=1 || R[settings_focused]=0
R[uiautomator_ok]=0
if timeout 60 "$ADB" -s $SER shell uiautomator dump /sdcard/probe-ui.xml > "$OUT/uiautomator.txt" 2>&1 \
   && timeout 30 "$ADB" -s $SER pull /sdcard/probe-ui.xml "$OUT/ui.xml" > /dev/null 2>&1 \
   && grep -q '<hierarchy' "$OUT/ui.xml"; then R[uiautomator_ok]=1; fi
R[uiautomator_msg]="$(tr -d '\r' < "$OUT/uiautomator.txt" | tr '\n' ' ' | cut -c1-200)"; rm -f "$OUT/uiautomator.txt"
timeout 30 "$ADB" -s $SER exec-out screencap -p > "$OUT/screen.png" 2>/dev/null || true
timeout 30 "$ADB" -s $SER exec-out screencap > "$RUNNER_TEMP/screen.raw" 2>/dev/null || true
read -r mean var < <(python3 -I - "$RUNNER_TEMP/screen.raw" <<'PY'
import struct, sys
d = open(sys.argv[1], 'rb').read()
if len(d) < 16: print('nan nan'); sys.exit()
w, h = struct.unpack_from('<II', d)
off = len(d) - w * h * 4
if w == 0 or h == 0 or off not in (12, 16): print('nan nan'); sys.exit()
px = d[off:]
lum = [(px[i] * 299 + px[i + 1] * 587 + px[i + 2] * 114) / 1000 for i in range(0, len(px), 4 * 97)]
m = sum(lum) / len(lum); v = sum((x - m) ** 2 for x in lum) / len(lum)
print(f'{m:.2f} {v:.2f}')
PY
)
rm -f "$RUNNER_TEMP/screen.raw"
R[shot_mean]=$mean; R[shot_var]=$var
R[screenshot_ok]=$(python3 -I -c 'import sys; m,v=float(sys.argv[1]),float(sys.argv[2]); print(1 if m > 8 and v > 20 else 0)' "$mean" "$var" 2>/dev/null || echo 0)
R[ss_up_end]=$([[ -n "$(sh_ pidof system_server)" ]] && echo 1 || echo 0)
R[sysui_up_end]=$([[ -n "$(sh_ pidof com.android.systemui)" ]] && echo 1 || echo 0)

if (( R[sf_pid_changes] == 0 && R[sf_fatal_in_window] == 0 && sf_down == 0 && R[ss_pid_changes] == 0 \
      && R[sysui_pid_changes] == 0 && ${#seen_ss[@]} > 0 && ${#seen_ui[@]} > 0 )) \
   && [[ ${R[uiautomator_ok]} == 1 && ${R[screenshot_ok]} == 1 && ${R[ss_up_end]} == 1 && ${R[sysui_up_end]} == 1 ]]; then
  R[stable]=1; R[verdict]=stable
fi
finish
