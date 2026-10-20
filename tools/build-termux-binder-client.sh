#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
out="${1:-$root/dist/termux-binder-client}"
sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/.android/sdk}}"
source_file="$root/termux-package/src/BridgeClient.java"

[[ -f "$source_file" ]] || { echo "Missing Binder client source: $source_file" >&2; exit 1; }
[[ -d "$sdk_root/platforms" && -d "$sdk_root/build-tools" ]] || {
  echo "Android SDK platforms/build-tools are required to compile the Termux app_process client." >&2
  exit 1
}

android_jar="$(find "$sdk_root/platforms" -mindepth 2 -maxdepth 2 -name android.jar -type f | sort -V | tail -n 1)"
d8="$(find "$sdk_root/build-tools" -type f -name d8 | sort -V | tail -n 1)"
[[ -f "$android_jar" ]] || { echo "Could not find android.jar in $sdk_root/platforms" >&2; exit 1; }
[[ -x "$d8" ]] || { echo "Could not find executable d8 in $sdk_root/build-tools" >&2; exit 1; }
command -v javac >/dev/null 2>&1 || { echo "JDK javac is required." >&2; exit 127; }

rm -rf "$out"
mkdir -p "$out/classes" "$out/dex"
javac -source 8 -target 8 -classpath "$android_jar" -d "$out/classes" "$source_file"
mapfile -t class_files < <(find "$out/classes" -type f -name '*.class' -print)
(("${#class_files[@]}" > 0)) || { echo "javac produced no client classes." >&2; exit 1; }
"$d8" --min-api 29 --output "$out/dex" "${class_files[@]}"
cp "$out/dex/classes.dex" "$out/bridge-client.dex"
test -s "$out/bridge-client.dex"
echo "Built Termux app_process Binder client DEX: $out/bridge-client.dex"
