#!/usr/bin/env python3
"""Static checks for the SELinux-safe XW11 Binder descriptor handoff."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]


class BinderOutputBrokerTests(unittest.TestCase):
    def test_aidl_exposes_a_session_scoped_parcel_file_descriptor(self):
        aidl = (ROOT / "lorie/src/main/aidl/com/termux/x11/IXw11OutputBroker.aidl").read_text()
        self.assertIn("ParcelFileDescriptor getOutputConnection(long sessionId, long surfaceGeneration)", aidl)

    def test_termux_broker_spawns_capture_with_socketpair_endpoint(self):
        java = (ROOT / "lorie/src/main/java/com/termux/x11/CmdEntryPoint.java").read_text()
        native = (ROOT / "lorie/src/main/cpp/lorie/cmdentrypoint.cpp").read_text()
        self.assertIn("--xw11-output-broker", java)
        self.assertIn("getXw11OutputConnection(long sessionId, long surfaceGeneration)", java)
        self.assertIn("socketpair(AF_UNIX, SOCK_STREAM, 0, pair)", native)
        self.assertIn("execv(capture, childArgv)", native)
        self.assertIn("ParcelFileDescriptor", native)
        self.assertIn('"getXw11OutputConnection", "(JJ)Landroid/os/ParcelFileDescriptor;"', native)

    def test_android_attaches_the_binder_descriptor_to_the_output_surface(self):
        activity = (ROOT / "lorie/src/main/java/com/termux/x11/MainActivity.java").read_text()
        application = (ROOT / "lorie/src/main/java/com/termux/x11/LorieApp.java").read_text()
        manifest = (ROOT / "lorie/src/main/AndroidManifest.xml").read_text()
        self.assertIn("broker.getOutputConnection(sessionId, generation)", activity)
        self.assertIn("attachXw11OutputTransport(accepted, sessionId)", activity)
        self.assertIn("ACTION_XW11_OUTPUT_BROKER", application)
        self.assertIn("com.termux.x11.ACTION_XW11_OUTPUT_BROKER", manifest)

    def test_supervisor_uses_binder_path_not_direct_abstract_socket_connect(self):
        script = (ROOT / "shell-loader/scripts/termux-xw11-wayland.in").read_text()
        self.assertIn('com.termux.x11.Loader --xw11-output-broker "$SESSION_ID"', script)
        self.assertIn('XW11_CAPTURE_BINARY="$CAPTURE"', script)
        self.assertNotIn('"$CAPTURE" --connect "$OUTPUT_SOCKET"', script)


if __name__ == "__main__":
    unittest.main()
