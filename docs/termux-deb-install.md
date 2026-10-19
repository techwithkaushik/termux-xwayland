# Install and test the Termux-side companion DEB

The companion starts Weston with the headless backend, waits for the Wayland socket, opens the Android host, and exposes a **read-only status endpoint bound to 127.0.0.1**. The host polls that endpoint while foregrounded and reports whether the Weston process and socket are still present. This is status detection only; desktop frames, input forwarding, and XWayland integration are not implemented yet.

## Install prerequisites in Termux

```sh
pkg update
pkg install dpkg weston termux-tools socat
# Optional Android notifications:
pkg install termux-api
```

The Termux:API Android app must also be installed for system notifications. Without it, the CLI still prints status to the terminal.

## Obtain the DEB

The CI workflow builds `termux-xwayland-bridge_0.3.0_all.deb` and publishes it as the `termux-xwayland-bridge-deb` artifact after the native checks pass. Download the artifact ZIP from the successful run, then copy the DEB to Termux.

For a local development build:

```sh
git clone https://github.com/techwithkaushik/termux-xwayland.git
cd termux-xwayland
git checkout feat/termux-host-discovery
bash termux-package/build-deb.sh
```

Install/update it in Termux:

```sh
dpkg -i dist/termux-xwayland-bridge_0.3.0_all.deb
```

This is a development package, not yet published to a signed apt repository. Ensure `socat` is installed before starting; the status monitor depends on it.

## Start and inspect the server

```sh
termux-xwayland status
termux-xwayland start
termux-xwayland status
```

`start` verifies that the Android host APK is installed, starts Weston, waits for the Wayland socket, starts a status endpoint bound only to `127.0.0.1`, then opens the Android host with a session identifier and monitor port. The host shows **“Termux-Companion connection received”** and polls the endpoint approximately every two seconds while the app is in the foreground. Reopening the host rechecks the stored session.

The status endpoint only answers whether the matching session's Weston process and Wayland socket are running. It carries no frame or input data and is not a desktop transport. The host UI changes to a stopped or unreachable state when appropriate.

Stop Weston with:

```sh
termux-xwayland stop
```

The status monitor remains available after an explicit stop so the open host can receive a `STOPPED` response. Starting a new session replaces the previous monitor. To remove the monitor manually, run the internal cleanup command:

```sh
termux-xwayland monitor-stop
```

## Commands

- `status` / `check`: report host installation, Weston process/socket, and whether the host is polling.
- `start`: start Weston, start the loopback status endpoint, then open the Android host.
- `server-start`: start Weston without opening the Android host.
- `stop`: stop the Weston process managed by this companion.
- `--help` / `--version`: show command usage and implementation status.

## Limitations and security

- Install the Android host APK through Android's normal APK installation path.
- Notifications are best-effort and require Termux:API; terminal output always remains available.
- The status listener binds only to `127.0.0.1`, reports only server state, and must never carry desktop frames or input.
- No `allow-external-apps=true`, Termux RUN_COMMAND permission, root, or SELinux change is required.
- Direct cross-UID abstract AF_UNIX connection failed with `EACCES` on the target Android 10 device. That transport remains disabled; the loopback endpoint is only for low-risk status polling.
