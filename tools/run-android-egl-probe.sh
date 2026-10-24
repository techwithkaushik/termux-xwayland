#!/usr/bin/env bash
# Launch the app-process EGL/Android-Surface probe and collect tagged diagnostics.
# Android 10 may restrict an ordinary app shell's logcat view; retry with su
# when available. Never clear logcat or modify Android logging permissions.
set -Eeuo pipefail

PACKAGE="${XW11_PACKAGE:-org.techwithkaushik.termuxxw11}"
ACTIVITY="${XW11_ACTIVITY:-com.termux.x11.NativeGraphicsProbeActivity}"
OUT="${1:-$HOME/xw11-egl-probe.log}"
TAG="XW11-EGL-PROBE"

for cmd in am logcat grep; do
    command -v "$cmd" >/dev/null 2>&1 || {
        echo "ERROR: required Android command not found: $cmd" >&2
        exit 127
    }
done

mkdir -p "$(dirname "$OUT")"
: > "$OUT"

echo "Launching EGL probe: $PACKAGE/$ACTIVITY"
if ! am start -W -n "$PACKAGE/$ACTIVITY"; then
    echo "ERROR: Activity launch failed. Check installed package/activity names." >&2
    exit 1
fi

sleep "${XW11_PROBE_WAIT_SECONDS:-5}"

# First try the current Termux UID. On rooted devices, retry through su if
# logd access is filtered. Capture only our tag; do not clear global log buffers.
if ! logcat -d -v brief -s "$TAG" > "$OUT" 2>&1 ||
   ! grep -q "$TAG" "$OUT"; then
    if command -v su >/dev/null 2>&1; then
        su -c "logcat -d -v brief -s $TAG" > "$OUT" 2>&1 || true
    fi
fi

echo
echo "Probe log: $OUT"
if ! grep -q "$TAG" "$OUT"; then
    echo "INCOMPLETE: no $TAG messages are visible to this shell." >&2
    echo "If the report is visible on screen, the EGL probe may still have passed." >&2
    echo "On rooted devices, check manually with:" >&2
    echo "  su -c \"logcat -d -v brief | grep -F '$TAG' | tail -n 40\"" >&2
    exit 1
fi
cat "$OUT"

if grep -Eiq 'window surface swap: passed' "$OUT"; then
    echo "PASS: app-process Android Surface EGL swap succeeded."
    echo "NOTE: this does not test Weston-buffer import or zero-copy presentation."
else
    echo "INCOMPLETE: no successful Android Surface swap was reported." >&2
    echo "Inspect the EGL error above; this is not a Weston-buffer import test." >&2
    exit 2
fi
