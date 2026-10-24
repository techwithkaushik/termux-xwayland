#include "xw11_buffer_registry.h"

void xw11_buffer_registry_init(xw11_buffer_registry *registry) {
    if (registry == nullptr)
        return;
    for (uint32_t i = 0; i < XW11_BUFFER_REGISTRY_CAPACITY; ++i) {
        registry->entries[i].buffer_id = 0;
        registry->entries[i].generation = 0;
        registry->entries[i].in_flight = 0;
    }
}

xw11_buffer_registry_result xw11_buffer_registry_submit(
    xw11_buffer_registry *registry, uint32_t buffer_id, uint64_t generation) {
    if (registry == nullptr)
        return XW11_BUFFER_REGISTRY_ERR_ARGUMENT;
    if (buffer_id == 0)
        return XW11_BUFFER_REGISTRY_ERR_ID;
    if (generation == 0)
        return XW11_BUFFER_REGISTRY_ERR_GENERATION;

    xw11_buffer_registry_entry *free_entry = nullptr;
    for (uint32_t i = 0; i < XW11_BUFFER_REGISTRY_CAPACITY; ++i) {
        xw11_buffer_registry_entry *entry = &registry->entries[i];
        if (entry->in_flight && entry->buffer_id == buffer_id)
            return XW11_BUFFER_REGISTRY_ERR_DUPLICATE;
        if (!entry->in_flight && free_entry == nullptr)
            free_entry = entry;
    }
    if (free_entry == nullptr)
        return XW11_BUFFER_REGISTRY_ERR_FULL;

    free_entry->buffer_id = buffer_id;
    free_entry->generation = generation;
    free_entry->in_flight = 1;
    return XW11_BUFFER_REGISTRY_OK;
}

xw11_buffer_registry_result xw11_buffer_registry_release(
    xw11_buffer_registry *registry, uint32_t buffer_id, uint64_t generation) {
    if (registry == nullptr)
        return XW11_BUFFER_REGISTRY_ERR_ARGUMENT;
    if (buffer_id == 0)
        return XW11_BUFFER_REGISTRY_ERR_ID;
    if (generation == 0)
        return XW11_BUFFER_REGISTRY_ERR_GENERATION;

    for (uint32_t i = 0; i < XW11_BUFFER_REGISTRY_CAPACITY; ++i) {
        xw11_buffer_registry_entry *entry = &registry->entries[i];
        if (entry->in_flight && entry->buffer_id == buffer_id) {
            if (entry->generation != generation)
                return XW11_BUFFER_REGISTRY_ERR_GENERATION;
            entry->buffer_id = 0;
            entry->generation = 0;
            entry->in_flight = 0;
            return XW11_BUFFER_REGISTRY_OK;
        }
    }
    return XW11_BUFFER_REGISTRY_ERR_NOT_IN_FLIGHT;
}

uint32_t xw11_buffer_registry_abandon_generation(
    xw11_buffer_registry *registry, uint64_t generation) {
    if (registry == nullptr || generation == 0)
        return 0;
    uint32_t abandoned = 0;
    for (uint32_t i = 0; i < XW11_BUFFER_REGISTRY_CAPACITY; ++i) {
        xw11_buffer_registry_entry *entry = &registry->entries[i];
        if (entry->in_flight && entry->generation == generation) {
            entry->buffer_id = 0;
            entry->generation = 0;
            entry->in_flight = 0;
            ++abandoned;
        }
    }
    return abandoned;
}

uint32_t xw11_buffer_registry_abandon_all(xw11_buffer_registry *registry) {
    if (registry == nullptr)
        return 0;
    uint32_t abandoned = 0;
    for (uint32_t i = 0; i < XW11_BUFFER_REGISTRY_CAPACITY; ++i) {
        xw11_buffer_registry_entry *entry = &registry->entries[i];
        if (entry->in_flight) {
            entry->buffer_id = 0;
            entry->generation = 0;
            entry->in_flight = 0;
            ++abandoned;
        }
    }
    return abandoned;
}

uint32_t xw11_buffer_registry_in_flight_count(
    const xw11_buffer_registry *registry) {
    if (registry == nullptr)
        return 0;
    uint32_t count = 0;
    for (uint32_t i = 0; i < XW11_BUFFER_REGISTRY_CAPACITY; ++i)
        count += registry->entries[i].in_flight ? 1u : 0u;
    return count;
}
