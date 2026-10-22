# XWayland replacement plan

## Goal

Replace the current Lorie-specific X.Org server build with a genuine upstream XWayland backend. This is a backend replacement, not a rename of the existing `Xlorie` library or executable.

## Verified current build facts

- `lorie/src/main/cpp/CMakeLists.txt` includes `recipes/xserver.cmake`.
- `recipes/xserver.cmake` assembles X.Org server core libraries and links them into the Lorie Android native implementation. It does not currently build upstream `hw/xwayland`.
- The current Android rendering path includes Lorie-specific screen setup and EGL/GLES rendering. That path must not be assumed to be the XWayland backend.
- The `.gitmodules` entry points `lorie/src/main/cpp/xserver` to `https://gitlab.freedesktop.org/xorg/xserver.git`; `termux-x11-port` pins it at `65d790bd208ec380b196eb98f144abb0b32e334d`.
- CI on 2026-10-22 confirmed that this pinned gitlink does not contain top-level `meson.build` or `hw/xwayland/meson.build`. It cannot directly provide the Meson-based XWayland backend.
- The official standalone XWayland release archive `xwayland-24.1.14.tar.xz` is available from the X.Org release directory. The repository now has `tools/fetch-xwayland-source.sh`, which fetches this pinned release over HTTPS, checks archive paths, extracts it under `build/third_party/xwayland-24.1.14`, and validates the version and required Meson files.
- The source fetch script verifies the archive against the fixed SHA-256 `4eb6b98d678299a4b96138d886345b294130695760345ef84596225a8224078a`, taken from the official Termux XWayland 24.1.14 package recipe, before extraction. Detached OpenPGP release-signature verification is still not configured, so the checksum trust root remains the reviewed repository pin.
- The current Android native build is CMake-based, while upstream XWayland uses Meson. The integration must explicitly bridge this build-system boundary.

## Target architecture

```text
X11 applications
      |
      v
Upstream XWayland (X11 server + hw/xwayland backend)
      |
      | Wayland client protocol: surfaces, buffers, input
      v
Wayland compositor (Weston or another compatible compositor)
      |
      v
Android native display/output integration
```

## Current source-validation workflow

The workflow `.github/workflows/xwayland-preflight.yml` now:
1. Checks shell-script syntax and Python syntax.
2. Runs unit tests for the Android Meson cross-file generator against a synthetic NDK layout for all four Android ABIs.
3. Tests `tools/check-xwayland-android-prefix.py` by compiling a host shared library and verifying that the guard rejects it in an ARM staging prefix.
3. Diagnoses the legacy submodule source, but does not let that known blocker mask the separate release-source check.
4. Fetches the pinned official XWayland 24.1.14 release and verifies the Meson build files and embedded version.
5. Audits all 19 Meson option names and reports host dependency availability independently of the known legacy-submodule blocker.

Run locally:

```sh
bash tools/fetch-xwayland-source.sh
python3 tools/generate-android-meson-cross-file.py \\
  --abi armeabi-v7a --api 24 \\
  --ndk "$ANDROID_NDK_HOME" \\
  --prefix "$PWD/build/android-stage/armeabi-v7a" \\
  --output "$PWD/build/meson/armeabi-v7a.ini"
```

The cross-file generator validates the NDK toolchain and target staging prefix, writes an Android Meson cross file, and creates an isolated `pkg-config` wrapper that only searches the supplied staging prefix and avoids applying the NDK sysroot a second time to staged dependency paths. The staging prefix must contain dependencies already built for the selected ABI. This is configuration scaffolding, not an XWayland cross-build.

The explicit build driver `tools/build-xwayland-android.sh` now wires these steps together: it validates the requested ABI/API and staged prefix, first audits ELF machine types under the prefix to reject host-architecture shared libraries, prepares the cross file outside the Meson build directory, configures XWayland with the Termux-derived Meson options, applies the Termux recipe's Android compiler/linker flags (`-fcommon`, `-fPIC`, Android `FNDELAY` compatibility, the staged libdrm include directory, and `-landroid-shmem`), runs `meson compile`, and verifies that Meson target metadata includes `Xwayland`. If an explicitly supplied `--source` path is invalid, the driver now fails directly instead of fetching source to a different default directory. Example (after dependencies have been staged for 32-bit ARM):

```sh
TERMUX_PREFIX="$PREFIX" bash tools/build-xwayland-android.sh \\
  --abi armeabi-v7a --api 24 --ndk "$ANDROID_NDK_HOME" \\
  --prefix "$PWD/build/android-stage/armeabi-v7a" \\
  --font-path "$PREFIX/share/fonts"
```

This driver is not itself evidence of a successful Android build: it cannot proceed until the prefix contains ABI-correct libraries and `.pc` metadata for every enabled XWayland dependency.

The script downloads source into `build/third_party`; it does not modify the existing native target. The release archive should be excluded from commits/build outputs as appropriate.

## Migration stages

### Stage 1 — establish a genuine upstream build

