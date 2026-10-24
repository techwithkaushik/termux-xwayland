#!/usr/bin/env python3
"""Guard the Weston output producer's bounded continuous-capture contract."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "tools/weston-capture/capture_main.c"
README = ROOT / "tools/weston-capture/README.md"


class WestonCaptureModeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = SOURCE.read_text(encoding="utf-8")
        cls.readme = README.read_text(encoding="utf-8")

    def test_one_shot_remains_default_and_continuous_is_opt_in(self):
        self.assertIn('strcmp(argv[4], "--continuous") == 0', self.source)
        self.assertIn("if (!continuous) break;", self.source)
        self.assertIn("xw11-weston-capture 7 1234 2 --continuous", self.readme)

    def test_continuous_mode_uses_monotonic_sequence_and_single_buffer(self):
        self.assertIn("uint64_t sequence = 1;", self.source)
        self.assertIn("++sequence;", self.source)
        self.assertIn("weston_capture_source_v1_capture(a.source, buffer);", self.source)
        self.assertIn("if (sequence == UINT64_MAX)", self.source)

    def test_transport_or_capture_failure_stops_loop(self):
        self.assertIn("if (a.failed)", self.source)
        self.assertIn("if (sent != XW11_OUTPUT_STREAM_OK)", self.source)
        self.assertIn("break;", self.source)

    def test_output_fd_is_validated_before_connecting_to_compositor(self):
        self.assertIn("#include <sys/socket.h>", self.source)
        self.assertIn("getsockopt((int)socket_fd, SOL_SOCKET, SO_TYPE", self.source)
        self.assertIn("socket_type != SOCK_STREAM", self.source)
        self.assertIn("peer.ss_family != AF_UNIX", self.source)
        self.assertIn("FD_CLOEXEC", self.source)
        self.assertLess(
            self.source.index("peer.ss_family != AF_UNIX"),
            self.source.index("wl_display_connect(NULL)"),
        )


if __name__ == "__main__":
    unittest.main()
