#!/usr/bin/env bash
# Fail-closed device acceptance probe for the real Wayland -> Binder frame path.
# This diagnoses readiness; it does not grant Weston capture authorization.
set -uo pipefail

fail=0
pass() { printf 'PASS: %s\n' "$*"; }
warn() { printf 'BLOCKED: %s\n' "$*"; fail=1; }
note() { printf 'INFO: %s\n' "$*"; }

prefix="${PREFIX:-/data/data/com.termux/files/usr}"
helper="${TERMUX_XWAYLAND_CAPTURE:-$prefix/bin/termux-xwayland-capture}"
launcher="$prefix/bin/termux-xwayland"
dex="$prefix/share/termux-xwayland/bridge-client.dex"

printf '%s\n' 'Termux-XWayland live-capture acceptance probe'
printf 'PREFIX=%s\n' "$prefix"

if command -v weston >/dev/null 2>&1; then
  pass "Weston executable: $(command -v weston)"
  weston --version 2>&1 | head -n 1 || true
else
  warn "Weston is not installed or not on PATH"
fi

if [[ -x "$launcher" ]]; then pass "Termux-XWayland launcher installed"; else warn "launcher missing: $launcher"; fi
if [[ -s "$dex" ]]; then pass "Binder client DEX installed"; else warn "Binder client DEX missing: $dex"; fi

if [[ -x "$helper" ]]; then
  pass "native capture helper executable: $helper"
  if command -v ldd >/dev/null 2>&1; then
    ldd "$helper" 2>&1 | sed -n '1,12p'
  fi
else
  warn "native capture helper is not installed; real desktop frames cannot be produced"
fi

if [[ -n "${WAYLAND_DISPLAY:-}" ]]; then
  pass "WAYLAND_DISPLAY is set to $WAYLAND_DISPLAY"
else
  note "WAYLAND_DISPLAY is unset in this shell; run this probe from the same Termux session or set it to the managed Weston socket"
fi

if command -v wayland-info >/dev/null 2>&1 && [[ -n "${WAYLAND_DISPLAY:-}" ]]; then
  if wayland-info 2>&1 | grep -q 'weston_capture_v1'; then
    pass "Weston capture protocol global is advertised"
  else
    warn "Weston capture protocol global not discovered (protocol may be unavailable on this socket)"
  fi
else
  note "wayland-info unavailable or WAYLAND_DISPLAY unset; protocol-global check skipped"
fi

if [[ -x "$helper" && -n "${WAYLAND_DISPLAY:-}" ]]; then
  note "Capture is NOT automatically started by this diagnostic: the compositor must independently authorize the helper."
  note "Run the managed launcher and inspect its logs for CAPTURE_STOPPED/unauthorized, FRAME_SENT, and Android received-frame counts."
fi

if (( fail )); then
  printf '%s\n' 'RESULT: NOT READY — at least one required component is missing.'
  exit 1
fi
printf '%s\n' 'RESULT: component checks passed only. This is NOT proof of live capture.'
printf '%s\n' 'Acceptance still requires repeated real FRAME_SENT events and a visibly changing Android preview on-device.'
