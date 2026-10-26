import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { LIMITS, LogRecorder, MODELS, ROW_BUDGET, annotate, answerText, askable, classify, clock, decodeFrame, logcatCommand, noteText, recognize, safeSummary, safeText, submission, summaryText, tally } from '../src/navee-log.js';
import { COMMANDS, GROUPS, SENSITIVE, describe } from '../src/navee-commands.js';
import { frame as authFrame } from './fixtures.mjs';
import { START, bytes, frame, hex, line } from './navee-fixtures.mjs';

// The submit server's documented refusals. Every kept row must pass them.
function serverAccepts(row) {
  if (/[\u0000-\u001f\u007f]/.test(row) || /authData|encryptData/i.test(row)) return false;
  if (/cmd=0X(?:30|31|63|7A)\b/i.test(row)) return false;
  if (/\b[0-9a-f]{2}(?:[:-][0-9a-f]{2}){5}\b/i.test(row)) return false;
  if (/[^\s@]+@[^\s@]+\.[^\s@]+/.test(row)) return false;
  const compact = row.replace(/0x/gi, '').replace(/[^0-9a-f]/gi, ' ');
  for (const run of compact.split(/\s{3,}/)) {
    const digits = run.replace(/\s/g, '').toLowerCase();
    for (let i = digits.indexOf('55aa'); i >= 0; i = digits.indexOf('55aa', i + 1))
      if (['30', '31', '63', '7a'].includes(digits.slice(i + 6, i + 8))) return false;
  }
  return true;
}
const kept = message => { const out = classify(message); if (out !== null) { assert(safeText(out), out); assert(serverAccepts(out), out); } return out; };

