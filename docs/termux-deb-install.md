# Install and test the Termux-side companion DEB

This first package is intentionally small: it installs a Termux command that checks for the Android host and can open it. It does **not** claim to start a compositor or stream frames; those depend on the IPC/FD handoff milestone.

## Build the DEB

In Termux, install the packaging tool if needed:

```sh
pkg update
pkg install dpkg
git clone https://github.com/techwithkaushik/termux-xwayland.git
cd termux-xwayland
git checkout feat/phase1-android-host
bash termux-package/build-deb.sh
```

The output is `dist/termux-xwayland-bridge_0.1.0_all.deb`.

## Install it as a Termux package

```sh
dpkg -i dist/termux-xwayland-bridge_0.1.0_all.deb
termux-xwayland status
termux-xwayland start
```

This installs the command into Termux's own prefix using dpkg; it does not install or replace the Android APK. The Android host APK must be installed separately through Android's normal APK installation path.

## What the test checks

- `status`/`check`: calls Android's package manager for `org.techwithkaushik.termuxxwayland` and reports installed/not detected.
- `start`: checks for the host first and requests its explicit launcher activity.
- `stop`: deliberately reports unsupported until a real persistent session controller exists.

The package-manager query can be filtered by Android package-visibility rules on some builds. If it reports not detected while the APK is installed, verify the exact package ID in Android Settings and share the output; do not interpret the fallback as proof that the APK is absent.

No `allow-external-apps=true`, Termux RUN_COMMAND permission, TCP listener, root or SELinux change is required. This first DEB is a local development package, not yet published to a signed apt repository.
