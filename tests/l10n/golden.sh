#!/usr/bin/env bash
# G9 golden reports: the diagnostics JSON, the ride CSV, the phone ride CSV and its GPX must stay
# byte-identical to tests/l10n/golden/. Compiles tests/l10n/GoldenReports.java with javac finding the
# pure classes it needs under src/ (no Android SDK), runs it under several default locales and time
# zones, and compares every output with the goldens.
#   bash tests/l10n/golden.sh            check (CI and tools/l10n/prove-english.sh)
#   bash tests/l10n/golden.sh --update   rewrite the goldens; only for an intended, reviewed format change
set -euo pipefail
umask 077
L10N_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$L10N_DIR/../../build.sh"
navee_find_java
GOLDEN_DIR="$L10N_DIR/golden"
WORK="$NAVEE_BUILD_DIR/l10n-golden"
rm -rf -- "$WORK"
mkdir -p "$WORK/classes"
javac --release 8 -Xlint:-options -encoding UTF-8 -implicit:class -sourcepath "$APP_DIR/src" \
    -d "$WORK/classes" "$L10N_DIR/GoldenReports.java"

# Reports must not depend on the phone's language, region or time zone. Turkish catches
# locale-sensitive case changes; German and Arabic catch locale-sensitive numbers.
RUNS=("en US America/Boise" "de DE Europe/Berlin" "tr TR Europe/Istanbul" "ar EG Asia/Kolkata")
first=""
for run in "${RUNS[@]}"; do
    read -r language country zone <<<"$run"
    out="$WORK/out-$language-$country"
    java -Duser.language="$language" -Duser.country="$country" -Duser.timezone="$zone" \
        -cp "$WORK/classes" com.jjricks.volt.GoldenReports "$out"
    if [[ -z "$first" ]]; then
        first="$out"
    elif ! diff -r "$first" "$out" >/dev/null; then
        echo "golden: output under $language-$country ($zone) differs from ${RUNS[0]}:" >&2
        diff -r "$first" "$out" | head -20 >&2
        exit 1
    fi
done

if [[ "${1:-}" == "--update" ]]; then
    mkdir -p "$GOLDEN_DIR"
    rm -f -- "$GOLDEN_DIR"/*
    cp "$first"/* "$GOLDEN_DIR"/
    echo "golden: rewrote $(ls "$GOLDEN_DIR" | wc -l | tr -d ' ') golden file(s) in $GOLDEN_DIR"
    exit 0
fi

status=0
for golden in "$GOLDEN_DIR"/*; do
    name="$(basename -- "$golden")"
    if [[ ! -f "$first/$name" ]]; then
        echo "golden: $name is no longer produced" >&2
        status=1
    elif ! cmp -s "$golden" "$first/$name"; then
        echo "golden: $name changed:" >&2
        diff "$golden" "$first/$name" | head -20 >&2
        status=1
    fi
done
for produced in "$first"/*; do
    [[ -f "$GOLDEN_DIR/$(basename -- "$produced")" ]] || {
        echo "golden: $(basename -- "$produced") has no golden file; review it and run --update" >&2
        status=1
    }
done
if [[ "$status" == 0 ]]; then
    echo "golden: PASS: $(ls "$GOLDEN_DIR" | wc -l | tr -d ' ') report file(s) byte-identical under ${#RUNS[@]} locale and time zone settings"
fi
exit "$status"
