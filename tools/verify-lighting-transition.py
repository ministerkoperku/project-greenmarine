#!/usr/bin/env python3
"""Check actual final-image GPU samples, independently of native readiness counters."""
import argparse
import json
import statistics
from pathlib import Path


def verify(folder, baseline=False):
    folder = Path(folder)
    result = json.loads((folder / 'result.json').read_text())
    if result.get('status') != 'complete':
        raise ValueError('Incomplete lighting fixture')
    samples = sorted(result['gpuSamples'], key=lambda row: row['frameNanos'])
    reports = []
    for index, event in enumerate(result['events'], 1):
        # First edit includes the initial screenshot and shader/readback warmup.
        if index == 1:
            continue
        requested = event['requestedNanos']
        prior = [s for s in samples if s['frameNanos'] < requested]
        current = [s for s in samples if s['event'] == index and s['frameNanos'] >= requested]
        if not prior or len(current) < 8:
            raise ValueError('Missing final-color transition GPU samples')
        before = statistics.median(s['receiverColor'] for s in prior[-5:])
        after = statistics.median(s['receiverColor'] for s in current[-5:])
        delta = abs(after-before)
        if delta < .08:
            raise ValueError('Lighting change too small to test popping')
        sequence = [before] + [s['receiverColor'] for s in current]
        fraction = max(abs(b-a) for a,b in zip(sequence,sequence[1:])) / delta
        settled = next((s for s in current if abs(s['receiverColor']-after) <= delta*.10), None)
        if settled is None:
            raise ValueError('Lighting never settled')
        native = event['gpuChangedFrameNanos']
        settling_ms = max(0,(settled['frameNanos']-native)/1e6)
        controls = [s['controlColor'] for s in current]
        if max(controls)-min(controls) > .05:
            raise ValueError('Unchanged control flickered')
        row = {'event':index,'kind':event['kind'],'colorDelta':delta,
               'largestFrameFraction':fraction,'settlingFromNativeMs':settling_ms}
        if not baseline and (fraction > .65 or settling_ms > 140):
            raise ValueError('Visible lighting step or excessive lag: '+str(row))
        reports.append(row)
    if len(reports) < 4:
        raise ValueError('Missing repeated shadow add/undo cases')
    return {'status':'baseline_measured' if baseline else 'accepted',
            'accepted':not baseline,'events':reports,
            'limits':['Controlled fixed-camera floor receiver; motion/depth rejection checked separately.',
                      'Not proof of an imperceptible transition in every scene.']}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('folder', type=Path)
    parser.add_argument('--baseline', action='store_true')
    args = parser.parse_args()
    report = verify(args.folder,args.baseline)
    (args.folder/'transition-verification.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,indent=2))
