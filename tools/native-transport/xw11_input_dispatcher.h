#pragma once

#include "xw11_input_event.h"
#include "xw11_input_queue.h"
#include "xw11_input_transport.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct xw11_input_dispatcher xw11_input_dispatcher;

typedef enum xw11_input_dispatcher_result {
    XW11_INPUT_DISPATCHER_OK = 0,
    XW11_INPUT_DISPATCHER_ERR_ARGUMENT,
    XW11_INPUT_DISPATCHER_ERR_MEMORY,
    XW11_INPUT_DISPATCHER_ERR_THREAD,
    XW11_INPUT_DISPATCHER_ERR_STATE,
    XW11_INPUT_DISPATCHER_ERR_QUEUE,
    XW11_INPUT_DISPATCHER_ERR_TRANSPORT
} xw11_input_dispatcher_result;

/*
 * On success, ownership of connected_fd transfers to the dispatcher. Use one
 * dispatcher per socket. The worker is the sole frame writer, preserving
 * sequence order. destroy() stops the worker, shuts down/closes the socket,
 * and releases the queue. A transport failure permanently stops submissions.
 */
xw11_input_dispatcher *xw11_input_dispatcher_create(
    int connected_fd, uint32_t session_id);
xw11_input_dispatcher_result xw11_input_dispatcher_start(
    xw11_input_dispatcher *dispatcher);
xw11_input_dispatcher_result xw11_input_dispatcher_submit(
    xw11_input_dispatcher *dispatcher, const xw11_input_event *event);
void xw11_input_dispatcher_stop(xw11_input_dispatcher *dispatcher);
void xw11_input_dispatcher_destroy(xw11_input_dispatcher *dispatcher);
xw11_input_dispatcher_result xw11_input_dispatcher_last_error(
    xw11_input_dispatcher *dispatcher);
size_t xw11_input_dispatcher_queue_size(xw11_input_dispatcher *dispatcher);
uint64_t xw11_input_dispatcher_dropped_motion_count(
    xw11_input_dispatcher *dispatcher);

#ifdef __cplusplus
}
#endif
