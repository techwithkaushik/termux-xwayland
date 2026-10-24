#!/usr/bin/env python3
"""Regression tests for the Termux-XW11 command launcher."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "shell-loader/scripts/termux-xw11.in"


class LauncherStartupTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = SCRIPT.read_text(encoding="utf-8")

    def test_explicitly_launches_default_display_activity(self):
        self.assertIn('am start -n "@APPLICATION_ID@/com.termux.x11.MainActivity"', self.source)
        self.assertIn('if [ "${TERMUX_XW11_NO_AUTO_ACTIVITY:-0}" != "1" ]', self.source)

    def test_server_still_starts_if_activity_launch_fails(self):
        launch = self.source.index('if ! am start -n')
        exec_server = self.source.index('exec /system/bin/app_process', launch)
        self.assertLess(launch, exec_server)
        self.assertIn('the native server will still start.', self.source)

    def test_tagged_windows_and_headless_mode_are_preserved(self):
        self.assertIn('if [ "$arg" = "-tag" ]', self.source)
        self.assertIn('if [ "$has_tag" -eq 0 ]', self.source)
        self.assertIn('TERMUX_XW11_NO_AUTO_ACTIVITY=1', self.source)


if __name__ == "__main__":
    unittest.main()
