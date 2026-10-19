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
check_grep '"$TERMUX_OPEN" "termuxxwayland://start?session_id=$session_id&server=running"' "$script" "Android host receives server-ready launch event"
check_grep 'Wayland server started; Android host launch request sent.' "$script" "start reports host launch handoff"

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
mock_content="$cli_tmp/content"
mock_weston="$cli_tmp/weston"
open_log="$cli_tmp/open.log"
control_log="$cli_tmp/control.log"
control_state="$cli_tmp/control-state"
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
cat > "$mock_content" <<'MOCK_CONTENT'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$MOCK_CONTROL_LOG"
method=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --method) method="${2:-}"; shift 2 ;;
    *) shift ;;
  esac
done
case "$method" in
  start)
    printf 'connected\n' > "$MOCK_CONTROL_STATE"
    printf 'Result: Bundle[{state=connected, protocol_version=1}]\n'
    ;;
  status)
    state=disconnected
    [[ ! -f "$MOCK_CONTROL_STATE" ]] || state="$(cat "$MOCK_CONTROL_STATE")"
    printf 'Result: Bundle[{state=%s, protocol_version=1}]\n' "$state"
    ;;
  stop)
    printf 'disconnected\n' > "$MOCK_CONTROL_STATE"
    printf 'Result: Bundle[{state=disconnected, protocol_version=1}]\n'
    ;;
  *) exit 2 ;;
esac
MOCK_CONTENT
cat > "$mock_weston" <<'MOCK_WESTON'
#!/usr/bin/env bash
set -euo pipefail
socket_name=wayland-1
for arg in "$@"; do
  case "$arg" in --socket=*) socket_name="${arg#--socket=}" ;; esac
done
exec python3 - "$XDG_RUNTIME_DIR/$socket_name" <<'PYTHON_WESTON'
import signal, socket, sys, time
server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
server.bind(sys.argv[1])
signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))
while True:
    time.sleep(1)
PYTHON_WESTON
MOCK_WESTON
chmod +x "$mock_pm" "$mock_open" "$mock_content" "$mock_weston"
cli_env=(env TERMUX_XWAYLAND_PM="$mock_pm" TERMUX_XWAYLAND_OPEN="$mock_open" TERMUX_XWAYLAND_CONTENT="$mock_content" TERMUX_XWAYLAND_WESTON="$mock_weston" TERMUX_XWAYLAND_RUNTIME_DIR="$cli_tmp/runtime" TERMUX_XWAYLAND_NOTIFY="$cli_tmp/no-notification-command" MOCK_OPEN_LOG="$open_log" MOCK_CONTROL_LOG="$control_log" MOCK_CONTROL_STATE="$control_state")

status_output="$("${cli_env[@]}" bash "$script" status)"
grep -Fq 'Android host: installed (' <<<"$status_output" || {
  echo "FAIL: status should detect a mock installed host" >&2; exit 1;
}
grep -Fq 'Android host connection: intent handoff only' <<<"$status_output" || {
  echo "FAIL: status should report no control session before start" >&2; exit 1;
}
grep -Fq 'Wayland frame stream: not connected' <<<"$status_output" || {
  echo "FAIL: status must not claim compositor streaming exists" >&2; exit 1;
}

"${cli_env[@]}" bash "$script" start >/dev/null
grep -Eq "^termuxxwayland://start\\?session_id=[a-f0-9]{32}&server=running$" "$open_log" || {
  echo "FAIL: start must open the host with a server-ready session ID" >&2; exit 1;
}
running_status="$("${cli_env[@]}" bash "$script" status)"
grep -Fq 'Wayland server: running' <<<"$running_status" || {
  echo "FAIL: status should detect the started Wayland server" >&2; exit 1;
}
grep -Fq 'Android host connection: intent handoff only' <<<"$running_status" || {
  echo "FAIL: status should report the accepted control handshake" >&2; exit 1;
}
grep -Fq 'Wayland frame stream: not connected' <<<"$running_status" || {
  echo "FAIL: status must not claim a connected stream without a transport" >&2; exit 1;
}
 
if MOCK_HOST_PRESENT=no "${cli_env[@]}" bash "$script" start >/dev/null 2>&1; then
  echo "FAIL: start should fail when no host package is detected" >&2; exit 1
fi
if MOCK_OPEN_FAIL=yes "${cli_env[@]}" bash "$script" start >/dev/null 2>&1; then
  echo "FAIL: start should report a failed deep-link launch" >&2; exit 1
fi
"${cli_env[@]}" bash "$script" stop >/dev/null
stopped_status="$("${cli_env[@]}" bash "$script" status)"
grep -Fq 'Wayland server: not running' <<<"$stopped_status" || {
  echo "FAIL: status should report the stopped Wayland server" >&2; exit 1;
}
 
echo "PASS: Termux companion package, host handoff, Weston lifecycle and status checks passed."
