#!/usr/bin/env python3
"""Every emulator validation a release needs, on one disposable emulator; never accepts a phone serial.

Build the app and android-tests/build.sh with the same key first, start a read-only AVD yourself,
then pass its explicit emulator serial, SDK adb, both APKs and an evidence directory. Existing
emulator app data is deliberately replaced. Before anything runs, every <instrumentation> in
android-tests/AndroidManifest.xml must be covered by a step below or EXCLUDED with a reason, so a
new validation can't be left out of a release by accident. The guard works per class, not per
mode: when a validation or runner gains a mode with checks of its own, add a step for it here, or
list it in SUBSUMED with the mode whose run already includes it. --only reruns named steps;
--list prints them. Each direct step's on-device files (screenshots) are copied to its evidence
directory before the app is removed. A loaded emulator's System UI, launcher or keyboard can stall
long enough for an "isn't responding" dialog, which takes window focus and covers screenshots in
every later check; system error dialogs stay hidden while steps run and any left over are closed
before each step (a stalled Volt is still killed, so its step fails). A step also waits for a freshly
booted emulator to finish starting its services (see await_settled). The Play Store is disabled and
left that way: on a read-only emulator it updates preinstalled apps, the keyboard among them, for many
minutes after every boot, replacing their processes mid-step, and re-enabling it between --only steps
made the launcher reload into the next step. Heads-up notifications (Play services posts several after
boot) are off while steps run: one slid over a Battery cell's border in a screencap.

Localization (l10n/README.md). Every step but the locale-* ones runs Volt in English: the emulator's system language must be
en-US, and each fresh install clears any per-app language (API 33+). --census gathers the text census
that every screenshot helper writes (android-tests/TextCensus.java) into <out>/census/<step>/, for
tools/l10n/census_diff.py, with census/sweep.json naming the app and test APKs (SHA-256). Run the same
steps twice for the base APK and once for the head APK, each into a new --out and all with the head's
test APK, then compare with census_diff.py --base A/census --base A2/census HEAD/census: the two base
runs show which records change from run to run (volatile, reported but not compared). A screenshot
without a census, and a step with no census at all, are reported, since G7 then covers nothing there. --pseudo-app adds the pseudo-locale APK (VOLT_PSEUDO_LOCALES=1 build.sh,
build/pseudo/volt-pseudo.apk), whose classes.dex must equal --app's, for the locale-* steps
(LocaleValidation under en-XA and ar-XB); without --pseudo-app they are skipped. Each language with status
beta or stable in l10n/lang/*.tsv (English, the source, aside) also gets locale-<tag>-<size> steps at every
size on the normal --app APK (LocaleValidation under that language, with its rtl field), and
locale-<tag>-scooter-<size> steps (-e walk scooter: Home's shortcuts and Setting tiles on the simulator, the
Maintenance row and page with seeded reminders, riding mode's recording, reconnecting, not connected and
Ride saved pills, all of which the page walk never reaches without a scooter or a ride; en-XA gets them
too, as locale-en-XA-scooter-<size>). All locale-* steps
report only: their findings never fail the sweep. They never join the census, so run them as their own
sweep (own --out, no --census) rather than in a G7 run.

The closing summary counts the two kinds apart: gating steps passed and failed (only those set the exit code),
then report-only steps ran, did not finish and skipped, none of which is a pass. summary.json gives each step a
status (passed, failed, reported, did-not-finish, skipped); only gating steps have the passed field.

Sizes. PHONE is the Fold5 cover (344x882 dp, dark, 1x text). WIDE is its open panel (725x604 dp, light) at
1x text, so the wide layout (side rail, two columns) stays checked. WIDE_LARGE is the same panel at 1.4x
Text size: Volt picks the wide layout from the width over the text scale (WideLayout, 650 dp), so 725 dp
at 1.4x (518) is the phone layout (bottom bar, one column) there. Each my-scooters step asserts its layout
(-e layout): phone at 344 and 725-large, wide at 725. The validations that
take a text scale run at all three sizes; the size-only ones at PHONE and WIDE.
"""
import argparse
import hashlib
import json
import shutil
from pathlib import Path
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ElementTree

HERE = Path(__file__).resolve().parent
PKG = 'com.jjricks.volt'
ALL = ('BLUETOOTH_SCAN', 'BLUETOOTH_CONNECT', 'POST_NOTIFICATIONS', 'ACCESS_FINE_LOCATION', 'ACCESS_COARSE_LOCATION')
NEARBY = ALL[:3]
PHONE, WIDE = ('344x882', 'dark', '1'), ('725x604', 'light', '1')
# The open panel at large text: the phone layout on a wide window (WideLayout's rule, see above).
WIDE_LARGE = ('725x604', 'light', '1.4')
SIZES = (PHONE, WIDE, WIDE_LARGE)
LAYOUT = {PHONE: 'phone', WIDE: 'wide', WIDE_LARGE: 'phone'}


