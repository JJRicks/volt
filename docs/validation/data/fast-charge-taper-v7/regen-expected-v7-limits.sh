#!/usr/bin/env bash
# Writes expected-v7-limits.csv: this checkout's tests/FastChargeTaperReplay.java compiled against
# ut5-fast-taper-v7 (f529f43) and replayed over sessions.csv at 95% and 80% charge limits.
# Usage: bash regen-expected-v7-limits.sh [COMMIT]   (needs git and a JDK: JAVA_HOME or javac on PATH)
set -euo pipefail
here="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo="$(git -C "$here" rev-parse --show-toplevel)"
commit="${1:-f529f43ec3181e2e6bb41f812626eb945b7cf524}"
bin="${JAVA_HOME:+$JAVA_HOME/bin/}"
tmp="$(mktemp -d)"
trap 'rm -rf -- "$tmp"' EXIT
mkdir -p "$tmp/base" "$tmp/gen" "$tmp/classes" "$tmp/test/com/jjricks/volt"
git -C "$repo" archive "$commit" src res l10n tools/l10n | tar -x -C "$tmp/base"
python3 "$tmp/base/tools/l10n/gen_catalog.py" --res "$tmp/base/res" --lang "$tmp/base/l10n/lang" --out "$tmp/gen" >/dev/null
cp "$repo/tests/FastChargeTaperReplay.java" "$tmp/test/com/jjricks/volt/"
"${bin}javac" -nowarn -encoding UTF-8 -d "$tmp/classes" -sourcepath "$tmp/test:$tmp/gen:$tmp/base/src" \
    "$tmp/test/com/jjricks/volt/FastChargeTaperReplay.java"
"${bin}java" -cp "$tmp/classes" com.jjricks.volt.FastChargeTaperReplay "$here/sessions.csv" --limits > "$tmp/out.csv"
mv "$tmp/out.csv" "$here/expected-v7-limits.csv"
echo "wrote $here/expected-v7-limits.csv from $commit"
