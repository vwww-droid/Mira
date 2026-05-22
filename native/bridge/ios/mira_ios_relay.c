#include "mira_pty_ios_shim.h"

#include "mira/shell.h"

#include <arpa/inet.h>
#include <ctype.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <netdb.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <time.h>
#include <unistd.h>

#ifndef PATH_MAX
#define PATH_MAX 4096
#endif

#define MIRA_IOS_WS_MAX_FRAME (1024U * 1024U)
#define MIRA_IOS_RELAY_RETRY_SECONDS 2
#define MIRA_IOS_LOG_RING_LIMIT (96U * 1024U)
#define MIRA_IOS_LOG_SNAPSHOT_LIMIT (64U * 1024U)

static const char mira_b64_table[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

typedef struct mira_ws_connection {
    int fd;
    pthread_mutex_t write_mutex;
} mira_ws_connection_t;

typedef struct mira_ws_frame {
    int opcode;
    unsigned char *payload;
    size_t length;
} mira_ws_frame_t;

typedef struct mira_ios_relay_state {
    pthread_mutex_t mutex;
    int running;
    pthread_t thread;
    mira_ws_connection_t *control;
    char relay_url[1024];
    char device_name[128];
    char device_model[128];
    char hardware_model[64];
    char os_version[64];
    char home_dir[PATH_MAX];
    char install_id[64];
    char status[256];
    char log_ring[MIRA_IOS_LOG_RING_LIMIT];
    size_t log_length;
    mira_ios_screen_input_callback_t screen_input_callback;
    void *screen_input_context;
    mira_ios_log_provider_t log_provider;
} mira_ios_relay_state_t;

typedef struct mira_ios_session_state {
    char server_ws[1024];
    char session_id[128];
    char install_id[64];
    char home_dir[PATH_MAX];
    int columns;
    int rows;
    int cell_width;
    int cell_height;
    mira_ws_connection_t *ws;
    mira_shell_session_t *shell;
    pthread_t reader_thread;
    int reader_started;
    volatile int running;
} mira_ios_session_state_t;

static int mira_ws_send_text(mira_ws_connection_t *ws, const char *text);

static mira_ios_relay_state_t g_relay = {
    .mutex = PTHREAD_MUTEX_INITIALIZER,
    .running = 0,
    .thread = 0,
    .control = NULL,
    .relay_url = {0},
    .device_name = {0},
    .device_model = {0},
    .hardware_model = {0},
    .os_version = {0},
    .home_dir = {0},
    .install_id = {0},
    .status = "idle",
    .log_ring = {0},
    .log_length = 0,
    .screen_input_callback = NULL,
    .screen_input_context = NULL,
    .log_provider = NULL,
};

static void mira_log_append_locked(const char *message) {
    if (message == NULL) message = "";
    char line[512];
    time_t now = time(NULL);
    struct tm tmv;
    localtime_r(&now, &tmv);
    int prefix = snprintf(line, sizeof(line), "%04d-%02d-%02d %02d:%02d:%02d ",
                          tmv.tm_year + 1900,
                          tmv.tm_mon + 1,
                          tmv.tm_mday,
                          tmv.tm_hour,
                          tmv.tm_min,
                          tmv.tm_sec);
    if (prefix < 0) prefix = 0;
    snprintf(line + (size_t) prefix, sizeof(line) - (size_t) prefix, "%s\n", message);

    size_t length = strlen(line);
    if (length >= MIRA_IOS_LOG_RING_LIMIT) {
        const char *tail = line + length - (MIRA_IOS_LOG_RING_LIMIT - 1U);
        memmove(g_relay.log_ring, tail, MIRA_IOS_LOG_RING_LIMIT - 1U);
        g_relay.log_length = MIRA_IOS_LOG_RING_LIMIT - 1U;
        g_relay.log_ring[g_relay.log_length] = '\0';
        return;
    }
    if (g_relay.log_length + length >= MIRA_IOS_LOG_RING_LIMIT) {
        size_t drop = g_relay.log_length + length - (MIRA_IOS_LOG_RING_LIMIT - 1U);
        if (drop < g_relay.log_length) {
            memmove(g_relay.log_ring, g_relay.log_ring + drop, g_relay.log_length - drop);
            g_relay.log_length -= drop;
        } else {
            g_relay.log_length = 0;
        }
    }
    memcpy(g_relay.log_ring + g_relay.log_length, line, length);
    g_relay.log_length += length;
    g_relay.log_ring[g_relay.log_length] = '\0';
}

static void mira_status_set(const char *format, ...) {
    char message[256];
    va_list args;
    va_start(args, format);
    vsnprintf(message, sizeof(message), format, args);
    va_end(args);

    pthread_mutex_lock(&g_relay.mutex);
    snprintf(g_relay.status, sizeof(g_relay.status), "%s", message);
    mira_log_append_locked(message);
    pthread_mutex_unlock(&g_relay.mutex);
    fprintf(stderr, "Mira iOS relay: %s\n", message);
}

const char *mira_ios_relay_status(void) {
    static char snapshot[256];
    pthread_mutex_lock(&g_relay.mutex);
    snprintf(snapshot, sizeof(snapshot), "%s", g_relay.status);
    pthread_mutex_unlock(&g_relay.mutex);
    return snapshot;
}

const char *mira_ios_relay_install_id(void) {
    static char snapshot[64];
    pthread_mutex_lock(&g_relay.mutex);
    snprintf(snapshot, sizeof(snapshot), "%s", g_relay.install_id);
    pthread_mutex_unlock(&g_relay.mutex);
    return snapshot;
}

int mira_ios_relay_send_control_json(const char *json) {
    if (json == NULL || json[0] == '\0') {
        errno = EINVAL;
        return -1;
    }
    pthread_mutex_lock(&g_relay.mutex);
    mira_ws_connection_t *control = g_relay.control;
    if (!g_relay.running || control == NULL) {
        pthread_mutex_unlock(&g_relay.mutex);
        errno = ENOTCONN;
        return -1;
    }
    int result = mira_ws_send_text(control, json);
    pthread_mutex_unlock(&g_relay.mutex);
    return result;
}

void mira_ios_relay_set_screen_input_callback(mira_ios_screen_input_callback_t callback, void *context) {
    pthread_mutex_lock(&g_relay.mutex);
    g_relay.screen_input_callback = callback;
    g_relay.screen_input_context = context;
    pthread_mutex_unlock(&g_relay.mutex);
}

void mira_ios_relay_set_log_provider(mira_ios_log_provider_t provider) {
    pthread_mutex_lock(&g_relay.mutex);
    g_relay.log_provider = provider;
    pthread_mutex_unlock(&g_relay.mutex);
}

static void mira_dispatch_screen_input(const char *json) {
    mira_ios_screen_input_callback_t callback = NULL;
    void *context = NULL;
    pthread_mutex_lock(&g_relay.mutex);
    callback = g_relay.screen_input_callback;
    context = g_relay.screen_input_context;
    pthread_mutex_unlock(&g_relay.mutex);
    fprintf(stderr, "Mira iOS relay: screen input callback=%s payload=%s\n", callback != NULL ? "set" : "nil", json == NULL ? "" : json);
    if (callback != NULL) {
        callback(json == NULL ? "" : json, context);
    }
}

static int mira_is_running(void) {
    pthread_mutex_lock(&g_relay.mutex);
    int running = g_relay.running;
    pthread_mutex_unlock(&g_relay.mutex);
    return running;
}

static int mira_write_all(int fd, const void *data, size_t length) {
    const unsigned char *cursor = (const unsigned char *) data;
    while (length > 0) {
        ssize_t written = send(fd, cursor, length, 0);
        if (written < 0 && errno == EINTR) {
            continue;
        }
        if (written <= 0) {
            return -1;
        }
        cursor += written;
        length -= (size_t) written;
    }
    return 0;
}

static int mira_read_exact(int fd, void *data, size_t length) {
    unsigned char *cursor = (unsigned char *) data;
    while (length > 0) {
        ssize_t read_count = recv(fd, cursor, length, 0);
        if (read_count < 0 && errno == EINTR) {
            continue;
        }
        if (read_count <= 0) {
            return -1;
        }
        cursor += read_count;
        length -= (size_t) read_count;
    }
    return 0;
}

static char *mira_b64_encode_alloc(const unsigned char *data, size_t length) {
    size_t out_len = ((length + 2U) / 3U) * 4U;
    char *out = (char *) malloc(out_len + 1U);
    if (out == NULL) {
        return NULL;
    }
    size_t j = 0;
    for (size_t i = 0; i < length; i += 3U) {
        uint32_t value = ((uint32_t) data[i]) << 16;
        int remain = (int) (length - i);
        if (remain > 1) value |= ((uint32_t) data[i + 1U]) << 8;
        if (remain > 2) value |= (uint32_t) data[i + 2U];
        out[j++] = mira_b64_table[(value >> 18) & 0x3F];
        out[j++] = mira_b64_table[(value >> 12) & 0x3F];
        out[j++] = remain > 1 ? mira_b64_table[(value >> 6) & 0x3F] : '=';
        out[j++] = remain > 2 ? mira_b64_table[value & 0x3F] : '=';
    }
    out[j] = '\0';
    return out;
}

static int mira_b64_value(unsigned char ch) {
    if (ch >= 'A' && ch <= 'Z') return ch - 'A';
    if (ch >= 'a' && ch <= 'z') return ch - 'a' + 26;
    if (ch >= '0' && ch <= '9') return ch - '0' + 52;
    if (ch == '+') return 62;
    if (ch == '/') return 63;
    return -1;
}

static unsigned char *mira_b64_decode_alloc(const char *text, size_t *out_length) {
    if (out_length != NULL) *out_length = 0;
    if (text == NULL) {
        return NULL;
    }
    size_t length = strlen(text);
    unsigned char *out = (unsigned char *) malloc((length / 4U + 1U) * 3U);
    if (out == NULL) {
        return NULL;
    }
    int vals[4];
    int val_count = 0;
    size_t out_count = 0;
    for (size_t i = 0; i < length; ++i) {
        unsigned char ch = (unsigned char) text[i];
        if (isspace(ch)) continue;
        if (ch == '=') vals[val_count++] = -2;
        else {
            int value = mira_b64_value(ch);
            if (value < 0) continue;
            vals[val_count++] = value;
        }
        if (val_count == 4) {
            uint32_t n = ((uint32_t) (vals[0] < 0 ? 0 : vals[0]) << 18) |
                         ((uint32_t) (vals[1] < 0 ? 0 : vals[1]) << 12) |
                         ((uint32_t) (vals[2] < 0 ? 0 : vals[2]) << 6) |
                         ((uint32_t) (vals[3] < 0 ? 0 : vals[3]));
            out[out_count++] = (unsigned char) ((n >> 16) & 0xFF);
            if (vals[2] != -2) out[out_count++] = (unsigned char) ((n >> 8) & 0xFF);
            if (vals[3] != -2) out[out_count++] = (unsigned char) (n & 0xFF);
            val_count = 0;
        }
    }
    if (out_length != NULL) *out_length = out_count;
    return out;
}

static void mira_random_bytes(unsigned char *buffer, size_t length) {
    for (size_t i = 0; i < length; ++i) {
        buffer[i] = (unsigned char) (arc4random() & 0xFFU);
    }
}

static void mira_json_escape(const char *input, char *out, size_t out_size) {
    if (out_size == 0) return;
    size_t j = 0;
    const unsigned char *cursor = (const unsigned char *) (input == NULL ? "" : input);
    while (*cursor != '\0' && j + 2U < out_size) {
        unsigned char ch = *cursor++;
        if (ch == '"' || ch == '\\') {
            if (j + 3U >= out_size) break;
            out[j++] = '\\';
            out[j++] = (char) ch;
        } else if (ch == '\n') {
            if (j + 3U >= out_size) break;
            out[j++] = '\\';
            out[j++] = 'n';
        } else if (ch == '\r') {
            if (j + 3U >= out_size) break;
            out[j++] = '\\';
            out[j++] = 'r';
        } else if (ch >= 0x20) {
            out[j++] = (char) ch;
        }
    }
    out[j] = '\0';
}

static int mira_json_get_string(const char *json, const char *key, char *out, size_t out_size) {
    if (out == NULL || out_size == 0) return 0;
    out[0] = '\0';
    if (json == NULL || key == NULL) return 0;
    char pattern[128];
    snprintf(pattern, sizeof(pattern), "\"%s\"", key);
    const char *cursor = strstr(json, pattern);
    if (cursor == NULL) return 0;
    cursor += strlen(pattern);
    while (*cursor && isspace((unsigned char) *cursor)) ++cursor;
    if (*cursor != ':') return 0;
    ++cursor;
    while (*cursor && isspace((unsigned char) *cursor)) ++cursor;
    if (*cursor != '"') return 0;
    ++cursor;
    size_t j = 0;
    while (*cursor && *cursor != '"' && j + 1U < out_size) {
        if (*cursor == '\\' && cursor[1] != '\0') {
            ++cursor;
            if (*cursor == 'n') out[j++] = '\n';
            else if (*cursor == 'r') out[j++] = '\r';
            else if (*cursor == 't') out[j++] = '\t';
            else out[j++] = *cursor;
            ++cursor;
            continue;
        }
        out[j++] = *cursor++;
    }
    out[j] = '\0';
    return 1;
}

static int mira_json_get_int(const char *json, const char *key, int fallback) {
    if (json == NULL || key == NULL) return fallback;
    char pattern[128];
    snprintf(pattern, sizeof(pattern), "\"%s\"", key);
    const char *cursor = strstr(json, pattern);
    if (cursor == NULL) return fallback;
    cursor += strlen(pattern);
    while (*cursor && isspace((unsigned char) *cursor)) ++cursor;
    if (*cursor != ':') return fallback;
    ++cursor;
    while (*cursor && isspace((unsigned char) *cursor)) ++cursor;
    return atoi(cursor);
}

static void mira_send_device_command_result(const char *request_json,
                                            const char *command,
                                            int ok,
                                            int exit_code,
                                            const char *stdout_text,
                                            const char *stderr_text,
                                            const char *error_text) {
    char request_id[128] = {0};
    char install_id[64] = {0};
    char command_text[128] = {0};
    mira_json_get_string(request_json, "requestId", request_id, sizeof(request_id));
    if (command == NULL || command[0] == '\0') {
        mira_json_get_string(request_json, "command", command_text, sizeof(command_text));
        command = command_text;
    }
    pthread_mutex_lock(&g_relay.mutex);
    snprintf(install_id, sizeof(install_id), "%s", g_relay.install_id);
    pthread_mutex_unlock(&g_relay.mutex);

    size_t stdout_cap = strlen(stdout_text == NULL ? "" : stdout_text) * 2U + 256U;
    size_t stderr_cap = strlen(stderr_text == NULL ? "" : stderr_text) * 2U + 256U;
    size_t error_cap = strlen(error_text == NULL ? "" : error_text) * 2U + 256U;
    size_t command_cap = strlen(command == NULL ? "" : command) * 2U + 256U;
    size_t json_cap = stdout_cap + stderr_cap + error_cap + command_cap + 1024U;
    char *escaped_stdout = (char *) malloc(stdout_cap);
    char *escaped_stderr = (char *) malloc(stderr_cap);
    char *escaped_error = (char *) malloc(error_cap);
    char *escaped_command = (char *) malloc(command_cap);
    char *json = (char *) malloc(json_cap);
    if (escaped_stdout == NULL || escaped_stderr == NULL || escaped_error == NULL || escaped_command == NULL || json == NULL) {
        free(escaped_stdout);
        free(escaped_stderr);
        free(escaped_error);
        free(escaped_command);
        free(json);
        return;
    }
    mira_json_escape(stdout_text == NULL ? "" : stdout_text, escaped_stdout, stdout_cap);
    mira_json_escape(stderr_text == NULL ? "" : stderr_text, escaped_stderr, stderr_cap);
    mira_json_escape(error_text == NULL ? "" : error_text, escaped_error, error_cap);
    mira_json_escape(command == NULL ? "" : command, escaped_command, command_cap);
    snprintf(json, json_cap,
             "{\"type\":\"device.command.result\",\"protocol\":1,\"installId\":\"%s\",\"requestId\":\"%s\",\"command\":\"%s\",\"ok\":%s,\"exitCode\":%d,\"stdout\":\"%s\",\"stderr\":\"%s\",\"error\":\"%s\"}",
             install_id,
             request_id,
             escaped_command,
             ok ? "true" : "false",
             exit_code,
             escaped_stdout,
             escaped_stderr,
             escaped_error);
    (void) mira_ios_relay_send_control_json(json);
    free(escaped_stdout);
    free(escaped_stderr);
    free(escaped_error);
    free(escaped_command);
    free(json);
}

static void mira_handle_device_command(const char *json) {
    char command[128] = {0};
    if (!mira_json_get_string(json, "command", command, sizeof(command)) || command[0] == '\0') {
        mira_send_device_command_result(json, "mira", 0, 2, "", "missing command\n", "missing command");
        return;
    }
    if (strcmp(command, "mira-ios-logs") == 0) {
        int max_bytes = mira_json_get_int(json, "maxBytes", (int) MIRA_IOS_LOG_SNAPSHOT_LIMIT);
        if (max_bytes <= 0 || max_bytes > (int) MIRA_IOS_LOG_SNAPSHOT_LIMIT) max_bytes = (int) MIRA_IOS_LOG_SNAPSHOT_LIMIT;
        mira_ios_log_provider_t provider = NULL;
        pthread_mutex_lock(&g_relay.mutex);
        provider = g_relay.log_provider;
        pthread_mutex_unlock(&g_relay.mutex);
        char *snapshot = provider != NULL ? provider(max_bytes) : NULL;
        if (snapshot == NULL || snapshot[0] == '\0') {
            free(snapshot);
            snapshot = strdup("");
        }
        if (snapshot == NULL) {
            mira_send_device_command_result(json, command, 0, 1, "", "log snapshot allocation failed\n", "log snapshot allocation failed");
            return;
        }
        mira_send_device_command_result(json, command, 1, 0, snapshot, "", "");
        free(snapshot);
        return;
    }

    char error[256];
    snprintf(error, sizeof(error), "unsupported command: %s\n", command);
    mira_send_device_command_result(json, command, 0, 127, "", error, error);
}

static int mira_mkdir_p(const char *path) {
    if (path == NULL || path[0] == '\0') return -1;
    char tmp[PATH_MAX];
    snprintf(tmp, sizeof(tmp), "%s", path);
    size_t len = strlen(tmp);
    if (len == 0) return -1;
    if (tmp[len - 1U] == '/') tmp[len - 1U] = '\0';
    for (char *cursor = tmp + 1; *cursor; ++cursor) {
        if (*cursor == '/') {
            *cursor = '\0';
            if (mkdir(tmp, 0755) != 0 && errno != EEXIST) return -1;
            *cursor = '/';
        }
    }
    if (mkdir(tmp, 0755) != 0 && errno != EEXIST) return -1;
    return 0;
}

static void mira_load_install_id(const char *home_dir, char *out, size_t out_size) {
    if (out_size == 0) return;
    out[0] = '\0';
    char dir[PATH_MAX];
    snprintf(dir, sizeof(dir), "%s/Library/Application Support/Mira", home_dir == NULL || home_dir[0] == '\0' ? "/tmp" : home_dir);
    (void) mira_mkdir_p(dir);
    char path[PATH_MAX];
    snprintf(path, sizeof(path), "%s/install-id", dir);
    FILE *file = fopen(path, "rb");
    if (file != NULL) {
        if (fgets(out, (int) out_size, file) != NULL) {
            size_t n = strlen(out);
            while (n > 0 && isspace((unsigned char) out[n - 1U])) out[--n] = '\0';
        }
        fclose(file);
        if (out[0] != '\0') return;
    }
    unsigned char random[16];
    mira_random_bytes(random, sizeof(random));
    snprintf(out, out_size,
             "%02x%02x%02x%02x-%02x%02x-%02x%02x-%02x%02x-%02x%02x%02x%02x%02x%02x",
             random[0], random[1], random[2], random[3], random[4], random[5], random[6], random[7],
             random[8], random[9], random[10], random[11], random[12], random[13], random[14], random[15]);
    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0600);
    if (fd >= 0) {
        file = fdopen(fd, "wb");
        if (file != NULL) {
            fprintf(file, "%s\n", out);
            fclose(file);
        } else {
            close(fd);
        }
    }
}

