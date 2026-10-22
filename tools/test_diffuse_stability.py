"""Execute native shader sampling statements across infinitesimal camera-surface shifts."""
import re
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]

def sampler(shader):
    text=(ROOT/'engine/shaders'/shader).read_text()
    if 'marineDiffuseDirection(normal,' in text:
        return 'return marineDiffuseDirection(normal,index);'
    result=[]
    for pattern in (r'vec3 tangent=[^;]+;',r'vec3 bitangent=[^;]+;',r'float seed=[^;]+;',
                    r'float a=[^;]+;',r'float r=[^;]+;',r'vec3 bounce=[^;]+;'):
        result.append(re.search(pattern,text).group())
    return '\n'.join(result).replace('float(j)','float(index)').replace('float(bounceIndex)','float(index)')+'\nreturn bounce;'

class DiffuseStabilityTest(unittest.TestCase):
    def test_actual_glsl_has_no_spatial_seed_step_in_constant_directional_light(self):
        helper=ROOT/'engine/shaders/diffuse_samples.glsl'
        common=helper.read_text() if helper.exists() else ''
        common+='''
        #include <greenmarine:frame.glsl>
        #include <greenmarine:surface_rays.glsl>
        const vec4 Options=vec4(0);
        float hash(float value){return fract(sin(value*12.9898)*43758.5453);}
        vec3 probeSample(vec3 pos,vec3 normal,int index){
        '''+sampler('surface_rt.comp')+'''
        }
        bool solSurfaceRays(vec2 uv,vec4 surface,vec4 material,bool needLighting,out vec4 lighting,out vec4 reflection){
            int pixel=int(uv.x*Frame.x);
            float boundary=float((pixel/2)%32)*.25;
            vec3 pos=vec3(boundary+(pixel%2==0?-.00001:.00001),0,.1);
            float energy=0.0;
            for(int j=0;j<4;j++){
                vec3 bounce=probeSample(pos,vec3(0,1,0),j);
                energy+=pow(max(dot(bounce,normalize(vec3(1,.2,.6))),0.0),4.0)*.25;
            }
            lighting=vec4(energy);reflection=vec4(vec3(energy),1);return true;
        }
        '''
        with tempfile.TemporaryDirectory() as folder:
            source=Path(folder)/'probe.glsl';source.write_text(common)
            result=subprocess.run(['python3',str(ROOT/'tools/render-reflection-fixture.py'),
                str(Path(folder)/'diffuse.png'),'--rays-source',str(source),'--distance','.5','--check-diffuse'],
                cwd=ROOT,env={**os.environ,'LIBGL_ALWAYS_SOFTWARE':'true'},text=True,capture_output=True)
            self.assertEqual(0,result.returncode,result.stdout+result.stderr)

    def test_native_hemisphere_directions_do_not_jump_at_quarter_block_boundaries(self):
        helper=ROOT/'engine/shaders/diffuse_samples.glsl'
        header=r'''
        #include <cmath>
        #include <cstdio>
        using std::sqrt;using std::sin;using std::cos;using std::abs;
        struct vec2{float x,y;vec2(float a,float b):x(a),y(b){} vec2 operator+(vec2 b)const{return{x+b.x,y+b.y};}vec2 operator*(float s)const{return{x*s,y*s};}};
        struct vec3{float x,y,z;vec2 xz;vec3(float a):x(a),y(a),z(a),xz(a,a){}vec3(float a,float b,float c):x(a),y(b),z(c),xz(a,c){}vec3 operator+(vec3 b)const{return{x+b.x,y+b.y,z+b.z};}vec3 operator-(vec3 b)const{return{x-b.x,y-b.y,z-b.z};}vec3 operator*(float s)const{return{x*s,y*s,z*s};}};
        float dot(vec2 a,vec2 b){return a.x*b.x+a.y*b.y;}
        float dot(vec3 a,vec3 b){return a.x*b.x+a.y*b.y+a.z*b.z;}
        vec2 floor(vec2 a){return{std::floor(a.x),std::floor(a.y)};}
        vec3 cross(vec3 a,vec3 b){return{a.y*b.z-a.z*b.y,a.z*b.x-a.x*b.z,a.x*b.y-a.y*b.x};}
        vec3 normalize(vec3 a){return a*(1/std::sqrt(dot(a,a)));}
        float fract(float a){return a-std::floor(a);}
        float hash(float value){return fract(sin(value*12.9898)*43758.5453);}
        struct{vec2 zw={0,0};}Options;
        '''
        if helper.exists():header+='\n#include "'+str(helper)+'"\n'
        for shader in ('surface_rt.comp','world_rt.comp'):
            with self.subTest(shader=shader),tempfile.TemporaryDirectory() as directory:
                source=header+'\nvec3 sample(vec3 pos,vec3 normal,int index){'+sampler(shader)+r'''
                }
                int main(){
                    vec3 normals[]={vec3(0,1,0),vec3(0,-1,0),vec3(1,0,0),vec3(-1,0,0),vec3(0,0,1),vec3(0,0,-1)};
                    float maxDirectionDelta=0,maxEnergyDelta=0;
                    for(vec3 n:normals){float before=0,after=0;
                        for(int j=0;j<4;j++){
                            vec3 a=sample(vec3(.24999f,0,.1f),n,j),b=sample(vec3(.25001f,0,.1f),n,j);
                            float d=std::sqrt(dot(a-b,a-b));if(d>maxDirectionDelta)maxDirectionDelta=d;
                            if(std::abs(dot(a,a)-1)>1e-5f || dot(a,n)<0)return 2;
                            vec3 source=normalize(vec3(1,.2f,.6f));
                            float aa=dot(a,source),bb=dot(b,source);aa=aa>0?aa:0;bb=bb>0?bb:0;
                            before+=aa*aa*aa*aa*.25f;after+=bb*bb*bb*bb*.25f;
                        }
                        float e=std::abs(before-after);if(e>maxEnergyDelta)maxEnergyDelta=e;
                    }
                    std::printf("directionDelta=%g energyDelta=%g\n",maxDirectionDelta,maxEnergyDelta);
                    return maxDirectionDelta>1e-3f || maxEnergyDelta>1e-3f?1:0;
                }
                '''
                path=Path(directory)/'sampling.cpp';binary=Path(directory)/'sampling';path.write_text(source)
                built=subprocess.run(['c++','-std=c++17',str(path),'-o',str(binary)],text=True,capture_output=True)
                self.assertEqual(0,built.returncode,built.stderr)
                result=subprocess.run([str(binary)],text=True,capture_output=True)
                self.assertEqual(0,result.returncode,result.stdout+result.stderr)
