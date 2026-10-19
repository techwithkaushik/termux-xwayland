# Termux workflow

## Current milestone

The native binary now provides a **single-client authenticated abstract Unix-domain socket smoke test** and sends one synthetic RGBA frame that the Android app validates and renders. It does not use TCP or localhost ports. This is not a compositor: no Wayland socket, real desktop frame capture, input forwarding, XWayland, XFCE or LXQt session is available yet.

## Build the native smoke-test server

Install the native tools in Termux:

    pkg update
    pkg install git rust clang pkg-config

Clone this repository and build the Rust binary:

    git clone https://github.com/techwithkaushik/termux-xwayland.git
    cd termux-xwayland
    git checkout feat/phase1-android-host
    cd native/compositor
    cargo test
    cargo build --release

Use `feat/phase1-android-host` until its PR is merged.

## Start a local authenticated test session

Generate a fresh 256-bit token for each run and start the server. The token is printed only so you can manually enter it in the Android app; do not share screenshots or logs containing it.

    export TERMUX_XWAYLAND_TOKEN_HEX="$(od -An -N32 -tx1 /dev/urandom | tr -d ' \n')"
    printf 'Session token: %s\n' "$TERMUX_XWAYLAND_TOKEN_HEX"
    ./target/release/termux-xwayland-compositor

The server listens on a session-specific abstract AF_UNIX socket derived from the first 8 token bytes of the token. In the Android app, enter the 64-character session token and tap **Connect securely**. No host, port, Wi-Fi, localhost TCP listener or Internet permission is needed. After connecting, the app checks the Termux process UID against the Android app UID, receives the synthetic frame, and sends a `PING` that must receive a `PONG`. When the UIDs differ and all checks pass, the status should say **Cross-UID IPC passed**, with both UIDs and the measured PING/PONG round-trip time. Tap **Disconnect** to close the session. When finished, stop the server with Ctrl+C and unset the token with `unset TERMUX_XWAYLAND_TOKEN_HEX`.

If the app reports `Android blocked the cross-app AF_UNIX connection (EACCES)`, the socket connection is being denied before token authentication. On Android 9/10 this is commonly SELinux per-app isolation; abstract sockets have no filesystem permission bits, so `chmod` cannot fix it. A shared-UID build requires the Android app and Termux to use the same shared UID and signing certificate (the ordinary CI debug APK does not satisfy that requirement). The alternative is to add an explicitly authorized Termux-mediated IPC endpoint. Do not disable SELinux or expose a TCP listener as a workaround. If the error is different, verify the server is running and the token has exactly 64 hexadecimal characters.

## What this test proves

It validates local AF_UNIX socket connection across the two app UIDs (when they differ), the versioned authenticated handshake, the reported Termux UID, bounded frame delivery, Android bitmap rendering and a bidirectional PING/PONG round trip. It does not display Linux applications. The next milestone is integrating a real Wayland compositor and forwarding its actual output; input forwarding and XWayland/XFCE should follow after real frame capture is stable.
