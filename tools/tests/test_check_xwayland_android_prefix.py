"""Tests for the target-prefix ELF architecture guard."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "check-xwayland-android-prefix.py"


@unittest.skipUnless(shutil.which("cc") and shutil.which("ar") and
                     (shutil.which("readelf") or shutil.which("llvm-readelf")),
                     "host C compiler, ar and readelf are required")
class PrefixArchitectureCheckTest(unittest.TestCase):
    def run_guard(self, prefix):
        return subprocess.run(
            [shutil.which("python3") or "python3", str(SCRIPT),
             "--prefix", str(prefix), "--abi", "armeabi-v7a"],
            text=True, capture_output=True, check=False,
        )

    def test_rejects_host_elf_library_in_arm_staging_prefix(self):
        with tempfile.TemporaryDirectory() as temp:
            prefix = Path(temp)
            libdir = prefix / "lib"
            libdir.mkdir()
            source = prefix / "tiny.c"
            source.write_text("int xwayland_prefix_fixture(void) { return 1; }\n", encoding="utf-8")
            library = libdir / "libfixture.so"
            build = subprocess.run(
                ["cc", "-shared", "-fPIC", str(source), "-o", str(library)],
                text=True, capture_output=True, check=False,
            )
            self.assertEqual(build.returncode, 0, build.stderr)
            result = self.run_guard(prefix)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("unexpected ELF machine", result.stderr)

    def test_rejects_host_static_archive_in_arm_staging_prefix(self):
        with tempfile.TemporaryDirectory() as temp:
            prefix = Path(temp)
            libdir = prefix / "lib"
            libdir.mkdir()
            source = prefix / "tiny.c"
            source.write_text("int xwayland_archive_fixture(void) { return 1; }\n", encoding="utf-8")
            object_file = prefix / "tiny.o"
            compile_result = subprocess.run(
                ["cc", "-c", str(source), "-o", str(object_file)],
                text=True, capture_output=True, check=False,
            )
            self.assertEqual(compile_result.returncode, 0, compile_result.stderr)
            archive = libdir / "libfixture.a"
            archive_result = subprocess.run(
                ["ar", "rcs", str(archive), str(object_file)],
                text=True, capture_output=True, check=False,
            )
            self.assertEqual(archive_result.returncode, 0, archive_result.stderr)
            result = self.run_guard(prefix)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("unexpected ELF machine", result.stderr)

    def test_reports_truncated_elf_shared_library(self):
        with tempfile.TemporaryDirectory() as temp:
            prefix = Path(temp)
            libdir = prefix / "lib"
            libdir.mkdir()
            malformed = libdir / "libtruncated.so"
            malformed.write_bytes(b"\x7fELF" + b"truncated")
            result = self.run_guard(prefix)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("readelf failed", result.stderr)

    def test_rejects_library_symlink_escaping_staging_prefix(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            prefix = root / "prefix"
            libdir = prefix / "lib"
            libdir.mkdir(parents=True)
            external_source = root / "outside.c"
            external_source.write_text(
                "int outside_prefix_fixture(void) { return 1; }\n",
                encoding="utf-8",
            )
            external_library = root / "liboutside.so"
            build = subprocess.run(
                ["cc", "-shared", "-fPIC", str(external_source), "-o", str(external_library)],
                text=True, capture_output=True, check=False,
            )
            self.assertEqual(build.returncode, 0, build.stderr)
            (libdir / "liboutside.so").symlink_to(external_library)
            result = self.run_guard(prefix)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("resolves outside staging prefix", result.stderr)


if __name__ == "__main__":
    unittest.main()
