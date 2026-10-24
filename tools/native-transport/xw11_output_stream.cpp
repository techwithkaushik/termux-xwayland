#include "xw11_output_stream.h"

#include <string.h>
#include <vector>

namespace {
void put32(uint8_t *p, uint32_t v) {
    p[0] = static_cast<uint8_t>(v >> 24);
    p[1] = static_cast<uint8_t>(v >> 16);
    p[2] = static_cast<uint8_t>(v >> 8);
    p[3] = static_cast<uint8_t>(v);
}
uint32_t get32(const uint8_t *p) {
    return (static_cast<uint32_t>(p[0]) << 24) |
           (static_cast<uint32_t>(p[1]) << 16) |
           (static_cast<uint32_t>(p[2]) << 8) | p[3];
}
xw11_output_stream_result map_io(xw11_io_result r) {
    return r == XW11_IO_OK ? XW11_OUTPUT_STREAM_OK
                           : XW11_OUTPUT_STREAM_ERR_TRANSPORT;
}
}

extern "C" xw11_output_stream_result xw11_output_stream_send(
    int fd, uint32_t session_id, const xw11_output_frame *frame,
    const uint8_t *pixels) {
    size_t source_bytes = 0;
    if (fd < 0 || session_id == 0 || !pixels)
        return XW11_OUTPUT_STREAM_ERR_ARGUMENT;
    if (xw11_output_frame_validate(frame, &source_bytes) != XW11_OUTPUT_FRAME_OK)
        return XW11_OUTPUT_STREAM_ERR_FRAME;

    std::vector<uint8_t> payload(XW11_PROTOCOL_MAX_PAYLOAD);
    uint8_t metadata[XW11_OUTPUT_FRAME_WIRE_SIZE];
    if (xw11_output_frame_encode(frame, metadata, sizeof(metadata)) !=
        XW11_OUTPUT_FRAME_OK)
        return XW11_OUTPUT_STREAM_ERR_FRAME;

    size_t offset = 0;
    while (offset < source_bytes) {
        const size_t chunk = source_bytes - offset < XW11_OUTPUT_CHUNK_MAX_BYTES
            ? source_bytes - offset : XW11_OUTPUT_CHUNK_MAX_BYTES;
        memcpy(payload.data(), metadata, sizeof(metadata));
        put32(payload.data() + XW11_OUTPUT_FRAME_WIRE_SIZE,
              static_cast<uint32_t>(offset));
        memcpy(payload.data() + XW11_OUTPUT_CHUNK_PREFIX_SIZE,
               pixels + offset, chunk);
        xw11_frame_header header = {
            XW11_PROTOCOL_MAJOR, XW11_PROTOCOL_MINOR, XW11_MSG_BUFFER_SUBMIT,
            0, static_cast<uint32_t>(XW11_OUTPUT_CHUNK_PREFIX_SIZE + chunk),
            session_id, frame->sequence_id
        };
        const xw11_io_result result =
            xw11_send_frame(fd, &header, payload.data());
        if (result != XW11_IO_OK)
            return map_io(result);
        offset += chunk;
    }
    return XW11_OUTPUT_STREAM_OK;
}

extern "C" xw11_output_stream_result xw11_output_stream_receive(
    int fd, uint32_t expected_session_id, uint64_t expected_generation,
    uint8_t *pixels, size_t pixel_capacity, xw11_output_frame *out_frame) {
    if (fd < 0 || expected_session_id == 0 || expected_generation == 0 ||
        !pixels || !out_frame)
        return XW11_OUTPUT_STREAM_ERR_ARGUMENT;

    std::vector<uint8_t> payload(XW11_PROTOCOL_MAX_PAYLOAD);
    xw11_frame_header header = {};
    xw11_output_frame frame = {};
    size_t source_bytes = 0;
    size_t offset = 0;
    bool first = true;
    while (true) {
        const xw11_io_result io = xw11_receive_frame(
            fd, &header, payload.data(), payload.size());
        if (io != XW11_IO_OK) return map_io(io);
        if (header.type != XW11_MSG_BUFFER_SUBMIT ||
            header.session_id != expected_session_id)
            return XW11_OUTPUT_STREAM_ERR_SESSION;
        if (header.sequence_id == 0)
            return XW11_OUTPUT_STREAM_ERR_SEQUENCE;
        if (header.payload_length < XW11_OUTPUT_CHUNK_PREFIX_SIZE)
            return XW11_OUTPUT_STREAM_ERR_FRAME;

        xw11_output_frame chunk_frame = {};
        if (xw11_output_frame_decode(payload.data(), XW11_OUTPUT_FRAME_WIRE_SIZE,
                                     &chunk_frame) != XW11_OUTPUT_FRAME_OK)
            return XW11_OUTPUT_STREAM_ERR_FRAME;
        const uint32_t chunk_offset =
            get32(payload.data() + XW11_OUTPUT_FRAME_WIRE_SIZE);
        const size_t chunk_bytes =
            header.payload_length - XW11_OUTPUT_CHUNK_PREFIX_SIZE;

        if (chunk_frame.sequence_id != header.sequence_id)
            return XW11_OUTPUT_STREAM_ERR_SEQUENCE;
        if (chunk_frame.surface_generation != expected_generation)
            return XW11_OUTPUT_STREAM_ERR_SESSION;
        if (first) {
            frame = chunk_frame;
            if (xw11_output_frame_validate(&frame, &source_bytes) !=
                XW11_OUTPUT_FRAME_OK)
                return XW11_OUTPUT_STREAM_ERR_FRAME;
            if (source_bytes > pixel_capacity)
                return XW11_OUTPUT_STREAM_ERR_BUFFER_TOO_SMALL;
            first = false;
        } else if (memcmp(&frame, &chunk_frame, sizeof(frame)) != 0) {
            return XW11_OUTPUT_STREAM_ERR_FRAME;
        }
        if (chunk_offset != offset || chunk_bytes == 0 ||
            chunk_bytes > source_bytes - offset)
            return XW11_OUTPUT_STREAM_ERR_FRAME;
        memcpy(pixels + offset,
               payload.data() + XW11_OUTPUT_CHUNK_PREFIX_SIZE, chunk_bytes);
        offset += chunk_bytes;
        if (offset == source_bytes) {
            *out_frame = frame;
            return XW11_OUTPUT_STREAM_OK;
        }
    }
}
