#!/usr/bin/env bash
# Inspect whether this Termux installation has the development interfaces
# needed to build a native Wayland capture client or a Weston authorization
# module. This is diagnostic only: it does not install packages or alter state.
set -u

have() {
  if command -v "$1" >/dev/null 2>&1; then
    printf 'FOUND: %s (%s)\n' "$1" "$(command -v "$1")"
  else
    printf 'MISSING: %s\n' "$1"
  fi
}

printf 'Weston capture build prerequisites\n'
printf 'Architecture: %s\n' "$(uname -m)"
printf 'Weston: '
if command -v weston >/dev/null 2>&1; then weston --version 2>&1 | head -n 1; else echo 'not installed'; fi

for tool in cc clang pkg-config wayland-scanner wayland-info; do have "$tool"; done
printf '\nWayland client development metadata:\n'
if command -v pkg-config >/dev/null 2>&1; then
  for module in wayland-client wayland-protocols; do
    if pkg-config --exists "$module"; then
      printf 'FOUND: %s %s\n' "$module" "$(pkg-config --modversion "$module")"
    else
      printf 'MISSING: pkg-config module %s\n' "$module"
    fi
  done
  for module in libweston-16 libweston-16-private; do
    if pkg-config --exists "$module"; then
      printf 'FOUND: %s %s\n' "$module" "$(pkg-config --modversion "$module")"
    else
      printf 'MISSING: pkg-config module %s (Weston-side module build unavailable via pkg-config)\n' "$module"
    fi
  done
else
  echo 'pkg-config unavailable; development metadata cannot be checked.'
fi

printf '\nHeader and protocol locations:\n'
for path in \
  "${PREFIX:-/data/data/com.termux/files/usr}/include/wayland-client.h" \
  "${PREFIX:-/data/data/com.termux/files/usr}/share/wayland-protocols" \
  "${PREFIX:-/data/data/com.termux/files/usr}/include/libweston-16" \
  "${PREFIX:-/data/data/com.termux/files/usr}/include/libweston-16/libweston" \
  "${PREFIX:-/data/data/com.termux/files/usr}/include/weston"; do
  if [[ -e "$path" ]]; then printf 'FOUND: %s\n' "$path"; else printf 'MISSING: %s\n' "$path"; fi
done

cat <<'NOTE'

Interpretation:
- A Wayland client can enumerate globals, but cannot grant itself permission to
  use Weston's privileged weston_capture_v1 interface.
- Continuous capture needs compositor-side authorization and a renderer path
  that services capture tasks. If Weston development/private headers or the
  relevant renderer support are missing, do not try to fake this with a client
  helper; a compatible Weston build/module is required.
- This probe does not claim that capture is enabled or that any frames exist.
NOTE
