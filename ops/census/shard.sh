#!/usr/bin/env bash
# usage: shard.sh BASETREE APKS OUT -- one shard of a cloud census, like the local census/worker-logcat.sh.
# env: CENSUS_BASE_TAG, CENSUS_HEADS (space-separated tags), CENSUS_STEPS (space-separated steps),
# CENSUS_SHARD (number). For each step, runs BASE-r1, every head, then BASE-r2 on this shard's emulator:
#   python3 -I android-tests/run-release-sweep.py --census --app APP --runner RUNNER --out OUT/out/TAG/STEP --only STEP
# from the BASE tree, with the BASE tree's test APK for every tag, then saves that run's logcat. A failed
# run never stops the shard. Writes OUT/out/<tag>/<step>/ and OUT/logs/shard-N/{results.txt,log-*.txt,emu.log}.
set -u
WT=$1; APKS=$2; OUT=$3; HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
SDK="${ANDROID_HOME:?}"; ADB="$SDK/platform-tools/adb"; SER=emulator-5554
B="${CENSUS_BASE_TAG:?}"; N="${CENSUS_SHARD:?}"
[[ "$B" =~ ^[a-z0-9][a-z0-9-]*$ && "$N" =~ ^[0-9]+$ ]] || { echo "bad shard environment" >&2; exit 2; }
for t in ${CENSUS_HEADS:?} ${CENSUS_STEPS:?}; do [[ "$t" =~ ^[a-z0-9][a-z0-9-]*$ ]] || { echo "bad tag or step" >&2; exit 2; }; done
L="$OUT/logs/shard-$N"; R="$L/results.txt"; RUNNER="$APKS/runner.apk"; mkdir -p "$L" "$OUT/out"
booted() { [[ "$("$ADB" -s $SER shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; }
echo "$(date +%T) shard $N: base $B heads $CENSUS_HEADS steps $CENSUS_STEPS" >> "$R"
# The job goes red (after every run has been tried) when an emulator never came up. Two boot
# failures in a row stop the shard: every run left is recorded as not run (report.py flags each one),
# instead of a broken image or emulator holding the runner for hours.
bootfail=0; failstreak=0
boot() {
  if bash "$HERE/emulator.sh" boot "$L/emu.log"; then failstreak=0; return 0; fi
  bootfail=1; failstreak=$((failstreak + 1)); return 1
}
if boot; then echo "$(date +%T) booted $SER ($(cat "$RUNNER_TEMP/census-image.txt"), -gpu swiftshader_indirect)" >> "$R"
else echo "$(date +%T) first boot failed" >> "$R"; echo "::error::shard $N: the emulator did not boot"; fi
# Known cloud-emulator flakes, matched in a failed run's sweep log: that run is retried once on the
# same emulator (both attempts are logged; report.py lists every retry). Only the password-keyboard
# waits of android-tests/PasswordKeyboardChecks.java (8 s each) are retried: on the swiftshader
# emulator Gboard sometimes needs longer to show its password keyboard (ImeTracker: onFailed at
# PHASE_WM_ABORT_SHOW_IME_POST_LAYOUT), or the window longer to settle above it. A head whose own
# change breaks the keyboard or hides the field fails both attempts.
FLAKES=(-e 'Enable the emulator software keyboard for password validation'
        -e 'Focused password is obscured by the keyboard'
        -e 'Password moved behind the keyboard while typing'
        -e 'Password keyboard did not dismiss'
        -e 'Password bounds did not settle after the keyboard/window transition')
stopped=""
for STEP in $CENSUS_STEPS; do
  for TAG in "$B-r1" $CENSUS_HEADS "$B-r2"; do
    if [[ -n $stopped ]]; then echo "$(date +%T) not run: $TAG $STEP ($stopped)" >> "$R"; continue; fi
    case "$TAG" in "$B-r1"|"$B-r2") APP="$APKS/$B/app.apk" ;; *) APP="$APKS/$TAG/app.apk" ;; esac
    if ! booted; then
      echo "$(date +%T) emulator not booted; restarting it" >> "$R"
      bash "$HERE/emulator.sh" kill
      if ! boot; then
        echo "$(date +%T) restart failed ($failstreak boot failure(s) in a row)" >> "$R"; echo "::error::shard $N: emulator restart failed"
        if (( failstreak >= 2 )); then
          stopped="shard stopped after $failstreak boot failures in a row"; echo "::error::shard $N: $stopped"
          echo "$(date +%T) not run: $TAG $STEP ($stopped)" >> "$R"; continue
        fi
      fi
    fi
    for attempt in 1 2; do
      label=""; (( attempt == 2 )) && label=" (retry)"
      echo "::group::$TAG $STEP$label"
      rm -rf "$OUT/out/$TAG/$STEP"
      T0=$(date +%s); "$ADB" -s $SER logcat -c 2>/dev/null
      (cd "$WT" && timeout -k 60 5400 python3 -I android-tests/run-release-sweep.py --adb "$ADB" --serial $SER --census \
          --app "$APP" --runner "$RUNNER" --out "$OUT/out/$TAG/$STEP" --only "$STEP") > "$L/log-$TAG-$STEP.txt" 2>&1
      RC=$?
      mkdir -p "$OUT/out/$TAG/$STEP"; timeout 120 "$ADB" -s $SER logcat -d -b main,system,crash > "$OUT/out/$TAG/$STEP/logcat.txt" 2>/dev/null
      SUM=$(grep -E "PASS|FAIL" "$L/log-$TAG-$STEP.txt" | grep -v -E "blake2|hashlib" | tail -1 | cut -c1-170)
      tail -n 4 "$L/log-$TAG-$STEP.txt"; echo "::endgroup::"
      if (( RC != 0 && attempt == 1 )) && grep -qF "${FLAKES[@]}" "$L/log-$TAG-$STEP.txt"; then
        cp "$L/log-$TAG-$STEP.txt" "$L/log-$TAG-$STEP-attempt1.txt"
        echo "$(date +%T) retry: $TAG $STEP after $(( $(date +%s)-T0 ))s: rc=$RC keyboard flake ($(grep -oF "${FLAKES[@]}" "$L/log-$TAG-$STEP.txt" | head -1))" | tee -a "$R"
        continue
      fi
      break
    done
    echo "$(date +%T) [$SER $(( $(date +%s)-T0 ))s] $TAG $STEP: rc=$RC $SUM" | tee -a "$R"
  done
done
echo "$(date +%T) done $SER" >> "$R"
bash "$HERE/emulator.sh" kill
exit $bootfail
