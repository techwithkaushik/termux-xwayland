#!/usr/bin/env python3
"""Regression tests for duplicate ACTION_START broadcasts."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "lorie/src/main/java/com/termux/x11/LorieApp.java"


class DuplicateActionStartTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        source = SOURCE.read_text(encoding="utf-8")
        start = source.index("case ACTION_START:")
        end = source.index("case MainActivity.ACTION_STOP:", start)
        cls.action_start = source[start:end]

    def test_same_active_binder_does_not_rebind_or_restart_logcat(self):
        guard = self.action_start.index("if (binder.equals(activeService))")
        connect = self.action_start.index("activity.connectToService(binder)")
        self.assertLess(guard, connect)
        self.assertIn("if (binder.equals(activeService)) {\n                        break;", self.action_start)

    def test_new_or_pending_connection_can_still_connect(self):
        self.assertIn("if (!sameConnection)", self.action_start)
        self.assertIn("pendingConnections.put(tag, binder)", self.action_start)
        self.assertIn("activity.connectToService(binder)", self.action_start)


if __name__ == "__main__":
    unittest.main()
