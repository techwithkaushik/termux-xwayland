#include "xw11_surface_generation.h"

#include <cassert>
#include <cstdint>
#include <limits>

int main() {
    xw11_surface_generation state{};
    uint64_t first = 0;
    uint64_t second = 0;

    xw11_surface_generation_init(&state);
    assert(xw11_surface_generation_accepts(&state, 1) ==
           XW11_SURFACE_ERR_INACTIVE);

    assert(xw11_surface_generation_created(&state, &first) ==
           XW11_SURFACE_OK);
    assert(first == 1);
    assert(xw11_surface_generation_accepts(&state, first) ==
           XW11_SURFACE_OK);
    assert(xw11_surface_generation_accepts(&state, first + 1) ==
           XW11_SURFACE_ERR_STALE);

    assert(xw11_surface_generation_destroyed(&state, first) ==
           XW11_SURFACE_OK);
    assert(xw11_surface_generation_destroyed(&state, first) ==
           XW11_SURFACE_OK);  // idempotent teardown
    assert(xw11_surface_generation_accepts(&state, first) ==
           XW11_SURFACE_ERR_INACTIVE);

    assert(xw11_surface_generation_created(&state, &second) ==
           XW11_SURFACE_OK);
    assert(second == first + 1);
    assert(xw11_surface_generation_accepts(&state, first) ==
           XW11_SURFACE_ERR_STALE);
    assert(xw11_surface_generation_accepts(&state, second) ==
           XW11_SURFACE_OK);
    assert(xw11_surface_generation_destroyed(&state, first) ==
           XW11_SURFACE_ERR_STALE);

    state.generation = std::numeric_limits<uint64_t>::max();
    assert(xw11_surface_generation_created(&state, &first) ==
           XW11_SURFACE_ERR_EXHAUSTED);
    assert(xw11_surface_generation_created(nullptr, &first) ==
           XW11_SURFACE_ERR_ARGUMENT);
    assert(xw11_surface_generation_created(&state, nullptr) ==
           XW11_SURFACE_ERR_ARGUMENT);
    assert(xw11_surface_generation_destroyed(nullptr, 1) ==
           XW11_SURFACE_ERR_ARGUMENT);
    assert(xw11_surface_generation_accepts(nullptr, 1) ==
           XW11_SURFACE_ERR_ARGUMENT);
    return 0;
}
