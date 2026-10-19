# Architecture

## Goal

Run graphical sessions from Termux and show them in the standalone Android app. Users install and start XFCE/LXQt packages in Termux or a compatible PRoot environment.

## Components

1. Android host: Kotlin Activity and a future SurfaceView renderer.
2. Termux compositor: Rust/Smithay candidate, subject to ARM32/Bionic dependency validation.
3. Bridge: authenticated per-session AF_UNIX stream socket for frames and input. Direct abstract AF_UNIX connections across app UIDs can be denied by Android SELinux (especially Android 9+); the standalone cross-UID mode is currently blocked on affected devices. Do not treat this transport as supported until verified on-device. No TCP/localhost dependency.
4. Native Wayland path: first end-to-end milestone.
5. XWayland path: compatibility for X11 applications, required for a conventional XFCE session.
6. Session integration: Termux remains the control environment; the app is the display/input endpoint, not a bundled Linux distribution.

## Constraints

- Android 10 is the minimum compatibility test target.
- Test armeabi-v7a and arm64-v8a independently, including native dependencies.
- Android 9+ per-app SELinux isolation can deny abstract AF_UNIX connect() across app UIDs even though abstract sockets have no filesystem permissions. `Permission denied` is not fixable with chmod.
- A shared-UID variant requires both APKs to declare the same shared UID and be signed with the same certificate; this is not compatible with arbitrary preinstalled Termux distributions/signing keys. Otherwise use an explicitly authorized Termux-mediated IPC design.
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
