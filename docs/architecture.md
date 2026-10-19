# Architecture

## Goal

Run graphical sessions from Termux and show them in the standalone Android app. Users install and start XFCE/LXQt packages in Termux or a compatible PRoot environment.

## Components

1. Android host: Kotlin Activity and a future SurfaceView renderer.
2. Termux compositor: Rust/Smithay candidate, subject to ARM32/Bionic dependency validation.
3. Bridge: authenticated per-session abstract AF_UNIX stream socket for frames and input; no TCP/localhost dependency.
4. Native Wayland path: first end-to-end milestone.
5. XWayland path: compatibility for X11 applications, required for a conventional XFCE session.
6. Session integration: Termux remains the control environment; the app is the display/input endpoint, not a bundled Linux distribution.

## Constraints

- Android 10 is the minimum compatibility test target.
- Test armeabi-v7a and arm64-v8a independently, including native dependencies.
- Do not assume cross-UID abstract Unix socket access works on every vendor ROM; verify Android SELinux behavior on-device.
- Do not claim a live session until an authenticated bridge handshake completes.
- GPU acceleration, DMA-BUF, zero-copy transport, zero latency and Unicode IME are not phase-one guarantees.
- Do not silently install Linux packages from the Android app.

## Implementation order

1. Buildable Android host and CI.
2. Authenticated bridge protocol and compositor smoke test.
3. Render a native Wayland test client in Android.
4. Input, resize, clipboard and lifecycle.
5. XWayland with a known X11 test application.
6. XFCE session, then optional LXQt session.
7. ARM32/ARM64 device validation and performance work.
