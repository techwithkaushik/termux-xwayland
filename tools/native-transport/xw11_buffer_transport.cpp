#include "xw11_buffer_transport.h"

#include <unistd.h>

extern "C" xw11_buffer_transport_result xw11_send_buffer(
    int socket_fd, const xw11_buffer_descriptor *descriptor,
    uint32_t session_id, uint64_t sequence_id, int buffer_fd) {
    if (socket_fd < 0 || descriptor == nullptr || buffer_fd < 0 ||
        session_id == 0 || sequence_id == 0)
        return XW11_BUFFER_TRANSPORT_ERR_ARGUMENT;

    uint8_t payload[XW11_BUFFER_WIRE_SIZE] = {};
    if (xw11_encode_buffer_descriptor(descriptor, payload, sizeof(payload)) !=
        XW11_BUFFER_WIRE_OK)
        return XW11_BUFFER_TRANSPORT_ERR_METADATA;

    xw11_fd_result fd_result = xw11_send_fd(socket_fd, buffer_fd);
    if (fd_result != XW11_FD_OK)
        return XW11_BUFFER_TRANSPORT_ERR_FD;

    xw11_frame_header frame = {};
    frame.major = XW11_PROTOCOL_MAJOR;
    frame.minor = XW11_PROTOCOL_MINOR;
    frame.type = XW11_MSG_BUFFER_SUBMIT;
    frame.payload_length = sizeof(payload);
    frame.session_id = session_id;
    frame.sequence_id = sequence_id;
    return xw11_send_frame(socket_fd, &frame, payload) == XW11_IO_OK
        ? XW11_BUFFER_TRANSPORT_OK : XW11_BUFFER_TRANSPORT_ERR_FRAME;
}

extern "C" xw11_buffer_transport_result xw11_receive_buffer(
    int socket_fd, uint32_t expected_session_id,
    xw11_sequence_tracker *sequence_tracker,
    uint64_t active_generation, xw11_received_buffer *out) {
    if (socket_fd < 0 || expected_session_id == 0 ||
        sequence_tracker == nullptr || active_generation == 0 || out == nullptr)
        return XW11_BUFFER_TRANSPORT_ERR_ARGUMENT;

    out->fd = -1;
    out->session_id = 0;
    out->sequence_id = 0;
    out->descriptor = {};

    int received_fd = -1;
    if (xw11_receive_fd(socket_fd, &received_fd) != XW11_FD_OK)
        return XW11_BUFFER_TRANSPORT_ERR_FD;

    uint8_t payload[XW11_BUFFER_WIRE_SIZE] = {};
    xw11_frame_header frame = {};
    if (xw11_receive_frame(socket_fd, &frame, payload, sizeof(payload)) != XW11_IO_OK) {
        close(received_fd);
        return XW11_BUFFER_TRANSPORT_ERR_FRAME;
    }
    if (frame.type != XW11_MSG_BUFFER_SUBMIT) {
        close(received_fd);
        return XW11_BUFFER_TRANSPORT_ERR_TYPE;
    }
    if (frame.session_id != expected_session_id ||
        sequence_tracker->session_id != expected_session_id) {
        close(received_fd);
        return XW11_BUFFER_TRANSPORT_ERR_SESSION;
    }
    if (xw11_sequence_tracker_accept(sequence_tracker, &frame) != XW11_PROTOCOL_OK) {
        close(received_fd);
        return XW11_BUFFER_TRANSPORT_ERR_SEQUENCE;
    }
    if (frame.payload_length != XW11_BUFFER_WIRE_SIZE ||
        xw11_decode_buffer_descriptor(payload, frame.payload_length,
                                      &out->descriptor) != XW11_BUFFER_WIRE_OK ||
        out->descriptor.generation != active_generation) {
        close(received_fd);
        out->descriptor = {};
        return XW11_BUFFER_TRANSPORT_ERR_METADATA;
    }

    out->fd = received_fd;
    out->session_id = frame.session_id;
    out->sequence_id = frame.sequence_id;
    return XW11_BUFFER_TRANSPORT_OK;
}
