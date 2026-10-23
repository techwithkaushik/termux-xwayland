#pragma once
#include "xw11_input_event.h"
#include "xw11_transport_io.h"
#ifdef __cplusplus
extern "C" {
#endif
typedef enum xw11_input_transport_result {
    XW11_INPUT_TRANSPORT_OK = 0,
    XW11_INPUT_TRANSPORT_ERR_ARGUMENT,
    XW11_INPUT_TRANSPORT_ERR_SEQUENCE,
    XW11_INPUT_TRANSPORT_ERR_CODEC,
    XW11_INPUT_TRANSPORT_ERR_IO
} xw11_input_transport_result;
/* Call from one dedicated writer thread per socket; sequence starts at 1. */
xw11_input_transport_result xw11_send_input_event(
    int fd, uint32_t session_id, uint64_t *next_sequence,
    const xw11_input_event *event);
#ifdef __cplusplus
}
#endif
