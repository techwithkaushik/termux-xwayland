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
