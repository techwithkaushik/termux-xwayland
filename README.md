# Termux XWayland

Termux XWayland pairs the existing Android display-host APK with a Termux-installed CLI/DEB. The supported flow does **not** require a second companion APK.

> **Current milestone:** the device confirmed that cross-UID abstract AF_UNIX connections fail with `Permission denied` on this Android/MIUI build. The runtime transport is therefore being moved to **authenticated TCP bound exclusively to 127.0.0.1:38473**. It is loopback-only (not LAN/public), uses the per-session random ID in the TXWB HELLO handshake, and does not require a separate companion APK. Device verification is still required after installing the new APK and DEB.

## Current implementation

- **Android host:** Kotlin UI listens on `127.0.0.1:38473` only, validates the session HELLO, bounds incoming TXWB frames and renders RGBA frames.
- **Termux package:** `termux-xwayland` starts Weston/XWayland and launches the client from the existing Termux installation.
- **Termux client:** `termux-package/src/BridgeClient.java` is compiled to DEX in CI and packaged in the Termux DEB. It connects to loopback TCP, authenticates the session, and can send 640×360 RGBA frames at 2 FPS when ffmpeg/XWayland are available.
- **No Binder service or separate companion APK:** the Android host remains the only APK; the Termux-side client stays inside the Termux DEB.
- **Network exposure:** the frame bridge binds only to IPv4 loopback `127.0.0.1`; it must never bind to `0.0.0.0` or a LAN address. Session authentication remains mandatory.
- **Protocol limits:** TXWB v1 uses a 12-byte header and a 16 MiB maximum payload.
- **Remaining:** on-device verification of loopback connection and actual frames, native Wayland capture/surface integration, Android touch/keyboard forwarding, resize/clipboard routing and reconnect handling.

## Build and run

With JDK 17, Gradle 8.9 and the Android SDK:

```bash
gradle :android-app:testDebugUnitTest :android-app:assembleDebug
bash tools/build-termux-binder-client.sh dist/termux-socket-client
install -D -m 0644 dist/termux-socket-client/bridge-client.dex \
  termux-package/data/data/com.termux/files/usr/share/termux-xwayland/bridge-client.dex
bash tools/test-termux-package.sh
bash termux-package/build-deb.sh dist
```

CI stages the DEX automatically and publishes the display-host APK, client DEX and Termux DEB. In Termux:

```bash
pkg install weston socat xorg-server ffmpeg xterm
termux-xwayland start
termux-xwayland status
```

The frame path requires XWayland, ffmpeg and an X11 client. Inspect `$PREFIX/tmp/termux-xwayland-$(id -u)/binder-session.log` if the authenticated loopback connection fails. The bridge binds only to `127.0.0.1:38473`.

## Verification boundary

CI compilation and package tests do not prove the target phone accepts the abstract socket or displays a real frame. Do not claim production desktop support until this is verified on Android 10 / MIUI.
