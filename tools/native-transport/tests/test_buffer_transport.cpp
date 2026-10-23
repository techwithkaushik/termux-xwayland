#include "xw11_buffer_transport.h"

#include <assert.h>
#include <fcntl.h>
#include <stdio.h>
#include <sys/socket.h>
#include <unistd.h>

int main() {
    int sockets[2];
    assert(socketpair(AF_UNIX, SOCK_STREAM, 0, sockets) == 0);
    int pipe_fds[2];
    assert(pipe(pipe_fds) == 0);

    xw11_buffer_descriptor source = {};
    source.buffer_id = 7;
    source.generation = 42;
    source.width = 320;
    source.height = 200;
    source.stride_bytes = 1280;
    source.fourcc = XW11_FORMAT_XRGB8888;
    source.modifier = 0;

    assert(xw11_send_buffer(sockets[0], &source, 9, 1, pipe_fds[0]) ==
           XW11_BUFFER_TRANSPORT_OK);

    xw11_sequence_tracker tracker = {};
    assert(xw11_sequence_tracker_reset(&tracker, 9) == XW11_PROTOCOL_OK);
    xw11_received_buffer received = {};
    received.fd = -1;
    assert(xw11_receive_buffer(sockets[1], 9, &tracker, 42, &received) ==
           XW11_BUFFER_TRANSPORT_OK);
    assert(received.fd >= 0);
    assert((fcntl(received.fd, F_GETFD) & FD_CLOEXEC) != 0);
    assert(received.descriptor.buffer_id == source.buffer_id);
    assert(received.descriptor.generation == source.generation);
    assert(received.descriptor.width == source.width);
    assert(received.sequence_id == 1);

    const char value = 'X';
    assert(write(pipe_fds[1], &value, 1) == 1);
    char read_back = 0;
    assert(read(received.fd, &read_back, 1) == 1 && read_back == value);

    close(received.fd);
    close(pipe_fds[0]);
    close(pipe_fds[1]);
    close(sockets[0]);
    close(sockets[1]);
    puts("PASS: XW11 buffer FD + metadata transport");
    return 0;
}
