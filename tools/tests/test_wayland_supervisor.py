#!/usr/bin/env python3
"""Static regression checks for the experimental Termux-XW11 supervisor."""

from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "shell-loader/scripts/termux-xw11-wayland.in"


class WaylandSupervisorTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = SCRIPT.read_text(encoding="utf-8")

    def test_starts_weston_managed_xwayland_without_desktop(self):
        self.assertIn("--xwayland", self.source)
        self.assertIn("--shell=kiosk", self.source)
        self.assertIn('BACKEND="headless"', self.source)
        self.assertIn('WESTON_ARGS+=(--backend=headless)', self.source)
        self.assertNotIn("startxfce", self.source.lower())
        self.assertNotIn("startlxde", self.source.lower())

    def test_requires_real_xwayland_and_checks_protocol_readiness(self):
        self.assertIn('command -v Xwayland', self.source)
        self.assertIn('command -v xdpyinfo', self.source)
        self.assertIn('[[ -S "$RUNTIME/$SESSION_SOCKET" ]]', self.source)
        self.assertIn("xserver listening on display", self.source)
        self.assertIn('DISPLAY=":$XDISPLAY" xdpyinfo', self.source)

    def test_nested_mode_is_explicit_and_checks_parent_display(self):
        self.assertIn('--nested) BACKEND="x11" ;;', self.source)
        self.assertIn('DISPLAY="$PARENT_DISPLAY" xdpyinfo', self.source)
        self.assertIn('WESTON_ARGS+=(--backend=x11)', self.source)

    def test_stop_only_signals_a_verified_weston_process(self):
        self.assertIn('is_owned_weston "$SESSION_PID"', self.source)
        self.assertIn('tr \'\\0\' \' \' < "/proc/$pid/cmdline"', self.source)
        self.assertIn('kill -TERM "$SESSION_PID"', self.source)
        self.assertIn('"$SESSION_RUNTIME" == "$RUNTIME_ROOT"/xw11-wayland.*', self.source)

    def test_explicitly_disclaims_android_surface_integration(self):
        self.assertIn("does not replace the Android Lorie renderer", self.source)
        self.assertIn("does not present Weston output on the Android app Surface", self.source)


if __name__ == "__main__":
    unittest.main()
