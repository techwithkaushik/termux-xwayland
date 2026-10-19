# Termux workflow

## Current milestone

The native binary currently provides a **single-client authenticated abstract Unix-domain socket smoke test** and sends one synthetic RGBA frame. On the target Android 10 device, the Android app reports `EACCES` when connecting to this socket across app UIDs. The handshake and synthetic frame therefore have **not** been verified on that device. This prototype does not provide a working cross-app transport or compositor.

The accepted IPC decision and constraints are documented in [ADR 0001](adr/0001-cross-app-ipc.md). Do not continue treating the abstract socket as the default cross-UID path.

## Binder companion capability probe (experimental)

Build both debug APKs with the same Gradle/JDK environment so Android signs them with the same debug certificate:

    gradle --no-daemon :ipc-companion:assembleDebug :android-app:assembleDebug

Install `ipc-companion/build/outputs/apk/debug/ipc-companion-debug.apk` first, then `android-app/build/outputs/apk/debug/android-app-debug.apk`. The companion declares a signature-level permission and the display host requests it. Open the display-host app and tap **Run Binder capability probe**. A pass requires a 16 KiB exact echo, a caller UID matching the host process, and a different service UID. If Android rejects the bind, check install order and signing certificate; do not weaken the permission to `normal` or `dangerous`.

This probe verifies only display-host ↔ companion Binder IPC. It does **not** verify access from the existing Termux app, a long-lived bidirectional stream, file-descriptor passing, or frame performance. Those remain separate device acceptance tests. No network permission, TCP listener, root, or SELinux change is introduced.

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
    git checkout feat/phase1-android-host
    cd native/compositor
    cargo test
    cargo build --release

Use `feat/phase1-android-host` until its PR is merged.

## Run the diagnostic socket test (optional)

This test can still be useful for protocol development, but it is expected to fail at the Android connection step on devices that deny cross-UID abstract sockets. It is not a workaround for the Android security policy.

Generate a fresh 256-bit token and start the server:

    export TERMUX_XWAYLAND_TOKEN_HEX="$(od -An -N32 -tx1 /dev/urandom | tr -d ' \n')"
    printf 'Session token: %s\n' "$TERMUX_XWAYLAND_TOKEN_HEX"
    ./target/release/termux-xwayland-compositor

Enter the 64-character token in the app and tap **Connect securely**. If the app reports `EACCES`, stop the test; changing the token or running `chmod` will not resolve a policy denial. Stop the server with Ctrl+C and clear the token with `unset TERMUX_XWAYLAND_TOKEN_HEX`. Do not share screenshots or logs containing the token.

## Transport requirements before compositor work

The next milestone is a device-verified, explicitly authorized IPC mechanism that works with the user's existing Termux installation. Investigate a Termux-side companion/plugin exposing a permission-protected Binder endpoint. Termux's command-launch API may be useful for control-plane requests, but it has not been established as a low-latency bidirectional stream for frames and input.

Do not disable SELinux, require root, or add localhost TCP as a fallback. Do not begin real desktop frame streaming until the IPC probe proves bidirectional bounded messaging, session lifecycle handling, and Android 10 compatibility.

## What is still missing

No real Wayland compositor, desktop frame capture, input forwarding, XWayland, XFCE or LXQt session is available yet. The current synthetic frame is not Linux desktop output. Android CI and Rust tests validate compilation/protocol logic only; actual Android IPC policy must be tested on the target device.
