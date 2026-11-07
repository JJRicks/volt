#!/usr/bin/env python3
"""Time app launch and the first Rides open on a disposable emulator with many saved rides.

Each case installs fresh, seeds finished synthetic journals (mode=seed), force-stops Volt, then measures a
cold start (mode=measure). The numbers are reported, with the plan's budgets marked; --enforce fails on a miss.
Emulator times are slower than a phone's, so compare a branch against main on the same emulator.

--release is the release sweep's gate (run-release-sweep.py step rides-timing). It takes at least two cold starts
per case and fails when the best of them misses a RELEASE_BUDGETS budget. Those budgets catch the launch and Rides
regressions #234 and #240 fixed (seconds) without failing on a busy Mac's noise (tenths): see RELEASE_BUDGETS.
"""
import argparse
import json
import pathlib
import re
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--adb", required=True)
parser.add_argument("--serial", required=True)
parser.add_argument("--app", required=True)
parser.add_argument("--runner", required=True)
parser.add_argument("--out", required=True)
parser.add_argument("--case", action="append", help="widthxheight dp")
parser.add_argument("--fixture", action="append", help="rides x seconds per ride, e.g. 41x3600")
parser.add_argument("--repeat", type=int, default=1, help="cold measurements per seeded case")
parser.add_argument("--enforce", action="store_true", help="fail when a budget is missed")
parser.add_argument("--release", action="store_true",
                    help="release gate: at least 2 cold starts per case; fail when the best one misses a RELEASE_BUDGETS budget")
parser.add_argument("--select-delay-ms", type=int, default=0,
                    help="test only: hold each Rides selection this long on the main thread (proves the gate sees a slow tap)")
args = parser.parse_args()
if not args.serial.startswith("emulator-"):
    parser.error("This destructive fixture refuses physical phones; use a disposable emulator.")
output = pathlib.Path(args.out).resolve()
output.mkdir(parents=True, exist_ok=True)
CLASS = "com.jjricks.volt.tests/com.jjricks.volt.tests.RidesTimingValidation"
# Plan budgets (quick-fixes PR 1): first row within 300 ms cold and 150 ms warm, no main-thread stall over 250 ms.
# first_row is the first frame after the tap (skeleton rows when the list loads on a worker); rows is the real rows.
BUDGETS = {"rides_cold_first_row_ms": 300, "rides_warm_first_row_ms": 150, "rides_warm_rows_ms": 150,
           "rides_cold_longest_main_message_ms": 250, "rides_warm_longest_main_message_ms": 250}
# Release gate (--release), judged on each case's best cold start. A regression to the code before #234 and #240
# costs seconds: first frame and service start command 2.2-4.5 s, the first Rides open stalling the main thread
# 3.2-24 s. The fixed code measures tenths even on a busy emulator (first frame 0.2-0.7 s, Rides stalls under
# 0.2 s). Each budget sits at least twice above the fixed numbers and below the old ones; the PR that added the
# gate (#272) records the numbers measured for it. launch_service_start_command_ms is when the service's start
# command ran (where Volt would begin connecting): Bluetooth is off and auto-connect is off here, so it is a proxy
# for connection start, not a connection time.
RELEASE_BUDGETS = {"launch_first_frame_ms": 1500, "launch_service_start_command_ms": 1500,
                   "rides_cold_first_row_ms": 1000, "rides_cold_longest_main_message_ms": 1000,
                   "rides_warm_longest_main_message_ms": 1000}
if args.release:
    args.repeat = max(args.repeat, 2)


def release_misses(name, runs):
    """The release budgets one case misses. Every run must report every gated measure as a time (an int >= 0):
    a missing value, or the validation's -1 for "no main-thread message seen", is a miss of its own, never a fast
    run, so it can't hide a slow one. With every value valid, the best run is judged against the budget."""
    misses = []
    if len(runs) < 2:
        misses.append(f"{name}: {len(runs)} cold start(s), the gate needs 2")
    for key, budget in RELEASE_BUDGETS.items():
        values = [row.get(key) for row in runs]
        bad = [i + 1 for i, v in enumerate(values) if not (isinstance(v, int) and not isinstance(v, bool) and v >= 0)]
        if bad or not values:
            misses.append(f"{name}: {key} missing or invalid in run(s) {bad} (values {values})")
        elif min(values) > budget:
            misses.append(f"{name}: best {key}={min(values)} > {budget} (runs {values})")
    return misses


