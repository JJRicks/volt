#!/usr/bin/env python3
"""Charge evidence across process ends and app updates; requires a disposable emulator and never accepts a phone serial.

ChargeEvidenceValidation's phases that one instrumentation run can't hold (its class comment says what each checks):
kill-test then a forced stop then after-kill (a schedule test cut off by the process end reads interrupted, process_ended;
a scooter selected while offline shows its own file), and arm, an update, then after-upgrade (a start armed before the
update keeps its plan, start window and lock at the start). With --previous-app (main's build, signed with the same key)
arm runs on that build and this one is installed over it, as a real update; without it, arm runs on this build and drops
the evidence files, as a stand-in. Every packet goes to the validation's in-process fake scooter; Bluetooth stays off.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess
import time

PKG = 'com.jjricks.volt'
RUNNER = f'{PKG}.tests/{PKG}.ChargeEvidenceValidation'
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--adb', required=True)
parser.add_argument('--serial', required=True)
parser.add_argument('--app', type=Path, required=True)
parser.add_argument('--runner', type=Path, required=True)
parser.add_argument('--out', type=Path, required=True)
parser.add_argument('--previous-app', type=Path, help="main's APK, signed with the same key as --app")
args = parser.parse_args()
if not re.fullmatch(r'emulator-\d+', args.serial):
    parser.error('An explicit emulator serial is required; physical devices are refused')
prefix = [args.adb, '-s', args.serial]


def run(*values, check=True, timeout=180):
    result = subprocess.run(prefix + list(values), text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout)
    if check and result.returncode:
        raise RuntimeError(result.stdout)
    return result.stdout


if run('shell', 'getprop', 'ro.kernel.qemu').strip() != '1' or run('shell', 'getprop', 'sys.boot_completed').strip() != '1':
    raise SystemExit('A booted disposable emulator is required')
args.out.mkdir(parents=True, exist_ok=True)


def fresh(app):
    for package in (PKG + '.tests', PKG):
        run('uninstall', package, check=False)
    run('install', str(app.resolve()))
    run('install', str(args.runner.resolve()))
    for permission in ('BLUETOOTH_SCAN', 'BLUETOOTH_CONNECT', 'POST_NOTIFICATIONS', 'ACCESS_FINE_LOCATION', 'ACCESS_COARSE_LOCATION'):
        run('shell', 'pm', 'grant', PKG, 'android.permission.' + permission)


def phase(name, *extra):
    output = run('shell', 'am', 'instrument', '-w', '-r', '-e', 'phase', name, *extra, RUNNER, check=False, timeout=1800)
    (args.out / f'{name}.txt').write_text(output)
    passed = 'result=PASS' in output and 'result=FAIL' not in output and 'INSTRUMENTATION_FAILED' not in output
    print(f'{name}: {"PASS" if passed else "FAIL"}', flush=True)
    if not passed:
        print(output, flush=True)
    return passed, output


def value(output, key):
    found = re.findall(rf'INSTRUMENTATION_RESULT: {key}=(-?\d+)', output)
    if not found:
        raise RuntimeError(f'{key} missing from the arm phase')
    return found[-1]


results = []
run('shell', 'cmd', 'bluetooth_manager', 'disable')
run('shell', 'settings', 'put', 'global', 'hidden_api_policy', '1')
try:
    # The process ends mid-test, as Android ends it: no stop, no save.
    fresh(args.app)
    ok, _ = phase('kill-test')
    run('shell', 'am', 'force-stop', PKG)
    time.sleep(2)
    if ok:
        ok, _ = phase('after-kill')
    results.append(dict(case='process-end-and-offline-selection', passed=ok))
    # A start armed before the update.
    previous = args.previous_app
    fresh(previous or args.app)
    ok, output = phase('arm', *([] if previous else ['-e', 'drop_evidence', 'true']))
    if ok:
        start, wire = value(output, 'start_wall_ms'), value(output, 'wire')
        run('shell', 'am', 'force-stop', PKG)
        if previous:
            run('install', '-r', str(args.app.resolve()))
        ok, _ = phase('after-upgrade', '-e', 'start', start, '-e', 'wire', wire)
    results.append(dict(case='armed-before-update', previous_app=bool(previous), passed=ok))
finally:
    run('pull', f'/sdcard/Android/data/{PKG}/files', str(args.out.resolve()), check=False, timeout=600)
    run('shell', 'settings', 'delete', 'global', 'hidden_api_policy', check=False)
    (args.out / 'results.json').write_text(json.dumps(results, indent=2) + '\n')
if len(results) < 2 or not all(result['passed'] for result in results):
    raise SystemExit(1)