static int mira_parse_ws_url(const char *url, char *scheme, size_t scheme_size, char *host, size_t host_size, int *port, char *path, size_t path_size) {
    if (url == NULL) return -1;
    const char *scheme_end = strstr(url, "://");
    if (scheme_end == NULL) return -1;
    size_t scheme_len = (size_t) (scheme_end - url);
    if (scheme_len + 1U > scheme_size) return -1;
    memcpy(scheme, url, scheme_len);
    scheme[scheme_len] = '\0';
    const char *authority = scheme_end + 3;
    const char *path_start = strchr(authority, '/');
    const char *authority_end = path_start == NULL ? url + strlen(url) : path_start;
    if (path_start == NULL || *path_start == '\0') snprintf(path, path_size, "/");
    else snprintf(path, path_size, "%s", path_start);

    const char *colon = NULL;
    for (const char *p = authority; p < authority_end; ++p) {
        if (*p == ':') colon = p;
    }
    if (colon != NULL) {
        size_t host_len = (size_t) (colon - authority);
        if (host_len + 1U > host_size) return -1;
        memcpy(host, authority, host_len);
        host[host_len] = '\0';
        *port = atoi(colon + 1);
    } else {
        size_t host_len = (size_t) (authority_end - authority);
        if (host_len + 1U > host_size) return -1;
        memcpy(host, authority, host_len);
        host[host_len] = '\0';
        *port = strcmp(scheme, "wss") == 0 ? 443 : 80;
    }
    return host[0] != '\0' && *port > 0 ? 0 : -1;
}

