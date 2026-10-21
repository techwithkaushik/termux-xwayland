#define _POSIX_C_SOURCE 200809L
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <getopt.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include <time.h>
#include <stdarg.h>

static FILE *txw_log_file;
static unsigned long txw_log_sequence;

static void txw_log_init(void) {
    const char *path = getenv("TERMUX_XWAYLAND_LOG");
    if (path && *path) {
        txw_log_file = fopen(path, "a");
        if (txw_log_file) setvbuf(txw_log_file, NULL, _IOLBF, 0);
    }
    if (!txw_log_file) txw_log_file = stderr;
}

static void txw_log(const char *level, const char *event, const char *format, ...) {
    if (!txw_log_file) txw_log_init();
    struct timespec now = {0};
    clock_gettime(CLOCK_REALTIME, &now);
    fprintf(txw_log_file, "TXWLOG ts=%lld.%03ld seq=%lu pid=%ld level=%s component=capture event=%s",
            (long long)now.tv_sec, now.tv_nsec / 1000000L, ++txw_log_sequence,
            (long)getpid(), level, event);
    if (format && *format) {
        fputc(' ', txw_log_file);
        va_list args;
        va_start(args, format);
        vfprintf(txw_log_file, format, args);
        va_end(args);
    }
    fputc('\n', txw_log_file);
    fflush(txw_log_file);
}

#include <wayland-client.h>
#include "weston-output-capture-client-protocol.h"

struct capture_state {
    struct wl_display *display;
    struct wl_output *output;
    struct wl_shm *shm;
    struct weston_capture_v1 *factory;
    struct weston_capture_source_v1 *source;
    uint32_t format;
    int width;
    int height;
    int got_size;
    int got_format;
    int formats_done;
    int source_events_seen;
    int completed;
    int failed;
    char failure[256];
};

static void registry_global(void *data, struct wl_registry *registry,
                            uint32_t name, const char *interface, uint32_t version) {
    struct capture_state *s = data;
    txw_log("DEBUG", "registry_global", "name=%u interface=%s version=%u", name, interface, version);
    if (!strcmp(interface, "wl_output") && !s->output)
        s->output = wl_registry_bind(registry, name, &wl_output_interface,
                                     version < 4 ? version : 4);
    else if (!strcmp(interface, "wl_shm") && !s->shm)
        s->shm = wl_registry_bind(registry, name, &wl_shm_interface, 1);
    else if (!strcmp(interface, "weston_capture_v1") && !s->factory)
        s->factory = wl_registry_bind(registry, name, &weston_capture_v1_interface,
                                      version < 2 ? version : 2);
}
static void registry_remove(void *data, struct wl_registry *registry, uint32_t name) {
    (void)data; (void)registry; (void)name;
}
static const struct wl_registry_listener registry_listener = {
    registry_global, registry_remove
};

