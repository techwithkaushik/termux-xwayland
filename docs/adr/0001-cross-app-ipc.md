# ADR 0001: Cross-app IPC without localhost TCP

- Status: Accepted for the current prototype
- Date: 2026-10-19

## Context

The Android display-host app and an ordinary Termux installation run under different Android UIDs. The current abstract AF_UNIX socket prototype fails at `LocalSocket.connect()` with `EACCES` on the target device. The failure occurs before the session token is sent, so changing the token or socket permissions cannot solve it.

The project must not require disabling SELinux, weakening Android security policy, or introducing a localhost TCP listener. The default path must preserve the user's existing Termux installation.

## Decision

Do not treat abstract AF_UNIX as a supported cross-UID transport for the normal install. Keep the current socket code only as a diagnostic prototype and do not report the cross-UID smoke test as working until it has passed on a real Android 10 device.

A production bridge needs an Android-authorized IPC endpoint whose access policy permits the two apps to communicate. Evaluate these options in order:

1. **Termux-side companion/plugin with an explicitly exported, permission-protected Binder service.** The service authenticates the display host, owns the Termux-side session, and brokers a framed bidirectional channel. This preserves the main Termux APK but requires installing a companion component and verifying whether Android permits the desired service-to-app connection and long-lived data flow.
2. **A coordinated Termux build and display-host build sharing the Termux UID and signing certificate.** This can enable same-UID IPC but is not compatible with arbitrary existing Termux installations and is not the default path.
3. **A Termux-mediated command API only for control-plane operations.** The Termux RUN_COMMAND API can be used to request a command launch when the installed Termux version and permission configuration support it. It is not, by itself, a proven low-latency, full-duplex frame/input transport; do not claim it solves streaming.

## Required transport contract

Before selecting a production implementation, prove all of the following with a small device test:

- No TCP socket, localhost port, network permission, root requirement, or SELinux policy modification.
- Explicit authorization of the display-host client; reject unknown callers.
- Bidirectional framed messages, bounded payloads, and backpressure.
- Long-lived session lifecycle: connect, disconnect, app backgrounding, Termux process exit, and reconnect.
- A measured frame path suitable for interactive display, not repeated shell invocations or temporary files as the real-time transport.
- Android 10 compatibility, including the actual Termux distribution/signing setup.

## Consequences

The current synthetic frame is only a protocol/UI test and cannot display a Linux desktop. Real Wayland frame transport and input forwarding must wait until the cross-app IPC transport passes the device test. CI compilation and unit tests cannot establish Android SELinux/Binder policy behavior; device verification is mandatory.

## Immediate next step

Build a minimal IPC capability probe for the selected Termux-side integration before implementing a compositor. Do not replace the user's Termux installation or silently add a network fallback. If a companion/plugin is required, document the install and authorization flow and keep it separate from the main Termux app.
