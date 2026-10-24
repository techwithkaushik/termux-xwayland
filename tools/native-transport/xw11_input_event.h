#pragma once

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Stable, ABI-independent input payload carried by XW11_MSG_INPUT_EVENT. */
#define XW11_INPUT_EVENT_WIRE_SIZE 40u

typedef enum xw11_input_event_type {
    XW11_INPUT_POINTER_MOTION = 1,
    XW11_INPUT_POINTER_BUTTON = 2,
    XW11_INPUT_TOUCH = 3,
    XW11_INPUT_KEY = 4,
    XW11_INPUT_FOCUS = 5
} xw11_input_event_type;

typedef enum xw11_input_event_action {
    XW11_INPUT_MOVE = 1,
    XW11_INPUT_DOWN = 2,
    XW11_INPUT_UP = 3,
    XW11_INPUT_CANCEL = 4
} xw11_input_event_action;

typedef enum xw11_input_event_result {
    XW11_INPUT_EVENT_OK = 0,
    XW11_INPUT_EVENT_ERR_ARGUMENT,
    XW11_INPUT_EVENT_ERR_TYPE,
    XW11_INPUT_EVENT_ERR_ACTION,
    XW11_INPUT_EVENT_ERR_GENERATION,
    XW11_INPUT_EVENT_ERR_WIRE_SIZE
} xw11_input_event_result;

typedef struct xw11_input_event {
    uint64_t surface_generation;
    uint64_t monotonic_time_ns;
    uint16_t type;
    uint16_t action;
    uint32_t code;       /* Linux input key/button code or touch pointer ID. */
    int32_t x;
    int32_t y;
    uint32_t modifiers;
    uint32_t device_id;
} xw11_input_event;

/* Wire fields are big-endian; signed coordinates use two's-complement bits. */
xw11_input_event_result xw11_input_event_encode(
    const xw11_input_event *event, uint8_t *out, size_t out_size);
xw11_input_event_result xw11_input_event_decode(
    const uint8_t *wire, size_t wire_size, xw11_input_event *out);

#ifdef __cplusplus
}
#endif
