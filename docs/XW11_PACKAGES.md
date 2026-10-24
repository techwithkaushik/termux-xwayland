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
- `termux-xw11-deb` — the validated `.deb` plus a manifest with package metadata and SHA-256; use this for straightforward Termux installation.
- `termux-xw11-launcher-package` — contains the `.deb` and Arch companion package together.
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

**The Binder-based output FD path is implemented on `xwayland/replace-xorg`, but the complete migration is not yet device-verified.** The Android app retains the existing Lorie Surface/input implementation; the new supervisor launches a Termux-side Binder broker, passes a connected AF_UNIX descriptor to the capture helper, and waits for an Android first-frame presentation acknowledgement. Installing the companion package or passing host-side checks does not prove that the device's SELinux policy, Weston module, APK, and Surface lifecycle work together.

Do not remove the legacy backend or treat the app as a functional replacement until the following have been tested on a real device:

- XWayland starts against the intended Wayland compositor and its X11 socket becomes ready.
- An X11 test window is visibly presented on Android.
- Pointer, touch, keyboard, resize, and focus events work responsively.
- Start/stop, surface recreation, and compositor disconnect are handled safely.

The implementation plan and acceptance checklist are tracked in [Issue #24](https://github.com/techwithkaushik/termux-xwayland/issues/24) and [XWayland replacement notes](./XWAYLAND_REPLACEMENT.md).
