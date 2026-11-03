#!/usr/bin/env python3
"""Run setup/capture-owner.py against a fake adb and fake phone tools, never a real phone.

The fake adb runs each `adb shell` string with the real /bin/sh, so quoting, the
device-side redirect of the --uid check and exit statuses behave as on a phone, and
the phone's bytes reach the helper unchanged. Its PATH holds only the fake tools: am,
cmd, monkey, getprop, setprop, date and logcat. The helper's polling clock advances
only when it sleeps, so a 3-second capture is always five reads.
"""
import contextlib
import importlib.util
import io
import json
import pathlib
import shlex
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

SOURCE = pathlib.Path(__file__).resolve().parents[1] / 'setup' / 'capture-owner.py'
spec = importlib.util.spec_from_file_location('capture_owner', SOURCE)
capture_owner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(capture_owner)

SERIAL = 'FAKE0001'
NAVEE = '10123'
CLOCK = '1800000000.100000000'
PRIVATE = 'PRIVATE-PHONE-TEXT'
TOOLS = ('am', 'cmd', 'monkey', 'getprop', 'setprop', 'date', 'logcat')

# adb: `devices`, then `-s SERIAL shell <string>` run by /bin/sh with exit status passed back.
# A shell string listed in adb_fail fails in adb itself (a dropped connection); the phone never runs it.
ADB = r'''
import json, pathlib, subprocess, sys
root = pathlib.Path(sys.argv[1]); argv = sys.argv[2:]
with root.joinpath('adb.log').open('a') as out: out.write(json.dumps(argv) + '\n')
if argv == ['devices']:
    print('List of devices attached\n' + 'FAKE0001\tdevice\n'); sys.exit(0)
if argv[:3] != ['-s', 'FAKE0001', 'shell'] or len(argv) != 4:
    sys.stderr.write('fake adb: unexpected call\n'); sys.exit(99)
if argv[3] in json.loads(root.joinpath('state.json').read_text()).get('adb_fail', []):
    sys.stderr.write('error: closed\n'); sys.exit(1)
sys.stdout.flush()
sys.exit(subprocess.run(['/bin/sh', '-c', argv[3]], env={'PATH': str(root / 'bin')}).returncode)
'''
# The phone's tools, sharing state.json. logcat honours --uid (Android 12+ only), -v uid, -t (a
# time, or a row count; ignore_since drops the time filter), -e and the NAVEE tag filter. Each log
# row is [epoch, writer, pid, tag, message]; a null writer is a row printed without the uid column.
# Like logcat, it repeats a row's prefix on each "\n"-separated line of its message, and writes the
# message's other characters (a bare "\r" among them) as UTF-8 bytes, unchanged. A command listed in
# fail exits 1 after printing private text.
PHONE = r'''
import json, pathlib, re, sys
root = pathlib.Path(sys.argv[1]); name = sys.argv[2]; args = sys.argv[3:]
path = root / 'state.json'; state = json.loads(path.read_text())
def save(): path.write_text(json.dumps(state))
NAVEE = 'com.navee.ucaret'
if ' '.join([name] + args) in state.get('fail', []):
    print('PRIVATE-PHONE-TEXT'); sys.stderr.write(name + ': PRIVATE-PHONE-TEXT refused\n'); sys.exit(1)
if name == 'am' and args == ['get-current-user']: print(state['user'])
elif name == 'am' and args == ['force-stop', NAVEE]: state['running'] = False; save()
elif name == 'monkey' and args == ['-p', NAVEE, '-c', 'android.intent.category.LAUNCHER', '1']:
    state['running'] = True; save(); print('Events injected: 1')
elif name == 'cmd' and args == ['user', 'list', '--all']: sys.stdout.write(state['users'])
elif name == 'cmd' and len(args) == 7 and args[:5] == ['package', 'list', 'packages', '-U', '--user'] and args[6] == NAVEE:
    sys.stdout.write(state['packages'].get(args[5], ''))
elif name == 'getprop' and args == ['log.tag.' + NAVEE]: print(state['property'])
elif name == 'setprop' and len(args) == 2 and args[0] == 'log.tag.' + NAVEE: state['property'] = args[1]; save()
elif name == 'date' and args == ['+%s.%N']: print(state['clock'])
elif name == 'logcat':
    uid = since = pattern = None; show_uid = False; tags = []; i = 0
    while i < len(args):
        a = args[i]
        if a.startswith('--uid='):
            if state['android'] < 12 or state.get('uid_refused'):
                sys.stderr.write("logcat: Unknown option '%s'.\n" % a); sys.exit(1)
            uid = a[len('--uid='):]
        elif a in ('-v', '-t', '-e'):
            i += 1
            if a == '-v': show_uid = show_uid or args[i] == 'uid'
            elif a == '-t': since = args[i]
            else: pattern = args[i]
        elif a not in ('-d', '-s', '*:S'): tags.append(a.split(':')[0])
        i += 1
    rows = [r for r in state['log'] if (uid is None or r[1] == uid) and (not tags or r[3] in tags)
            and (pattern is None or re.search(pattern, r[4]))]
    reading = since is not None and '.' in since
    if reading and not state.get('ignore_since'): rows = [r for r in rows if float(r[0]) >= float(since)]
    elif since is not None and not reading: rows = rows[-int(since):]
    if reading: state['reads'] = state.get('reads', 0) + 1; save()
    if reading and state.get('refuse_read'):
        # A refused read after some output: neither part may count or be shown.
        print('%s %5d %5d D %s: %s' % (rows[0][0], rows[0][2], rows[0][2], rows[0][3], rows[0][4]) if rows else 'PRIVATE-PHONE-TEXT')
        sys.stderr.write('logcat: PRIVATE-PHONE-TEXT read refused\n'); sys.exit(1)
    lines = ['--------- beginning of main']
    for r in rows:
        writer = ('%5s ' % r[1]) if show_uid and r[1] is not None else ''
        prefix = '%s %s%5d %5d D %s: ' % (r[0], writer, r[2], r[2], r[3])
        lines += [prefix + part for part in r[4].split('\n')]
    sys.stdout.buffer.write(('\n'.join(lines) + '\n').encode('utf-8'))
else:
    sys.stderr.write('fake phone: unexpected command\n'); sys.exit(64)
'''


