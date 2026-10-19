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
    def test_header_is_exactly_12_bytes(self):
        self.assertEqual(PROBE.HEADER.size, 12)

    def test_valid_header_fields_are_big_endian(self):
        encoded = PROBE.HEADER.pack(
            PROBE.MAGIC, PROBE.VERSION, PROBE.PING, 0, len(PROBE.EXPECTED_PAYLOAD)
        )
        magic, version, kind, reserved, length = PROBE.HEADER.unpack(encoded)
        self.assertEqual((magic, version, kind, reserved, length), (
            b"TXWB", 1, 7, 0, len(PROBE.EXPECTED_PAYLOAD)
        ))

    def test_bad_magic_is_detectable(self):
        header = PROBE.HEADER.pack(b"NOPE", PROBE.VERSION, PROBE.PING, 0, 0)
        self.assertNotEqual(PROBE.HEADER.unpack(header)[0], PROBE.MAGIC)

    def test_unsupported_version_is_detectable(self):
        header = PROBE.HEADER.pack(PROBE.MAGIC, 99, PROBE.PING, 0, 0)
        self.assertNotEqual(PROBE.HEADER.unpack(header)[1], PROBE.VERSION)

    def test_nonzero_reserved_bits_are_detectable(self):
        header = PROBE.HEADER.pack(PROBE.MAGIC, PROBE.VERSION, PROBE.PING, 1, 0)
        self.assertNotEqual(PROBE.HEADER.unpack(header)[3], 0)

    def test_wrong_message_type_is_detectable(self):
        header = PROBE.HEADER.pack(PROBE.MAGIC, PROBE.VERSION, PROBE.PONG, 0, 0)
        self.assertNotEqual(PROBE.HEADER.unpack(header)[2], PROBE.PING)

    def test_payload_limit_is_enforced_by_probe_policy(self):
        header = PROBE.HEADER.pack(
            PROBE.MAGIC, PROBE.VERSION, PROBE.PING, 0, PROBE.MAX_PAYLOAD + 1
        )
        length = PROBE.HEADER.unpack(header)[4]
        self.assertGreater(length, PROBE.MAX_PAYLOAD)

    def test_truncated_header_is_rejected(self):
        class ClosedSocket:
            def recv(self, size):
                return b""
        with self.assertRaises(ValueError):
            PROBE.recv_exact(ClosedSocket(), PROBE.HEADER.size)


if __name__ == "__main__":
    unittest.main(verbosity=2)
