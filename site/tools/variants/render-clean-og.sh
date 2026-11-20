#!/bin/bash
# Renders the "clean" homepage variant's link-preview card with a local Google Chrome.
# The PNG is committed as site/variants/clean/og.png; it overrides the shared og.png for that variant only.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"; out="$here/../../variants/clean/og.png"
chrome="${CHROME:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"
profile="$(mktemp -d)"; trap 'rm -rf "$profile"' EXIT
"$chrome" --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=1 --virtual-time-budget=4000 \
  --user-data-dir="$profile" --window-size=1200,630 --screenshot="$out" "file://$here/clean-og.html" >/dev/null 2>&1
echo "Rendered $out"
