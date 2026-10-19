# Local bridge protocol v1

**Status:** bounded framing, authenticated abstract Unix-domain socket transport, one synthetic RGBA test frame, and Android-side frame decoding/rendering are implemented. This remains a transport/rendering smoke test; the test pattern is not desktop output. Wayland compositor integration, real frame capture, input forwarding and production session brokering are not implemented.

## Transport and security

- The Termux-side Rust process listens on a Linux/Android **abstract AF_UNIX stream socket** named `termux_xwayland_<16-hex-session-fingerprint>`. The first 8 bytes of the session token are hex-encoded to create a per-session socket name (the full token is never embedded in the name). The abstract address is represented as `@termux_xwayland_<16-hex-session-fingerprint>` in diagnostics; the leading NUL is part of the kernel address, not the displayed name.
- The Android app connects with `android.net.LocalSocket` using `LocalSocketAddress.Namespace.ABSTRACT`. This path does not use TCP/IP, localhost, a port, Wi-Fi, or the Internet permission.
- Abstract sockets have no filesystem permission checks. A known socket name may be reachable by other local processes depending on Android SELinux policy. The session-derived name reduces collisions and casual discovery but is not a substitute for token authentication. A fresh 32-byte token (64 hex characters) is required on every connection; never log or share the token.
- The token is supplied to the native process via `TERMUX_XWAYLAND_TOKEN_HEX`; the server never prints it. The workflow prints the token in the Termux terminal solely for manual entry into the Android app.
- Token bytes are compared using a constant-time equality loop after fixed-length validation. Failed handshakes are rejected.
- The environment-variable mechanism is development plumbing, not a release-ready secret broker. A future Termux launcher should provide scoped, short-lived credentials and a session-specific socket name. Production transport also needs connection-rate limits, robust lifecycle management and a tested pairing flow.
- Android/Termux cross-UID abstract-socket connectivity can be affected by device SELinux policy and OEM builds. It must be tested on target devices; do not silently fall back to a public TCP listener if blocked.
- The server currently handles one client at a time. Clipboard and input messages are not enabled.

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
- `FRAME`: width (`u16`), height (`u16`), pixel format (`u8`, initially `1` = RGBA8888), three reserved zero bytes, then `width × height × 4` bytes. The smoke test sends one 320×180 synthetic pattern after `WELCOME`; Android validates metadata and exact payload length before rendering. The 16 MiB envelope cap means larger frames must be tiled or compressed rather than bypassing the limit.
- `RESIZE`: width (`u16`) and height (`u16`); reject zero dimensions and values beyond implementation limits.
- `CLIPBOARD`: UTF-8 bytes, maximum 1 MiB, only after explicit session authentication. Never automatically execute clipboard contents.
- `PING`, `PONG`, `CLOSE`: empty payloads in version 1.
- `INPUT`: define explicit event types, coordinate bounds, pointer/button state, key representation and input-rate limits before enabling.

## Testing and state

Rust tests cover framing and rejection of malformed headers, oversized payloads, token validation and abstract socket address construction. The Android client validates the `WELCOME` UID, receives and renders the synthetic `FRAME`, then sends `PING` and requires an empty `PONG`. “Cross-UID IPC passed” means the authenticated socket connected despite different UIDs and a request/response round trip worked; it does not mean a desktop is connected. The displayed round-trip time is a local smoke-test measurement, not a performance guarantee.

CI compiles the Android client and native server, but cannot verify that a real Android device's SELinux policy permits cross-UID abstract-socket connections. That requires on-device testing. A production client must clear state on close, timeout, app lifecycle transitions and reconnect failures.
