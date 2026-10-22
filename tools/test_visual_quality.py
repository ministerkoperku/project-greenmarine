"""Execute scalar production shader policies for clouds, output precision and rough reflections."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
INCLUDES = ROOT / 'src/main/resources/assets/greenmarine/shaders/include'


class VisualQualityTest(unittest.TestCase):
    def test_cloud_wrap_is_continuous_and_low_resolution_detail_fades(self):
        helper = INCLUDES / 'cloud_noise.glsl'
        self.assertTrue(helper.exists(), 'periodic filtered cloud noise is missing')
        source = '#include <cmath>\nusing uint=unsigned int;\n' + '#include "' + str(helper) + '"\n' + r'''
        int main() {
            for(int i=-120;i<120;++i) {
                float x=i*.13f,y=i*.07f;
                float first=marineCloudNoise(x,y,16);
                float wrapped=marineCloudNoise(x+16.0f,y-16.0f,16);
                if(std::abs(first-wrapped)>.00002f || first<0 || first>1) return 1;
            }
            if(marineCloudDetailWeight(.05f)<.99f) return 2;
            if(marineCloudDetailWeight(2.0f)>.01f) return 3;
            if(std::abs(marineCloudNoise(-.00001f,3.2f,16)-marineCloudNoise(.00001f,3.2f,16))>.0001f) return 4;
            return 0;
        }
        '''
        self.execute(source)

    def test_output_dither_is_bounded_and_has_zero_mean(self):
        helper = INCLUDES / 'output_quality.glsl'
        self.assertTrue(helper.exists())
        source = '#include <cmath>\n' + '#include "' + str(helper) + '"\n' + r'''
        int main() {
            float sum=0;
            for(int y=0;y<4;++y)for(int x=0;x<4;++x) {
                float value=marineOutputDither(x,y);
                if(std::abs(value)>.5f/255.0f) return 1;
                sum+=value;
            }
            if(std::abs(sum)>.000001f) return 2;
            if(marinePuddleRetention(7.0f)!=0 || marinePuddleRetention(1.0f)!=1) return 3;
            return 0;
        }
        '''
        self.execute(source)

    def execute(self, source):
        with tempfile.TemporaryDirectory() as temp:
            path, binary = Path(temp) / 'policy.cpp', Path(temp) / 'policy'
            path.write_text(source)
            result = subprocess.run([shutil.which('c++') or 'g++', '-std=c++17', str(path), '-o', str(binary)], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(subprocess.run([str(binary)]).returncode, 0)


if __name__ == '__main__':
    unittest.main()
