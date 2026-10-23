#pragma once

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define XW11_BUFFER_REGISTRY_CAPACITY 16u

typedef enum xw11_buffer_registry_result {
    XW11_BUFFER_REGISTRY_OK = 0,
    XW11_BUFFER_REGISTRY_ERR_ARGUMENT,
    XW11_BUFFER_REGISTRY_ERR_ID,
    XW11_BUFFER_REGISTRY_ERR_GENERATION,
    XW11_BUFFER_REGISTRY_ERR_DUPLICATE,
    XW11_BUFFER_REGISTRY_ERR_NOT_IN_FLIGHT,
    XW11_BUFFER_REGISTRY_ERR_FULL
} xw11_buffer_registry_result;

typedef struct xw11_buffer_registry_entry {
    uint32_t buffer_id;
    uint64_t generation;
    uint8_t in_flight;
} xw11_buffer_registry_entry;

typedef struct xw11_buffer_registry {
    xw11_buffer_registry_entry entries[XW11_BUFFER_REGISTRY_CAPACITY];
} xw11_buffer_registry;

/* Caller serializes access; this allocation-free primitive has no internal locks. */
void xw11_buffer_registry_init(xw11_buffer_registry *registry);

/* Reserve a buffer ID until release or abandonment. Duplicate in-flight IDs fail. */
xw11_buffer_registry_result xw11_buffer_registry_submit(
    xw11_buffer_registry *registry, uint32_t buffer_id, uint64_t generation);

/* Release only the exact in-flight buffer/generation pair. */
xw11_buffer_registry_result xw11_buffer_registry_release(
    xw11_buffer_registry *registry, uint32_t buffer_id, uint64_t generation);

/* Abandon buffers belonging to a destroyed Surface generation. Returns count. */
uint32_t xw11_buffer_registry_abandon_generation(
    xw11_buffer_registry *registry, uint64_t generation);

/* Abandon every in-flight buffer after peer disconnect. Returns count. */
uint32_t xw11_buffer_registry_abandon_all(xw11_buffer_registry *registry);

uint32_t xw11_buffer_registry_in_flight_count(
    const xw11_buffer_registry *registry);

#ifdef __cplusplus
}
#endif
