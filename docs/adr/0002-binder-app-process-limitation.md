# ADR 0002: Termux:X11-style Binder bootstrap

- Status: Implemented; target-device validation pending
- Date: 2026-10-20

## Context

The old Termux-side client ran in `app_process`, constructed a synthetic `ActivityThread`/context, then called `Context.bindService()`. Android 10 / MIUI rejected it with `Unable to find app for caller IApplicationThread`, because ActivityManager does not register this synthetic process as a normal application process.

## Decision

Adapt the Termux:X11 `CmdEntryPoint` pattern instead of binding a service from `app_process`:

1. Construct the platform context using the Unsafe-allocated `ActivityThread` approach.
2. Send a package-targeted broadcast carrying a Binder endpoint to the existing host APK.
3. The host receiver checks the Binder descriptor and forwards it to the in-app session service.
4. The host service calls back into the Termux-side Binder endpoint; the endpoint checks the host UID and transfers one end of a connected socket pair through Binder.
5. Keep the TXWB session protocol on the transferred FD; no TCP, root, SELinux weakening, or separately installable companion APK.

## Security qualification

Android 10's public `BroadcastReceiver` API does not expose the original sender UID, so this receiver does not claim to authenticate broadcast origin. Binder descriptor validation is a sanity check, not authentication. The Termux endpoint checks the host UID before handing over an FD, payloads remain bounded, and the host service refuses to replace an active session. This limits the broadcast surface but does not make the broadcast itself a security boundary.

## Validation

- Scaffold checks assert the receiver exists and the client no longer calls `Context.bindService()`.
- GitHub Actions must compile the Java DEX and Android Kotlin receiver/service.
- Android 10 / MIUI device testing is required to prove broadcast delivery, Binder callback, FD handoff, PING/PONG and visible XWayland frames. CI success alone is not proof.

## Reference implementation

- [Termux:X11 CmdEntryPoint](https://github.com/termux/termux-x11/blob/master/lorie/src/main/java/com/termux/x11/CmdEntryPoint.java)
- [Termux:X11 LorieApp](https://github.com/termux/termux-x11/blob/master/lorie/src/main/java/com/termux/x11/LorieApp.java)
- [Termux:X11 Loader](https://github.com/termux/termux-x11/blob/master/shell-loader/src/main/java/com/termux/x11/Loader.java)
