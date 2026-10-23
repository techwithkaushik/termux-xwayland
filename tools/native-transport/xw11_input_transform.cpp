#include "xw11_input_transform.h"

#include <stdint.h>

extern "C" xw11_input_transform_result xw11_map_input_coordinates(
    const xw11_input_transform *t, uint64_t event_generation,
    int32_t view_x, int32_t view_y, uint32_t *out_x, uint32_t *out_y) {
    if (t == nullptr || out_x == nullptr || out_y == nullptr)
        return XW11_INPUT_TRANSFORM_ERR_ARGUMENT;
    if (t->generation == 0 || event_generation == 0 ||
        event_generation != t->generation)
        return XW11_INPUT_TRANSFORM_ERR_GENERATION;
    if (t->viewport_width == 0 || t->viewport_height == 0 ||
        t->output_width == 0 || t->output_height == 0 ||
        t->viewport_width > INT32_MAX || t->viewport_height > INT32_MAX)
        return XW11_INPUT_TRANSFORM_ERR_VIEWPORT;

    const int64_t local_x = (int64_t)view_x - (int64_t)t->viewport_x;
    const int64_t local_y = (int64_t)view_y - (int64_t)t->viewport_y;
    if (local_x < 0 || local_y < 0 ||
        (uint64_t)local_x >= t->viewport_width ||
        (uint64_t)local_y >= t->viewport_height)
        return XW11_INPUT_TRANSFORM_ERR_OUTSIDE;

    /* 64-bit products avoid overflow for validated 32-bit coordinates/sizes. */
    uint64_t x = ((uint64_t)local_x * t->output_width) / t->viewport_width;
    uint64_t y = ((uint64_t)local_y * t->output_height) / t->viewport_height;
    if (x >= t->output_width)
        x = t->output_width - 1;
    if (y >= t->output_height)
        y = t->output_height - 1;
    *out_x = (uint32_t)x;
    *out_y = (uint32_t)y;
    return XW11_INPUT_TRANSFORM_OK;
}
