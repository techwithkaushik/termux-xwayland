# Local bridge protocol v1

**Status:** bounded framing, authenticated loopback transport, one synthetic RGBA test frame, and Android-side frame decoding/rendering are implemented. This remains a transport/rendering smoke test; the test pattern is not desktop output. Wayland compositor integration, real frame capture, input forwarding and production session brokering are not implemented.

## Transport and security

- The current server binds explicitly to IPv4 loopback (`127.0.0.1`), never `0.0.0.0` or a LAN interface.
- Each test run requires a fresh 32-byte token (64 hexadecimal characters), supplied to the native process via `TERMUX_XWAYLAND_TOKEN_HEX`. The server never prints the token. The workflow prints it in the local terminal solely for manual entry in the Android app; treat it as a secret.
- Token bytes are compared with a constant-time equality loop after the fixed-size token check. Failed handshakes are rejected and the connection is closed.
- The current environment-variable mechanism is development plumbing, not a release-ready secret broker. A future launcher should deliver one-time session credentials through a scoped mechanism that avoids process-environment exposure.
- Read/write timeouts are applied to the current handshake socket. Production transport needs stronger lifecycle management, rate limiting and a tested pairing flow.
- Loopback reachability between Termux and the Android app is device/ROM dependent and must be tested. If blocked, do not fall back to a public listener; evaluate a scoped IPC mechanism.
- Clipboard and input messages are not enabled by the current smoke-test server.

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
| 2 | `WELCOME` | Termux → Android | Implemented: empty payload after valid token |
| 3 | `FRAME` | Termux → Android | Smoke-test producer and Android renderer implemented; sends one synthetic 320×180 RGBA pattern, not desktop pixels |
| 4 | `INPUT` | Android → Termux | Planned; event schema still required |
| 5 | `RESIZE` | Android → Termux | Planned |
| 6 | `CLIPBOARD` | Bidirectional | Planned; must be opt-in and separately bounded |
| 7 | `PING` | Bidirectional | Server responds with `PONG`; no periodic heartbeat yet |
| 8 | `PONG` | Bidirectional | Implemented by smoke-test server |
| 9 | `CLOSE` | Bidirectional | Implemented for graceful test-session shutdown |

## Planned payload conventions

- `HELLO`: exactly 32 bytes of session token. Never log payload bytes.
- `WELCOME`: empty payload.
- `FRAME`: width (`u16`), height (`u16`), pixel format (`u8`, initially `1` = RGBA8888), three reserved zero bytes, then `width × height × 4` bytes. The smoke test sends one 320×180 synthetic pattern after `WELCOME`; Android validates metadata and exact payload length before rendering. The 16 MiB envelope cap means large frames must be tiled or compressed rather than bypassing the limit.
- `RESIZE`: width (`u16`) and height (`u16`); reject zero dimensions and values beyond implementation limits.
- `CLIPBOARD`: UTF-8 bytes, maximum 1 MiB, only after explicit session authentication. Never automatically execute clipboard contents.
- `PING`, `PONG`, `CLOSE`: empty payloads in version 1.
- `INPUT`: define explicit event types, coordinate bounds, pointer/button state, key representation and input-rate limits before enabling.

## Testing and state

Rust tests cover round trips and rejection of bad magic, unsupported versions, unknown kinds, nonzero reserved bits, oversized payloads and token validation. The Android client verifies the empty `WELCOME`, then validates and displays the synthetic `FRAME`. “Test frame received” confirms authenticated transport and rendering only; it must not be interpreted as a connected desktop. A production client must clear state on close, timeout, app lifecycle transitions and reconnect failures.
