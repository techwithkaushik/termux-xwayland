# Termux XWayland

A standalone Android display host for graphical sessions launched from Termux. The intended goal is to let users start graphical sessions in Termux and view them in this app, including XFCE through XWayland and native Wayland applications.

> **Status: protocol/UI smoke test only; no real desktop rendering yet.** The Rust binary sends one synthetic RGBA test frame over an abstract AF_UNIX socket. The Android app validates and renders the frame and implements a PING/PONG round trip, but the target Android 10 device has rejected the cross-UID socket connection with EACCES before token authentication. Do not describe this cross-UID transport as working. Wayland compositor integration, input forwarding, XWayland and XFCE/LXQt display are still pending. Do not run xfce4-session expecting it to appear here.

## Current implementation

- Android host: Kotlin and Android Views, with a socket client and bounded protocol decoder.
- Native endpoint: Rust single-client abstract AF_UNIX stream socket prototype; no TCP listener or localhost port.
- Session handshake: fresh 256-bit token supplied through TERMUX_XWAYLAND_TOKEN_HEX; token values are never printed by the server.
- Protocol: versioned 12-byte envelope, 16 MiB maximum payload, validation for magic/version/type/reserved bits and payload length.
- Keep scope honest: this is a synthetic-frame transport/rendering test, not a compositor and not a working desktop server.
- Architecture targets remain armeabi-v7a and arm64-v8a; Android 10 compatibility must be verified on-device.

## Optional X11 capture diagnostic

tools/capture-x11-frame.sh is an optional, one-shot debugging helper only. FFmpeg is **not** part of the runtime rendering architecture. Live frames must come from the compositor's own output buffer; the current Android app still receives a synthetic frame. See docs/x11-capture-probe.md and docs/architecture.md.

## Build and test

Android shell build (Gradle 8.9 and JDK 17):

    gradle :android-app:assembleDebug

Native protocol/server checks from native/compositor:

    cargo fmt --check
    cargo test
    cargo check
    cargo build --release

See docs/termux-workflow.md for building and starting the diagnostic server. Use feat/phase1-android-host until the PR is merged.

## Architecture and next milestones

1. Buildable Android host and CI. **Complete.**
2. Bounded bridge framing and protocol tests. **Implemented; cross-UID Android device connection fails with EACCES and is not a supported transport yet.**
3. Select and verify an Android-10-compatible IPC transport.
4. Build a real Wayland compositor and render one real Wayland client surface in Android.
5. Integrate upstream XWayland and verify a known X11 test client.
6. Add input, resize, clipboard and lifecycle handling.
7. Add XFCE, then optional LXQt session support.
8. Validate ARM32/ARM64, Android 10+, performance and release packaging.

See docs/xwayland-integration-plan.md for the detailed process topology, rootful-first plan and exit checks.

## Security notes

- The abstract AF_UNIX socket is diagnostic only; do not assume it works across Android UIDs.
- Generate a new high-entropy token per session. Never place secrets in exported intents, command-line arguments, or logs.
- A token cannot override Android IPC authorization; the connection must be permitted before authentication can happen.
- Do not add a public TCP/LAN or localhost fallback if Android SELinux blocks cross-UID access.
- Do not disable SELinux or require root.
- Do not assume GPU acceleration, DMA-BUF, zero-copy transport, or ARM32 dependency availability; test each explicitly.

## Reference projects

- [Termux:X11](https://github.com/termux/termux-x11) — Android display-host and Termux bootstrap/package integration patterns; it is an X server, not this project's Wayland compositor/XWayland stack.
- [termux-wayland](https://github.com/J-x-Z/termux-wayland) — experimental Termux-hosted Wayland compositor and Android bridge.
- [Wayland XWayland documentation](https://wayland.freedesktop.org/docs/book/Xwayland.html) — upstream XWayland/compositor integration model.

This repository is a separate implementation. Before copying code from a reference, preserve applicable license notices and review license compatibility. The repository is GPL-3.0-only.
