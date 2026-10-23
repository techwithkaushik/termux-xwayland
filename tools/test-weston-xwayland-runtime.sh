#!/usr/bin/env bash
# Run an isolated, bounded Weston + upstream XWayland + X11-client smoke test.
# Default mode nests Weston inside the current Termux:X11 DISPLAY. This tests
# protocol/window creation, not Android-native Surface presentation or zero-copy.
set -Eeuo pipefail

PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
PARENT_DISPLAY="${DISPLAY:-:0}"
TMP_ROOT="${TMPDIR:-$PREFIX/tmp}"
LOG_ROOT="${HOME:-.}/xw11-logs"
MODE="nested"

usage() {
  cat <<'USAGE'
Usage: bash tools/test-weston-xwayland-runtime.sh [--nested | --headless]

  --nested   Create a Weston X11-backend window on the current DISPLAY (default).
  --headless Test the compositor/XWayland protocol without a visible output.

This smoke test owns and stops only the Weston process it starts. It never
stops pre-existing Weston/XWayland servers and does not modify system files.
USAGE
}

while (($#)); do
  case "$1" in
    --nested) MODE="nested" ;;
    --headless) MODE="headless" ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
  shift
done

for command in weston xdpyinfo xterm xwininfo; do
  command -v "$command" >/dev/null 2>&1 || {
    echo "ERROR: required command not found: $command" >&2
    exit 127
  }
done

if [[ "$MODE" == "nested" ]]; then
  DISPLAY="$PARENT_DISPLAY" xdpyinfo >/dev/null 2>&1 || {
    echo "ERROR: parent X11 display is not reachable: $PARENT_DISPLAY" >&2
    exit 1
  }
fi

mkdir -p "$LOG_ROOT"
RUNTIME="$(mktemp -d "$TMP_ROOT/xw11-acceptance.XXXXXX")"
chmod 700 "$RUNTIME"
LOG="$LOG_ROOT/weston-xwayland-acceptance-$(basename "$RUNTIME").log"
CONSOLE_LOG="${LOG%.log}.console.log"
SOCKET="wayland-xw11-acceptance"

WESTON_PID=""
XTERM_PID=""
cleanup() {
  local status=$?
  trap - EXIT INT TERM
  if [[ -n "$XTERM_PID" ]]; then
    kill "$XTERM_PID" 2>/dev/null || true
    wait "$XTERM_PID" 2>/dev/null || true
  fi
  if [[ -n "$WESTON_PID" ]]; then
    kill -TERM "$WESTON_PID" 2>/dev/null || true
    # Give Weston time to stop XWayland and release its sockets.
    for _ in {1..20}; do
      kill -0 "$WESTON_PID" 2>/dev/null || break
      sleep 0.1
    done
    kill -KILL "$WESTON_PID" 2>/dev/null || true
    wait "$WESTON_PID" 2>/dev/null || true
  fi
  rm -rf "$RUNTIME"
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

WESTON_ARGS=(
  --renderer=pixman
  --shell=kiosk
  --xwayland
  "--socket=$SOCKET"
  --no-config
  "--log=$LOG"
)
if [[ "$MODE" == "nested" ]]; then
  WESTON_ARGS+=(--backend=x11)
else
  WESTON_ARGS+=(--backend=headless)
fi

echo "Mode: $MODE"
echo "Parent DISPLAY: $PARENT_DISPLAY"
echo "Runtime: $RUNTIME"
echo "Weston log: $LOG"

if [[ "$MODE" == "nested" ]]; then
  env DISPLAY="$PARENT_DISPLAY" XDG_RUNTIME_DIR="$RUNTIME" \
    PATH="$PREFIX/bin:$PATH" weston "${WESTON_ARGS[@]}" \
    >"$CONSOLE_LOG" 2>&1 &
else
  env XDG_RUNTIME_DIR="$RUNTIME" PATH="$PREFIX/bin:$PATH" \
    weston "${WESTON_ARGS[@]}" >"$CONSOLE_LOG" 2>&1 &
fi
WESTON_PID=$!

ready=0
for _ in {1..60}; do
  if [[ -S "$RUNTIME/$SOCKET" ]] && grep -q 'xserver listening on display' "$LOG" 2>/dev/null; then
    ready=1
    break
  fi
  if ! kill -0 "$WESTON_PID" 2>/dev/null; then
    break
  fi
  sleep 0.25
done

if (( ! ready )); then
  echo "FAIL: Weston/XWayland did not become ready." >&2
  tail -n 60 "$LOG" "$CONSOLE_LOG" 2>/dev/null || true
  exit 1
fi

XDISPLAY="$(sed -n 's/.*xserver listening on display :\([0-9][0-9]*\).*/\1/p' "$LOG" | tail -n 1)"
if [[ -z "$XDISPLAY" ]]; then
  echo "FAIL: could not parse XWayland display from Weston log." >&2
  exit 1
fi
echo "XWayland DISPLAY=:$XDISPLAY"

if ! DISPLAY=":$XDISPLAY" xdpyinfo >/dev/null 2>&1; then
  echo "FAIL: XWayland did not accept an X11 client connection." >&2
  tail -n 60 "$LOG" "$CONSOLE_LOG" 2>/dev/null || true
  exit 1
fi
echo "PASS: xdpyinfo connected to :$XDISPLAY"

DISPLAY=":$XDISPLAY" xterm -title "XW11 Acceptance Smoke Test" \
  -geometry 80x24+0+0 >"$LOG_ROOT/xw11-acceptance-xterm.log" 2>&1 &
XTERM_PID=$!
sleep 1

TREE="$(DISPLAY=":$XDISPLAY" xwininfo -root -tree 2>&1)"
if ! grep -Fq 'XW11 Acceptance Smoke Test' <<<"$TREE"; then
  echo "FAIL: X11 client window was not found in the root window tree." >&2
  printf '%s\n' "$TREE"
  tail -n 60 "$LOG" "$CONSOLE_LOG" 2>/dev/null || true
  exit 1
fi

echo "PASS: X11 client window was created."
printf '%s\n' "$TREE" | grep -F 'XW11 Acceptance Smoke Test' | head -n 5
echo "PASS: Weston + XWayland smoke test complete."
echo "NOTE: Pixman is CPU rendering; this does not test Android Surface/EGL import, zero-copy, or touch latency."
