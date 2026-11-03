#!/usr/bin/env bash
# Recapture the round-trip fixtures from the local aapt (build-tools 36.1.0, the build's pinned version).
# Writes resources.arsc (aapt's compiled table), dump.txt (aapt dump --values resources) and
# resources-pseudo.arsc (the same package step with --pseudo-localize) here.
set -euo pipefail
HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
SDK_DIR="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
AAPT="$SDK_DIR/build-tools/36.1.0/aapt"
ANDROID_JAR="$SDK_DIR/platforms/android-36/android.jar"
WORK="$(mktemp -d)"
trap 'rm -rf -- "$WORK"' EXIT
"$AAPT" package -f -M "$HERE/AndroidManifest.xml" -S "$HERE/res" -I "$ANDROID_JAR" -F "$WORK/fixture.apk"
(cd "$WORK" && unzip -q -o fixture.apk resources.arsc)
cp "$WORK/resources.arsc" "$HERE/resources.arsc"
"$AAPT" dump --values resources "$WORK/fixture.apk" > "$HERE/dump.txt"
"$AAPT" package -f --pseudo-localize -M "$HERE/AndroidManifest.xml" -S "$HERE/res" -I "$ANDROID_JAR" -F "$WORK/pseudo.apk"
(cd "$WORK" && mkdir pseudo && cd pseudo && unzip -q -o ../pseudo.apk resources.arsc)
cp "$WORK/pseudo/resources.arsc" "$HERE/resources-pseudo.arsc"
"$AAPT" version > "$HERE/aapt-version.txt"
echo "Captured $HERE/resources.arsc and dump.txt with $(cat "$HERE/aapt-version.txt")"
