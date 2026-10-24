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
        start = source.index("// Keep this bridge optional so a stale APK cannot prevent legacy :0 startup.")
        end = source.index("static JNINativeMethod mainActivityMethods[]", start)
        cls.bridge_registration = source[start:end]

    def test_optional_bridge_failure_does_not_abort_jni_load(self):
        self.assertNotIn("return JNI_ERR", self.bridge_registration)
        self.assertIn("env->ExceptionClear()", self.bridge_registration)
        self.assertIn("Optional XW11 Surface bridge class not found; continuing with legacy Lorie", self.bridge_registration)
        self.assertIn("env->ExceptionDescribe()", self.bridge_registration)
        self.assertIn("XW11 JNI registration result: %u registered, %u failed", self.bridge_registration)

    def test_successful_bridge_registration_is_logged(self):
        self.assertIn("XW11 JNI registration result: %u registered, %u failed", self.bridge_registration)
        self.assertIn("++registered", self.bridge_registration)
        self.assertIn("++failed", self.bridge_registration)

    def test_input_attach_jni_signature_matches_java_declaration(self):
        java_path = ROOT / "lorie/src/main/java/com/termux/x11/Xw11NativeSurfaceView.java"
        java = java_path.read_text(encoding="utf-8")
        source = SOURCE.read_text(encoding="utf-8")
        self.assertIn(
            "native int nativeAttachInputTransport(int fd, long sessionId, long generation)",
            java,
        )
        self.assertIn(
            '{"nativeAttachInputTransport", "(IJJ)I"',
            source,
        )
        self.assertIn(
            'JNIEnv *env, __unused jclass clazz, jint fd, jlong sessionId, jlong generation) -> jint',
            source,
        )

    def test_output_receiver_slot_is_reserved_until_worker_join(self):
        source = SOURCE.read_text(encoding="utf-8")
        start = source.index('{"nativeDetachOutputTransport"')
        end = source.index('{"nativeAttachInputTransport"', start)
        detach = source[start:end]
        self.assertIn("pthread_join(thread, nullptr)", detach)
        self.assertLess(detach.index("pthread_join(thread, nullptr)"),
                        detach.index("xw11OutputThreadCreated = false"))
        self.assertIn("xw11OutputThread == thread", detach)

    def test_first_successful_android_surface_presentation_is_logged(self):
        source = SOURCE.read_text(encoding="utf-8")
        worker_start = source.index("static void *xw11OutputWorker")
        worker_end = source.index("struct Xw11InputLock", worker_start)
        worker = source[worker_start:worker_end]
        self.assertIn("if (!firstFramePresented)", worker)
        self.assertIn("XW11 first frame presented to Android Surface", worker)
        self.assertLess(worker.index("presented != XW11_PRESENT_OK"),
                        worker.index("XW11 first frame presented to Android Surface"))
        self.assertIn("frame.width, frame.height", worker)
        self.assertIn("frame.sequence_id", worker)
        self.assertIn("generation);", worker)

    def test_received_frame_is_validated_and_converted_before_presentation(self):
        source = SOURCE.read_text(encoding="utf-8")
        worker_start = source.index("static void *xw11OutputWorker")
        worker_end = source.index("struct Xw11InputLock", worker_start)
        worker = source[worker_start:worker_end]
        self.assertIn("xw11_output_frame_to_rgba(", worker)
        self.assertIn("converted != XW11_OUTPUT_FRAME_OK", worker)
        self.assertIn("rgbaStride", worker)
        self.assertIn("rgba, rgbaStride", worker)
        self.assertIn("free(rgba);", worker)

    def test_output_receiver_allocates_rgba_for_actual_frame_dimensions(self):
        source = SOURCE.read_text(encoding="utf-8")
        worker_start = source.index("static void *xw11OutputWorker")
        worker_end = source.index("struct Xw11InputLock", worker_start)
        worker = source[worker_start:worker_end]
        self.assertIn("const size_t capacity = XW11_OUTPUT_FRAME_MAX_BYTES;", worker)
        self.assertIn("const size_t rgbaBytes = rgbaStride * (size_t)frame.height;", worker)
        self.assertIn("malloc(rgbaBytes)", worker)
        self.assertNotIn("malloc(capacity));\n    if (!pixels || !rgba)", worker)
        self.assertIn("free(rgba);", worker)


if __name__ == "__main__":
    unittest.main()
