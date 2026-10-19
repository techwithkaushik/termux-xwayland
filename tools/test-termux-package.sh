#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
pkg="$root/termux-package"
script="$pkg/data/data/com.termux/files/usr/bin/termux-xwayland"
test -f "$pkg/DEBIAN/control"
test -f "$pkg/DEBIAN/postinst"
test -f "$script"
bash -n "$pkg/DEBIAN/postinst"
# Script uses Termux's absolute bash at runtime; parse it with the CI host bash.
bash -n "$script"
grep -q '^Package: termux-xwayland-bridge$' "$pkg/DEBIAN/control"
grep -q '^Architecture: all$' "$pkg/DEBIAN/control"
grep -q 'APP_ID="org.techwithkaushik.termuxxwayland"' "$script"
grep -Fq '"$SYSTEM_PM" path' "$script"
grep -Fq '"$SYSTEM_AM" start -W -n' "$script"
if grep -q 'allow-external-apps' "$script" || grep -q 'RUN_COMMAND' "$script"; then
  echo "Companion must not depend on RUN_COMMAND or allow-external-apps." >&2
  exit 1
fi
echo "Termux companion package structure and shell syntax checks passed."
