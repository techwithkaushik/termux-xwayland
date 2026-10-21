# Termux workflow

## Current milestone

The Termux DEB contains a Java direct-socket client compiled to DEX in CI. It connects to the existing Android host over a session-specific abstract AF_UNIX socket, validates TXWB v1 framing, and maintains a PING/PONG heartbeat. When XWayland and ffmpeg are installed, the client can capture a 640×360 X11/XWayland image at 2 FPS and send RGBA FRAME messages. The Android host validates and displays received frames. Target-device visual verification is still pending; native Wayland capture and input forwarding remain incomplete.

The Binder service has been removed from the runtime path. The retired ipc-companion APK is not part of the supported install flow. Cross-UID abstract sockets previously failed with EACCES on the target Android 10 device, so the new direction must be verified on-device.

## Build artifacts

CI publishes:
- termux-xwayland-debug-apk: Android display-host APK.
- termux-xwayland-socket-client-dex: DEX for the Termux-side app_process client.
- termux-xwayland-bridge-deb: Termux CLI and socket client DEX.

## Install and run

Install the DEB inside Termux using dpkg -i. For optional XWayland capture, install the required Termux packages:

```bash
pkg install weston socat xorg-server ffmpeg xterm
termux-xwayland start
termux-xwayland status
```

termux-xwayland start starts Weston/XWayland, opens the Android host, starts the direct socket client and waits for authenticated PING/PONG. The host remains READY/idle: it does not auto-launch a desktop environment or GUI application. Later run `termux-xwayland app xterm` for one app, or `termux-xwayland desktop startxfce4` for an explicit desktop session. If XWayland and ffmpeg are available, it also starts frame capture. `termux-xwayland app COMMAND` or `termux-xwayland x11 COMMAND` launches an X11 client; `termux-xwayland desktop COMMAND` explicitly launches a desktop session. No desktop starts automatically. The x11 command using the display reported by Weston. termux-xwayland status reports the socket heartbeat and recent frame capture separately. termux-xwayland stop closes the client and stops the monitor and Weston.

## Security and limitations

- The socket session ID is generated freshly for each launch and is not printed in logs.
- The host verifies the kernel-reported peer UID against the installed Termux package UID.
- No TCP frame/input fallback, root, SELinux weakening, RUN_COMMAND or second APK is required.
- Cross-UID socket access can still be blocked by Android/MIUI SELinux policy; check the client log on the target phone.
- Native Wayland capture, touch/keyboard input forwarding and resize/clipboard routing are not implemented.
