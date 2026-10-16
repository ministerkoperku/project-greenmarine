"""Synthetic evidence exercises rejection gates, not Solstice's exact artistic look."""
import json
import struct
import subprocess
import sys
import tempfile
import unittest
import zlib
from pathlib import Path

VERIFIER = Path(__file__).with_name('verify-solstice.py')


def png(path, width, height, pixel):
    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))
    raw = b''.join(b'\0' + bytes(c for x in range(width) for c in pixel(x / width, y / height)) for y in range(height))
    path.write_bytes(b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 2, 0, 0, 0))
                     + chunk(b'IDAT', zlib.compress(raw)) + chunk(b'IEND', b''))


def world(x, y, case='water'):
    variation=(int(x*36)+int(y*24)) % 6 * 5
    water=.02<x<.66 and .27<y<.65
    if y<.1:
        color=(90+variation,140+variation,180+variation)
    else:
        color=(40+variation,105+variation,55+variation)
    if water:
        color=(25+variation,100+variation,140+variation)
        if case=='water-flat': color=tuple(c+40 for c in color)
        if case=='surface-normals': color=(165+variation,205+variation,165+variation)
        if case=='surface-albedo': color=(25+variation,75+variation,160+variation)
        if case=='water-wave-vertices': color=(60+(int(x*24+y*14)%8)*20,0,0)
    if case in ['water-bob','water-moving']:
        color=tuple(min(255,c+(8 if case=='water-bob' else 25)) for c in color)
    if case=='water-night': color=tuple(int(c*.3) for c in color)
    if case=='black-sky' and y<.1: color=(0,0,0)
    if case=='white-trees' and .7<x<.98 and .35<y<.7: color=(245,245,245)
    return color


class SolsticeVerifierTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix='solstice-evidence-test-')
        self.folder=Path(self.temp.name)
        self.record={'status':'complete','capabilities':{'backend':'Vulkan','hardwareRayTracing':True},
                     'packStatus':{'pack':'greenmarine:solstice_rt','state':'LOADED','detail':'Hardware surface RT active'},
                     'offStatus':{'state':'FALLBACK','detail':'Hardware dispatch inactive'},
                     'failureStatus':{'state':'FAILED'},'recovered':True,
                     'wireframeStatus':{'state':'FALLBACK','detail':'Wireframe: surface capture unavailable'},
                     'legacyNativeStatus':{'state':'FALLBACK','detail':'Optional surface native API unavailable'},
                     'surfaceNativeRecovered':True,'surfaceReady':True,'surfaceRaysReady':True,
                     'surfaceRayFrames':50,'legacyRayFrames':0,'draws':100,'variants':5,'opaqueCopies':30,
                     'surfaceCpu':{'count':8,'meanMs':.1},'surfaceGpu':{'count':8,'meanMs':.3},
                     'mainWorldGpu':{'count':20,'meanMs':1},'vanillaMainGpu':{'count':20,'meanMs':.3},
                     'environmentLowAltitude':48,'environmentHighAltitude':96,'environmentRain':.8,'oit':True}
        names=['water','water-flat','water-bob','water-moving','water-resized','water-no-rays',
               'water-night','water-wave-vertices','surface-normals','surface-albedo','underwater','wet-lightning','vanilla']
        for name in names:
            w,h=(1250,938) if name=='water-resized' else (160,96)
            png(self.folder/(name+'.png'),w,h,lambda x,y,n=name:world(x,y,n))
        png(self.folder/'environment-low.png',160,96,lambda x,y:(96,0,255))
        png(self.folder/'environment-high.png',160,96,lambda x,y:(191,204,255))

    def tearDown(self): self.temp.cleanup()

    def run_verifier(self):
        self.assertTrue(VERIFIER.exists(),'Independent Solstice verifier must be implemented')
        (self.folder/'result.json').write_text(json.dumps(self.record))
        result=subprocess.run([sys.executable,str(VERIFIER),str(self.folder)],capture_output=True,text=True)
        self.assertTrue((self.folder/'verification.json').exists(),result.stdout+result.stderr)
        return result,json.loads((self.folder/'verification.json').read_text())

    def test_accepts_complete_actual_path_and_portable_path(self):
        result,summary=self.run_verifier();self.assertEqual(result.returncode,0,result.stdout+result.stderr)
        self.assertTrue(summary['accepted']);self.assertIn('environment-high.png',summary['sourceCases'])
        self.record.update(capabilities={'backend':'OpenGL','hardwareRayTracing':False},packStatus={'pack':'greenmarine:solstice_rt','state':'FALLBACK'},surfaceRaysReady=False,surfaceRayFrames=0,oit=False)
        result,summary=self.run_verifier();self.assertEqual(result.returncode,0,result.stdout+result.stderr)
        self.assertFalse(summary['hardware'])

    def test_missing_final_environment_or_native_checks_is_not_acceptance(self):
        (self.folder/'environment-high.png').unlink();self.record.pop('surfaceNativeRecovered')
        result,summary=self.run_verifier();self.assertEqual(result.returncode,2,result.stdout+result.stderr)
        self.assertEqual(summary['status'],'needs_final');self.assertFalse(summary['accepted'])

    def test_rejects_failed_probe_and_false_hardware_readiness(self):
        self.record['status']='failed';result,_=self.run_verifier();self.assertEqual(result.returncode,1)
        self.record['status']='complete';self.record['surfaceRaysReady']=False
        result,summary=self.run_verifier();self.assertEqual(result.returncode,1);self.assertIn('surface',summary['error'].lower())

    def test_rejects_false_wireframe_readiness_or_missing_recovery(self):
        self.record['wireframeStatus']['state']='LOADED';result,_=self.run_verifier();self.assertEqual(result.returncode,1)
        self.record['wireframeStatus']['state']='FALLBACK';self.record['surfaceNativeRecovered']=False
        result,_=self.run_verifier();self.assertEqual(result.returncode,1)

    def test_rejects_identical_water_controls_and_static_wave_diagnostic(self):
        (self.folder/'water-flat.png').write_bytes((self.folder/'water.png').read_bytes())
        result,_=self.run_verifier();self.assertEqual(result.returncode,1)
        png(self.folder/'water-flat.png',160,96,lambda x,y:world(x,y,'water-flat'))
        png(self.folder/'water-wave-vertices.png',160,96,lambda x,y:(170,0,0))
        result,summary=self.run_verifier();self.assertEqual(result.returncode,1);self.assertIn('wave',summary['error'].lower())

    def test_rejects_reused_capture_color_and_white_chunk_dominance(self):
        (self.folder/'surface-normals.png').write_bytes((self.folder/'surface-albedo.png').read_bytes())
        result,_=self.run_verifier();self.assertEqual(result.returncode,1)
        png(self.folder/'surface-normals.png',160,96,lambda x,y:world(x,y,'surface-normals'))
        png(self.folder/'water.png',160,96,lambda x,y:world(x,y,'white-trees'))
        result,summary=self.run_verifier();self.assertEqual(result.returncode,1);self.assertIn('white',summary['error'].lower())

    def test_rejects_black_day_sky_and_stale_environment_uniforms(self):
        png(self.folder/'water.png',160,96,lambda x,y:world(x,y,'black-sky'))
        result,_=self.run_verifier();self.assertEqual(result.returncode,1)
        png(self.folder/'water.png',160,96,world)
        png(self.folder/'environment-high.png',160,96,lambda x,y:(96,0,255))
        result,summary=self.run_verifier();self.assertEqual(result.returncode,1);self.assertIn('environment',summary['error'].lower())

    def test_accepts_fog_tinted_wave_variation_but_rejects_static_tinted_red(self):
        def fog_wave(x,y):
            color=world(x,y,'water-wave-vertices')
            return (min(color[0]+15,255),30,35) if color[1]==0 and color[2]==0 else color
        png(self.folder/'water-wave-vertices.png',160,96,fog_wave)
        result,summary=self.run_verifier();self.assertEqual(result.returncode,0,result.stdout+result.stderr)
        png(self.folder/'water-wave-vertices.png',160,96,lambda x,y:(170,30,35))
        result,summary=self.run_verifier();self.assertEqual(result.returncode,1);self.assertIn('wave',summary['error'].lower())

    def test_accepts_opengl_resize_at_actual_one_to_one_display_scale(self):
        self.record.update(capabilities={'backend':'OpenGL','hardwareRayTracing':False},packStatus={'pack':'greenmarine:solstice_rt','state':'FALLBACK'},surfaceRaysReady=False,surfaceRayFrames=0,oit=False)
        png(self.folder/'water-resized.png',1000,750,world)
        result,summary=self.run_verifier();self.assertEqual(result.returncode,0,result.stdout+result.stderr)
        self.assertEqual(summary['resize']['after'],[1000,750])

    def test_rejects_resize_substitution_and_missing_separate_gpu_timing(self):
        (self.folder/'water-resized.png').write_bytes((self.folder/'water.png').read_bytes())
        result,_=self.run_verifier();self.assertEqual(result.returncode,1)
        png(self.folder/'water-resized.png',1250,938,world)
        self.record['surfaceGpu']['count']=0
        result,summary=self.run_verifier();self.assertEqual(result.returncode,1);self.assertIn('timing',summary['error'].lower())


if __name__=='__main__': unittest.main()