def frame(identity='010203040506', role=0):
    data = [0x55, 0xAA, 0, 0x30, 9, 0, role] + list(bytes.fromhex(identity)) + [0]
    data += [sum(data) % 256, 0xFE, 0xFD]
    return ' '.join('%02x' % value for value in data)


def auth(writer, identity='010203040506', pid=4321, epoch='1800000001.250', role=0, tag='com.navee.ucaret'):
    return [epoch, writer, pid, tag, '[2] BleHandler.l: Send authData: [' + frame(identity, role) + ']']


# A whole NAVEE -v uid row, valid and fresh, that another app can put inside its own message.
FORGED = '1800000002.000 10123  4321  4321 D com.navee.ucaret: Send authData: [' + frame('0A0B0C0D0E0F') + ']'
# Characters that str.splitlines() or text-mode reads treat as line breaks; only "\n" makes logcat
# repeat the real writer's prefix ("\r\n" gets it after the "\n").
SEPARATORS = ('\r', '\r\n', '\x0b', '\x0c', '\x1c', '\x1d', '\x1e', '\x85', '\u2028', '\u2029')
# Writers that NAVEE's uid 10123 is a prefix of, or that contain it: another app, a work-profile app.
LONGER_UIDS = ('1012345', '101230', '110123', '1010123')


def shell(command):
    return ['-s', SERIAL, 'shell', command]


PREFLIGHT = ['am get-current-user', 'cmd package list packages -U --user 0 com.navee.ucaret', 'cmd user list --all']
CHECK = 'logcat --uid=10123 -d -t 1 >/dev/null 2>&1; echo $?'
START = ['getprop log.tag.com.navee.ucaret', 'date +%s.%N', 'setprop log.tag.com.navee.ucaret VERBOSE',
         'getprop log.tag.com.navee.ucaret', 'am force-stop com.navee.ucaret',
         'monkey -p com.navee.ucaret -c android.intent.category.LAUNCHER 1']
