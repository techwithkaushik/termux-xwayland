# ADR 0002: Binder client process registration

- Status: Blocked pending an approved transport/launch constraint change
- Date: 2026-10-20

## Context

The Termux-side client is launched with `/system/bin/app_process` under the existing Termux UID. It constructs a synthetic `ActivityThread` and `ContextImpl` from Termux's `ApplicationInfo`, then calls `Context.bindService()` on the Android host's `BridgeSessionService`.

On the target Android 10 / MIUI device, this fails before the host service receives a request:

```
SecurityException: Unable to find app for caller android.app.IApplicationThread...
when binding service ...BridgeSessionService
```

Android's ActivityManager resolves the caller's `IApplicationThread` to a registered process record before accepting a service bind. Constructing an `ActivityThread` and application context reflectively does not register that process with ActivityManager. The platform implementation explicitly throws this exception when no process record is found.

## Decision

Do not treat this Binder route as a working transport and do not add frame/input streaming on top of it. Do not retry with `ActivityThread.systemMain()/getSystemContext()`; that does not register the caller and triggers a separate MIUI theme-resource failure on this device.

Keep the current security constraints: no root, SELinux weakening, TCP/localhost fallback, Termux `RUN_COMMAND` dependency, or separately installable companion APK. The existing Android host APK is not being removed or replaced.

## Required next step

A Binder-based design must originate from a real, ActivityManager-registered Android application process, or use another explicitly approved IPC bootstrap. The current constraints provide no registered Termux-side Android process and the attempted cross-UID abstract AF_UNIX route already failed with `EACCES` on the target device. Therefore no compliant, proven Binder bootstrap is available yet.

Before implementing a new transport, validate a minimal end-to-end capability probe on Android 10 and confirm the caller identity and authorization boundary. A green CI build is not device IPC proof.

## Validation evidence

- Device log: `bindService failed: java.lang.SecurityException: Unable to find app for caller ...`
- Android framework source: `ActiveServices.bindServiceLocked` rejects a caller when `getRecordForApp(caller)` returns null: https://android.googlesource.com/platform/frameworks/base/+/oreo-release/services/core/java/com/android/server/am/ActiveServices.java
- This is a process-registration failure, not a service export/permission tweak or a frame-capture issue.
