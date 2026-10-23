# Native presentation/input transport contract (draft)

Status: partial implementation. A bounded CPU-copy Android `ANativeWindow` presenter now exists in `tools/native-transport/xw11_surface_presenter_android.{h,cpp}` and is added to the Xlorie native build for compile coverage. It is not called by the app, is not connected to a Weston frame producer, and must not share a Surface concurrently with the existing EGL renderer. Input forwarding to Weston is not implemented. This is not yet an end-to-end presentation/input bridge.

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
- `xw11_transport_io.h/.cpp` adds bounded stream-socket frame send/receive with partial-I/O and EINTR handling, payload-capacity checks, and explicit EOF/system/protocol errors.
- `xw11_fd_passing.h/.cpp` provides a separate one-descriptor `SCM_RIGHTS` transfer primitive for connected Unix stream sockets. It checks the marker/control message, rejects malformed or multiple-FD transfers, and marks the received descriptor close-on-exec. This is not yet used by a producer/consumer.
- `tests/test_transport_io.cpp` exercises payload round trips, empty frames, too-small receive buffers, and truncated connections using a local Unix socket pair. `tests/test_fd_passing.cpp` checks descriptor transfer, descriptor usability, close-on-exec, and argument rejection.
- The XWayland preflight workflow compiles all three native transport test executables with C++17 warnings-as-errors and runs them.

The control-frame transport remains standalone: it is not connected to Weston, Android/gralloc buffers, fences, or XWayland process startup. Passing an FD successfully does not prove that the FD represents an importable graphics buffer. A too-small receive buffer or framing error requires closing the stream because payload bytes may remain unread. The protocol module exposes a per-connection sequence tracker, but it is not wired into an Android/Weston producer or consumer. Do not describe the frame transport as integrated until producer/consumer paths and device tests exist.

## Buffer submission transport (implemented; graphics import pending)

`xw11_buffer_transport.h/.cpp` now composes the existing FD-passing and frame-protocol primitives into one buffer submission operation. The sender transfers one descriptor with `SCM_RIGHTS` first, then sends a `BUFFER_SUBMIT` frame containing the fixed-size buffer metadata. The receiver reads the ancillary FD with `recvmsg()` before reading the framed metadata, verifies message type, expected session, monotonic sequence, descriptor validity, and the active Surface generation, and transfers FD ownership to the caller only on success. On any receive error it closes the received FD; the caller must close the connection because stream resynchronization is not guaranteed.

A host socket-pair test verifies the FD-plus-metadata round trip, close-on-exec, metadata fields, and actual descriptor usability. The module is included in the portable CMake target and Android native source list. A single writer per socket is required; callers must close the connection if either half of a submission fails.

This remains a generic descriptor transport test. The test uses a pipe FD, not a graphics allocation. It does not establish DMA-BUF/gralloc import, DRM modifier compatibility, fence synchronization, buffer release/reuse, a Weston output producer, or Android Surface presentation. The producer and consumer still need to be integrated with the actual compositor/backend and validated on the target device.

## Graphics buffer wire metadata codec (implemented; transport integration pending)

`xw11_buffer_wire.h/.cpp` defines a fixed 36-byte, big-endian encoding for the existing `xw11_buffer_descriptor`: buffer ID, Surface generation, dimensions, stride, DRM fourcc, and modifier. Encode and decode both validate the descriptor before accepting it; the decoder requires an exact payload length and does not publish partially decoded state. A host test covers round-trip values, byte order, invalid sizes, null arguments, and invalid descriptors.

The codec is intended for the bounded `BUFFER_SUBMIT` control message while the actual graphics handle is transferred separately using `SCM_RIGHTS`. It does not send pixels, transfer an FD, prove that an FD is a graphics allocation, negotiate import support, or provide fences. The producer and consumer are still not connected to Weston or the Android presenter. The 1 MiB control-frame payload limit remains suitable for this small metadata record—not for raw display frames.

## Experimental Android Surface presenter (partial implementation)

