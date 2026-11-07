#!/usr/bin/env python3
"""Automatic ride copies end to end (RideAutoExportValidation); disposable emulator only, never a phone serial.

Clears Volt's data, deletes /sdcard/Download/VoltRides and any ride-* file in Download, then drives the real
system folder and save pickers. Run it only on an emulator whose shared storage you do not need. Screenshots
and their text census land in --out/auto-export-validation.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--adb', required=True)
parser.add_argument('--serial', required=True)
parser.add_argument('--app', type=Path, required=True)
parser.add_argument('--runner', type=Path, required=True)
parser.add_argument('--out', type=Path, required=True)
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
for package in ('com.jjricks.volt.tests', 'com.jjricks.volt'):
    run('uninstall', package, check=False)
run('install', str(args.app.resolve()))
run('install', str(args.runner.resolve()))
run('shell', 'cmd', 'bluetooth_manager', 'disable')
run('shell', 'rm -rf /sdcard/Download/VoltRides /sdcard/Download/ride-*')
run('shell', 'settings', 'put', 'system', 'screen_off_timeout', '2147483647')
run('shell', 'input', 'keyevent', '224'); run('shell', 'wm', 'dismiss-keyguard', check=False)
for permission in ('BLUETOOTH_SCAN', 'BLUETOOTH_CONNECT', 'POST_NOTIFICATIONS', 'ACCESS_FINE_LOCATION', 'ACCESS_COARSE_LOCATION'):
    run('shell', 'pm', 'grant', 'com.jjricks.volt', 'android.permission.' + permission)
output = run('shell', 'am', 'instrument', '-w', 'com.jjricks.volt.tests/com.jjricks.volt.tests.RideAutoExportValidation', timeout=900)
(args.out / 'result.txt').write_text(output)
run('pull', '/sdcard/Android/data/com.jjricks.volt/files/auto-export-validation', str(args.out.resolve()), check=False)
(args.out / 'folder-listing.txt').write_text(run('shell', 'ls', '-la', '/sdcard/Download/', '/sdcard/Download/VoltRides/', check=False))
(args.out / 'folder-sha256.txt').write_text(run('shell', 'sha256sum /sdcard/Download/ride-* /sdcard/Download/VoltRides/*', check=False))
passed = 'result=PASS' in output and 'result=FAIL' not in output
(args.out / 'results.json').write_text(json.dumps({'passed': passed}, indent=2) + '\n')
print('PASS' if passed else 'FAIL', flush=True)
if not passed:
    print(output, flush=True)
    raise SystemExit(1)
