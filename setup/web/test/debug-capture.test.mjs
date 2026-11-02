import test from 'node:test';
import assert from 'node:assert/strict';
import { CaptureSession, JOURNAL_KEY } from '../src/capture.js';
import { DebugCaptureSession, PROBE_MS, RECORD_MS, WATCH_SECONDS } from '../src/debug-capture.js';
import { HEALTH, LIMITS, parseProbe, probeCommand } from '../src/navee-log.js';
import { SetupError } from '../src/protocol.js';
import { row } from './fixtures.mjs';
import { frame, line } from './navee-fixtures.mjs';
import { fakeAndroid, logRow } from './fake-android.mjs';

const token = 'c'.repeat(64);
function store() {
  const data = new Map();
  return { getItem: k => data.get(k) ?? null, setItem: (k, v) => data.set(k, v), removeItem: k => data.delete(k) };
}
// The device's report after a poll's rows (see appLogcat), added unless a test wrote its own.
const reported = out => /(?:^|\n)~\d{1,3}$/.test(out) ? out : out ? `${out}\n~0` : '~0';
// Phone double: classifies each trusted command, never runs anything. shell(command, op, n), when
// given, runs the filter check, probe and polls on a real shell instead (see fake-android.mjs).
function fixture({ polls = [], fail = '', facts = {}, after = () => {}, logs, probe = () => '1\nVERBOSE\n812\n37', filter = () => '0', shell } = {}) {
  const calls = []; let time = 0, wall = 1_900_000_000_000, poll = 0, probes = 0, locked = false, restored = false;
  const storage = store();
  const run = async (command, opts = {}) => {
    const op = command.includes('\nn() { ') ? 'probe' : command.includes(' -v epoch ') ? 'logs' : command.endsWith('-d -t 1 >/dev/null 2>&1; echo $?') ? 'filter'
      : command === 'am get-current-user' ? 'user' : command === 'cmd user list --all' ? 'users'
      : command.startsWith('cmd package resolve-activity') ? 'launcher' : command.startsWith('pm list') ? 'uid'
      : command.startsWith('umask') ? 'prepare' : command.includes('nohup sh') ? 'arm' : command.includes('printf STARTED') ? 'start'
      : command === 'date +%s.%N' ? 'date' : command.startsWith('dumpsys package') ? 'navee' : command === 'getprop ro.build.version.release' ? 'android'
      : command === 'getprop ro.product.model' ? 'phone' : command === 'sh -s clean' ? 'cleanup'
      : command.startsWith('getprop') ? 'readback' : command.includes('printf RELEASED') ? 'release' : command.includes('printf ABSENT') ? 'exists'
      : command.includes('printf PENDING') ? 'inspect' : null;
    if (!op) throw new Error('Unexpected command');
    calls.push({ op, command, opts, at: time });
    if (fail === op) throw new SetupError('injected', 'Injected test failure');
    after(op, calls);
    switch (op) {
      case 'user': return '0';
      case 'users': return 'Users:\n\tUserInfo{0:Owner:13} running';
      case 'launcher': return 'com.navee.ucaret/.MainActivity';
      case 'uid': return 'package:com.navee.ucaret uid:10123';
      case 'prepare': locked = true; return `1800000000.000000000\n${btoa('')}`;
      case 'arm': return 'READY';
      case 'start': return 'STARTED';
      case 'date': return '1800000003.500000000';
      case 'navee': return facts.navee ?? 'versionName=2.1.9';
      case 'android': return facts.android ?? '16';
      case 'phone': if (facts.phone instanceof Error) throw facts.phone; return facts.phone ?? 'Pixel 9';
      case 'logs': {
        time += 1_000; wall += 1_000;
        if (shell) return shell(command, op, poll++);
        if (logs) return reported(logs(poll++, command));
        return reported(polls[Math.min(poll++, polls.length - 1)] ?? '');
      }
      case 'probe': return shell ? shell(command, op, probes++) : probe(probes++, command);
      case 'filter': { const reply = shell ? await shell(command, op, 0) : filter(); if (reply instanceof Error) throw reply; return reply; }
      case 'cleanup': restored = true; return '';
      case 'readback': return '';
      case 'release': assert(restored); locked = false; return 'RELEASED';
      case 'exists': return locked ? 'PRESENT' : 'ABSENT';
      case 'inspect': return `${btoa('')}\nPENDING`;
    }
  };
  const records = [], healths = [];
  const session = new DebugCaptureSession({ run, serial: 'test-phone', storage, token: () => token, now: () => time, wall: () => wall,
    delay: async ms => { time += ms; wall += ms; }, onRecord: r => records.push(r.rows.length), onHealth: h => healths.push(h.state) });
  return { session, calls, storage, records, healths, advance: ms => { time += ms; wall += ms; } };
}
const write = (stamp, cmd = 0x52, value = 1) => line(`Send write command: ${frame(cmd, [value])}`, stamp);
// A row as -v uid prints it (Android 11 and older): the uid that wrote it right after the epoch.
const by = (writer, row) => row.replace(/^(\S+) /, `$1 ${writer} `);

