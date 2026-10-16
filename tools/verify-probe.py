#!/usr/bin/env python3
"""Check the diagnostic pass against an ordinary frame of the same test world."""
import subprocess
import sys
import json
from pathlib import Path

directory = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("run/probe-output")
if json.loads((directory / "result.json").read_text()).get("status") != "complete":
    raise SystemExit("FAIL: runtime probe did not complete successfully")


def center_green(filename):
    result = subprocess.run([
        "magick", str(directory / filename), "-gravity", "center", "-crop", "25%x25%+0+0",
        "+repage", "-format", "%[fx:mean.g]", "info:"
    ], check=True, capture_output=True, text=True)
    return float(result.stdout)


on = center_green("glow-on.png")
off = center_green("glow-off.png")
reloaded = center_green("glow-after-reload.png")
reloaded_off = center_green("glow-after-reload-off.png")
print(f"Center green: enabled={on:.5f}, disabled={off:.5f}, reloaded enabled={reloaded:.5f}, reloaded disabled={reloaded_off:.5f}")
if on <= off + 0.001 or reloaded <= reloaded_off + 0.001:
    raise SystemExit("FAIL: glow does not preserve and visibly brighten the world")
print("PASS: enabled glow changes the world image and survives resize/resource reload")
