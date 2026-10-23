#!/usr/bin/env bash
set -Eeuo pipefail

# Collect non-invasive Android graphics capability hints before choosing the
# XWayland -> Weston -> Android native-buffer transport. This does not modify
# the device and intentionally avoids dumping the full environment or logcat.
OUT="${1:-xw11-device-probe.txt}"
TMP="${OUT}.tmp"
trap 'rm -f "$TMP"' EXIT

section() { printf '\n===== %s =====\n' "$1"; }
run_if_available() {
    local cmd="$1"
    shift
    if command -v "$cmd" >/dev/null 2>&1; then
        printf '\n--- %s ---\n' "$cmd"
        "$@" 2>&1 || printf '[probe failed: %s]\n' "$cmd"
    else
        printf '\n--- %s: not installed ---\n' "$cmd"
    fi
}

{
    section "Android and ABI"
    printf 'date_utc='; date -u '+%Y-%m-%dT%H:%M:%SZ'
    printf 'uname='; uname -a
    for prop in ro.build.version.release ro.build.version.sdk ro.product.cpu.abi ro.product.cpu.abilist ro.hardware ro.board.platform; do
        if command -v getprop >/dev/null 2>&1; then
            printf '%s=' "$prop"; getprop "$prop"
        fi
    done

    section "Graphics device nodes"
    for path in /dev/dri /dev/dma_heap /dev/ion /dev/kgsl-3d0 /dev/mali0 /dev/pvr; do
        if [[ -e "$path" ]]; then
            ls -ld "$path"
            if [[ -d "$path" ]]; then
                find "$path" -maxdepth 1 -mindepth 1 -printf '%M %p\n' 2>/dev/null || ls -la "$path"
            fi
        else
            printf 'absent: %s\n' "$path"
        fi
    done

    section "Relevant libraries"
    for root in /system/lib /system/lib64 /vendor/lib /vendor/lib64 "${PREFIX:-}/lib"; do
        [[ -d "$root" ]] || continue
        printf '-- %s --\n' "$root"
        find "$root" -maxdepth 2 -type f \( \
            -iname '*EGL*' -o -iname '*GLES*' -o -iname '*vulkan*' -o \
            -iname '*gralloc*' -o -iname '*hardware*' -o -iname '*drm*' -o \
            -iname '*gbm*' -o -iname '*wayland*' \
        \) -print 2>/dev/null | head -n 120
    done

    section "Available diagnostic tools"
    for cmd in eglinfo es2_info glxinfo vulkaninfo weston Xwayland termux-xwayland termux-xw11; do
        if command -v "$cmd" >/dev/null 2>&1; then
            printf '%s=%s\n' "$cmd" "$(command -v "$cmd")"
        else
            printf '%s=not-found\n' "$cmd"
        fi
    done

    section "EGL and Vulkan hints"
    run_if_available eglinfo eglinfo
    run_if_available vulkaninfo vulkaninfo --summary

    section "SurfaceFlinger graphics summary"
    if command -v dumpsys >/dev/null 2>&1; then
        dumpsys SurfaceFlinger 2>&1 | grep -Ei 'GLES:|EGL|renderer|vendor|OpenGL|composition|HWC|display.*format|supported.*format|protected' | head -n 100 || true
    else
        echo 'dumpsys is not available in this shell.'
    fi

    section "Interpretation limits"
    cat <<'LIMITS'
This report is a capability hint, not proof that buffer import/export works.
File presence and EGL extension strings do not prove that Weston can export a
buffer which Android SurfaceFlinger can import with correct synchronization.
Do not choose a zero-copy transport until an actual producer/consumer probe
confirms handle transfer, format/modifier compatibility, fence behavior, and
safe buffer release on the target device.
LIMITS
} > "$TMP"

mv "$TMP" "$OUT"
trap - EXIT
printf 'Wrote graphics capability report: %s\n' "$OUT"
