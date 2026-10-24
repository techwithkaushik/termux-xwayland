#include "xw11_fd_passing.h"

#include <errno.h>
#include <fcntl.h>
#include <stddef.h>
#include <stdint.h>
#include <sys/socket.h>
#include <unistd.h>

namespace {
constexpr uint8_t kFdMarker = 0xA7;

void close_rights(struct msghdr *message) {
    for (struct cmsghdr *cmsg = CMSG_FIRSTHDR(message); cmsg != nullptr;
         cmsg = CMSG_NXTHDR(message, cmsg)) {
        if (cmsg->cmsg_level != SOL_SOCKET || cmsg->cmsg_type != SCM_RIGHTS ||
            cmsg->cmsg_len < CMSG_LEN(sizeof(int)))
            continue;
        const size_t bytes = cmsg->cmsg_len - CMSG_LEN(0);
        const size_t count = bytes / sizeof(int);
        const int *fds = reinterpret_cast<const int *>(CMSG_DATA(cmsg));
        for (size_t i = 0; i < count; ++i)
            close(fds[i]);
    }
}
}  // namespace

extern "C" xw11_fd_result xw11_send_fd(int socket_fd, int fd_to_send) {
    if (socket_fd < 0 || fd_to_send < 0)
        return XW11_FD_ERR_ARGUMENT;

    uint8_t marker = kFdMarker;
    struct iovec iov = {&marker, sizeof(marker)};
    union {
        struct cmsghdr align;
        uint8_t bytes[CMSG_SPACE(sizeof(int))];
    } control = {};
    struct msghdr message = {};
    message.msg_iov = &iov;
    message.msg_iovlen = 1;
    message.msg_control = control.bytes;
    message.msg_controllen = sizeof(control.bytes);

    struct cmsghdr *cmsg = CMSG_FIRSTHDR(&message);
    cmsg->cmsg_level = SOL_SOCKET;
    cmsg->cmsg_type = SCM_RIGHTS;
    cmsg->cmsg_len = CMSG_LEN(sizeof(int));
    *reinterpret_cast<int *>(CMSG_DATA(cmsg)) = fd_to_send;

    ssize_t result;
    do {
        result = sendmsg(socket_fd, &message,
#ifdef MSG_NOSIGNAL
                         MSG_NOSIGNAL
#else
                         0
#endif
        );
    } while (result < 0 && errno == EINTR);

    return result == static_cast<ssize_t>(sizeof(marker))
               ? XW11_FD_OK
               : (result < 0 ? XW11_FD_ERR_SYSTEM : XW11_FD_ERR_PROTOCOL);
}

extern "C" xw11_fd_result xw11_receive_fd(int socket_fd, int *received_fd) {
    if (socket_fd < 0 || received_fd == nullptr)
        return XW11_FD_ERR_ARGUMENT;
    *received_fd = -1;

    uint8_t marker = 0;
    struct iovec iov = {&marker, sizeof(marker)};
    union {
        struct cmsghdr align;
        uint8_t bytes[CMSG_SPACE(sizeof(int) * 4)];
    } control = {};
    struct msghdr message = {};
    message.msg_iov = &iov;
    message.msg_iovlen = 1;
    message.msg_control = control.bytes;
    message.msg_controllen = sizeof(control.bytes);

    ssize_t result;
    do {
#ifdef MSG_CMSG_CLOEXEC
        result = recvmsg(socket_fd, &message, MSG_CMSG_CLOEXEC);
#else
        result = recvmsg(socket_fd, &message, 0);
#endif
    } while (result < 0 && errno == EINTR);
    if (result < 0)
        return XW11_FD_ERR_SYSTEM;
    if (result != static_cast<ssize_t>(sizeof(marker))) {
        close_rights(&message);
        return result == 0 ? XW11_FD_ERR_SYSTEM : XW11_FD_ERR_PROTOCOL;
    }

    int found_fd = -1;
    size_t found_count = 0;
    bool malformed = marker != kFdMarker ||
                     (message.msg_flags & MSG_CTRUNC) != 0;
    for (struct cmsghdr *cmsg = CMSG_FIRSTHDR(&message); cmsg != nullptr;
         cmsg = CMSG_NXTHDR(&message, cmsg)) {
        if (cmsg->cmsg_level != SOL_SOCKET || cmsg->cmsg_type != SCM_RIGHTS) {
            malformed = true;
            continue;
        }
        if (cmsg->cmsg_len < CMSG_LEN(sizeof(int))) {
            malformed = true;
            continue;
        }
        const size_t bytes = cmsg->cmsg_len - CMSG_LEN(0);
        if (bytes % sizeof(int) != 0) {
            malformed = true;
            continue;
        }
        const size_t count = bytes / sizeof(int);
        int *fds = reinterpret_cast<int *>(CMSG_DATA(cmsg));
        for (size_t i = 0; i < count; ++i) {
            ++found_count;
            if (found_fd == -1)
                found_fd = fds[i];
            else
                close(fds[i]);
        }
    }

    if (found_count != 1 || malformed) {
        if (found_fd >= 0)
            close(found_fd);
        return XW11_FD_ERR_PROTOCOL;
    }

#ifndef MSG_CMSG_CLOEXEC
    const int flags = fcntl(found_fd, F_GETFD);
    if (flags < 0 || fcntl(found_fd, F_SETFD, flags | FD_CLOEXEC) < 0) {
        close(found_fd);
        return XW11_FD_ERR_SYSTEM;
    }
#endif
    *received_fd = found_fd;
    return XW11_FD_OK;
}
