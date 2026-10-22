# XWayland replacement plan

## Goal

Replace the current Lorie-specific X.Org server build with the upstream XWayland server backend. This is a backend replacement, not a rename of the existing `Xlorie` library or executable.

## Current build facts

- `lorie/src/main/cpp/CMakeLists.txt` includes `recipes/xserver.cmake`.
- `recipes/xserver.cmake` assembles X.Org server core libraries (DIX, MI, FB, DRI3, EXA, Present, and extensions) and links them into the Lorie Android native implementation.
- The current Android rendering path includes Lorie-specific screen setup and EGL/GLES rendering. That path must not be assumed to be the XWayland backend.
- A real XWayland build needs the upstream `hw/xwayland` backend, its Meson configuration, Wayland client/protocol dependencies, and a compatible Wayland compositor connection.

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

## Migration stages

### Stage 1 — establish a genuine upstream build

1. Record the X server submodule URL and pinned commit used by this branch.
2. Select a compatible upstream XWayland/X server revision and inspect its `hw/xwayland` Meson options and dependencies.
3. Verify which dependencies are already vendored and which need Termux/Android-compatible builds.
4. Add a separate, explicit XWayland build target without deleting the working Lorie target yet.
5. Build for the repository's supported Android ABI(s) in CI.

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

- [ ] XWayland upstream backend is part of the build graph.
- [ ] Native build succeeds for target Android ABI(s).
- [ ] Compositor connection and XWayland startup handshake succeed.
- [ ] X11 socket becomes ready and a minimal client connects.
- [ ] Window contents are visibly presented on Android.
- [ ] Pointer, keyboard, touch, resize, clipboard, and focus are tested.
- [ ] Stop/restart and error cleanup are tested.
- [ ] CI passes; device validation is reported separately from CI.

## Current status

This change starts the migration plan and acceptance criteria. It does not yet claim that XWayland is built, linked, or running. Implementation should proceed stage by stage, with the first code change establishing the pinned upstream source and a separate build target.
