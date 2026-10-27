#!/usr/bin/env python3
"""Reduce a navee-charge-recordings-v1 export to the numeric replay fixture (sessions.csv).

Keeps only what the estimator and the learning store read: charger, stop reason, time since the
recording started, battery %, pack volts, signed amps, the gap flag and the prediction recorded at
the time. Drops recording and profile IDs, wall-clock dates and temperature.
Usage: python3 -I strip_export.py EXPORT.json OUT.csv
"""
import csv, json, sys

def number(value, places=None):
    if value is None:
        return ''
    return repr(float(value)) if places is None else ('%.' + str(places) + 'f') % value

def main(source, out):
    with open(source, encoding='utf-8') as handle:
        export = json.load(handle)
    if export.get('format') != 'navee-charge-recordings-v1':
        raise SystemExit('Unexpected export format')
    with open(out, 'w', newline='', encoding='utf-8') as handle:
        handle.write('# Owner UT5 MAX charge recordings, chronological; replay input for fast-charge-taper.md.\n')
        handle.write('# elapsed_ms counts from each recording start. No IDs, dates or wall clocks.\n')
        rows = csv.writer(handle, lineterminator='\n')
        rows.writerow(['session', 'charger', 'stop', 'elapsed_ms', 'soc', 'volts', 'amps', 'gap',
                       'recorded_version', 'recorded_full', 'recorded_source', 'recorded_learned'])
        for index, session in enumerate(export['sessions']):
            if session['schedule_pauses'] or session['charger_pauses']:
                raise SystemExit('Pauses are not represented in this fixture')
            start = session['started_elapsed_ms']
            for sample in session['samples']:
                estimate = sample.get('estimate') or {}
                rows.writerow([index, session['charger'], session['stop_reason'] or '', sample['elapsed_ms'] - start,
                               number(sample['battery_percent']), number(sample['voltage_v']), number(sample['current_a']),
                               1 if sample['gap_before'] else 0, estimate.get('estimator_version') or '',
                               number(estimate.get('remaining_minutes_to_full'), 4), estimate.get('full_source') or '',
                               estimate.get('learned_sessions') or 0])

if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