static int mira_build_ws_url(const char *raw_url, const char *default_path, char *out, size_t out_size) {
    if (raw_url == NULL || raw_url[0] == '\0' || out == NULL || out_size == 0) return -1;
    char normalized[1024];
    if (strstr(raw_url, "://") == NULL) snprintf(normalized, sizeof(normalized), "http://%s", raw_url);
    else snprintf(normalized, sizeof(normalized), "%s", raw_url);

    char scheme[16], host[512], path[512];
    int port = 0;
    const char *scheme_end = strstr(normalized, "://");
    if (scheme_end == NULL) return -1;
    char ws_url[1024];
    if (strncmp(normalized, "http://", 7) == 0) snprintf(ws_url, sizeof(ws_url), "ws://%s", normalized + 7);
    else if (strncmp(normalized, "https://", 8) == 0) snprintf(ws_url, sizeof(ws_url), "wss://%s", normalized + 8);
    else snprintf(ws_url, sizeof(ws_url), "%s", normalized);

    if (mira_parse_ws_url(ws_url, scheme, sizeof(scheme), host, sizeof(host), &port, path, sizeof(path)) != 0) return -1;
    if (strcmp(path, "/") == 0 || path[0] == '\0') {
        snprintf(path, sizeof(path), "%s", default_path);
    } else if (strstr(path, default_path) == NULL) {
        size_t n = strlen(path);
        while (n > 0 && path[n - 1U] == '/') path[--n] = '\0';
        snprintf(path + strlen(path), sizeof(path) - strlen(path), "%s", default_path);
    }
    if ((strcmp(scheme, "ws") != 0) && (strcmp(scheme, "wss") != 0)) return -1;
    if ((strcmp(scheme, "ws") == 0 && port == 80) || (strcmp(scheme, "wss") == 0 && port == 443)) {
        snprintf(out, out_size, "%s://%s%s", scheme, host, path);
    } else {
        snprintf(out, out_size, "%s://%s:%d%s", scheme, host, port, path);
    }
    return 0;
}

