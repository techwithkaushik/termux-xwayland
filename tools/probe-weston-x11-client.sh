#!/usr/bin/env bash
# Run one already-installed X11 test client as a child of Weston.
# No packages are installed; no TCP listener or permission/SELinux changes.
set -u

for cmd in weston Xwayland mktemp; do
  command -v "$cmd" >/dev/null 2>&1 || { printf 'MISSING: %s\n' "$cmd" >&2; exit 2; }
done

client=""
for candidate in xterm xeyes xclock xmessage; do
  if command -v "$candidate" >/dev/null 2>&1; then
    client="$candidate"
    break
  fi
done

if [[ -z "$client" ]]; then
  printf 'No supported X11 smoke-test client found (checked: xterm, xeyes, xclock, xmessage).\n'
  printf 'Install one from your configured Termux repositories, then rerun; this script will not install packages.\n'
  exit 3
fi

workdir="$(mktemp -d "${TMPDIR:-/tmp}/termux-weston-x11-client.XXXXXX")" || exit 2
chmod 700 "$workdir"
runtime="$workdir/runtime"
mkdir -m 700 "$runtime"
log="$workdir/weston.log"
stdout_log="$workdir/stdout.log"
pid=""
x11_socket_dir="${PREFIX:-/data/data/com.termux/files/usr}/tmp/.X11-unix"
created_x11_socket_dir=0

cleanup() {
  if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
    kill -TERM "$pid" 2>/dev/null || true
    for _ in 1 2 3 4 5; do
      kill -0 "$pid" 2>/dev/null || break
      sleep 1
    done
    kill -KILL "$pid" 2>/dev/null || true
    wait "$pid" 2>/dev/null || true
  fi
  if [[ "$created_x11_socket_dir" -eq 1 ]]; then
    rmdir "$x11_socket_dir" 2>/dev/null || printf 'NOTE: Leaving non-empty X11 socket directory: %s\n' "$x11_socket_dir"
  fi
  printf '\nLogs retained at: %s\n' "$workdir"
}
trap cleanup EXIT INT TERM

if [[ ! -e "$x11_socket_dir" ]]; then
  if mkdir -m 1777 "$x11_socket_dir" 2>/dev/null; then
    created_x11_socket_dir=1
    chmod 1777 "$x11_socket_dir" || exit 2
  else
    printf 'ERROR: Cannot create X11 socket directory: %s\n' "$x11_socket_dir" >&2
    exit 2
  fi
elif [[ ! -d "$x11_socket_dir" ]]; then
  printf 'ERROR: X11 socket path is not a directory: %s\n' "$x11_socket_dir" >&2
  exit 2
fi

export XDG_RUNTIME_DIR="$runtime"
export WAYLAND_DISPLAY=wayland-x11-probe

printf 'Using X11 test client: %s\n' "$client"
printf 'Starting Weston headless with XWayland and the test client...\n'
weston --backend=headless --renderer=pixman --socket="$WAYLAND_DISPLAY" \
  --no-config --log="$log" --xwayland -- "$client" >"$stdout_log" 2>&1 &
pid=$!

ready=0
for _ in $(seq 1 15); do
  if [[ -S "$runtime/$WAYLAND_DISPLAY" ]]; then
    ready=1
    break
  fi
  if ! kill -0 "$pid" 2>/dev/null; then break; fi
  sleep 1
done

sleep 2
if [[ "$ready" -eq 1 ]]; then
  printf '\nRESULT: Weston created its Wayland socket.\n'
else
  printf '\nRESULT: Weston did not create its Wayland socket.\n'
fi
if [[ -S "$x11_socket_dir/X0" ]]; then
  printf 'X11 socket: present (%s/X0)\n' "$x11_socket_dir"
else
  printf 'X11 socket: not present at %s/X0\n' "$x11_socket_dir"
fi
if grep -Eqi 'xwayland.*(failed|error)|failed.*xwayland|could not.*xwayland|cannot open display|unable to open display' "$log" "$stdout_log"; then
  printf 'Client/compositor logs contain a possible XWayland or display error.\n'
else
  printf 'No obvious XWayland/display error matched in logs (not proof of a visible surface).\n'
fi
printf '\n=== Weston log (last 120 lines) ===\n'
[[ ! -f "$log" ]] || tail -n 120 "$log"
printf '\n=== Client/stdout log (last 80 lines) ===\n'
tail -n 80 "$stdout_log" 2>/dev/null || true
printf '\nThis headless test cannot prove Android frame export or on-screen visibility.\n'
[[ "$ready" -eq 1 ]]
