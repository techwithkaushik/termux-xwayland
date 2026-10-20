# Binder and frame transport

The existing Android host APK and the existing Termux DEB/CLI are the only installed components. The DEB launches the Java client in the existing Termux UID through `app_process`; no separately installable companion APK is built.

## Bootstrap: follow Termux:X11

The Termux-side client does **not** call `Context.bindService()`. A synthetic `ActivityThread` created inside `app_process` is not registered as an Android app process, so ActivityManager rejects service binding with `Unable to find app for caller IApplicationThread`.

Instead, the client follows the Termux:X11 `CmdEntryPoint` pattern:
1. Build a platform context in the `app_process` process.
2. Send a package-targeted broadcast to the installed host APK with a Binder endpoint in the extras.
3. The host receiver validates the broadcast sender UID maps to `com.termux`, then starts the in-app session service.
4. The host service calls the client Binder endpoint, which transfers a connected socket FD over Binder.
5. TXWB v1 runs over that FD. The client endpoint only accepts Binder calls from the installed host APK UID.

The host service is not exported. Only the dedicated receiver is exported, and it checks the actual broadcast sender UID before accepting the Binder object. No TCP listener, root, SELinux change, or Termux `RUN_COMMAND` permission is used.

## Security and session

- The host receiver validates the sender UID against Android's package-manager mapping for `com.termux`.
- The client Binder endpoint validates the caller UID against the installed host APK UID.
- Binder transfers a connected socket FD; no cross-UID AF_UNIX connect is required.
- No TCP listener carries frames or input. The loopback status monitor remains separate control-plane polling.
- TXWB v1 framing enforces known message kinds, reserved-bit validation and a 16 MiB payload ceiling.
- The client reports connected only after a valid PING/PONG and logs a timestamped heartbeat every five seconds.

## XWayland frame path

When XWayland and ffmpeg are installed, the client starts an optional ffmpeg X11 capture at 2 FPS and sends bounded RGBA `FRAME` messages through the Binder-transferred FD. The Android service validates each frame with `RgbaFrameCodec` and hands decoded pixels to the existing activity for display.

This path is X11/XWayland-only. Native Wayland capture and input forwarding are not implemented. Target-device verification on Android 10 / MIUI remains required; CI cannot prove visible frames on the phone.