The native helper `xw11_presenter_set_window`, `xw11_presenter_present_rgba8888`, and `xw11_presenter_clear_window` provide a first consumer-side implementation:
- owns an explicit `ANativeWindow` reference and generation;
- rejects stale generations, invalid dimensions, invalid source strides, and frames above a bounded memory budget;
- copies RGBA8888 rows into a locked Android window buffer and posts the buffer;
- serializes window replacement, frame posting, and teardown under a native mutex.

This is an intentionally CPU-copy diagnostic path, not zero-copy. It is compiled into the native library, but no JNI entry point or dedicated Wayland `SurfaceView` calls it yet. The current `LorieView` Surface is already owned by the legacy EGL renderer, so wiring this presenter to that same Surface would create competing producers and is explicitly prohibited. The next integration must add a separate Surface owner or a controlled renderer-mode switch before a Weston frame producer is connected.

Input is still not forwarded to Weston. Do not assume the generic virtual-keyboard/pointer protocols are available: verify the actual Weston build's advertised globals first. The Android event adapter must preserve key/button down-up transitions, map coordinates using the active surface generation, and enqueue events off the UI thread to a compositor-side input injector. If the selected Weston build has no authorized virtual-input protocol, add and package a narrowly scoped Weston module/protocol rather than silently injecting into the legacy X server.

## Surface generation guard

A portable state-machine primitive now lives in `tools/native-transport/xw11_surface_generation.h/.cpp`. It issues a new nonzero generation for each successful surface creation, rejects events submitted against stale generations, stops accepting the destroyed generation, makes repeated teardown idempotent, and refuses generation-counter wraparound. Its host test covers create/destroy/recreate, stale-event rejection, null arguments, and exhaustion.

The primitive is integrated into the current Android Surface lifecycle, but not yet into an asynchronous Weston frame/input transport. The guard is compiled into the Android `Xlorie` native library and called from the existing `LorieView` Surface callbacks: Java reports `surfaceCreated`, native code starts a generation, gates window updates on active state, and marks the generation inactive when the Surface is destroyed. Because the existing `surfaceChanged` JNI callback does not carry a generation captured by an asynchronous producer, this wiring is a lifecycle gate—not complete stale-frame rejection. Any future producer/consumer must capture and validate the original generation for each submitted frame/input event. This protects the current legacy Surface lifecycle; it does **not** connect Weston or replace the current renderer. Do not treat this as proof of XWayland output, zero-copy presentation, or on-device latency.

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

## Redmi 9 Active probe findings (2026-10-23)

The first successful on-device report was collected from Termux on Android 10 / API 29, kernel 4.9.190, ABI `armeabi-v7a`, MediaTek platform `mt6765`.

Observed:
- `/dev/ion` exists, while `/dev/dri`, `/dev/dma_heap`, `/dev/kgsl-3d0`, `/dev/mali0`, and `/dev/pvr` were not visible in the Termux shell.
- Vendor graphics components include `/vendor/lib/egl/libEGL_mtk.so`, `libGLESv2_mtk.so`, `/vendor/lib/hw/gralloc.mt6765.so`, and the Android graphics allocator/mapper HAL libraries.
- Termux has Weston, Xwayland, Mesa EGL/GLES/GBM and Wayland libraries. The optional `eglinfo` and `vulkaninfo` diagnostics are not installed.
- `dumpsys` is unavailable in the ordinary Termux shell, so this run did not capture SurfaceFlinger renderer details.

Interpretation and next probe:
- These observations are consistent with a vendor-managed Android graphics stack, but do not prove that Termux can allocate, export, import, or synchronize shareable buffers. In particular, `/dev/ion` visibility alone does not establish a usable zero-copy path.
- Do not hard-code a DRM/GBM or DMA-BUF path based on this report. Next, inspect the actual Android host's `Surface`/EGL capability from the app process and test buffer allocation/import plus acquire/release synchronization on-device. Keep the transport behind an adapter so an unsupported path fails clearly.
- The report is diagnostic evidence, not an acceptance test. No rendering/input integration or X.Org removal is implied by these findings.

## Root SurfaceFlinger probe findings (2026-10-23)

