#include "xw11_buffer_descriptor.h"

#include <assert.h>
#include <stdint.h>
#include <stdio.h>

static xw11_buffer_descriptor valid_descriptor() {
    xw11_buffer_descriptor d = {};
    d.buffer_id = 1;
    d.generation = 3;
    d.width = 720;
    d.height = 1600;
    d.stride_bytes = 2880;
    d.fourcc = XW11_FORMAT_XRGB8888;
    d.modifier = 0; /* Linear modifier; import support must still be negotiated. */
    return d;
}

int main() {
    uint64_t bytes = 99;
    xw11_buffer_descriptor d = valid_descriptor();
    assert(xw11_validate_buffer_descriptor(&d, &bytes) == XW11_BUFFER_VALID);
    assert(bytes == 2880ull * 1600ull);

    d.buffer_id = 0;
    assert(xw11_validate_buffer_descriptor(&d, &bytes) == XW11_BUFFER_ERR_ID);
    d = valid_descriptor(); d.generation = 0;
    assert(xw11_validate_buffer_descriptor(&d, &bytes) == XW11_BUFFER_ERR_GENERATION);
    d = valid_descriptor(); d.width = 0;
    assert(xw11_validate_buffer_descriptor(&d, &bytes) == XW11_BUFFER_ERR_DIMENSIONS);
    d = valid_descriptor(); d.width = XW11_BUFFER_MAX_DIMENSION + 1;
    assert(xw11_validate_buffer_descriptor(&d, &bytes) == XW11_BUFFER_ERR_DIMENSIONS);
    d = valid_descriptor(); d.fourcc = 0;
    assert(xw11_validate_buffer_descriptor(&d, &bytes) == XW11_BUFFER_ERR_FORMAT);
    d = valid_descriptor(); d.stride_bytes = 100;
    assert(xw11_validate_buffer_descriptor(&d, &bytes) == XW11_BUFFER_ERR_STRIDE);
    d = valid_descriptor(); d.height = XW11_BUFFER_MAX_DIMENSION;
    d.width = XW11_BUFFER_MAX_DIMENSION; d.stride_bytes = 65536;
    assert(xw11_validate_buffer_descriptor(&d, &bytes) == XW11_BUFFER_ERR_SIZE);
    assert(xw11_validate_buffer_descriptor(nullptr, &bytes) == XW11_BUFFER_ERR_ARGUMENT);
    assert(bytes == 0);

    d = valid_descriptor(); d.width = 800; d.height = 600;
    d.stride_bytes = 1600; d.fourcc = XW11_FORMAT_RGB565;
    assert(xw11_validate_buffer_descriptor(&d, &bytes) == XW11_BUFFER_VALID);
    assert(bytes == 960000ull);

    puts("PASS: XW11 buffer descriptor validation");
    return 0;
}
