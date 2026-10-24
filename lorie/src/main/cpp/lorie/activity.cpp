#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include <cstring>
#include <pthread.h>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/mman.h>
#include <cerrno>
#include <jni.h>
#include <android/looper.h>
#include <cwchar>
#include <linux/in.h>
#include <arpa/inet.h>
#include <poll.h>
#include <new>
#include "xw11_surface_generation.h"
#include "xw11_surface_presenter_android.h"
#include "xw11_input_dispatcher.h"
#include "xw11_input_event.h"
#include "xw11_output_stream.h"
#include "xw11_output_frame.h"
#include "lorie.h"

#pragma clang diagnostic ignored "-Wunknown-pragmas"
#pragma ide diagnostic ignored "cppcoreguidelines-narrowing-conversions"
#pragma ide diagnostic ignored "ConstantFunctionResult"
#define log(prio, ...) __android_log_print(ANDROID_LOG_ ## prio, "LorieNative", __VA_ARGS__)
// `r` must be a LorieViewResources* — the connection fd is per-instance state, not a process global.
#define sendEvent(r, ...) do { if ((r) && (r)->connFd != -1) { lorieEvent e = { __VA_ARGS__ }; write((r)->connFd, &e, sizeof(e)); } } while (0)

// Experimental XW11 input transport; disabled until a connected session socket is supplied.
static pthread_mutex_t xw11InputMutex = PTHREAD_MUTEX_INITIALIZER;
static xw11_input_dispatcher *xw11InputDispatcher = nullptr;
static uint64_t xw11InputGeneration = 0;
// Prevent delayed Surface callbacks from replacing a newer input session.
static uint64_t xw11LastInputGeneration = 0;

/* Isolated experimental output receiver. It never touches LorieView's renderer. */
static pthread_mutex_t xw11OutputMutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_t xw11OutputThread;
static int xw11OutputFd = -1;
static uint32_t xw11OutputSession = 0;
static uint64_t xw11OutputGeneration = 0;
static bool xw11OutputThreadCreated = false;
static bool xw11OutputRunning = false;

static void *xw11OutputWorker(void *) {
    const int fd = xw11OutputFd;
    const uint32_t session = xw11OutputSession;
    const uint64_t generation = xw11OutputGeneration;
    const size_t capacity = XW11_OUTPUT_FRAME_MAX_BYTES;
    // Keep only the bounded wire buffer resident. Allocate the packed RGBA
    // destination to the validated dimensions of each received frame; reserving
    // two 64 MiB buffers at startup is wasteful on low-memory Android devices.
    uint8_t *pixels = static_cast<uint8_t *>(malloc(capacity));
    if (!pixels) {
        log(ERROR, "XW11 output receiver: wire frame buffer allocation failed");
        return nullptr;
    }
    uint64_t lastSequence = 0;
    bool firstFramePresented = false;
    while (true) {
        pthread_mutex_lock(&xw11OutputMutex);
        const bool running = xw11OutputRunning &&
            xw11OutputGeneration == generation && xw11OutputFd == fd;
        pthread_mutex_unlock(&xw11OutputMutex);
        if (!running) break;

        xw11_output_frame frame = {};
        const xw11_output_stream_result received = xw11_output_stream_receive(
            fd, session, generation, pixels, capacity, &frame);
        if (received != XW11_OUTPUT_STREAM_OK) {
            log(WARN, "XW11 output receiver stopped (transport result=%d)",
                (int)received);
            break;
        }
        if (frame.sequence_id <= lastSequence) {
            log(ERROR, "XW11 output receiver rejected non-monotonic frame sequence");
            break;
        }
        lastSequence = frame.sequence_id;

        /*
         * Validate metadata and convert into a separate tightly packed RGBA
         * buffer. This avoids mutating the received wire frame and makes source
         * stride/byte-count checks part of the actual Android presentation path.
         */
        const size_t rgbaStride = (size_t)frame.width * 4u;
        const size_t rgbaBytes = rgbaStride * (size_t)frame.height;
        uint8_t *rgba = static_cast<uint8_t *>(malloc(rgbaBytes));
        if (!rgba) {
            log(ERROR, "XW11 output receiver: RGBA allocation failed for %ux%u",
                frame.width, frame.height);
            break;
        }
        const xw11_output_frame_error converted = xw11_output_frame_to_rgba(
            &frame, pixels, capacity, rgba, rgbaBytes, rgbaStride);
        if (converted != XW11_OUTPUT_FRAME_OK) {
            log(ERROR, "XW11 output frame rejected during RGBA conversion (result=%d)",
                (int)converted);
            free(rgba);
            break;
        }
        const xw11_present_result presented = xw11_presenter_present_rgba8888(
            generation, frame.width, frame.height, rgba, rgbaStride);
        free(rgba);
        if (presented != XW11_PRESENT_OK) {
            log(WARN, "XW11 output frame not presented (result=%d)", (int)presented);
            break;
        }
        if (!firstFramePresented) {
            firstFramePresented = true;
            log(INFO, "XW11 first frame presented to Android Surface: %ux%u sequence=%llu generation=%llu",
                frame.width, frame.height,
                (unsigned long long)frame.sequence_id,
                (unsigned long long)generation);
            /*
             * The Binder-FD producer waits for this acknowledgement before it
             * reports frame_sent=1. This makes supervisor readiness mean that
             * Android actually presented the frame, not merely that socket
             * buffering accepted it.
             */
            static const char presentedAck[] = "XW11PRESENTED\n";
            size_t ackOffset = 0;
            while (ackOffset < sizeof(presentedAck) - 1) {
                ssize_t written = send(fd, presentedAck + ackOffset,
                                       sizeof(presentedAck) - 1 - ackOffset,
                                       MSG_NOSIGNAL);
                if (written < 0 && errno == EINTR) continue;
                if (written <= 0) {
                    log(WARN, "XW11 first-frame acknowledgement failed: %s",
                        strerror(errno));
                    break;
                }
                ackOffset += (size_t)written;
            }
        }
    }
    free(pixels);
    return nullptr;
}


