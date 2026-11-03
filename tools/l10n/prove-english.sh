#!/usr/bin/env bash
# The English-unchanged proof (localization plan, section 2) for the commits since <base>: runs every
# gate that works on this computer and prints a summary block to paste into the PR description and the
# Codex handoff comment. Commit first: the git-based gates read HEAD.
#
#   tools/l10n/prove-english.sh <base> [--same-apk] [--pseudo] [--skip-build] [--skip-tests]
#
#   --same-apk    build the merge base and HEAD in temporary worktrees and require byte-identical
#                 classes.dex and resources.arsc (B0a, and any batch that must not change the app)
#   --pseudo      also build HEAD with VOLT_PSEUDO_LOCALES=1 and check the pseudo APK: en-XA and ar-XB
#                 present, the release guard rejects it, and its classes.dex equals the release one
#   --skip-build  do not run build.sh here (G3 then reports "not run")
#   --skip-tests  do not run tests/run.sh (G6 then reports "not run")
#
# G7 (the emulator text census) never runs here: run android-tests/run-release-sweep.py --census twice
# for base and once for head on an English-pinned emulator, then tools/l10n/census_diff.py --base A
# --base A2 HEAD, and paste its last lines (l10n/README.md, step 7).
set -uo pipefail
umask 077

TOOLS_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(git -C "$TOOLS_DIR" rev-parse --show-toplevel)"
cd "$ROOT" || exit 2

usage() { sed -n '2,17p' "$TOOLS_DIR/prove-english.sh" | sed 's/^# \{0,1\}//'; exit 2; }
[[ $# -ge 1 ]] || usage
BASE_ARG="$1"; shift
SAME_APK=0; PSEUDO=0; SKIP_BUILD=0; SKIP_TESTS=0
for arg in "$@"; do
    case "$arg" in
        --same-apk) SAME_APK=1 ;;
        --pseudo) PSEUDO=1 ;;
        --skip-build) SKIP_BUILD=1 ;;
        --skip-tests) SKIP_TESTS=1 ;;
        *) usage ;;
    esac
done
BASE="$(git rev-parse --verify "$BASE_ARG^{commit}" 2>/dev/null)" || { echo "Unknown base: $BASE_ARG" >&2; exit 2; }
HEAD_SHA="$(git rev-parse HEAD)"
MERGE="$(git merge-base "$BASE" HEAD)" || { echo "No merge base with $BASE_ARG" >&2; exit 2; }
DIRTY=""
[[ -z "$(git status --porcelain --untracked-files=no)" ]] || DIRTY=" (uncommitted changes present; git gates read HEAD)"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/volt-prove-english.XXXXXX")"
WORKTREES=()
cleanup() {
    local tree
    for tree in "${WORKTREES[@]+"${WORKTREES[@]}"}"; do git worktree remove --force "$tree" >/dev/null 2>&1; done
    rm -rf -- "$WORK"
}
trap cleanup EXIT

ROWS=()
FAILED=0
record() { # gate result detail
    ROWS+=("| $1 | $2 | $3 |")
    [[ "$2" == FAIL ]] && FAILED=1
    printf '%-34s %s  %s\n' "$1" "$2" "$3" >&2
}
last_summary() { # log -> the "tool: PASS|FAILED: detail" line's detail
    grep -E '^[a-z_]+: (PASS|FAILED)' "$1" | tail -1 | sed -E 's/^[a-z_]+: (PASS|FAILED): //'
}
show_failure() { echo "---- $1 (last lines of $2)" >&2; tail -25 "$2" >&2; }
gate() { # label log command...
    local label="$1" log="$2"; shift 2
    if "$@" >"$log" 2>&1; then
        record "$label" PASS "$(last_summary "$log")"
    else
        record "$label" FAIL "$(last_summary "$log")"
        show_failure "$label" "$log"
    fi
}

KIND="$(python3 tools/l10n/ci_gates.py --base "$BASE" --print-mode 2>/dev/null || echo unknown)"
echo "prove-english: base $(git rev-parse --short=12 "$BASE"), merge base $(git rev-parse --short=12 "$MERGE"), head $(git rev-parse --short=12 HEAD), $KIND change$DIRTY" >&2

# Tools.
log="$WORK/unittest.log"
if python3 -m unittest discover -s tools/l10n >"$log" 2>&1; then
    record "Proof tools' unit tests" PASS "$(grep -Eo 'Ran [0-9]+ tests?' "$log" | tail -1)"
