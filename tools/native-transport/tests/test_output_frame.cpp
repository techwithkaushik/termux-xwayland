#include "xw11_output_frame.h"

#include <assert.h>
#include <stdint.h>
#include <string.h>

int main() {
    xw11_output_frame frame = {7, 11, 2, 2, 12,
        XW11_OUTPUT_FORMAT_XRGB8888, 20};
    size_t bytes = 0;
    assert(xw11_output_frame_validate(&frame, &bytes) == XW11_OUTPUT_FRAME_OK);
    assert(bytes == 20);

    uint8_t wire[XW11_OUTPUT_FRAME_WIRE_SIZE] = {};
    assert(xw11_output_frame_encode(&frame, wire, sizeof(wire)) == XW11_OUTPUT_FRAME_OK);
    xw11_output_frame decoded = {};
    assert(xw11_output_frame_decode(wire, sizeof(wire), &decoded) == XW11_OUTPUT_FRAME_OK);
    assert(decoded.surface_generation == 7 && decoded.sequence_id == 11);
    assert(decoded.width == 2 && decoded.height == 2 && decoded.stride_bytes == 12);

    const uint8_t src[20] = {
        3, 2, 1, 0, 6, 5, 4, 0, 99, 99, 99, 99,
        9, 8, 7, 0, 12, 11, 10, 0
    };
    uint8_t dst[24] = {};
    assert(xw11_output_frame_to_rgba(&frame, src, sizeof(src), dst, sizeof(dst), 12)
           == XW11_OUTPUT_FRAME_OK);
    const uint8_t expected[] = {
        1,2,3,255, 4,5,6,255, 0,0,0,0,
        7,8,9,255, 10,11,12,255
    };
    assert(memcmp(dst, expected, 8) == 0);
    assert(memcmp(dst + 12, expected + 12, 8) == 0);

    frame.surface_generation = 0;
    assert(xw11_output_frame_validate(&frame, nullptr) ==
           XW11_OUTPUT_FRAME_ERR_GENERATION);
    frame.surface_generation = 7;
    frame.fourcc = 0x12345678u;
    assert(xw11_output_frame_validate(&frame, nullptr) ==
           XW11_OUTPUT_FRAME_ERR_FORMAT);
    frame.fourcc = XW11_OUTPUT_FORMAT_XRGB8888;
    frame.stride_bytes = 4;
    assert(xw11_output_frame_validate(&frame, nullptr) ==
           XW11_OUTPUT_FRAME_ERR_STRIDE);

    wire[39] = 1;
    assert(xw11_output_frame_decode(wire, sizeof(wire), &decoded) ==
           XW11_OUTPUT_FRAME_ERR_ARGUMENT);
    return 0;
}
