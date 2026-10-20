# Termux XWayland

Termux XWayland pairs the existing Android display-host APK with a Termux-installed CLI/DEB. The supported flow does **not** require a second companion APK.

> **Current milestone:** the previous Binder FD transport has been removed from the runtime path. The Android host now listens on a session-named abstract AF_UNIX socket; the Termux app_process client connects directly, authenticates the per-session ID, verifies the kernel-reported peer UID, and sends bounded TXWB v1 frames. This removes the failing bindService/MIUI hidden-context path. Cross-UID abstract sockets can still be denied by Android/MIUI SELinux policy, so the new transport must be verified on the target phone before calling the stream fixed.

## Current implementation

- **Android host:** Kotlin UI and a direct abstract AF_UNIX socket listener; verifies the kernel-reported Termux UID and validates incoming RGBA frames before display.
- **Termux package:** termux-xwayland starts Weston/XWayland, launches the socket client and reports heartbeat/frame-capture state separately.
- **Termux client:** termux-package/src/BridgeClient.java is compiled to DEX in CI and packaged in the Termux DEB. It uses optional ffmpeg X11 capture and sends 640×360 RGBA frames at 2 FPS over the direct socket.
- **No Binder service or separate companion APK:** the app does not call bindService; the unused Binder service has been removed.
- **No TCP frame/input fallback:** the loopback status monitor is control-plane polling only. Frames and input are not sent over TCP. No root, SELinux weakening, allow-external-apps=true or Termux RUN_COMMAND is required.
- **Protocol limits:** TXWB v1 uses a 12-byte header and a 16 MiB maximum payload.
- **Remaining:** target-device verification of cross-UID socket access, native Wayland surface capture, Android touch/keyboard forwarding, resize/clipboard routing and backpressure/reconnect handling.

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
pkg install weston socat xorg-server ffmpeg xterm
termux-xwayland start
termux-xwayland status
```

The frame path requires XWayland, ffmpeg and an X11 client. If Android/MIUI denies the cross-UID abstract socket, inspect $PREFIX/tmp/termux-xwayland-$(id -u)/binder-session.log; the log should now report SOCKET_CLIENT_ERROR, not a Binder bind failure.

## Verification boundary

CI compilation and package tests do not prove the target phone accepts the abstract socket or displays a real frame. Do not claim production desktop support until this is verified on Android 10 / MIUI.
