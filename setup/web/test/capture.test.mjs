import test from 'node:test';
import assert from 'node:assert/strict';
import { CaptureSession, JOURNAL_KEY, readJournal } from '../src/capture.js';
import { SetupError } from '../src/protocol.js';
import { frame, row } from './fixtures.mjs';
import { fakeAndroid, logRow } from './fake-android.mjs';

const token = 'a'.repeat(64);
function store() {
  const data = new Map();
  return { getItem: k => data.get(k) ?? null, setItem: (k, v) => data.set(k, v), removeItem: k => data.delete(k) };
}
// The device's report after a read's rows (see appLogcat), added unless a test wrote its own.
const reported = out => /(?:^|\n)~(?:\d{1,3}|pid\d{1,3}|uid)$/.test(out) ? out : out ? `${out}\n~0` : '~0';
// filter answers the once-per-capture --uid check ('0': Android 12+). shell(command, op, n), when
// given, runs the check and polls on a real shell instead (see fake-android.mjs).
function fixture({ fail = '', original = '', logs = row(), clockStep = 0, after = () => {}, storage = store(), serial = 'test-phone', user = '0', userList = 'Users:\n\tUserInfo{0:Owner:13} running', otherPackage = '', launcher = 'com.navee.ucaret/.MainActivity', uidRow = 'package:com.navee.ucaret uid:10123', filter = () => '0', shell } = {}) {
  const calls = [], phases = []; let locked = false, restored = false, time = 0, captured = false, polls = 0;
  const run = async (command, opts = {}) => {
    let op;
    if (command.endsWith('-d -t 1 >/dev/null 2>&1; echo $?')) op = 'filter';
    else if (command.includes(' -v epoch ')) op = 'logs';
    else if (command === 'am get-current-user') op = 'user';
    else if (command === 'cmd user list --all') op = 'users';
    else if (command.startsWith('cmd package resolve-activity')) op = 'launcher';
    else if (command.startsWith('pm list') && !command.includes('--user 0 ')) op = 'other-user';
    else if (command.includes('printf RECOVERABLE')) op = 'reacquire';
    else if (command.startsWith('pm list')) op = 'uid';
    else if (command.startsWith('umask')) op = 'prepare';
    else if (command.includes('nohup sh')) op = 'arm';
    else if (command.includes('printf STARTED')) op = 'start';
    else if (command === 'sh -s clean') op = 'cleanup';
    else if (command.startsWith('getprop')) op = 'readback';
    else if (command.includes('printf RELEASED')) op = 'release';
    else if (command.includes('printf FOREIGN')) op = 'preparation-state';
    else if (command.includes('printf ABSENT')) op = 'exists';
    else if (command.includes('printf PENDING')) op = 'inspect';
    else throw new Error('Unexpected command');
    calls.push({ op, command, opts });
    if (op === 'prepare') locked = true;
    if (fail === op) throw new SetupError('injected', 'Injected test failure');
    after(op);
    switch (op) {
      case 'uid': return uidRow;
      case 'user': return user;
      case 'users': return userList;
      case 'other-user': return otherPackage;
      case 'launcher': return launcher;
      case 'reacquire': assert(!locked); locked = true; return 'RECOVERABLE';
      case 'prepare': return `1800000000.000\n${btoa(original)}`;
      case 'arm': assert.equal(readJournal(storage).stage, 'armed'); return 'READY';
      case 'start': assert(calls.some(x => x.op === 'arm')); return 'STARTED';
      case 'filter': { const reply = shell ? await shell(command, op, 0) : filter(); if (reply instanceof Error) throw reply; return reply; }
      case 'logs': captured = true; time += clockStep; return shell ? shell(command, op, polls++) : reported(logs);
      case 'cleanup': restored = true; return '';
      case 'readback': return original;
      case 'release': assert(restored); locked = false; return 'RELEASED';
      case 'inspect': if (!locked) throw new Error('Missing lock'); return `${btoa(original)}\nPENDING`;
      case 'preparation-state': return locked ? 'OURS' : 'ABSENT';
      case 'exists': return locked ? 'PRESENT' : 'ABSENT';
    }
  };
  const session = new CaptureSession({ run, serial, storage, token: () => token, update: s => phases.push(s), now: () => time, delay: async ms => { time += ms; } });
  return { session, calls, phases, storage, captured: () => captured };
}
test('successful capture cannot export until exact restoration and lock release', async () => {
  const f = fixture({ original: '  DEBUG  ' });
  assert.throws(() => f.session.document(), { code: 'unsafe-export' });
  await f.session.begin();
  assert.equal(JSON.parse(f.session.document()).owner_identity, '010203040506');
  assert.equal(f.storage.getItem(JOURNAL_KEY), null);
  assert.deepEqual(f.calls.map(x => x.op), ['user', 'users', 'uid', 'launcher', 'prepare', 'arm', 'start', 'filter', 'logs', 'exists', 'inspect', 'cleanup', 'readback', 'release']);
  const capture = f.calls.find(x => x.op === 'logs').command;
  assert.match(capture, /--uid=10123/); assert.match(capture, /-e 'Send authData:'/); assert.match(capture, /com.navee.ucaret:D '\*:S'/);
  assert.doesNotMatch(capture, /pidof/);
});
for (const fail of ['uid', 'prepare', 'arm', 'start', 'logs']) test(`failure at ${fail} still performs cleanup and never exports`, async () => {
  const f = fixture({ fail });
  await assert.rejects(f.session.begin());
  assert.throws(() => f.session.document(), { code: 'unsafe-export' });
  if (fail !== 'uid') assert(f.calls.some(x => x.op === 'cleanup'));
});
for (const fail of ['inspect', 'cleanup', 'readback', 'release']) test(`unverified ${fail} retains recovery metadata without owner identity`, async () => {
  const f = fixture({ fail }); await assert.rejects(f.session.begin(), { code: 'cleanup' });
  const persisted = f.storage.getItem(JOURNAL_KEY);
  assert(persisted); assert(!persisted.includes('010203040506')); assert(!persisted.includes('owner_identity'));
  assert.throws(() => f.session.document(), { code: 'unsafe-export' });
});
test('cancellation after possible mutation restores and discards a captured result', async () => {
  let f; f = fixture({ after: op => { if (op === 'start') f.session.cancel(); } });
  await assert.rejects(f.session.begin(), { code: 'cancelled' });
  assert(f.calls.some(x => x.op === 'cleanup')); assert(!f.captured()); assert.equal(f.session.result, null);
});
test('no fresh frame times out at 90 seconds and cleans up', async () => {
  const f = fixture({ logs: '', clockStep: 30_000 }); await assert.rejects(f.session.begin(), { code: 'empty' });
  assert.equal(f.calls.filter(x => x.op === 'logs').length, 3); assert(f.calls.some(x => x.op === 'cleanup'));
});
test('conflicting owner data cannot be exported even when cleanup succeeds', async () => {
  const f = fixture({ logs: `${row()}\n${row('020304050607')}` });
  await assert.rejects(f.session.begin(), { code: 'conflict' }); assert.equal(f.session.result, null);
});
test('blocked persistent storage fails before any phone operation', async () => {
  const storage = store(); storage.setItem = () => { throw new Error('storage denied'); };
  const f = fixture({ storage }); await assert.rejects(f.session.begin()); assert.equal(f.calls.length, 0);
});
test('wrong Android package / ambiguous package UID fails before changing logging', async () => {
  for (const uidRow of ['package:com.navee.ucaret.fake uid:10123', 'package:com.navee.ucaret uid:0', 'package:com.navee.ucaret uid:1010123', 'package:com.navee.ucaret uid:10123\npackage:other uid:10124']) {
    const f = fixture({ uidRow }); await assert.rejects(f.session.begin(), { code: 'package' });
    assert(!f.calls.some(x => x.op === 'prepare'));
  }
});
test('secondary Android users are rejected before any logging change', async () => {
  const f = fixture({ user: '10' }); await assert.rejects(f.session.begin(), { code: 'profile' });
  assert(!f.calls.some(x => x.op === 'prepare'));
});
test('NAVEE in another user is rejected, without mutating or retaining profile names', async () => {
  const f = fixture({ userList: 'Users:\n UserInfo{0:Owner:13} running\n UserInfo{10:Private name:10}', otherPackage: 'package:com.navee.ucaret uid:1010123' });
  await assert.rejects(f.session.begin(), { code: 'profile' });
  assert(!f.calls.some(x => x.op === 'prepare')); assert.equal(f.storage.getItem(JOURNAL_KEY), null);
});
test('another user without NAVEE is allowed, but malformed enumeration fails closed', async () => {
  const f = fixture({ userList: 'Users:\n UserInfo{0:Owner:13} running\n UserInfo{10:Work:10}' });
  await f.session.begin(); assert(f.session.document());
  for (const userList of ['Unknown output', 'Users:\n UserInfo{0:A:13}\n UserInfo{0:B:10}', 'Users:\n UserInfo{10:A:10}', 'Users:\n UserInfo{0:A:13}\n junk']) {
    const broken = fixture({ userList }); await assert.rejects(broken.session.begin(), { code: 'profile' });
    assert(!broken.calls.some(x => x.op === 'prepare'));
  }
});
test('a wrong package or injected launcher is rejected before mutation', async () => {
  for (const launcher of ['other.package/.Activity', 'com.navee.ucaret/.Main; touch /tmp/pwn', 'No activity found', 'com.navee.ucaret/.Main\nextra']) {
    const f = fixture({ launcher }); await assert.rejects(f.session.begin(), { code: 'launcher' });
    assert(!f.calls.some(x => x.op === 'prepare'));
  }
});
test('damaged journal and different phone never receive restore commands', async () => {
  const f = fixture(); f.storage.setItem(JOURNAL_KEY, '{damaged');
  await assert.rejects(f.session.recover(), { code: 'journal' }); assert.equal(f.calls.length, 0);
  f.storage.setItem(JOURNAL_KEY, JSON.stringify({ version: 1, token, serial: 'other-phone', original: '', stage: 'armed' }));
  await assert.rejects(f.session.recover(), { code: 'device' }); assert.equal(f.calls.length, 0);
});
test('pending journal blocks a second capture and export from another tab', async () => {
  const f = fixture(); await f.session.begin();
  f.storage.setItem(JOURNAL_KEY, JSON.stringify({ version: 1, token, serial: 'test-phone', original: '', stage: 'armed' }));
  assert.throws(() => f.session.document(), { code: 'unsafe-export' });
  await assert.rejects(f.session.begin(), { code: 'recovery' });
});
test('a cancellation arriving during cleanup still discards the candidate', async () => {
  let f; f = fixture({ after: op => { if (op === 'cleanup') f.session.cancel(); } });
  await assert.rejects(f.session.begin(), { code: 'cancelled' });
  assert.equal(f.storage.getItem(JOURNAL_KEY), null);
  assert.throws(() => f.session.document(), { code: 'unsafe-export' });
});
test('lost final release reply reacquires exclusively and repeats verified restoration', async () => {
  const f = fixture({ original: 'WARN' }), normal = f.session.run;
  let lost = false;
  f.session.run = async (...args) => {
    const response = await normal(...args);
    if (response === 'RELEASED' && !lost) { lost = true; throw new Error('USB reply lost'); }
    return response;
  };
  await assert.rejects(f.session.begin(), { code: 'cleanup' });
  assert(f.storage.getItem(JOURNAL_KEY));
  await f.session.recover();
  assert(f.calls.some(x => x.op === 'reacquire'));
  assert.equal(f.calls.filter(x => x.op === 'cleanup').length, 2);
  assert.equal(f.storage.getItem(JOURNAL_KEY), null);
  assert.throws(() => f.session.document(), { code: 'unsafe-export' });
});

