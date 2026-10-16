import importlib.util
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location('world_programs', Path(__file__).with_name('verify-world-programs.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class WorldProgramsVerifierTest(unittest.TestCase):
    def test_white_patch_metric_detects_neutral_white_not_green_trees(self):
        white = (20, 20, bytes([240, 239, 238])*400)
        green = (20, 20, bytes([25, 110, 30])*400)
        self.assertEqual(module.white_fraction(white), 1)
        self.assertEqual(module.white_fraction(green), 0)

    def test_material_delta_rejects_mismatched_size(self):
        with self.assertRaises(ValueError):
            module.mean_delta((1, 1, bytes(3)), (2, 1, bytes(6)), (0, 0, 1, 1))
