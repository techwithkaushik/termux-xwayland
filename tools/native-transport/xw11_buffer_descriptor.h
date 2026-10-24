#pragma once

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* DRM fourcc values are encoded as little-endian character bytes. */
#define XW11_FORMAT_RGB565   0x36314752u /* 'RG16' */
#define XW11_FORMAT_XRGB8888 0x34325258u /* 'XR24' */
#define XW11_FORMAT_ARGB8888 0x34325241u /* 'AR24' */

#define XW11_BUFFER_MAX_DIMENSION 16384u
#define XW11_BUFFER_MAX_BYTES (512ull * 1024ull * 1024ull)

typedef enum xw11_buffer_validation {
    XW11_BUFFER_VALID = 0,
    XW11_BUFFER_ERR_ARGUMENT,
    XW11_BUFFER_ERR_ID,
    XW11_BUFFER_ERR_GENERATION,
    XW11_BUFFER_ERR_DIMENSIONS,
    XW11_BUFFER_ERR_FORMAT,
    XW11_BUFFER_ERR_STRIDE,
    XW11_BUFFER_ERR_SIZE
} xw11_buffer_validation;

/*
 * Metadata only: this does not prove that fd is a graphics buffer or that
 * the consumer can import its format/modifier. The descriptor is validated
 * before any allocation, import, or GPU submission.
 */
typedef struct xw11_buffer_descriptor {
    uint32_t buffer_id;
    uint64_t generation;
    uint32_t width;
    uint32_t height;
    uint32_t stride_bytes;
    uint32_t fourcc;
    uint64_t modifier;
} xw11_buffer_descriptor;

xw11_buffer_validation xw11_validate_buffer_descriptor(
    const xw11_buffer_descriptor *descriptor,
    uint64_t *out_minimum_bytes);

#ifdef __cplusplus
}
#endif