def size_name(size_case):
    """Step-name suffix: the width, plus -large for WIDE_LARGE (charge-schedule-725-large)."""
    return size_case[0].split('x')[0] + ('-large' if size_case is WIDE_LARGE else '')

# Registered but deliberately not run here, with the reason; everything else must have a step.
EXCLUDED = {'ParkedValidation': 'physical parked-scooter checks that send real lock, PIN and clock commands; run only under an explicit physical test plan',
            'RideAutoExportValidation': 'drives the system folder and save pickers and deletes Download/VoltRides and Download/ride-* in shared storage; run it with run-ride-auto-export.py on a disposable emulator'}
# Modes not run on their own because a step's mode already runs every check they make.
SUBSUMED = {'ResponsiveDashboardValidation battery, capacity and fold': 'all (in the default responsive matrix)',
            'DeveloperSettingsValidation preferences': 'all (developer-settings steps)',
            'run-backup-scenario --inspect': 'its default prepare run, which ends with inspect',
            'AutoStartValidation cadence and location': 'auto-start (the full pass)',
            'ShortcutPickerValidation look': 'the shortcut-picker steps, which take the same pictures and check them (look runs on any build, for before/after pictures)'}
ANDROID = '{http://schemas.android.com/apk/res/android}'
STATUS_LABEL = {'passed': 'PASS', 'failed': 'FAIL', 'reported': 'REPORT', 'did-not-finish': 'REPORT (did not finish)', 'skipped': 'SKIPPED'}


def runner(name, script, covers, *extra):
    return dict(name=name, kind='runner', script=script, covers=covers, extra=list(extra))


def inst(name, cls, *extra, grant=ALL, size=None, hidden_api=False, pseudo=False, report_only=False):
    """pseudo: install --pseudo-app instead of --app. pseudo or report_only: report the result without failing the sweep."""
    return dict(name=name, kind='inst', cls=cls, covers=[cls.rsplit('.', 1)[1]], extra=list(extra), grant=grant, size=size, hidden_api=hidden_api,
                pseudo=pseudo, report_only=pseudo or report_only)


def shipped_languages():
    """(tag, rtl) for each language with status beta or stable in l10n/lang/*.tsv, the source language aside."""
    found = []
    for path in sorted((HERE.parent / 'l10n' / 'lang').glob('*.tsv')):
        fields = dict(line.split('\t', 1) for line in path.read_text(encoding='utf-8').splitlines()
                      if '\t' in line and not line.startswith('#'))
        if fields.get('status') in ('beta', 'stable') and fields.get('folder') != 'values':
            found.append((fields['tag'], fields.get('rtl') == 'true'))
    return found


