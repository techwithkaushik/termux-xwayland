# Termux XWayland

A standalone Android display host for graphical sessions launched from Termux. The intended goal is to let users start graphical sessions in Termux and view them in this app, including XFCE through XWayland and native Wayland applications.

> **Status: early prototype — not a working desktop viewer yet.** The Android app is a UI shell and the Rust executable is still a scaffold. Versioned bridge framing and its Rust unit tests are now present, but there is no running compositor, authenticated listener, frame transport, input bridge, XWayland integration, or XFCE/LXQt display yet. Do not run `xfce4-session` expecting it to appear in this prototype.

## Design

- Android host: Kotlin and Android Views; SurfaceView frame presentation is a next milestone.
- Termux-side compositor: Rust; Smithay is a candidate after ARM32/Bionic dependency validation.
- Display transport: versioned messages with a bounded 12-byte envelope; authentication and transport remain to be implemented.
- Legacy apps: XWayland after the native Wayland path is stable.
- Desktop environments: XFCE first, LXQt second. Users manage their packages from Termux.
- Architecture targets: `armeabi-v7a` and `arm64-v8a`.
- Android 10 compatibility is a first-class test requirement.

## Build the Android shell

Open the repository in Android Studio, or use Gradle 8.9 with JDK 17:

    gradle :android-app:assembleDebug

Run the native protocol checks from `native/compositor`:

    cargo fmt --check
    cargo test
    cargo check

The Android build validates only the UI shell, not a functioning compositor or desktop session.

## Bridge protocol

See [docs/bridge-protocol.md](docs/bridge-protocol.md) for message framing, payload limits, authentication requirements and planned message types. The current Rust module implements only generic framing. It does **not** open a socket or authenticate a peer.

## Reference projects

- [Termux:X11](https://github.com/termux/termux-x11) — Android display-host patterns and Termux integration.
- [termux-wayland](https://github.com/J-x-Z/termux-wayland) — experimental Termux-hosted Wayland compositor and Android bridge.

This repository is a separate implementation. Before copying code from a reference, preserve applicable license notices and review license compatibility. The repository is GPL-3.0-only.

## Milestones

1. Buildable Android host and CI. **Complete for the current scaffold.**
2. Define and test bounded bridge framing and authenticated protocol. **Framing/tests implemented; authentication/transport remain.**
3. Build a minimal Termux-side compositor and render a native Wayland test client in Android.
4. Input, resize, clipboard and lifecycle.
5. XWayland integration and first X11 client.
6. XFCE session support.
7. LXQt session support.
8. ARM32/ARM64 device matrix, performance, recovery and release packaging.

## Safety and limitations

- The Android app must not claim a desktop is connected until a real authenticated bridge handshake succeeds.
- A future listener must bind only to loopback and use a fresh high-entropy per-session token.
- Never put long-lived pairing secrets in exported intents or logs.
- Do not assume GPU acceleration, DMA-BUF, cross-UID loopback connectivity, or ARM32 dependency availability; test each explicitly.
