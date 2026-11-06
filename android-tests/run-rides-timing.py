#!/usr/bin/env python3
"""Time app launch and the first Rides open on a disposable emulator with many saved rides.

Each case installs fresh, seeds finished synthetic journals (mode=seed), force-stops Volt, then measures a
cold start (mode=measure). The numbers are reported, with the plan's budgets marked; --enforce fails on a miss.
Emulator times are slower than a phone's, so compare a branch against main on the same emulator.
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
args = parser.parse_args()
if not args.serial.startswith("emulator-"):
    parser.error("This destructive fixture refuses physical phones; use a disposable emulator.")
output = pathlib.Path(args.out).resolve()
output.mkdir(parents=True, exist_ok=True)
CLASS = "com.jjricks.volt.tests/com.jjricks.volt.tests.RidesTimingValidation"
# Plan budgets (quick-fixes PR 1): first row within 300 ms cold and 150 ms warm, no main-thread stall over 250 ms.
BUDGETS = {"rides_cold_first_row_ms": 300, "rides_warm_first_row_ms": 150,
           "rides_cold_longest_main_message_ms": 250, "rides_warm_longest_main_message_ms": 250}


def adb(*command, optional=False):
    result = subprocess.run([args.adb, "-s", args.serial, *command], capture_output=True, text=True)
    text = result.stdout + result.stderr
    if result.returncode and not optional:
        raise RuntimeError(text)
    return text


def instrument(*extra):
    text = adb("shell", "am", "instrument", "-w", "-r", *extra, CLASS)
    values = dict(re.findall(r"INSTRUMENTATION_RESULT: (\w+)=(.*)", text))
    if values.get("result") != "PASS":
        raise RuntimeError(text)
    return values


if adb("shell", "getprop", "ro.kernel.qemu").strip() != "1":
    parser.error("Selected device did not identify itself as an emulator.")
cases = args.case or ["344x882", "725x604"]
fixtures = args.fixture or ["41x3600", "300x300"]
results, missed = [], []
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
                measured = instrument("-e", "mode", "measure")
                row = {"fixture": fixture, "case": case, "run": run + 1, "seeded_bytes": int(seeded["seeded_bytes"])}
                row.update({k: (int(v) if re.fullmatch(r"-?\d+", v) else v) for k, v in measured.items() if k != "result"})
                results.append(row)
                print(json.dumps(row), flush=True)
                for key, budget in BUDGETS.items():
                    if isinstance(row.get(key), int) and row[key] > budget:
                        missed.append(f"{fixture} {case} run {run + 1}: {key}={row[key]} > {budget}")
finally:
    adb("shell", "wm", "size", "reset", optional=True)
    adb("shell", "wm", "density", "reset", optional=True)
(output / "rides-timing.json").write_text(json.dumps({"results": results, "budget_misses": missed}, indent=2))
for line in missed:
    print("over budget:", line)
if missed and args.enforce:
    raise SystemExit("Rides timing over budget")
