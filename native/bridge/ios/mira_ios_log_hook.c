#include "fishhook.h"
#include "mira_pty_ios_shim.h"

#include <stdarg.h>
#include <stdio.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>
#include <sys/uio.h>
#include <unistd.h>

static ssize_t (*mira_original_write)(int fd, const void *buf, size_t nbyte) = NULL;
static ssize_t (*mira_original_write_nocancel)(int fd, const void *buf, size_t nbyte) = NULL;
static ssize_t (*mira_original_writev)(int fd, const struct iovec *iov, int iovcnt) = NULL;
static ssize_t (*mira_original_writev_nocancel)(int fd, const struct iovec *iov, int iovcnt) = NULL;
static int (*mira_original_vfprintf)(FILE *stream, const char *format, va_list args) = NULL;
static int mira_write_hook_installed = 0;
static __thread int mira_inside_write_hook = 0;
static int mira_log_capture_fd = -1;

void mira_ios_log_hook_set_capture_fd(int fd) {
    mira_log_capture_fd = fd;
}

static void mira_raw_log_write(const void *buf, size_t nbyte) {
    if (mira_log_capture_fd < 0 || buf == NULL || nbyte == 0) {
        return;
    }

    if (mira_original_write != NULL) {
        (void) mira_original_write(mira_log_capture_fd, buf, nbyte);
    } else {
        (void) write(mira_log_capture_fd, buf, nbyte);
    }
}

static void mira_capture_standard_fd(int fd, const void *buf, size_t nbyte) {
    if ((fd != STDOUT_FILENO && fd != STDERR_FILENO) || buf == NULL || nbyte == 0 || mira_inside_write_hook || mira_log_capture_fd < 0) {
        return;
    }

    mira_inside_write_hook = 1;
    size_t captured = nbyte > 4096U ? 4096U : nbyte;
    const char *prefix = fd == STDERR_FILENO ? "stderr: " : "stdout: ";
    mira_raw_log_write(prefix, strlen(prefix));
    mira_raw_log_write(buf, captured);
    const char *bytes = (const char *) buf;
    if (captured == 0 || bytes[captured - 1] != '\n') {
        mira_raw_log_write("\n", 1);
    }
    mira_inside_write_hook = 0;
}

static void mira_capture_standard_iov(int fd, const struct iovec *iov, int iovcnt, size_t written) {
    if ((fd != STDOUT_FILENO && fd != STDERR_FILENO) || iov == NULL || iovcnt <= 0 || written == 0 || mira_inside_write_hook) {
        return;
    }
    char buffer[4096];
    size_t remaining = written > sizeof(buffer) ? sizeof(buffer) : written;
    size_t offset = 0;
    for (int i = 0; i < iovcnt && remaining > 0; ++i) {
        if (iov[i].iov_base == NULL || iov[i].iov_len == 0) {
            continue;
        }
        size_t chunk = iov[i].iov_len > remaining ? remaining : iov[i].iov_len;
        memcpy(buffer + offset, iov[i].iov_base, chunk);
        offset += chunk;
        remaining -= chunk;
    }
    mira_capture_standard_fd(fd, buffer, offset);
}

static void mira_capture_standard_stream(FILE *stream, const char *text) {
    if (stream == NULL || text == NULL || mira_inside_write_hook) {
        return;
    }
    int fd = fileno(stream);
    mira_capture_standard_fd(fd, text, strlen(text));
}

static void mira_capture_formatted_stream(FILE *stream, const char *format, va_list args) {
    if (stream == NULL || format == NULL || mira_inside_write_hook) {
        return;
    }
    char buffer[4096];
    va_list capture_args;
    va_copy(capture_args, args);
    int count = vsnprintf(buffer, sizeof(buffer), format, capture_args);
    va_end(capture_args);
    if (count <= 0) {
        return;
    }
    buffer[sizeof(buffer) - 1] = '\0';
    mira_capture_standard_stream(stream, buffer);
}

