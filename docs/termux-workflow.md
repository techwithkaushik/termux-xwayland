# Termux workflow

## Current milestone

The native binary currently provides a **single-client authenticated abstract Unix-domain socket smoke test** and sends one synthetic RGBA frame. On the target Android 10 device, the Android app reports `EACCES` when connecting to this socket across app UIDs. The handshake and synthetic frame therefore have **not** been verified on that device. This prototype does not provide a working cross-app transport or compositor.

The accepted IPC decision and constraints are documented in [ADR 0001](adr/0001-cross-app-ipc.md). Do not continue treating the abstract socket as the default cross-UID path.

## Termux integration direction (no RUN_COMMAND dependency)

The generic Termux `RUN_COMMAND` API is not the display transport and requires the user's explicit external-command configuration. This project will not require `allow-external-apps=true` merely to launch its display host, and the Android host no longer exposes a RUN_COMMAND diagnostic button or permission.

Termux:X11 is a useful architectural reference because it ships **both** an Android display app and a Termux-side package; its optional shared-UID build is a separate distribution choice and requires matching Termux signing keys. See the [upstream Termux:X11 README](https://github.com/termux/termux-x11#setup-instructions). Do not assume its private Binder interfaces are stable public APIs or available to this app.

The agreed component model is **Termux app → Termux-side companion package/CLI → Termux-XWayland Android host**. The user starts the companion from the Termux shell; it manages the Linux session and coordinates with the Android host. The host owns the display surface and Android input.

Implementation contract and acceptance tests: [Termux companion integration contract](termux-companion-contract.md).

1. Keep the standalone host UI independent of Termux's generic command-launch permission.
2. Implement the companion package skeleton with start/stop/status commands and explicit session lifecycle.
3. Verify a supported authorization mechanism and Binder/file-descriptor handoff with the actual Termux installation and signing keys on Android 10. Do not assume a signature permission authorizes unrelated certificates.
4. Add bounded, bidirectional streaming and lifecycle tests. Real compositor work begins only after device acceptance; no shell polling or temporary-file frame transport.

The initial Termux DEB package lives under `termux-package/`. Build it with `bash termux-package/build-deb.sh`, install the generated `.deb` inside Termux using `dpkg -i`, then run `termux-xwayland status` and `termux-xwayland start`. See [termux-deb-install.md](termux-deb-install.md). It currently detects/launches the Android host only; no real session streaming is implemented.

The earlier same-certificate Binder APK was only a feasibility experiment and is not part of the supported setup. Do not install or distribute a separate companion APK. The remaining design blocker is an explicitly authorized channel between the existing Termux environment and the Android host that supports descriptor handoff and a persistent bounded stream without RUN_COMMAND, TCP fallback, root, or SELinux changes.

## Probe Termux:X11 and XWayland sockets

The read-only diagnostic `tools/probe-termux-x11-sockets.sh` checks the runtime paths and Unix socket nodes visible to the Termux shell. Termux:X11 is itself a fully fledged X server; its X11 display socket is commonly `$TMPDIR/.X11-unix/X0` for `DISPLAY=:0`. That is not a Wayland socket. The probe separately checks common Wayland compositor socket names such as `wayland-0` and `wayland-1`, but their absence does not by itself identify every possible transport. Actual paths can vary with `TMPDIR`, `XDG_RUNTIME_DIR`, display number, and how the server was launched.

From a Termux shell, start Termux:X11 first:

    termux-x11 :0

Leave it running, open another Termux session, go to the repository root, and run:

    bash tools/probe-termux-x11-sockets.sh

For a meaningful XWayland check, an actual Wayland compositor/backend and an XWayland server must be running; the current project does not implement those yet. The script does not start or stop servers, change permissions, or modify files. It reports socket paths and metadata only; a socket's presence does not prove that another Android app is authorized to connect to it. If no expected socket is found, check the printed runtime directories and process list.

## Build the native diagnostic server

Install the native tools in Termux:

    pkg update
    pkg install git rust clang pkg-config

Clone this repository and build the Rust diagnostic binary:

    git clone https://github.com/techwithkaushik/termux-xwayland.git
    cd termux-xwayland
    git checkout feat/rust-jni-socket-reader
    cd native/compositor
    cargo test
    cargo build --release

Use `feat/rust-jni-socket-reader` for this work until PR #12 is merged.

## Run the diagnostic socket test (optional)

This test can still be useful for protocol development, but it is expected to fail at the Android connection step on devices that deny cross-UID abstract sockets. It is not a workaround for the Android security policy.

Generate a fresh 256-bit token and start the server:

    export TERMUX_XWAYLAND_TOKEN_HEX="$(od -An -N32 -tx1 /dev/urandom | tr -d ' \n')"
    printf 'Session token: %s\n' "$TERMUX_XWAYLAND_TOKEN_HEX"
    ./target/release/termux-xwayland-compositor

Enter the 64-character token in the app and tap **Connect securely**. If the app reports `EACCES`, stop the test; changing the token or running `chmod` will not resolve a policy denial. Stop the server with Ctrl+C and clear the token with `unset TERMUX_XWAYLAND_TOKEN_HEX`. Do not share screenshots or logs containing the token.

## Transport requirements before compositor work

The next milestone is the Termux-side companion package described in [termux-companion-contract.md](termux-companion-contract.md), followed by a device-verified, explicitly authorized control/FD handoff and persistent frame/input channel. The generic command-launch API is not required and is not a streaming transport.

Do not disable SELinux, require root, or add localhost TCP as a fallback. Do not begin real desktop frame streaming until the IPC probe proves bidirectional bounded messaging, session lifecycle handling, and Android 10 compatibility.

## What is still missing

No real Wayland compositor, desktop frame capture, input forwarding, XWayland, XFCE or LXQt session is available yet. The current synthetic frame is not Linux desktop output. Android CI and Rust tests validate compilation/protocol logic only; actual Android IPC policy must be tested on the target device.