static mira_ws_connection_t *mira_ws_connect(const char *url, char *error, size_t error_size) {
    char scheme[16], host[512], path[512];
    int port = 0;
    if (mira_parse_ws_url(url, scheme, sizeof(scheme), host, sizeof(host), &port, path, sizeof(path)) != 0) {
        snprintf(error, error_size, "bad websocket url");
        return NULL;
    }
    if (strcmp(scheme, "wss") == 0) {
        snprintf(error, error_size, "wss not implemented in native C POC, use http/ws relay URL");
        return NULL;
    }
    if (strcmp(scheme, "ws") != 0) {
        snprintf(error, error_size, "unsupported websocket scheme");
        return NULL;
    }

    struct addrinfo hints;
    memset(&hints, 0, sizeof(hints));
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;
    char port_text[16];
    snprintf(port_text, sizeof(port_text), "%d", port);
    struct addrinfo *result = NULL;
    int gai = getaddrinfo(host, port_text, &hints, &result);
    if (gai != 0) {
        snprintf(error, error_size, "dns failed: %s", gai_strerror(gai));
        return NULL;
    }

    int fd = -1;
    for (struct addrinfo *ai = result; ai != NULL; ai = ai->ai_next) {
        fd = socket(ai->ai_family, ai->ai_socktype, ai->ai_protocol);
        if (fd < 0) continue;
#ifdef SO_NOSIGPIPE
        int yes = 1;
        setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &yes, sizeof(yes));
#endif
        if (connect(fd, ai->ai_addr, ai->ai_addrlen) == 0) break;
        close(fd);
        fd = -1;
    }
    freeaddrinfo(result);
    if (fd < 0) {
        snprintf(error, error_size, "connect failed: %s", strerror(errno));
        return NULL;
    }

    unsigned char nonce[16];
    mira_random_bytes(nonce, sizeof(nonce));
    char *key = mira_b64_encode_alloc(nonce, sizeof(nonce));
    if (key == NULL) {
        close(fd);
        snprintf(error, error_size, "alloc failed");
        return NULL;
    }
    char host_header[600];
    if (port == 80) snprintf(host_header, sizeof(host_header), "%s", host);
    else snprintf(host_header, sizeof(host_header), "%s:%d", host, port);
    char request[2048];
    snprintf(request, sizeof(request),
             "GET %s HTTP/1.1\r\n"
             "Host: %s\r\n"
             "Upgrade: websocket\r\n"
             "Connection: Upgrade\r\n"
             "Sec-WebSocket-Key: %s\r\n"
             "Sec-WebSocket-Version: 13\r\n\r\n",
             path,
             host_header,
             key);
    free(key);
    if (mira_write_all(fd, request, strlen(request)) != 0) {
        snprintf(error, error_size, "handshake write failed");
        close(fd);
        return NULL;
    }

    char response[4096];
    size_t n = 0;
    int state = 0;
    while (n + 1U < sizeof(response)) {
        unsigned char ch;
        if (mira_read_exact(fd, &ch, 1) != 0) {
            snprintf(error, error_size, "handshake read failed");
            close(fd);
            return NULL;
        }
        response[n++] = (char) ch;
        if (state == 0 && ch == '\r') state = 1;
        else if (state == 1 && ch == '\n') state = 2;
        else if (state == 2 && ch == '\r') state = 3;
        else if (state == 3 && ch == '\n') break;
        else state = 0;
    }
    response[n] = '\0';
    if (strstr(response, "101") == NULL) {
        snprintf(error, error_size, "websocket handshake failed");
        close(fd);
        return NULL;
    }

    mira_ws_connection_t *ws = (mira_ws_connection_t *) calloc(1, sizeof(*ws));
    if (ws == NULL) {
        close(fd);
        snprintf(error, error_size, "alloc failed");
        return NULL;
    }
    ws->fd = fd;
    pthread_mutex_init(&ws->write_mutex, NULL);
    return ws;
}

