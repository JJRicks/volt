#!/usr/bin/env python3
"""Validate a census request (ops/census/request.json) and plan the run.

usage: python3 -I ops/census/plan.py REQUEST PLANDIR [--github-output FILE]

The request comes from a pushed run branch, so nothing in it is trusted: every SHA must be 40
lowercase hex characters, every tag [a-z0-9-]+ (at most 40), and every step a line of
ops/census/steps.txt. PLANDIR receives
  trees.txt   "TAG SHA" per tree, base first (what build-trees.sh builds)
  plan.json   base tag and SHA, head tags, steps and shards (what shard.sh and diff use)
  steps.txt   the requested steps, one per line, in steps.txt order
and --github-output gets base_tag, base_sha and shards (the run job's matrix include list).
"""
import json
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
SHA = re.compile(r'[0-9a-f]{40}')
TAG = re.compile(r'[a-z0-9][a-z0-9-]{0,39}')
MAX_SHARDS = 12
MAX_HEADS = 8


def fail(message):
    sys.exit('census request: ' + message)


def tree(entry, what):
    if not isinstance(entry, dict) or set(entry) != {'tag', 'sha'}:
        fail(f'{what} must be {{"tag": ..., "sha": ...}}')
    tag, sha = entry['tag'], entry['sha']
    if not isinstance(tag, str) or not TAG.fullmatch(tag):
        fail(f'{what} tag must match [a-z0-9][a-z0-9-]* (at most 40 characters)')
    if not isinstance(sha, str) or not SHA.fullmatch(sha):
        fail(f'{what} sha must be a full 40-character lowercase commit SHA')
    return tag, sha


def main():
    args = sys.argv[1:]
    output = None
    if len(args) == 4 and args[2] == '--github-output':
        output = Path(args[3]); args = args[:2]
    if len(args) != 2:
        fail('usage: plan.py REQUEST PLANDIR [--github-output FILE]')
    request_path, plan_dir = Path(args[0]), Path(args[1])
    if request_path.stat().st_size > 64 * 1024:
        fail('request.json is too large')
    request = json.loads(request_path.read_text(encoding='utf-8'))
    if not isinstance(request, dict) or not {'base', 'heads'} <= set(request) <= {'base', 'heads', 'steps'}:
        fail('expected {"base": ..., "heads": [...], "steps": "all" or [...]}')
    base_tag, base_sha = tree(request['base'], 'base')
    heads_in = request['heads']
    if not isinstance(heads_in, list) or not 1 <= len(heads_in) <= MAX_HEADS:
        fail(f'heads must be a list of 1 to {MAX_HEADS} trees')
    heads = [tree(entry, f'heads[{i}]') for i, entry in enumerate(heads_in)]
    reserved = {base_tag, base_tag + '-r1', base_tag + '-r2'}
    seen = set()
    for tag, _ in heads:
        if tag in reserved or tag in seen:
            fail(f'head tag {tag} repeats a tag or clashes with the base runs ({", ".join(sorted(reserved))})')
        seen.add(tag)

    known = [line.strip() for line in (HERE / 'steps.txt').read_text().splitlines() if line.strip()]
    wanted = request.get('steps', 'all')
    if wanted == 'all':
        steps = known
    elif isinstance(wanted, list) and wanted and all(isinstance(s, str) for s in wanted):
        unknown = [s for s in wanted if s not in known]
        if unknown:
            fail('unknown step(s) (not in ops/census/steps.txt): ' + ', '.join(repr(s)[:60] for s in unknown[:5]))
        steps = [s for s in known if s in set(wanted)]
    else:
        fail('steps must be "all" or a non-empty list of step names')

    weights = {}
    for line in (HERE / 'weights.tsv').read_text().splitlines():
        if line and not line.startswith('#'):
            name, seconds = line.split('\t')
            weights[name] = int(seconds)
    # Longest first onto the lightest shard; every tag of a step runs on the same shard.
    count = min(MAX_SHARDS, len(steps))
    shards = [dict(index=i + 1, steps=[], load=0) for i in range(count)]
    for step in sorted(steps, key=lambda s: (-weights.get(s, 60), known.index(s))):
        lightest = min(shards, key=lambda s: (s['load'], s['index']))
        lightest['steps'].append(step); lightest['load'] += weights.get(step, 60)
    for shard in shards:
        shard['steps'].sort(key=known.index)

    plan_dir.mkdir(parents=True, exist_ok=True)
    (plan_dir / 'trees.txt').write_text(''.join(f'{t} {s}\n' for t, s in [(base_tag, base_sha)] + heads))
    (plan_dir / 'steps.txt').write_text(''.join(s + '\n' for s in steps))
    plan = dict(base=dict(tag=base_tag, sha=base_sha), heads=[dict(tag=t, sha=s) for t, s in heads], steps=steps,
                shards=[dict(index=s['index'], steps=s['steps'], local_seconds_per_run=s['load']) for s in shards])
    (plan_dir / 'plan.json').write_text(json.dumps(plan, indent=2) + '\n')
    matrix = [dict(index=s['index'], steps=' '.join(s['steps'])) for s in shards]
    if output:
        with output.open('a') as handle:
            handle.write(f'base_tag={base_tag}\nbase_sha={base_sha}\nshards={json.dumps(matrix, separators=(",", ":"))}\n')
    runs = len(steps) * (2 + len(heads))
    print(f'base {base_tag} {base_sha}; heads {" ".join(t for t, _ in heads)}; {len(steps)} step(s), {runs} run(s), {count} shard(s)')
    for s in shards:
        print(f'  shard {s["index"]}: ~{s["load"] * (2 + len(heads))} s local-equivalent: {" ".join(s["steps"])}')


if __name__ == '__main__':
    main()
