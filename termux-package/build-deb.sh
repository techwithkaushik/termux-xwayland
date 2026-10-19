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

mkdir -p "$out"
stage="$(mktemp -d "${TMPDIR:-/tmp}/termux-xwayland-deb.XXXXXX")"
cleanup() { rm -rf "$stage"; }
trap cleanup EXIT

# Build from a staging copy so Android/Termux umask or checkout modes cannot
# leave DEBIAN at mode 0700 (dpkg-deb rejects that control-directory mode).
cp -R "$pkg/." "$stage/"
find "$stage" -type d -exec chmod 0755 {} +
chmod 0644 "$stage/DEBIAN/control"
chmod 0755 "$stage/DEBIAN/postinst"
chmod 0755 "$stage/data/data/com.termux/files/usr/bin/termux-xwayland"

# Remove an old output first; never leave a stale archive looking successful.
rm -f "$archive"
dpkg-deb --root-owner-group --build "$stage" "$archive"
dpkg-deb --info "$archive" >/dev/null
echo "Built and validated: $archive"