static void capture_format(void *data, struct weston_capture_source_v1 *source, uint32_t drm_format) {
    struct capture_state *s = data;
    (void)source;
    s->source_events_seen++;
    fprintf(stderr, "CAPTURE_EVENT: drm_format=0x%08x\n", drm_format);

    /*
     * Weston reports DRM fourcc values; wl_shm buffers use wl_shm_format
     * enum values. Map explicitly before creating the shared-memory buffer.
     */
    uint32_t shm_format;
    switch (drm_format) {
    case 0x34325258u: /* DRM_FORMAT_XRGB8888: XR24 */
        shm_format = WL_SHM_FORMAT_XRGB8888;
        break;
    case 0x34325241u: /* DRM_FORMAT_ARGB8888: AR24 */
        shm_format = WL_SHM_FORMAT_ARGB8888;
        break;
    default:
        txw_log("ERROR", "unsupported_capture_format",
                "drm_format=0x%08x", drm_format);
        s->failed = 1;
        snprintf(s->failure, sizeof(s->failure),
                 "unsupported DRM capture format 0x%08x", drm_format);
        return;
    }

    if (!s->got_format || shm_format == WL_SHM_FORMAT_ARGB8888) {
        s->format = shm_format;
        s->got_format = 1;
    }
}
static void capture_size(void *data, struct weston_capture_source_v1 *source, int32_t width, int32_t height) {
    struct capture_state *s = data;
    (void)source;
    s->source_events_seen++;
    fprintf(stderr, "CAPTURE_EVENT: size=%dx%d\n", width, height);
    if (width > 0 && height > 0 && width <= 8192 && height <= 8192) {
        s->width = width;
        s->height = height;
        s->got_size = 1;
    } else {
        s->failed = 1;
        snprintf(s->failure, sizeof(s->failure), "invalid capture dimensions %dx%d", width, height);
    }
}
static void capture_complete(void *data, struct weston_capture_source_v1 *source) {
    (void)source;
    fprintf(stderr, "CAPTURE_EVENT: complete\n");
    txw_log("INFO", "capture_complete_event", NULL);
    ((struct capture_state *)data)->completed = 1;
}
static void capture_retry(void *data, struct weston_capture_source_v1 *source) {
    (void)source;
    struct capture_state *s = data;
    txw_log("ERROR", "capture_retry_event", NULL);
    s->failed = 1;
    snprintf(s->failure, sizeof(s->failure), "capture buffer rejected (retry)");
}
static void capture_failed(void *data, struct weston_capture_source_v1 *source, const char *message) {
    (void)source;
    struct capture_state *s = data;
    txw_log("ERROR", "capture_failed_event", "message=%s", message ? message : "no reason supplied");
    s->failed = 1;
    snprintf(s->failure, sizeof(s->failure), "Weston capture failed: %s", message ? message : "no reason supplied");
}
static void capture_formats_done(void *data, struct weston_capture_source_v1 *source) {
    (void)source;
    struct capture_state *s = data;
    s->source_events_seen++;
    fprintf(stderr, "CAPTURE_EVENT: formats_done\n");
    ((struct capture_state *)data)->formats_done = 1;
}
static const struct weston_capture_source_v1_listener source_listener = {
    capture_format, capture_size, capture_complete, capture_retry,
    capture_failed, capture_formats_done
};

static int wait_events(struct capture_state *s, int (*done)(struct capture_state *), int timeout_ms) {
    int elapsed = 0;
    txw_log("INFO", "event_wait_begin", "timeout_ms=%d events=%d size=%d format=%d formats_done=%d",
            timeout_ms, s->source_events_seen, s->got_size, s->got_format, s->formats_done);
    while (!done(s) && !s->failed && elapsed < timeout_ms) {
        while (wl_display_prepare_read(s->display) != 0) {
            if (wl_display_dispatch_pending(s->display) < 0) {
                snprintf(s->failure, sizeof(s->failure), "dispatch pending failed: %s", strerror(errno));
                s->failed = 1;
                break;
            }
            if (done(s) || s->failed) break;
        }
        if (done(s) || s->failed) break;
        if (wl_display_flush(s->display) < 0 && errno != EAGAIN) {
            wl_display_cancel_read(s->display);
            snprintf(s->failure, sizeof(s->failure), "flush failed: %s", strerror(errno));
            s->failed = 1;
            break;
        }
        struct pollfd pfd = { .fd = wl_display_get_fd(s->display), .events = POLLIN };
        int slice = timeout_ms - elapsed > 500 ? 500 : timeout_ms - elapsed;
        int rc = poll(&pfd, 1, slice);
        if (rc < 0) {
            int saved = errno;
            wl_display_cancel_read(s->display);
            if (saved == EINTR) { elapsed += slice; continue; }
            snprintf(s->failure, sizeof(s->failure), "poll failed: %s", strerror(saved));
            s->failed = 1;
            break;
        }
        elapsed += slice;
        if (rc == 0) {
            wl_display_cancel_read(s->display);
            txw_log("DEBUG", "event_wait_tick", "elapsed_ms=%d events=%d size=%d format=%d formats_done=%d",
                    elapsed, s->source_events_seen, s->got_size, s->got_format, s->formats_done);
            continue;
        }
        if (pfd.revents & (POLLERR | POLLHUP | POLLNVAL)) {
            wl_display_cancel_read(s->display);
            snprintf(s->failure, sizeof(s->failure), "Wayland FD poll flags=0x%x", pfd.revents);
            s->failed = 1;
            break;
        }
        if (wl_display_read_events(s->display) < 0 ||
            wl_display_dispatch_pending(s->display) < 0) {
            snprintf(s->failure, sizeof(s->failure), "Wayland event read/dispatch failed: %s", strerror(errno));
            s->failed = 1;
            break;
        }
    }
    if (!done(s) && !s->failed) {
        s->failed = 1;
        snprintf(s->failure, sizeof(s->failure), "timed out waiting for Wayland events after %dms", elapsed);
    }
    txw_log(s->failed ? "ERROR" : "INFO", "event_wait_end",
            "elapsed_ms=%d done=%d failed=%d events=%d size=%d format=%d formats_done=%d message=%s",
            elapsed, done(s), s->failed, s->source_events_seen, s->got_size, s->got_format,
            s->formats_done, s->failure);
    return s->failed ? -1 : 0;
}
static int source_ready(struct capture_state *s) {
    return s->got_size && s->got_format && (s->formats_done || wl_proxy_get_version((struct wl_proxy *)s->source) < 2);
}
static int capture_done(struct capture_state *s) { return s->completed; }

