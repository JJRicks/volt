#!/usr/bin/env bash
# usage: build-trees.sh PLANDIR WORK -> WORK/apks/<tag>/{app.apk,commit.txt,build.log} and
# WORK/apks/runner.apk (+ runner-build.log), like the local census/build.sh: each tree is built with
# "VOLT_RELEASE=1 bash build.sh"; the test APK comes from the base tree only (the first line of
# PLANDIR/trees.txt), and every APK is signed with one throwaway key made for this workflow run.
set -euo pipefail
PLAN=$1; WORK=$2; HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
mkdir -p "$WORK/trees" "$WORK/apks"

# One throwaway signing key per workflow run, outside every tree and every uploaded directory. build.sh
# reads $NAVEE_KEY_DIR/navee-offline-lock.p12 (alias navee-offline) and its password from
# navee-offline-lock.pass. It is never uploaded and dies with the runner.
KEYS="${RUNNER_TEMP:?}/census-throwaway-key"
( umask 077; mkdir -p "$KEYS"
  od -An -N32 -tx1 /dev/urandom | tr -d ' \n' > "$KEYS/navee-offline-lock.pass"
  NAVEE_SIGNING_PASSWORD="$(cat "$KEYS/navee-offline-lock.pass")" keytool -genkeypair -noprompt \
      -alias navee-offline -keyalg RSA -keysize 3072 -validity 30 \
      -dname "CN=Volt census throwaway key (GitHub Actions run - never released)" \
      -storetype PKCS12 -keystore "$KEYS/navee-offline-lock.p12" \
      -storepass:env NAVEE_SIGNING_PASSWORD -keypass:env NAVEE_SIGNING_PASSWORD ) 2>&1 | grep -v -i pass || true
[[ -f "$KEYS/navee-offline-lock.p12" ]] || { echo "keystore was not created" >&2; exit 1; }
export NAVEE_KEY_DIR="$KEYS" ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:?}}"
unset NAVEE_SIGNING_KEYSTORE NAVEE_SIGNING_PASSWORD NAVEE_BUILD_DIR NAVEE_APK_PATH

first=1
while read -r tag sha; do
  [[ "$tag" =~ ^[a-z0-9][a-z0-9-]*$ && "$sha" =~ ^[0-9a-f]{40}$ ]] || { echo "bad trees.txt line" >&2; exit 2; }
  W="$WORK/trees/$tag"; A="$WORK/apks/$tag"; mkdir -p "$A"
  bash "$HERE/fetch-tree.sh" "$sha" "$W"
  echo "::group::build $tag"
  if ! (cd "$W" && python3 setup/shizuku-dependencies.py --fetch && VOLT_RELEASE=1 bash build.sh) > "$A/build.log" 2>&1; then
    cat "$A/build.log"; echo "::endgroup::"; echo "::error::$tag: BUILD FAILED"; exit 1
  fi
  tail -n 5 "$A/build.log"; echo "::endgroup::"
  cp "$W/dist/volt.apk" "$A/app.apk"; git -C "$W" rev-parse HEAD > "$A/commit.txt"
  if [[ $first == 1 ]]; then
    echo "::group::test APK from $tag"
    if ! (cd "$W" && bash android-tests/build.sh) > "$WORK/apks/runner-build.log" 2>&1; then
      cat "$WORK/apks/runner-build.log"; echo "::endgroup::"; echo "::error::$tag: RUNNER BUILD FAILED"; exit 1
    fi
    tail -n 3 "$WORK/apks/runner-build.log"; echo "::endgroup::"
    cp "$W/.local/build/parked-validation/parked-validation.apk" "$WORK/apks/runner.apk"
    echo "$tag" > "$WORK/apks/runner-from.txt"; first=0
  fi
  echo "$tag ${sha:0:7} app=$(sha256sum "$A/app.apk" | cut -c1-12) runner=$(sha256sum "$WORK/apks/runner.apk" | cut -c1-12)" | tee -a "$WORK/apks/builds.txt"
done < "$PLAN/trees.txt"
cp "$PLAN"/plan.json "$PLAN"/steps.txt "$PLAN"/trees.txt "$WORK/apks/"
