"""Fit the standard charger's 99% stage (estimator ut5-stock-finish-v9) from data already in the repository.

Inputs (no new recordings):
  ../fast-charge-taper-v7/sessions.csv           the owner's anonymized UT5 MAX recordings; the five complete
                                                 standard-charger charges (sessions 2, 3, 8, 9, 10) fit the model
  ../stock-startup-v5/scooter-b/paired-input.csv another UT5 MAX on its own standard charger, held out entirely

Model, minutes from a 99% reading to the first displayed 100%:
  tail     = OFFSET + TAU * ln(max(I, 0.68) / 0.68)
  headroom = clamp(CV_SET - CV_DROP * I - V, 0, 0.4)
  weight   = clamp((I - 1.3) / (1.7 - 1.3), 0, 1)
  minutes  = tail + weight * AH_PER_VOLT * headroom / I * 60
CV_SET and CV_DROP: least squares of volts on amps over constant-voltage rows (current below 97% of the charge's
highest at 99%). OFFSET and TAU: least squares of minutes left on ln(I / 0.68) over the same rows. AH_PER_VOLT:
least squares through the origin of the leftover minutes on headroom / I * 60 over constant-current rows. The
headroom cap and the fade currents are fixed by hand, not fitted.

  python3 fit_stock_finish.py           fit, then leave-one-charge-out and held-out-scooter results (also under
                                        pack-voltage offsets of 0.15 and 0.3 V)
  python3 fit_stock_finish.py --check   also verify the constants in Ut5MaxStockChargeReference.java (CI)
"""
import csv, math, os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, '..', '..', '..', '..'))
END, HMAX, I_LOW, I_HIGH = 0.68, 0.4, 1.3, 1.7
OWNER = (2, 3, 8, 9, 10)


def owner_finishes():
    rows = {}
    with open(os.path.join(HERE, '..', 'fast-charge-taper-v7', 'sessions.csv')) as f:
        for r in csv.DictReader(line for line in f if not line.startswith('#')):
            s = int(r['session'])
            if s in OWNER and r['charger'] == 'STOCK':
                rows.setdefault(s, []).append((int(r['elapsed_ms']), float(r['soc']), float(r['volts']), abs(float(r['amps']))))
    out = {}
    for s, rs in rows.items():
        done = next(t for (t, soc, _, _), (_, before, _, _) in zip(rs[1:], rs) if soc >= 100 and before < 100)
        out['A%d' % s] = [(v, a, (done - t) / 60000) for t, soc, v, a in rs if soc >= 99 and soc < 100 and t < done]
    return out


def scooter_b():
    with open(os.path.join(HERE, '..', 'stock-startup-v5', 'scooter-b', 'paired-input.csv')) as f:
        return [(float(r['volts']), abs(float(r['amps'])), float(r['truth_full'])) for r in csv.DictReader(f)
                if 99 <= float(r['soc']) < 100 and r['truth_full'] and float(r['truth_full']) > 0]


def line(xs, ys):
    n = len(xs); sx = sum(xs); sy = sum(ys); sxx = sum(x * x for x in xs); sxy = sum(x * y for x, y in zip(xs, ys))
    b = (n * sxy - sx * sy) / (n * sxx - sx * sx)
    return (sy - b * sx) / n, b


def fit(charges):
    cv = [(v, a, t) for c in charges for (v, a, t) in c if a < max(x[1] for x in c) * .97]
    cv_set, slope = line([a for _, a, _ in cv], [v for v, _, _ in cv])
    offset, tau = line([math.log(max(a, END) / END) for _, a, _ in cv], [t for _, _, t in cv])
    num = den = 0.0
    for c in charges:
        top = max(x[1] for x in c)
        for v, a, t in c:
            if a >= top * .97:
                x = max(0.0, cv_set + slope * a - v) / a * 60
                num += x * (t - offset - tau * math.log(max(a, END) / END)); den += x * x
    return {'CV_SET': cv_set, 'CV_DROP': -slope, 'OFFSET': offset, 'TAU': tau, 'AH_PER_VOLT': num / den}


def minutes(p, v, a):
    tail = p['OFFSET'] + p['TAU'] * math.log(max(a, END) / END)
    headroom = min(HMAX, max(0.0, p['CV_SET'] - p['CV_DROP'] * a - v))
    weight = min(1.0, max(0.0, (a - I_LOW) / (I_HIGH - I_LOW)))
    return tail + weight * p['AH_PER_VOLT'] * headroom / a * 60


def score(p, rows, dv=0.0):
    e = [minutes(p, v + dv, a) - t for v, a, t in rows]
    ab = sorted(abs(x) for x in e)
    return sum(ab) / len(ab), ab[int(.9 * (len(ab) - 1))], min(e), max(e)


def main():
    owner = owner_finishes(); b = scooter_b()
    full = fit(list(owner.values()))
    print('constants (all five owner finishes): ' + ', '.join('%s %.4f' % kv for kv in full.items()))
    print('held out        offset   MAE    p90    most short  most long   (minutes, 99% readings)')
    for name in sorted(owner, key=lambda k: int(k[1:])) + ['B']:
        p = full if name == 'B' else fit([c for k, c in owner.items() if k != name])
        for dv in (0, -.15, .15, -.3, .3):
            print('%-15s %+5.2f V %6.2f %6.2f %+10.1f %+10.1f' % (
                'scooter B' if name == 'B' else 'owner ' + name[1:], dv, *score(p, b if name == 'B' else owner[name], dv)))
    if '--check' in sys.argv:
        src = open(os.path.join(ROOT, 'src', 'com', 'jjricks', 'volt', 'Ut5MaxStockChargeReference.java')).read()
        names = {'CV_SET': 'FINISH_CV_SET', 'CV_DROP': 'FINISH_CV_DROP', 'OFFSET': 'FINISH_OFFSET', 'TAU': 'FINISH_TAU',
                 'AH_PER_VOLT': 'FINISH_AH_PER_VOLT'}
        bad = []
        for key, java in names.items():
            m = re.search(java + r'\s*=\s*([0-9.]+)', src)
            if not m or abs(float(m.group(1)) - round(full[key], 4)) > 1e-9:
                bad.append('%s: Java %s, fit %.4f' % (java, m.group(1) if m else 'missing', full[key]))
        if bad:
            sys.exit('stock-finish-v9 constants differ from the fit:\n  ' + '\n  '.join(bad))
        print('check: Java constants match the fit')


if __name__ == '__main__':
    main()
