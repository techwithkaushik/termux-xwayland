#!/usr/bin/env bash
set -Eeuo pipefail

# Configure and compile the standalone upstream XWayland release for Android.
# Dependencies must already be built for the requested ABI in --prefix.
ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
ABI="armeabi-v7a"
API="24"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
PREFIX=""
SOURCE_DIR="$ROOT_DIR/build/third_party/xwayland-24.1.14"
BUILD_DIR=""
FONT_PATH="${TERMUX_PREFIX:-}/share/fonts"

usage() {
    cat <<'USAGE'
Usage: tools/build-xwayland-android.sh --prefix DIR [options]

Required:
  --prefix DIR       Staging prefix containing Android target-ABI dependencies

Options:
  --abi ABI          armeabi-v7a, arm64-v8a, x86, or x86_64 (default: armeabi-v7a)
  --api LEVEL        Android API level (default: 24)
  --ndk DIR          Android NDK (defaults to ANDROID_NDK_HOME/ANDROID_NDK_ROOT)
  --source DIR       Extracted upstream XWayland 24.1.14 source directory
  --build-dir DIR    Meson build directory (default: build/meson/<ABI>)
  --font-path DIR    Runtime font directory (default: $TERMUX_PREFIX/share/fonts)
  -h, --help         Show this help

This script does not build dependencies. It fails if the staged prefix or
required pkg-config metadata is missing. It never modifies the legacy Xlorie
target.
USAGE
}

while (($#)); do
    case "$1" in
        --abi) ABI="${2:?missing value for --abi}"; shift 2 ;;
        --api) API="${2:?missing value for --api}"; shift 2 ;;
        --ndk) NDK="${2:?missing value for --ndk}"; shift 2 ;;
        --prefix) PREFIX="${2:?missing value for --prefix}"; shift 2 ;;
        --source) SOURCE_DIR="${2:?missing value for --source}"; shift 2 ;;
        --build-dir) BUILD_DIR="${2:?missing value for --build-dir}"; shift 2 ;;
        --font-path) FONT_PATH="${2:?missing value for --font-path}"; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) echo "ERROR: unknown argument: $1" >&2; usage >&2; exit 2 ;;
    esac
done

case "$ABI" in
    armeabi-v7a|arm64-v8a|x86|x86_64) ;;
    *) echo "ERROR: unsupported Android ABI: $ABI" >&2; exit 2 ;;
esac
[[ "$API" =~ ^[0-9]+$ ]] && (( API >= 21 && API <= 100 )) || {
    echo "ERROR: Android API must be an integer from 21 to 100" >&2
    exit 2
}
[[ -n "$PREFIX" ]] || { echo "ERROR: --prefix is required" >&2; exit 2; }
[[ -n "$NDK" ]] || { echo "ERROR: pass --ndk or set ANDROID_NDK_HOME" >&2; exit 2; }
[[ -d "$PREFIX" ]] || { echo "ERROR: staging prefix not found: $PREFIX" >&2; exit 2; }
PREFIX="$(cd -- "$PREFIX" && pwd)"

for tool in python3 meson ninja pkg-config; do
    command -v "$tool" >/dev/null 2>&1 || { echo "ERROR: required tool missing: $tool" >&2; exit 127; }
done

if [[ ! -f "$SOURCE_DIR/meson.build" || ! -f "$SOURCE_DIR/hw/xwayland/meson.build" ]]; then
    bash "$ROOT_DIR/tools/fetch-xwayland-source.sh" "$ROOT_DIR/build/third_party"
fi
[[ -f "$SOURCE_DIR/meson.build" && -f "$SOURCE_DIR/hw/xwayland/meson.build" ]] || {
    echo "ERROR: standalone XWayland source is not present at $SOURCE_DIR" >&2
    exit 2
}

if [[ -z "$BUILD_DIR" ]]; then
    BUILD_DIR="$ROOT_DIR/build/meson/$ABI"
fi
CROSS_DIR="$ROOT_DIR/build/meson-cross/$ABI"
CROSS_FILE="$CROSS_DIR/android-cross.ini"
mkdir -p "$CROSS_DIR"
python3 "$ROOT_DIR/tools/generate-android-meson-cross-file.py" \
    --abi "$ABI" --api "$API" --ndk "$NDK" --prefix "$PREFIX" --output "$CROSS_FILE"

if [[ -z "$FONT_PATH" || "$FONT_PATH" == "/share/fonts" ]]; then
    echo "ERROR: set TERMUX_PREFIX or pass --font-path to the runtime font directory" >&2
    exit 2
fi

meson setup "$BUILD_DIR" "$SOURCE_DIR" \
    --cross-file "$CROSS_FILE" \
    --prefix "$PREFIX" \
    -Dmitshm=true -Dxres=true -Dxv=true -Dsecure-rpc=false \
    -Dscreensaver=true -Dxdmcp=true -Dglx=true -Ddri3=true \
    -Dxinerama=true -Dxace=true -Dxcsecurity=true -Dxf86bigfont=true \
    -Ddrm=true -Dglamor=false -Dxvfb=false -Dlibunwind=false \
    -Dipv6=true -Dsha1=libcrypto -Ddefault_font_path="$FONT_PATH"

meson compile -C "$BUILD_DIR"

python3 - "$BUILD_DIR" <<'PY'
import json
import subprocess
import sys

build_dir = sys.argv[1]
targets = json.loads(subprocess.check_output(
    ["meson", "introspect", "--targets", build_dir], text=True
))
names = {target.get("name", "") for target in targets}
if not any(name.lower() == "xwayland" for name in names):
    print("ERROR: build completed but Meson target introspection did not find Xwayland", file=sys.stderr)
    print("Targets: " + ", ".join(sorted(names)), file=sys.stderr)
    raise SystemExit(1)
print("Verified Meson target metadata includes Xwayland.")
PY

echo "XWayland Android build completed for ABI=$ABI API=$API"
echo "Build directory: $BUILD_DIR"
echo "NOTE: this is a compile check only; compositor startup and Android device rendering/input still require runtime validation."
