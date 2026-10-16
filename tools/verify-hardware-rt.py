#!/usr/bin/env python3
"""Reject successful-startup-only evidence and moving-camera surface regressions."""
import json
import subprocess
from pathlib import Path


def pixels(path):
    width, height = map(int, subprocess.check_output(['magick', 'identify', '-format', '%w %h', str(path)]).split())
    raw = subprocess.check_output(['magick', str(path), '-depth', '8', 'RGB:-'])
    return width, height, raw


def alignment(path):
    w, h, data = pixels(path)
    green = red = 0
    # Solid terrain ROI; alpha foliage/clouds outside this ROI remain documented limits.
    for y in range(int(h*.48), int(h*.75)):
        for x in range(int(w*.30), int(w*.75)):
            i = (y*w+x)*3
            r, g, b = data[i:i+3]
            green += g > 220 and r < 30 and b < 30
            red += r > 220 and g < 30 and b < 30
    if green+red < w*h*.04:
        raise ValueError('Missing hardware alignment diagnostic')
    return green/(green+red)


def verify(folder):
    folder = Path(folder)
    result = json.loads((folder/'result.json').read_text())
    if result['status'] != 'complete':
        raise ValueError('Incomplete hardware probe')
    hardware = result['capabilities']['hardwareRayTracing']
    if hardware:
        if result['packStatus']['state'] != 'LOADED' or result['traceFrames'] < 40 or result['triangles'] <= 0:
            raise ValueError('No successful hardware scene dispatch')
        if not result.get('emptySceneCleared'):
            raise ValueError('Empty scene lifecycle was not verified')
        if not result.get('publicCapabilities', {}).get('hardwareRayTracing'):
            raise ValueError('Public capabilities disagree with actual RT')
        for timing in ('traceCpu', 'traceGpu'):
            if result[timing]['count'] <= 0 or result[timing]['meanMs'] <= 0:
                raise ValueError('Missing CPU/GPU timing')
        scores = {n: alignment(folder/(n+'.png')) for n in ('alignment', 'alignment-bobbing', 'alignment-moving')}
        if min(scores.values()) < .75 or abs(scores['alignment']-scores['alignment-bobbing']) > .05:
            raise ValueError('Ray/raster alignment regressed during camera movement: '+str(scores))
    else:
        if result['packStatus']['state'] != 'FALLBACK' or result['traceFrames'] != 0:
            raise ValueError('Unavailable path incorrectly claims hardware RT')
        scores = {}
    if result['offStatus']['state'] != 'FALLBACK' or 'inactive' not in result['offStatus']['detail']:
        raise ValueError('Rays Off falsely claims an active hardware path')
    for name in ('rays', 'rays-moving', 'resized', 'rays-off'):
        w, h, data = pixels(folder/(name+'.png'))
        if max(data)-min(data) < 30:
            raise ValueError('Blank scene capture: '+name)
    if pixels(folder/'rays.png')[:2] == pixels(folder/'resized.png')[:2]:
        raise ValueError('Resize not exercised')
    return {'backend': result['capabilities']['backend'], 'hardware': hardware, 'alignment': scores}


if __name__ == '__main__':
    import sys
    print(json.dumps(verify(sys.argv[1]), indent=2))
