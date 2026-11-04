#!/usr/bin/env python3
"""After diff.sh: every (tag, step) run's result in one compact file, the checks that make a step's
comparison trustworthy, and the job summary.

usage: python3 -I ops/census/report.py PLANDIR OUT DIFFDIR
Writes DIFFDIR/results.tsv (tag, step, rc, seconds, the sweep's PASS/FAIL line, census screens and
problems from summary.json), DIFFDIR/missing.txt (tag and step of every run without summary.json),
DIFFDIR/not-clean.txt (tag, step and reason of every run that makes its step's comparison
untrustworthy, see below), DIFFDIR/results.txt (every shard's results.txt), and prints Markdown for
$GITHUB_STEP_SUMMARY. Exits 1 when any run is missing or not clean.

census_diff.py only compares a screen that every base run has; a screen one base run lacks is counted
as volatile and never compared, and a step where every run has no screens passes with nothing
compared. So a run is NOT CLEAN when
  - it has no summary.json, its summary.json is unreadable or has no entry for the step, or it has
    no census_screens (the sweep did not get to the census);
  - its sweep's rc is not 0 (it stopped partway, so later screens may be missing or never checked);
  - it has no census screens on a step whose local reference (screens.tsv) has some;
  - (base runs) the two base runs do not have the same census files (each such screen would be
    volatile, so a head change on it would never be seen);
  - (base runs of the commit screens.tsv was measured on) its screen count differs from the local
    census's count for that step.
A head with fewer census screens than the base is listed too, as a note: census_diff.py reports
each screen it lacks ("only in base"), so that already fails its step's diff.
"""
import json
import os
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
SUFFIX = '.census.tsv'  # census_diff.py's SUFFIX

plan_dir, out, diff_dir = map(Path, sys.argv[1:4])
plan = json.loads((plan_dir / 'plan.json').read_text())
base = plan['base']['tag']; heads = [h['tag'] for h in plan['heads']]; steps = plan['steps']
base_runs = [base + '-r1', base + '-r2']
tags = [base_runs[0], *heads, base_runs[1]]

reference, reference_sha = {}, None
for line in (HERE / 'screens.tsv').read_text().splitlines():
    if line.startswith('# sha\t'):
        reference_sha = line.split('\t')[1].strip()
    elif line and not line.startswith('#'):
        name, count = line.split('\t'); reference[name] = int(count)
exact_reference = reference_sha == plan['base']['sha']

LINE = re.compile(r'^\S+ \[\S+ (\d+)s\] (\S+) (\S+): rc=(-?\d+) ?(.*)$')
ran = {}
shard_logs = sorted((out / 'logs').glob('shard-*/results.txt'), key=lambda p: int(p.parent.name.split('-')[1]))
with (diff_dir / 'results.txt').open('w') as merged:
    for path in shard_logs:
        merged.write(f'## {path.parent.name}\n')
        for line in path.read_text(errors='replace').splitlines():
            merged.write(line + '\n')
            m = LINE.match(line)
            if m: ran[(m.group(2), m.group(3))] = (m.group(4), m.group(1), m.group(5))


def census_files(tag, step):
    root = out / 'out' / tag / step / 'census'
    found = set()
    for directory, _, names in os.walk(root):
        found.update(os.path.relpath(os.path.join(directory, n), root) for n in names if n.endswith(SUFFIX))
    return found


