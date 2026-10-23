#!/usr/bin/env bash
# One-command layered acceptance runner for XW11 migration.
# Host tests are mandatory; runtime/device gates run only when explicitly requested.
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_DIR="${XW11_BUILD_DIR:-${TMPDIR:-/tmp}/xw11-native-transport-build}"
RUN_WESTON="${XW11_RUN_WESTON:-0}"
RUN_ANDROID="${XW11_RUN_ANDROID_PRESENTER:-0}"

for cmd in python3 cmake ctest; do
  command -v "$cmd" >/dev/null 2>&1 || {
    echo "ERROR: required tool missing: $cmd" >&2
    exit 127
  }
done

echo "== 1/3: Android source-contract regression tests =="
python3 "$ROOT/tools/tests/test_android_graphics_probe.py"

echo "== 2/3: Portable native transport build + tests =="
cmake -S "$ROOT/tools/native-transport" -B "$BUILD_DIR" -DCMAKE_BUILD_TYPE=Debug
cmake --build "$BUILD_DIR" --parallel "${CMAKE_BUILD_PARALLEL_LEVEL:-2}"
ctest --test-dir "$BUILD_DIR" --output-on-failure

if [[ "$RUN_WESTON" == "1" ]]; then
  echo "== Optional: Weston + upstream XWayland runtime smoke test =="
  bash "$ROOT/tools/test-weston-xwayland-runtime.sh" --headless
else
  echo "SKIP: Weston runtime test (set XW11_RUN_WESTON=1 to enable)."
fi

if [[ "$RUN_ANDROID" == "1" ]]; then
  echo "== Optional: Android Surface presenter probe =="
  bash "$ROOT/tools/run-android-presenter-probe.sh"
else
  echo "SKIP: Android presenter probe (set XW11_RUN_ANDROID_PRESENTER=1 on-device)."
fi

echo "PASS: selected XW11 acceptance gates completed."
echo "LIMITATION: this runner does not claim Weston frame import, zero-copy, or end-to-end input."
