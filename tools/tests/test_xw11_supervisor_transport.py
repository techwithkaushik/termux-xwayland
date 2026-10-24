#!/usr/bin/env python3
"""Regression checks for the experimental XW11 supervisor session metadata."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "shell-loader/scripts/termux-xw11-wayland.in"


class SupervisorTransportLifecycleTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = SCRIPT.read_text(encoding="utf-8")

    def test_session_metadata_is_fresh_and_private(self):
        self.assertIn('SESSION_ID="$(od -An -N4 -tu4 /dev/urandom', self.source)
        self.assertIn('chmod 700 "$TRANSPORT_DIR"', self.source)
        self.assertIn('chmod 600 "$session_file" "$generation_file" "$transport_dir_file"', self.source)
        self.assertIn('XW11_CAPTURE_PID_FILE="$CAPTURE_AUTH_PID_FILE"', self.source)
        self.assertIn('chmod 600 "$session_file" "$generation_file" "$transport_dir_file"', self.source)

    def test_stop_removes_only_recorded_transport_directory_and_capture(self):
        self.assertIn('if [[ -n "$transport_dir" && "$transport_dir" == "$RUNTIME_ROOT"/xw11-transport.* ]]; then', self.source)
        self.assertIn('rm -rf -- "$transport_dir"', self.source)
        self.assertIn('"$capture_pid_file" "$broker_pid_file" "$output_socket_file" "$output_token_file"', self.source)
        self.assertIn('if [[ "$capture_cmdline" == *"--xw11-output-broker"*', self.source)

    def test_status_exposes_session_binding_for_diagnostics(self):
        self.assertIn('echo "SESSION_ID=$(cat "$session_file")"', self.source)
        self.assertIn('echo "SURFACE_GENERATION=$(cat "$generation_file")"', self.source)
        self.assertIn('echo "CAPTURE_PID=$(cat "$capture_pid_file")"', self.source)

    def test_supervisor_launches_authenticated_output_capture(self):
        self.assertIn('"$AM" start -n "$ANDROID_COMPONENT"', self.source)
        self.assertIn('com.termux.x11.Loader --xw11-output-broker "$SESSION_ID"', self.source)
        self.assertIn('XW11_CAPTURE_BINARY="$CAPTURE"', self.source)
        self.assertIn('NOTE: output capture uses a Binder-transferred connected AF_UNIX FD', self.source)
        self.assertIn("no desktop is launched", self.source)
        self.assertNotIn("authenticated loopback TCP", self.source)


if __name__ == "__main__":
    unittest.main()
