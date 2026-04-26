#include <jni.h>
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "mira/pty.h"
#include "pty/pty_trace.h"

JNIEXPORT jlong JNICALL Java_com_vwww_mira_MiraPtyProcess_nativeOpen(JNIEnv *env, jobject thiz, jstring shell_path, jstring cwd, jobjectArray args, jobjectArray env_vars, jint rows, jint columns, jint cell_width, jint cell_height);
JNIEXPORT void JNICALL Java_com_vwww_mira_MiraPtyProcess_nativeResize(JNIEnv *env, jobject thiz, jlong handle, jint columns, jint rows, jint cell_width, jint cell_height);
JNIEXPORT void JNICALL Java_com_vwww_mira_MiraPtyProcess_nativeSetUtf8Mode(JNIEnv *env, jobject thiz, jlong handle);
JNIEXPORT jint JNICALL Java_com_vwww_mira_MiraPtyProcess_nativeRead(JNIEnv *env, jobject thiz, jlong handle, jbyteArray buffer, jint length);
JNIEXPORT void JNICALL Java_com_vwww_mira_MiraPtyProcess_nativeWrite(JNIEnv *env, jobject thiz, jlong handle, jbyteArray data, jint length);
JNIEXPORT jint JNICALL Java_com_vwww_mira_MiraPtyProcess_nativeWaitFor(JNIEnv *env, jobject thiz, jlong handle);
JNIEXPORT jint JNICALL Java_com_vwww_mira_MiraPtyProcess_nativePid(JNIEnv *env, jobject thiz, jlong handle);
JNIEXPORT void JNICALL Java_com_vwww_mira_MiraPtyProcess_nativeKill(JNIEnv *env, jobject thiz, jlong handle, jint signal_number);
JNIEXPORT void JNICALL Java_com_vwww_mira_MiraPtyProcess_nativeClose(JNIEnv *env, jobject thiz, jlong handle);

static JNINativeMethod g_mira_pty_methods[] = {
    { "nativeOpen", "(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;IIII)J", (void *) Java_com_vwww_mira_MiraPtyProcess_nativeOpen },
    { "nativeRead", "(J[BI)I", (void *) Java_com_vwww_mira_MiraPtyProcess_nativeRead },
    { "nativeWrite", "(J[BI)V", (void *) Java_com_vwww_mira_MiraPtyProcess_nativeWrite },
    { "nativeResize", "(JIIII)V", (void *) Java_com_vwww_mira_MiraPtyProcess_nativeResize },
    { "nativeSetUtf8Mode", "(J)V", (void *) Java_com_vwww_mira_MiraPtyProcess_nativeSetUtf8Mode },
    { "nativeWaitFor", "(J)I", (void *) Java_com_vwww_mira_MiraPtyProcess_nativeWaitFor },
    { "nativePid", "(J)I", (void *) Java_com_vwww_mira_MiraPtyProcess_nativePid },
    { "nativeKill", "(JI)V", (void *) Java_com_vwww_mira_MiraPtyProcess_nativeKill },
    { "nativeClose", "(J)V", (void *) Java_com_vwww_mira_MiraPtyProcess_nativeClose },
};

static void mira_jni_throw_runtime_exception(JNIEnv *env, const char *message) {
    jclass exception_class = (*env)->FindClass(env, "java/lang/RuntimeException");
    if (exception_class != NULL) {
        (*env)->ThrowNew(env, exception_class, message == NULL ? "native PTY error" : message);
    }
}

static void mira_jni_throw_io_exception(JNIEnv *env, const char *message) {
    jclass exception_class = (*env)->FindClass(env, "java/io/IOException");
    if (exception_class != NULL) {
        (*env)->ThrowNew(env, exception_class, message == NULL ? "native PTY I/O error" : message);
    }
}

static void mira_jni_throw_runtime_errno(JNIEnv *env, const char *operation, int saved_errno) {
    char message[512];
    snprintf(message,
             sizeof(message),
             "%s: errno=%d (%s)",
             operation == NULL ? "native PTY error" : operation,
             saved_errno,
             strerror(saved_errno));
    mira_jni_throw_runtime_exception(env, message);
}

static void mira_jni_throw_io_errno(JNIEnv *env, const char *operation, int saved_errno) {
    char message[512];
    snprintf(message,
             sizeof(message),
             "%s: errno=%d (%s)",
             operation == NULL ? "native PTY I/O error" : operation,
             saved_errno,
             strerror(saved_errno));
    mira_jni_throw_io_exception(env, message);
}

