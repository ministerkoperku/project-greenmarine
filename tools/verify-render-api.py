#!/usr/bin/env python3
"""Checks the controlled magenta-mesh fixture; not a generic shader image validator."""
import json
import sys
from pathlib import Path
import subprocess


def dimensions(path):
    return tuple(map(int, subprocess.run(['magick', 'identify', '-format', '%w %h', str(path)],
        check=True, capture_output=True, text=True).stdout.split()))


def magenta_fraction(path):
    width, height = dimensions(path)
    crop = f'{int(width*.5)}x{int(height*.6)}+{int(width*.25)}+{int(height*.2)}'
    condition = '(r>35/255 && r>g*2.5 && b>g*2.5 && b>15/255)?1:0'
    return float(subprocess.run(['magick', str(path), '-crop', crop, '+repage', '-fx', condition,
        '-format', '%[fx:mean]', 'info:'], check=True, capture_output=True, text=True).stdout)


def verify(folder):
    folder = Path(folder)
    result = json.loads((folder / 'result.json').read_text())
    if result.get('status') != 'complete':
        raise ValueError('Runtime did not complete')
    for flag in ('shaderFailureIsolated', 'wrongThreadResetRejected', 'borrowedSceneSurvived'):
        if result.get(flag) is not True:
            raise ValueError(f'Missing acceptance flag: {flag}')
    resources = result['openResources']
    if resources['textureAllocations'] != 3 or resources['programCompilations'] != 2:
        raise ValueError('Graph did not reuse its resources')
    if result['resizeResources']['textureAllocations'] != 6:
        raise ValueError('Resize did not replace exactly three attachments')
    for stage in ('openTiming', 'reloadTiming'):
        for cost in ('cpu', 'gpu'):
            if result[stage][cost]['count'] < 40 or result[stage][cost]['meanMs'] <= 0:
                raise ValueError(f'Missing {cost} measurements for {stage}')
    fractions = {}
    sizes = {}
    for name in ('baseline', 'geometry', 'occluded', 'resized', 'reloaded', 'released'):
        path = folder / f'{name}.png'
        fractions[name] = magenta_fraction(path)
        sizes[name] = dimensions(path)
    for name in ('geometry', 'resized', 'reloaded'):
        if fractions[name] < .08:
            raise ValueError(f'Contributed geometry missing: {name}')
    for name in ('baseline', 'occluded', 'released'):
        if fractions[name] > .005:
            raise ValueError(f'Geometry leaked into {name}')
    if sizes['baseline'] != sizes['geometry'] or sizes['geometry'] == sizes['resized']:
        raise ValueError('Resize captures did not exercise different dimensions')
    return fractions


if __name__ == '__main__':
    try:
        print(json.dumps(verify(sys.argv[1]), indent=2))
    except (ValueError, KeyError, OSError) as error:
        print(f'FAIL: {error}', file=sys.stderr)
        sys.exit(1)
