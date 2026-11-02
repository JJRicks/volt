import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { execFile } from 'node:child_process';
import { CaptureSession, JOURNAL_KEY } from '../src/capture.js';
import { DebugCaptureSession, PROBE_MS, RECORD_MS, WATCH_SECONDS } from '../src/debug-capture.js';
import { LIMITS, parseProbe, probeCommand } from '../src/navee-log.js';
import { SetupError } from '../src/protocol.js';
import { row } from './fixtures.mjs';
import { frame, line } from './navee-fixtures.mjs';

const token = 'c'.repeat(64);
function store() {
  const data = new Map();
  return { getItem: k => data.get(k) ?? null, setItem: (k, v) => data.set(k, v), removeItem: k => data.delete(k) };
}
// Phone double: classifies each trusted command, never runs anything.
function fixture({ polls = [], fail = '', facts = {}, after = () => {}, logs, probe = () => '4321\nVERBOSE\n812\n37' } = {}) {
  const calls = []; let time = 0, wall = 1_900_000_000_000, poll = 0, probes = 0, locked = false, restored = false;
  const storage = store();
  const run = async (command, opts = {}) => {
    const op = command.startsWith('p=$(pidof ') ? 'probe' : command === 'am get-current-user' ? 'user' : command === 'cmd user list --all' ? 'users'
      : command.startsWith('cmd package resolve-activity') ? 'launcher' : command.startsWith('pm list') ? 'uid'
      : command.startsWith('umask') ? 'prepare' : command.includes('nohup sh') ? 'arm' : command.includes('printf STARTED') ? 'start'
      : command === 'date +%s.%N' ? 'date' : command.startsWith('dumpsys package') ? 'navee' : command === 'getprop ro.build.version.release' ? 'android'
      : command === 'getprop ro.product.model' ? 'phone' : command.startsWith('logcat') ? 'logs' : command === 'sh -s clean' ? 'cleanup'
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
        if (logs) return logs(poll++, command);
        return polls[Math.min(poll++, polls.length - 1)] ?? '';
      }
      case 'probe': return probe(probes++, command);
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
  assert.deepEqual(ops.slice(0, 13), ['user', 'users', 'uid', 'launcher', 'prepare', 'arm', 'start', 'date', 'navee', 'android', 'phone', 'probe', 'logs']);
  assert.deepEqual(result.health, { state: 'healthy', text: 'Receiving NAVEE Bluetooth lines.' });
  assert.deepEqual(ops.slice(-5), ['exists', 'inspect', 'cleanup', 'readback', 'release']);
  // One more poll after Stop collects the last rows.
  assert.equal(ops.filter(op => op === 'logs').length, 4);
  // Same lock and guard as owner capture, with the longer watchdog bound.
  const arm = f.calls.find(c => c.op === 'arm');
  assert.match(arm.opts.input, new RegExp(`while \\[ "\\$i" -lt ${WATCH_SECONDS} \\]; do`));
  assert.match(arm.opts.input, /volt-owner-capture\.lock/);
  const logs = f.calls.filter(c => c.op === 'logs').map(c => c.command);
  assert.equal(logs[0], "logcat --uid=10123 -d -v epoch -t '1800000000.000000000' -s com.navee.ucaret:V '*:S' | head -n 100");
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
    if (command.startsWith('logcat')) return row();
    if (command.includes('printf ABSENT')) return 'PRESENT';
    if (command.includes('printf PENDING')) return `${btoa('')}\nPENDING`;
    if (command.includes('printf RELEASED')) return 'RELEASED';
    return '';
  };
  const session = new CaptureSession({ run, serial: 'p', storage: store(), token: () => token, now: () => 0, delay: async () => {} });
  await session.begin();
  assert.match(calls.find(c => c.command.includes('nohup sh')).opts.input, /while \[ "\$i" -lt 120 \]; do\n {6}ours/);
  assert.match(calls.find(c => c.command.startsWith('logcat')).command, /-e 'Send authData:' -s com.navee.ucaret:D/);
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
    seen.push(Number(/\| head -n (\d+)$/.exec(command)[1]));
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
  const replies = ['\nVERBOSE\n812\n0', '4321\nVERBOSE\n900\n0', '4321\nVERBOSE\n950\n12'];
  f = fixture({ probe: n => replies[Math.min(n, 2)], logs: n => {
    if (n === 0) f.session.addNote('now lights'); // a note is not a NAVEE line
    if (n === 8) f.session.stop();
    return n >= 6 ? write('1800000004.000') : '';
  } });
  await f.session.begin();
  assert.deepEqual(f.healths, ['checking', 'not-running', 'navee-silent', 'no-bluetooth', 'healthy']);
  assert.deepEqual(f.session.capture().health, { state: 'healthy', text: 'Receiving NAVEE Bluetooth lines.' });
  // Android 11 and older refuse --uid: named while recording, and kept for the review.
  let g;
  g = fixture({ probe: () => '4321\nVERBOSE\n250\nx', logs: n => { if (n === 2) g.session.stop(); return ''; } });
  await g.session.begin();
  assert.deepEqual(g.healths, ['checking', 'unsupported']);
  assert.equal(g.session.capture().health.state, 'unsupported');
  assert.equal(g.session.capture().recorder.rows.length, 0);
});
test('a failed, slow or junk probe only makes health unknown; the recording continues', async () => {
  let f;
  const replies = [new SetupError('timeout', 'slow'), new Error('usb glitch'), 'sh: garbage', new SetupError('command', 'exit 1'), '4321\nVERBOSE\n812\n0'];
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
  for (const reply of [() => '4321\nVERBOSE\n1\n1', () => { throw new Error('usb gone'); }, () => { throw new SetupError('cancelled', 'Capture stopped.'); }]) {
    let f;
    f = fixture({ probe: () => { f.session.cancel(); return reply(); } });
    await assert.rejects(f.session.begin(), { code: 'cancelled' });
    assert(f.calls.filter(c => c.op === 'logs').length <= 1);
    assert(f.calls.some(c => c.op === 'cleanup'));
    assert.throws(() => f.session.capture(), { code: 'unsafe-export' });
    assert.equal(f.storage.getItem(JOURNAL_KEY), null);
  }
});
test('the probe works in a real shell: dividers aren’t counted, a refused option is x, it always exits 0', async () => {
  const root = await mkdtemp(join(tmpdir(), 'volt-probe-test-')), bin = join(root, 'bin');
  try {
    await mkdir(bin);
    const scripts = {
      pidof: '[ "$1" = com.navee.ucaret ] && [ -f "$FIXTURE/running" ] && echo 4321 || exit 1',
      getprop: '[ "$1" = log.tag.com.navee.ucaret ] && cat "$FIXTURE/property"',
      // Android 11 logcat: "Unknown option" and exit 1 for --uid; dividers whenever a buffer prints.
      logcat: `printf '%s\\n' "$*" >> "$FIXTURE/args"
case "$*" in *--uid=*) [ ! -f "$FIXTURE/no-uid" ] || { echo "logcat: Unknown option '--uid=10123'." >&2; exit 1; }; n=$(cat "$FIXTURE/navee") ;; *) n=$(cat "$FIXTURE/all") ;; esac
[ "$n" = 0 ] || echo '--------- beginning of main'
i=0; while [ "$i" -lt "$n" ]; do echo "11-02 10:00:01.000  1234  1234 I tag: line $i"; i=$((i+1)); done
[ "$n" = 0 ] || echo '--------- switch to system'`
    };
    for (const [name, script] of Object.entries(scripts)) await writeFile(join(bin, name), `#!/bin/sh\n${script}\n`, { mode: 0o700 });
    const probe = async ({ running = true, property = 'VERBOSE', all, navee, uid = true }) => {
      await writeFile(join(root, 'property'), `${property}\n`); await writeFile(join(root, 'all'), String(all)); await writeFile(join(root, 'navee'), String(navee));
      for (const [flag, on] of [['running', running], ['no-uid', !uid]]) on ? await writeFile(join(root, flag), '') : await rm(join(root, flag), { force: true });
      const stdout = await new Promise((resolve, reject) => execFile('/bin/sh', ['-c', probeCommand(10123, '1800000000.000000000')],
        { env: { ...process.env, PATH: `${bin}:${process.env.PATH}`, FIXTURE: root } }, (error, out) => error ? reject(error) : resolve(out)));
      return parseProbe(stdout.replace(/\r?\n$/, '')); // as run() returns it
    };
    assert.deepEqual(await probe({ all: 7, navee: 3 }), { propertyOk: true, uidFilter: true, running: true, phoneLines: 7, naveeLines: 3 });
    assert.deepEqual(await probe({ all: 0, navee: 0 }), { propertyOk: true, uidFilter: true, running: true, phoneLines: 0, naveeLines: 0 });
    assert.deepEqual(await probe({ running: false, property: 'INFO', all: 5, navee: 0 }), { propertyOk: false, uidFilter: true, running: false, phoneLines: 5, naveeLines: 0 });
    assert.deepEqual(await probe({ all: 9, navee: 0, uid: false }), { propertyOk: true, uidFilter: false, running: true, phoneLines: 9, naveeLines: null });
    assert.deepEqual((await readFile(join(root, 'args'), 'utf8')).split('\n').slice(0, 2), ['-d -t 1800000000.000000000', '-d -t 1800000000.000000000 --uid=10123']);
  } finally { await rm(root, { recursive: true, force: true }); }
});
test('a recording cannot start while a recovery record exists', async () => {
  const f = fixture();
  f.storage.setItem(JOURNAL_KEY, JSON.stringify({ version: 1, token, serial: 'test-phone', original: '', stage: 'armed' }));
  await assert.rejects(f.session.begin(), { code: 'recovery' });
  assert.equal(f.calls.length, 0);
});
