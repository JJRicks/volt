#!/bin/bash
# Installs one homepage release on the VPS and keeps the live variant choice. Run as root, after review,
# merge and the owner's OK, from the Mac through the deploy wrappers (see site/README.md, "Deploy"):
#   bash -s -- <commit> <archive sha256> <manifest sha256> <previous release commit> <nginx config sha256> < deploy-release.sh
# The archive and manifest must already be in /tmp as volt-home-<commit>.tar.gz and .manifest.
#
# Replaces the update.sh used through Volt 1.13.0. The same checks, plus:
#   - the release must hold variants.json and a real variants/original/index.html;
#   - every file's modification time is derived from its SHA-256, so nginx's ETag and Last-Modified change
#     when a file's content does. With "Cache-Control: no-cache" (nginx/volt-homepage.conf), a return visit
#     after a variant switch or a deploy revalidates every file. Limit: the time is the SHA-256 reduced to
#     600 million seconds, so two same-size files can be crafted to share a validator (site/README.md,
#     "Caching"); ordinary files don't collide in practice;
#   - the release's cards/ (variant link-preview images, named by SHA-256) are added to the append-only
#     store /var/www/volt-home/cards/; a name that exists with other bytes stops the deploy (CARD_CONFLICT)
#     before current moves, and nothing in the store is ever replaced or removed;
#   - modes never depend on the caller's umask: everything this script creates or owns (release tree, card
#     store, cards) gets 755/644 explicitly on every run, and every served path, including the home and
#     releases folders it doesn't own, must be world-readable before current moves (PERMISSIONS_BAD);
#   - once nginx serves /var/www/volt-home/live, the switch (site/server/) must be installed;
#   - the retry contract: every refusal before current moves prints a marker and its reason (DEPLOY_REFUSED,
#     CARD_CONFLICT, PERMISSIONS_BAD) and leaves nothing behind that stops the identical command from
#     succeeding once the cause is fixed. Folders it doesn't own are checked before anything is created. A
#     release folder this run created is removed again on any failure before current moves (it was never
#     served; STAGED_RELEASE_REMOVED). One that already exists (an earlier attempt, a rolled-back deploy, an
#     older release) is used as it is when nothing serves it and it matches the manifest exactly
#     (STAGED_RELEASE_REUSED; a later refusal leaves it in place), and replaced when nothing serves it and it
#     doesn't (STAGED_RELEASE_REMOVED). A folder that is current or holds live is never removed. One deploy
#     runs at a time ($HOME_DIR/.deploy.lock, released by the kernel when the script ends, however it ends);
#   - before current moves, the live link's target is saved. After current moves,
#     volt-homepage-switch.service must succeed in this run AND the live link must point inside the new
#     release. Otherwise the script restores both links itself, each with one atomic rename (current to the
#     previous release, live to its saved target; no switch run is involved), checks both, and fails:
#     DEPLOY_FAILED when both are back, DEPLOY_INCOMPLETE (with the commands to finish by hand) when not.
#     A status file left by an earlier run is never trusted.
# Until the switch runs, / keeps serving the previous release's copy of the live variant; nothing dangles.
#
# The VOLT_* variables below exist for tests/site-homepage-test.py, which runs this script in a temporary
# folder with stand-ins for nginx and systemctl. Production uses the defaults.
#
# The whole script is one { ... } group, ended by exit: bash -s reads it from the SSH channel, and bash parses
# a group completely before running any of it, so a dropped connection can never cut the script off half way.
{
set -euo pipefail
# Every mode below is explicit, and nothing depends on the caller's umask (a root shell with umask 077
# would otherwise make folders nginx's workers cannot enter).
umask 022
# The retry contract (site/README.md, "Failure modes"). Whether a failed run moved anything is read from the
# links themselves when the script ends, never from a variable: a rename happens in a child process, and a
# signal can land after it renamed a link but before the shell could record that. So, on any failing exit:
#   - current or live points into the new release (it didn't when the run started; that is checked before
#     anything changes): something was moved and no rollback finished. DEPLOY_INTERRUPTED names the links
#     and prints the exact commands that put both back. Nothing is removed.
#   - the links can't be read: DEPLOY_INTERRUPTED says so, removes nothing and gives no commands.
#   - neither link points into it: nothing served changed. DEPLOY_REFUSED and the reason, and a release folder
#     this run created (STAGED=1) is removed again, so the identical command works once the cause is fixed. A
#     folder this run found and reused (STAGED=0) stays: it matches the manifest, and the next run reuses it.
# Once current is about to move, the critical section below holds HUP, INT and TERM until the outcome is
# completely reported (UPDATE_OK, DEPLOY_FAILED or DEPLOY_INCOMPLETE), and only then sets FINAL=1. The link
# reading above stays as the safety net for any other exit in between.
#
# Probes. Every check whose answer decides what happens next has three outcomes: confirmed yes, confirmed no,
# and unknown (the command failed, or printed nothing usable). Unknown is never read as yes, no, idle or safe:
# before current moves it refuses; after, it ends in DEPLOY_INCOMPLETE naming what could not be confirmed. So
# each probe below prints an exact answer word (or a checked path) and is read by that word, never by an empty
# string or by "not a match". site/README.md, "Probes", lists them all.
#
# One lock for every writer of current and live: the switch's own lock file. The switch holds it for its whole
# run (it reads current and writes live under it), and this script holds it while it reads live and moves
# current, and from its check of the switch's result through the rollback and its read-back. It never holds it
# while the switch it started runs (that would deadlock); a switch the Workbench starts meanwhile waits for it,
# then reads current as this script left it. The commands it prints for an operator take it too (flock).
# site/README.md, "Writers of current and live", lists every writer.
LINKS_LOCKED=0; REASON=; FINAL=0; STAGED=0; LINKS_CHECKED=0; START_LIVE=; ERR_LINE=; SIGNALED=0; SWITCH_STARTED=0; SWITCH_STATE=
refuse() {
  REASON="$*"
  exit 1
}
trap 'ERR_LINE=$LINENO' ERR
on_exit() {
  local rc=$? into= live_back live_step
  { [ "$rc" -ne 0 ] && [ "$FINAL" -eq 0 ]; } || return 0
  # Let a waiting switch run finish before anything below waits for the unit to be idle (inline: this trap can
  # run before unlock_links is defined).
  if [ "$LINKS_LOCKED" -eq 1 ]; then exec 8>&-; LINKS_LOCKED=0; fi
  if [ "$LINKS_CHECKED" -eq 1 ]; then
    into="$(links_into_release 2>/dev/null)" || into=unknown
  fi
  if [ -n "$into" ] && [ "$SWITCH_STARTED" -eq 1 ] && ! switch_settles; then
    report_switch_running
    return 0
  fi
  if [ "$into" = unknown ]; then
    echo "DEPLOY_INTERRUPTED (exit $rc near line ${ERR_LINE:-?}${REASON:+: $REASON}): where current and live point" \
         "could not be read, so nothing was removed and no commands are given. Read both as root" \
         "(readlink -f $HOME_DIR/current; readlink $HOME_DIR/live): neither inside $REL means nothing served" \
         "changed and the same command works again; otherwise put both back as in site/README.md, \"Rollback\"." >&2
    return 0
  fi
  if [ -n "$into" ]; then
    # The target live had before this deploy: read just before current moved, else when the run started. Both
    # reads are confirmed (a link, or no link at all); an unreadable live link stops the run before anything.
    live_back="${prior_live-$START_LIVE}"
    if [ -n "$live_back" ]; then
      live_step="ln -sfn $live_back $HOME_DIR/live.new && mv -Tf $HOME_DIR/live.new $HOME_DIR/live"
    else
      live_step="rm -f $HOME_DIR/live"   # there was no live link before this deploy
    fi
    echo "DEPLOY_INTERRUPTED (exit $rc near line ${ERR_LINE:-?}${REASON:+: $REASON}): the new release $REL" \
         "already holds $into, and no rollback ran. Nothing was removed. If both point into it and live is the" \
         "variant you want, the deploy itself finished; otherwise put both back before anything else." >&2
    echo "DEPLOY_INTERRUPTED put back by hand, as root: $(link_steps "ln -sfn $PREV $HOME_DIR/current.new" \
         "&& mv -Tf $HOME_DIR/current.new $HOME_DIR/current && $live_step" \
         "&& readlink -f $HOME_DIR/current && { readlink $HOME_DIR/live || echo no-live-link; }")" >&2
    echo "DEPLOY_INTERRUPTED once both are back, the same command works again (it reuses $REL)." >&2
    return 0
  fi
  echo "DEPLOY_REFUSED ${REASON:-unexpected failure (exit $rc near line ${ERR_LINE:-?})}. Nothing served changed; fix the cause and run the same command again." >&2
  if [ "$STAGED" -eq 1 ]; then
    if unserved_release remove; then
      echo "STAGED_RELEASE_REMOVED $REL (staged by this run, never served)" >&2
    else
      echo "STAGED_RELEASE_LEFT $REL could not be removed (reason above, or nothing serving it could not be confirmed). The same command checks it and reuses or replaces it." >&2
    fi
  fi
}
trap on_exit EXIT
# A signal becomes an ordinary exit, so the trap above runs with a failing status (bash would otherwise
# run it with $? = 0 and skip the clean-up).
trap 'exit 129' HUP; trap 'exit 130' INT; trap 'exit 143' TERM
# A write to a closed SSH channel fails (EPIPE) instead of killing the shell before it has cleaned up.
trap '' PIPE
[[ $# -eq 5 ]] || refuse "usage: <commit> <archive sha256> <manifest sha256> <previous release commit> <nginx config sha256>"
C="$1"; EXPECTED_TAR="$2"; EXPECTED_MANIFEST="$3"; EXPECTED_PREV="$4"; EXPECTED_CONFIG="$5"
[[ "$C" =~ ^[0-9a-f]{40}$ && "$EXPECTED_PREV" =~ ^[0-9a-f]{40}$ ]] || refuse "the commits must be 40 lowercase hex characters"
for checksum in "$EXPECTED_TAR" "$EXPECTED_MANIFEST" "$EXPECTED_CONFIG"; do
  [[ "$checksum" =~ ^[0-9a-f]{64}$ ]] || refuse "a SHA-256 must be 64 lowercase hex characters: $checksum"
done
HOME_DIR="${VOLT_HOME_DIR:-/var/www/volt-home}"
NGINX_DIR="${VOLT_NGINX_DIR:-/etc/nginx}"
UPLOADS="${VOLT_UPLOAD_DIR:-/tmp}"
SWITCH="${VOLT_SWITCH_BIN:-/usr/local/sbin/volt-homepage-switch}"
STATUS="${VOLT_SWITCH_STATUS:-/var/lib/volt-homepage-switch/status.json}"
OWNER="${VOLT_RELEASE_OWNER:-root:root}"
# The switch's lock (volt-homepage-switch takes <state dir>/lock; the status file sits in the same folder).
SWITCH_LOCK="${VOLT_SWITCH_LOCK:-${STATUS%/*}/lock}"
SETTLE_SECONDS="${VOLT_SWITCH_SETTLE_SECONDS:-60}"   # the unit's TimeoutStartSec is 30
[[ "$SETTLE_SECONDS" =~ ^[0-9]{1,4}$ ]] || refuse "VOLT_SWITCH_SETTLE_SECONDS must be a whole number of seconds"
TAR="$UPLOADS/volt-home-$C.tar.gz"; MAN="$UPLOADS/volt-home-$C.manifest"
CONFIG="$NGINX_DIR/sites-available/volt-homepage"
REL="$HOME_DIR/releases/$C"
PREV="$HOME_DIR/releases/$EXPECTED_PREV"
# Each Python step names itself in its first argument (tag:<name>), for site/README.md's probe list and for the
# tests, which make each one fail in turn.
# The file probes: "link" prints absent, other (not a link) or link + the target as written + the resolved
# target; "kind" prints absent, link, dir or other; "exec" prints absent, yes or no; "real" prints the resolved
# path. Any other error (a folder that can't be searched, say) raises: no answer word, so unknown.
PROBE_PY='
import os, stat, sys
what, path = sys.argv[1], sys.argv[2]
def lstat_or_absent():
    try:
        return os.lstat(path)
    except FileNotFoundError:
        print("absent")
        sys.exit(0)
if what == "tag:link":
    st = lstat_or_absent()
    if not stat.S_ISLNK(st.st_mode):
        print("other")
        sys.exit(0)
    raw = os.readlink(path)
    print("link")
    print(raw)
    print(os.path.realpath(os.path.join(os.path.dirname(path), raw)))
elif what == "tag:kind":
    mode = lstat_or_absent().st_mode
    print("link" if stat.S_ISLNK(mode) else "dir" if stat.S_ISDIR(mode) else "other")
elif what == "tag:exec":
    try:
        mode = os.stat(path).st_mode
    except FileNotFoundError:
        print("absent")
        sys.exit(0)
    print("yes" if stat.S_ISREG(mode) and os.access(path, os.X_OK) else "no")
elif what == "tag:real":
    print(os.path.realpath(path))
'
# probe_one <kind|exec> <path> <answers...>: prints one of the answers, or unknown.
probe_one() {
  local out answer what=$1 path=$2
  shift 2
  out="$(python3 -I -c "$PROBE_PY" "tag:$what" "$path" 2>/dev/null)" || out=
  for answer in "$@"; do
    if [ "$out" = "$answer" ]; then echo "$answer"; return 0; fi
  done
  echo unknown
}
# is_abs_path <text>: one line that starts with /.
is_abs_path() { [ "${1#/}" != "$1" ] && [[ "$1" != *$'\n'* ]]; }
# read_link <link>: sets LINK to absent, other, link or unknown; for a link, LINK_RAW (the target as written)
# and LINK_REAL (resolved, absolute). Python first. If it fails, coreutils readlink: a link that still reads
# is still evidence, so a rename that finished before its helper failed is seen. Neither: unknown.
read_link() {
  local out state= raw= real=
  LINK=unknown; LINK_RAW=; LINK_REAL=
  out="$(python3 -I -c "$PROBE_PY" tag:link "$1" 2>/dev/null)" || out=
  { IFS= read -r state; IFS= read -r raw; IFS= read -r real; } <<<"$out" || true
  case "$state" in
    absent|other)
      if [ "$out" = "$state" ]; then LINK=$state; return 0; fi ;;
    link)
      if [ -n "$raw" ] && is_abs_path "$real" && [ "$out" = "link"$'\n'"$raw"$'\n'"$real" ]; then
        LINK=link; LINK_RAW=$raw; LINK_REAL=$real
        return 0
      fi ;;
  esac
  raw="$(readlink "$1" 2>/dev/null)" && [ -n "$raw" ] && [[ "$raw" != *$'\n'* ]] || return 0
  real="$(readlink -f "$1" 2>/dev/null)" && is_abs_path "$real" || return 0
  LINK=link; LINK_RAW=$raw; LINK_REAL=$real
}
# link_is <link> <resolved folder>: prints yes (a readable link whose resolved target is exactly that folder),
# no (it reads, and points elsewhere or isn't a link) or unknown.
link_is() {
  read_link "$1"
  case "$LINK" in
    link) if [ "$LINK_REAL" = "$2" ]; then echo yes; else echo no; fi ;;
    absent|other) echo no ;;
    *) echo unknown ;;
  esac
}
# canon <path>: its resolved absolute path, or a failure.
canon() {
  local out
  out="$(python3 -I -c "$PROBE_PY" tag:real "$1" 2>/dev/null)" || out=
  is_abs_path "$out" || out="$(readlink -f "$1" 2>/dev/null)" || return 1
  is_abs_path "$out" || return 1
  printf '%s\n' "$out"
}
sha() { python3 -I -c 'import hashlib, sys; print(hashlib.sha256(open(sys.argv[2], "rb").read()).hexdigest())' tag:sha "$1"; }
# Points a link (current or live) at a target in one rename: a new link under a unique temporary name,
# renamed over the old one. Its exit status is never trusted: every caller reads the link afterwards.
swap_link() {  # <link> <target>
  python3 -I - tag:swap "$1" "$2" <<'PY'
import os, sys
link, target = sys.argv[2], sys.argv[3]
temp = '%s.%d.new' % (link, os.getpid())
os.symlink(target, temp)
try:
    os.replace(temp, link)
except BaseException:
    os.unlink(temp)
    raise
PY
}
point_current() { swap_link "$HOME_DIR/current" "$1"; }
# unchanged: current is confirmed to resolve to the previous release, and the nginx site file has the expected
# SHA-256 (a failed hash prints nothing, which never equals a 64-character one).
unchanged() {
  [ "$(link_is "$HOME_DIR/current" "$PREV_REAL")" = yes ] && [ "$(sha "$CONFIG" 2>/dev/null)" = "$EXPECTED_CONFIG" ]
}
# The switch unit as systemd sees it. switch_busy prints why it isn't confirmed idle (a job is queued, it is
# mid-run, or either query failed or said something else) and succeeds then; it fails only when both queries
# answered and the unit is idle: ActiveState active, inactive or failed, and no job for it in list-jobs.
SWITCH_UNIT=volt-homepage-switch.service
switch_busy() {
  local state jobs line
  state="$(systemctl show -p ActiveState --value "$SWITCH_UNIT" 2>/dev/null)" || { echo "unknown: systemctl show failed"; return 0; }
  case "$state" in
    active|inactive|failed) ;;
    activating|deactivating|reloading|refreshing) echo "$state"; return 0 ;;
    *) echo "unknown: systemctl show gave no state"; return 0 ;;
  esac
  # Read whole, not piped into grep -q: with pipefail, systemctl's EPIPE after an early match would read as
  # "no job". A failed or unrecognised job list is unknown, never "no job": a start job can be queued while
  # the unit is still inactive.
  jobs="$(systemctl list-jobs --no-legend 2>/dev/null)" || { echo "unknown: systemctl list-jobs failed"; return 0; }
  while IFS= read -r line; do
    [ -n "$line" ] || continue
    [[ "$line" =~ ^[[:space:]]*[0-9]+[[:space:]]+[^[:space:]]+ ]] || { echo "unknown: systemctl list-jobs gave an unreadable line"; return 0; }
    case "$line" in *"$SWITCH_UNIT"*) echo "a job is queued"; return 0 ;; esac
  done <<<"$jobs"
  return 1
}
# switch_settles: waits (at most SETTLE_SECONDS) until the switch unit is confirmed idle. A switch run started
# by this deploy goes on inside systemd even if the systemctl client that asked for it is gone, so nothing
# reads the links to decide on a rollback, a recovery message or a retry before this succeeds. SWITCH_STATE
# says why not.
switch_settles() {
  local deadline=$(( SECONDS + SETTLE_SECONDS ))
  while SWITCH_STATE="$(switch_busy)"; do
    [ "$SECONDS" -lt "$deadline" ] || return 1
    sleep 0.2
  done
  SWITCH_STATE=idle
}
switch_not_idle() {
  case "$SWITCH_STATE" in
    unknown*) echo "$SWITCH_UNIT could not be confirmed idle after ${SETTLE_SECONDS}s ($SWITCH_STATE)" ;;
    *) echo "$SWITCH_UNIT is still running after ${SETTLE_SECONDS}s ($SWITCH_STATE)" ;;
  esac
}
# lock_links: takes the switch's lock (see above) on fd 8, waiting at most SETTLE_SECONDS for a switch run that
# holds it. Prints nothing; fails when the lock file can't be opened or the lock isn't taken in time, which
# counts as unknown. unlock_links lets it go (closing fd 8 releases it; nothing else holds that descriptor).
lock_links() {
  { exec 8>>"$SWITCH_LOCK"; } 2>/dev/null || return 1
  if [ "$(python3 -I -c 'import fcntl, sys, time
deadline = time.monotonic() + float(sys.argv[2])
while True:
    try:
        fcntl.flock(8, fcntl.LOCK_EX | fcntl.LOCK_NB)
        break
    except BlockingIOError:
        if time.monotonic() >= deadline:
            sys.exit(1)
        time.sleep(0.1)
print("locked")' tag:linklock "$SETTLE_SECONDS" 2>/dev/null)" = locked ]; then
    LINKS_LOCKED=1
    return 0
  fi
  exec 8>&-
  return 1
}
unlock_links() {
  [ "$LINKS_LOCKED" -eq 1 ] || return 0
  exec 8>&-
  LINKS_LOCKED=0
}
# link_steps <commands>...: commands an operator runs to change the links. With the switch installed they run
# under its lock, so a switch run can't interleave with them (flock waits at most 60 s, then fails, changing
# nothing). The commands hold no single quotes.
link_steps() {
  if [ "${switch_installed:-0}" -eq 1 ]; then
    printf "flock -w 60 %s sh -c '%s'" "$SWITCH_LOCK" "$*"
  else
    printf '%s' "$*"
  fi
}
# The switch run isn't confirmed finished: the links can still change, so no put-back commands (they would
# race it).
report_switch_running() {
  echo "DEPLOY_INCOMPLETE $(switch_not_idle), so live may still change." \
       "current -> $(readlink "$HOME_DIR/current" 2>/dev/null || echo '?'), live -> $(readlink "$HOME_DIR/live" 2>/dev/null || echo 'none or ?')." \
       "Don't touch either link while it runs." >&2
  echo "DEPLOY_INCOMPLETE wait until 'systemctl is-active $SWITCH_UNIT' no longer prints activating and" \
       "'systemctl list-jobs' lists nothing for it, then read both links: both inside $REL means the deploy" \
       "went through; otherwise put current back to $PREV and live to ${prior_live-$START_LIVE} (site/README.md, \"Rollback\")." >&2
}
# A link after current moved could not be confirmed: name what, give no commands, and say how to decide.
report_unconfirmed() {  # <what could not be confirmed>
  echo "DEPLOY_INCOMPLETE could not confirm $1. Nothing was removed; no commands are given, because they would" \
       "act on links whose state is unknown." >&2
  echo "DEPLOY_INCOMPLETE read both links as root (readlink -f $HOME_DIR/current; readlink $HOME_DIR/live):" \
       "current $PREV and live ${prior_live:-none} means nothing changed and the same command works again; both" \
       "inside $REL means the deploy went through; otherwise put both back as in site/README.md, \"Rollback\"." >&2
}
# links_into_release: prints which of current and live resolve to $REL or into it ("current", "live",
# "current and live"), or nothing; fails when either link can't be read. A link to a folder that doesn't
# exist (yet) counts by its path.
links_into_release() {
  local link into=
  for link in current live; do
    read_link "$HOME_DIR/$link"
    case "$LINK" in
      unknown) return 1 ;;
      link) case "$LINK_REAL" in "$REL_REAL"|"$REL_REAL"/*) into="${into:+$into and }$link" ;; esac ;;
    esac
  done
  printf '%s\n' "$into"
}
# unserved_release <check|remove>: succeeds only when $REL is confirmed a real folder (not a link) that nothing
# serves: current and live are each absent, not a link, or resolve outside it. With "remove", it is then
# removed. Otherwise it prints why (or a Python error) and fails, removing nothing.
unserved_release() {
  local out
  out="$(python3 -I - tag:unserved "$1" "$HOME_DIR" "$REL" <<'PY'
import os, shutil, stat, sys
action, home, rel = sys.argv[2:5]
if not stat.S_ISDIR(os.lstat(rel).st_mode):
    sys.exit('%s is not a folder' % rel)
real = os.path.realpath(rel)
for link in ('current', 'live'):
    path = os.path.join(home, link)
    try:
        if not stat.S_ISLNK(os.lstat(path).st_mode):
            continue
    except FileNotFoundError:
        continue
    target = os.path.realpath(path)
    if target == real or target.startswith(real + os.sep):
        sys.exit('%s is served: %s points into it' % (rel, link))
if action == 'remove':
    shutil.rmtree(rel)
print('unserved')
PY
)" || return 1
  [ "$out" = unserved ]
}
# release_matches [set-times]: prints yes when $REL holds exactly the manifest: only folders and regular files
# (no links or anything else), the same file names, the same SHA-256 for each; "no: <why>" when it was read
# and differs; unknown when it couldn't be read. With set-times, each file then gets a modification time
# derived from its SHA-256 (a second between 2001 and 2020): same content, same ETag and Last-Modified, in
# every release and variant.
release_matches() {
  local out
  out="$(python3 -I - tag:matches "$REL" "$MAN" "${1:-}" 2>/dev/null <<'PY'
import hashlib, os, stat, sys
rel, manifest, set_times = sys.argv[2], sys.argv[3], sys.argv[4] == 'set-times'
def fail(error):
    raise error
entries = [line.split('  ', 1) for line in open(manifest).read().splitlines()]
assert entries and all(len(entry) == 2 for entry in entries)
found = []
for folder, dirs, files in os.walk(rel, onerror=fail):
    for name in dirs + files:
        path = os.path.join(folder, name)
        mode = os.lstat(path).st_mode
        if stat.S_ISREG(mode):
            found.append(os.path.relpath(path, rel).replace(os.sep, '/'))
        elif not stat.S_ISDIR(mode):
            print('no: %s is neither a file nor a folder' % path)
            sys.exit(0)
if sorted(found) != sorted(name for _, name in entries):
    print('no: the files in %s are not the files in the manifest' % rel)
    sys.exit(0)
for digest, name in entries:
    with open(os.path.join(rel, name), 'rb') as f:
        if hashlib.sha256(f.read()).hexdigest() != digest:
            print('no: %s differs from the manifest' % name)
            sys.exit(0)
if set_times:
    for digest, name in entries:
        when = 1_000_000_000 + int(digest[:15], 16) % 600_000_000
        os.utime(os.path.join(rel, name), (when, when), follow_symlinks=False)
print('yes')
PY
)" || out=
  case "$out" in
    yes|"no: "*) [[ "$out" != *$'\n'* ]] && { echo "$out"; return 0; } ;;
  esac
  echo unknown
}
# check_modes <path>... [--tree <path>...]: every path (and, after --tree, everything under it) must be
# readable by nginx's unprivileged workers: folders o+rx, files o+r. Prints ok when every path was read and
# passes; PERMISSIONS_BAD on stderr when one doesn't; a Python error (nothing on stdout) when one can't be read.
check_modes() {
  python3 -I - tag:modes "$@" <<'PY'
import os, stat, sys
def fail(error):
    raise error
def present(path):
    try:
        os.lstat(path)
        return True
    except FileNotFoundError:
        return False
paths, tree = [], False
for arg in sys.argv[2:]:
    if arg == '--tree':
        tree = True
        continue
    if not present(arg):
        continue
    paths.append((arg, os.stat(arg).st_mode))   # a named path is checked through a link, as nginx follows it
    if tree and stat.S_ISDIR(os.stat(arg).st_mode):
        for folder, dirs, files in os.walk(arg, onerror=fail):
            dirs.sort()
            paths += [(path, os.lstat(path).st_mode) for path in (os.path.join(folder, name) for name in sorted(dirs + files))]
bad = []
for p, mode in paths:
    if stat.S_ISLNK(mode) or os.path.basename(p).startswith('.'):
        continue
    need = stat.S_IROTH | stat.S_IXOTH if stat.S_ISDIR(mode) else stat.S_IROTH
    if mode & need != need:
        bad.append('%s mode=%o' % (p, stat.S_IMODE(mode)))
if bad:
    print('PERMISSIONS_BAD nginx could not read: %s%s. Nothing served changed; current is still the previous '
          'release. Fix with chmod (folders 755, files 644) and run the same command again.'
          % ('; '.join(bad[:5]), ' ...' if len(bad) > 5 else ''), file=sys.stderr)
    sys.exit(1)
print('ok')
PY
}
modes_ok() { [ "$(check_modes "$@")" = ok ]; }
[ "$(sha "$TAR" 2>/dev/null)" = "$EXPECTED_TAR" ] || refuse "the archive $TAR is missing or its SHA-256 is not $EXPECTED_TAR"
[ "$(sha "$MAN" 2>/dev/null)" = "$EXPECTED_MANIFEST" ] || refuse "the manifest $MAN is missing or its SHA-256 is not $EXPECTED_MANIFEST"
PREV_REAL="$(canon "$PREV")" || refuse "could not resolve $PREV"
releases_real="$(canon "$HOME_DIR/releases")" || refuse "could not resolve $HOME_DIR/releases"
REL_REAL="$releases_real/$C"
unchanged || refuse "current is not $PREV (or could not be read), or the nginx site file's SHA-256 is not $EXPECTED_CONFIG"
# One deploy at a time, from here on: everything below may stage, reuse or remove $REL. flock's lock belongs
# to the open file, which only this shell (and its children) holds, so it ends with the script however the
# script ends (a crash included) and never needs cleaning up. Busy: refused, and the same command works later.
LOCK="$HOME_DIR/.deploy.lock"
exec 9>>"$LOCK" || refuse "could not open the deploy lock $LOCK"
[ "$(python3 -I -c 'import fcntl; fcntl.flock(9, fcntl.LOCK_EX | fcntl.LOCK_NB); print("locked")' tag:lock 2>/dev/null)" = locked ] \
  || refuse "another deploy holds $LOCK (or it could not be taken); run the same command again once it has finished"
rel_kind="$(probe_one kind "$REL" absent dir link other)"
case "$rel_kind" in
  absent|dir) ;;
  link) refuse "$REL is a symbolic link" ;;
  other) refuse "$REL is not a folder" ;;
  *) refuse "could not tell whether $REL exists" ;;
esac
# The switch is installed when its program is there and systemd has loaded its unit. Either answer unknown
# refuses; a missing program means it isn't (the unit then has nothing to run).
case "$(probe_one exec "$SWITCH" absent yes no)" in
  absent|no) switch_installed=0 ;;
  yes)
    load="$(systemctl show -p LoadState --value "$SWITCH_UNIT" 2>/dev/null)" || load=
    case "$load" in
      loaded) switch_installed=1 ;;
      not-found) switch_installed=0 ;;
      *) refuse "could not tell whether $SWITCH_UNIT is installed (LoadState: ${load:-no answer})" ;;
    esac ;;
  *) refuse "could not tell whether $SWITCH is installed" ;;
esac
# A switch run still going (an earlier deploy that was killed, say) could still move live: wait for it before
# deciding anything from the links.
if [ "$switch_installed" -eq 1 ]; then
  switch_settles || refuse "$(switch_not_idle); run the same command again once it has finished"
fi
# A release folder that is served (current, or holding live) is never staged over or removed. From here on,
# a link that points into $REL was moved by this run (see on_exit).
served="$(links_into_release)" || refuse "could not read where current and live point"
[ -z "$served" ] || refuse "$REL is served (it holds $served); it is never staged over or removed"
read_link "$HOME_DIR/live"
case "$LINK" in
  link) START_LIVE=$LINK_RAW ;;
  absent) START_LIVE= ;;
  other) refuse "$HOME_DIR/live is not a symbolic link" ;;
  *) refuse "could not read $HOME_DIR/live" ;;
esac
LINKS_CHECKED=1
if [ "$rel_kind" = dir ]; then
  unserved_release check || refuse "$REL already exists and cannot be staged over (reason above, or nothing serving it could not be confirmed); it is never removed"
fi
# nginx serves the live variant once its configuration names live; then the switch must be there. grep: 0
# names it, 1 doesn't, anything else is unknown.
if grep -q '^[[:space:]]*root /var/www/volt-home/live;' "$CONFIG"; then
  serves_live=1
else
  rc=$?
  [ "$rc" -eq 1 ] || refuse "could not read $CONFIG (grep exit $rc)"
  serves_live=0
fi
if [ "$serves_live" -eq 1 ] && [ "$switch_installed" -eq 0 ]; then
  refuse "nginx serves $HOME_DIR/live but the switch is not installed; stopping before any change"
fi
# The folders above the release that this script does not own: checked before anything is created.
modes_ok "$HOME_DIR" "$HOME_DIR/releases" || refuse "a folder above the release is not readable by nginx, or could not be checked (above)"
archive_ok="$(python3 -I - tag:archive "$TAR" "$MAN" <<'PY'
import hashlib, re, sys, tarfile
from pathlib import Path, PurePosixPath
expected = {}
for line in Path(sys.argv[3]).read_text().splitlines():
    digest, name = line.split('  ', 1)
    assert re.fullmatch(r'[0-9a-f]{64}', digest)
    path = PurePosixPath(name)
    assert not path.is_absolute() and '..' not in path.parts and name not in expected
    assert name == path.as_posix() and name not in ('', '.')
    expected[name] = digest
assert expected and 'index.html' in expected
# A release built with variants: the current page as original, and the list the Workbench reads.
assert 'variants.json' in expected and 'variants/original/index.html' in expected
with tarfile.open(sys.argv[2]) as archive:
    members = archive.getmembers()
    assert len(members) == len(expected) and len({m.name for m in members}) == len(expected)
    assert {m.name for m in members} == set(expected)
    for member in members:
        assert member.isfile()
        assert hashlib.sha256(archive.extractfile(member).read()).hexdigest() == expected[member.name]
print('ok')
PY
)" || archive_ok=
[ "$archive_ok" = ok ] || refuse "the archive does not match its manifest (or could not be checked)"
BACKUP="$NGINX_DIR/volt-deployment-backups/$(date -u +%Y%m%d-%H%M%S)-volt-home-$C"
install -d -m 700 "$BACKUP"
cp -a "$NGINX_DIR/sites-available" "$BACKUP/"
cp -a "$NGINX_DIR/sites-enabled" "$BACKUP/"
printf '%s\n' "$PREV" > "$BACKUP/previous-release.txt"
printf '%s\n' "${START_LIVE:-none}" > "$BACKUP/previous-live.txt"
# A release folder that already exists was left by an earlier attempt (a refusal that could not remove it,
# a crash, a rolled-back deploy) or is an older release being deployed again. Served: refused above.
# Not served and exactly this release: used as it is, and left in place if this run is refused later.
# Not served and read as anything else: replaced. Not read: refused, nothing removed.
if [ "$rel_kind" = dir ]; then
  matches="$(release_matches)"
  case "$matches" in
    yes)
      echo "STAGED_RELEASE_REUSED $REL (already there, not served, matches the manifest)" >&2 ;;
    "no: "*)
      unserved_release remove || refuse "could not remove $REL (reason above)"
      rel_kind=absent
      echo "STAGED_RELEASE_REMOVED $REL (left by an earlier attempt, not served, did not match the manifest: ${matches#no: }); staging it again" >&2 ;;
    *) refuse "could not check $REL against the manifest; it is left as it is" ;;
  esac
fi
if [ "$rel_kind" = absent ]; then
  install -d -m 755 "$REL"
  STAGED=1
  tar -xzf "$TAR" -C "$REL" --no-same-owner --no-same-permissions
fi
chown -R "$OWNER" "$REL"
find "$REL" -type d -exec chmod 755 {} +
find "$REL" -type f -exec chmod 644 {} +
# Check every file against the manifest (and that there is nothing but files and folders), then set the
# content-derived modification times.
matches="$(release_matches set-times)"
[ "$matches" = yes ] || refuse "the release folder does not match the manifest, or could not be checked ($matches)"
# Link-preview cards (site/README.md, "Variants"): add the release's cards/ to the append-only store
# $HOME_DIR/cards/, served at /cards/<name> with an immutable cache. A name is its file's SHA-256, so an
# existing name must already hold the same bytes: a different file under it stops the deploy
# (CARD_CONFLICT) before current moves. Nothing in the store is ever replaced or removed, rollbacks included.
cards_added="$(python3 -I - tag:cards "$REL/cards" "$HOME_DIR/cards" <<'PY'
import hashlib, os, re, sys
from pathlib import Path
source, store = Path(sys.argv[2]), Path(sys.argv[3])
os.umask(0o022)
# The store and every card in it get their modes on every run, so a store made or filled under another
# umask, such as a 0700 folder or 0600 cards, is repaired here rather than served as 403s.
if store.is_dir() and not store.is_symlink():
    os.chmod(store, 0o755)
    for old in store.iterdir():
        if not old.name.startswith('.') and old.is_file() and not old.is_symlink():
            os.chmod(old, 0o644)
if not source.is_dir():
    print(0); sys.exit(0)
store.mkdir(mode=0o755, exist_ok=True)
os.chmod(store, 0o755)   # explicit: the umask filters the mode given to mkdir
added = 0
for card in sorted(source.iterdir()):
    data = card.read_bytes()
    digest = hashlib.sha256(data).hexdigest()
    m = re.fullmatch(r'([0-9a-f]{64})\.(png|jpg|jpeg|webp)', card.name)
    if not card.is_file() or not m or m.group(1) != digest:
        print('CARD_CONFLICT %s is not named <its sha256>.<png|jpg|jpeg|webp>; nothing changed' % card.name, file=sys.stderr)
        sys.exit(1)
    target = store / card.name
    if target.exists() or target.is_symlink():
        if target.is_symlink() or not target.is_file() or target.read_bytes() != data:
            print('CARD_CONFLICT %s already holds different bytes; the store keeps it and the deploy stops' % target, file=sys.stderr)
            sys.exit(1)
        os.chmod(target, 0o644)   # repairs a card stored under another umask
        continue
    temp = store / ('.%s.%d.new' % (card.name, os.getpid()))
    with open(temp, 'wb') as f:
        f.write(data); f.flush(); os.fsync(f.fileno())
    os.chmod(temp, 0o644)
    when = 1_000_000_000 + int(digest[:15], 16) % 600_000_000
    os.utime(temp, (when, when))
    try:
        os.link(temp, target)   # never replaces: fails if the name appeared meanwhile
    except FileExistsError:
        if target.read_bytes() != data:
            print('CARD_CONFLICT %s already holds different bytes' % target, file=sys.stderr)
            sys.exit(1)
    else:
        added += 1
    finally:
        os.unlink(temp)
print(added)
PY
)" || refuse "the release's cards could not be added to the card store (reason above)"
[[ "$cards_added" =~ ^[0-9]+$ ]] || refuse "the card step reported nothing usable"
# Every path nginx serves must be readable by its unprivileged workers. Paths this script owns were set
# above (release tree) or just now (store, cards); the folders above them are only checked. Any path without
# the world bits stops the deploy here, before current moves (PERMISSIONS_BAD), with nothing served changed.
modes_ok "$HOME_DIR" "$HOME_DIR/releases" --tree "$REL" "$HOME_DIR/cards" || refuse "a served path is not readable by nginx, or could not be checked (above)"
nginx -t || refuse "nginx -t failed (its output is above)"
unchanged || refuse "current or the nginx site file changed during the deploy (or current could not be read)"
# The live link as it is now, so a failed deploy can put it back without the switch. A switch run the
# Workbench started meanwhile is awaited first; live must read as a link or as no link at all.
if [ "$switch_installed" -eq 1 ]; then
  switch_settles || refuse "$(switch_not_idle); run the same command again once it has finished"
fi
# From here until current has moved, no switch run can change live: this script holds the switch's lock.
if [ "$switch_installed" -eq 1 ]; then
  lock_links || refuse "could not take the switch lock $SWITCH_LOCK within ${SETTLE_SECONDS}s (a switch run holds it, or it can't be opened); run the same command again once the switch has finished"
fi
read_link "$HOME_DIR/live"
case "$LINK" in
  link) prior_live=$LINK_RAW ;;
  absent) prior_live= ;;
  other) refuse "$HOME_DIR/live is not a symbolic link" ;;
  *) refuse "could not read $HOME_DIR/live" ;;
esac
# ---- The critical section ------------------------------------------------------------------------------
# From the first link change until the outcome is completely reported, HUP, INT and TERM are held: the handler
# only records the signal, and finish acts on it once every line of the outcome is out (a success then exits
# 128+n; a failure keeps its own code, 1 or 2). FINAL=1 is set by finish, after the last line. The commands
# here run without set -e, and every result is read as a probe: what a link resolves to, never a rename's exit
# code. SIGKILL can't be held: then nothing reports, and the next run waits for the switch and refuses while a
# link points into the release (site/README.md).
hold() { SIGNALED=$1; }
finish() {  # <exit code>: the outcome is completely reported
  FINAL=1
  trap 'exit 129' HUP; trap 'exit 130' INT; trap 'exit 143' TERM
  if [ "$SIGNALED" -ne 0 ]; then
    echo "DEPLOY_SIGNAL_HELD a signal (exit $SIGNALED) arrived while the links were changing; it was held until the outcome above was complete." >&2
    [ "$1" -ne 0 ] || exit "$SIGNALED"
  fi
  exit "$1"
}
trap 'hold 129' HUP; trap 'hold 130' INT; trap 'hold 143' TERM
set +e
point_current "$REL"
moved="$(link_is "$HOME_DIR/current" "$REL_REAL")"
if [ "$moved" = no ] && [ "$(link_is "$HOME_DIR/current" "$PREV_REAL")" = yes ]; then
  # Confirmed: current did not move, so nothing served changed.
  trap 'exit 129' HUP; trap 'exit 130' INT; trap 'exit 143' TERM
  set -e
  refuse "could not move current"
fi
if [ "$moved" != yes ]; then
  report_unconfirmed "where current points after the rename (it reads as neither $REL nor $PREV)"
  finish 2
fi
live_line="live=not-installed"
if [ "$switch_installed" -eq 1 ]; then
  # The switch takes the lock itself: let it go first. A switch the Workbench started meanwhile then runs
  # with current already in the new release.
  unlock_links
  # In a session of its own, so a signal to the deploy's process group (Ctrl-C, a hang-up) can't kill the client
  # half way; and whatever the client says, the unit itself must be confirmed idle before anything reads the links.
  SWITCH_STARTED=1
  switch_ok=1
  python3 -I -c 'import os, sys; os.setsid(); os.execvp(sys.argv[2], sys.argv[2:])' tag:start systemctl start "$SWITCH_UNIT" || switch_ok=0
  if ! switch_settles; then
    report_switch_running
    finish 2
  fi
  # From the check of the switch's result through the rollback and its read-back, no switch run can change
  # live: one the Workbench starts now waits, and then sees current as this script leaves it. Held to the end.
  if ! lock_links; then
    report_unconfirmed "the links: the switch lock $SWITCH_LOCK could not be taken within ${SETTLE_SECONDS}s, so a switch run may still be changing live"
    finish 2
  fi
  read_link "$HOME_DIR/live"
  live_target=; inside=0
  case "$LINK" in
    link)
      live_target=$LINK_RAW
      case "$LINK_REAL" in "$REL_REAL"/variants/?*) inside=1 ;; esac ;;
    absent|other) ;;
    *)
      report_unconfirmed "where live points after the switch ran (switch_ok=$switch_ok)"
      finish 2 ;;
  esac
  if [ "$switch_ok" -ne 1 ] || [ "$inside" -ne 1 ]; then
    # Put both links back as they were, directly, then confirm both. The switch is confirmed idle, so the
    # put-back commands printed below can't race it.
    point_current "$PREV"
    if [ -n "$prior_live" ]; then
      read_link "$HOME_DIR/live"
      { [ "$LINK" = link ] && [ "$LINK_RAW" = "$prior_live" ]; } || swap_link "$HOME_DIR/live" "$prior_live"
    else
      # There was no live link before: one this run's switch made inside the new release goes.
      python3 -I -c 'import os, sys; p, rel = sys.argv[2:4]; t = os.path.realpath(p)
if os.path.islink(p) and (t == rel or t.startswith(rel + os.sep)): os.unlink(p)' tag:swap "$HOME_DIR/live" "$REL_REAL"
    fi
    current_back="$(link_is "$HOME_DIR/current" "$PREV_REAL")"
    current_now="$(readlink "$HOME_DIR/current" 2>/dev/null || echo 'could not be read')"
    read_link "$HOME_DIR/live"
    live_back=unknown; live_now='could not be read'
    case "$LINK" in
      link)
        live_now=$LINK_RAW
        if [ -n "$prior_live" ]; then
          if [ "$LINK_RAW" = "$prior_live" ]; then live_back=yes; else live_back=no; fi
        else
          case "$LINK_REAL" in "$REL_REAL"|"$REL_REAL"/*) live_back=no ;; *) live_back=yes ;; esac
        fi ;;
      absent|other)
        live_now=none
        if [ -n "$prior_live" ]; then live_back=no; else live_back=yes; fi ;;
    esac
    if [ "$current_back" = yes ] && [ "$live_back" = yes ]; then
      echo "DEPLOY_FAILED switch_ok=$switch_ok live_after_switch=${live_target:-none}: live was not left inside the new release." \
           "Restored: current -> $PREV, live -> ${prior_live:-none}. The new release stays in $REL for a look; see $STATUS." >&2
      finish 1
    fi
    if [ -n "$prior_live" ]; then
      live_step="ln -sfn $prior_live $HOME_DIR/live.new && mv -Tf $HOME_DIR/live.new $HOME_DIR/live"
    else
      live_step="rm -f $HOME_DIR/live"
    fi
    echo "DEPLOY_INCOMPLETE the deploy failed (switch_ok=$switch_ok) and the rollback could not be confirmed:" \
         "current -> $current_now (want $PREV), live -> $live_now (want ${prior_live:-none}); confirmed back:" \
         "current $current_back, live $live_back. The switch is confirmed idle, so the commands below can't race it." >&2
    echo "DEPLOY_INCOMPLETE finish by hand, as root: $(link_steps "ln -sfn $PREV $HOME_DIR/current.new" \
         "&& mv -Tf $HOME_DIR/current.new $HOME_DIR/current && $live_step" \
         "&& readlink -f $HOME_DIR/current && { readlink $HOME_DIR/live || echo no-live-link; }")" >&2
    finish 2
  fi
  # This run's status only: it must name the new release. It is only reported (the outcome was decided by the
  # links above); a status that can't be read says result=unknown.
  live_line="$(python3 -I - tag:status "$STATUS" "$C" "$live_target" <<'PY'
import json, os, sys
try:
    status = json.load(open(sys.argv[2]))
except (OSError, ValueError):
    status = {}
if status.get('current_release') != sys.argv[3]:
    status = {}
name = os.path.basename(sys.argv[4])
print('live=%s result=%s in_new_release=yes message=%s' % (name, status.get('result', 'unknown'), status.get('message', '(no status for this release)')))
PY
)"
  case "$live_line" in
    live=*) ;;
    *) live_line="live=$(basename "$live_target") result=unknown in_new_release=yes message=(status not read)" ;;
  esac
fi
echo "UPDATE_OK current=$REL previous=$PREV backup=$BACKUP cards_added=$cards_added $live_line"
if [ "$switch_installed" -eq 1 ] && [ -n "$prior_live" ]; then
  echo "Rollback: $(link_steps "ln -sfn $PREV $HOME_DIR/current.new && mv -Tf $HOME_DIR/current.new $HOME_DIR/current" \
       "&& ln -sfn $prior_live $HOME_DIR/live.new && mv -Tf $HOME_DIR/live.new $HOME_DIR/live") (cards stay)"
else
  echo "Rollback: $(link_steps "ln -sfn $PREV $HOME_DIR/current.new && mv -Tf $HOME_DIR/current.new $HOME_DIR/current")"
fi
finish 0
}
