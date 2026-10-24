#include "xw11_surface_generation.h"

#include <limits.h>

void xw11_surface_generation_init(xw11_surface_generation *state) {
    if (state == nullptr)
        return;
    state->generation = 0;
    state->active = 0;
}

xw11_surface_result xw11_surface_generation_created(
    xw11_surface_generation *state, uint64_t *out_generation) {
    if (state == nullptr || out_generation == nullptr)
        return XW11_SURFACE_ERR_ARGUMENT;
    if (state->generation == UINT64_MAX)
        return XW11_SURFACE_ERR_EXHAUSTED;

    ++state->generation;
    state->active = 1;
    *out_generation = state->generation;
    return XW11_SURFACE_OK;
}

xw11_surface_result xw11_surface_generation_destroyed(
    xw11_surface_generation *state, uint64_t generation) {
    if (state == nullptr)
        return XW11_SURFACE_ERR_ARGUMENT;
    if (generation == 0 || generation != state->generation)
        return XW11_SURFACE_ERR_STALE;
    if (!state->active)
        return XW11_SURFACE_OK;

    state->active = 0;
    return XW11_SURFACE_OK;
}

xw11_surface_result xw11_surface_generation_accepts(
    const xw11_surface_generation *state, uint64_t generation) {
    if (state == nullptr)
        return XW11_SURFACE_ERR_ARGUMENT;
    if (!state->active)
        return XW11_SURFACE_ERR_INACTIVE;
    if (generation == 0 || generation != state->generation)
        return XW11_SURFACE_ERR_STALE;
    return XW11_SURFACE_OK;
}
