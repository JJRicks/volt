#!/usr/bin/env python3
"""Run run-release-sweep.py against a fake emulator's adb and check its closing summary, exit code and the APK identity
every summary.json record carries; then --merge over synthesized run folders of a split sweep."""
raise SystemExit('injected failure (CI check, reverted next commit)')
import hashlib
import json
import os
import pathlib
import subprocess
import sys
import tempfile
import unittest
import zipfile

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
elif argv[0] == 'install':
    # FAKE_INSTALLS logs each installed path and its bytes' SHA-256; FAKE_REBUILD's files (os.pathsep between) are rewritten after the first install.
    import hashlib, pathlib
    with open(os.environ.get('FAKE_INSTALLS', os.devnull), 'a') as log: log.write(f'{argv[-1]} {hashlib.sha256(pathlib.Path(argv[-1]).read_bytes()).hexdigest()}\n')
    for rebuilt in filter(None, os.environ.get('FAKE_REBUILD', '').split(os.pathsep)): pathlib.Path(rebuilt).write_bytes(b'rebuilt mid-sweep')
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
        for name in ('app.apk', 'runner.apk'): (self.root / name).write_bytes(name.encode())

    def sweep(self, *only, fail=None, pseudo=None, **fake):
        env = dict(os.environ, **fake)
        if fail: env['FAKE_FAIL'] = fail
        command = [sys.executable, str(SWEEP), '--adb', str(self.adb), '--serial', 'emulator-5554', '--app', str(self.root / 'app.apk'),
                   '--runner', str(self.root / 'runner.apk'), '--out', str(self.root / 'out')]
        for name in only: command += ['--only', name]
        if pseudo: command += ['--pseudo-app', str(pseudo)]
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

    def test_every_record_names_the_apks_and_the_start(self):
        _, _, rows = self.sweep(GATING, REPORT)
        for row in rows.values():
            self.assertEqual(row['app_sha256'], hashlib.sha256(b'app.apk').hexdigest())
            self.assertEqual(row['runner_sha256'], hashlib.sha256(b'runner.apk').hexdigest())
            self.assertIsInstance(row['sweep_started'], float)
        self.assertEqual(rows[GATING]['sweep_started'], rows[REPORT]['sweep_started'])
        self.assertNotIn('pseudo_app_sha256', rows[GATING], 'only with --pseudo-app')
        self.assertFalse((self.root / 'out' / 'census').exists(), 'identity is recorded without --census')

    def test_steps_install_the_runs_own_copies(self):
        # The app and the pseudo-locale APK share a classes.dex (the sweep checks that) and differ otherwise.
        app, pseudo, runner = self.root / 'app.apk', self.root / 'pseudo.apk', self.root / 'runner.apk'
        for path, extra in ((app, b'en'), (pseudo, b'en-XA')):
            with zipfile.ZipFile(path, 'w') as archive:
                archive.writestr('classes.dex', b'dex'); archive.writestr('resources.arsc', extra)
        original = {name: hashlib.sha256(path.read_bytes()).hexdigest() for name, path in (('app', app), ('runner', runner), ('pseudo', pseudo))}
        installs = self.root / 'installs.txt'
        _, _, rows = self.sweep(GATING, PSEUDO, pseudo=pseudo, FAKE_INSTALLS=str(installs), FAKE_REBUILD=os.pathsep.join(map(str, (app, runner, pseudo))))
        for path in (app, runner, pseudo): self.assertEqual(path.read_bytes(), b'rebuilt mid-sweep', 'the originals were replaced after the first install')
        copies = self.root / 'out' / 'apks'
        expected = {str((copies / f'{name}.apk').resolve()): digest for name, digest in original.items()}
        logged = [line.split() for line in installs.read_text().splitlines()]
        self.assertEqual(sorted(path for path, _ in logged), sorted([str((copies / 'runner.apk').resolve())] * 2 + [
            str((copies / 'app.apk').resolve()), str((copies / 'pseudo.apk').resolve())]), 'two steps, the copies only')
        for path, digest in logged: self.assertEqual(expected.get(path), digest, path)
        self.assertEqual(rows[PSEUDO]['status'], 'reported', 'the pseudo step ran')
        for row in rows.values():
            self.assertEqual((row['app_sha256'], row['runner_sha256'], row['pseudo_app_sha256']), (original['app'], original['runner'], original['pseudo']))

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



APP, RUNNER, OTHER = 'a' * 64, 'b' * 64, 'c' * 64
PSEUDO_APK, OTHER_PSEUDO_APK = 'd' * 64, 'e' * 64


