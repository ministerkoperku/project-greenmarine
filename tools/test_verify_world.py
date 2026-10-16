import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from test_verify_probe import solid_png

class WorldVerifierTest(unittest.TestCase):
    def verify(self, lit=160, blocked=66, valid=True, blocked_baseline=64):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d)
            for name,value in [('emitter-on.png',lit),('emitter-off.png',64),('emitter-blocked.png',blocked),('emitter-blocked-off.png',blocked_baseline)]:
                solid_png(root/name,value,64)
            (root/'result.json').write_text(json.dumps(dict(status='complete',hdrVerified=valid,parametersVerified=valid,voxelScans=2)))
            return subprocess.run([sys.executable,str(Path(__file__).with_name('verify-world-probe.py')),d],capture_output=True,text=True)
    def test_accepts_world_emission_with_opaque_occlusion_and_complete_authoring_checks(self):
        result=self.verify();self.assertEqual(0,result.returncode,result.stdout+result.stderr)
    def test_rejects_screen_without_emitter_lighting(self):
        self.assertNotEqual(0,self.verify(lit=64).returncode)
    def test_rejects_emission_that_ignores_a_wall(self):
        self.assertNotEqual(0,self.verify(blocked=160).returncode)
    def test_rejects_failed_hdr_or_parameter_controls(self):
        self.assertNotEqual(0,self.verify(valid=False).returncode)
    def test_darker_scene_with_a_blocker_does_not_hide_unoccluded_emission(self):
        self.assertNotEqual(0,self.verify(blocked=84,blocked_baseline=16).returncode)
