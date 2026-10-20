# Termux workflow

## Current milestone

The Termux DEB contains a Java Binder client compiled to DEX in CI. It binds under the existing Termux UID, receives a connected FD, validates TXWB v1 frames and maintains a PING/PONG heartbeat. When XWayland and ffmpeg are installed, the client can also capture a 640×360 X11/XWayland image at 2 FPS and send RGBA `FRAME` messages over Binder. The Android service validates and displays received frames. This path requires target-device visual verification; native Wayland capture and input forwarding remain incomplete.

The old abstract AF_UNIX cross-UID connection failed with `EACCES` on the target Android 10 device. It remains diagnostic only. The retired `:ipc-companion` APK is not part of the supported install flow.

## Build artifacts

CI publishes:
- `termux-xwayland-debug-apk`: Android display-host APK.
- `termux-xwayland-binder-client-dex`: DEX for the Termux-side `app_process` client.
- `termux-xwayland-bridge-deb`: Termux CLI and Binder client DEX.

## Install and run

Install the DEB inside Termux using `dpkg -i`. For optional XWayland capture, install the required Termux packages:

```bash
pkg install weston socat xorg-server ffmpeg xterm
termux-xwayland start
termux-xwayland x11 xterm
termux-xwayland status
```

`termux-xwayland start` starts Weston, opens the host, starts the Binder client and waits for an authorized PING/PONG. If XWayland and ffmpeg are available, it also starts a frame-capture process. `termux-xwayland x11 COMMAND` launches an X11 client using `DISPLAY=:0`. `termux-xwayland status` reports the Binder heartbeat and recent frame capture separately. `termux-xwayland stop` closes the Binder session and stops the monitor and Weston.

## Security and limitations

- The Binder service authorizes the actual caller UID through Android's package manager and accepts only the installed `com.termux` package.
- The socket FD is transferred through Binder; no cross-UID AF_UNIX connect or TCP frame/input listener is used.
- The loopback status monitor is low-rate control-plane polling only.
- XWayland capture requires `xorg-server` and `ffmpeg`; without them the heartbeat remains usable and the frame stream reports waiting/unavailable.
- The frame path is X11/XWayland-only, 640×360 at 2 FPS. Native Wayland surfaces, touch/keyboard, resize, clipboard, backpressure and reconnect are still future work.
- CI cannot establish MIUI Binder policy, frame content or visual correctness on the target Android 10 phone.

Do not report a working desktop until a real XWayland frame is visible on the device and the stream survives stop/reconnect.
