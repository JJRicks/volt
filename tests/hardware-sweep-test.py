#!/usr/bin/env python3
"""Run run-hardware-sweep.py against a recording fake adb, never a phone."""
import json
import os
import pathlib
import subprocess
import sys
import tempfile
import unittest

SWEEP = pathlib.Path(__file__).resolve().parents[1] / 'android-tests' / 'run-hardware-sweep.py'
# Prints canned device answers and logs every call. FAKE_PLAN maps a ParkedValidation mode to pass,
# fail, notime or unrestored; FAKE_CHARGING is the snapshot's charging_state; FAKE_QEMU, FAKE_LOCKED and
# FAKE_SIGNATURE simulate an emulator, a locked phone and a runner signed with another key.
FAKE = r'''#!/usr/bin/env python3
import json, os, pathlib, sys
state = pathlib.Path(os.environ['FAKE_STATE'])
argv = sys.argv[1:]
if argv[:1] == ['-s']: argv = argv[2:]
with state.joinpath('calls').open('a') as out: out.write(json.dumps(argv) + '\n')
plan = json.loads(os.environ.get('FAKE_PLAN', '{}'))
if argv[:3] == ['shell', 'getprop', 'ro.kernel.qemu']: print(os.environ.get('FAKE_QEMU', ''))
elif argv[:3] == ['shell', 'pm', 'list']: print('package:com.jjricks.volt')
elif argv[:2] == ['shell', 'dumpsys'] and argv[2] == 'power': print('mWakefulness=Awake')
elif argv[:2] == ['shell', 'dumpsys'] and argv[2] == 'window': print('isKeyguardShowing=' + os.environ.get('FAKE_LOCKED', 'false'))
elif argv[:2] == ['shell', 'dumpsys'] and argv[2] == 'package': print('versionName=1.10.0')
elif argv[:1] == ['install']: print('Success')
elif argv[:3] == ['shell', 'am', 'instrument']:
    mode = argv[argv.index('mode') + 1]
    state.joinpath('mode').write_text(mode)
    if os.environ.get('FAKE_SIGNATURE') == 'other':
        print('java.lang.SecurityException: Permission Denial: starting instrumentation ComponentInfo{com.jjricks.volt.tests} not signed')
        sys.exit(1)
    outcome = plan.get(mode, 'pass')
    if mode == 'snapshot': print('INSTRUMENTATION_RESULT: charging_state=' + os.environ.get('FAKE_CHARGING', '1.0'))
    if outcome == 'fail': print('INSTRUMENTATION_RESULT: error=IllegalStateException: Change declined'); print('INSTRUMENTATION_CODE: 1')
    elif outcome == 'notime': print('INSTRUMENTATION_RESULT: error=IllegalStateException: No restorable stored charging timestamp'); print('INSTRUMENTATION_CODE: 1')
    else: print('INSTRUMENTATION_RESULT: result=Saved'); print('INSTRUMENTATION_CODE: 0')
elif argv[:1] == ['pull']:
    mode = state.joinpath('mode').read_text() if state.joinpath('mode').exists() else ''
    if mode == 'sweep':
        folder = pathlib.Path(argv[2]) / 'validation'; folder.mkdir(parents=True, exist_ok=True)
        restored = plan.get('sweep') != 'unrestored'
        rows = [dict(key='headlight', before=0, test=1, after=1, restored=restored, outcome='Change and restoration confirmed'),
                dict(key='maximum_speed', outcome='Excluded by the test plan; no write')]
        folder.joinpath('control-validation.json').write_text(json.dumps(dict(results=rows)))
'''