def adb(*command, optional=False, timeout=None):
    result = subprocess.run([args.adb, "-s", args.serial, *command], capture_output=True, text=True, timeout=timeout)
    text = result.stdout + result.stderr
    if result.returncode and not optional:
        raise RuntimeError(text)
    return text


def instrument(*extra):
    text = adb("shell", "am", "instrument", "-w", "-r", *extra, CLASS, timeout=900)
    values = dict(re.findall(r"INSTRUMENTATION_RESULT: (\w+)=(.*)", text))
    if values.get("result") != "PASS":
        raise RuntimeError(text)
    return values


if adb("shell", "getprop", "ro.kernel.qemu").strip() != "1":
    parser.error("Selected device did not identify itself as an emulator.")
cases = args.case or ["344x882", "725x604"]
fixtures = args.fixture or ["41x3600", "300x300"]
results, missed, release_missed = [], [], []
try:
    adb("shell", "svc", "bluetooth", "disable")
    adb("shell", "settings", "put", "system", "screen_off_timeout", "2147483647")
    adb("shell", "input", "keyevent", "82")
    for fixture in fixtures:
        rides, seconds = map(int, fixture.split("x"))
        for case in cases:
            width, height = map(int, case.split("x"))
            if not (280 <= width <= 1600 and 280 <= height <= 1800):
                parser.error("Unreasonable viewport")
            adb("uninstall", "com.jjricks.volt.tests", optional=True)
            adb("uninstall", "com.jjricks.volt", optional=True)
            adb("shell", "wm", "density", "480")
            adb("shell", "wm", "size", f"{width * 3}x{height * 3}")
            adb("install", args.app)
            adb("install", args.runner)
            for permission in ("BLUETOOTH_SCAN", "BLUETOOTH_CONNECT", "POST_NOTIFICATIONS"):
                adb("shell", "pm", "grant", "com.jjricks.volt", f"android.permission.{permission}")
            seeded = instrument("-e", "mode", "seed", "-e", "rides", str(rides), "-e", "seconds", str(seconds))
            for run in range(args.repeat):
                adb("shell", "am", "force-stop", "com.jjricks.volt")
                delay = ("-e", "select_delay_ms", str(args.select_delay_ms)) if args.select_delay_ms else ()
                measured = instrument("-e", "mode", "measure", *delay)
                row = {"fixture": fixture, "case": case, "run": run + 1, "seeded_bytes": int(seeded["seeded_bytes"])}
                row.update({k: (int(v) if re.fullmatch(r"-?\d+", v) else v) for k, v in measured.items() if k != "result"})
                results.append(row)
                print(json.dumps(row), flush=True)
                for key, budget in BUDGETS.items():
                    if isinstance(row.get(key), int) and row[key] > budget:
                        missed.append(f"{fixture} {case} run {run + 1}: {key}={row[key]} > {budget}")
            runs = [row for row in results if row["fixture"] == fixture and row["case"] == case]
            release_missed += release_misses(f"{fixture} {case}", runs)
finally:
    adb("shell", "wm", "size", "reset", optional=True)
    adb("shell", "wm", "density", "reset", optional=True)
(output / "rides-timing.json").write_text(json.dumps({"results": results, "budget_misses": missed,
                                                     "release_budgets": RELEASE_BUDGETS if args.release else None,
                                                     "release_misses": release_missed if args.release else None}, indent=2))
for line in missed:
    print("over budget:", line)
if args.release:
    for line in release_missed:
        print("over release budget:", line)
    print("release gate:", "FAIL" if release_missed else "PASS", f"(best of {args.repeat} cold starts per case)")
if missed and args.enforce:
    raise SystemExit("Rides timing over budget")
if release_missed and args.release:
    raise SystemExit("Rides timing over the release budget")