static char **mira_jni_copy_string_array(JNIEnv *env, jobjectArray array) {
    if (array == NULL) {
        return NULL;
    }

    jsize length = (*env)->GetArrayLength(env, array);
    char **result = (char **) calloc((size_t) length + 1U, sizeof(char *));
    if (result == NULL) {
        return NULL;
    }

    for (jsize i = 0; i < length; ++i) {
        jstring item = (jstring) (*env)->GetObjectArrayElement(env, array, i);
        if (item == NULL) {
            for (jsize j = 0; j < i; ++j) {
                free(result[j]);
            }
            free(result);
            return NULL;
        }
        const char *utf8 = (*env)->GetStringUTFChars(env, item, NULL);
        if (utf8 == NULL) {
            for (jsize j = 0; j < i; ++j) {
                free(result[j]);
            }
            free(result);
            return NULL;
        }
        result[i] = strdup(utf8);
        (*env)->ReleaseStringUTFChars(env, item, utf8);
        (*env)->DeleteLocalRef(env, item);
        if (result[i] == NULL) {
            for (jsize j = 0; j <= i; ++j) {
                free(result[j]);
            }
            free(result);
            return NULL;
        }
    }

    return result;
}

static void mira_jni_free_string_array(char **array) {
    if (array == NULL) {
        return;
    }
    for (char **cursor = array; *cursor != NULL; ++cursor) {
        free(*cursor);
    }
    free(array);
}

static char *mira_jni_copy_string(JNIEnv *env, jstring value) {
    if (value == NULL) {
        return NULL;
    }
    const char *utf8 = (*env)->GetStringUTFChars(env, value, NULL);
    if (utf8 == NULL) {
        return NULL;
    }
    char *copy = strdup(utf8);
    (*env)->ReleaseStringUTFChars(env, value, utf8);
    return copy;
}

static mira_pty_process_t *mira_jni_handle_to_pty(jlong handle) {
    return (mira_pty_process_t *) (uintptr_t) handle;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) reserved;

    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK || env == NULL) {
        return JNI_ERR;
    }

    jclass klass = (*env)->FindClass(env, "com/vwww/mira/MiraPtyProcess");
    if (klass == NULL) {
        return JNI_ERR;
    }

    if ((*env)->RegisterNatives(
            env,
            klass,
            g_mira_pty_methods,
            (jint) (sizeof(g_mira_pty_methods) / sizeof(g_mira_pty_methods[0]))
        ) != JNI_OK) {
        (*env)->DeleteLocalRef(env, klass);
        return JNI_ERR;
    }

    (*env)->DeleteLocalRef(env, klass);
    return JNI_VERSION_1_6;
}

JNIEXPORT jlong JNICALL Java_com_vwww_mira_MiraPtyProcess_nativeOpen(JNIEnv *env,
                                                                           jobject thiz,
                                                                           jstring shell_path,
                                                                           jstring cwd,
                                                                           jobjectArray args,
                                                                           jobjectArray env_vars,
                                                                           jint rows,
                                                                           jint columns,
                                                                           jint cell_width,
                                                                           jint cell_height) {
    (void) thiz;
    MIRA_PTY_LOGI("jni nativeOpen enter rows=%d cols=%d cell=%dx%d", (int) rows, (int) columns, (int) cell_width, (int) cell_height);

    char *shell_path_copy = mira_jni_copy_string(env, shell_path);
    char *cwd_copy = mira_jni_copy_string(env, cwd);
    char **args_copy = mira_jni_copy_string_array(env, args);
    char **env_copy = mira_jni_copy_string_array(env, env_vars);

    if (shell_path_copy == NULL ||
        (cwd != NULL && cwd_copy == NULL) ||
        (args != NULL && args_copy == NULL) ||
        (env_vars != NULL && env_copy == NULL)) {
        mira_jni_free_string_array(args_copy);
        mira_jni_free_string_array(env_copy);
        free(shell_path_copy);
        free(cwd_copy);
        MIRA_PTY_LOGE("jni nativeOpen alloc failed");
        mira_jni_throw_runtime_exception(env, "Failed to allocate PTY arguments");
        return 0;
    }
    MIRA_PTY_LOGI("jni nativeOpen args ready shell=%s cwd=%s argv0=%s", shell_path_copy, cwd_copy == NULL ? "(null)" : cwd_copy, (args_copy != NULL && args_copy[0] != NULL) ? args_copy[0] : "(null)");

    mira_pty_process_t *pty = mira_pty_open(shell_path_copy,
                                            cwd_copy,
                                            args_copy,
                                            env_copy,
                                            (int) rows,
                                            (int) columns,
                                            (int) cell_width,
                                            (int) cell_height);
    int saved_errno = errno;

    mira_jni_free_string_array(args_copy);
    mira_jni_free_string_array(env_copy);
    free(shell_path_copy);
    free(cwd_copy);

    if (pty == NULL) {
        MIRA_PTY_PERROR("jni mira_pty_open");
        mira_jni_throw_runtime_errno(env, "Failed to create PTY subprocess", saved_errno);
        return 0;
    }

    MIRA_PTY_LOGI("jni nativeOpen ok handle=%p", (void *) pty);
    return (jlong) (uintptr_t) pty;
}

JNIEXPORT void JNICALL Java_com_vwww_mira_MiraPtyProcess_nativeResize(JNIEnv *env,
                                                                            jobject thiz,
                                                                            jlong handle,
                                                                            jint columns,
                                                                            jint rows,
                                                                            jint cell_width,
                                                                            jint cell_height) {
    (void) thiz;

    mira_pty_process_t *pty = mira_jni_handle_to_pty(handle);
    if (pty == NULL) {
        return;
    }
    if (mira_pty_resize(pty, (int) columns, (int) rows, (int) cell_width, (int) cell_height) != 0) {
        mira_jni_throw_runtime_errno(env, "PTY resize failed", errno);
    }
}

