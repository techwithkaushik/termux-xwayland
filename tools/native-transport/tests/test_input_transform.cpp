#include "xw11_input_transform.h"

#include <assert.h>
#include <stdint.h>
#include <stdio.h>

int main() {
    xw11_input_transform t = {5, 10, 20, 100, 200, 720, 1600};
    uint32_t x = 0, y = 0;
    assert(xw11_map_input_coordinates(&t, 5, 10, 20, &x, &y) == XW11_INPUT_TRANSFORM_OK);
    assert(x == 0 && y == 0);
    assert(xw11_map_input_coordinates(&t, 5, 109, 219, &x, &y) == XW11_INPUT_TRANSFORM_OK);
    assert(x == 712 && y == 1592);
    assert(xw11_map_input_coordinates(&t, 4, 20, 30, &x, &y) == XW11_INPUT_TRANSFORM_ERR_GENERATION);
    assert(xw11_map_input_coordinates(&t, 5, 9, 30, &x, &y) == XW11_INPUT_TRANSFORM_ERR_OUTSIDE);
    assert(xw11_map_input_coordinates(&t, 5, 10, 220, &x, &y) == XW11_INPUT_TRANSFORM_ERR_OUTSIDE);
    t.viewport_width = 0;
    assert(xw11_map_input_coordinates(&t, 5, 10, 20, &x, &y) == XW11_INPUT_TRANSFORM_ERR_VIEWPORT);
    assert(xw11_map_input_coordinates(nullptr, 5, 0, 0, &x, &y) == XW11_INPUT_TRANSFORM_ERR_ARGUMENT);
    assert(xw11_map_input_coordinates(&t, 5, 0, 0, nullptr, &y) == XW11_INPUT_TRANSFORM_ERR_ARGUMENT);
    puts("PASS: XW11 input coordinate transform");
    return 0;
}
