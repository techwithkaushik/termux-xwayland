# Binder session endpoint

The existing Android host APK exposes `BridgeSessionService`. No second companion APK is built or installed.

## Security and transport

- The service checks the actual Binder caller UID against Android's package-manager mapping for `com.termux`.
- Binder transfers a connected socket FD; no cross-UID AF_UNIX connect is required.
- No TCP listener is used for frames or input. The legacy loopback status monitor is separate control-plane polling only.
- A replacement session closes the previous host FD. Service destruction and peer EOF close the active session.
- TXWB v1 framing enforces known message kinds, reserved-bit validation and a 16 MiB payload ceiling.
- The host persists Binder state only after a valid PING/PONG exchange. The Termux CLI requires a recent heartbeat; a stale log line alone is not considered connected.

## Binder wire contract

1. Bind to `org.techwithkaushik.termuxxwayland/.BridgeSessionService`.
2. Transact with descriptor `org.techwithkaushik.termuxxwayland.IBridgeSession` and code `IBinder.FIRST_CALL_TRANSACTION`.
3. Write protocol version `1` to the request parcel.
4. Read a `ParcelFileDescriptor` from the reply; the socket begins with TXWB `WELCOME` carrying the host UID.
5. Send empty `PING` and require empty `PONG`. The client prints `SESSION_CONNECTED` only after the first PONG and logs a timestamped heartbeat every five seconds. Send empty `CLOSE` for orderly shutdown.

## Remaining work

The Binder heartbeat is not a desktop stream. A real compositor frame source, Android rendering of a real Wayland surface, input/resize/clipboard routing, reconnect/backpressure policy, and target-device verification are still outstanding. The historical abstract-socket diagnostic is not the production connection path.

CI verifies host compilation, DEX generation, package lifecycle tests and native protocol tests. It cannot prove Binder authorization or FD lifecycle behavior on Android 10 / MIUI.
