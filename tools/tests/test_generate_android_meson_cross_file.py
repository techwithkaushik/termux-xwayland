#!/usr/bin/env python3
"""Unit tests for the Android Meson cross-file generator using a fake NDK."""
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "generate-android-meson-cross-file.py"
COMPILERS = {
    "armeabi-v7a": "armv7a-linux-androideabi24-clang",
    "arm64-v8a": "aarch64-linux-android24-clang",
    "x86": "i686-linux-android24-clang",
    "x86_64": "x86_64-linux-android24-clang",
}


class CrossFileGeneratorTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.bin_dir = self.root / "ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
        self.bin_dir.mkdir(parents=True)
        (self.bin_dir.parent / "sysroot").mkdir()
        compiler_names = [name for compiler in COMPILERS.values() for name in (compiler, compiler.replace("-clang", "-clang++"))]
        for name in ("llvm-ar", "llvm-strip", *compiler_names):
            path = self.bin_dir / name
            path.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
            path.chmod(0o755)
        # The script resolves pkg-config through PATH. A stub keeps the test
        # independent of whether the machine has a system pkg-config binary.
        fakebin = self.root / "host-bin"
        fakebin.mkdir()
        pkg = fakebin / "pkg-config"
        pkg.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
        pkg.chmod(0o755)
        self.env = dict(os.environ)
        self.env["PATH"] = str(fakebin) + os.pathsep + self.env.get("PATH", "")
        self.prefix = self.root / "target-prefix"
        (self.prefix / "lib/pkgconfig").mkdir(parents=True)
        (self.prefix / "share/pkgconfig").mkdir(parents=True)
        (self.prefix / "include").mkdir()
        self.output = self.root / "generated/android-cross.ini"

    def tearDown(self):
        self.temp.cleanup()

    def run_generator(self, abi):
        return subprocess.run(
            [
                sys.executable, str(SCRIPT),
                "--abi", abi, "--api", "24",
                "--ndk", str(self.root / "ndk"),
                "--prefix", str(self.prefix),
                "--output", str(self.output),
            ],
            text=True, capture_output=True, env=self.env, check=False,
        )

    def test_generates_cross_file_for_every_supported_abi(self):
        for abi, compiler in COMPILERS.items():
            with self.subTest(abi=abi):
                result = self.run_generator(abi)
                self.assertEqual(result.returncode, 0, result.stderr)
                config = self.output.read_text(encoding="utf-8")
                self.assertIn(compiler, config)
                self.assertIn("system = 'android'", config)
                self.assertIn("needs_exe_wrapper = true", config)
                self.assertIn(str(self.bin_dir.parent / "sysroot"), config)
                self.assertTrue((self.output.parent / "android-pkg-config").is_file())

    def test_rejects_missing_target_pkg_config_metadata(self):
        (self.prefix / "lib/pkgconfig").rmdir()
        (self.prefix / "share/pkgconfig").rmdir()
        result = self.run_generator("arm64-v8a")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("no target pkg-config metadata", result.stderr)


if __name__ == "__main__":
    unittest.main()
