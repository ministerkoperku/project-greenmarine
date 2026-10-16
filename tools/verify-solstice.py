#!/usr/bin/env python3
"""Verify controlled Solstice probe evidence; shader presence alone is not acceptance.

Exit 0 means accepted, 1 rejected, 2 needs the final environment/lifecycle cases.
Writes verification.json with source hashes, metrics and the actual acceptance state.
This checks fixture behavior, not Eclipse parity or full ray/raster alignment.
"""
import argparse
import hashlib
import json
import math
import subprocess
from pathlib import Path

SCENE_CASES = ('water', 'water-flat', 'water-bob', 'water-moving', 'water-resized',
               'water-no-rays', 'water-night', 'water-wave-vertices', 'surface-normals',
               'surface-albedo', 'underwater', 'wet-lightning', 'vanilla')
WATER = (.20, .35, .66, .58)
BACKGROUND = (.05, .10, .35, .25)
CAPTURE_PLANE = (.10, .30, .36, .50)
SKY = (.10, .025, .65, .085)
TREES = (.70, .35, .98, .70)
ENVIRONMENT_SAMPLE = (.40, .35, .60, .45)


def pixels(path):
    width, height = map(int, subprocess.check_output(['magick', 'identify', '-format', '%w %h', str(path)]).split())
    raw = subprocess.check_output(['magick', str(path), '-depth', '8', 'RGB:-'])
    if width <= 0 or height <= 0 or len(raw) != width*height*3:
        raise ValueError('Invalid RGB capture: ' + path.name)
    return width, height, raw


def roi(image, bounds, stride=3):
    width, height, raw = image
    x0, y0, x1, y1 = bounds
    colors = [tuple(raw[(y*width+x)*3:(y*width+x)*3+3])
              for y in range(int(y0*height), int(y1*height), stride)
              for x in range(int(x0*width), int(x1*width), stride)]
    if not colors:
        raise ValueError('Empty image mask')
    return colors


def means(colors):
    return [sum(p[c] for p in colors)/len(colors) for c in range(3)]


def deviations(colors):
    average = means(colors)
    return [math.sqrt(sum((p[c]-average[c])**2 for p in colors)/len(colors)) for c in range(3)]


def brightness(colors):
    return sum(means(colors))/3


def delta(a, b, bounds):
    if a[:2] != b[:2]:
        raise ValueError('Controlled pair dimensions differ')
    first, second = roi(a, bounds), roi(b, bounds)
    return sum(abs(x-y) for p,q in zip(first,second) for x,y in zip(p,q))/(len(first)*3)


def require(condition, message):
    if not condition:
        raise ValueError(message)


def timing(record, name):
    value = record.get(name, {})
    require(value.get('count', 0) > 0 and math.isfinite(value.get('meanMs', 0)) and value.get('meanMs', 0) > 0,
            'Missing separate timing: ' + name)