test('definite busy prepare clears only the browser journal and remains retryable', async () => {
  const f = fixture(), normal = f.session.run;
  let blocked = true;
  f.session.run = async (command, ...args) => {
    if (blocked && command.startsWith('umask')) throw new SetupError('lock-busy', 'Another setup owns the phone capture lock.');
    return normal(command, ...args);
  };
  await assert.rejects(f.session.begin(), { code: 'lock-busy' });
  assert.equal(f.storage.getItem(JOURNAL_KEY), null);
  assert.equal(f.session.safe, true);
  assert(!f.calls.some(x => ['inspect', 'cleanup', 'release', 'preparation-state'].includes(x.op)));
  blocked = false;
  await f.session.begin();
  assert(f.session.document());
});
for (const state of ['FOREIGN', 'AMBIGUOUS']) test(`lost prepare reply with ${state} reservation respects its ownership`, async () => {
  const f = fixture(), normal = f.session.run;
  const seen = [];
  f.session.run = async (command, ...args) => {
    seen.push(command);
    if (command.startsWith('umask')) throw new SetupError('timeout', 'Reply lost.');
    if (command.includes('printf FOREIGN')) return state;
    return normal(command, ...args);
  };
  await assert.rejects(f.session.begin(), { code: state === 'FOREIGN' ? 'timeout' : 'cleanup' });
  assert.equal(!!f.storage.getItem(JOURNAL_KEY), state === 'AMBIGUOUS');
  assert(!seen.some(command => command.includes('printf PENDING') || command === 'sh -s clean' || command.includes('printf RELEASED')));
  if (state === 'AMBIGUOUS') await assert.rejects(f.session.recover());
  else {
    f.storage.setItem(JOURNAL_KEY, JSON.stringify({ version: 1, token, serial: 'test-phone', original: '', stage: 'preparing' }));
    await f.session.recover();
    assert.equal(f.storage.getItem(JOURNAL_KEY), null);
  }
});
test('foreign armed reservation retains our recovery journal and receives no cleanup writes', async () => {
  const f = fixture(), seen = [];
  f.storage.setItem(JOURNAL_KEY, JSON.stringify({ version: 1, token, serial: 'test-phone', original: 'WARN', stage: 'armed' }));
  f.session.run = async command => {
    seen.push(command);
    if (command.includes('printf ABSENT')) return 'PRESENT';
    throw new SetupError('command', 'Token mismatch.');
  };
  await assert.rejects(f.session.recover());
  assert(f.storage.getItem(JOURNAL_KEY));
  assert(!seen.some(command => command === 'sh -s clean' || command.includes('printf RELEASED')));
});
// Android 11 and older: logcat has no --uid, so the capture reads NAVEE's one verified process.
const auth = (stamp, pid, identity) => logRow(stamp, pid, `Send authData: ${frame(identity)}`);
const READ_ARGS = "-d -v epoch -t 1800000000.000 -e Send authData: -s com.navee.ucaret:D *:S";
test('on a fake Android 11, capture reads only NAVEE’s verified process; an impostor’s name and tag are ignored', async () => {
  const phone = await fakeAndroid();
  try {
    // Read, the impostor's different identity would be a conflict.
    await phone.processes([[7777, 10999], [4321, 10123]]);
    await phone.log([auth('1800000000.100', 7777, '0A0B0C0D0E0F'), auth('1800000000.200', 4321)]);
    const f = fixture({ shell: phone.exec });
    await f.session.begin();
    assert.equal(JSON.parse(f.session.document()).owner_identity, '010203040506');
    assert.deepEqual(await phone.args(), ['--uid=10123 -d -t 1', `--pid=4321 ${READ_ARGS}`]);
    assert.deepEqual(f.calls.slice(-5).map(x => x.op), ['exists', 'inspect', 'cleanup', 'readback', 'release']);
  } finally { await phone.dispose(); }
});
test('on a fake Android 11, capture waits for NAVEE’s process within its 90 seconds and follows a restart', async () => {
  const phone = await fakeAndroid();
  try {
    await phone.log([auth('1800000000.200', 4321), auth('1800000000.300', 5555)]);
    const f = fixture({ clockStep: 10_000, shell: async (command, op, n) => {
      if (op === 'logs' && n === 2) await phone.processes([[4321, 10123]]);
      return phone.exec(command);
    } });
    await f.session.begin();
    assert.equal(JSON.parse(f.session.document()).owner_identity, '010203040506');
    assert.equal(f.calls.filter(x => x.op === 'logs').length, 3);
    assert.equal((await phone.args()).filter(a => a.startsWith('--pid=')).length, 1);
  } finally { await phone.dispose(); }
  // NAVEE restarting with a new pid is followed: each poll resolves the pid again.
  const restarted = await fakeAndroid();
  try {
    await restarted.processes([[4321, 10123]]);
    const f = fixture({ shell: async (command, op, n) => {
      if (op === 'logs' && n === 1) { await restarted.processes([[5555, 10123]]); await restarted.log([auth('1800000000.300', 5555)]); }
      return restarted.exec(command);
    } });
    await f.session.begin();
    assert.equal(JSON.parse(f.session.document()).owner_identity, '010203040506');
    assert.deepEqual((await restarted.args()).slice(1), [`--pid=4321 ${READ_ARGS}`, `--pid=5555 ${READ_ARGS}`]);
  } finally { await restarted.dispose(); }
});
for (const [list, code, message] of [[[], 'not-running', /^NAVEE wasn’t running/], [[[7777, 10999]], 'impostor', /^Another app is using NAVEE’s name/],
  [[[4321, 10123], [4400, 10123]], 'several', /^More than one NAVEE process/]])
  test(`on a fake Android 11 without one verified NAVEE process (${code}), nothing is read for 90 seconds and the reason is named`, async () => {
    const phone = await fakeAndroid();
    try {
      await phone.processes(list); await phone.log([auth('1800000000.200', 7777), auth('1800000000.300', 4321)]);
      const f = fixture({ clockStep: 30_000, shell: phone.exec });
      await assert.rejects(f.session.begin(), { code, message });
      assert.equal(f.calls.filter(x => x.op === 'logs').length, 3);
      assert(!(await phone.args()).some(a => a.startsWith('--pid=')), 'logcat never ran');
      assert(f.calls.some(x => x.op === 'cleanup')); assert.equal(f.storage.getItem(JOURNAL_KEY), null);
      assert.throws(() => f.session.document(), { code: 'unsafe-export' });
    } finally { await phone.dispose(); }
  });
