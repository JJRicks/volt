#!/usr/bin/env bash
# CI only (.github/workflows/tests.yml): make sure the runner's Android SDK has the exact build-tools and
# platform build.sh uses (navee_find_sdk), installing them with the image's sdkmanager when missing.
set -euo pipefail
BUILD_TOOLS=36.1.0
PLATFORM=android-36
sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
[[ -n "$sdk" && -d "$sdk" ]] || { echo "::error::This runner has no Android SDK (ANDROID_SDK_ROOT is unset)." >&2; exit 1; }
need=()
for file in aapt aidl d8 zipalign apksigner core-lambda-stubs.jar; do
    [[ -e "$sdk/build-tools/$BUILD_TOOLS/$file" ]] || { need+=("build-tools;$BUILD_TOOLS"); break; }
done
for file in android.jar framework.aidl; do
    [[ -f "$sdk/platforms/$PLATFORM/$file" ]] || { need+=("platforms;$PLATFORM"); break; }
done
if (( ${#need[@]} )); then
    echo "Installing ${need[*]}"
    # sdkmanager asks to accept each license; yes stops with SIGPIPE when it exits, which is fine here.
    (yes || true) | "$sdk/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$sdk" --install "${need[@]}" | tail -n 5
fi
for file in "build-tools/$BUILD_TOOLS/source.properties" "platforms/$PLATFORM/source.properties"; do
    echo "$file: $(grep -h '^Pkg.Revision=' "$sdk/$file")"
done
echo "ANDROID_SDK_ROOT=$sdk" >> "${GITHUB_ENV:-/dev/null}"
