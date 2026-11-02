// Deliberately independent of browser APIs so the trust boundary can be tested.
export const MAX_CAPTURE_BYTES = 1024 * 1024;
export const PACKAGE = 'com.navee.ucaret';
export const PROPERTY = `log.tag.${PACKAGE}`;
export class SetupError extends Error {
  constructor(code, message) { super(message); this.name = 'SetupError'; this.code = code; }
}
export function parseAuthLine(line) {
  const at = line.indexOf('Send authData:');
  if (at < 0) return null;
  let candidate = line.slice(at + 14).trim();
  if (candidate.startsWith('[')) {
    const end = candidate.indexOf(']');
    if (end < 0) return null;
    candidate = candidate.slice(1, end).trim();
  }
  if (!/^[\da-f]{2}(?:[\s:-]*[\da-f]{2}){16}$/i.test(candidate)) return null;
  const bytes = candidate.replace(/[\s:-]/g, '').match(/../g).map(x => parseInt(x, 16));
  if (bytes.slice(0, 5).join() !== '85,170,0,48,9' || bytes[5] > 4 || bytes[6] > 1 ||
      bytes[13] !== 0 || bytes[15] !== 254 || bytes[16] !== 253 ||
      bytes.slice(0, 14).reduce((a, b) => a + b, 0) % 256 !== bytes[14]) return null;
  return { identity: bytes.slice(7, 13).map(x => x.toString(16).padStart(2, '0')).join('').toUpperCase(), role: bytes[6] };
}
export function parseCapture(text, since, previous = null) {
  if (new TextEncoder().encode(text).length > MAX_CAPTURE_BYTES)
    throw new SetupError('oversize', 'The filtered capture was unexpectedly large. No setup was saved.');
  let found = previous;
  for (const line of text.split('\n')) {
    const stamp = /^\s*(\d{9,}(?:\.\d+)?)\s/.exec(line);
    if (!stamp || Number(stamp[1]) < since) continue;
    const parsed = parseAuthLine(line);
    if (!parsed) continue;
    if (found && (found.identity !== parsed.identity || found.role !== parsed.role))
      throw new SetupError('conflict', 'More than one owner identity or role arrived. Use one NAVEE account and one scooter, then try again.');
    found = parsed;
  }
  return found;
}
export function ownerDocument(result, now = new Date()) {
  if (!result || !/^[A-F0-9]{12}$/.test(result.identity) || ![0, 1].includes(result.role))
    throw new SetupError('invalid', 'No verified owner setup is ready to save.');
  return JSON.stringify({
    format: 'navee-offline-owner-v1', owner_identity: result.identity, role: result.role,
    model_name: 'NAVEE scooter', captured_at_utc: now.toISOString(),
    note: 'Private Bluetooth owner identity; not a NAVEE password or cloud login token.'
  }, null, 2) + '\n';
}
export const quote = value => "'" + String(value).replaceAll("'", "'\\''") + "'";
// Reading only NAVEE's logs. logcat --uid arrived in Android 12; Android 11 and older exit "Unknown
// option" (and a pipe would hide that as no output). Ask logcat itself, not the SDK level, once per
// capture: "0" means --uid works, anything else means the fallback: NAVEE's verified pid (the debugger)
// or, where every row must be NAVEE's own (owner capture), each row's writer, -v uid (see ownRows).
export const LOG_FILTERS = ['uid', 'pid', 'rows'];
const appUid = uid => { if (!/^\d{5}$/.test(String(uid))) throw new Error('Invalid app uid'); return String(uid); };
export const logFilterCheck = uid => `logcat --uid=${appUid(uid)} -d -t 1 >/dev/null 2>&1; echo $?`;
export const logFilter = (output, fallback = 'pid') => output === '0' ? 'uid' : fallback;
// Shell fragment: NAVEE's processes whose real uid is uid. A process name proves nothing, and
// /proc/<pid> itself is root-owned once an app is non-dumpable, so the Uid: line of its status is
// read. Leaves k = verified pids, q = the last of them, o = same-named processes with another uid.
export const verifiedPids = uid => `k=0 o=0 q=; for p in $(pidof ${PACKAGE} 2>/dev/null); do set -- $(grep '^Uid:' /proc/$p/status 2>/dev/null); ` +
  `if [ "$2" = ${appUid(uid)} ]; then k=$((k+1)) q=$p; elif [ -n "$2" ]; then o=$((o+1)); fi; done`;
