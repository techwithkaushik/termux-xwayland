#pragma once

#include "xw11_transport_protocol.h"

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef enum xw11_io_result {
    XW11_IO_OK = 0,
    XW11_IO_ERR_ARGUMENT,
    XW11_IO_ERR_SYSTEM,
    XW11_IO_ERR_EOF,
    XW11_IO_ERR_PROTOCOL,
    XW11_IO_ERR_BUFFER_TOO_SMALL
} xw11_io_result;

/*
 * Send one complete frame on a connected stream socket. Partial writes and
 * EINTR are handled. A non-empty payload must not be NULL.
 */
xw11_io_result xw11_send_frame(int fd, const xw11_frame_header *header,
                               const uint8_t *payload);

/*
 * Receive one complete frame from a connected stream socket. Partial reads
 * and EINTR are handled. On BUFFER_TOO_SMALL or any framing error, the caller
 * must close the connection: unread payload bytes may remain on the stream.
 * Payload may be NULL only when the decoded payload length is zero.
 */
xw11_io_result xw11_receive_frame(int fd, xw11_frame_header *header,
                                  uint8_t *payload, size_t payload_capacity);

#ifdef __cplusplus
}
#endif
