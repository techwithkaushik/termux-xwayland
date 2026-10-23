#pragma once

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef enum xw11_surface_result {
    XW11_SURFACE_OK = 0,
    XW11_SURFACE_ERR_ARGUMENT,
    XW11_SURFACE_ERR_INACTIVE,
    XW11_SURFACE_ERR_STALE,
    XW11_SURFACE_ERR_EXHAUSTED
} xw11_surface_result;

/*
 * Portable lifecycle gate for the Android Surface presentation adapter.
 * This is deliberately independent of Android headers so the state machine
 * can be tested on the host before it is wired to ANativeWindow/JNI.
 */
typedef struct xw11_surface_generation {
    uint64_t generation;
    int active;
} xw11_surface_generation;

void xw11_surface_generation_init(xw11_surface_generation *state);
xw11_surface_result xw11_surface_generation_created(
    xw11_surface_generation *state, uint64_t *out_generation);
xw11_surface_result xw11_surface_generation_destroyed(
    xw11_surface_generation *state, uint64_t generation);
xw11_surface_result xw11_surface_generation_accepts(
    const xw11_surface_generation *state, uint64_t generation);

#ifdef __cplusplus
}
#endif
