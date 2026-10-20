# Binder session endpoint (implementation milestone)

The Android host now exposes `BridgeSessionService` inside the existing display-host APK. It does **not** add a second companion APK or change the installed Termux package.

## Security and transport

- The service accepts the private `IBridgeSession` Binder transaction only from a UID that owns the exact `com.termux` package.
- The client and host exchange a connected `ParcelFileDescriptor` socket pair through Binder. The client does not connect to a cross-UID abstract or filesystem Unix socket.
- The endpoint opens no TCP listener. The one-shot TCP probes remain diagnostics only and are not a fallback.
- A new session closes the previous host FD. Service destruction and peer EOF close the session FD.
- The socket uses the existing TXWB v1 framing from `BridgeProtocol`, including reserved-bit validation, known message kinds, and the 16 MiB payload ceiling. PING/PONG and CLOSE are implemented.

## Binder wire contract

1. Bind to `org.techwithkaushik.termuxxwayland/.BridgeSessionService`.
2. Transact with descriptor `org.techwithkaushik.termuxxwayland.IBridgeSession` and code `IBinder.FIRST_CALL_TRANSACTION`.
3. Write protocol version `1` to the request parcel.
4. On success, read a `ParcelFileDescriptor` from the reply. Its stream starts with TXWB `WELCOME`; the four-byte payload is the host UID in big-endian order.
5. Send TXWB `PING` to receive `PONG`; send empty `CLOSE` for orderly shutdown. Malformed frames terminate the session.

## Remaining work — not yet claimed complete

The Termux-side Binder client and CLI lifecycle wiring are still required. The service currently accepts framed INPUT/RESIZE/CLIPBOARD messages but only logs their kind; Android input injection, real compositor frame production, rendering, reconnect/backpressure policy, and device verification of this new service are not implemented by this milestone. The host's synthetic test pattern is not a Linux desktop stream.

CI compilation alone cannot prove Android 10 Binder authorization or descriptor handoff. The next device milestone must use the existing Termux process (not an installable companion APK) to bind, receive the FD, exchange PING/PONG repeatedly, disconnect, reconnect, and verify FD cleanup.
