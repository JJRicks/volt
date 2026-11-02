import test from 'node:test';
import assert from 'node:assert/strict';
import { parseAuthLine, parseCapture, ownerDocument, quote, MAX_CAPTURE_BYTES, LOG_FILTERS, appLogcat, logFilter, logFilterCheck, logcatReply, ownRows, verifiedPids, writerOf } from '../src/protocol.js';
import { frame, row } from './fixtures.mjs';
import { fakeAndroid, logRow } from './fake-android.mjs';
test('strict six-byte identity with supported roles and signed checksum', () => {
  assert.deepEqual(parseAuthLine(row('ABCDEF123456', 1)), { identity: 'ABCDEF123456', role: 1 });
  assert.deepEqual(parseAuthLine(`Send authData: [${frame()}]`), { identity: '010203040506', role: 0 });
  assert.equal(parseAuthLine(row(undefined, 2)), null);
  assert.equal(parseAuthLine(row().replace('55 aa', '55 ab')), null);
  assert.equal(parseAuthLine(row() + ' 00'), null);
  assert.equal(parseAuthLine('password=irrelevant'), null);
});
test('only fresh timestamped auth; repeated identical values okay; conflicting role or identity rejected', () => {
  assert.equal(parseCapture(row(undefined, 0, '1799999999.999'), 1800000000), null);
  assert.equal(parseCapture(`Send authData: ${frame()}`, 1800000000), null);
  assert.equal(parseCapture(`${row()}\n${row()}`, 1800000000).identity, '010203040506');
  assert.throws(() => parseCapture(`${row()}\n${row('010203040507')}`, 1800000000), { code: 'conflict' });
  assert.throws(() => parseCapture(row(undefined, 1), 1800000000, { identity: '010203040506', role: 0 }), { code: 'conflict' });
});
test('byte bound rejects multibyte and oversized input', () => {
  assert.throws(() => parseCapture('x'.repeat(MAX_CAPTURE_BYTES + 1), 0), { code: 'oversize' });
  assert.throws(() => parseCapture('é'.repeat(MAX_CAPTURE_BYTES / 2 + 1), 0), { code: 'oversize' });
});
test('export format interoperates with the native owner importer and contains no phone serial', () => {
  const out = JSON.parse(ownerDocument({ identity: '010203040506', role: 0 }, new Date(0)));
  assert.equal(out.format, 'navee-offline-owner-v1'); assert.equal(out.owner_identity, '010203040506');
  assert.equal(out.captured_at_utc, '1970-01-01T00:00:00.000Z'); assert.equal(out.serial, undefined);
  assert.throws(() => ownerDocument({ identity: 'wrong', role: 0 }));
});
test('shell quoting treats quotes and metacharacters as data', () => {
  assert.equal(quote("a'b;$x"), "'a'\\''b;$x'");
});
test('the log filter is asked of logcat itself, and anything but a clean 0 falls back to each row’s writer', () => {
  assert.equal(logFilterCheck(10123), 'logcat --uid=10123 -d -t 1 >/dev/null 2>&1; echo $?');
  assert.equal(logFilter('0'), 'uid');
  for (const reply of ['1', '255', '', ' 0', '0\n0', null, undefined]) assert.equal(logFilter(reply), 'rows', String(reply));
  // No pid mode: logcat --pid also returns rows an earlier process wrote under a recycled pid.
  assert.deepEqual(LOG_FILTERS, ['uid', 'rows']);
  for (const uid of ['10123;reboot', '1012', '123456', '1e4', -10123, ' 10123'])
    for (const build of [logFilterCheck, verifiedPids, writerOf, id => ownRows('', id), id => appLogcat(id, 'uid', '-d')]) assert.throws(() => build(uid), String(uid));
  for (const filter of ['pid', 'tag', '']) assert.throws(() => appLogcat(10123, filter, '-d'), filter);
});
test('a read is by --uid or by each row’s writer, and reports logcat’s exit status', () => {
  assert.equal(appLogcat(10123, 'uid', "-d -t '1800000000.1'", 'head -n 5'),
    `{ r=$( { { logcat --uid=10123 -d -t '1800000000.1' 2>/dev/null; echo "$?" >&3; } | head -n 5 >&4; } 3>&1 ); echo "~$r"; } 4>&1`);
  assert.equal(appLogcat(10123, 'rows', "-d -t '1800000000.1'", 'head -n 5'),
    `{ r=$( { { logcat -v uid -d -t '1800000000.1' 2>/dev/null; echo "$?" >&3; } | head -n 5 >&4; } 3>&1 ); echo "~$r"; } 4>&1`);
  assert.equal(appLogcat(10123, 'rows', '-d'), `{ r=$( { { logcat -v uid -d 2>/dev/null; echo "$?" >&3; } | cat >&4; } 3>&1 ); echo "~$r"; } 4>&1`);
  // The capture-health probe's running count: the Uid: line of /proc/<pid>/status, never the process
  // name alone or /proc/<pid>'s owner. It no longer selects rows to read.
  assert.equal(verifiedPids(10123), `k=0 o=0 q=; for p in $(pidof com.navee.ucaret 2>/dev/null); do set -- $(grep '^Uid:' /proc/$p/status 2>/dev/null); if [ "$2" = 10123 ]; then k=$((k+1)) q=$p; elif [ -n "$2" ]; then o=$((o+1)); fi; done`);
});
test('a logcat reply separates output from its report; a failure is never an empty read', () => {
  assert.deepEqual(logcatReply('a\nb\n~0'), { output: 'a\nb', status: 'ok' });
  assert.deepEqual(logcatReply('~0'), { output: '', status: 'ok' });
  assert.deepEqual(logcatReply('~1'), { output: '', status: 'failed' });
  assert.deepEqual(logcatReply('~127'), { output: '', status: 'failed' });
  // head ended the read early: logcat's SIGPIPE status after real output.
  assert.deepEqual(logcatReply('a\n~141'), { output: 'a', status: 'ok' });
  // strict (owner capture, nothing cuts its read short): only exit 0 is a whole read, whatever came before.
  assert.deepEqual(logcatReply('a\n~141', true), { output: 'a', status: 'failed' });
  assert.deepEqual(logcatReply('a\n~1', true), { output: 'a', status: 'failed' });
  assert.deepEqual(logcatReply('a\n~0', true), { output: 'a', status: 'ok' });
  assert.deepEqual(logcatReply('~0', true), { output: '', status: 'ok' });
  // No report: the command didn't finish normally, whatever came before. The old pid-mode reports are gone.
  for (const text of ['', null, undefined, '\n', '~', '~x', '~01234', 'a\n~0 ', 'rows ~0', '~pid0', '~pid2', '~uid', 'a\n~uid'])
    assert.equal(logcatReply(text).status, 'failed', String(text));
  assert.deepEqual(logcatReply('a\n~0 '), { output: 'a\n~0 ', status: 'failed' });
});
test('on a real shell over a fake Android 11, the debugger’s fallback reads every row with its writer, cut short by head', async () => {
  // head -n 3 keeps logcat's divider and two rows, ending the read early.
  const phone = await fakeAndroid(), cut = 'head -n 3', args = "-d -v epoch -t '1800000004.100' -s com.navee.ucaret:V '*:S'";
  try {
    const read = async filter => logcatReply(await phone.exec(appLogcat(10123, filter, args, cut)));
    assert.equal(await phone.exec(logFilterCheck(10123)), '1');
    await phone.log([logRow('1800000003.000', 4321, 'before the cursor'), logRow('1800000004.500', 4321, 'navee one'),
      logRow('1800000004.600', 4321, 'impostor on a recycled pid', undefined, 10999), logRow('1800000004.700', 4321, 'navee two'),
      logRow('1800000004.800', 1000, 'system', 'ActivityManager', 1000)]);
    // A refused --uid is reported, not read as no rows.
    assert.deepEqual(await read('uid'), { output: '', status: 'failed' });
    // Rows under NAVEE's tag, each with its writer; a read cut short by head still counts as a good read.
    const rows = await read('rows');
    assert.equal(rows.status, 'ok');
    assert.deepEqual(rows.output.split('\n'), ['--------- beginning of main', '1800000004.500 10123  4321  4321 D com.navee.ucaret: [2] BleHandler.l: navee one',
      '1800000004.600 10999  4321  4321 D com.navee.ucaret: [2] BleHandler.l: impostor on a recycled pid']);
    const writer = writerOf(10123);
    assert.deepEqual(rows.output.split('\n').map(line => writer(line)?.own ?? null), [null, true, false]);
    assert.equal((await phone.args()).at(-1), '-v uid -d -v epoch -t 1800000004.100 -s com.navee.ucaret:V *:S');
    // A logcat without -v uid refuses the read: a failure, never an empty poll.
    const old = await fakeAndroid({ uidFormat: false });
    try {
      await old.log([logRow('1800000004.500', 4321, 'navee one')]);
      assert.deepEqual(logcatReply(await old.exec(appLogcat(10123, 'rows', args, cut))), { output: '', status: 'failed' });
    } finally { await old.dispose(); }
    // With --uid (Android 12+), the check says so.
    const modern = await fakeAndroid({ uid: true });
    try { assert.equal(logFilter(await modern.exec(logFilterCheck(10123))), 'uid'); } finally { await modern.dispose(); }
  } finally { await phone.dispose(); }
});
test('writerOf reads each -v uid line’s writer column: NAVEE’s uid, another uid, or none', () => {
  const writer = writerOf(10123), own = '1800000000.125 10123  4321  4321 D com.navee.ucaret: x';
  assert.deepEqual(writer(own), { own: true, stamp: '1800000000.125', rest: '4321  4321 D com.navee.ucaret: x' });
  assert.deepEqual(writer(`  ${own}`), { own: true, stamp: '  1800000000.125', rest: '4321  4321 D com.navee.ucaret: x' });
  for (const other of [own.replace('10123', '10999'), own.replace('10123', '110123'), own.replace('10123', '1012'), own.replace('10123', 'shell'), own.replace('10123', 'u0_a5')])
    assert.deepEqual(writer(other), { own: false }, other);
  // No writer column: a divider, a row printed without it, message text that only looks like one.
  for (const none of ['--------- beginning of main', own.replace(' 10123 ', ' '), '10123  4321  4321 D com.navee.ucaret: x', 'x 1800000000.125 10123  4321  4321 D t: x', '', '~0'])
    assert.equal(writer(none), null, none);
  assert.equal(writerOf(10005)(own.replace('10123', 'u0_a5')).own, true);
  assert.equal(writerOf(10005)(own.replace('10123', '10005')).own, true);
});
test('owner capture’s fallback keeps only rows NAVEE’s uid wrote, on a real shell over a fake Android 11', async () => {
  const phone = await fakeAndroid(), args = "-d -v epoch -t '1800000004.100' -s com.navee.ucaret:V '*:S'";
  try {
    await phone.log([logRow('1800000004.500', 4321, 'navee one'), logRow('1800000004.600', 4321, 'impostor on a recycled pid', undefined, 10999),
      logRow('1800000004.700', 1000, 'system', 'ActivityManager', 1000)]);
    const reply = logcatReply(await phone.exec(appLogcat(10123, 'rows', args)), true);
    assert.equal(reply.status, 'ok');
    assert.equal(ownRows(reply.output, 10123), '1800000004.500 4321  4321 D com.navee.ucaret: [2] BleHandler.l: navee one');
    assert.equal((await phone.args()).at(-1), '-v uid -d -v epoch -t 1800000004.100 -s com.navee.ucaret:V *:S');
  } finally { await phone.dispose(); }
});
test('ownRows keeps only rows NAVEE’s uid wrote, never a row without a writer', () => {
  const own = '  1800000000.125 10123  4321  4321 D com.navee.ucaret: x';
  assert.equal(ownRows(`${own}\n`, 10123), '  1800000000.125 4321  4321 D com.navee.ucaret: x');
  for (const other of [own.replace('10123', '10999'), own.replace('10123', '110123'), own.replace('10123', 'shell'), own.replace(' 10123 ', ' '),
    '--------- beginning of main', '10123  4321  4321 D com.navee.ucaret: x', '~0']) assert.equal(ownRows(other, 10123), '', other);
  // getpwuid names of five characters or fewer stand in for the number: uid 10005 prints as u0_a5.
  assert.notEqual(ownRows(own.replace('10123', 'u0_a5'), 10005), '');
  assert.notEqual(ownRows(own.replace('10123', '10005'), 10005), '');
  assert.equal(ownRows(own.replace('10123', 'u0_a5'), 10123), '');
  assert.throws(() => ownRows(own, '10123; id'));
});
