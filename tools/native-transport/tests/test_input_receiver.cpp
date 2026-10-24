#include "xw11_input_event.h"
#include "xw11_input_receiver.h"
#include "xw11_transport_io.h"
#include "xw11_transport_protocol.h"

#include <assert.h>
#include <stdint.h>
#include <sys/socket.h>
#include <unistd.h>

static void send_event(int fd, uint32_t session, uint64_t sequence,
                       uint16_t message_type) {
    xw11_input_event event{};
    event.surface_generation = 7;
    event.monotonic_time_ns = 123456;
    event.type = XW11_INPUT_POINTER_MOTION;
    event.action = XW11_INPUT_MOVE;
    event.x = 31;
    event.y = 42;
    uint8_t payload[XW11_INPUT_EVENT_WIRE_SIZE] = {};
    assert(xw11_input_event_encode(&event, payload, sizeof(payload)) ==
           XW11_INPUT_EVENT_OK);

    xw11_frame_header header{};
    header.major = XW11_PROTOCOL_MAJOR;
    header.minor = XW11_PROTOCOL_MINOR;
    header.type = message_type;
    header.payload_length = sizeof(payload);
    header.session_id = session;
    header.sequence_id = sequence;
    assert(xw11_send_frame(fd, &header, payload) == XW11_IO_OK);
}

int main() {
    int sockets[2] = {-1, -1};
    assert(socketpair(AF_UNIX, SOCK_STREAM, 0, sockets) == 0);
    xw11_sequence_tracker tracker{};
    assert(xw11_sequence_tracker_reset(&tracker, 91) == XW11_PROTOCOL_OK);
    xw11_input_event received{};

    send_event(sockets[0], 91, 1, XW11_MSG_INPUT_EVENT);
    assert(xw11_receive_input_event(sockets[1], &tracker, &received) ==
           XW11_INPUT_RECEIVER_OK);
    assert(received.surface_generation == 7);
    assert(received.x == 31 && received.y == 42);
    assert(tracker.last_sequence_id == 1);

    send_event(sockets[0], 91, 1, XW11_MSG_INPUT_EVENT);
    assert(xw11_receive_input_event(sockets[1], &tracker, &received) ==
           XW11_INPUT_RECEIVER_ERR_SEQUENCE);
    close(sockets[0]);
    close(sockets[1]);

    assert(socketpair(AF_UNIX, SOCK_STREAM, 0, sockets) == 0);
    assert(xw11_sequence_tracker_reset(&tracker, 91) == XW11_PROTOCOL_OK);
    send_event(sockets[0], 92, 1, XW11_MSG_INPUT_EVENT);
    assert(xw11_receive_input_event(sockets[1], &tracker, &received) ==
           XW11_INPUT_RECEIVER_ERR_SESSION);
    close(sockets[0]);
    close(sockets[1]);

    assert(socketpair(AF_UNIX, SOCK_STREAM, 0, sockets) == 0);
    assert(xw11_sequence_tracker_reset(&tracker, 91) == XW11_PROTOCOL_OK);
    send_event(sockets[0], 91, 1, XW11_MSG_HELLO);
    assert(xw11_receive_input_event(sockets[1], &tracker, &received) ==
           XW11_INPUT_RECEIVER_ERR_MESSAGE_TYPE);
    close(sockets[0]);
    close(sockets[1]);
    return 0;
}