READ_BY_UID = "logcat --uid=10123 -d -v epoch -t 1800000000.100000000 -e 'Send authData:' -s com.navee.ucaret:D '*:S'"
READ_ROWS = "logcat -v uid -d -v epoch -t 1800000000.100000000 -e 'Send authData:' -s com.navee.ucaret:D '*:S'"
CLEANUP = ['am force-stop com.navee.ucaret', 'setprop log.tag.com.navee.ucaret INFO', 'getprop log.tag.com.navee.ucaret']
# Anything past the read-only preflight: a log change, NAVEE stopped or started, or a capture read.
STARTED = ('getprop', 'date', 'setprop', 'am force-stop', 'monkey', 'logcat -v uid -d', 'logcat --uid=10123 -d -v')


class Clock:
    """time.monotonic/time.sleep for the helper: time passes only in sleep, rounded so 5 x 0.6 is 3."""
    def __init__(self):
        self.now = 0.0

    def monotonic(self):
        return self.now

    def sleep(self, seconds):
        self.now = round(self.now + seconds, 6)


class OwnRows(unittest.TestCase):
    """The same cases as OwnerCaptureEngineTest.android11 and protocol.test.mjs."""
    own = '  1800000000.123456789 10123  4321  4321 D com.navee.ucaret: Send authData: x'

    def test_keeps_only_rows_navees_uid_wrote(self):
        self.assertEqual('  1800000000.123456789 4321  4321 D com.navee.ucaret: Send authData: x\n',
                         capture_owner.own_rows(self.own + '\r\n', NAVEE))
        # Another uid, uids sharing digits (NAVEE's a prefix of some), shell, no uid column, the divider,
        # no epoch, a report line.
        for other in ([self.own.replace('10123', '10999'), self.own.replace('10123', 'shell'),
                       self.own.replace(' 10123 ', ' '), '--------- beginning of main',
                       '10123  4321  4321 D com.navee.ucaret: Send authData: x', '~0']
                      + [self.own.replace('10123', writer) for writer in LONGER_UIDS]):
            self.assertEqual('', capture_owner.own_rows(other, NAVEE), other)

    def test_rows_without_writer_column_are_never_trusted(self):
        # Without the column, a pid equal to NAVEE's uid is still a pid, and a NAVEE prefix inside the
        # message is still message text: the writer is only the column right after the epoch.
        for row in ('1800000001.250 10123 10123 D com.navee.ucaret: Send authData: [' + frame('0A0B0C0D0E0F') + ']',
                    '1800000001.250 10123  4321 D com.navee.ucaret: Send authData: [' + frame('0A0B0C0D0E0F') + ']',
                    '1800000001.250  4321  4321 D com.navee.ucaret: x ' + FORGED,
                    '1800000001.250  4321  4321 D com.navee.ucaret: ' + FORGED[len('1800000002.000 '):]):
            self.assertEqual('', capture_owner.own_rows(row, NAVEE), row)

    def test_short_uid_names(self):
        # getpwuid names of five characters or fewer stand in for the number: uid 10005 prints as u0_a5.
        self.assertNotEqual('', capture_owner.own_rows(self.own.replace('10123', 'u0_a5'), '10005'))
        self.assertNotEqual('', capture_owner.own_rows(self.own.replace('10123', '10005'), 10005))
        self.assertEqual('', capture_owner.own_rows(self.own.replace('10123', 'u0_a5'), NAVEE))
        for bad in ('10123; id', '2000', '110123', '09999', ''):
            with self.assertRaises(ValueError):
                capture_owner.own_rows(self.own, bad)

    def test_message_separators_cannot_forge_a_row(self):
        # logcat repeats the real writer's prefix only after "\n"; every other separator is message text.
        # (Capture.test_android11_message_separators_cannot_forge_a_row sends these through adb too.)
        for separator in [s for s in SEPARATORS if '\n' not in s]:
            impostor = '1800000001.000 10999  4321  4321 D com.navee.ucaret: x' + separator + FORGED
            self.assertEqual('', capture_owner.own_rows(impostor, NAVEE), repr(separator))
            self.assertIsNone(capture_owner.parse_capture(capture_owner.own_rows(impostor, NAVEE), 1800000000))


