#!/usr/bin/env bash
# Run the isolated CPU-copy ANativeWindow presenter smoke test.
# Does not stop or reconfigure the existing Termux:X11/Lorie session.
set -Eeuo pipefail

PACKAGE="${XW11_PACKAGE:-org.techwithkaushik.termuxxw11}"
ACTIVITY="${XW11_ACTIVITY:-com.termux.x11.NativePresenterProbeActivity}"
TAG="XW11-PRESENTER-PROBE"
OUT="${1:-$HOME/xw11-presenter-probe.log}"

for cmd in am logcat grep; do
    command -v "$cmd" >/dev/null 2>&1 || {
        echo "ERROR: required Android command not found: $cmd" >&2
        exit 127
    }
done

mkdir -p "$(dirname "$OUT")"
: > "$OUT"

echo "Launching isolated native presenter probe: $PACKAGE/$ACTIVITY"
if ! am start -W -n "$PACKAGE/$ACTIVITY"; then
    echo "ERROR: Activity launch failed. Build and install an APK containing this probe." >&2
    exit 1
fi

sleep "${XW11_PRESENTER_WAIT_SECONDS:-4}"
if ! logcat -d -v brief -s "$TAG" > "$OUT" 2>&1 || ! grep -q "$TAG" "$OUT"; then
    if command -v su >/dev/null 2>&1; then
        su -c "logcat -d -v brief -s $TAG" > "$OUT" 2>&1 || true
    fi
fi

echo "Probe log: $OUT"
if ! grep -q "$TAG" "$OUT"; then
    echo "INCOMPLETE: no $TAG result found. Check the on-screen report and native XW11Surface logs." >&2
    exit 1
fi
cat "$OUT"

if grep -Fq 'PASS: native ANativeWindow CPU-copy frame posted' "$OUT"; then
    echo "PASS: synthetic RGBA frame reached the experimental native Surface presenter."
    echo "NOTE: this validates only the CPU-copy consumer; no Weston frame producer is connected."
else
    echo "FAIL: the native presenter did not confirm posting the test frame." >&2
    exit 2
fi
