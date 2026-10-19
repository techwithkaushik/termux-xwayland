# XWayland integration plan

## Goal

This project must host a real Wayland compositor and run the upstream XWayland server as a Wayland client. It is not an Android X server and must not reimplement the X11 protocol. The target is Termux Linux applications displayed inside the Android host app.

Upstream reference: https://wayland.freedesktop.org/docs/book/Xwayland.html. Termux:X11 is useful for Android bootstrap and Termux package integration, but its Xorg DDX is an X server, not the compositor/XWayland implementation this project needs.

## Proposed process topology

1. Termux session launcher starts the session and native compositor process.
2. Wayland compositor creates a private Wayland socket, manages Wayland surfaces and input, composites client buffers, and exports bounded output frames to Android through explicitly authorized IPC.
3. XWayland is spawned by the compositor and connects to its Wayland socket. It also exposes an X11 display socket for X11 applications.
4. X11 clients connect to XWayland using normal X11 client libraries.
5. Android display host shows compositor output and sends touch/keyboard events back to the compositor. It does not connect directly to XWayland's internal Wayland channel.

## XWayland window-management mode

Start with rootful XWayland if the chosen compositor integration supports it: the X11 desktop appears as one surface, avoiding full rootless X11 window management for the first vertical slice. Do not promise rootless window integration until the compositor's XWM implementation is present and tested. Rootless mode requires the compositor to manage X11 window state through XWayland's XWM protocol.

## Implementation phases and exit checks

### Phase A — dependency feasibility

- Inspect the selected compositor framework's XWayland support and version-specific API.
- Verify Termux packages/build recipes can provide the XWayland executable and runtime libraries for both 32-bit ARM userspace and aarch64.
- Record required X11, Wayland, XKB, font and rendering dependencies.
- Build a minimal compositor binary on the target Termux environment before adding Android frame export.
- Do not silently depend on host /usr paths or assume a Debian chroot is available.

Exit: reproducible ARM32 build/check with a documented dependency list.

### Phase B — one native Wayland client

- Start a minimal compositor with a private runtime directory and Wayland socket.
- Run a tiny Wayland test client and verify that the compositor receives surface commits and damage.
- Render the surface into a known compositor output buffer.
- Export that buffer through the selected Android-10-compatible IPC transport.

Exit: Android shows changing pixels produced by a real Wayland client, not a generated test pattern or external screen recorder.

### Phase C — XWayland

- Launch the actual XWayland executable as a compositor-managed child process.
- Complete XWayland's Wayland connection and X11 socket setup using the compositor framework's supported integration.
- Start a minimal X11 test client such as xeyes or xclock if available in the target package set.
- Verify the client window appears through the compositor output path.

Exit: a real X11 client is visible in Android, and its pixels originate from compositor surfaces.

### Phase D — input and lifecycle

- Map Android touch/key events to compositor input with coordinate scaling and key-state cleanup.
- Handle orientation/resize, pause/resume, compositor exit, XWayland exit and reconnect.
- Add XFCE only after the single X11 client path works.

Exit: repeated start/stop and input tests pass on Android 10 without root, TCP/localhost, or SELinux policy changes.

## IPC and security gate

The current abstract AF_UNIX socket is diagnostic only: the target Android 10 device rejects cross-UID connection with EACCES before token authentication. A token authenticates a peer after a connection exists; it cannot grant Android permission to connect.

Evaluate a documented Termux-side bootstrap such as app_process or a loader only for launching code under the intended process identity. Termux:X11 shell-loader and CmdEntryPoint are bootstrap references, not proof that its transport can be reused. Do not infer that app_process bypasses UID/SELinux boundaries. The chosen IPC must independently pass Android 10 authorization, bidirectional bounded messaging, lifecycle and sustained-stream tests.

No localhost TCP fallback, root requirement, or SELinux disabling. Do not use repeated RUN_COMMAND invocations or shared-storage files as the live frame stream.

## Current status

- Android host and bounded protocol smoke test: present.
- Synthetic frame: diagnostic only.
- Real Wayland compositor and surface rendering: not implemented.
- Android-authorized full-duplex IPC: not proven on target device.
- XWayland integration and X11 client rendering: not implemented.
- XFCE/LXQt: not yet supported.
