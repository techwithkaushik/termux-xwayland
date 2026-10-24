#!/data/data/com.termux/files/usr/bin/bash
# Build and install the Weston capture producer in the active Termux prefix.
set -Eeuo pipefail

ROOT="$(cd "$(dirname "\${BASH_SOURCE[0]}")/.." && pwd)"
PREFIX="\${PREFIX:-/data/data/com.termux/files/usr}"
BUILD_DIR="\${TMPDIR:-$PREFIX/tmp}/xw11-weston-capture-build"

for command in cmake pkg-config wayland-scanner; do
  command -v "$command" >/dev/null 2>&1 || {
    echo "ERROR: missing $command. Install cmake, pkg-config and wayland development packages first." >&2
    exit 127
  }
done
pkg-config --exists wayland-client || {
  echo "ERROR: wayland-client development files are missing from pkg-config." >&2
  echo "Install the Termux wayland package and its development files, then retry." >&2
  exit 1
}

cmake -S "$ROOT/tools/weston-capture" -B "$BUILD_DIR" -DCMAKE_BUILD_TYPE=Release
cmake --build "$BUILD_DIR" --parallel 2
install -d "$PREFIX/bin"
install -m 755 "$BUILD_DIR/xw11-weston-capture" "$PREFIX/bin/xw11-weston-capture"

set +e
"$PREFIX/bin/xw11-weston-capture" >/dev/null 2>&1
result=$?
set -e
if [[ "$result" -ne 2 ]]; then
  echo "ERROR: installed capture helper failed its usage smoke test (exit $result)." >&2
  exit 1
fi
echo "Installed: $PREFIX/bin/xw11-weston-capture"
