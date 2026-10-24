# Weston output producer for the Android Surface bridge

Status: one-shot Weston capture producer and native Android frame receiver are implemented and build-verified in CI. End-to-end socket wiring, continuous capture, and physical-device rendering validation remain incomplete.

## Chosen first producer path

Use Weston 16.0's `weston_output_capture_v1` protocol as the first diagnostic producer path rather than patching the Android UI or copying pixels out of an unrelated renderer. The protocol is defined in Weston source at `protocol/weston-output-capture.xml`; its client-side implementation is exercised by Weston’s `clients/screenshot.c`. The protocol lets a Wayland client request an output capture into a compositor-writable `wl_shm` buffer and reports the output dimensions and accepted DRM formats.

This path is deliberately a **CPU-copy diagnostic transport**, not zero-copy. It can establish that the compositor's actual composed output reaches our Android Surface presenter before a more efficient DMA-BUF path is considered. Weston describes the capture factory as privileged, so the implementation must treat absence/denial of the global as an explicit capability failure, not silently fall back to an unrelated screenshot source.

## Producer responsibilities

A separate native Wayland client must:

1. Connect only to the selected `WAYLAND_DISPLAY` under the supplied `XDG_RUNTIME_DIR`.
2. Bind `wl_output`, `wl_shm`, and `weston_capture_v1` only when advertised by the compositor.
3. Create a `weston_capture_source_v1` for the chosen output using the framebuffer source.
4. Wait for `format`, `formats_done`, and `size` events before allocating a buffer. Reject unsupported formats and dimensions outside the transport's configured memory budget.
5. Allocate a writable SHM buffer with the exact format, dimensions, and 4-byte row alignment required by the protocol.
6. Submit exactly one capture at a time per capture-source object. Wait for `complete`, `retry`, or `failed` before reusing that object/buffer. Recreate the buffer when format or dimensions change.
7. Convert the advertised format to the presenter's accepted RGBA8888 layout, honoring source stride and channel order. Conversion must be explicit; never reinterpret XRGB8888 as RGBA8888.
8. Send frames through a bounded, versioned producer/consumer channel with session ID, Surface generation, sequence number, dimensions, format, stride, and byte length. Use one writer per stream and close the stream after framing errors.
9. Apply back-pressure: do not queue an unbounded number of captured frames. If the consumer is behind, skip/coalesce capture requests rather than blocking Weston or growing memory use.
10. Stop captures and release buffers/protocol objects when the output disappears or the Wayland connection closes.

## Android consumer responsibilities

The Android-side consumer must validate the session, sequence, active Surface generation, format, dimensions, stride, and maximum frame bytes before calling `xw11_presenter_present_rgba8888`. It must not call the presenter from the Android UI thread. The existing LorieView renderer and its input callbacks remain unchanged until this producer and the separate Wayland input receiver are validated together.

The presenter's RGBA API accepts CPU pixels. The output-capture SHM path therefore copies and converts pixels; it is a bring-up path only and must not be described as zero-copy or as a final performance design.

## Capability and failure behavior

- If `weston_capture_v1` is not advertised, report `capture_global_unavailable` and exit without touching the existing UI.
- If the global exists but capture is denied, report the protocol failure and exit cleanly.
- If formats are not supported by the converter, report the fourcc and exit; do not guess channel order.
- If output size exceeds the configured frame budget, reject it before allocation.
- If capture returns `retry`, rebuild using the latest advertised format/size and retry within a bounded limit.
- If the consumer socket disconnects, stop capture and free SHM/protocol resources.
- On Surface generation change, discard pending frames from the prior generation.

## Why not patch Weston’s renderer first?

Weston’s headless backend calls its renderer’s `repaint_output()` and keeps a renderer-owned renderbuffer. The renderbuffer is an opaque renderer resource; reading its pixels is not a generic public operation available to an unrelated Android process. Weston’s own output-capture protocol already provides the correct compositor-owned capture lifecycle and format/size negotiation. It is a lower-risk diagnostic producer seam than patching the renderer or assuming that a DMA-BUF can be imported by Android.

## Acceptance tests

1. Verify the running Weston advertises `weston_capture_v1`; verify the expected explicit failure when it does not.
2. Capture a known X11 test pattern and verify dimensions, stride, channel order, and corner pixels.
3. Present captured frames to an attached test Surface and verify that the displayed image changes when the test pattern changes.
4. Exercise output resize, Surface destroy/recreate, compositor restart, socket disconnect, capture denial, unsupported format, and oversized dimensions.
5. Measure frame rate and CPU cost. This path is diagnostic only; do not make it the default backend unless performance is acceptable.
6. Keep legacy Lorie/X.Org UI and input paths as fallback until output and input end-to-end tests both pass.

