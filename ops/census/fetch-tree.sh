#!/usr/bin/env bash
# usage: fetch-tree.sh SHA DIR: a fresh, shallow checkout of one commit of this repository in DIR,
# as the top of its own git checkout (build.sh's VOLT_RELEASE=1 check needs that).
set -euo pipefail
SHA=$1; DIR=$2
[[ "$SHA" =~ ^[0-9a-f]{40}$ ]] || { echo "fetch-tree: not a full commit SHA" >&2; exit 2; }
[[ ! -e "$DIR" ]] || { echo "fetch-tree: $DIR already exists" >&2; exit 2; }
URL="${GITHUB_SERVER_URL:-https://github.com}/${GITHUB_REPOSITORY:-JJRicks/volt}"
git -c init.defaultBranch=census init -q "$DIR"
git -C "$DIR" fetch -q --depth 1 --no-tags "$URL" "$SHA"
git -C "$DIR" -c advice.detachedHead=false checkout -q --detach FETCH_HEAD
[[ "$(git -C "$DIR" rev-parse HEAD)" == "$SHA" ]] || { echo "fetch-tree: fetched the wrong commit" >&2; exit 1; }
echo "fetched $SHA into $DIR"