static ssize_t mira_hooked_write(int fd, const void *buf, size_t nbyte) {
    ssize_t result;
    if (mira_original_write == NULL) {
        return -1;
    }

    result = mira_original_write(fd, buf, nbyte);
    if (mira_inside_write_hook) {
        return result;
    }

    if ((fd == STDOUT_FILENO || fd == STDERR_FILENO) && buf != NULL && result > 0) {
        mira_capture_standard_fd(fd, buf, (size_t) result);
    }

    return result;
}

static ssize_t mira_hooked_write_nocancel(int fd, const void *buf, size_t nbyte) {
    ssize_t result;
    if (mira_original_write_nocancel == NULL) {
        return mira_original_write != NULL ? mira_original_write(fd, buf, nbyte) : -1;
    }
    result = mira_original_write_nocancel(fd, buf, nbyte);
    if (result > 0) {
        mira_capture_standard_fd(fd, buf, (size_t) result);
    }
    return result;
}

static ssize_t mira_hooked_writev(int fd, const struct iovec *iov, int iovcnt) {
    ssize_t result;
    if (mira_original_writev == NULL) {
        return -1;
    }
    result = mira_original_writev(fd, iov, iovcnt);
    if (result > 0) {
        mira_capture_standard_iov(fd, iov, iovcnt, (size_t) result);
    }
    return result;
}

static ssize_t mira_hooked_writev_nocancel(int fd, const struct iovec *iov, int iovcnt) {
    ssize_t result;
    if (mira_original_writev_nocancel == NULL) {
        return mira_original_writev != NULL ? mira_original_writev(fd, iov, iovcnt) : -1;
    }
    result = mira_original_writev_nocancel(fd, iov, iovcnt);
    if (result > 0) {
        mira_capture_standard_iov(fd, iov, iovcnt, (size_t) result);
    }
    return result;
}

static int mira_hooked_vfprintf(FILE *stream, const char *format, va_list args) {
    if (mira_original_vfprintf == NULL) {
        return -1;
    }
    va_list original_args;
    va_copy(original_args, args);
    mira_inside_write_hook = 1;
    int result = mira_original_vfprintf(stream, format, original_args);
    mira_inside_write_hook = 0;
    va_end(original_args);

    mira_capture_formatted_stream(stream, format, args);
    return result;
}

static int mira_hooked_fprintf(FILE *stream, const char *format, ...) {
    va_list args;
    va_start(args, format);
    int result = mira_hooked_vfprintf(stream, format, args);
    va_end(args);
    return result;
}

static int mira_hooked_printf(const char *format, ...) {
    va_list args;
    va_start(args, format);
    int result = mira_hooked_vfprintf(stdout, format, args);
    va_end(args);
    return result;
}

void mira_ios_install_log_hooks(void) {
    if (mira_write_hook_installed) {
        return;
    }
    mira_write_hook_installed = 1;
    struct rebinding bindings[] = {
        {"write", mira_hooked_write, (void **) &mira_original_write},
        {"__write_nocancel", mira_hooked_write_nocancel, (void **) &mira_original_write_nocancel},
        {"writev", mira_hooked_writev, (void **) &mira_original_writev},
        {"__writev_nocancel", mira_hooked_writev_nocancel, (void **) &mira_original_writev_nocancel},
        {"vfprintf", mira_hooked_vfprintf, (void **) &mira_original_vfprintf},
        {"fprintf", mira_hooked_fprintf, NULL},
        {"printf", mira_hooked_printf, NULL},
    };
    rebind_symbols(bindings, sizeof(bindings) / sizeof(bindings[0]));
}

void mira_ios_emit_log_smoke_test(void) {
    const char *direct = "mira-ios-log-smoke native-write stdout\n";
    (void) write(STDOUT_FILENO, direct, strlen(direct));

    printf("mira-ios-log-smoke native-printf stdout\n");
    fflush(stdout);

    fprintf(stderr, "mira-ios-log-smoke native-fprintf stderr\n");
    fflush(stderr);

    struct iovec iov[2] = {
        {.iov_base = "mira-ios-log-smoke native-writev ", .iov_len = strlen("mira-ios-log-smoke native-writev ")},
        {.iov_base = "stderr\n", .iov_len = strlen("stderr\n")},
    };
    (void) writev(STDERR_FILENO, iov, 2);
}
