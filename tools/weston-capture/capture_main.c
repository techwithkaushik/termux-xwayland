#define _GNU_SOURCE
#include <wayland-client.h>
#include "weston-output-capture-client-protocol.h"
#include "xw11_output_stream.h"
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

#define MAX_DIM 8192u
#define MAX_BYTES (64u * 1024u * 1024u)

struct app {
    struct wl_display *display;
    struct wl_registry *registry;
    struct wl_shm *shm;
    struct wl_output *output;
    struct weston_capture_v1 *factory;
    struct weston_capture_source_v1 *source;
    uint32_t output_name;
    uint32_t format;
    uint32_t width, height;
    int got_size, formats_done, complete, failed;
    char failure[256];
};

static void source_format(void *data, struct weston_capture_source_v1 *source,
                          uint32_t format) {
    struct app *a = data;
    if (format == XW11_OUTPUT_FORMAT_XRGB8888 ||
        format == XW11_OUTPUT_FORMAT_ARGB8888)
        a->format = format;
}
static void source_formats_done(void *data, struct weston_capture_source_v1 *source) {
    ((struct app *)data)->formats_done = 1;
}
static void source_size(void *data, struct weston_capture_source_v1 *source,
                        int32_t width, int32_t height) {
    struct app *a = data;
    if (width > 0 && height > 0) {
        a->width = (uint32_t)width;
        a->height = (uint32_t)height;
        a->got_size = 1;
    }
}
static void source_complete(void *data, struct weston_capture_source_v1 *source) {
    ((struct app *)data)->complete = 1;
}
static void source_retry(void *data, struct weston_capture_source_v1 *source) {
    struct app *a = data;
    a->failed = 1;
    snprintf(a->failure, sizeof(a->failure), "capture_retry: output parameters changed");
}
static void source_failed(void *data, struct weston_capture_source_v1 *source,
                          const char *message) {
    struct app *a = data;
    a->failed = 1;
    snprintf(a->failure, sizeof(a->failure), "capture_failed: %.220s",
             message ? message : "no compositor message");
}
static const struct weston_capture_source_v1_listener source_listener = {
    .format = source_format,
    .size = source_size,
    .complete = source_complete,
    .retry = source_retry,
    .failed = source_failed,
    .formats_done = source_formats_done,
};

static void registry_global(void *data, struct wl_registry *registry,
                            uint32_t name, const char *interface, uint32_t version) {
    struct app *a = data;
    if (!strcmp(interface, wl_shm_interface.name)) {
        a->shm = wl_registry_bind(registry, name, &wl_shm_interface, 1);
    } else if (!strcmp(interface, wl_output_interface.name) && !a->output) {
        a->output_name = name;
        a->output = wl_registry_bind(registry, name, &wl_output_interface,
                                     version < 4 ? version : 4);
    } else if (!strcmp(interface, weston_capture_v1_interface.name)) {
        a->factory = wl_registry_bind(registry, name,
                                      &weston_capture_v1_interface,
                                      version < 2 ? version : 2);
    }
}
static void registry_remove(void *data, struct wl_registry *registry, uint32_t name) {}
static const struct wl_registry_listener registry_listener = {
    registry_global, registry_remove
};

static int connect_authenticated_socket(const char *name, const char *token,
                                       uint32_t session) {
    if (!name || !*name || strlen(name) >= sizeof(((struct sockaddr_un *)0)->sun_path) - 1 ||
        !token || strlen(token) != 64) {
        fprintf(stderr, "ERROR: invalid abstract socket name or authentication token\n");
        return -1;
    }
    for (size_t i = 0; i < 64; ++i) {
        if (!((token[i] >= '0' && token[i] <= '9') ||
              (token[i] >= 'a' && token[i] <= 'f') ||
              (token[i] >= 'A' && token[i] <= 'F'))) {
            fprintf(stderr, "ERROR: output token must be 64 hexadecimal characters\n");
            return -1;
        }
    }
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) { perror("create output socket"); return -1; }
    struct sockaddr_un address = {0};
    address.sun_family = AF_UNIX;
    address.sun_path[0] = '\0'; /* Linux abstract namespace; cross-UID filesystem-independent IPC. */
    memcpy(address.sun_path + 1, name, strlen(name));
    socklen_t length = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + strlen(name));
    if (connect(fd, (struct sockaddr *)&address, length) != 0) {
        perror("connect Android XW11 output socket");
        close(fd);
        return -1;
    }
    char line[128];
    int n = snprintf(line, sizeof(line), "XW11AUTH %u %s\n", session, token);
    if (n <= 0 || (size_t)n >= sizeof(line)) { close(fd); return -1; }
    size_t offset = 0;
    while (offset < (size_t)n) {
        ssize_t written = write(fd, line + offset, (size_t)n - offset);
        if (written < 0 && errno == EINTR) continue;
        if (written <= 0) { perror("write output authentication"); close(fd); return -1; }
        offset += (size_t)written;
    }
    return fd;
}

