#!/bin/bash
# Installs the live-homepage switch on the VPS. Run as root from a copy of site/server/:
#   bash install-switch.sh
# Installs /usr/local/sbin/volt-homepage-switch and its two systemd units, creates the Workbench's
# request folder /var/lib/volt-dev/homepage (owned by volt-dev), and runs the switch once. With no
# request yet, that makes original live. Replaced files are kept in /var/backups/volt-homepage-switch/.
# Touches nothing else: no nginx files, no other sites. Uninstalling is described in site/README.md.
set -euo pipefail
# Every mode below is explicit; nothing depends on the caller's umask.
umask 022
here="$(cd "$(dirname "$0")" && pwd)"
[ "$(id -u)" -eq 0 ] || { echo "Run as root." >&2; exit 1; }
id volt-dev >/dev/null 2>&1 || { echo "The volt-dev user (the Workbench service) does not exist." >&2; exit 1; }
[ -d /var/www/volt-home/releases ] || { echo "No /var/www/volt-home/releases." >&2; exit 1; }
/usr/bin/python3 -I -c 'import sys; sys.exit(0 if sys.version_info >= (3, 8) else 1)' || { echo "Needs Python 3.8 or later." >&2; exit 1; }
/usr/bin/python3 -I -c 'import ast, sys; ast.parse(open(sys.argv[1]).read())' "$here/volt-homepage-switch"

backup="/var/backups/volt-homepage-switch/$(date -u +%Y%m%d-%H%M%S)"
install -d -m 700 "$backup"
for file in /usr/local/sbin/volt-homepage-switch /etc/systemd/system/volt-homepage-switch.service \
            /etc/systemd/system/volt-homepage-switch.path; do
  if [ -e "$file" ]; then cp -a "$file" "$backup/"; fi
done

install -o root -g root -m 0755 "$here/volt-homepage-switch" /usr/local/sbin/volt-homepage-switch
install -o root -g root -m 0644 "$here/volt-homepage-switch.service" /etc/systemd/system/volt-homepage-switch.service
install -o root -g root -m 0644 "$here/volt-homepage-switch.path" /etc/systemd/system/volt-homepage-switch.path
# The Workbench writes /var/lib/volt-dev/homepage/request; its parent stays volt-dev's private folder.
install -d -o volt-dev -g volt-dev -m 0700 /var/lib/volt-dev/homepage

systemctl daemon-reload
systemctl enable --now volt-homepage-switch.path
result=0
systemctl start volt-homepage-switch.service || result=$?
echo "SWITCH_INSTALLED backup=$backup"
cat /var/lib/volt-homepage-switch/status.json
echo "live -> $(readlink /var/www/volt-home/live 2>/dev/null || echo '(none)')"
exit "$result"
