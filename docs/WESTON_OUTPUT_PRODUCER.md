# Weston output producer for the Android Surface bridge

Status: implementation design backed by the Weston 16.0 source/API audit. This document does not claim that the producer is already implemented or running.

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

The protocol is a viable API seam, not proof that a particular packaged Weston build enables it or grants capture access. The producer executable, transport receiver, and device validation remain to be implemented.

## Native frame contract (implemented; producer/consumer wiring pending)

The portable module `tools/native-transport/xw11_output_frame.h/.cpp` now defines a fixed 40-byte big-endian frame metadata record and validates generation, sequence, dimensions, stride, format, and byte count before conversion. It explicitly converts little-endian XRGB8888/ARGB8888 source pixels into the Android presenter's RGBA8888 byte order and supplies a host unit test for round-trip metadata, padded rows, conversion, and malformed metadata. CMake includes the module and test in the portable transport suite.

This is a real implementation of the frame metadata/conversion boundary, but it is not yet a socket sender, Weston capture client, or Android receiver. The next code milestone is the Weston output-capture client and bounded frame delivery into the JNI bridge; until that exists, do not claim that compositor frames are displayed on the Android Surface.
