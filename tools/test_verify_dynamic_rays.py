import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

SCRIPT=Path(__file__).with_name('verify-dynamic-rays.py')

class DynamicRayVerifierTest(unittest.TestCase):
    def fixture(self):
        events=[]
        for index in range(5):
            requested=1_000_000_000+index*1_000_000_000
            events.append({'kind':'add' if index%2==0 else 'undo','cycle':index//2+1,
                           'requestedNanos':requested,'nativePublishedNanos':requested+100_000_000,
                           'firstSubmittedNanos':requested+120_000_000,'gpuChangedFrameNanos':requested+140_000_000,
                           'gpuObservedNanos':requested+150_000_000,'beforeVisibility':1 if index%2==0 else 0,
                           'afterVisibility':0 if index%2==0 else 1,'controlVisibility':1,'receiverNormalY':1})
        for event in events:event['metricsBefore']={'nativeStats':[1,100,1,100,4,0,0,0,0,1000,0,2,0,0]}
        samples=[{'event':i+1,'phase':4 if event['kind']=='add' else 6,'observedNanos':event['gpuObservedNanos'],'frameNanos':event['gpuChangedFrameNanos'],'receiverVisibility':event['afterVisibility'],'controlVisibility':1,'receiverNormalY':1,'controlNormalY':1}for i,event in enumerate(events)]
        samples += [{'event':5,'phase':20,'receiverVisibility':0,'controlVisibility':1,'receiverNormalY':1,'controlNormalY':1}for _ in range(60)]
        return {'gpuSamples':samples,'status':'complete','restored':True,'capabilities':{'backend':'Vulkan','hardwareRayTracing':True},
                'packStatus':{'state':'LOADED'},'events':events,'frames':[{'deltaMs':10+(i%4),'instrumentation':False}for i in range(120)],
                'movement':{'sectionCrossings':3,'requestedBoundsChanges':3,'finalBoundsMatched':True,'maxReadyGapMs':50,'gpuSamples':60,'gpuFailures':0},
                'surfaceRayFrames':120,'metricsMode':'dynamic','sourceCases':['warm-unblocked.png','movement-end.png'],'submissionFaultRecovered':True,'submissionFaultTriggered':True,
                'finalMetrics':{'nativeStats':[5,100,5,100,4,1,0,0,0,1000,0,2,0,0]}}
    def verifier(self):
        self.assertTrue(SCRIPT.exists(),'Dynamic runtime verifier must be implemented before acceptance')
        spec=importlib.util.spec_from_file_location('dynamic_verifier',SCRIPT);module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
        return module
    def test_accepts_two_actual_gpu_edit_undo_cycles_and_reports_tail_intervals(self):
        summary=self.verifier().verify_record(self.fixture())
        self.assertTrue(summary['accepted']);self.assertGreaterEqual(summary['frameIntervals']['p99Ms'],summary['frameIntervals']['p95Ms'])
    def test_rejects_delayed_publication_and_gpu_change_without_undo(self):
        module=self.verifier();record=self.fixture();record['events'][0]['gpuObservedNanos']+=2_000_000_000
        with self.assertRaises(ValueError):module.verify_record(record)
        record=self.fixture();record['events'][1]['afterVisibility']=0
        with self.assertRaises(ValueError):module.verify_record(record)
    def test_rejects_no_crossing_no_restoration_and_cpu_only_success(self):
        module=self.verifier()
        for mutate in [lambda r:r.update(restored=False),lambda r:r['movement'].update(sectionCrossings=0),lambda r:r['events'][0].update(controlVisibility=0),lambda r:r['events'][0].update(gpuObservedNanos=0),lambda r:r['movement'].update(gpuFailures=1)]:
            record=self.fixture();mutate(record)
            with self.assertRaises(ValueError):module.verify_record(record)
    def test_rejects_claimed_shadow_change_without_matching_gpu_readback(self):
        module=self.verifier();record=self.fixture();record['gpuSamples']=[]
        with self.assertRaises(ValueError):module.verify_record(record)
        record=self.fixture();record['gpuSamples'][0]['receiverVisibility']=1
        with self.assertRaises(ValueError):module.verify_record(record)
        record=self.fixture();record['gpuSamples'][0].pop('controlVisibility')
        with self.assertRaises(ValueError):module.verify_record(record)
        record=self.fixture();record['gpuSamples'][-1]['controlVisibility']=0
        with self.assertRaises(ValueError):module.verify_record(record)

    def test_rejects_missing_post_submit_recovery_and_steady_native_idle(self):
        module=self.verifier()
        for field in ('submissionFaultTriggered','submissionFaultRecovered'):
            for value in (None,False):
                record=self.fixture()
                if value is None:record.pop(field)
                else:record[field]=value
                with self.assertRaises(ValueError):module.verify_record(record)
        record=self.fixture();record['finalMetrics']['nativeStats'][11]+=1
        with self.assertRaises(ValueError):module.verify_record(record)

    def test_old_baseline_is_measured_without_claiming_new_latency_acceptance(self):
        module=self.verifier();record=self.fixture();record['metricsMode']='legacy_scene_identity'
        for index,event in enumerate(record['events']):
            event['gpuObservedNanos']+=3_000_000_000
            for sample in record['gpuSamples']:
                if sample.get('event')==index+1 and sample.get('phase') in (4,6):sample['observedNanos']=event['gpuObservedNanos']
        summary=module.verify_record(record,baseline=True)
        self.assertFalse(summary['accepted']);self.assertEqual(summary['status'],'baseline_measured')

if __name__=='__main__':unittest.main()
