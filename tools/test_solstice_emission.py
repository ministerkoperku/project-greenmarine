"""Execute the production terrain emission expression independently of incident light."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TERRAIN = ROOT / 'src/main/resources/assets/greenmarine/shaders/include/solstice_terrain.glsl'


class SolsticeEmissionTest(unittest.TestCase):
    def test_incident_blocklight_does_not_turn_matte_surfaces_into_emitters(self):
        source = TERRAIN.read_text()
        start = source.index('    float emission=')
        end = source.index('    lit+=base*emission;', start)
        statements = source[start:end]
        program = r'''
#include <cmath>
#include <cstdio>
#include <algorithm>
using std::pow;
float max(double a,double b){return std::max(a,b);}
struct vec3 {float x,y,z;};
struct vec4 {float x,y,z,a; float b;};
float solLuma(vec3 v){return v.x*.2126f+v.y*.7152f+v.z*.0722f;}
const int SOL_EMISSION=20;
float marineParameter(int){return .35f;}
struct Pbr {bool present;float emission;};
float evaluate(float incoming,float albedo,bool authored,float authoredEmission,float addonEmission) {
    vec3 base={albedo,albedo,albedo};
    vec4 surfaceLight={incoming,0,0,0,0},addon={0,0,0,addonEmission>0?1.f:0.f,addonEmission};
    Pbr pbr={authored,authoredEmission};
''' + statements + r'''
    return emission;
}
int main(){
    if(evaluate(1.f,.8f,false,0,0)!=0.f){std::puts("non-emissive sand emits merely because it receives block light");return 1;}
    if(evaluate(0.f,.8f,false,0,0)!=0.f)return 2;
    if(std::abs(evaluate(0.f,.1f,true,.8f,0)-evaluate(1.f,.9f,true,.8f,0))>1e-5f)return 3;
    if(evaluate(0.f,.1f,false,0,1.f)<1.f)return 4;
    return 0;
}
'''
        with tempfile.TemporaryDirectory(prefix='solstice-emission-') as folder:
            source_file, binary = Path(folder)/'emission.cpp', Path(folder)/'emission'
            source_file.write_text(program)
            build = subprocess.run([shutil.which('c++') or 'g++', '-std=c++17', '-Wno-narrowing', str(source_file), '-o', str(binary)], capture_output=True, text=True)
            self.assertEqual(0, build.returncode, build.stderr)
            result = subprocess.run([str(binary)], capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)


if __name__ == '__main__':
    unittest.main()