static int make_buffer_file(size_t bytes) {
    const char *tmp = getenv("TMPDIR");
    if (!tmp || !*tmp) tmp = "/data/data/com.termux/files/usr/tmp";
    size_t n = strlen(tmp) + 32;
    char *path = malloc(n);
    if (!path) return -1;
    snprintf(path, n, "%s/txw-frame-XXXXXX", tmp);
    int fd = mkstemp(path);
    int saved = errno;
    unlink(path);
    free(path);
    errno = saved;
    if (fd < 0) return -1;
    if (ftruncate(fd, (off_t)bytes) < 0) { close(fd); return -1; }
    return fd;
}

struct sync_wait_state { int done; };
static void sync_wait_done(void *data, struct wl_callback *callback, uint32_t serial) {
    (void)serial;
    ((struct sync_wait_state *)data)->done = 1;
    wl_callback_destroy(callback);
}
static const struct wl_callback_listener sync_wait_listener = { sync_wait_done };

/* Bound protocol handshakes so a wedged compositor produces a useful error
 * instead of requiring the caller to kill this helper with timeout(1). */
static int roundtrip_with_timeout(struct wl_display *display, int timeout_ms) {
    struct sync_wait_state state = {0};
    struct wl_callback *callback = wl_display_sync(display);
    if (!callback) return -1;
    if (wl_callback_add_listener(callback, &sync_wait_listener, &state) < 0) {
        wl_callback_destroy(callback);
        return -1;
    }
    if (wl_display_flush(display) < 0) {
        wl_callback_destroy(callback);
        return -1;
    }
    int remaining = timeout_ms;
    while (!state.done && remaining > 0) {
        /* Never call wl_display_dispatch() after poll: it may block while
         * waiting for a second event. Use Wayland's prepare/read protocol. */
        while (wl_display_prepare_read(display) != 0) {
            if (wl_display_dispatch_pending(display) < 0) {
                wl_callback_destroy(callback);
                return -1;
            }
            if (state.done) break;
        }
        if (state.done) break;
        if (wl_display_flush(display) < 0 && errno != EAGAIN) {
            wl_display_cancel_read(display);
            wl_callback_destroy(callback);
            return -1;
        }
        struct pollfd pfd = { .fd = wl_display_get_fd(display), .events = POLLIN };
        int slice = remaining > 250 ? 250 : remaining;
        int rc = poll(&pfd, 1, slice);
        if (rc < 0) {
            int saved = errno;
            wl_display_cancel_read(display);
            if (saved == EINTR) { remaining -= slice; continue; }
            wl_callback_destroy(callback);
            errno = saved;
            return -1;
        }
        remaining -= slice;
        if (rc == 0) {
            wl_display_cancel_read(display);
            continue;
        }
        if (pfd.revents & (POLLERR | POLLHUP | POLLNVAL)) {
            wl_display_cancel_read(display);
            wl_callback_destroy(callback);
            errno = EPIPE;
            return -1;
        }
        if (wl_display_read_events(display) < 0 ||
            wl_display_dispatch_pending(display) < 0) {
            wl_callback_destroy(callback);
            return -1;
        }
    }
    if (!state.done) {
        wl_callback_destroy(callback);
        errno = ETIMEDOUT;
        return -2;
    }
    return 0;
}

