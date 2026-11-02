// A fake Android 11 phone for the on-device log commands, run by the real /bin/sh: logcat without
// --uid ("Unknown option", exit 1) unless uid is true, with --pid, -t <time>, -e <regex> and -s <tag>
// filters and a divider line; pidof; getprop; and /proc/<pid>/status. No real device or adb.
import { appendFile, mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { execFile } from 'node:child_process';

const scripts = {
  logcat: `printf '%s\\n' "$*" >> "$FIXTURE/args"
pid=; t=0; tag=; e=; prev=
for a in "$@"; do
  case "$a" in --uid=*) [ -f "$FIXTURE/uid" ] || { echo "logcat: Unknown option '$a'." >&2; exit 1; } ;; --pid=*) pid=\${a#--pid=} ;; esac
  [ "$prev" = -t ] && t=$a; [ "$prev" = -s ] && tag=\${a%%:*}; [ "$prev" = -e ] && e=$a; prev=$a
done
case "$t" in *.*) ;; *) t=0 ;; esac
[ ! -s "$FIXTURE/log" ] || echo '--------- beginning of main'
awk -v pid="$pid" -v t="$t" -v tag="$tag" -v e="$e" '($1 + 0 >= t + 0) && (pid == "" || $2 == pid) && (tag == "" || $5 == tag ":") && (e == "" || $0 ~ e)' "$FIXTURE/log"`,
  pidof: '[ "$1" = com.navee.ucaret ] && [ -s "$FIXTURE/pids" ] && cat "$FIXTURE/pids" || exit 1',
  getprop: '[ "$1" = log.tag.com.navee.ucaret ] && cat "$FIXTURE/property"'
};
// A row as logcat -v epoch prints it.
export const logRow = (stamp, pid, message, tag = 'com.navee.ucaret') => `${stamp}  ${pid}  ${pid} D ${tag}: [2] BleHandler.l: ${message}`;
export async function fakeAndroid({ uid = false } = {}) {
  const root = await mkdtemp(join(tmpdir(), 'volt-fake-android-')), bin = join(root, 'bin');
  await mkdir(bin); await mkdir(join(root, 'proc'));
  for (const [name, script] of Object.entries(scripts)) await writeFile(join(bin, name), `#!/bin/sh\n${script}\n`, { mode: 0o700 });
  await writeFile(join(root, 'log'), ''); await writeFile(join(root, 'pids'), ''); await writeFile(join(root, 'property'), 'VERBOSE\n');
  if (uid) await writeFile(join(root, 'uid'), '');
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
