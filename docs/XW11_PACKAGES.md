# Termux-XW11 packages

Termux-XW11 follows the same two-part distribution model as Termux:X11:

1. An Android APK (`Termux-XW11`) that provides the host UI.
2. A Termux companion Debian package (`termux-xw11-nightly`) that installs the `termux-xw11` and `termux-xw11-preference` commands plus the loader APK into the Termux prefix.

The companion package is architecture-independent (`Architecture: all`). The XWayland server binary is a separate native Termux package and must be integrated into the runtime before this project can be considered a working XWayland replacement.

## Download CI packages

Open the [latest XW11 Android package workflow](https://github.com/techwithkaushik/termux-xwayland/actions/workflows/xw11-android-build.yml) and select a successful run on `xwayland/replace-xorg`.

Download these artifacts:
- `termux-xw11-main-apk` — standalone APK for ordinary installation.
- `termux-xw11-shareduid-apk` — optional shared-UID APK; only use it with a matching Termux installation/signature.
- `termux-xw11-launcher-package` — contains `termux-xw11-nightly-*-all.deb` and the Arch companion package.
- `termux-xw11-loader-apk` — diagnostic artifact; normally the loader inside the companion package is used.

GitHub Actions artifacts download as ZIP files. Extract the ZIP on the device or on a computer first.

## Install

1. Install `termux-xw11-universal-debug.apk` on Android.
2. Copy the `termux-xw11-nightly-*-all.deb` file into Termux's accessible storage, for example after using `termux-setup-storage`.
3. From Termux, change to the directory containing the downloaded package and install it:

```sh
dpkg-deb -f ./termux-xw11-nightly-*-all.deb Package Version Architecture Depends
apt install ./termux-xw11-nightly-*-all.deb
```

If your Termux `apt` version does not accept a local path, use:

```sh
dpkg -i ./termux-xw11-nightly-*-all.deb
apt --fix-broken install
```

The CI workflow validates the package metadata, expected file paths, executable launcher scripts, embedded loader APK, and SHA-256 before publishing the artifact.

## Important backend status

**The current APK/package build is a packaging milestone, not a completed XWayland migration.** The Android app still contains the legacy Lorie renderer and its current loader entry point. The standalone upstream XWayland `.deb` produced by the separate XWayland package workflow is not yet wired to a tested Android presentation/input transport. Installing the companion package therefore does not prove that Weston/XWayland output will be visible in the Android app.

Do not remove the legacy backend or treat the app as a functional replacement until the following have been tested on a real device:

- XWayland starts against the intended Wayland compositor and its X11 socket becomes ready.
- An X11 test window is visibly presented on Android.
- Pointer, touch, keyboard, resize, and focus events work responsively.
- Start/stop, surface recreation, and compositor disconnect are handled safely.

The implementation plan and acceptance checklist are tracked in [Issue #24](https://github.com/techwithkaushik/termux-xwayland/issues/24) and [XWayland replacement notes](./XWAYLAND_REPLACEMENT.md).
