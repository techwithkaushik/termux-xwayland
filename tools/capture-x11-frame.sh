#!/usr/bin/env bash
set -euo pipefail

# Capture one real X11 frame for diagnosis. This does not connect the Android
# app to the bridge; it verifies that the Termux X11 display is capturable.
usage() {
  echo "Usage: $0 OUTPUT.png [WIDTHxHEIGHT]" >&2
  echo "Example: $0 ~/x11-check.png 320x180" >&2
}

if [[ $# -lt 1 || $# -gt 2 ]]; then
  usage
  exit 2
fi
if [[ -z "${DISPLAY:-}" ]]; then
  echo "ERROR: DISPLAY is unset; start your X11/XWayland session first." >&2
  exit 1
fi
if ! command -v ffmpeg >/dev/null 2>&1; then
  echo "ERROR: ffmpeg is required (Termux: pkg install ffmpeg)." >&2
  exit 1
fi

output=$1
size=${2:-320x180}
if [[ ! "$size" =~ ^[1-9][0-9]*x[1-9][0-9]*$ ]]; then
  echo "ERROR: size must be WIDTHxHEIGHT with positive integers." >&2
  exit 2
fi

mkdir -p "$(dirname "$output")"
ffmpeg -hide_banner -loglevel error -y \
  -f x11grab -video_size "$size" -i "$DISPLAY" \
  -frames:v 1 -update 1 "$output"

if [[ ! -s "$output" ]]; then
  echo "ERROR: ffmpeg returned without creating a non-empty image." >&2
  exit 1
fi
printf 'Captured X11 frame: %s (%s)\n' "$output" "$size"