struct Xw11InputLock {
    Xw11InputLock() { pthread_mutex_lock(&xw11InputMutex); }
    ~Xw11InputLock() { pthread_mutex_unlock(&xw11InputMutex); }
    Xw11InputLock(const Xw11InputLock&) = delete;
    Xw11InputLock& operator=(const Xw11InputLock&) = delete;
};

bool lorieDebugEnabled = false;

// Timestamp of the last real input reaching the X session, from any source. Read/written only
// from the Android main thread via JNI.
static volatile int64_t lastInputTimestampMs = 0;

static int64_t nowMs() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t) ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static struct {
    jclass self;
    jmethodID clientConnectedStateChanged, resetIme;
    jfieldID lastServerHeartbeat;
} MainActivity = {nullptr};

static struct {
    jclass self;
    jmethodID forName;
    jmethodID decode;
} Charset = {nullptr};

static struct {
    jclass self;
    jmethodID toString;
} CharBuffer = {nullptr};

static struct {
    jmethodID setLaunchWindowingMode;
} ActivityOptions = {nullptr};

// Bundles the native state belonging to the current LorieView instance so it can be released
// as a unit when that instance is torn down, instead of leaking as scattered process globals.
struct LorieViewResources {
    Renderer renderer;
    JNIEnv* env = nullptr;    // GUI-thread JNIEnv. Must be used only in GUI thread.
    jobject thiz = nullptr;   // global ref to the owning LorieView
    jobject activity = nullptr; // global ref to the owning MainActivity
    volatile int connFd = -1;
    bool destroyed = true;
    xw11_surface_generation surfaceGeneration{};

    LorieViewResources(JNIEnv* callerEnv, jobject view);
    ~LorieViewResources();
    void connect(jint fd);
    int xcallback(int fd, int events);
};

static jclass FindClassOrDie(JNIEnv *env, const char* name) {
    jclass clazz = env->FindClass(name);
    if (!clazz) {
        char buffer[1024] = {0};
        sprintf(buffer, "class %s not found", name);
        log(ERROR, "%s", buffer);
        env->FatalError(buffer);
        return nullptr;
    }

    return (jclass) env->NewGlobalRef(clazz);
}

static jmethodID FindMethodOrDie(JNIEnv *env, jclass clazz, const char* name, const char* signature, jboolean isStatic) {
    jmethodID method = isStatic ? env->GetStaticMethodID(clazz, name, signature) : env->GetMethodID(clazz, name, signature);
    if (!method) {
        char buffer[1024] = {0};
        sprintf(buffer, "method %s %s not found", name, signature);
        log(ERROR, "%s", buffer);
        env->FatalError(buffer);
        return nullptr;
    }

    return method;
}

// @CriticalNative: no implicit JNIEnv*/jclass, unlike the @FastNative entries below.
static jboolean requestConnection(__unused jlong ptr) {
#define check(cond, fmt, ...) if ((cond)) do { __android_log_print(ANDROID_LOG_ERROR, "requestConnection", fmt, ## __VA_ARGS__); goto end; } while (0)
    bool sent = JNI_FALSE;
    // We do not want to block GUI thread for a long time so we will set timeout to 20 msec.
    struct sockaddr_in server = { .sin_family = AF_INET, .sin_port = htons(PORT) };
    server.sin_addr.s_addr = inet_addr("127.0.0.1");
    int so_error, sock = socket(AF_INET, SOCK_STREAM, 0);
    check(sock < 0, "Could not create socket: %s", strerror(errno));
    check(fcntl(sock, F_SETFL, O_NONBLOCK) < 0, "failed to set socket non-block: %s", strerror(errno));
    int r;
    r = connect(sock, (struct sockaddr *)&server, sizeof(server));
    check(r < 0 && errno != EINPROGRESS, "failed to connect socket: %s", strerror(errno));
    if (r < 0 && errno == EINPROGRESS) {
        // Connection is in progress; use poll to wait for it
        struct pollfd pfd = { .fd = sock, .events = POLLOUT };
        r = poll(&pfd, 1, 20);  // timeout set to 50ms
        if (!r) goto end;
        // check(!r, "Connection timed out after 20ms."); // We do not want to flood logcat with this message
        check(r < 0, "poll failed: %s", strerror(errno));
        socklen_t len = sizeof(so_error);
        check(getsockopt(sock, SOL_SOCKET, SO_ERROR, &so_error, &len) < 0, "getsockopt failed: %s", strerror(errno));
        if (so_error == ECONNREFUSED) goto end; // Regular situation which happens often if server is not started. No need to spam logcat with this.
        check(so_error != 0, "Connection failed: %s", strerror(so_error));

        check(write(sock, MAGIC, sizeof(MAGIC)) < 0, "failed to send message: %s", strerror(errno));
        sent = JNI_TRUE;
        goto end;
    }

    check(1, "something went wrong: %s, %s", strerror(errno), strerror(r));

    end: if (sock >= 0) close(sock);
    return sent;
#undef errorReturn
}

static jlong nativeInit(JNIEnv *env, jobject thiz) {
    if (!Charset.self) {
        // Init clipboard-related JNI stuff
        Charset.self = FindClassOrDie(env, "java/nio/charset/Charset");
        Charset.forName = FindMethodOrDie(env, Charset.self, "forName", "(Ljava/lang/String;)Ljava/nio/charset/Charset;", JNI_TRUE);
        Charset.decode = FindMethodOrDie(env, Charset.self, "decode", "(Ljava/nio/ByteBuffer;)Ljava/nio/CharBuffer;", JNI_FALSE);

        CharBuffer.self = FindClassOrDie(env,  "java/nio/CharBuffer");
        CharBuffer.toString = FindMethodOrDie(env, CharBuffer.self, "toString", "()Ljava/lang/String;", JNI_FALSE);

        MainActivity.self = FindClassOrDie(env,  "com/termux/x11/MainActivity");
        MainActivity.clientConnectedStateChanged = FindMethodOrDie(env, MainActivity.self, "clientConnectedStateChanged", "()V", JNI_FALSE);
        MainActivity.lastServerHeartbeat = env->GetFieldID(MainActivity.self, "lastServerHeartbeat", "J");
        MainActivity.resetIme = FindMethodOrDie(env, env->GetObjectClass(thiz), "resetIme", "()V", JNI_FALSE);
    }

    return (jlong) (intptr_t) new (malloc(sizeof(LorieViewResources))) LorieViewResources(env, thiz);
}

