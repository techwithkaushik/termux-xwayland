# Weston output capture helper

This directory contains the native producer for Weston's `weston_capture_v1`
protocol. It captures output frames into a bounded `wl_shm` buffer and sends XW11
frames to the Android output-only Surface.

## Build

Requires CMake 3.22+, a C/C++ compiler, `pkg-config`, `wayland-client` development
files and `wayland-scanner`.

```sh
cmake -S tools/weston-capture -B "${TMPDIR:-$PREFIX/tmp}/xw11-weston-capture-build"
cmake --build "${TMPDIR:-$PREFIX/tmp}/xw11-weston-capture-build" --parallel 2
install -m 755 "${TMPDIR:-$PREFIX/tmp}/xw11-weston-capture-build/xw11-weston-capture" "$PREFIX/bin/xw11-weston-capture"
```

On-device convenience build: `bash tools/build-weston-capture-termux.sh`.

## Android output transport

The supported supervisor path launches a Termux-side `app_process` Binder broker.
The Android app asks that broker for a connected stream descriptor; the broker creates
an `AF_UNIX` socketpair, launches this capture helper with one endpoint, and returns
the other endpoint as a `ParcelFileDescriptor` through Binder. The capture helper does
not connect directly to an Android abstract socket, so this path avoids the SELinux
cross-domain `connectto` denial without disabling SELinux or using TCP.

The inherited-descriptor mode is:

```sh
xw11-weston-capture CONNECTED_SOCKET_FD SESSION_ID SURFACE_GENERATION --continuous
```

The descriptor is a capability supplied by the trusted Binder broker. The helper
validates that it is a connected `AF_UNIX` stream before connecting to the private
Wayland socket. In broker mode it waits for `XW11PRESENTED` from Android after the first
frame has actually been presented; `frame_sent=1` therefore represents a successful
first-frame presentation acknowledgement, not merely a successful socket write. The Weston capture-authority module permits capture only for the helper
PID published in `XW11_CAPTURE_PID_FILE` and matching the compositor UID.

The old authenticated abstract-socket `--connect` mode remains available for diagnostics,
but it is not used by the supervisor's Android output path.

Native Android code validates session, Surface generation, frame metadata and sequence
numbers, converts XRGB/ARGB to RGBA, and presents to the dedicated MainActivity Surface.
The existing Lorie input/touch/keyboard path is separate.

## Safety and limitations

- Binder transfers the connected FD capability; no TCP or SELinux permissive workaround.
- Continuous mode uses one bounded SHM capture buffer and does not queue frames.
- Fails explicitly if `weston_capture_v1` is unavailable or capture is denied.
- Captures the first output advertised by the compositor.
- Rejects dimensions above 8192 or 64 MiB before allocating shared memory.
- Surface recreation and target-device end-to-end behavior must be verified on-device.
