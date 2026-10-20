#define _POSIX_C_SOURCE 200809L
#include <stdio.h>
#include <string.h>
#include <wayland-client.h>

static int capture_global;

static void global(void *data, struct wl_registry *registry, uint32_t name,
                   const char *interface, uint32_t version) {
    (void)data;
    (void)registry;
    printf("%s v%u (name=%u)\n", interface, version, name);
    if (strcmp(interface, "weston_capture_v1") == 0)
        capture_global = 1;
}

static void global_remove(void *data, struct wl_registry *registry, uint32_t name) {
    (void)data;
    (void)registry;
    (void)name;
}

static const struct wl_registry_listener registry_listener = {
    .global = global,
    .global_remove = global_remove,
};

int main(void) {
    struct wl_display *display = wl_display_connect(NULL);
    if (!display) {
        fprintf(stderr, "Could not connect to Wayland display (check WAYLAND_DISPLAY and XDG_RUNTIME_DIR).\n");
        return 2;
    }

    struct wl_registry *registry = wl_display_get_registry(display);
    if (!registry) {
        fprintf(stderr, "Could not get Wayland registry.\n");
        wl_display_disconnect(display);
        return 2;
    }

    wl_registry_add_listener(registry, &registry_listener, NULL);
    if (wl_display_roundtrip(display) < 0) {
        fprintf(stderr, "Wayland registry roundtrip failed.\n");
        wl_registry_destroy(registry);
        wl_display_disconnect(display);
        return 2;
    }

    printf("weston_capture_v1: %s\n",
           capture_global ? "advertised (authorization still untested)" : "not advertised");
    wl_registry_destroy(registry);
    wl_display_disconnect(display);
    return capture_global ? 0 : 4;
}
