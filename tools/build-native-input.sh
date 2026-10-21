#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
out="${1:-${PREFIX:-/data/data/com.termux/files/usr}/bin/termux-xwayland-input}"
command -v cc >/dev/null 2>&1 || { echo "Missing C compiler (cc)." >&2; exit 127; }
command -v pkg-config >/dev/null 2>&1 || { echo "Missing pkg-config." >&2; exit 127; }
pkg-config --exists x11 xtst || {
  echo "Missing X11/XTest development packages. Install libx11 and libxtst." >&2
  exit 1
}
[[ -r "$root/native/input/termux-xwayland-input.c" ]] || { echo "Missing native input helper source." >&2; exit 1; }
tmp="$(mktemp -d "${TMPDIR:-/tmp}/txw-input-build.XXXXXX")"
trap 'rm -rf "$tmp"' EXIT
cc -std=c11 -O2 -Wall -Wextra -Werror \
  $(pkg-config --cflags x11 xtst) \
  "$root/native/input/termux-xwayland-input.c" \
  -o "$tmp/termux-xwayland-input" \
  $(pkg-config --libs x11 xtst)
mkdir -p "$(dirname "$out")"
install -m 0755 "$tmp/termux-xwayland-input" "$out"
echo "Built persistent native XTest input helper: $out"
