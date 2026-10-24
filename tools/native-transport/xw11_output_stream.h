#pragma once

#include "xw11_output_frame.h"
#include "xw11_transport_io.h"

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Bounded output-frame stream over an already-connected XW11 stream socket.
 * Each protocol message carries frame metadata, a byte offset, and one chunk.
 * Frames larger than the 1 MiB protocol payload limit are split into ordered
 * chunks; receiver assembles into caller-owned memory and never allocates from
 * untrusted wire dimensions.
 */
#define XW11_OUTPUT_CHUNK_PREFIX_SIZE (XW11_OUTPUT_FRAME_WIRE_SIZE + 4u)
#define XW11_OUTPUT_CHUNK_MAX_BYTES (XW11_PROTOCOL_MAX_PAYLOAD - XW11_OUTPUT_CHUNK_PREFIX_SIZE)

typedef enum xw11_output_stream_result {
    XW11_OUTPUT_STREAM_OK = 0,
    XW11_OUTPUT_STREAM_ERR_ARGUMENT,
    XW11_OUTPUT_STREAM_ERR_FRAME,
    XW11_OUTPUT_STREAM_ERR_TRANSPORT,
    XW11_OUTPUT_STREAM_ERR_SESSION,
    XW11_OUTPUT_STREAM_ERR_SEQUENCE,
    XW11_OUTPUT_STREAM_ERR_BUFFER_TOO_SMALL
} xw11_output_stream_result;

xw11_output_stream_result xw11_output_stream_send(
    int fd, uint32_t session_id, const xw11_output_frame *frame,
    const uint8_t *pixels);

xw11_output_stream_result xw11_output_stream_receive(
    int fd, uint32_t expected_session_id, uint64_t expected_generation,
    uint8_t *pixels, size_t pixel_capacity, xw11_output_frame *out_frame);

#ifdef __cplusplus
}
#endif
