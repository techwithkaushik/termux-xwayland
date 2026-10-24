#include "xw11_surface_presenter_android.h"

#include <android/native_window.h>
#include <limits.h>
#include <pthread.h>
#include <string.h>

namespace {
constexpr uint32_t kMaxDimension = 8192;
constexpr size_t kMaxFrameBytes = 256u * 1024u * 1024u;

pthread_mutex_t g_state_lock = PTHREAD_MUTEX_INITIALIZER;
// Only one producer may lock/post a window buffer at a time. This lock is
// deliberately separate from lifecycle state so Surface callbacks do not need
// to hold the state mutex while a potentially large frame is copied.
pthread_mutex_t g_present_lock = PTHREAD_MUTEX_INITIALIZER;
ANativeWindow *g_window = nullptr;
uint64_t g_generation = 0;
// Monotonic high-water mark survives detach so delayed stale attaches cannot win.
uint64_t g_last_generation = 0;
// These cache fields are protected by g_present_lock and keyed by generation.
uint64_t g_cached_generation = 0;
uint32_t g_width = 0;
uint32_t g_height = 0;

class MutexLock {
public:
    explicit MutexLock(pthread_mutex_t *mutex) : mutex_(mutex) {
        pthread_mutex_lock(mutex_);
    }
    ~MutexLock() { pthread_mutex_unlock(mutex_); }
    MutexLock(const MutexLock&) = delete;
    MutexLock& operator=(const MutexLock&) = delete;
private:
    pthread_mutex_t *mutex_;
};
}

extern "C" xw11_present_result xw11_presenter_set_window(
    ANativeWindow *window, uint64_t generation) {
    if (window == nullptr || generation == 0)
        return XW11_PRESENT_ERR_ARGUMENT;

    ANativeWindow_acquire(window);
    ANativeWindow *previous = nullptr;
    {
        MutexLock lock(&g_state_lock);
        // Surface callbacks can race with delayed native work. Never let an older
        // generation replace a newer Surface, even after the newer Surface detached.
        if (generation < g_last_generation ||
            (generation == g_last_generation &&
             (g_generation != generation || g_window != window))) {
            ANativeWindow_release(window);
            return XW11_PRESENT_ERR_STALE_ATTACH;
        }
        // Repeated surfaceChanged callbacks for the same live Surface are idempotent.
        if (generation == g_last_generation && g_generation == generation &&
            g_window == window) {
            ANativeWindow_release(window);
            return XW11_PRESENT_OK;
        }
        previous = g_window;
        g_window = window;
        g_generation = generation;
        g_last_generation = generation;
    }
    // A concurrent presenter owns its own ANativeWindow reference, so releasing
    // the old owner here cannot invalidate an in-flight frame.
    if (previous != nullptr)
        ANativeWindow_release(previous);
    return XW11_PRESENT_OK;
}

extern "C" void xw11_presenter_clear_window(uint64_t generation) {
    ANativeWindow *previous = nullptr;
    {
        MutexLock lock(&g_state_lock);
        if (generation != 0 && generation == g_generation) {
            previous = g_window;
            g_window = nullptr;
            g_generation = 0;
        }
    }
    if (previous != nullptr)
        ANativeWindow_release(previous);
}

extern "C" xw11_present_result xw11_presenter_present_rgba8888(
    uint64_t generation, uint32_t width, uint32_t height,
    const uint8_t *pixels, size_t source_stride) {
    if (pixels == nullptr || generation == 0)
        return XW11_PRESENT_ERR_ARGUMENT;
    if (width == 0 || height == 0 || width > kMaxDimension ||
        height > kMaxDimension || width > SIZE_MAX / 4u ||
        (size_t)width * 4u > kMaxFrameBytes / height)
        return XW11_PRESENT_ERR_DIMENSIONS;

    const size_t row_bytes = (size_t)width * 4u;
    if (source_stride < row_bytes || source_stride > kMaxFrameBytes)
        return XW11_PRESENT_ERR_STRIDE;
    // The final row needs only row_bytes; it does not need trailing padding.
    // Bound the bytes actually read, not source_stride * height.
    if ((size_t)(height - 1u) > (SIZE_MAX - row_bytes) / source_stride)
        return XW11_PRESENT_ERR_STRIDE;
    const size_t source_bytes = (size_t)(height - 1u) * source_stride + row_bytes;
    if (source_bytes > kMaxFrameBytes)
        return XW11_PRESENT_ERR_STRIDE;

    MutexLock present_lock(&g_present_lock);
    ANativeWindow *window = nullptr;
    {
        MutexLock state_lock(&g_state_lock);
        if (g_window == nullptr || generation != g_generation)
            return XW11_PRESENT_ERR_STALE_GENERATION;
        window = g_window;
        // Keep this frame's window alive even if surfaceDestroyed races with
        // the copy/post below. A detached Surface may receive its final in-flight
        // post, but no use-after-free or descriptor lifetime race is possible.
        ANativeWindow_acquire(window);
    }

    xw11_present_result result = XW11_PRESENT_OK;
    if (g_cached_generation != generation) {
        g_cached_generation = generation;
        g_width = 0;
        g_height = 0;
    }

    const int32_t current_width = ANativeWindow_getWidth(window);
    const int32_t current_height = ANativeWindow_getHeight(window);
    if (g_width != width || g_height != height ||
        current_width != (int32_t)width || current_height != (int32_t)height) {
        const int32_t geometry = ANativeWindow_setBuffersGeometry(
            window, (int32_t)width, (int32_t)height, WINDOW_FORMAT_RGBA_8888);
        if (geometry != 0) {
            result = XW11_PRESENT_ERR_WINDOW_SIZE;
            ANativeWindow_release(window);
            return result;
        }
        g_width = width;
        g_height = height;
    }

    ANativeWindow_Buffer buffer;
    if (ANativeWindow_lock(window, &buffer, nullptr) != 0) {
        ANativeWindow_release(window);
        return XW11_PRESENT_ERR_WINDOW_LOCK;
    }

    if (buffer.bits == nullptr || buffer.width != (int32_t)width ||
        buffer.height != (int32_t)height || buffer.stride < (int32_t)width ||
        static_cast<size_t>(buffer.stride) > SIZE_MAX / 4u) {
        // ANativeWindow_Buffer::stride is an int32 pixel count. On 32-bit
        // ABIs, multiplying a malformed stride by four can overflow size_t.
        result = XW11_PRESENT_ERR_WINDOW_SIZE;
    } else {
        uint8_t *destination = static_cast<uint8_t *>(buffer.bits);
        const size_t destination_stride = (size_t)buffer.stride * 4u;
        for (uint32_t y = 0; y < height; ++y) {
            memcpy(destination + (size_t)y * destination_stride,
                   pixels + (size_t)y * source_stride, row_bytes);
        }
    }

    const int32_t post_result = ANativeWindow_unlockAndPost(window);
    if (post_result != 0 && result == XW11_PRESENT_OK)
        result = XW11_PRESENT_ERR_WINDOW_LOCK;
    ANativeWindow_release(window);
    return result;
}