A follow-up `su -c 'dumpsys SurfaceFlinger'` probe on the same Redmi 9 Active exposed additional graphics information:
- EGL reports version 1.4 and extensions including `EGL_ANDROID_image_native_buffer`, `EGL_ANDROID_native_fence_sync`, `EGL_KHR_fence_sync`, `EGL_KHR_wait_sync`, `EGL_EXT_image_dma_buf_import`, and `EGL_EXT_image_dma_buf_import_modifiers`.
- GLES reports Imagination Technologies PowerVR Rogue GE8320, OpenGL ES 3.2. The driver lists `GL_EXT_memory_object` and `GL_EXT_memory_object_fd`.
- SurfaceFlinger reports `EGL_ANDROID_native_fence_sync` and `EGL_KHR_wait_sync` in its sync configuration; the internal display is 720x1600 and uses HWC composition for the sampled layers.
- The dump showed a high cumulative HWC missed-frame count, but this single cumulative value is not a measurement of the proposed XW11 renderer and must not be used as a performance baseline.

These extension strings are promising capability clues, not proof that an app-owned buffer can be exported from Weston and imported by Android's host surface. The next test must query extensions in the actual Termux-XW11 process/context, enumerate supported buffer formats/modifiers, and verify allocation, FD transfer, import, acquire/release fence handling, and buffer lifetime on-device. Do not assume DMA-BUF support solely from EGL extension presence, and do not treat the memory-object FD extension as interchangeable with Android gralloc handles.

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

## App-process EGL diagnostic (implemented; device run pending)

The library now contains `NativeGraphicsProbe`, a JNI-backed diagnostic that initializes the default EGL display, queries selected EGL extensions, creates a small 16x16 ES2 pbuffer/context, and records the GL vendor, renderer, version, and selected memory-object capability. It releases its EGL objects before returning. The probe is opt-in so normal display startup does not initialize or terminate an extra EGL display.

After installing a build containing this change, request a one-shot report from the host activity with the intent extra `org.techwithkaushik.termuxxw11.RUN_EGL_PROBE=true`. Read logcat entries tagged `XW11-EGL-PROBE`. Use the installed package ID and activity component for the current build; debug variants may use a suffix.

This diagnostic is deliberately limited: it tests whether the app process can initialize EGL and create a pbuffer. It does not access an Android `Surface`, allocate Android gralloc buffers, enumerate DMA-BUF formats/modifiers, transfer graphics-buffer FDs, or validate acquire/release fences. A successful result is only the prerequisite for a separate surface-backed buffer interoperability test. Device execution and report review are still pending.

### Android Surface-backed EGL probe (device run passed; Weston integration pending)

The opt-in diagnostic creates a Java `SurfaceView` and passes its live `Surface` to native code. The native path obtains an `ANativeWindow`, selects an EGL window configuration, creates an EGL window surface and ES2 context, clears one frame, and calls `eglSwapBuffers`. It then releases the EGL objects and native-window reference.

**Target-device result (Redmi 9 Active, Android 10 / API 29, 2026-10-23):**
- App-process EGL initialization and pbuffer/context probe completed.
- EGL version: 1.4; GL vendor: Imagination Technologies; renderer: PowerVR Rogue GE8320; GL version: OpenGL ES 3.2.
- `EGL_ANDROID_image_native_buffer`: reported yes.
- `EGL_ANDROID_native_fence_sync`, `EGL_EXT_image_dma_buf_import`, and `EGL_EXT_image_dma_buf_import_modifiers`: reported no by this app-process probe.
- `window surface swap: passed` on the app-owned Android Surface.
- The script initially could not see tagged logs as the ordinary Termux UID. Root-visible logcat confirmed the report; the runner now retries through `su` when ordinary logcat access is filtered.

This is evidence that the app process can render a clear operation to its own Android Surface. It is **not** evidence that Weston buffers can be exported/imported, that SurfaceFlinger and the app expose identical EGL capabilities, or that the path is GPU zero-copy. In particular, the app-process extension result differs from the broader SurfaceFlinger dump above; treat each result as belonging to its queried process/context.