else
    record "Proof tools' unit tests" FAIL "$(grep -Eo 'Ran [0-9]+ tests?' "$log" | tail -1)"; show_failure unittest "$log"
fi
log="$WORK/catalog.log"
if python3 tools/l10n/gen_catalog.py --check >"$log" 2>&1; then
    record "Catalog XML (gen_catalog --check)" PASS "$(tail -1 "$log" | sed 's/^gen_catalog: //')"
else
    record "Catalog XML (gen_catalog --check)" FAIL "$(tail -1 "$log" | sed 's/^gen_catalog: //')"; show_failure catalog "$log"
fi

# G1, G4, G5.
gate "G1 ledger of moved literals" "$WORK/g1.log" python3 tools/l10n/ledger_check.py --base "$BASE"
if [[ -f tests/l10n/LedgerTest.java ]]; then
    record "G2 template equivalence" "in G6" "tests/l10n/LedgerTest runs inside tests/run.sh"
else
    record "G2 template equivalence" "n/a" "tests/l10n/LedgerTest arrives with the catalog (B0b)"
fi
if [[ "$KIND" != ordinary && "$KIND" != english-change ]]; then  # a batch, or a kind ci_gates could not tell
    gate "G4 frozen test statements" "$WORK/g4.log" python3 tools/l10n/frozen_literals.py --base "$BASE"
else
    # As in CI: G4 binds batches only. An English change lists its test changes, and an ordinary change
    # (a feature, a fix, a tooling change) may edit tests, so it is listed too but never fails here.
    log="$WORK/g4.log"
    python3 tools/l10n/frozen_literals.py --base "$BASE" --report >"$log" 2>&1
    status=$?
    detail="$(grep -E '^frozen_literals: (PASS|REPORT|FAILED): ' "$log" | tail -1 | sed -E 's/^frozen_literals: [A-Z]+: //')"
    if [[ "$status" != 0 ]]; then
        record "G4 frozen test statements" FAIL "${detail:-frozen_literals.py failed}"; show_failure G4 "$log"
    elif grep -q '^frozen_literals: REPORT: ' "$log"; then
        record "G4 frozen test statements" REPORT "$detail; listed, not enforced: G4 binds batches only (this is an ordinary or English change: $KIND)"
        show_failure "G4 (report)" "$log"
    else
        record "G4 frozen test statements" PASS "$detail"
    fi
