#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
xml="${TERMUX_XWAYLAND_CAPTURE_XML:-${PREFIX:-/data/data/com.termux/files/usr}/share/libweston-16/protocols/weston-output-capture.xml}"
out="${1:-$root/dist/termux-xwayland-capture}"
scanner="${WAYLAND_SCANNER:-wayland-scanner}"

command -v cc >/dev/null 2>&1 || { echo "Missing C compiler (cc)." >&2; exit 127; }
command -v pkg-config >/dev/null 2>&1 || { echo "Missing pkg-config." >&2; exit 127; }
command -v "$scanner" >/dev/null 2>&1 || { echo "Missing wayland-scanner." >&2; exit 127; }
pkg-config --exists wayland-client || { echo "Missing wayland-client development metadata." >&2; exit 1; }
[[ -r "$xml" ]] || {
  echo "Missing Weston capture protocol XML: $xml" >&2
  echo "Install the Termux Weston package that provides share/libweston-16/protocols/weston-output-capture.xml." >&2
  exit 1
}
[[ -r "$root/native/capture/termux-xwayland-capture.c" ]] || {
  echo "Missing native capture client source." >&2
  exit 1
}

build_dir="$(mktemp -d "${TMPDIR:-/data/data/com.termux/files/usr/tmp}/txw-capture-build.XXXXXX")"
cleanup() { rm -rf "$build_dir"; }
trap cleanup EXIT

"$scanner" client-header "$xml" "$build_dir/weston-output-capture-client-protocol.h"
"$scanner" private-code "$xml" "$build_dir/weston-output-capture-protocol.c"
cc -std=c11 -O2 -Wall -Wextra -Werror \
  -I"$build_dir" \
  $(pkg-config --cflags wayland-client) \
  "$root/native/capture/termux-xwayland-capture.c" \
  "$build_dir/weston-output-capture-protocol.c" \
  -o "$build_dir/termux-xwayland-capture" \
  $(pkg-config --libs wayland-client)

mkdir -p "$(dirname "$out")"
install -m 0755 "$build_dir/termux-xwayland-capture" "$out"
echo "Built native Weston capture helper: $out"
echo "The helper requests privileged framebuffer capture; runtime authorization/backend support is not guaranteed."
