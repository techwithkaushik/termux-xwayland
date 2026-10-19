#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
pkg="$root/termux-package"
script="$pkg/data/data/com.termux/files/usr/bin/termux-xwayland"

check_file() {
  if [[ ! -f "$1" ]]; then
    echo "FAIL: required file missing: $1" >&2
    exit 1
  fi
}
check_grep() {
  local pattern="$1" file="$2" description="$3"
  if ! grep -Fq -- "$pattern" "$file"; then
    echo "FAIL: $description" >&2
    echo "  file: $file" >&2
    echo "  expected text: $pattern" >&2
    exit 1
  fi
}

check_file "$pkg/DEBIAN/control"
check_file "$pkg/DEBIAN/postinst"
check_file "$script"

if ! bash -n "$pkg/DEBIAN/postinst"; then
  echo "FAIL: postinst shell syntax check" >&2
  exit 1
fi
if ! bash -n "$script"; then
  echo "FAIL: companion shell syntax check" >&2
  exit 1
fi

check_grep 'Package: termux-xwayland-bridge' "$pkg/DEBIAN/control" "package name metadata"
check_grep 'Architecture: all' "$pkg/DEBIAN/control" "package architecture metadata"
check_grep '"org.techwithkaushik.termuxxwayland"' "$script" "release host package ID"
check_grep '"org.techwithkaushik.termuxxwayland.debug"' "$script" "debug host package ID"
check_grep '"$SYSTEM_PM" path' "$script" "Android package detection command"
check_grep 'TERMUX_OPEN="${TERMUX_XWAYLAND_OPEN:-${PREFIX:-/data/data/com.termux/files/usr}/bin/termux-open}"' "$script" "Termux open command default"
check_grep '"$TERMUX_OPEN" "termuxxwayland://start"' "$script" "Android host deep-link launch"

if grep -q 'allow-external-apps' "$script" || grep -q 'RUN_COMMAND' "$script"; then
  echo "FAIL: companion must not depend on RUN_COMMAND or allow-external-apps." >&2
  exit 1
fi

if command -v dpkg-deb >/dev/null 2>&1; then
  tmp_out="$(mktemp -d)"
  trap 'rm -rf "$tmp_out"' EXIT
  if ! bash "$root/termux-package/build-deb.sh" "$tmp_out"; then
    echo "FAIL: DEB package build" >&2
    exit 1
  fi
  archive="$tmp_out/termux-xwayland-bridge_0.1.0_all.deb"
  check_file "$archive"
  [[ "$(dpkg-deb -f "$archive" Package)" == "termux-xwayland-bridge" ]] || {
    echo "FAIL: wrong package name in built DEB" >&2; exit 1;
  }
  [[ "$(dpkg-deb -f "$archive" Architecture)" == "all" ]] || {
    echo "FAIL: wrong architecture in built DEB" >&2; exit 1;
  }
  if dpkg-deb --contents "$archive" | grep -Fq './build-deb.sh'; then
    echo "FAIL: build tooling must not be installed at the package root." >&2
    exit 1
  fi
  if ! dpkg-deb --contents "$archive" | grep -Fq './data/data/com.termux/files/usr/bin/termux-xwayland'; then
    echo "FAIL: built DEB does not contain the companion executable at the expected path." >&2
    exit 1
  fi
fi


# Exercise the CLI behavior without Android by injecting mock executables.
# Production defaults remain /system/bin/pm and Termux's termux-open.
cli_tmp="$(mktemp -d)"
trap 'rm -rf "$cli_tmp" "${tmp_out:-}"' EXIT
mock_pm="$cli_tmp/pm"
mock_open="$cli_tmp/termux-open"
mock_notify="$cli_tmp/termux-notification"
open_log="$cli_tmp/open.log"
notify_log="$cli_tmp/notify.log"
cat > "$mock_pm" <<'MOCK_PM'
#!/usr/bin/env bash
if [[ "${1:-}" == path && "${MOCK_HOST_PRESENT:-yes}" == yes ]]; then
  case "${2:-}" in
    org.techwithkaushik.termuxxwayland|org.techwithkaushik.termuxxwayland.debug)
      printf 'package:/mock/base.apk\n'
      exit 0
      ;;
  esac
fi
exit 1
MOCK_PM
cat > "$mock_open" <<'MOCK_OPEN'
#!/usr/bin/env bash
[[ "${MOCK_OPEN_FAIL:-no}" != yes ]] || exit 1
printf '%s\n' "$*" >> "$MOCK_OPEN_LOG"
MOCK_OPEN
cat > "$mock_notify" <<'MOCK_NOTIFY'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$MOCK_NOTIFY_LOG"
MOCK_NOTIFY
chmod +x "$mock_pm" "$mock_open" "$mock_notify"
cli_env=(env TERMUX_XWAYLAND_PM="$mock_pm" TERMUX_XWAYLAND_OPEN="$mock_open" TERMUX_XWAYLAND_NOTIFY="$mock_notify" MOCK_OPEN_LOG="$open_log" MOCK_NOTIFY_LOG="$notify_log")

status_output="$("${cli_env[@]}" bash "$script" status)"
grep -Fq 'Android host: installed (' <<<"$status_output" || {
  echo "FAIL: status should detect a mock installed host" >&2; exit 1;
}
grep -Fq 'Live compositor/stream: not implemented yet' <<<"$status_output" || {
  echo "FAIL: status must not claim compositor streaming exists" >&2; exit 1;
}

start_output="$("${cli_env[@]}" bash "$script" start)"
grep -Fxq 'termuxxwayland://start' "$open_log" || {
  echo "FAIL: start must open the registered host deep link" >&2; exit 1;
}
grep -Fq 'Connection is not confirmed' <<<"$start_output" || {
  echo "FAIL: start must not claim the host is connected before a handshake" >&2; exit 1;
}
grep -Fq -- '--title Termux XWayland --content Android host found; launch requested. Connection is not confirmed yet.' "$notify_log" || {
  echo "FAIL: start should issue a truthful Termux notification when host launch is requested" >&2; exit 1;
}

if MOCK_HOST_PRESENT=no "${cli_env[@]}" bash "$script" start >/dev/null 2>&1; then
  echo "FAIL: start should fail when no host package is detected" >&2; exit 1
fi
if MOCK_OPEN_FAIL=yes "${cli_env[@]}" bash "$script" start >/dev/null 2>&1; then
  echo "FAIL: start should report a failed deep-link launch" >&2; exit 1
fi
stop_status=0
"${cli_env[@]}" bash "$script" stop >/dev/null 2>&1 || stop_status=$?
[[ "$stop_status" -eq 3 ]] || {
  echo "FAIL: stop should clearly return exit code 3 while session control is unimplemented" >&2; exit 1;
}

echo "PASS: Termux companion package structure, shell syntax, and DEB build checks passed."