LorieViewResources::LorieViewResources(JNIEnv *callerEnv, jobject view) {
    JavaVM* vm;
    destroyed = false;
    xw11_surface_generation_init(&surfaceGeneration);
    renderer.init(callerEnv, view);
    renderer.connFdPtr = &connFd; // lets the renderer thread wake up a GPU copy waiter

    callerEnv->GetJavaVM(&vm);
    vm->AttachCurrentThread(&env, nullptr);
    thiz = env->NewGlobalRef(view);

    jfieldID activityField = env->GetFieldID(env->GetObjectClass(view), "activity", "Lcom/termux/x11/MainActivity;");
    jobject a = env->GetObjectField(view, activityField);
    if (a)
        activity = env->NewGlobalRef(a);

    connect(-1);
}

LorieViewResources::~LorieViewResources() {
    destroyed = true;

    if (connFd != -1) {
        ALooper_removeFd(ALooper_forThread(), connFd);
        close(connFd);
        connFd = -1;
    }

    renderer.destroy();

    if (thiz) {
        env->DeleteGlobalRef(thiz);
        thiz = nullptr;
    }

    if (activity) {
        env->DeleteGlobalRef(activity);
        activity = nullptr;
    }
}

int LorieViewResources::xcallback(int fd, int events) {
    if (events & (ALOOPER_EVENT_ERROR | ALOOPER_EVENT_HANGUP)) {
        ALooper_removeFd(ALooper_forThread(), fd);
        close(connFd);
        connFd = -1;
        renderer.setSharedState(nullptr);
        renderer.removeAllBuffers();
        log(DEBUG, "disconnected");
        if (activity)
            env->CallVoidMethod(activity, MainActivity.clientConnectedStateChanged);
        return 1;
    }

    if (connFd != -1) {
        lorieEvent e = {0};

        again:
        if (read(connFd, &e, sizeof(e)) == sizeof(e)) {
            switch(e.type) {
                case EVENT_CLIPBOARD_SEND: {
                    if (!e.clipboardSend.count)
                        break;
                    size_t capacity;
                    if (__builtin_add_overflow((size_t) e.clipboardSend.count, (size_t) 1, &capacity))
                        return xcallback(fd, ALOOPER_EVENT_HANGUP);
                    char *clipboard = (char*) calloc(capacity, 1);
                    if (!clipboard) {
                        log(ERROR, "Could not allocate clipboard buffer");
                        return xcallback(fd, ALOOPER_EVENT_HANGUP);
                    }
                    read(connFd, clipboard, e.clipboardSend.count);
                    clipboard[e.clipboardSend.count] = 0;
                    log(DEBUG, "Clipboard content (%zu symbols) is %s", strlen(clipboard), clipboard);
                    jmethodID id = env->GetMethodID(env->GetObjectClass(thiz), "setClipboardText","(Ljava/lang/String;)V");
                    jobject bb = env->NewDirectByteBuffer(clipboard, strlen(clipboard));
                    jobject charset = env->CallStaticObjectMethod(Charset.self, Charset.forName, env->NewStringUTF("UTF-8"));
                    jobject cb = env->CallObjectMethod(charset, Charset.decode, bb);
                    env->DeleteLocalRef(bb);

                    auto str = (jstring) env->CallObjectMethod(cb, CharBuffer.toString);
                    env->CallVoidMethod(thiz, id, str);
                    free(clipboard);
                    break;
                }
                case EVENT_CLIPBOARD_REQUEST: {
                    env->CallVoidMethod(thiz, env->GetMethodID(env->GetObjectClass(thiz), "requestClipboard", "()V"));
                    break;
                }
                case EVENT_SHARED_SERVER_STATE: {
                    struct lorie_shared_server_state* state = nullptr;
                    int stateFd = ancil_recv_fd(connFd);

                    if (stateFd < 0)
                        break;

                    state = (struct lorie_shared_server_state*) mmap(nullptr, sizeof(*state), PROT_READ|PROT_WRITE, MAP_SHARED, stateFd, 0);
                    if (!state || state == MAP_FAILED) {
                        log(ERROR, "Failed to map server state: %s", strerror(errno));
                        state = nullptr;
                    }

                    renderer.setSharedState(state);

                    close(stateFd); // Closing file descriptor does not unmmap shared memory fragment.
                    break;
                }
                case EVENT_ADD_BUFFER: {
                    static LorieBuffer* buffer = nullptr;
                    const LorieBuffer_Desc* desc;
                    LorieBuffer_recvHandleFromUnixSocket(connFd, &buffer);
                    desc = LorieBuffer_description(buffer);
                    log(INFO, "Received shared buffer width %d stride %d height %d format %d type %d id %llu", desc->width, desc->stride, desc->height, desc->format, desc->type, desc->id);
                    renderer.addBuffer(buffer);
                    break;
                }
                case EVENT_REMOVE_BUFFER: {
                    renderer.removeBuffer(e.removeBuffer.id);
                    break;
                }
                case EVENT_WINDOW_FOCUS_CHANGED: {
                    env->CallVoidMethod(thiz, MainActivity.resetIme);
                    break;
                }
                case EVENT_HEARTBEAT: {
                    if (activity)
                        env->SetLongField(activity, MainActivity.lastServerHeartbeat, nowMs());
                    break;
                }
                case EVENT_SYNC_REPLY: {
                    jmethodID id = env->GetMethodID(env->GetObjectClass(thiz), "onSyncReply", "(I)V");
                    env->CallVoidMethod(thiz, id, (jint) e.sync.serial);
                    break;
                }
            }
        }

        int n;
        if (ioctl(connFd, FIONREAD, &n) >= 0 && n > sizeof(e))
            goto again;
    }

    return 1;
}

