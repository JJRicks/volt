// A fake Android 11 phone for the on-device log commands, run by the real /bin/sh: logcat without
// --uid ("Unknown option", exit 1) unless uid is true, with --pid, -t <time>, -e <regex> and -s <tag>
// filters, -v uid (each row's writer; refused unless uidFormat) and a divider line; pidof; getprop;
// and /proc/<pid>/status. No real device or adb.
import { appendFile, mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { execFile } from 'node:child_process';

const scripts = {
  logcat: `printf '%s\\n' "$*" >> "$FIXTURE/args"
pid=; uid=; show=; t=0; tag=; e=; prev=
for a in "$@"; do
  case "$a" in --uid=*) [ -f "$FIXTURE/uid" ] || { echo "logcat: Unknown option '$a'." >&2; exit 1; }; uid=\${a#--uid=} ;; --pid=*) pid=\${a#--pid=} ;; esac
  [ "$prev" = -v ] && [ "$a" = uid ] && { [ -f "$FIXTURE/uid-format" ] || { echo "logcat: Invalid parameter 'uid' to -v." >&2; exit 1; }; show=1; }
  [ "$prev" = -t ] && t=$a; [ "$prev" = -s ] && tag=\${a%%:*}; [ "$prev" = -e ] && e=$a; prev=$a
done
case "$t" in *.*) ;; *) t=0 ;; esac
[ ! -s "$FIXTURE/log" ] || echo '--------- beginning of main'
awk -v pid="$pid" -v uid="$uid" -v show="$show" -v t="$t" -v tag="$tag" -v e="$e" '{ w = substr($0, 1, index($0, "\t") - 1); $0 = substr($0, index($0, "\t") + 1) }
  ($1 + 0 >= t + 0) && (pid == "" || $2 == pid) && (uid == "" || w == uid) && (tag == "" || $5 == tag ":") && (e == "" || $0 ~ e) {
    if (show) { s = $1; sub(/^[^ ]+/, ""); print s " " w $0 } else print }' "$FIXTURE/log"`,
  pidof: '[ "$1" = com.navee.ucaret ] && [ -s "$FIXTURE/pids" ] && cat "$FIXTURE/pids" || exit 1',
  getprop: '[ "$1" = log.tag.com.navee.ucaret ] && cat "$FIXTURE/property"'
};
// A row as logcat -v epoch prints it, kept with the uid of the app that wrote it (logd's record).
export const logRow = (stamp, pid, message, tag = 'com.navee.ucaret', writer = 10123) => `${writer}\t${stamp}  ${pid}  ${pid} D ${tag}: [2] BleHandler.l: ${message}`;
export async function fakeAndroid({ uid = false, uidFormat = true } = {}) {
  const root = await mkdtemp(join(tmpdir(), 'volt-fake-android-')), bin = join(root, 'bin');
  await mkdir(bin); await mkdir(join(root, 'proc'));
  for (const [name, script] of Object.entries(scripts)) await writeFile(join(bin, name), `#!/bin/sh\n${script}\n`, { mode: 0o700 });
  await writeFile(join(root, 'log'), ''); await writeFile(join(root, 'pids'), ''); await writeFile(join(root, 'property'), 'VERBOSE\n');
  if (uid) await writeFile(join(root, 'uid'), '');
  if (uidFormat) await writeFile(join(root, 'uid-format'), '');
  const env = { ...process.env, PATH: `${bin}:${process.env.PATH}`, FIXTURE: root };
  return {
    // The processes named com.navee.ucaret, as [pid, uid] pairs.
    async processes(list) {
      for (const [pid, owner] of list) {
        await mkdir(join(root, 'proc', String(pid)), { recursive: true });
        await writeFile(join(root, 'proc', String(pid), 'status'), `Name:\tcom.navee.ucaret\nUid:\t${owner}\t${owner}\t${owner}\t${owner}\n`);
      }
      await writeFile(join(root, 'pids'), list.map(([pid]) => pid).join(' '));
    },
    log: rows => appendFile(join(root, 'log'), rows.map(row => `${row}\n`).join('')),
    property: value => writeFile(join(root, 'property'), `${value}\n`),
    args: async () => (await readFile(join(root, 'args'), 'utf8').catch(() => '')).split('\n').filter(Boolean),
    // As transport.runCommand returns it: one final line ending removed, a non-zero exit an error.
    exec: command => new Promise((resolve, reject) => execFile('/bin/sh', ['-c', command.replaceAll('/proc/', `${root}/proc/`)], { env },
      (error, out) => error ? reject(error) : resolve(out.replace(/\r?\n$/, '')))),
    dispose: () => rm(root, { recursive: true, force: true })
  };
}
