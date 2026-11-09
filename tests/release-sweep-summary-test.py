#!/usr/bin/env python3
"""Run run-release-sweep.py against a fake emulator's adb and check its closing summary and exit code."""
import json
import os
import pathlib
import subprocess
import sys
import tempfile
import unittest

SWEEP = pathlib.Path(__file__).resolve().parents[1] / 'android-tests' / 'run-release-sweep.py'
# Answers like a settled emulator. Every instrumentation passes unless an argument contains FAKE_FAIL.
FAKE = r'''#!/usr/bin/env python3
import os, sys
argv = sys.argv[3:]
line = ' '.join(argv)
if argv[:2] == ['shell', 'getprop']: print({'ro.kernel.qemu': '1', 'sys.boot_completed': '1', 'persist.sys.locale': 'en-US', 'ro.build.version.sdk': '36'}.get(argv[2], ''))
elif argv[:2] == ['shell', 'date']: print('1000000')
elif argv[:2] == ['shell', 'pidof']: print('123')
elif argv[:2] == ['shell', 'ps']: print('10:00')
elif argv[:2] == ['shell', 'settings'] and argv[2] == 'get': print('null')
elif line.startswith('shell date'): print('11-08 00:00:00.000')
elif argv[:3] == ['shell', 'am', 'instrument']:
    bad = os.environ.get('FAKE_FAIL')
    print('INSTRUMENTATION_RESULT: result=' + ('FAIL' if bad and bad in line else 'PASS'))
'''
GATING, OTHER_GATING = 'dashboard-slots', 'capacity-widget'
GATING_FAILS = 'DashboardSlotsValidation'
REPORT = 'locale-es-344'
PSEUDO = 'locale-en-XA-344'


class Summary(unittest.TestCase):
    def setUp(self):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        self.root = pathlib.Path(folder.name)
        self.adb = self.root / 'adb'
        self.adb.write_text(FAKE)
        self.adb.chmod(0o755)
        for name in ('app.apk', 'runner.apk'): (self.root / name).write_bytes(b'')

    def sweep(self, *only, fail=None):
        env = dict(os.environ)
        if fail: env['FAKE_FAIL'] = fail
        command = [sys.executable, str(SWEEP), '--adb', str(self.adb), '--serial', 'emulator-5554', '--app', str(self.root / 'app.apk'),
                   '--runner', str(self.root / 'runner.apk'), '--out', str(self.root / 'out')]
        for name in only: command += ['--only', name]
        done = subprocess.run(command, env=env, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=120)
        self.assertTrue((self.root / 'out' / 'summary.json').exists(), done.stdout + done.stderr)
        rows = {row['step']: row for row in json.loads((self.root / 'out' / 'summary.json').read_text())}
        return done.returncode, done.stdout.splitlines(), rows

    def test_all_pass(self):
        code, lines, rows = self.sweep(GATING, OTHER_GATING, REPORT)
        self.assertEqual(code, 0)
        self.assertIn('Gating steps: 2 passed, 0 failed', lines)
        self.assertIn('Report-only steps (never counted as passes): 1 ran, 0 did not finish, 0 skipped', lines)
        self.assertEqual(rows[REPORT]['status'], 'reported')
        self.assertNotIn('passed', rows[REPORT])
        self.assertIs(rows[GATING]['passed'], True)

    def test_gating_failure_fails_the_run_and_is_named(self):
        code, lines, rows = self.sweep(GATING, OTHER_GATING, REPORT, fail=GATING_FAILS)
        self.assertEqual(code, 1)
        self.assertIn(f'Gating steps: 1 passed, 1 failed: {GATING}', lines)
        self.assertIn('Report-only steps (never counted as passes): 1 ran, 0 did not finish, 0 skipped', lines)
        self.assertEqual(rows[GATING]['status'], 'failed')
        self.assertIs(rows[GATING]['passed'], False)

    def test_skipped_report_is_not_a_pass(self):
        code, lines, rows = self.sweep(GATING, PSEUDO)
        self.assertEqual(code, 0)
        self.assertIn('Gating steps: 1 passed, 0 failed', lines)
        self.assertIn(f'Report-only steps (never counted as passes): 0 ran, 0 did not finish, 1 skipped; skipped: {PSEUDO}', lines)
        self.assertTrue(any(line.startswith(f'{PSEUDO}: SKIPPED skipped:') for line in lines), lines)
        self.assertEqual(rows[PSEUDO]['status'], 'skipped')
        self.assertNotIn('passed', rows[PSEUDO])
        self.assertFalse(any('steps passed' in line for line in lines), lines)

    def test_report_only_failure_does_not_fail_the_run(self):
        code, lines, rows = self.sweep(GATING, REPORT, fail='es-344')
        self.assertEqual(code, 0)
        self.assertIn('Gating steps: 1 passed, 0 failed', lines)
        self.assertIn(f'Report-only steps (never counted as passes): 0 ran, 1 did not finish, 0 skipped; did not finish: {REPORT}', lines)
        self.assertEqual(rows[REPORT]['status'], 'did-not-finish')
        self.assertNotIn('passed', rows[REPORT])


if __name__ == '__main__': unittest.main()