class PhoneAnswers(unittest.TestCase):
    def test_package_uid_is_one_exact_row(self):
        self.assertEqual(NAVEE, capture_owner.package_uid('package:com.navee.ucaret uid:10123\n'))
        for listing in ('package:com.navee.ucaret uid:110123', 'package:com.navee.ucaret uid:2000',
                        'package:com.navee.ucaret uid:09999', 'package:com.navee.ucaret.evil uid:10123',
                        'package:com.navee.ucaret uid:10123 extra', 'package:com.navee.ucaret uid:10123,1010123',
                        'package:com.navee.ucaret uid:10123\npackage:com.navee.ucaret uid:10124',
                        'package:com.navee.ucaret uid:10123\npackage:com.navee.ucaret.lite uid:10200',
                        'package:com.navee.ucaret', ''):
            with self.assertRaises(capture_owner.SetupError, msg=listing):
                capture_owner.package_uid(listing)

    def test_users_and_other_profiles(self):
        self.assertEqual([0, 10], capture_owner.android_users(
            'Users:\n\tUserInfo{0:Owner:c13} running\n\tUserInfo{10:Work: profile}:1030}\n'))
        # The limits, as in protocol.js parseAndroidUsers: user ids up to 21474, 32 users, 8 KiB.
        owner = 'Users:\n\tUserInfo{0:Owner:c13}\n'
        self.assertEqual([0, 21474], capture_owner.android_users(owner + '\tUserInfo{21474:X:0}'))
        self.assertEqual(list(range(32)), capture_owner.android_users(
            'Users:\n' + ''.join('\tUserInfo{%d:U:0}\n' % n for n in range(32))))
        self.assertEqual([0], capture_owner.android_users('Users:\n\tUserInfo{0:' + 'O' * 8000 + ':c13}'))
        for listing in ('', 'Users:', 'Users:\n\tUserInfo{10:Work:1030}', owner + '\tUserInfo{0:Again:c13}',
                        'Users:\n\tUserInfo{0:Owner}', 'Error: couldn\'t get users', 'Users:\n\tUserInfo{99999:X:0}',
                        owner + '\tUserInfo{21475:X:0}', owner + '\tUserInfo{99999:X:0}',
                        'Users:\n' + ''.join('\tUserInfo{%d:U:0}\n' % n for n in range(33)),
                        'Users:\n\tUserInfo{0:' + 'O' * 8200 + ':c13}'):
            with self.assertRaises(capture_owner.SetupError, msg=listing[:80]):
                capture_owner.android_users(listing)
        self.assertFalse(capture_owner.has_navee_package(''))
        self.assertFalse(capture_owner.has_navee_package('package:com.navee.ucaret.lite uid:1010200'))
        self.assertTrue(capture_owner.has_navee_package('package:com.navee.ucaret.lite uid:1010200\npackage:com.navee.ucaret uid:1010123'))
        # The 16 KiB limit: 400 rows of 37 bytes are read, 500 refuse.
        self.assertTrue(capture_owner.has_navee_package('package:com.navee.ucaret uid:1010123\n' * 400))
        for listing in ('Error: unknown user 10', 'package:com.navee.ucaret uid:1010123\n' * 500):
            with self.assertRaises(capture_owner.SetupError, msg=listing[:80]):
                capture_owner.has_navee_package(listing)


