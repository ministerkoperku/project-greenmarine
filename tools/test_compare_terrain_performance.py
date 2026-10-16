"""Guard performance claims against the cap/AFK/viewport mistakes found in probes."""
import copy
import importlib.util
import unittest
from pathlib import Path
spec = importlib.util.spec_from_file_location('comparison', Path(__file__).with_name('compare-terrain-performance.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def profile():
    phases = [dict(name=name, width=1200, height=675, framerateSetting=260,
                   effectiveFramerateLimit=260, throttleReason='NONE', vsync=False, sunAngle=0, rainBrightness=1, rainLevel=0,
                   startMetrics=dict.fromkeys(['generation','packNanos','prepareNanos','publishNanos'],0),
                   endMetrics=dict.fromkeys(['generation','packNanos','prepareNanos','publishNanos'],1),
                   worldGpu={}, surfaceCpu={}, surfaceGpu={}, composeCpu={}, composeGpu={})
              for name in module.NAMES]
    return dict(status='complete', phases=phases,
                frames=[dict(phase=name, intervalMs=5) for name in module.NAMES for _ in range(100)])


class ComparisonTest(unittest.TestCase):
    def test_matching_complete_profiles_compare_actual_intervals(self):
        p=profile(); result=module.compare(p,copy.deepcopy(p))
        self.assertTrue(result['accepted'])
        self.assertEqual(result['phases'][0]['after']['meanMs'],5)
        self.assertNotIn('composeGpu',result['phases'][-1]['after'])

    def test_rejects_frame_cap_and_inactivity_throttle(self):
        for key,value in [('framerateSetting',120),('effectiveFramerateLimit',30),('throttleReason','SHORT_AFK'),('vsync',True)]:
            p=profile(); q=copy.deepcopy(p);q['phases'][0][key]=value
            with self.subTest(key=key), self.assertRaises(ValueError):module.compare(p,q)

    def test_rejects_mismatched_viewport(self):
        p=profile();q=copy.deepcopy(p);q['phases'][0]['width']=960
        with self.assertRaises(ValueError):module.compare(p,q)

    def test_rejects_partial_and_missing_samples(self):
        p=profile();q=copy.deepcopy(p);q['status']='running'
        with self.assertRaises(ValueError):module.compare(p,q)
        q=copy.deepcopy(p);q['frames']=[]
        with self.assertRaises(ValueError):module.compare(p,q)

    def test_rejects_different_sun_or_weather(self):
        for key,value in [('sunAngle',1),('rainBrightness',.35),('rainLevel',.8)]:
            p=profile();q=copy.deepcopy(p);q['phases'][0][key]=value
            with self.subTest(key=key), self.assertRaises(ValueError):module.compare(p,q)
