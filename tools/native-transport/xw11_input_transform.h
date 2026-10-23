#pragma once

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef enum xw11_input_transform_result {
    XW11_INPUT_TRANSFORM_OK = 0,
    XW11_INPUT_TRANSFORM_ERR_ARGUMENT,
    XW11_INPUT_TRANSFORM_ERR_GENERATION,
    XW11_INPUT_TRANSFORM_ERR_VIEWPORT,
    XW11_INPUT_TRANSFORM_ERR_OUTSIDE
} xw11_input_transform_result;

/* Coordinates are in Android view pixels; output coordinates are X/Wayland pixels. */
typedef struct xw11_input_transform {
    uint64_t generation;
    int32_t viewport_x;
    int32_t viewport_y;
    uint32_t viewport_width;
    uint32_t viewport_height;
    uint32_t output_width;
    uint32_t output_height;
} xw11_input_transform;

xw11_input_transform_result xw11_map_input_coordinates(
    const xw11_input_transform *transform,
    uint64_t event_generation,
    int32_t view_x,
    int32_t view_y,
    uint32_t *out_x,
    uint32_t *out_y);

#ifdef __cplusplus
}
#endif
