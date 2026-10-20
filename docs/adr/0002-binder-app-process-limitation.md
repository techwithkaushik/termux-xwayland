# ADR 0002: Termux:X11-style Binder bootstrap

- Status: Implemented; target-device validation pending
- Date: 2026-10-20

## Context

The original Termux-side client was launched with `/system/bin/app_process` under the existing Termux UID. It constructed a synthetic `ActivityThread` and `ContextImpl` from Termux's `ApplicationInfo`, then called `Context.bindService()` on the Android host's `BridgeSessionService`.

On Android 10 / MIUI, that failed before the host service received a request:

```
SecurityException: Unable to find app for caller android.app.IApplicationThread...
when binding service ...BridgeSessionService
```

ActivityManager resolves the caller's `IApplicationThread` to a registered process record before accepting a service bind. Creating an `ActivityThread` reflectively does not register the `app_process` process.

## Decision

Adapt the established Termux:X11 `CmdEntryPoint` pattern instead of binding a service from `app_process`:

1. Construct the platform context using the Unsafe-allocated `ActivityThread` approach used by Termux:X11.
2. Send a package-targeted broadcast carrying a Binder endpoint to the existing host APK.
3. Validate the broadcast sender UID against the installed `com.termux` package before forwarding the Binder to the in-app session service.
4. The host service calls back into the Termux-side Binder endpoint; the endpoint checks the host UID and transfers one end of a connected socket pair through Binder.
5. Keep the session protocol on the transferred FD; do not use TCP, root, SELinux weakening, or a separate companion APK.

The host session service is non-exported. The receiver is exported only because the existing Termux process must notify it; it rejects senders whose UID does not map to `com.termux`.

## Validation

- Static scaffold checks now assert that the receiver is present and the client does not call `Context.bindService()`.
- GitHub Actions must compile the Java DEX and Android Kotlin service/receiver.
- Android 10 / MIUI device testing is still required to prove the broadcast is delivered, the Binder callback succeeds, and the FD session reaches PING/PONG. CI success alone is not proof of end-to-end operation.

## Reference implementation

- [Termux:X11 CmdEntryPoint](https://github.com/termux/termux-x11/blob/master/lorie/src/main/java/com/termux/x11/CmdEntryPoint.java)
- [Termux:X11 LorieApp](https://github.com/termux/termux-x11/blob/master/lorie/src/main/java/com/termux/x11/LorieApp.java)
- [Termux:X11 Loader](https://github.com/termux/termux-x11/blob/master/shell-loader/src/main/java/com/termux/x11/Loader.java)
