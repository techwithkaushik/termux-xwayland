# Weston output capture helper

This directory contains a diagnostic client for Weston's privileged `weston_capture_v1`
protocol. It captures the first advertised `wl_output` frame into a compositor-writable
`wl_shm` buffer and sends bounded XW11 output frames.

## Build

Requires CMake 3.22+, a C/C++ compiler, `pkg-config`, `wayland-client` development
files and `wayland-scanner`.

```sh
cmake -S tools/weston-capture -B build/weston-capture
cmake --build build/weston-capture --parallel 2
install -m 755 build/weston-capture/xw11-weston-capture "$PREFIX/bin/xw11-weston-capture"
```

## Run

The supervisor starts the Android `MainActivity` with a random per-session token and
abstract Unix socket name. The producer proves the token before sending frames:

```sh
xw11-weston-capture --connect xw11-output-SESSION TOKEN SESSION_ID 1 --continuous
```

For a trusted launcher that already owns a connected Unix stream socket, the legacy
descriptor form remains available:

```sh
xw11-weston-capture 7 1234 1 --continuous
```

The Android app consumes the authentication line, makes its output-only Surface visible,
and replies with its live generation (`XW11READY`). The producer uses that negotiated
value before sending frames. Native validates session, surface generation, frame metadata
and sequence numbers, converts XRGB/ARGB to RGBA, and presents to the dedicated MainActivity
Surface. The existing Lorie input/touch/keyboard path is separate.

On-device convenience build: `bash tools/build-weston-capture-termux.sh` (run from this
repository in Termux after installing the required CMake/pkg-config/Wayland development tools).

## Safety and limitations

- Uses a random 256-bit token and one-session abstract socket; socket name alone is not trusted.
- Continuous mode uses one bounded SHM capture buffer and does not queue frames.
- Fails explicitly if `weston_capture_v1` is unavailable or capture is denied.
- Captures the first output advertised by the compositor.
- Rejects dimensions above 8192 or 64 MiB before allocating shared memory.
- Surface generation currently assumes the newly-created output-only view's first generation is 1; lifecycle recreation requires restarting the producer with the new generation.
- Android/device end-to-end rendering must still be verified on the target device.
