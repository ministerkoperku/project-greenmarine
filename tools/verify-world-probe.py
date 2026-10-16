#!/usr/bin/env python3
"""Checks the controlled offscreen voxel-emitter fixture, not arbitrary screenshots."""
import json
import subprocess
import sys
from pathlib import Path
root=Path(sys.argv[1])
result=json.loads((root/'result.json').read_text())
if result.get('status')!='complete' or not result.get('hdrVerified') or not result.get('parametersVerified') or result.get('voxelScans',0)<1:
    raise SystemExit('FAIL: world/HDR/control fixture incomplete')
def floor(name):
    path=root/name
    w,h=map(int,subprocess.run(['magick','identify','-format','%w %h',str(path)],check=True,capture_output=True,text=True).stdout.split())
    crop=f'{max(1,round(w*.4))}x{max(1,round(h*.2))}+{round(w*.3)}+{round(h*.55)}'
    return float(subprocess.run(['magick',str(path),'-crop',crop,'+repage','-format','%[fx:(mean.r+mean.g+mean.b)/3]','info:'],check=True,capture_output=True,text=True).stdout)
on,off,blocked,blocked_off=(floor(name) for name in ['emitter-on.png','emitter-off.png','emitter-blocked.png','emitter-blocked-off.png'])
print(f'Floor brightness: emitter={on:.5f}, off={off:.5f}, blocked={blocked:.5f}, blocked off={blocked_off:.5f}')
if on-off<.025 or blocked-blocked_off>(on-off)*.4:
    raise SystemExit('FAIL: world emitter is missing or rays ignore opaque occlusion')
print('PASS: offscreen world emission, opaque occlusion, HDR and parameter controls')
