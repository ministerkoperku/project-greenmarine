import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
import subprocess

def image(path, size, color):
    subprocess.run(['magick', '-size', f'{size[0]}x{size[1]}', 'xc:rgb' + str(color), str(path)], check=True)

spec = importlib.util.spec_from_file_location('render_verifier', Path(__file__).with_name('verify-render-api.py'))
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)


class RenderVerifierTest(unittest.TestCase):
    def fixture(self, folder):
        result = {'status': 'complete', 'shaderFailureIsolated': True, 'wrongThreadResetRejected': True,
                  'borrowedSceneSurvived': True, 'openResources': {'textureAllocations': 3, 'programCompilations': 2},
                  'resizeResources': {'textureAllocations': 6}, 'openTiming': {'cpu': {'count': 40, 'meanMs': .1}, 'gpu': {'count': 40, 'meanMs': .02}},
                  'reloadTiming': {'cpu': {'count': 40, 'meanMs': .1}, 'gpu': {'count': 40, 'meanMs': .02}}}
        (folder / 'result.json').write_text(json.dumps(result))
        for name in ('baseline', 'geometry', 'occluded', 'resized', 'reloaded', 'released'):
            size = (100, 100) if name in ('resized', 'reloaded', 'released') else (120, 90)
            color = (120, 4, 80) if name in ('geometry', 'resized', 'reloaded') else (40, 90, 40)
            image(folder / f'{name}.png', size, color)

    def test_rejects_blank_geometry_and_stale_failed_results(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp); self.fixture(folder)
            verifier.verify(folder)
            image(folder / 'geometry.png', (120, 90), (40, 90, 40))
            with self.assertRaises(ValueError): verifier.verify(folder)
            self.fixture(folder)
            result = json.loads((folder / 'result.json').read_text()); result['status'] = 'failed'
            (folder / 'result.json').write_text(json.dumps(result))
            with self.assertRaises(ValueError): verifier.verify(folder)

    def test_rejects_visible_occluded_geometry_and_missing_gpu_measurements(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp); self.fixture(folder)
            image(folder / 'occluded.png', (120, 90), (120, 4, 80))
            with self.assertRaises(ValueError): verifier.verify(folder)
            self.fixture(folder)
            result = json.loads((folder / 'result.json').read_text()); result['reloadTiming']['gpu']['count'] = 0
            (folder / 'result.json').write_text(json.dumps(result))
            with self.assertRaises(ValueError): verifier.verify(folder)
