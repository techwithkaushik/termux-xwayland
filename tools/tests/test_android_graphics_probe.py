#!/usr/bin/env python3
"""Regression guards for the opt-in Android EGL diagnostic path.

These are source-contract checks, not substitutes for building the APK or running
on a device. The real Weston-buffer import path remains a separate acceptance gate.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]


class AndroidGraphicsProbeContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.java_probe = (ROOT / "lorie/src/main/java/com/termux/x11/NativeGraphicsProbe.java").read_text()
        cls.activity = (ROOT / "lorie/src/main/java/com/termux/x11/MainActivity.java").read_text()
        cls.native = (ROOT / "lorie/src/main/cpp/lorie/graphics_probe.cpp").read_text()
        cls.cmake = (ROOT / "lorie/src/main/cpp/recipes/xserver.cmake").read_text()
        cls.probe_activity = (ROOT / "lorie/src/main/java/com/termux/x11/NativeGraphicsProbeActivity.java").read_text()
        cls.manifest = (ROOT / "lorie/src/main/AndroidManifest.xml").read_text()
        cls.runner = (ROOT / "tools/run-android-egl-probe.sh").read_text()
        cls.presenter_activity = (ROOT / "lorie/src/main/java/com/termux/x11/NativePresenterProbeActivity.java").read_text()
        cls.presenter_view = (ROOT / "lorie/src/main/java/com/termux/x11/Xw11NativeSurfaceView.java").read_text()
        cls.presenter_runner = (ROOT / "tools/run-android-presenter-probe.sh").read_text()

    def test_java_and_jni_entry_points_match(self):
        self.assertIn("native String probeEglCapabilities();", self.java_probe)
        self.assertIn("native String probeWindowSurface(Surface surface);", self.java_probe)
        self.assertIn("Java_com_termux_x11_NativeGraphicsProbe_probeEglCapabilities", self.native)
        self.assertIn("Java_com_termux_x11_NativeGraphicsProbe_probeWindowSurface", self.native)

    def test_native_probe_is_linked_into_existing_android_library(self):
        self.assertIn('"lorie/graphics_probe.cpp"', self.cmake)
        self.assertIn("android", self.cmake)
        self.assertIn("EGL", self.cmake)
        self.assertIn("GLESv2", self.cmake)

    def test_window_probe_uses_and_releases_android_surface(self):
        self.assertIn("ANativeWindow_fromSurface(env, surface_object)", self.native)
        self.assertIn("eglCreateWindowSurface(display, config, window, nullptr)", self.native)
        self.assertIn("eglSwapBuffers(display, egl_surface)", self.native)
        self.assertIn("ANativeWindow_release(window)", self.native)
        self.assertIn("eglDestroySurface(display, egl_surface)", self.native)

    def test_diagnostic_has_an_isolated_activity_and_runner(self):
        self.assertIn('android:name=".NativeGraphicsProbeActivity"', self.manifest)
        self.assertIn('android:exported="true"', self.manifest)
        self.assertIn("NativeGraphicsProbe.probeEglCapabilities()", self.probe_activity)
        self.assertIn("NativeGraphicsProbe.probeWindowSurface(holder.getSurface())", self.probe_activity)
        self.assertIn('ACTIVITY="${XW11_ACTIVITY:-com.termux.x11.NativeGraphicsProbeActivity}"', self.runner)
        self.assertNotIn("RUN_EGL_PROBE", self.runner)
        self.assertIn("window surface swap: passed", self.runner)


    def test_native_presenter_probe_is_isolated_and_bounded(self):
        self.assertIn('android:name=".NativePresenterProbeActivity"', self.manifest)
        self.assertIn("Xw11NativeSurfaceView(this)", self.presenter_activity)
        self.assertIn("MAX_TEST_PIXELS = 4 * 1024 * 1024", self.presenter_activity)
        # Allow line wrapping and the captured dimensions used by the worker thread.
        self.assertRegex(
            self.presenter_activity,
            r"presentRgbaFrame\(\s*pixels,\s*(?:width|frameWidth),\s*(?:height|frameHeight),\s*stride,\s*generation\s*\)",
        )
        self.assertIn("generationForDiagnostics()", self.presenter_activity)
        # Large frame allocation, generation, and native Surface locking must stay off the UI thread.
        self.assertIn('new Thread(() -> {', self.presenter_activity)
        self.assertIn('}, "XW11-Presenter-Probe");', self.presenter_activity)
        self.assertIn("worker.start();", self.presenter_activity)
        self.assertIn("runOnUiThread(() -> {", self.presenter_activity)
        # A destroyed/replaced Surface must invalidate old frame generations before detach.
        destroyed = self.presenter_view.index("public void surfaceDestroyed(@NonNull SurfaceHolder holder)")
        destroyed_end = self.presenter_view.index("\n    }", destroyed)
        lifecycle = self.presenter_view[destroyed:destroyed_end]
        self.assertIn("generation = 0;", lifecycle)
        self.assertIn("nativeDetachSurface(current);", lifecycle)
        self.assertIn("frameGeneration != generation", self.presenter_view)
        self.assertIn("final int frameWidth = width;", self.presenter_activity)
        self.assertIn("final int frameHeight = height;", self.presenter_activity)
        self.assertIn("long generationForDiagnostics()", self.presenter_view)
        self.assertIn("nativePresentRgbaFrame", self.presenter_view)
        self.assertIn('ACTIVITY="${XW11_ACTIVITY:-com.termux.x11.NativePresenterProbeActivity}"', self.presenter_runner)
        self.assertIn("PASS: native ANativeWindow CPU-copy frame posted", self.presenter_runner)
        self.assertIn("no Weston frame producer is connected", self.presenter_runner)

    def test_diagnostic_is_opt_in_and_surface_lifecycle_scoped(self):
        key = 'getBooleanExtra("org.techwithkaushik.termuxxw11.RUN_EGL_PROBE", false)'
        self.assertIn(key, self.activity)
        self.assertIn("probeView.getHolder().addCallback(new SurfaceHolder.Callback()", self.activity)
        self.assertIn("surfaceCreated(@NonNull SurfaceHolder holder)", self.activity)
        self.assertIn("probeWindowSurface(holder.getSurface())", self.activity)
        self.assertIn("return;", self.activity[self.activity.index(key):self.activity.index("app = (LorieApp) getApplication()")])
        self.assertIn("Capability hints only; not a Weston buffer import test", self.native)


if __name__ == "__main__":
    unittest.main(verbosity=2)
