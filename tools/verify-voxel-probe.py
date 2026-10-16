#!/usr/bin/env python3
"""Acceptance check for the fixed voxel-wall probe, not arbitrary gameplay images."""
import json
import subprocess
import sys
from pathlib import Path

directory=Path(sys.argv[1])
result=json.loads((directory/"result.json").read_text())
if result.get("status")!="complete" or result.get("voxelScans",0)<1:
    raise SystemExit("FAIL: voxel fixture did not complete with a snapshot")


def floor_color(name):
    path=directory/name
    dimensions=subprocess.run(["magick","identify","-format","%w %h",str(path)],check=True,capture_output=True,text=True).stdout
    width,height=map(int,dimensions.split())
    crop=f"{max(1,round(width*0.4))}x{max(1,round(height*0.2))}+{round(width*0.3)}+{round(height*0.55)}"
    values=subprocess.run(["magick",str(path),"-crop",crop,"+repage","-format","%[fx:mean.g-mean.r] %[fx:mean.r]","info:"],check=True,capture_output=True,text=True).stdout
    return tuple(map(float,values.split()))


off,off_red=floor_color("voxel-rays-off.png")
on,on_red=floor_color("voxel-rays-on.png")
print(f"Floor green minus red: rays off={off:.5f}, rays on={on:.5f}; retained scene red={on_red:.5f}")
if off<=on+0.025 or on_red<0.1:
    raise SystemExit("FAIL: wall does not block colored light while preserving ordinary scene color")
print("PASS: voxel wall occludes the point light on the foreground floor")
