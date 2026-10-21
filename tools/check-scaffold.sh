#!/usr/bin/env bash
set -euo pipefail

# Repository and Android host structure.
test -f settings.gradle.kts
test -f build.gradle.kts
test -f android-app/build.gradle.kts
test -f android-app/src/main/AndroidManifest.xml
test -f android-app/src/main/java/org/techwithkaushik/termuxxwayland/MainActivity.kt
test -f android-app/src/main/java/org/techwithkaushik/termuxxwayland/DesktopSurfaceView.kt
test ! -e android-app/src/main/java/org/techwithkaushik/termuxxwayland/CursorOverlayView.kt
test -f android-app/src/main/java/org/techwithkaushik/termuxxwayland/BridgeProtocol.kt
test -f android-app/src/main/java/org/techwithkaushik/termuxxwayland/BridgeSessionService.kt
test -f android-app/src/main/java/org/techwithkaushik/termuxxwayland/BridgeSessionReceiver.kt
test -f android-app/src/main/java/org/techwithkaushik/termuxxwayland/RgbaFrameCodec.kt
test -f android-app/src/main/cpp/native_egl_renderer.cpp
grep -q 'eglSwapBuffers' android-app/src/main/cpp/native_egl_renderer.cpp
grep -q 'pendingDesktopFrame.set(null)' android-app/src/main/java/org/techwithkaushik/termuxxwayland/MainActivity.kt

# Native Wayland capture helper and protocol.
test -f native/capture/termux-xwayland-capture.c
test -f native/capture/weston-output-capture.xml
grep -q 'weston_capture_v1_create' native/capture/termux-xwayland-capture.c
grep -q 'interface name="weston_capture_v1" version="2"' native/capture/weston-output-capture.xml
test -f tools/build-native-capture.sh
bash -n tools/build-native-capture.sh
grep -q 'weston-output-capture.xml' tools/build-native-capture.sh

# Termux companion package and supported build/test scripts.
test -f termux-package/DEBIAN/control
test -f termux-package/DEBIAN/postinst
test -f termux-package/data/data/com.termux/files/usr/bin/termux-xwayland
test -f termux-package/src/BridgeClient.java
test -f tools/build-termux-binder-client.sh
test -f tools/test-termux-package.sh
test -f termux-package/build-deb.sh
bash -n tools/build-termux-binder-client.sh
bash -n tools/test-termux-package.sh
bash -n termux-package/build-deb.sh
grep -q 'startFrameForwarder' termux-package/src/BridgeClient.java
grep -q 'FRAME_CAPTURE_UNAVAILABLE' termux-package/src/BridgeClient.java
! grep -qi 'ffmpeg' termux-package/src/BridgeClient.java
grep -q 'native/capture/termux-xwayland-capture.c' termux-package/build-deb.sh
grep -q 'build-native-capture.sh' termux-package/build-deb.sh

# Native compositor structure.
test -f native/compositor/Cargo.toml
test -f native/compositor/src/main.rs
test -f native/compositor/src/protocol.rs
test -f native/compositor/src/bridge.rs
grep -q 'MAX_PAYLOAD_LEN' native/compositor/src/protocol.rs

echo "Android host, native capture, Termux package, and compositor scaffold checks passed."
bash tools/test-termux-package.sh
