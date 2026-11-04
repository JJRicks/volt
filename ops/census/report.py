#!/usr/bin/env python3
"""After diff.sh: every (tag, step) run's result in one compact file, the checks that make a step's
comparison trustworthy, and the job summary.

usage: python3 -I ops/census/report.py PLANDIR OUT DIFFDIR
Writes DIFFDIR/results.tsv (tag, step, rc, seconds, the sweep's PASS/FAIL line, census screens and
problems from summary.json), DIFFDIR/missing.txt (tag and step of every run without summary.json),
DIFFDIR/not-clean.txt (tag, step and reason of every run that makes its step's comparison
untrustworthy, see below), DIFFDIR/census-failed.txt (head, step, census_diff rc and its PASS/FAILED
line for every step whose census_diff did not PASS, read from diff.sh's diff-<head>-summary.txt: the
local census verdict), DIFFDIR/gaps.txt (the cloud-gaps.tsv steps of this run and why, kept out of
both of those), DIFFDIR/verdict.txt (one line per head: PASS or FAILED with its step count),
DIFFDIR/results.txt (every shard's results.txt), and prints Markdown for $GITHUB_STEP_SUMMARY.
Exits 1 when any run of a covered step is missing or not clean (census.yml fails the diff job
separately when census-failed.txt is not empty).

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
A step listed in cloud-gaps.tsv (a step the cloud emulator cannot run; the laptop census must) is
NOT COVERED: its runs and its census_diff result go to gaps.txt only, never to missing.txt,
not-clean.txt or census-failed.txt, so they never decide the job's colour, and its PASS proves
nothing. Runs that shard.sh retried after
a known cloud flake are listed (the retry's result is the one used). A head with fewer census screens
than the base is listed too, as a note: census_diff.py reports each screen it lacks ("only in base"),
so that already fails its step's diff.
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

gaps = {}
gap_file = HERE / 'cloud-gaps.tsv'
if gap_file.is_file():
    for line in gap_file.read_text().splitlines():
        if line and not line.startswith('#'):
            name, _, why = line.partition('\t'); gaps[name.strip()] = why.strip()

LINE = re.compile(r'^\S+ \[\S+ (\d+)s\] (\S+) (\S+): rc=(-?\d+) ?(.*)$')
RETRY = re.compile(r'^\S+ retry: (\S+) (\S+) (.*)$')
NOT_RUN = re.compile(r'^\S+ not run: (\S+) (\S+) \((.*)\)$')
BOOTED = re.compile(r'^\S+ booted \S+ \((system-images;[^,]+),')
ran, retries, skipped, images = {}, [], {}, {}
shard_logs = sorted((out / 'logs').glob('shard-*/results.txt'), key=lambda p: int(p.parent.name.split('-')[1]))
with (diff_dir / 'results.txt').open('w') as merged:
    for path in shard_logs:
        merged.write(f'## {path.parent.name}\n')
        for line in path.read_text(errors='replace').splitlines():
            merged.write(line + '\n')
            m = LINE.match(line)
            if m: ran[(m.group(2), m.group(3))] = (m.group(4), m.group(1), m.group(5))
            m = RETRY.match(line)
            if m: retries.append(f'{m.group(1)}\t{m.group(2)}\t{m.group(3)}')
            m = NOT_RUN.match(line)
            if m: skipped[(m.group(1), m.group(2))] = m.group(3)
            m = BOOTED.match(line)
            if m: images.setdefault(m.group(1), []).append(path.parent.name)


def census_files(tag, step):
    root = out / 'out' / tag / step / 'census'
    found = set()
    for directory, _, names in os.walk(root):
        found.update(os.path.relpath(os.path.join(directory, n), root) for n in names if n.endswith(SUFFIX))
    return found


rows, missing, not_clean, notes, gap_runs = [], [], [], [], []
for step in steps:
    screens_of = {}
    for tag in tags:
        rc, seconds, line = ran.get((tag, step), ('', '', 'not run: ' + skipped.get((tag, step), 'no result line')))
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
            reasons.append('no summary.json')
            if step not in gaps:
                missing.append(f'{tag}\t{step}')
        if rc != '0':
            reasons.append(f'sweep rc={rc}' if rc else line)
        if tag in screens_of and screens_of[tag] == 0 and reference.get(step, 0) > 0:
            reasons.append(f'no census screens (local census: {reference[step]})')
        if tag in base_runs and exact_reference and tag in screens_of and step in reference \
                and screens_of[tag] != reference[step] and screens_of[tag] != 0:
            reasons.append(f'{screens_of[tag]} census screens, local census of this commit has {reference[step]}')
        (gap_runs if step in gaps else not_clean).extend(f'{tag}\t{step}\t{r}' for r in reasons)
        rows.append('\t'.join([tag, step, rc, seconds, line.replace('\t', ' ')[:170], screens, problems]))
    r1, r2 = census_files(base_runs[0], step), census_files(base_runs[1], step)
    if r1 != r2 and step not in gaps:
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

# The census verdict itself, as the local census reads it from diff.sh: a step passes for a head
# only when its summary line has rc=0 and "census_diff: PASS". A step without a line (diff.sh did
# not get to it) counts as failed.
SUMMARY = re.compile(r'^(\S+) rc=(-?\d+) missing=\[[^\]]*\] ?(.*)$')
TEXTDIFF = re.compile(r'(\d+) text difference\(s\)')
census_failed, verdicts, verdict_lines = [], {}, []
for h in heads:
    lines = {}
    summary_file = diff_dir / f'diff-{h}-summary.txt'
    if summary_file.is_file():
        for text in summary_file.read_text(errors='replace').splitlines():
            m = SUMMARY.match(text)
            if m: lines[m.group(1)] = (m.group(2), m.group(3))
    bad, textdiff = [], 0
    for step in steps:
        if step in gaps:
            continue
        rc, verdict = lines.get(step, ('', 'no diff.sh summary line'))
        if rc != '0' or not verdict.startswith('census_diff: PASS'):
            bad.append(step); census_failed.append(f'{h}\t{step}\trc={rc or "none"}\t{verdict[:200]}')
            m = TEXTDIFF.search(verdict)
            if m: textdiff += int(m.group(1))
    covered = sum(1 for st in steps if st not in gaps)
    verdicts[h] = (bad, textdiff, covered)
    verdict_lines.append(f'{h}\tFAILED\t{len(bad)} of {covered} step(s)\t{textdiff} text difference(s)' if bad
                         else f'{h}\tPASS\t{covered} of {covered} step(s)\t0 text difference(s)')
(diff_dir / 'census-failed.txt').write_text(''.join(m + '\n' for m in census_failed))
(diff_dir / 'verdict.txt').write_text(''.join(m + '\n' for m in verdict_lines))
gap_steps = [st for st in steps if st in gaps]
(diff_dir / 'gaps.txt').write_text(''.join(f'{st}\t{gaps[st]}\n' for st in gap_steps)
                                   + ''.join(f'run\t{g}\n' for g in gap_runs))
failed = sum(1 for r in rows if r.split('\t')[2] != '0')
bad_steps = sorted({n.split('\t')[1] for n in not_clean}, key=steps.index)
print(f'## Volt census (G7): base `{base}` {plan["base"]["sha"]}\n')
for h in plan['heads']:
    print(f'- head `{h["tag"]}` {h["sha"]}')
print('\n**Census verdict** (census_diff per step, as the local diff.sh; '
      + (f'{len(gap_steps)} cloud-gap step(s) left out' if gap_steps else 'no cloud-gap steps') + '):\n')
for h in heads:
    bad, textdiff, covered = verdicts[h]
    print(f'- `{h}`: **census FAILED: {len(bad)} of {covered} step(s)**, {textdiff} text difference(s) '
          f'({", ".join(bad[:12])}{", ..." if len(bad) > 12 else ""})' if bad
          else f'- `{h}`: **census PASS: {covered} of {covered} step(s)**')
print(f'\n{len(steps)} step(s) x {len(tags)} run(s) = {len(rows)} runs on {len(plan["shards"])} shard(s); '
      f'{failed} run(s) with a non-zero sweep rc; **{len(missing)} missing summary.json**; '
      f'**{len(bad_steps)} step(s) not clean** (their PASS proves nothing until rerun); '
      f'**{len(gap_steps)} step(s) not covered** (cloud-gaps.tsv: the laptop census must run them).\n')
print('System image: ' + ('; '.join(f'`{i}` on {len(s)} shard(s)' + (f' ({", ".join(s)})' if len(images) > 1 else '')
                                   for i, s in sorted(images.items())) or 'none booted')
      + ('. **Shards used different images**: compare a step only within its shard.' if len(images) > 1 else '') + '\n')
print(f'Screen counts checked against the local census of {reference_sha}'
      + (' (this base): every base run must match it.\n' if exact_reference else ' (not this base): only zero-screen runs are flagged.\n'))
for h in heads:
    total = diff_dir / f'diff-{h}-total.txt'
    print(f'### {h} vs {base}-r1 + {base}-r2\n\n```\n{total.read_text().strip() if total.is_file() else "(no diff)"}\n```\n')
if not_clean:
    print('### Not clean (rerun these steps before trusting their result)\n\n```\n'
          + '\n'.join(n.replace('\t', ' ') for n in not_clean) + '\n```\n')
if census_failed:
    print('### Census FAILED (census_diff did not PASS; read diff-<head>.txt)\n\n```\n'
          + '\n'.join(n.replace('\t', ' ') for n in census_failed) + '\n```\n')
if gap_steps:
    print('### Steps the cloud cannot cover (cloud-gaps.tsv; not in the verdict or the job colour): '
          'run them on the laptop census\n\n```\n'
          + '\n'.join(f'{st}: {gaps[st]}' for st in gap_steps)
          + ''.join('\n  ' + g.replace('\t', ' ') for g in gap_runs) + '\n```\n')
if retries:
    print('### Runs retried after a known cloud flake (the retry\'s result is used)\n\n```\n'
          + '\n'.join(r.replace('\t', ' ') for r in retries) + '\n```\n')
if notes:
    print('### Heads with fewer census screens than the base (their diff lists each as "only in base")\n\n```\n'
          + '\n'.join(n.replace('\t', ' ') for n in notes) + '\n```\n')
if missing:
    print('### Missing runs (no summary.json)\n\n```\n' + '\n'.join(m.replace('\t', ' ') for m in missing) + '\n```')
sys.exit(1 if missing or not_clean else 0)
