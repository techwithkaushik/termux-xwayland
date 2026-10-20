# Architecture

## Goal

Run graphical sessions from Termux and show them in the standalone Android app. Termux remains the Linux userspace and session controller; the Android app is the display/input endpoint, not a bundled Linux distribution.

## Components

1. **Android host:** Kotlin Activity plus a `SurfaceView` or equivalent surface renderer. Validate frame dimensions, format and payload bounds before updating the surface.
2. **Wayland compositor:** native Rust compositor (Smithay is a candidate, not yet integrated). Own Wayland surfaces, damage tracking, composition and output lifecycle.
3. **Frame export:** export the compositor's rendered output buffer at the compositor boundary. Prefer a supported shared-buffer path when available; otherwise use bounded RGBA frames over the authenticated bridge as the first correct implementation. Do not capture the screen by running an external desktop recorder.
4. **Three-part integration:** the existing Termux app provides the Linux userspace; a Termux-side companion package/CLI starts and manages sessions; the Termux-XWayland Android host owns display and input. The companion contract is in [termux-companion-contract.md](termux-companion-contract.md). Control-plane launch and persistent frame/input transport are separate responsibilities. Direct abstract AF_UNIX connections across app UIDs can be denied by Android SELinux; test on Android 10. No TCP/localhost fallback. **Bootstrap update (2026-10-20):** the client now follows the Termux:X11 pattern: a package-targeted broadcast passes a caller-UID-checked Binder endpoint to the host, which calls back to transfer the socket FD. It no longer calls `Context.bindService()` from `app_process`; see [ADR 0002](adr/0002-binder-app-process-limitation.md). Device validation is still required.
5. **Input:** Android touch/key events mapped into compositor input events with coordinate scaling, focus and key-state cleanup.
6. **XWayland:** compatibility layer for X11 applications, including the eventual XFCE path. Native Wayland test client comes first.

## Rendering decision

The compositor is the source of truth for desktop pixels. Frames should be obtained from its output/render target, not by launching FFmpeg to repeatedly capture `$DISPLAY`. FFmpeg is optional diagnostic tooling only; it is not a runtime dependency and is not part of the live rendering pipeline.

The initial frame transport may copy pixels to a bounded protocol message. Optimize only after correctness is proven: damage rectangles, frame pacing, buffer reuse and shared memory can follow. Do not claim zero-copy or GPU acceleration without device measurements.

## Milestones

1. Android host and bounded protocol smoke test. **Implemented; still only a synthetic frame.**
2. Native compositor dependency and Android 10/ARM32 build feasibility. **Not yet verified.**
3. One native Wayland surface rendered by the compositor and exported through the bridge.
4. Android displays changing compositor frames with lifecycle-safe start/stop.
5. Touch/keyboard input and resize handling.
6. XWayland integration and a known X11 test client.
7. XFCE session, ARM32/ARM64 validation, memory/performance checks and release packaging.

## Constraints and security

- Android 10 is the minimum compatibility target.
- Test `armeabi-v7a` and `arm64-v8a` independently, including native dependencies.
- Android 9+ per-app SELinux isolation can deny abstract AF_UNIX `connect()` across app UIDs even though abstract sockets have no filesystem permissions. `Permission denied` is not fixable with chmod.
- Do not assume shared UID, signature permissions, or private Termux:X11 interfaces are available across arbitrary APK signing keys. Verify the actual authorization and FD-handoff design on the target Android 10 device before implementing the production endpoint.
- Do not claim a live session until a real compositor surface is displayed and authenticated input/frame transport is verified.
- GPU acceleration, DMA-BUF, zero-copy transport, zero latency and Unicode IME are not assumed; measure and validate them separately.
