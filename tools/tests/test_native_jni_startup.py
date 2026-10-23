#!/usr/bin/env python3
"""Guard legacy JNI startup against optional XW11 bridge failures."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "lorie/src/main/cpp/lorie/activity.cpp"


class OptionalXw11JniStartupTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        source = SOURCE.read_text(encoding="utf-8")
        start = source.index("// This bridge is experimental and is not part of the legacy :0 startup path.")
        end = source.index("static JNINativeMethod mainActivityMethods[]", start)
        cls.bridge_registration = source[start:end]

    def test_optional_bridge_failure_does_not_abort_jni_load(self):
        self.assertNotIn("return JNI_ERR", self.bridge_registration)
        self.assertIn("env->ExceptionClear()", self.bridge_registration)
        self.assertIn("continuing with legacy Lorie", self.bridge_registration)
        self.assertIn("continuing without it", self.bridge_registration)

    def test_successful_bridge_registration_is_logged(self):
        self.assertIn("Optional XW11 Surface bridge registered", self.bridge_registration)


if __name__ == "__main__":
    unittest.main()
