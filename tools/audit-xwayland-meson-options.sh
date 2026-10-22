#!/usr/bin/env bash
set -Eeuo pipefail

# Check that the Meson options used by Termux's Android XWayland recipe
# actually exist in the pinned upstream release before attempting a cross-build.
SOURCE_DIR="${1:-build/third_party/xwayland-24.1.14}"
OPTIONS_FILE="$SOURCE_DIR/meson_options.txt"

if [[ ! -f "$OPTIONS_FILE" ]]; then
    echo "ERROR: Meson options file not found: $OPTIONS_FILE" >&2
    exit 2
fi

# These option names are taken from Termux's XWayland package recipe.
# This is an option-presence audit only; it does not claim that the Android
# dependencies are built or that a cross-compilation succeeds.
options=(
    mitshm
    xres
    xv
    secure-rpc
    screensaver
    xdmcp
    glx
    dri3
    xinerama
    xace
    xcsecurity
    xf86bigfont
    drm
    glamor
    xvfb
    libunwind
    ipv6
    sha1
    default_font_path
)

failures=0
for option_name in "${options[@]}"; do
    if grep -Eq "option[[:space:]]*\([[:space:]]*['\"]${option_name}['\"]" "$OPTIONS_FILE"; then
        printf 'OK: Meson option exists: %s\n' "$option_name"
    else
        printf 'MISSING: Meson option not found upstream: %s\n' "$option_name" >&2
        failures=$((failures + 1))
    fi
done

if (( failures > 0 )); then
    echo "ERROR: $failures Termux recipe option(s) need reconciliation with upstream." >&2
    exit 1
fi

echo "All Termux recipe option names are present in the pinned upstream source."
