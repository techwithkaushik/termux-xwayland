#include "xw11_transport_protocol.h"

#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

static xw11_frame_header valid_header(void) {
    xw11_frame_header h;
    h.major = XW11_PROTOCOL_MAJOR;
    h.minor = XW11_PROTOCOL_MINOR;
    h.type = XW11_MSG_OUTPUT_CONFIG;
    h.flags = 0;
    h.payload_length = 4096;
    h.session_id = 17;
    h.sequence_id = UINT64_C(0x0102030405060708);
    return h;
}

int main(void) {
    uint8_t bytes[XW11_PROTOCOL_HEADER_SIZE];
    xw11_frame_header input = valid_header();
    xw11_frame_header output;

    assert(xw11_encode_header(&input, bytes, sizeof(bytes)) == XW11_PROTOCOL_OK);
    assert(memcmp(bytes, "XW11", 4) == 0);
    assert(bytes[20] == 1 && bytes[27] == 8);
    assert(xw11_decode_header(bytes, sizeof(bytes), &output) == XW11_PROTOCOL_OK);
    assert(output.type == input.type);
    assert(output.payload_length == input.payload_length);
    assert(output.session_id == input.session_id);
    assert(output.sequence_id == input.sequence_id);

    assert(xw11_decode_header(bytes, XW11_PROTOCOL_HEADER_SIZE - 1, &output)
           == XW11_PROTOCOL_ERR_ARGUMENT);

    bytes[0] = '!';
    assert(xw11_decode_header(bytes, sizeof(bytes), &output) == XW11_PROTOCOL_ERR_MAGIC);
    assert(xw11_encode_header(&input, bytes, sizeof(bytes) - 1) == XW11_PROTOCOL_ERR_ARGUMENT);

    input = valid_header(); input.major = 2;
    assert(xw11_encode_header(&input, bytes, sizeof(bytes)) == XW11_PROTOCOL_ERR_VERSION);
    input = valid_header(); input.type = 0;
    assert(xw11_encode_header(&input, bytes, sizeof(bytes)) == XW11_PROTOCOL_ERR_TYPE);
    input = valid_header(); input.flags = 1;
    assert(xw11_encode_header(&input, bytes, sizeof(bytes)) == XW11_PROTOCOL_ERR_FLAGS);
    input = valid_header(); input.payload_length = XW11_PROTOCOL_MAX_PAYLOAD + 1u;
    assert(xw11_encode_header(&input, bytes, sizeof(bytes)) == XW11_PROTOCOL_ERR_LENGTH);
    input = valid_header(); input.session_id = 0;
    assert(xw11_encode_header(&input, bytes, sizeof(bytes)) == XW11_PROTOCOL_ERR_SESSION);
    input = valid_header(); input.sequence_id = 0;
    assert(xw11_encode_header(&input, bytes, sizeof(bytes)) == XW11_PROTOCOL_ERR_SEQUENCE);

    xw11_sequence_tracker tracker = {0, 0};
    input = valid_header(); /* Use a valid frame to isolate the unset-session check. */
    assert(xw11_sequence_tracker_accept(&tracker, &input) == XW11_PROTOCOL_ERR_SESSION);
    assert(xw11_sequence_tracker_reset(&tracker, 17) == XW11_PROTOCOL_OK);
    input = valid_header();
    input.sequence_id = 1;
    assert(xw11_sequence_tracker_accept(&tracker, &input) == XW11_PROTOCOL_OK);
    input.sequence_id = 2;
    assert(xw11_sequence_tracker_accept(&tracker, &input) == XW11_PROTOCOL_OK);
    assert(xw11_sequence_tracker_accept(&tracker, &input) == XW11_PROTOCOL_ERR_SEQUENCE);
    input.sequence_id = 1;
    assert(xw11_sequence_tracker_accept(&tracker, &input) == XW11_PROTOCOL_ERR_SEQUENCE);
    input.sequence_id = 3;
    input.session_id = 18;
    assert(xw11_sequence_tracker_accept(&tracker, &input) == XW11_PROTOCOL_ERR_SESSION);
    assert(xw11_sequence_tracker_reset(&tracker, 0) == XW11_PROTOCOL_ERR_SESSION);
    assert(xw11_sequence_tracker_reset(&tracker, 18) == XW11_PROTOCOL_OK);
    input = valid_header();
    input.session_id = 18;
    input.sequence_id = 1;
    assert(xw11_sequence_tracker_accept(&tracker, &input) == XW11_PROTOCOL_OK);

    puts("XW11 protocol header and sequence tests passed");
    return 0;
}
