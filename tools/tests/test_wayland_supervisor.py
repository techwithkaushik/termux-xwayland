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

    def test_android_surface_output_transport_is_integrated(self):
        # Abstract AF_UNIX socket names are random, non-filesystem names; do not
        # regress to the old loopback-TCP port allocator.
        self.assertIn('"$AM" start -n "$ANDROID_COMPONENT"', self.source)
        self.assertIn('XW11_CAPTURE_BINARY="$CAPTURE"', self.source)
        self.assertIn('com.termux.x11.Loader --xw11-output-broker "$SESSION_ID"', self.source)
        self.assertIn('OUTPUT_TRANSPORT=Binder-FD', self.source)
        self.assertNotIn('"$CAPTURE" --connect "$OUTPUT_SOCKET"', self.source)
        self.assertIn('echo "status=running"', self.source)
        self.assertIn('frame_sent=1', self.source)


if __name__ == "__main__":
    unittest.main()
