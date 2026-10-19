# Termux XWayland

A standalone Android display host for graphical sessions launched from Termux. The intended goal is to let users start graphical sessions in Termux and view them in this app, including XFCE through XWayland and native Wayland applications.

> **Status: authenticated bridge smoke test; no desktop rendering yet.** The Rust binary now listens on IPv4 loopback and performs a token-authenticated protocol handshake. The Android app has a connection form and reports success only after the handshake completes. There is still no Wayland compositor, frame stream, `SurfaceView` renderer, input bridge, XWayland integration, or XFCE/LXQt display. Do not run `xfce4-session` expecting it to appear here.

## Current implementation

- Android host: Kotlin and Android Views, with a socket client and bounded protocol decoder.
- Native endpoint: Rust single-client TCP listener bound to `127.0.0.1` only.
- Session handshake: fresh 256-bit token supplied through `TERMUX_XWAYLAND_TOKEN_HEX`; token values are never printed by the server.
- Protocol: versioned 12-byte envelope, 16 MiB maximum payload, validation for magic/version/type/reserved bits and payload length.
- Keep scope honest: this is a handshake test endpoint, not a compositor and not a remote desktop server.
- Architecture targets remain `armeabi-v7a` and `arm64-v8a`; Android 10 compatibility must be verified on-device.

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
2. Bounded bridge framing and authenticated loopback handshake. **Implemented; on-device loopback compatibility still needs testing.**
3. Implement a real Wayland compositor and bounded frame transport; render frames in Android `SurfaceView`.
4. Add input, resize, clipboard and lifecycle handling with explicit limits.
5. Integrate XWayland and verify a known X11 test client.
6. Add XFCE, then optional LXQt session support.
7. Validate ARM32/ARM64, Android 10+, performance and release packaging.

## Security notes

- The listener binds only to loopback; never expose it on `0.0.0.0` or a LAN interface.
- Generate a new high-entropy token per session. Never place secrets in exported intents, command-line arguments, or logs.
- The current prototype reads the token from a per-process environment variable; this is suitable only as temporary development plumbing and needs a safer session broker before release.
- Do not assume GPU acceleration, DMA-BUF, cross-UID loopback connectivity, or ARM32 dependency availability; test each explicitly.

## Reference projects

- [Termux:X11](https://github.com/termux/termux-x11) — Android display-host patterns and Termux integration.
- [termux-wayland](https://github.com/J-x-Z/termux-wayland) — experimental Termux-hosted Wayland compositor and Android bridge.

This repository is a separate implementation. Before copying code from a reference, preserve applicable license notices and review license compatibility. The repository is GPL-3.0-only.
