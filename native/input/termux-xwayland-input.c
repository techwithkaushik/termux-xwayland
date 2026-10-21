#define _POSIX_C_SOURCE 200809L
#include <X11/Xlib.h>
#include <X11/extensions/XTest.h>
#include <errno.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int parse_int(const char *s, int *out) {
    char *end = NULL;
    errno = 0;
    long value = strtol(s, &end, 10);
    if (errno || !end || *end != '\0' || value < -32768 || value > 32767) return 0;
    *out = (int)value;
    return 1;
}

static void button(Display *dpy, int n, int down) {
    if (n >= 1 && n <= 3) XTestFakeButtonEvent(dpy, (unsigned int)n, down, CurrentTime);
    else if (n == 4 || n == 5 || n == 6 || n == 7) {
        if (down) {
            XTestFakeButtonEvent(dpy, (unsigned int)n, True, CurrentTime);
            XTestFakeButtonEvent(dpy, (unsigned int)n, False, CurrentTime);
        }
    }
}

int main(int argc, char **argv) {
    const char *display_name = getenv("DISPLAY");
    int width = 640, height = 360;
    for (int i = 1; i < argc; ++i) {
        if (!strcmp(argv[i], "--display") && i + 1 < argc) display_name = argv[++i];
        else if (!strcmp(argv[i], "--width") && i + 1 < argc) { if (!parse_int(argv[++i], &width) || width < 1) return 64; }
        else if (!strcmp(argv[i], "--height") && i + 1 < argc) { if (!parse_int(argv[++i], &height) || height < 1) return 64; }
        else { fprintf(stderr, "usage: termux-xwayland-input [--display DISPLAY] [--width N] [--height N]\n"); return 64; }
    }
    if (!display_name || !*display_name) {
        fprintf(stderr, "INPUT_HELPER_UNAVAILABLE reason=DISPLAY_not_set\n");
        return 2;
    }
    Display *dpy = XOpenDisplay(display_name);
    if (!dpy) {
        fprintf(stderr, "INPUT_HELPER_UNAVAILABLE reason=XOpenDisplay_failed display=%s\n", display_name);
        return 3;
    }
    if (!XTestQueryExtension(dpy, &(int){0}, &(int){0}, &(int){0}, &(int){0})) {
        fprintf(stderr, "INPUT_HELPER_UNAVAILABLE reason=XTest_extension_missing\n");
        XCloseDisplay(dpy);
        return 4;
    }
    fprintf(stderr, "INPUT_HELPER_READY display=%s screen=%dx%d transport=persistent-XTest\n",
            display_name, width, height);
    fflush(stderr);

    char line[160];
    while (fgets(line, sizeof(line), stdin)) {
        size_t len = strlen(line);
        if (len == sizeof(line) - 1 && line[len - 1] != '\n') {
            int ch;
            while ((ch = getchar()) != '\n' && ch != EOF) {}
            continue;
        }
        char op[12] = {0}, action[8] = {0};
        int a = 0, b = 0;
        if (sscanf(line, "%11s %d %d %7s", op, &a, &b, action) < 1) continue;
        if (!strcmp(op, "REL")) {
            if (a < -8192 || a > 8192 || b < -8192 || b > 8192) continue;
            XTestFakeRelativeMotionEvent(dpy, a, b, CurrentTime);
        } else if (!strcmp(op, "MOVE")) {
            if (a < 0 || a >= width || b < 0 || b >= height) continue;
            XTestFakeMotionEvent(dpy, DefaultScreen(dpy), a, b, CurrentTime);
        } else if (!strcmp(op, "BUTTON")) {
            if (a < 1 || a > 3 || (strcmp(action, "DOWN") && strcmp(action, "UP"))) continue;
            button(dpy, a, !strcmp(action, "DOWN"));
        } else if (!strcmp(op, "DOWN") || !strcmp(op, "UP") || !strcmp(op, "CANCEL")) {
            if (a < 0 || a >= width || b < 0 || b >= height) continue;
            XTestFakeMotionEvent(dpy, DefaultScreen(dpy), a, b, CurrentTime);
            button(dpy, 1, !strcmp(op, "DOWN"));
        } else if (!strcmp(op, "CLICK")) {
            if (a < 1 || a > 3) continue;
            button(dpy, a, 1);
            button(dpy, a, 0);
        } else if (!strcmp(op, "SCROLL")) {
            if (a < -20 || a > 20 || b < -20 || b > 20) continue;
            int v = a > 0 ? 4 : 5;
            int h = b > 0 ? 7 : 6;
            for (int i = 0; i < abs(a); ++i) button(dpy, v, 1);
            for (int i = 0; i < abs(b); ++i) button(dpy, h, 1);
        } else {
            continue;
        }
        XFlush(dpy);
    }
    XCloseDisplay(dpy);
    return 0;
}
