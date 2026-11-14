// The NAVEE debugger's trust boundary: which official-app log rows may be kept,
// shown and sent. Pure and free of browser APIs so every rule is unit-tested.
// Raw rows are never returned, stored or thrown; only allowlisted, redacted text
// leaves classify(). Anything unrecognized is only counted as hidden.
import { LOG_FILTERS, PACKAGE, PROPERTY, SetupError, appLogcat, quote, verifiedPids, writerOf, writerPattern } from './protocol.js';
import { SENSITIVE, describe, keyLabel } from './navee-commands.js';

export const LIMITS = { rows: 3000, text: 512, row: 600, note: 200, description: 4000, contact: 240,
  model: 120, version: 40, android: 40, phone: 80, duration: 3600, hidden: 1_000_000, summary: 2000, body: 256 * 1024 };
// Rows stop being added before the JSON body could exceed the server limit,
// leaving room for the longest description and contact (UTF-8, escaped).
export const ROW_BUDGET = LIMITS.body - 34 * 1024;
export const FORMAT = 'volt-navee-capture-v1';
// ModelRegistry.KNOWN names, after the UT5 MAX layer Volt's controls are built on.
export const MODELS = ['UT5 Max', 'N65i', 'ST3 Pro', 'GT5 Max', 'GT3 Max', 'UT5 Ultra X', 'XT5 Pro'];
export const OTHER_MODEL = 'Other / not sure';

