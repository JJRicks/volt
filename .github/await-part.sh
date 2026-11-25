#!/usr/bin/env bash
# Waits for a part of a CI job that an earlier command started in the background (.github/workflows/tests.yml,
# core-tests): <prefix>.pid names its process, which writes <prefix>.status (its exit code) when it ends.
# Fails when the process is gone without writing one, so a killed part can't hang the job or pass.
set -euo pipefail
prefix="$1"
pid="$(cat "$prefix.pid")"
until [[ -s "$prefix.status" ]]; do
    if ! kill -0 "$pid" 2>/dev/null; then
        sleep 1
        [[ -s "$prefix.status" ]] && break
        echo "::error::$(basename -- "$prefix") stopped without a result"
        cat "$prefix.log" 2>/dev/null || true
        exit 1
    fi
    sleep 2
done
