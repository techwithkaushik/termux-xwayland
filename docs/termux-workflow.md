# Termux workflow

## Current milestone

The Termux DEB contains the Java app_process client and manages Weston/XWayland. The client requests native Weston output capture and forwards RGBA frames over the authenticated Binder-transferred FD. Actual frame delivery still depends on the installed Weston capture protocol and its authorization policy.

The stream is capped at 1920×1080 worth of pixels per frame while preserving the display aspect ratio. This prevents a phone-sized output (for example 1080×2400) from creating a roughly 10 MB packet per frame; the higher-detail pixel budget can use about 8 MB per frame. The Android renderer keeps the newest frame rather than building a stale-frame queue. Pointer movement prefers a persistent native XTest helper; when its optional development dependencies are unavailable, the xdotool fallback remains enabled.

Termux:X11 is the reference for the performance target: it keeps the X server and native rendering pipeline in the same app process and uses native EGL/GLES presentation, rather than transporting a complete RGBA desktop image for every frame. The current bridge is still a copied-frame path, not zero-copy. Measure frame FPS and pointer latency on the target Android 10 device before claiming real-time performance.

## Optional native input helper

The DEB ships the source and build recipe. Install the optional toolchain in Termux before starting the session:

```bash
pkg install clang pkg-config libx11 libxtst
termux-xwayland stop
termux-xwayland start
```

At startup the CLI attempts to build termux-xwayland-input. It keeps an XTest connection open and receives bounded pointer commands through stdin. If compilation or XTest initialization fails, the client logs the reason and falls back to xdotool.

FRAME_SENT entries in the Termux session log include the measured interval FPS, stream dimensions, packet size, capture read time and bridge write time. Use these to distinguish a slow capture source from a transport or rendering bottleneck.
## Build artifacts

CI publishes:
- termux-xwayland-debug-apk: Android display-host APK.
- termux-xwayland-binder-client-dex: DEX for the Termux-side app_process client.
- termux-xwayland-bridge-deb: Termux CLI and socket client DEX.

## Install and run

Install the DEB inside Termux using dpkg -i. Install the Weston/XWayland runtime and the optional native-helper build dependencies:

```bash
pkg install weston socat xorg-xwayland xdotool xterm clang pkg-config libx11 libxtst wayland
termux-xwayland start
termux-xwayland status
```

termux-xwayland start starts Weston/XWayland, opens the Android host, starts the Binder-FD client and waits for an authenticated PING/PONG heartbeat. The host remains READY/idle: it does not auto-launch a desktop environment or GUI application. Later run `termux-xwayland app xterm` for one app, or `termux-xwayland desktop startxfce4` for an explicit desktop session. The native Weston capture helper is built on-device when the Weston capture protocol and development dependencies are available; the compositor must still authorize capture. `termux-xwayland app COMMAND` or `termux-xwayland x11 COMMAND` launches an X11 client; `termux-xwayland desktop COMMAND` explicitly launches a desktop session. No desktop starts automatically. The x11 command uses the display reported by Weston. termux-xwayland status reports the Binder heartbeat and frame-capture status separately. termux-xwayland stop closes the client and stops the monitor and Weston.

## Security and limitations

- The socket session ID is generated freshly for each launch and is not printed in logs.
- The host verifies the kernel-reported peer UID against the installed Termux package UID.
- No TCP frame/input fallback, root, SELinux weakening, RUN_COMMAND or second APK is required.
- Cross-UID socket access can still be blocked by Android/MIUI SELinux policy; check the client log on the target phone.
- Native Wayland capture requires compositor authorization; helper compilation does not guarantee captured pixels.
- Pointer movement uses a persistent XTest helper when available; keyboard/text and some button paths still use xdotool.
- Resize and clipboard routing remain incomplete.