T = PKG + '.tests.'
STEPS = [
    runner('backups', 'run-backups.py', ['AppDataBackupValidation', 'AppDataMaintenanceValidation', 'BackupInventoryValidation',
                                         'BackupUiValidation', 'DashboardTransferValidation', 'FullBackupJourneyValidation']),
    runner('backup-scenario', 'run-backup-scenario.py', ['BackupScenarioValidation']),
    runner('backup-render', 'run-backup-scenario.py', [], '--render'),
    runner('backup-render-damaged', 'run-backup-scenario.py', [], '--render', '--render-mode', 'render-damaged'),
    runner('backup-render-scooters', 'run-backup-scenario.py', [], '--render', '--render-mode', 'render-scooters'),
    runner('guided', 'run-guided-tests.py', ['GuidedTestValidation']),
    runner('guided-queue', 'run-guided-tests.py', [], '--mode', 'queue'),
    runner('compat-gating', 'run-compat-gating.py', ['CompatibilityGatingValidation']),
    runner('compat-controls', 'run-compat-gating.py', ['CompatControlsValidation'], '--mode', 'controls'),
    runner('compat-menu-edit', 'run-compat-gating.py', ['ScooterMenuEditValidation'], '--mode', 'menu-edit'),
    runner('compat-ut5-screens', 'run-compat-gating.py', ['Ut5SimulationScreens'], '--mode', 'ut5-screens'),
    runner('compat-import-name', 'run-compat-gating.py', [], '--mode', 'import-name'),
    runner('compat-owner-profiles', 'run-compat-gating.py', [], '--mode', 'owner-profiles'),
    runner('charge-capture', 'run-charge-capture.py', ['ChargeCaptureValidation']),
    runner('charge-estimates', 'run-charge-capture.py', ['ChargeEstimateValidation'], '--suite', 'estimates'),
    runner('battery-widgets', 'run-battery-widgets.py', ['BatteryWidgetValidation']),
    runner('learned-range', 'run-learned-range.py', ['LearnedRangeValidation']),
    runner('ride-auto-stop', 'run-ride-auto-stop.py', ['RideAutoStopValidation']),
    runner('ride-backups', 'run-ride-backups.py', ['RideBackupValidation']),
    runner('rides', 'run-rides.py', ['RidesAnalysisValidation']),
    # Seeds many rides, then times a cold launch and two Rides opens (#234, #240: "Volt launches instantly"). --release
    # fails when the best of two cold starts per case misses a generous budget (run-rides-timing.py RELEASE_BUDGETS;
    # every run must report every gated time, and the service start command stands in for connection start):
    # a regression costs seconds, load noise tenths. The plan's tight budgets are still reported, not gated.
    runner('rides-timing', 'run-rides-timing.py', ['RidesTimingValidation'], '--release'),
    runner('live-stats', 'run-live-stats.py', ['LiveStatsValidation']),
    runner('responsive', 'run-responsive.py', ['ResponsiveDashboardValidation']),
    # The default matrix leaves out the modes whose checks run nowhere else.
    runner('responsive-modes', 'run-responsive.py', [], *[arg for mode in ('chart', 'followup', 'gauge', 'quick', 'motion')
                                                          for size in ('344x882', '725x604') for arg in ('--case', f'{size}:1.0:{mode}')]),
    runner('motion-polish', 'run-motion-polish.py', ['MotionPolishValidation']),
    runner('haptics', 'run-haptics.py', ['QuickHapticsValidation', 'ConnectionHapticsValidation']),
]
for case in SIZES:
    (size, theme, text), w = case, size_name(case)
    STEPS += [inst(f'charge-schedule-{w}', T + 'ChargeScheduleValidation', '-e', 'theme', theme, '-e', 'text_scale', text, size=size),
              inst(f'charge-alerts-{w}', T + 'ChargeAlertValidation', '-e', 'theme', theme, '-e', 'text_scale', text, size=size),
              inst(f'my-scooters-{w}', T + 'MyScootersValidation', '-e', 'theme', theme, '-e', 'text_scale', text,
                   '-e', 'layout', LAYOUT[case], size=size)]
    # The Record charges automatically switch beside the recording controls, off and on.
    STEPS += [inst(f'charge-auto-record-page-{w}', PKG + '.ChargeAutoRecordValidation', '-e', 'mode', 'page', '-e', 'theme', theme,
                   '-e', 'text_scale', text, size=size, hidden_api=True)]
    if case is not WIDE_LARGE:
        STEPS += [inst(f'developer-settings-{w}', T + 'DeveloperSettingsValidation', '-e', 'theme', theme, size=size),
                  inst(f'dashboard-offline-lock-{w}', T + 'DashboardValidation', '-e', 'mode', 'offline-lock', '-e', 'theme', theme, size=size)]
