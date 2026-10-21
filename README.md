# Termux XWayland

A standalone Android display host for graphical sessions launched from Termux. The goal is to let users start graphical sessions in Termux and view them in this app, including X11 applications through XWayland and native Wayland applications.

> **Status: compositor bootstrap implemented; no real desktop rendering yet on Android.** The Termux CLI can start/stop Weston with its headless Pixman backend and request XWayland startup without automatically opening a desktop environment. The Android test-frame path now presents bounded RGBA payloads through native `ANativeWindow` rather than `ImageView`/`Bitmap`. This is a session-lifecycle milestone, not the final Android renderer: Weston currently renders headlessly, the Android app still has a synthetic RGBA test frame path, and the target Android 10 device rejected the prototype cross-UID abstract socket with EACCES. Real compositor-surface export, authorized persistent frame/input transport, GPU-backed Android presentation and visible X11/Wayland clients are not yet implemented. Do not run xfce4-session expecting it to appear in the Android app.

## Current implementation

- Android host: Kotlin, a `SurfaceView`, bounded frame validation, and a Rust JNI `ANativeWindow` presenter. Frames are copied directly into the Android native window without allocating a Java `Bitmap`.
- Native endpoint: Rust single-client abstract AF_UNIX stream socket prototype; no TCP listener or localhost port.
- Termux-side package: `termux-xwayland-bridge` is a DEB installed inside the existing Termux environment. It remains a Termux package/CLI; no separate companion APK is required. The CLI has start/status/stop lifecycle commands for a Weston headless session with XWayland requested, and opens the Android host. This does not yet establish an authorized persistent frame/input channel.
- Termux integration: flow is **Termux app → Termux-side companion package/CLI → Termux-XWayland Android host**. No dependency on Termux RUN_COMMAND or `allow-external-apps=true`. The CLI manages the local compositor process; authorized Android FD/stream transport is still not implemented.
- Session handshake: fresh 256-bit token supplied through TERMUX_XWAYLAND_TOKEN_HEX; token values are never printed by the server.
- Protocol: versioned 12-byte envelope, 16 MiB maximum payload, validation for magic/version/type/reserved bits and payload length.
- Keep scope honest: the CLI now launches a real Weston process in headless/Pixman mode, but the Android app currently receives only a synthetic frame in the current bridge prototype. This is not yet a working Android desktop renderer.
- Architecture targets remain armeabi-v7a and arm64-v8a; Android 10 compatibility must be verified on-device.

## Optional X11 capture diagnostic

tools/capture-x11-frame.sh is an optional, one-shot debugging helper only. FFmpeg is **not** part of the runtime rendering architecture. Live frames must come from the compositor's own output buffer; the current Android app still receives a synthetic frame. See docs/x11-capture-probe.md and docs/architecture.md.

## Build and test

Android shell build (Gradle 8.9 and JDK 17):

    gradle --no-daemon :android-app:testDebugUnitTest :android-app:assembleDebug

Native protocol/server checks from native/compositor:

    cargo fmt --check
    cargo test
    cargo check
    cargo build --release

See docs/termux-workflow.md for the diagnostic server workflow, docs/termux-companion-contract.md for the three-part integration contract, and docs/termux-deb-install.md for building/installing the initial Termux DEB package. Use feat/phase1-android-host until the PR is merged.

## Architecture and next milestones

1. Buildable Android host and CI. **Complete.**
2. Bounded bridge framing and protocol tests. **Implemented; cross-UID Android device connection fails with EACCES and is not a supported transport yet.**
3. Termux-only companion CLI with Weston session start/status/stop is in place. Authorized Termux-to-host IPC, descriptor handoff, and persistent full-duplex transport remain the blocking milestone; no companion APK is part of the supported installation.
4. Export a real Wayland client surface from the compositor to the Android host (current Weston bootstrap is headless and does not export frames).
5. Integrate upstream XWayland and verify a known X11 test client.
6. Add input, resize, clipboard and lifecycle handling.
7. Add XFCE, then optional LXQt session support.
8. Validate ARM32/ARM64, Android 10+, performance and release packaging.

See docs/xwayland-integration-plan.md for the detailed process topology, rootful-first plan and exit checks.

## Security notes

- The abstract AF_UNIX socket is diagnostic only; do not assume it works across Android UIDs.
- Generate a new high-entropy token per session. Never place secrets in exported intents, command-line arguments, or logs.
- A token cannot override Android IPC authorization; the connection must be permitted before authentication can happen.
- Historical Binder experiments only demonstrated host-to-test-service feasibility; they are not the production architecture and must not be treated as proof that the existing Termux app can connect.
- Do not add a public TCP/LAN or localhost fallback if Android SELinux blocks cross-UID access.
- Do not disable SELinux or require root.
- Do not assume GPU acceleration, DMA-BUF, zero-copy transport, or ARM32 dependency availability; test each explicitly.

## Reference projects

- [Termux:X11](https://github.com/termux/termux-x11) — Android display-host and Termux bootstrap/package integration patterns; it is an X server, not this project's Wayland compositor/XWayland stack.
- [termux-wayland](https://github.com/J-x-Z/termux-wayland) — experimental Termux-hosted Wayland compositor and Android bridge.
- [Wayland XWayland documentation](https://wayland.freedesktop.org/docs/book/Xwayland.html) — upstream XWayland/compositor integration model.

This repository is a separate implementation. Before copying code from a reference, preserve applicable license notices and review license compatibility. The repository is GPL-3.0-only.
