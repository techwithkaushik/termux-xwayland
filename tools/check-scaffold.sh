#!/usr/bin/env bash
set -euo pipefail
test -f settings.gradle.kts
test -f build.gradle.kts
test -f android-app/build.gradle.kts
test -f android-app/src/main/AndroidManifest.xml
test -f android-app/src/main/java/org/techwithkaushik/termuxxwayland/MainActivity.kt
test -f native/compositor/Cargo.toml
test -f native/compositor/src/main.rs
grep -q 'Not connected' android-app/src/main/java/org/techwithkaushik/termuxxwayland/MainActivity.kt
grep -q 'Scaffold only' native/compositor/src/main.rs
echo "Scaffold structure checks passed."
