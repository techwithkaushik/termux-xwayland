# Weston output capture helper

This directory contains a diagnostic client for Weston 16's privileged
`weston_capture_v1` protocol. It captures the first advertised `wl_output`
frame into a compositor-writable `wl_shm` buffer and sends the raw pixels over
an already-connected XW11 stream socket using the bounded output-frame protocol.
One-shot mode is the default; `--continuous` requests and sends successive
frames over the same socket with a strictly increasing sequence number.

## Build

Requires CMake 3.22+, a C/C++ compiler, `pkg-config`, `wayland-client` development
files and `wayland-scanner`.

```sh
cmake -S tools/weston-capture -B build/weston-capture
cmake --build build/weston-capture --parallel 2
```

## Run

The launcher/Android bridge must create and authenticate the connected Unix
stream socket and pass its descriptor to this helper. The helper does not create
a public socket, does not accept unauthenticated clients, and does not launch a
desktop. Arguments are the connected socket FD, non-zero XW11 session ID and
non-zero Android Surface generation:

```sh
xw11-weston-capture 7 1234 2\n# For continuous capture until the peer disconnects:\nxw11-weston-capture 7 1234 2 --continuous
```

The socket peer must use `xw11_output_stream_receive()` with the same session
and generation, then convert the captured DRM XRGB8888/ARGB8888 bytes through
`xw11_output_frame_to_rgba()` before calling the Android presenter.

## Safety and limitations

- One-shot mode sends one frame; `--continuous` sends successive captures until capture fails or the socket disconnects.\n- Continuous mode uses one bounded SHM buffer and does not queue frames.
- Fails explicitly if `weston_capture_v1` is absent or capture is denied.
- Captures the first output advertised by the compositor.
- Rejects dimensions above 8192 or 64 MiB before allocating shared memory.
- Does not touch the legacy Lorie Surface or input path.
- This helper is not yet wired into the supervisor or Android JNI receiver.
