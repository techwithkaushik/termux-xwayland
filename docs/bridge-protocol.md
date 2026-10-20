# Local bridge protocol v1

**Status:** the active Android-to-Termux session path uses an explicitly authorized Binder service and a transferred socket file descriptor (FD), with bounded TXWB framing, PING/PONG and optional X11/XWayland frame capture. The Rust abstract-socket server described below is a separate legacy transport smoke-test endpoint; it is not the active Binder session endpoint. Neither path is a complete Wayland compositor, and native Wayland capture/input forwarding remain unfinished.

## Transport and security

- **Legacy smoke-test only:** the standalone Termux-side Rust process can listen on a Linux/Android **abstract AF_UNIX stream socket** named `termux_xwayland_<16-hex-session-fingerprint>`. This endpoint is separate from the active Binder session flow. The first 8 bytes of the session token are hex-encoded to create a per-session socket name (the full token is never embedded in the name). The abstract address is represented as `@termux_xwayland_<16-hex-session-fingerprint>` in diagnostics; the leading NUL is part of the kernel address, not the displayed name.
- **Active session transport:** `BridgeClient` runs under the Termux UID, binds to the Android host `BridgeSessionService` using Binder, and receives a connected socket FD. It does not create or connect to a filesystem-named control socket. The FD handoff avoids a cross-UID pathname/abstract-socket `EACCES` failure. No TCP listener, port, Wi-Fi, or Internet permission is involved.
- The legacy abstract-socket probe has no filesystem permission checks and may still be restricted by Android SELinux policy. Do not migrate the active Binder FD session back to a cross-UID abstract-socket connect unless target-device SELinux and caller authentication have been proven.
- The token is supplied to the native process via `TERMUX_XWAYLAND_TOKEN_HEX`; the server never prints it. The workflow prints the token in the Termux terminal solely for manual entry into the Android app.
- Token bytes are compared using a constant-time equality loop after fixed-length validation. Failed handshakes are rejected.
- The environment-variable mechanism is development plumbing, not a release-ready secret broker. A future Termux launcher should provide scoped, short-lived credentials and a session-specific socket name. Production transport also needs connection-rate limits, robust lifecycle management and a tested pairing flow.
- Android Binder caller validation is performed by checking the calling UID and installed package. The service transfers only a connected FD after authorization. Do not silently fall back to a public TCP listener if Binder is unavailable.
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
- `FRAME`: width (`u16`), height (`u16`), pixel format (`u8`, `1` = RGBA8888), three reserved zero bytes, then `width × height × 4` bytes. The legacy abstract-socket probe sends one synthetic 320×180 pattern. The Binder path can send actual X11/XWayland captures as 640×360 RGBA at 2 FPS when ffmpeg is available; the Android service validates metadata and exact payload length before rendering. Native Wayland capture is not implemented. The 16 MiB envelope cap means larger frames must be tiled or compressed rather than bypassing the limit.
- `RESIZE`: width (`u16`) and height (`u16`); reject zero dimensions and values beyond implementation limits.
- `CLIPBOARD`: UTF-8 bytes, maximum 1 MiB, only after explicit session authentication. Never automatically execute clipboard contents.
- `PING`, `PONG`, `CLOSE`: empty payloads in version 1.
- `INPUT`: define explicit event types, coordinate bounds, pointer/button state, key representation and input-rate limits before enabling.

## Testing and state

Rust tests cover framing and rejection of malformed headers, oversized payloads, token validation and abstract socket address construction. The active Binder client validates the TXWB WELCOME payload, then sends PING and requires an empty PONG; the host Binder service validates the caller package/UID before handing over the FD. Frame capture remains optional and depends on `DISPLAY` and `ffmpeg`. “Session connected” means the Binder FD protocol round trip works; it does not mean a complete desktop is connected.

On Android, the Rust binary now initializes `android_logger` so native `log::info!`/`warn!` messages appear in Logcat under the `TermuxXWayland` tag; desktop builds continue to use `env_logger`. Java Binder lifecycle/session transitions are also logged under `TermuxXWaylandClient`. Neither logger prints session tokens or frame contents. CI compilation cannot prove device-level service binding or real rendering; verify on the Android 10 target with `adb logcat -s TermuxXWayland TermuxXWaylandClient`. A production client must clear state on close, timeout, app lifecycle transitions and reconnect failures.
