#!/usr/bin/env python3
"""One-shot TXWB v1 framing probe; loopback-only and not a runtime bridge."""
import socket
import struct
import sys

HOST = "127.0.0.1"
PORT = 38472
MAGIC = b"TXWB"
VERSION = 1
PING = 7
PONG = 8
HEADER = struct.Struct("!4sBBHI")
MAX_PAYLOAD = 64 * 1024
EXPECTED_PAYLOAD = b"TERMUX_XWAYLAND_FRAME_PROBE"


def recv_exact(conn: socket.socket, size: int) -> bytes:
    data = bytearray()
    while len(data) < size:
        chunk = conn.recv(size - len(data))
        if not chunk:
            raise ValueError(f"peer closed early: expected {size} bytes, got {len(data)}")
        data.extend(chunk)
    return bytes(data)


def main() -> int:
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as server:
            server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            server.bind((HOST, PORT))
            server.listen(1)
            server.settimeout(60)
            print(f"TXWB v1 framing diagnostic listening on {HOST}:{PORT} (loopback only)", flush=True)
            try:
                conn, address = server.accept()
            except socket.timeout:
                print("FAIL: no Android connection received within 60 seconds", file=sys.stderr)
                return 2
            with conn:
                conn.settimeout(3)
                header = recv_exact(conn, HEADER.size)
                magic, version, kind, reserved, length = HEADER.unpack(header)
                if magic != MAGIC:
                    raise ValueError("invalid TXWB magic")
                if version != VERSION:
                    raise ValueError(f"unsupported protocol version: {version}")
                if reserved != 0:
                    raise ValueError("reserved header bits must be zero")
                if kind != PING:
                    raise ValueError(f"expected PING (7), got message type {kind}")
                if length > MAX_PAYLOAD:
                    raise ValueError(f"payload length {length} exceeds {MAX_PAYLOAD}-byte diagnostic limit")
                payload = recv_exact(conn, length)
                if payload != EXPECTED_PAYLOAD:
                    raise ValueError("payload did not match the expected diagnostic marker")
                response = HEADER.pack(MAGIC, VERSION, PONG, 0, len(payload)) + payload
                conn.sendall(response)
                print(
                    f"PASS: TXWB v{version} PING/PONG frame verified; "
                    f"{HEADER.size}-byte header, {length}-byte exact payload, peer {address[0]}",
                    flush=True,
                )
                return 0
    except (OSError, ValueError, struct.error) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
