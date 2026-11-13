"""Execute the production compose history predicate and blend with controlled texels.

This CPU shader-policy fixture exercises the exact GLSL statements as C++; it is
not Minecraft/Vulkan acceptance. Texture stubs provide fixed sampled inputs.
"""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
COMPOSE = ROOT / 'src/main/resources/assets/greenmarine/shaders/core/solstice_compose.fsh'
POLICY = ROOT / 'src/main/resources/assets/greenmarine/shaders/include/texture_history.glsl'


@unittest.skipUnless(shutil.which('c++'), 'C++ compiler required for shader-policy fixture')
class SolsticeHistoryTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        source = COMPOSE.read_text()
        start = source.index('vec4 previous=texture(HistoryNormalDepth,uv);')
        end = source.index('color=mix(color,old,clamp(max(stability,response),0.0,.96));', start)
        end += len('color=mix(color,old,clamp(max(stability,response),0.0,.96));')
        # Keep the actual rejection and blending statements, including the
        # production if(consistent). Only the texture inputs are controlled.
        body = source[start:end] + '\n}'
        policy = POLICY.read_text()
        program = r'''
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
float max(float a,float b){return std::max(a,b);}
float min(float a,float b){return std::min(a,b);}
struct vec2 { float x,y; vec2(float a,float b):x(a),y(b){} vec2 operator*(vec2 b)const{return{x*b.x,y*b.y};} };
struct ivec2 { explicit ivec2(vec2){} };
struct vec3 {
    float r,g,b;
    explicit vec3(float v):r(v),g(v),b(v){}
    vec3(float x,float y,float z):r(x),g(y),b(z){}
    vec3 operator+(vec3 v)const{return{r+v.r,g+v.g,b+v.b};}
    vec3 operator-(vec3 v)const{return{r-v.r,g-v.g,b-v.b};}
    vec3 operator*(float v)const{return{r*v,g*v,b*v};}
};
struct vec4 {
    float x,y,z,w,a,r,g,b; vec3 xyz,rgb;
    vec4(float x,float y,float z,float w):x(x),y(y),z(z),w(w),a(w),r(x),g(y),b(z),xyz(x,y,z),rgb(x,y,z){}
};
struct Texture {vec4 value;};
vec4 texture(Texture t,vec2){return t.value;}
vec4 texelFetch(Texture t,ivec2,int){return t.value;}
vec2 textureSize(Texture,int){return{64,64};}
float dot(vec3 a,vec3 b){return a.r*b.r+a.g*b.g+a.b*b.b;}
vec3 abs(vec3 a){return{std::abs(a.r),std::abs(a.g),std::abs(a.b)};}
float abs(float a){return std::abs(a);}
float clamp(float a,float lo,float hi){return std::clamp(a,lo,hi);}
vec3 clamp(vec3 a,vec3 lo,vec3 hi){return{clamp(a.r,lo.r,hi.r),clamp(a.g,lo.g,hi.g),clamp(a.b,lo.b,hi.b)};}
vec3 mix(vec3 a,vec3 b,float t){return a*(1-t)+b*t;}
float mix(float a,float b,float t){return a*(1-t)+b*t;}
float smoothstep(float lo,float hi,float a){float t=clamp((a-lo)/(hi-lo),0,1);return t*t*(3-2*t);}
''' + policy + r'''
int main(int argc,char** argv){
    if(argc!=8)return 2;
    float channels[6];
    for(int i=0;i<6;i++)channels[i]=std::strtof(argv[i+1],nullptr);
    float oldColor=std::strtof(argv[7],nullptr);
    vec2 uv(.5f,.5f);
    vec4 current(0,0,1,.5f),material(channels[0],channels[1],channels[2],1.45f);
    Texture HistoryNormalDepth{{0,0,1,.5f}};
    Texture HistoryAlbedoMaterial{{channels[3],channels[4],channels[5],1.45f}};
    Texture HistorySceneDepth{{.5f,0,0,0}},HistorySampler{{oldColor,oldColor,oldColor,1}};
    vec4 Context(1,0,0,0),projected(0,0,.5f,1);
    vec4 RayOptions(0,0,1,1),Climate(0,0,0,0),HistoryInfo(1,1,100,.01f);
    vec3 color(.4f);
''' + body + r'''
    std::printf("%d %.9f %.9f %.9f\n",consistent,color.r,color.g,color.b);
}
'''
        cls.temp = tempfile.TemporaryDirectory(prefix='solstice-history-')
        path = Path(cls.temp.name)
        fixture = path / 'history.cpp'
        cls.binary = path / 'history'
        fixture.write_text(program)
        result = subprocess.run(['c++', '-std=c++17', str(fixture), '-o', str(cls.binary)],
                                capture_output=True, text=True)
        if result.returncode:
            cls.temp.cleanup()
            raise RuntimeError(result.stderr)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def sample(self, old_albedo, old_color=.8, current_albedo=.4):
        def channels(value):
            return (value,) * 3 if isinstance(value, (int, float)) else value
        arguments = (*channels(current_albedo), *channels(old_albedo), old_color)
        result = subprocess.run([str(self.binary), *(str(value) for value in arguments)],
                                capture_output=True, text=True, check=True)
        accepted, *rgb = result.stdout.split()
        return int(accepted), [float(value) for value in rgb]

    def test_same_albedo_can_reuse_previous_lighting(self):
        accepted, rgb = self.sample(.4)
        self.assertEqual(1, accepted)
        self.assertTrue(all(value > .42 for value in rgb), rgb)

    def test_clearly_different_albedo_cannot_reuse_previous_color(self):
        accepted, rgb = self.sample(.5)
        self.assertEqual(0, accepted)
        for value in rgb:
            self.assertAlmostEqual(.4, value, places=6)

    def test_distinct_nearby_albedo_texel_cannot_borrow_previous_final_color(self):
        accepted, rgb = self.sample(.43, .43)
        self.assertEqual(0, accepted,
                         f'different albedo texel was accepted and changed final RGB from .4 to {rgb}')
        for value in rgb:
            self.assertAlmostEqual(.4, value, places=6)


    def test_one_half_float_rounding_step_can_reuse_lighting(self):
        accepted, rgb = self.sample(.400244140625)
        self.assertEqual(1, accepted)
        self.assertTrue(all(value > .42 for value in rgb), rgb)

    def test_dark_distinct_texel_is_rejected_at_its_own_precision(self):
        accepted, rgb = self.sample(.0011, .43, current_albedo=.001)
        self.assertEqual(0, accepted, rgb)
        for value in rgb:
            self.assertAlmostEqual(.4, value, places=6)

    def test_bright_channel_does_not_hide_another_channels_texture_difference(self):
        accepted, rgb = self.sample((1., .01, .01002), .43,
                                    current_albedo=(1., .01, .01))
        self.assertEqual(0, accepted, rgb)
        for value in rgb:
            self.assertAlmostEqual(.4, value, places=6)

    def test_one_subnormal_half_float_rounding_step_can_reuse_lighting(self):
        accepted, _ = self.sample(2 ** -24, current_albedo=0.)
        self.assertEqual(1, accepted)

    def test_two_subnormal_half_float_steps_are_distinct(self):
        accepted, rgb = self.sample(2 ** -23, .43, current_albedo=0.)
        self.assertEqual(0, accepted, rgb)


if __name__ == '__main__':
    unittest.main()
