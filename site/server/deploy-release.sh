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
#     succeeding once the cause is fixed. Folders it doesn't own are checked before anything is created; a
#     release folder this run staged is removed on any failure before current moves (it was never served),
#     and one left by an earlier attempt that is neither current nor holds live is replaced (STAGED_RELEASE_REMOVED);
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
set -euo pipefail
# Every mode below is explicit, and nothing depends on the caller's umask (a root shell with umask 077
# would otherwise make folders nginx's workers cannot enter).
umask 022
# The retry contract (site/README.md, "Failure modes"). Until current moves (MOVED=1), any failure prints a
# marker with its reason, and the release folder this run staged (STAGED=1) is removed again: nothing ever
# served it, so the identical command works once the cause is fixed.
REPORTED=0; MOVED=0; STAGED=0; ERR_LINE=
refuse() {
  echo "DEPLOY_REFUSED $*. Nothing served changed; fix the cause and run the same command again." >&2
  REPORTED=1
  exit 1
}
trap 'ERR_LINE=$LINENO' ERR
on_exit() {
  local rc=$?
  if [ "$rc" -ne 0 ] && [ "$MOVED" -eq 0 ]; then
    [ "$REPORTED" -eq 1 ] || echo "DEPLOY_REFUSED unexpected failure (exit $rc near line ${ERR_LINE:-?}). Nothing served changed; fix the cause and run the same command again." >&2
    if [ "$STAGED" -eq 1 ] && remove_unserved_release; then
      echo "STAGED_RELEASE_REMOVED $REL (staged by this run, never served)" >&2
    fi
  fi
}
trap on_exit EXIT
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
TAR="$UPLOADS/volt-home-$C.tar.gz"; MAN="$UPLOADS/volt-home-$C.manifest"
CONFIG="$NGINX_DIR/sites-available/volt-homepage"
REL="$HOME_DIR/releases/$C"
PREV="$HOME_DIR/releases/$EXPECTED_PREV"
sha() { python3 -I -c 'import hashlib, sys; print(hashlib.sha256(open(sys.argv[1], "rb").read()).hexdigest())' "$1"; }
realpath_of() { python3 -I -c 'import os, sys; print(os.path.realpath(sys.argv[1]))' "$1"; }
# Points a link (current or live) at a target in one rename: a new link under a unique temporary name,
# renamed over the old one. Returns non-zero, changing nothing, if that fails.
swap_link() {  # <link> <target>
  python3 -I - "$1" "$2" <<'PY'
import os, sys
link, target = sys.argv[1], sys.argv[2]
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
unchanged() {
  [ "$(realpath_of "$HOME_DIR/current")" = "$(realpath_of "$PREV")" ] && [ "$(sha "$CONFIG")" = "$EXPECTED_CONFIG" ]
}
# Removes $REL only when nothing serves it: a real folder (not a link) that is not current and does not
# hold the live target. Returns non-zero, removing nothing, otherwise.
remove_unserved_release() {
  python3 -I - "$HOME_DIR" "$REL" <<'PY'
import os, shutil, sys
home, rel = sys.argv[1], sys.argv[2]
if os.path.islink(rel) or not os.path.isdir(rel):
    sys.exit(1)
real = os.path.realpath(rel)
for link in ('current', 'live'):
    target = os.path.realpath(os.path.join(home, link))
    if target == real or target.startswith(real + os.sep):
        sys.exit(1)
shutil.rmtree(rel)
PY
}
# check_modes <path>... [--tree <path>...]: every path (and, after --tree, everything under it) must be
# readable by nginx's unprivileged workers: folders o+rx, files o+r. PERMISSIONS_BAD otherwise.
check_modes() {
  python3 -I - "$@" <<'PY'
import os, stat, sys
from pathlib import Path
paths, tree = [], False
for arg in sys.argv[1:]:
    if arg == '--tree':
        tree = True
        continue
    p = Path(arg)
    paths += [p] + (sorted(p.rglob('*')) if tree and p.is_dir() else [])
bad = []
for p in paths:
    if p.is_symlink() or p.name.startswith('.') or not p.exists():
        continue
    mode = p.stat().st_mode
    need = stat.S_IROTH | stat.S_IXOTH if p.is_dir() else stat.S_IROTH
    if mode & need != need:
        bad.append('%s mode=%o' % (p, stat.S_IMODE(mode)))
if bad:
    print('PERMISSIONS_BAD nginx could not read: %s%s. Nothing served changed; current is still the previous '
          'release. Fix with chmod (folders 755, files 644) and run the same command again.'
          % ('; '.join(bad[:5]), ' ...' if len(bad) > 5 else ''), file=sys.stderr)
    sys.exit(1)
PY
}
[ "$(sha "$TAR" 2>/dev/null)" = "$EXPECTED_TAR" ] || refuse "the archive $TAR is missing or its SHA-256 is not $EXPECTED_TAR"
[ "$(sha "$MAN" 2>/dev/null)" = "$EXPECTED_MANIFEST" ] || refuse "the manifest $MAN is missing or its SHA-256 is not $EXPECTED_MANIFEST"
unchanged || refuse "current is not $PREV, or the nginx site file's SHA-256 is not $EXPECTED_CONFIG"
[ ! -L "$REL" ] || refuse "$REL is a symbolic link"
if [ -e "$REL" ]; then
  # Left by an earlier attempt (a crash, or a deploy that failed after current moved and was rolled back).
  # Nothing serves it, so it is replaced; current or the live target is never touched.
  remove_unserved_release || refuse "$REL exists and is current or holds the live variant"
  echo "STAGED_RELEASE_REMOVED $REL (left by an earlier attempt, not served); staging it again" >&2
fi
# nginx serves the live variant once its configuration names live; then the switch must be there.
serves_live=0
if grep -q '^[[:space:]]*root /var/www/volt-home/live;' "$CONFIG"; then serves_live=1; fi
switch_installed=0
if [ -x "$SWITCH" ] && systemctl cat volt-homepage-switch.service >/dev/null 2>&1; then switch_installed=1; fi
if [ "$serves_live" -eq 1 ] && [ "$switch_installed" -eq 0 ]; then
  refuse "nginx serves $HOME_DIR/live but the switch is not installed; stopping before any change"
fi
# The folders above the release that this script does not own: checked before anything is created.
check_modes "$HOME_DIR" "$HOME_DIR/releases" || refuse "a folder above the release is not readable by nginx (above)"
python3 -I - "$TAR" "$MAN" <<'PY' || refuse "the archive does not match its manifest"
import hashlib, re, sys, tarfile
from pathlib import Path, PurePosixPath
expected = {}
for line in Path(sys.argv[2]).read_text().splitlines():
    digest, name = line.split('  ', 1)
    assert re.fullmatch(r'[0-9a-f]{64}', digest)
    path = PurePosixPath(name)
    assert not path.is_absolute() and '..' not in path.parts and name not in expected
    assert name == path.as_posix() and name not in ('', '.')
    expected[name] = digest
assert expected and 'index.html' in expected
# A release built with variants: today's page as original, and the list the Workbench reads.
assert 'variants.json' in expected and 'variants/original/index.html' in expected
with tarfile.open(sys.argv[1]) as archive:
    members = archive.getmembers()
    assert len(members) == len(expected) and len({m.name for m in members}) == len(expected)
    assert {m.name for m in members} == set(expected)
    for member in members:
        assert member.isfile()
        assert hashlib.sha256(archive.extractfile(member).read()).hexdigest() == expected[member.name]
PY
BACKUP="$NGINX_DIR/volt-deployment-backups/$(date -u +%Y%m%d-%H%M%S)-volt-home-$C"
install -d -m 700 "$BACKUP"
cp -a "$NGINX_DIR/sites-available" "$BACKUP/"
cp -a "$NGINX_DIR/sites-enabled" "$BACKUP/"
printf '%s\n' "$PREV" > "$BACKUP/previous-release.txt"
readlink "$HOME_DIR/live" > "$BACKUP/previous-live.txt" 2>/dev/null || true
install -d -m 755 "$REL"
STAGED=1
tar -xzf "$TAR" -C "$REL" --no-same-owner --no-same-permissions
chown -R "$OWNER" "$REL"
find "$REL" -type d -exec chmod 755 {} +
find "$REL" -type f -exec chmod 644 {} +
[ -z "$(find "$REL" ! -type f ! -type d)" ] || refuse "the archive holds something other than files and folders"
# Check every file against the manifest, then give it a modification time derived from its SHA-256
# (a second between 2001 and 2020): same content, same ETag and Last-Modified, in every release and variant.
python3 -I - "$REL" "$MAN" <<'PY' || refuse "the release folder does not match the manifest"
import hashlib, os, sys
from pathlib import Path
rel, manifest = Path(sys.argv[1]), Path(sys.argv[2])
entries = [line.split('  ', 1) for line in manifest.read_text().splitlines()]
found = sorted(p.relative_to(rel).as_posix() for p in rel.rglob('*') if p.is_file())
assert found == sorted(name for _, name in entries), 'the release folder does not match the manifest'
for digest, name in entries:
    path = rel / name
    assert hashlib.sha256(path.read_bytes()).hexdigest() == digest, name
    when = 1_000_000_000 + int(digest[:15], 16) % 600_000_000
    os.utime(path, (when, when), follow_symlinks=False)
PY
# Link-preview cards (site/README.md, "Variants"): add the release's cards/ to the append-only store
# $HOME_DIR/cards/, served at /cards/<name> with an immutable cache. A name is its file's SHA-256, so an
# existing name must already hold the same bytes: a different file under it stops the deploy
# (CARD_CONFLICT) before current moves. Nothing in the store is ever replaced or removed, rollbacks included.
cards_added="$(python3 -I - "$REL/cards" "$HOME_DIR/cards" <<'PY'
import hashlib, os, re, sys
from pathlib import Path
source, store = Path(sys.argv[1]), Path(sys.argv[2])
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
# Every path nginx serves must be readable by its unprivileged workers. Paths this script owns were set
# above (release tree) or just now (store, cards); the folders above them are only checked. Any path without
# the world bits stops the deploy here, before current moves (PERMISSIONS_BAD), with nothing served changed.
check_modes "$HOME_DIR" "$HOME_DIR/releases" --tree "$REL" "$HOME_DIR/cards" || refuse "a served path is not readable by nginx (above)"
nginx -t || refuse "nginx -t failed (its output is above)"
unchanged || refuse "current or the nginx site file changed during the deploy"
# The live link as it was, so a failed deploy can put it back without the switch.
prior_live="$(readlink "$HOME_DIR/live" 2>/dev/null || true)"
point_current "$REL" || refuse "could not move current (it still points at $PREV)"
MOVED=1
live_line="live=not-installed"
if [ "$switch_installed" -eq 1 ]; then
  switch_ok=1
  systemctl start volt-homepage-switch.service || switch_ok=0
  live_target="$(readlink "$HOME_DIR/live" 2>/dev/null || true)"
  inside=0
  case "$live_target" in "$REL"/variants/?*) inside=1 ;; esac
  if [ "$switch_ok" -ne 1 ] || [ "$inside" -ne 1 ]; then
    # Put both links back as they were, directly, then check both. Nothing here may stop the script early.
    set +e
    point_current "$PREV"
    if [ -n "$prior_live" ]; then
      [ "$(readlink "$HOME_DIR/live" 2>/dev/null)" = "$prior_live" ] || swap_link "$HOME_DIR/live" "$prior_live"
    fi
    current_now="$(realpath_of "$HOME_DIR/current")"
    live_now="$(readlink "$HOME_DIR/live" 2>/dev/null)"
    set -e
    current_back=0; live_back=0
    [ "$current_now" = "$(realpath_of "$PREV")" ] && current_back=1
    if [ -n "$prior_live" ]; then
      [ "$live_now" = "$prior_live" ] && live_back=1
    else
      case "$live_now" in "$REL"/*) ;; *) live_back=1 ;; esac
    fi
    if [ "$current_back" -eq 1 ] && [ "$live_back" -eq 1 ]; then
      echo "DEPLOY_FAILED switch_ok=$switch_ok live_after_switch=${live_target:-none}: live was not left inside the new release." \
           "Restored: current -> $PREV, live -> ${prior_live:-none}. The new release stays in $REL for a look; see $STATUS." >&2
      exit 1
    fi
    echo "DEPLOY_INCOMPLETE the deploy failed (switch_ok=$switch_ok) and the rollback did not finish:" \
         "current -> ${current_now:-?} (want $PREV), live -> ${live_now:-none} (want ${prior_live:-none})." >&2
    echo "DEPLOY_INCOMPLETE finish by hand, as root:" \
         "ln -sfn $PREV $HOME_DIR/current.new && mv -Tf $HOME_DIR/current.new $HOME_DIR/current" \
         "${prior_live:+&& ln -sfn $prior_live $HOME_DIR/live.new && mv -Tf $HOME_DIR/live.new $HOME_DIR/live}" \
         "&& readlink -f $HOME_DIR/current && readlink $HOME_DIR/live" >&2
    exit 2
  fi
  # This run's status only: it must name the new release.
  live_line="$(python3 -I - "$STATUS" "$C" "$live_target" <<'PY'
import json, os, sys
try:
    status = json.load(open(sys.argv[1]))
except (OSError, ValueError):
    status = {}
if status.get('current_release') != sys.argv[2]:
    status = {}
name = os.path.basename(sys.argv[3])
print('live=%s result=%s in_new_release=yes message=%s' % (name, status.get('result', 'unknown'), status.get('message', '(no status for this release)')))
PY
)"
fi
echo "UPDATE_OK current=$(realpath_of "$HOME_DIR/current") previous=$PREV backup=$BACKUP cards_added=$cards_added $live_line"
if [ "$switch_installed" -eq 1 ] && [ -n "$prior_live" ]; then
  echo "Rollback: ln -sfn $PREV $HOME_DIR/current.new && mv -Tf $HOME_DIR/current.new $HOME_DIR/current && ln -sfn $prior_live $HOME_DIR/live.new && mv -Tf $HOME_DIR/live.new $HOME_DIR/live (cards stay)"
else
  echo "Rollback: ln -sfn $PREV $HOME_DIR/current.new && mv -Tf $HOME_DIR/current.new $HOME_DIR/current"
fi
