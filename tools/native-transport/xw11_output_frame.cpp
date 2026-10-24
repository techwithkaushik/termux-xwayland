#include "xw11_output_frame.h"

#include <limits.h>
#include <string.h>

namespace {
void put32(uint8_t *p, uint32_t v) {
    p[0] = static_cast<uint8_t>(v >> 24);
    p[1] = static_cast<uint8_t>(v >> 16);
    p[2] = static_cast<uint8_t>(v >> 8);
    p[3] = static_cast<uint8_t>(v);
}
void put64(uint8_t *p, uint64_t v) {
    for (unsigned i = 0; i < 8; ++i)
        p[i] = static_cast<uint8_t>(v >> (56u - i * 8u));
}
uint32_t get32(const uint8_t *p) {
    return (static_cast<uint32_t>(p[0]) << 24) |
           (static_cast<uint32_t>(p[1]) << 16) |
           (static_cast<uint32_t>(p[2]) << 8) | p[3];
}
uint64_t get64(const uint8_t *p) {
    uint64_t v = 0;
    for (unsigned i = 0; i < 8; ++i)
        v = (v << 8) | p[i];
    return v;
}
}

extern "C" xw11_output_frame_error xw11_output_frame_validate(
    const xw11_output_frame *f, size_t *out_source_bytes) {
    if (!f) return XW11_OUTPUT_FRAME_ERR_ARGUMENT;
    if (f->surface_generation == 0) return XW11_OUTPUT_FRAME_ERR_GENERATION;
    if (f->sequence_id == 0) return XW11_OUTPUT_FRAME_ERR_SEQUENCE;
    if (!f->width || !f->height ||
        f->width > XW11_OUTPUT_FRAME_MAX_DIMENSION ||
        f->height > XW11_OUTPUT_FRAME_MAX_DIMENSION)
        return XW11_OUTPUT_FRAME_ERR_DIMENSIONS;
    if (f->fourcc != XW11_OUTPUT_FORMAT_XRGB8888 &&
        f->fourcc != XW11_OUTPUT_FORMAT_ARGB8888)
        return XW11_OUTPUT_FRAME_ERR_FORMAT;
    if (f->width > UINT32_MAX / 4u ||
        f->stride_bytes < f->width * 4u)
        return XW11_OUTPUT_FRAME_ERR_STRIDE;
    const size_t row = static_cast<size_t>(f->width) * 4u;
    if (f->stride_bytes > XW11_OUTPUT_FRAME_MAX_BYTES ||
        static_cast<size_t>(f->height - 1u) >
            (SIZE_MAX - row) / f->stride_bytes)
        return XW11_OUTPUT_FRAME_ERR_SIZE;
    const size_t bytes = static_cast<size_t>(f->height - 1u) *
                         f->stride_bytes + row;
    if (bytes > XW11_OUTPUT_FRAME_MAX_BYTES)
        return XW11_OUTPUT_FRAME_ERR_SIZE;
    if (f->pixel_bytes != bytes)
        return XW11_OUTPUT_FRAME_ERR_SIZE;
    if (out_source_bytes) *out_source_bytes = bytes;
    return XW11_OUTPUT_FRAME_OK;
}

extern "C" xw11_output_frame_error xw11_output_frame_encode(
    const xw11_output_frame *f, uint8_t *out, size_t out_size) {
    if (!out || out_size < XW11_OUTPUT_FRAME_WIRE_SIZE)
        return XW11_OUTPUT_FRAME_ERR_ARGUMENT;
    xw11_output_frame_error e = xw11_output_frame_validate(f, nullptr);
    if (e != XW11_OUTPUT_FRAME_OK) return e;
    put64(out, f->surface_generation);
    put64(out + 8, f->sequence_id);
    put32(out + 16, f->width);
    put32(out + 20, f->height);
    put32(out + 24, f->stride_bytes);
    put32(out + 28, f->fourcc);
    put32(out + 32, f->pixel_bytes);
    put32(out + 36, 0); /* Reserved; must be zero in v1. */
    return XW11_OUTPUT_FRAME_OK;
}

extern "C" xw11_output_frame_error xw11_output_frame_decode(
    const uint8_t *bytes, size_t size, xw11_output_frame *out) {
    if (!bytes || !out || size != XW11_OUTPUT_FRAME_WIRE_SIZE)
        return XW11_OUTPUT_FRAME_ERR_ARGUMENT;
    if (get32(bytes + 36) != 0) return XW11_OUTPUT_FRAME_ERR_ARGUMENT;
    xw11_output_frame f = {
        get64(bytes), get64(bytes + 8), get32(bytes + 16),
        get32(bytes + 20), get32(bytes + 24), get32(bytes + 28),
        get32(bytes + 32)
    };
    xw11_output_frame_error e = xw11_output_frame_validate(&f, nullptr);
    if (e != XW11_OUTPUT_FRAME_OK) return e;
    *out = f;
    return XW11_OUTPUT_FRAME_OK;
}

extern "C" xw11_output_frame_error xw11_output_frame_to_rgba(
    const xw11_output_frame *f, const uint8_t *source, size_t source_size,
    uint8_t *destination, size_t destination_size, size_t destination_stride) {
    size_t source_bytes = 0;
    xw11_output_frame_error e = xw11_output_frame_validate(f, &source_bytes);
    if (e != XW11_OUTPUT_FRAME_OK) return e;
    const size_t row = static_cast<size_t>(f->width) * 4u;
    if (!source || !destination) return XW11_OUTPUT_FRAME_ERR_ARGUMENT;
    if (source_size < source_bytes || destination_stride < row ||
        destination_stride > XW11_OUTPUT_FRAME_MAX_BYTES)
        return XW11_OUTPUT_FRAME_ERR_STRIDE;
    if (static_cast<size_t>(f->height - 1u) >
        (SIZE_MAX - row) / destination_stride)
        return XW11_OUTPUT_FRAME_ERR_SIZE;
    const size_t destination_bytes =
        static_cast<size_t>(f->height - 1u) * destination_stride + row;
    if (destination_bytes > destination_size ||
        destination_bytes > XW11_OUTPUT_FRAME_MAX_BYTES)
        return XW11_OUTPUT_FRAME_ERR_SIZE;

    for (uint32_t y = 0; y < f->height; ++y) {
        const uint8_t *src = source + static_cast<size_t>(y) * f->stride_bytes;
        uint8_t *dst = destination + static_cast<size_t>(y) * destination_stride;
        for (uint32_t x = 0; x < f->width; ++x) {
            const uint8_t *p = src + static_cast<size_t>(x) * 4u;
            uint8_t *q = dst + static_cast<size_t>(x) * 4u;
            /* DRM XRGB/ARGB little-endian bytes are B,G,R,X/A in memory. */
            q[0] = p[2]; q[1] = p[1]; q[2] = p[0];
            q[3] = f->fourcc == XW11_OUTPUT_FORMAT_ARGB8888 ? p[3] : 255;
        }
    }
    return XW11_OUTPUT_FRAME_OK;
}