class HardwareSweep(unittest.TestCase):
    def setUp(self):
        self.dir = pathlib.Path(tempfile.mkdtemp())
        self.adb = self.dir / 'adb'; self.adb.write_text(FAKE); self.adb.chmod(0o755)
        self.runner = self.dir / 'runner.apk'; self.runner.write_bytes(b'apk')
        self.out = self.dir / 'out'

    def run_sweep(self, *extra, serial='57181FDCG0018E', parked=True, **env):
        command = [sys.executable, '-I', str(SWEEP), '--adb', str(self.adb), '--serial', serial, '--runner', str(self.runner),
                   '--out', str(self.out)] + (['--parked'] if parked else []) + list(extra)
        environ = dict(os.environ, FAKE_STATE=str(self.dir), **env)
        return subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, env=environ, timeout=60)

    def calls(self):
        path = self.dir / 'calls'
        return [json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []

    def modes(self):
        return [c[c.index('mode') + 1] for c in self.calls() if c[:3] == ['shell', 'am', 'instrument']]

    def summary(self):
        return {s['step']: s['status'] for s in json.loads((self.out / 'summary.json').read_text())['steps']}

    def test_refuses_emulators_and_missing_parked_confirmation(self):
        result = self.run_sweep(serial='emulator-5554')
        self.assertNotEqual(result.returncode, 0); self.assertIn('physical phone', result.stdout); self.assertEqual(self.calls(), [])
        result = self.run_sweep(parked=False)
        self.assertNotEqual(result.returncode, 0); self.assertIn('--parked is required', result.stdout); self.assertEqual(self.calls(), [])
        result = self.run_sweep(FAKE_QEMU='1')
        self.assertNotEqual(result.returncode, 0); self.assertIn('This is an emulator', result.stdout)
        self.assertNotIn('instrument', json.dumps(self.calls()))

    def test_refuses_a_locked_phone_before_installing_anything(self):
        result = self.run_sweep(FAKE_LOCKED='true')
        self.assertNotEqual(result.returncode, 0); self.assertIn('Unlock the phone', result.stdout)
        self.assertFalse(any(c[:1] == ['install'] for c in self.calls()))

    def test_default_plan_and_volt_is_never_installed_or_removed(self):
        result = self.run_sweep()
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertEqual(self.modes(), ['snapshot', 'navigation', 'background', 'clock', 'sweep', 'time', 'schedule'])
        sweep = next(c for c in self.calls() if c[:3] == ['shell', 'am', 'instrument'] and 'sweep' in c)
        self.assertEqual(sweep[sweep.index('exclude') + 1].split(','), ['maximum_speed', 'speed_limit', 'drive_speed_limit', 'dual_drive', 'timed_charging'])
        installs = [c for c in self.calls() if c[:1] in (['install'], ['uninstall'])]
        self.assertEqual(installs[0][:1], ['install']); self.assertTrue(installs[0][-1].endswith('runner.apk'))
        self.assertEqual(installs[1:], [['uninstall', 'com.jjricks.volt.tests']], 'only the runner is installed and removed')
        for call in self.calls():
            if call[:2] == ['shell', 'rm']: self.assertEqual(call[2:], ['-rf', '/sdcard/Android/data/com.jjricks.volt/files/validation'])
            self.assertNotIn('pm clear', ' '.join(call))
        report = (self.out / 'report.md').read_text()
        self.assertIn('| headlight | 0 | 1 | 1 | Change and restoration confirmed |', report); self.assertNotIn('NOT RESTORED', report)

    def test_opt_in_steps_run_only_when_named(self):
        self.run_sweep('--include', 'lock')
        self.assertIn('lock', self.modes()); self.assertNotIn('pin', self.modes())

    def test_schedule_test_needs_a_charge_in_progress(self):
        result = self.run_sweep(FAKE_CHARGING='0.0')
        self.assertEqual(result.returncode, 0, result.stdout); self.assertNotIn('schedule', self.modes())
        self.assertEqual(self.summary()['schedule-test'], 'SKIP')

    def test_a_failed_write_skips_later_writes(self):
        result = self.run_sweep('--include', 'lock', FAKE_PLAN=json.dumps(dict(sweep='fail')))
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.modes(), ['snapshot', 'navigation', 'background', 'clock', 'sweep'])
        summary = self.summary()
        self.assertEqual((summary['settings'], summary['charge-time'], summary['schedule-test'], summary['lock']), ('FAIL', 'SKIP', 'SKIP', 'SKIP'))
        self.assertEqual(self.calls()[-1], ['uninstall', 'com.jjricks.volt.tests'], 'the runner is removed even after a failure')

    def test_missing_charge_time_is_a_skip_not_a_failure(self):
        result = self.run_sweep(FAKE_PLAN=json.dumps(dict(time='notime')))
        self.assertEqual(result.returncode, 0, result.stdout); self.assertEqual(self.summary()['charge-time'], 'SKIP')
        self.assertIn('schedule', self.modes(), 'a skipped step does not stop later writes')

    def test_an_unrestored_setting_is_flagged_and_fails(self):
        result = self.run_sweep(FAKE_PLAN=json.dumps(dict(sweep='unrestored')))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('**NOT RESTORED: headlight.', (self.out / 'report.md').read_text())

    def test_a_runner_signed_with_another_key_is_explained(self):
        result = self.run_sweep('--only', 'snapshot', FAKE_SIGNATURE='other')
        self.assertNotEqual(result.returncode, 0); self.assertIn('not signed with the installed Volt key', result.stdout)


if __name__ == '__main__':
    unittest.main()
