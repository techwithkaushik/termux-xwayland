#include "xw11_input_receiver.h"

#include "xw11_transport_io.h"

#include <stdint.h>

extern "C" xw11_input_receiver_result xw11_receive_input_event(
    int fd, xw11_sequence_tracker *tracker, xw11_input_event *out_event) {
    if (fd < 0 || tracker == nullptr || tracker->session_id == 0 ||
        out_event == nullptr) {
        return XW11_INPUT_RECEIVER_ERR_ARGUMENT;
    }

    xw11_frame_header header{};
    uint8_t payload[XW11_INPUT_EVENT_WIRE_SIZE] = {};
    const xw11_io_result io =
        xw11_receive_frame(fd, &header, payload, sizeof(payload));
    if (io != XW11_IO_OK) {
        return io == XW11_IO_ERR_SYSTEM || io == XW11_IO_ERR_EOF
            ? XW11_INPUT_RECEIVER_ERR_IO
            : XW11_INPUT_RECEIVER_ERR_FRAME;
    }

    if (header.type != XW11_MSG_INPUT_EVENT) {
        return XW11_INPUT_RECEIVER_ERR_MESSAGE_TYPE;
    }
    if (header.payload_length != XW11_INPUT_EVENT_WIRE_SIZE) {
        return XW11_INPUT_RECEIVER_ERR_PAYLOAD_LENGTH;
    }
    if (header.session_id != tracker->session_id) {
        return XW11_INPUT_RECEIVER_ERR_SESSION;
    }

    xw11_input_event decoded{};
    if (xw11_input_event_decode(payload, header.payload_length, &decoded) !=
        XW11_INPUT_EVENT_OK) {
        return XW11_INPUT_RECEIVER_ERR_EVENT;
    }

    const xw11_protocol_error sequence =
        xw11_sequence_tracker_accept(tracker, &header);
    if (sequence == XW11_PROTOCOL_ERR_SESSION) {
        return XW11_INPUT_RECEIVER_ERR_SESSION;
    }
    if (sequence != XW11_PROTOCOL_OK) {
        return XW11_INPUT_RECEIVER_ERR_SEQUENCE;
    }

    *out_event = decoded;
    return XW11_INPUT_RECEIVER_OK;
}