STEPS += [
    inst('dashboard', T + 'DashboardValidation'),
    inst('dashboard-layout-slots', T + 'DashboardValidation', '-e', 'mode', 'layout-slots'),
    inst('dashboard-home-widgets', T + 'DashboardValidation', '-e', 'mode', 'home-widgets'),
    inst('dashboard-slots', T + 'DashboardSlotsValidation'),
    inst('capacity-widget', T + 'CapacityWidgetValidation'),
    inst('charge-schedule-wire', PKG + '.ChargeScheduleWireValidation', hidden_api=True),
    inst('charge-live-unplug', PKG + '.ChargeLiveUnplugValidation', hidden_api=True),
    inst('charge-live-unplug-notifications-off', PKG + '.ChargeLiveUnplugValidation', '-e', 'mode', 'notifications-off', grant=('BLUETOOTH_SCAN', 'BLUETOOTH_CONNECT'), hidden_api=True),
    # Record charges automatically: turns the emulator's Bluetooth on for its run (a recording ends at once with it off) and off again.
    inst('charge-auto-record', PKG + '.ChargeAutoRecordValidation', hidden_api=True),
    # Idle efficiency: the connection notice posts only on a change, the keep-screen-on flag only on a change, the simulator's service stops out of sight.
    inst('idle-efficiency', PKG + '.IdleEfficiencyValidation', hidden_api=True),
    inst('first-connection', T + 'FirstConnectionValidation', hidden_api=True),
    inst('charge-alerts-notifications-off', T + 'ChargeAlertValidation', '-e', 'mode', 'notifications-off', grant=('BLUETOOTH_SCAN', 'BLUETOOTH_CONNECT')),
    # The charging view's one time tile; its restart mode needs the same install, so it runs by hand after this one.
    inst('charge-time-tile', T + 'ChargeTimeTileValidation', size=PHONE[0]),
    inst('charge-time-tile-layout-344', T + 'ChargeTimeTileValidation', '-e', 'mode', 'layout', '-e', 'size_label', '344', size=PHONE[0]),
    inst('charge-time-tile-layout-725', T + 'ChargeTimeTileValidation', '-e', 'mode', 'layout', '-e', 'size_label', '725', size=WIDE[0]),
    inst('launch-denied', T + 'LaunchValidation', '-e', 'mode', 'denied', grant=()),
    inst('launch-granted', T + 'LaunchValidation', '-e', 'mode', 'granted'),
    inst('phone-ride-denied', T + 'PhoneRideValidation', '-e', 'mode', 'denied', grant=NEARBY),
    inst('phone-ride-granted', T + 'PhoneRideValidation', '-e', 'mode', 'granted'),
    inst('riding-from-zero', T + 'RidingFromZeroValidation'),
    inst('regen-display', T + 'RegenDisplayValidation'),
    inst('riding-auto-close', T + 'RidingAutoCloseValidation', hidden_api=True),
    inst('riding-lost-connection', T + 'RidingLostConnectionValidation', hidden_api=True),
    # Auto-record and auto ride mode. Location stays ungranted: these steps press its prompt themselves. The restored-* steps are the
    # restored-settings first launch. The harness's other -e only scenarios follow one permission across runs of one install, which
    # a step's fresh install can't do (order in its class comment).
    inst('auto-start', PKG + '.AutoStartValidation', grant=NEARBY, hidden_api=True),
    *[inst(f'auto-start-{mode}', PKG + '.AutoStartValidation', '-e', 'only', mode, grant=NEARBY, hidden_api=True)
      for mode in ('gates', 'gps-grant', 'gps-decline', 'gps-riding', 'dialog-guard', 'cancel-empty', 'restored-grant', 'restored-decline')],
]
for theme in ('dark', 'light'):
    STEPS += [inst(f'report-invite-{theme}', T + 'ReportInviteValidation', '-e', 'theme', theme),
              inst(f'report-shortcuts-{theme}', T + 'ReportShortcutsValidation', '-e', 'theme', theme),
              inst(f'report-diagnostics-{theme}', T + 'ReportDiagnosticsValidation', '-e', 'theme', theme),
              inst(f'owner-model-{theme}', T + 'OwnerModelValidation', '-e', 'theme', theme),
              inst(f'model-home-{theme}', T + 'ModelHomeValidation', '-e', 'theme', theme)]
# Developer simulator: UT5 MAX, GT3 Max and GT5 Max EU in Parked, Riding and Charging, then every store compared (phone and wide, dark and light).
STEPS += [inst(f'simulator-scenes-{size.split("x")[0]}-{theme}', T + 'SimulatorScenesValidation', '-e', 'theme', theme, size=size)
          for size in (PHONE[0], WIDE[0]) for theme in ('dark', 'light')]
# Home's Choose shortcut picker (sections, search, the Charge alerts shortcut): every size, the Fold5 cover at 1.4x text too,
# and German and European Portuguese (the longest names) on that tightest case.
PHONE_LARGE = ('344x882', 'dark', '1.4')
STEPS += [inst(f'shortcut-picker-{w}', T + 'ShortcutPickerValidation', '-e', 'theme', theme, '-e', 'text_scale', text, size=size)
          for (size, theme, text), w in [(case, size_name(case)) for case in SIZES] + [(PHONE_LARGE, '344-large')]]
STEPS += [inst(f'shortcut-picker-{tag}-344-large', T + 'ShortcutPickerValidation', '-e', 'language', tag, '-e', 'theme', 'light',
               '-e', 'text_scale', '1.4', size='344x882') for tag in ('de', 'pt-PT')]
# Maintenance reminders added with no connection (never-read and stale scooters, a restart), phone and wide.
STEPS += [inst(f'maintenance-offline-{size_name(case)}', T + 'MaintenanceOfflineValidation', '-e', 'theme', case[1], '-e', 'text_scale', case[2], size=case[0])
          for case in (PHONE, WIDE)]
STEPS += [inst('riding-chip-row', T + 'RidingChipRowValidation', size='344x882')]
STEPS += [inst('riding-chip-choices', T + 'RidingChipChoicesValidation')]
STEPS += [inst('riding-config-menu', T + 'RidingConfigMenuValidation'),
          inst('riding-config-menu-narrow', T + 'RidingConfigMenuValidation', '-e', 'label', 'narrow', size='344x882')]
STEPS += [inst('riding-stopped-motion', T + 'RidingStoppedMotionValidation', size='344x882')]
STEPS += [inst('riding-battery-gauge', T + 'RidingBatteryGaugeValidation')]
STEPS += [inst('riding-disconnected', T + 'RidingDisconnectedValidation', hidden_api=True),
          inst('riding-disconnected-narrow', T + 'RidingDisconnectedValidation', '-e', 'label', 'narrow', size='344x882', hidden_api=True)]
