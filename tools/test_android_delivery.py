"""Android delivery must fail before copying a host-only distribution."""
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]


class AndroidDeliveryTest(unittest.TestCase):
    def test_refuses_host_only_jar_without_creating_delivery(self):
        with tempfile.TemporaryDirectory() as temp:
            jar, destination = Path(temp) / 'host.jar', Path(temp) / 'delivery'
            with ZipFile(jar, 'w') as archive:
                archive.writestr('fabric.mod.json', '{"id":"greenmarine","version":"test"}')
                archive.writestr('native/linux-aarch64/libgreenmarine_engine.so', b'host')
            result = subprocess.run([sys.executable, str(ROOT / 'tools/export-android-mod.py'),
                                     str(jar), '--destination', str(destination)], capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('android-aarch64', result.stderr)
            self.assertFalse(destination.exists())


if __name__ == '__main__':
    unittest.main()
