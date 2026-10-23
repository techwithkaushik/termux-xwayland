#pragma once

/*
 * Android Surface frame consumer for the experimental XW11 presentation path.
 *
 * This module is intentionally not wired to LorieView's existing EGL renderer:
 * both paths must never own/present to the same Surface concurrently. A future
 * dedicated Wayland SurfaceView may call these functions from its native bridge.
 */
#include <android/native_window.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef enum xw11_present_result {
    XW11_PRESENT_OK = 0,
    XW11_PRESENT_ERR_ARGUMENT,
    XW11_PRESENT_ERR_STALE_GENERATION,
    XW11_PRESENT_ERR_DIMENSIONS,
    XW11_PRESENT_ERR_STRIDE,
    XW11_PRESENT_ERR_WINDOW_LOCK,
    XW11_PRESENT_ERR_WINDOW_SIZE
} xw11_present_result;

/* Takes its own ANativeWindow reference. A generation must be non-zero. */
xw11_present_result xw11_presenter_set_window(ANativeWindow *window,
                                               uint64_t generation);
/* Detaches only the matching generation; stale teardown is ignored. */
void xw11_presenter_clear_window(uint64_t generation);

/*
 * Copies one tightly bounded RGBA8888 frame to the active native window.
 * source_stride is measured in bytes. The caller retains ownership of pixels.
 * Calls must run off the Android UI thread. This is a CPU-copy diagnostic path,
 * not a zero-copy claim; the caller must not submit from an old Surface generation.
 */
xw11_present_result xw11_presenter_present_rgba8888(
    uint64_t generation, uint32_t width, uint32_t height,
    const uint8_t *pixels, size_t source_stride);

#ifdef __cplusplus
}
#endif