class Merge(unittest.TestCase):
    """--merge over synthesized run folders, laid out as a run writes them: summary.json and <step>.txt."""

    @classmethod
    def setUpClass(cls):
        listed = subprocess.run([sys.executable, str(SWEEP), '--list'], text=True, stdout=subprocess.PIPE, check=True, timeout=60).stdout
        cls.steps = [line for line in listed.splitlines() if not line.startswith('(')]
        # The pseudo-locale steps: skipped in a run without --pseudo-app, as most runs of a split sweep are.
        cls.pseudo = [step for step in cls.steps if step.startswith(('locale-en-XA-', 'locale-ar-XB-'))]
        cls.gating = len(cls.steps) - 1 - len(cls.pseudo)

    def setUp(self):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        self.root = pathlib.Path(folder.name)

    def run_folder(self, name, started, statuses, app=APP, runner=RUNNER, outputs=(), identity=True, pseudo=None):
        """statuses: {step: status}; a status of reported, did-not-finish or skipped is a report-only record.
        pseudo: the run's pseudo_app_sha256 (given with --pseudo-app), which a run puts on every record."""
        folder = self.root / name
        folder.mkdir()
        rows = []
        for step, status in statuses.items():
            row = dict(step=step, status=status, seconds=1)
            if status in ('passed', 'failed'): row['passed'] = status == 'passed'
            else: row['report_only'] = True
            if identity: row.update(app_sha256=app, runner_sha256=runner, sweep_started=started)
            if pseudo: row['pseudo_app_sha256'] = pseudo
            rows.append(row)
            (folder / f'{step}.txt').write_text('INSTRUMENTATION_RESULT: result=PASS\n')
        for step in outputs: (folder / f'{step}.txt').write_text('cut off\n')
        (folder / 'summary.json').write_text(json.dumps(rows))
        return folder

    def everything(self, **overrides):
        statuses = {step: 'skipped' if step in self.pseudo else 'passed' for step in self.steps}
        statuses.update({REPORT: 'reported'}, **overrides)
        return statuses

    def merge(self, *folders):
        command = [sys.executable, str(SWEEP), '--merge', '--out', str(self.root / 'merged')] + [str(f) for f in folders]
        done = subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=60)
        merged = self.root / 'merged' / 'summary.json'
        rows = {row['step']: row for row in json.loads(merged.read_text())} if merged.exists() else None
        return done.returncode, done.stdout.splitlines(), done.stderr, rows

    def split(self, statuses):
        """The steps cut in two halves, one run folder each."""
        names = list(statuses)
        half = len(names) // 2
        return {n: statuses[n] for n in names[:half]}, {n: statuses[n] for n in names[half:]}

    def test_halves_merge_into_one_verdict_per_step(self):
        first, second = self.split(self.everything())
        code, lines, _, rows = self.merge(self.run_folder('two', 2000.0, second), self.run_folder('one', 1000.0, first))
        self.assertEqual(code, 0, lines)
        self.assertEqual(len(rows), len(self.steps))
        self.assertEqual(lines[-2:], [f'Gating steps: {self.gating} passed, 0 failed',
                                      f'Report-only steps (never counted as passes): 1 ran, 0 did not finish, {len(self.pseudo)} skipped; '
                                      'skipped: ' + ', '.join(self.pseudo)])
        self.assertFalse(any('pseudo-locale APK' in line for line in lines), 'no pseudo-locale step ran')
        self.assertTrue(lines[1].startswith(f'  run 1: {self.root / "one"}, started 1970-01-01 00:16:40 UTC'), lines[:3])
        self.assertEqual(rows[self.steps[0]]['run'], 1)
        self.assertEqual(rows[self.steps[-1]]['run'], 2)
        self.assertEqual((self.root / 'merged' / 'merge.txt').read_text().splitlines(), lines)

    def test_a_rerun_replaces_a_failure_and_every_try_is_listed(self):
        first, second = self.split(self.everything(**{GATING: 'failed', OTHER_GATING: 'passed'}))
        first.update({GATING: 'failed', OTHER_GATING: 'passed'}); second.pop(GATING, None); second.pop(OTHER_GATING, None)
        rerun = self.run_folder('rerun', 3000.0, {GATING: 'passed', OTHER_GATING: 'failed'})
        code, lines, _, rows = self.merge(rerun, self.run_folder('one', 1000.0, first), self.run_folder('two', 2000.0, second))
        self.assertEqual(code, 1, lines)
        self.assertEqual(rows[GATING]['status'], 'passed')
        self.assertEqual([t['status'] for t in rows[GATING]['tries']], ['failed', 'passed'])
        self.assertIn(f'{GATING}: PASS (run 3); tries: run 1 FAIL, run 3 PASS', lines)
        # The latest verdict wins either way: a later failure replaces an earlier pass.
        self.assertIn(f'{OTHER_GATING}: FAIL (run 3); tries: run 1 PASS, run 3 FAIL', lines)
        self.assertIn(f'Gating steps: {self.gating - 1} passed, 1 failed: {OTHER_GATING}', lines)
        self.assertTrue(any(line.startswith('Verdicts that changed between tries') and GATING in line and OTHER_GATING in line for line in lines))

    def test_a_later_skip_does_not_replace_a_run(self):
        first, second = self.split(self.everything(**{PSEUDO: 'reported'}))
        code, lines, _, rows = self.merge(self.run_folder('one', 1000.0, first, pseudo=PSEUDO_APK), self.run_folder('two', 2000.0, second, pseudo=PSEUDO_APK),
                                          self.run_folder('three', 3000.0, {PSEUDO: 'skipped'}))
        self.assertEqual(code, 0, lines)
        self.assertEqual(rows[PSEUDO]['status'], 'reported')
        self.assertIn(f'Report-only steps (never counted as passes): 2 ran, 0 did not finish, {len(self.pseudo) - 1} skipped; skipped: '
                      + ', '.join(step for step in self.pseudo if step != PSEUDO), lines)

    def test_a_step_without_a_verdict_is_named(self):
        statuses = self.everything()
        del statuses[OTHER_GATING]
        first, second = self.split(statuses)
        code, lines, _, rows = self.merge(self.run_folder('one', 1000.0, first),
                                          self.run_folder('two', 2000.0, second, outputs=[OTHER_GATING]))
        self.assertEqual(code, 3, lines)
        self.assertEqual(rows[OTHER_GATING]['status'], 'no-verdict')
        self.assertEqual(rows[OTHER_GATING]['tries'], [dict(run=2, folder=str(self.root / 'two'), status='no-verdict')])
        self.assertIn(f'{OTHER_GATING}: NO VERDICT; tries: run 2 NO VERDICT', lines)
        self.assertEqual(lines[-1], f'Without a verdict in any run (neither a pass nor a fail): 1: {OTHER_GATING}')
        self.assertIn(f'Gating steps: {self.gating - 1} passed, 0 failed', lines)

    def test_mixed_apks_are_refused(self):
        for key in ('app', 'runner'):
            with self.subTest(key):
                self.setUp()
                first, second = self.split(self.everything())
                code, lines, error, rows = self.merge(self.run_folder('one', 1000.0, first),
                                                      self.run_folder('two', 2000.0, second, **{key: OTHER}))
                self.assertEqual(code, 2)
                self.assertIn(f'Refusing to merge: the runs used different {key}', error)
                self.assertIn(OTHER, error)
                self.assertEqual(lines, [])
                self.assertIsNone(rows, 'nothing is written')

    def pseudo_runs(self, *pseudo_apks):
        """The steps without the pseudo-locale ones in run 1 (no --pseudo-app), then one run per pseudo_apks entry that reruns
        the pseudo-locale steps with that pseudo_app_sha256 (None: none recorded)."""
        folders = [self.run_folder('main', 1000.0, {step: status for step, status in self.everything().items() if step not in self.pseudo})]
        for number, apk in enumerate(pseudo_apks, 2):
            statuses = {step: 'reported' for step in self.pseudo}
            statuses[self.pseudo[-1]] = 'did-not-finish'
            folders.append(self.run_folder(f'pseudo{number}', 1000.0 * number, statuses, pseudo=apk))
        return folders

    def test_one_pseudo_apk_is_named(self):
        code, lines, _, rows = self.merge(*self.pseudo_runs(PSEUDO_APK, PSEUDO_APK))
        self.assertEqual(code, 0, lines)
        self.assertIn(f'Pseudo-locale steps ran with one pseudo-locale APK: SHA-256 {PSEUDO_APK} (runs 2, 3).', lines)
        self.assertEqual(lines[-2], f'Gating steps: {self.gating} passed, 0 failed')
        self.assertEqual(rows[PSEUDO]['pseudo_app_sha256'], PSEUDO_APK)

    def test_mixed_pseudo_apks_are_noted_not_refused(self):
        # Report-only steps: the gating verdicts still merge, and the note names each pseudo-locale APK and its runs.
        code, lines, error, rows = self.merge(*self.pseudo_runs(PSEUDO_APK, OTHER_PSEUDO_APK, PSEUDO_APK))
        self.assertEqual(code, 0, lines + [error])
        note = [line for line in lines if 'pseudo-locale APK' in line]
        self.assertEqual(note, [f'Note: the pseudo-locale steps (report only, so no gating effect) did not all run with one recorded pseudo-locale APK: '
                                f'SHA-256 {PSEUDO_APK} in runs 2, 4; SHA-256 {OTHER_PSEUDO_APK} in run 3. Each pseudo-locale record keeps '
                                'its own run\'s pseudo_app_sha256; record this in VERIFICATION.txt.'])
        self.assertEqual(lines[-2:], [f'Gating steps: {self.gating} passed, 0 failed',
                                      f'Report-only steps (never counted as passes): {len(self.pseudo)} ran, 1 did not finish, 0 skipped'
                                      f'; did not finish: {self.pseudo[-1]}'])
        self.assertEqual(rows[PSEUDO]['run'], 4)
        self.assertEqual(rows[PSEUDO]['pseudo_app_sha256'], PSEUDO_APK)
        self.assertEqual((self.root / 'merged' / 'merge.txt').read_text().splitlines(), lines)

    def test_a_pseudo_run_without_its_apk_recorded_is_noted(self):
        code, lines, _, _ = self.merge(*self.pseudo_runs(PSEUDO_APK, None))
        self.assertEqual(code, 0, lines)
        self.assertIn(f'Note: the pseudo-locale steps (report only, so no gating effect) did not all run with one recorded pseudo-locale APK: '
                      f'SHA-256 {PSEUDO_APK} in run 2; no SHA-256 recorded in run 3. Each pseudo-locale record keeps '
                      'its own run\'s pseudo_app_sha256; record this in VERIFICATION.txt.', lines)

    def test_pseudo_steps_without_any_apk_recorded_are_noted(self):
        code, lines, _, _ = self.merge(*self.pseudo_runs(None))
        self.assertEqual(code, 0, lines)
        self.assertIn('Note: the pseudo-locale steps (report only, so no gating effect) did not all run with one recorded pseudo-locale APK: '
                      'no SHA-256 recorded in run 2. Each pseudo-locale record keeps its own run\'s pseudo_app_sha256; record this in VERIFICATION.txt.', lines)

    def test_a_pseudo_apk_only_counts_where_pseudo_steps_ran(self):
        # Run 1 was given --pseudo-app but ran only gating steps; run 2 skipped the pseudo-locale steps (no --pseudo-app).
        statuses = {step: status for step, status in self.everything().items() if step not in self.pseudo}
        first, second = self.split(statuses)
        skipped = dict(second, **{step: 'skipped' for step in self.pseudo})
        code, lines, _, _ = self.merge(self.run_folder('one', 1000.0, first, pseudo=OTHER_PSEUDO_APK), self.run_folder('two', 2000.0, skipped),
                                       self.run_folder('three', 3000.0, {PSEUDO: 'reported'}, pseudo=PSEUDO_APK))
        self.assertEqual(code, 0, lines)
        self.assertIn(f'Pseudo-locale steps ran with one pseudo-locale APK: SHA-256 {PSEUDO_APK} (run 3).', lines)
        self.assertFalse(any(OTHER_PSEUDO_APK in line for line in lines), lines)

    def test_runs_without_identity_are_refused(self):
        first, second = self.split(self.everything())
        old = self.run_folder('old', 1000.0, first, identity=False)
        code, _, error, rows = self.merge(old, self.run_folder('new', 2000.0, second))
        self.assertEqual(code, 2)
        self.assertIn(f'{old / "summary.json"} has no app and runner SHA-256', error)
        self.assertIsNone(rows)
        (old / 'summary.json').unlink()
        code, _, error, _ = self.merge(old)
        self.assertEqual(code, 2)
        self.assertIn(f'{old} has no summary.json', error)

    def test_out_must_not_be_a_run(self):
        run = self.run_folder('one', 1000.0, self.everything())
        done = subprocess.run([sys.executable, str(SWEEP), '--merge', '--out', str(run), str(run)], text=True,
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=60)
        self.assertEqual(done.returncode, 2)
        self.assertIn('is one of the run folders', done.stderr)


if __name__ == '__main__': unittest.main()
