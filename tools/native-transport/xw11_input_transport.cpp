#include "xw11_input_transport.h"
#include <limits.h>
extern "C" xw11_input_transport_result xw11_send_input_event(
    int fd, uint32_t session_id, uint64_t *next_sequence,
    const xw11_input_event *event) {
    if (fd < 0 || session_id == 0 || next_sequence == nullptr || event == nullptr)
        return XW11_INPUT_TRANSPORT_ERR_ARGUMENT;
    if (*next_sequence == 0 || *next_sequence == UINT64_MAX)
        return XW11_INPUT_TRANSPORT_ERR_SEQUENCE;
    uint8_t payload[XW11_INPUT_EVENT_WIRE_SIZE];
    if (xw11_input_event_encode(event, payload, sizeof(payload)) != XW11_INPUT_EVENT_OK)
        return XW11_INPUT_TRANSPORT_ERR_CODEC;
    xw11_frame_header header{};
    header.major = XW11_PROTOCOL_MAJOR;
    header.minor = XW11_PROTOCOL_MINOR;
    header.type = XW11_MSG_INPUT_EVENT;
    header.payload_length = sizeof(payload);
    header.session_id = session_id;
    header.sequence_id = *next_sequence;
    if (xw11_send_frame(fd, &header, payload) != XW11_IO_OK)
        return XW11_INPUT_TRANSPORT_ERR_IO;
    ++(*next_sequence);
    return XW11_INPUT_TRANSPORT_OK;
}
