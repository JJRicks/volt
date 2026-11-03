#!/usr/bin/env bash
# build.sh's versionName: dev marker and commit, the release flag, and source trees that
# aren't their own git checkout. Temporary repositories only; no SDK, signing key or network.
set -euo pipefail
umask 077
TEST_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="$(cd -- "$TEST_DIR/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf -- "$WORK"' EXIT
VERSION="$(python3 -I -c 'import sys; from xml.etree import ElementTree as E; print(E.parse(sys.argv[1]).getroot().get("{http://schemas.android.com/apk/res/android}versionName"))' "$APP_DIR/AndroidManifest.xml")"
checks=0

g() { git -c user.name=test -c user.email=test@example.invalid -c commit.gpgsign=false -c core.hooksPath=/dev/null "$@"; }
# A copy of build.sh and the manifest: enough for navee_version_name.
tree() { mkdir -p "$1"; cp "$APP_DIR/build.sh" "$APP_DIR/AndroidManifest.xml" "$1/"; }
# Prints the versionName, or FAIL when build.sh refuses.
name() { local dir="$1"; shift; env "$@" bash -c 'source "$1/build.sh"; navee_version_name' _ "$dir" 2>/dev/null || echo FAIL; }
expect() {
    checks=$((checks + 1))
    [[ "$1" == "$2" ]] || { echo "build-version-test: $3: expected '$2', got '$1'" >&2; exit 1; }
}

# Its own checkout: the commit, then -dirty with any change, including untracked files.
tree "$WORK/own"; g -C "$WORK/own" init -q; g -C "$WORK/own" add -A; g -C "$WORK/own" commit -qm volt
sha="$(git -C "$WORK/own" rev-parse --short=7 HEAD)"
expect "$(name "$WORK/own")" "$VERSION-dev+$sha" "A clean checkout"
expect "$(name "$WORK/own" VOLT_RELEASE=0)" "$VERSION-dev+$sha" "VOLT_RELEASE=0 is a dev build"
expect "$(name "$WORK/own" VOLT_RELEASE=1)" "$VERSION" "A clean release build"
expect "$(name "$WORK/own" VOLT_RELEASE=yes)" "FAIL" "Other VOLT_RELEASE values are refused"
echo "# edit" >> "$WORK/own/build.sh"
expect "$(name "$WORK/own")" "$VERSION-dev+$sha-dirty" "A modified checkout"
expect "$(name "$WORK/own" VOLT_RELEASE=1)" "FAIL" "A release build refuses modified files"
g -C "$WORK/own" checkout -q -- build.sh; touch "$WORK/own/notes.txt"
expect "$(name "$WORK/own")" "$VERSION-dev+$sha-dirty" "Untracked files"
expect "$(name "$WORK/own" VOLT_RELEASE=1)" "FAIL" "A release build refuses untracked files"
rm "$WORK/own/notes.txt"
expect "$(name "$WORK/own" VOLT_RELEASE=1)" "$VERSION" "Clean again"

# A source ZIP unpacked inside an unrelated, dirty repository: never that repository's commit.
mkdir "$WORK/outer"; g -C "$WORK/outer" init -q; g -C "$WORK/outer" commit -q --allow-empty -m outer
tree "$WORK/outer/volt-src"
expect "$(name "$WORK/outer/volt-src")" "$VERSION-dev" "A source tree inside another repository"
expect "$(name "$WORK/outer/volt-src" VOLT_RELEASE=1)" "$VERSION" "A release build of that tree"
# Even when the outer repository tracks it: it still isn't Volt's own checkout.
g -C "$WORK/outer" add -A; g -C "$WORK/outer" commit -qm vendored
expect "$(name "$WORK/outer/volt-src")" "$VERSION-dev" "A tree vendored by another repository"

# No git repository at all.
tree "$WORK/plain"
expect "$(GIT_CEILING_DIRECTORIES="$WORK" name "$WORK/plain")" "$VERSION-dev" "No git"

# A symlinked path to a checkout is still that checkout.
ln -s "$WORK/own" "$WORK/link"
expect "$(name "$WORK/link")" "$VERSION-dev+$sha" "A symlinked checkout"

echo "build.sh versionName tests passed: $checks checks"
