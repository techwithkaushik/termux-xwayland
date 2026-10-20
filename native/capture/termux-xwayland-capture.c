#define _POSIX_C_SOURCE 200809L
#include <errno.h>
#include <fcntl.h>
#include <getopt.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
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
    int completed;
    int failed;
    char failure[256];
};

static void registry_global(void *data, struct wl_registry *registry,
                            uint32_t name, const char *interface, uint32_t version) {
    struct capture_state *s = data;
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

static void capture_format(void *data, struct weston_capture_source_v1 *source, uint32_t format) {
    struct capture_state *s = data;
    (void)source;
    if (format == WL_SHM_FORMAT_XRGB8888 || format == WL_SHM_FORMAT_ARGB8888) {
        if (!s->got_format || format == WL_SHM_FORMAT_ARGB8888) {
            s->format = format;
            s->got_format = 1;
        }
    }
}
static void capture_size(void *data, struct weston_capture_source_v1 *source, int32_t width, int32_t height) {
    struct capture_state *s = data;
    (void)source;
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
    ((struct capture_state *)data)->completed = 1;
}
static void capture_retry(void *data, struct weston_capture_source_v1 *source) {
    (void)source;
    struct capture_state *s = data;
    s->failed = 1;
    snprintf(s->failure, sizeof(s->failure), "capture buffer rejected (retry)");
}
static void capture_failed(void *data, struct weston_capture_source_v1 *source, const char *message) {
    (void)source;
    struct capture_state *s = data;
    s->failed = 1;
    snprintf(s->failure, sizeof(s->failure), "Weston capture failed: %s", message ? message : "no reason supplied");
}
static void capture_formats_done(void *data, struct weston_capture_source_v1 *source) {
    (void)source;
    ((struct capture_state *)data)->formats_done = 1;
}
static const struct weston_capture_source_v1_listener source_listener = {
    capture_format, capture_size, capture_complete, capture_retry,
    capture_failed, capture_formats_done
};

static int wait_events(struct capture_state *s, int (*done)(struct capture_state *)) {
    while (!done(s) && !s->failed) {
        if (wl_display_dispatch(s->display) < 0) {
            snprintf(s->failure, sizeof(s->failure), "Wayland connection closed: %s", strerror(errno));
            s->failed = 1;
        }
    }
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

static int run_capture(struct capture_state *s, int requested_width, int requested_height) {
    if (wl_display_roundtrip(s->display) < 0) {
        fprintf(stderr, "CAPTURE_ERROR: initial Wayland roundtrip failed\n");
        return 2;
    }
    if (!s->output || !s->shm || !s->factory) {
        fprintf(stderr, "CAPTURE_UNAVAILABLE: missing wl_output, wl_shm, or weston_capture_v1 global\n");
        return 3;
    }
    /* Source 1 is Weston framebuffer capture. Weston still enforces its policy. */
    s->source = weston_capture_v1_create(s->factory, s->output, WESTON_CAPTURE_V1_SOURCE_FRAMEBUFFER);
    if (!s->source) {
        fprintf(stderr, "CAPTURE_ERROR: could not create Weston capture source\n");
        return 4;
    }
    weston_capture_source_v1_add_listener(s->source, &source_listener, s);
    if (wl_display_roundtrip(s->display) < 0 || wait_events(s, source_ready) < 0) {
        fprintf(stderr, "CAPTURE_ERROR: %s\n", s->failure[0] ? s->failure : "capture source did not provide size/format");
        return 5;
    }
    if (s->width != requested_width || s->height != requested_height) {
        fprintf(stderr, "CAPTURE_SIZE_MISMATCH: requested=%dx%d compositor=%dx%d\n",
                requested_width, requested_height, s->width, s->height);
        return 6;
    }
    if (s->width > INT32_MAX / 4 || (size_t)s->height > SIZE_MAX / ((size_t)s->width * 4)) {
        fprintf(stderr, "CAPTURE_ERROR: frame dimensions overflow\n");
        return 7;
    }
    int stride = s->width * 4;
    size_t bytes = (size_t)stride * (size_t)s->height;
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

    uint8_t *rgba = malloc(bytes);
    if (!rgba) { wl_buffer_destroy(buffer); munmap(pixels, bytes); return 12; }
    fprintf(stderr, "CAPTURE_READY: source=framebuffer size=%dx%d shm_format=%s; streaming RGBA frames to stdout\n",
            s->width, s->height, s->format == WL_SHM_FORMAT_ARGB8888 ? "ARGB8888" : "XRGB8888");
    for (;;) {
        s->completed = 0;
        s->failed = 0;
        s->failure[0] = '\0';
        weston_capture_source_v1_capture(s->source, buffer);
        if (wl_display_flush(s->display) < 0 || wait_events(s, capture_done) < 0) {
            fprintf(stderr, "CAPTURE_STOPPED: %s\n", s->failure[0] ? s->failure : "capture request failed");
            free(rgba); wl_buffer_destroy(buffer); munmap(pixels, bytes); return 13;
        }
        const uint8_t *src = pixels;
        for (size_t i = 0; i < bytes; i += 4) {
            rgba[i] = src[i + 2];
            rgba[i + 1] = src[i + 1];
            rgba[i + 2] = src[i];
            rgba[i + 3] = s->format == WL_SHM_FORMAT_ARGB8888 ? src[i + 3] : 255;
        }
        size_t written = 0;
        while (written < bytes) {
            ssize_t n = write(STDOUT_FILENO, rgba + written, bytes - written);
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
    struct capture_state s = {0};
    s.display = wl_display_connect(NULL);
    if (!s.display) { fprintf(stderr, "CAPTURE_ERROR: cannot connect to Wayland: %s\n", strerror(errno)); return 2; }
    struct wl_registry *registry = wl_display_get_registry(s.display);
    wl_registry_add_listener(registry, &registry_listener, &s);
    int result = wl_display_roundtrip(s.display) < 0 ? 2 : run_capture(&s, width, height);
    if (s.source) weston_capture_source_v1_destroy(s.source);
    if (s.factory) weston_capture_v1_destroy(s.factory);
    if (s.output) wl_output_destroy(s.output);
    if (s.shm) wl_shm_destroy(s.shm);
    if (registry) wl_registry_destroy(registry);
    wl_display_disconnect(s.display);
    return result;
}
