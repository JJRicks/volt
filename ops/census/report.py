#!/usr/bin/env python3
"""After diff.sh: every (tag, step) run's result in one compact file, and the job summary.

usage: python3 -I ops/census/report.py PLANDIR OUT DIFFDIR
Writes DIFFDIR/results.tsv (tag, step, rc, seconds, the sweep's PASS/FAIL line, census screens and
problems from summary.json), DIFFDIR/missing.txt (tag and step of every run without summary.json),
DIFFDIR/results.txt (every shard's results.txt), and prints Markdown for $GITHUB_STEP_SUMMARY.
Exits 1 when any run is missing.
"""
import json
import re
import sys
from pathlib import Path

plan_dir, out, diff_dir = map(Path, sys.argv[1:4])
plan = json.loads((plan_dir / 'plan.json').read_text())
base = plan['base']['tag']; heads = [h['tag'] for h in plan['heads']]; steps = plan['steps']
tags = [base + '-r1', *heads, base + '-r2']
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
rows, missing = [], []
for step in steps:
    for tag in tags:
        rc, seconds, line = ran.get((tag, step), ('', '', 'not run'))
        summary = out / 'out' / tag / step / 'summary.json'
        screens = problems = ''
        if summary.is_file():
            try:
                result = [r for r in json.loads(summary.read_text()) if r.get('step') == step]
                if result:
                    screens = str(result[0].get('census_screens', '')); problems = str(len(result[0].get('census_errors', [])))
            except (ValueError, AttributeError):
                problems = 'unreadable summary.json'
        else:
            missing.append(f'{tag}\t{step}')
        rows.append('\t'.join([tag, step, rc, seconds, line.replace('\t', ' ')[:170], screens, problems]))
(diff_dir / 'results.tsv').write_text('tag\tstep\trc\tseconds\tsweep\tcensus_screens\tcensus_problems\n' + ''.join(r + '\n' for r in rows))
(diff_dir / 'missing.txt').write_text(''.join(m + '\n' for m in missing))
failed = sum(1 for r in rows if r.split('\t')[2] not in ('0',))
print(f'## Volt census (G7): base `{base}` {plan["base"]["sha"]}\n')
for h in plan['heads']:
    print(f'- head `{h["tag"]}` {h["sha"]}')
print(f'\n{len(steps)} step(s) x {len(tags)} run(s) = {len(rows)} runs on {len(plan["shards"])} shard(s); '
      f'{failed} run(s) with a non-zero sweep rc; **{len(missing)} missing summary.json**.\n')
for h in heads:
    total = diff_dir / f'diff-{h}-total.txt'
    print(f'### {h} vs {base}-r1 + {base}-r2\n\n```\n{total.read_text().strip() if total.is_file() else "(no diff)"}\n```\n')
if missing:
    print('### Missing runs (no summary.json)\n\n```\n' + '\n'.join(m.replace('\t', ' ') for m in missing) + '\n```')
sys.exit(1 if missing else 0)
