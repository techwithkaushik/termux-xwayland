#!/usr/bin/env bash
# Collect non-secret Android/Termux IPC prerequisites for the device probe.
# This script is diagnostic only: it sends no broadcasts and never prints tokens.
set -u

section() { printf '\n== %s ==\n' "$1"; }
have() { command -v "$1" >/dev/null 2>&1; }

section "Android runtime"
if have getprop; then
  printf 'SDK=%s\n' "$(getprop ro.build.version.sdk 2>/dev/null || true)"
  printf 'Android release=%s\n' "$(getprop ro.build.version.release 2>/dev/null || true)"
  printf 'Build fingerprint available=%s\n' "$([ -n "$(getprop ro.build.fingerprint 2>/dev/null || true)" ] && echo yes || echo no)"
else
  echo "getprop unavailable (run inside Termux on Android)"
fi

section "Termux package"
if have pm; then
  if pm path com.termux >/dev/null 2>&1; then
    echo "com.termux installed=yes"
    pm path com.termux 2>/dev/null | sed 's#=.*#=<installed>#'
  else
    echo "com.termux installed=no or package-manager query denied"
  fi
else
  echo "pm unavailable"
fi

if have dumpsys; then
  # Print only non-secret package metadata; intentionally avoid dumping all package state.
  dumpsys package com.termux 2>/dev/null | awk '
    /versionName=/ { print "Termux " $0; found_version=1 }
    /userId=/ { print "Termux " $0; found_uid=1 }
    END {
      if (!found_version) print "Termux version metadata unavailable"
      if (!found_uid) print "Termux UID metadata unavailable"
    }'
else
  echo "dumpsys unavailable"
fi

section "RUN_COMMAND permission (control-plane only)"
if have cmd; then
  result="$(cmd package check-permission com.termux.permission.RUN_COMMAND com.termux 2>/dev/null || true)"
  if [ -n "$result" ]; then
    printf 'com.termux.permission.RUN_COMMAND=%s\n' "$result"
  else
    echo "Permission state unavailable; inspect Termux app settings and package metadata manually."
  fi
else
  echo "cmd unavailable; permission state not checked"
fi

section "Important limits"
echo "This does not prove Binder access, command execution, cross-UID authorization, or streaming."
echo "No socket, broadcast, network listener, token, or session data was created or emitted."
echo "Share this output only after reviewing it; it contains Android version and package metadata."
