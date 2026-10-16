import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from test_verify_probe import solid_png


class VoxelVerifierTest(unittest.TestCase):
    def verify(self, on_green=64, status="complete"):
        with tempfile.TemporaryDirectory() as temporary:
            directory=Path(temporary)
            solid_png(directory/"voxel-rays-off.png",128,64)
            solid_png(directory/"voxel-rays-on.png",on_green,64)
            (directory/"result.json").write_text(json.dumps({"status":status,"voxelScans":1}))
            return subprocess.run([sys.executable,str(Path(__file__).with_name("verify-voxel-probe.py")),str(directory)],capture_output=True,text=True)
    def test_accepts_a_wall_removing_colored_light_without_blacking_out_the_scene(self):
        result=self.verify(); self.assertEqual(0,result.returncode,result.stdout+result.stderr)
    def test_rejects_identical_frames_from_a_missing_wall_or_unimplemented_shadow(self):
        self.assertNotEqual(0,self.verify(on_green=128).returncode)
    def test_rejects_failed_capture_with_stale_valid_pixels(self):
        self.assertNotEqual(0,self.verify(status="failed").returncode)


if __name__=="__main__": unittest.main()
