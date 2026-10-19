# Termux XWayland

A standalone Android display host for graphical sessions launched from Termux. The intended goal is to let users start graphical sessions in Termux and view them in this app, including XFCE through XWayland and native Wayland applications.

> **Status: authenticated cross-UID IPC smoke test with UID reporting, PING/PONG and synthetic frame rendering; no real desktop rendering yet.** The Rust binary now listens on an abstract AF_UNIX stream socket (a session-specific abstract socket derived from the first 8 token bytes), authenticates a per-session token, reports the Termux process UID, and sends one synthetic RGBA test frame. The Android app connects with `LocalSocket`, compares the Termux UID with its own UID, validates and renders the frame, then verifies bidirectional communication with a PING/PONG round trip. This is not real desktop output; Wayland compositor integration, input forwarding, XWayland and XFCE/LXQt display are still pending. Do not run `xfce4-session` expecting it to appear here.

## Current implementation

- Android host: Kotlin and Android Views, with a socket client and bounded protocol decoder.
- Native endpoint: Rust single-client abstract AF_UNIX stream socket; no TCP listener or localhost port.
- Session handshake: fresh 256-bit token supplied through `TERMUX_XWAYLAND_TOKEN_HEX`; token values are never printed by the server.
- Protocol: versioned 12-byte envelope, 16 MiB maximum payload, validation for magic/version/type/reserved bits and payload length.
- Keep scope honest: this is a synthetic-frame transport/rendering test, not a compositor and not a working desktop server.
- Architecture targets remain `armeabi-v7a` and `arm64-v8a`; Android 10 compatibility must be verified on-device.

## Verify real X11 capture (diagnostic only)

After starting an X11/XWayland session in Termux, run `pkg install ffmpeg` and then `tools/capture-x11-frame.sh "$HOME/x11-check.png" 320x180`. This captures one real frame to a local PNG and helps isolate display-capture problems. It is **not yet wired to the Android bridge**; the app still receives a synthetic frame. See [docs/x11-capture-probe.md](docs/x11-capture-probe.md).

## Build and test

Android shell build (Gradle 8.9 and JDK 17):

    gradle :android-app:assembleDebug

Native protocol/server checks from `native/compositor`:

    cargo fmt --check
    cargo test
    cargo check
    cargo build --release

See [docs/termux-workflow.md](docs/termux-workflow.md) for building and starting the loopback smoke-test server, and for entering its session token in the Android app. Use the development branch `feat/phase1-android-host` until the PR is merged.

## Architecture and next milestones

1. Buildable Android host and CI. **Complete.**
2. Bounded bridge framing and authenticated abstract AF_UNIX handshake. **Implemented; cross-UID Android device testing is still required.**
3. Implement a real Wayland compositor and bounded frame transport; render frames in Android `SurfaceView`.
4. Add input, resize, clipboard and lifecycle handling with explicit limits.
5. Integrate XWayland and verify a known X11 test client.
6. Add XFCE, then optional LXQt session support.
7. Validate ARM32/ARM64, Android 10+, performance and release packaging.

## Security notes

- The prototype uses an abstract AF_UNIX socket and has no TCP listener. Do not add a public TCP/LAN fallback if Android SELinux blocks cross-UID access.
- Generate a new high-entropy token per session. Never place secrets in exported intents, command-line arguments, or logs.
- The current prototype reads the token from a per-process environment variable; this is suitable only as temporary development plumbing and needs a safer session broker before release.
- Do not assume GPU acceleration, DMA-BUF, cross-UID loopback connectivity, or ARM32 dependency availability; test each explicitly.

## Reference projects

- [Termux:X11](https://github.com/termux/termux-x11) — Android display-host patterns and Termux integration.
- [termux-wayland](https://github.com/J-x-Z/termux-wayland) — experimental Termux-hosted Wayland compositor and Android bridge.

This repository is a separate implementation. Before copying code from a reference, preserve applicable license notices and review license compatibility. The repository is GPL-3.0-only.
