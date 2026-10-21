#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <cstdint>
#include <vector>

#define LOG_TAG "TermuxXWaylandEGL"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {
struct Renderer {
    ANativeWindow* window = nullptr;
    EGLDisplay display = EGL_NO_DISPLAY;
    EGLContext context = EGL_NO_CONTEXT;
    EGLSurface surface = EGL_NO_SURFACE;
    GLuint program = 0;
    GLuint texture = 0;
    GLint position = -1;
    GLint texCoord = -1;
    GLint sampler = -1;
    int lastWidth = 0;
    int lastHeight = 0;
};

const char* kVertexShader =
    "attribute vec2 aPosition; attribute vec2 aTexCoord; varying vec2 vTexCoord;"
    "void main(){ gl_Position=vec4(aPosition,0.0,1.0); vTexCoord=aTexCoord; }";
const char* kFragmentShader =
    "precision mediump float; varying vec2 vTexCoord; uniform sampler2D uTexture;"
    "void main(){ gl_FragColor=texture2D(uTexture,vTexCoord); }";

GLuint compileShader(GLenum type, const char* source) {
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    GLint ok = GL_FALSE;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
    if (ok != GL_TRUE) {
        GLint length = 0;
        glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &length);
        std::vector<char> log(static_cast<size_t>(std::max(1, length)));
        glGetShaderInfoLog(shader, length, nullptr, log.data());
        LOGE("Shader compile failed: %s", log.data());
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

bool makeCurrent(Renderer* r) {
    if (!r || r->display == EGL_NO_DISPLAY || r->surface == EGL_NO_SURFACE ||
        r->context == EGL_NO_CONTEXT) {
        LOGE("makeCurrent rejected invalid EGL handles");
        return false;
    }
    if (eglMakeCurrent(r->display, r->surface, r->surface, r->context) != EGL_TRUE) {
        const EGLint error = eglGetError();
        LOGE("eglMakeCurrent failed: 0x%x (BAD_SURFACE=0x%x CONTEXT_LOST=0x%x)",
             error, EGL_BAD_SURFACE, EGL_CONTEXT_LOST);
        return false;
    }
    return true;
}

bool initializeGl(Renderer* r) {
    GLuint vs = compileShader(GL_VERTEX_SHADER, kVertexShader);
    GLuint fs = compileShader(GL_FRAGMENT_SHADER, kFragmentShader);
    if (!vs || !fs) {
        if (vs) glDeleteShader(vs);
        if (fs) glDeleteShader(fs);
        return false;
    }
    r->program = glCreateProgram();
    glAttachShader(r->program, vs);
    glAttachShader(r->program, fs);
    glBindAttribLocation(r->program, 0, "aPosition");
    glBindAttribLocation(r->program, 1, "aTexCoord");
    glLinkProgram(r->program);
    glDeleteShader(vs);
    glDeleteShader(fs);
    GLint linked = GL_FALSE;
    glGetProgramiv(r->program, GL_LINK_STATUS, &linked);
    if (linked != GL_TRUE) {
        LOGE("GL program link failed");
        return false;
    }
    r->position = glGetAttribLocation(r->program, "aPosition");
    r->texCoord = glGetAttribLocation(r->program, "aTexCoord");
    r->sampler = glGetUniformLocation(r->program, "uTexture");
    glGenTextures(1, &r->texture);
    glBindTexture(GL_TEXTURE_2D, r->texture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    return true;
}

void destroy(Renderer* r) {
    if (!r) return;
    if (r->display != EGL_NO_DISPLAY && r->context != EGL_NO_CONTEXT) {
        eglMakeCurrent(r->display, r->surface, r->surface, r->context);
        if (r->texture) glDeleteTextures(1, &r->texture);
        if (r->program) glDeleteProgram(r->program);
        eglMakeCurrent(r->display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    }
    if (r->display != EGL_NO_DISPLAY) {
        if (r->surface != EGL_NO_SURFACE) eglDestroySurface(r->display, r->surface);
        if (r->context != EGL_NO_CONTEXT) eglDestroyContext(r->display, r->context);
        eglTerminate(r->display);
    }
    if (r->window) ANativeWindow_release(r->window);
    delete r;
}
} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_org_techwithkaushik_termuxxwayland_NativeEglRenderer_nativeInitialize(
    JNIEnv* env, jobject /* thiz */, jobject javaSurface) {
    auto* r = new Renderer();
    r->window = ANativeWindow_fromSurface(env, javaSurface);
    if (!r->window) {
        LOGE("ANativeWindow_fromSurface failed");
        delete r;
        return 0;
    }

    r->display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (r->display == EGL_NO_DISPLAY || eglInitialize(r->display, nullptr, nullptr) != EGL_TRUE) {
        LOGE("eglInitialize failed: 0x%x", eglGetError());
        destroy(r);
        return 0;
    }

    const EGLint configAttrs[] = {
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT, EGL_SURFACE_TYPE, EGL_WINDOW_BIT, EGL_NONE
    };
    EGLConfig config = nullptr;
    EGLint count = 0;
    if (eglChooseConfig(r->display, configAttrs, &config, 1, &count) != EGL_TRUE || count < 1) {
        LOGE("No compatible EGL window config: 0x%x", eglGetError());
        destroy(r);
        return 0;
    }

    // Android SurfaceView buffers have a concrete native pixel format. An EGL
    // RGBA config can otherwise fail eglCreateWindowSurface with EGL_BAD_MATCH
    // when the ANativeWindow still advertises a different format.
    EGLint nativeVisualId = 0;
    if (eglGetConfigAttrib(r->display, config, EGL_NATIVE_VISUAL_ID, &nativeVisualId) != EGL_TRUE) {
        LOGE("eglGetConfigAttrib(EGL_NATIVE_VISUAL_ID) failed: 0x%x", eglGetError());
        destroy(r);
        return 0;
    }
    const int geometryResult = ANativeWindow_setBuffersGeometry(r->window, 0, 0, nativeVisualId);
    if (geometryResult != 0) {
        LOGE("ANativeWindow_setBuffersGeometry failed: %d nativeVisualId=%d", geometryResult, nativeVisualId);
        destroy(r);
        return 0;
    }
    LOGI("EGL native window format configured visual=%d", nativeVisualId);

    const EGLint contextAttrs[] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};
    r->context = eglCreateContext(r->display, config, EGL_NO_CONTEXT, contextAttrs);
    r->surface = eglCreateWindowSurface(r->display, config, r->window, nullptr);
    if (r->context == EGL_NO_CONTEXT || r->surface == EGL_NO_SURFACE || !makeCurrent(r)) {
        LOGE("EGL surface/context creation failed: 0x%x", eglGetError());
        destroy(r);
        return 0;
    }
    if (!initializeGl(r)) {
        destroy(r);
        return 0;
    }
    LOGI("Native EGL renderer initialized");
    return reinterpret_cast<jlong>(r);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_techwithkaushik_termuxxwayland_NativeEglRenderer_nativeRender(
    JNIEnv* env, jobject /* thiz */, jlong handle, jintArray javaPixels, jint width, jint height) {
    auto* r = reinterpret_cast<Renderer*>(handle);
    if (!r || !javaPixels || width <= 0 || height <= 0 ||
        static_cast<jlong>(width) * height > 16777216LL) {
        LOGE("nativeRender rejected invalid input handle=%p width=%d height=%d",
             static_cast<void*>(r), width, height);
        return JNI_FALSE;
    }
    if (!makeCurrent(r)) return JNI_FALSE;

    const jsize length = env->GetArrayLength(javaPixels);
    const jlong required = static_cast<jlong>(width) * height;
    if (length < required) return JNI_FALSE;

    std::vector<jint> argb(static_cast<size_t>(required));
    env->GetIntArrayRegion(javaPixels, 0, static_cast<jsize>(required), argb.data());
    if (env->ExceptionCheck()) return JNI_FALSE;
    std::vector<uint8_t> rgba(static_cast<size_t>(required) * 4);
    for (jlong i = 0; i < required; ++i) {
        const uint32_t p = static_cast<uint32_t>(argb[static_cast<size_t>(i)]);
        const size_t j = static_cast<size_t>(i) * 4;
        rgba[j] = static_cast<uint8_t>((p >> 16) & 0xff);
        rgba[j + 1] = static_cast<uint8_t>((p >> 8) & 0xff);
        rgba[j + 2] = static_cast<uint8_t>(p & 0xff);
        rgba[j + 3] = static_cast<uint8_t>((p >> 24) & 0xff);
    }

    EGLint surfaceWidth = 0, surfaceHeight = 0;
    eglQuerySurface(r->display, r->surface, EGL_WIDTH, &surfaceWidth);
    eglQuerySurface(r->display, r->surface, EGL_HEIGHT, &surfaceHeight);
    if (surfaceWidth <= 0 || surfaceHeight <= 0) {
        LOGE("eglQuerySurface returned invalid size %dx%d", surfaceWidth, surfaceHeight);
        return JNI_FALSE;
    }
    glViewport(0, 0, surfaceWidth, surfaceHeight);
    // Clear unused letterbox pixels to transparent, not opaque black.
    // The Java SurfaceView requests PixelFormat.TRANSLUCENT and EGL selects
    // an RGBA config, so the window compositor can reveal its parent behind
    // these pixels while the server-provided desktop pixels remain unchanged.
    glClearColor(0.f, 0.f, 0.f, 0.f);
    glClear(GL_COLOR_BUFFER_BIT);

    const float sourceAspect = static_cast<float>(width) / static_cast<float>(height);
    const float targetAspect = static_cast<float>(surfaceWidth) / static_cast<float>(surfaceHeight);
    float x = 1.f, y = 1.f;
    if (sourceAspect > targetAspect) y = targetAspect / sourceAspect;
    else x = sourceAspect / targetAspect;
    const GLfloat vertices[] = {
        -x, -y, 0.f, 1.f,  x, -y, 1.f, 1.f,
        -x,  y, 0.f, 0.f,  x,  y, 1.f, 0.f
    };

    glUseProgram(r->program);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, r->texture);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    if (r->lastWidth != width || r->lastHeight != height) {
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, rgba.data());
        r->lastWidth = width;
        r->lastHeight = height;
    } else {
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, rgba.data());
    }
    glUniform1i(r->sampler, 0);
    glEnableVertexAttribArray(0);
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(GLfloat), vertices);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(GLfloat), vertices + 2);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glDisableVertexAttribArray(0);
    glDisableVertexAttribArray(1);
    const GLenum glError = glGetError();
    if (glError != GL_NO_ERROR) {
        LOGE("OpenGL draw/upload failed: 0x%x frame=%dx%d surface=%dx%d",
             glError, width, height, surfaceWidth, surfaceHeight);
        return JNI_FALSE;
    }
    if (eglSwapBuffers(r->display, r->surface) != EGL_TRUE) {
        LOGE("eglSwapBuffers failed: 0x%x", eglGetError());
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_org_techwithkaushik_termuxxwayland_NativeEglRenderer_nativeRelease(
    JNIEnv* /* env */, jobject /* thiz */, jlong handle) {
    destroy(reinterpret_cast<Renderer*>(handle));
}
