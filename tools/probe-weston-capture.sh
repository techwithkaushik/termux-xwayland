#!/data/data/com.termux/files/usr/bin/bash
set -u

WAYLAND_INFO_BIN="${TERMUX_XWAYLAND_WAYLAND_INFO:-wayland-info}"
WAYLAND_DISPLAY_NAME="${WAYLAND_DISPLAY:-wayland-1}"
RUNTIME_DIR="${TERMUX_XWAYLAND_RUNTIME_DIR:-${XDG_RUNTIME_DIR:-${TMPDIR:-/data/data/com.termux/files/usr/tmp}/termux-xwayland-${UID:-$(id -u)}}}"
LOG_FILE="${TERMUX_XWAYLAND_WESTON_LOG:-$RUNTIME_DIR/weston.log}"
CONSOLE_LOG_FILE="${TERMUX_XWAYLAND_WESTON_CONSOLE_LOG:-$RUNTIME_DIR/weston-console.log}"

if [[ "$WAYLAND_DISPLAY_NAME" == */* ]]; then
  socket_path="$WAYLAND_DISPLAY_NAME"
else
  socket_path="$RUNTIME_DIR/$WAYLAND_DISPLAY_NAME"
fi

if [[ ! -S "$socket_path" ]]; then
  printf 'Capture protocol probe: unavailable (Wayland socket missing: %s)\n' "$socket_path"
  printf 'Run termux-xwayland status and start the server first.\n'
  exit 3
fi

if command -v "$WAYLAND_INFO_BIN" >/dev/null 2>&1; then
  output="$(WAYLAND_DISPLAY="$WAYLAND_DISPLAY_NAME" XDG_RUNTIME_DIR="$RUNTIME_DIR" "$WAYLAND_INFO_BIN" 2>&1)"
  result=$?
  if (( result != 0 )); then
    printf 'Capture protocol probe: failed to query Wayland globals (exit %s)\n' "$result"
    printf '%s\n' "$output" | tail -n 20
    exit "$result"
  fi
  if grep -Eq '(^|[[:space:]])weston_capture_v1([[:space:]]|$)' <<<"$output"; then
    printf 'Capture protocol probe: weston_capture_v1 global is advertised\n'
    printf 'Authorization: not tested; compositor-side approval is still required\n'
    printf 'Frame capture: not verified by this probe\n'
    exit 0
  fi
  printf 'Capture protocol probe: weston_capture_v1 global is not advertised\n'
  exit 4
fi

printf 'Capture protocol probe: wayland-info is unavailable in this Termux repository.\n'
printf 'Weston version: '
weston --version 2>&1 || true
printf '\nCapture-related Weston log lines (if any):\n'
found=0
for file in "$LOG_FILE" "$CONSOLE_LOG_FILE"; do
  if [[ -r "$file" ]]; then
    matches="$(grep -Ein 'capture|weston_capture|output.*screenshot|xwayland|error|warning' "$file" | tail -n 25 || true)"
    if [[ -n "$matches" ]]; then
      printf '%s\n%s\n' "--- $file ---" "$matches"
      found=1
    fi
  fi
done
if (( found == 0 )); then
  printf 'No matching lines found in the default log paths.\n'
fi
printf '\nThis fallback only inspects logs; it cannot confirm protocol advertisement or capture authorization.\n'
printf 'Set TERMUX_XWAYLAND_WESTON_LOG and TERMUX_XWAYLAND_WESTON_CONSOLE_LOG if your logs are elsewhere.\n'
exit 2