static void mira_ws_close(mira_ws_connection_t *ws) {
    if (ws == NULL) return;
    if (ws->fd >= 0) {
        shutdown(ws->fd, SHUT_RDWR);
        close(ws->fd);
        ws->fd = -1;
    }
    pthread_mutex_destroy(&ws->write_mutex);
    free(ws);
}

static int mira_ws_send_frame(mira_ws_connection_t *ws, int opcode, const unsigned char *payload, size_t length) {
    if (ws == NULL || ws->fd < 0) return -1;
    pthread_mutex_lock(&ws->write_mutex);
    unsigned char header[14];
    size_t header_len = 0;
    header[header_len++] = (unsigned char) (0x80 | (opcode & 0x0F));
    if (length < 126U) {
        header[header_len++] = (unsigned char) (0x80 | length);
    } else if (length <= 0xFFFFU) {
        header[header_len++] = 0x80 | 126;
        header[header_len++] = (unsigned char) ((length >> 8) & 0xFF);
        header[header_len++] = (unsigned char) (length & 0xFF);
    } else {
        header[header_len++] = 0x80 | 127;
        uint64_t long_len = (uint64_t) length;
        for (int i = 7; i >= 0; --i) header[header_len++] = (unsigned char) ((long_len >> (8 * i)) & 0xFF);
    }
    unsigned char mask[4];
    mira_random_bytes(mask, sizeof(mask));
    memcpy(header + header_len, mask, sizeof(mask));
    header_len += sizeof(mask);
    int rc = mira_write_all(ws->fd, header, header_len);
    if (rc == 0 && length > 0) {
        unsigned char *masked = (unsigned char *) malloc(length);
        if (masked == NULL) rc = -1;
        else {
            for (size_t i = 0; i < length; ++i) masked[i] = payload[i] ^ mask[i % 4U];
            rc = mira_write_all(ws->fd, masked, length);
            free(masked);
        }
    }
    pthread_mutex_unlock(&ws->write_mutex);
    return rc;
}