## Source references

- Weston 16.0 protocol: `protocol/weston-output-capture.xml`
- Weston 16.0 reference client: `clients/screenshot.c`
- Weston 16.0 capture implementation: `libweston/output-capture.c`

The protocol is a viable API seam, not proof that a particular packaged Weston build enables it or grants capture access. A one-shot producer executable now exists under `tools/weston-capture/` and is built in CI. It captures the first advertised output into writable `wl_shm` and sends one frame through the bounded XW11 output stream. It is not yet wired into the supervisor or Android JNI receiver; the socket must be connected and authenticated by an external caller, and device validation remains pending.

## Native frame contract (implemented; producer/consumer wiring pending)

The portable module `tools/native-transport/xw11_output_frame.h/.cpp` now defines a fixed 40-byte big-endian frame metadata record and validates generation, sequence, dimensions, stride, format, and byte count before conversion. It explicitly converts little-endian XRGB8888/ARGB8888 source pixels into the Android presenter's RGBA8888 byte order and supplies a host unit test for round-trip metadata, padded rows, conversion, and malformed metadata. CMake includes the module and test in the portable transport suite.

The frame metadata/conversion boundary, Weston one-shot capture client, bounded socket sender/receiver, and JNI Surface presenter now exist. They are separate components and are not yet connected by the session supervisor; therefore, do not claim that compositor frames are displayed on the Android Surface.


## One-shot producer implementation

`tools/weston-capture/capture_main.c` implements the Weston 16 capture protocol lifecycle: registry discovery, explicit failure when the privileged global is unavailable, output capture source setup, supported XRGB8888/ARGB8888 format and size negotiation, bounded SHM allocation, one capture request, and chunked output transport. The `tools/weston-capture` CMake target generates client bindings from the vendored protocol XML using `wayland-scanner`. CI builds this target against `wayland-client`.

The producer is deliberately one-shot rather than a permanent frame loop. It validates the actual compositor protocol and frame delivery seam first. It currently chooses the first output, and does not yet attach the stream to the Android Surface consumer or automatically restart capture after resize.


## Android receiver milestone (2026-10-24)

The optional Android bridge now exposes `Xw11NativeSurfaceView.attachOutputTransport(ParcelFileDescriptor, sessionId)`.
The JNI layer validates that the supplied descriptor is a connected AF_UNIX stream socket, duplicates it,
and receives output frames on a dedicated pthread rather than the UI thread. The receiver checks the
session id, Surface generation, frame dimensions/format, and strictly increasing frame sequence before
converting XRGB/ARGB memory in place and presenting it through the dedicated native Surface presenter.
Surface teardown shuts down the duplicated socket and joins the worker before detaching the Surface.

The output-frame/stream sources are now part of the Android `Xlorie` native target. The stream implementation
uses bounded C allocation rather than STL containers to preserve this target's `-nostdlib++` constraint.

**Still not end-to-end:** the session supervisor does not yet create/authorize the output socket and pass its
descriptor plus session id to the Android view; the capture producer is one-shot and still requires the
Weston capture protocol to be advertised and permitted. The existing Lorie view remains the default path.
The latest Android CI run must complete before treating this receiver integration as build-verified, and
a device test is required before claiming live rendering works.


## Continuous capture milestone (2026-10-24)

The Weston capture helper now has an opt-in `--continuous` mode. It reuses one
bounded SHM buffer, submits the next capture only after the previous capture
completes, increments the frame sequence monotonically, and stops on capture or
transport failure. One-shot remains the default for diagnostics. This does not
solve Surface socket provisioning, output resize/recreation, or device-side
validation; the supervisor still needs to establish the authenticated connected
socket and provide its descriptor/session/generation to both processes.


## Surface resize receiver lifecycle (2026-10-24)

`Xw11NativeSurfaceView` now retains a private duplicate of the authenticated output socket descriptor together with the non-zero session ID. When Android reports a changed Surface size, the view detaches the receiver for the current Surface generation before updating the native Surface, then restarts the receiver with the same session ID and the current generation. Surface destruction closes the retained descriptor and clears the session ID. This prevents stale queued receiver state from silently crossing a Surface resize and keeps ownership of the caller's descriptor unchanged.

A source-level regression test lives at `tools/tests/test_xw11_surface_resize.py`. This is receiver-side lifecycle handling only: it does not resize the Weston capture SHM buffer. The producer currently reports `capture_retry` and exits when the compositor says output parameters changed. The supervisor still needs an authenticated socket endpoint and coordinated producer restart/re-negotiation on resize before the entire lifecycle can be considered complete.
