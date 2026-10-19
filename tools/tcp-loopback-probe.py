#!/usr/bin/env python3
"""One-shot loopback TCP diagnostic for Termux-XWayland; not a runtime bridge."""
import socket
import sys

HOST = "127.0.0.1"
PORT = 38471
REQUEST = b"TERMUX_XWAYLAND_TCP_PROBE\n"
RESPONSE = b"PASS TERMUX_XWAYLAND_TCP_PROBE\n"


def main() -> int:
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as server:
            server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            server.bind((HOST, PORT))
            server.listen(1)
            server.settimeout(60)
            print(f"TCP diagnostic listening on {HOST}:{PORT} (loopback only)", flush=True)
            try:
                conn, address = server.accept()
            except socket.timeout:
                print("FAIL: no Android connection received within 60 seconds", file=sys.stderr)
                return 2
            with conn:
                conn.settimeout(3)
                data = bytearray()
                while len(data) <= len(REQUEST):
                    chunk = conn.recv(len(REQUEST) + 1 - len(data))
                    if not chunk:
                        break
                    data.extend(chunk)
                    if data.endswith(b"\n"):
                        break
                if bytes(data) != REQUEST:
                    print(f"FAIL: unexpected request ({len(data)} bytes)", file=sys.stderr)
                    return 3
                conn.sendall(RESPONSE)
                print(f"PASS: loopback TCP echo completed for peer {address[0]}", flush=True)
                return 0
    except OSError as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
