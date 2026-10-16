#!/usr/bin/env python3
"""Compare matching, complete terrain profiles; reject viewport/cap/throttle drift."""
import argparse
import json
import statistics
from pathlib import Path

NAMES = ['hardware-still', 'hardware-moving', 'raster-still', 'raster-moving', 'disabled-still']


def compare(before, after):
    reports = []
    for data in [before, after]:
        if data.get('status') != 'complete' or [p['name'] for p in data['phases']] != NAMES:
            raise ValueError('Both profiles must complete all five phases')
    for old, new in zip(before['phases'], after['phases']):
        for phase in [old, new]:
            if phase['throttleReason'] != 'NONE' or phase['vsync']:
                raise ValueError('Throttled or VSync-enabled phase: ' + phase['name'])
            if phase['framerateSetting'] < 260 or phase['effectiveFramerateLimit'] != phase['framerateSetting']:
                raise ValueError('Unexpected frame limit: ' + phase['name'])
        for key in ['width', 'height', 'framerateSetting', 'effectiveFramerateLimit', 'sunAngle', 'rainBrightness', 'rainLevel']:
            if old[key] != new[key]:
                raise ValueError('Mismatched ' + key + ': ' + old['name'])
        row = {'phase': old['name'], 'width': old['width'], 'height': old['height']}
        for label, data, phase in [('before', before, old), ('after', after, new)]:
            values = sorted(f['intervalMs'] for f in data['frames'] if f['phase'] == phase['name'])
            if len(values) < 100 or any(v <= 0 for v in values):
                raise ValueError('Insufficient valid frame samples')
            metrics = {}
            for key in ['generation', 'packNanos', 'prepareNanos', 'publishNanos']:
                metrics[key] = phase['endMetrics'][key] - phase['startMetrics'][key]
            row[label] = {'frames': len(values), 'meanMs': statistics.mean(values),
                          'medianMs': statistics.median(values), 'p95Ms': values[int(len(values)*.95)],
                          'p99Ms': values[int(len(values)*.99)], 'maxMs': max(values), 'metricsDelta': metrics,
                          'worldGpu': phase['worldGpu']}
            if phase['name'] != 'disabled-still':
                for key in ['surfaceCpu', 'surfaceGpu', 'composeCpu', 'composeGpu']:
                    row[label][key] = phase[key]
        reports.append(row)
    return {'accepted': True, 'scope': 'Same natural-terrain route, instrumented local client; no universal FPS claim', 'phases': reports}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('before', type=Path)
    parser.add_argument('after', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    result = compare(json.loads(args.before.read_text()), json.loads(args.after.read_text()))
    text = json.dumps(result, indent=2) + '\n'
    if args.output:
        args.output.write_text(text)
    print(text)
