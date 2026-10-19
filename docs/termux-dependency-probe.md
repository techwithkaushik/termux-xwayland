# Termux compositor/XWayland dependency probe

Run the read-only inventory on the target Termux environment:

    cd ~/projects/termux-xwayland
    bash tools/probe-wayland-deps.sh

The probe reports architecture, toolchain binaries, pkg-config modules, and related packages already installed. It does not install packages, start a server, change permissions, or inspect token values.

## What the report establishes

- Whether the current shell is using the expected 32-bit ARM userspace.
- Which Wayland server/client, XKB, renderer, X11/XCB and XWayland development/runtime pieces are already visible to Termux.
- Which missing dependencies must be checked against Termux package availability or built separately.

A missing pkg-config module does not necessarily mean no package exists; package names and exported module names can differ. A present Xwayland executable also does not prove that a selected compositor framework can launch and manage it.

## Gate before adding compositor dependencies

1. Save the probe output without secrets.
2. Check missing modules against the current Termux repository for the actual `arm` architecture.
3. Choose a compositor framework/version whose XWayland integration API is understood.
4. Build a minimal compositor before implementing frame export.
5. Verify the Android IPC transport independently: the current abstract AF_UNIX cross-UID connection fails with EACCES on the target Android 10 device.

Do not install a large package set speculatively. Keep the base Termux install intact and prefer a small, reproducible dependency set.
