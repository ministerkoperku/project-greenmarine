import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
import subprocess

spec = importlib.util.spec_from_file_location('hardware_verifier', Path(__file__).with_name('verify-hardware-rt.py'))
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)

class HardwareVerifierTest(unittest.TestCase):
    def fixture(self, folder):
        record = {'status':'complete', 'capabilities':{'hardwareRayTracing':True,'backend':'Vulkan'},
                  'publicCapabilities':{'hardwareRayTracing':True}, 'packStatus':{'state':'LOADED'},
                  'traceFrames':60,'triangles':12,'emptySceneCleared':True,
                  'traceCpu':{'count':20,'meanMs':.1},'traceGpu':{'count':20,'meanMs':.5},
                  'offStatus':{'state':'FALLBACK','detail':'Hardware dispatch inactive'}}
        (folder/'result.json').write_text(json.dumps(record))
        for name in ('rays','rays-moving','resized','rays-off','alignment','alignment-bobbing','alignment-moving'):
            subprocess.run(['magick','-size', '80x60' if name=='resized' else '100x75',
                            'xc:#00ff00',str(folder/(name+'.png'))],check=True)
        return record
    def test_rejects_bobbing_mismatch_and_empty_scene_staleness(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder=Path(tmp); record=self.fixture(folder);verifier.verify(folder)
            subprocess.run(['magick','-size','100x75','xc:#ff0000',str(folder/'alignment-bobbing.png')],check=True)
            with self.assertRaises(ValueError):verifier.verify(folder)
            self.fixture(folder);record['emptySceneCleared']=False
            (folder/'result.json').write_text(json.dumps(record))
            with self.assertRaises(ValueError):verifier.verify(folder)
    def test_rejects_false_hardware_status_and_stale_results(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder=Path(tmp);record=self.fixture(folder)
            record['capabilities']['hardwareRayTracing']=False
            (folder/'result.json').write_text(json.dumps(record))
            with self.assertRaises(ValueError):verifier.verify(folder)
            record['status']='failed';(folder/'result.json').write_text(json.dumps(record))
            with self.assertRaises(ValueError):verifier.verify(folder)