void LorieViewResources::connect(jint fd) {
    if (connFd != -1) {
        ALooper_removeFd(ALooper_forThread(), connFd);
        close(connFd);
        renderer.setSharedState(nullptr);
        renderer.removeAllBuffers();
        log(DEBUG, "disconnected");
    }

    if ((connFd = fd) != -1) {
        ALooper_addFd(ALooper_forThread(), fd, 0, ALOOPER_EVENT_INPUT | ALOOPER_EVENT_ERROR | ALOOPER_EVENT_HANGUP,
                      +[](int fd, int events, void* data) -> int { return ((LorieViewResources*) data)->xcallback(fd, events); }, this);

        // Give the X server our renderer wakeup cond var fd, resent on every reconnect.
        lorieEvent e = { .type = EVENT_RENDERER_WAKEUP_COND };
        write(connFd, &e, sizeof(e));
        ancil_send_fd(connFd, renderer.getWakeupCondFd());

        log(DEBUG, "XCB connection is successfull");
    }
}

static void startLogcat(JNIEnv *env, __unused jclass clazz, __unused jlong ptr, jint fd) {
    log(DEBUG, "Starting logcat with output to given fd");
    lorieDebugEnabled = true;

    switch(fork()) {
        case -1:
            log(ERROR, "fork: %s", strerror(errno));
            return;
        case 0:
            dup2(fd, 1);
            dup2(fd, 2);
            prctl(PR_SET_PDEATHSIG, SIGTERM);
            // Capture the complete device log stream for diagnosis. A --pid filter here
            // hides logs from the X server, Weston/XWayland helpers, SurfaceFlinger,
            // and Android framework processes that are needed to diagnose connection/rendering.
            execl("/system/bin/logcat", "logcat", NULL);
            log(ERROR, "exec logcat: %s", strerror(errno));
            env->FatalError("Exiting");
    }
}

static void sendTextEvent(JNIEnv *env, __unused jobject thiz, jlong ptr, jbyteArray text) {
    lastInputTimestampMs = nowMs();
    auto* r = (LorieViewResources*) ptr;
    if (r && r->connFd != -1 && text) {
        jsize length = env->GetArrayLength(text);
        jbyte *str = env->GetByteArrayElements(text, nullptr);
        char *p = (char*) str;
        mbstate_t mbstate = { 0 };
        if (!length)
            return;

        log(DEBUG, "Parsing text: %.*s", length, str);

        while (*p) {
            wchar_t wc;
            size_t len = mbrtowc(&wc, p, MB_CUR_MAX, &mbstate);

            if (len == (size_t)-1 || len == (size_t)-2) {
                log(ERROR, "Invalid UTF-8 sequence encountered");
                break;
            }

            if (len == 0)
                break;

            if (wc == L'\n') {
                sendEvent(r, .key = { .t = EVENT_KEY, .key = KEY_ENTER + 8, .state = 1 });
                sendEvent(r, .key = { .t = EVENT_KEY, .key = KEY_ENTER + 8, .state = 0 });
            } else if (wc != L'\r') {
                log(DEBUG, "Sending unicode event: %lc (U+%X)", wc, wc);
                sendEvent(r, .unicode = { .t = EVENT_UNICODE, .code = (uint32_t) wc });
            }
            p += len;
            if (p - (char*) str >= length)
                break;
            usleep(2500);
        }

        env->ReleaseByteArrayElements(text, str, JNI_ABORT);
    }
}

