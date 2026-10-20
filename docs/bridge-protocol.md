# Local bridge protocol v1

**Status:** bounded framing, authenticated abstract Unix-domain socket transport, one synthetic RGBA test frame, and Android-side frame decoding/rendering are implemented. This remains a transport/rendering smoke test; the test pattern is not desktop output. Wayland compositor integration, real frame capture, input forwarding and production session brokering are not implemented.

## Transport and security

- The Android display-host app listens on IPv4 loopback TCP `127.0.0.1:38473` only. It must never bind to `0.0.0.0`, a LAN interface, or a public address.
- The Termux `app_process` client connects to `127.0.0.1:38473`. This replaces abstract AF_UNIX for this device because on-device testing showed `Permission denied` for the cross-UID abstract-socket connection.
- The transport does not use Wi-Fi or the Internet, but TCP loopback is still reachable by local apps. A fresh random 32-character lowercase hex session ID is required in the TXWB `HELLO` payload before the host sends `WELCOME`. Never log session payloads. This handshake is a local pairing gate, not a substitute for a future stronger secret broker.
- The Android host binds only to loopback and validates the session ID before accepting frames. Do not add unauthenticated listeners or fall back to a public TCP address.
- The server handles one client at a time. Clipboard and input messages are not enabled.

## Frame format

All integer fields are unsigned, big-endian. Every message begins with a 12-byte header:

| Offset | Size | Meaning |
|---:|---:|---|
| 0 | 4 | ASCII magic `TXWB` |
| 4 | 1 | Protocol version (`1`) |
| 5 | 1 | Message kind |
| 6 | 2 | Reserved; must be zero |
| 8 | 4 | Payload length, excluding header |

Payloads are limited to 16 MiB. Receivers validate the header and length before allocating the payload. Unknown kinds, unsupported versions, nonzero reserved bits, truncated messages and oversized payloads are protocol errors.

## Message kinds

| Value | Name | Direction | Current status |
|---:|---|---|---|
| 1 | `HELLO` | Android → Termux | Implemented: exactly 32 token bytes |
| 2 | `WELCOME` | Termux → Android | Implemented: 4-byte effective Termux process UID (big-endian) after valid token |
| 3 | `FRAME` | Termux → Android | Smoke-test producer and Android renderer implemented; sends one synthetic 320×180 RGBA pattern, not desktop pixels |
| 4 | `INPUT` | Android → Termux | Planned; event schema still required |
| 5 | `RESIZE` | Android → Termux | Planned |
| 6 | `CLIPBOARD` | Bidirectional | Planned; must be opt-in and separately bounded |
| 7 | `PING` | Bidirectional | Server responds with `PONG`; no periodic heartbeat yet |
| 8 | `PONG` | Bidirectional | Implemented by smoke-test server |
| 9 | `CLOSE` | Bidirectional | Implemented for graceful test-session shutdown |

## Payload conventions

- `HELLO`: exactly 32 bytes of session token. Never log payload bytes.
- `WELCOME`: exactly 4 bytes containing the Termux process effective UID as an unsigned big-endian `u32`. The Android app compares this with `Process.myUid()` to show whether the connection crosses app UIDs.
- `FRAME`: width (`u16`), height (`u16`), pixel format (`u8`, `1` = RGBA8888), three reserved zero bytes, then `width × height × 4` bytes. The legacy local-socket diagnostic sends one synthetic 320×180 pattern. The Termux loopback client can send actual X11/XWayland captures as 640×360 RGBA at 2 FPS when ffmpeg is available; the Android service validates metadata and exact payload length before rendering. Native Wayland capture is not implemented. The 16 MiB envelope cap means larger frames must be tiled or compressed rather than bypassing the limit.
- `RESIZE`: width (`u16`) and height (`u16`); reject zero dimensions and values beyond implementation limits.
- `CLIPBOARD`: UTF-8 bytes, maximum 1 MiB, only after explicit session authentication. Never automatically execute clipboard contents.
- `PING`, `PONG`, `CLOSE`: empty payloads in version 1.
- `INPUT`: define explicit event types, coordinate bounds, pointer/button state, key representation and input-rate limits before enabling.

## Testing and state

Rust tests cover framing and rejection of malformed headers, oversized payloads, token validation and abstract socket address construction. The Termux client validates the `WELCOME` UID against its own effective UID, receives the framed protocol response, sends `PING` and requires an empty `PONG`. TCP does not expose the kernel peer UID in the same way as local sockets, so the bridge does not claim kernel peer-UID verification. “Cross-UID IPC passed” means the authenticated socket connected despite different UIDs and a request/response round trip worked; it does not mean a desktop is connected. The displayed round-trip time is a local smoke-test measurement, not a performance guarantee. If Android cannot retrieve valid peer credentials or the server-reported UID disagrees, the test fails rather than claiming success.

CI compiles the Android client and native server, but cannot verify that a real Android device's SELinux policy permits loopback TCP connections. That requires on-device testing. A production client must clear state on close, timeout, app lifecycle transitions and reconnect failures.
