# Termux XWayland

Termux XWayland pairs the existing Android display-host APK with a Termux-installed CLI/DEB. The supported flow does **not** require a second companion APK. The goal is a Termux:X11-like Android display experience built around **Weston/Wayland + XWayland**, not a standalone Xorg/X-server stack.

> **Current integration mode:** the launcher temporarily starts Weston with `--debug` to unblock privileged output-capture testing. This is intentionally a broad, development-only authorization mode; do not use it for multi-user/public sessions. Remove it after the host is verified and a narrow compositor-side authority is implemented. Binder IPC still uses a transferred socket FD and requires no companion APK, root, or SELinux changes.

## Current implementation

- **Android host:** Kotlin UI receives the Termux-authorized Binder session, validates the caller and TXWB framing, and renders received RGBA frames.
- **Lifecycle:** `termux-xwayland start` starts host services and opens the Android host in a ready/idle state. It does not launch a desktop environment or GUI application. Start an app later with `termux-xwayland app xterm`, or explicitly start a desktop session with `termux-xwayland desktop startxfce4`.
- **Termux package:** `termux-xwayland` starts Weston/XWayland and launches the client from the existing Termux installation.
- **Termux client:** `termux-package/src/BridgeClient.java` is compiled to DEX in CI and packaged in the Termux DEB. It opens a Binder-authorized file-descriptor session. The native Weston capture helper and a persistent XTest pointer helper are built on-device when their development dependencies are available. Capture authorization remains a separate requirement; a successful helper build alone does not prove that live frames are flowing.
- **Packaging:** the Android host remains the only APK; the Termux-side client stays inside the Termux DEB.
- **Transport security:** no production TCP listener is used. Binder caller UID checks and the session FD handoff are required.
- **Protocol limits:** TXWB v1 uses a 12-byte header and a 16 MiB maximum payload.
- **Capture blocker:** Native Wayland capture uses the native `termux-xwayland-capture` helper. Weston 16’s headless backend defaults to the no-op renderer unless explicitly configured. The launcher now forces `--use-pixman` so the output has real pixels and renderer capture tasks can be serviced. The `weston_capture_v1` protocol is also privileged and requires compositor authorization. A Wayland client alone cannot safely enable this. The native capture helper is now included as source plus an on-device build recipe; the current debug mode is temporary and broad, and must be replaced with narrowly scoped compositor authorization before production.
- **Latency work:** the stream is capped at 640×360 worth of RGBA pixels while preserving the desktop aspect ratio; input coordinates still use the full XWayland dimensions. Pointer movement prefers one persistent native XTest process instead of spawning `xdotool` for each move. EGL swap interval is set to zero where supported to avoid an extra blocking vsync wait.
- **Remaining:** on-device verification of actual capture FPS, end-to-end frame latency, pointer latency, authorized native Wayland capture, keyboard routing, resize/clipboard routing and reconnect handling. This is not yet a zero-copy path; Termux:X11's in-process X server/render pipeline remains the reference architecture.

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
pkg install weston socat xorg-xwayland xdotool xterm clang pkg-config libx11 libxtst wayland
termux-xwayland start
termux-xwayland status
# Later, when desired:
termux-xwayland app xterm
# Or launch a desktop environment explicitly:
termux-xwayland desktop startxfce4
```

Weston is started with the Pixman software renderer (`--use-pixman`) because the headless backend's default no-op renderer cannot produce capturable desktop pixels. This uses CPU rendering. The stream deliberately caps RGBA transport to 230,400 pixels per frame so a 1080×2400 output does not become a 10 MB IPC packet; the Android surface upscales that preview. For lower pointer latency, the optional `termux-xwayland-input` helper keeps XTest connected and receives pointer commands over stdin rather than launching a process per event. Install `clang pkg-config libx11 libxtst` to enable that helper. Weston and XWayland can still start independently of frame streaming. Do not install FFmpeg for frame capture: the bridge intentionally has no FFmpeg fallback. `termux-xwayland status` and `termux-xwayland logs` help diagnose server and Binder-session state; `bash tools/verify-live-capture.sh` checks required device components. None of these alone proves frame capture works.

## Verification boundary

CI compilation and package tests do not prove that the target phone displays a real desktop. Native Wayland surface capture and Android touch/keyboard forwarding remain separate milestones and must be verified on Android 10 / MIUI.