extern char* __progname;

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, __unused void *reserved) {
    JNIEnv* env;

    if (!strcmp(__progname, "com.termux.x11")) {
        // Redirects stderr to logcat.
        pthread_create([]{ static pthread_t t; return &t; }(), nullptr, +[](__unused void* cookie) -> void* {
            FILE *fp;
            int p[2];
            size_t len;
            char *line = nullptr;
            pipe(p);

            fp = fdopen(p[0], "r");

            dup2(p[1], 2);
            dup2(p[1], 1);
            while ((getline(&line, &len, fp)) != -1) {
                log(DEBUG, "%s%s", line, (line[len - 1] == '\n') ? "" : "\n");
            }

            return nullptr;
        }, nullptr);
    }

    static JNINativeMethod methods[] = {
            {"nativeInit", "()J", (void *)&nativeInit},
            {"nativeDestroy", "(J)V", (void *) +[](__unused JNIEnv *env, __unused jobject thiz, jlong ptr) {
                auto* r = (LorieViewResources*) ptr;
                if (!r) return;
                r->~LorieViewResources();
                free(r);
            }},
            {"surfaceCreated", "(J)V", (void *) +[](__unused JNIEnv *env, __unused jobject thiz, jlong ptr) {
                auto* r = (LorieViewResources*) ptr;
                if (!r || r->destroyed) return;
                uint64_t generation = 0;
                xw11_surface_result result =
                        xw11_surface_generation_created(&r->surfaceGeneration, &generation);
                if (result != XW11_SURFACE_OK) {
                    log(ERROR, "Surface generation create failed: %d", (int) result);
                    return;
                }
                log(DEBUG, "Android Surface generation %llu created",
                    (unsigned long long) generation);
            }},
            {"surfaceChanged", "(JLandroid/view/Surface;)V", (void *) +[](JNIEnv *env, __unused jobject thiz, jlong ptr, jobject sfc) {
                auto* r = (LorieViewResources*) ptr;
                if (!r || r->destroyed) return;
                if (sfc) {
                    xw11_surface_result result = xw11_surface_generation_accepts(
                            &r->surfaceGeneration, r->surfaceGeneration.generation);
                    if (result != XW11_SURFACE_OK) {
                        log(ERROR, "Rejecting Surface update for inactive generation: %d",
                            (int) result);
                        return;
                    }
                } else if (r->surfaceGeneration.active) {
                    xw11_surface_generation_destroyed(
                            &r->surfaceGeneration, r->surfaceGeneration.generation);
                }
                r->renderer.setWindow(env, sfc);
            }},
            {"setViewport", "(JIIIIIII)V", (void *) +[](__unused JNIEnv *env, __unused jobject thiz, jlong ptr, jint x, jint y, jint w, jint h, jint ew, jint eh, jint hidden) {
                auto* r = (LorieViewResources*) ptr;
                if (!r || r->destroyed) return;
                r->renderer.setViewport(x, y, w, h, ew, eh, hidden);
            }},
            {"setRendererZoom", "(JI)V", (void *) +[](__unused JNIEnv *env, __unused jobject thiz, jlong ptr, jint percent) {
                auto* r = (LorieViewResources*) ptr;
                if (!r || r->destroyed) return;
                r->renderer.setZoom(percent);
            }},
            {"setZoomAnchor", "(JFFFF)V", (void *) +[](__unused JNIEnv *env, __unused jobject thiz, jlong ptr, jfloat sourceX, jfloat sourceY, jfloat fracX, jfloat fracY) {
                auto* r = (LorieViewResources*) ptr;
                if (!r || r->destroyed) return;
                r->renderer.setZoomAnchor(sourceX, sourceY, fracX, fracY);
            }},
            {"clearZoomAnchor", "(J)V", (void *) +[](__unused JNIEnv *env, __unused jobject thiz, jlong ptr) {
                auto* r = (LorieViewResources*) ptr;
                if (!r || r->destroyed) return;
                r->renderer.clearZoomAnchor();
            }},
            {"setFollowCursorPan", "(JZ)V", (void *) +[](__unused JNIEnv *env, __unused jobject thiz, jlong ptr, jboolean enabled) {
                auto* r = (LorieViewResources*) ptr;
                if (!r || r->destroyed) return;
                r->renderer.setFollowCursorPan(enabled);
            }},
            {"getCursorPosition", "(J)J", (void *) +[](__unused JNIEnv *env, __unused jobject thiz, jlong ptr) -> jlong {
                auto* r = (LorieViewResources*) ptr;
                if (!r || r->destroyed || !r->renderer.state) return 0;
                return ((jlong) r->renderer.state->cursor.x << 32) | (jlong) r->renderer.state->cursor.y;
            }},
            {"sendSync", "(JI)V", (void *) +[](__unused JNIEnv *env, __unused jobject thiz, jlong ptr, jint serial) {
                auto* r = (LorieViewResources*) ptr;
                sendEvent(r, .sync = { .t = EVENT_SYNC, .serial = (uint32_t) serial });
            }},
            {"setFiltering", "(JI)V", (void *) +[](__unused JNIEnv* env, __unused jobject self, jlong ptr, jint filtering) {
                auto* r = (LorieViewResources*) ptr;
                if (!r || r->destroyed) return;
                r->renderer.setFiltering(filtering);
            }},
            {"connect", "(JI)V", (void *) +[](__unused JNIEnv* env, __unused jclass clazz, jlong ptr, jint fd) {
                auto* r = (LorieViewResources*) ptr;
                if (!r) return;
                r->connect(fd);
            }},
            // @CriticalNative: no implicit JNIEnv*/jclass, unlike the @FastNative entries below.
            {"connected", "(J)Z", (void *) +[](jlong ptr) -> jboolean {
                auto* r = (LorieViewResources*) ptr;
                return r && r->connFd != -1;
            }},
            {"startLogcat", "(JI)V", (void *)&startLogcat},
            {"setClipboardSyncEnabled", "(JZZ)V", (void *) +[](__unused JNIEnv* env, __unused jobject cls, jlong ptr, jboolean enable, __unused jboolean ignored) {
                auto* r = (LorieViewResources*) ptr;
                sendEvent(r, .clipboardEnable = { .t = EVENT_CLIPBOARD_ENABLE, .enable = enable });
            }},
            {"sendClipboardAnnounce", "(J)V", (void *) +[](__unused JNIEnv *env, __unused jobject thiz, jlong ptr) {
                auto* r = (LorieViewResources*) ptr;
                sendEvent(r, .type = EVENT_CLIPBOARD_ANNOUNCE);
            }},
            {"sendClipboardEvent", "(J[B)V", (void *) +[](JNIEnv *env, __unused jobject thiz, jlong ptr, jbyteArray text) {
                auto* r = (LorieViewResources*) ptr;
                if (r && r->connFd != -1 && text) {
                    jsize length = env->GetArrayLength(text);
                    jbyte* str = env->GetByteArrayElements(text, nullptr);
                    sendEvent(r, .clipboardSend = { .t = EVENT_CLIPBOARD_SEND, .count = (uint32_t) length });
                    write(r->connFd, str, length);
                    env->ReleaseByteArrayElements(text, str, JNI_ABORT);
                }
            }},
            {"sendWindowChange", "(JIIILjava/lang/String;)V", (void *) +[](__unused JNIEnv* env, __unused jobject cls, jlong ptr, jint width, jint height, jint framerate, jstring jname) {
                auto* r = (LorieViewResources*) ptr;
                if (r && r->connFd != -1) {
                    const char *name = (!jname || width <= 0 || height <= 0) ? nullptr : env->GetStringUTFChars(jname, JNI_FALSE);
                    sendEvent(r, .screenSize = { .t = EVENT_SCREEN_SIZE, .width = (uint16_t) width, .height = (uint16_t) height, .framerate = (uint16_t) framerate, .name_size = (name ? strlen(name) : 0) });
                    if (name) {
                        write(r->connFd, name, strlen(name));
                        env->ReleaseStringUTFChars(jname, name);
                    }
                }
            }},
            {"sendMouseEvent", "(JFFIZZ)V", (void *) +[](JNIEnv* env, __unused jobject cls, jlong ptr, jfloat x, jfloat y, jint which_button, jboolean button_down, jboolean relative) {
                lastInputTimestampMs = nowMs();
                auto* r = (LorieViewResources*) ptr;
                if (r && r->connFd != -1) {
                    if (which_button > 0)
                        env->CallVoidMethod(r->thiz, MainActivity.resetIme);
                    sendEvent(r, .mouse = { .t = EVENT_MOUSE, .x = x, .y = y, .detail = (uint8_t) which_button, .down = button_down, .relative = relative });
                }
            }},
            {"sendTouchEvent", "(JIIII)V", (void *) +[](__unused JNIEnv* env, __unused jobject cls, jlong ptr, jint action, jint id, jint x, jint y) {
                lastInputTimestampMs = nowMs();
                auto* r = (LorieViewResources*) ptr;
                if (action != -1)
                    sendEvent(r, .touch = { .t = EVENT_TOUCH, .type = (uint16_t) action, .id = (uint16_t) id, .x = (uint16_t) x, .y = (uint16_t) y });
            }},
            {"sendStylusEvent", "(JFFIIIIIZZ)V", (void *) +[](JNIEnv *env, __unused jobject thiz, jlong ptr, jfloat x, jfloat y, jint pressure, jint tilt_x, jint tilt_y, jint orientation, jint buttons, jboolean eraser, jboolean mouse) {
                lastInputTimestampMs = nowMs();
                auto* r = (LorieViewResources*) ptr;
                if (r && r->connFd != -1) {
                    env->CallVoidMethod(r->thiz, MainActivity.resetIme);
                    sendEvent(r, .stylus = { .t = EVENT_STYLUS, .x = x, .y = y, .pressure = (uint16_t) pressure, .tilt_x = (int8_t) tilt_x, .tilt_y = (int8_t) tilt_y, .orientation = (int16_t) orientation, .buttons = (uint8_t) buttons, .eraser = eraser, .mouse = mouse });
                }
            }},
            {"requestStylusEnabled", "(JZ)V", (void *) +[](__unused JNIEnv *env, __unused jobject thiz, jlong ptr, jboolean enabled) {
                auto* r = (LorieViewResources*) ptr;
                sendEvent(r, .stylusEnable = { .t = EVENT_STYLUS_ENABLE, .enable = enabled });
            }},
            {"sendLockKeysState", "(JI)V", (void *) +[](__unused JNIEnv *env, __unused jobject thiz, jlong ptr, jint state) {
                auto* r = (LorieViewResources*) ptr;
                sendEvent(r, .lockKeysState = { .t = EVENT_LOCK_KEYS_STATE, .state = (uint8_t) state });
            }},
            {"sendKeyEvent", "(JIIZ)Z", (void *) +[](__unused JNIEnv* env, __unused jobject cls, jlong ptr, jint scan_code, jint key_code, jboolean key_down) -> jboolean {
                lastInputTimestampMs = nowMs();
                auto* r = (LorieViewResources*) ptr;
                if (r && r->connFd != -1) {
                    int code = scan_code ?: (key_code >= 0 && key_code < (jint) (sizeof(android_to_linux_keycode) / sizeof(*android_to_linux_keycode)) ? android_to_linux_keycode[key_code] : 0);
                    sendEvent(r, .key = { .t = EVENT_KEY, .key = (uint16_t) (code + 8), .state = key_down });
                }
                return true;
            }},
            {"sendTextEvent", "(J[B)V", (void *)&sendTextEvent},
            {"requestConnection", "(J)Z", (void *)&requestConnection},
            {"getLastInputTimestamp", "()J", (void *) +[](__unused JNIEnv* env, __unused jclass clazz) -> jlong {
                return (jlong) lastInputTimestampMs;
            }},
            {"markUserActivity", "()V", (void *) +[](__unused JNIEnv* env, __unused jclass clazz) {
                lastInputTimestampMs = nowMs();
            }},
    };
    vm->AttachCurrentThread(&env, nullptr);
    jclass cls = env->FindClass("com/termux/x11/LorieView");
    env->RegisterNatives(cls, methods, sizeof(methods)/sizeof(methods[0]));

    pthread_t freeformLookupThread;
    pthread_create(&freeformLookupThread, nullptr, +[](void* arg) -> void* {
        auto* vm = (JavaVM*) arg;
        JNIEnv* threadEnv;
        vm->AttachCurrentThread(&threadEnv, nullptr);
        jclass activityOptionsClass = threadEnv->FindClass("android/app/ActivityOptions");
        jmethodID method = activityOptionsClass ? threadEnv->GetMethodID(activityOptionsClass, "setLaunchWindowingMode", "(I)V") : nullptr;
        threadEnv->ExceptionClear();
        vm->DetachCurrentThread();
        return (void*) method;
    }, vm);
    void* freeformMethod;
    pthread_join(freeformLookupThread, &freeformMethod);
    ActivityOptions.setLaunchWindowingMode = (jmethodID) freeformMethod;
    if (!ActivityOptions.setLaunchWindowingMode)
        log(ERROR, "ActivityOptions.setLaunchWindowingMode(int) not found");

    static JNINativeMethod xw11SurfaceMethods[] = {
            {"nativeAttachSurface", "(Landroid/view/Surface;J)I", (void *) +[](JNIEnv *env, __unused jclass clazz, jobject surface, jlong generation) -> jint {
                if (!surface || generation <= 0)
                    return (jint) XW11_PRESENT_ERR_ARGUMENT;
                ANativeWindow *window = ANativeWindow_fromSurface(env, surface);
                if (!window) {
                    if (env->ExceptionCheck())
                        env->ExceptionClear();
                    return (jint) XW11_PRESENT_ERR_ARGUMENT;
                }
                xw11_present_result result = xw11_presenter_set_window(
                        window, (uint64_t) generation);
                ANativeWindow_release(window);
                return (jint) result;
            }},
            {"nativePresentRgbaFrame","(Ljava/nio/ByteBuffer;IIIIIJ)I", (void *) +[](JNIEnv *env, __unused jclass clazz, jobject pixels, jint offset, jint length, jint width, jint height, jint strideBytes, jlong generation) -> jint {
                if (!pixels || offset < 0 || length < 0 || width <= 0 || height <= 0 ||
                    width > 8192 || height > 8192 || strideBytes <= 0 ||
                    strideBytes > 256 * 1024 * 1024 || generation <= 0)
                    return (jint) XW11_PRESENT_ERR_ARGUMENT;
                void *base = env->GetDirectBufferAddress(pixels);
                jlong capacity = env->GetDirectBufferCapacity(pixels);
                if (!base || capacity < 0 || (jlong) offset > capacity ||
                    (jlong) length > capacity - offset)
                    return (jint) XW11_PRESENT_ERR_ARGUMENT;
                const size_t requiredStride = (size_t) width * 4u;
                if ((size_t) strideBytes < requiredStride ||
                    (size_t) (height - 1) > (SIZE_MAX - requiredStride) / (size_t) strideBytes)
                    return (jint) XW11_PRESENT_ERR_STRIDE;
                // Only row bytes are read from the final row; trailing padding is optional.
                const size_t requiredBytes =
                        (size_t) (height - 1) * (size_t) strideBytes + requiredStride;
                if ((size_t) length < requiredBytes)
                    return (jint) XW11_PRESENT_ERR_STRIDE;
                return (jint) xw11_presenter_present_rgba8888(
                        (uint64_t) generation, (uint32_t) width, (uint32_t) height,
                        static_cast<const uint8_t *>(base) + offset, (size_t) strideBytes);
            }},
            {"nativeDetachSurface", "(J)V", (void *) +[](__unused JNIEnv *env, __unused jclass clazz, jlong generation) {
                if (generation > 0)
                    xw11_presenter_clear_window((uint64_t) generation);
            }},
            {"nativeAttachOutputTransport", "(IJJ)I", (void *) +[](__unused JNIEnv *env, __unused jclass clazz, jint fd, jlong sessionId, jlong generation) -> jint {
                if (fd < 0 || sessionId <= 0 || (uint64_t) sessionId > UINT32_MAX || generation <= 0)
                    return (jint) XW11_OUTPUT_STREAM_ERR_ARGUMENT;
                int socketType = 0;
                socklen_t socketTypeLength = sizeof(socketType);
                if (getsockopt(fd, SOL_SOCKET, SO_TYPE, &socketType, &socketTypeLength) != 0) {
                    log(ERROR, "XW11 output attach rejected: getsockopt(SO_TYPE) fd=%d errno=%d (%s)",
                        fd, errno, strerror(errno));
                    return (jint) XW11_OUTPUT_STREAM_ERR_ARGUMENT;
                }
                if (socketType != SOCK_STREAM) {
                    log(ERROR, "XW11 output attach rejected: fd=%d socket type=%d, expected SOCK_STREAM",
                        fd, socketType);
                    return (jint) XW11_OUTPUT_STREAM_ERR_ARGUMENT;
                }
                struct sockaddr_storage peer = {};
                socklen_t peerLength = sizeof(peer);
                if (getpeername(fd, reinterpret_cast<struct sockaddr *>(&peer), &peerLength) != 0) {
                    log(ERROR, "XW11 output attach rejected: getpeername fd=%d errno=%d (%s)",
                        fd, errno, strerror(errno));
                    return (jint) XW11_OUTPUT_STREAM_ERR_ARGUMENT;
                }
                /*
                 * The output server uses an authenticated abstract AF_UNIX socket.
                 * Older prototypes used IPv4 loopback; accept that only for
                 * compatibility, but do not reject the current local IPC transport.
                 * Session ID + 256-bit token are validated by Xw11OutputSocketServer
                 * before this duplicated descriptor is handed to native code.
                 */
                if (peer.ss_family == AF_INET) {
                    if (reinterpret_cast<struct sockaddr_in *>(&peer)->sin_addr.s_addr != htonl(INADDR_LOOPBACK)) {
                        log(ERROR, "XW11 output attach rejected: IPv4 peer is not loopback");
                        return (jint) XW11_OUTPUT_STREAM_ERR_ARGUMENT;
                    }
                } else if (peer.ss_family != AF_UNIX) {
                    log(ERROR, "XW11 output attach rejected: unsupported peer family=%d",
                        (int)peer.ss_family);
                    return (jint) XW11_OUTPUT_STREAM_ERR_ARGUMENT;
                }
                int ownedFd = dup(fd);
                if (ownedFd < 0) return (jint) XW11_OUTPUT_STREAM_ERR_TRANSPORT;
                pthread_mutex_lock(&xw11OutputMutex);
                if (xw11OutputThreadCreated || xw11OutputRunning) {
                    pthread_mutex_unlock(&xw11OutputMutex);
                    close(ownedFd);
                    return (jint) XW11_OUTPUT_STREAM_ERR_SESSION;
                }
                xw11OutputFd = ownedFd;
                xw11OutputSession = (uint32_t)sessionId;
                xw11OutputGeneration = (uint64_t)generation;
                xw11OutputRunning = true;
                const int created = pthread_create(&xw11OutputThread, nullptr, xw11OutputWorker, nullptr);
                if (created != 0) {
                    xw11OutputRunning = false;
                    xw11OutputFd = -1;
                    pthread_mutex_unlock(&xw11OutputMutex);
                    close(ownedFd);
                    return (jint) XW11_OUTPUT_STREAM_ERR_TRANSPORT;
                }
                xw11OutputThreadCreated = true;
                pthread_mutex_unlock(&xw11OutputMutex);
                return 0;
            }},
            {"nativeDetachOutputTransport", "(J)V", (void *) +[](__unused JNIEnv *env, __unused jclass clazz, jlong generation) {
                pthread_mutex_lock(&xw11OutputMutex);
                if (!xw11OutputThreadCreated || generation <= 0 ||
                    xw11OutputGeneration != (uint64_t)generation) {
                    pthread_mutex_unlock(&xw11OutputMutex);
                    return;
                }
                const pthread_t thread = xw11OutputThread;
                const int fd = xw11OutputFd;
                // Keep the slot reserved until join completes. Otherwise a concurrent
                // attach could overwrite the globals while the previous worker is exiting.
                xw11OutputRunning = false;
                pthread_mutex_unlock(&xw11OutputMutex);
                if (fd >= 0) shutdown(fd, SHUT_RDWR);
                pthread_join(thread, nullptr);
                if (fd >= 0) close(fd);
                pthread_mutex_lock(&xw11OutputMutex);
                if (xw11OutputThreadCreated && xw11OutputThread == thread &&
                    xw11OutputGeneration == (uint64_t)generation) {
                    xw11OutputThreadCreated = false;
                    xw11OutputFd = -1;
                    xw11OutputGeneration = 0;
                    xw11OutputSession = 0;
                }
                pthread_mutex_unlock(&xw11OutputMutex);
            }},
            {"nativeAttachInputTransport", "(IJJ)I", (void *) +[](JNIEnv *env, __unused jclass clazz, jint fd, jlong sessionId, jlong generation) -> jint {
                if (fd < 0 || sessionId <= 0 || (uint64_t) sessionId > UINT32_MAX || generation <= 0)
                    return (jint) XW11_INPUT_DISPATCHER_ERR_ARGUMENT;

                // ParcelFileDescriptor can wrap a regular file or pipe. This
                // protocol requires a connected Unix stream socket.
                int socketType = 0;
                socklen_t socketTypeLength = sizeof(socketType);
                if (getsockopt(fd, SOL_SOCKET, SO_TYPE, &socketType, &socketTypeLength) != 0 ||
                    socketType != SOCK_STREAM)
                    return (jint) XW11_INPUT_DISPATCHER_ERR_ARGUMENT;
                struct sockaddr_storage peer = {};
                socklen_t peerLength = sizeof(peer);
                if (getpeername(fd, reinterpret_cast<struct sockaddr *>(&peer), &peerLength) != 0 ||
                    peer.ss_family != AF_UNIX)
                    return (jint) XW11_INPUT_DISPATCHER_ERR_ARGUMENT;

                {
                    Xw11InputLock lock;
                    if ((uint64_t) generation < xw11LastInputGeneration)
                        return (jint) XW11_INPUT_DISPATCHER_ERR_STATE;
                }

                int ownedFd = dup(fd);
                if (ownedFd < 0) return (jint) XW11_INPUT_DISPATCHER_ERR_TRANSPORT;
                xw11_input_dispatcher *next = xw11_input_dispatcher_create(ownedFd, (uint32_t) sessionId);
                if (!next) {
                    close(ownedFd);
                    return (jint) XW11_INPUT_DISPATCHER_ERR_MEMORY;
                }
                xw11_input_dispatcher_result started = xw11_input_dispatcher_start(next);
                if (started != XW11_INPUT_DISPATCHER_OK) {
                    xw11_input_dispatcher_destroy(next);
                    return (jint) started;
                }

                xw11_input_dispatcher *previous = nullptr;
                {
                    Xw11InputLock lock;
                    // Recheck after allocation: another attach may have won the race.
                    if ((uint64_t) generation < xw11LastInputGeneration) {
                        xw11_input_dispatcher_destroy(next);
                        return (jint) XW11_INPUT_DISPATCHER_ERR_STATE;
                    }
                    previous = xw11InputDispatcher;
                    xw11InputDispatcher = next;
                    xw11InputGeneration = (uint64_t) generation;
                    xw11LastInputGeneration = (uint64_t) generation;
                }
                if (previous) xw11_input_dispatcher_destroy(previous);
                return 0;
            }},
            {"nativeSubmitInputEvent", "(JJIIIIIII)Z", (void *) +[](__unused JNIEnv *env, __unused jclass clazz, jlong generation, jlong timeNs, jint type, jint action, jint code, jint x, jint y, jint modifiers, jint deviceId) -> jboolean {
                if (generation <= 0 || timeNs < 0) return JNI_FALSE;
                xw11_input_event event = {};
                event.surface_generation = (uint64_t) generation;
                event.monotonic_time_ns = (uint64_t) timeNs;
                event.type = (uint16_t) type;
                event.action = (uint16_t) action;
                event.code = (uint32_t) code;
                event.x = (int32_t) x;
                event.y = (int32_t) y;
                event.modifiers = (uint32_t) modifiers;
                event.device_id = (uint32_t) deviceId;
                Xw11InputLock lock;
                if (!xw11InputDispatcher || xw11InputGeneration != (uint64_t) generation)
                    return JNI_FALSE;
                return xw11_input_dispatcher_submit(xw11InputDispatcher, &event) == XW11_INPUT_DISPATCHER_OK
                        ? JNI_TRUE : JNI_FALSE;
            }},
            {"nativeDetachInputTransport", "(J)V", (void *) +[](__unused JNIEnv *env, __unused jclass clazz, jlong generation) {
                if (generation <= 0) return;
                Xw11InputLock lock;
                if (xw11InputDispatcher && xw11InputGeneration == (uint64_t) generation) {
                    xw11_input_dispatcher_destroy(xw11InputDispatcher);
                    xw11InputDispatcher = nullptr;
                    xw11InputGeneration = 0;
                }
            }},
    };
    // Keep this bridge optional so a stale APK cannot prevent legacy :0 startup.
    // Register methods individually: RegisterNatives(array) is all-or-nothing, so
    // one stale/mismatched declaration otherwise disables every output/input method.
    // ExceptionDescribe identifies the exact Java method/signature that failed.
    jclass xw11SurfaceClass = env->FindClass("com/termux/x11/Xw11NativeSurfaceView");
    if (xw11SurfaceClass) {
        unsigned int registered = 0;
        unsigned int failed = 0;
        for (const JNINativeMethod &method : xw11SurfaceMethods) {
            if (env->RegisterNatives(xw11SurfaceClass, const_cast<JNINativeMethod *>(&method), 1) == JNI_OK) {
                ++registered;
                continue;
            }
            ++failed;
            log(ERROR, "XW11 JNI registration failed for %s %s",
                method.name, method.signature);
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
            }
        }
        if (failed == 0) {
            log(INFO, "XW11 JNI registration result: %u registered, %u failed",
                registered, failed);
        } else {
            log(ERROR, "XW11 JNI registration result: %u registered, %u failed",
                registered, failed);
        }
        env->DeleteLocalRef(xw11SurfaceClass);
    } else {
        log(WARN, "Optional XW11 Surface bridge class not found; continuing with legacy Lorie");
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
    }

    static JNINativeMethod mainActivityMethods[] = {
            {"nativeSetLaunchWindowingModeFreeform", "(Ljava/lang/Object;)V", (void *) +[](JNIEnv *env, __unused jclass clazz, jobject options) {
                if (ActivityOptions.setLaunchWindowingMode)
                    env->CallVoidMethod(options, ActivityOptions.setLaunchWindowingMode, 5); // WINDOWING_MODE_FREEFORM
            }},
    };
    env->RegisterNatives(env->FindClass("com/termux/x11/MainActivity"), mainActivityMethods, sizeof(mainActivityMethods)/sizeof(mainActivityMethods[0]));

    registerCmdEntryPointNatives(env);

    return JNI_VERSION_1_6;
}