1. Validate the pinned standalone XWayland source; checksum verification is implemented, while detached release-signature verification remains outstanding.
2. Use the official Termux XWayland 24.1.14 recipe as the concrete dependency/options baseline. Target dependencies are `libandroid-shmem`, `libdrm`, `libepoxy`, `libpciaccess`, `libpixman`, `libwayland`, `libwayland-protocols`, `libx11`, `libxau`, `libxcvt`, `libxfont2`, `libxinerama`, `libxkbfile`, `libxshmfence`, OpenGL, OpenSSL, `xkeyboard-config`, `xorg-protocol-txt`, and `xorg-xkbcomp`; build dependency: `libwayland-cross-scanner`. The recipe explicitly configures `mitshm`, `xres`, `xv`, `screensaver`, `xdmcp`, `glx`, `dri3`, `xinerama`, `xace`, `xcsecurity`, `xf86bigfont`, `drm`, and IPv6 on; `secure-rpc`, `glamor`, `xvfb`, and `libunwind` off; SHA-1 via `libcrypto`; and the default font path under `$TERMUX_PREFIX/share/fonts`. The Android build must use target-ABI dependencies and a Wayland cross-pkg-config wrapper; host pkg-config results are not sufficient.
3. Build target-ABI Wayland client/protocol and X server dependencies with the Android NDK; never use host libraries for an Android target.
4. Add an Android Meson cross file and a separate, explicit XWayland build target without deleting the working Lorie target.
5. Build for supported Android ABIs in CI.

**Exit criteria:** CI proves that the upstream XWayland backend is compiled and linked for the target ABI. Merely compiling X.Org core or producing a library named `Xwayland` does not satisfy this stage.

### Stage 2 — connect to a Wayland compositor

1. Start or connect to a compositor and pass its Wayland socket to XWayland.
2. Implement the XWayland startup handshake and X11 display socket/readiness checks.
3. Verify a minimal X11 client can connect and create a window.
4. Keep server startup separate from launching a desktop environment.

**Exit criteria:** an X11 test client runs through XWayland with no Lorie DDX in the active server path.

### Stage 3 — Android presentation and input

1. Decide and document the compositor output backend that presents frames on Android.
2. Route Android touch, mouse, keyboard, focus, resize, clipboard, and lifecycle events through the appropriate Wayland/XWayland paths.
3. Validate frame pacing, buffer lifetime, synchronization, and teardown before optimizing.

**Exit criteria:** visible output and working input on a real Android device, including repeated start/stop and resize tests.

### Stage 4 — remove the legacy backend

Only after Stages 1–3 pass, remove obsolete Lorie-specific X server build wiring and native rendering code.

## Guardrails

- Do not rename `Xlorie` and call it XWayland.
- Do not delete the current build before the replacement builds and passes smoke tests.
- Do not claim Android rendering works based only on a successful native compile.
- Keep XWayland, compositor/output integration, and Android UI as separate modules with explicit interfaces.
- Validate 32-bit ARM and other supported ABIs before changing release defaults.

## Validation checklist

- [x] Archive SHA-256 is checked against the official Termux 24.1.14 package recipe.
- [ ] Detached release signature is verified against a trusted X.Org signing key.
- [ ] Android Meson cross-build succeeds for target ABI(s).
- [ ] Compositor connection and XWayland startup handshake succeed.
- [ ] X11 socket becomes ready and a minimal client connects.
- [ ] Window contents are visibly presented on Android.
- [ ] Pointer, keyboard, touch, resize, clipboard, and focus are tested.
- [ ] Stop/restart and error cleanup are tested.
- [ ] CI passes; device validation is reported separately from CI.

## Current status

The latest source-preflight workflow run [39775947199](https://github.com/techwithkaushik/termux-xwayland/actions/runs/39775947199) succeeded on commit `4105f3ada34dc799a919a4f3900cbd741ed702d5`. Its shell syntax/help checks, Python compilation and unit tests, standalone release fetch/layout validation, all 19 Meson option-presence checks, and informational host-dependency report completed successfully. The legacy-source diagnostic remains intentionally non-blocking because the pinned gitlink lacks the standalone Meson XWayland backend.

The target-prefix architecture guard, host-library rejection regression test, malformed-ELF regression test, and test for shared-library symlinks escaping the staging prefix have been added. The latest symlink-hardening changes are awaiting CI; they are not yet covered by the successful run cited above. This guard checks ELF machine types of shared libraries only; it does not validate static archives, dependency completeness, ABI compatibility beyond machine type, or linkability.

The Android Meson build driver and cross-file generator are scaffolding only until a target-ABI staging prefix is populated. No real Android target build, compositor handshake, X11 client session, or device rendering/input test has been completed. Next work is to make target dependency staging reproducible, run the actual cross-build (prioritizing `armeabi-v7a`), then validate compositor/XWayland startup on the Android device. Detached release-signature verification is also outstanding. Keep this PR Draft and preserve the existing `Xlorie` backend until those acceptance tests pass.
