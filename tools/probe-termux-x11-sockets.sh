#!/usr/bin/env bash
# Read-only diagnostic for Termux:X11 and XWayland Unix sockets.
set -uo pipefail

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  cat <<'USAGE'
Usage: bash tools/probe-termux-x11-sockets.sh

Read-only check of X11 display sockets and common Wayland runtime sockets.
Start Termux:X11 first (for example: termux-x11 :0). If testing XWayland,
also start the actual Wayland compositor/backend and XWayland server.
This script does not start or stop processes or change permissions.
USAGE
  exit 0
fi

termux_tmp="${TMPDIR:-}"
if [[ -z "$termux_tmp" && -n "${PREFIX:-}" ]]; then termux_tmp="$PREFIX/tmp"; fi
if [[ -z "$termux_tmp" ]]; then termux_tmp="/data/data/com.termux/files/usr/tmp"; fi
runtime_dir="${XDG_RUNTIME_DIR:-$termux_tmp}"
display_value="${DISPLAY:-:0}"
display_number="${display_value##*:}"
display_number="${display_number%%.*}"
if [[ ! "$display_number" =~ ^[0-9]+$ ]]; then display_number=0; fi

printf 'Termux:X11 / XWayland socket probe (read-only)\n'
printf 'UID: %s\n' "$(id -u 2>/dev/null || printf unknown)"
printf 'PREFIX: %s\n' "${PREFIX:-<unset>}"
printf 'TMPDIR: %s\n' "$termux_tmp"
printf 'XDG_RUNTIME_DIR: %s\n' "$runtime_dir"
printf 'DISPLAY: %s (X11 socket candidate X%s)\n\n' "$display_value" "$display_number"

declare -a dirs=()
add_dir() {
  local candidate="$1" existing
  [[ -n "$candidate" ]] || return 0
  for existing in "${dirs[@]:-}"; do [[ "$existing" == "$candidate" ]] && return 0; done
  dirs+=("$candidate")
}
add_dir "$termux_tmp"
add_dir "$runtime_dir"
add_dir "${PREFIX:+$PREFIX/tmp}"
add_dir "/tmp"

printf '%s\n' '== Runtime directories =='
for dir in "${dirs[@]}"; do
  if [[ -d "$dir" ]]; then ls -ld "$dir" 2>/dev/null || true
  else printf 'MISSING: %s\n' "$dir"; fi
done

printf '\n%s\n' '== X11 display socket candidates =='
declare -a x_candidates=(
  "$runtime_dir/.X11-unix/X$display_number"
  "$termux_tmp/.X11-unix/X$display_number"
  "/tmp/.X11-unix/X$display_number"
)
x_found=0
for path in "${x_candidates[@]}"; do
  if [[ -S "$path" ]]; then
    printf 'FOUND X11 Unix socket: %s\n' "$path"
    stat -c '  mode=%a owner=%u:%g' "$path" 2>/dev/null || true
    x_found=$((x_found + 1))
  elif [[ -e "$path" ]]; then printf 'EXISTS, but not a socket: %s\n' "$path"
  else printf 'NOT FOUND: %s\n' "$path"; fi
done

printf '\n%s\n' '== Wayland backend socket candidates =='
declare -a w_candidates=(
  "$runtime_dir/wayland-0"
  "$runtime_dir/wayland-1"
  "$termux_tmp/wayland-0"
  "$termux_tmp/wayland-1"
  "$runtime_dir/termux-x11"
  "$termux_tmp/termux-x11"
  "/tmp/wayland-0"
)
w_found=0
for path in "${w_candidates[@]}"; do
  if [[ -S "$path" ]]; then
    printf 'FOUND possible Wayland socket: %s\n' "$path"
    stat -c '  mode=%a owner=%u:%g' "$path" 2>/dev/null || true
    w_found=$((w_found + 1))
  elif [[ -e "$path" ]]; then printf 'EXISTS, but not a socket: %s\n' "$path"
  else printf 'NOT FOUND: %s\n' "$path"; fi
done

printf '\n%s\n' '== Other Unix sockets under runtime directories =='
for dir in "${dirs[@]}"; do
  [[ -d "$dir" ]] || continue
  find "$dir" -maxdepth 3 -type s -print 2>/dev/null
done

printf '\n%s\n' '== Relevant processes (if visible) =='
ps -A 2>/dev/null | grep -E '[t]ermux-x11|[X]wayland|[X]org|[w]eston|[w]lroots' || true

printf '\nRESULT: X11 socket candidates found: %d; Wayland socket candidates found: %d.\n' "$x_found" "$w_found"
if (( x_found > 0 && w_found == 0 )); then
  printf '%s\n' 'Interpretation: an X11 display socket exists, but no common Wayland socket path was found.'
  printf '%s\n' 'This alone does not prove whether Termux:X11 or XWayland created the X11 socket.'
elif (( x_found == 0 && w_found == 0 )); then
  printf '%s\n' 'No candidate sockets found. Verify server processes and their TMPDIR/XDG_RUNTIME_DIR.'
fi
printf '%s\n' 'Socket presence alone does not prove cross-app access or identify the owning server.'
