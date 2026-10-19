# Termux workflow

## Current milestone

The native binary now provides a **single-client, authenticated loopback handshake smoke test** and the Android app can connect to it. This is not a compositor: no Wayland socket, frame stream, input forwarding, XWayland, XFCE or LXQt session is available yet.

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

For the current development branch, use `feat/phase1-android-host` until its PR is merged.

## Start a local authenticated test session

Generate a fresh 256-bit token for each run and start the server. The token is printed only so you can manually enter it in the Android app; do not share screenshots or logs containing it.

    export TERMUX_XWAYLAND_TOKEN_HEX="$(od -An -N32 -tx1 /dev/urandom | tr -d ' \n')"
    export TERMUX_XWAYLAND_PORT=38765
    printf 'Session token: %s\n' "$TERMUX_XWAYLAND_TOKEN_HEX"
    ./target/release/termux-xwayland-compositor

The server binds only to `127.0.0.1`. In the Android app, enter host `127.0.0.1`, port `38765`, and the session token, then tap **Connect securely**. The UI should report **Bridge authenticated** only after the token handshake succeeds. Tap **Disconnect** to close the session. The token is held in the shell environment for this test run; unset it when finished with `unset TERMUX_XWAYLAND_TOKEN_HEX`.

If connection fails, check that the server is running, the port matches, and the token has exactly 64 hexadecimal characters. Do not expose the server on `0.0.0.0` or a LAN interface as a workaround.

## What this test proves

It validates loopback connectivity on the device and the versioned authenticated handshake only. It does not display Linux applications. The next milestone is a real Wayland compositor plus bounded frame delivery and Android `SurfaceView` rendering; only after that should input forwarding and XWayland/XFCE be added.
