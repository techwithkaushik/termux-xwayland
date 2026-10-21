# Direct socket session endpoint

The existing Android host APK listens on a session-specific abstract AF_UNIX socket. The Termux DEB includes the Java app_process client; no second companion APK is built or installed. The old Binder service has been removed from the runtime path.

## Security and session

- The socket name is derived from a fresh 128-bit session ID delivered in the launch intent.
- The client sends the session ID in HELLO; the host compares it with the current session and verifies the kernel-reported peer UID against the installed com.termux package UID.
- No TCP listener carries frames or input. The loopback status monitor is separate control-plane polling only.
- TXWB v1 framing enforces known message kinds, reserved-bit validation and a 16 MiB payload ceiling.
- The client reports connected only after WELCOME validation and maintains a PING/PONG heartbeat every five seconds.

## XWayland frame path

When Xwayland and ffmpeg are installed, the CLI starts Weston with a 640×360 headless output and XWayland enabled. The client starts optional ffmpeg X11 capture at 2 FPS and sends bounded RGBA FRAME messages over the direct socket. The Android host validates frame metadata and payload length before displaying decoded pixels.

If ffmpeg, XWayland, or a usable X11 display is unavailable, the socket heartbeat can remain available while frame capture reports waiting/unavailable. Launch an X11 app with termux-xwayland x11 xterm (or another installed app).

## Verification boundary

Cross-UID abstract AF_UNIX access previously returned EACCES on the target Android 10 / MIUI device. This migration removes the failing Binder bind and MIUI context construction, but does not prove SELinux will allow the reverse-direction socket connection. Verify the new direction on-device before claiming the stream is fixed. Native Wayland capture and input forwarding are not implemented.
