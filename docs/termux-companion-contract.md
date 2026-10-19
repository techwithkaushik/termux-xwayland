# Termux companion integration contract (design)

## Goal

Provide a three-part integration without requiring the user to enable Termux's generic RUN_COMMAND API:

1. **Termux app** remains the existing Linux userspace, package manager and shell.
2. **Termux-side companion package/CLI** is installed inside the Termux environment. The user starts a session by invoking its command from Termux. It manages session intent and starts/observes the Linux-side compositor process.
3. **Termux-XWayland Android host** owns the display surface, Android input, session UI and Android lifecycle.

The companion package is not the same thing as the existing experimental `:ipc-companion` APK. That APK currently only tests same-certificate host-to-companion Binder calls.

## Responsibilities

### Termux-side package

- Install a versioned command such as `termux-xwayland` into the Termux prefix.
- Validate arguments and report actionable errors without logging session secrets.
- Create a private runtime directory under the Termux user's runtime/temp area with restrictive permissions.
- Start/stop the Linux session and compositor process; track PID and exit status.
- Negotiate protocol version, session ID, dimensions and supported pixel formats.
- Never use repeated shell commands, stdout scraping, or temporary frame files as the real-time transport.

### Android host

- Expose only the minimal explicit Android entry point required for starting a session.
- Validate caller/package identity and session parameters; reject malformed or oversized input.
- Own the visible display surface and map touch, keyboard, resize, focus and lifecycle events.
- Close descriptors and stop sessions deterministically on user stop, process death or disconnect.
- Display honest state: unavailable, starting, connected, disconnected, failed.

### Session bridge

- Separate low-rate control messages (start, stop, status, resize) from the high-rate frame/input channel.
- Prefer an Android-authorized Binder-mediated handoff for session setup and file descriptors; then use a persistent bounded channel for frames/input.
- Keep protocol versioning, message limits, backpressure and disconnect handling explicit.
- Do not assume the private Termux:X11 Binder interfaces are a public or stable API.
- Do not assume a signature permission works across independently signed APKs. Validate the actual installation/signing model before choosing a permission strategy.

## Message contract (initial proposal)

Control messages are versioned and bounded. Every request includes a protocol version, random session ID, message type and bounded payload.

- `HELLO`: protocol min/max and client build.
- `START_SESSION`: requested dimensions and supported formats; no shell command string supplied by an untrusted Android intent.
- `SESSION_READY`: negotiated protocol, session ID and transport handles.
- `STATUS`: state and bounded diagnostic code; no secret tokens.
- `RESIZE`: positive width/height within configured caps.
- `INPUT`: typed key/touch event with coordinate and payload validation.
- `STOP_SESSION` / `CLOSE`: idempotent shutdown.

Transport requirements:

- Explicitly authorize the Android host and Termux-side initiator.
- Bound control messages and frame dimensions/bytes before allocation.
- Authenticate the session independently of a user-provided display name.
- Handle duplicate start, stale session IDs, disconnect, process death, app background/foreground, and reconnect.
- Pass file descriptors only through a documented Android IPC mechanism; validate descriptor ownership and close every duplicated descriptor.
- No TCP/localhost listener, root requirement, SELinux weakening, or requirement to replace the user's existing Termux app.

## Implementation sequence

1. **Architecture/spec (this document):** agree on component boundaries and control contract.
2. **Discovery/authorization spike:** verify an explicit entry point and bidirectional IPC on Android 10 with the actual Termux distribution. Record package name, signing assumptions, API level and results.
3. **Termux package skeleton:** installable shell/launcher script and lifecycle/status commands; no fake claims of a display session.
4. **Binder/FD proof:** pass one bounded test payload/descriptor and test close/error behavior.
5. **Persistent stream proof:** bidirectional bounded frame/input messages and reconnect/backpressure tests.
6. **Real compositor:** only after transport acceptance, render one real Wayland client and display its frames.
7. **XWayland and desktop sessions:** add XWayland, then XFCE/LXQt after the native Wayland path is proven.

## Acceptance criteria

- Existing Termux remains installed and unmodified; no `allow-external-apps=true` requirement.
- Android 10 / API 29 works on the target device with the real installation/signing arrangement.
- Start/stop/status/reconnect work without force-stopping either app.
- Unknown clients and malformed/oversized requests are rejected.
- File descriptors close on all success/error paths.
- No TCP, localhost port, root, SELinux policy change, or temporary-file frame streaming.
- The app displays a real compositor frame before the project claims desktop support.

## Current status

This is an architecture contract, not an implemented transport. The existing abstract AF_UNIX prototype is blocked by cross-UID `EACCES` on the target device. The current Binder probe is host-to-test-companion only. No FD handoff, Termux-side package, real compositor or real desktop frame path has been implemented or device-verified.
