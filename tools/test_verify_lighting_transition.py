import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location('transition',Path(__file__).with_name('verify-lighting-transition.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class LightingTransitionTest(unittest.TestCase):
    def fixture(self, ramp):
        events=[];samples=[]
        for event in range(1,6):
            start=event*1_000_000_000
            before,after=(.8,.5) if event%2 else (.5,.8)
            for n in range(5):
                samples.append({'event':event-1,'frameNanos':start-25_000_000+n*5_000_000,'receiverColor':before,'controlColor':.7})
            for n in range(30):
                fraction=min(1,(n+1)/8) if ramp else 1
                samples.append({'event':event,'frameNanos':start+(n+1)*5_000_000,'receiverColor':before+(after-before)*fraction,'controlColor':.7})
            events.append({'requestedNanos':start,'gpuChangedFrameNanos':start+5_000_000,'kind':'add' if event%2 else 'undo'})
        return {'status':'complete','events':events,'gpuSamples':samples}

    def check(self, record, baseline=False):
        with tempfile.TemporaryDirectory() as name:
            folder=Path(name);(folder/'result.json').write_text(json.dumps(record))
            return module.verify(folder,baseline)

    def test_rejects_a_step_even_when_native_readiness_succeeds(self):
        with self.assertRaisesRegex(ValueError,'Visible lighting step'):
            self.check(self.fixture(False))

    def test_accepts_short_measured_transition_and_preserves_baseline_label(self):
        self.assertTrue(self.check(self.fixture(True))['accepted'])
        self.assertFalse(self.check(self.fixture(False),True)['accepted'])

    def test_rejects_missing_gpu_samples_and_control_flicker(self):
        fixture=self.fixture(True);fixture['gpuSamples']=[]
        with self.assertRaises(ValueError):self.check(fixture)
        fixture=self.fixture(True);fixture['gpuSamples'][65]['controlColor']=.1
        with self.assertRaises(ValueError):self.check(fixture)

    def test_rejects_incomplete_run(self):
        fixture=self.fixture(True);fixture['status']='failed'
        with self.assertRaises(ValueError):self.check(fixture)
