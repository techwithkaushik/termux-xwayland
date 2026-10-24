#include "xw11_buffer_descriptor.h"

#include <limits.h>

extern "C" xw11_buffer_validation xw11_validate_buffer_descriptor(
    const xw11_buffer_descriptor *d, uint64_t *out_minimum_bytes) {
    if (out_minimum_bytes != nullptr)
        *out_minimum_bytes = 0;
    if (d == nullptr)
        return XW11_BUFFER_ERR_ARGUMENT;
    if (d->buffer_id == 0)
        return XW11_BUFFER_ERR_ID;
    if (d->generation == 0)
        return XW11_BUFFER_ERR_GENERATION;
    if (d->width == 0 || d->height == 0 ||
        d->width > XW11_BUFFER_MAX_DIMENSION ||
        d->height > XW11_BUFFER_MAX_DIMENSION)
        return XW11_BUFFER_ERR_DIMENSIONS;

    uint64_t bytes_per_pixel;
    switch (d->fourcc) {
        case XW11_FORMAT_RGB565: bytes_per_pixel = 2; break;
        case XW11_FORMAT_XRGB8888:
        case XW11_FORMAT_ARGB8888: bytes_per_pixel = 4; break;
        default: return XW11_BUFFER_ERR_FORMAT;
    }

    const uint64_t minimum_stride = (uint64_t)d->width * bytes_per_pixel;
    if ((uint64_t)d->stride_bytes < minimum_stride)
        return XW11_BUFFER_ERR_STRIDE;

    /* Multiply only after a division-based overflow check. */
    if (d->height != 0 &&
        (uint64_t)d->stride_bytes > UINT64_MAX / (uint64_t)d->height)
        return XW11_BUFFER_ERR_SIZE;
    const uint64_t total = (uint64_t)d->stride_bytes * d->height;
    if (total == 0 || total > XW11_BUFFER_MAX_BYTES)
        return XW11_BUFFER_ERR_SIZE;

    if (out_minimum_bytes != nullptr)
        *out_minimum_bytes = total;
    return XW11_BUFFER_VALID;
}
