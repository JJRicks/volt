// The NAVEE debugger's trust boundary: which official-app log rows may be kept,
// shown and sent. Pure and free of browser APIs so every rule is unit-tested.
// Raw rows are never returned, stored or thrown; only allowlisted, redacted text
// leaves classify(). Anything unrecognized is only counted as hidden.
import { SetupError } from './protocol.js';
import { SENSITIVE, commandName } from './navee-commands.js';

export const LIMITS = { rows: 3000, text: 512, row: 600, note: 200, description: 4000, contact: 240,
  model: 120, version: 40, android: 40, phone: 80, duration: 3600, hidden: 1_000_000, body: 256 * 1024 };
// Rows stop being added before the JSON body could exceed the server limit,
// leaving room for the longest description and contact (UTF-8, escaped).
export const ROW_BUDGET = LIMITS.body - 34 * 1024;
export const FORMAT = 'volt-navee-capture-v1';
// ModelRegistry.KNOWN names, after the UT5 MAX layer Volt's controls are built on.
export const MODELS = ['UT5 Max', 'N65i', 'ST3 Pro', 'GT5 Max', 'UT5 Ultra X', 'XT5 Pro'];
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
const SENSITIVE_FRAME = /55[^0-9a-z]{0,3}aa[^0-9a-z]{0,3}[0-9a-f]{2}[^0-9a-z]{0,3}(?:30|31|63|7a)/i;
const SENSITIVE_CMD = /cmd\W{0,3}(?:0x)?(?:30|31|63|7a)\b/i;
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
// Final check applied to every row, kept or user-written, before display or send.
export function safeText(text) {
  return typeof text === 'string' && /^[\x20-\x7e]{1,600}$/.test(text) && !hasSecret(text) &&
    !new RegExp(MAC.source, 'i').test(text) && !personal(text) && !opaqueBytes(text);
}
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
const hex = n => '0x' + n.toString(16).padStart(2, '0');
// Short, display-only reading of a kept row; never part of what is sent.
export function annotate(text) {
  const frame = decodeFrame(hexBytes(text) || []);
  if (frame) {
    const [name, depth] = commandName(frame.cmd, frame.payload), values = frame.payload.slice(depth);
    if (frame.read) return `read ${hex(frame.cmd)}${name ? ` ${name}` : ''}`;
    return `${hex(frame.cmd)}${name ? ` ${name}` : ''}${values.length ? ` = ${values.join(', ')}` : ''}`;
  }
  const command = /^sendCmd cmd=0X([0-9A-F]{2})/.exec(text);
  if (command) return commandName(parseInt(command[1], 16))[0] || '';
  return '';
}
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
// A user-written marker: local text, clearly labelled, held to the same rules.
export function noteText(text) {
  const clean = printable(String(text ?? '')).replace(/\s+/g, ' ');
  if (!clean) throw new SetupError('note', 'Type a short note first.');
  if (clean.length > LIMITS.note) throw new SetupError('note', `Keep notes to ${LIMITS.note} characters.`);
  if (hasSecret(clean) || personal(clean)) throw new SetupError('note', 'Notes can’t include email addresses, phone numbers or Bluetooth security data.');
  const note = `[note] ${redact(clean)}`;
  if (!safeText(note)) throw new SetupError('note', 'That note can’t be added. Try plain words.');
  return note;
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
export class LogRecorder {
  constructor(start) {
    if (!/^\d{9,11}\.\d{1,9}$/.test(start)) throw new SetupError('clock', 'The phone did not provide a valid capture time.');
    this.start = start; this.cursor = start; this.floor = nanos(start); this.edge = this.floor;
    this.seen = new Map(); this.rows = []; this.hidden = 0; this.bytes = 0; this.full = false; this.next = 1;
  }
  // Returns how many timestamped rows the poll held and how many were new.
  ingest(output) {
    let lines = 0, fresh = 0, added = 0;
    const counts = new Map();
    for (const line of String(output).split('\n')) {
      const row = ROW.exec(line);
      if (!row) continue;
      lines++;
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
      const message = NAVEE.exec(row[2]);
      const kept = message ? classify(message[1]) : null;
      if (kept === null) { this.hidden = Math.min(LIMITS.hidden, this.hidden + 1); continue; }
      if (this.add(row[1], kept, false)) added++;
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
  remove(id) {
    const i = this.rows.findIndex(row => row.id === id);
    if (i < 0) return;
    const [row] = this.rows.splice(i, 1);
    this.bytes -= row.time.length + row.text.length + 4;
  }
}
export function logcatCommand(uid, cursor, count) {
  if (!/^\d{5}$/.test(String(uid)) || !/^\d{9,11}\.\d{1,9}$/.test(cursor) || !Number.isInteger(count) || count < 1 || count > 1000)
    throw new Error('Invalid logcat request');
  // logcat refuses -m (--max-count) together with -t, so the batch is bounded by head instead.
  return `logcat --uid=${uid} -d -v epoch -t '${cursor}' -s com.navee.ucaret:V '*:S' | head -n ${count}`;
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
  if (!body.model || !body.description || body.description.length > LIMITS.description || body.contact === null ||
      body.navee_version === null || body.android === null || body.phone === null ||
      !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{3})?Z$/.test(startedAt) ||
      !Number.isInteger(durationS) || durationS < 0 || durationS > LIMITS.duration ||
      !Number.isInteger(hidden) || hidden < 0 || hidden > LIMITS.hidden)
    throw new SetupError('invalid', 'Some details are missing or too long. Check the description and contact fields.');
  if (body.rows.length < 1 || body.rows.length > LIMITS.rows || !rows.some(row => !row.note))
    throw new SetupError('invalid', 'Keep at least one NAVEE line to send.');
  if (!body.rows.every(row => row.length <= LIMITS.row && /^\d{2}:\d{2}:\d{2}\.\d{3} /.test(row) && safeText(row.slice(13))))
    throw new SetupError('invalid', 'A line could not be checked safely, so nothing was sent.');
  const json = JSON.stringify(body);
  if (new TextEncoder().encode(json).length > LIMITS.body) throw new SetupError('invalid', 'This capture is too large to send. Delete some lines and try again.');
  return json;
}
