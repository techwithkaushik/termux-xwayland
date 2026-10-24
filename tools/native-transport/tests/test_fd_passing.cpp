#include "xw11_fd_passing.h"

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

    assert(xw11_send_fd(sockets[0], pipe_fds[0]) == XW11_FD_OK);
    int received = -1;
    assert(xw11_receive_fd(sockets[1], &received) == XW11_FD_OK);
    assert(received >= 0);
    assert((fcntl(received, F_GETFD) & FD_CLOEXEC) != 0);

    const char marker = 'Q';
    assert(write(pipe_fds[1], &marker, 1) == 1);
    char read_back = 0;
    assert(read(received, &read_back, 1) == 1);
    assert(read_back == marker);

    assert(xw11_send_fd(-1, pipe_fds[0]) == XW11_FD_ERR_ARGUMENT);
    assert(xw11_receive_fd(sockets[1], nullptr) == XW11_FD_ERR_ARGUMENT);

    close(received);
    close(pipe_fds[0]);
    close(pipe_fds[1]);
    close(sockets[0]);
    close(sockets[1]);
    puts("XW11 SCM_RIGHTS descriptor-passing tests passed");
    return 0;
}
