#pragma once

#include "xw11_input_event.h"
#include "xw11_transport_protocol.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef enum xw11_input_receiver_result {
    XW11_INPUT_RECEIVER_OK = 0,
    XW11_INPUT_RECEIVER_ERR_ARGUMENT,
    XW11_INPUT_RECEIVER_ERR_IO,
    XW11_INPUT_RECEIVER_ERR_FRAME,
    XW11_INPUT_RECEIVER_ERR_SESSION,
    XW11_INPUT_RECEIVER_ERR_SEQUENCE,
    XW11_INPUT_RECEIVER_ERR_MESSAGE_TYPE,
    XW11_INPUT_RECEIVER_ERR_PAYLOAD_LENGTH,
    XW11_INPUT_RECEIVER_ERR_EVENT
} xw11_input_receiver_result;

/*
 * Receive and validate one XW11 input frame. The tracker must be reset to the
 * negotiated nonzero session before calling. On any error, close the socket:
 * the stream may no longer be safely resynchronized. This function only
 * decodes transport data; it does not inject input into a Wayland compositor.
 */
xw11_input_receiver_result xw11_receive_input_event(
    int fd, xw11_sequence_tracker *tracker, xw11_input_event *out_event);

#ifdef __cplusplus
}
#endif
