"""Execute shared scalar shader policy for colored metals and foreground rejection."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
POLICY = ROOT / 'src/main/resources/assets/greenmarine/shaders/include/reflection_policy.glsl'


class ReflectionPolicyTest(unittest.TestCase):
    def test_copper_keeps_its_hue_and_foreground_is_not_a_matching_surface(self):
        self.assertTrue(POLICY.exists(), 'shared material/reflection policy missing')
        source = '#include <initializer_list>\n#include <cmath>\n#include "' + str(POLICY) + '"\n' + r'''
        int main() {
            float red=marineMaterialF0(.7f,2.0f),green=marineMaterialF0(.2f,2.0f),blue=marineMaterialF0(.05f,2.0f);
            if (!(red > green && green > blue)) return 1;
            if (marineMaterialF0(.7f,1.0f) != marineMaterialF0(.05f,1.0f)) return 2;
            if (marineFresnel(red,1.0f) != red) return 3;
            if (marineFresnel(red,0.0f) != 1.0f) return 4;
            if (!marineOpaqueSurfaceVisible(.5f,.5f)) return 5;
            if (marineOpaqueSurfaceVisible(.505f,.5f)) return 6;
            if (marineOpaqueSurfaceVisible(1.0f,.99f)) return 7;
            if (marineOpaqueSurfaceVisible(.001005f,.001f)) return 8;
            if (marineOpaqueSurfaceVisible(0.0f,0.0f)) return 9;
            if (marineCaptureCoordinate(0,3,5)!=0 || marineCaptureCoordinate(1,3,5)!=2 || marineCaptureCoordinate(2,3,5)!=4) return 10;
            if (marineCaptureCoordinate(0,3,6)!=1 || marineCaptureCoordinate(2,3,6)!=5) return 11;
            if (!marineReflectionSurfaceMatches(.005f,.01f,.1f,1000.0f)) return 12;
            if (marineReflectionSurfaceMatches(.03f,.01f,.1f,1000.0f)) return 13;
            if (marineReflectionSurfaceMatches(.005f,.3f,.1f,1000.0f)) return 14;
            if (marineReflectionFilterRadius(.9f,1.0f)!=1.0f) return 15;
            if (marineReflectionFilterRadius(.5f,2.0f)<=marineReflectionFilterRadius(.1f,2.0f)) return 16;
            if (marineReflectionTapWeight(1.5f,1.0f)!=0.0f) return 17;
            if (marineReflectionTapWeight(1.5f,1.8f)<=0.0f) return 18;
            if(marineQualityReflectionRadius(.9f,2.0f,1.0f)!=1.0f) return 19;
            if(marineQualityReflectionRadius(.9f,2.0f,2.0f)<=1.0f) return 20;
            if(marineNeedsSurfaceReflection(1.0f) || marineNeedsSurfaceReflection(7.0f)) return 21;
            if(!marineNeedsSurfaceReflection(4.0f) || !marineNeedsSurfaceReflection(6.0f)) return 22;
            for(int capture=1;capture<130;++capture)for(int divisor: {2,4}) {
                int output=(capture-1)/divisor+1;
                for(int texel=0;texel<output;++texel) {
                    int expected=int(((long long)(texel*2+1)*capture)/(output*2));
                    if(marineCaptureCoordinate(texel,output,capture)!=expected || expected>=capture)return 23;
                }
            }
            if(!marineReflectionFootprintMatches(.005f,.3f,.1f,1000.0f,.1f))return 24;
            if(marineReflectionFootprintMatches(.03f,.3f,.1f,1000.0f,.1f))return 25;
            if(marineReflectionFootprintMatches(.005f,.3f,.1f,1000.0f,1.0f))return 26;
            if(marineTranslucentSurfaceVisible(.6f,.5f))return 27;
            if(!marineTranslucentSurfaceVisible(.3f,.5f) || !marineTranslucentSurfaceVisible(0.0f,.5f))return 28;
            for(int capture=2;capture<130;++capture)for(int divisor: {2,4}) {
                int output=(capture-1)/divisor+1;
                if(output<2)continue;
                for(int sample=0;sample<50;++sample) {
                    float uv=(sample+.5f)/50.0f;
                    int first=marineReflectionBase(uv,output,capture);
                    float f=marineReflectionFraction(uv,first,output,capture);
                    float left=marineCaptureCoordinate(first,output,capture)+.5f;
                    float right=marineCaptureCoordinate(first+1,output,capture)+.5f;
                    float reconstructed=left+(right-left)*f;
                    float expected=uv*capture;expected=expected<left?left:(expected>right?right:expected);
                    if(std::abs(reconstructed-expected)>.00002f || first<0 || first>=output-1)return 29;
                }
            }
            if(std::abs(marineSpecularWeight(.5f,.9f,2.0f,1.0f)-.5f)>.00001f)return 30;
            if(marineSpecularWeight(.5f,.1f,2.0f,1.0f)!=marineSpecularWeight(.5f,.9f,2.0f,1.0f))return 31;
            if(std::abs(marineMaterialF0(.2f,8.0f)-.17f)>.00001f || !marineNeedsSurfaceReflection(8.0f))return 32;
            if(std::abs(marineSpecularWeight(.17f,.9f,8.0f,1.0f)-.17f)>.00001f)return 33;
            if(marineSpecularWeight(.5f,.5f,1.0f,1.0f)!=0.0f || marineSpecularWeight(.5f,.5f,2.0f,0.0f)!=0.0f)return 34;
            if(marineSpecularWeight(.8f,0.0f,2.0f,2.0f)>1.0f)return 35;
            if(std::abs(marineSampleCoverage(.25f,1.f)-.25f)>.00001f)return 36;
            if(marineSampleCoverage(0.f,1.f)!=0.f || marineSampleCoverage(1.f,0.f)!=0.f)return 37;
            if(marineSampleCoverage(2.f,1.f)!=1.f || marineSampleCoverage(-1.f,1.f)!=0.f)return 38;
            if(marineMaterialF0(.1f,9.f)!=.04f || marineMaterialF0(.9f,9.f)!=.04f || !marineNeedsSurfaceReflection(9.f))return 39;
            return 0;
        }
        '''
        with tempfile.TemporaryDirectory() as temp:
            path, binary = Path(temp) / 'policy.cpp', Path(temp) / 'policy'
            path.write_text(source)
            result = subprocess.run([shutil.which('c++') or 'g++', '-std=c++17', str(path), '-o', str(binary)], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(subprocess.run([str(binary)]).returncode, 0)


if __name__ == '__main__':
    unittest.main()
