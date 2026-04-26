#pragma once

#include <errno.h>
#include <string.h>
#include <unistd.h>

#if defined(__ANDROID__)
#include <android/log.h>
#define MIRA_PTY_TRACE_TAG "MiraPtyNative"
#define MIRA_PTY_LOGI(fmt, ...) __android_log_print(ANDROID_LOG_INFO, MIRA_PTY_TRACE_TAG, "%s:%d pid=%d " fmt, __func__, __LINE__, getpid(), ##__VA_ARGS__)
#define MIRA_PTY_LOGW(fmt, ...) __android_log_print(ANDROID_LOG_WARN, MIRA_PTY_TRACE_TAG, "%s:%d pid=%d " fmt, __func__, __LINE__, getpid(), ##__VA_ARGS__)
#define MIRA_PTY_LOGE(fmt, ...) __android_log_print(ANDROID_LOG_ERROR, MIRA_PTY_TRACE_TAG, "%s:%d pid=%d " fmt, __func__, __LINE__, getpid(), ##__VA_ARGS__)
#else
#include <stdio.h>
#define MIRA_PTY_LOGI(fmt, ...) fprintf(stderr, "[MiraPtyNative][I] %s:%d pid=%d " fmt "\n", __func__, __LINE__, getpid(), ##__VA_ARGS__)
#define MIRA_PTY_LOGW(fmt, ...) fprintf(stderr, "[MiraPtyNative][W] %s:%d pid=%d " fmt "\n", __func__, __LINE__, getpid(), ##__VA_ARGS__)
#define MIRA_PTY_LOGE(fmt, ...) fprintf(stderr, "[MiraPtyNative][E] %s:%d pid=%d " fmt "\n", __func__, __LINE__, getpid(), ##__VA_ARGS__)
#endif

#define MIRA_PTY_PERROR(op) MIRA_PTY_LOGE("%s failed errno=%d (%s)", op, errno, strerror(errno))