test('a refused log read is an error at once, never “nothing found”, and still cleans up', async () => {
  // Android 11 refusing --uid after a check that said it works.
  const phone = await fakeAndroid();
  try {
    await phone.processes([[4321, 10123]]); await phone.log([auth('1800000000.200', 4321)]);
    const f = fixture({ shell: (command, op) => op === 'filter' ? '0' : phone.exec(command) });
    await assert.rejects(f.session.begin(), { code: 'logcat', message: /^Volt couldn’t read NAVEE’s log on this phone/ });
    assert.equal(f.calls.filter(x => x.op === 'logs').length, 1); assert(f.calls.some(x => x.op === 'cleanup'));
    assert.throws(() => f.session.document(), { code: 'unsafe-export' });
  } finally { await phone.dispose(); }
  // A failing logcat or no pidof in either mode, even after a good poll.
  for (const [filter, reply] of [['0', '~1'], ['1', '~1'], ['1', '~127'], ['1', '~255']]) {
    const f = fixture({ filter: () => filter, logs: reply });
    await assert.rejects(f.session.begin(), { code: 'logcat' }); assert(f.calls.some(x => x.op === 'cleanup'));
  }
});
test('a failed or slow --uid check falls back to the pid; Android 12 keeps --uid', async () => {
  for (const reply of [new SetupError('timeout', 'slow'), new Error('usb glitch'), '1', '', 'sh: junk']) {
    const f = fixture({ filter: () => reply }); await f.session.begin();
    assert.match(f.calls.find(x => x.op === 'logs').command, /pidof com\.navee\.ucaret/);
    assert.doesNotMatch(f.calls.find(x => x.op === 'logs').command, /--uid=/);
  }
  const phone = await fakeAndroid({ uid: true });
  try {
    await phone.processes([[4321, 10123]]); await phone.log([auth('1800000000.200', 4321)]);
    const f = fixture({ shell: phone.exec }); await f.session.begin();
    assert.equal(JSON.parse(f.session.document()).owner_identity, '010203040506');
    assert.deepEqual(await phone.args(), ['--uid=10123 -d -t 1', `--uid=10123 ${READ_ARGS}`]);
  } finally { await phone.dispose(); }
});
test('cancelling during the --uid check reads nothing and cleans up', async () => {
  let f; f = fixture({ after: op => { if (op === 'filter') f.session.cancel(); } });
  await assert.rejects(f.session.begin(), { code: 'cancelled' });
  assert(!f.captured()); assert(f.calls.some(x => x.op === 'cleanup'));
});
