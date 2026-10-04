/*
 * JNI wrapper for libtcc — on-device C compilation for WireDroid.
 *
 * Exposes two JNI methods:
 *   - NativeCompiler.compileToSharedLib(source, outputPath, includePath)
 *   - NativeCompiler.isAvailable()
 */
#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include <android/log.h>

#include "libtcc.h"

#define TAG "NativeCompiler"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* ── Error collection ──────────────────────────────────────────── */

typedef struct {
    char *buf;
    int   len;
    int   cap;
} ErrorBuf;

static void error_callback(void *opaque, const char *msg)
{
    ErrorBuf *eb = (ErrorBuf *)opaque;
    int msglen = (int)strlen(msg);
    int needed = eb->len + msglen + 2; /* +1 for \n, +1 for \0 */
    if (needed > eb->cap) {
        int newcap = needed * 2;
        eb->buf = realloc(eb->buf, newcap);
        eb->cap = newcap;
    }
    memcpy(eb->buf + eb->len, msg, msglen);
    eb->len += msglen;
    eb->buf[eb->len++] = '\n';
    eb->buf[eb->len] = '\0';
}

/* ── compileToSharedLib ────────────────────────────────────────── */

JNIEXPORT jstring JNICALL
Java_com_ccs_wiredroid_NativeCompiler_compileToSharedLib(
        JNIEnv *env, jclass clazz,
        jstring jsource, jstring joutputPath, jstring jincludePath)
{
    const char *source      = (*env)->GetStringUTFChars(env, jsource, NULL);
    const char *outputPath  = (*env)->GetStringUTFChars(env, joutputPath, NULL);
    const char *includePath = jincludePath
                              ? (*env)->GetStringUTFChars(env, jincludePath, NULL)
                              : NULL;

    LOGD("compileToSharedLib: output=%s include=%s", outputPath,
         includePath ? includePath : "(none)");

    ErrorBuf eb = { NULL, 0, 0 };
    eb.buf = malloc(1024);
    eb.cap = 1024;
    eb.buf[0] = '\0';

    TCCState *s = tcc_new();
    if (!s) {
        (*env)->ReleaseStringUTFChars(env, jsource, source);
        (*env)->ReleaseStringUTFChars(env, joutputPath, outputPath);
        if (includePath) (*env)->ReleaseStringUTFChars(env, jincludePath, includePath);
        free(eb.buf);
        return (*env)->NewStringUTF(env, "Failed to create TCC compilation context");
    }

    tcc_set_error_func(s, &eb, error_callback);

    /* Enable position-independent code for shared libs and disable default CRT/libs search */
    /* Must be called BEFORE tcc_set_output_type so s->nostdlib is set and crtbegin_so.o is not added */
    tcc_set_options(s, "-nostdlib -fPIC");

    /* Output type: shared library (.so) */
    tcc_set_output_type(s, TCC_OUTPUT_DLL);

    /* Add user include path (contains jni.h and standard headers) */
    if (includePath && includePath[0]) {
        tcc_add_include_path(s, includePath);
    }

    /* Compile the source string */
    int ret = tcc_compile_string(s, source);
    if (ret < 0) {
        LOGE("Compilation failed: %s", eb.buf);
        jstring result = (*env)->NewStringUTF(env, eb.buf[0] ? eb.buf : "Compilation failed");
        tcc_delete(s);
        (*env)->ReleaseStringUTFChars(env, jsource, source);
        (*env)->ReleaseStringUTFChars(env, joutputPath, outputPath);
        if (includePath) (*env)->ReleaseStringUTFChars(env, jincludePath, includePath);
        free(eb.buf);
        return result;
    }

    /* Output the compiled .so file */
    ret = tcc_output_file(s, outputPath);
    if (ret < 0) {
        LOGE("Output failed: %s", eb.buf);
        jstring result = (*env)->NewStringUTF(env, eb.buf[0] ? eb.buf : "Failed to write output file");
        tcc_delete(s);
        (*env)->ReleaseStringUTFChars(env, jsource, source);
        (*env)->ReleaseStringUTFChars(env, joutputPath, outputPath);
        if (includePath) (*env)->ReleaseStringUTFChars(env, jincludePath, includePath);
        free(eb.buf);
        return result;
    }

    LOGD("Compilation successful: %s", outputPath);
    tcc_delete(s);

    (*env)->ReleaseStringUTFChars(env, jsource, source);
    (*env)->ReleaseStringUTFChars(env, joutputPath, outputPath);
    if (includePath) (*env)->ReleaseStringUTFChars(env, jincludePath, includePath);
    free(eb.buf);

    /* NULL = success */
    return NULL;
}

