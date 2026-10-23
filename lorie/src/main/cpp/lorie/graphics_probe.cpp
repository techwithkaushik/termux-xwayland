#include <jni.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>

#include <stdio.h>
#include <string.h>

namespace {

bool has_extension(const char *extensions, const char *wanted) {
    if (extensions == nullptr || wanted == nullptr || *wanted == '\0')
        return false;
    const size_t length = strlen(wanted);
    const char *cursor = extensions;
    while ((cursor = strstr(cursor, wanted)) != nullptr) {
        const bool left = cursor == extensions || cursor[-1] == ' ';
        const char right_char = cursor[length];
        const bool right = right_char == '\0' || right_char == ' ';
        if (left && right)
            return true;
        cursor += length;
    }
    return false;
}

void append_line(char *out, size_t capacity, const char *key,
                 const char *value) {
    const size_t used = strlen(out);
    if (used >= capacity)
        return;
    snprintf(out + used, capacity - used, "%s: %s\n", key,
             value != nullptr ? value : "(unavailable)");
}

void append_capability(char *out, size_t capacity, const char *name,
                       bool supported) {
    const size_t used = strlen(out);
    if (used >= capacity)
        return;
    snprintf(out + used, capacity - used, "%s: %s\n", name,
             supported ? "yes" : "no");
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_termux_x11_NativeGraphicsProbe_probeEglCapabilities(JNIEnv *env,
                                                              jclass) {
    char report[4096] = {};
    append_line(report, sizeof(report), "probe", "Termux-XW11 app-process EGL pbuffer");
    append_line(report, sizeof(report), "warning",
                "Capability hints only; not a Weston buffer import test");

    EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (display == EGL_NO_DISPLAY) {
        char error[32];
        snprintf(error, sizeof(error), "0x%04x", eglGetError());
        append_line(report, sizeof(report), "eglGetDisplay", "failed");
        append_line(report, sizeof(report), "eglError", error);
        return env->NewStringUTF(report);
    }

    EGLint major = 0;
    EGLint minor = 0;
    if (eglInitialize(display, &major, &minor) != EGL_TRUE) {
        char error[32];
        snprintf(error, sizeof(error), "0x%04x", eglGetError());
        append_line(report, sizeof(report), "eglInitialize", "failed");
        append_line(report, sizeof(report), "eglError", error);
        return env->NewStringUTF(report);
    }

    char version[64];
    snprintf(version, sizeof(version), "%d.%d", major, minor);
    append_line(report, sizeof(report), "EGL version", version);

    const char *egl_extensions = eglQueryString(display, EGL_EXTENSIONS);
    append_line(report, sizeof(report), "EGL vendor", eglQueryString(display, EGL_VENDOR));
    append_line(report, sizeof(report), "EGL extensions", egl_extensions);
    append_capability(report, sizeof(report), "EGL_ANDROID_image_native_buffer",
                      has_extension(egl_extensions, "EGL_ANDROID_image_native_buffer"));
    append_capability(report, sizeof(report), "EGL_ANDROID_native_fence_sync",
                      has_extension(egl_extensions, "EGL_ANDROID_native_fence_sync"));
    append_capability(report, sizeof(report), "EGL_EXT_image_dma_buf_import",
                      has_extension(egl_extensions, "EGL_EXT_image_dma_buf_import"));
    append_capability(report, sizeof(report), "EGL_EXT_image_dma_buf_import_modifiers",
                      has_extension(egl_extensions, "EGL_EXT_image_dma_buf_import_modifiers"));

    const EGLint config_attributes[] = {
        EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
        EGL_NONE
    };
    EGLConfig config = nullptr;
    EGLint config_count = 0;
    if (eglChooseConfig(display, config_attributes, &config, 1, &config_count) != EGL_TRUE ||
        config_count < 1) {
        append_line(report, sizeof(report), "EGL pbuffer config", "unavailable");
        eglTerminate(display);
        return env->NewStringUTF(report);
    }

    const EGLint pbuffer_attributes[] = {
        EGL_WIDTH, 16, EGL_HEIGHT, 16, EGL_NONE
    };
    EGLSurface surface = eglCreatePbufferSurface(display, config, pbuffer_attributes);
    if (surface == EGL_NO_SURFACE) {
        char error[32];
        snprintf(error, sizeof(error), "0x%04x", eglGetError());
        append_line(report, sizeof(report), "EGL pbuffer", "creation failed");
        append_line(report, sizeof(report), "eglError", error);
        eglTerminate(display);
        return env->NewStringUTF(report);
    }

    const EGLint context_attributes[] = {
        EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE
    };
    EGLContext context = eglCreateContext(display, config, EGL_NO_CONTEXT,
                                          context_attributes);
    if (context == EGL_NO_CONTEXT) {
        char error[32];
        snprintf(error, sizeof(error), "0x%04x", eglGetError());
        append_line(report, sizeof(report), "EGL ES2 context", "creation failed");
        append_line(report, sizeof(report), "eglError", error);
        eglDestroySurface(display, surface);
        eglTerminate(display);
        return env->NewStringUTF(report);
    }

    if (eglMakeCurrent(display, surface, surface, context) != EGL_TRUE) {
        char error[32];
        snprintf(error, sizeof(error), "0x%04x", eglGetError());
        append_line(report, sizeof(report), "eglMakeCurrent", "failed");
        append_line(report, sizeof(report), "eglError", error);
    } else {
        append_line(report, sizeof(report), "GL vendor",
                    reinterpret_cast<const char *>(glGetString(GL_VENDOR)));
        append_line(report, sizeof(report), "GL renderer",
                    reinterpret_cast<const char *>(glGetString(GL_RENDERER)));
        append_line(report, sizeof(report), "GL version",
                    reinterpret_cast<const char *>(glGetString(GL_VERSION)));
        const char *gl_extensions =
            reinterpret_cast<const char *>(glGetString(GL_EXTENSIONS));
        append_capability(report, sizeof(report), "GL_EXT_memory_object_fd",
                          has_extension(gl_extensions, "GL_EXT_memory_object_fd"));
    }

    eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    eglDestroyContext(display, context);
    eglDestroySurface(display, surface);
    eglTerminate(display);
    return env->NewStringUTF(report);
}
