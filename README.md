# Termux XWayland

Termux XWayland is an Android display host paired with a Termux-installed CLI/DEB. It uses the existing Termux installation; there is no second companion APK in the supported install flow.

> **Current milestone: Binder session heartbeat, not desktop streaming.** The Android host exposes `BridgeSessionService`, checks that the actual Binder caller UID maps to the installed `com.termux` package, and transfers a connected socket FD over Binder. The Termux DEB includes a Java client compiled to DEX in CI; it runs under the existing Termux UID through Android `app_process` and maintains bounded TXWB v1 PING/PONG. CI passes for the host APK, client DEX and DEB wiring. The new client/service handoff still requires device verification on Android 10 / MIUI.

## Current implementation

- **Android host:** Kotlin UI plus the in-app Binder session service.
- **Termux package:** `termux-xwayland` starts/stops Weston, opens the host, launches the Binder client and reports session state.
- **Binder client:** source is `termux-package/src/BridgeClient.java`; CI compiles it to DEX and stages it into the Termux DEB.
- **No separate companion APK:** the retired `:ipc-companion` module is removed from Gradle settings, CI and the repository.
- **No TCP frame/input fallback:** the historical loopback-only status monitor is low-rate status/control data only. It does not carry desktop frames or input. No root, SELinux weakening, `allow-external-apps=true` or Termux RUN_COMMAND is required.
- **Protocol:** TXWB v1, 12-byte header, reserved-bit validation and 16 MiB maximum payload.
- **Not yet implemented:** real compositor frame production, Android rendering of a real Wayland surface, touch/keyboard forwarding, clipboard routing, reconnect/backpressure policy and performance verification.

## Build and test

With JDK 17, Gradle 8.9 and the Android SDK:

```bash
gradle :android-app:testDebugUnitTest :android-app:assembleDebug
bash tools/build-termux-binder-client.sh dist/termux-binder-client
install -D -m 0644 dist/termux-binder-client/bridge-client.dex \
  termux-package/data/data/com.termux/files/usr/share/termux-xwayland/bridge-client.dex
bash tools/test-termux-package.sh
bash termux-package/build-deb.sh dist
```

CI stages the DEX automatically and publishes three artifacts: the display-host APK, the Binder-client DEX and the Termux DEB. Install the DEB inside Termux, then run `termux-xwayland status` and `termux-xwayland start`. The CLI waits for the Binder session to connect; `termux-xwayland stop` closes the session and stops Weston.

## Security and verification boundary

- Cross-UID abstract AF_UNIX connect returned `EACCES` on the target Android 10 device and is not the production transport.
- Binder authorization checks the actual caller UID through Android's package manager.
- The connected FD is transferred through Binder. The client does not connect to a cross-UID abstract/filesystem socket.
- Temporary TCP echo/protocol probes were historical diagnostics only; they are not a frame/input transport.
- CI compilation does not prove Binder authorization or FD lifecycle behavior on the phone.
- Do not claim a working desktop until the new Binder session is verified on-device and a real Wayland surface is rendered.

See [docs/termux-workflow.md](docs/termux-workflow.md), [docs/binder-session-endpoint.md](docs/binder-session-endpoint.md), and [docs/termux-companion-contract.md](docs/termux-companion-contract.md).