Next acceptance stage: implement and test a bounded native bridge from an actual Weston frame producer to a separate Android Surface owner. First verify a supported buffer allocation/export/import mechanism and correct acquire/release synchronization on this device. Keep the existing Lorie renderer isolated until the new producer/consumer path passes visual, lifecycle, input, and performance tests.

## On-device Weston/XWayland protocol smoke test (2026-10-23)

The Redmi 9 Active / Termux session completed these manual tests:

- Headless Weston 16.0.1 with `--renderer=pixman --shell=kiosk --xwayland` started upstream XWayland 24.1.14. `DISPLAY=:1 xdpyinfo` succeeded and `xwininfo -root -tree` showed an XTerm window.
- A second isolated headless runtime started XWayland on `:2`; `xdpyinfo` and XTerm window enumeration succeeded. The X11 abstract socket for `:1` was already occupied, so Weston selected another display. This is expected display allocation behavior, not evidence of a renderer failure.
- Weston with `--backend=x11 --renderer=pixman --shell=kiosk --xwayland`, using the existing Termux:X11 `:0` as its parent display, created a 1024x600 X11 output. It launched XWayland on `:3`; `DISPLAY=:3 xdpyinfo` succeeded and `xwininfo -root -tree` showed the test XTerm window.
- One earlier runtime directory disappeared while its Weston process remained alive and retained a deleted lock-file descriptor. A fresh, uniquely named runtime directory worked. Runtime directories must be owned by the test process and must not be removed while their compositor is running.

These results establish Weston/XWayland startup, X11 protocol connectivity, and X11 client-window creation. They do **not** establish that the nested Weston window was visually confirmed on the Android display, that Weston renders directly to an Android `Surface`, that rendering is GPU accelerated, that buffers are zero-copy, or that touch/pointer latency is acceptable. All successful Weston logs above report the Pixman CPU renderer.

For repeatable, bounded testing, run:

```sh
bash tools/test-weston-xwayland-runtime.sh --nested
```

This starts a uniquely isolated nested Weston instance, waits for the XWayland display, checks `xdpyinfo`, launches a test XTerm, verifies the window tree, and cleans up only the instance it started. Use `--headless` for a protocol-only test. It intentionally uses Pixman and is not an Android-native presentation acceptance test.

Next device gate: run the opt-in app-process EGL pbuffer and Surface-backed EGL probes described above, then implement and validate actual Weston buffer export/import, synchronization fences, buffer lifetime, and input forwarding. Do not remove the Lorie fallback until visible X11 output and input pass on the target Android device.


### Repeatable nested smoke-test result (2026-10-23)

The committed `tools/test-weston-xwayland-runtime.sh --nested` was executed on the target Termux device and passed all scripted checks:

- The test created its private runtime directory and started nested Weston.
- Weston selected XWayland display `:1`.
- `DISPLAY=:1 xdpyinfo` succeeded.
- `xwininfo -root -tree` found the exact window title `XW11 Acceptance Smoke Test`.
- The script reported `PASS: Weston + XWayland smoke test complete.`

