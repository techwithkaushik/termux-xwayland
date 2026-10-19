# Termux workflow (planned)

The target workflow is to start a desktop from Termux and display it in the Termux XWayland Android app.

## Current status

The repository contains an Android UI shell and a Rust scaffold only. There is no working Wayland socket, display bridge, XWayland integration or desktop launcher yet. The commands below describe the intended environment, not a working end-to-end setup.

## Environment preparation

    pkg update
    pkg install git rust clang pkg-config libxkbcommon

Desktop package availability depends on the chosen Termux repository or Linux PRoot distribution. No package installation should be silently performed by the Android app.

## Intended workflow after implementation

1. Start the compositor/bridge from Termux.
2. Open the Android app and approve first-time pairing if required.
3. In a Termux session configured with the compositor's WAYLAND_DISPLAY, launch a native Wayland test client.
4. After XWayland integration, start an X11 client; then test the XFCE session.
5. Stop the session from Termux or the app's explicit disconnect control.

Do not run xfce4-session expecting it to appear in this early scaffold.
