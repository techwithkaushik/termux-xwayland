#include "xw11_buffer_wire.h"

#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

int main() {
    xw11_buffer_descriptor source = {};
    source.buffer_id = 0x01020304u;
    source.generation = 0x0102030405060708ull;
    source.width = 720;
    source.height = 1600;
    source.stride_bytes = 2880;
    source.fourcc = XW11_FORMAT_XRGB8888;
    source.modifier = 0x1112131415161718ull;

    uint8_t wire[XW11_BUFFER_WIRE_SIZE] = {};
    assert(xw11_encode_buffer_descriptor(&source, wire, sizeof(wire)) == XW11_BUFFER_WIRE_OK);
    assert(wire[0] == 1 && wire[1] == 2 && wire[2] == 3 && wire[3] == 4);
    assert(wire[4] == 1 && wire[11] == 8);
    assert(wire[28] == 0x11 && wire[35] == 0x18);

    xw11_buffer_descriptor decoded = {};
    assert(xw11_decode_buffer_descriptor(wire, sizeof(wire), &decoded) == XW11_BUFFER_WIRE_OK);
    assert(decoded.buffer_id == source.buffer_id);
    assert(decoded.generation == source.generation);
    assert(decoded.width == source.width && decoded.height == source.height);
    assert(decoded.stride_bytes == source.stride_bytes);
    assert(decoded.fourcc == source.fourcc && decoded.modifier == source.modifier);

    assert(xw11_encode_buffer_descriptor(&source, wire, sizeof(wire) - 1) == XW11_BUFFER_WIRE_ERR_SIZE);
    assert(xw11_decode_buffer_descriptor(wire, sizeof(wire) - 1, &decoded) == XW11_BUFFER_WIRE_ERR_SIZE);
    assert(xw11_decode_buffer_descriptor(wire, sizeof(wire) + 1, &decoded) == XW11_BUFFER_WIRE_ERR_SIZE);
    assert(xw11_encode_buffer_descriptor(nullptr, wire, sizeof(wire)) == XW11_BUFFER_WIRE_ERR_ARGUMENT);
    assert(xw11_decode_buffer_descriptor(nullptr, sizeof(wire), &decoded) == XW11_BUFFER_WIRE_ERR_ARGUMENT);

    wire[0] = wire[1] = wire[2] = wire[3] = 0;
    assert(xw11_decode_buffer_descriptor(wire, sizeof(wire), &decoded) == XW11_BUFFER_WIRE_ERR_DESCRIPTOR);
    source.buffer_id = 0;
    assert(xw11_encode_buffer_descriptor(&source, wire, sizeof(wire)) == XW11_BUFFER_WIRE_ERR_DESCRIPTOR);

    puts("PASS: XW11 buffer descriptor wire codec");
    return 0;
}
