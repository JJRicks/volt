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
# The job goes red (after every run has been tried) when an emulator never came up.
bootfail=0
if bash "$HERE/emulator.sh" boot "$L/emu.log"; then echo "$(date +%T) booted $SER (-gpu $(cat "$RUNNER_TEMP/census-gpu.txt" 2>/dev/null))" >> "$R"
else echo "$(date +%T) first boot failed" >> "$R"; echo "::error::shard $N: the emulator did not boot"; bootfail=1; fi
for STEP in $CENSUS_STEPS; do
  for TAG in "$B-r1" $CENSUS_HEADS "$B-r2"; do
    case "$TAG" in "$B-r1"|"$B-r2") APP="$APKS/$B/app.apk" ;; *) APP="$APKS/$TAG/app.apk" ;; esac
    if ! booted; then
      echo "$(date +%T) emulator not booted; restarting it" >> "$R"
      bash "$HERE/emulator.sh" kill; bash "$HERE/emulator.sh" boot "$L/emu.log" || { echo "$(date +%T) restart failed" >> "$R"; echo "::error::shard $N: emulator restart failed"; bootfail=1; }
    fi
    echo "::group::$TAG $STEP"
    T0=$(date +%s); "$ADB" -s $SER logcat -c 2>/dev/null
    (cd "$WT" && timeout -k 60 5400 python3 -I android-tests/run-release-sweep.py --adb "$ADB" --serial $SER --census \
        --app "$APP" --runner "$RUNNER" --out "$OUT/out/$TAG/$STEP" --only "$STEP") > "$L/log-$TAG-$STEP.txt" 2>&1
    RC=$?
    mkdir -p "$OUT/out/$TAG/$STEP"; timeout 120 "$ADB" -s $SER logcat -d -b main,system,crash > "$OUT/out/$TAG/$STEP/logcat.txt" 2>/dev/null
    SUM=$(grep -E "PASS|FAIL" "$L/log-$TAG-$STEP.txt" | grep -v -E "blake2|hashlib" | tail -1 | cut -c1-170)
    tail -n 4 "$L/log-$TAG-$STEP.txt"; echo "::endgroup::"
    echo "$(date +%T) [$SER $(( $(date +%s)-T0 ))s] $TAG $STEP: rc=$RC $SUM" | tee -a "$R"
  done
done
echo "$(date +%T) done $SER" >> "$R"
bash "$HERE/emulator.sh" kill
exit $bootfail