static int make_shm_fd(void) {
#ifdef MFD_CLOEXEC
    int fd = memfd_create("xw11-capture", MFD_CLOEXEC);
    if (fd >= 0) return fd;
#endif
    char path[] = "/tmp/xw11-capture-XXXXXX";
    int fd2 = mkstemp(path);
    if (fd2 >= 0) {
        unlink(path);
        fcntl(fd2, F_SETFD, FD_CLOEXEC);
    }
    return fd2;
}

static void cleanup(struct app *a) {
    if (a->source) weston_capture_source_v1_destroy(a->source);
    if (a->factory) weston_capture_v1_destroy(a->factory);
    if (a->output) wl_output_destroy(a->output);
    if (a->shm) wl_shm_destroy(a->shm);
    if (a->registry) wl_registry_destroy(a->registry);
    if (a->display) wl_display_disconnect(a->display);
}

int main(int argc, char **argv) {
    int continuous = 0;
    int connect_mode = argc >= 2 && strcmp(argv[1], "--connect") == 0;
    int expected_argc = connect_mode ? 6 : 4;
    if (argc == expected_argc + 1 && strcmp(argv[argc - 1], "--continuous") == 0)
        continuous = 1;
    else if (argc != expected_argc) {
        fprintf(stderr, "usage: %s CONNECTED_SOCKET_FD SESSION_ID SURFACE_GENERATION [--continuous]\n"
                        "   or: %s --connect ABSTRACT_SOCKET_NAME TOKEN SESSION_ID SURFACE_GENERATION [--continuous]\n",
                argv[0], argv[0]);
        return 2;
    }
    char *end = NULL;
    int socket_fd = -1;
    const char *session_arg = connect_mode ? argv[4] : argv[2];
    const char *generation_arg = connect_mode ? argv[5] : argv[3];
    unsigned long session = strtoul(session_arg, &end, 10);
    if (!end || *end || !session || session > UINT32_MAX) return 2;
    unsigned long long generation = strtoull(generation_arg, &end, 10);
    if (!end || *end || !generation) return 2;
    if (connect_mode) {
        socket_fd = connect_authenticated_socket(argv[2], argv[3], (uint32_t)session);
        if (socket_fd < 0) return 4;
    } else {
        long parsed_fd = strtol(argv[1], &end, 10);
        if (!end || *end || parsed_fd < 0 || parsed_fd > INT_MAX) return 2;
        socket_fd = (int)parsed_fd;
    }

    /*
     * The descriptor is a capability supplied by the trusted launcher, not a
     * pathname to connect to. Reject pipes, files, TCP sockets and unconnected
     * sockets before touching the compositor. This is transport validation;
     * the launcher must still authenticate the peer/session before handing the
     * descriptor to this process.
     */
    int socket_type = 0;
    socklen_t socket_type_size = sizeof(socket_type);
    struct sockaddr_storage peer = {0};
    socklen_t peer_size = sizeof(peer);
    if (getsockopt((int)socket_fd, SOL_SOCKET, SO_TYPE,
                   &socket_type, &socket_type_size) != 0 ||
        socket_type != SOCK_STREAM ||
        getpeername((int)socket_fd, (struct sockaddr *)&peer, &peer_size) != 0 ||
        peer.ss_family != AF_UNIX) {
        fprintf(stderr, "ERROR: output FD must be a connected AF_UNIX SOCK_STREAM socket\\n");
        return 3;
    }
    int descriptor_flags = fcntl((int)socket_fd, F_GETFD);
    if (descriptor_flags < 0 ||
        fcntl((int)socket_fd, F_SETFD, descriptor_flags | FD_CLOEXEC) != 0) {
        perror("configure output socket descriptor");
        return 3;
    }

    struct app a = {0};
    a.display = wl_display_connect(NULL);
    if (!a.display) { perror("wl_display_connect"); return 10; }
    a.registry = wl_display_get_registry(a.display);
    wl_registry_add_listener(a.registry, &registry_listener, &a);
    if (wl_display_roundtrip(a.display) < 0 ||
        wl_display_roundtrip(a.display) < 0) {
        fprintf(stderr, "ERROR: Wayland registry roundtrip failed\n");
        cleanup(&a); return 11;
    }
    if (!a.factory) {
        fprintf(stderr, "ERROR: capture_global_unavailable (weston_capture_v1 not advertised)\n");
        cleanup(&a); return 12;
    }
    if (!a.output || !a.shm) {
        fprintf(stderr, "ERROR: required wl_output/wl_shm global unavailable\n");
        cleanup(&a); return 13;
    }
    a.source = weston_capture_v1_create(a.factory, a.output,
                                         WESTON_CAPTURE_V1_SOURCE_FRAMEBUFFER);
    if (!a.source) { cleanup(&a); return 14; }
    weston_capture_source_v1_add_listener(a.source, &source_listener, &a);
    if (wl_display_roundtrip(a.display) < 0 ||
        wl_display_roundtrip(a.display) < 0 || a.failed ||
        !a.formats_done || !a.got_size || !a.format) {
        fprintf(stderr, "ERROR: capture source unavailable: %s\n",
                a.failure[0] ? a.failure : "no supported XRGB8888/ARGB8888 format or size");
        cleanup(&a); return 15;
    }
    if (a.width > MAX_DIM || a.height > MAX_DIM ||
        (size_t)a.width * 4u > MAX_BYTES / a.height) {
        fprintf(stderr, "ERROR: output dimensions exceed 64 MiB capture budget\n");
        cleanup(&a); return 16;
    }

    const size_t stride = (size_t)a.width * 4u;
    const size_t bytes = stride * a.height;
    int shm_fd = make_shm_fd();
    if (shm_fd < 0 || ftruncate(shm_fd, (off_t)bytes) != 0) {
        perror("create capture shm"); if (shm_fd >= 0) close(shm_fd);
        cleanup(&a); return 17;
    }
    void *pixels = mmap(NULL, bytes, PROT_READ | PROT_WRITE, MAP_SHARED, shm_fd, 0);
    if (pixels == MAP_FAILED) {
        perror("mmap capture shm"); close(shm_fd); cleanup(&a); return 18;
    }
    struct wl_shm_pool *pool = wl_shm_create_pool(a.shm, shm_fd, (int)bytes);
    struct wl_buffer *buffer = wl_shm_pool_create_buffer(
        pool, 0, (int)a.width, (int)a.height, (int)stride,
        a.format == XW11_OUTPUT_FORMAT_XRGB8888
            ? WL_SHM_FORMAT_XRGB8888 : WL_SHM_FORMAT_ARGB8888);
    wl_shm_pool_destroy(pool);
    close(shm_fd);
    if (!buffer) {
        fprintf(stderr, "ERROR: could not create wl_shm capture buffer\n");
        munmap(pixels, bytes); cleanup(&a); return 19;
    }

    uint64_t sequence = 1;
    xw11_output_stream_result sent = XW11_OUTPUT_STREAM_OK;
    do {
        a.complete = 0;
        weston_capture_source_v1_capture(a.source, buffer);
        while (!a.complete && !a.failed) {
            if (wl_display_dispatch(a.display) < 0) {
                a.failed = 1;
                snprintf(a.failure, sizeof(a.failure), "Wayland connection lost during capture");
            }
        }
        if (a.failed) {
            fprintf(stderr, "ERROR: %s\n", a.failure);
            sent = XW11_OUTPUT_STREAM_ERR_TRANSPORT;
            break;
        }

        xw11_output_frame frame = {
            (uint64_t)generation, sequence, a.width, a.height, (uint32_t)stride,
            a.format, (uint32_t)bytes
        };
        sent = xw11_output_stream_send(
            (int)socket_fd, (uint32_t)session, &frame, (const uint8_t *)pixels);
        if (sent != XW11_OUTPUT_STREAM_OK) {
            fprintf(stderr, "ERROR: output transport failed (%d)\n", (int)sent);
            break;
        }
        if (sequence == UINT64_MAX) {
            fprintf(stderr, "ERROR: output frame sequence exhausted\n");
            sent = XW11_OUTPUT_STREAM_ERR_SEQUENCE;
            break;
        }
        ++sequence;
        if (!continuous) break;
    } while (1);

    wl_buffer_destroy(buffer);
    munmap(pixels, bytes);
    cleanup(&a);
    if (sent != XW11_OUTPUT_STREAM_OK) return 21;
    fprintf(stderr, "captured=%ux%u format=0x%08x frames=%llu bytes/frame=%zu\n",
            a.width, a.height, (unsigned long long)(sequence - 1), bytes);
    return 0;
}
