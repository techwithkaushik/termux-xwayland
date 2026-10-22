#!/usr/bin/env bash
set -Eeuo pipefail

# Read-only preflight for migrating the Android native build from the Lorie DDX
# to the upstream XWayland backend. This deliberately does not alter the active
# CMake target or claim that the Android cross-build is already supported.

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
XSERVER_DIR="$ROOT_DIR/lorie/src/main/cpp/xserver"
REQUIRE_DEPS=0
HOST_DEPS_ONLY=0

usage() {
    cat <<'USAGE'
Usage: tools/probe-xwayland-source.sh [--require-deps] [--host-deps-only]

By default, checks that the X server submodule contains the upstream XWayland
Meson backend and reports common host tools/dependencies. With --host-deps-only,
reports only host tools/dependencies. It does not build or modify files.

Options:
  --require-deps  Exit non-zero if a required tool or dependency is missing.
  --host-deps-only Skip the legacy submodule check and report host tools/deps only.
USAGE
}

for arg in "$@"; do
    case "$arg" in
        --require-deps) REQUIRE_DEPS=1 ;;
        --host-deps-only) HOST_DEPS_ONLY=1 ;;
        -h|--help) usage; exit 0 ;;
        *) echo "Unknown option: $arg" >&2; usage >&2; exit 2 ;;
    esac
done

failures=0
warn() { printf 'MISSING: %s\n' "$*"; failures=$((failures + 1)); }
ok() { printf 'OK: %s\n' "$*"; }

if (( ! HOST_DEPS_ONLY )); then
    if [[ ! -d "$XSERVER_DIR/.git" && ! -f "$XSERVER_DIR/.git" ]]; then
        echo "ERROR: X server submodule is not initialized at: $XSERVER_DIR" >&2
        echo "Run: git submodule update --init --recursive lorie/src/main/cpp/xserver" >&2
        exit 2
    fi

    if [[ -f "$XSERVER_DIR/hw/xwayland/meson.build" && -f "$XSERVER_DIR/meson.build" ]]; then
        ok "upstream XWayland Meson backend exists in the pinned X server source"
    else
        echo "ERROR: expected hw/xwayland/meson.build and top-level meson.build were not found." >&2
        echo "This source revision cannot be used as the XWayland backend without updating the source pin." >&2
        exit 2
    fi

    if command -v git >/dev/null 2>&1; then
        printf 'X server source revision: '
        git -C "$XSERVER_DIR" rev-parse --short=12 HEAD 2>/dev/null || echo "unknown (submodule metadata unavailable)"
    fi

fi

for tool in meson ninja pkg-config; do
    if command -v "$tool" >/dev/null 2>&1; then
        ok "$tool: $("$tool" --version 2>/dev/null | head -n 1)"
    else
        warn "$tool is not installed"
    fi
done

# These are upstream build dependencies, not proof that Android-target builds
# can consume host libraries. For an NDK build, every dependency must match the
# target ABI and sysroot rather than accidentally resolving to host libraries.
if command -v pkg-config >/dev/null 2>&1; then
    for dep in wayland-client wayland-protocols pixman-1 xfont2 xkbfile xshmfence libxcvt; do
        if pkg-config --exists "$dep"; then
            ok "pkg-config dependency available on host: $dep"
        else
            warn "pkg-config dependency missing on host: $dep"
        fi
    done
fi

cat <<'NOTE'

Important:
- Host pkg-config success is not Android cross-build success.
- The current app uses CMake and an Android NDK toolchain; upstream XWayland uses Meson.
- A follow-up build integration must provide an Android Meson cross file and target-ABI
  builds of Wayland client/protocol and X server dependencies.
- The existing Lorie/Xlorie target is intentionally untouched by this preflight.
NOTE

if (( REQUIRE_DEPS && failures > 0 )); then
    exit 1
fi
exit 0
