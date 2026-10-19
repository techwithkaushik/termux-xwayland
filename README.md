# Termux XWayland

A standalone Android display host for graphical sessions launched from Termux. The intended goal is to let users start graphical sessions in Termux and view them in this app, including XFCE through XWayland and native Wayland applications.

> **Status: Phase 1 scaffold — not a working desktop viewer yet.** The current Android app is a UI shell and the Rust binary is a placeholder. It does not yet implement a Wayland compositor, frame transport, input bridge, XWayland integration, or XFCE/LXQt session display. Do not run xfce4-session expecting it to appear in this prototype.

## Design

- Android host: Kotlin and Android Views; SurfaceView frame presentation is a next milestone.
- Termux-side compositor: Rust; Smithay is a candidate after ARM32/Bionic compatibility validation.
- Display transport: authenticated, per-session local bridge. No unauthenticated listener.
- Legacy apps: XWayland after the native Wayland path is stable.
- Desktop environments: XFCE first, LXQt second. Users manage their packages from Termux.
- Architecture targets: armeabi-v7a and arm64-v8a.
- Android 10 compatibility is a first-class test requirement.

## Build the Android shell

Open the repository in Android Studio, or use Gradle 8.9 with JDK 17:

    gradle :android-app:assembleDebug

The build currently validates only the Android UI shell, not a functioning compositor or desktop session.

## Reference projects

- [Termux:X11](https://github.com/termux/termux-x11) — Android display-host patterns and Termux integration.
- [termux-wayland](https://github.com/J-x-Z/termux-wayland) — experimental Termux-hosted Wayland compositor and Android bridge.

This repository is a separate implementation. Before copying code from a reference, preserve applicable license notices and review license compatibility. The repository is GPL-3.0-only.

## Milestones

1. Buildable Android host and CI. **Current.**
2. Define and test authenticated bridge protocol; build a minimal Termux-side compositor.
3. Render a native Wayland test client in the Android display surface.
4. Input, resize, clipboard and lifecycle.
5. XWayland integration and first X11 client.
6. XFCE session support.
7. LXQt session support.
8. ARM32/ARM64 device matrix, performance, recovery and release packaging.

## Safety and limitations

- The Android app must not claim a desktop is connected until a real bridge handshake succeeds.
- The bridge must bind only to loopback and use a fresh high-entropy per-session token.
- Never put long-lived pairing secrets in exported intents or logs.
- Do not assume GPU acceleration, DMA-BUF, cross-UID loopback connectivity, or ARM32 dependency availability; test each explicitly.
