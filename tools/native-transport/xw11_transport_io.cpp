#include "xw11_transport_io.h"

#include <errno.h>
#include <sys/socket.h>

namespace {

bool send_all(int fd, const uint8_t *bytes, size_t size) {
    size_t offset = 0;
    while (offset < size) {
        const ssize_t count = send(fd, bytes + offset, size - offset,
#ifdef MSG_NOSIGNAL
                                   MSG_NOSIGNAL
#else
                                   0
#endif
        );
        if (count > 0) {
            offset += static_cast<size_t>(count);
            continue;
        }
        if (count < 0 && errno == EINTR)
            continue;
        return false;
    }
    return true;
}

enum class ReadResult { kOk, kEof, kError };

ReadResult receive_all(int fd, uint8_t *bytes, size_t size) {
    size_t offset = 0;
    while (offset < size) {
        const ssize_t count = recv(fd, bytes + offset, size - offset, 0);
        if (count > 0) {
            offset += static_cast<size_t>(count);
            continue;
        }
        if (count == 0)
            return ReadResult::kEof;
        if (errno == EINTR)
            continue;
        return ReadResult::kError;
    }
    return ReadResult::kOk;
}

xw11_io_result map_read_result(ReadResult result) {
    if (result == ReadResult::kOk)
        return XW11_IO_OK;
    return result == ReadResult::kEof ? XW11_IO_ERR_EOF : XW11_IO_ERR_SYSTEM;
}

}  // namespace

extern "C" xw11_io_result xw11_send_frame(
    int fd, const xw11_frame_header *header, const uint8_t *payload) {
    if (fd < 0 || header == nullptr ||
        (header->payload_length != 0 && payload == nullptr))
        return XW11_IO_ERR_ARGUMENT;

    uint8_t wire_header[XW11_PROTOCOL_HEADER_SIZE];
    if (xw11_encode_header(header, wire_header, sizeof(wire_header)) !=
        XW11_PROTOCOL_OK)
        return XW11_IO_ERR_PROTOCOL;

    if (!send_all(fd, wire_header, sizeof(wire_header)))
        return XW11_IO_ERR_SYSTEM;
    if (header->payload_length != 0 &&
        !send_all(fd, payload, header->payload_length))
        return XW11_IO_ERR_SYSTEM;
    return XW11_IO_OK;
}

extern "C" xw11_io_result xw11_receive_frame(
    int fd, xw11_frame_header *header, uint8_t *payload,
    size_t payload_capacity) {
    if (fd < 0 || header == nullptr)
        return XW11_IO_ERR_ARGUMENT;

    uint8_t wire_header[XW11_PROTOCOL_HEADER_SIZE];
    const xw11_io_result read_header_result =
        map_read_result(receive_all(fd, wire_header, sizeof(wire_header)));
    if (read_header_result != XW11_IO_OK)
        return read_header_result;

    xw11_frame_header decoded;
    if (xw11_decode_header(wire_header, sizeof(wire_header), &decoded) !=
        XW11_PROTOCOL_OK)
        return XW11_IO_ERR_PROTOCOL;

    if (decoded.payload_length > payload_capacity ||
        (decoded.payload_length != 0 && payload == nullptr))
        return XW11_IO_ERR_BUFFER_TOO_SMALL;

    if (decoded.payload_length != 0) {
        const xw11_io_result payload_result =
            map_read_result(receive_all(fd, payload, decoded.payload_length));
        if (payload_result != XW11_IO_OK)
            return payload_result;
    }

    *header = decoded;
    return XW11_IO_OK;
}
