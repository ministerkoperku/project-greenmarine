#!/usr/bin/env python3
"""Verify actual world materials, camera/lifecycle probes and the owner's white-patch reproduction."""
import json
import subprocess
from pathlib import Path


def pixels(path):
    w, h = map(int, subprocess.check_output(['magick', 'identify', '-format', '%w %h', str(path)]).split())
    return w, h, subprocess.check_output(['magick', str(path), '-depth', '8', 'RGB:-'])


def roi(image, bounds):
    w, h, data = image
    x0, y0, x1, y1 = bounds
    return [tuple(data[(y*w+x)*3:(y*w+x)*3+3])
            for y in range(int(y0*h), int(y1*h)) for x in range(int(x0*w), int(x1*w))]


def mean_delta(a, b, bounds):
    if a[:2] != b[:2]:
        raise ValueError('Pair dimensions differ')
    aa, bb = roi(a, bounds), roi(b, bounds)
    return sum(abs(x-y) for p, q in zip(aa, bb) for x, y in zip(p, q))/(len(aa)*3)


def white_fraction(image):
    colors = roi(image, (.68, .44, .96, .65))
    return sum(min(p) > 175 and max(p)-min(p) < 45 for p in colors)/len(colors)


def verify(folder):
    folder = Path(folder)
    result = json.loads((folder/'result.json').read_text())
    if result.get('status') != 'complete' or not result.get('recovered'):
        raise ValueError('World lifecycle probe incomplete')
    hardware = result['capabilities']['hardwareRayTracing']
    if result['packStatus']['state'] != ('LOADED' if hardware else 'FALLBACK'):
        raise ValueError('Backend readiness status is incorrect')
    if result['offStatus']['state'] != 'FALLBACK' or result['failureStatus']['state'] != 'FAILED':
        raise ValueError('Off/failure reporting failed')
    if result['draws'] <= 0 or result['variants'] < 3 or result['opaqueCopies'] <= 0:
        raise ValueError('No actual material draws/opaque captures')
    if result['mainWorldGpu']['count'] <= 0 or result['vanillaMainGpu']['count'] <= 0:
        raise ValueError('Missing world/vanilla GPU timing')
    images = {n: pixels(folder/(n+'.png')) for n in (
        'water', 'water-flat', 'water-bob', 'water-moving', 'water-resized',
        'water-no-rays', 'water-night', 'water-wave-vertices', 'vanilla', 'prismarine-original',
        'prismarine-corrected', 'prismarine-no-rays', 'prismarine-no-bloom')}
    for name, (_, _, data) in images.items():
        if max(data)-min(data) < 30:
            raise ValueError('Blank image: '+name)
    if images['water'][:2] == images['water-resized'][:2]:
        raise ValueError('Resize not exercised')
    water_delta = mean_delta(images['water'], images['water-flat'], (.20, .35, .66, .58))
    if water_delta < 1.5:
        raise ValueError('Water material controls have no visible effect')
    wave = roi(images['water-wave-vertices'], (.02, .27, .43, .57))
    red = [p[0] for p in wave if p[0] > 35 and p[1] < 10 and p[2] < 10]
    if len(red) < len(wave)*.3 or max(red)-min(red) < 45:
        raise ValueError('GPU water vertices have no wave displacement variation')
    day = roi(images['water-resized'], (.72, .42, .90, .54))
    night = roi(images['water-night'], (.72, .42, .90, .54))
    brightness = lambda values: sum(sum(p) for p in values)/(len(values)*3)
    night_ratio = brightness(night)/brightness(day)
    if night_ratio > .8:
        raise ValueError('Forced night environment failed to darken terrain')
    original, corrected = (white_fraction(images['prismarine-'+n]) for n in ('original', 'corrected'))
    if hardware and (original < .05 or corrected > original*.25):
        raise ValueError('Prismarine white patch was not reproduced and reduced')
    bloom_delta = mean_delta(images['prismarine-no-rays'], images['prismarine-no-bloom'], (.45, .40, .62, .58))
    if bloom_delta < .1:
        raise ValueError('Portable bloom stopped contributing')
    return {'backend': result['capabilities']['backend'], 'hardware': hardware,
            'oit': result['oit'], 'waterControlDelta': water_delta,
            'nightTerrainRatio': night_ratio, 'waveVertexRedRange': max(red)-min(red), 'originalWhiteFraction': original,
            'correctedWhiteFraction': corrected, 'portableBloomDelta': bloom_delta}


if __name__ == '__main__':
    import sys
    print(json.dumps(verify(sys.argv[1]), indent=2))