The unified CI run for this change also completed successfully: [run 39931512759](https://github.com/techwithkaushik/termux-xwayland/actions/runs/39931512759). CI validates repository checks and packaging; it does not run on the Android device and is not evidence of native graphics-buffer interoperability.

The next concrete device measurement is the app-process EGL pbuffer plus Android Surface-backed EGL probe. It should be run on the installed XW11 APK before writing a buffer-import bridge; it will establish whether this app can render to its own Surface, not whether Weston buffers can be imported.

## Buffer descriptor validation primitive (implemented; not connected to producer/consumer)

The portable native module `tools/native-transport/xw11_buffer_descriptor.h/.cpp` validates buffer metadata before a future import or submission path can allocate or use it. It requires nonzero buffer ID and surface generation, bounds dimensions to 16384x16384, recognizes only RGB565/XRGB8888/ARGB8888 fourcc values, verifies stride against width and bytes-per-pixel, checks multiplication safely, and caps the declared backing-store size at 512 MiB. The host test covers invalid IDs/generations, zero and excessive dimensions, unknown formats, undersized stride, oversized total size, null input, and RGB565.

This is a metadata validation gate only. It does not open or inspect a graphics FD, validate a DRM modifier, prove that a gralloc/DMA-BUF handle is importable, or synchronize producer/consumer access. Format/modifier support must be negotiated against the actual Weston producer and Android EGL consumer, and buffer lifetime must be enforced by the eventual transport owner. CI compiles the validator with C++17 and warnings-as-errors and runs its host test.

## In-flight buffer lifetime registry (implemented; producer/consumer wiring pending)

The allocation-free native module `tools/native-transport/xw11_buffer_registry.h/.cpp` tracks up to 16 in-flight buffer IDs and their Surface generations. It rejects duplicate submissions, refuses release for the wrong generation, supports release before reuse, abandons all buffers for a destroyed generation, and provides a disconnect cleanup operation. The caller must serialize access; the registry intentionally has no internal locks and does not own file descriptors or fences. Its host test covers duplicate IDs, wrong-generation release, full capacity, release/reuse, generation abandonment, and disconnect cleanup.

The registry is compiled into `Xlorie` and its standalone host test runs in CI. It is not yet called by Weston, the Android presentation adapter, or the FD-passing transport. It therefore establishes a testable ownership-state primitive, not proof that any graphics buffer has been exported, imported, presented, or synchronized. The future transport must pair each registry entry with real FD/fence ownership and only release a buffer after the consumer has actually finished using it.

## Generation-aware input coordinate mapping (implemented; event routing pending)

The portable module `tools/native-transport/xw11_input_transform.h/.cpp` maps Android view-pixel coordinates through a configured viewport into compositor output coordinates. It rejects stale or zero generations, empty/invalid dimensions, and events outside the visible viewport; it uses 64-bit intermediate arithmetic to avoid overflow and clamps the final valid coordinate to the last output pixel. The host test covers top-left and bottom-right mapping, stale generation, outside-viewport events, invalid dimensions, and null arguments.

The mapper is compiled into `Xlorie` and tested in CI. It is not yet connected to Android MotionEvent callbacks or Wayland input injection. Do not infer that pointer/touch events currently reach Weston from the existence of this primitive; event timestamps, button/key transition preservation, focus handling, and actual Wayland event submission remain integration work.


## Input-event wire codec (implemented; routing not connected)

The portable module `tools/native-transport/xw11_input_event.h/.cpp` now defines a fixed 40-byte, big-endian payload for `XW11_MSG_INPUT_EVENT`. It carries Surface generation, monotonic timestamp, event type/action, key or button code / touch pointer ID, signed compositor coordinates, modifiers, and device ID. Encode/decode validate event types, legal action combinations, exact payload size, and nonzero generation; wire format is independent of C++ struct padding and host endianness. CI compiles it with warnings-as-errors and exercises round-trip, signed coordinates, byte order, invalid size/type/action, and zero-generation rejection.

This is the serialization boundary only. It is not yet connected to Android `MotionEvent` / `KeyEvent`, a live Unix control socket, or a Weston input backend. A consumer must still reject stale generations, preserve key/button down-up pairs, validate event ordering, and submit events through Weston/libinput's supported input path. Do not treat the codec test as evidence that Android input reaches XWayland.


## Android Surface lifecycle bridge (implemented; frame source pending)

`com.termux.x11.Xw11NativeSurfaceView` is an isolated, package-private SurfaceView intended for the experimental path. Its holder callbacks allocate a nonzero generation, attach the Surface through JNI, and detach only the matching generation. The JNI bridge obtains an `ANativeWindow` using `ANativeWindow_fromSurface`, transfers a retained reference to the presenter, and releases its temporary reference. The presenter already retains its own reference and ignores stale-generation teardown.

This view is deliberately **not added to the current Activity layout**: with no Weston frame producer connected it would be blank, and replacing `LorieView` prematurely would break the working legacy path. The bridge therefore establishes Surface ownership/lifecycle only; it does not prove that any frame is rendered. The next integration step is to connect a real Weston output producer to `xw11_presenter_present_rgba8888` (or a negotiated importable-buffer path) and only then add an explicit renderer-mode switch.

## Bounded native input queue (implemented; Android event source and Weston consumer pending)

`xw11_input_queue.h/.cpp` adds a thread-safe, fixed-capacity queue of 256 validated input events for the planned UI-thread-to-transport-thread boundary. Adjacent pointer/touch motion from the same Surface generation and device is coalesced to reduce latency under high-frequency movement. When full, incoming motion is dropped and counted; a key/button transition may evict the oldest queued motion, but is rejected rather than silently evicting another transition if the queue contains only transitions. The queue does not create threads, send socket messages, or inject input into Weston. The native Xlorie build compiles the module, and CI compiles/runs its host test with warnings-as-errors.

This queue is deliberately bounded and non-blocking at the API boundary. Callers must handle `ERR_FULL`, avoid losing matching key/button release events, and reset or filter events by Surface generation when a view is destroyed. It is a reusable queue primitive, not evidence that Android input currently reaches Weston or XWayland.

## Framed input transport adapter (implemented; caller and Weston receiver pending)

`xw11_input_transport.h/.cpp` bridges one validated input-event payload to the versioned XW11 stream protocol. It serializes the fixed-size event, wraps it as `XW11_MSG_INPUT_EVENT`, attaches the negotiated nonzero session ID and monotonically increasing sequence ID, and sends the complete frame through the partial-I/O-safe socket helper. Sequence values advance only after a successful send; zero and exhausted sequence state are rejected. The API contract requires a single dedicated writer per socket so concurrent writers cannot interleave frame bytes.

CI has a socket-pair round-trip test that validates message type, session, sequence, payload and event values. The adapter and its protocol/I/O dependencies are included in the native Xlorie source list for compile coverage. **It is not yet called by Android event callbacks or a running transport thread, and there is no Weston-side receiver/injector.** A successful socket-pair test proves wire framing only, not live input delivery.

## Input frame receiver (implemented; compositor injection pending)

`xw11_input_receiver.h/.cpp` now receives one framed input message, enforces the expected session, checks the message type and exact fixed-size payload, decodes the event, and applies the per-connection monotonic sequence tracker before returning the event to its caller. The receiver rejects replayed/out-of-order frames and does not publish a partially decoded event. Its host test covers a valid event, duplicate sequence, wrong session, and wrong message type; CI compiles it with warnings-as-errors.

This is a protocol endpoint primitive only. It is not yet attached to a listening socket in Weston, does not create Wayland seats/devices, and does not inject pointer, touch, keyboard, or focus events into the compositor. The Android event callbacks are also not connected to the queue/transport. The receiver's caller must close the connection after a framing/validation error because a stream may no longer be safely resynchronized. Do not describe input forwarding as end-to-end until the Android producer, socket lifecycle, receiver, and compositor input adapter are connected and tested together.

## Dedicated input dispatcher (implemented; Android producer and Weston endpoint pending)

`xw11_input_dispatcher.h/.cpp` now owns a bounded input queue and one dedicated writer thread for a connected socket/session. Producers submit events without writing framed socket bytes themselves; the worker serializes frames and assigns monotonically increasing sequence IDs. The queue's motion coalescing/drop policy remains separate from non-motion transitions. Shutdown signals the worker, shuts down the socket to interrupt blocked I/O, joins the thread, closes the owned descriptor, and releases the queue. The host test uses a local socket pair to verify a submitted button event is framed, sequenced, decoded, and delivered; it also checks that submissions after stop are rejected.

The dispatcher takes ownership of the connected file descriptor only when creation succeeds. Use one dispatcher per connection, and do not share its descriptor with another writer. This module does not create or authenticate a session, open a listening socket, or reconnect automatically. It is compiled into the native app library for coverage, but is not called by Android `MotionEvent`/`KeyEvent` callbacks. No Weston-side receiver or compositor input injector is packaged yet; therefore the dispatcher test proves the client transport primitive only, not interactive XWayland input.
