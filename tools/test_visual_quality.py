"""Execute scalar production shader policies for clouds, output precision and rough reflections."""
import shutil
import os
import re
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
INCLUDES = ROOT / 'src/main/resources/assets/greenmarine/shaders/include'


class VisualQualityTest(unittest.TestCase):
    def test_clear_air_scattering_is_bounded_and_has_no_sky_surface_silhouette(self):
        source='''#include <greenmarine:solstice_scene.glsl>
        bool solSurfaceRays(vec2 uv,vec4 surface,vec4 material,bool needLighting,out vec4 lighting,out vec4 reflection){
            bool sky=(int(uv.x*Frame.x)%2)==0;
            vec3 scatter=solVolumetric(uv,vec3(0,0,-1),48.0,sky,1.0);
            float energy=.1+8.0*solLuma(scatter);
            lighting=vec4(energy);reflection=vec4(vec3(energy),1);return true;
        }
        '''
        with tempfile.TemporaryDirectory() as folder:
            shader=Path(folder)/'air.glsl';shader.write_text(source)
            result=subprocess.run(['python3',str(ROOT/'tools/render-reflection-fixture.py'),
                str(Path(folder)/'air.png'),'--rays-source',str(shader),'--distance','.5','--volumetric-test','--check-pairs'],
                cwd=ROOT,env={**os.environ,'LIBGL_ALWAYS_SOFTWARE':'true'},capture_output=True,text=True)
            self.assertEqual(0,result.returncode,result.stdout+result.stderr)

    def test_star_kernels_are_continuous_across_cells_and_cube_faces(self):
        with tempfile.TemporaryDirectory() as folder:
            for cube in (False,True):
                body='''
                float px=floor(uv.x*Frame.x),py=floor(uv.y*Frame.y);
                float side=mod(px,2.0)<1.0?-.000001:.000001;
                float pair=floor(px*.5),sy=(py*2.0-179.5)/180.0;
                vec3 d;
                '''
                if cube:
                    body+='''int edge=int(mod(pair,6.0));
                    if(edge==0)d=vec3(1.0+side,sy,1);
                    else if(edge==1)d=vec3(-1.0-side,sy,1);
                    else if(edge==2)d=vec3(1.0+side,sy,-1);
                    else if(edge==3)d=vec3(-1.0-side,sy,-1);
                    else if(edge==4)d=vec3(sy,1.0+side,1);
                    else d=vec3(sy,-1.0-side,1);
                    '''
                else:body+='d=vec3((mod(pair,32.0)+1.0)/180.0+side,(mod(py,64.0)+.5)/180.0,1);'
                source='''#include <greenmarine:solstice.glsl>
                #include <greenmarine:surface_rays.glsl>
                bool solSurfaceRays(vec2 uv,vec4 surface,vec4 material,bool needLighting,out vec4 lighting,out vec4 reflection){
                '''+body+'''
                    float energy=.1+clamp(solLuma(solStars(normalize(d)))*100.0,0.0,.8);
                    lighting=vec4(energy);reflection=vec4(vec3(energy),1);return true;
                }
                '''
                shader=Path(folder)/'stars.glsl';shader.write_text(source)
                result=subprocess.run(['python3',str(ROOT/'tools/render-reflection-fixture.py'),
                    str(Path(folder)/'stars.png'),'--rays-source',str(shader),'--distance','.5','--check-pairs'],
                    cwd=ROOT,env={**os.environ,'LIBGL_ALWAYS_SOFTWARE':'true'},capture_output=True,text=True)
                self.assertEqual(0,result.returncode,result.stdout+result.stderr)

    def test_sun_shadow_handover_is_continuous_at_zero_native_coverage(self):
        # Execute the actual terrain composition with two controlled column-query
        # answers: a near roof blocks, while the scene exit is above that roof.
        terrain=(INCLUDES/'solstice_terrain.glsl').read_text()
        block=terrain[terrain.index('    float sunVisibility=mix'):terrain.index('    float ambientOcclusion=')]
        block=re.sub(r'        // Diagnostic 8:.*?return;\}', '',block,flags=re.S)
        block=re.sub(r' else if\(!water.*?return;\}', '',block,flags=re.S)
        block=block.replace('marineHeightFieldSun','probeColumns').replace('SurfaceSun','probeSun').replace('RayBoundsMin','probeMin').replace('RayBoundsMax','probeMax')
        source='''
        #include <greenmarine:frame.glsl>
        #include <greenmarine:surface_rays.glsl>
        const vec4 probeSun=vec4(0,1,0,1),probeMin=vec4(-16),probeMax=vec4(16);
        float probeColumns(vec3 position,vec3 sun,float start){return start<5.0?0.0:1.0;}
        float probeBoundary(float nativeCoverage){
            vec3 surfacePosition=vec3(0),normal=vec3(0,1,0);
            float localVisibility=1.0;vec4 nativeLight=vec4(1);
        '''+block+'''
            return sunVisibility;
        }
        bool solSurfaceRays(vec2 uv,vec4 surface,vec4 material,bool needLighting,out vec4 lighting,out vec4 reflection){
            float coverage=(int(uv.x*Frame.x)%2)==0?0.0:.000001;
            float value=.1+.8*probeBoundary(coverage);
            lighting=vec4(value);reflection=vec4(vec3(value),1);return true;
        }
        '''
        with tempfile.TemporaryDirectory() as folder:
            shader=Path(folder)/'boundary.glsl';shader.write_text(source)
            result=subprocess.run(['python3',str(ROOT/'tools/render-reflection-fixture.py'),
                str(Path(folder)/'boundary.png'),'--rays-source',str(shader),'--distance','.5','--check-diffuse'],
                cwd=ROOT,env={**os.environ,'LIBGL_ALWAYS_SOFTWARE':'true'},capture_output=True,text=True)
            self.assertEqual(0,result.returncode,result.stdout+result.stderr)

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
