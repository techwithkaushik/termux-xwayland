#!/usr/bin/env bash
set -euo pipefail
test -f settings.gradle.kts
test -f build.gradle.kts
test -f android-app/build.gradle.kts
test -f android-app/src/main/AndroidManifest.xml
test -f android-app/src/main/java/org/techwithkaushik/termuxxwayland/MainActivity.kt
test -f native/compositor/Cargo.toml
test -f native/compositor/src/main.rs
test -f native/compositor/src/protocol.rs
test -f docs/bridge-protocol.md
grep -q 'Not connected' android-app/src/main/java/org/techwithkaushik/termuxxwayland/MainActivity.kt
grep -q 'bridge protocol v' native/compositor/src/main.rs
grep -q 'MAX_PAYLOAD_LEN' native/compositor/src/protocol.rs
echo "Scaffold and protocol structure checks passed."
