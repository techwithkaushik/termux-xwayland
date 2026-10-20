# Termux XWayland

Termux XWayland pairs the existing Android display-host APK with a Termux-installed CLI/DEB. The supported flow does **not** require a second companion APK.

> **Current milestone:** authorized Binder session plus an optional XWayland frame path. The host checks the real Binder caller UID, receives TXWB v1 messages through a transferred socket FD, and only marks the session connected after PING/PONG. When XWayland and ffmpeg are available, the Termux client captures a 640×360 X11/XWayland image at 2 FPS and sends bounded RGBA `FRAME` messages over that same Binder FD. This frame path builds in CI but still needs target-device visual verification; native Wayland capture and input forwarding are not implemented.

## Current implementation

- **Android host:** Kotlin UI, in-app Binder session service, bounded frame validation and display of received XWayland frames.
- **Termux package:** `termux-xwayland` starts Weston, enables XWayland when installed, launches the Binder client and reports heartbeat/frame-capture state separately.
- **Binder client:** `termux-package/src/BridgeClient.java` is compiled to DEX in CI and packaged in the Termux DEB. It uses optional ffmpeg X11 capture and sends frames through the Binder-transferred FD.
- **No separate companion APK:** the retired `:ipc-companion` module is removed from Gradle settings, CI and the repository.
- **No TCP frame/input fallback:** the loopback status monitor is control-plane polling only. Frames and input are not sent over TCP. No root, SELinux weakening, `allow-external-apps=true` or Termux RUN_COMMAND is required.
- **Protocol limits:** TXWB v1 uses a 12-byte header and a 16 MiB maximum payload. The current raw frame is 640×360 RGBA at 2 FPS.
- **Remaining:** native Wayland surface capture, Android touch/keyboard forwarding, resize/clipboard routing, backpressure/reconnect handling and target-device verification.

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

CI stages the DEX automatically and publishes the display-host APK, Binder-client DEX and Termux DEB. In Termux, install the DEB and the optional X11 capture tools if needed:

```bash
pkg install weston socat xorg-server ffmpeg xterm
termux-xwayland start
termux-xwayland x11 xterm
termux-xwayland status
```

`xorg-server`, `ffmpeg` and `xterm` are optional for the capture path; without them the Binder heartbeat can still connect, but frame capture reports waiting/unavailable. `termux-xwayland x11 COMMAND` launches an X11 app with `DISPLAY=:0`.

## Security and verification boundary

- Cross-UID abstract AF_UNIX connect returned `EACCES` on the target Android 10 device and is not the production transport.
- Binder authorization checks the actual caller UID through Android's package manager.
- The connected FD is transferred through Binder. The client does not connect to a cross-UID abstract/filesystem socket.
- The X11 capture path is optional and limited to XWayland/X11 content; it is not native Wayland capture.
- CI compilation and package tests do not prove the target phone displays a real XWayland frame. Do not claim production desktop support until this is verified on Android 10 / MIUI.

See [docs/termux-workflow.md](docs/termux-workflow.md), [docs/binder-session-endpoint.md](docs/binder-session-endpoint.md), and [docs/bridge-protocol.md](docs/bridge-protocol.md).
