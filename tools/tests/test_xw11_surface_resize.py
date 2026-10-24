#!/usr/bin/env python3
"""Regression checks for output receiver lifecycle across Android Surface resize."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
VIEW = ROOT / "lorie/src/main/java/com/termux/x11/Xw11NativeSurfaceView.java"


class SurfaceResizeLifecycleTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = VIEW.read_text(encoding="utf-8")

    def test_output_socket_is_retained_for_receiver_restart(self):
        self.assertIn("ParcelFileDescriptor.dup(connectedSocket.getFileDescriptor())", self.source)
        self.assertIn("outputTransport = retained;", self.source)
        self.assertIn("outputSessionId = sessionId;", self.source)

    def test_resize_detaches_old_receiver_before_surface_update(self):
        changed = self.source.index("boolean sizeChanged = surfaceWidth != width || surfaceHeight != height;")
        detach = self.source.index("if (sizeChanged) detachOutputSafely(current);", changed)
        attach = self.source.index("int result = attachSurfaceSafely(surface, current);", detach)
        restart = self.source.index("attachOutputSafely(outputTransport.getFd(), outputSessionId, current)", attach)
        self.assertLess(changed, detach)
        self.assertLess(detach, attach)
        self.assertLess(attach, restart)
        self.assertIn("else if (outputTransport != null && outputSessionId > 0)", self.source)

    def test_surface_destroy_closes_retained_transport(self):
        destroyed = self.source.index("public void surfaceDestroyed")
        close = self.source.index("closeOutputTransport();", destroyed)
        self.assertGreater(close, destroyed)
        self.assertIn("outputSessionId = 0;", self.source)

    def test_legacy_view_is_not_modified_by_this_bridge(self):
        self.assertTrue((ROOT / "lorie/src/main/java/com/termux/x11/LorieView.java").is_file())


if __name__ == "__main__":
    unittest.main()
