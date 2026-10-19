# Weston headless feasibility probe

The Termux dependency inventory found Weston 16 and XWayland 24. This probe tests whether Weston can actually start its headless/pixman backend on the device and create a private Wayland socket while the XWayland module is enabled.

Run from the repository checkout:

```sh
bash tools/probe-weston-headless.sh
```

The script:
- creates a private temporary runtime directory with mode 0700;
- launches Weston with `--backend=headless --renderer=pixman --xwayland`;
- waits up to 12 seconds for the Wayland socket;
- prints the relevant logs and stops the test process on exit;
- does not install packages, use TCP, or change SELinux settings.

A successful result only establishes that Weston starts and creates its socket. It does **not** yet prove an actual Wayland client can render, that XWayland is ready for clients, or that compositor output can be streamed to the Android app. Check the logs for XWayland module startup/errors; the module may initialize asynchronously.

The probe retains its log directory and prints its path so failures can be diagnosed. Remove that temporary directory manually after inspecting it.

## Next gate

If Weston starts, test a real Wayland client under the same `XDG_RUNTIME_DIR` and `WAYLAND_DISPLAY`, then verify XWayland's X11 display and a real X11 client. Android frame export remains blocked until the cross-UID IPC design has a tested, authorized transport; do not treat this headless test as a desktop integration.
