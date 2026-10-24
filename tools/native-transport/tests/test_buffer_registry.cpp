#include "xw11_buffer_registry.h"

#include <assert.h>
#include <stdio.h>

int main() {
    xw11_buffer_registry registry = {};
    xw11_buffer_registry_init(&registry);
    assert(xw11_buffer_registry_in_flight_count(&registry) == 0);
    assert(xw11_buffer_registry_submit(nullptr, 1, 1) == XW11_BUFFER_REGISTRY_ERR_ARGUMENT);
    assert(xw11_buffer_registry_submit(&registry, 0, 1) == XW11_BUFFER_REGISTRY_ERR_ID);
    assert(xw11_buffer_registry_submit(&registry, 1, 0) == XW11_BUFFER_REGISTRY_ERR_GENERATION);

    assert(xw11_buffer_registry_submit(&registry, 7, 10) == XW11_BUFFER_REGISTRY_OK);
    assert(xw11_buffer_registry_submit(&registry, 7, 10) == XW11_BUFFER_REGISTRY_ERR_DUPLICATE);
    assert(xw11_buffer_registry_release(&registry, 7, 11) == XW11_BUFFER_REGISTRY_ERR_GENERATION);
    assert(xw11_buffer_registry_in_flight_count(&registry) == 1);
    assert(xw11_buffer_registry_release(&registry, 7, 10) == XW11_BUFFER_REGISTRY_OK);
    assert(xw11_buffer_registry_release(&registry, 7, 10) == XW11_BUFFER_REGISTRY_ERR_NOT_IN_FLIGHT);

    for (uint32_t i = 1; i <= XW11_BUFFER_REGISTRY_CAPACITY; ++i)
        assert(xw11_buffer_registry_submit(&registry, i, i < 5 ? 20 : 21) == XW11_BUFFER_REGISTRY_OK);
    assert(xw11_buffer_registry_submit(&registry, 100, 22) == XW11_BUFFER_REGISTRY_ERR_FULL);
    assert(xw11_buffer_registry_abandon_generation(&registry, 20) == 4);
    assert(xw11_buffer_registry_in_flight_count(&registry) == XW11_BUFFER_REGISTRY_CAPACITY - 4);
    assert(xw11_buffer_registry_submit(&registry, 1, 22) == XW11_BUFFER_REGISTRY_OK);
    assert(xw11_buffer_registry_abandon_all(&registry) == XW11_BUFFER_REGISTRY_CAPACITY - 3);
    assert(xw11_buffer_registry_in_flight_count(&registry) == 0);
    assert(xw11_buffer_registry_abandon_all(&registry) == 0);
    assert(xw11_buffer_registry_abandon_generation(nullptr, 1) == 0);

    puts("PASS: XW11 buffer lifetime registry");
    return 0;
}
