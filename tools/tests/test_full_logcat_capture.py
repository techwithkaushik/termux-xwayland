#!/usr/bin/env python3
"""Regression tests for complete logcat capture in the X11 activity bridge."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "lorie/src/main/cpp/lorie/activity.cpp"


class FullLogcatCaptureTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        source = SOURCE.read_text(encoding="utf-8")
        start = source.index("static void startLogcat(")
        end = source.index("\nstatic void sendTextEvent(", start)
        cls.start_logcat = source[start:end]

    def test_logcat_is_not_restricted_to_parent_pid(self):
        self.assertNotIn("--pid=", self.start_logcat)
        self.assertNotIn('sprintf(buf, "--pid=', self.start_logcat)

    def test_logcat_still_streams_to_the_existing_output_fd(self):
        self.assertIn("dup2(fd, 1);", self.start_logcat)
        self.assertIn("dup2(fd, 2);", self.start_logcat)
        self.assertIn('execl("/system/bin/logcat", "logcat", NULL);', self.start_logcat)

    def test_child_still_exits_when_parent_dies(self):
        self.assertIn("prctl(PR_SET_PDEATHSIG, SIGTERM);", self.start_logcat)


if __name__ == "__main__":
    unittest.main()
