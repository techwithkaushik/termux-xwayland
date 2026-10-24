#include "xw11_input_event.h"

#include <string.h>

namespace {
void put16(uint8_t *p, uint16_t v) {
    p[0] = static_cast<uint8_t>(v >> 8);
    p[1] = static_cast<uint8_t>(v);
}
void put32(uint8_t *p, uint32_t v) {
    p[0] = static_cast<uint8_t>(v >> 24);
    p[1] = static_cast<uint8_t>(v >> 16);
    p[2] = static_cast<uint8_t>(v >> 8);
    p[3] = static_cast<uint8_t>(v);
}
void put64(uint8_t *p, uint64_t v) {
    for (unsigned i = 0; i < 8; ++i)
        p[i] = static_cast<uint8_t>(v >> (56u - 8u * i));
}
uint16_t get16(const uint8_t *p) {
    return static_cast<uint16_t>((static_cast<uint16_t>(p[0]) << 8) | p[1]);
}
uint32_t get32(const uint8_t *p) {
    return (static_cast<uint32_t>(p[0]) << 24) |
           (static_cast<uint32_t>(p[1]) << 16) |
           (static_cast<uint32_t>(p[2]) << 8) |
           static_cast<uint32_t>(p[3]);
}
uint64_t get64(const uint8_t *p) {
    uint64_t v = 0;
    for (unsigned i = 0; i < 8; ++i)
        v = (v << 8) | p[i];
    return v;
}
xw11_input_event_result validate(const xw11_input_event *e) {
    if (!e) return XW11_INPUT_EVENT_ERR_ARGUMENT;
    if (e->surface_generation == 0) return XW11_INPUT_EVENT_ERR_GENERATION;
    if (e->type < XW11_INPUT_POINTER_MOTION || e->type > XW11_INPUT_FOCUS)
        return XW11_INPUT_EVENT_ERR_TYPE;
    if (e->action < XW11_INPUT_MOVE || e->action > XW11_INPUT_CANCEL)
        return XW11_INPUT_EVENT_ERR_ACTION;
    if ((e->type == XW11_INPUT_POINTER_MOTION && e->action != XW11_INPUT_MOVE) ||
        (e->type == XW11_INPUT_POINTER_BUTTON && e->action != XW11_INPUT_DOWN &&
         e->action != XW11_INPUT_UP) ||
        (e->type == XW11_INPUT_KEY && e->action != XW11_INPUT_DOWN &&
         e->action != XW11_INPUT_UP) ||
        (e->type == XW11_INPUT_FOCUS && e->action != XW11_INPUT_DOWN &&
         e->action != XW11_INPUT_UP))
        return XW11_INPUT_EVENT_ERR_ACTION;
    return XW11_INPUT_EVENT_OK;
}
}

extern "C" xw11_input_event_result xw11_input_event_encode(
    const xw11_input_event *event, uint8_t *out, size_t out_size) {
    xw11_input_event_result result = validate(event);
    if (result != XW11_INPUT_EVENT_OK) return result;
    if (!out) return XW11_INPUT_EVENT_ERR_ARGUMENT;
    if (out_size != XW11_INPUT_EVENT_WIRE_SIZE)
        return XW11_INPUT_EVENT_ERR_WIRE_SIZE;
    put64(out, event->surface_generation);
    put64(out + 8, event->monotonic_time_ns);
    put16(out + 16, event->type);
    put16(out + 18, event->action);
    put32(out + 20, event->code);
    put32(out + 24, static_cast<uint32_t>(event->x));
    put32(out + 28, static_cast<uint32_t>(event->y));
    put32(out + 32, event->modifiers);
    put32(out + 36, event->device_id);
    return XW11_INPUT_EVENT_OK;
}

extern "C" xw11_input_event_result xw11_input_event_decode(
    const uint8_t *wire, size_t wire_size, xw11_input_event *out) {
    if (!wire || !out) return XW11_INPUT_EVENT_ERR_ARGUMENT;
    if (wire_size != XW11_INPUT_EVENT_WIRE_SIZE)
        return XW11_INPUT_EVENT_ERR_WIRE_SIZE;
    xw11_input_event event{};
    event.surface_generation = get64(wire);
    event.monotonic_time_ns = get64(wire + 8);
    event.type = get16(wire + 16);
    event.action = get16(wire + 18);
    event.code = get32(wire + 20);
    event.x = static_cast<int32_t>(get32(wire + 24));
    event.y = static_cast<int32_t>(get32(wire + 28));
    event.modifiers = get32(wire + 32);
    event.device_id = get32(wire + 36);
    xw11_input_event_result result = validate(&event);
    if (result != XW11_INPUT_EVENT_OK) return result;
    *out = event;
    return XW11_INPUT_EVENT_OK;
}
