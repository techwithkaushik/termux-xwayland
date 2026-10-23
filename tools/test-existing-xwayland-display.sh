#!/usr/bin/env bash
# Non-destructive acceptance test for an already-running XWayland display.
# It creates and closes only its own temporary X11 client window; it never
# starts, stops, or signals Weston/XWayland or any existing desktop session.
set -Eeuo pipefail

DISPLAY_TO_TEST="${1:-${DISPLAY:-}}"
if [[ -z "$DISPLAY_TO_TEST" ]]; then
    echo "Usage: DISPLAY=:1 bash tools/test-existing-xwayland-display.sh [DISPLAY]" >&2
    exit 2
fi

for command in xdpyinfo xterm xwininfo; do
    command -v "$command" >/dev/null 2>&1 || {
        echo "ERROR: required command not found: $command" >&2
        exit 127
    }
done

DISPLAY="$DISPLAY_TO_TEST" xdpyinfo >/dev/null 2>&1 || {
    echo "FAIL: X11 display is not reachable: $DISPLAY_TO_TEST" >&2
    exit 1
}

TITLE="XW11-existing-display-acceptance-$$"
LOG_DIR="${TMPDIR:-/tmp}"
CLIENT_LOG="$LOG_DIR/xw11-existing-display-$$.log"
XTERM_PID=""

cleanup() {
    local status=$?
    trap - EXIT INT TERM
    if [[ -n "$XTERM_PID" ]]; then
        kill "$XTERM_PID" 2>/dev/null || true
        wait "$XTERM_PID" 2>/dev/null || true
    fi
    rm -f "$CLIENT_LOG"
    exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

DISPLAY="$DISPLAY_TO_TEST" xterm -title "$TITLE" -geometry 80x24+0+0 \
    >"$CLIENT_LOG" 2>&1 &
XTERM_PID=$!

found=0
for _ in {1..40}; do
    if ! kill -0 "$XTERM_PID" 2>/dev/null; then
        break
    fi
    TREE="$(DISPLAY="$DISPLAY_TO_TEST" xwininfo -root -tree 2>/dev/null || true)"
    if grep -Fq "$TITLE" <<<"$TREE"; then
        found=1
        break
    fi
    sleep 0.25
done

if (( ! found )); then
    echo "FAIL: xterm started but its window was not found on $DISPLAY_TO_TEST." >&2
    DISPLAY="$DISPLAY_TO_TEST" xwininfo -root -tree >&2 || true
    cat "$CLIENT_LOG" >&2 || true
    exit 1
fi

echo "PASS: xdpyinfo connected to $DISPLAY_TO_TEST"
echo "PASS: created and discovered X11 client window: $TITLE"
echo "PASS: existing XWayland/Weston processes were not touched"