static int mira_ws_send_text(mira_ws_connection_t *ws, const char *text) {
    return mira_ws_send_frame(ws, 0x1, (const unsigned char *) (text == NULL ? "" : text), strlen(text == NULL ? "" : text));
}

static int mira_ws_read_frame(mira_ws_connection_t *ws, mira_ws_frame_t *frame) {
    if (ws == NULL || frame == NULL || ws->fd < 0) return -1;
    memset(frame, 0, sizeof(*frame));
    unsigned char h[2];
    if (mira_read_exact(ws->fd, h, 2) != 0) return -1;
    frame->opcode = h[0] & 0x0F;
    int masked = (h[1] & 0x80) != 0;
    uint64_t length = h[1] & 0x7F;
    if (length == 126) {
        unsigned char ext[2];
        if (mira_read_exact(ws->fd, ext, sizeof(ext)) != 0) return -1;
        length = ((uint64_t) ext[0] << 8) | ext[1];
    } else if (length == 127) {
        unsigned char ext[8];
        if (mira_read_exact(ws->fd, ext, sizeof(ext)) != 0) return -1;
        length = 0;
        for (int i = 0; i < 8; ++i) length = (length << 8) | ext[i];
    }
    if (length > MIRA_IOS_WS_MAX_FRAME) return -1;
    unsigned char mask[4] = {0};
    if (masked && mira_read_exact(ws->fd, mask, sizeof(mask)) != 0) return -1;
    frame->payload = (unsigned char *) malloc((size_t) length + 1U);
    if (frame->payload == NULL) return -1;
    frame->length = (size_t) length;
    if (length > 0 && mira_read_exact(ws->fd, frame->payload, (size_t) length) != 0) {
        free(frame->payload);
        memset(frame, 0, sizeof(*frame));
        return -1;
    }
    if (masked) {
        for (size_t i = 0; i < frame->length; ++i) frame->payload[i] ^= mask[i % 4U];
    }
    frame->payload[frame->length] = '\0';
    return 0;
}

static void mira_ws_frame_free(mira_ws_frame_t *frame) {
    if (frame != NULL) {
        free(frame->payload);
        memset(frame, 0, sizeof(*frame));
    }
}

static void mira_session_send_json(mira_ios_session_state_t *session, const char *json) {
    if (session != NULL && session->ws != NULL && session->running) {
        (void) mira_ws_send_text(session->ws, json);
    }
}

static void *mira_session_shell_reader(void *arg) {
    mira_ios_session_state_t *session = (mira_ios_session_state_t *) arg;
    unsigned char buffer[8192];
    while (session->running && session->shell != NULL) {
        ssize_t read_count = mira_shell_read(session->shell, buffer, sizeof(buffer));
        if (read_count <= 0) break;
        char *encoded = mira_b64_encode_alloc(buffer, (size_t) read_count);
        if (encoded == NULL) break;
        char json[12288];
        snprintf(json, sizeof(json), "{\"type\":\"terminal.output\",\"sessionId\":\"%s\",\"dataBase64\":\"%s\"}", session->session_id, encoded);
        free(encoded);
        mira_session_send_json(session, json);
    }
    if (session->running) {
        char json[256];
        snprintf(json, sizeof(json), "{\"type\":\"session.close\",\"sessionId\":\"%s\"}", session->session_id);
        mira_session_send_json(session, json);
    }
    return NULL;
}

