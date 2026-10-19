#!/usr/bin/env python3
"""Negative tests for the TXWB v1 diagnostic server; no socket or network required."""
import importlib.util
import pathlib
import struct
import unittest

SCRIPT = pathlib.Path(__file__).with_name("tcp-framed-protocol-probe.py")
SPEC = importlib.util.spec_from_file_location("tcp_framed_protocol_probe", SCRIPT)
PROBE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(PROBE)


class FramedProtocolValidationTests(unittest.TestCase):
    def make_header(self, *, magic=PROBE.MAGIC, version=PROBE.VERSION,
                    kind=PROBE.PING, reserved=0, length=0):
        return PROBE.HEADER.pack(magic, version, kind, reserved, length)

    def test_header_is_exactly_12_bytes(self):
        self.assertEqual(PROBE.HEADER.size, 12)

    def test_valid_ping_header_is_accepted(self):
        header = self.make_header(length=len(PROBE.EXPECTED_PAYLOAD))
        self.assertEqual(
            PROBE.validate_header(header),
            (PROBE.PING, len(PROBE.EXPECTED_PAYLOAD)),
        )

    def test_bad_magic_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "magic"):
            PROBE.validate_header(self.make_header(magic=b"NOPE"))

    def test_unsupported_version_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "version"):
            PROBE.validate_header(self.make_header(version=99))

    def test_nonzero_reserved_bits_are_rejected(self):
        with self.assertRaisesRegex(ValueError, "reserved"):
            PROBE.validate_header(self.make_header(reserved=1))

    def test_wrong_message_type_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "expected PING"):
            PROBE.validate_header(self.make_header(kind=PROBE.PONG))

    def test_payload_over_limit_is_rejected_before_read(self):
        with self.assertRaisesRegex(ValueError, "exceeds"):
            PROBE.validate_header(self.make_header(length=PROBE.MAX_PAYLOAD + 1))

    def test_wrong_header_length_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "header size"):
            PROBE.validate_header(b"TXWB")

    def test_truncated_payload_is_rejected(self):
        class ShortSocket:
            def __init__(self):
                self.sent = False
            def recv(self, size):
                if self.sent:
                    return b""
                self.sent = True
                return b"abc"
        with self.assertRaisesRegex(ValueError, "closed early"):
            PROBE.recv_exact(ShortSocket(), 5)


if __name__ == "__main__":
    unittest.main(verbosity=2)