class Capture(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='volt-capture-owner-')
        self.root = pathlib.Path(self.temp.name)
        bin_dir = self.root / 'bin'; bin_dir.mkdir()
        python = shlex.quote(sys.executable)
        self.root.joinpath('adb.py').write_text(ADB); self.root.joinpath('phone.py').write_text(PHONE)
        self.adb = self.root / 'adb'
        self.adb.write_text('#!/bin/sh\nexec %s -I -S %s %s "$@"\n' % (python, shlex.quote(str(self.root / 'adb.py')), shlex.quote(str(self.root))))
        self.adb.chmod(0o700)
        for tool in TOOLS:
            path = bin_dir / tool
            path.write_text('#!/bin/sh\nexec %s -I -S %s %s %s "$@"\n' % (
                python, shlex.quote(str(self.root / 'phone.py')), shlex.quote(str(self.root)), tool))
            path.chmod(0o700)
        self.output = self.root / 'owner.json'
        self.state = dict(android=12, user='0', property='INFO', clock=CLOCK, running=False,
                          users='Users:\n\tUserInfo{0:Owner:c13} running\n',
                          packages={'0': 'package:com.navee.ucaret uid:10123\n'}, log=[])

    def tearDown(self):
        self.temp.cleanup()

    def capture(self, **changes):
        state = dict(self.state, **changes)
        self.root.joinpath('state.json').write_text(json.dumps(state))
        self.root.joinpath('adb.log').write_text('')
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err), mock.patch.object(capture_owner, 'time', Clock()):
            code = capture_owner.main(['--adb', str(self.adb), '--output', str(self.output), '--timeout', '3'])
        self.out, self.err = out.getvalue(), err.getvalue()
        self.assertNotIn(PRIVATE, self.out + self.err)
        return code

    def calls(self):
        return [json.loads(line) for line in self.root.joinpath('adb.log').read_text().splitlines()]

    def shells(self):
        return [call[3] for call in self.calls() if len(call) == 4]

    def phone(self):
        return json.loads(self.root.joinpath('state.json').read_text())

    def saved(self):
        document = json.loads(self.output.read_text())
        return document['owner_identity'], document['role']

    def assert_refused_unchanged(self, code, message):
        """Refused in the read-only preflight: nothing on the phone changed, nothing saved."""
        self.assertEqual(1, code)
        self.assertIn(message, self.err)
        self.assertFalse(self.output.exists())
        self.assertEqual([], [s for s in self.shells() if s.startswith(STARTED)])
        self.assertEqual('INFO', self.phone()['property'])
        self.assertFalse(self.phone()['running'])

    def assert_no_identity(self, code, message):
        self.assertEqual(1, code)
        self.assertIn(message, self.err)
        self.assertFalse(self.output.exists())
        self.assert_cleaned()

    def assert_cleaned(self):
        self.assertEqual(CLEANUP, self.shells()[-3:])
        self.assertEqual('INFO', self.phone()['property'])
        self.assertFalse(self.phone()['running'])

    def test_android12_reads_by_uid(self):
        # The impostor's frame under NAVEE's tag and pid, before NAVEE's own, never leaves logd's --uid filter.
        log = [auth('10999', '0A0B0C0D0E0F', epoch='1800000001.000'), auth(NAVEE, 'ABCDEF123456', role=1)]
        self.assertEqual(0, self.capture(log=log), self.err)
        self.assertEqual(('ABCDEF123456', 1), self.saved())
        self.assertEqual([['devices']] + [shell(c) for c in PREFLIGHT + [CHECK] + START + [READ_BY_UID] + CLEANUP], self.calls())
        self.assert_cleaned()

    def test_android11_reads_rows_by_writer(self):
        # Another uid's valid frame as NAVEE's pid, and a row without the uid column, beside NAVEE's own.
        log = [auth('10999', '0A0B0C0D0E0F', epoch='1800000001.000'), auth(None, '111111111111', epoch='1800000001.100'),
               auth(NAVEE)]
        self.assertEqual(0, self.capture(android=11, log=log), self.err)
        self.assertEqual(('010203040506', 0), self.saved())
        self.assertEqual([['devices']] + [shell(c) for c in PREFLIGHT + [CHECK] + START + [READ_ROWS] + CLEANUP], self.calls())
        self.assert_cleaned()

    def test_android11_impostor_on_recycled_pid_is_never_read(self):
        # Other uids wrote valid frames as pid 4321 and exited; NAVEE, now pid 4321, wrote none. All window long.
        # Some writers start with NAVEE's uid (1012345 is a work-profile app's), so only an exact match counts.
        log = [auth(writer, '0A0B0C0D0E0F') for writer in ('10999', 'u0_a5', 'shell') + LONGER_UIDS]
        self.assert_no_identity(self.capture(android=11, log=log), 'No fresh owner identity arrived')
        self.assertEqual(5, self.phone()['reads'])
        self.assertEqual([READ_ROWS] * 5, [s for s in self.shells() if s.startswith('logcat') and s != CHECK])

    def test_rows_without_writer_column_are_never_trusted(self):
        # No column: a plain row, one whose pid equals NAVEE's uid, and one carrying a whole NAVEE row in its message.
        log = [auth(None), auth(None, pid=10123), ['1800000001.250', None, 4321, 'com.navee.ucaret', 'x ' + FORGED]]
        self.assert_no_identity(self.capture(android=11, log=log), 'No fresh owner identity arrived')
        self.assertEqual(5, self.phone()['reads'])

    def test_android11_message_separators_cannot_forge_a_row(self):
        # Another app's message holds a line-break-like character, then a whole valid NAVEE row. logcat prints
        # it as that app's row (and repeats its prefix after "\n"), and adb carries the bytes unchanged, so the
        # helper must not turn a bare "\r" into a line break the way a text-mode read would.
        impostors = [['1800000001.000', '10999', 4321, 'com.navee.ucaret', 'x' + separator + FORGED]
                     for separator in SEPARATORS]
        self.assert_no_identity(self.capture(android=11, log=impostors), 'No fresh owner identity arrived')
        self.assertEqual(5, self.phone()['reads'])
        # NAVEE's own row still counts beside them, with no conflict from the impostors' identity.
        self.assertEqual(0, self.capture(android=11, log=impostors + [auth(NAVEE)]), self.err)
        self.assertEqual(('010203040506', 0), self.saved())

    def test_stale_frames_are_never_saved(self):
        # A phone whose logcat ignores the -t time: the helper still drops rows from before its capture stamp.
        stale = [auth(NAVEE, 'ABCDEF123456', epoch='1799999999.000'), auth(NAVEE, 'ABCDEF123456', epoch='1800000000.099')]
        for android in (12, 11):
            with self.subTest(android=android):
                self.assert_no_identity(self.capture(android=android, ignore_since=True, log=stale),
                                        'No fresh owner identity arrived')
                self.assertEqual(5, self.phone()['reads'])
                self.output.unlink(missing_ok=True)
                self.assertEqual(0, self.capture(android=android, ignore_since=True, log=stale + [auth(NAVEE)]), self.err)
                self.assertEqual(('010203040506', 0), self.saved())
                self.output.unlink()

    def test_failed_uid_check_reads_rows(self):
        # A phone whose --uid check fails for any reason reads -v uid rows, never unfiltered ones.
        log = [auth('10999', '0A0B0C0D0E0F', epoch='1800000001.000'), auth(NAVEE)]
        self.assertEqual(0, self.capture(uid_refused=True, log=log), self.err)
        self.assertEqual(('010203040506', 0), self.saved())
        self.assertIn(READ_ROWS, self.shells())
        self.assertNotIn(READ_BY_UID, self.shells())

    def test_uid_check_failing_in_adb_reads_rows(self):
        # On an Android 12 phone the check itself fails before logcat answers: adb drops it, or it times out.
        log = [auth('10999', '0A0B0C0D0E0F', epoch='1800000001.000'), auth(NAVEE)]
        self.assertEqual(0, self.capture(log=log, adb_fail=[CHECK]), self.err)
        self.assertEqual(('010203040506', 0), self.saved())
        self.assertEqual(PREFLIGHT + [CHECK] + START + [READ_ROWS] + CLEANUP, self.shells())
        self.output.unlink()
        real_run = subprocess.run
        def check_times_out(command, *args, **kwargs):
            if command[-1] == CHECK:
                raise subprocess.TimeoutExpired(command, kwargs.get('timeout'))
            return real_run(command, *args, **kwargs)
        with mock.patch.object(capture_owner.subprocess, 'run', check_times_out):
            self.assertEqual(0, self.capture(log=log), self.err)
        self.assertEqual(('010203040506', 0), self.saved())
        # The timed-out check never reached the fake adb, so its log skips from the preflight to the start.
        self.assertEqual(PREFLIGHT + START + [READ_ROWS] + CLEANUP, self.shells())

    def test_navees_own_identities_must_agree(self):
        log = [auth(NAVEE, epoch='1800000001.000'), auth(NAVEE, 'ABCDEF123456')]
        self.assert_no_identity(self.capture(android=11, log=log), 'More than one owner identity')

    def test_refused_read_is_named_and_cleaned_up(self):
        # A refused read, even after printing a valid NAVEE row, is an error at once in either mode.
        for android, read in ((12, READ_BY_UID), (11, READ_ROWS)):
            with self.subTest(android=android):
                self.assert_no_identity(self.capture(android=android, refuse_read=True, log=[auth(NAVEE)]),
                                        "Couldn't read NAVEE's log")
                self.assertNotIn('No fresh owner identity', self.err)
                self.assertEqual(1, self.phone()['reads'])
                self.assertEqual(1, self.shells().count(read))

    def test_wrong_current_user(self):
        self.assert_refused_unchanged(self.capture(user='10'), 'main Android user')
        self.assertEqual([['devices'], shell('am get-current-user')], self.calls())

    def test_refused_preflight_names_the_step(self):
        # A preflight command the phone refuses (exit 1) gets that step's message, not adb's generic one.
        users = 'Users:\n\tUserInfo{0:Owner:c13} running\n\tUserInfo{10:Work:1030} running\n'
        for command, message in (('am get-current-user', 'could not be checked safely'),
                                 ('cmd package list packages -U --user 0 com.navee.ucaret', 'could not be identified'),
                                 ('cmd user list --all', 'could not be checked safely'),
                                 ('cmd package list packages -U --user 10 com.navee.ucaret', 'could not be checked safely')):
            with self.subTest(command):
                self.assert_refused_unchanged(self.capture(users=users, fail=[command]), message)
                self.assertNotIn('ADB could not complete', self.err)
                self.assertEqual(shell(command), self.calls()[-1])

    def test_navee_missing(self):
        self.assert_refused_unchanged(self.capture(packages={}), 'Install and sign into the official NAVEE app')

    def test_malformed_or_multi_row_package_list(self):
        for listing in ('package:com.navee.ucaret uid:10123\npackage:com.navee.ucaret.helper uid:10200\n',
                        'package:com.navee.ucaret uid:10123\npackage:com.navee.ucaret uid:10124\n',
                        'package:com.navee.ucaret uid:2000\n', 'package:com.navee.ucaret.evil uid:10123\n',
                        PRIVATE + '\n'):
            with self.subTest(listing=listing):
                self.assert_refused_unchanged(self.capture(packages={'0': listing}), 'could not be identified')

    def test_navee_in_another_profile(self):
        users = 'Users:\n\tUserInfo{0:Owner:c13} running\n\tUserInfo{10:%s:1030} running\n' % PRIVATE
        packages = {'0': 'package:com.navee.ucaret uid:10123\n', '10': 'package:com.navee.ucaret uid:1010123\n'}
        self.assert_refused_unchanged(self.capture(users=users, packages=packages),
                                      'also installed in another Android user or work profile')
        self.assertEqual(shell('cmd package list packages -U --user 10 com.navee.ucaret'), self.calls()[-1])
        # An unreadable user list refuses too.
        self.assert_refused_unchanged(self.capture(users=PRIVATE + '\n'), 'could not be checked safely')

    def test_another_profile_without_navee_continues(self):
        users = 'Users:\n\tUserInfo{0:Owner:c13} running\n\tUserInfo{10:Work:1030} running\n'
        packages = {'0': 'package:com.navee.ucaret uid:10123\n', '10': 'package:com.navee.ucaret.lite uid:1010200\n'}
        self.assertEqual(0, self.capture(users=users, packages=packages, log=[auth(NAVEE)]), self.err)
        self.assertEqual(PREFLIGHT + ['cmd package list packages -U --user 10 com.navee.ucaret', CHECK], self.shells()[:5])
        self.assertEqual(('010203040506', 0), self.saved())

if __name__ == '__main__':
    unittest.main()
