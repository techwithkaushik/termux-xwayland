#!/usr/bin/env python3
"""Ensure XW11 output is hosted by MainActivity without taking over input."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
ACTIVITY = ROOT / "lorie/src/main/java/com/termux/x11/MainActivity.java"
VIEW = ROOT / "lorie/src/main/java/com/termux/x11/Xw11NativeSurfaceView.java"
SERVER = ROOT / "lorie/src/main/java/com/termux/x11/Xw11OutputSocketServer.java"


class MainActivityOutputSurfaceTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.activity = ACTIVITY.read_text(encoding="utf-8")
        cls.view = VIEW.read_text(encoding="utf-8")
        cls.server = SERVER.read_text(encoding="utf-8")

    def test_main_activity_hosts_output_surface_and_accepts_auth_intent(self):
        self.assertIn("xw11OutputView = new Xw11NativeSurfaceView(this, true);", self.activity)
        self.assertIn("frm.addView(xw11OutputView);", self.activity)
        self.assertIn("startXw11OutputBridge(getIntent());", self.activity)
        self.assertIn("new Xw11OutputSocketServer(this, socketName, token, sessionId)", self.activity)
        self.assertIn("activity.attachXw11OutputTransport(accepted, sessionId)", self.server)
        self.assertIn("XW11AUTH ", self.server)
        self.assertIn("readHandshake(peer.getInputStream())", self.server)
        self.assertIn("setVisibility(View.VISIBLE)", self.activity)

    def test_output_only_view_does_not_submit_input(self):
        self.assertIn("if (outputOnly) return false;", self.view)
        self.assertIn("Retain the socket even when the view is still GONE", self.view)
        self.assertIn("else if (outputTransport != null && outputSessionId > 0)", self.view)
        for method in ("onTouchEvent", "onGenericMotionEvent", "onKeyDown", "onKeyUp", "onFocusChanged"):
            self.assertIn(method, self.view)

    def test_existing_input_handler_and_keyboard_listener_are_kept(self):
        self.assertIn("new TouchInputHandler(this, new InputEventSender(this, lorieView))", self.activity)
        self.assertIn("lorieView.setOnKeyListener(mLorieKeyListener);", self.activity)
        self.assertIn("mInputHandler.handleTouchEvent(lorieParent, lorieView, e)", self.activity)


if __name__ == "__main__":
    unittest.main()
