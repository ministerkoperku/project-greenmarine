"""User-facing authoring workflows; no game process required."""
import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
TOOL = ROOT / 'tools/greenmarine-pack.py'


class PackAuthoringTest(unittest.TestCase):
    def run_tool(self, *args):
        return subprocess.run([sys.executable, str(TOOL), *map(str, args)], capture_output=True, text=True)

    def test_starter_validates_and_packages_reproducibly(self):
        with tempfile.TemporaryDirectory() as temp:
            pack = Path(temp) / 'pack'
            result = self.run_tool('init', pack, '--id', 'demo:ocean')
            self.assertEqual(result.returncode, 0, result.stderr)
            manifest = json.loads((pack / 'assets/demo/greenmarine/packs/ocean.json').read_text())
            self.assertEqual(manifest['id'], 'demo:ocean')
            self.assertTrue(manifest['programs'])
            self.assertFalse(any(p.get('worldRays', False) for p in manifest['programs']))
            self.assertEqual(self.run_tool('validate', pack).returncode, 0)
            first, second = Path(temp) / 'a.zip', Path(temp) / 'b.zip'
            for archive in (first, second):
                result = self.run_tool('package', pack, archive)
                self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(first.read_bytes(), second.read_bytes())
            with ZipFile(first) as archive:
                self.assertIn('assets/demo/shaders/core/terrain.fsh', archive.namelist())
            self.assertNotEqual(self.run_tool('init', pack, '--id', 'demo:ocean').returncode, 0)

    def test_validate_rejects_missing_local_program_and_invalid_manifest(self):
        with tempfile.TemporaryDirectory() as temp:
            pack = Path(temp) / 'pack'
            self.assertEqual(self.run_tool('init', pack, '--id', 'demo:ocean').returncode, 0)
            (pack / 'assets/demo/shaders/core/terrain.fsh').unlink()
            result = self.run_tool('validate', pack)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('terrain.fsh', result.stderr)

    def test_raster_shader_variants_compile(self):
        spec = importlib.util.spec_from_file_location('solstice_test_helpers', ROOT / 'tools/test_solstice_shaders.py')
        helper = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(helper)
        shader = ROOT / 'src/main/resources/assets/greenmarine/shaders/core/raster_terrain.fsh'
        self.assertTrue(shader.exists())
        for defines in ((), ('ALPHA_CUTOUT 0.1',), ('MULTIDRAW_TERRAIN',),
                        ('OIT', 'OIT_ALPHA_ONLY'), ('OIT', 'OIT_ACCUMULATE')):
            code, output = helper.compile_shader(shader, 'frag', defines)
            self.assertEqual(code, 0, output)

    def test_validator_requires_runtime_manifest_fields(self):
        with tempfile.TemporaryDirectory() as temp:
            manifest = Path(temp) / 'assets/demo/greenmarine/packs/broken.json'
            manifest.parent.mkdir(parents=True)
            for data in ({'schema': 1, 'id': 'demo:broken'}, [],
                         {'schema': 1, 'id': 'demo:broken', 'passes': 'bad'}):
                manifest.write_text(json.dumps(data))
                result = self.run_tool('validate', temp)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn('Pack error:', result.stderr)
                self.assertNotIn('Traceback', result.stderr)


if __name__ == '__main__':
    unittest.main()
