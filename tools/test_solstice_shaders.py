"""Compile actual Solstice resources and host variants, without launching Minecraft."""
import json
import os
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'src/main/resources/assets'
MANIFEST = ASSETS / 'greenmarine/greenmarine/packs/solstice_rt.json'
HOST = Path(os.environ.get('GREENMARINE_MINECRAFT_JAR', Path.home() / '.gradle/caches/fabric-loom/26.3/minecraft-client.jar'))


def flatten(text, chain=()):
    def replace(match):
        name = match[1]
        if name in chain:
            raise ValueError('cyclic include: ' + name)
        namespace, path = name.split(':', 1)
        resource = f'assets/{namespace}/shaders/include/{path}'
        local = ASSETS.parent / resource
        if local.exists():
            included = local.read_text()
        else:
            with ZipFile(HOST) as host:
                included = host.read(resource).decode()
        return flatten(included, chain + (name,))
    return re.sub(r'#include\s*<([^>]+)>', replace, text)


def compile_shader(path, stage, defines=(), backend='vulkan'):
    if 'OIT' in defines:
        defines = tuple(defines) + ('OIT_COEFF_COUNT 8', 'OIT_COEFF_ATTACHMENT_COUNT 2', 'OIT_WAVELET_RANK 2')
    source = flatten(path.read_text())
    if backend == 'vulkan':
        source = source.replace('#version 330', '#version 450', 1)
    version = '#version 450' if backend == 'vulkan' else '#version 330'
    source = source.replace(version, version + '\n' + '\n'.join('#define ' + d for d in defines), 1)
    with tempfile.TemporaryDirectory(prefix='solstice-glsl-') as temp:
        shader = Path(temp) / ('shader.' + stage)
        binary = Path(temp) / 'shader.spv'
        shader.write_text(source)
        target = 'vulkan1.1' if backend == 'vulkan' else 'opengl'
        mode = '-V' if backend == 'vulkan' else '-G'
        result = subprocess.run(['glslangValidator', mode, '--target-env', target,
                                 '--auto-map-bindings', '--auto-map-locations', '-S', stage,
                                 '-o', str(binary), str(shader)], capture_output=True, text=True)
        if result.returncode:
            return result.returncode, result.stdout + result.stderr
        validation_target = 'vulkan1.1' if backend == 'vulkan' else 'opengl4.5'
        valid = subprocess.run(['spirv-val', '--target-env', validation_target, str(binary)], capture_output=True, text=True)
        return valid.returncode, valid.stdout + valid.stderr


class SolsticeShadersTest(unittest.TestCase):
    def manifest(self):
        self.assertTrue(MANIFEST.exists(), 'Solstice built-in manifest must be delivered')
        return json.loads(MANIFEST.read_text())

    def test_complete_pack_keeps_parameter_budget_and_raster_fallback(self):
        pack = self.manifest()
        self.assertEqual(pack['schema'], 1)
        self.assertLessEqual(len(pack['parameters']), 32)
        for parameter in pack['parameters'].values():
            self.assertLessEqual(parameter['min'], parameter['default'])
            self.assertGreaterEqual(parameter['max'], parameter['default'])
        self.assertEqual({p['target'] for p in pack['programs']}, {'terrain', 'sky', 'clouds'})
        terrain = next(p for p in pack['programs'] if p['target'] == 'terrain')
        self.assertIn('surface', terrain)
        for program in pack['programs']:
            for field, suffix in [('vertex', '.vsh'), ('fragment', '.fsh'), ('surface', '.fsh')]:
                if field in program:
                    namespace, name = program[field].split(':')
                    self.assertTrue((ASSETS / namespace / 'shaders' / (name + suffix)).exists())
        self.assertTrue(any(p.get('requires') == 'hardware_rt' and p.get('fallback') for p in pack['passes']))
        self.assertEqual(pack['passes'][-1].get('scale', 1), 1)
        external = ROOT / 'examples/solstice-rt/assets/greenmarine/greenmarine/packs/solstice_rt.json'
        self.assertEqual(external.read_bytes(), MANIFEST.read_bytes())

    @unittest.skipUnless(shutil.which('glslangValidator') and shutil.which('spirv-val') and HOST.exists(), 'offline GLSL/SPIR-V toolchain and cached Minecraft 26.3 required')
    def test_world_stages_compile_in_classic_multidraw_and_all_oit_phases(self):
        pack = self.manifest()
        variants = [(), ('MULTIDRAW_TERRAIN',), ('ALPHA_CUTOUT 0.1',),
                    ('OIT', 'OIT_ALPHA_ONLY', 'OIT_DEPTH_BOUNDS'),
                    ('OIT', 'OIT_ALPHA_ONLY', 'OIT_TRANSMITTANCE'),
                    ('OIT', 'OIT_ACCUMULATE')]
        for program in pack['programs']:
            for field, stage, suffix in [('vertex', 'vert', '.vsh'), ('fragment', 'frag', '.fsh'), ('surface', 'frag', '.fsh')]:
                if field not in program:
                    continue
                namespace, name = program[field].split(':')
                path = ASSETS / namespace / 'shaders' / (name + suffix)
                for defines in variants if program['target'] != 'sky' else [()]:
                    for backend in ['vulkan', 'opengl']:
                        with self.subTest(program=program['target'], field=field, defines=defines, backend=backend):
                            code, output = compile_shader(path, stage, defines, backend)
                            self.assertEqual(code, 0, output)

    @unittest.skipUnless(shutil.which('glslangValidator') and shutil.which('spirv-val') and HOST.exists(), 'offline GLSL/SPIR-V toolchain required')
    def test_every_scene_and_fallback_shader_compiles(self):
        for program in self.manifest()['passes']:
            for field in ['fragment', 'fallback']:
                if field in program:
                    namespace, name = program[field].split(':')
                    for backend in ['vulkan', 'opengl']:
                        with self.subTest(fragment=program[field], backend=backend):
                            code, output = compile_shader(ASSETS / namespace / 'shaders' / (name + '.fsh'), 'frag', backend=backend)
                            self.assertEqual(code, 0, output)


if __name__ == '__main__':
    unittest.main()
