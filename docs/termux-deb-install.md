# Install and test the Termux-side companion DEB

The companion can now start and stop a real Weston Wayland server using the headless backend. The Android display transport is still a separate, unfinished milestone: host discovery is not the same as an authenticated connection, and this server will not yet stream its frames into the Android app.

## Install prerequisites in Termux

```sh
pkg update
pkg install dpkg weston termux-tools
# Optional Android notifications:
pkg install termux-api
```

The Termux:API Android app must also be installed for system notifications. Without it, the CLI still prints status to the terminal.

## Build the DEB

```sh
git clone https://github.com/techwithkaushik/termux-xwayland.git
cd termux-xwayland
git checkout feat/real-wayland-server-lifecycle
bash termux-package/build-deb.sh
dpkg -i dist/termux-xwayland-bridge_0.1.0_all.deb
```

This is a local development package, not yet published to a signed apt repository.

## Start and inspect the server

```sh
termux-xwayland status
termux-xwayland start
termux-xwayland status
```

`start` verifies that the Android host APK is installed, starts Weston with `headless-backend.so`, waits for the real Wayland socket to appear, then opens the Android host deep link. It prints the runtime directory and `WAYLAND_DISPLAY` name. The process log and PID file are stored in a private runtime directory.

The headless backend provides a real Wayland server and socket for Linux Wayland clients, but does not itself display output in the Android app. Current status deliberately reports:

`Android stream connection: not established`

This is expected until an authorized, authenticated persistent frame/input transport connects Termux to the host. Do not report Connected based only on package detection, app launch, or a Wayland socket.

Stop the server with:

```sh
termux-xwayland stop
```

## Commands

- `status` / `check`: report Android host installation, Weston process/socket, and explicit transport status.
- `start`: start Weston and open the Android host.
- `server-start`: start Weston without opening the Android host.
- `stop`: stop the Weston process managed by this companion.
- `--help` / `--version`: show command usage and implementation status.

## Limitations and security

- The Android host APK must be installed separately through Android's normal APK installation path.
- Notifications are best-effort and require Termux:API; terminal output always remains available.
- The current compositor output is headless; no desktop frames or input are transported to Android yet.
- No `allow-external-apps=true`, Termux RUN_COMMAND permission, TCP listener, root or SELinux change is required.
- Direct cross-UID abstract AF_UNIX connection has failed with `EACCES` on the target Android 10 device. Do not work around that with a permanent TCP/localhost listener.
