#!/usr/bin/env bash
# Recapture the round-trip fixtures from the local aapt (build-tools 36.1.0, the build's pinned version).
# Writes resources.arsc (aapt's compiled table), dump.txt (aapt dump --values resources) and
# resources-pseudo.arsc (the same package step with --pseudo-localize) here, and
# ../aapt-qualified/resources.arsc (text in values-en, values-v31, values-night and two translations) and
# ../aapt-pseudo-percent/resources-pseudo.arsc (aapt's NUL-mangled en-XA text, which pseudo_fix.py repairs) and
# ../aapt-pseudo-percent-shared/resources-pseudo.arsc (text the repair must refuse).
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
QUALIFIED="$HERE/../aapt-qualified"
"$AAPT" package -f -M "$QUALIFIED/AndroidManifest.xml" -S "$QUALIFIED/res" -I "$ANDROID_JAR" -F "$WORK/qualified.apk"
(cd "$WORK" && mkdir qualified && cd qualified && unzip -q -o ../qualified.apk resources.arsc)
cp "$WORK/qualified/resources.arsc" "$QUALIFIED/resources.arsc"
"$AAPT" dump --values resources "$WORK/qualified.apk" > "$QUALIFIED/dump.txt"
PERCENT="$HERE/../aapt-pseudo-percent"
"$AAPT" package -f --pseudo-localize -M "$PERCENT/AndroidManifest.xml" -S "$PERCENT/res" -I "$ANDROID_JAR" -F "$WORK/percent.apk"
(cd "$WORK" && mkdir percent && cd percent && unzip -q -o ../percent.apk resources.arsc)
cp "$WORK/percent/resources.arsc" "$PERCENT/resources-pseudo.arsc"
SHARED="$HERE/../aapt-pseudo-percent-shared"
"$AAPT" package -f --pseudo-localize -M "$SHARED/AndroidManifest.xml" -S "$SHARED/res" -I "$ANDROID_JAR" -F "$WORK/shared.apk"
(cd "$WORK" && mkdir shared && cd shared && unzip -q -o ../shared.apk resources.arsc)
cp "$WORK/shared/resources.arsc" "$SHARED/resources-pseudo.arsc"
"$AAPT" version > "$HERE/aapt-version.txt"
echo "Captured $HERE/resources.arsc and dump.txt with $(cat "$HERE/aapt-version.txt")"
