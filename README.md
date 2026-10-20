# Termux XWayland

Termux XWayland pairs the existing Android display-host APK with a Termux-installed CLI/DEB. The supported flow does **not** require a second companion APK. The goal is a Termux:X11-like Android display experience built around **Weston/Wayland + XWayland**, not a standalone Xorg/X-server stack.

> **Current milestone:** the runtime bridge uses Termux-authorized Binder IPC and a socket file descriptor transferred between the Termux client and Android host. It does not require a TCP listener, a separate companion APK, root, or SELinux changes. CI and on-device verification are still required.

## Current implementation

- **Android host:** Kotlin UI receives the Termux-authorized Binder session, validates the caller and TXWB framing, and renders received RGBA frames.
- **Termux package:** `termux-xwayland` starts Weston/XWayland and launches the client from the existing Termux installation.
- **Termux client:** `termux-package/src/BridgeClient.java` is compiled to DEX in CI and packaged in the Termux DEB. It opens a Binder-authorized file-descriptor session and can send 640×360 RGBA frames at 2 FPS when ffmpeg and XWayland are available.
- **Packaging:** the Android host remains the only APK; the Termux-side client stays inside the Termux DEB.
- **Transport security:** no production TCP listener is used. Binder caller UID checks and the session FD handoff are required.
- **Protocol limits:** TXWB v1 uses a 12-byte header and a 16 MiB maximum payload.
- **Remaining:** on-device verification of loopback connection and actual frames, native Wayland capture/surface integration, Android touch/keyboard forwarding, resize/clipboard routing and reconnect handling.

## Build and run

With JDK 17, Gradle 8.9 and the Android SDK:

```bash
gradle :android-app:testDebugUnitTest :android-app:assembleDebug
bash tools/build-termux-binder-client.sh dist/termux-binder-client
install -D -m 0644 dist/termux-socket-client/bridge-client.dex \
  termux-package/data/data/com.termux/files/usr/share/termux-xwayland/bridge-client.dex
bash tools/test-termux-package.sh
bash termux-package/build-deb.sh dist
```

CI stages the DEX automatically and publishes the display-host APK, client DEX and Termux DEB. In Termux:

```bash
pkg install weston socat xorg-xwayland ffmpeg xterm
termux-xwayland start
termux-xwayland status
```

The optional X11 frame path requires Weston with XWayland support, ffmpeg and an X11 client. Inspect logs with `termux-xwayland logs` if the Binder session or frame capture fails.

## Verification boundary

CI compilation and package tests do not prove that the target phone displays a real desktop. Native Wayland surface capture and Android touch/keyboard forwarding remain separate milestones and must be verified on Android 10 / MIUI.
