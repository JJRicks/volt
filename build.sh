#!/usr/bin/env bash
# Local SDK-only build. No Gradle, network access, or dependency downloads.
set -euo pipefail
umask 077

APP_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

# A private, optional local-build.env contains trusted Bash assignments. It must
# remain untracked. Explicit environment overrides take precedence over this file.
# Sourcing this script from test runners only defines configuration and helpers;
# it does not build or generate a signing key.
navee_load_config() {
    local name index value
    local names=(NAVEE_BUILD_DIR NAVEE_KEY_DIR NAVEE_APK_PATH
        NAVEE_SIGNING_KEYSTORE NAVEE_SIGNING_ALIAS NAVEE_SIGNING_PASSWORD
        JAVA_HOME ANDROID_SDK_ROOT ANDROID_HOME)
    local saved_names=() saved_values=()
    for name in "${names[@]}"; do
        if declare -p "$name" >/dev/null 2>&1; then
            saved_names+=("$name")
            saved_values+=("${!name}")
        fi
    done
    if [[ -f "$APP_DIR/local-build.env" ]]; then
        # This file is local code, never downloaded or obtained from a scooter.
        source "$APP_DIR/local-build.env"
    fi
    for ((index = 0; index < ${#saved_names[@]}; index++)); do
        printf -v "${saved_names[index]}" '%s' "${saved_values[index]}"
    done
    NAVEE_BUILD_DIR="${NAVEE_BUILD_DIR:-$APP_DIR/.local/build}"
    NAVEE_KEY_DIR="${NAVEE_KEY_DIR:-$APP_DIR/.local/keys}"
    NAVEE_APK_PATH="${NAVEE_APK_PATH:-$APP_DIR/dist/volt.apk}"
    for name in NAVEE_BUILD_DIR NAVEE_KEY_DIR NAVEE_APK_PATH; do
        value="${!name}"
        if [[ "$value" != /* ]]; then
            printf -v "$name" '%s' "$APP_DIR/$value"
        fi
    done
    if [[ -n "${NAVEE_SIGNING_KEYSTORE:-}" && "$NAVEE_SIGNING_KEYSTORE" != /* ]]; then
        NAVEE_SIGNING_KEYSTORE="$APP_DIR/$NAVEE_SIGNING_KEYSTORE"
    fi
    BUILD_DIR="$NAVEE_BUILD_DIR/volt"
    KEY_DIR="$NAVEE_KEY_DIR"
    APK_PATH="$NAVEE_APK_PATH"
}

navee_find_sdk() {
    SDK_DIR="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
    if [[ -z "$SDK_DIR" ]]; then
        for candidate in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" /opt/android-sdk; do
            if [[ -d "$candidate/platforms/android-36" ]]; then
                SDK_DIR="$candidate"
                break
            fi
        done
    fi
    [[ -n "$SDK_DIR" ]] || { echo "Set ANDROID_SDK_ROOT to your installed Android SDK." >&2; return 1; }
    BUILD_TOOLS="$SDK_DIR/build-tools/36.1.0"
    ANDROID_JAR="$SDK_DIR/platforms/android-36/android.jar"
    for required in "$ANDROID_JAR" "$BUILD_TOOLS/aapt" "$BUILD_TOOLS/d8" "$BUILD_TOOLS/zipalign" "$BUILD_TOOLS/apksigner" "$BUILD_TOOLS/core-lambda-stubs.jar"; do
        [[ -f "$required" ]] || { echo "Missing installed SDK file: $required" >&2; return 1; }
    done
}

navee_find_java() {
    if [[ -z "${JAVA_HOME:-}" ]]; then
        for candidate in "/Applications/Android Studio.app/Contents/jbr/Contents/Home" "/Applications/Android Studio.app/Contents/jbr" "/opt/android-studio/jbr"; do
            if [[ -x "$candidate/bin/javac" ]]; then
                JAVA_HOME="$candidate"
                break
            fi
        done
        if [[ -z "${JAVA_HOME:-}" && -x /usr/libexec/java_home ]]; then
            JAVA_HOME="$(/usr/libexec/java_home 2>/dev/null || true)"
        fi
    fi
    if [[ -n "${JAVA_HOME:-}" ]]; then
        [[ -x "$JAVA_HOME/bin/javac" && -x "$JAVA_HOME/bin/java" ]] || {
            echo "JAVA_HOME must point to a locally installed JDK 17 or newer." >&2
            return 1
        }
        export JAVA_HOME
        export PATH="$JAVA_HOME/bin:$PATH"
    fi
    for tool in java javac jar keytool; do
        command -v "$tool" >/dev/null 2>&1 || {
            echo "Install a JDK 17 or newer, or set JAVA_HOME to an existing JDK." >&2
            return 1
        }
    done
}

navee_load_signing_identity() {
    local allow_generate="${1:-false}"
    SIGNING_ALIAS="${NAVEE_SIGNING_ALIAS:-navee-offline}"
    if [[ -n "${NAVEE_SIGNING_KEYSTORE:-}" ]]; then
        SIGNING_KEYSTORE="$NAVEE_SIGNING_KEYSTORE"
        [[ -f "$SIGNING_KEYSTORE" ]] || { echo "Signing keystore does not exist." >&2; return 1; }
        [[ -n "${NAVEE_SIGNING_PASSWORD:-}" ]] || {
            echo "Set NAVEE_SIGNING_PASSWORD with NAVEE_SIGNING_KEYSTORE." >&2
            return 1
        }
    else
        SIGNING_KEYSTORE="$KEY_DIR/navee-offline-lock.p12"
        PASSWORD_FILE="$KEY_DIR/navee-offline-lock.pass"
        if [[ ! -f "$SIGNING_KEYSTORE" ]]; then
            [[ "$allow_generate" == true ]] || {
                echo "Build the main app first to create its local signing key." >&2
                return 1
            }
            mkdir -p "$KEY_DIR"
            if [[ ! -f "$PASSWORD_FILE" ]]; then
                od -An -N32 -tx1 /dev/urandom | tr -d ' \n' > "$PASSWORD_FILE"
                chmod 600 "$PASSWORD_FILE"
            fi
            NAVEE_SIGNING_PASSWORD="$(cat "$PASSWORD_FILE")"
            export NAVEE_SIGNING_PASSWORD
            keytool -genkeypair -noprompt \
                -alias "$SIGNING_ALIAS" -keyalg RSA -keysize 3072 -validity 3650 \
                -dname "CN=Navee Offline Lock Local Build" \
                -storetype PKCS12 -keystore "$SIGNING_KEYSTORE" \
                -storepass:env NAVEE_SIGNING_PASSWORD -keypass:env NAVEE_SIGNING_PASSWORD
            chmod 600 "$SIGNING_KEYSTORE"
        fi
        [[ -f "$PASSWORD_FILE" ]] || { echo "The saved signing password is missing." >&2; return 1; }
        NAVEE_SIGNING_PASSWORD="$(cat "$PASSWORD_FILE")"
    fi
    export NAVEE_SIGNING_PASSWORD
}

# Localization (tools/l10n, l10n/README.md). The text catalog is generated from the
# res/values/strings_<area>.xml files; until the first one exists these steps do nothing, so the
# build output stays byte-identical.
navee_l10n_active() {
    local strings_file
    for strings_file in "$APP_DIR"/res/values/strings_*.xml; do
        [[ -f "$strings_file" ]] && return 0
    done
    return 1
}

# G3: the English aapt compiled into $1 must equal the generator's English, byte for byte.
# Extra arguments go to roundtrip.py (the pseudo build passes --allow-pseudo).
navee_l10n_roundtrip() {
    navee_l10n_active || return 0
    local apk="$1"
    shift
    python3 "$APP_DIR/tools/l10n/roundtrip.py" --apk "$apk" --expected "$BUILD_DIR/l10n/en.expected.tsv" ${1+"$@"}
}

# Pseudo-locales (en-XA, ar-XB) are for local layout tests only; an APK that has them must not ship.
# Text in an English or non-locale configuration (values-en, values-night, values-v31) must not ship
# either: it would replace the catalog English on the phones it matches.
navee_release_guard() {
    local configs
    configs="$("$BUILD_TOOLS/aapt" dump configurations "$1")" || {
        echo "Release guard: cannot list the resource configurations of $1" >&2
        return 1
    }
    if grep -Eq '(^|-)r?X[AB](-|$)|\+X[AB](\+|-|$)' <<<"$configs"; then
        echo "Release guard: $1 has pseudo-locale configurations (en-XA or ar-XB); it must not be released." >&2
        return 1
    fi
    python3 "$APP_DIR/tools/l10n/roundtrip.py" --guard --apk "$1" || {
        echo "Release guard: $1 has text that would override the English catalog; it must not be released." >&2
        return 1
    }
}

# VOLT_PSEUDO_LOCALES=1 also packages the same classes.dex with aapt --pseudo-localize into
# build/pseudo/volt-pseudo.apk for emulator layout checks. It never writes to dist/.
navee_build_pseudo() {
    local work="$BUILD_DIR/pseudo" out_dir="$APP_DIR/build/pseudo"
    local pseudo_apk="$out_dir/volt-pseudo.apk"
    rm -rf -- "$work"
    mkdir -p "$work/generated" "$out_dir"
    "$BUILD_TOOLS/aapt" package -f -m --pseudo-localize \
        -M "$APP_DIR/AndroidManifest.xml" -S "$APP_DIR/res" -A "$APP_DIR/licenses" -I "$ANDROID_JAR" \
        -J "$work/generated" -F "$work/resources.apk"
    # Pseudo-localization adds configurations, not resources, so the ids and classes.dex are unchanged.
    cmp -s "$BUILD_DIR/generated/com/jjricks/volt/R.java" "$work/generated/com/jjricks/volt/R.java" || {
        echo "Pseudo build: aapt assigned different resource ids; refusing to reuse classes.dex." >&2
        return 1
    }
    navee_l10n_roundtrip "$work/resources.apk" --allow-pseudo
    cp "$work/resources.apk" "$work/unsigned.apk"
    zip -q -j "$work/unsigned.apk" "$BUILD_DIR"/dex/classes*.dex
    "$BUILD_TOOLS/zipalign" -P 16 -f 4 "$work/unsigned.apk" "$work/aligned.apk"
    "$BUILD_TOOLS/apksigner" sign --ks "$SIGNING_KEYSTORE" \
        --ks-key-alias "$SIGNING_ALIAS" --ks-pass env:NAVEE_SIGNING_PASSWORD \
        --key-pass env:NAVEE_SIGNING_PASSWORD \
        --out "$pseudo_apk" "$work/aligned.apk"
    "$BUILD_TOOLS/apksigner" verify "$pseudo_apk"
    "$BUILD_TOOLS/zipalign" -c -P 16 4 "$pseudo_apk"
    if navee_release_guard "$pseudo_apk" 2>/dev/null; then
        echo "Pseudo build: aapt added no en-XA or ar-XB configurations." >&2
        return 1
    fi
    echo "Built pseudo-locale APK (en-XA, ar-XB; never release it): $pseudo_apk"
}

navee_build_app() {
    navee_find_sdk
    navee_find_java
    mkdir -p "$BUILD_DIR" "$(dirname -- "$APK_PATH")"
    rm -rf -- "$BUILD_DIR/generated" "$BUILD_DIR/classes" "$BUILD_DIR/dex" "$BUILD_DIR/dependencies" \
        "$BUILD_DIR/l10n" "$BUILD_DIR/pseudo"
    mkdir -p "$BUILD_DIR/generated" "$BUILD_DIR/classes" "$BUILD_DIR/dex"

    python3 - "$APP_DIR/setup/owner-capture-guard.sh" "$BUILD_DIR/generated" <<'PYGUARD'
import json, pathlib, sys
script = pathlib.Path(sys.argv[1]).read_text()
target = pathlib.Path(sys.argv[2]) / "com/jjricks/volt/OwnerCaptureGuardScript.java"
target.parent.mkdir(parents=True, exist_ok=True)
target.write_text("package com.jjricks.volt; final class OwnerCaptureGuardScript { static final String TEXT = " + json.dumps(script) + "; }\n")
PYGUARD
    python3 "$APP_DIR/setup/shizuku-dependencies.py" --prepare "$BUILD_DIR/dependencies"
    local dep_classpath="" dep
    local dep_jars=()
    for dep in "$BUILD_DIR/dependencies/aidl-13.1.5.jar" "$BUILD_DIR/dependencies/annotation-1.3.0.jar" \
            "$BUILD_DIR/dependencies/api-13.1.5.jar" "$BUILD_DIR/dependencies/provider-13.1.5.jar" \
            "$BUILD_DIR/dependencies/shared-13.1.5.jar"; do
        dep_classpath="$dep_classpath:$dep"
        dep_jars+=("$dep")
    done
    for source_file in "$APP_DIR"/aidl/com/jjricks/volt/*.aidl; do
        "$BUILD_TOOLS/aidl" -I"$APP_DIR/aidl" -p"$SDK_DIR/platforms/android-36/framework.aidl" \
            -o"$BUILD_DIR/generated" "$source_file"
    done

    if navee_l10n_active; then
        python3 "$APP_DIR/tools/l10n/gen_catalog.py" --android --res "$APP_DIR/res" --lang "$APP_DIR/l10n/lang" \
            --out "$BUILD_DIR/generated" --tsv "$BUILD_DIR/l10n/en.expected.tsv"
    fi

    "$BUILD_TOOLS/aapt" package -f -m \
        -M "$APP_DIR/AndroidManifest.xml" -S "$APP_DIR/res" -A "$APP_DIR/licenses" -I "$ANDROID_JAR" \
        -J "$BUILD_DIR/generated" -F "$BUILD_DIR/resources.apk"
    navee_l10n_roundtrip "$BUILD_DIR/resources.apk"

    # javac argument files support quoted paths, including spaces in source paths.
    : > "$BUILD_DIR/sources.txt"
    while IFS= read -r -d '' source_file; do
        printf '"%s"\n' "$source_file" >> "$BUILD_DIR/sources.txt"
    done < <(find "$APP_DIR/src" "$BUILD_DIR/generated" -type f -name '*.java' -print0)
    [[ -f "$APP_DIR/src/com/jjricks/volt/MainActivity.java" ]] || {
        echo "MainActivity.java is missing from src/com/jjricks/volt/." >&2
        return 1
    }
    javac --release 8 -encoding UTF-8 \
        -classpath "$ANDROID_JAR:$BUILD_TOOLS/core-lambda-stubs.jar$dep_classpath" \
        -d "$BUILD_DIR/classes" "@$BUILD_DIR/sources.txt"
    jar cf "$BUILD_DIR/compiled.jar" -C "$BUILD_DIR/classes" .
    "$BUILD_TOOLS/d8" --lib "$ANDROID_JAR" --min-api 26 \
        --output "$BUILD_DIR/dex" "$BUILD_DIR/compiled.jar" "${dep_jars[@]}"
    cp "$BUILD_DIR/resources.apk" "$BUILD_DIR/unsigned.apk"
    zip -q -j "$BUILD_DIR/unsigned.apk" "$BUILD_DIR"/dex/classes*.dex
    "$BUILD_TOOLS/zipalign" -P 16 -f 4 "$BUILD_DIR/unsigned.apk" "$BUILD_DIR/aligned.apk"

    # Each fresh clone creates its own private, ignored signing key. Keep the same
    # key for future updates to that installation; never commit or share the key.
    navee_load_signing_identity true
    "$BUILD_TOOLS/apksigner" sign --ks "$SIGNING_KEYSTORE" \
        --ks-key-alias "$SIGNING_ALIAS" --ks-pass env:NAVEE_SIGNING_PASSWORD \
        --key-pass env:NAVEE_SIGNING_PASSWORD \
        --out "$APK_PATH" "$BUILD_DIR/aligned.apk"
    "$BUILD_TOOLS/apksigner" verify --verbose "$APK_PATH"
    "$BUILD_TOOLS/zipalign" -c -P 16 4 "$APK_PATH"
    navee_release_guard "$APK_PATH"
    # v0.5 adds optional map tiles. Bluetooth/authentication and recording remain
    # local; INTERNET is used only by the route viewer's replaceable tile source.
    echo "Built and verified: $APK_PATH"
    if [[ "${VOLT_PSEUDO_LOCALES:-}" == 1 ]]; then
        navee_build_pseudo
    fi
}

navee_load_config
if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
    navee_build_app
fi
