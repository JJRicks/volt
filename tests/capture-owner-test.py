#!/usr/bin/env python3
"""Run setup/capture-owner.py against a fake adb and fake phone tools, never a real phone.

The fake adb runs each `adb shell` string with the real /bin/sh, so quoting, the
device-side redirect of the --uid check and exit statuses behave as on a phone. Its
PATH holds only the fake tools: am, cmd, monkey, getprop, setprop, date and logcat.
The helper's polling clock advances only when it sleeps, so a 3-second capture is
always five reads, each allowed at least 0.6 real seconds.
"""
import contextlib
import importlib.util
import io
import json
import pathlib
import shlex
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
ADB = r'''
import json, pathlib, subprocess, sys
root = pathlib.Path(sys.argv[1]); argv = sys.argv[2:]
with root.joinpath('adb.log').open('a') as out: out.write(json.dumps(argv) + '\n')
if argv == ['devices']:
    print('List of devices attached\n' + 'FAKE0001\tdevice\n'); sys.exit(0)
if argv[:3] != ['-s', 'FAKE0001', 'shell'] or len(argv) != 4:
    sys.stderr.write('fake adb: unexpected call\n'); sys.exit(99)
sys.stdout.flush()
sys.exit(subprocess.run(['/bin/sh', '-c', argv[3]], env={'PATH': str(root / 'bin')}).returncode)
'''
# The phone's tools, sharing state.json. logcat honours --uid (Android 12+ only), -v uid, -t (a
# time, or a row count), -e and the NAVEE tag filter. Each log row is [epoch, writer, pid, tag,
# message]; a null writer is a row printed without the uid column.
PHONE = r'''
import json, pathlib, re, sys
root = pathlib.Path(sys.argv[1]); name = sys.argv[2]; args = sys.argv[3:]
path = root / 'state.json'; state = json.loads(path.read_text())
def save(): path.write_text(json.dumps(state))
NAVEE = 'com.navee.ucaret'
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
    if since is not None and '.' in since: rows = [r for r in rows if float(r[0]) >= float(since)]
    elif since is not None: rows = rows[-int(since):]
    reading = since is not None and '.' in since
    if reading: state['reads'] = state.get('reads', 0) + 1; save()
    if reading and state.get('refuse_read'):
        # A refused read after some output: neither part may count or be shown.
        print('%s %5d %5d D %s: %s' % (rows[0][0], rows[0][2], rows[0][2], rows[0][3], rows[0][4]) if rows else PRIVATE)
        sys.stderr.write('logcat: ' + PRIVATE + ' read refused\n'); sys.exit(1)
    print('--------- beginning of main')
    for r in rows:
        writer = ('%5s ' % r[1]) if show_uid and r[1] is not None else ''
        print('%s %s%5d %5d D %s: %s' % (r[0], writer, r[2], r[2], r[3], r[4]))
else:
    sys.stderr.write('fake phone: unexpected command\n'); sys.exit(64)
'''


def frame(identity='010203040506', role=0):
    data = [0x55, 0xAA, 0, 0x30, 9, 0, role] + list(bytes.fromhex(identity)) + [0]
    data += [sum(data) % 256, 0xFE, 0xFD]
    return ' '.join('%02x' % value for value in data)


def auth(writer, identity='010203040506', pid=4321, epoch='1800000001.250', role=0, tag='com.navee.ucaret'):
    return [epoch, writer, pid, tag, '[2] BleHandler.l: Send authData: [' + frame(identity, role) + ']']


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
        # Another uid, a uid sharing digits, shell, no uid column, the divider, no epoch, a report line.
        for other in (self.own.replace('10123', '10999'), self.own.replace('10123', '110123'),
                      self.own.replace('10123', 'shell'), self.own.replace(' 10123 ', ' '),
                      '--------- beginning of main', '10123  4321  4321 D com.navee.ucaret: Send authData: x', '~0'):
            self.assertEqual('', capture_owner.own_rows(other, NAVEE), other)

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
        fake = '1800000002.000 10123  4321  4321 D com.navee.ucaret: Send authData: [' + frame('0A0B0C0D0E0F') + ']'
        for separator in ('\r', '\x0b', '\x0c', '\x1c', '\x1d', '\x1e', '\x85', ' ', ' '):
            impostor = '1800000001.000 10999  4321  4321 D com.navee.ucaret: x' + separator + fake
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
        for listing in ('', 'Users:', 'Users:\n\tUserInfo{10:Work:1030}', 'Users:\n\tUserInfo{0:Owner:c13}\n\tUserInfo{0:Again:c13}',
                        'Users:\n\tUserInfo{0:Owner}', 'Error: couldn\'t get users', 'Users:\n\tUserInfo{99999:X:0}',
                        'Users:\n' + ''.join('\tUserInfo{%d:U:0}\n' % n for n in range(33))):
            with self.assertRaises(capture_owner.SetupError, msg=listing):
                capture_owner.android_users(listing)
        self.assertFalse(capture_owner.has_navee_package(''))
        self.assertFalse(capture_owner.has_navee_package('package:com.navee.ucaret.lite uid:1010200'))
        self.assertTrue(capture_owner.has_navee_package('package:com.navee.ucaret.lite uid:1010200\npackage:com.navee.ucaret uid:1010123'))
        with self.assertRaises(capture_owner.SetupError):
            capture_owner.has_navee_package('Error: unknown user 10')


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
        log = [auth('10999', '0A0B0C0D0E0F'), auth('u0_a5', '0A0B0C0D0E0F'), auth('shell', '0A0B0C0D0E0F'),
               auth('1010123', '0A0B0C0D0E0F')]
        self.assert_no_identity(self.capture(android=11, log=log), 'No fresh owner identity arrived')
        self.assertEqual(5, self.phone()['reads'])
        self.assertEqual([READ_ROWS] * 5, [s for s in self.shells() if s.startswith('logcat') and s != CHECK])

    def test_rows_without_writer_column_are_never_trusted(self):
        self.assert_no_identity(self.capture(android=11, log=[auth(None)]), 'No fresh owner identity arrived')
        self.assertEqual(5, self.phone()['reads'])

    def test_failed_uid_check_reads_rows(self):
        # A phone whose --uid check fails for any reason reads -v uid rows, never unfiltered ones.
        log = [auth('10999', '0A0B0C0D0E0F', epoch='1800000001.000'), auth(NAVEE)]
        self.assertEqual(0, self.capture(uid_refused=True, log=log), self.err)
        self.assertEqual(('010203040506', 0), self.saved())
        self.assertIn(READ_ROWS, self.shells())
        self.assertNotIn(READ_BY_UID, self.shells())

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