/* ── compileFile ───────────────────────────────────────────────── */

JNIEXPORT jstring JNICALL
Java_com_ccs_wiredroid_NativeCompiler_compileFile(
        JNIEnv *env, jclass clazz,
        jstring jsourcePath, jstring joutputPath, jstring jincludePath)
{
    const char *sourcePath  = (*env)->GetStringUTFChars(env, jsourcePath, NULL);
    const char *outputPath  = (*env)->GetStringUTFChars(env, joutputPath, NULL);
    const char *includePath = jincludePath
                              ? (*env)->GetStringUTFChars(env, jincludePath, NULL)
                              : NULL;

    ErrorBuf eb = { NULL, 0, 0 };
    eb.buf = malloc(1024);
    eb.cap = 1024;
    eb.buf[0] = '\0';

    TCCState *s = tcc_new();
    if (!s) {
        (*env)->ReleaseStringUTFChars(env, jsourcePath, sourcePath);
        (*env)->ReleaseStringUTFChars(env, joutputPath, outputPath);
        if (includePath) (*env)->ReleaseStringUTFChars(env, jincludePath, includePath);
        free(eb.buf);
        return (*env)->NewStringUTF(env, "Failed to create TCC compilation context");
    }

    tcc_set_error_func(s, &eb, error_callback);

    /* Must be called BEFORE tcc_set_output_type so s->nostdlib is set and crtbegin_so.o is not added */
    tcc_set_options(s, "-nostdlib -fPIC");

    tcc_set_output_type(s, TCC_OUTPUT_DLL);

    if (includePath && includePath[0]) {
        tcc_add_include_path(s, includePath);
    }

    int ret = tcc_add_file(s, sourcePath);
    if (ret < 0) {
        jstring result = (*env)->NewStringUTF(env, eb.buf[0] ? eb.buf : "Failed to compile file");
        tcc_delete(s);
        (*env)->ReleaseStringUTFChars(env, jsourcePath, sourcePath);
        (*env)->ReleaseStringUTFChars(env, joutputPath, outputPath);
        if (includePath) (*env)->ReleaseStringUTFChars(env, jincludePath, includePath);
        free(eb.buf);
        return result;
    }

    ret = tcc_output_file(s, outputPath);
    if (ret < 0) {
        jstring result = (*env)->NewStringUTF(env, eb.buf[0] ? eb.buf : "Failed to write output file");
        tcc_delete(s);
        (*env)->ReleaseStringUTFChars(env, jsourcePath, sourcePath);
        (*env)->ReleaseStringUTFChars(env, joutputPath, outputPath);
        if (includePath) (*env)->ReleaseStringUTFChars(env, jincludePath, includePath);
        free(eb.buf);
        return result;
    }

    tcc_delete(s);
    (*env)->ReleaseStringUTFChars(env, jsourcePath, sourcePath);
    (*env)->ReleaseStringUTFChars(env, joutputPath, outputPath);
    if (includePath) (*env)->ReleaseStringUTFChars(env, jincludePath, includePath);
    free(eb.buf);
    return NULL;
}

/* ── isAvailable ───────────────────────────────────────────────── */

JNIEXPORT jboolean JNICALL
Java_com_ccs_wiredroid_NativeCompiler_isAvailable(JNIEnv *env, jclass clazz)
{
    TCCState *s = tcc_new();
    if (!s) return JNI_FALSE;
    tcc_delete(s);
    return JNI_TRUE;
}