test('recording polls filtered NAVEE rows until Stop, then cleans up before review is allowed', async () => {
  let f;
  f = fixture({ logs: (n, command) => {
    if (n === 2) f.session.stop();
    return [write('1800000004.000'), line('网络请求 getUserInfo', '1800000004.100'), ...(n >= 1 ? [write('1800000005.000', 0x6d, 2)] : [])].join('\n');
  } });
  const done = f.session.begin();
  assert.throws(() => f.session.capture(), { code: 'unsafe-export' });
  await done;
  const result = f.session.capture();
  assert.equal(result.reason, 'stopped');
  assert.deepEqual(result.recorder.rows.map(r => r.text), [`Send write command: ${frame(0x52, [1])}`, `Send write command: ${frame(0x6d, [2])}`]);
  assert.equal(result.recorder.hidden, 1);
  assert.deepEqual(result.facts, { naveeVersion: '2.1.9', android: '16', phone: 'Pixel 9' });
  assert.equal(result.startedAt, '2027-01-15T08:00:00.000Z');
  assert.equal(f.storage.getItem(JOURNAL_KEY), null);
  const ops = f.calls.map(c => c.op);
  assert.deepEqual(ops.slice(0, 14), ['user', 'users', 'uid', 'launcher', 'prepare', 'arm', 'start', 'date', 'navee', 'android', 'phone', 'filter', 'probe', 'logs']);
  assert.equal(ops.filter(op => op === 'filter').length, 1);
  assert.deepEqual(result.health, { state: 'healthy', text: 'Receiving NAVEE Bluetooth lines.' });
  assert.deepEqual(ops.slice(-5), ['exists', 'inspect', 'cleanup', 'readback', 'release']);
  // One more poll after Stop collects the last rows.
  assert.equal(ops.filter(op => op === 'logs').length, 4);
  // Same lock and guard as owner capture, with the longer watchdog bound.
  const arm = f.calls.find(c => c.op === 'arm');
  assert.match(arm.opts.input, new RegExp(`while \\[ "\\$i" -lt ${WATCH_SECONDS} \\]; do`));
  assert.match(arm.opts.input, /volt-owner-capture\.lock/);
  const logs = f.calls.filter(c => c.op === 'logs').map(c => c.command);
  assert.equal(f.calls.find(c => c.op === 'filter').command, 'logcat --uid=10123 -d -t 1 >/dev/null 2>&1; echo $?');
  assert.equal(logs[0], `{ r=$( { { logcat --uid=10123 -d -v epoch -t '1800000000.000000000' -s com.navee.ucaret:V '*:S' 2>/dev/null; echo "$?" >&3; } | head -n 100 >&4; } 3>&1 ); echo "~$r"; } 4>&1`);
  assert.match(logs[1], /-t '1800000004\.100'/);
  assert.equal(f.calls.find(c => c.op === 'logs').opts.maxBytes, 1024 * 1024);
});
test('owner capture keeps its 120-second watchdog and authentication poll', async () => {
  const calls = [];
  const run = async (command, opts) => {
    calls.push({ command, opts });
    if (command === 'am get-current-user') return '0';
    if (command === 'cmd user list --all') return 'Users:\n\tUserInfo{0:Owner:13} running';
    if (command.startsWith('cmd package')) return 'com.navee.ucaret/.MainActivity';
    if (command.startsWith('pm list')) return 'package:com.navee.ucaret uid:10123';
    if (command.startsWith('umask')) return `1800000000.000\n${btoa('')}`;
    if (command.includes('nohup sh')) return 'READY';
    if (command.startsWith('logcat --uid=10123 -d -t 1 ')) return '0';
    if (command.includes(' -v epoch ')) return `${row()}\n~0`;
    if (command.includes('printf ABSENT')) return 'PRESENT';
    if (command.includes('printf PENDING')) return `${btoa('')}\nPENDING`;
    if (command.includes('printf RELEASED')) return 'RELEASED';
    return '';
  };
  const session = new CaptureSession({ run, serial: 'p', storage: store(), token: () => token, now: () => 0, delay: async () => {} });
  await session.begin();
  assert.match(calls.find(c => c.command.includes('nohup sh')).opts.input, /while \[ "\$i" -lt 120 \]; do\n {6}ours/);
  assert.match(calls.find(c => c.command.includes(' -v epoch ')).command, /--uid=10123 -d -v epoch .* -e 'Send authData:' -s com.navee.ucaret:D/);
});
test('the recording stops itself at the time limit and still cleans up', async () => {
  const f = fixture({ polls: [write('1800000004.000')] });
  await f.session.begin();
  const result = f.session.capture();
  assert.equal(result.reason, 'limit');
  assert(result.durationS >= RECORD_MS / 1000 && result.durationS <= LIMITS.duration);
  assert(f.calls.some(c => c.op === 'cleanup'));
});
test('a full capture stops recording with its rows kept', async () => {
  const many = Array.from({ length: 3100 }, (_, i) => write(`18000001${String(Math.floor(i / 1000)).padStart(2, '0')}.${String(i % 1000).padStart(3, '0')}`)).join('\n');
  const f = fixture({ polls: [many] });
  await f.session.begin();
  const result = f.session.capture();
  assert.equal(result.reason, 'full'); assert.equal(result.recorder.rows.length, LIMITS.rows);
});
test('oversized replies shrink the batch, crowded batches drain before pausing', async () => {
  let f;
  const seen = [];
  f = fixture({ logs: (n, command) => {
    seen.push(Number(/\| head -n (\d+) >&4/.exec(command)[1]));
    if (n === 0) throw new SetupError('oversize', 'too big');
    if (n === 4) f.session.stop();
    // A full batch (lines >= the head -n bound) is polled again immediately.
    return n === 1 ? Array.from({ length: 10 }, (_, i) => write(`1800000004.${String(i).padStart(3, '0')}`)).join('\n') : '';
  } });
  await f.session.begin();
  assert.deepEqual(seen.slice(0, 4), [100, 10, 10, 20]);
  assert.equal(f.session.capture().recorder.rows.length, 10);
});
test('a failed poll, a cancellation or a failed cleanup discards the recording', async () => {
  const failed = fixture({ fail: 'logs' });
  await assert.rejects(failed.session.begin(), { code: 'injected' });
  assert(failed.calls.some(c => c.op === 'cleanup'));
  assert.throws(() => failed.session.capture(), { code: 'unsafe-export' });
  let f;
  f = fixture({ polls: [write('1800000004.000')], after: op => { if (op === 'logs') f.session.cancel(); } });
  await assert.rejects(f.session.begin(), { code: 'cancelled' });
  assert.throws(() => f.session.capture(), { code: 'unsafe-export' });
  const g = fixture({ polls: [write('1800000004.000')], fail: 'release', after: (op, calls) => { if (op === 'logs' && calls.filter(c => c.op === 'logs').length > 1) g.session.stop(); } });
  await assert.rejects(g.session.begin(), { code: 'cleanup' });
  assert(g.storage.getItem(JOURNAL_KEY));
  assert(!g.storage.getItem(JOURNAL_KEY).includes('Send write'));
  assert.throws(() => g.session.capture(), { code: 'unsafe-export' });
});
test('slow polls are retried twice before the recording fails', async () => {
  let f, n = 0;
  f = fixture({ logs: () => { n++; if (n <= 2) throw new SetupError('timeout', 'slow'); f.session.stop(); return write('1800000004.000'); } });
  await f.session.begin();
  assert.equal(f.session.capture().recorder.rows.length, 1);
  const g = fixture({ logs: () => { throw new SetupError('timeout', 'slow'); } });
  await assert.rejects(g.session.begin(), { code: 'timeout' });
});
test('notes are stamped on the phone clock while recording, and refused afterwards', async () => {
  let f;
  f = fixture({ logs: n => {
    if (n === 0) assert.equal(f.session.addNote('now rainbow mode').text, '[note] now rainbow mode');
    if (n === 1) f.session.stop();
    return [write('1800000004.000'), write('1800000009.000')].join('\n');
  } });
  assert.throws(() => f.session.addNote('early'), { code: 'note' });
  await f.session.begin();
  const rows = f.session.capture().recorder.rows;
  // date: 1800000003.500 at wall 1.9e12; the note was added 1 s of polling later.
  assert.deepEqual(rows.map(r => r.time), ['08:00:04.000', '08:00:04.500', '08:00:09.000']);
  assert.throws(() => f.session.addNote('late'), { code: 'note' });
});
test('only safe, well-formed device facts are kept; others become unknown', async () => {
  const f = fixture({ facts: { navee: 'versionName=2.1.9; rm -rf', android: '16 QPR1 build', phone: new SetupError('command', 'x') }, polls: [''],
    after: (op, calls) => { if (op === 'logs') calls.length > 0 && f.session.stop(); } });
  await f.session.begin();
  assert.deepEqual(f.session.capture().facts, { naveeVersion: 'unknown', android: 'unknown', phone: 'unknown' });
  const g = fixture({ facts: { phone: 'Owner me@example.com' }, polls: [''], after: op => { if (op === 'logs') g.session.stop(); } });
  await g.session.begin();
  assert.equal(g.session.capture().facts.phone, 'unknown');
});
test('capture health is probed as recording starts, then about every 5 s, not on every poll', async () => {
  let f;
  f = fixture({ logs: n => { if (n === 9) f.session.stop(); return ''; } });
  await f.session.begin();
  const probes = f.calls.filter(c => c.op === 'probe'), polls = f.calls.filter(c => c.op === 'logs');
  assert.equal(polls.length, 11);
  assert(probes.length >= 4 && probes.length <= polls.length / 2 + 1, String(probes.length));
  assert.equal(f.calls.indexOf(probes[0]) + 1, f.calls.indexOf(polls[0]));
  for (let i = 1; i < probes.length; i++) {
    const gap = probes[i].at - probes[i - 1].at;
    assert(gap >= PROBE_MS && gap < PROBE_MS + 3_500, String(gap));
  }
  // None after Stop: the last poll only collects.
  assert(f.calls.indexOf(probes.at(-1)) < f.calls.indexOf(polls.at(-2)));
  for (const probe of probes) {
    assert.equal(probe.command, probeCommand('10123', '1800000000.000000000'));
    assert.deepEqual(probe.opts, { maxBytes: 4096, timeout: 8_000 });
  }
});
test('health follows the probe and kept NAVEE rows, changes only with its state, and is kept for review', async () => {
  let f;
  const replies = ['0\nVERBOSE\n812\n0', '1\nVERBOSE\n900\n0', '1\nVERBOSE\n950\n12'];
  f = fixture({ probe: n => replies[Math.min(n, 2)], logs: n => {
    if (n === 0) f.session.addNote('now lights'); // a note is not a NAVEE line
    if (n === 8) f.session.stop();
    return n >= 6 ? write('1800000004.000') : '';
  } });
  await f.session.begin();
  assert.deepEqual(f.healths, ['checking', 'not-running', 'navee-silent', 'no-bluetooth', 'healthy']);
  assert.deepEqual(f.session.capture().health, { state: 'healthy', text: 'Receiving NAVEE Bluetooth lines.' });
});
test('the log filter is chosen once per recording: --uid where logcat has it, else each row’s writer', async () => {
  const record = async filter => {
    let f;
    f = fixture({ filter, logs: (n, command) => { if (n === 1) f.session.stop(); return command.includes(' -v uid ') ? by(10123, write('1800000004.000')) : write('1800000004.000'); } });
    await f.session.begin();
    const of = op => f.calls.filter(c => c.op === op);
    assert.equal(of('filter').length, 1);
    assert.deepEqual(of('filter')[0].opts, { maxBytes: 256, timeout: 8_000 });
    assert.equal(f.session.capture().recorder.rows.length, 1);
    const polls = of('logs').map(c => c.command), probes = of('probe').map(c => c.command);
    const mode = polls.every(c => c.includes('logcat --uid=10123 ')) ? 'uid' : polls.every(c => c.includes('logcat -v uid -d -v epoch ')) ? 'rows' : 'mixed';
    assert(!polls.some(c => /pidof|--pid|\/proc\//.test(c)));
    assert.deepEqual(new Set(probes), new Set([probeCommand('10123', '1800000000.000000000', mode)]));
    return mode;
  };
  assert.equal(await record(() => '0'), 'uid');
  // Android 11 and older ("Unknown option" → 1), and anything unclear, read each row's writer, which works everywhere.
  for (const reply of [() => '1', () => '255', () => 'logcat: junk', () => '', () => new SetupError('timeout', 'slow'), () => new Error('usb glitch')])
    assert.equal(await record(reply), 'rows', String(reply));
  // Only a cancellation during the check propagates.
  for (const reply of [f => { f.session.cancel(); return '0'; }, () => new SetupError('cancelled', 'Capture stopped.')]) {
    let f;
    f = fixture({ filter: () => reply(f) });
    await assert.rejects(f.session.begin(), { code: 'cancelled' });
    assert.equal(f.calls.filter(c => c.op === 'logs').length, 0);
    assert(f.calls.some(c => c.op === 'cleanup'));
  }
});
test('a failing logcat is reported to health, never read as an empty poll; the recording continues', async () => {
  let f;
  f = fixture({ logs: n => { if (n === 4) f.session.stop(); return n < 3 ? '~1' : write('1800000004.000'); } });
  await f.session.begin();
  assert.deepEqual(f.healths, ['checking', 'no-bluetooth', 'logcat-failed', 'healthy']);
  assert.equal(f.session.capture().reason, 'stopped'); assert.equal(f.session.capture().recorder.rows.length, 1);
  // With the -v uid fallback failing too, neither filter works; kept for the review.
  let g;
  g = fixture({ filter: () => '1', logs: n => { if (n === 2) g.session.stop(); return '~1'; } });
  await g.session.begin();
  assert.deepEqual(g.healths, ['checking', 'no-bluetooth', 'unsupported']);
  assert.equal(g.session.capture().health.state, 'unsupported'); assert.equal(g.session.capture().recorder.rows.length, 0);
  // head ending a full batch early (logcat's SIGPIPE status after rows) is a good read.
  let h;
  h = fixture({ logs: n => { if (n === 1) h.session.stop(); return `${write('1800000004.000')}\n~141`; } });
  await h.session.begin();
  assert.equal(h.session.capture().health.state, 'healthy'); assert.equal(h.session.capture().recorder.rows.length, 1);
});
test('rows mode: another uid’s rows under NAVEE’s tag are ignored and named; rows without a writer are never recorded', async () => {
  const other = by(10999, write('1800000004.000', 0x6d, 2)), own = by(10123, write('1800000005.000'));
  let f;
  f = fixture({ filter: () => '1', logs: n => { if (n === 3) f.session.stop(); return n < 2 ? other : [other, own].join('\n'); } });
  await f.session.begin();
  // Only another uid's rows: a count, never content. Then NAVEE's own arrive.
  assert.deepEqual(f.healths, ['checking', 'no-bluetooth', 'impostor', 'healthy']);
  assert.equal(HEALTH.impostor, 'Another app is writing under NAVEE’s name; its lines are ignored.');
  const recorder = f.session.capture().recorder;
  assert.deepEqual(recorder.rows.map(r => r.text), [`Send write command: ${frame(0x52, [1])}`]);
  assert.deepEqual([recorder.foreign, recorder.hidden, recorder.own, recorder.unattributed], [1, 0, 1, 0]);
  // Kept for the review when nothing of NAVEE's ever arrives.
  let g;
  g = fixture({ filter: () => '1', logs: n => { if (n === 2) g.session.stop(); return other; } });
  await g.session.begin();
  assert.deepEqual(g.session.capture().health, { state: 'impostor', text: HEALTH.impostor });
  assert.equal(g.session.capture().recorder.rows.length, 0); assert.equal(g.session.capture().recorder.hidden, 0);
  // A row printed without its writer column can't be told from another app's: never recorded, and the
  // reader can't pick out NAVEE's lines.
  let h;
  h = fixture({ filter: () => '1', logs: n => { if (n === 2) h.session.stop(); return [write('1800000004.000'), write('1800000004.100', 0x6d, 2)].join('\n'); } });
  await h.session.begin();
  const empty = h.session.capture();
  assert.equal(empty.recorder.rows.length, 0); assert.equal(empty.recorder.hidden, 0); assert.equal(empty.recorder.unattributed, 2);
  assert.equal(empty.health.state, 'unsupported');
  // The old pid-mode reports are failed reads now, never "not running" or an impostor.
  let k;
  k = fixture({ shell: (command, op, n) => {
    if (op !== 'logs') return op === 'filter' ? '1' : '1\nVERBOSE\n812\n37';
    if (n === 1) k.session.stop();
    return n ? '~uid' : '~pid0';
  } });
  await k.session.begin();
  assert.deepEqual(k.healths, ['checking', 'no-bluetooth', 'unsupported']);
});
test('on a fake Android 11 over a real shell, rows mode records only NAVEE’s uid: never an impostor on a recycled pid, and across a restart', async () => {
  const phone = await fakeAndroid();
  try {
    // NAVEE (uid 10123) as pid 4321; another uid wrote a valid frame under NAVEE's tag as pid 4321 before
    // NAVEE took that pid, and keeps writing under its own pid 7777.
    await phone.processes([[4321, 10123], [7777, 10999]]);
    await phone.log([logRow('1800000001.000', 1000, 'Start proc', 'ActivityManager', 1000), logRow('1800000002.000', 4321, `Send write command: ${frame(0x6d, [2])}`, undefined, 10999),
      logRow('1800000004.000', 4321, `Send write command: ${frame(0x52, [1])}`), logRow('1800000004.100', 7777, `Send write command: ${frame(0x6d, [3])}`, undefined, 10999)]);
    let f;
    f = fixture({ shell: async (command, op, n) => {
      if (op === 'logs' && n === 2) { // NAVEE restarts with a new pid; the impostor takes 4321
        await phone.processes([[5555, 10123], [4321, 10999]]);
        await phone.log([logRow('1800000006.000', 4321, `Send write command: ${frame(0x6d, [4])}`, undefined, 10999),
          logRow('1800000006.500', 5555, `Send write command: ${frame(0x52, [0])}`)]);
      }
      if (op === 'logs' && n === 4) f.session.stop();
      return phone.exec(command);
    } });
    await f.session.begin();
    const result = f.session.capture(), args = await phone.args();
    assert.deepEqual(result.recorder.rows.map(r => r.text), [`Send write command: ${frame(0x52, [1])}`, `Send write command: ${frame(0x52, [0])}`]);
    assert.deepEqual([result.recorder.foreign, result.recorder.hidden], [3, 0]);
    assert.equal(result.health.state, 'healthy');
    assert.equal(args[0], '--uid=10123 -d -t 1'); assert.equal(args.filter(a => a.startsWith('--uid=')).length, 1);
    assert(args.some(a => a === "-v uid -d -v epoch -t 1800000000.000000000 -s com.navee.ucaret:V *:S"));
    assert(args.some(a => a === '-v uid -d -v epoch -t 1800000006.500 -s com.navee.ucaret:V *:S'));
    assert(!args.some(a => a.includes('--pid')));
    assert(!f.calls.filter(c => c.op === 'logs').some(c => /pidof|\/proc\//.test(c.command)));
  } finally { await phone.dispose(); }
  // Only the impostor writes: nothing is recorded, and the page says why.
  const fake = await fakeAndroid();
  try {
    await fake.processes([[7777, 10999]]);
    await fake.log([logRow('1800000004.100', 7777, `Send write command: ${frame(0x6d, [2])}`, undefined, 10999)]);
    let g;
    g = fixture({ shell: (command, op, n) => { if (op === 'logs' && n === 2) g.session.stop(); return fake.exec(command); } });
    await g.session.begin();
    const capture = g.session.capture();
    assert.equal(capture.recorder.rows.length, 0); assert.equal(capture.recorder.hidden, 0); assert.equal(capture.recorder.foreign, 1);
    assert.deepEqual(capture.health, { state: 'impostor', text: 'Another app is writing under NAVEE’s name; its lines are ignored.' });
  } finally { await fake.dispose(); }
});
test('on a real shell, a flood of another uid’s rows larger than the batch, even in one millisecond, can’t stall the cursor or starve NAVEE’s later rows', async () => {
  const phone = await fakeAndroid(), flood = `Send write command: ${frame(0x6d, [2])}`;
  try {
    // 250 identical rows in one millisecond, then 150 more a millisecond apart, all under NAVEE's tag
    // from another uid on NAVEE's pid; then NAVEE's own.
    await phone.processes([[4321, 10123]]);
    await phone.log([...Array.from({ length: 250 }, () => logRow('1800000004.000', 4321, flood, undefined, 10999)),
      ...Array.from({ length: 150 }, (_, i) => logRow(`1800000004.${String(i + 1).padStart(3, '0')}`, 4321, flood, undefined, 10999)),
      logRow('1800000005.000', 4321, `Send write command: ${frame(0x52, [1])}`), logRow('1800000005.100', 4321, `Send write command: ${frame(0x52, [0])}`)]);
    const batches = [];
    let f;
    f = fixture({ shell: (command, op, n) => {
      if (op === 'logs') { batches.push(Number(/\| head -n (\d+) >&4/.exec(command)[1])); if (n === 12) f.session.stop(); }
      return phone.exec(command);
    } });
    await f.session.begin();
    const { recorder, health } = f.session.capture();
    assert.deepEqual(recorder.rows.map(r => r.text), [`Send write command: ${frame(0x52, [1])}`, `Send write command: ${frame(0x52, [0])}`]);
    // Every flood row moved the cursor and dedupe once; none was kept or counted as NAVEE's.
    assert.deepEqual([recorder.foreign, recorder.hidden, recorder.own, recorder.cursor], [400, 0, 2, '1800000005.100']);
    assert.equal(health.state, 'healthy');
    // Full windows (logcat's divider counts, as head counts it) were read again at once, the crowded
    // millisecond grew the batch, and NAVEE's rows arrived within the first few polls.
    assert.deepEqual(batches.slice(0, 6), [100, 100, 200, 200, 400, 400]);
  } finally { await phone.dispose(); }
});
test('a failed, slow or junk probe only makes health unknown; the recording continues', async () => {
  let f;
  const replies = [new SetupError('timeout', 'slow'), new Error('usb glitch'), 'sh: garbage', new SetupError('command', 'exit 1'), '1\nVERBOSE\n812\n0'];
  f = fixture({ probe: n => { const reply = replies[Math.min(n, replies.length - 1)]; if (reply instanceof Error) throw reply; return reply; },
    logs: n => { if (n === 30) f.session.stop(); return n >= 25 ? write('1800000004.000') : ''; } });
  await f.session.begin();
  const result = f.session.capture(), probes = f.calls.filter(c => c.op === 'probe');
  assert.equal(result.reason, 'stopped'); assert.equal(result.recorder.rows.length, 1);
  assert.deepEqual(f.calls.slice(-5).map(c => c.op), ['exists', 'inspect', 'cleanup', 'readback', 'release']);
  assert.deepEqual(f.healths, ['checking', 'navee-silent', 'healthy']);
  // A failed probe waits three times as long; junk output still counts as an answer.
  assert(probes[1].at - probes[0].at >= 3 * PROBE_MS && probes[2].at - probes[1].at >= 3 * PROBE_MS);
  assert(probes[3].at - probes[2].at < 2 * PROBE_MS);
  // Probe failures never touched the poll: one poll per pass, up to Stop and the final read.
  assert.equal(f.calls.filter(c => c.op === 'logs').length, 32);
});
test('cancelling during a probe still discards the recording and cleans up', async () => {
  for (const reply of [() => '1\nVERBOSE\n1\n1', () => { throw new Error('usb gone'); }, () => { throw new SetupError('cancelled', 'Capture stopped.'); }]) {
    let f;
    f = fixture({ probe: () => { f.session.cancel(); return reply(); } });
    await assert.rejects(f.session.begin(), { code: 'cancelled' });
    assert(f.calls.filter(c => c.op === 'logs').length <= 1);
    assert(f.calls.some(c => c.op === 'cleanup'));
    assert.throws(() => f.session.capture(), { code: 'unsafe-export' });
    assert.equal(f.storage.getItem(JOURNAL_KEY), null);
  }
});
test('the probe works on a real shell: dividers aren’t counted, only NAVEE’s uid’s rows are NAVEE’s, a refused filter is unknown, it exits 0', async () => {
  const phone = await fakeAndroid();
  try {
    const probe = async (filter, uid = 10123, on = phone) => parseProbe(await on.exec(probeCommand(uid, '1800000000.000000000', filter)));
    assert.deepEqual(await probe('rows'), { propertyOk: true, running: false, phoneLines: 0, naveeLines: 0 });
    await phone.log([logRow('1800000001.000', 1000, 'Start proc', 'ActivityManager', 1000), logRow('1800000002.000', 4321, 'one'), logRow('1800000003.000', 4321, 'two', 'OtherTag'),
      logRow('1800000003.500', 4321, 'impostor on a recycled pid', undefined, 10999), logRow('1800000003.600', 4321, 'shares digits', undefined, 110123)]);
    // NAVEE's uid wrote two rows (any tag); the system, the impostor on NAVEE's pid and a uid sharing its digits wrote the rest.
    assert.deepEqual(await probe('rows'), { propertyOk: true, running: false, phoneLines: 5, naveeLines: 2 });
    // Running counts only processes whose Uid: line is NAVEE's.
    await phone.processes([[7777, 10999]]);
    assert.equal((await probe('rows')).running, false);
    await phone.processes([[4321, 10123], [7777, 10999]]);
    assert.deepEqual(await probe('rows'), { propertyOk: true, running: true, phoneLines: 5, naveeLines: 2 });
    // Android 11 refuses --uid: unknown, never zero.
    assert.deepEqual(await probe('uid'), { propertyOk: true, running: true, phoneLines: 5, naveeLines: null });
    await phone.property('INFO');
    assert.equal((await probe('rows')).propertyOk, false);
    assert(!(await phone.args()).some(a => a.includes('--pid')));
  } finally { await phone.dispose(); }
  // A logcat without -v uid: unknown, never zero; a short getpwuid name is NAVEE's for a low uid.
  const old = await fakeAndroid({ uidFormat: false });
  try {
    await old.log([logRow('1800000002.000', 4321, 'one')]);
    assert.deepEqual(parseProbe(await old.exec(probeCommand(10123, '1800000000.000000000', 'rows'))), { propertyOk: true, running: false, phoneLines: 1, naveeLines: null });
  } finally { await old.dispose(); }
  const low = await fakeAndroid();
  try {
    await low.log([logRow('1800000002.000', 4321, 'one', undefined, 'u0_a5'), logRow('1800000002.500', 4321, 'two', undefined, 10005), logRow('1800000003.000', 4321, 'three', undefined, 'u0_a50')]);
    assert.equal(parseProbe(await low.exec(probeCommand(10005, '1800000000.000000000', 'rows'))).naveeLines, 2);
  } finally { await low.dispose(); }
});
test('a recording cannot start while a recovery record exists', async () => {
  const f = fixture();
  f.storage.setItem(JOURNAL_KEY, JSON.stringify({ version: 1, token, serial: 'test-phone', original: '', stage: 'armed' }));
  await assert.rejects(f.session.begin(), { code: 'recovery' });
  assert.equal(f.calls.length, 0);
});
