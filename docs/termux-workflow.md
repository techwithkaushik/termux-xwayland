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

The server listens on a session-specific abstract AF_UNIX socket derived from the first 8 token bytes of the token. In the Android app, enter the 64-character session token and tap **Connect securely**. No host, port, Wi-Fi, localhost TCP listener or Internet permission is needed. The UI should report **Test frame received** after the handshake succeeds and the synthetic test pattern is rendered. Tap **Disconnect** to close the session. When finished, stop the server with Ctrl+C and unset the token with `unset TERMUX_XWAYLAND_TOKEN_HEX`.

If connection fails, verify that the server is running and the token has exactly 64 hexadecimal characters. Abstract-socket access between separate Android app UIDs may be affected by device SELinux policy. This needs on-device testing; do not expose a TCP listener as a workaround.

## What this test proves

It validates local AF_UNIX socket connection, the versioned authenticated handshake, bounded frame delivery and Android bitmap rendering. It does not display Linux applications. The next milestone is integrating a real Wayland compositor and forwarding its actual output; input forwarding and XWayland/XFCE should follow after real frame capture is stable.