// ByteUtil.g: "[55 aa 00 52 01 01 a9 fe fd]", lowercase pairs, single spaces.
const HEX = '\\[[0-9a-f]{2}(?: [0-9a-f]{2}){0,511}\\]';
const FRAME_KINDS = new Set(['write', 'read', 'written']);
// NAVEE 2.1.x Bluetooth/device-control messages (com.uz.navee.ble.*, ui/device/*).
// BleHandler's notify-description rows ("<Chinese description> Data length: N",
// then separate Error / Update Data / CheckSum rows) are deliberately absent:
// their payload rows carry no command byte, and some describe serial or PIN reads.
const ALLOW = [
  ['write', new RegExp(`^Send write command: ${HEX}$`)],
  ['read', new RegExp(`^Send read command: ${HEX}$`)],
  ['written', new RegExp(`^Write success: ${HEX}$`)],
  ['write-failure', /^Write failure: /],
  ['notify', /^Notify success$/],
  ['notify-failure', /^Notify failure: /],
  ['mtu', /^setMTUSuccess: mtu = \d{1,5}$/],
  ['mtu-failure', /^SetMTUFailure: /],
  ['reconnect', /^reconnect after no notify in 7s$/],
  ['send-cmd', /^sendCmd cmd=0X[0-9A-F]{2}, value=0X[0-9A-F]{2}(?:,0X[0-9A-F]{2}){0,255}$/],
  ['ambient', /^sendAmbientCmd: on=0x[0-9A-F]{2} e=0x[0-9A-F]{2} d=0x[0-9A-F]{2} s=0x[0-9A-F]{2}$/],
  ['connect', /^Connect (?:device |mac )?(?:success|fail|start) \(/],
  ['disconnect', /^DisConnected /],
  ['bond', /^Create Bond \(/],
  ['bond-failure', /^Can`t create Bond with /],
  ['lock', /^refreshLockStatus -?\d{1,10}$/],
  ['scan', /^onScanStarted\b/],
  ['scan-done', /^onScanFinished, result count: \d{1,6}$/],
  ['stale', /^out date connection rejected\b/],
  // Exo and DG (other device families) log raw protocol payloads the 55 aa checks can't read:
  // ExoProtocolHandler's "[Exo] type=0x70, data=[…]" carries the serial, and DgEBikeDevicePort
  // logs "DG write auth: […]". Only their fixed status messages are kept.
  ['exo-reconnect', /^\[Exo\] reconect after passive disconnect$/],
  ['dg-write', /^DG write (?:ok: [A-Za-z_]{1,32}|fail \([A-Za-z_]{1,32}\): [^[\]]{1,200})$/], // the failure text is an exception message, never a payload
  ['dg-notify', /^DG notify (?:success$|fail: )/],
  ['dg-session', /^DG session -> \w{1,64}$/],
  ['dg-connect', /^DG (?:connect success|connect fail|disconnected)\b/],
  ['dg-control', /^DG onControlResult: -?\d{1,10}$/],
  ['dg-error', /^DG error code: E-?\d{1,10}$/]
];
// A NAVEE frame header with a sensitive command byte, in any separator style
// (spaces, colons, commas, brackets, "0x" prefixes or none at all).
const SENSITIVE_FRAME = /55[^0-9a-z]{0,3}aa[^0-9a-z]{0,3}[0-9a-f]{2}[^0-9a-z]{0,3}(?:30|31|63|7a|50)/i;
const SENSITIVE_CMD = /cmd\W{0,3}(?:0x)?(?:30|31|63|7a|50)\b/i;
const SECRET_WORDS = /authdata|encryptdata/i;
const MAC = /\b[0-9a-f]{2}(?:[:-][0-9a-f]{2}){5}\b/gi;
const BARE_ADDRESS = /\b[0-9a-f]{12}\b/gi; // BleHandler.r(): MAC or advertised ID, colons removed
const EMAIL = /[^\s@]+@[^\s@]+\.[^\s@]+/;
// Nine or more digits, optionally split like a phone number, outside hex bytes.
const PHONE = /\+?\(?\d(?:[\s().-]{0,2}\d){8,}/;
const HEX_TOKENS = /\[[0-9a-f ]*\]|0x[0-9a-f]{2}/gi;
// Serials: OEM BleHandler.h() is [A-Za-z][A-Za-z\d]{16} (N2546ZZ9US1234567);
// redact any similar letter-led token with several digits (X2538SYNTHETIC0042).
const SERIAL = /\b[a-z][a-z0-9]{13,23}\b/gi;

function hasSecret(text) {
  return SECRET_WORDS.test(text) || SENSITIVE_CMD.test(text) ||
    SENSITIVE_FRAME.test(text) || SENSITIVE_FRAME.test(text.replace(/0x(?=[0-9a-f]{2})/gi, ''));
}
function personal(text) {
  return EMAIL.test(text) || PHONE.test(text.replace(HEX_TOKENS, ' '));
}
function redact(text) {
  return text.replace(MAC, '[MAC]').replace(BARE_ADDRESS, '[MAC]')
    .replace(SERIAL, token => (token.match(/\d/g) || []).length >= 4 ? '[serial]' : token);
}
// Printable ASCII only. NAVEE's full-width comma ("，error") becomes ", ".
function printable(text) {
  return text.replace(/，\s*/g, ', ').replace(/[\t\r\n]/g, ' ').replace(/[^\x20-\x7e]/g, '?').trim();
}
function bound(text, max) { return text.length > max ? text.slice(0, max - 3) + '...' : text; }
// The device name/identifier is replaced, whatever form it takes.
function anonymize(kind, text) {
  switch (kind) {
    case 'connect': case 'bond': case 'dg-connect': return text.replace(/\([^()]*\)/, '([scooter])');
    case 'disconnect': return /^DisConnected (?:(?:mac|device) )?\(/.test(text) ? text.replace(/\([^()]*\)/, '([scooter])') : 'DisConnected [scooter]';
    case 'bond-failure': return text.replace(/^(Can`t create Bond with )[^;]*/, '$1[scooter]');
    case 'stale': return 'out date connection rejected: [scooter] (target=[scooter])';
    default: return text;
  }
}
// Bytes Volt can't read are never sent: every bracketed byte group must be a
// well-formed, non-sensitive NAVEE frame (55 aa … fe fd with a valid checksum).
function opaqueBytes(text) {
  for (const group of text.matchAll(/\[([0-9a-f]{2}(?:[ ,:-]+[0-9a-f]{2})*)\]/gi)) {
    const frame = decodeFrame(group[1].split(/[ ,:-]+/).map(x => parseInt(x, 16)));
    if (!frame || SENSITIVE.has(frame.cmd)) return true;
  }
  return false;
}
function checked(text, max) {
  return typeof text === 'string' && text.length <= max && /^[\x20-\x7e]+$/.test(text) && !hasSecret(text) &&
    !new RegExp(MAC.source, 'i').test(text) && !personal(text) && !opaqueBytes(text);
}
// Final check applied to every row, kept or user-written, before display or send.
export const safeText = text => checked(text, LIMITS.row);
// The same rules for the submission's one-line summary, which may be longer.
export const safeSummary = text => checked(text, LIMITS.summary);
export function hexBytes(text) {
  const match = /\[([0-9a-f]{2}(?: [0-9a-f]{2})*)\]/i.exec(text);
  return match ? match[1].split(' ').map(x => parseInt(x, 16)) : null;
}
// 55 aa <flag> <cmd> [<len> <payload…>] <checksum> fe fd; read requests omit len.
export function decodeFrame(bytes) {
  if (!bytes || bytes.length < 7 || bytes[0] !== 0x55 || bytes[1] !== 0xaa || bytes.at(-2) !== 0xfe || bytes.at(-1) !== 0xfd) return null;
  if ((bytes.slice(0, -3).reduce((a, b) => a + b, 0) & 255) !== bytes.at(-3)) return null;
  if (bytes.length === 7) return { flag: bytes[2], cmd: bytes[3], read: true, payload: [] };
  if (bytes.length !== bytes[4] + 8) return null;
  return { flag: bytes[2], cmd: bytes[3], read: false, payload: bytes.slice(5, -3) };
}
const SEND_CMD = /^sendCmd cmd=0X([0-9A-F]{2}), value=((?:0X[0-9A-F]{2},?)+)$/;
const sendCmd = text => { const m = SEND_CMD.exec(text); return m && [parseInt(m[1], 16), m[2].split(',').map(x => parseInt(x.slice(2), 16))]; };
const NOTE = /^\[(?:note|you said)\] /;
// What Volt makes of a kept frame or sendCmd row (see describe()), or null for any other row.
// Runs only on text classify() already kept; the result is display/summary only.
export function recognize(text) {
  if (typeof text !== 'string' || NOTE.test(text)) return null;
  if (/^(?:Send write command|Send read command|Write success): \[/.test(text)) {
    const frame = decodeFrame(hexBytes(text));
    return frame && describe(frame.cmd, frame.payload, frame.read);
  }
  const command = sendCmd(text);
  return command && describe(command[0], command[1], false);
}
// Short, display-only reading of a kept row; never part of what is sent.
export const annotate = text => recognize(text)?.reading || '';
// Unknown commands and values the page asks about. Reads and NAVEE's clock sync are routine.
export const askable = info => !!info && (info.status === 'new' || info.status === 'new-value') && !info.read && !info.routine;
// Classify one NAVEE message (the text after "com.navee.ucaret:"). Returns the
// redacted text to keep, or null to hide it.
export function classify(message) {
  if (typeof message !== 'string' || message.length > 8192) return null;
  // LogHelper prefixes "[<thread>] <Class>.<method>: "; it identifies nothing useful.
  const text = printable(message.replace(/^\[\d{1,10}\] [^\s:]{1,200}: /, ''));
  const rule = ALLOW.find(([, pattern]) => pattern.test(text));
  if (!rule || hasSecret(message) || hasSecret(text)) return null;
  const [kind] = rule;
  if (FRAME_KINDS.has(kind)) {
    // Only well-formed NAVEE frames, so the command byte is always known.
    const frame = decodeFrame(hexBytes(text));
    if (!frame || SENSITIVE.has(frame.cmd)) return null;
  }
  if (kind === 'send-cmd' && SENSITIVE.has(parseInt(text.slice(10, 12), 16))) return null;
  const kept = bound(redact(anonymize(kind, text)), LIMITS.text);
  return safeText(kept) ? kept : null;
}
function userText(text, prefix) {
  const clean = printable(String(text ?? '')).replace(/\s+/g, ' ');
  if (!clean) throw new SetupError('note', 'Type a short note first.');
  if (clean.length > LIMITS.note) throw new SetupError('note', `Keep notes to ${LIMITS.note} characters.`);
  if (hasSecret(clean) || personal(clean)) throw new SetupError('note', 'Notes can’t include email addresses, phone numbers or Bluetooth security data.');
  const note = `${prefix} ${redact(clean)}`;
  if (!safeText(note)) throw new SetupError('note', 'That note can’t be added. Try plain words.');
  return note;
}
// A user-written marker: local text, clearly labelled, held to the same rules.
export const noteText = text => userText(text, '[note]');
// The user's answer about one unknown command: "[you said] <reading>: <text>", same rules.
export function answerText(reading, text) {
  if (typeof reading !== 'string' || !safeText(reading) || /[[\]]/.test(reading)) throw new SetupError('note', 'That line can’t be answered.');
  return userText(text, `[you said] ${reading}:`);
}
// Phone epoch "seconds.fraction" → UTC "HH:MM:SS.mmm".
export function clock(stamp) {
  const [sec, frac = ''] = String(stamp).split('.');
  return new Date(Number(sec) * 1000).toISOString().slice(11, 19) + '.' + frac.padEnd(3, '0').slice(0, 3);
}
const nanos = stamp => { const [sec, frac = ''] = stamp.split('.'); return BigInt(sec) * 1_000_000_000n + BigInt(frac.padEnd(9, '0').slice(0, 9)); };
// Rolling 64-bit FNV-1a: boundary dedupe without retaining raw row text.
function digest(text) {
  let a = 0xcbf29ce484222325n;
  for (let i = 0; i < text.length; i++) a = BigInt.asUintN(64, (a ^ BigInt(text.charCodeAt(i))) * 0x100000001b3n);
  return a;
}
const ROW = /^\s*(\d{9,11}\.\d{1,9})\s+(.*)$/;
const NAVEE = /^\d{1,10}\s+\d{1,10}\s+[VDIWEFA]\s+com\.navee\.ucaret\s*: ?(.*)$/;
// Holds only kept, redacted rows plus counters. Each poll re-reads from the last
// row's timestamp, so rows at that boundary are deduplicated by digest and count.
// writer: NAVEE's uid when polls are read with -v uid (Android 11 and older, see
// logcatCommand), null when logcat already kept only NAVEE's rows (--uid). Given a
// writer, only rows that uid wrote are read at all. The phone's grep already dropped
// every other row; any that still arrives (another uid's, or one without the writer
// column) is only counted (foreign, unattributed), never kept, hidden, shown, copied
// or sent, and never moves the cursor or dedupe: its writer chose its time, so one
// stamped ahead would skip NAVEE's later rows.
export class LogRecorder {
  constructor(start, writer = null) {
    if (!/^\d{9,11}\.\d{1,9}$/.test(start)) throw new SetupError('clock', 'The phone did not provide a valid capture time.');
    this.start = start; this.cursor = start; this.floor = nanos(start); this.edge = this.floor;
    this.seen = new Map(); this.rows = []; this.hidden = 0; this.bytes = 0; this.full = false; this.next = 1;
    // own: NAVEE rows read (kept, hidden or folded); the others are never NAVEE's.
    this.writer = writer === null ? null : writerOf(writer); this.own = 0; this.foreign = 0; this.unattributed = 0;
    // Folded: "Write success" echoes marked onto their command as " (ok)", and confirmed repeats of a routine read.
    this.folded = 0; this.confirmedReads = new Set(); this.barrier = -1n;
  }
  // Failures and reconnects end correlation: no later confirmation is credited to an earlier command.
  static BREAKS = /^(?:Write failure: |Notify failure: |SetMTUFailure: |Connect |DisConnected|reconnect after)/;
  // NAVEE logs every frame twice (sent, then "Write success") and polls status (read 0x70) every few
  // seconds. A confirmation is marked onto the latest unconfirmed identical command after the last
  // failure; a repeat of a read already confirmed is removed only once its own confirmation arrives,
  // so failed or unanswered attempts always stay. Unmatched confirmations are kept as their own rows.
  fold(text) {
    const echo = /^Write success: (\[[0-9a-f ]+\])$/.exec(text);
    if (echo) {
      for (let i = this.rows.length - 1; i >= Math.max(0, this.rows.length - 40); i--) {
        const row = this.rows[i];
        if (row.at <= this.barrier) break;
        if (row.note || row.acked || (row.text !== `Send write command: ${echo[1]}` && row.text !== `Send read command: ${echo[1]}`)) continue;
        const read = row.text.startsWith('Send read command: ');
        if (read && this.confirmedReads.has(echo[1])) {
          this.rows.splice(i, 1); this.bytes -= row.time.length + row.text.length + 4; this.folded = Math.min(LIMITS.hidden, this.folded + 1);
        } else {
          this.rows[i] = { ...row, id: this.next++, text: `${row.text} (ok)`, acked: true }; this.bytes += 5;
          if (read) this.confirmedReads.add(echo[1]);
        }
        return true;
      }
      return false;
    }
    // NAVEE's "sendCmd cmd=0X6F, value=0X05,0X01" restates the frame it just logged; the frame row stays.
    const command = sendCmd(text);
    if (command) {
      const [cmd, values] = command;
      return this.rows.slice(-3).some(row => {
        const frame = !row.note && /^Send write command: /.test(row.text) ? decodeFrame(hexBytes(row.text)) : null;
        return frame && frame.cmd === cmd && frame.payload.length === values.length && frame.payload.every((b, i) => b === values[i]);
      });
    }
    return false;
  }
  // Called after a failure/reconnect row is kept.
  breakCorrelation(row) { this.barrier = row.at; this.confirmedReads.clear(); }
  // Returns how many timestamped rows the poll held (whoever wrote them) and how many of NAVEE's were new.
  ingest(output) {
    let lines = 0, fresh = 0, added = 0;
    const counts = new Map();
    for (const line of String(output).split('\n')) {
      const row = ROW.exec(line);
      if (!row) continue;
      lines++;
      // Counted each time one arrives (it never moves the dedupe); only the "any at all" matters.
      const written = this.writer ? this.writer(line) : { own: true, rest: row[2] };
      if (!written?.own) {
        if (written) this.foreign = Math.min(LIMITS.hidden, this.foreign + 1);
        else this.unattributed = Math.min(LIMITS.hidden, this.unattributed + 1);
        continue;
      }
      const at = nanos(row[1]);
      if (at < this.floor || at < this.edge) continue;
      const key = digest(row[2]);
      if (at === this.edge) {
        const n = (counts.get(key) || 0) + 1;
        counts.set(key, n);
        if (n <= (this.seen.get(key) || 0)) continue;
        this.seen.set(key, n);
      } else {
        this.edge = at; this.cursor = row[1]; this.seen = new Map([[key, 1]]); counts.clear(); counts.set(key, 1);
      }
      fresh++;
      if (this.full) continue;
      this.own = Math.min(LIMITS.hidden, this.own + 1);
      const message = NAVEE.exec(written.rest);
      const kept = message ? classify(message[1]) : null;
      if (kept === null) { this.hidden = Math.min(LIMITS.hidden, this.hidden + 1); continue; }
      if (this.fold(kept)) { this.folded = Math.min(LIMITS.hidden, this.folded + 1); continue; }
      const added_row = this.add(row[1], kept, false);
      if (added_row) { added++; if (LogRecorder.BREAKS.test(kept)) this.breakCorrelation(added_row); }
    }
    return { lines, fresh, added };
  }
  add(stamp, text, note) {
    const time = clock(stamp), size = time.length + text.length + 4;
    if (this.full || this.rows.length >= LIMITS.rows || this.bytes + size > ROW_BUDGET) { this.full = true; return null; }
    const row = { id: this.next++, at: nanos(stamp), time, text, note, annotation: note ? '' : annotate(text) };
    // Notes use the browser clock and may precede rows still in flight: keep order.
    let i = this.rows.length;
    while (i > 0 && this.rows[i - 1].at > row.at) i--;
    this.rows.splice(i, 0, row); this.bytes += size;
    return row;
  }
  addNote(stamp, text) {
    const row = this.add(stamp, noteText(text), true);
    if (!row) throw new SetupError('full', 'The capture is full, so no more notes can be added.');
    return row;
  }
  // Inserts the user's answer right after the first row with that exact command and value
  // (and after earlier answers to it). Linked by signature: confirmations replace row ids.
  addAnswer(sig, text) {
    const i = this.rows.findIndex(row => !row.note && recognize(row.text)?.sig === sig), target = this.rows[i];
    const info = target ? recognize(target.text) : null;
    if (!askable(info)) throw new SetupError('note', 'That line is no longer in the capture.');
    const note = answerText(info.reading, text), size = target.time.length + note.length + 4;
    if (this.full || this.rows.length >= LIMITS.rows || this.bytes + size > ROW_BUDGET) throw new SetupError('full', 'The capture is full, so no more notes can be added.');
    let j = i + 1;
    while (j < this.rows.length && this.rows[j].note && this.rows[j].sig === sig) j++;
    const row = { id: this.next++, at: target.at, time: target.time, text: note, note: true, annotation: '', sig, said: note.slice(`[you said] ${info.reading}: `.length) };
    this.rows.splice(j, 0, row); this.bytes += size;
    return row;
  }
  remove(id) {
    const i = this.rows.findIndex(row => row.id === id);
    if (i < 0) return;
    const [row] = this.rows.splice(i, 1);
    this.bytes -= row.time.length + row.text.length + 4;
  }
}
const request = (uid, cursor, filter) => /^\d{5}$/.test(String(uid)) && /^\d{9,11}\.\d{1,9}$/.test(cursor) && LOG_FILTERS.includes(filter);
// filter: 'uid' where logcat has --uid (Android 12+), else 'rows': the rows under NAVEE's tag that NAVEE's
// uid wrote (-v uid, kept on the phone by writerPattern), for a LogRecorder given NAVEE's uid.
export function logcatCommand(uid, cursor, count, filter = 'uid') {
  if (!request(uid, cursor, filter) || !Number.isInteger(count) || count < 1 || count > 1000) throw new Error('Invalid logcat request');
  // logcat refuses -m (--max-count) together with -t, so the batch is bounded by head instead. In rows
  // mode the phone drops every other writer's rows before head counts the batch: another app may write
  // any number of rows under NAVEE's tag, at any times it likes, so its rows must never take up the
  // batch or move the cursor. Then, as with --uid, the batch and cursor see only NAVEE's own rows.
  const keep = filter === 'rows' ? `grep -E ${quote(writerPattern(uid))} | ` : '';
  return appLogcat(uid, filter, `-d -v epoch -t '${cursor}' -s ${PACKAGE}:V '*:S'`, `${keep}head -n ${count}`);
}
// Capture health, read-only and counted on the phone so no log text is transferred. One line each:
// how many NAVEE processes have its uid (? without pidof), its log property, then the number of log
// lines since the recording started from all apps and from NAVEE (any tag): by --uid, or in rows mode
// the -v uid rows whose writer column is NAVEE's uid. Rows mode adds two counts of lines under NAVEE's
// tag the poll never reads: those another uid wrote, and those without a writer column. A count is x
// when logcat itself fails.
export function probeCommand(uid, cursor, filter = 'uid') {
  if (!request(uid, cursor, filter)) throw new Error('Invalid probe request');
  const head = `${verifiedPids(uid)}
command -v pidof >/dev/null && echo "$k" || echo '?'
getprop ${PROPERTY}
n() { set -- $( { { logcat -d -t ${quote(cursor)} "$@" 2>/dev/null; echo "$?" >&3; } | grep -v '^--------- ' | wc -l; } 3>&1 ); [ "$1" = 0 ] && echo "$2" || echo x; }
n
`;
  if (filter === 'uid') return `${head}n --uid=${uid}`;
  const own = quote(writerPattern(uid)), count = (args, then) =>
    `set -- $( { { logcat -d -t ${quote(cursor)} -v uid -v epoch${args} 2>/dev/null; echo "$?" >&3; } | ${then}; } 3>&1 ); [ "$1" = 0 ] && echo "$2" || echo x`;
  // c: NAVEE's tag, not NAVEE's uid; then with a writer column (another uid's), or (c -v) without one.
  return `${head}${count('', `grep -c -E ${own}`)}
c() { ${count(` -s ${PACKAGE}:V '*:S'`, `grep -v '^--------- ' | grep -v -E ${own} | grep -c "$@" -E ${quote(writerPattern(null))}`)}; }
c
c -v`;
}
// probeCommand's output → captureHealth's facts. Anything unexpected is unknown (null). Rows mode's two
// extra lines are foreign (another uid's rows under NAVEE's tag) and unattributed (rows there without a writer).
export function parseProbe(output) {
  const lines = String(output ?? '').split(/\r?\n/);
  if (lines.length !== 4 && lines.length !== 6) return { propertyOk: null, running: null, phoneLines: null, naveeLines: null };
  const [pids, property, all, navee, other, bare] = lines, count = text => /^\d{1,9}$/.test(text.trim()) ? Number(text.trim()) : null;
  const facts = { propertyOk: /^[\x20-\x7e]{0,91}$/.test(property) ? property === 'VERBOSE' : null,
    running: /^\d{1,3}$/.test(pids) ? Number(pids) > 0 : null, phoneLines: count(all), naveeLines: count(navee) };
  return lines.length === 6 ? { ...facts, foreign: count(other), unattributed: count(bare) } : facts;
}
export const HEALTH = {
  checking: 'Checking that NAVEE’s lines are arriving…',
  property: 'NAVEE’s log setting didn’t take on this phone, so its lines can’t be read. Try recording again.',
  unsupported: 'This phone’s log reader can’t pick out NAVEE’s lines, so nothing can be recorded on it yet.',
  'logcat-failed': 'This phone’s log reader is refusing the request, so no NAVEE lines are arriving. Try recording again.',
  impostor: 'Another app is writing under NAVEE’s name; its lines are ignored.',
  'phone-logs': 'This phone isn’t keeping app logs. Try this: in Developer options, set Logger buffer size to 256K or more (not Off), then record again.',
  'not-running': 'NAVEE isn’t running. Open NAVEE and connect it to your scooter.',
  'navee-silent': 'NAVEE isn’t writing logs this page can read. Try this: close NAVEE fully (swipe it away), reopen it and connect. Some Xiaomi (MIUI) phones may also restrict app logs.',
  'no-bluetooth': 'NAVEE is logging, but no Bluetooth lines yet. Make sure it’s connected to your scooter, then change a setting.',
  healthy: 'Receiving NAVEE Bluetooth lines.'
};
// Why a recording is (still) empty, in order; null facts are unknown and never name a cause. filter
// and logcat (logcatReply's status) come from the latest poll; own is the recorder's count of rows
// NAVEE's uid wrote under its tag; foreign and unattributed (rows mode) count rows under NAVEE's tag
// that another uid wrote, or that lack a writer column (the probe's counts, as the poll never reads
// them, plus any the recorder was handed); the rest come from the probe. Kept rows prove NAVEE's lines
// arrive, which outranks counts of a ring buffer that may have rotated.
export function captureHealth({ propertyOk = null, filter = null, logcat = null, running = null, phoneLines = null, naveeLines = null,
  kept = 0, own = 0, foreign = 0, unattributed = 0 } = {}) {
  // none: a poll has read the log and nothing under NAVEE's tag was NAVEE's. (The probe runs before the
  // first poll, when nothing has been read yet.)
  const empty = !(kept > 0), none = empty && !(own > 0) && logcat === 'ok';
  const state = propertyOk === false ? 'property'
    // Rows mode is the fallback for a refused --uid, so a failing read means neither works; so do rows
    // the reader prints without their writer, which can never be told apart from another app's.
    : logcat === 'failed' ? (filter === 'rows' && empty ? 'unsupported' : 'logcat-failed')
    : none && unattributed > 0 ? 'unsupported'
    // NAVEE's tag, but only ever from another uid: those rows are ignored (only counted).
    : none && foreign > 0 ? 'impostor'
    : running === true && phoneLines === 0 && !(naveeLines > 0) && empty ? 'phone-logs'
    : running === false ? 'not-running'
    : running === true && phoneLines > 0 && naveeLines === 0 && empty ? 'navee-silent'
    : naveeLines > 0 && empty ? 'no-bluetooth'
    : empty ? 'checking' : 'healthy';
  return { state, text: HEALTH[state] };
}
// What the kept command rows add up to: new commands by key, new values by exact value, user
// answers by signature, and counts of known commands and reads.
export function tally(rows) {
  const news = new Map(), values = new Map(), said = new Map(), seen = new Map();
  let known = 0, reads = 0;
  for (const row of rows) if (row.note && row.sig && row.said) said.set(row.sig, [...(said.get(row.sig) || []), row.said]);
  for (const row of rows) {
    const info = row.note ? null : recognize(row.text);
    if (!info) continue;
    if (info.name) seen.set(info.key, (seen.get(info.key) || 0) + 1);
    if (info.status === 'known') { known++; continue; }
    if (info.status === 'read') { reads++; continue; }
    const map = info.status === 'new' ? news : values, id = info.status === 'new' ? info.key + (info.read ? 'r' : '') : info.sig;
    const item = map.get(id) || { ...info, count: 0, answers: [] };
    item.count++;
    for (const answer of said.get(info.sig) || []) if (!item.answers.includes(answer)) item.answers.push(answer);
    map.set(id, item);
  }
  return { known, reads, news: [...news.values()], values: [...values.values()], seen };
}
// Plain-ASCII summary sent beside the rows, e.g. "New to Volt: 0xa4 x3 [you said: rainbow mode];
// New values: 0x53 Energy recovery=45 x1; Known: 18 commands". Empty when nothing is new.
// Built from tally() only, bounded, and held to the row rules (safeSummary).
export function summaryText(rows) {
  const t = tally(rows);
  if (!t.news.length && !t.values.length) return '';
  const known = `Known: ${t.known} ${t.known === 1 ? 'command' : 'commands'}${t.reads ? `, ${t.reads} ${t.reads === 1 ? 'read' : 'reads'}` : ''}`;
  const sections = [['New to Volt', t.news, item => `${keyLabel(item.key)}${item.read ? ' read' : ''} x${item.count}`],
    ['New values', t.values, item => `${keyLabel(item.key)} ${item.name}=${item.values.join(',') || 'nothing'} x${item.count}`]].filter(([, items]) => items.length);
  // One budget for the whole line: each section gets an equal share of what is left (unused
  // room passes on) and keeps space for a "+N more" tail; each item's answers stay short.
  const build = withAnswers => {
    const said = item => withAnswers && item.answers.length ? ` [you said: ${bound(item.answers.join(' / ').replace(/[[\]]/g, ''), LIMITS.note)}]` : '';
    let out = '';
    sections.forEach(([title, items, text], s) => {
      const share = Math.floor((LIMITS.summary - known.length - out.length) / (sections.length - s)) - 2 - 16;
      let part = `${title}: `;
      for (let i = 0; i < items.length; i++) {
        const next = `${part}${i ? ', ' : ''}${text(items[i])}${said(items[i])}`;
        if (next.length > share) { part += `${i ? ', ' : ''}+${items.length - i} more`; break; }
        part = next;
      }
      out += `${part}; `;
    });
    return out + known;
  };
  const full = build(true);
  if (safeSummary(full)) return full;
  const plain = build(false);
  return safeSummary(plain) ? plain : '';
}
function field(value, max, pattern = /^[\x20-\x7e]*$/) {
  const text = String(value ?? '').trim();
  return text.length <= max && pattern.test(text) ? text : null;
}
// Builds the exact body the server accepts; anything out of bounds is refused
// here rather than sent. rows are re-checked even though classify() kept them.
export function submission({ model, description, contact, naveeVersion, android, phone, startedAt, durationS, hidden, rows }) {
  const text = String(description ?? '').replace(/\r\n?/g, '\n').replace(/[\u0000-\u0009\u000b-\u001f\u007f]/g, ' ').trim();
  const body = {
    format: FORMAT, model: field(model, LIMITS.model), description: text,
    contact: field(String(contact ?? '').replace(/[\u0000-\u001f\u007f]/g, ' '), LIMITS.contact, /^[^\u0000-\u001f\u007f]*$/),
    navee_version: field(naveeVersion, LIMITS.version), android: field(android, LIMITS.android), phone: field(phone, LIMITS.phone),
    started_at: startedAt, duration_s: durationS, hidden_rows: hidden, rows: rows.map(row => `${row.time} ${row.text}`)
  };
  // An empty capture first: no edit to the details can make it sendable. Then name the field to fix.
  if (!rows.some(row => !row.note)) throw new SetupError('invalid', 'There are no NAVEE lines to send. Keep at least one, or record again.');
  const problem = !body.description ? 'Describe the problem first.'
    : body.description.length > LIMITS.description ? `Keep the description to ${LIMITS.description.toLocaleString('en-US')} characters.`
    : body.contact === null ? `The contact is too long or has characters that can’t be sent. Use up to ${LIMITS.contact} characters.`
    : !body.model ? 'Choose your scooter model.'
    : body.navee_version === null || body.android === null || body.phone === null ? 'The phone details couldn’t be read. Record again.'
    : !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{3})?Z$/.test(startedAt) ||
      !Number.isInteger(durationS) || durationS < 0 || durationS > LIMITS.duration ||
      !Number.isInteger(hidden) || hidden < 0 || hidden > LIMITS.hidden ? 'The recording details couldn’t be checked; record again.' : '';
  if (problem) throw new SetupError('invalid', problem);
  if (body.rows.length > LIMITS.rows) throw new SetupError('invalid', 'This capture is too large to send. Delete some lines and try again.');
  if (!body.rows.every(row => row.length <= LIMITS.row && /^\d{2}:\d{2}:\d{2}\.\d{3} /.test(row) && safeText(row.slice(13))))
    throw new SetupError('invalid', 'A line could not be checked safely, so nothing was sent.');
  // Optional: present only when the capture holds something Volt doesn't know.
  const summary = summaryText(rows);
  if (summary) body.summary = summary;
  if (body.summary !== undefined && !safeSummary(body.summary))
    throw new SetupError('invalid', 'A line could not be checked safely, so nothing was sent.');
  const json = JSON.stringify(body);
  if (new TextEncoder().encode(json).length > LIMITS.body) throw new SetupError('invalid', 'This capture is too large to send. Delete some lines and try again.');
  return json;
}
