"""Behavioral invariance and sprite-boundary checks for production relief GLSL."""
import math
import shutil
import tempfile
import unittest
from probe_solstice_relief_orientation import compile_probe, sample


@unittest.skipUnless(shutil.which('c++'), 'C++ compiler required for shader-policy fixture')
class SolsticeReliefTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='solstice-relief-')
        cls.binary = compile_probe(cls.temp.name)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def check_rotations(self, fixture):
        values = sample(self.binary, fixture)
        reference = values[0][1:4]
        for turn, nx, ny, nz, fetches, invalid in values:
            self.assertEqual(0, invalid, f'rotation {turn} sampled a foreign atlas sprite')
            self.assertTrue(all(math.isfinite(v) for v in (nx, ny, nz)))
            for actual, expected in zip((nx, ny, nz), reference):
                self.assertAlmostEqual(expected, actual, places=6,
                                       msg=f'equivalent physical heightfield at UV rotation {turn}')
        return values

    def test_asymmetric_heightfield_has_one_world_normal_in_all_uv_orientations(self):
        self.check_rotations(0)

    def test_reverse_uv_orientation_does_not_flatten_a_bumped_heightfield(self):
        values = self.check_rotations(1)
        self.assertLess(values[0][2], .99)

    def test_flat_texture_stays_flat_in_all_uv_orientations(self):
        for _, nx, ny, nz, _, _ in self.check_rotations(2):
            self.assertEqual((0., 1., 0.), (nx, ny, nz))

    def test_tile_edge_stays_inside_its_sprite_and_preserves_orientation(self):
        self.check_rotations(3)

    def test_tile_corner_stays_inside_its_sprite_and_preserves_orientation(self):
        self.check_rotations(4)

    def test_transparent_neighbor_preserves_orientation(self):
        self.check_rotations(5)

    def test_two_transparent_neighbors_leave_that_axis_flat(self):
        for _, nx, _, _, _, _ in self.check_rotations(6):
            self.assertAlmostEqual(0., nx, places=6)

    def test_transparent_center_stays_flat(self):
        for _, nx, ny, nz, _, _ in self.check_rotations(7):
            self.assertEqual((0., 1., 0.), (nx, ny, nz))


if __name__ == '__main__':
    unittest.main()
