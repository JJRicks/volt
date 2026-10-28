#!/usr/bin/env python3
"""Owner-present checks on a real phone and a parked scooter; never runs on an emulator.

The physical counterpart of run-release-sweep.py. It drives the separate ParkedValidation runner
against the Volt already installed on the phone and its saved scooter, so the runner APK must be
signed with the same key as that Volt (build android-tests with the release identity for a
release-signed phone). It never installs, replaces, clears or uninstalls Volt; it only installs the
runner and removes it again. Pass the phone's explicit serial and --parked to confirm the scooter is
parked, off the road, with the owner beside it; keep the phone unlocked with Volt in front.

Default plan, in order (--list prints it): read-only snapshot, navigation and background lifecycle
checks; then a clock sync, every mapped setting changed, read back and restored, and a charging
start time written and restored. When the snapshot shows the scooter charging, Test scheduled
charging runs end to end. Speed caps, maximum speed and dual motor drive are never written (each is
recorded as excluded), nor is Scheduled charging toggled on its own: the schedule test owns it and
always ends with it off. A charging plan the owner already has (Scheduled charging or Charge done by)
is left alone: the charge ceiling isn't written and the start-time and schedule-test steps are
skipped, since Volt would read either change as the plan being replaced. Lock and the PIN state read
run only when named with --include. After any write step fails, or its evidence can't be collected,
later write steps are skipped: a setting that wasn't put back stops the plan.

The runner APK's manifest is checked (with the SDK's aapt2) before anything touches the phone: it
must be the test package whose ParkedValidation instruments Volt, never Volt or another app.

Each step's evidence (ParkedValidation's validation folder, cleared and checked empty first) is pulled
to its own directory under --out, which must be new or empty, beside summary.json and report.md. A
step only passes with its own fresh report. The pulled diagnostics can hold device
serials, so keep --out local; report.md carries only control outcomes.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess
import time

PKG = 'com.jjricks.volt'
RUNNER = f'{PKG}.tests/{PKG}.tests.ParkedValidation'
EVIDENCE = f'/sdcard/Android/data/{PKG}/files/validation'
# Owner's plan: no speed or drive-mode writes on a hardware sweep; the schedule test owns timed_charging.
EXCLUDE = ('maximum_speed', 'speed_limit', 'drive_speed_limit', 'dual_drive', 'timed_charging')
STEPS = [
    dict(name='snapshot', mode='snapshot', writes=False, what='connect and save every reading'),
    dict(name='navigation', mode='navigation', writes=False, what='every tab and section, no scooter writes'),
    dict(name='background', mode='background', writes=False, what='10 s background grace and hard cutoff'),
    dict(name='clock', mode='clock', writes=True, what='scooter clock sync'),
    dict(name='settings', mode='sweep', writes=True, what='each mapped setting changed, read back, restored',
         extra=('-e', 'exclude', ','.join(EXCLUDE))),
    dict(name='charge-time', mode='time', writes=True, plan_free=True, what='charging start time written and restored'),
    dict(name='schedule-test', mode='schedule', writes=True, charging=True, plan_free=True, what='Test scheduled charging (needs a charge in progress)'),
    dict(name='lock', mode='lock', writes=True, opt_in=True, what='lock changed, read back, restored'),
    dict(name='pin-state', mode='pin', writes=False, opt_in=True, what='PIN enabled/configured state, never digits'),
]
OPTIONAL = [step['name'] for step in STEPS if step.get('opt_in')]

parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
parser.add_argument('--adb')
parser.add_argument('--serial', help="the phone's explicit adb serial")
parser.add_argument('--runner', type=Path, help='parked-validation.apk signed with the installed Volt key')
parser.add_argument('--aapt2', help="SDK aapt2 for the runner manifest check (default: newest build-tools beside --adb's SDK)")
parser.add_argument('--out', type=Path)
parser.add_argument('--pull-timeout', type=int, default=300, help='seconds allowed to pull one step\'s evidence')
parser.add_argument('--parked', action='store_true', help='confirm the scooter is parked with the owner beside it')
parser.add_argument('--include', action='append', default=[], choices=OPTIONAL, help='add an opt-in step (repeatable)')
parser.add_argument('--only', action='append', help='run only these steps (repeatable)')
parser.add_argument('--list', action='store_true', help='print the plan and exit')
args = parser.parse_args()
if args.list:
    for step in STEPS:
        tag = ' [opt-in]' if step.get('opt_in') else ' [writes]' if step['writes'] else ''
        print(f"{step['name']}{tag}: {step['what']}")
    print('never written: ' + ', '.join(EXCLUDE))
    raise SystemExit(0)
for flag in ('adb', 'serial', 'runner', 'out'):
    if getattr(args, flag) is None: parser.error(f'--{flag} is required')
if re.fullmatch(r'emulator-\d+', args.serial):
    parser.error('This sweep is for a physical phone; use run-release-sweep.py on emulators')
if not args.parked:
    parser.error('--parked is required: confirm the scooter is parked, off the road, with the owner beside it')
names = {step['name'] for step in STEPS}
for name in args.only or []:
    if name not in names: parser.error(f'Unknown step {name}; see --list')
if args.out.exists() and any(args.out.iterdir()):
    parser.error('--out must be a new or empty directory, so every report comes from this run')
prefix = [args.adb, '-s', args.serial]


def runner_manifest():
    """The runner must be the test package instrumenting Volt with ParkedValidation; checked before any device call."""
    aapt2 = args.aapt2
    if aapt2 is None:
        tools = sorted((Path(args.adb).resolve().parent.parent / 'build-tools').glob('*/aapt2'))
        if not tools: raise SystemExit('aapt2 not found; pass --aapt2 from the Android SDK build-tools')
        aapt2 = str(tools[-1])
    dump = subprocess.run([aapt2, 'dump', 'xmltree', str(args.runner), '--file', 'AndroidManifest.xml'],
                          text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=60)
    package = re.search(r'A: package="([^"]+)"', dump.stdout)
    instruments = set()
    for block in dump.stdout.split('E: instrumentation')[1:]:
        block = block.split('E: ', 1)[0]
        name = re.search(r'android:name\(0x[0-9a-f]+\)="([^"]+)"', block)
        target = re.search(r'android:targetPackage\(0x[0-9a-f]+\)="([^"]+)"', block)
        if name and target: instruments.add((name.group(1), target.group(1)))
    if dump.returncode or not package or package.group(1) != PKG + '.tests' or (PKG + '.tests.ParkedValidation', PKG) not in instruments:
        raise SystemExit(f'{args.runner} is not the ParkedValidation runner ({PKG}.tests instrumenting {PKG}); nothing was installed')


runner_manifest()


def adb(*values, check=True, timeout=240):
    result = subprocess.run(prefix + list(values), text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout)
    if check and result.returncode: raise RuntimeError(result.stdout)
    return result.stdout


if adb('shell', 'getprop', 'ro.kernel.qemu').strip() == '1':
    raise SystemExit('This is an emulator; the hardware sweep needs the physical phone')
if f'package:{PKG}' not in adb('shell', 'pm', 'list', 'packages', PKG).split():
    raise SystemExit('Volt is not installed on this phone; install it yourself first (this sweep never installs Volt)')
if 'mWakefulness=Awake' not in adb('shell', 'dumpsys', 'power'):
    raise SystemExit('Wake and unlock the phone, open Volt, then run again')
if re.search(r'(?:isKeyguardShowing|mKeyguardShowing|mDreamingLockscreen)=true', adb('shell', 'dumpsys', 'window')):
    raise SystemExit('Unlock the phone, open Volt, then run again')
version = re.search(r'versionName=(\S+)', adb('shell', 'dumpsys', 'package', PKG))
args.out.mkdir(parents=True, exist_ok=True)


def instrument(step, extra=()):
    """Runs one mode; the second value says why its evidence is missing (None when this run's report arrived)."""
    cleared = subprocess.run(prefix + ['shell', f'rm -rf {EVIDENCE} && ! test -e {EVIDENCE}'], text=True,
                             stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=60)
    if cleared.returncode: return '', 'old evidence could not be cleared from the phone'
    output = adb('shell', 'am', 'instrument', '-w', '-r', '-e', 'mode', step['mode'], *step.get('extra', ()), *extra, RUNNER,
                 check=False, timeout=900)
    destination = args.out / step['name']; destination.mkdir(parents=True, exist_ok=True)
    pulled = subprocess.run(prefix + ['pull', EVIDENCE, str(destination)], text=True, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, timeout=args.pull_timeout)
    reports = list(destination.rglob('control-validation.json')) + list(destination.rglob('partial-control-validation.json'))
    if pulled.returncode or not reports: return output, 'its evidence could not be pulled from the phone'
    return output, None


def verdict(output):
    if 'Permission Denial' in output and 'instrumentation' in output.lower():
        return 'FAIL', 'the runner is not signed with the installed Volt key'
    error = re.search(r'INSTRUMENTATION_RESULT: error=(.*)', output)
    if 'No restorable stored charging timestamp' in output:
        return 'SKIP', 'the scooter has no stored charging start time to restore'
    if 'A charging schedule is active' in output:
        return 'SKIP', 'a charging schedule is active; it was left as set'
    if error: return 'FAIL', error.group(1).strip()[:300]
    if 'INSTRUMENTATION_CODE: 0' in output and 'Process crashed' not in output and 'INSTRUMENTATION_FAILED' not in output:
        return 'PASS', ''
    return 'FAIL', 'runner did not finish cleanly'


def evidence(step):
    if not (args.out / step).is_dir(): return []
    for name in ('control-validation.json', 'partial-control-validation.json'):
        found = list((args.out / step).rglob(name))
        if found:
            data = json.loads(found[0].read_text())
            return data['results'] if isinstance(data, dict) else data
    return []


results, charging, planned, write_failed = [], None, None, None
installed = adb('install', '-r', '--no-incremental', str(args.runner.resolve()), check=False)
if 'Success' not in installed: raise SystemExit('Could not install the runner: ' + installed.strip()[-300:])
try:
    for step in STEPS:
        if args.only and step['name'] not in args.only: continue
        if step.get('opt_in') and step['name'] not in args.include and step['name'] not in (args.only or []): continue
        row = dict(step=step['name'], writes=step['writes'])
        if step['writes'] and write_failed:
            row.update(status='SKIP', detail=f'skipped after {write_failed} failed'); results.append(row)
        elif step.get('plan_free') and planned == 'true':
            row.update(status='SKIP', detail='a charging schedule is active; it was left as set'); results.append(row)
        elif step.get('charging') and charging is not None and charging != '1.0':
            row.update(status='SKIP', detail='the scooter was not charging; plug it in to include this test'); results.append(row)
        else:
            started = time.monotonic()
            print(f"{step['name']}: {step['what']}…", flush=True)
            extra = ('-e', 'exclude', ','.join(EXCLUDE + ('charge_limit',))) if step['name'] == 'settings' and planned == 'true' else ()
            try: output, missing = instrument(step, extra)
            # An attempt that threw (a timed-out clear, run or pull) has no trustworthy evidence either.
            except Exception as error: output, missing = '', f'it could not finish ({type(error).__name__}), so its evidence is missing'
            (args.out / f"{step['name']}.txt").write_text(output)
            status, detail = verdict(output)
            if missing and status != 'SKIP': status, detail = 'FAIL', missing
            found = re.search(r'INSTRUMENTATION_RESULT: charging_state=(\S+)', output)
            if found and charging is None: charging = found.group(1)
            found = re.search(r'INSTRUMENTATION_RESULT: schedule_active=(\S+)', output)
            if found and planned is None: planned = found.group(1)
            row.update(status=status, detail=detail, seconds=round(time.monotonic() - started))
            results.append(row)
            # A failed write, or any step whose evidence is missing, stops later writes: they couldn't be shown restored.
            if status == 'FAIL' and (step['writes'] or missing): write_failed = step['name']
        print(f"{row['step']}: {row['status']}" + (f" :: {row['detail']}" if row['detail'] else ''), flush=True)
finally:
    adb('uninstall', PKG + '.tests', check=False)
    (args.out / 'summary.json').write_text(json.dumps(dict(volt=version.group(1) if version else None, steps=results), indent=2) + '\n')

lines = [f"# Hardware sweep — Volt {version.group(1) if version else '?'}", '', '| Step | Result | Detail |', '|---|---|---|']
lines += [f"| {r['step']} | {r['status']} | {r['detail']} |" for r in results]
controls = [row for r in results for row in evidence(r['step']) if 'key' in row]
unrestored = [row['key'] for row in controls if row.get('restored') is False]
if unrestored: lines[1:1] = ['', f"**NOT RESTORED: {', '.join(unrestored)}. Check these in Volt now.**"]
if controls:
    lines += ['', '| Control | Before | Test | After | Outcome |', '|---|---|---|---|---|']
    lines += [f"| {c['key']} | {c.get('before', '')} | {c.get('test', '')} | {c.get('after', '')} | {c.get('outcome', '')} |" for c in controls]
(args.out / 'report.md').write_text('\n'.join(lines) + '\n')
failed = [r['step'] for r in results if r['status'] == 'FAIL']
print(f"{sum(r['status'] == 'PASS' for r in results)} passed, {len(failed)} failed, "
      f"{sum(r['status'] == 'SKIP' for r in results)} skipped" + (': failed ' + ', '.join(failed) if failed else ''))
print(f"Report: {args.out / 'report.md'}")
raise SystemExit(1 if failed or unrestored else 0)
