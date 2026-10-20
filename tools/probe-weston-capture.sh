#!/data/data/com.termux/files/usr/bin/bash
set -u

WAYLAND_INFO_BIN="${TERMUX_XWAYLAND_WAYLAND_INFO:-wayland-info}"
WAYLAND_DISPLAY_NAME="${WAYLAND_DISPLAY:-wayland-1}"
RUNTIME_DIR="${XDG_RUNTIME_DIR:-${TMPDIR:-/data/data/com.termux/files/usr/tmp}}"

if ! command -v "$WAYLAND_INFO_BIN" >/dev/null 2>&1; then
  printf 'Capture protocol probe: unavailable (wayland-info not installed)\n'
  printf 'Install the diagnostic tool with: pkg install wayland-utils\n'
  exit 2
fi

if [[ "$WAYLAND_DISPLAY_NAME" == */* ]]; then
  socket_path="$WAYLAND_DISPLAY_NAME"
else
  socket_path="$RUNTIME_DIR/$WAYLAND_DISPLAY_NAME"
fi

if [[ ! -S "$socket_path" ]]; then
  printf 'Capture protocol probe: unavailable (Wayland socket missing: %s)\n' "$socket_path"
  printf 'Start the server first: termux-xwayland start\n'
  exit 3
fi

output="$(WAYLAND_DISPLAY="$WAYLAND_DISPLAY_NAME" XDG_RUNTIME_DIR="$RUNTIME_DIR" "$WAYLAND_INFO_BIN" 2>&1)"
result=$?
if (( result != 0 )); then
  printf 'Capture protocol probe: failed to query Wayland globals (exit %s)\n' "$result"
  printf '%s\n' "$output" | tail -n 20
  exit "$result"
fi

if grep -Eq '(^|[[:space:]])weston_capture_v1([[:space:]]|$)' <<<"$output"; then
  printf 'Capture protocol probe: weston_capture_v1 global is advertised\n'
  printf 'Authorization: not tested; Weston requires compositor-side approval per capture request\n'
  printf 'Frame capture: not verified by this probe\n'
  exit 0
fi

printf 'Capture protocol probe: weston_capture_v1 global is not advertised\n'
printf 'This Weston build/session cannot use the current output-capture protocol path.\n'
exit 4
