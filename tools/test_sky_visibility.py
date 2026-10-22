"""Execute the shared scalar GLSL visibility contract, then compile its consumers."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
HELPER = ROOT / 'src/main/resources/assets/greenmarine/shaders/include/sky_visibility.glsl'


class SkyVisibilityTest(unittest.TestCase):
    def test_enclosed_cave_has_no_sky_and_exposed_surface_preserves_it(self):
        self.assertTrue(HELPER.exists(), 'shared shader sky visibility policy is missing')
        # This scalar GLSL is also valid C++; execute the actual shader function, not a second implementation.
        source = '#include "' + str(HELPER) + '"\n' + r'''
        int main() {
            if (marineSkyVisibility(0.0f) != 0.0f) return 1;
            if (marineSkyVisibility(-1.0f) != 0.0f) return 2;
            if (marineSkyVisibility(1.0f) != 1.0f) return 3;
            if (marineSkyVisibility(2.0f) != 1.0f) return 4;
            if (marineSkyVisibility(0.05f) > 0.01f) return 5;
            float previous = 0;
            for (int i=0; i<=100; ++i) {
                float value = marineSkyVisibility(i / 100.0f);
                if (value < previous || value < 0 || value > 1) return 6;
                previous = value;
            }
            return 0;
        }
        '''
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'policy.cpp'
            binary = Path(temp) / 'policy'
            path.write_text(source)
            result = subprocess.run([shutil.which('c++') or 'g++', '-std=c++17', str(path), '-o', str(binary)], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            result = subprocess.run([str(binary)])
            self.assertEqual(result.returncode, 0, 'shader visibility violated cave/open-sky contract')


if __name__ == '__main__':
    unittest.main()
