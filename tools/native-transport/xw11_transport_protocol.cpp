#include "xw11_transport_protocol.h"

#include <string.h>

static uint16_t read_be16(const uint8_t *p) {
    return (uint16_t)(((uint16_t)p[0] << 8) | (uint16_t)p[1]);
}

static uint32_t read_be32(const uint8_t *p) {
    return ((uint32_t)p[0] << 24) | ((uint32_t)p[1] << 16) |
           ((uint32_t)p[2] << 8) | (uint32_t)p[3];
}

static uint64_t read_be64(const uint8_t *p) {
    return ((uint64_t)read_be32(p) << 32) | read_be32(p + 4);
}

static void write_be16(uint8_t *p, uint16_t v) {
    p[0] = (uint8_t)(v >> 8);
    p[1] = (uint8_t)v;
}

static void write_be32(uint8_t *p, uint32_t v) {
    p[0] = (uint8_t)(v >> 24);
    p[1] = (uint8_t)(v >> 16);
    p[2] = (uint8_t)(v >> 8);
    p[3] = (uint8_t)v;
}

static void write_be64(uint8_t *p, uint64_t v) {
    write_be32(p, (uint32_t)(v >> 32));
    write_be32(p + 4, (uint32_t)v);
}

static xw11_protocol_error validate(const xw11_frame_header *h) {
    if (h == NULL)
        return XW11_PROTOCOL_ERR_ARGUMENT;
    if (h->major != XW11_PROTOCOL_MAJOR || h->minor > XW11_PROTOCOL_MINOR)
        return XW11_PROTOCOL_ERR_VERSION;
    if (h->type < XW11_MSG_HELLO || h->type > XW11_MSG_GOODBYE)
        return XW11_PROTOCOL_ERR_TYPE;
    if (h->flags != 0)
        return XW11_PROTOCOL_ERR_FLAGS;
    if (h->payload_length > XW11_PROTOCOL_MAX_PAYLOAD)
        return XW11_PROTOCOL_ERR_LENGTH;
    if (h->session_id == 0)
        return XW11_PROTOCOL_ERR_SESSION;
    if (h->sequence_id == 0)
        return XW11_PROTOCOL_ERR_SEQUENCE;
    return XW11_PROTOCOL_OK;
}

xw11_protocol_error xw11_decode_header(const uint8_t *bytes, size_t size,
                                       xw11_frame_header *out) {
    if (bytes == NULL || out == NULL || size < XW11_PROTOCOL_HEADER_SIZE)
        return XW11_PROTOCOL_ERR_ARGUMENT;
    if (memcmp(bytes, XW11_PROTOCOL_MAGIC, 4) != 0)
        return XW11_PROTOCOL_ERR_MAGIC;

    xw11_frame_header h;
    h.major = bytes[4];
    h.minor = bytes[5];
    h.type = read_be16(bytes + 6);
    h.flags = read_be32(bytes + 8);
    h.payload_length = read_be32(bytes + 12);
    h.session_id = read_be32(bytes + 16);
    h.sequence_id = read_be64(bytes + 20);
    xw11_protocol_error result = validate(&h);
    if (result != XW11_PROTOCOL_OK)
        return result;
    *out = h;
    return XW11_PROTOCOL_OK;
}

xw11_protocol_error xw11_encode_header(const xw11_frame_header *header,
                                       uint8_t *out, size_t out_size) {
    if (out == NULL || out_size < XW11_PROTOCOL_HEADER_SIZE)
        return XW11_PROTOCOL_ERR_ARGUMENT;
    xw11_protocol_error result = validate(header);
    if (result != XW11_PROTOCOL_OK)
        return result;

    memcpy(out, XW11_PROTOCOL_MAGIC, 4);
    out[4] = header->major;
    out[5] = header->minor;
    write_be16(out + 6, header->type);
    write_be32(out + 8, header->flags);
    write_be32(out + 12, header->payload_length);
    write_be32(out + 16, header->session_id);
    write_be64(out + 20, header->sequence_id);
    return XW11_PROTOCOL_OK;
}


xw11_protocol_error xw11_sequence_tracker_reset(xw11_sequence_tracker *tracker,
                                                uint32_t session_id) {
    if (tracker == NULL)
        return XW11_PROTOCOL_ERR_ARGUMENT;
    if (session_id == 0)
        return XW11_PROTOCOL_ERR_SESSION;
    tracker->session_id = session_id;
    tracker->last_sequence_id = 0;
    return XW11_PROTOCOL_OK;
}

xw11_protocol_error xw11_sequence_tracker_accept(xw11_sequence_tracker *tracker,
                                                 const xw11_frame_header *header) {
    if (tracker == NULL || header == NULL)
        return XW11_PROTOCOL_ERR_ARGUMENT;

    xw11_protocol_error result = validate(header);
    if (result != XW11_PROTOCOL_OK)
        return result;
    if (tracker->session_id == 0 || header->session_id != tracker->session_id)
        return XW11_PROTOCOL_ERR_SESSION;
    if (header->sequence_id <= tracker->last_sequence_id)
        return XW11_PROTOCOL_ERR_SEQUENCE;

    tracker->last_sequence_id = header->sequence_id;
    return XW11_PROTOCOL_OK;
}
