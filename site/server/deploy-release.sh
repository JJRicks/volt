#!/bin/bash
# Installs one homepage release on the VPS and keeps the live variant choice. Run as root, after review,
# merge and the owner's OK, from the Mac through the deploy wrappers (see site/README.md, "Deploy"):
#   bash -s -- <commit> <archive sha256> <manifest sha256> <previous release commit> <nginx config sha256> < deploy-release.sh
# The archive and manifest must already be in /tmp as volt-home-<commit>.tar.gz and .manifest.
#
# Replaces the update.sh used through Volt 1.13.0. The same checks, plus:
#   - the release must hold variants.json and a real variants/original/index.html;
#   - once nginx serves /var/www/volt-home/live, the switch (site/server/) must be installed;
#   - after current moves, volt-homepage-switch.service runs, so the live variant moves to the new release
#     (or falls back to original when the release lacks it) and its status file records the result.
# Until the switch runs, / keeps serving the previous release's copy of the live variant; nothing dangles.
set -euo pipefail
[[ $# -eq 5 ]]
C="$1"; EXPECTED_TAR="$2"; EXPECTED_MANIFEST="$3"; EXPECTED_PREV="$4"; EXPECTED_CONFIG="$5"
[[ "$C" =~ ^[0-9a-f]{40}$ && "$EXPECTED_PREV" =~ ^[0-9a-f]{40}$ ]]
for checksum in "$EXPECTED_TAR" "$EXPECTED_MANIFEST" "$EXPECTED_CONFIG"; do
  [[ "$checksum" =~ ^[0-9a-f]{64}$ ]]
done
TAR="/tmp/volt-home-$C.tar.gz"; MAN="/tmp/volt-home-$C.manifest"
CONFIG=/etc/nginx/sites-available/volt-homepage
HOME_DIR=/var/www/volt-home
REL="$HOME_DIR/releases/$C"
PREV="$HOME_DIR/releases/$EXPECTED_PREV"
SWITCH=/usr/local/sbin/volt-homepage-switch
STATUS=/var/lib/volt-homepage-switch/status.json
unchanged() {
  [ "$(readlink -f "$HOME_DIR/current")" = "$PREV" ]
  [ "$(sha256sum "$CONFIG" | cut -d' ' -f1)" = "$EXPECTED_CONFIG" ]
}
[ "$(sha256sum "$TAR" | cut -d' ' -f1)" = "$EXPECTED_TAR" ]
[ "$(sha256sum "$MAN" | cut -d' ' -f1)" = "$EXPECTED_MANIFEST" ]
unchanged
[ ! -e "$REL" ]
[ ! -L "$REL" ]
# nginx serves the live variant once its configuration names live; then the switch must be there.
serves_live=0
if grep -q '^[[:space:]]*root /var/www/volt-home/live;' "$CONFIG"; then serves_live=1; fi
switch_installed=0
if [ -x "$SWITCH" ] && systemctl cat volt-homepage-switch.service >/dev/null 2>&1; then switch_installed=1; fi
if [ "$serves_live" -eq 1 ] && [ "$switch_installed" -eq 0 ]; then
  echo "nginx serves $HOME_DIR/live but the switch is not installed; stopping before any change." >&2
  exit 1
fi
python3 -I - "$TAR" "$MAN" <<'PY'
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
BACKUP="/etc/nginx/volt-deployment-backups/$(date -u +%Y%m%d-%H%M%S)-volt-home-$C"
install -d -m 700 "$BACKUP"
cp -a /etc/nginx/sites-available "$BACKUP/"
cp -a /etc/nginx/sites-enabled "$BACKUP/"
printf '%s\n' "$PREV" > "$BACKUP/previous-release.txt"
readlink "$HOME_DIR/live" > "$BACKUP/previous-live.txt" 2>/dev/null || true
install -d -m 755 "$REL"
tar -xzf "$TAR" -C "$REL" --no-same-owner --no-same-permissions
chown -R root:root "$REL"
find "$REL" -type d -exec chmod 755 {} +
find "$REL" -type f -exec chmod 644 {} +
(cd "$REL" && sha256sum --quiet -c "$MAN")
[ "$(find "$REL" -type f | wc -l)" -eq "$(wc -l < "$MAN")" ]
[ -z "$(find "$REL" ! -type f ! -type d)" ]
nginx -t
unchanged
ln -sfn "$REL" "$HOME_DIR/current.new"
mv -Tf "$HOME_DIR/current.new" "$HOME_DIR/current"
live_line="live=not-installed"
if [ "$switch_installed" -eq 1 ]; then
  systemctl start volt-homepage-switch.service || echo "The switch reported a problem; see its status below." >&2
  live_line="$(python3 -I - "$STATUS" "$REL" <<'PY'
import json, os, sys
status = json.load(open(sys.argv[1]))
live = os.readlink('/var/www/volt-home/live')
inside = live.startswith(sys.argv[2] + '/variants/')
print('live=%s result=%s in_new_release=%s message=%s' % (status.get('live'), status.get('result'), 'yes' if inside else 'NO', status.get('message')))
PY
)"
fi
echo "UPDATE_OK current=$(readlink -f "$HOME_DIR/current") previous=$PREV backup=$BACKUP $live_line"
echo "Rollback: ln -sfn $PREV $HOME_DIR/current.new && mv -Tf $HOME_DIR/current.new $HOME_DIR/current && systemctl start volt-homepage-switch.service"
