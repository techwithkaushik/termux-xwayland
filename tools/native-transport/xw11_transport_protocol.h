#pragma once

/*
 * XW11 control-channel framing v1.
 *
 * This is a transport-neutral protocol primitive only. It is not wired into
 * the Android app, Weston, or XWayland yet. Wire values are big-endian so the
 * protocol does not depend on host ABI, padding, or alignment.
 */
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define XW11_PROTOCOL_MAGIC "XW11"
#define XW11_PROTOCOL_HEADER_SIZE 28u
#define XW11_PROTOCOL_MAJOR 1u
#define XW11_PROTOCOL_MINOR 0u
#define XW11_PROTOCOL_MAX_PAYLOAD (1024u * 1024u)

typedef enum xw11_message_type {
    XW11_MSG_HELLO = 1,
    XW11_MSG_CAPABILITIES = 2,
    XW11_MSG_OUTPUT_CONFIG = 3,
    XW11_MSG_BUFFER_SUBMIT = 4,
    XW11_MSG_BUFFER_RELEASE = 5,
    XW11_MSG_INPUT_EVENT = 6,
    XW11_MSG_SUSPEND = 7,
    XW11_MSG_RESUME = 8,
    XW11_MSG_ERROR = 9,
    XW11_MSG_GOODBYE = 10
} xw11_message_type;

typedef enum xw11_protocol_error {
    XW11_PROTOCOL_OK = 0,
    XW11_PROTOCOL_ERR_ARGUMENT,
    XW11_PROTOCOL_ERR_MAGIC,
    XW11_PROTOCOL_ERR_VERSION,
    XW11_PROTOCOL_ERR_TYPE,
    XW11_PROTOCOL_ERR_FLAGS,
    XW11_PROTOCOL_ERR_LENGTH,
    XW11_PROTOCOL_ERR_SESSION,
    XW11_PROTOCOL_ERR_SEQUENCE
} xw11_protocol_error;

typedef struct xw11_frame_header {
    uint8_t major;
    uint8_t minor;
    uint16_t type;
    uint32_t flags;
    uint32_t payload_length;
    uint32_t session_id;
    uint64_t sequence_id;
} xw11_frame_header;

/* Decode and validate exactly one fixed-size header; payload bytes follow it. */
xw11_protocol_error xw11_decode_header(const uint8_t *bytes, size_t size,
                                       xw11_frame_header *out);

/* Encode a validated header. Returns 0 on success, otherwise protocol error. */
xw11_protocol_error xw11_encode_header(const xw11_frame_header *header,
                                       uint8_t *out, size_t out_size);

#ifdef __cplusplus
}
#endif
