#!/usr/bin/env bash
# One-command layered acceptance runner for XW11 migration.
# Host tests/build are mandatory; selected optional gates all run, even if one fails.
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_DIR="${XW11_BUILD_DIR:-${TMPDIR:-/tmp}/xw11-native-transport-build}"
RUN_WESTON="${XW11_RUN_WESTON:-0}"
RUN_ANDROID="${XW11_RUN_ANDROID_PRESENTER:-0}"
RUN_ANDROID_EGL="${XW11_RUN_ANDROID_EGL_PROBE:-0}"

for cmd in python3 cmake ctest; do
  command -v "$cmd" >/dev/null 2>&1 || {
    echo "ERROR: required tool missing: $cmd" >&2
    exit 127
  }
done

echo "== 1/3: Source-contract regression tests =="
python3 "$ROOT/tools/tests/test_android_graphics_probe.py"
python3 "$ROOT/tools/tests/test_xwayland_runtime_script.py"

echo "== 2/3: Portable native transport build + tests =="
cmake -S "$ROOT/tools/native-transport" -B "$BUILD_DIR" -DCMAKE_BUILD_TYPE=Debug
cmake --build "$BUILD_DIR" --parallel "${CMAKE_BUILD_PARALLEL_LEVEL:-2}"
ctest --test-dir "$BUILD_DIR" --output-on-failure

optional_failures=0
run_optional_gate() {
  local name="$1"
  shift
  echo "== Optional: $name =="
  if "$@"; then
    echo "PASS: $name"
  else
    local status=$?
    echo "FAIL: $name (exit $status)" >&2
    optional_failures=$((optional_failures + 1))
  fi
}

if [[ "$RUN_WESTON" == "1" ]]; then
  run_optional_gate "Weston runtime test" bash "$ROOT/tools/test-weston-xwayland-runtime.sh" --headless
else
  echo "SKIP: Weston runtime test (set XW11_RUN_WESTON=1 to enable)."
fi

if [[ "$RUN_ANDROID" == "1" ]]; then
  run_optional_gate "Android presenter probe" bash "$ROOT/tools/run-android-presenter-probe.sh"
else
  echo "SKIP: Android presenter probe (set XW11_RUN_ANDROID_PRESENTER=1 on-device)."
fi

if [[ "$RUN_ANDROID_EGL" == "1" ]]; then
  run_optional_gate "Android app-process EGL probe" bash "$ROOT/tools/run-android-egl-probe.sh"
else
  echo "SKIP: Android app-process EGL probe (set XW11_RUN_ANDROID_EGL_PROBE=1 on-device)."
fi

if (( optional_failures > 0 )); then
  echo "FAIL: $optional_failures optional acceptance gate(s) failed." >&2
  exit 1
fi

echo "PASS: selected XW11 acceptance gates completed."
echo "LIMITATION: this runner does not claim Weston frame import, zero-copy, or end-to-end input."
