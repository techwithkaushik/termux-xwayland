# Termux XWayland

Termux XWayland pairs the existing Android display-host APK with a Termux-installed CLI/DEB. The supported flow does **not** require a second companion APK. The goal is a Termux:X11-like Android display experience built around **Weston/Wayland + XWayland**, not a standalone Xorg/X-server stack.

> **Current milestone:** the runtime bridge uses Termux-authorized Binder IPC and a socket file descriptor transferred between the Termux client and Android host. It does not require a TCP listener, a separate companion APK, root, or SELinux changes. CI and on-device verification are still required.

## Current implementation

- **Android host:** Kotlin UI receives the Termux-authorized Binder session, validates the caller and TXWB framing, and renders received RGBA frames.
- **Termux package:** `termux-xwayland` starts Weston/XWayland and launches the client from the existing Termux installation.
- **Termux client:** `termux-package/src/BridgeClient.java` is compiled to DEX in CI and packaged in the Termux DEB. It opens a Binder-authorized file-descriptor session. Frame forwarding is wired to a native `termux-xwayland-capture` helper, but that helper is not implemented or packaged yet; therefore real desktop frames are not currently sent.
- **Packaging:** the Android host remains the only APK; the Termux-side client stays inside the Termux DEB.
- **Transport security:** no production TCP listener is used. Binder caller UID checks and the session FD handoff are required.
- **Protocol limits:** TXWB v1 uses a 12-byte header and a 16 MiB maximum payload.
- **Capture blocker:** Weston’s `weston_capture_v1` protocol is privileged and capture requests are denied unless a compositor-side authority explicitly authorizes them. A Wayland client alone cannot safely enable this. The native capture helper and a narrowly scoped authorization mechanism still need implementation.
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
```

CI stages the DEX automatically and publishes the display-host APK, client DEX and Termux DEB. In Termux:

```bash
pkg install weston socat xorg-xwayland xdotool xterm
termux-xwayland start
termux-xwayland status
```

Weston and XWayland can start independently of frame streaming. Do not install FFmpeg for frame capture: the bridge intentionally has no FFmpeg fallback. `termux-xwayland status` and `termux-xwayland logs` help diagnose server and Binder-session state; they do not prove frame capture works.

## Verification boundary

CI compilation and package tests do not prove that the target phone displays a real desktop. Native Wayland surface capture and Android touch/keyboard forwarding remain separate milestones and must be verified on Android 10 / MIUI.
