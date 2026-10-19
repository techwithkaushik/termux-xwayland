#!/usr/bin/env bash
set -euo pipefail
test -f settings.gradle.kts
test -f build.gradle.kts
test -f android-app/build.gradle.kts
test -f android-app/src/main/AndroidManifest.xml
test -f android-app/src/main/java/org/techwithkaushik/termuxxwayland/MainActivity.kt
test -f android-app/src/main/java/org/techwithkaushik/termuxxwayland/BridgeProtocol.kt
test -f native/compositor/Cargo.toml
test -f native/compositor/src/main.rs
test -f native/compositor/src/protocol.rs
test -f native/compositor/src/bridge.rs
test -f docs/bridge-protocol.md
test -f tools/probe-termux-x11-sockets.sh
bash -n tools/probe-termux-x11-sockets.sh
test -f tools/probe-android-ipc-prereqs.sh
bash -n tools/probe-android-ipc-prereqs.sh
test -f tools/probe-weston-headless.sh
test -f docs/weston-headless-probe.md
bash -n tools/probe-weston-headless.sh
test -f tools/probe-weston-x11-client.sh
test -f docs/weston-xwayland-client-probe.md
bash -n tools/probe-weston-x11-client.sh
test -x tools/capture-x11-frame.sh
test -f docs/x11-capture-probe.md
bash -n tools/capture-x11-frame.sh
grep -q 'no real desktop rendering yet' README.md
grep -q 'synthetic RGBA test frame' README.md
grep -q 'Connect securely' android-app/src/main/java/org/techwithkaushik/termuxxwayland/MainActivity.kt
grep -q 'termux_xwayland_' native/compositor/src/bridge.rs
grep -q 'LocalSocketAddress.Namespace.ABSTRACT' android-app/src/main/java/org/techwithkaushik/termuxxwayland/MainActivity.kt
grep -q 'Cross-UID IPC passed' android-app/src/main/java/org/techwithkaushik/termuxxwayland/MainActivity.kt
grep -q 'candidate.peerCredentials' android-app/src/main/java/org/techwithkaushik/termuxxwayland/MainActivity.kt
grep -q 'BridgeMessageKind.PING' android-app/src/main/java/org/techwithkaushik/termuxxwayland/MainActivity.kt
grep -q 'libc::geteuid()' native/compositor/src/bridge.rs
grep -q 'MAX_PAYLOAD_LEN' native/compositor/src/protocol.rs
echo "Bridge, socket-probe syntax, and scaffold structure checks passed."
