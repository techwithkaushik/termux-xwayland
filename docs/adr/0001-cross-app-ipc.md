# ADR 0001: Authorized cross-app IPC without a TCP frame fallback

- Status: Binder session transport implemented; target-device verification pending
- Date: 2026-10-20

## Context and constraints

The Android display host and an ordinary Termux installation run under different Android UIDs. The abstract AF_UNIX socket prototype failed with `EACCES` on the target Android 10 device before token authentication. Changing socket permissions or tokens cannot fix that authorization denial.

The production path must preserve the existing Termux install. It must not require root, SELinux policy changes, `allow-external-apps=true`, Termux RUN_COMMAND, or a TCP/localhost listener for frames or input. The old loopback status monitor is not a frame transport.

## Decision

Use the existing Android host APK to expose `BridgeSessionService`. The service checks that the actual Binder caller UID maps to package `com.termux`, creates a connected socket pair and transfers the client FD through Binder. A Java client runs under the existing Termux UID via `app_process`; CI compiles it to DEX and includes that DEX in the Termux DEB. No separate companion APK is built or installed.

The transferred socket uses TXWB v1 framing. The host sends WELCOME, responds to empty PING with PONG and supports CLOSE. The client validates the 12-byte header and 16 MiB payload ceiling and maintains a persistent heartbeat. The host closes a replaced session and shuts down the socket to wake blocked reads. INPUT/RESIZE/CLIPBOARD are bounded at the framing layer but are not routed yet.

## Verification boundary

CI now builds the Android host, compiles the Termux app_process client DEX and tests the Termux DEB lifecycle/package wiring. The client/service handoff still requires target-device verification on Android 10 / MIUI: bind as the actual Termux UID, receive the FD, repeat PING/PONG, stop the process, reconnect and check descriptor cleanup. Do not claim a live desktop stream until those checks pass and a real Wayland surface is rendered.

## Historical diagnostics

- Cross-UID abstract AF_UNIX connect: denied with `EACCES` on the target device.
- A historical separately installed test companion returned an exact 16 KiB Binder echo. That feasibility probe is retired and is not part of the supported install flow.
- Temporary loopback TCP echo and TXWB PING/PONG diagnostics passed on-device. They remain historical diagnostics, not a production transport or fallback.

## Next milestones

1. Verify Binder authorization, FD transfer, repeated PING/PONG, process exit and reconnect on Android 10.
2. Connect the session lifecycle to the real compositor process and add bounded frame delivery/backpressure.
3. Render one real Wayland client surface and only then implement input/resize/clipboard forwarding.
4. Integrate XWayland and desktop environments after the native Wayland path is proven.

No localhost TCP frame/input transport, root or SELinux weakening is accepted.