static void *mira_session_thread(void *arg) {
    mira_ios_session_state_t *session = (mira_ios_session_state_t *) arg;
    session->running = 1;
    char error[256] = {0};
    mira_shell_options_t options;
    memset(&options, 0, sizeof(options));
    options.backend = MIRA_SHELL_BACKEND_AUTO;
    options.cwd = session->home_dir;
    options.rows = session->rows > 0 ? session->rows : 24;
    options.columns = session->columns > 0 ? session->columns : 80;
    options.cell_width = session->cell_width;
    options.cell_height = session->cell_height;
    session->shell = mira_shell_open(&options);
    if (session->shell == NULL) {
        const char *detail = mira_shell_last_error();
        mira_status_set("session shell failed: %s", detail != NULL && detail[0] != '\0' ? detail : strerror(errno));
        goto done;
    }
    session->ws = mira_ws_connect(session->server_ws, error, sizeof(error));
    if (session->ws == NULL) {
        mira_status_set("device ws failed: %s", error);
        goto done;
    }
    char attach[512];
    snprintf(attach, sizeof(attach), "{\"type\":\"device.attach\",\"protocol\":1,\"installId\":\"%s\",\"sessionId\":\"%s\"}", session->install_id, session->session_id);
    if (mira_ws_send_text(session->ws, attach) != 0) {
        mira_status_set("device attach failed");
        goto done;
    }
    mira_status_set("session attached: %s", session->session_id);

    session->reader_started = pthread_create(&session->reader_thread, NULL, mira_session_shell_reader, session) == 0;

    while (session->running && mira_is_running()) {
        mira_ws_frame_t frame;
        if (mira_ws_read_frame(session->ws, &frame) != 0) break;
        if (frame.opcode == 0x8) {
            mira_ws_frame_free(&frame);
            break;
        }
        if (frame.opcode == 0x9) {
            (void) mira_ws_send_frame(session->ws, 0xA, frame.payload, frame.length);
            mira_ws_frame_free(&frame);
            continue;
        }
        if (frame.opcode == 0x1) {
            const char *text = (const char *) frame.payload;
            char type[64];
            mira_json_get_string(text, "type", type, sizeof(type));
            if (strcmp(type, "terminal.input") == 0) {
                size_t b64_capacity = frame.length + 1U;
                char *b64 = (char *) malloc(b64_capacity);
                if (b64 != NULL) {
                    if (mira_json_get_string(text, "dataBase64", b64, b64_capacity)) {
                        size_t data_len = 0;
                        unsigned char *data = mira_b64_decode_alloc(b64, &data_len);
                        if (data != NULL) {
                            if (session->shell != NULL) {
                                ssize_t written = mira_shell_write(session->shell, data, data_len);
                                fprintf(stderr, "Mira iOS relay: terminal.input len=%zu written=%zd\n", data_len, written);
                            }
                            free(data);
                        }
                    }
                    free(b64);
                }
            } else if (strcmp(type, "terminal.resize") == 0) {
                int cols = mira_json_get_int(text, "cols", 0);
                int rows = mira_json_get_int(text, "rows", 0);
                int cw = mira_json_get_int(text, "cellWidth", 0);
                int ch = mira_json_get_int(text, "cellHeight", 0);
                if (session->shell != NULL) (void) mira_shell_resize(session->shell, cols, rows, cw, ch);
            } else if (strcmp(type, "session.close") == 0) {
                mira_ws_frame_free(&frame);
                break;
            }
        }
        mira_ws_frame_free(&frame);
    }

done:
    session->running = 0;
    if (session->shell != NULL) {
        mira_shell_close(session->shell);
        session->shell = NULL;
    }
    if (session->reader_started) {
        pthread_join(session->reader_thread, NULL);
        session->reader_started = 0;
    }
    if (session->ws != NULL) {
        mira_ws_close(session->ws);
        session->ws = NULL;
    }
    free(session);
    mira_status_set("session closed");
    return NULL;
}

static void mira_start_session_from_json(const char *json) {
    mira_ios_session_state_t *session = (mira_ios_session_state_t *) calloc(1, sizeof(*session));
    if (session == NULL) return;
    if (!mira_json_get_string(json, "serverWs", session->server_ws, sizeof(session->server_ws)) ||
        !mira_json_get_string(json, "sessionId", session->session_id, sizeof(session->session_id))) {
        free(session);
        return;
    }
    pthread_mutex_lock(&g_relay.mutex);
    snprintf(session->install_id, sizeof(session->install_id), "%s", g_relay.install_id);
    snprintf(session->home_dir, sizeof(session->home_dir), "%s", g_relay.home_dir);
    pthread_mutex_unlock(&g_relay.mutex);
    session->columns = mira_json_get_int(json, "cols", 80);
    session->rows = mira_json_get_int(json, "rows", 24);
    session->cell_width = mira_json_get_int(json, "cellWidth", 0);
    session->cell_height = mira_json_get_int(json, "cellHeight", 0);
    char session_id[sizeof(session->session_id)];
    snprintf(session_id, sizeof(session_id), "%s", session->session_id);
    pthread_t thread;
    if (pthread_create(&thread, NULL, mira_session_thread, session) != 0) {
        free(session);
        mira_status_set("session thread failed");
        return;
    }
    pthread_detach(thread);
    mira_status_set("session opening: %s", session_id);
}

