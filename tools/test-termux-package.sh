#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
pkg="$root/termux-package"
script="$pkg/data/data/com.termux/files/usr/bin/termux-xwayland"

test -f "$pkg/DEBIAN/control"
test -f "$pkg/DEBIAN/postinst"
test -f "$script"
bash -n "$pkg/DEBIAN/postinst"
bash -n "$script"

grep -q '^Package: termux-xwayland-bridge$' "$pkg/DEBIAN/control"
grep -q '^Architecture: all$' "$pkg/DEBIAN/control"
grep -Fq '"org.techwithkaushik.termuxxwayland"' "$script"
grep -Fq '"org.techwithkaushik.termuxxwayland.debug"' "$script"
grep -Fq '"$SYSTEM_PM" path' "$script"
grep -Fq '"$SYSTEM_AM" start -W -n' "$script"

if grep -q 'allow-external-apps' "$script" || grep -q 'RUN_COMMAND' "$script"; then
  echo "Companion must not depend on RUN_COMMAND or allow-external-apps." >&2
  exit 1
fi

# Build and inspect the actual package whenever dpkg-deb is available.
if command -v dpkg-deb >/dev/null 2>&1; then
  tmp_out="$(mktemp -d)"
  trap 'rm -rf "$tmp_out"' EXIT
  bash "$root/termux-package/build-deb.sh" "$tmp_out"
  archive="$tmp_out/termux-xwayland-bridge_0.1.0_all.deb"
  test -s "$archive"
  test "$(dpkg-deb -f "$archive" Package)" = "termux-xwayland-bridge"
  test "$(dpkg-deb -f "$archive" Architecture)" = "all"
  if dpkg-deb --contents "$archive" | grep -Fq './build-deb.sh'; then
    echo "Build tooling must not be installed at the package root." >&2
    exit 1
  fi
  dpkg-deb --contents "$archive" | grep -Fq './data/data/com.termux/files/usr/bin/termux-xwayland'
fi

echo "Termux companion package structure, shell syntax, and DEB build checks passed."