def verify(folder):
    folder = Path(folder)
    result_bytes = (folder/'result.json').read_bytes()
    record = json.loads(result_bytes)
    sources = {'result.json': {'sha256': hashlib.sha256(result_bytes).hexdigest()}}
    require(record.get('status') == 'complete' and record.get('recovered') is True, 'Failed/incomplete world lifecycle probe')
    backend = record.get('capabilities', {}).get('backend')
    hardware = record.get('capabilities', {}).get('hardwareRayTracing')
    require(backend in ('Vulkan', 'OpenGL'), 'Unsupported acceptance backend')
    require(hardware is (backend == 'Vulkan'), 'Backend hardware capability disagrees with requested acceptance path')
    require(record.get('packStatus', {}).get('pack') == 'greenmarine:solstice_rt', 'Different pack was rendered')
    require(record.get('packStatus', {}).get('state') == ('LOADED' if hardware else 'FALLBACK'), 'Incorrect pack readiness status')
    require(record.get('offStatus', {}).get('state') == 'FALLBACK'
            and 'inactive' in record.get('offStatus', {}).get('detail', '').lower(), 'Hardware Off incorrectly reports an active path')
    require(record.get('failureStatus', {}).get('state') == 'FAILED', 'Shader failure was not reported')
    require(record.get('surfaceReady') is True, 'No actual raster surface capture')
    require(record.get('surfaceRaysReady') is hardware, 'Surface ray readiness is incorrect')
    require(record.get('draws', 0)>0 and record.get('variants', 0)>=3 and record.get('opaqueCopies', 0)>0,
            'Missing actual material draws or opaque copies')
    require(record.get('legacyRayFrames', -1)==0, 'Primary legacy rays unexpectedly replaced raster surface dispatch')
    if hardware:
        require(record.get('surfaceRayFrames', 0)>=40, 'No sustained surface ray dispatch')
        timing(record, 'surfaceCpu'); timing(record, 'surfaceGpu')
    else:
        require(record.get('surfaceRayFrames', -1)==0, 'Portable fallback falsely claims native surface dispatch')
    timing(record, 'mainWorldGpu'); timing(record, 'vanillaMainGpu')

    missing = []
    if 'wireframeStatus' not in record:
        missing.append('wireframeStatus')
    else:
        require(record['wireframeStatus'].get('state')=='FALLBACK'
                and 'unavailable' in record['wireframeStatus'].get('detail', '').lower(), 'Wireframe falsely publishes available surface capture')
    if hardware:
        for field in ('legacyNativeStatus', 'surfaceNativeRecovered'):
            if field not in record: missing.append(field)
        if 'legacyNativeStatus' in record:
            require(record['legacyNativeStatus'].get('state')=='FALLBACK', 'Missing optional native API falsely claims active surface RT')
        if 'surfaceNativeRecovered' in record:
            require(record['surfaceNativeRecovered'] is True, 'Optional surface native API did not recover')

    images = {}
    for case in SCENE_CASES:
        path = folder/(case+'.png')
        require(path.is_file(), 'Missing scene capture: ' + path.name)
        images[case] = pixels(path)
        sources[path.name] = {'sha256': hashlib.sha256(path.read_bytes()).hexdigest(), 'dimensions': list(images[case][:2])}
        require(max(deviations(roi(images[case], (.02,.05,.98,.58))))>=6, 'Blank/static scene capture: ' + case)
    # The same logical 1000x750 request is 1.25x on Vulkan and 1x/1.25x on OpenGL.
    expected_sizes = ((1250,938),) if hardware else ((1000,750),(1250,938))
    require(images['water-resized'][:2] in expected_sizes and images['water'][:2] != images['water-resized'][:2],
            'Expected physical resize was not exercised')
    for case in ('water-flat', 'water-bob', 'water-moving'):
        require(images[case][:2] == images['water'][:2], 'Camera/control pair dimensions differ: '+case)
    sky_brightness = brightness(roi(images['water'], SKY))
    require(sky_brightness > 20, 'Day sky is empty black')
    tree_colors = roi(images['water'], TREES)
    white_fraction = sum(min(p)>175 and max(p)-min(p)<45 for p in tree_colors)/len(tree_colors)
    require(white_fraction < .25, 'Neutral white chunk dominance in tree mask')
    water_delta = delta(images['water'], images['water-flat'], WATER)
    control_background = delta(images['water'], images['water-flat'], BACKGROUND)
    require(water_delta > 1.5 and water_delta > control_background*2+1, 'Water controls lack a local material effect relative to the fixed background mask')
    camera_background = {case: delta(images['water'],images[case],BACKGROUND) for case in ('water-bob','water-moving')}
    require(min(camera_background.values()) > .5, 'Camera bob/movement captures did not exercise a changed view')
    wave_colors = roi(images['water-wave-vertices'], (.02,.27,.43,.57))
    # Height fog adds green/blue; select red chroma without assuming a pure RGB output.
    red = [p[0] for p in wave_colors if p[0]>40 and p[0]>2*max(p[1],p[2])]
    red_fraction = len(red)/len(wave_colors)
    require(red_fraction >= .15, 'Wave diagnostic lacks a substantial water mask')
    red_range = max(red)-min(red)
    red_mean = sum(red)/len(red)
    red_std = math.sqrt(sum((r-red_mean)**2 for r in red)/len(red))
    require(red_range >= 50 and red_std >= 15, 'Wave diagnostic is static; no displaced vertex variation')
    normals, albedo = roi(images['surface-normals'],CAPTURE_PLANE), roi(images['surface-albedo'],CAPTURE_PLANE)
    normal_mean, albedo_mean = means(normals), means(albedo)
    require(normal_mean[1]-max(normal_mean[0],normal_mean[2]) > 8, 'Surface normal diagnostic lacks upward-facing captured water normals')
    require(max(albedo_mean)-min(albedo_mean)>20, 'Surface albedo diagnostic lacks material color')
    capture_delta = sum(abs(a-b) for a,b in zip(normal_mean,albedo_mean))/3
    require(capture_delta > 20, 'Surface normal/albedo diagnostics reuse the same image')
    day_brightness = brightness(roi(images['water-resized'],(.72,.42,.90,.54)))
    night_ratio = brightness(roi(images['water-night'],(.72,.42,.90,.54)))/max(day_brightness,1)
    require(night_ratio < .8, 'Night state failed to darken terrain')

    environment = {}
    for field in ('environmentLowAltitude','environmentHighAltitude','environmentRain'):
        if field not in record: missing.append(field)
    for case in ('environment-low','environment-high'):
        path = folder/(case+'.png')
        if not path.is_file():
            missing.append(path.name); continue
        image = pixels(path)
        sources[path.name] = {'sha256': hashlib.sha256(path.read_bytes()).hexdigest(), 'dimensions': list(image[:2])}
        color = means(roi(image, ENVIRONMENT_SAMPLE))
        require(color[2]>245 and max(deviations(roi(image,ENVIRONMENT_SAMPLE)))<3, 'Environment scene diagnostic has invalid dimension/color data')
        environment[case] = color
    if not any(str(field).startswith('environment') for field in missing):
        low, high = environment['environment-low'], environment['environment-high']
        for color, field in ((low,'environmentLowAltitude'),(high,'environmentHighAltitude')):
            expected = max(0,min(1,record[field]/128))*255
            require(abs(color[0]-expected)<=5, 'Environment altitude upload disagrees with the actual camera')
        require(record['environmentHighAltitude']>record['environmentLowAltitude'] and high[0]-low[0]>10, 'Environment altitude was not changed')
        require(abs(high[1]-record['environmentRain']*255)<=5 and high[1]-low[1]>10, 'Environment rain upload is stale or disagrees with actual weather')

    return {'status':'needs_final' if missing else 'accepted', 'accepted':not missing,
            'backend':backend, 'hardware':hardware, 'oit':record.get('oit'), 'missing':missing,
            'resize':{'before':list(images['water'][:2]),'after':list(images['water-resized'][:2]),'logicalTarget':[1000,750]},
            'metrics':{'waterControlDelta':water_delta, 'controlBackgroundDelta':control_background,
                       'cameraBackgroundDeltas':camera_background, 'waveRedFraction':red_fraction,
                       'waveRedRange':red_range,'waveRedStd':red_std,'surfaceDiagnosticMeanDelta':capture_delta,
                       'surfaceNormalMean':normal_mean,'surfaceAlbedoMean':albedo_mean,
                       'daySkyBrightness':sky_brightness,'treeWhiteFraction':white_fraction,'nightTerrainRatio':night_ratio,
                       'environmentChannels':environment},
            'timings':{name:record.get(name) for name in ('surfaceCpu','surfaceGpu','mainWorldGpu','vanillaMainGpu')},
            'sourceCases':sorted(sources), 'sources':sources,
            'limits':['Image masks check fixture behavior; they do not establish full ray/raster alignment.',
                      'No exact water look, Eclipse similarity, infinite scene or whole-frame performance claim.']}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('folder', type=Path)
    args = parser.parse_args()
    try:
        summary = verify(args.folder)
        code = 0 if summary['accepted'] else 2
    except (ValueError, KeyError, OSError, subprocess.SubprocessError) as error:
        summary = {'status':'rejected', 'accepted':False, 'error':str(error)}
        code = 1
    (args.folder/'verification.json').write_text(json.dumps(summary,indent=2)+'\n')
    print(json.dumps(summary,indent=2))
    return code


if __name__=='__main__':
    raise SystemExit(main())
