# Termux-side integration contract

## Components

1. **Existing Termux app** owns the shell, packages and Linux userspace.
2. **Termux DEB/CLI** owns Weston/compositor lifecycle and launches a Java client through Android `app_process` while retaining the real Termux UID.
3. **Existing Termux-XWayland Android host APK** owns the display UI and exposes `BridgeSessionService` for authorized Binder FD handoff.

There is no separate companion APK in the supported build/install flow.

## Current implementation

- The Android service verifies that the actual Binder caller UID maps to package `com.termux`.
- Binder creates a connected socket pair and transfers the client end in the transaction reply.
- The Termux-side Java helper is compiled against Android SDK stubs and converted to DEX in CI; the DEX is included in the Termux DEB.
- The client validates TXWB v1 framing, checks WELCOME, sends PING, verifies PONG and repeats until disconnect.
- The service validates framing, responds to PING and handles CLOSE; descriptor shutdown is attempted during replacement and service destruction.
- INPUT/RESIZE/CLIPBOARD are bounded protocol messages but are not routed yet.
- The Weston server and read-only status monitor are lifecycle/control components; the monitor is not a frame/input transport.

## Build and run

```bash
bash tools/build-termux-binder-client.sh dist/termux-binder-client
install -D -m 0644 dist/termux-binder-client/bridge-client.dex \
  termux-package/data/data/com.termux/files/usr/share/termux-xwayland/bridge-client.dex
bash tools/test-termux-package.sh
bash termux-package/build-deb.sh dist
```

CI stages the DEX automatically before building the DEB. Install the DEB inside Termux and use `termux-xwayland start` / `stop`.

## Required target-device acceptance

On Android 10 / MIUI with the existing Termux install:
- Verify the app_process helper creates a `com.termux` package context and binds successfully.
- Verify the host rejects callers whose UID does not map to `com.termux`.
- Verify Binder FD handoff and repeated bidirectional PING/PONG.
- Stop the client and confirm the host ends the session and releases its descriptor.
- Restart the session and verify reconnect without force-stopping either app.
- Verify malformed/oversized frames are rejected and no fallback is opened.
- Confirm no TCP frame/input listener, root, SELinux policy change, Termux replacement or `allow-external-apps=true` requirement.

CI compilation is not proof of Android authorization or device compatibility.

## Not yet complete

The Binder heartbeat is not a desktop stream. A real compositor frame source, Android rendering, input/resize/clipboard routing, reconnect/backpressure policy and measured performance remain future work. Do not claim desktop support until a real Wayland client surface is visible on the device.
