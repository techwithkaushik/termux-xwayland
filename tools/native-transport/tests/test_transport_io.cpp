#include "xw11_transport_io.h"

#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

static xw11_frame_header sample_header(uint32_t length) {
    xw11_frame_header header;
    header.major = XW11_PROTOCOL_MAJOR;
    header.minor = XW11_PROTOCOL_MINOR;
    header.type = XW11_MSG_INPUT_EVENT;
    header.flags = 0;
    header.payload_length = length;
    header.session_id = 23;
    header.sequence_id = 1;
    return header;
}

int main(void) {
    int sockets[2];
    assert(socketpair(AF_UNIX, SOCK_STREAM, 0, sockets) == 0);

    const uint8_t payload[] = {0, 1, 2, 0x7f, 0x80, 0xff};
    const xw11_frame_header sent = sample_header(sizeof(payload));
    assert(xw11_send_frame(sockets[0], &sent, payload) == XW11_IO_OK);

    uint8_t received_payload[sizeof(payload)] = {};
    xw11_frame_header received = {};
    assert(xw11_receive_frame(sockets[1], &received, received_payload,
                              sizeof(received_payload)) == XW11_IO_OK);
    assert(received.type == sent.type);
    assert(received.session_id == sent.session_id);
    assert(received.sequence_id == sent.sequence_id);
    assert(received.payload_length == sizeof(payload));
    assert(memcmp(payload, received_payload, sizeof(payload)) == 0);

    const xw11_frame_header empty = sample_header(0);
    assert(xw11_send_frame(sockets[0], &empty, nullptr) == XW11_IO_OK);
    assert(xw11_receive_frame(sockets[1], &received, nullptr, 0) == XW11_IO_OK);
    assert(received.payload_length == 0);

    const xw11_frame_header too_large_for_buffer = sample_header(sizeof(payload));
    assert(xw11_send_frame(sockets[0], &too_large_for_buffer, payload) == XW11_IO_OK);
    assert(xw11_receive_frame(sockets[1], &received, received_payload,
                              sizeof(received_payload) - 1) ==
           XW11_IO_ERR_BUFFER_TOO_SMALL);

    close(sockets[0]);
    close(sockets[1]);

    assert(socketpair(AF_UNIX, SOCK_STREAM, 0, sockets) == 0);
    const uint8_t truncated[] = {'X', 'W', '1', '1', 1};
    assert(write(sockets[0], truncated, sizeof(truncated)) ==
           static_cast<ssize_t>(sizeof(truncated)));
    close(sockets[0]);
    assert(xw11_receive_frame(sockets[1], &received, received_payload,
                              sizeof(received_payload)) == XW11_IO_ERR_EOF);
    close(sockets[1]);

    puts("XW11 socket transport tests passed");
    return 0;
}