JNIEXPORT void JNICALL Java_com_vwww_mira_MiraPtyProcess_nativeSetUtf8Mode(JNIEnv *env,
                                                                                  jobject thiz,
                                                                                  jlong handle) {
    (void) thiz;

    mira_pty_process_t *pty = mira_jni_handle_to_pty(handle);
    if (pty == NULL) {
        return;
    }
    if (mira_pty_set_utf8_mode(pty) != 0) {
        mira_jni_throw_runtime_errno(env, "PTY UTF-8 mode update failed", errno);
    }
}

JNIEXPORT jint JNICALL Java_com_vwww_mira_MiraPtyProcess_nativeRead(JNIEnv *env,
                                                                          jobject thiz,
                                                                          jlong handle,
                                                                          jbyteArray buffer,
                                                                          jint length) {
    (void) thiz;

    mira_pty_process_t *pty = mira_jni_handle_to_pty(handle);
    if (pty == NULL || buffer == NULL || length < 0) {
        mira_jni_throw_io_exception(env, "Invalid PTY read arguments");
        return -1;
    }

    jsize buffer_length = (*env)->GetArrayLength(env, buffer);
    if (length > buffer_length) {
        length = buffer_length;
    }
    if (length == 0) {
        return 0;
    }

    jbyte *bytes = (*env)->GetByteArrayElements(env, buffer, NULL);
    if (bytes == NULL) {
        mira_jni_throw_io_exception(env, "Unable to access read buffer");
        return -1;
    }

    ssize_t result = mira_pty_read(pty, bytes, (size_t) length);
    int saved_errno = errno;
    (*env)->ReleaseByteArrayElements(env, buffer, bytes, result < 0 ? JNI_ABORT : 0);
    if (result < 0) {
        mira_jni_throw_io_errno(env, "PTY read failed", saved_errno);
        return -1;
    }
    if (result == 0) {
        return -1;
    }
    return (jint) result;
}

JNIEXPORT void JNICALL Java_com_vwww_mira_MiraPtyProcess_nativeWrite(JNIEnv *env,
                                                                           jobject thiz,
                                                                           jlong handle,
                                                                           jbyteArray data,
                                                                           jint length) {
    (void) thiz;

    mira_pty_process_t *pty = mira_jni_handle_to_pty(handle);
    if (pty == NULL || data == NULL || length < 0) {
        mira_jni_throw_io_exception(env, "Invalid PTY write arguments");
        return;
    }

    jsize data_length = (*env)->GetArrayLength(env, data);
    if (length > data_length) {
        length = data_length;
    }
    if (length == 0) {
        return;
    }

    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    if (bytes == NULL) {
        mira_jni_throw_io_exception(env, "Unable to access write buffer");
        return;
    }

    ssize_t result = mira_pty_write(pty, bytes, (size_t) length);
    int saved_errno = errno;
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
    if (result < 0) {
        mira_jni_throw_io_errno(env, "PTY write failed", saved_errno);
    } else if (result != (ssize_t) length) {
        mira_jni_throw_io_exception(env, "PTY write failed: short write");
    }
}

JNIEXPORT jint JNICALL Java_com_vwww_mira_MiraPtyProcess_nativeWaitFor(JNIEnv *env,
                                                                              jobject thiz,
                                                                             jlong handle) {
    (void) env;
    (void) thiz;

    mira_pty_process_t *pty = mira_jni_handle_to_pty(handle);
    if (pty == NULL) {
        return -EINVAL;
    }
    return (jint) mira_pty_wait_for(pty);
}

JNIEXPORT jint JNICALL Java_com_vwww_mira_MiraPtyProcess_nativePid(JNIEnv *env,
                                                                          jobject thiz,
                                                                         jlong handle) {
    (void) env;
    (void) thiz;

    mira_pty_process_t *pty = mira_jni_handle_to_pty(handle);
    if (pty == NULL) {
        return -1;
    }
    return (jint) mira_pty_pid(pty);
}

JNIEXPORT void JNICALL Java_com_vwww_mira_MiraPtyProcess_nativeKill(JNIEnv *env,
                                                                           jobject thiz,
                                                                           jlong handle,
                                                                           jint signal_number) {
    (void) env;
    (void) thiz;

    mira_pty_process_t *pty = mira_jni_handle_to_pty(handle);
    if (pty == NULL) {
        return;
    }
    (void) mira_pty_kill(pty, (int) signal_number);
}

JNIEXPORT void JNICALL Java_com_vwww_mira_MiraPtyProcess_nativeClose(JNIEnv *env,
                                                                           jobject thiz,
                                                                            jlong handle) {
    (void) env;
    (void) thiz;

    mira_pty_process_t *pty = mira_jni_handle_to_pty(handle);
    if (pty == NULL) {
        return;
    }
    (void) mira_pty_close(pty);
}