rows, missing, not_clean, notes = [], [], [], []
for step in steps:
    screens_of = {}
    for tag in tags:
        rc, seconds, line = ran.get((tag, step), ('', '', 'not run'))
        summary = out / 'out' / tag / step / 'summary.json'
        screens = problems = ''
        reasons = []
        if summary.is_file():
            try:
                result = [r for r in json.loads(summary.read_text()) if r.get('step') == step]
                if not result:
                    reasons.append('summary.json has no entry for the step')
                elif 'census_screens' not in result[0]:
                    reasons.append('summary.json has no census_screens')
                else:
                    screens = str(result[0]['census_screens']); problems = str(len(result[0].get('census_errors', [])))
                    screens_of[tag] = int(result[0]['census_screens'])
            except (ValueError, AttributeError, TypeError, KeyError):
                problems = 'unreadable summary.json'; reasons.append('unreadable summary.json')
        else:
            missing.append(f'{tag}\t{step}'); reasons.append('no summary.json')
        if rc != '0':
            reasons.append(f'sweep rc={rc or "none (not run)"}')
        if tag in screens_of and screens_of[tag] == 0 and reference.get(step, 0) > 0:
            reasons.append(f'no census screens (local census: {reference[step]})')
        if tag in base_runs and exact_reference and tag in screens_of and step in reference \
                and screens_of[tag] != reference[step] and screens_of[tag] != 0:
            reasons.append(f'{screens_of[tag]} census screens, local census of this commit has {reference[step]}')
        not_clean.extend(f'{tag}\t{step}\t{r}' for r in reasons)
        rows.append('\t'.join([tag, step, rc, seconds, line.replace('\t', ' ')[:170], screens, problems]))
    r1, r2 = census_files(base_runs[0], step), census_files(base_runs[1], step)
    if r1 != r2:
        only1, only2 = sorted(r1 - r2), sorted(r2 - r1)
        detail = '; '.join(x for x in [f'{len(only1)} only in r1 (e.g. {only1[0]})' if only1 else '',
                                       f'{len(only2)} only in r2 (e.g. {only2[0]})' if only2 else ''] if x)
        not_clean.append(f'{base}-r1/r2\t{step}\tthe base runs have different census screens ({detail}): '
                         'those screens are volatile and never compared')
    floor = max((screens_of.get(t, 0) for t in base_runs), default=0)
    for h in heads:
        if h in screens_of and screens_of[h] < floor:
            notes.append(f'{h}\t{step}\t{screens_of[h]} census screens, the base has {floor}')

(diff_dir / 'results.tsv').write_text('tag\tstep\trc\tseconds\tsweep\tcensus_screens\tcensus_problems\n' + ''.join(r + '\n' for r in rows))
(diff_dir / 'missing.txt').write_text(''.join(m + '\n' for m in missing))
(diff_dir / 'not-clean.txt').write_text(''.join(m + '\n' for m in not_clean))
failed = sum(1 for r in rows if r.split('\t')[2] != '0')
bad_steps = sorted({n.split('\t')[1] for n in not_clean}, key=steps.index)
print(f'## Volt census (G7): base `{base}` {plan["base"]["sha"]}\n')
for h in plan['heads']:
    print(f'- head `{h["tag"]}` {h["sha"]}')
print(f'\n{len(steps)} step(s) x {len(tags)} run(s) = {len(rows)} runs on {len(plan["shards"])} shard(s); '
      f'{failed} run(s) with a non-zero sweep rc; **{len(missing)} missing summary.json**; '
      f'**{len(bad_steps)} step(s) not clean** (their PASS proves nothing until rerun).\n')
print(f'Screen counts checked against the local census of {reference_sha}'
      + (' (this base): every base run must match it.\n' if exact_reference else ' (not this base): only zero-screen runs are flagged.\n'))
for h in heads:
    total = diff_dir / f'diff-{h}-total.txt'
    print(f'### {h} vs {base}-r1 + {base}-r2\n\n```\n{total.read_text().strip() if total.is_file() else "(no diff)"}\n```\n')
if not_clean:
    print('### Not clean (rerun these steps before trusting their result)\n\n```\n'
          + '\n'.join(n.replace('\t', ' ') for n in not_clean) + '\n```\n')
if notes:
    print('### Heads with fewer census screens than the base (their diff lists each as "only in base")\n\n```\n'
          + '\n'.join(n.replace('\t', ' ') for n in notes) + '\n```\n')
if missing:
    print('### Missing runs (no summary.json)\n\n```\n' + '\n'.join(m.replace('\t', ' ') for m in missing) + '\n```')
sys.exit(1 if missing or not_clean else 0)
