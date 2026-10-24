#include "xw11_output_stream.h"

#include <assert.h>
#include <stdint.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>
#include <thread>
#include <vector>

int main() {
    int sockets[2];
    assert(socketpair(AF_UNIX, SOCK_STREAM, 0, sockets) == 0);
    const uint32_t width = 512, height = 512, stride = width * 4;
    const size_t bytes = static_cast<size_t>(stride) * height;
    xw11_output_frame frame = {9, 3, width, height, stride,
        XW11_OUTPUT_FORMAT_XRGB8888, static_cast<uint32_t>(bytes)};
    std::vector<uint8_t> source(bytes);
    for (size_t i = 0; i < bytes; ++i) source[i] = static_cast<uint8_t>(i * 13u);

    xw11_output_stream_result send_result = XW11_OUTPUT_STREAM_ERR_TRANSPORT;
    std::thread sender([&] {
        send_result = xw11_output_stream_send(
            sockets[0], 44, &frame, source.data());
        shutdown(sockets[0], SHUT_WR);
    });
    std::vector<uint8_t> received(bytes);
    xw11_output_frame decoded = {};
    const auto receive_result = xw11_output_stream_receive(
        sockets[1], 44, 9, received.data(), received.size(), &decoded);
    sender.join();
    assert(send_result == XW11_OUTPUT_STREAM_OK);
    assert(receive_result == XW11_OUTPUT_STREAM_OK);
    assert(decoded.sequence_id == 3 && decoded.width == width &&
           decoded.height == height);
    assert(received == source);

    close(sockets[0]);
    close(sockets[1]);
    return 0;
}
