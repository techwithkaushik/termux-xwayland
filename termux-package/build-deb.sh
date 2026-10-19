#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
pkg="$root/termux-package"
out="${1:-$root/dist}"
archive="$out/termux-xwayland-bridge_0.1.0_all.deb"

command -v dpkg-deb >/dev/null 2>&1 || {
  echo "dpkg-deb is required. Install dpkg in Termux or on the build host." >&2
  exit 127
}

test -f "$pkg/DEBIAN/control"
test -f "$pkg/DEBIAN/postinst"
test -f "$pkg/data/data/com.termux/files/usr/bin/termux-xwayland"

mkdir -p "$out"
# Use a unique staging directory and explicitly create/copy each control file.
# Some Android/Termux cp implementations handle multi-source directory copies
# differently, so do not rely on: cp -R DEBIAN data "$stage/".
stage="$(mktemp -d "${TMPDIR:-/tmp}/termux-xwayland-deb.XXXXXX")"
cleanup() { rm -rf "$stage"; }
trap cleanup EXIT

mkdir -p "$stage/DEBIAN" "$stage/data"
cp "$pkg/DEBIAN/control" "$stage/DEBIAN/control"
cp "$pkg/DEBIAN/postinst" "$stage/DEBIAN/postinst"
cp -R "$pkg/data/." "$stage/data/"

find "$stage" -type d -exec chmod 0755 {} +
chmod 0644 "$stage/DEBIAN/control"
chmod 0755 "$stage/DEBIAN/postinst"
chmod 0755 "$stage/data/data/com.termux/files/usr/bin/termux-xwayland"

# Remove an old output first; never leave a stale archive looking successful.
rm -f "$archive"
dpkg-deb --root-owner-group --build "$stage" "$archive"
dpkg-deb --info "$archive" >/dev/null
echo "Built and validated: $archive"
