#!/usr/bin/env bash
# Launch the opt-in app-process EGL/Android-Surface probe and collect only its
# tagged diagnostics. Does not clear logcat, stop the X11 server, or change prefs.
set -Eeuo pipefail

PACKAGE="${XW11_PACKAGE:-org.techwithkaushik.termuxxw11}"
ACTIVITY="${XW11_ACTIVITY:-com.termux.x11.MainActivity}"
EXTRA="org.techwithkaushik.termuxxw11.RUN_EGL_PROBE"
OUT="${1:-$HOME/xw11-egl-probe.log}"
TAG="XW11-EGL-PROBE"
LOGGER_PID=""

cleanup() {
    if [[ -n "$LOGGER_PID" ]]; then
        kill "$LOGGER_PID" 2>/dev/null || true
        wait "$LOGGER_PID" 2>/dev/null || true
    fi
}
trap cleanup EXIT INT TERM

for cmd in am logcat; do
    command -v "$cmd" >/dev/null 2>&1 || {
        echo "ERROR: required Android command not found: $cmd" >&2
        exit 127
    }
done

mkdir -p "$(dirname "$OUT")"
: > "$OUT"

# Start a fresh log reader before launching the activity; do not use logcat -c,
# which would erase unrelated diagnostic history.
logcat -T 1 -v brief -s "$TAG" > "$OUT" 2>&1 &
LOGGER_PID=$!
sleep 1

echo "Launching opt-in EGL probe: $PACKAGE/$ACTIVITY"
if ! am start -W -n "$PACKAGE/$ACTIVITY" --ez "$EXTRA" true; then
    echo "ERROR: Activity launch failed. Check installed package/activity names." >&2
    exit 1
fi

# The diagnostic runs from the Activity's Surface callbacks. Give Android time
# to create the Surface and log both pbuffer and window-surface results.
sleep 8
cleanup
LOGGER_PID=""

echo
echo "Probe log: $OUT"
if ! grep -q "$TAG" "$OUT"; then
    echo "FAIL: no $TAG messages captured." >&2
    echo "Check that this APK contains NativeGraphicsProbe and keep the app foregrounded." >&2
    exit 1
fi
cat "$OUT"

if grep -Eiq 'window surface swap: passed' "$OUT"; then
    echo "PASS: app-process Android Surface EGL swap succeeded."
else
    echo "INCOMPLETE: no successful Android Surface swap was reported."
    echo "Inspect the EGL error in the log above; this is not a Weston-buffer import test."
    exit 2
fi
