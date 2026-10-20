# Binder and frame transport

The existing Android host APK and the existing Termux DEB/CLI are the only installed components. The DEB launches the Java client in the existing Termux UID through `app_process`; no separately installable companion APK is built.

## Bootstrap: follow Termux:X11

The Termux-side client does **not** call `Context.bindService()`. A synthetic `ActivityThread` created inside `app_process` is not registered as an Android app process, so ActivityManager rejects service binding with `Unable to find app for caller IApplicationThread`.

Instead, the client follows the Termux:X11 `CmdEntryPoint` pattern:
1. Build a platform context in the `app_process` process.
2. Send a package-targeted broadcast to the existing host APK with a Binder endpoint in a nested Bundle.
3. The host receiver checks the Binder descriptor and forwards the Binder to the in-app session service.
4. The host service calls the client Binder endpoint, which checks the calling UID against the installed host APK UID and transfers one end of a connected socket pair through Binder.
5. TXWB v1 runs over that FD.

Android 10's public `BroadcastReceiver` API does not expose the sender UID, so the receiver does not claim to authenticate the broadcast sender. It validates the Binder descriptor as a sanity check, and the host service refuses to replace an active session. The Termux-side Binder endpoint authenticates the host UID before transferring an FD. A malicious app can at most attempt to trigger a new session/denial of service; it cannot impersonate the Termux-side endpoint's Binder implementation. Keep payload bounds and do not treat the broadcast alone as authentication.

The host session service is not exported. No TCP listener, root, SELinux change, or Termux `RUN_COMMAND` permission is used.

## XWayland frame path

When XWayland and ffmpeg are installed, the client starts an optional ffmpeg X11 capture at 2 FPS and sends bounded RGBA `FRAME` messages through the Binder-transferred FD. The Android service validates each frame with `RgbaFrameCodec` and hands decoded pixels to the existing activity for display.

This path is X11/XWayland-only. Native Wayland capture and input forwarding are not implemented. Target-device verification on Android 10 / MIUI remains required; CI cannot prove visible frames on the phone.