static int run_capture(struct capture_state *s, int requested_width, int requested_height) {
    if (wl_display_roundtrip(s->display) < 0) {
        fprintf(stderr, "CAPTURE_ERROR: initial Wayland roundtrip failed\n");
        return 2;
    }
    if (!s->output || !s->shm || !s->factory) {
        fprintf(stderr, "CAPTURE_UNAVAILABLE: missing wl_output, wl_shm, or weston_capture_v1 global\n");
        return 3;
    }
    /*
     * Capture the compositor's blending space, not the post-composition
     * framebuffer. Weston may keep the native XWayland cursor as a separate
     * cursor surface/plane; framebuffer capture can omit it. The blending
     * source preserves the compositor-rendered cursor without drawing a
     * synthetic Android-side pointer.
     */
    fprintf(stderr, "CAPTURE_SOURCE_CREATE: output=%s source=blending\n", s->output ? "present" : "missing");
    s->source = weston_capture_v1_create(s->factory, s->output, WESTON_CAPTURE_V1_SOURCE_BLENDING);
    if (!s->source) {
        fprintf(stderr, "CAPTURE_ERROR: could not create Weston capture source\n");
        return 4;
    }
    weston_capture_source_v1_add_listener(s->source, &source_listener, s);
    txw_log("INFO", "source_handshake_begin", "timeout_ms=5000");
    int handshake = roundtrip_with_timeout(s->display, 5000);
    txw_log(handshake == 0 ? "INFO" : "ERROR", "source_handshake_end", "result=%d errno=%d events=%d size=%d format=%d formats_done=%d", handshake, errno, s->source_events_seen, s->got_size, s->got_format, s->formats_done);
    if (handshake < 0) {
        const struct wl_interface *interface = NULL;
        uint32_t object_id = 0;
        uint32_t code = wl_display_get_protocol_error(s->display, &interface, &object_id);
        if (interface) {
            fprintf(stderr, "CAPTURE_PROTOCOL_ERROR: interface=%s code=%u object=%u\n",
                    interface->name, code, object_id);
        } else if (handshake == -2 || errno == ETIMEDOUT) {
            fprintf(stderr, "CAPTURE_TIMEOUT: compositor did not complete source handshake within 5000ms\n");
        } else {
            fprintf(stderr, "CAPTURE_CONNECTION_ERROR: source handshake failed: %s\n",
                    strerror(errno));
        }
        fprintf(stderr, "CAPTURE_ERROR: source initialization incomplete (size=%d format=%d formats_done=%d events=%d)\n",
                s->got_size, s->got_format, s->formats_done, s->source_events_seen);
        return 5;
    }
    if (wait_events(s, source_ready, 5000) < 0) {
        fprintf(stderr, "CAPTURE_ERROR: %s (size=%d format=%d formats_done=%d events=%d)\n",
                s->failure[0] ? s->failure : "capture source did not provide size/format",
                s->got_size, s->got_format, s->formats_done, s->source_events_seen);
        return 5;
    }
    /*
     * Weston capture buffers must use the compositor's native output size.
     * The Android TXWB stream has a fixed size, so scale after capture rather
     * than rejecting perfectly valid outputs whose dimensions differ.
     */
    if (s->width > INT32_MAX / 4 ||
        (size_t)s->height > SIZE_MAX / ((size_t)s->width * 4) ||
        requested_width > INT32_MAX / 4 ||
        (size_t)requested_height > SIZE_MAX / ((size_t)requested_width * 4)) {
        fprintf(stderr, "CAPTURE_ERROR: frame dimensions overflow\n");
        return 7;
    }
    int stride = s->width * 4;
    size_t bytes = (size_t)stride * (size_t)s->height;
    size_t output_bytes = (size_t)requested_width * (size_t)requested_height * 4;
    int fd = make_buffer_file(bytes);
    if (fd < 0) {
        fprintf(stderr, "CAPTURE_ERROR: cannot allocate shared frame: %s\n", strerror(errno));
        return 8;
    }
    void *pixels = mmap(NULL, bytes, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (pixels == MAP_FAILED) {
        fprintf(stderr, "CAPTURE_ERROR: mmap failed: %s\n", strerror(errno));
        close(fd);
        return 9;
    }
    struct wl_shm_pool *pool = wl_shm_create_pool(s->shm, fd, (int32_t)bytes);
    close(fd);
    if (!pool) { munmap(pixels, bytes); fprintf(stderr, "CAPTURE_ERROR: wl_shm pool creation failed\n"); return 10; }
    struct wl_buffer *buffer = wl_shm_pool_create_buffer(pool, 0, s->width, s->height,
                                                         stride, s->format);
    wl_shm_pool_destroy(pool);
    if (!buffer) { munmap(pixels, bytes); fprintf(stderr, "CAPTURE_ERROR: wl_shm buffer creation failed\n"); return 11; }

    uint8_t *rgba = malloc(output_bytes);
    if (!rgba) { wl_buffer_destroy(buffer); munmap(pixels, bytes); return 12; }
    fprintf(stderr, "CAPTURE_READY: source=framebuffer source_size=%dx%d stream_size=%dx%d shm_format=%s; streaming RGBA frames to stdout\n",
            s->width, s->height, requested_width, requested_height,
            s->format == WL_SHM_FORMAT_ARGB8888 ? "ARGB8888" : "XRGB8888");
    txw_log("INFO", "capture_dimensions",
            "source_width=%d source_height=%d stream_width=%d stream_height=%d",
            s->width, s->height, requested_width, requested_height);
    unsigned long frame_number = 0;
    for (;;) {
        s->completed = 0;
        s->failed = 0;
        s->failure[0] = '\0';
        weston_capture_source_v1_capture(s->source, buffer);
        if (wl_display_flush(s->display) < 0 || wait_events(s, capture_done, 5000) < 0) {
            fprintf(stderr, "CAPTURE_STOPPED: %s\n", s->failure[0] ? s->failure : "capture request failed");
            free(rgba); wl_buffer_destroy(buffer); munmap(pixels, bytes); return 13;
        }
        const uint8_t *src = pixels;
        /*
         * Nearest-neighbour scaling keeps the helper dependency-free and
         * guarantees exactly requested_width * requested_height RGBA bytes
         * per frame, which is the contract consumed by BridgeClient.
         */
        for (int y = 0; y < requested_height; y++) {
            int source_y = (int)(((int64_t)y * s->height) / requested_height);
            for (int x = 0; x < requested_width; x++) {
                int source_x = (int)(((int64_t)x * s->width) / requested_width);
                size_t source_offset = ((size_t)source_y * (size_t)s->width +
                                        (size_t)source_x) * 4;
                size_t target_offset = ((size_t)y * (size_t)requested_width +
                                        (size_t)x) * 4;
                rgba[target_offset] = src[source_offset + 2];
                rgba[target_offset + 1] = src[source_offset + 1];
                rgba[target_offset + 2] = src[source_offset];
                rgba[target_offset + 3] =
                    s->format == WL_SHM_FORMAT_ARGB8888 ? src[source_offset + 3] : 255;
            }
        }
        /*
         * Log aggregate pixel health, not pixel contents. This distinguishes a
         * working Binder transport carrying a genuinely black Weston buffer
         * from a frame-forwarding failure without dumping desktop data to logs.
         */
        frame_number++;
        if (frame_number == 1 || frame_number % 50 == 0) {
            size_t non_black = 0;
            unsigned int max_channel = 0;
            for (size_t i = 0; i < output_bytes; i += 4) {
                unsigned int r = rgba[i];
                unsigned int g = rgba[i + 1];
                unsigned int b = rgba[i + 2];
                if (r || g || b) non_black++;
                if (r > max_channel) max_channel = r;
                if (g > max_channel) max_channel = g;
                if (b > max_channel) max_channel = b;
            }
            txw_log(non_black ? "INFO" : "WARN", "frame_pixel_health",
                    "frame=%lu non_black_pixels=%zu total_pixels=%zu max_channel=%u",
                    frame_number, non_black, output_bytes / 4, max_channel);
        }
        size_t written = 0;
        while (written < output_bytes) {
            ssize_t n = write(STDOUT_FILENO, rgba + written, output_bytes - written);
            if (n < 0 && errno == EINTR) continue;
            if (n <= 0) {
                fprintf(stderr, "CAPTURE_STOPPED: output pipe closed\n");
                free(rgba); wl_buffer_destroy(buffer); munmap(pixels, bytes); return 0;
            }
            written += (size_t)n;
        }
    }
}

int main(int argc, char **argv) {
    int width = 640, height = 360;
    static const struct option options[] = {
        {"display", required_argument, NULL, 'd'},
        {"width", required_argument, NULL, 'w'},
        {"height", required_argument, NULL, 'h'},
        {"format", required_argument, NULL, 'f'},
        {NULL, 0, NULL, 0}
    };
    int option;
    while ((option = getopt_long(argc, argv, "d:w:h:f:", options, NULL)) != -1) {
        char *end = NULL;
        long value;
        switch (option) {
        case 'd': if (setenv("WAYLAND_DISPLAY", optarg, 1) < 0) return 2; break;
        case 'w':
        case 'h':
            errno = 0; value = strtol(optarg, &end, 10);
            if (errno || !end || *end || value < 1 || value > 8192) {
                fprintf(stderr, "Invalid dimension: %s\n", optarg); return 2;
            }
            if (option == 'w') width = (int)value; else height = (int)value;
            break;
        case 'f': if (strcmp(optarg, "rgba")) { fprintf(stderr, "Only --format rgba is supported\n"); return 2; } break;
        default: fprintf(stderr, "Usage: %s [--display NAME] [--width N] [--height N] [--format rgba]\n", argv[0]); return 2;
        }
    }
    txw_log_init();
    txw_log("INFO", "process_start", "argc=%d", argc);
    struct capture_state s = {0};
    s.display = wl_display_connect(NULL);
    if (!s.display) { fprintf(stderr, "CAPTURE_ERROR: cannot connect to Wayland: %s\n", strerror(errno)); return 2; }
    struct wl_registry *registry = wl_display_get_registry(s.display);
    wl_registry_add_listener(registry, &registry_listener, &s);
    txw_log("INFO", "registry_roundtrip_begin", NULL);
    int registry_rc = wl_display_roundtrip(s.display);
    txw_log(registry_rc < 0 ? "ERROR" : "INFO", "registry_roundtrip_end", "result=%d output=%d shm=%d capture_factory=%d errno=%d", registry_rc, s.output != NULL, s.shm != NULL, s.factory != NULL, errno);
    int result = registry_rc < 0 ? 2 : run_capture(&s, width, height);
    if (s.source) weston_capture_source_v1_destroy(s.source);
    if (s.factory) weston_capture_v1_destroy(s.factory);
    if (s.output) wl_output_destroy(s.output);
    if (s.shm) wl_shm_destroy(s.shm);
    if (registry) wl_registry_destroy(registry);
    wl_display_disconnect(s.display);
    txw_log(result == 0 ? "INFO" : "ERROR", "process_exit", "exit_code=%d", result);
    if (txw_log_file && txw_log_file != stderr) fclose(txw_log_file);
    txw_log_file = NULL;
    return result;
}
