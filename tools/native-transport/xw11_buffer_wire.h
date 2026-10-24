#pragma once

#include "xw11_buffer_descriptor.h"

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Fixed big-endian BUFFER_SUBMIT metadata; the graphics buffer itself travels via SCM_RIGHTS. */
#define XW11_BUFFER_WIRE_SIZE 36u

typedef enum xw11_buffer_wire_result {
    XW11_BUFFER_WIRE_OK = 0,
    XW11_BUFFER_WIRE_ERR_ARGUMENT,
    XW11_BUFFER_WIRE_ERR_SIZE,
    XW11_BUFFER_WIRE_ERR_DESCRIPTOR
} xw11_buffer_wire_result;

/* Encodes validated metadata only. Does not transfer or validate a graphics FD. */
xw11_buffer_wire_result xw11_encode_buffer_descriptor(
    const xw11_buffer_descriptor *descriptor, uint8_t *out, size_t out_size);

/* Requires exactly XW11_BUFFER_WIRE_SIZE bytes and validates decoded metadata. */
xw11_buffer_wire_result xw11_decode_buffer_descriptor(
    const uint8_t *bytes, size_t size, xw11_buffer_descriptor *out);

#ifdef __cplusplus
}
#endif
