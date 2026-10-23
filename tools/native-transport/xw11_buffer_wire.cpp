#include "xw11_buffer_wire.h"

#include <stddef.h>
#include <stdint.h>

namespace {
void put_u32(uint8_t *out, uint32_t value) {
    out[0] = (uint8_t)(value >> 24);
    out[1] = (uint8_t)(value >> 16);
    out[2] = (uint8_t)(value >> 8);
    out[3] = (uint8_t)value;
}

void put_u64(uint8_t *out, uint64_t value) {
    for (unsigned i = 0; i < 8; ++i)
        out[i] = (uint8_t)(value >> (56u - i * 8u));
}

uint32_t get_u32(const uint8_t *in) {
    return ((uint32_t)in[0] << 24) | ((uint32_t)in[1] << 16) |
           ((uint32_t)in[2] << 8) | (uint32_t)in[3];
}

uint64_t get_u64(const uint8_t *in) {
    uint64_t value = 0;
    for (unsigned i = 0; i < 8; ++i)
        value = (value << 8) | in[i];
    return value;
}
}

extern "C" xw11_buffer_wire_result xw11_encode_buffer_descriptor(
    const xw11_buffer_descriptor *descriptor, uint8_t *out, size_t out_size) {
    if (descriptor == nullptr || out == nullptr)
        return XW11_BUFFER_WIRE_ERR_ARGUMENT;
    if (out_size < XW11_BUFFER_WIRE_SIZE)
        return XW11_BUFFER_WIRE_ERR_SIZE;
    if (xw11_validate_buffer_descriptor(descriptor, nullptr) != XW11_BUFFER_VALID)
        return XW11_BUFFER_WIRE_ERR_DESCRIPTOR;

    put_u32(out, descriptor->buffer_id);
    put_u64(out + 4, descriptor->generation);
    put_u32(out + 12, descriptor->width);
    put_u32(out + 16, descriptor->height);
    put_u32(out + 20, descriptor->stride_bytes);
    put_u32(out + 24, descriptor->fourcc);
    put_u64(out + 28, descriptor->modifier);
    return XW11_BUFFER_WIRE_OK;
}

extern "C" xw11_buffer_wire_result xw11_decode_buffer_descriptor(
    const uint8_t *bytes, size_t size, xw11_buffer_descriptor *out) {
    if (bytes == nullptr || out == nullptr)
        return XW11_BUFFER_WIRE_ERR_ARGUMENT;
    if (size != XW11_BUFFER_WIRE_SIZE)
        return XW11_BUFFER_WIRE_ERR_SIZE;

    xw11_buffer_descriptor decoded = {};
    decoded.buffer_id = get_u32(bytes);
    decoded.generation = get_u64(bytes + 4);
    decoded.width = get_u32(bytes + 12);
    decoded.height = get_u32(bytes + 16);
    decoded.stride_bytes = get_u32(bytes + 20);
    decoded.fourcc = get_u32(bytes + 24);
    decoded.modifier = get_u64(bytes + 28);
    if (xw11_validate_buffer_descriptor(&decoded, nullptr) != XW11_BUFFER_VALID)
        return XW11_BUFFER_WIRE_ERR_DESCRIPTOR;

    *out = decoded;
    return XW11_BUFFER_WIRE_OK;
}
