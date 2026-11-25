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
# G5's first-commit run is "not run" when that commit only adds rows to existing characterization listings
# (char_first.py --listing-additions); the new rows are left to the run at head (G6).
#
# G7 (the emulator text census) never runs here: run android-tests/run-release-sweep.py --census twice
# for base and once for head on an English-pinned emulator, then tools/l10n/census_diff.py --base A
# --base A2 HEAD, and paste its last lines (l10n/README.md, step 7).
#
# Each kind gets the rules ci_gates.py gives it. On an ordinary change G1 and G5 read "not applied". A feature-text
# change (new English alongside a feature) is held to a batch's gates, except that G4 lists its test edits and its
# feature tests and runners are listed for review; two more rows check that it only adds English and that every proof
# test still runs.
set -uo pipefail
umask 077

TOOLS_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(git -C "$TOOLS_DIR" rev-parse --show-toplevel)"
cd "$ROOT" || exit 2

usage() { sed -n '2,25p' "$TOOLS_DIR/prove-english.sh" | sed 's/^# \{0,1\}//'; exit 2; }
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
# The same rule as ci_gates.py (plan): G1 binds a batch and an English change, and a kind ci_gates could not
# tell. An ordinary change may change English, so its report says G1 was not applied, never PASS, and only
# the ledger check CI holds it to runs.
if [[ "$KIND" == ordinary ]]; then
    record "G1 ledger of moved literals" "not applied" "an ordinary change may change a literal, so this run does not show English is unchanged"
    gate "Ledgers agree with the catalog" "$WORK/g1.log" python3 tools/l10n/ledger_check.py --catalog-only
else
    gate "G1 ledger of moved literals" "$WORK/g1.log" python3 tools/l10n/ledger_check.py --base "$BASE"
fi
if [[ -f tests/l10n/LedgerTest.java ]]; then
    record "G2 template equivalence" "in G6" "tests/l10n/LedgerTest runs inside tests/run.sh"
else
    record "G2 template equivalence" "n/a" "tests/l10n/LedgerTest arrives with the catalog (B0b)"
fi
if [[ "$KIND" != ordinary && "$KIND" != english-change && "$KIND" != feature-text ]]; then  # a batch, or a kind ci_gates could not tell
    gate "G4 frozen test statements" "$WORK/g4.log" python3 tools/l10n/frozen_literals.py --base "$BASE"
else
    # As in CI: G4 binds batches whole. An English change lists its test changes, and a feature-text change lists
    # its feature tests' and other tests' edits (its characterization tests stay strict, and its proof tests frozen
    # as tooling, below); in both, a type-shadowing test file or an added exit fails (frozen_literals ALWAYS_STRICT).
    # The arguments are the ones ci_gates.py passes. An ordinary change (a feature, a fix, a tooling change) may edit
    # tests, so its edits are listed but never fail here.
    log="$WORK/g4.log"
    g4_args=()
    if [[ "$KIND" == ordinary ]]; then
        g4_args=(--report)
    else
        while IFS= read -r arg; do g4_args+=("$arg"); done < <(python3 tools/l10n/ci_gates.py --base "$BASE" --print-g4-args)
    fi
    python3 tools/l10n/frozen_literals.py --base "$BASE" ${g4_args[@]+"${g4_args[@]}"} >"$log" 2>&1
    status=$?
    detail="$(grep -E '^frozen_literals: (PASS|REPORT|FAILED): ' "$log" | tail -1 | sed -E 's/^frozen_literals: [A-Z]+: //')"
    if [[ "$status" != 0 ]]; then
        record "G4 frozen test statements" FAIL "${detail:-frozen_literals.py failed}"; show_failure G4 "$log"
    elif grep -q '^frozen_literals: REPORT: ' "$log"; then
        record "G4 frozen test statements" REPORT "$detail; listed, not enforced: G4 binds batches whole (this is a $KIND change)"
        show_failure "G4 (report)" "$log"
    else
        record "G4 frozen test statements" PASS "$detail"
    fi