fi
gate "G5 characterization first" "$WORK/g5.log" python3 tools/l10n/char_first.py --base "$BASE"
FIRST="$(python3 tools/l10n/char_first.py --base "$BASE" --print-first 2>/dev/null || true)"
if [[ -n "$FIRST" && "$SKIP_TESTS" == 0 ]]; then
    tree="$WORK/first"
    if git worktree add --detach "$tree" "$FIRST" >/dev/null 2>&1; then
        WORKTREES+=("$tree")
        log="$WORK/g5-first.log"
        # The characterization tests must be compiled by tests/run.sh (a tests/l10n/*.sources list, B0b on).
        unwired=()
        while IFS= read -r test_file; do
            [[ -n "$test_file" ]] || continue
            grep -qsF "$test_file" "$tree"/tests/l10n/*.sources || unwired+=("$test_file")
        done < <(git -C "$tree" diff --name-only "$FIRST^" "$FIRST" -- tests/l10n/characterization | grep '\.java$')
        if (( ${#unwired[@]} )); then
            record "G5 tests pass at the first commit" FAIL "not in any tests/l10n/*.sources, so tests/run.sh never runs them: ${unwired[*]}"
        elif (cd "$tree" && NAVEE_BUILD_DIR="$WORK/first-build" bash tests/run.sh && \
                { [[ ! -f tests/l10n/golden.sh ]] || NAVEE_BUILD_DIR="$WORK/first-build" bash tests/l10n/golden.sh; }) >"$log" 2>&1; then
            record "G5 tests pass at the first commit" PASS "tests/run.sh and tests/l10n/golden.sh at $(git rev-parse --short=12 "$FIRST") (base code plus the new tests)"
        else
            record "G5 tests pass at the first commit" FAIL "tests/run.sh or golden.sh at $(git rev-parse --short=12 "$FIRST")"; show_failure G5 "$log"
        fi
    fi
fi

# G3 and G6.
if [[ "$SKIP_BUILD" == 1 ]]; then
    record "G3 aapt round-trip (build.sh)" "not run" "--skip-build"
else
    log="$WORK/build.log"
    if bash build.sh >"$log" 2>&1; then
        if grep -q '^roundtrip: ' "$log"; then
            record "G3 aapt round-trip (build.sh)" PASS "$(grep '^roundtrip: ' "$log" | tail -1 | sed 's/^roundtrip: //')"
        else
            record "G3 aapt round-trip (build.sh)" PASS "build passed; round-trip dormant until res/values/strings_*.xml exists"
        fi
    else
        record "G3 aapt round-trip (build.sh)" FAIL "build.sh failed"; show_failure build "$log"
    fi
fi
if [[ "$SKIP_TESTS" == 1 ]]; then
    record "G6 existing suites (tests/run.sh)" "not run" "--skip-tests"
else
    log="$WORK/g6.log"
    if bash tests/run.sh >"$log" 2>&1; then
        record "G6 existing suites (tests/run.sh)" PASS "$(grep -c ' passed' "$log" | tr -d ' ') suites reported passing"
    else
        record "G6 existing suites (tests/run.sh)" FAIL "tests/run.sh failed"; show_failure G6 "$log"
    fi
fi
record "G7 screen text census" "not run" "emulator: run-release-sweep.py --census, then census_diff.py"

# G8, G9, G10.
gate "G8 hard-coded text lint" "$WORK/g8.log" python3 tools/l10n/hardcoded_lint.py --check --base "$BASE" --tight
log="$WORK/g9.log"
if bash tests/l10n/golden.sh >"$log" 2>&1; then
    if git cat-file -e "$MERGE:tests/l10n/golden" 2>/dev/null && ! git diff --quiet "$MERGE" HEAD -- tests/l10n/golden; then
        if [[ "$KIND" == english-change ]]; then
            record "G9 reports stay English (goldens)" REPORT "tests/l10n/golden changed since the merge base (owner-approved change)"
        else
            record "G9 reports stay English (goldens)" FAIL "tests/l10n/golden changed since the merge base"
        fi
    else
        record "G9 reports stay English (goldens)" PASS "$(tail -1 "$log" | sed 's/^golden: PASS: //')"
    fi
else
    record "G9 reports stay English (goldens)" FAIL "$(tail -1 "$log")"; show_failure G9 "$log"
fi
# The same list CI uses (ci_gates.is_tooling): tools/l10n, workflows, tests/l10n outside
# characterization/, golden/ and *.sources, tests/run.sh, build.sh and android-tests/build.sh.
tooling_files=($(python3 tools/l10n/ci_gates.py --base "$BASE" --print-tooling))
tooling="${#tooling_files[@]} file(s)"
(( ${#tooling_files[@]} )) && tooling+=", e.g. ${tooling_files[0]}"
if (( ${#tooling_files[@]} == 0 )); then
    record "Proof tooling unchanged" PASS "tools/l10n, workflows, tests/l10n tooling, tests/run.sh and build scripts as at the merge base"
elif [[ "$KIND" == ordinary ]]; then
    record "Proof tooling unchanged" "n/a" "a tooling change, not a batch: $tooling"
else
    record "Proof tooling unchanged" FAIL "changed in a $KIND pull request: $tooling"
fi
if [[ "$KIND" == english-change ]]; then
    ledger_files=($(git diff --name-only "$MERGE" HEAD -- l10n/ledger))
    if (( ${#ledger_files[@]} )); then
        record "No ledger rows in an English change" FAIL "an English change must not also extract text: ${ledger_files[*]}"
    else
        record "No ledger rows in an English change" PASS "l10n/ledger unchanged"
    fi
fi
if [[ -f tests/l10n/FmtLegacyTest.java ]]; then
    record "G10 format legacy parity" "in G6" "tests/l10n/FmtLegacyTest runs inside tests/run.sh"
else
    record "G10 format legacy parity" "n/a" "tests/l10n/FmtLegacyTest arrives with Fmt (B0b, B1)"
fi

# APK identity and the pseudo build, in clean worktrees with a throwaway signing key.
build_tree() { # rev name [extra env]
    local rev="$1" name="$2" tree="$WORK/$2"
    shift 2
    git worktree add --detach "$tree" "$rev" >/dev/null 2>&1 || return 1
    WORKTREES+=("$tree")
    if [[ -d "$ROOT/.local/shizuku-dependencies" ]]; then
        mkdir -p "$tree/.local" && cp -R "$ROOT/.local/shizuku-dependencies" "$tree/.local/"
    fi
    (cd "$tree" && env -u NAVEE_SIGNING_KEYSTORE -u NAVEE_SIGNING_PASSWORD -u NAVEE_SIGNING_ALIAS \
        NAVEE_BUILD_DIR="$WORK/$name-build" NAVEE_KEY_DIR="$WORK/throwaway-key" NAVEE_APK_PATH="$WORK/$name.apk" \
        "$@" bash build.sh) >"$WORK/$name-build.log" 2>&1
}
member_hash() { unzip -p "$1" "$2" | shasum -a 256 | cut -c1-64; }
if [[ "$SAME_APK" == 1 || "$PSEUDO" == 1 ]]; then
    head_env=()
    [[ "$PSEUDO" == 1 ]] && head_env=(VOLT_PSEUDO_LOCALES=1)
    if build_tree HEAD head "${head_env[@]+"${head_env[@]}"}"; then
        HEAD_DEX="$(member_hash "$WORK/head.apk" classes.dex)"; HEAD_ARSC="$(member_hash "$WORK/head.apk" resources.arsc)"
    else
        record "Head build (clean worktree)" FAIL "see build log"; show_failure "head build" "$WORK/head-build.log"
    fi
fi
if [[ "$SAME_APK" == 1 && -n "${HEAD_DEX:-}" ]]; then
    if build_tree "$MERGE" base; then
        BASE_DEX="$(member_hash "$WORK/base.apk" classes.dex)"; BASE_ARSC="$(member_hash "$WORK/base.apk" resources.arsc)"
        if [[ "$BASE_DEX" == "$HEAD_DEX" && "$BASE_ARSC" == "$HEAD_ARSC" ]]; then
            record "Release APK identical to base" PASS "classes.dex ${HEAD_DEX:0:16}…, resources.arsc ${HEAD_ARSC:0:16}… (sha256)"
        else
            record "Release APK identical to base" FAIL "dex ${BASE_DEX:0:12}→${HEAD_DEX:0:12}, arsc ${BASE_ARSC:0:12}→${HEAD_ARSC:0:12}"
        fi
    else
        record "Release APK identical to base" FAIL "base build failed"; show_failure "base build" "$WORK/base-build.log"
    fi
fi
if [[ "$PSEUDO" == 1 && -n "${HEAD_DEX:-}" ]]; then
    pseudo="$WORK/head/build/pseudo/volt-pseudo.apk"
    configs="$(python3 tools/l10n/roundtrip.py --configs --apk "$pseudo" 2>/dev/null | tr '\n' ' ')"
    guard="$( (source build.sh && navee_find_sdk && navee_release_guard "$pseudo") 2>&1 >/dev/null )"
    guard_status=$?
    pseudo_dex="$(member_hash "$pseudo" classes.dex)"
    if [[ "$configs" == *en-rXA* && "$configs" == *ar-rXB* && "$guard_status" != 0 && "$pseudo_dex" == "$HEAD_DEX" ]] && \
            (source build.sh && navee_find_sdk && navee_release_guard "$WORK/head.apk") >/dev/null 2>&1; then
        record "Pseudo-locale APK" PASS "en-XA and ar-XB present; release guard rejects it and passes the release APK; same classes.dex"
    else
        record "Pseudo-locale APK" FAIL "configs: $configs; guard: ${guard:-accepted}; dex ${pseudo_dex:0:12} vs ${HEAD_DEX:0:12}"
    fi
fi

{
    echo
    echo "<!-- l10n-english-proof -->"
    echo "**English proof** · \`tools/l10n/prove-english.sh $BASE_ARG\` · $KIND change · merge base \`$(git rev-parse --short=12 "$MERGE")\` · head \`$(git rev-parse --short=12 HEAD)\`$DIRTY"
    echo
    echo "| Gate | Result | Detail |"
    echo "|---|---|---|"
    printf '%s\n' "${ROWS[@]}"
    echo
    if [[ "$FAILED" == 0 ]]; then echo "Result: every gate that runs locally passed."; else echo "Result: FAILED (see above)."; fi
}
exit "$FAILED"