STEPS += [inst('more-menu-phone', T + 'MoreMenuValidation', '-e', 'wide', 'false', '-e', 'theme', 'dark', size='344x882'),
          inst('more-menu-wide', T + 'MoreMenuValidation', '-e', 'wide', 'true', '-e', 'theme', 'light', size='850x880')]
STEPS += [inst(name, cls) for name, cls in (
    ('map-tiles', PKG + '.MapTilesValidation'), ('history', T + 'HistoryValidation'),
    ('route-gesture', T + 'RouteGestureValidation'), ('search-focus', T + 'SearchFocusValidation'),
    ('ui-organization', T + 'UiOrganizationValidation'), ('update-check', T + 'UpdateCheckValidation'))]
# Pseudo-locale text and layout checks (plan section 6.2): report only until the localization gate.
for locale in ('en-XA', 'ar-XB'):
    for case in SIZES:
        (size, theme, text), w = case, size_name(case)
        STEPS.append(inst(f'locale-{locale}-{w}', T + 'LocaleValidation', '-e', 'locale', locale, '-e', 'theme', theme, '-e', 'text_scale', text,
                          '-e', 'variant', f'{locale}-{w}', size=size, pseudo=True))
        # The scooter walk's screens checked for unextracted text too; ar-XB's right-to-left checks are on hold (owner).
        if locale == 'en-XA':
            STEPS.append(inst(f'locale-{locale}-scooter-{w}', T + 'LocaleValidation', '-e', 'walk', 'scooter', '-e', 'locale', locale, '-e', 'theme', theme,
                              '-e', 'text_scale', text, '-e', 'variant', f'{locale}-scooter-{w}', size=size, pseudo=True))
# Each shipped language on the normal APK (plan 7.2 step 6), report only like the pseudo-locales.
for tag, rtl in shipped_languages():
    for case in SIZES:
        (size, theme, text), w = case, size_name(case)
        STEPS.append(inst(f'locale-{tag}-{w}', T + 'LocaleValidation', '-e', 'locale', tag, '-e', 'rtl', str(rtl).lower(), '-e', 'theme', theme,
                          '-e', 'text_scale', text, '-e', 'variant', f'{tag}-{w}', size=size, report_only=True))
        # The states the page walk can't reach without a scooter or a ride: Home's shortcuts and Setting tiles on the
        # simulator, the Maintenance row and page with seeded reminders, and riding mode's recording pill.
        STEPS.append(inst(f'locale-{tag}-scooter-{w}', T + 'LocaleValidation', '-e', 'walk', 'scooter', '-e', 'locale', tag, '-e', 'rtl', str(rtl).lower(),
                          '-e', 'theme', theme, '-e', 'text_scale', text, '-e', 'variant', f'{tag}-scooter-{w}', size=size, report_only=True))


def registered():
    root = ElementTree.parse(HERE / 'AndroidManifest.xml').getroot()
    return sorted(element.get(ANDROID + 'name').rsplit('.', 1)[1] for element in root.iter('instrumentation'))


def uncovered():
    covered = {c for step in STEPS for c in step['covers']}
    return [name for name in registered() if name not in covered and name not in EXCLUDED]


parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--adb')
parser.add_argument('--serial')
parser.add_argument('--app', type=Path)
parser.add_argument('--runner', type=Path)
parser.add_argument('--out', type=Path)
parser.add_argument('--only', action='append', help='step name (repeatable)')
parser.add_argument('--census', action='store_true', help='gather every step\'s text census into <out>/census/ for tools/l10n/census_diff.py (sweep the base APK twice, the head once)')
parser.add_argument('--pseudo-app', type=Path, help='the pseudo-locale APK for the en-XA and ar-XB locale-* steps; its classes.dex must equal --app\'s')
parser.add_argument('--list', action='store_true', help='print the steps and exit')
args = parser.parse_args()
missing = uncovered()
if missing:
    raise SystemExit('Not covered by the release sweep: ' + ', '.join(missing) + '. Add a step for each.')
if args.list:
    for step in STEPS: print(step['name'])
    for name, why in EXCLUDED.items(): print(f'(excluded) {name}: {why}')
    for name, where in SUBSUMED.items(): print(f'(subsumed) {name}: by {where}')
    raise SystemExit(0)
for flag in ('adb', 'serial', 'app', 'runner', 'out'):
    if getattr(args, flag) is None: parser.error(f'--{flag} is required')
if not re.fullmatch(r'emulator-\d+', args.serial):
    parser.error('An explicit emulator serial is required; physical devices are refused')
names = {step['name'] for step in STEPS}


