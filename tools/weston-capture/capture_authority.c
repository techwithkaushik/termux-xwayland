#define _GNU_SOURCE
#include <libweston/libweston.h>
#include <wayland-server-core.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

/*
 * Per-session authorization for weston_capture_v1.
 *
 * The producer writes its PID to XW11_CAPTURE_PID_FILE immediately before it
 * connects to the private Wayland socket. Only that exact Wayland peer PID,
 * running under the compositor's UID, is authorized. Missing, malformed,
 * stale, or unreadable files fail closed.
 */
static int read_authorized_pid(pid_t *pid_out)
{
    const char *path = getenv("XW11_CAPTURE_PID_FILE");
    if (!path || path[0] != '/' || !pid_out)
        return -1;

    int fd = open(path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0)
        return -1;

    struct stat st;
    if (fstat(fd, &st) != 0 || !S_ISREG(st.st_mode) ||
        st.st_uid != getuid() || (st.st_mode & 0077) != 0) {
        close(fd);
        return -1;
    }

    char buf[32];
    ssize_t n;
    do {
        n = read(fd, buf, sizeof(buf) - 1);
    } while (n < 0 && errno == EINTR);
    close(fd);
    if (n <= 0)
        return -1;
    buf[n] = '\0';

    char *end = NULL;
    errno = 0;
    long value = strtol(buf, &end, 10);
    if (errno || end == buf || value <= 1 || value > INT_MAX)
        return -1;
    while (*end == '\n' || *end == '\r' || *end == ' ' || *end == '\t')
        ++end;
    if (*end != '\0')
        return -1;

    *pid_out = (pid_t)value;
    return 0;
}

static void authorize_capture(struct wl_listener *listener,
                              struct weston_output_capture_attempt *attempt)
{
    (void)listener;
    if (!attempt || !attempt->who || !attempt->who->client)
        return;

    pid_t peer_pid = -1;
    uid_t peer_uid = (uid_t)-1;
    gid_t peer_gid = (gid_t)-1;
    wl_client_get_credentials(attempt->who->client, &peer_pid, &peer_uid, &peer_gid);
    (void)peer_gid;

    pid_t authorized_pid = -1;
    if (peer_uid == getuid() &&
        read_authorized_pid(&authorized_pid) == 0 &&
        peer_pid == authorized_pid) {
        attempt->authorized = true;
    }
}

WL_EXPORT int wet_module_init(struct weston_compositor *compositor,
                              int *argc, char *argv[])
{
    static struct wl_listener authority_listener;
    (void)argc;
    (void)argv;

    if (!compositor)
        return -1;

    if (!getenv("XW11_CAPTURE_PID_FILE")) {
        weston_log("xw11-capture-authority: XW11_CAPTURE_PID_FILE is not set; capture remains denied\n");
        return 0;
    }

    weston_compositor_add_screenshot_authority(compositor, &authority_listener,
                                                authorize_capture);
    weston_log("xw11-capture-authority: per-PID capture authorization installed\n");
    return 0;
}
