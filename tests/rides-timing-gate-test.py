#!/usr/bin/env python3
"""Run run-rides-timing.py --release against a fake adb with canned measurements, never a device."""
import json
import os
import pathlib
import subprocess
import sys
import tempfile
import unittest

RUNNER = pathlib.Path(__file__).resolve().parents[1] / 'android-tests' / 'run-rides-timing.py'
# Answers the runner's adb calls. FAKE_MEASURES is a JSON list of the measure runs' results, in order.
FAKE = r'''#!/usr/bin/env python3
import json, os, pathlib, sys
state = pathlib.Path(os.environ['FAKE_STATE'])
argv = sys.argv[1:]
if argv[:1] == ['-s']: argv = argv[2:]
with state.joinpath('calls').open('a') as out: out.write(json.dumps(argv) + '\n')
if argv[:3] == ['shell', 'getprop', 'ro.kernel.qemu']: print('1')
elif argv[:3] == ['shell', 'am', 'instrument']:
    if 'seed' in argv: print('INSTRUMENTATION_RESULT: seeded_bytes=1\nINSTRUMENTATION_RESULT: result=PASS')
    else:
        counter = state / 'measured'
        n = int(counter.read_text()) if counter.exists() else 0
        counter.write_text(str(n + 1))
        values = json.loads(os.environ['FAKE_MEASURES'])[n]
        for key, value in values.items(): print(f'INSTRUMENTATION_RESULT: {key}={value}')
        print('INSTRUMENTATION_RESULT: result=PASS')
'''
GOOD = {'launch_first_frame_ms': 300, 'launch_service_start_command_ms': 250, 'rides_cold_first_row_ms': 20,
        'rides_cold_longest_main_message_ms': 40, 'rides_warm_longest_main_message_ms': 30}


def run(measures, *extra):
    with tempfile.TemporaryDirectory() as folder:
        root = pathlib.Path(folder)
        adb = root / 'adb'; adb.write_text(FAKE); adb.chmod(0o755)
        env = dict(os.environ, FAKE_STATE=str(root), FAKE_MEASURES=json.dumps(measures))
        result = subprocess.run([sys.executable, '-I', str(RUNNER), '--adb', str(adb), '--serial', 'emulator-5554',
                                 '--app', 'app.apk', '--runner', 'runner.apk', '--out', str(root / 'out'),
                                 '--case', '344x882', '--fixture', '2x10', '--release', *extra],
                                env=env, capture_output=True, text=True, timeout=60)
        report = json.loads((root / 'out' / 'rides-timing.json').read_text()) if (root / 'out' / 'rides-timing.json').exists() else None
        calls = [json.loads(line) for line in (root / 'calls').read_text().splitlines()]
        return result, report, calls


def changed(**values):
    return dict(GOOD, **values)


class ReleaseGate(unittest.TestCase):
    def assertGate(self, measures, passed):
        result, report, _ = run(measures)
        self.assertEqual(result.returncode == 0, passed, result.stdout + result.stderr)
        self.assertIn('release gate: ' + ('PASS' if passed else 'FAIL'), result.stdout)
        self.assertEqual(not report['release_misses'], passed, report['release_misses'])
        return report

    def test_good_and_good_pass(self):
        self.assertGate([GOOD, GOOD], True)

    def test_best_of_two_forgives_one_slow_start(self):
        self.assertGate([changed(launch_first_frame_ms=5000), GOOD], True)

    def test_slow_and_slow_fail(self):
        report = self.assertGate([changed(rides_warm_longest_main_message_ms=4000), changed(rides_warm_longest_main_message_ms=5000)], False)
        self.assertTrue(any('best rides_warm_longest_main_message_ms=4000' in m for m in report['release_misses']))

    def test_sentinel_never_counts_as_fast(self):
        # Codex's case: -1 ("no main-thread message seen") and a 5 s stall must not pass as a best of -1.
        report = self.assertGate([changed(rides_warm_longest_main_message_ms=-1), changed(rides_warm_longest_main_message_ms=5000)], False)
        self.assertTrue(any('missing or invalid in run(s) [1]' in m for m in report['release_misses']))

    def test_sentinel_with_a_good_run_still_fails(self):
        self.assertGate([changed(rides_cold_longest_main_message_ms=-1), GOOD], False)

    def test_all_missing_fail(self):
        missing = {k: v for k, v in GOOD.items() if k != 'launch_service_start_command_ms'}
        self.assertGate([missing, missing], False)

    def test_non_numbers_are_missing(self):
        self.assertGate([changed(rides_cold_first_row_ms='n/a'), changed(rides_cold_first_row_ms='n/a')], False)

    def test_old_connect_name_is_not_the_gated_proxy(self):
        old = {('launch_connect_start_ms' if k == 'launch_service_start_command_ms' else k): v for k, v in GOOD.items()}
        self.assertGate([old, old], False)

    def test_release_takes_two_cold_starts(self):
        _, _, calls = run([GOOD, GOOD], '--repeat', '1')
        self.assertEqual(sum(1 for c in calls if c[:3] == ['shell', 'am', 'instrument'] and 'measure' in c), 2)

    def test_select_delay_reaches_the_measure_run(self):
        _, _, calls = run([GOOD, GOOD], '--select-delay-ms', '3000')
        measures = [c for c in calls if c[:3] == ['shell', 'am', 'instrument'] and 'measure' in c]
        self.assertTrue(measures and all('select_delay_ms' in c and '3000' in c for c in measures))
        _, _, calls = run([GOOD, GOOD])
        self.assertFalse(any('select_delay_ms' in c for c in calls))

    def test_refuses_a_phone(self):
        with tempfile.TemporaryDirectory() as folder:
            result = subprocess.run([sys.executable, '-I', str(RUNNER), '--adb', 'adb', '--serial', 'R5CW123', '--app', 'a',
                                     '--runner', 'r', '--out', folder, '--release'], capture_output=True, text=True, timeout=60)
        self.assertNotEqual(result.returncode, 0)


if __name__ == '__main__':
    unittest.main()