def dex_hash(apk):
    import zipfile
    with zipfile.ZipFile(apk) as archive:
        return hashlib.sha256(archive.read('classes.dex')).hexdigest()


if args.pseudo_app:
    if dex_hash(args.pseudo_app) != dex_hash(args.app):
        parser.error('--pseudo-app has a different classes.dex from --app; build both with the same build.sh run (VOLT_PSEUDO_LOCALES=1)')
for name in args.only or []:
    if name not in names: parser.error(f'Unknown step {name}; see --list')
prefix = [args.adb, '-s', args.serial]


def adb(*values, check=True, timeout=240):
    result = subprocess.run(prefix + list(values), text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout)
    if check and result.returncode: raise RuntimeError(result.stdout)
    return result.stdout


if adb('shell', 'getprop', 'ro.kernel.qemu').strip() != '1' or adb('shell', 'getprop', 'sys.boot_completed').strip() != '1':
    raise SystemExit('A booted disposable emulator is required')
# English pin: the steps assert English text, and the census compares English. Volt follows the system
# language (System default), so the emulator itself must be en-US.
SYSTEM_LOCALE = adb('shell', 'getprop', 'persist.sys.locale').strip() or adb('shell', 'getprop', 'ro.product.locale').strip()
if SYSTEM_LOCALE != 'en-US':
    raise SystemExit(f'The emulator\'s system language is {SYSTEM_LOCALE or "unknown"}; set it to English (United States) first')
SDK = int(adb('shell', 'getprop', 'ro.build.version.sdk').strip() or 0)
args.out.mkdir(parents=True, exist_ok=True)
if args.census:
    # census_diff.py reads this to refuse base runs of different app APKs (HEAD passed as a base by
    # mistake would turn every change into a volatile record) and runs with different test APKs.
    (args.out / 'census').mkdir(exist_ok=True)
    (args.out / 'census' / 'sweep.json').write_text(json.dumps(dict(
        format='volt-census-sweep-v1', app_sha256=hashlib.sha256(args.app.read_bytes()).hexdigest(),
        runner_sha256=hashlib.sha256(args.runner.read_bytes()).hexdigest(), only=args.only or []), indent=2) + '\n')


# A system error dialog's window title names the stalled process; a crash dialog also has focus.
ERROR_DIALOG = re.compile(r'Window\{\S+ u\d+ Application (?:Not Responding|Error): ([\w.:]+)\}')
# Its own buttons, by locale-independent ID: Wait for a stalled app, Close for a crashed one.
DIALOG_BUTTON = re.compile(r'resource-id="android:id/aerr_(wait|close)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')


