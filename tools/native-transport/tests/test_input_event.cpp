#include "xw11_input_event.h"

#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

int main() {
    xw11_input_event in{};
    in.surface_generation = 0x0102030405060708ULL;
    in.monotonic_time_ns = 0x1112131415161718ULL;
    in.type = XW11_INPUT_TOUCH;
    in.action = XW11_INPUT_MOVE;
    in.code = 7;
    in.x = -1234;
    in.y = 5678;
    in.modifiers = 0xaabbccdd;
    in.device_id = 42;
    uint8_t wire[XW11_INPUT_EVENT_WIRE_SIZE]{};
    assert(xw11_input_event_encode(&in, wire, sizeof(wire)) == XW11_INPUT_EVENT_OK);
    assert(wire[0] == 1 && wire[7] == 8 && wire[16] == 0 && wire[17] == 3);
    xw11_input_event out{};
    assert(xw11_input_event_decode(wire, sizeof(wire), &out) == XW11_INPUT_EVENT_OK);
    assert(out.surface_generation == in.surface_generation);
    assert(out.monotonic_time_ns == in.monotonic_time_ns);
    assert(out.type == in.type && out.action == in.action && out.code == in.code);
    assert(out.x == in.x && out.y == in.y && out.modifiers == in.modifiers);
    assert(out.device_id == in.device_id);
    assert(xw11_input_event_decode(wire, sizeof(wire) - 1, &out) == XW11_INPUT_EVENT_ERR_WIRE_SIZE);
    in.surface_generation = 0;
    assert(xw11_input_event_encode(&in, wire, sizeof(wire)) == XW11_INPUT_EVENT_ERR_GENERATION);
    in.surface_generation = 1;
    in.type = 99;
    assert(xw11_input_event_encode(&in, wire, sizeof(wire)) == XW11_INPUT_EVENT_ERR_TYPE);
    in.type = XW11_INPUT_POINTER_MOTION;
    in.action = XW11_INPUT_DOWN;
    assert(xw11_input_event_encode(&in, wire, sizeof(wire)) == XW11_INPUT_EVENT_ERR_ACTION);
    wire[0] = wire[1] = wire[2] = wire[3] = wire[4] = wire[5] = wire[6] = wire[7] = 0;
    assert(xw11_input_event_decode(wire, sizeof(wire), &out) == XW11_INPUT_EVENT_ERR_GENERATION);
    puts("XW11 input event codec tests passed");
    return 0;
}
