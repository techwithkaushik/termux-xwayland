# Weston capture build gate

Run this from the Termux checkout:

```sh
bash tools/probe-weston-capture-build.sh
```

The probe is read-only. It reports the compiler, Wayland client development
metadata, scanner, protocol data and Weston development headers. Missing
`wayland-info` is not itself a blocker: a small client can enumerate Wayland
globals when Wayland client headers and libraries are available.

The critical distinction is authorization versus discovery. Weston 16 exposes
`weston_capture_v1` as a privileged single-shot capture protocol. A client can
discover the global, but cannot authorize itself. Weston has compositor-side
authorization callbacks; capture tasks also have to be serviced by the
compositor renderer/backend. See the [Weston 16 output-capture implementation](https://fossies.org/dox/weston-16.0.0/output-capture_8c_source.html) and [protocol description](https://wayland.app/protocols/weston-output-capture).

Consequently, the supported next implementation gate is:
1. Establish whether the installed Termux Weston has compatible development
   headers and capture protocol support.
2. If it does not, build a version-matched Weston 16 configuration with a
   narrowly scoped authorization policy for the local Termux session and
   verified headless renderer capture support.
3. Build a Wayland client helper against that exact protocol/build and validate
   pixel format, dimensions, stride, alpha and buffer lifetime before connecting
   it to the existing Binder FD frame transport.

Do not enable blanket screenshot authorization for arbitrary Wayland clients.
Do not claim success from a visible protocol global alone: an authorized,
successful capture and a real frame received by Android are the acceptance test.


## Build the native capture client on Termux

The installed Weston package on the target device may provide
`share/libweston-16/protocols/weston-output-capture.xml`. If the XML and
`wayland-client` development metadata are present, build the client locally
for the device's actual ABI:

```sh
bash tools/build-native-capture.sh "$PREFIX/bin/termux-xwayland-capture"
```

This generates the client protocol bindings from the installed XML and links
against the installed Wayland client library. The output is a Termux-side
helper, not a companion APK. The bridge will discover it through `PATH`.

Run the existing status and capture probe afterward. A successful build only
proves that the client compiled. A runtime `CAPTURE_STOPPED` or
`Weston capture failed` message is useful evidence that compositor policy or
the selected renderer/backend still needs work. The helper emits tightly
packed RGBA frames to stdout only after a successful capture completion; its
diagnostics go to stderr.

## Required production authorization policy

The stock capture helper is intentionally not treated as production-ready just
because it can enumerate `weston_capture_v1`. Weston 16's capture authorization
callback receives the requesting `wl_client` and the target output. The
production compositor build must install an authority that:

- defaults to deny;
- verifies the requesting client's UID/GID/PID via Wayland client credentials;
- checks the client PID's executable identity against the exact installed
  `termux-xwayland-capture` binary (not merely a process name);
- permits only the managed output created by this Termux-XWayland session;
- rejects all other clients and all unexpected outputs;
- is covered by a negative test proving a normal Wayland client is denied.

The authority must be compiled into, or loaded by, the same version-matched
Weston/libweston build that serves the `wayland-1` socket. A client-side option,
a shell variable, or changing the protocol request cannot grant capture rights.
Do not use `weston --debug` as a production substitute: that is a broad debug
mode, not the narrow per-client policy described above.

## End-to-end acceptance criteria

A release is not considered live-desktop capable until all of the following
are evidenced on the target Android device:

1. A real X11 client (for example xterm) is visible in the Weston output.
2. The capture helper reports successful capture completions without
   `unauthorized` or synthetic-frame code paths.
3. The Binder session log reports repeated `FRAME_SENT` events with increasing
   counters while the desktop changes.
4. The Android host's received-frame counter increases and the visible image
   changes when the user moves a window or types.
5. The capture helper exits cleanly when the Binder session is stopped.
6. A non-helper Wayland client remains denied capture.

CI can validate source, package contents and protocol/unit tests, but the
physical-device checks above cannot be inferred from a green CI run.
