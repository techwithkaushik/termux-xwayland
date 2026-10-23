#!/usr/bin/env bash
# Launch the app-process EGL/Android-Surface probe and collect tagged diagnostics.
# Never clear logcat: preserve unrelated Android diagnostic history.
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

# The Activity reports its result to the screen and Android logcat. Use a
# post-run dump rather than a long-lived reader: logcat -T 1 can miss messages
# on some Android 10/vendor logd builds when the reader starts near launch.
sleep "${XW11_PROBE_WAIT_SECONDS:-5}"
logcat -d -v brief -s "$TAG" > "$OUT" 2>&1 || true

echo
echo "Probe log: $OUT"
if ! grep -q "$TAG" "$OUT"; then
    echo "FAIL: no $TAG messages captured from logcat." >&2
    echo "The on-screen report may still indicate success; verify that this APK logs the probe result." >&2
    echo "Recent app logs (filtered):" >&2
    logcat -d -v brief -s AndroidRuntime:E System.err:W "$TAG:V" 2>&1 | tail -n 80 >&2 || true
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
