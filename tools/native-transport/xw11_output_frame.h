#pragma once

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define XW11_OUTPUT_FRAME_WIRE_SIZE 40u
#define XW11_OUTPUT_FRAME_MAX_DIMENSION 8192u
#define XW11_OUTPUT_FRAME_MAX_BYTES (64u * 1024u * 1024u)

/* Wire fourcc values use the DRM little-endian four-character convention. */
#define XW11_OUTPUT_FORMAT_XRGB8888 0x34325258u
#define XW11_OUTPUT_FORMAT_ARGB8888 0x34325241u

typedef enum xw11_output_frame_error {
    XW11_OUTPUT_FRAME_OK = 0,
    XW11_OUTPUT_FRAME_ERR_ARGUMENT,
    XW11_OUTPUT_FRAME_ERR_DIMENSIONS,
    XW11_OUTPUT_FRAME_ERR_FORMAT,
    XW11_OUTPUT_FRAME_ERR_STRIDE,
    XW11_OUTPUT_FRAME_ERR_SIZE,
    XW11_OUTPUT_FRAME_ERR_GENERATION,
    XW11_OUTPUT_FRAME_ERR_SEQUENCE
} xw11_output_frame_error;

/* Stable metadata; serialized big-endian and independent of host struct ABI. */
typedef struct xw11_output_frame {
    uint64_t surface_generation;
    uint64_t sequence_id;
    uint32_t width;
    uint32_t height;
    uint32_t stride_bytes;
    uint32_t fourcc;
    uint32_t pixel_bytes;
} xw11_output_frame_error;

xw11_output_frame_error xw11_output_frame_validate(
    const xw11_output_frame *frame, size_t *out_source_bytes);
xw11_output_frame_error xw11_output_frame_encode(
    const xw11_output_frame *frame, uint8_t *out, size_t out_size);
xw11_output_frame_error xw11_output_frame_decode(
    const uint8_t *bytes, size_t size, xw11_output_frame *out);

/* Convert little-endian XRGB8888/ARGB8888 pixels to RGBA8888. */
xw11_output_frame_error xw11_output_frame_to_rgba(
    const xw11_output_frame *frame, const uint8_t *source,
    size_t source_size, uint8_t *destination, size_t destination_size,
    size_t destination_stride);

#ifdef __cplusplus
}
#endif
