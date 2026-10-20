# Termux workflow

## Current milestone

The Android host exposes `BridgeSessionService`; the Termux DEB contains a Java client compiled to DEX in CI. The client binds under the existing Termux UID, receives a connected FD over Binder, validates TXWB v1 frames and maintains PING/PONG. This is a transport milestone only; real Wayland frames, Android rendering and input forwarding are not complete.

The old abstract AF_UNIX cross-UID connection failed with `EACCES` on the target Android 10 device. It remains diagnostic only. The retired `:ipc-companion` test APK is no longer part of Gradle settings, CI or the supported install flow.

## Build artifacts

CI publishes:
- `termux-xwayland-debug-apk`: Android display-host APK.
- `termux-xwayland-binder-client-dex`: DEX for the Termux-side `app_process` client.
- `termux-xwayland-bridge-deb`: Termux CLI and Binder client DEX.

For local builds with JDK 17, Gradle 8.9 and the Android SDK:

```bash
gradle :android-app:testDebugUnitTest :android-app:assembleDebug
bash tools/build-termux-binder-client.sh dist/termux-binder-client
install -D -m 0644 dist/termux-binder-client/bridge-client.dex \
  termux-package/data/data/com.termux/files/usr/share/termux-xwayland/bridge-client.dex
bash tools/test-termux-package.sh
bash termux-package/build-deb.sh dist
```

Install the DEB inside Termux using `dpkg -i`, then run `termux-xwayland status` and `termux-xwayland start`. Start launches Weston, opens the host, starts the Binder client and waits for the host's WELCOME plus initial PING/PONG. Use `termux-xwayland stop` to close the Binder session and stop the monitor and Weston.

## Security and diagnostics

- The Binder service authorizes the actual Binder caller UID through Android's package manager and accepts only the installed `com.termux` package.
- The socket FD is transferred through Binder; the client does not connect to a cross-UID AF_UNIX path.
- No companion APK, Termux RUN_COMMAND, root, SELinux change or TCP frame/input fallback is used.
- The loopback-only status monitor is low-rate status/control data only, not a frame/input channel.
- The client/service integration has not yet been verified on the target Android 10 device. CI cannot establish MIUI Binder policy or FD lifecycle behavior.

## Remaining work

1. Verify binding, FD handoff, repeated PING/PONG, stop, reconnect and descriptor cleanup on the target phone.
2. Route the session to a real compositor frame source with bounded backpressure.
3. Render a real Wayland client surface in the Android host.
4. Add Android input, resize and clipboard forwarding after real frame delivery works.
5. Integrate XWayland and then desktop environments.

Do not report the current Binder heartbeat or synthetic test frame as a working desktop stream.
