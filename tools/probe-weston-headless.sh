#!/usr/bin/env bash
# Read-only feasibility check: launch Weston headless briefly and inspect its log.
# Does not install packages, alter permissions outside a private temporary dir,
# or expose a network listener.
set -u

for cmd in weston Xwayland mktemp; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    printf 'MISSING: %s\n' "$cmd" >&2
    exit 2
  fi
done

workdir="$(mktemp -d "${TMPDIR:-/tmp}/termux-weston-probe.XXXXXX")" || exit 2
chmod 700 "$workdir"
runtime="$workdir/runtime"
mkdir -m 700 "$runtime"
log="$workdir/weston.log"
pid=""

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
  printf '\nLog: %s\n' "$log"
  printf 'Probe files retained in: %s\n' "$workdir"
}
trap cleanup EXIT INT TERM

export XDG_RUNTIME_DIR="$runtime"
export WAYLAND_DISPLAY=wayland-probe

printf 'Weston: %s\n' "$(weston --version 2>&1 | head -1)"
printf 'XWayland: %s\n' "$(Xwayland -version 2>&1 | head -1)"
printf 'Starting a short headless/pixman Weston session with XWayland enabled...\n'

weston --backend=headless --renderer=pixman --socket="$WAYLAND_DISPLAY" \
  --no-config --log="$log" --xwayland >"$workdir/stdout.log" 2>&1 &
pid=$!

ready=0
for _ in $(seq 1 12); do
  if [[ -S "$runtime/$WAYLAND_DISPLAY" ]]; then
    ready=1
    break
  fi
  if ! kill -0 "$pid" 2>/dev/null; then
    break
  fi
  sleep 1
done

if [[ "$ready" -eq 1 ]]; then
  printf '\nRESULT: Weston created its Wayland socket.\n'
else
  printf '\nRESULT: Weston did not create its Wayland socket within 12 seconds.\n'
fi

printf '\n=== Weston log (last 100 lines) ===\n'
if [[ -f "$log" ]]; then tail -n 100 "$log"; else echo '(no Weston log created)'; fi
printf '\n=== Weston stdout/stderr (last 60 lines) ===\n'
tail -n 60 "$workdir/stdout.log" 2>/dev/null || true

if [[ "$ready" -ne 1 ]]; then
  exit 1
fi

if grep -Eqi 'xwayland.*(failed|error)|failed.*xwayland|could not.*xwayland' "$log" "$workdir/stdout.log"; then
  printf '\nNOTE: The Wayland socket started, but logs contain a possible XWayland error. Inspect the log above.\n'
  exit 1
fi

printf '\nNOTE: Socket creation proves only a headless compositor startup. It does not prove a client rendered or frames reached Android.\n'
