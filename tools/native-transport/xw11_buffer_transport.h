#pragma once

#include "xw11_buffer_descriptor.h"
#include "xw11_buffer_wire.h"
#include "xw11_fd_passing.h"
#include "xw11_transport_io.h"

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef enum xw11_buffer_transport_result {
    XW11_BUFFER_TRANSPORT_OK = 0,
    XW11_BUFFER_TRANSPORT_ERR_ARGUMENT,
    XW11_BUFFER_TRANSPORT_ERR_FD,
    XW11_BUFFER_TRANSPORT_ERR_FRAME,
    XW11_BUFFER_TRANSPORT_ERR_SESSION,
    XW11_BUFFER_TRANSPORT_ERR_SEQUENCE,
    XW11_BUFFER_TRANSPORT_ERR_TYPE,
    XW11_BUFFER_TRANSPORT_ERR_METADATA
} xw11_buffer_transport_result;

typedef struct xw11_received_buffer {
    xw11_buffer_descriptor descriptor;
    int fd; /* Caller owns this FD after successful receive. */
    uint32_t session_id;
    uint64_t sequence_id;
} xw11_received_buffer;

/*
 * Send one buffer submission over a connected AF_UNIX stream.
 * The FD marker + ancillary FD precede the framed metadata; use a single
 * writer for this socket. If either send fails, close the connection.
 */
xw11_buffer_transport_result xw11_send_buffer(
    int socket_fd, const xw11_buffer_descriptor *descriptor,
    uint32_t session_id, uint64_t sequence_id, int buffer_fd);

/*
 * Receive one FD + BUFFER_SUBMIT frame. The receiver consumes the FD on any
 * failure and returns ownership only on success. Caller closes the connection
 * after any error because stream resynchronization is not guaranteed.
 */
xw11_buffer_transport_result xw11_receive_buffer(
    int socket_fd, uint32_t expected_session_id,
    xw11_sequence_tracker *sequence_tracker,
    uint64_t active_generation, xw11_received_buffer *out);

#ifdef __cplusplus
}
#endif
