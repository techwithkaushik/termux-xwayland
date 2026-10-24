#!/usr/bin/env python3
"""Ensure XW11 output is hosted by MainActivity without taking over input."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
ACTIVITY = ROOT / "lorie/src/main/java/com/termux/x11/MainActivity.java"
VIEW = ROOT / "lorie/src/main/java/com/termux/x11/Xw11NativeSurfaceView.java"


class MainActivityOutputSurfaceTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.activity = ACTIVITY.read_text(encoding="utf-8")
        cls.view = VIEW.read_text(encoding="utf-8")

    def test_main_activity_hosts_hidden_output_surface_until_transport_attaches(self):
        self.assertIn("xw11OutputView = new Xw11NativeSurfaceView(this, true);", self.activity)
        self.assertIn("xw11OutputView.setVisibility(View.GONE);", self.activity)
        self.assertIn("frm.addView(xw11OutputView);", self.activity)
        self.assertIn("xw11OutputView.attachOutputTransport(connectedSocket, sessionId)", self.activity)
        self.assertIn("xw11OutputView.setVisibility(View.VISIBLE);", self.activity)
        self.assertLess(
            self.activity.index("xw11OutputView.setVisibility(View.VISIBLE);", self.activity.index("boolean attachXw11OutputTransport")),
            self.activity.index("xw11OutputView.attachOutputTransport(connectedSocket, sessionId)", self.activity.index("boolean attachXw11OutputTransport")),
        )

    def test_output_only_view_does_not_submit_input(self):
        self.assertIn("if (outputOnly) return false;", self.view)
        self.assertIn("Retain the socket even when the view is still GONE", self.view)
        self.assertIn("else if (outputTransport != null && outputSessionId > 0)", self.view)
        self.assertIn("if (outputOnly) return;", self.view)
        for method in ("onTouchEvent", "onGenericMotionEvent", "onKeyDown", "onKeyUp", "onFocusChanged"):
            self.assertIn(method, self.view)

    def test_existing_input_handler_and_keyboard_listener_are_kept(self):
        self.assertIn("new TouchInputHandler(this, new InputEventSender(this, lorieView))", self.activity)
        self.assertIn("lorieView.setOnKeyListener(mLorieKeyListener);", self.activity)
        self.assertIn("mInputHandler.handleTouchEvent(lorieParent, lorieView, e)", self.activity)


if __name__ == "__main__":
    unittest.main()