// One read of NAVEE's lines, `logcat <filter> <args> | <then>` (args and then are trusted shell built
// from validated values): by uid, every row with its writer's uid, or by NAVEE's one verified pid,
// resolved in the same command so a restart is followed. A last line reports logcat's exit status ("~0"), or instead of reading in pid
// mode: "~pid0" (not running), "~pidN" (several), "~uid" (only another uid has the name), "~127" (no pidof).
export function appLogcat(uid, filter, args, then = 'cat') {
  if (!LOG_FILTERS.includes(filter)) throw new Error('Invalid log filter');
  const read = by => `{ r=$( { { logcat ${by} ${args} 2>/dev/null; echo "$?" >&3; } | ${then} >&4; } 3>&1 ); echo "~$r"; } 4>&1`;
  if (filter === 'uid') return read(`--uid=${appUid(uid)}`);
  if (filter === 'rows') return read('-v uid');
  return `${verifiedPids(uid)}
if ! command -v pidof >/dev/null; then echo '~127'; elif [ "$k" = 1 ]; then ${read('--pid="$q"')}; elif [ "$k" = 0 ] && [ "$o" != 0 ]; then echo '~uid'; else echo "~pid$k"; fi`;
}
// The -v uid rows NAVEE's uid wrote, without that column (as --uid prints them); any other row, or one
// without the column, is dropped. logd records each row's uid from the kernel's socket credentials, so
// unlike a process name or a recycled pid no other app can claim it. getpwuid names of five characters
// or fewer replace the number, so an app uid below 10010 prints as u0_a0 to u0_a9.
export function ownRows(output, uid) {
  const id = Number(appUid(uid)), names = [String(id), id < 10010 ? `u0_a${id - 10000}` : String(id)];
  return String(output).split('\n').flatMap(line => {
    const row = /^(\s*\d{9,}(?:\.\d+)?)\s+(\S+)\s+(?=\d+\s+\d+\s+[A-Z]\s)/.exec(line);
    return row && names.includes(row[2]) ? [`${row[1]} ${line.slice(row[0].length)}`] : [];
  }).join('\n');
}
// appLogcat's reply: the output before its report, and the outcome. A non-zero exit after output is
// head ending the read early (SIGPIPE), unless strict (nothing cuts the read short, so any output may
// be incomplete); with no output, or with no report at all, the read failed.
export function logcatReply(text, strict = false) {
  const all = String(text ?? ''), at = all.lastIndexOf('\n'), last = /^~(?:(\d{1,3})|pid(\d{1,3})|(uid))$/.exec(all.slice(at + 1));
  const output = last ? all.slice(0, Math.max(at, 0)) : all;
  const status = !last ? 'failed' : last[3] ? 'other-uid' : last[2] !== undefined ? (last[2] === '0' ? 'no-pid' : 'several')
    : last[1] === '0' || (!strict && output.trim() !== '') ? 'ok' : 'failed';
  return { output, status };
}
export function tokenValid(token) { return typeof token === 'string' && /^[a-f0-9]{64}$/.test(token); }
export function randomToken() {
  return Array.from(crypto.getRandomValues(new Uint8Array(32)), x => x.toString(16).padStart(2, '0')).join('');
}
export function parseAndroidUsers(text) {
  if (new TextEncoder().encode(text).length > 8192) throw new SetupError('profile', 'Android profiles could not be checked safely. No capture was started.');
  const lines = text.trim().split(/\r?\n/);
  if (lines.shift() !== 'Users:' || lines.length < 1 || lines.length > 32)
    throw new SetupError('profile', 'Android profiles could not be checked safely. No capture was started.');
  const ids = new Set();
  for (const line of lines) {
    // User-visible names are discarded, never displayed, persisted or commands.
    const match = /^\s*UserInfo\{(\d{1,5}):[^\r\n]*:[a-f\d]+\}(?: running)?\s*$/i.exec(line);
    if (!match || Number(match[1]) > 21474 || ids.has(Number(match[1])))
      throw new SetupError('profile', 'Android profiles could not be checked safely. No capture was started.');
    ids.add(Number(match[1]));
  }
  if (!ids.has(0)) throw new SetupError('profile', 'The main Android profile could not be identified.');
  return [...ids];
}
export function hasNaveePackage(text) {
  if (!text.trim()) return false;
  let found = false;
  for (const line of text.split(/\r?\n/)) {
    const match = /^package:([a-zA-Z\d_.]+) uid:(\d{1,10})$/.exec(line);
    if (!match) throw new SetupError('profile', 'Apps in another Android profile could not be checked safely.');
    if (match[1] === PACKAGE) found = true;
  }
  return found;
}
export function validLauncher(component) {
  return typeof component === 'string' && component.length <= 256 &&
    /^com\.navee\.ucaret\/(?:\.?[A-Za-z_$][\w$]*)(?:\.[A-Za-z_$][\w$]*)*$/.test(component);
}
