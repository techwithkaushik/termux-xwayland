# Diagnostic logging

The project uses two log streams because the compositor helper runs as the Termux UID while the Android host runs as an Android app.

## 1. Android host (ADB / logcat)

Clear old logs, reproduce the issue, then collect only this app's tag:

```sh
adb logcat -c
# Reproduce once in Termux and open the Android host app.
adb logcat -v threadtime -s TermuxXWayland:I TermuxXWaylandClient:I '*:S'
```

For a wider capture including crashes and framework Binder errors:

```sh
adb logcat -v threadtime > termux-xwayland-logcat.txt
```

Stop the second command with Ctrl-C after reproducing. Do not share unrelated personal logs.

## 2. Native capture helper (Termux UID)

The helper emits structured records to stderr and, when `TERMUX_XWAYLAND_LOG` is set, appends the same diagnostic milestones to that file. Each structured line starts with `TXWLOG` and includes timestamp, sequence, PID, level, component and event.

```sh
mkdir -p "$PREFIX/tmp"
export TERMUX_XWAYLAND_LOG="$PREFIX/tmp/termux-xwayland-capture.log"
export XDG_RUNTIME_DIR="$PREFIX/tmp/termux-xwayland-10224"
export WAYLAND_DISPLAY=wayland-1

"$PREFIX/bin/termux-xwayland-capture" \
  --display "$WAYLAND_DISPLAY" --width 640 --height 360 --format rgba \
  >/dev/null 2>"$PREFIX/tmp/termux-xwayland-capture.stderr"
rc=$?
echo "helper_exit=$rc"
cat "$PREFIX/tmp/termux-xwayland-capture.log"
cat "$PREFIX/tmp/termux-xwayland-capture.stderr"
```

To avoid stale evidence, remove both log files before each run. Use `timeout 12s` if the helper does not exit itself.

## 3. Collect the minimum useful bundle

Please provide:
- `termux-xwayland-capture.log`
- `termux-xwayland-capture.stderr`
- A filtered `adb logcat -v threadtime -s TermuxXWayland:I TermuxXWaylandClient:I '*:S'` capture taken during the same reproduction
- The output of `termux-xwayland status`

Redact session tokens, authentication material and unrelated app logs before sharing.
