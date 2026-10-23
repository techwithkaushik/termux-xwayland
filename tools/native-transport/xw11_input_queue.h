#pragma once
#include "xw11_input_event.h"
#include <stddef.h>
#ifdef __cplusplus
extern "C" {
#endif
typedef struct xw11_input_queue xw11_input_queue;
typedef enum xw11_input_queue_result { XW11_INPUT_QUEUE_OK=0, XW11_INPUT_QUEUE_ERR_ARGUMENT, XW11_INPUT_QUEUE_ERR_FULL, XW11_INPUT_QUEUE_ERR_EMPTY } xw11_input_queue_result;
#define XW11_INPUT_QUEUE_CAPACITY 256u
xw11_input_queue *xw11_input_queue_create(void);
void xw11_input_queue_destroy(xw11_input_queue *queue);
/* Coalesces adjacent same-device motion. On pressure, motion is discarded before transitions. */
xw11_input_queue_result xw11_input_queue_push(xw11_input_queue *queue, const xw11_input_event *event);
xw11_input_queue_result xw11_input_queue_pop(xw11_input_queue *queue, xw11_input_event *event);
size_t xw11_input_queue_size(xw11_input_queue *queue);
uint64_t xw11_input_queue_dropped_motion_count(xw11_input_queue *queue);
#ifdef __cplusplus
}
#endif
