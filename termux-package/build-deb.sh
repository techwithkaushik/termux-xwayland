#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
pkg="$root/termux-package"
out="${1:-$root/dist}"
command -v dpkg-deb >/dev/null 2>&1 || {
  echo "dpkg-deb is required. Install dpkg in Termux or on the build host." >&2
  exit 127
}
mkdir -p "$out"
chmod 0755 "$pkg/data/data/com.termux/files/usr/bin/termux-xwayland" "$pkg/DEBIAN/postinst"
dpkg-deb --root-owner-group --build "$pkg" "$out/termux-xwayland-bridge_0.1.0_all.deb"
echo "Built: $out/termux-xwayland-bridge_0.1.0_all.deb"
