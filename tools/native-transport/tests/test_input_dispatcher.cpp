#include "xw11_input_dispatcher.h"
#include "xw11_transport_io.h"
#include "xw11_transport_protocol.h"

#include <assert.h>
#include <stdint.h>
#include <sys/socket.h>
#include <unistd.h>

int main() {
    int sockets[2] = {-1, -1};
    assert(socketpair(AF_UNIX, SOCK_STREAM, 0, sockets) == 0);

    xw11_input_dispatcher *dispatcher =
        xw11_input_dispatcher_create(sockets[0], 123);
    assert(dispatcher != nullptr);
    assert(xw11_input_dispatcher_start(dispatcher) ==
           XW11_INPUT_DISPATCHER_OK);

    xw11_input_event sent{};
    sent.surface_generation = 4;
    sent.monotonic_time_ns = 998877;
    sent.type = XW11_INPUT_POINTER_BUTTON;
    sent.action = XW11_INPUT_DOWN;
    sent.code = 1;
    sent.x = 100;
    sent.y = 200;
    assert(xw11_input_dispatcher_submit(dispatcher, &sent) ==
           XW11_INPUT_DISPATCHER_OK);

    xw11_frame_header header{};
    uint8_t payload[XW11_INPUT_EVENT_WIRE_SIZE] = {};
    assert(xw11_receive_frame(sockets[1], &header, payload, sizeof(payload)) ==
           XW11_IO_OK);
    assert(header.type == XW11_MSG_INPUT_EVENT);
    assert(header.session_id == 123);
    assert(header.sequence_id == 1);
    xw11_input_event received{};
    assert(xw11_input_event_decode(payload, sizeof(payload), &received) ==
           XW11_INPUT_EVENT_OK);
    assert(received.surface_generation == 4);
    assert(received.type == XW11_INPUT_POINTER_BUTTON);
    assert(received.action == XW11_INPUT_DOWN);
    assert(received.x == 100 && received.y == 200);

    xw11_input_dispatcher_stop(dispatcher);
    assert(xw11_input_dispatcher_submit(dispatcher, &sent) ==
           XW11_INPUT_DISPATCHER_ERR_STATE);
    xw11_input_dispatcher_destroy(dispatcher);
    close(sockets[1]);
    return 0;
}
