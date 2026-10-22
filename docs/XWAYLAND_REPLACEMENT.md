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
1. Checks both shell scripts' syntax.
2. Diagnoses the legacy submodule source, but does not let that known blocker mask the separate release-source check.
3. Fetches the pinned official XWayland 24.1.14 release and verifies the Meson build files and embedded version.
4. Reports host dependency availability as informational only.

Run locally:

```sh
bash tools/fetch-xwayland-source.sh
```

The script downloads source into `build/third_party`; it does not modify the existing native target. The release archive should be excluded from commits/build outputs as appropriate.

## Migration stages

### Stage 1 — establish a genuine upstream build

1. Validate the pinned standalone XWayland source; checksum verification is implemented, while detached release-signature verification remains outstanding.
2. Use the official Termux XWayland 24.1.14 recipe as the concrete dependency/options baseline: `libandroid-shmem`, `libdrm`, `libepoxy`, `libpciaccess`, `libpixman`, `libwayland`, `libwayland-protocols`, `libx11`, `libxau`, `libxcvt`, `libxfont2`, `libxinerama`, `libxkbfile`, `libxshmfence`, OpenGL, OpenSSL, `xkeyboard-config`, `xorg-protocol-txt`, and `xorg-xkbcomp`; use the Termux Wayland cross-scanner. The recipe disables glamor and configures the other XWayland features explicitly.
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

The legacy gitlink source check failed for the expected reason. A separate, version-pinned standalone XWayland 24.1.14 source-fetch/structure-validation path has been added to CI, including a fixed SHA-256 check from the official Termux package recipe. The workflow now runs for relevant pull-request changes (or manual dispatch), not for every push to the feature branch, avoiding duplicate push-plus-PR runs. The updated workflow run is not yet verified. No Android native build or device runtime test has been claimed. Next: verify the new workflow, add detached signature verification, then implement the Android Meson cross-build and target-ABI dependency graph.
