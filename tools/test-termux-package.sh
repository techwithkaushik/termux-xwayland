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
check_grep 'Depends: bash, coreutils, socat' "$pkg/DEBIAN/control" "loopback status monitor dependency"
check_grep 'Architecture: all' "$pkg/DEBIAN/control" "package architecture metadata"
check_grep '"org.techwithkaushik.termuxxwayland"' "$script" "release host package ID"
check_grep '"org.techwithkaushik.termuxxwayland.debug"' "$script" "debug host package ID"
check_grep '"$SYSTEM_PM" path' "$script" "Android package detection command"
check_grep 'TERMUX_OPEN="${TERMUX_XWAYLAND_OPEN:-${PREFIX:-/data/data/com.termux/files/usr}/bin/termux-open}"' "$script" "Termux open command default"
check_grep '"$TERMUX_OPEN" "termuxxwayland://start?session_id=$session_id&server=running&port=$port&token=$token"' "$script" "Android host receives authenticated server monitor details"
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
cli_tmp="$(mktemp -d)"
trap 'rm -rf "$cli_tmp" "${tmp_out:-}"' EXIT
mock_pm="$cli_tmp/pm"
mock_open="$cli_tmp/termux-open"
mock_weston="$cli_tmp/weston"
mock_socat="$cli_tmp/socat"
open_log="$cli_tmp/open.log"
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
cat > "$mock_socat" <<'MOCK_SOCAT'
#!/usr/bin/env bash
set -euo pipefail
port=""
for arg in "$@"; do
  case "$arg" in TCP-LISTEN:*) port="${arg#TCP-LISTEN:}"; port="${port%%,*}" ;; esac
done
[[ "$port" =~ ^[0-9]+$ ]] || exit 2
printf 'N listening on AF=2 127.0.0.1:%s\n' "$port" >&2
trap 'exit 0' TERM INT
while :; do sleep 1; done
MOCK_SOCAT
chmod +x "$mock_pm" "$mock_open" "$mock_weston" "$mock_socat"
cli_env=(env TERMUX_XWAYLAND_PM="$mock_pm" TERMUX_XWAYLAND_OPEN="$mock_open" TERMUX_XWAYLAND_WESTON="$mock_weston" TERMUX_XWAYLAND_SOCAT="$mock_socat" TERMUX_XWAYLAND_RUNTIME_DIR="$cli_tmp/runtime" TERMUX_XWAYLAND_NOTIFY="$cli_tmp/no-notification-command" MOCK_OPEN_LOG="$open_log")

status_output="$("${cli_env[@]}" bash "$script" status)"
grep -Fq 'Android host: installed (' <<<"$status_output" || {
  echo "FAIL: status should detect a mock installed host" >&2; exit 1;
}
grep -Fq 'Android host detection: not active' <<<"$status_output" || {
  echo "FAIL: status should report no monitor before start" >&2; exit 1;
}
grep -Fq 'Wayland frame stream: not connected' <<<"$status_output" || {
  echo "FAIL: status must not claim compositor streaming exists" >&2; exit 1;
}

"${cli_env[@]}" bash "$script" start >/dev/null
launch_uri="$(cat "$open_log")"
[[ "$launch_uri" == termuxxwayland://start\?session_id=* &&
   "$launch_uri" == *"&server=running&port="* &&
   "$launch_uri" == *"&token="* ]] || {
  echo "FAIL: start must send session ID, monitor port, and authenticated token (got: $launch_uri)" >&2; exit 1;
}
session_id="${launch_uri#*session_id=}"
session_id="${session_id%%&*}"
port="${launch_uri#*&port=}"
port="${port%%&*}"
token="${launch_uri#*&token=}"
[[ "$session_id" =~ ^[a-f0-9]{32}$ && "$port" =~ ^[0-9]{5}$ && "$token" =~ ^[a-f0-9]{64}$ ]] || {
  echo "FAIL: could not parse monitor session parameters" >&2; exit 1;
}
running_status="$("${cli_env[@]}" bash "$script" status)"
grep -Fq 'Wayland server: running' <<<"$running_status" || {
  echo "FAIL: status should detect the started Wayland server" >&2; exit 1;
}
grep -Fq 'Android host detection: waiting for host app' <<<"$running_status" || {
  echo "FAIL: status should report monitor awaiting first host poll" >&2; exit 1;
}
valid_response="$(printf 'STATUS %s\n' "$token" | "${cli_env[@]}" bash "$script" status-handler "$session_id" "$token")"
[[ "$valid_response" == "RUNNING $session_id" ]] || {
  echo "FAIL: authenticated status request should report running Weston" >&2; exit 1;
}
invalid_response="$(printf 'STATUS %s\n' "$(printf '0%.0s' {1..64})" | "${cli_env[@]}" bash "$script" status-handler "$session_id" "$token")"
[[ "$invalid_response" == "DENIED" ]] || {
  echo "FAIL: monitor must reject an incorrect token" >&2; exit 1;
}
active_status="$("${cli_env[@]}" bash "$script" status)"
grep -Fq 'Android host detection: active (host polling server status)' <<<"$active_status" || {
  echo "FAIL: status should record authenticated host polling" >&2; exit 1;
}
grep -Fq 'Wayland frame stream: not connected' <<<"$active_status" || {
  echo "FAIL: status must not claim desktop streaming exists" >&2; exit 1;
}

"${cli_env[@]}" bash "$script" stop >/dev/null
stopped_response="$(printf 'STATUS %s\\n' "$token" | "${cli_env[@]}" bash "$script" status-handler "$session_id" "$token")"
[[ "$stopped_response" == "STOPPED $session_id" ]] || {
  echo "FAIL: host monitor should detect when Weston stops" >&2; exit 1;
}
"${cli_env[@]}" bash "$script" monitor-stop

if MOCK_HOST_PRESENT=no "${cli_env[@]}" bash "$script" start >/dev/null 2>&1; then
  echo "FAIL: start should fail when no host package is detected" >&2; exit 1
fi
if MOCK_OPEN_FAIL=yes "${cli_env[@]}" bash "$script" start >/dev/null 2>&1; then
  echo "FAIL: start should report a failed deep-link launch" >&2; exit 1
fi
"${cli_env[@]}" bash "$script" stop >/dev/null
stopped_status="$("${cli_env[@]}" bash "$script" status)"
grep -Fq 'Wayland server: not running' <<<"$stopped_status" || {
  echo "FAIL: status should report the stopped server" >&2; exit 1;
}
echo "PASS: Termux package, authenticated host polling, Weston lifecycle and status checks passed."
