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
grep -q 'native Wayland capture' README.md
grep -q 'direct abstract AF_UNIX socket' README.md
grep -q 'Run legacy socket diagnostic' android-app/src/main/java/org/techwithkaushik/termuxxwayland/MainActivity.kt
grep -q 'Direct socket transport active' android-app/src/main/java/org/techwithkaushik/termuxxwayland/MainActivity.kt
grep -q 'statusPollHandler.post(statusPollRunnable)' android-app/src/main/java/org/techwithkaushik/termuxxwayland/MainActivity.kt
grep -q 'statusPollHandler.removeCallbacks(statusPollRunnable)' android-app/src/main/java/org/techwithkaushik/termuxxwayland/MainActivity.kt
grep -q 'termux_xwayland_' native/compositor/src/bridge.rs
grep -q 'LocalSocketAddress.Namespace.ABSTRACT' android-app/src/main/java/org/techwithkaushik/termuxxwayland/MainActivity.kt
grep -q 'Legacy AF_UNIX diagnostic' android-app/src/main/java/org/techwithkaushik/termuxxwayland/MainActivity.kt
grep -q 'candidate.peerCredentials' android-app/src/main/java/org/techwithkaushik/termuxxwayland/MainActivity.kt
grep -q 'BridgeMessageKind.PING' android-app/src/main/java/org/techwithkaushik/termuxxwayland/MainActivity.kt
grep -q 'libc::geteuid()' native/compositor/src/bridge.rs
grep -q 'MAX_PAYLOAD_LEN' native/compositor/src/protocol.rs
! grep -q 'ipc-companion' settings.gradle.kts
! grep -q 'ipc-companion' .github/workflows/ci.yml
grep -q 'SESSION_HEALTHY epoch_seconds=' termux-package/src/BridgeClient.java
grep -q 'LocalServerSocket(socketName)' android-app/src/main/java/org/techwithkaushik/termuxxwayland/BridgeSocketServer.kt
grep -q 'socket.peerCredentials.uid' android-app/src/main/java/org/techwithkaushik/termuxxwayland/BridgeSocketServer.kt
grep -q 'LocalSocketAddress.Namespace.ABSTRACT' termux-package/src/BridgeClient.java
! grep -q 'bindService(' termux-package/src/BridgeClient.java
! grep -q 'createTermuxContext' termux-package/src/BridgeClient.java
grep -q 'startFrameForwarder' termux-package/src/BridgeClient.java
grep -q 'XWayland frame capture: sending recent frames' termux-package/data/data/com.termux/files/usr/bin/termux-xwayland
grep -q 'termux-xwayland x11' termux-package/data/data/com.termux/files/usr/bin/termux-xwayland
echo "Bridge, socket-probe syntax, and scaffold structure checks passed."

test -f termux-package/DEBIAN/control
test -f termux-package/DEBIAN/postinst
test -f termux-package/data/data/com.termux/files/usr/bin/termux-xwayland
test -f termux-package/src/BridgeClient.java
test -f tools/build-termux-binder-client.sh
bash -n tools/build-termux-binder-client.sh
test -f termux-package/build-deb.sh
bash -n termux-package/build-deb.sh
bash tools/test-termux-package.sh
