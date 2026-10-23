# Native presentation/input transport contract (draft)

Status: design contract only. No producer/consumer transport is wired into the APK yet.

## Boundary and modules

Keep the implementation split into four independently testable parts:

1. **Android host UI** — owns the Activity and Android `Surface` lifecycle. Java/Kotlin may manage UI and permissions only.
2. **Native presentation adapter** — owns the current `ANativeWindow` reference and surface-generation counter. It must not own the compositor process.
3. **Wayland compositor adapter** — starts/stops Weston and reports its Wayland socket and output lifecycle. It must not launch a desktop environment.
4. **XWayland supervisor** — starts upstream XWayland with the compositor socket and X11 display parameters, then reports readiness/failure. It must not render frames itself.

The existing `LorieView` / X.Org renderer remains a temporary fallback and reference until device acceptance tests pass. Do not route new Wayland frames through its EGL renderer.

## Transport choice

Do not assume that a Unix socket alone makes display output zero-copy. The first implementation must probe whether the selected Weston output path can export buffers that Android can import safely.

Preferred path:
- Negotiate a versioned capability handshake over a private Unix-domain socket.
- Transfer native buffer handles with `SCM_RIGHTS` (never serialize process-local file descriptor integers).
- Transfer acquire/release sync-file fences when the selected buffer path supports them.
- Keep the buffer producer's ownership until the consumer has acknowledged release; never reuse a buffer while Android/GPU is reading it.
- Keep dimensions, stride, DRM fourcc/pixel format, modifier, usage flags, and buffer generation explicit.
- If the device/backend cannot import the exported buffer, fail with a diagnostic reason rather than silently adding an unbounded CPU-copy/render pass.

A shared-memory pixel buffer may be used as a separately named diagnostic fallback for early protocol tests, but it is not the performance target and must not be represented as zero-copy.

## Framing primitive now in the repository

The transport-neutral framing primitive lives in `tools/native-transport/`:
- `xw11_transport_protocol.h/.cpp` defines a 28-byte, big-endian v1 header with explicit magic, version, type, flags, payload length, session ID, and sequence ID.
- `tests/test_protocol.cpp` covers round-trip encoding/decoding and rejects invalid magic, unsupported versions/types, nonzero reserved flags, oversized payloads, zero session/sequence IDs, and truncated headers.
- `xw11_transport_io.h/.cpp` adds bounded stream-socket frame send/receive with partial-I/O and EINTR handling, payload-capacity checks, and explicit EOF/system/protocol errors. It does not pass file descriptors.
- `tests/test_transport_io.cpp` exercises payload round trips, empty frames, too-small receive buffers, and truncated connections using a local Unix socket pair.
- The XWayland preflight workflow compiles both test executables with C++17 warnings-as-errors and runs them.

This is still a standalone transport module. It is not connected to Android JNI, Weston, native buffers, FD passing, or XWayland process startup. A too-small receive buffer or framing error requires closing the stream because payload bytes may remain unread. The current module does not enforce per-session sequence monotonicity. Do not describe the frame transport as integrated until producer/consumer paths and device tests exist.

## Control protocol requirements

Every message must include:
- fixed magic and protocol major/minor version;
- message type and payload length;
- monotonically increasing session ID and sequence number;
- bounded lengths and validated enums before allocation or use.

Initial message types:
- `HELLO` / `CAPABILITIES` — protocol version and supported buffer/fence modes;
- `OUTPUT_CONFIG` — width, height, scale, format, stride, modifier, generation;
- `BUFFER_SUBMIT` — buffer ID plus transferred handle(s) and optional acquire fence;
- `BUFFER_RELEASE` — buffer ID plus optional release fence;
- `INPUT_EVENT` — pointer/touch/key/focus event with coordinates tied to the current output generation;
- `SUSPEND` / `RESUME` — host surface lifecycle;
- `ERROR` / `GOODBYE` — bounded error code and orderly teardown.

Reject unknown mandatory message types, unsupported major versions, stale generations, duplicate buffer IDs, negative or overflowing dimensions/strides, and payloads larger than the configured maximum. A disconnect must release all received FDs and mark every in-flight buffer abandoned before a new session can start.

## Lifecycle invariants

- Starting the server prepares the host only; it does not automatically launch a desktop environment.
- Each Android surface creation increments a generation. Events and buffers from an older generation are dropped.
- Surface destruction stops new submissions, drains or abandons in-flight buffers, releases the native window, and only then acknowledges teardown.
- Stop/restart and compositor disconnect must be idempotent.
- UI-thread work is limited to Android lifecycle and view operations; socket I/O, process supervision, and waits run off the UI thread.
- Input is timestamped and forwarded without waiting for a frame render. Pointer movement may be coalesced; button/key transitions may not be dropped.

## Target-device graphics capability probe

Before choosing the concrete buffer transport, run the non-invasive Android probe on the target Termux device:

```sh
bash tools/probe-android-buffer-transport.sh "$HOME/xw11-device-probe.txt"
```

It records Android API/ABI hints, graphics device nodes, relevant EGL/GLES/Vulkan/DRM/gralloc library paths, available diagnostic tools, and a filtered SurfaceFlinger graphics summary. It does not change system settings or dump the full environment. The report is evidence for selecting follow-up tests only: device-node/library presence or EGL extension strings do not prove that the Weston producer and Android consumer can import the same buffers with correct fences. The probe must be followed by a real buffer import/export and synchronization test before the transport is selected.

## Test gates before enabling the new path

### Host/unit tests
- malformed/truncated headers, oversized payloads, unknown versions and message types;
- integer overflow in width/height/stride and format validation;
- duplicated/stale buffer IDs and invalid lifecycle transitions;
- FD ownership on success, rejection, and peer disconnect;
- repeated suspend/resume and reconnect;
- input coordinate transform across resize and scale changes.

### Android/device tests (report separately from CI)
- build and run first on `armeabi-v7a` / Android 10;
- visible minimal X11 window under upstream XWayland;
- responsive pointer, touch, keyboard, focus, and resize;
- repeated surface recreation and start/stop with no crash, stale frame, or FD/buffer leak;
- confirm with tracing that the preferred path does not copy each frame through a CPU bitmap.

## Current blocker

The repository currently builds/packages the legacy Lorie path; the separate upstream XWayland package is not yet connected to a tested Android presentation backend. The next implementation step is to prototype and verify buffer export/import support on the target device before selecting concrete producer and consumer APIs. A successful APK build or an open X11 socket alone is not acceptance evidence.
