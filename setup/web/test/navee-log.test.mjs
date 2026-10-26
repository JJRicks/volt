import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { LIMITS, LogRecorder, MODELS, ROW_BUDGET, annotate, classify, clock, decodeFrame, logcatCommand, noteText, safeText, submission } from '../src/navee-log.js';
import { COMMANDS, SENSITIVE } from '../src/navee-commands.js';
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
    ['[Exo] connect success, mac=C0:FF:EE:12:34:56', '[Exo] connect success, mac=[MAC]'],
    [`Exo notify raw: ${frame(0x40, [1, 2])}`, `Exo notify raw: ${frame(0x40, [1, 2])}`],
    ['DG write light: [aa 01 02]', 'DG write light: [aa 01 02]'],
    ['DG write ok: light', 'DG write ok: light'],
    ['DG write fail (light): Timeout', 'DG write fail (light): Timeout'],
    ['DG notify raw: [aa 10 20]', 'DG notify raw: [aa 10 20]'],
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
  assert.equal(kept('[Exo] link C0:FF:EE:12:34:56 and c0-ff-ee-12-34-57 via C0FFEE123458'), '[Exo] link [MAC] and [MAC] via [MAC]');
  assert.equal(kept('[Exo] serial N2546ZZ9US1234567 ok'), '[Exo] serial [serial] ok');
  assert.equal(kept('[Exo] sn=X2538SYNTHETIC0042, other n2546zz9us7654321'), '[Exo] sn=[serial], other [serial]');
  assert.equal(kept('Connect success (N2546ZZ9US1234567)'), 'Connect success ([scooter])');
  // Long words without digits are not serials.
  assert.equal(kept('[Exo] onCharacteristicChanged notification'), '[Exo] onCharacteristicChanged notification');
  for (const message of ['[Exo] owner rider@example.com', 'Write failure: mail a.b+c@mail.example.org', '[Exo] call +1 (555) 123-4567',
    'DG write fail (x): 5551234567', '[Exo] 555.123.4567', 'Notify failure: 0044 20 7946 0958'])
    assert.equal(kept(message), null, message);
  // Hex bytes that happen to be decimal digits are not phone numbers.
  const clock = frame(0x6f, [6, 0x12, 0x34, 0x56, 0x78]);
  assert.equal(kept(`Send write command: ${clock}`), `Send write command: ${clock}`);
  assert.equal(kept('DG notify raw: [12 34 56 78 90 12 34 56 78]'), 'DG notify raw: [12 34 56 78 90 12 34 56 78]');
});
test('rows are bounded printable ASCII and oversize input is hidden', () => {
  const long = kept(`[Exo] ${'x'.repeat(2000)}`);
  assert.equal(long.length, LIMITS.text); assert(long.endsWith('...'));
  assert.equal(kept(`[Exo] ${'x'.repeat(9000)}`), null);
  assert.equal(kept('[Exo] 状态 ok\tnext'), '[Exo] ?? ok next');
  assert.equal(kept('[Exo] bell\u0007 here'), '[Exo] bell? here');
  assert.equal(classify(undefined), null);
});
test('frames decode and annotate in plain English where Volt knows the command', () => {
  assert.deepEqual(decodeFrame(bytes(0x52, [1])), { flag: 0, cmd: 0x52, read: false, payload: [1] });
  assert.deepEqual(decodeFrame(bytes(0x70, [], 0, true)), { flag: 0, cmd: 0x70, read: true, payload: [] });
  assert.equal(decodeFrame(bytes(0x52, [1]).slice(1)), null);
  assert.equal(annotate(`Send write command: ${frame(0x52, [1])}`), '0x52 Cruise control = 1');
  assert.equal(annotate(`Write success: ${frame(0x6d, [4, 2, 1])}`), '0x6d Line lights color = 1');
  assert.equal(annotate(`Write success: ${frame(0x6d, [1, 2])}`), '0x6d Ambient light = 2');
  assert.equal(annotate(`Send write command: ${frame(0x6f, [4, 90])}`), '0x6f Charge ceiling = 90');
  assert.equal(annotate(`Send write command: ${frame(0x5e, [1, 3, 5, 2])}`), '0x5e = 1, 3, 5, 2');
  assert.equal(annotate(`Send read command: ${hex(bytes(0x70, [], 0, true))}`), 'read 0x70 Settings readback');
  assert.equal(annotate(`Send read command: ${hex(bytes(0x73, [], 0, true))}`), 'read 0x73');
  assert.equal(annotate('sendCmd cmd=0X57, value=0X01'), 'Automatic lights');
  assert.equal(annotate('Notify success'), '');
});
test('command names and opcodes match ScooterCommands.java; models match ModelRegistry KNOWN', async () => {
  const java = await readFile(new URL('../../../src/com/jjricks/volt/ScooterCommands.java', import.meta.url), 'utf8');
  const statements = java.split(/\);\s*\n/);
  assert.match(java, /sensitiveCommand\(int cmd\) \{ return cmd==0x30 \|\| cmd==0x31 \|\| cmd==0x63 \|\| cmd==0x7a; \}/);
  for (const [key, label] of Object.entries(COMMANDS)) {
    const [cmd, selector, sub] = key.split(':').map(x => parseInt(x, 16));
    if (key === '70') { assert.match(java, /readbackCmd = sensitive \? 0x7a : 0x70/); continue; }
    if (key === '6f:06') { assert.match(java, /setClock[\s\S]*localTime\(6,/); continue; }
    if (sub !== undefined) {
      const [, base, kind] = /^(Line|Star) lights(?: (.*))?$/.exec(label);
      assert.match(java, new RegExp(`lightCatalog\\("${base.toLowerCase()}",${selector},`));
      assert.equal(['', 'pattern', 'color', 'Boost color'].indexOf(kind || ''), sub);
      assert.match(java, /new int\[\]\{target,0\}[\s\S]*new int\[\]\{target,1\}[\s\S]*new int\[\]\{target,2\}[\s\S]*new int\[\]\{target,3\}/);
      continue;
    }
    if (cmd === 0x6d) { assert.match(java, new RegExp(`oemOnlyLight\\("\\w+","${label}",${selector},`)); continue; }
    const found = statements.find(s => s.includes(`"${label}"`) && new RegExp(`0x${cmd.toString(16).padStart(2, '0')}\\b`).test(s) &&
      (selector === undefined || s.includes(`new int[]{${selector}}`)));
    assert(found, `${key} ${label}`);
  }
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
  assert.equal(r.rows[0].annotation, '0x52 Cruise control = 1');
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
  big.ingest(Array.from({ length: 600 }, (_, i) => line(`[Exo] ${'y'.repeat(500)}`, `1800000100.${String(i).padStart(3, '0')}`)).join('\n'));
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
  full.ingest(Array.from({ length: 3000 }, (_, i) => line(`[Exo] ${'z'.repeat(300)}`, `1800000100.${String(i).padStart(3, '0')}`)).join('\n'));
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
