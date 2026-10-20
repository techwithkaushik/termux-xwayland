#!/usr/bin/env bash
set -euo pipefail

test -f settings.gradle.kts
test -f build.gradle.kts
test -f android-app/build.gradle.kts
test -f android-app/src/main/AndroidManifest.xml
test -f android-app/src/main/java/org/techwithkaushik/termuxxwayland/MainActivity.kt
test -f android-app/src/main/java/org/techwithkaushik/termuxxwayland/BridgeProtocol.kt
test -f android-app/src/main/java/org/techwithkaushik/termuxxwayland/NativeBridge.kt
test -f native/host-jni/Cargo.toml
test -f native/host-jni/src/lib.rs
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
grep -q 'termux_xwayland_' native/compositor/src/bridge.rs
grep -q 'Rust JNI socket reader' android-app/src/main/java/org/techwithkaushik/termuxxwayland/MainActivity.kt
grep -q 'Run Rust JNI socket test' android-app/src/main/java/org/techwithkaushik/termuxxwayland/MainActivity.kt
grep -q 'nativeReadSocket' android-app/src/main/java/org/techwithkaushik/termuxxwayland/NativeBridge.kt
grep -q 'Java_org_techwithkaushik_termuxxwayland_NativeBridge_nativeReadSocket' native/host-jni/src/lib.rs
grep -q 'libc::geteuid()' native/compositor/src/bridge.rs
grep -q 'MAX_PAYLOAD_LEN' native/compositor/src/protocol.rs
echo "Bridge, JNI, socket-probe syntax, and scaffold structure checks passed."

test -f termux-package/DEBIAN/control
test -f termux-package/DEBIAN/postinst
test -f termux-package/data/data/com.termux/files/usr/bin/termux-xwayland
test -f termux-package/build-deb.sh
bash -n termux-package/build-deb.sh
bash tools/test-termux-package.sh
