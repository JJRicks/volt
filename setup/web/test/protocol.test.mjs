import test from 'node:test';
import assert from 'node:assert/strict';
import { parseAuthLine, parseCapture, ownerDocument, quote, MAX_CAPTURE_BYTES, appLogcat, logFilter, logFilterCheck, logcatReply, verifiedPids } from '../src/protocol.js';
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
test('the log filter is asked of logcat itself, and anything but a clean 0 falls back to the pid', () => {
  assert.equal(logFilterCheck(10123), 'logcat --uid=10123 -d -t 1 >/dev/null 2>&1; echo $?');
  assert.equal(logFilter('0'), 'uid');
  for (const reply of ['1', '255', '', ' 0', '0\n0', null, undefined]) assert.equal(logFilter(reply), 'pid', String(reply));
  for (const uid of ['10123;reboot', '1012', '123456', '1e4', -10123, ' 10123'])
    for (const build of [logFilterCheck, verifiedPids, id => appLogcat(id, 'pid', '-d'), id => appLogcat(id, 'uid', '-d')]) assert.throws(() => build(uid), String(uid));
  assert.throws(() => appLogcat(10123, 'tag', '-d'));
});
test('the pid fallback reads only a process whose real uid is NAVEE’s, and reports what it did', () => {
  // The Uid: line of /proc/<pid>/status, never the process name alone or /proc/<pid>'s owner.
  assert.equal(verifiedPids(10123), `k=0 o=0 q=; for p in $(pidof com.navee.ucaret 2>/dev/null); do set -- $(grep '^Uid:' /proc/$p/status 2>/dev/null); if [ "$2" = 10123 ]; then k=$((k+1)) q=$p; elif [ -n "$2" ]; then o=$((o+1)); fi; done`);
  assert.equal(appLogcat(10123, 'uid', "-d -t '1800000000.1'", 'head -n 5'),
    `{ r=$( { { logcat --uid=10123 -d -t '1800000000.1' 2>/dev/null; echo "$?" >&3; } | head -n 5 >&4; } 3>&1 ); echo "~$r"; } 4>&1`);
  assert.equal(appLogcat(10123, 'pid', '-d'), `${verifiedPids(10123)}
if ! command -v pidof >/dev/null; then echo '~127'; elif [ "$k" = 1 ]; then { r=$( { { logcat --pid="$q" -d 2>/dev/null; echo "$?" >&3; } | cat >&4; } 3>&1 ); echo "~$r"; } 4>&1; elif [ "$k" = 0 ] && [ "$o" != 0 ]; then echo '~uid'; else echo "~pid$k"; fi`);
});
test('a logcat reply separates output from its report; a failure is never an empty read', () => {
  assert.deepEqual(logcatReply('a\nb\n~0'), { output: 'a\nb', status: 'ok' });
  assert.deepEqual(logcatReply('~0'), { output: '', status: 'ok' });
  assert.deepEqual(logcatReply('~1'), { output: '', status: 'failed' });
  assert.deepEqual(logcatReply('~127'), { output: '', status: 'failed' });
  // head ended the read early: logcat's SIGPIPE status after real output.
  assert.deepEqual(logcatReply('a\n~141'), { output: 'a', status: 'ok' });
  assert.deepEqual(logcatReply('~pid0'), { output: '', status: 'no-pid' });
  assert.deepEqual(logcatReply('~pid2'), { output: '', status: 'several' });
  assert.deepEqual(logcatReply('~uid'), { output: '', status: 'other-uid' });
  // No report: the command didn't finish normally, whatever came before.
  for (const text of ['', null, undefined, '\n', '~', '~x', '~01234', 'a\n~0 ', 'rows ~0']) assert.equal(logcatReply(text).status, 'failed', String(text));
  assert.deepEqual(logcatReply('a\n~0 '), { output: 'a\n~0 ', status: 'failed' });
});
test('on a real shell over a fake Android 11, the fallback reads NAVEE’s own process only', async () => {
  // head -n 2 keeps logcat's divider and one row, ending the read early.
  const phone = await fakeAndroid(), cut = 'head -n 2', args = "-d -v epoch -t '1800000004.100' -s com.navee.ucaret:V '*:S'";
  try {
    const read = async filter => logcatReply(await phone.exec(appLogcat(10123, filter, args, cut)));
    assert.equal(await phone.exec(logFilterCheck(10123)), '1');
    await phone.log([logRow('1800000003.000', 4321, 'before the cursor'), logRow('1800000004.500', 4321, 'navee one'), logRow('1800000004.600', 7777, 'impostor'),
      logRow('1800000004.700', 4321, 'navee two'), logRow('1800000004.800', 1000, 'system', 'ActivityManager')]);
    // A refused --uid is reported, not read as no rows.
    assert.deepEqual(await read('uid'), { output: '', status: 'failed' });
    assert.deepEqual(await read('pid'), { output: '', status: 'no-pid' });
    await phone.processes([[4321, 10123]]);
    // Owned by NAVEE's uid: rows. A read cut short by head still counts as a good read.
    const owned = await read('pid');
    assert.equal(owned.status, 'ok'); assert.match(owned.output, /navee one/); assert.doesNotMatch(owned.output, /navee two|impostor|before|system/);
    assert.match((await phone.args()).at(-1), /^--pid=4321 -d -v epoch -t 1800000004\.100 -s com\.navee\.ucaret:V \*:S$/);
    // Another uid using NAVEE's name: nothing read, a distinct report.
    await phone.processes([[7777, 10999]]);
    const calls = (await phone.args()).length;
    assert.deepEqual(await read('pid'), { output: '', status: 'other-uid' });
    assert.equal((await phone.args()).length, calls, 'logcat never ran for the impostor');
    // Beside the real NAVEE, an impostor is ignored; two verified NAVEE processes are not guessed between.
    await phone.processes([[7777, 10999], [4321, 10123]]);
    assert.match((await read('pid')).output, /navee one/);
    await phone.processes([[4321, 10123], [4400, 10123]]);
    assert.deepEqual(await read('pid'), { output: '', status: 'several' });
    // With --uid (Android 12+), the check says so.
    const modern = await fakeAndroid({ uid: true });
    try { assert.equal(logFilter(await modern.exec(logFilterCheck(10123))), 'uid'); } finally { await modern.dispose(); }
  } finally { await phone.dispose(); }
});
