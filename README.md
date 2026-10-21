# Termux XWayland

Termux XWayland pairs the existing Android display-host APK with a Termux-installed CLI/DEB. The supported flow does **not** require a second companion APK. The goal is a Termux:X11-like Android display experience built around **Weston/Wayland + XWayland**, not a standalone Xorg/X-server stack.

> **Current integration mode:** the launcher temporarily starts Weston with `--debug` to unblock privileged output-capture testing. This is intentionally a broad, development-only authorization mode; do not use it for multi-user/public sessions. Remove it after the host is verified and a narrow compositor-side authority is implemented. Binder IPC still uses a transferred socket FD and requires no companion APK, root, or SELinux changes.

## Current implementation

- **Android host:** Kotlin UI receives the Termux-authorized Binder session, validates the caller and TXWB framing, and renders received RGBA frames.
- **Termux package:** `termux-xwayland` starts Weston/XWayland and launches the client from the existing Termux installation.
- **Termux client:** `termux-package/src/BridgeClient.java` is compiled to DEX in CI and packaged in the Termux DEB. It opens a Binder-authorized file-descriptor session. Frame forwarding is wired to the native `termux-xwayland-capture` helper source, but the helper is deliberately not packaged until a matching Weston build grants narrowly scoped capture authorization; therefore real desktop frames are not yet guaranteed or claimed.
- **Packaging:** the Android host remains the only APK; the Termux-side client stays inside the Termux DEB.
- **Transport security:** no production TCP listener is used. Binder caller UID checks and the session FD handoff are required.
- **Protocol limits:** TXWB v1 uses a 12-byte header and a 16 MiB maximum payload.
- **Capture blocker:** Weston’s `weston_capture_v1` protocol is privileged and capture requests are denied unless a compositor-side authority explicitly authorizes them. A Wayland client alone cannot safely enable this. The native capture helper is now included as source plus an on-device build recipe; the current debug mode is temporary and broad, and must be replaced with narrowly scoped compositor authorization before production.
- **Remaining:** on-device verification of Binder session and actual frames, authorized native Wayland capture, Android touch/keyboard forwarding, resize/clipboard routing and reconnect handling.

## Build and run

With JDK 17, Gradle 8.9 and the Android SDK:

```bash
gradle :android-app:testDebugUnitTest :android-app:assembleDebug
bash tools/build-termux-binder-client.sh dist/termux-binder-client
install -D -m 0644 dist/termux-binder-client/bridge-client.dex \
  termux-package/data/data/com.termux/files/usr/share/termux-xwayland/bridge-client.dex
bash tools/test-termux-package.sh
bash termux-package/build-deb.sh dist
bash tools/verify-live-capture.sh
```

CI stages the DEX automatically and publishes the display-host APK, client DEX and Termux DEB. In Termux:

```bash
pkg install weston socat xorg-xwayland xdotool xterm
termux-xwayland start
termux-xwayland status
```

Weston and XWayland can start independently of frame streaming. Do not install FFmpeg for frame capture: the bridge intentionally has no FFmpeg fallback. `termux-xwayland status` and `termux-xwayland logs` help diagnose server and Binder-session state; `bash tools/verify-live-capture.sh` checks required device components. None of these alone proves frame capture works.

## Verification boundary

CI compilation and package tests do not prove that the target phone displays a real desktop. Native Wayland surface capture and Android touch/keyboard forwarding remain separate milestones and must be verified on Android 10 / MIUI.