static void *mira_control_thread(void *arg) {
    (void) arg;
    char relay_url[1024], device_name[128], device_model[128], hardware_model[64], os_version[64], home_dir[PATH_MAX], install_id[64];
    pthread_mutex_lock(&g_relay.mutex);
    snprintf(relay_url, sizeof(relay_url), "%s", g_relay.relay_url);
    snprintf(device_name, sizeof(device_name), "%s", g_relay.device_name);
    snprintf(device_model, sizeof(device_model), "%s", g_relay.device_model);
    snprintf(hardware_model, sizeof(hardware_model), "%s", g_relay.hardware_model);
    snprintf(os_version, sizeof(os_version), "%s", g_relay.os_version);
    snprintf(home_dir, sizeof(home_dir), "%s", g_relay.home_dir);
    snprintf(install_id, sizeof(install_id), "%s", g_relay.install_id);
    pthread_mutex_unlock(&g_relay.mutex);

    char control_ws[1024];
    if (mira_build_ws_url(relay_url, "/ws/control", control_ws, sizeof(control_ws)) != 0) {
        mira_status_set("bad relay url");
        goto done;
    }

    while (mira_is_running()) {
        char error[256] = {0};
        mira_status_set("control connecting");
        mira_ws_connection_t *ws = mira_ws_connect(control_ws, error, sizeof(error));
        if (ws == NULL) {
            mira_status_set("control failed: %s", error);
            sleep(MIRA_IOS_RELAY_RETRY_SECONDS);
            continue;
        }
        char escaped_device[256], escaped_model[256], escaped_hardware[128], escaped_os_version[128], escaped_relay[1200];
        mira_json_escape(device_name, escaped_device, sizeof(escaped_device));
        mira_json_escape(device_model, escaped_model, sizeof(escaped_model));
        mira_json_escape(hardware_model, escaped_hardware, sizeof(escaped_hardware));
        mira_json_escape(os_version, escaped_os_version, sizeof(escaped_os_version));
        mira_json_escape(relay_url, escaped_relay, sizeof(escaped_relay));
        char register_json[2560];
        snprintf(register_json, sizeof(register_json),
                 "{\"type\":\"device.register\",\"protocol\":1,\"installId\":\"%s\",\"deviceName\":\"%s\",\"packageName\":\"com.vwww.mira.ios\",\"platform\":\"ios\",\"osName\":\"iOS\",\"osVersion\":\"%s\",\"screenSource\":\"app-key-window\",\"model\":\"%s\",\"hardwareModel\":\"%s\",\"arch\":\"arm64\",\"state\":\"idle\",\"transport\":\"control\",\"relayUrl\":\"%s\"}",
                 install_id,
                 escaped_device[0] ? escaped_device : "iPhone",
                 escaped_os_version,
                 escaped_model[0] ? escaped_model : "iPhone",
                 escaped_hardware,
                 escaped_relay);
        if (mira_ws_send_text(ws, register_json) != 0) {
            mira_status_set("control register failed");
            mira_ws_close(ws);
            sleep(MIRA_IOS_RELAY_RETRY_SECONDS);
            continue;
        }
        pthread_mutex_lock(&g_relay.mutex);
        g_relay.control = ws;
        pthread_mutex_unlock(&g_relay.mutex);
        mira_status_set("control connected");

        while (mira_is_running()) {
            mira_ws_frame_t frame;
            if (mira_ws_read_frame(ws, &frame) != 0) break;
            if (frame.opcode == 0x8) {
                mira_ws_frame_free(&frame);
                break;
            }
            if (frame.opcode == 0x9) {
                (void) mira_ws_send_frame(ws, 0xA, frame.payload, frame.length);
                mira_ws_frame_free(&frame);
                continue;
            }
            if (frame.opcode == 0x1) {
                char type[64];
                mira_json_get_string((const char *) frame.payload, "type", type, sizeof(type));
                if (strcmp(type, "control.ready") == 0) {
                    mira_status_set("control ready");
                } else if (strcmp(type, "session.open") == 0) {
                    mira_start_session_from_json((const char *) frame.payload);
                } else if (strcmp(type, "session.close") == 0) {
                    mira_status_set("session close requested");
                } else if (strcmp(type, "screen.input") == 0) {
                    mira_dispatch_screen_input((const char *) frame.payload);
                } else if (strcmp(type, "device.command") == 0) {
                    mira_handle_device_command((const char *) frame.payload);
                }
            }
            mira_ws_frame_free(&frame);
        }

        pthread_mutex_lock(&g_relay.mutex);
        if (g_relay.control == ws) g_relay.control = NULL;
        pthread_mutex_unlock(&g_relay.mutex);
        mira_ws_close(ws);
        if (mira_is_running()) {
            mira_status_set("control disconnected");
            sleep(MIRA_IOS_RELAY_RETRY_SECONDS);
        }
    }

done:
    pthread_mutex_lock(&g_relay.mutex);
    g_relay.running = 0;
    g_relay.thread = 0;
    pthread_mutex_unlock(&g_relay.mutex);
    mira_status_set("stopped");
    return NULL;
}

int mira_ios_relay_start_with_device_info(
    const char *relay_url,
    const char *device_name,
    const char *home_dir,
    const char *device_model,
    const char *hardware_model,
    const char *os_version
) {
    if (relay_url == NULL || relay_url[0] == '\0') {
        mira_status_set("relay url required");
        errno = EINVAL;
        return -1;
    }
    pthread_mutex_lock(&g_relay.mutex);
    if (g_relay.running) {
        pthread_mutex_unlock(&g_relay.mutex);
        return 0;
    }
    snprintf(g_relay.relay_url, sizeof(g_relay.relay_url), "%s", relay_url);
    snprintf(g_relay.device_name, sizeof(g_relay.device_name), "%s", device_name == NULL || device_name[0] == '\0' ? "iPhone" : device_name);
    snprintf(g_relay.device_model, sizeof(g_relay.device_model), "%s", device_model == NULL || device_model[0] == '\0' ? "iPhone" : device_model);
    snprintf(g_relay.hardware_model, sizeof(g_relay.hardware_model), "%s", hardware_model == NULL ? "" : hardware_model);
    snprintf(g_relay.os_version, sizeof(g_relay.os_version), "%s", os_version == NULL ? "" : os_version);
    snprintf(g_relay.home_dir, sizeof(g_relay.home_dir), "%s", home_dir == NULL || home_dir[0] == '\0' ? "/tmp" : home_dir);
    mira_load_install_id(g_relay.home_dir, g_relay.install_id, sizeof(g_relay.install_id));
    g_relay.running = 1;
    pthread_mutex_unlock(&g_relay.mutex);

    if (pthread_create(&g_relay.thread, NULL, mira_control_thread, NULL) != 0) {
        pthread_mutex_lock(&g_relay.mutex);
        g_relay.running = 0;
        pthread_mutex_unlock(&g_relay.mutex);
        mira_status_set("control thread failed");
        return -1;
    }
    mira_status_set("starting");
    return 0;
}

int mira_ios_relay_start(const char *relay_url, const char *device_name, const char *home_dir) {
    return mira_ios_relay_start_with_device_info(relay_url, device_name, home_dir, device_name, "", "");
}

void mira_ios_relay_stop(void) {
    mira_ws_connection_t *control = NULL;
    pthread_t thread = 0;
    pthread_mutex_lock(&g_relay.mutex);
    g_relay.running = 0;
    control = g_relay.control;
    g_relay.control = NULL;
    thread = g_relay.thread;
    pthread_mutex_unlock(&g_relay.mutex);
    if (control != NULL) {
        mira_ws_close(control);
    }
    if (thread != 0 && !pthread_equal(thread, pthread_self())) {
        pthread_join(thread, NULL);
    }
    mira_status_set("stopped");
}
