# Binder and frame transport

The existing Android host APK exposes `BridgeSessionService`; the Termux DEB includes the Java `app_process` client. No second companion APK is built or installed.

## Security and session

- The service checks the actual Binder caller UID against Android's package-manager mapping for `com.termux`.
- Binder transfers a connected socket FD; no cross-UID AF_UNIX connect is required.
- No TCP listener carries frames or input. The loopback status monitor is separate control-plane polling only.
- TXWB v1 framing enforces known message kinds, reserved-bit validation and a 16 MiB payload ceiling.
- The client reports connected only after a valid PING/PONG and logs a timestamped heartbeat every five seconds. The CLI considers the session healthy only while that heartbeat is fresh.

## XWayland frame path

When `Xwayland` and `ffmpeg` are installed, the CLI starts Weston with a 640×360 headless output and XWayland enabled. The client starts an optional ffmpeg X11 capture at 2 FPS and sends bounded RGBA `FRAME` messages through the Binder-transferred FD. The Android service validates each frame with `RgbaFrameCodec` and hands decoded pixels to the existing activity for display.

If ffmpeg, XWayland, or a usable X11 display is unavailable, the Binder heartbeat remains available and frame capture reports waiting/unavailable rather than claiming desktop output. Launch an X11 app with `termux-xwayland x11 xterm` (or another installed app).

This frame path is X11/XWayland-only and still needs target-device visual verification. Native Wayland capture and input forwarding are not implemented.

## Remaining work

- Verify XWayland output dimensions and frame content on Android 10 / MIUI.
- Add bounded backpressure, capture-process restart and explicit stream shutdown handling.
- Implement native Wayland capture through a safe compositor API; do not enable the risky Weston debug capture protocol in production.
- Implement Android touch/keyboard forwarding and resize/clipboard routing after frame delivery is proven.

CI validates builds and package wiring, but cannot prove the target phone's XWayland display or actual visible frame content.
