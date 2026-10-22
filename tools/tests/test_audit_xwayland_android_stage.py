"""Tests for the Android dependency staging-prefix audit."""
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "audit-xwayland-android-stage.py"
MODULES = (
    "libdrm", "epoxy", "pciaccess", "pixman-1", "wayland-client",
    "wayland-protocols", "x11", "xau", "xcvt", "xfont2", "xinerama",
    "xkbfile", "xshmfence", "openssl",
)


@unittest.skipUnless(shutil.which("pkg-config"), "pkg-config is required")
class AndroidStageAuditTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.prefix = Path(self.temp.name) / "stage"
        self.pc_dir = self.prefix / "lib/pkgconfig"
        self.pc_dir.mkdir(parents=True)

    def tearDown(self):
        self.temp.cleanup()

    def write_modules(self, prefix_value=None):
        package_prefix = str(prefix_value or self.prefix)
        for module in MODULES:
            (self.pc_dir / f"{module}.pc").write_text(
                f"prefix={package_prefix}\n"
                "exec_prefix=\u0024{prefix}\n"
                "libdir=\u0024{exec_prefix}/lib\n"
                "includedir=\u0024{prefix}/include\n"
                f"Name: {module}\n"
                "Description: synthetic staging test module\n"
                "Version: 1.0\n"
                "Libs: -L\u0024{libdir} -lfixture\n"
                "Cflags: -I\u0024{includedir}\n",
                encoding="utf-8",
            )

    def run_audit(self):
        return subprocess.run(
            [sys.executable, str(SCRIPT), "--prefix", str(self.prefix),
             "--abi", "armeabi-v7a"],
            text=True, capture_output=True, check=False,
        )

    def test_accepts_complete_relocatable_staging_metadata(self):
        self.write_modules()
        result = self.run_audit()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("Validated 14 required pkg-config modules", result.stdout)

    def test_rejects_missing_required_module(self):
        self.write_modules()
        (self.pc_dir / "xkbfile.pc").unlink()
        result = self.run_audit()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("required pkg-config module missing: xkbfile", result.stderr)

    def test_rejects_metadata_prefix_outside_staging_root(self):
        self.write_modules(prefix_value="/usr/local")
        result = self.run_audit()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("pkg-config prefix escapes", result.stderr)


if __name__ == "__main__":
    unittest.main()
