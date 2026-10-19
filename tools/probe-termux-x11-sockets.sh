#!/usr/bin/env bash
# Read-only diagnostic for Termux:X11 / XWayland Unix sockets.
# Run after starting Termux:X11 and, if desired, an XWayland server.
set -uo pipefail

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  cat <<'USAGE'
Usage: bash tools/probe-termux-x11-sockets.sh

Read-only check of likely Termux runtime directories and Unix socket paths.
Start Termux:X11 first (for example: termux-x11 :0), then run this script.
If testing XWayland specifically, also start the XWayland server/client setup
used by your environment. This script does not start or stop any process.
USAGE
  exit 0
fi

termux_tmp="${TMPDIR:-}"
if [[ -z "$termux_tmp" && -n "${PREFIX:-}" ]]; then
  termux_tmp="$PREFIX/tmp"
fi
if [[ -z "$termux_tmp" ]]; then
  termux_tmp="/data/data/com.termux/files/usr/tmp"
fi
runtime_dir="${XDG_RUNTIME_DIR:-$termux_tmp}"
display_value="${DISPLAY:-:0}"
display_number="${display_value##*:}"
display_number="${display_number%%.*}"
if [[ ! "$display_number" =~ ^[0-9]+$ ]]; then
  display_number=0
fi

printf 'Termux:X11 socket probe (read-only)\n'
printf 'UID: %s\n' "$(id -u 2>/dev/null || printf unknown)"
printf 'PREFIX: %s\n' "${PREFIX:-<unset>}"
printf 'TMPDIR: %s\n' "$termux_tmp"
printf 'XDG_RUNTIME_DIR: %s\n' "$runtime_dir"
printf 'DISPLAY: %s (X11 socket candidate X%s)\n' "$display_value" "$display_number"
printf '\n'

declare -a dirs=()
add_dir() {
  local candidate="$1" existing
  [[ -n "$candidate" ]] || return 0
  for existing in "${dirs[@]:-}"; do
    [[ "$existing" == "$candidate" ]] && return 0
  done
  dirs+=("$candidate")
}
add_dir "$termux_tmp"
add_dir "$runtime_dir"
add_dir "${PREFIX:+$PREFIX/tmp}"
add_dir "/tmp"

printf '%s\n' '== Runtime directories =='
for dir in "${dirs[@]}"; do
  if [[ -d "$dir" ]]; then
    ls -ld "$dir" 2>/dev/null || true
  else
    printf 'MISSING: %s\n' "$dir"
  fi
done

printf '\n%s\n' '== Expected socket candidates =='
declare -a candidates=(
  "$runtime_dir/termux-x11"
  "$termux_tmp/termux-x11"
  "$runtime_dir/.X11-unix/X$display_number"
  "$termux_tmp/.X11-unix/X$display_number"
  "/tmp/.X11-unix/X$display_number"
)
found=0
for path in "${candidates[@]}"; do
  if [[ -S "$path" ]]; then
    printf 'FOUND Unix socket: %s\n' "$path"
    stat -c '  type=%F mode=%a owner=%u:%g' "$path" 2>/dev/null || true
    found=$((found + 1))
  elif [[ -e "$path" ]]; then
    printf 'EXISTS, but is not a socket: %s\n' "$path"
  else
    printf 'NOT FOUND: %s\n' "$path"
  fi
done

printf '\n%s\n' '== Other Unix sockets under runtime directories =='
for dir in "${dirs[@]}"; do
  [[ -d "$dir" ]] || continue
  find "$dir" -maxdepth 3 -type s -print 2>/dev/null
done

printf '\n%s\n' '== Relevant processes (if visible) =='
ps -A 2>/dev/null | grep -E '[t]ermux-x11|[X]wayland|[X]org' || true

printf '\n'
if (( found > 0 )); then
  printf 'RESULT: found %d expected socket candidate(s).\n' "$found"
  printf 'Note: presence alone does not prove that the socket is connectable or belongs to XWayland.\n'
else
  printf '%s\n' 'RESULT: no expected socket candidate found.'
  printf '%s\n' 'Check that the relevant server is running and that TMPDIR/XDG_RUNTIME_DIR match its environment.'
  exit 1
fi
