#!/usr/bin/env bash
# G9 golden reports: the diagnostics JSON, the ride CSV, the phone ride CSV and its GPX must stay
# byte-identical to tests/l10n/golden/. Compiles tests/l10n/GoldenReports.java with javac finding the
# pure classes it needs under src/ and the generated catalog (T, M, EnglishText, AppLanguages, as
# tests/run.sh generates it: no Android SDK), runs it under several default locales and time zones with
# the built-in English, then with a non-English text source and with one that throws on any use, and
# compares every output with the goldens. So a class the reports reach may use the catalog for its
# display text, while the reports themselves must never print or even read it.
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
# The catalog the app's display text comes from, generated as tests/run.sh does (without --android).
python3 "$APP_DIR/tools/l10n/gen_catalog.py" --res "$APP_DIR/res" --lang "$APP_DIR/l10n/lang" --out "$WORK/gen" >/dev/null
javac --release 8 -Xlint:-options -encoding UTF-8 -implicit:class -sourcepath "$APP_DIR/src:$WORK/gen" \
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

# The same reports with a translated (key-echo, non-English) text source and with one that throws on any
# use: a report that prints catalog text, or reads it at all, differs or stops here, as
# ReportsStayEnglishTest requires of the report builders.
for text in foreign throwing; do
    out="$WORK/out-$text"
    if ! java -Duser.language=en -Duser.country=US -Duser.timezone=America/Boise \
            -cp "$WORK/classes" com.jjricks.volt.GoldenReports "$out" "$text" >"$WORK/$text.log" 2>&1; then
        echo "golden: the reports failed with a $text text source installed (a report reads the catalog):" >&2
        head -20 "$WORK/$text.log" >&2
        exit 1
    elif ! diff -r "$first" "$out" >/dev/null; then
        echo "golden: the reports changed with a $text text source installed (a report prints catalog text):" >&2
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
    echo "golden: PASS: $(ls "$GOLDEN_DIR" | wc -l | tr -d ' ') report file(s) byte-identical under ${#RUNS[@]} locale and time zone settings, and with a non-English and a throwing text source"
fi
exit "$status"
