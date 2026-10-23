#pragma once

#ifdef __cplusplus
extern "C" {
#endif

typedef enum xw11_fd_result {
    XW11_FD_OK = 0,
    XW11_FD_ERR_ARGUMENT,
    XW11_FD_ERR_SYSTEM,
    XW11_FD_ERR_PROTOCOL
} xw11_fd_result;

/*
 * Send exactly one already-open descriptor over a connected AF_UNIX stream.
 * The descriptor is duplicated into the receiver by SCM_RIGHTS; the sender
 * retains ownership of its original descriptor. This is a control-plane
 * primitive, not buffer-import support.
 */
xw11_fd_result xw11_send_fd(int socket_fd, int fd_to_send);

/*
 * Receive exactly one descriptor. On success, caller owns the returned FD.
 * On any error, no received descriptor is transferred to the caller.
 */
xw11_fd_result xw11_receive_fd(int socket_fd, int *received_fd);

#ifdef __cplusplus
}
#endif
