#!/usr/bin/env bash
# usage: diff.sh BASETREE OUT DIFFDIR B1 B2 H STEPS -> DIFFDIR/diff-H.txt, diff-H-summary.txt and
# diff-H-total.txt (also on stdout). The local census/diff.sh, unchanged apart from its paths: per step,
# BASETREE's tools/l10n/census_diff.py --base OUT/out/B1/STEP/census --base OUT/out/B2/STEP/census
# OUT/out/H/STEP/census, its rc, the tags whose summary.json is missing, and its PASS/FAILED line.
WT=$1; R=$2; D=$3; B1=$4; B2=$5; H=$6; ST=$7
for t in "$B1" "$B2" "$H"; do [[ "$t" =~ ^[a-z0-9][a-z0-9-]*$ ]] || { echo "bad tag" >&2; exit 2; }; done
mkdir -p "$D"; OUT=$D/diff-$H.txt; SUM=$D/diff-$H-summary.txt; : > "$OUT"; : > "$SUM"
cd "$WT" || exit 2
while read -r step; do [ -z "$step" ] && continue
  echo "== $step" >> "$OUT"; miss=""
  for t in $B1 $B2 $H; do [ -f "$R/out/$t/$step/summary.json" ] || miss="$miss $t"; done
  python3 -I tools/l10n/census_diff.py --base "$R/out/$B1/$step/census" --base "$R/out/$B2/$step/census" "$R/out/$H/$step/census" >> "$OUT" 2>&1
  echo "$step rc=$? missing=[$miss] $(grep '^census_diff: \(PASS\|FAILED\)' "$OUT" | tail -1)" >> "$SUM"
done < "$ST"
python3 -I - "$SUM" <<'PY' | tee "$D/diff-$H-total.txt"
import re, sys
t = dict(screens=0, records=0, textdiff=0, volatile=0, form=0, num=0); bad = []; vol = []
for line in open(sys.argv[1]):
    m = re.search(r"(\d+) screen\(s\).*?(\d+) text difference\(s\), (\d+) of (\d+) record\(s\) volatile.*?(\d+) compared by form.*?(\d+) with numbers", line)
    if m:
        s, td, v, r, f, n = map(int, m.groups()); t["screens"] += s; t["textdiff"] += td; t["volatile"] += v; t["records"] += r; t["form"] += f; t["num"] += n
        if v: vol.append(f"{line.split()[0]}:{v}")
    if " rc=0 " not in line or "missing=[]" not in line: bad.append(line.strip()[:220])
print("TOTAL", t); print("VOLATILE BY STEP:", ", ".join(vol) or "none"); print("NOT CLEAN:", *(bad or ["none"]), sep="\n  ")
PY
