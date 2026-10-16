import json
import struct
import subprocess
import sys
import tempfile
import unittest
import zlib
from pathlib import Path


def solid_png(path, green, red=16):
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
    raw = (b"\0" + bytes([red, green, 16, 255]) * 32) * 32
    path.write_bytes(b"\x89PNG\r\n\x1a\n"
                     + chunk(b"IHDR", struct.pack(">IIBBBBB", 32, 32, 8, 6, 0, 0, 0))
                     + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))


class ProbeVerifierTest(unittest.TestCase):
    def verify(self, reload_green=76, status="complete"):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            for name, green in [("glow-on.png", 76), ("glow-off.png", 51),
                                ("glow-after-reload.png", reload_green), ("glow-after-reload-off.png", 51)]:
                solid_png(directory / name, green)
            (directory / "result.json").write_text(json.dumps({"status": status}))
            return subprocess.run([sys.executable, str(Path(__file__).with_name("verify-probe.py")), str(directory)],
                                  capture_output=True, text=True)

    def test_rejects_reload_that_returns_only_vanilla_pixels(self):
        self.assertNotEqual(0, self.verify(reload_green=51).returncode)

    def test_rejects_capture_failure_even_when_previous_pixels_look_valid(self):
        self.assertNotEqual(0, self.verify(status="failed").returncode)

    def test_accepts_both_enabled_disabled_pairs(self):
        result = self.verify()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