test('every allowlisted NAVEE Bluetooth/device message shape is kept', () => {
  const cruise = frame(0x52, [1]);
  const cases = [
    [`Send write command: ${cruise}`, `Send write command: ${cruise}`],
    [`Send read command: ${hex(bytes(0x70, [], 0, true))}`, 'Send read command: [55 aa 00 70 6f fe fd]'],
    [`Write success: ${cruise}`, `Write success: ${cruise}`],
    ["Write failure: BleException { code=102, description='This device not connect!'}", "Write failure: BleException { code=102, description='This device not connect!'}"],
    ['Notify success', 'Notify success'],
    ['Notify failure: Gatt Exception Occurred! ', 'Notify failure: Gatt Exception Occurred!'],
    ['setMTUSuccess: mtu = 247', 'setMTUSuccess: mtu = 247'],
    ['SetMTUFailure: Timeout Exception Occurred!', 'SetMTUFailure: Timeout Exception Occurred!'],
    ['reconnect after no notify in 7s', 'reconnect after no notify in 7s'],
    ['sendCmd cmd=0X52, value=0X01', 'sendCmd cmd=0X52, value=0X01'],
    ['sendCmd cmd=0X6D, value=0X04,0X02,0X01', 'sendCmd cmd=0X6D, value=0X04,0X02,0X01'],
    ['sendAmbientCmd: on=0x01 e=0x03 d=0x05 s=0x02', 'sendAmbientCmd: on=0x01 e=0x03 d=0x05 s=0x02'],
    ['Connect success (C0FFEE123456)', 'Connect success ([scooter])'],
    ['Connect fail (UT5 MAX 0042)，error = BleException { code=101 }', 'Connect fail ([scooter]), error = BleException { code=101 }'],
    ['Connect start (null)', 'Connect start ([scooter])'],
    ['Connect device success (A1B2C3D4E5F6)', 'Connect device success ([scooter])'],
    ['Connect device fail (A1B2C3D4E5F6)，error = x', 'Connect device fail ([scooter]), error = x'],
    ['Connect mac start (C0:FF:EE:12:34:56)', 'Connect mac start ([scooter])'],
    ['DisConnected (C0FFEE123456)', 'DisConnected ([scooter])'],
    ['DisConnected mac (C0:FF:EE:12:34:56) isActive: (true)', 'DisConnected mac ([scooter]) isActive: (true)'],
    ['DisConnected device (C0FFEE123456)', 'DisConnected device ([scooter])'],
    ['DisConnected Blue Rocket', 'DisConnected [scooter]'],
    ['Create Bond (C0FFEE123456)', 'Create Bond ([scooter])'],
    ['Can`t create Bond with C0FFEE123456; Location permission denied', 'Can`t create Bond with [scooter]; Location permission denied'],
    ['refreshLockStatus 1', 'refreshLockStatus 1'],
    ['onScanStarted, b: true', 'onScanStarted, b: true'],
    ['onScanFinished, result count: 3', 'onScanFinished, result count: 3'],
    ['out date connection rejected: C0:FF:EE:12:34:56 (target=AA:BB:CC:DD:EE:FF)', 'out date connection rejected: [scooter] (target=[scooter])'],
    ['[Exo] reconect after passive disconnect', '[Exo] reconect after passive disconnect'],
    ['DG write ok: light', 'DG write ok: light'],
    ['DG write fail (light): Timeout', 'DG write fail (light): Timeout'],
    ['DG notify success', 'DG notify success'],
    ['DG notify fail: Gatt error', 'DG notify fail: Gatt error'],
    ['DG session -> AUTHENTICATED', 'DG session -> AUTHENTICATED'],
    ['DG connect success, mac=C0:FF:EE:12:34:56, status=0', 'DG connect success, mac=[MAC], status=0'],
    ['DG connect fail (C0:FF:EE:12:34:56), error=x', 'DG connect fail ([scooter]), error=x'],
    ['DG disconnected, mac=C0-FF-EE-12-34-56, active=true, status=8', 'DG disconnected, mac=[MAC], active=true, status=8'],
    ['DG onControlResult: 0', 'DG onControlResult: 0'],
    ['DG error code: E0012', 'DG error code: E0012']
  ];
  for (const [message, expected] of cases) {
    assert.equal(kept(message), expected, message);
    // The LogHelper "[thread] Class.method: " prefix is stripped; plain Log.d rows work too.
    assert.equal(kept(`[17] BleHandler$13.e: ${message}`), expected, message);
  }
});
test('network, account, payment, download, unknown and notify-description rows are hidden', () => {
  for (const message of ['网络请求 https://api.example/getUserInfo {"token":"secret"}', 'getUserInfo: {"nickname":"Rider"}',
    'getScooterInfo result {"sn":"N2546ZZ9US1234567"}', 'login success token=abc', 'pay order 12', 'download firmware 1.2.3',
    'upload log', 'Exo write light: [55 aa 00 52 01 01 a9 fe fd]', 'DG bind connected device C0:FF:EE:12:34:56', 'LD notify data: [01 02]',
    '读取车辆SN Data length: 18', 'Error: 0', `Update Data: ${hex([0x4e, 0x32, 0x35])}`, 'CheckSum: 3a', '首页信息上报 Data length: 15',
    '', 'Notify success extra', 'setMTUSuccess: mtu = 247 more', 'sendCmd cmd=0X52, value=1', 'Send write command: 55 aa 00 52 01 01 a9 fe fd',
    'refreshLockStatus one', 'DG session -> two words'])
    assert.equal(kept(message), null, message);
});
test('Exo and DG raw payload rows are hidden: they can carry a serial or authentication bytes', () => {
  // ExoProtocolHandler.v() logs type=0x70 data whose first 14 bytes F() decodes as the serial (synthetic X1234567890123).
  const serial = hex([...'X1234567890123'].map(c => c.charCodeAt(0)).concat([0, 1, 2]));
  for (const message of [`[Exo] type=0x70, data=${serial}, len=47`, '[Exo] connect success, mac=C0:FF:EE:12:34:56', '[Exo] tx [01 02 03]',
    `Exo notify raw: ${frame(0x40, [1, 2])}`, `Exo notify raw: ${serial}`, 'DG write auth: [a5 5a 10 01 02 03 04 05 06 07 08 09 0a 0b 0c 0d]',
    'DG write light: [aa 01 02]', 'DG notify raw: [aa 10 20]', `DG notify raw: ${serial}`, 'DG write ok: [01 02]', 'DG write fail (auth): [01 02]x'.replace('x', ''),
    'DG write ok: light extra'])
    assert.equal(kept(message), null, message);
  // Their fixed status rows stay.
  assert.equal(kept('DG write ok: auth'), 'DG write ok: auth');
  assert.equal(kept('DG write fail (auth): Timeout'), 'DG write fail (auth): Timeout');
});
test('only well-formed NAVEE frames are kept in frame rows', () => {
  const good = bytes(0x52, [1]);
  for (const broken of [[...good.slice(0, -3), (good.at(-3) + 1) & 255, 0xfe, 0xfd], bytes(0x52, [1]).map((x, i) => i === 4 ? 2 : x),
    [0x55, 0xab, ...good.slice(2)], good.slice(0, -1), [0x01, 0x02]])
    for (const kind of ['Send write command', 'Write success', 'Send read command'])
      assert.equal(kept(`${kind}: ${hex(broken)}`), null, `${kind} ${broken}`);
});
test('owner auth, encrypted data, PIN write and PIN read frames are dropped in every hex style', () => {
  assert.equal(kept(`Send authData: ${authFrame()}`), null);
  assert.equal(kept(`Send authData: [${authFrame()}]`), null);
  assert.equal(kept(`Send encryptData: ${frame(0x31, [1, 2, 3])}`), null);
  for (const cmd of SENSITIVE) {
    const payload = cmd === 0x63 ? [1, 1, 1, 2, 3, 4] : cmd === 0x7a ? [] : [1, 2, 3];
    for (const kind of ['Send write command', 'Write success', 'Send read command'])
      assert.equal(kept(`${kind}: ${hex(bytes(cmd, payload, 0, kind === 'Send read command' && !payload.length))}`), null, `${kind} ${cmd}`);
    for (const style of ['navee', 'plain', 'colon', 'dash', 'comma', 'none', 'upper', '0x', '0X']) {
      const shown = frame(cmd, payload, style);
      for (const message of [`Exo notify raw: ${shown}`, `DG notify raw: ${shown}`, `[Exo] tx ${shown}`, `Write failure: ${shown}`, `DG write pin: ${shown}`])
        assert.equal(kept(message), null, `${message}`);
    }
    for (const flag of [0, 1]) assert.equal(kept(`Write success: ${hex(bytes(cmd, payload, flag))}`), null);
    const command = cmd.toString(16).toUpperCase().padStart(2, '0');
    assert.equal(kept(`sendCmd cmd=0X${command}, value=0X01,0X01,0X01,0X02,0X03,0X04`), null);
    assert.equal(kept(`sendCmd cmd=0X${command}, value=0X00`), null);
  }
  for (const message of ['[Exo] authData ok', 'DG write auth: encryptData', '[Exo] AUTHDATA', 'Write failure: cmd=0x63 rejected'])
    assert.equal(kept(message), null, message);
  // Non-sensitive frames survive, including payload bytes that look like 0x30.
  assert.equal(kept(`Send write command: ${frame(0x52, [0x30])}`), `Send write command: ${frame(0x52, [0x30])}`);
});
test('MACs, scooter identifiers and serials are redacted; emails and phone numbers drop the row', () => {
  assert.equal(kept('Write failure: link C0:FF:EE:12:34:56 and c0-ff-ee-12-34-57 via C0FFEE123458'), 'Write failure: link [MAC] and [MAC] via [MAC]');
  assert.equal(kept('Write failure: serial N2546ZZ9US1234567 ok'), 'Write failure: serial [serial] ok');
  assert.equal(kept('Write failure: sn=X2538SYNTHETIC0042, other n2546zz9us7654321'), 'Write failure: sn=[serial], other [serial]');
  assert.equal(kept('Connect success (N2546ZZ9US1234567)'), 'Connect success ([scooter])');
  // Long words without digits are not serials.
  assert.equal(kept('Write failure: onCharacteristicChanged notification'), 'Write failure: onCharacteristicChanged notification');
  for (const message of ['Write failure: owner rider@example.com', 'Write failure: mail a.b+c@mail.example.org', 'Write failure: call +1 (555) 123-4567',
    'DG write fail (x): 5551234567', 'Write failure: 555.123.4567', 'Notify failure: 0044 20 7946 0958'])
    assert.equal(kept(message), null, message);
  // Hex bytes that happen to be decimal digits are not phone numbers.
  const clock = frame(0x6f, [6, 0x12, 0x34, 0x56, 0x78]);
  assert.equal(kept(`Send write command: ${clock}`), `Send write command: ${clock}`);
  assert.equal(kept(`Write failure: ${frame(0x52, [0x12, 0x34, 0x56, 0x78, 0x90])}`), `Write failure: ${frame(0x52, [0x12, 0x34, 0x56, 0x78, 0x90])}`);
  // Bytes that aren't a NAVEE frame are opaque, so the row is hidden.
  for (const message of ['Write failure: [12 34 56 78 90 12 34 56 78]', 'Notify failure: data=[58 31 32 33]', 'Write failure: [55 aa 00 52 01 01 00 fe fd]'])
    assert.equal(kept(message), null, message);
});
test('rows are bounded printable ASCII and oversize input is hidden', () => {
  const long = kept(`Write failure: ${'x'.repeat(2000)}`);
  assert.equal(long.length, LIMITS.text); assert(long.endsWith('...'));
  assert.equal(kept(`Write failure: ${'x'.repeat(9000)}`), null);
  assert.equal(kept('Write failure: 状态 ok\tnext'), 'Write failure: ?? ok next');
  assert.equal(kept('Write failure: bell\u0007 here'), 'Write failure: bell? here');
  assert.equal(classify(undefined), null);
});
test('frames decode and annotate in plain English where Volt knows the command', () => {
  assert.deepEqual(decodeFrame(bytes(0x52, [1])), { flag: 0, cmd: 0x52, read: false, payload: [1] });
  assert.deepEqual(decodeFrame(bytes(0x70, [], 0, true)), { flag: 0, cmd: 0x70, read: true, payload: [] });
  assert.equal(decodeFrame(bytes(0x52, [1]).slice(1)), null);
  assert.equal(annotate(`Send write command: ${frame(0x52, [1])}`), 'Cruise control → On');
  assert.equal(annotate(`Write success: ${frame(0x6d, [4, 2, 1])}`), 'Line lights color → Red');
  assert.equal(annotate(`Write success: ${frame(0x6d, [1, 2])}`), 'Ambient light → Follow headlight');
  assert.equal(annotate(`Send write command: ${frame(0x6f, [4, 90])}`), 'Charge ceiling → 90%');
  assert.equal(annotate(`Send write command: ${frame(0x5e, [1, 3, 5, 2])}`), '0x5e = 1, 3, 5, 2');
  assert.equal(annotate(`Send read command: ${hex(bytes(0x70, [], 0, true))}`), 'Read vehicle status');
  assert.equal(annotate(`Send read command: ${hex(bytes(0x73, [], 0, true))} (ok)`), 'Read firmware version');
  assert.equal(annotate('sendCmd cmd=0X57, value=0X01'), 'Automatic lights → On');
  assert.equal(annotate('Notify success'), '');
  // A failure row or a note quoting a frame is not a command row.
  assert.equal(annotate(`Write failure: ${frame(0x52, [1])}`), '');
  assert.equal(recognize(`[note] ${frame(0x52, [1])}`), null);
});
// Real UT5 MAX write sequence (values as captured; clock bytes synthetic): every one is known.
const UT5 = [
  [0x51, [1], 'Electronic lock → On'], [0x51, [0], 'Electronic lock → Off'], [0x53, [60], 'Energy recovery → Medium'], [0x53, [90], 'Energy recovery → High'],
  [0x55, [1], 'Dashboard units → Kilometers / km/h'], [0x55, [0], 'Dashboard units → Miles / mph'], [0x52, [1], 'Cruise control → On'], [0x52, [0], 'Cruise control → Off'],
  [0x54, [1], 'Tail light → On'], [0x57, [0], 'Automatic lights → Off'], [0x57, [1], 'Automatic lights → On'], [0x6f, [5, 1], 'Low Battery Mode → On'],
  [0x6f, [5, 0], 'Low Battery Mode → Off'], [0x6f, [4, 95], 'Charge ceiling → 95%'], [0x6f, [4, 80], 'Charge ceiling → 80%'], [0x6f, [4, 100], 'Charge ceiling → 100%'],
  [0x6a, [3], 'Kick-start speed → 3 km/h'], [0x6a, [0], 'Kick-start speed → 0 km/h'], [0x5f, [1], 'Traction control → On'], [0x5f, [0], 'Traction control → Off'],
  [0x81, [1, 1], 'Walk Assist → On'], [0x81, [1, 0], 'Walk Assist → Off'], [0x81, [3, 1], 'Hill descent control → On'], [0x81, [3, 0], 'Hill descent control → Off'],
  [0x6f, [1, 1], 'Scheduled charging → On'], [0x6f, [1, 0], 'Scheduled charging → Off'],
  // 2027-01-15 02:00 and 14:05:30 local wall clock, as WireCommands.localTime encodes them.
  [0x6f, [3, 0x6b, 0x49, 0x7d, 0xa0], 'Charging start time → 02:00'], [0x6f, [6, 0x6b, 0x4a, 0x27, 0xaa], 'Clock sync → 14:05']
];
test('a real UT5 MAX capture classifies every write as known, with the right reading', () => {
  const r = new LogRecorder(START), lines = [];
  let ms = 1000;
  const at = () => `1800000${String(Math.floor(ms / 1000)).padStart(3, '0')}.${String(ms++ % 1000).padStart(3, '0')}`;
  for (const cmd of [0x70, 0x73, 0x74, 0x7b]) { const read = hex(bytes(cmd, [], 0, true)); lines.push(line(`Send read command: ${read}`, at()), line(`Write success: ${read}`, at())); }
  for (const [cmd, payload] of UT5) {
    lines.push(line(`Send write command: ${frame(cmd, payload)}`, at()), line(`Write success: ${frame(cmd, payload)}`, at()),
      line(`sendCmd cmd=0X${cmd.toString(16).toUpperCase()}, value=${payload.map(b => `0X${b.toString(16).toUpperCase().padStart(2, '0')}`).join(',')}`, at()));
    ms += 900;
  }
  r.ingest(lines.join('\n'));
  const infos = r.rows.map(row => recognize(row.text));
  assert.deepEqual(infos.slice(0, 4).map(info => [info.status, info.reading]), [['read', 'Read vehicle status'], ['read', 'Read firmware version'], ['read', 'Read serial number'], ['read', 'Read fault codes']]);
  assert.deepEqual(infos.slice(4).map(info => [info.status, info.reading]), UT5.map(([, , reading]) => ['known', reading]));
  assert.deepEqual(r.rows.slice(4).map(row => row.annotation), UT5.map(([, , reading]) => reading));
  assert.equal(infos.at(-1).routine, true);
  assert(!infos.some(askable));
  const t = tally(r.rows);
  assert.deepEqual([t.known, t.reads, t.news, t.values], [UT5.length, 4, [], []]);
  // Nothing new: no summary, and the body is exactly today's format.
  assert.equal(summaryText(r.rows), '');
  assert(!('summary' in JSON.parse(submission({ ...base(r), rows: r.rows }))));
});
test('unknown commands are new, unknown values of known commands are new values', () => {
  const info = (cmd, payload, read = false) => describe(cmd, payload, read);
  assert.deepEqual(['status', 'key', 'reading', 'sig'].map(k => info(0xa4, [1])[k]), ['new', 'a4', '0xa4 = 1', 'a4=1']);
  assert.deepEqual(['status', 'key', 'reading'].map(k => info(0x6d, [4, 7, 1])[k]), ['new', '6d:04:07', '0x6d:04:07 = 1']);
  assert.deepEqual(['status', 'key', 'reading'].map(k => info(0x6d, [9, 1])[k]), ['new', '6d:09', '0x6d:09 = 1']);
  assert.deepEqual(['status', 'key', 'reading'].map(k => info(0x6f, [8, 1])[k]), ['new', '6f:08', '0x6f:08 = 1']);
  assert.deepEqual(['status', 'name', 'reading', 'sig'].map(k => info(0x53, [45])[k]), ['new-value', 'Energy recovery', 'Energy recovery = 45', '53=45']);
  for (const [cmd, payload, reading] of [[0x52, [2], 'Cruise control = 2'], [0x52, [1, 1], 'Cruise control = 1, 1'], [0x6f, [4, 77], 'Charge ceiling = 77'],
    [0x6d, [4, 2, 3], 'Line lights color = 3'], [0x6a, [6], 'Kick-start speed = 6'], [0x6b, [0x85], 'Custom speed cap = 133'], [0x6b, [20], 'Custom speed cap = 20'],
    [0x6f, [3, 0, 0, 0, 0], 'Charging start time = 0, 0, 0, 0'], [0x6f, [3, 1, 2], 'Charging start time = 1, 2'], [0x6c, [0x80 | 50, 1], 'Prompt sound = 178, 1']])
    assert.deepEqual([info(cmd, payload).status, info(cmd, payload).reading], ['new-value', reading], reading);
  // Speed caps: 0 is Off, enabled values carry bit 7 and Volt's lower bound.
  assert.equal(info(0x6b, [0]).reading, 'Custom speed cap → Off'); assert.equal(info(0x6b, [0x80 | 25]).reading, 'Custom speed cap → 25 km/h');
  assert.equal(info(0x6e, [3, 0x80 | 5]).reading, 'Drive mode speed cap → 5 km/h'); assert.equal(info(0x6e, [3, 0x80 | 4]).status, 'new-value');
  assert.equal(info(0x6e, [1, 40]).reading, 'Maximum speed → 40 km/h'); assert.equal(info(0x6e, [2, 1]).reading, 'Dual motor drive → Dual motor');
  // Reads are routine; only an unknown read is new, and it is never asked about.
  assert.deepEqual([info(0x7b, [], true).status, info(0x7c, [], true).status, info(0x7c, [], true).reading, askable(info(0x7c, [], true))], ['read', 'new', 'Read 0x7c', false]);
  assert.deepEqual([info(0x52, [], true).status, info(0x52, [], true).reading], ['new', 'Read 0x52']);
  // NAVEE's own clock sync is known when well formed and never asked about.
  assert.equal(askable(info(0x6f, [6, 0, 0, 0, 0])), false);
  assert.equal(askable(info(0xa4, [1])), true); assert.equal(askable(info(0x53, [45])), true); assert.equal(askable(info(0x52, [1])), false);
  assert.equal(recognize('sendCmd cmd=0XA4, value=0X01').status, 'new');
});
// Parses ScooterCommands' static catalogue by expanding its helper methods (toggle, choice,
// lightCatalog, oemOnlyLight) into the add()/Feature argument list they all share.
function javaFeatures(source) {
  // Comments out, strings kept.
  const java = source.replace(/"(?:[^"\\\n]|\\.)*"|\/\/[^\n]*|\/\*[\s\S]*?\*\//g, m => m[0] === '"' ? m : ' ');
  const close = { '(': ')', '{': '}', '[': ']' };
  const split = (text, sep) => {
    const parts = []; let depth = 0, quote = false, last = 0;
    for (let i = 0; i < text.length; i++) {
      const c = text[i];
      if (quote) { if (c === '\\') i++; else if (c === '"') quote = false; continue; }
      if (c === '"') quote = true; else if ('({['.includes(c)) depth++; else if (')}]'.includes(c)) depth--;
      else if (c === sep && depth === 0) { parts.push(text.slice(last, i).trim()); last = i + 1; }
    }
    return [...parts, text.slice(last).trim()];
  };
  const group = (text, at) => { // text[at] opens a bracket; returns the inside
    let depth = 0, quote = false;
    for (let i = at; i < text.length; i++) {
      const c = text[i];
      if (quote) { if (c === '\\') i++; else if (c === '"') quote = false; continue; }
      if (c === '"') quote = true; else if (close[c]) depth++; else if (')}]'.includes(c) && --depth === 0) return text.slice(at + 1, i);
    }
    throw new Error('unbalanced Java');
  };
  const calls = (text, name) => [...text.matchAll(new RegExp(`(?<![\\w.])${name.replace(' ', '\\s+')}\\s*\\(`, 'g'))].map(m => split(group(text, m.index + m[0].length - 1), ','));
  const value = (expr, env) => {
    let m;
    if ((m = /^"((?:[^"\\]|\\.)*)"$/.exec(expr))) return m[1];
    if (/^0x[0-9a-f]+$/i.test(expr)) return parseInt(expr, 16);
    if (/^-?\d+$/.test(expr)) return Number(expr);
    if ((m = /^new (?:int|String)\[\]\s*\{([\s\S]*)\}$/.exec(expr))) return m[1].trim() ? split(m[1], ',').map(x => value(x, env)) : [];
    if (expr === 'NO_OPTIONS' || expr === 'NO_VALUES') return [];
    if ((m = /^Type\.(\w+)$/.exec(expr))) return m[1].toLowerCase();
    const sum = split(expr, '+');
    if (sum.length > 1) { const parts = sum.map(x => value(x, env)); return parts.every(x => typeof x === 'string') ? parts.join('') : undefined; }
    return /^\w+$/.test(expr) ? env[expr] : undefined;
  };
  // A helper's parameter names and the add()/new Feature() calls in its body.
  const helper = name => {
    const at = java.search(new RegExp(`private static void ${name}\\s*\\(`));
    const params = split(group(java, java.indexOf('(', at)), ',').map(p => p.split(/\s+/).pop());
    const body = group(java, java.indexOf('{', at));
    return { params, inner: [...calls(body, 'add'), ...calls(body, 'new Feature')] };
  };
  const helpers = Object.fromEntries(['toggle', 'choice', 'lightCatalog', 'oemOnlyLight'].map(name => [name, helper(name)]));
  const block = group(java, java.indexOf('{', java.search(/\bstatic\s*\{/)));
  const feature = args => { const [key, label, group, , type, unit, cmd, min, max, , options, values, prefix] = args; return { key, label, group, type, unit, cmd, min, max, options, values, prefix }; };
  const out = [];
  for (const statement of split(block, ';')) {
    const m = /^(\w+)\s*\(/.exec(statement);
    if (!m) continue;
    const args = split(group(statement, statement.indexOf('(')), ',').map(x => value(x, {}));
    if (m[1] === 'add') { out.push(feature(args)); continue; }
    const { params, inner } = helpers[m[1]];
    const env = Object.fromEntries(params.map((p, i) => [p, args[i]]));
    for (const call of inner) out.push(feature(call.map(x => value(x, env))));
  }
  return out;
}
test('the command database matches ScooterCommands.java and Protocol.java exactly; models match ModelRegistry KNOWN', async () => {
  const java = await readFile(new URL('../../../src/com/jjricks/volt/ScooterCommands.java', import.meta.url), 'utf8');
  const protocol = await readFile(new URL('../../../src/com/jjricks/volt/Protocol.java', import.meta.url), 'utf8');
  assert.match(java, /sensitiveCommand\(int cmd\) \{ return cmd==0x30 \|\| cmd==0x31 \|\| cmd==0x63 \|\| cmd==0x7a; \}/);
  const features = javaFeatures(java), keyOf = f => [f.cmd, ...f.prefix].map(n => n.toString(16).padStart(2, '0')).join(':');
  assert.equal(features.length, new Set(features.map(f => f.key)).size); assert(features.length >= 39, `parsed ${features.length} Java features`);
  const covered = new Set();
  for (const f of features) {
    assert.equal(typeof f.cmd, 'number', f.key); assert(Array.isArray(f.prefix), f.key);
    if (f.type === 'pin') { assert(SENSITIVE.has(f.cmd)); continue; } // PIN frames are never kept
    const key = keyOf(f), entry = COMMANDS[key];
    assert(entry, `${f.key} → ${key} is missing`); covered.add(key);
    assert.deepEqual([entry.name, entry.group, entry.kind], [f.label, f.group, f.type], key);
    if (f.type === 'toggle' || f.type === 'choice') {
      assert.equal(f.options.length, f.values.length, key);
      assert.deepEqual(entry.values, Object.fromEntries(f.values.map((v, i) => [v, f.options[i]])), key);
    }
    if (f.type === 'slider') {
      assert.deepEqual([entry.min, entry.max, entry.unit], [f.min, f.max, f.unit], key);
      // Speed caps: Java refuses enabled values below the cap and sends 0 for Off.
      const floor = new RegExp(`key\\.equals\\("${f.key}"\\) && value>0 && value<(\\d+)\\) return false`).exec(java);
      assert.equal(entry.cap, floor ? Number(floor[1]) : undefined, key);
    }
  }
  // Besides Java's controls, only the reads Protocol names (minus the PIN read) and NAVEE's clock sync.
  const reads = [...protocol.matchAll(/public static final int (READ_\w+) = 0x([0-9a-f]{2});/g)].filter(([, name]) => name !== 'READ_PIN');
  assert(reads.length >= 9);
  for (const [, name, cmd] of reads) {
    const entry = COMMANDS[cmd];
    assert.equal(entry?.kind, 'read', name); covered.add(cmd);
    for (const word of name.slice(5).toLowerCase().split('_')) assert(entry.name.toLowerCase().includes(word.replace(/s$/, '')), `${name} vs ${entry.name}`);
  }
  assert.deepEqual(/int\[\] READS = \{([^}]*)\}/.exec(java)[1].split(',').map(x => x.trim()).sort(), reads.map(([, , cmd]) => `0x${cmd}`).sort());
  assert.match(java, /setClock[\s\S]*?localTime\(6,/); assert.deepEqual([COMMANDS['6f:06'].kind, COMMANDS['6f:06'].routine], ['time', true]); covered.add('6f:06');
  assert.deepEqual(Object.keys(COMMANDS).filter(key => !covered.has(key)), []);
  assert(!Object.keys(COMMANDS).some(key => SENSITIVE.has(parseInt(key, 16))));
  assert.deepEqual([...new Set(Object.values(COMMANDS).map(e => e.group))].sort(), [...GROUPS].sort());
  const registry = await readFile(new URL('../../../src/com/jjricks/volt/ModelRegistry.java', import.meta.url), 'utf8');
  const known = [...registry.matchAll(/KNOWN\.put\("\d+",new Known\("([^"]+)"/g)].map(match => match[1]);
  assert.deepEqual(MODELS.slice(1), known);
  assert.match(java, /UT5 MAX \(PID 2538\)/);
});
test('recorder keeps only fresh filtered rows, dedupes across polls and counts hidden rows once', () => {
  const r = new LogRecorder(START);
  const poll1 = [line('Notify success', '1799999999.999'), '--------- beginning of main', line(`Send write command: ${frame(0x52, [1])}`, '1800000001.000'),
    line('getUserInfo {"a":1}', '1800000001.500'), line('Notify success', '1800000002.000'), line('Notify success', '1800000002.000')].join('\n');
  assert.deepEqual(r.ingest(poll1), { lines: 5, fresh: 4, added: 3 });
  assert.equal(r.hidden, 1); assert.equal(r.cursor, '1800000002.000');
  assert.deepEqual(r.rows.map(x => [x.time, x.text]), [['08:00:01.000', `Send write command: ${frame(0x52, [1])}`], ['08:00:02.000', 'Notify success'], ['08:00:02.000', 'Notify success']]);
  assert.equal(r.rows[0].annotation, 'Cruise control → On');
  // The next poll starts at the boundary timestamp and repeats those rows.
  const poll2 = [line('Notify success', '1800000002.000'), line('Notify success', '1800000002.000'), line('Notify success', '1800000002.000'),
    line('getScooterInfo', '1800000002.000'), line('setMTUSuccess: mtu = 247', '1800000003.250')].join('\n');
  assert.deepEqual(r.ingest(poll2), { lines: 5, fresh: 3, added: 2 });
  assert.equal(r.rows.length, 5); assert.equal(r.hidden, 2); assert.equal(r.cursor, '1800000003.250');
  assert.deepEqual(r.ingest(poll2), { lines: 5, fresh: 0, added: 0 });
  // Malformed and foreign-tag rows count as hidden; text is never retained.
  r.ingest(`1800000004.000 1 1 D other.tag: Notify success\n1800000004.001 garbage`);
  assert.equal(r.hidden, 4);
  assert(!JSON.stringify([...r.seen.keys()].map(String)).includes('garbage'));
  r.remove(r.rows[0].id); assert.equal(r.rows.length, 4);
  assert.throws(() => new LogRecorder('soon'), { code: 'clock' });
});
test('recorder stops at 3000 rows or its byte budget', () => {
  const r = new LogRecorder(START);
  const many = Array.from({ length: 3005 }, (_, i) => line('Notify success', `1800000${String(100 + Math.floor(i / 1000)).padStart(3, '0')}.${String(i % 1000).padStart(3, '0')}`)).join('\n');
  r.ingest(many);
  assert.equal(r.rows.length, LIMITS.rows); assert.equal(r.full, true);
  const big = new LogRecorder(START);
  big.ingest(Array.from({ length: 600 }, (_, i) => line(`Write failure: ${'y'.repeat(500)}`, `1800000100.${String(i).padStart(3, '0')}`)).join('\n'));
  assert.equal(big.full, true); assert(big.bytes <= ROW_BUDGET); assert(big.rows.length < 600);
  assert.throws(() => big.addNote('1800000200.000', 'late'), { code: 'full' });
});
test('notes are labelled local text, ordered by time and held to the same rules', () => {
  const r = new LogRecorder(START);
  r.ingest(line('Notify success', '1800000005.000'));
  const note = r.addNote('1800000003.000', '  now switching to   rainbow mode ');
  assert.equal(note.text, '[note] now switching to rainbow mode'); assert.equal(note.note, true); assert.equal(note.annotation, '');
  assert.equal(r.rows[0].id, note.id);
  assert.equal(noteText('mac C0:FF:EE:12:34:56 sn N2546ZZ9US1234567'), '[note] mac [MAC] sn [serial]');
  for (const bad of ['', '   ', 'x'.repeat(201), 'mail me@example.com', 'call 555 123 4567', 'authData', `frame ${frame(0x63, [1, 1, 1, 2, 3, 4], 'plain')}`])
    assert.throws(() => noteText(bad), { code: 'note' }, bad);
  assert.equal(noteText('café'), '[note] caf?');
});
test('clock and logcat command are strict', () => {
  assert.equal(clock('1800000000.1'), '08:00:00.100'); assert.equal(clock('1800000000.123456789'), '08:00:00.123');
  assert.equal(logcatCommand(10123, '1800000000.125', 100), "logcat --uid=10123 -d -v epoch -t '1800000000.125' -s com.navee.ucaret:V '*:S' | head -n 100");
  for (const args of [['10123;x', '1800000000.125', 1], [10123, "1'; reboot", 1], [10123, '1800000000.125', 0], [10123, '1800000000.125', 1001], [1, '1800000000.125', 1]])
    assert.throws(() => logcatCommand(...args));
});
function recorded() {
  const r = new LogRecorder(START);
  r.ingest([line(`Send write command: ${frame(0x52, [1])}`, '1800000001.000'), line('getUserInfo', '1800000001.100')].join('\n'));
  r.addNote('1800000001.050', 'cruise on');
  return r;
}
const base = r => ({ model: 'UT5 Max', description: 'Lights only change in NAVEE.\nI cycle modes.', contact: 'u/rider', naveeVersion: '2.1.9',
  android: '16', phone: 'Pixel 9', startedAt: '2027-01-15T08:00:00.000Z', durationS: 61, hidden: r.hidden, rows: r.rows });
test('submission is the exact server format and refuses anything out of bounds', () => {
  const r = recorded(), body = submission(base(r));
  assert.deepEqual(Object.keys(JSON.parse(body)), ['format', 'model', 'description', 'contact', 'navee_version', 'android', 'phone', 'started_at', 'duration_s', 'hidden_rows', 'rows']);
  assert.deepEqual(JSON.parse(body), { format: 'volt-navee-capture-v1', model: 'UT5 Max', description: 'Lights only change in NAVEE.\nI cycle modes.', contact: 'u/rider',
    navee_version: '2.1.9', android: '16', phone: 'Pixel 9', started_at: '2027-01-15T08:00:00.000Z', duration_s: 61, hidden_rows: 1,
    rows: [`08:00:01.000 Send write command: ${frame(0x52, [1])}`, '08:00:01.050 [note] cruise on'] });
  for (const change of [{ description: ' ' }, { description: 'x'.repeat(4001) }, { contact: 'x'.repeat(241) }, { model: 'm'.repeat(121) },
    { naveeVersion: 'v'.repeat(41) }, { android: 'a'.repeat(41) }, { phone: 'p'.repeat(81) }, { startedAt: 'yesterday' }, { durationS: 3601 },
    { durationS: 1.5 }, { hidden: -1 }, { hidden: 1_000_001 }, { rows: [] }, { rows: r.rows.filter(x => x.note) },
    { rows: [...r.rows, { time: '08:00:02.000', text: 'Write success: [55 aa 00 63 06 01 01 01 02 03 04 6f fe fd]' }] },
    { rows: [...r.rows, { time: '08:00:02.000', text: 'peer C0:FF:EE:12:34:56' }] },
    { rows: [...r.rows, { time: '08:00:02.000', text: 'x'.repeat(600) }] }])
    assert.throws(() => submission({ ...base(r), ...change }), { code: 'invalid' }, Object.keys(change).join());
  // The whole body stays under 256 KB even with the longest description and contact.
  const full = new LogRecorder(START);
  full.ingest(Array.from({ length: 3000 }, (_, i) => line(`Write failure: ${'z'.repeat(300)}`, `1800000100.${String(i).padStart(3, '0')}`)).join('\n'));
  const longest = submission({ ...base(full), description: '“'.repeat(4000), contact: '"'.repeat(240), hidden: full.hidden, rows: full.rows });
  assert(new TextEncoder().encode(longest).length <= LIMITS.body);
});
test('fuzzed garbage around allowlisted prefixes never yields an unsafe kept row', () => {
  let seed = 7;
  const random = n => { seed = (Math.imul(seed, 1103515245) + 12345) >>> 1; return seed % n; };
  const pieces = ['Send write command: ', 'Write success: ', '[Exo] ', 'DG write ', 'Write failure: ', 'Connect success (', 'DisConnected ', 'Can`t create Bond with ',
    '55 aa 00 30 ', '55:aa:01:63:', '0x55,0xaa,0x00,0x7a', 'C0:FF:EE:12:34:56', 'A1B2C3D4E5F6', 'N2546ZZ9US1234567', 'me@x.io', '+1 555 123 4567', 'authData', 'encryptData',
    'cmd=0X31', '[55 aa 00 52 01 01 a9 fe fd]', ')', '(', ';', ' ', '\t', '\u0000', '状态', '，', 'x'.repeat(40), '0123456789'];
  let keptCount = 0;
  for (let i = 0; i < 4000; i++) {
    let message = pieces[random(8)];
    for (let j = random(6); j >= 0; j--) message += pieces[random(pieces.length)];
    if (kept(message) !== null) keptCount++;
  }
  assert(keptCount > 100, `fuzz exercised kept rows: ${keptCount}`);
});
test('write confirmations fold into their command and repeated routine reads collapse', () => {
  const r = new LogRecorder(START);
  const cruise = frame(0x52, [1]), status = '[55 aa 00 70 6f fe fd]', units = frame(0x55, [1]);
  r.ingest([line(`Send write command: ${cruise}`, '1800000001.000'), line(`Send read command: ${status}`, '1800000001.010'),
    line(`Write success: ${cruise}`, '1800000001.020'), line(`Write success: ${status}`, '1800000001.030'),
    line(`Send read command: ${status}`, '1800000002.000'), line(`Write success: ${status}`, '1800000002.010'),
    line(`Send read command: ${status}`, '1800000003.000'), line(`Write success: ${status}`, '1800000003.010'),
    line(`Send write command: ${units}`, '1800000004.000'), line(`Write success: ${frame(0x55, [0])}`, '1800000004.100')].join('\n'));
  assert.deepEqual(r.rows.map(x => x.text), [`Send write command: ${cruise} (ok)`, `Send read command: ${status} (ok)`,
    `Send write command: ${units}`, `Write success: ${frame(0x55, [0])}`]);
  assert.equal(r.folded, 6); assert.equal(r.hidden, 0);
  // A confirmed row is still safe to send and the body still matches the server format.
  assert(r.rows.every(x => safeText(x.text)));
  assert.equal(JSON.parse(submission({ ...base(r), rows: r.rows })).rows[0].endsWith('(ok)'), true);
  // NAVEE's sendCmd restatement of the frame just logged folds; one that doesn't match stays.
  const s = new LogRecorder(START);
  s.ingest([line(`Send write command: ${frame(0x6f, [5, 1])}`, '1800000005.000'), line('sendCmd cmd=0X6F, value=0X05,0X01', '1800000005.001'),
    line('sendCmd cmd=0X6F, value=0X05,0X00', '1800000005.002'), line('sendCmd cmd=0X54, value=0X01', '1800000005.003')].join('\n'));
  assert.deepEqual(s.rows.map(x => x.text), [`Send write command: ${frame(0x6f, [5, 1])}`, 'sendCmd cmd=0X6F, value=0X05,0X00', 'sendCmd cmd=0X54, value=0X01']);
  // Codex: a failed read and its identical retry. The retry is the one confirmed; the failed attempt stays.
  const retry = new LogRecorder(START);
  for (const [t, text] of [['1800000001.000', `Send read command: ${status}`], ['1800000002.000', 'Write failure: Timeout'],
      ['1800000010.000', `Send read command: ${status}`], ['1800000010.010', `Write success: ${status}`]])
    retry.ingest(line(text, t));
  assert.deepEqual(retry.rows.map(x => x.text), [`Send read command: ${status}`, 'Write failure: Timeout', `Send read command: ${status} (ok)`]);
  assert.deepEqual(retry.rows.map(x => x.time.slice(6)), ['01.000', '02.000', '10.000']);
  // A confirmed read, then an identical read that fails: the failed repeat stays, unconfirmed.
  const failed = new LogRecorder(START);
  failed.ingest([line(`Send read command: ${status}`, '1800000001.000'), line(`Write success: ${status}`, '1800000001.010'),
    line(`Send read command: ${status}`, '1800000002.000'), line('Write failure: Timeout', '1800000002.500'),
    line(`Write success: ${status}`, '1800000003.000')].join('\n'));
  assert.deepEqual(failed.rows.map(x => x.text), [`Send read command: ${status} (ok)`, `Send read command: ${status}`, 'Write failure: Timeout', `Write success: ${status}`]);
  // A confirmation after a reconnect is never credited to a command from before it.
  const reconnect = new LogRecorder(START);
  reconnect.ingest([line(`Send write command: ${cruise}`, '1800000001.000'), line('DisConnected ([scooter])', '1800000001.500'),
    line(`Write success: ${cruise}`, '1800000002.000')].join('\n'));
  assert.deepEqual(reconnect.rows.map(x => x.text), [`Send write command: ${cruise}`, 'DisConnected ([scooter])', `Write success: ${cruise}`]);
  // Confirmation replaces the row object so the live list re-renders it.
  assert.equal(new Set(r.rows.map(x => x.id)).size, r.rows.length);
});
function unknowns() {
  const r = new LogRecorder(START);
  r.ingest([line(`Send write command: ${frame(0xa4, [1])}`, '1800000001.000'), line(`Write success: ${frame(0xa4, [1])}`, '1800000001.010'),
    line(`Send write command: ${frame(0x52, [1])}`, '1800000002.000'), line(`Send write command: ${frame(0xa4, [1])}`, '1800000003.000'),
    line(`Send write command: ${frame(0x6d, [4, 7, 1])}`, '1800000004.000'), line(`Send write command: ${frame(0x53, [45])}`, '1800000005.000'),
    line(`Send write command: ${frame(0xa4, [2])}`, '1800000006.000'), line('Send read command: [55 aa 00 70 6f fe fd]', '1800000007.000'),
    line(`Send write command: ${frame(0x6f, [6, 0x6b, 0x4a, 0x27, 0xaa])}`, '1800000008.000')].join('\n'));
  return r;
}
test('answers are labelled, follow the row they explain and pass the note rules', () => {
  const r = unknowns();
  assert.equal(answerText('0xa4 = 1', '  rainbow   mode '), '[you said] 0xa4 = 1: rainbow mode');
  assert.equal(answerText('Energy recovery = 45', 'peer C0:FF:EE:12:34:56'), '[you said] Energy recovery = 45: peer [MAC]');
  for (const bad of ['', 'x'.repeat(201), 'mail me@example.com', 'call 555 123 4567', 'authData', '[12 34 56]'])
    assert.throws(() => answerText('0xa4 = 1', bad), { code: 'note' }, bad);
  assert.throws(() => answerText('[55 aa]', 'x'), { code: 'note' });
  const first = r.addAnswer('a4=1', 'rainbow mode'), second = r.addAnswer('a4=1', 'or maybe strobe');
  const texts = r.rows.map(row => row.text);
  assert.deepEqual(texts.slice(0, 3), [`Send write command: ${frame(0xa4, [1])} (ok)`, '[you said] 0xa4 = 1: rainbow mode', '[you said] 0xa4 = 1: or maybe strobe']);
  assert.deepEqual([first.note, first.sig, first.said, first.time, second.said], [true, 'a4=1', 'rainbow mode', '08:00:01.000', 'or maybe strobe']);
  assert(r.rows.every(row => safeText(row.text)));
  // Notes typed later still sort by time; answers keep their place.
  r.addNote('1800000001.500', 'next');
  assert.deepEqual(r.rows.slice(3, 5).map(row => row.text), ['[note] next', `Send write command: ${frame(0x52, [1])}`]);
  // Only unknown commands/values present in the capture can be answered.
  for (const sig of ['52=1', '70=r', '6f:06=107,74,39,170', 'ff=1']) assert.throws(() => r.addAnswer(sig, 'x'), { code: 'note' }, sig);
  assert.throws(() => r.addAnswer('53=45', 'x'.repeat(201)), { code: 'note' });
  const before = r.bytes; r.remove(second.id); assert(r.bytes < before);
});
test('the summary names what is new, with answers, and holds to the row rules', () => {
  const r = unknowns();
  assert.equal(summaryText(r.rows), 'New to Volt: 0xa4 x3, 0x6d:04:07 x1; New values: 0x53 Energy recovery=45 x1; Known: 2 commands, 1 read');
  r.addAnswer('a4=1', 'rainbow mode'); r.addAnswer('a4=2', 'strobe [fast]'); r.addAnswer('53=45', 'regen slider halfway');
  const summary = summaryText(r.rows);
  assert.equal(summary, 'New to Volt: 0xa4 x3 [you said: rainbow mode / strobe fast], 0x6d:04:07 x1; New values: 0x53 Energy recovery=45 x1 [you said: regen slider halfway]; Known: 2 commands, 1 read');
  assert(safeSummary(summary)); assert(serverAccepts(summary));
  const t = tally(r.rows);
  assert.deepEqual([t.known, t.reads, t.news.map(n => [n.key, n.count]), t.values.map(v => [v.sig, v.count])], [2, 1, [['a4', 3], ['6d:04:07', 1]], [['53=45', 1]]]);
  // Deleting the lines removes them from the summary; answers alone don't count.
  for (const row of r.rows.filter(row => !row.note && recognize(row.text)?.status !== 'known')) r.remove(row.id);
  assert.equal(summaryText(r.rows), '');
  // The same checks as rows, with a 2000-character bound.
  for (const bad of ['x'.repeat(2001), 'peer C0:FF:EE:12:34:56', 'me@example.com', 'New: [12 34 56]', 'cmd=0x63', 'café', '+1 555 123 4567'])
    assert.equal(safeSummary(bad), false, bad);
  assert(safeSummary('x'.repeat(2000)));
});
test('a capture full of new commands keeps the summary bounded and the body under 256 KB', () => {
  const r = new LogRecorder(START);
  r.ingest(Array.from({ length: 2000 }, (_, i) => line(`Send write command: ${frame(0x90 + (i % 16), [i >> 4 & 255, 0x80 | i & 15, ...Array(10).fill(0x22)])}`,
    `1800000${String(100 + Math.floor(i / 1000)).padStart(3, '0')}.${String(i % 1000).padStart(3, '0')}`)).join('\n'));
  for (const row of r.rows.filter(row => recognize(row.text)?.status === 'new').slice(0, 40)) r.addAnswer(recognize(row.text).sig, `answer ${'y'.repeat(180)} ${row.id}`);
  const summary = summaryText(r.rows);
  assert(summary.length <= LIMITS.summary, summary.length); assert(safeSummary(summary)); assert.match(summary, /\+\d+ more/);
  const body = submission({ ...base(r), description: '“'.repeat(4000), contact: '"'.repeat(240), rows: r.rows });
  assert.equal(JSON.parse(body).summary, summary);
  assert(new TextEncoder().encode(body).length <= LIMITS.body);
});
test('submission adds the summary last only when something is new', () => {
  const r = unknowns();
  r.addAnswer('a4=1', 'rainbow mode');
  const sent = JSON.parse(submission({ ...base(r), rows: r.rows }));
  assert.deepEqual(Object.keys(sent), ['format', 'model', 'description', 'contact', 'navee_version', 'android', 'phone', 'started_at', 'duration_s', 'hidden_rows', 'rows', 'summary']);
  assert.equal(sent.summary, 'New to Volt: 0xa4 x3 [you said: rainbow mode], 0x6d:04:07 x1; New values: 0x53 Energy recovery=45 x1; Known: 2 commands, 1 read');
  assert.equal(sent.rows[1], '08:00:01.000 [you said] 0xa4 = 1: rainbow mode');
  assert(sent.rows.every(row => serverAccepts(row)));
  assert(!('summary' in JSON.parse(submission(base(recorded())))));
});