fi
# The active entries of the tests/l10n/*.sources lists in the tree $1, read as tests/run.sh reads them:
# everything from "#" on is a comment, the rest is trimmed, and blank lines are skipped. tests/run.sh runs
# only these, so a characterization test counts as listed only when one of them is exactly its path.
active_sources() {
    local list line
    for list in "$1"/tests/l10n/*.sources; do
        [[ -f "$list" ]] || continue
        while IFS= read -r line || [[ -n "$line" ]]; do
            line="${line%%#*}"; line="${line#"${line%%[![:space:]]*}"}"; line="${line%"${line##*[![:space:]]}"}"
            [[ -n "$line" ]] && printf '%s\n' "$line"
        done < "$list"
    done
    return 0
}
# As in CI (ci_gates.plan): G5 binds a batch, an English change and a kind ci_gates could not tell. An
# ordinary change may edit characterization tests along with code, so G5 reads "not applied", never PASS,
# and nothing runs at a first commit; G6 still runs every listed test at head.
FIRST=""
if [[ "$KIND" == ordinary ]]; then
    record "G5 characterization first" "not applied" "an ordinary change may edit characterization tests along with code; G5 binds batches and English changes"
else
    gate "G5 characterization first" "$WORK/g5.log" python3 tools/l10n/char_first.py --base "$BASE"
    FIRST="$(python3 tools/l10n/char_first.py --base "$BASE" --print-first 2>/dev/null || true)"
fi
if [[ -n "$FIRST" && "$KIND" == english-change ]]; then
    # An owner-approved English change rewrites its characterization tests to pin the new English, which the
    # base code does not print yet, so they cannot pass at the first commit. Their order is still G5's (above),
    # and G6 runs them at head, where they must pass. G6 runs only what a tests/l10n/*.sources list names, so
    # each one must be listed at head, or it would never run (checked with or without --skip-tests).
    unwired=(); active="$(active_sources .)"
    while IFS= read -r test_file; do
        [[ -n "$test_file" ]] || continue
        grep -qxF -- "$test_file" <<<"$active" || unwired+=("$test_file")
    done < <(git diff --name-only --diff-filter=d "$FIRST^" "$FIRST" -- tests/l10n/characterization | grep '\.java$')
    if (( ${#unwired[@]} )); then
        record "G5 tests pass at the first commit" FAIL "not an active entry of any tests/l10n/*.sources list at head, so tests/run.sh never runs them: ${unwired[*]}"
    else
        record "G5 tests pass at the first commit" "n/a" "an English change's characterization tests pin the new English, which the base code does not print; G5 checks their order, and each is an active entry of a tests/l10n/*.sources list at head, so G6 runs them there"
    fi
elif [[ -n "$FIRST" && "$SKIP_TESTS" == 0 ]] && \
        additions="$(python3 tools/l10n/char_first.py --base "$BASE" --listing-additions 2>/dev/null)"; then
    # Owner-approved Nov 13 2026: a first commit that only adds rows to existing listings (.tsv data an
    # unchanged characterization test compares line for line) cannot pass there when a row is for an entry
    # new in this pull request, by construction. Its existing rows are byte-for-byte and in order what the
    # merge base had, and main passes them; the new rows are left to G6 at head. Nothing ran, so never PASS.
    # Any other first commit (a .java test, a new file, a changed, removed or reordered row) runs below.
    record "G5 tests pass at the first commit" "not run" "$additions"
elif [[ -n "$FIRST" && "$SKIP_TESTS" == 0 ]]; then
    tree="$WORK/first"
    if git worktree add --detach "$tree" "$FIRST" >/dev/null 2>&1; then
        WORKTREES+=("$tree")
        log="$WORK/g5-first.log"
        # The characterization tests must be compiled by tests/run.sh (a tests/l10n/*.sources list, B0b on).
        unwired=(); active="$(active_sources "$tree")"
        while IFS= read -r test_file; do
            [[ -n "$test_file" ]] || continue
            grep -qxF -- "$test_file" <<<"$active" || unwired+=("$test_file")
        done < <(git -C "$tree" diff --name-only "$FIRST^" "$FIRST" -- tests/l10n/characterization | grep '\.java$')
        if (( ${#unwired[@]} )); then
            record "G5 tests pass at the first commit" FAIL "not an active entry of any tests/l10n/*.sources list, so tests/run.sh never runs them: ${unwired[*]}"
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
# A feature-text change may edit tests/run.sh, so a pass of it alone does not show that the proof suites ran. G6 then
# runs it with JAVA_HOME pointing at a copy of the JDK's bin/ whose java records each main class it runs and its exit
# status (run.sh's navee_find_java puts $JAVA_HOME/bin first on PATH), and every proof suite ci_gates names
# (--print-proof-suites: the tests/l10n/*.sources entries that are not feature tests, characterization included)
# must have run and exited 0, compared by name. A run.sh that skips the recording java records nothing and fails.
real_java_bin() { # the directory of the JDK tests/run.sh would use
    local home="${JAVA_HOME:-}"
    if [[ -z "$home" && -f build.sh ]]; then
        home="$(source ./build.sh >/dev/null 2>&1 && navee_find_java >/dev/null 2>&1 && printf '%s' "${JAVA_HOME:-}")"
    fi
    if [[ -z "$home" && -x /usr/libexec/java_home ]]; then home="$(/usr/libexec/java_home 2>/dev/null || true)"; fi
    if [[ -n "$home" && -x "$home/bin/java" ]]; then (cd "$home/bin" && pwd -P); return; fi
    local java; java="$(command -v java)" || return 1
    python3 -c 'import os, sys; print(os.path.dirname(os.path.realpath(sys.argv[1])))' "$java"
}
recording_jdk() { # $1 = record file; prints the JAVA_HOME of a JDK whose java records main classes there
    local real tool shim="$WORK/recording-jdk"
    real="$(real_java_bin)" && [[ -x "$real/java" && -x "$real/javac" ]] || return 1
    mkdir -p "$shim/bin"
    for tool in "$real"/*; do [[ "$(basename -- "$tool")" == java ]] || ln -s "$tool" "$shim/bin/"; done
    cat >"$shim/bin/java" <<SHIM
#!/usr/bin/env bash
# Finds the main class the way the java launcher does: an @argfile's words take its place, the options that take a
# separate value skip it (-cp, --add-opens and the rest), an option joined to its value (-Dx=y, -javaagent:a.jar,
# --add-opens=x) is one word, and the first other word is the class. -jar and -m run no class by name.
args=()
for arg in "\$@"; do
    if [[ "\$arg" == @* && "\$arg" != @@* && -f "\${arg#@}" ]]; then
        set -f; words=(\$(cat -- "\${arg#@}")); set +f
        args+=(\${words[@]+"\${words[@]}"})
    else
        args+=("\$arg")
    fi
done
main=""; skip=0
for arg in \${args[@]+"\${args[@]}"}; do
    if (( skip )); then skip=0; continue; fi
    case "\$arg" in
        -cp|-classpath|--class-path|-p|--module-path|--upgrade-module-path|--add-modules|--add-exports|--add-opens|\
        --add-reads|--patch-module|--limit-modules|--enable-native-access|--source|--illegal-native-access) skip=1 ;;
        -jar|-m|--module) break ;;
        -*) ;;
        *) main="\$arg"; break ;;
    esac
done
"$real/java" "\$@"; status=\$?
[[ -z "\$main" ]] || printf '%s\t%s\n' "\$main" "\$status" >>"$1"
exit "\$status"
SHIM
    chmod 700 "$shim/bin/java"
    printf '%s\n' "$shim"
}
if [[ "$SKIP_TESTS" == 1 ]]; then
    record "G6 existing suites (tests/run.sh)" "not run" "--skip-tests"
    [[ "$KIND" == feature-text ]] && record "G6 proof suites ran, by name" "not run" "--skip-tests"
else
    log="$WORK/g6.log"
    suites="$WORK/suites.tsv"; : >"$suites"
    g6_env=()
    if [[ "$KIND" == feature-text ]]; then
        if jdk="$(recording_jdk "$suites")"; then g6_env=(JAVA_HOME="$jdk"); else jdk=""; fi
    fi
    if env ${g6_env[@]+"${g6_env[@]}"} bash tests/run.sh >"$log" 2>&1; then
        record "G6 existing suites (tests/run.sh)" PASS "$(grep -c ' passed' "$log" | tr -d ' ') suites reported passing"
    else
        record "G6 existing suites (tests/run.sh)" FAIL "tests/run.sh failed"; show_failure G6 "$log"
    fi
    if [[ "$KIND" == feature-text && -z "$jdk" ]]; then
        record "G6 proof suites ran, by name" FAIL "no JDK found to record which suites tests/run.sh ran"
    elif [[ "$KIND" == feature-text ]]; then
        expected=($(python3 tools/l10n/ci_gates.py --base "$BASE" --print-proof-suites))
        missing=()
        for name in ${expected[@]+"${expected[@]}"}; do
            grep -qxF "com.jjricks.volt.$name"$'\t'0 "$suites" && ! grep -q "^com\.jjricks\.volt\.$name"$'\t'"[^0]" "$suites" || missing+=("$name")
        done
        if (( ${#missing[@]} )); then
            record "G6 proof suites ran, by name" FAIL "${#missing[@]} of ${#expected[@]} proof suite(s) did not run and pass at head: ${missing[*]}"
        else
            record "G6 proof suites ran, by name" PASS "all ${#expected[@]} proof suite(s) the tests/l10n/*.sources lists name ran and exited 0"
        fi
    fi
fi
record "G7 screen text census" "not run" "emulator: run-release-sweep.py --census, then census_diff.py"

# G8, G9, G10.
# The same rule as ci_gates.py: an ordinary change may record new text (or findings a stronger lint now
# sees) with --write-baselines, so every finding must be listed; a batch or an English change may only shrink.
if [[ "$KIND" == ordinary ]]; then
    gate "G8 hard-coded text lint" "$WORK/g8.log" python3 tools/l10n/hardcoded_lint.py --check
else
    gate "G8 hard-coded text lint" "$WORK/g8.log" python3 tools/l10n/hardcoded_lint.py --check --base "$BASE" --tight
fi
log="$WORK/g9.log"
if bash tests/l10n/golden.sh >"$log" 2>&1; then
    if git cat-file -e "$MERGE:tests/l10n/golden" 2>/dev/null && ! git diff --quiet "$MERGE" HEAD -- tests/l10n/golden; then
        if [[ "$KIND" == english-change ]]; then
            record "G9 reports stay English (goldens)" REPORT "tests/l10n/golden changed since the merge base (owner-approved change)"
        elif [[ "$KIND" == ordinary ]]; then
            # As in CI: an ordinary change may change a report format and update its goldens. Say so, never PASS.
            record "G9 reports stay English (goldens)" REPORT "tests/l10n/golden changed since the merge base; not enforced on an ordinary change, so name it in the PR"
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
# characterization/, golden/ and *.sources, tests/run.sh, build.sh and android-tests/build.sh. An English
# change's edits to existing non-gate tests/l10n/*Test.java files, and a feature-text change's edits to the feature
# tests tools/l10n/test-kinds.tsv lists, tests/run.sh and android-tests/build.sh, are left out and listed for review,
# as in CI.
tooling_files=($(python3 tools/l10n/ci_gates.py --base "$BASE" --print-tooling))
tooling="${#tooling_files[@]} file(s)"
(( ${#tooling_files[@]} )) && tooling+=", e.g. ${tooling_files[0]}"
if (( ${#tooling_files[@]} == 0 )) && [[ "$KIND" == feature-text ]]; then
    record "Proof tooling unchanged" PASS "tools/l10n, workflows, the proof tests and other tests/l10n tooling, and build.sh as at the merge base; feature tests and runners are listed below"
elif (( ${#tooling_files[@]} == 0 )); then
    record "Proof tooling unchanged" PASS "tools/l10n, workflows, tests/l10n tooling, tests/run.sh and build scripts as at the merge base"
elif [[ "$KIND" == ordinary ]]; then
    record "Proof tooling unchanged" "n/a" "a tooling change, not a batch: $tooling"
else
    record "Proof tooling unchanged" FAIL "changed in a $KIND pull request: $tooling"
fi
if [[ "$KIND" == feature-text ]]; then
    # As in CI (ci_gates.py): only new English keys, no existing key, translation, ledger or src literal changed;
    # every proof test a tests/l10n/*.sources list ran at the merge base still listed; tests/run.sh's l10n lines kept.
    gate "Feature text: only new English" "$WORK/feature-text.log" python3 tools/l10n/ci_gates.py --base "$BASE" --check-feature-text
    feature_edits=($(python3 tools/l10n/ci_gates.py --base "$BASE" --print-feature-edits))
    if (( ${#feature_edits[@]} )); then
        record "Feature tests and runners edited" REPORT "${#feature_edits[@]} file(s): ${feature_edits[*]}; check that each edit follows the feature (proof tests: tools/l10n/test-kinds.tsv)"
    fi
fi
if [[ "$KIND" == english-change ]]; then
    ledger_files=($(git diff --name-only "$MERGE" HEAD -- l10n/ledger))
    if (( ${#ledger_files[@]} )); then
        record "No ledger rows in an English change" FAIL "an English change must not also extract text: ${ledger_files[*]}"
    else
        record "No ledger rows in an English change" PASS "l10n/ledger unchanged"
    fi
    english_tests=($(python3 tools/l10n/ci_gates.py --base "$BASE" --print-english-tests))
    if (( ${#english_tests[@]} )); then
        record "tests/l10n edits for review" REPORT "${#english_tests[@]} file(s): ${english_tests[*]}; check that each edit only follows a row of l10n/english-changes.tsv"
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
    if [[ "$FAILED" != 0 ]]; then
        echo "Result: FAILED (see above)."
    elif [[ "$KIND" == ordinary ]]; then
        echo "Result: every gate an ordinary change is held to passed. G1 and G5 were not applied, so this is not a proof that English is unchanged."
    else
        echo "Result: every gate that runs locally passed."
    fi
}
exit "$FAILED"
