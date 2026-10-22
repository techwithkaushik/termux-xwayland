# XWayland replacement plan

## Goal

Replace the current Lorie-specific X.Org server build with the upstream XWayland server backend. This is a backend replacement, not a rename of the existing `Xlorie` library or executable.

## Verified current build facts

- `lorie/src/main/cpp/CMakeLists.txt` includes `recipes/xserver.cmake`.
- `recipes/xserver.cmake` assembles X.Org server core libraries and links them into the Lorie Android native implementation. It does not currently build the upstream `hw/xwayland` backend.
- The current Android rendering path includes Lorie-specific screen setup and EGL/GLES rendering. That path must not be assumed to be the XWayland backend.
- The `.gitmodules` entry points `lorie/src/main/cpp/xserver` to `https://gitlab.freedesktop.org/xorg/xserver.git`; the `termux-x11-port` tree pins that submodule at `65d790bd208ec380b196eb98f144abb0b32e334d`.
- **CI verified on 2026-10-22 that this pinned source does not contain either top-level `meson.build` or `hw/xwayland/meson.build`.** The current pin therefore cannot be used for a Meson-based upstream XWayland build as-is. This is a source revision blocker, not an Android dependency failure.
- The current top-level native build is CMake-based, while upstream X server/XWayland uses Meson. The integration must explicitly bridge this build-system boundary rather than assuming CMake's existing source lists build `hw/xwayland`.
- Upstream XWayland needs Wayland client/protocol dependencies and a compatible Wayland compositor connection. Android target-ABI availability must be proven rather than inferred from host packages.

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

## First implementation change: source/dependency preflight

The branch includes `tools/probe-xwayland-source.sh`. Run it from a checkout with submodules initialized:

```sh
bash tools/probe-xwayland-source.sh
```

The CI workflow checks script syntax, initializes recursive submodules, and checks for both Meson files. The workflow currently fails at the source check because the pinned source is too old for this integration. This failure is intentional and must not be hidden or converted into a successful build result.

## Migration stages

### Stage 1 — establish a genuine upstream build

1. Choose and pin a specific upstream X server revision that actually contains `meson.build` and `hw/xwayland/meson.build`.
2. Review the source revision's release/commit and Meson options before updating the gitlink; do not blindly move to an arbitrary development head.
3. Verify which dependencies are already vendored and which need Android/NDK-compatible builds.
4. Add an Android Meson cross file and a separate, explicit XWayland build target without deleting the working Lorie target.
5. Build for the project's supported Android ABI(s) in CI.

**Exit criteria:** CI proves the upstream XWayland backend is compiled and linked. Merely compiling X.Org core or producing a library named `Xwayland` does not satisfy this stage.

### Stage 2 — connect to a Wayland compositor

1. Start or connect to a compositor and pass its Wayland socket to XWayland.
2. Implement the required XWayland startup handshake and X11 display socket/readiness checks.
3. Verify a minimal X11 client can connect and create a window.
4. Keep server startup separate from launching a desktop environment.

**Exit criteria:** an X11 test client runs through XWayland with no Lorie DDX in the active server path.

### Stage 3 — Android presentation and input

1. Decide and document the compositor output backend that presents frames on Android.
2. Route Android touch, mouse, keyboard, focus, resize, clipboard, and lifecycle events through the appropriate Wayland/XWayland paths.
3. Validate frame pacing, buffer lifetime, synchronization, and teardown before optimizing.

**Exit criteria:** visible output and working input on a real Android device, including repeated start/stop and resize tests.

### Stage 4 — remove the legacy backend

Only after Stages 1–3 pass, remove obsolete Lorie-specific X server build wiring and native rendering code. Keep unrelated application/UI code only where it remains necessary for Android integration.

## Guardrails

- Do not rename `Xlorie` and call it XWayland.
- Do not delete the current build before the replacement builds and passes smoke tests.
- Do not claim Android rendering works based only on a successful native compile.
- Keep XWayland, compositor/output integration, and Android UI as separate modules with explicit interfaces.
- Validate 32-bit ARM and the project's other supported ABIs before changing release defaults.

## Validation checklist

- [ ] Pin an upstream revision containing the XWayland Meson backend.
- [ ] Android Meson cross-build succeeds for target ABI(s).
- [ ] Compositor connection and XWayland startup handshake succeed.
- [ ] X11 socket becomes ready and a minimal client connects.
- [ ] Window contents are visibly presented on Android.
- [ ] Pointer, keyboard, touch, resize, clipboard, and focus are tested.
- [ ] Stop/restart and error cleanup are tested.
- [ ] CI passes; device validation is reported separately from CI.

## Current status

The source preflight workflow ran on 2026-10-22 and correctly failed because the pinned X server revision `65d790bd208ec380b196eb98f144abb0b32e334d` lacks the required Meson/XWayland source files. No Android XWayland build or device runtime test has been claimed. The next code-changing prerequisite is selecting and pinning a verified upstream source revision; then the Android cross-build can be implemented against that exact source.
