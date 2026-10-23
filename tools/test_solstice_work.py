"""Execute the actual puddle function with counted noise calls to verify dry-work elimination."""
import re
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


class SolsticeWorkTest(unittest.TestCase):
    def test_fully_filtered_cloud_octaves_skip_noise_evaluation(self):
        shader = (ROOT / 'src/main/resources/assets/greenmarine/shaders/include/solstice.glsl').read_text()
        start = shader.index('float solCloudLayer(')
        end = start + shader[start:].index('\nvec3 solOutputDither')
        function = shader[start:end]
        helper = ROOT / 'src/main/resources/assets/greenmarine/shaders/include/cloud_noise.glsl'
        program = '#include <cmath>\nusing uint=unsigned int;\n#include "' + str(helper) + '"\n' + r'''
        struct vec2 {float x,y;};struct {float y;} Context;
        const int SOL_CLOUD_DETAIL=0;int calls=0;
        float marineParameter(int){return 1;}
        float clamp(float v,float lo,float hi){return v<lo?lo:(v>hi?hi:v);}
        float max(float a,float b){return a>b?a:b;}
        float mix(float a,float b,float t){return a+(b-a)*t;}
        float countedNoise(float x,float y,int period){++calls;return marineCloudNoise(x,y,period);}
        #define marineCloudNoise countedNoise
        ''' + function + r'''
        int main(){
            Context.y=3;
            if(std::abs(solCloudLayer({.2f,.3f},16,2.0f)-.5f)>.00001f || calls!=0)return 1;
            solCloudLayer({.2f,.3f},16,.01f);if(calls!=4)return 2;
            calls=0;Context.y=1;solCloudLayer({.2f,.3f},16,.01f);if(calls!=2)return 3;
            return 0;
        }
        '''
        self.execute(program)

    def test_dry_dark_and_vertical_surfaces_do_not_evaluate_puddle_noise(self):
        source = (ROOT / 'src/main/resources/assets/greenmarine/shaders/include/solstice.glsl').read_text()
        function = re.search(r'float solPuddle\([^}]+\}', source).group()
        program = r'''
        #include <cmath>
        struct vec2 { float x,y; vec2 operator+(vec2 b)const{return {x+b.x,y+b.y};} vec2 operator*(float s)const{return{x*s,y*s};} };
        struct vec3 { vec2 xz; float y; };
        struct {vec2 xz;} SurfaceOrigin;
        struct {float z;} Environment;
        const int SOL_WETNESS=0,SOL_PUDDLES=1;
        float parameters[2]={1,1};int calls=0;
        float marineParameter(int i){return parameters[i];}
        float solNoise(vec2){++calls;return .8f;}
        float smoothstep(float low,float high,float x){float t=(x-low)/(high-low);t=t<0?0:(t>1?1:t);return t*t*(3-2*t);}
        ''' + function + r'''
        int main(){
            vec3 p={{3,5},2},up={{0,0},1},wall={{1,0},0};
            Environment.z=0; if(solPuddle(p,up,1)!=0 || calls!=0)return 1;
            Environment.z=1; if(solPuddle(p,wall,1)!=0 || calls!=0)return 2;
            if(solPuddle(p,up,.65f)!=0 || calls!=0)return 3;
            parameters[1]=0;if(solPuddle(p,up,1)!=0 || calls!=0)return 4;
            parameters[1]=1;parameters[0]=0;if(solPuddle(p,up,1)!=0 || calls!=0)return 5;
            parameters[0]=1;if(solPuddle(p,up,1)<.99f || calls!=2)return 6;
            return 0;
        }
        '''
        self.execute(program)

    def execute(self, program):
        with tempfile.TemporaryDirectory() as temp:
            file,binary=Path(temp)/'policy.cpp',Path(temp)/'policy'
            file.write_text(program)
            built=subprocess.run(['c++','-std=c++17',str(file),'-o',str(binary)],capture_output=True,text=True)
            self.assertEqual(built.returncode,0,built.stderr)
            self.assertEqual(subprocess.run([str(binary)]).returncode,0)


if __name__ == '__main__':
    unittest.main()
