"""Execute production visibility/diffuse/specular statements at the opaque horizon.

This CPU shader-policy fixture isolates direct lighting from ambient radiance.
It does not replace Vulkan gameplay acceptance.
"""
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
INCLUDE = ROOT / 'src/main/resources/assets/greenmarine/shaders/include'


def function(source, signature):
    start = source.index(signature)
    opening = source.index('{', start)
    depth = 1
    end = opening + 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]


@unittest.skipUnless(shutil.which('c++'), 'C++ compiler required for shader-policy fixture')
class SolsticeHorizonTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        terrain = (INCLUDE / 'solstice_terrain.glsl').read_text()
        common = (INCLUDE / 'solstice.glsl').read_text()
        local_visibility = function(terrain, 'float solLocalSunVisibility(')
        irradiance = '\n'.join(function(common[match.start():], 'vec3 solIrradiance(')
                               for match in re.finditer(r'vec3 solIrradiance\(', common))
        specular = '\n'.join(function(common[match.start():], 'vec3 solSunSpecular(')
                             for match in re.finditer(r'vec3 solSunSpecular\(', common))
        illumination = re.search(r'vec3 illumination=solIrradiance[^;]+;', terrain).group()
        highlight = re.search(r'lit\+=solSunSpecular[^;]+;', terrain).group()
        blend = 'float sunVisibility=mix(localVisibility,clamp(nativeLight.a,0.0,1.0),nativeCoverage);'
        if blend not in terrain:
            raise ValueError('Production sun visibility composition changed; update the behavioral fixture.')
        program = r'''
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
float max(float a,float b){return std::max(a,b);}
float min(float a,float b){return std::min(a,b);}
float clamp(float a,float lo,float hi){return std::clamp(a,lo,hi);}
float mix(float a,float b,float t){return a*(1-t)+b*t;}
float smoothstep(float lo,float hi,float a){float t=clamp((a-lo)/(hi-lo),0,1);return t*t*(3-2*t);}
struct vec2 {float x,y;vec2(float x,float y):x(x),y(y){}};
struct vec3 {
    float x,y,z;
    explicit vec3(float a):x(a),y(a),z(a){}
    vec3(float x,float y,float z):x(x),y(y),z(z){}
    vec3 operator+(vec3 b)const{return{x+b.x,y+b.y,z+b.z};}
    vec3 operator-(vec3 b)const{return{x-b.x,y-b.y,z-b.z};}
    vec3 operator*(float b)const{return{x*b,y*b,z*b};}
    vec3 operator*(vec3 b)const{return{x*b.x,y*b.y,z*b.z};}
    vec3& operator+=(vec3 b){x+=b.x;y+=b.y;z+=b.z;return *this;}
};
vec3 operator-(float a,vec3 b){return{a-b.x,a-b.y,a-b.z};}
vec3 operator+(float a,vec3 b){return{a+b.x,a+b.y,a+b.z};}
struct vec4 {
    float x,y,z,w,a,g;vec3 xyz;
    vec4(float x,float y,float z,float w):x(x),y(y),z(z),w(w),a(w),g(y),xyz(x,y,z){}
};
float dot(vec3 a,vec3 b){return a.x*b.x+a.y*b.y+a.z*b.z;}
vec3 normalize(vec3 a){return a*(1/std::sqrt(dot(a,a)));}
const int SOL_NIGHT=0,SOL_SPECULAR=1;
float marineParameter(int){return 1;}
vec3 ambient(0);
vec3 solAmbient(vec3){return ambient;}
vec3 solSunColor(){return vec3(1);}
vec4 SurfaceSun(-.02f,1,0,1),Moon(1,0,0,0),VoxelTracing(0,0,0,0),VoxelOriginSize(0,0,0,32);
struct MarineVoxelHit {bool hit;};
MarineVoxelHit marineVoxelTrace(vec3,vec3,float,bool){return{false};}
''' + local_visibility + '\n' + irradiance + '\n' + specular + r'''
int main(int argc,char** argv){
    int mode=argc>1?std::atoi(argv[1]):0;
    vec3 normal(1,0,0),position(0),shading=normalize(vec3(1,.2f,0)),view(1,0,0);
    vec3 sun=normalize(vec3(mode==1?.02f:-.02f,1,0));
    SurfaceSun=vec4(sun.x,sun.y,sun.z,mode==2 || mode==6?0:1);
    Moon=mode==6?vec4(sun.x,sun.y,sun.z,1):vec4(1,0,0,mode==2 || mode==3?1:0);
    ambient=mode==4?vec3(.1f,.2f,.3f):vec3(0);
    float nativeCoverage=0;
    vec4 nativeLight(0,0,0,0);
    float localVisibility=solLocalSunVisibility(position,normal);
''' + blend + r'''
    vec2 surfaceLight(mode==4?.5f:0,1);
    float ambientOcclusion=1,tracedBlocks=0,roughness=.7f;
    vec3 lit(0),f0(.04f);vec4 addon(0,0,0,0);
''' + illumination + '\n' + highlight + r'''
    std::printf("%.9f %.9f %.9f %.9f %.9f %.9f %.9f\n",sunVisibility,illumination.x,illumination.y,illumination.z,lit.x,lit.y,lit.z);
}
'''
        cls.temp = tempfile.TemporaryDirectory(prefix='solstice-horizon-')
        cpp = Path(cls.temp.name) / 'horizon.cpp'
        cls.binary = Path(cls.temp.name) / 'horizon'
        cpp.write_text(program)
        built = subprocess.run(['c++', '-std=c++17', str(cpp), '-o', str(cls.binary)],
                               capture_output=True, text=True)
        if built.returncode:
            cls.temp.cleanup()
            raise RuntimeError(built.stderr)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def sample(self, mode):
        result = subprocess.run([str(self.binary), str(mode)], capture_output=True,
                                text=True, check=True)
        values = [float(value) for value in result.stdout.split()]
        return values[0], values[1:4], values[4:7]

    def test_opaque_geometric_backside_has_no_direct_sun_diffuse(self):
        visibility, diffuse, _ = self.sample(0)
        self.assertTrue(all(abs(value) < 1e-7 for value in diffuse),
                        f'opaque backside receives sun: visibility={visibility}, diffuse={diffuse}')

    def test_opaque_geometric_backside_has_no_direct_sun_specular(self):
        visibility, _, specular = self.sample(0)
        self.assertTrue(all(abs(value) < 1e-7 for value in specular),
                        f'opaque backside receives highlight: visibility={visibility}, specular={specular}')

    def test_geometric_frontside_keeps_direct_sun(self):
        _, diffuse, specular = self.sample(1)
        self.assertTrue(all(value > .1 for value in diffuse), diffuse)
        self.assertTrue(all(value > 0 for value in specular), specular)

    def test_night_keeps_moonlight_when_sun_is_below_geometric_horizon(self):
        _, diffuse, _ = self.sample(2)
        self.assertTrue(all(value > .01 for value in diffuse), diffuse)


    def test_twilight_backside_rejects_sun_but_keeps_frontside_moon(self):
        _, diffuse, specular = self.sample(3)
        for actual, expected in zip(diffuse, (.0223572394, .031182466, .0505979668)):
            self.assertAlmostEqual(expected, actual, places=6)
        self.assertTrue(all(abs(value) < 1e-7 for value in specular), specular)

    def test_backside_keeps_ambient_and_block_lighting(self):
        _, diffuse, _ = self.sample(4)
        for actual, expected in zip(diffuse, (.27375, .31, .3575)):
            self.assertAlmostEqual(expected, actual, places=6)


    def test_moon_also_respects_the_opaque_geometric_horizon(self):
        _, diffuse, _ = self.sample(6)
        self.assertTrue(all(abs(value) < 1e-7 for value in diffuse), diffuse)


if __name__ == '__main__':
    unittest.main()