def clear_error_dialogs():
    """Answer dialogs shown before they were hidden (boot, or between steps) as a person would.

    Pressing the dialog's own button works for every owner; the persistent System UI ignores force-stop."""
    for _ in range(10):
        owners = set(ERROR_DIALOG.findall(adb('shell', 'dumpsys', 'window', 'windows')))
        if not owners: return
        dump = adb('shell', 'f=/data/local/tmp/volt-sweep-ui.xml; uiautomator dump $f >/dev/null && cat $f; rm -f $f', check=False)
        buttons = sorted(DIALOG_BUTTON.findall(dump), key=lambda button: button[0] != 'wait')
        if buttons:
            left, top, right, bottom = map(int, buttons[0][1:])
            adb('shell', 'input', 'tap', str((left + right) // 2), str((top + bottom) // 2))
        time.sleep(1)
    raise RuntimeError('System error dialogs would not close: ' + ', '.join(sorted(owners)))


def anrs_since(mark):
    lines = adb('shell', f"logcat -d -b events -t '{mark}'", check=False).splitlines()
    return [line.split(' I am_anr  : ', 1)[1][:200] for line in lines if ' I am_anr  : ' in line]


def running_seconds(process):
    pids = adb('shell', 'pidof', process, check=False).split()
    clock = adb('shell', 'ps', '-o', 'ETIME=', '-p', pids[0], check=False).split() if pids else []
    if not clock or not re.fullmatch(r'(\d+-)?[\d:]+', clock[0]): return 0
    days, _, clock = clock[0].rpartition('-'); seconds = 0
    for part in clock.split(':'): seconds = seconds * 60 + int(part)
    return seconds + int(days or 0) * 86400


def await_settled(quiet=60, limit=600):
    """sys.boot_completed comes minutes before a fresh emulator has started its services: four emulators booted
    together logged System UI, keyboard and Play services startup ANRs for about three minutes (one restarted
    its system server), and steps run then found no keyboard. Startup ANRs fire within ~20 s, so begin once
    System UI and the selected keyboard have each run for a minute and no other app has stopped responding
    in that minute; a service still missing counts as unsettled. After the limit, say so and run anyway."""
    end = time.monotonic() + limit
    while True:
        try:
            now = int(adb('shell', 'date', '+%s').strip())
            recent = [anr for anr in anrs_since(f'{now - quiet}.000') if PKG not in anr]
            ime = adb('shell', 'settings', 'get', 'secure', 'default_input_method').strip().partition('/')[0]
            young = [name for name in ('com.android.systemui', ime) if name not in ('', 'null') and running_seconds(name) < quiet]
        except (RuntimeError, ValueError) as error: recent, young = [f'{type(error).__name__}: {error}'.strip()[:200]], []
        if not recent and not young: return
        if time.monotonic() > end:
            print(f'{args.serial} not settled after {limit} s: ' + '; '.join(recent[-2:] + [name + ' just started' for name in young]), flush=True)
            return
        time.sleep(5)


def fresh(grant, app=None):
    for package in (PKG + '.tests', PKG): adb('uninstall', package, check=False)
    adb('install', '--no-incremental', str((app or args.app).resolve())); adb('install', '--no-incremental', str(args.runner.resolve()))
    # English reset: no per-app language survives into a step (API 33+; uninstalling already clears it below).
    if SDK >= 33: adb('shell', f"cmd locale set-app-locales {PKG} --locales ''", check=False)
    for permission in grant: adb('shell', 'pm', 'grant', PKG, 'android.permission.' + permission, check=False)


def gather_census(step_name):
    """Copies a step's census files into <out>/census/<step>/; returns (screens, problems).

    A problem is a census that could not be taken or a screenshot with no census beside it (a helper
    that does not call TextCensus.write, or a runner that does not pull the census files). A
    <name>.census-skip.txt (TextCensus.skip, for a render that holds no text) stands in for the
    census and counts no screen.
    """
    source, target = args.out / step_name, args.out / 'census' / step_name
    screens, errors = 0, []
    if not source.is_dir(): return screens, errors
    for path in sorted(source.rglob('*')):
        if path.name.endswith('.census.tsv') or path.name.endswith('.census-error.txt'):
            destination = target / path.relative_to(source); destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(path, destination)
            if path.name.endswith('.census.tsv'): screens += 1
            else: errors.append(f'{path.relative_to(source)}: {path.read_text(errors="replace").strip()[:200]}')
        elif path.suffix == '.png':
            stem = path.with_suffix('')
            if not any(stem.with_name(stem.name + s).exists() for s in ('.census.tsv', '.census-error.txt', '.census-skip.txt')):
                errors.append(f'{path.relative_to(source)}: no census beside this screenshot')
    return screens, errors


def run_step(step):
    if step['kind'] == 'runner':
        command = [sys.executable, '-I', str(HERE / step['script']), '--adb', args.adb, '--serial', args.serial,
                   '--app', str(args.app), '--runner', str(args.runner), '--out', str(args.out / step['name'])] + step['extra']
        result = subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=7200)
        return result.returncode == 0, result.stdout
    if step['pseudo'] and not args.pseudo_app:
        return True, 'skipped: give --pseudo-app (build/pseudo/volt-pseudo.apk) to run the pseudo-locale checks'
    if step['size']:
        w, h = map(int, step['size'].split('x'))
        adb('shell', 'wm', 'density', '480'); adb('shell', 'wm', 'size', f'{w * 3}x{h * 3}')
    if step['hidden_api']: adb('shell', 'settings', 'put', 'global', 'hidden_api_policy', '1')
    try:
        fresh(step['grant'], args.pseudo_app if step['pseudo'] else None)
        output = adb('shell', 'am', 'instrument', '-w', '-r', *step['extra'], f'{PKG}.tests/{step["cls"]}', check=False, timeout=1800)
    finally:
        # Screenshots live in the app's files; keep them (failures too) before the next install removes them.
        destination = args.out / step['name']; destination.mkdir(parents=True, exist_ok=True)
        adb('pull', f'/sdcard/Android/data/{PKG}/files', str(destination), check=False, timeout=600)
        if step['hidden_api']: adb('shell', 'settings', 'delete', 'global', 'hidden_api_policy', check=False)
        if step['size']: adb('shell', 'wm', 'size', 'reset', check=False); adb('shell', 'wm', 'density', 'reset', check=False)
    passed = 'result=PASS' in output and 'result=FAIL' not in output and 'INSTRUMENTATION_FAILED' not in output and 'Process crashed' not in output
    return passed, output


await_settled()
adb('shell', 'cmd', 'bluetooth_manager', 'disable')
adb('shell', 'settings', 'delete', 'global', 'hidden_api_policy', check=False)
hide_dialogs = adb('shell', 'settings', 'get', 'global', 'hide_error_dialogs').strip()
STORE = 'com.android.vending'
store_enabled = f'package:{STORE}' in adb('shell', 'pm', 'list', 'packages', '-e', STORE).split()
heads_up = adb('shell', 'settings', 'get', 'global', 'heads_up_notifications_enabled').strip()
results = []
try:
    # Android then kills an app that stops responding instead of showing a focus-taking dialog.
    adb('shell', 'settings', 'put', 'global', 'hide_error_dialogs', '1')
    if store_enabled:
        adb('shell', 'pm', 'disable-user', '--user', '0', STORE)
        print(f'Play Store disabled on {args.serial} for the sweep; re-enable with: adb -s {args.serial} shell pm enable {STORE}', flush=True)
    adb('shell', 'settings', 'put', 'global', 'heads_up_notifications_enabled', '0')
    for step in STEPS:
        if args.only and step['name'] not in args.only: continue
        started = time.monotonic()
        mark = adb('shell', "date '+%m-%d %H:%M:%S.000'").strip()
        try: await_settled(); clear_error_dialogs(); passed, output = run_step(step)
        except Exception as error: passed, output = False, f'{type(error).__name__}: {error}'
        if not passed:
            try: output += ''.join(f'\nANR during step: {anr}' for anr in anrs_since(mark))
            except Exception as error: output += f'\nANR log unavailable: {error}'
        (args.out / f'{step["name"]}.txt').write_text(output)
        report_only = step.get('report_only', False)
        detail = ' '.join(re.findall(r'(?:assertions|checks|check_count|screens|unextracted|glued|unfilled|fit_problems|direction_problems|truncated_tiles)=\d+', output)[:9])
        if output.startswith('skipped:'): detail = output
        error = re.search(r'error=(.{0,240})', output)
        if report_only: status = 'skipped' if output.startswith('skipped:') else 'reported' if passed else 'did-not-finish'
        else: status = 'passed' if passed else 'failed'
        result = dict(step=step['name'], status=status, seconds=round(time.monotonic() - started))
        label = STATUS_LABEL[status]
        if report_only: result['report_only'] = True
        else: result['passed'] = passed
        # Report-only locale steps are not English, so they never join the G7 census.
        if args.census and not report_only:
            screens, census_errors = gather_census(step['name'])
            result['census_screens'], result['census_errors'] = screens, census_errors
            detail += f' census={screens}' + (f' census_errors={len(census_errors)}' if census_errors else '')
            if not screens: detail += ' (no screenshots: this step proves nothing for G7)'
        results.append(result)
        print(f'{step["name"]}: {label} {detail}' + ('' if passed or not error else f' :: {error.group(1)}'), flush=True)
finally:
    for package in (PKG + '.tests', PKG): adb('uninstall', package, check=False)
    adb('shell', 'wm', 'size', 'reset', check=False); adb('shell', 'wm', 'density', 'reset', check=False)
    adb('shell', 'settings', 'put', 'system', 'font_scale', '1.0', check=False)
    if heads_up in ('', 'null'): adb('shell', 'settings', 'delete', 'global', 'heads_up_notifications_enabled', check=False)
    else: adb('shell', 'settings', 'put', 'global', 'heads_up_notifications_enabled', heads_up, check=False)
    if hide_dialogs in ('', 'null'): adb('shell', 'settings', 'delete', 'global', 'hide_error_dialogs', check=False)
    else: adb('shell', 'settings', 'put', 'global', 'hide_error_dialogs', hide_dialogs, check=False)
    (args.out / 'summary.json').write_text(json.dumps(results, indent=2) + '\n')
def with_status(status): return [r['step'] for r in results if r['status'] == status]


failed, unfinished, skipped = with_status('failed'), with_status('did-not-finish'), with_status('skipped')
print(f'Gating steps: {len(with_status("passed"))} passed, {len(failed)} failed' + (': ' + ', '.join(failed) if failed else ''))
print(f'Report-only steps (never counted as passes): {len(with_status("reported"))} ran, {len(unfinished)} did not finish, {len(skipped)} skipped'
      + ('; did not finish: ' + ', '.join(unfinished) if unfinished else '') + ('; skipped: ' + ', '.join(skipped) if skipped else ''))
if args.census:
    census_errors = [f'{r["step"]}/{e}' for r in results for e in r.get('census_errors', [])]
    empty = [r['step'] for r in results if 'census_screens' in r and not r['census_screens']]
    print(f'census: {sum(r.get("census_screens", 0) for r in results)} screen(s) in {args.out / "census"}'
          + (f'; {len(census_errors)} problem(s): ' + '; '.join(census_errors[:5]) if census_errors else '')
          + (f'; no census from {len(empty)} step(s) (G7 covers nothing there): ' + ', '.join(empty) if empty else ''))
raise SystemExit(1 if failed else 0)
