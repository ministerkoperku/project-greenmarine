#!/usr/bin/env python3
"""Verify real NamedPassProbe GPU evidence, including every lifecycle checkpoint."""

import json
import math
from pathlib import Path
import sys


def verify(path):
    result = json.loads(path.read_text())
    assert result["fixture"] == "named-pass-gpu", "Wrong fixture"
    assert result["status"] == "complete", result.get("error", result["status"])
    assert result["restored"] is True, "Original settings were not restored"
    expected = [0.16, 0.32, 0.48, 1.0]
    assert result["expectedRgba"] == expected, "Unexpected producer reference"
    tolerance = result["tolerance"]
    assert 0 < tolerance <= 0.012, "Excessive pixel tolerance"
    assert result["samplesPerCase"] == 3, "Missing repeated GPU samples"
    cases = result["cases"]
    assert len(cases) == 8, "Expected eight lifecycle checkpoints"
    for index, case in enumerate(cases):
        history = index >= 4
        name = ("history-" if history else "plain-") + ("baseline", "resize", "quality", "reset")[index % 4]
        assert case["name"] == name and case["passed"] is True, f"Incomplete case: {name}"
        assert case["producerHistory"] is history, f"Wrong history mode: {name}"
        assert case["passScale"] == 1 and case["passFormat"] == "RGBA16F", f"Wrong alias fixture: {name}"
        samples = case["gpuSamples"]
        assert len(samples) == 3, f"Expected three GPU samples: {name}"
        for sample in samples:
            assert sample["packStatus"]["state"] == "LOADED", f"Pack not Loaded: {name}"
            assert sample["packStatus"]["pack"] == ("probe:named_pass_history" if history else "probe:named_pass_plain"), f"Wrong pack: {name}"
            assert sample["rays"] == "OFF", f"Unexpected ray requirement: {name}"
            assert sample["quality"] == ("HIGH" if index % 4 < 2 else "LOW"), f"Quality transition missing: {name}"
            assert sample["sceneFrames"] > 0, f"No actual pack submission: {name}"
            assert sample["width"] > 0 and sample["height"] > 0, f"Invalid dimensions: {name}"
            if index % 4 == 1:
                assert (sample["width"], sample["height"]) != (case["beforeWidth"], case["beforeHeight"]), f"Resize not observed: {name}"
            pixels = sample["rgba"]
            assert len(pixels) == 20, f"Expected five RGBA pixels: {name}"
            assert all(math.isfinite(value) and abs(value - expected[channel % 4]) <= tolerance for channel, value in enumerate(pixels)), f"Producer color was overwritten: {name}: {pixels}"
    print("Named pass GPU evidence passed: eight cases, 24 readbacks, 120 pixels")


if __name__ == "__main__":
    try:
        verify(Path(sys.argv[1] if len(sys.argv) > 1 else "run/probe-named-passes/result.json"))
    except (AssertionError, KeyError, OSError, TypeError, ValueError) as error:
        print(f"Named pass GPU evidence failed: {error}", file=sys.stderr)
        sys.exit(1)
