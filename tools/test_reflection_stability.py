"""Execute actual reconstruction GLSL at partially represented producer footprints."""
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]

class ReflectionStabilityTest(unittest.TestCase):
    def test_shadowed_corner_does_not_restore_unshadowed_lightmap(self):
        with tempfile.TemporaryDirectory() as folder:
            result=subprocess.run(['python3',str(ROOT/'tools/render-reflection-fixture.py'),
                str(Path(folder)/'corner.png'),'--lighting-corner','--distance','2','--slope','.435','--divisor','2'],cwd=ROOT,
                env={**os.environ,'LIBGL_ALWAYS_SOFTWARE':'true'},text=True,capture_output=True)
            self.assertEqual(0,result.returncode,result.stdout+result.stderr)

    def test_ultra_interpolates_a_reduced_resolution_shadow_edge(self):
        with tempfile.TemporaryDirectory() as folder:
            result=subprocess.run(['python3',str(ROOT/'tools/render-reflection-fixture.py'),
                str(Path(folder)/'shadow.png'),'--lighting-step','--distance','.5','--divisor','2'],cwd=ROOT,
                env={**os.environ,'LIBGL_ALWAYS_SOFTWARE':'true'},text=True,capture_output=True)
            self.assertEqual(0,result.returncode,result.stdout+result.stderr)

    def test_coplanar_diffuse_material_seams_do_not_punch_holes_in_lighting(self):
        with tempfile.TemporaryDirectory() as folder:
            result=subprocess.run(['python3',str(ROOT/'tools/render-reflection-fixture.py'),
                str(Path(folder)/'lighting.png'),'--lighting-seams','--distance','.5','--divisor','2'],cwd=ROOT,
                env={**os.environ,'LIBGL_ALWAYS_SOFTWARE':'true'},text=True,capture_output=True)
            self.assertEqual(0,result.returncode,result.stdout+result.stderr)

    def test_partial_producer_footprint_fades_without_full_strength_islands(self):
        with tempfile.TemporaryDirectory() as folder:
            for phase in (-.4,-.2,0,.2,.4):
                with self.subTest(phase=phase):
                    result=subprocess.run(['python3',str(ROOT/'tools/render-reflection-fixture.py'),
                        str(Path(folder)/'coverage.png'),'--check-confidence','--probe-phase',str(phase)],cwd=ROOT,
                        env={**os.environ,'LIBGL_ALWAYS_SOFTWARE':'true'},text=True,capture_output=True)
                    self.assertEqual(0,result.returncode,result.stdout+result.stderr)
