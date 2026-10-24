#!/usr/bin/env python3
"""Source-contract regression tests for the isolated Weston/XWayland smoke test."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "tools/test-weston-xwayland-runtime.sh"
RUNNER = ROOT / "tools/run-xw11-acceptance.sh"


class WestonRuntimeScriptContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.script = SCRIPT.read_text()
        cls.runner = RUNNER.read_text()

    def test_creates_termux_x11_socket_directory_without_server(self):
        self.assertIn('mkdir -p "$TMP_ROOT/.X11-unix"', self.script)
        self.assertNotIn('chmod 700 "$TMP_ROOT/.X11-unix"', self.script)
        self.assertNotIn('termux-xw11 :0', self.script)

    def test_headless_mode_uses_private_runtime_and_own_process(self):
        self.assertIn('RUNTIME="$(mktemp -d "$TMP_ROOT/xw11-acceptance.XXXXXX")"', self.script)
        self.assertIn('XDG_RUNTIME_DIR="$RUNTIME"', self.script)
        self.assertIn('WESTON_ARGS+=(--backend=headless)', self.script)
        self.assertIn('kill -TERM "$WESTON_PID"', self.script)
        self.assertIn('rm -rf "$RUNTIME"', self.script)

    def test_smoke_test_verifies_real_x11_client(self):
        self.assertIn('xdpyinfo', self.script)
        self.assertIn('xterm -title "XW11 Acceptance Smoke Test"', self.script)
        self.assertIn('xwininfo -root -tree', self.script)
        self.assertIn('PASS: Weston + XWayland smoke test complete.', self.script)


    def test_supervisor_only_signals_its_own_socket_session(self):
        supervisor = (ROOT / "shell-loader/scripts/termux-xw11-wayland.in").read_text()
        self.assertIn('cmdline="$(tr \'\\0\' \' \' < "/proc/$pid/cmdline")" || return 1', supervisor)
        self.assertIn('[[ "$cmdline" == *"--socket=$SOCKET"* ]]', supervisor)
        self.assertIn('kill -TERM "$SESSION_PID"', supervisor)
        self.assertIn('SESSION_RUNTIME" == "$RUNTIME_ROOT"/xw11-wayland.*', supervisor)

    def test_optional_gates_are_both_attempted_and_failures_reported(self):
        self.assertIn('run_optional_gate()', self.runner)
        self.assertIn('run_optional_gate "Weston runtime test"', self.runner)
        self.assertIn('run_optional_gate "Android presenter probe"', self.runner)
        self.assertIn('run_optional_gate "Android app-process EGL probe"', self.runner)
        self.assertIn('XW11_RUN_ANDROID_EGL_PROBE', self.runner)
        self.assertIn('tools/run-android-egl-probe.sh', self.runner)
        self.assertIn('optional_failures', self.runner)
        self.assertIn('exit 1', self.runner)


if __name__ == "__main__":
    unittest.main(verbosity=2)
