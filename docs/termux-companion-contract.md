# Termux-side integration contract

## Components

1. **Existing Termux app** owns the shell, packages and Linux userspace.
2. **Termux DEB/CLI** owns Weston/XWayland lifecycle and launches a Java client through Android app_process while retaining the real Termux UID.
3. **Existing Termux-XWayland Android host APK** listens on a session-specific abstract AF_UNIX socket and renders validated frames.

There is no separate companion APK and no Binder service in the production runtime path.

## Current implementation

- The launch intent carries a fresh 128-bit session ID; the socket name and HELLO handshake are tied to that session.
- The host verifies the kernel-reported peer UID against the installed com.termux package UID.
- The Termux-side Java helper is compiled against Android SDK stubs and converted to DEX in CI; the DEX is included in the Termux DEB.
- The client validates TXWB v1 framing, checks WELCOME, sends PING, verifies PONG and repeats until disconnect.
- The host validates bounded RGBA frames and displays them; ffmpeg can capture X11/XWayland output at 640×360 and 2 FPS.
- INPUT/RESIZE/CLIPBOARD are bounded protocol messages but are not routed yet.
- The Weston server and read-only status monitor are lifecycle/control components; the monitor is not a frame/input transport.

## Build and run

```bash
bash tools/build-termux-binder-client.sh dist/termux-socket-client
install -D -m 0644 dist/termux-socket-client/bridge-client.dex \
  termux-package/data/data/com.termux/files/usr/share/termux-xwayland/bridge-client.dex
bash tools/test-termux-package.sh
bash termux-package/build-deb.sh dist
```

CI stages the DEX automatically before building the DEB. Install the DEB inside Termux and use termux-xwayland start / stop.

## Required target-device acceptance

Confirm that the direct socket connects without EACCES, the session heartbeat remains fresh, and the Android preview receives increasing frame counts from an actual XWayland client. CI alone cannot establish this.
