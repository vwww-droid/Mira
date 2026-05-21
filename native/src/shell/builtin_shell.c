#include "shell/builtin_shell.h"

#include <ctype.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <pthread.h>
#include <pwd.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/utsname.h>
#include <time.h>
#include <unistd.h>

#ifndef PATH_MAX
#define PATH_MAX 4096
#endif

#define MIRA_BUILTIN_INPUT_LIMIT 4096U
#define MIRA_BUILTIN_READ_CHUNK 8192U

struct mira_builtin_shell {
    pthread_mutex_t mutex;
    pthread_cond_t cond;
    char *output;
    size_t output_length;
    size_t output_capacity;
    char input[MIRA_BUILTIN_INPUT_LIMIT];
    size_t input_length;
    char cwd[PATH_MAX];
    char home[PATH_MAX];
    int columns;
    int rows;
    int closed;
    int exit_status;
};

static void mira_builtin_append_locked(mira_builtin_shell_t *shell, const char *data, size_t length) {
    if (shell == NULL || data == NULL || length == 0) {
        return;
    }

    if (shell->output_length == SIZE_MAX || length > SIZE_MAX - shell->output_length - 1U) {
        return;
    }
    size_t needed = shell->output_length + length + 1U;

    if (needed > shell->output_capacity) {
        size_t next = shell->output_capacity == 0 ? 4096U : shell->output_capacity;
        while (next < needed) {
            if (next > SIZE_MAX / 2U) {
                next = needed;
                break;
            }
            next *= 2U;
        }
        char *resized = (char *) realloc(shell->output, next);
        if (resized == NULL) {
            return;
        }
        shell->output = resized;
        shell->output_capacity = next;
    }
    memcpy(shell->output + shell->output_length, data, length);
    shell->output_length += length;
    shell->output[shell->output_length] = '\0';
    pthread_cond_broadcast(&shell->cond);
}

static void mira_builtin_append_cstr_locked(mira_builtin_shell_t *shell, const char *text) {
    if (text != NULL) {
        mira_builtin_append_locked(shell, text, strlen(text));
    }
}

static void mira_builtin_printf_locked(mira_builtin_shell_t *shell, const char *format, ...) {
    char stack[2048];
    va_list args;
    va_start(args, format);
    int needed = vsnprintf(stack, sizeof(stack), format, args);
    va_end(args);
    if (needed <= 0) {
        return;
    }
    if ((size_t) needed < sizeof(stack)) {
        mira_builtin_append_locked(shell, stack, (size_t) needed);
        return;
    }
    char *heap = (char *) malloc((size_t) needed + 1U);
    if (heap == NULL) {
        return;
    }
    va_start(args, format);
    vsnprintf(heap, (size_t) needed + 1U, format, args);
    va_end(args);
    mira_builtin_append_locked(shell, heap, (size_t) needed);
    free(heap);
}

static void mira_builtin_prompt_locked(mira_builtin_shell_t *shell) {
    const char *display = shell->cwd;
    size_t home_len = strlen(shell->home);
    if (home_len > 0 && strncmp(shell->cwd, shell->home, home_len) == 0) {
        if (shell->cwd[home_len] == '\0') {
            display = "~";
        } else if (shell->cwd[home_len] == '/') {
            mira_builtin_append_cstr_locked(shell, "mira-ios:~");
            mira_builtin_append_cstr_locked(shell, shell->cwd + home_len);
            mira_builtin_append_cstr_locked(shell, " $ ");
            return;
        }
    }
    mira_builtin_printf_locked(shell, "mira-ios:%s $ ", display);
}

static void mira_builtin_strip_line(char *line) {
    if (line == NULL) {
        return;
    }
    char *start = line;
    while (*start && isspace((unsigned char) *start)) {
        ++start;
    }
    if (start != line) {
        memmove(line, start, strlen(start) + 1U);
    }
    size_t length = strlen(line);
    while (length > 0 && isspace((unsigned char) line[length - 1U])) {
        line[--length] = '\0';
    }
}

static int mira_builtin_tokenize(char *line, char **argv, int max_argc) {
    int argc = 0;
    char *cursor = line;
    while (*cursor != '\0' && argc < max_argc - 1) {
        while (*cursor != '\0' && isspace((unsigned char) *cursor)) {
            ++cursor;
        }
        if (*cursor == '\0') {
            break;
        }

        char *write_cursor = cursor;
        argv[argc++] = write_cursor;
        char quote = 0;
        if (*cursor == '\'' || *cursor == '"') {
            quote = *cursor++;
        }

        while (*cursor != '\0') {
            if (quote != 0) {
                if (*cursor == quote) {
                    ++cursor;
                    break;
                }
            } else if (isspace((unsigned char) *cursor)) {
                break;
            }
            if (*cursor == '\\' && cursor[1] != '\0') {
                ++cursor;
            }
            *write_cursor++ = *cursor++;
        }
        int has_more = *cursor != '\0';
        *write_cursor = '\0';
        if (has_more) {
            ++cursor;
        }
        while (*cursor != '\0' && isspace((unsigned char) *cursor)) {
            ++cursor;
        }
    }
    argv[argc] = NULL;
    return argc;
}

static int mira_builtin_resolve_path(mira_builtin_shell_t *shell, const char *input, char *out, size_t out_size, int require_existing) {
    if (shell == NULL || out == NULL || out_size == 0) {
        errno = EINVAL;
        return -1;
    }
    const char *path = (input == NULL || input[0] == '\0') ? shell->home : input;
    char combined[PATH_MAX];
    if (strcmp(path, "~") == 0) {
        snprintf(combined, sizeof(combined), "%s", shell->home);
    } else if (strncmp(path, "~/", 2) == 0) {
        snprintf(combined, sizeof(combined), "%s/%s", shell->home, path + 2);
    } else if (path[0] == '/') {
        snprintf(combined, sizeof(combined), "%s", path);
    } else {
        snprintf(combined, sizeof(combined), "%s/%s", shell->cwd, path);
    }

    if (require_existing) {
        char resolved[PATH_MAX];
        if (realpath(combined, resolved) == NULL) {
            return -1;
        }
        snprintf(out, out_size, "%s", resolved);
        return 0;
    }

    if (combined[0] == '/') {
        snprintf(out, out_size, "%s", combined);
    } else {
        snprintf(out, out_size, "%s/%s", shell->cwd, combined);
    }
    return 0;
}

static void mira_builtin_cmd_help(mira_builtin_shell_t *shell) {
    mira_builtin_append_cstr_locked(shell,
        "Mira iOS builtin shell\r\n"
        "commands:\r\n"
        "  help                 show this help\r\n"
        "  pwd                  print current directory\r\n"
        "  cd [dir]             change directory\r\n"
        "  ls [dir]             list directory\r\n"
        "  cat <file>           print file\r\n"
        "  echo [text]          print text\r\n"
        "  mkdir <dir>          create directory\r\n"
        "  touch <file>         create or update file\r\n"
        "  rm <file>            remove file\r\n"
        "  rmdir <dir>          remove empty directory\r\n"
        "  write <file> <text>  overwrite file\r\n"
        "  append <file> <text> append file\r\n"
        "  stat <path>          show file metadata\r\n"
        "  env                  show shell environment\r\n"
        "  uname                show system name\r\n"
        "  id                   show uid and gid\r\n"
        "  date                 show current date\r\n"
        "  clear                clear terminal\r\n"
        "  exit [code]          close session\r\n");
}

static void mira_builtin_cmd_ls(mira_builtin_shell_t *shell, int argc, char **argv) {
    char path[PATH_MAX];
    if (mira_builtin_resolve_path(shell, argc > 1 ? argv[1] : NULL, path, sizeof(path), 1) != 0) {
        mira_builtin_printf_locked(shell, "ls: %s: %s\r\n", argc > 1 ? argv[1] : ".", strerror(errno));
        return;
    }
    DIR *dir = opendir(path);
    if (dir == NULL) {
        mira_builtin_printf_locked(shell, "ls: %s: %s\r\n", path, strerror(errno));
        return;
    }
    struct dirent *entry;
    while ((entry = readdir(dir)) != NULL) {
        if (strcmp(entry->d_name, ".") == 0 || strcmp(entry->d_name, "..") == 0) {
            continue;
        }
        char child[PATH_MAX];
        snprintf(child, sizeof(child), "%s/%s", path, entry->d_name);
        struct stat st;
        const char *suffix = "";
        if (stat(child, &st) == 0 && S_ISDIR(st.st_mode)) {
            suffix = "/";
        }
        mira_builtin_printf_locked(shell, "%s%s\r\n", entry->d_name, suffix);
    }
    closedir(dir);
}

static void mira_builtin_cmd_cat(mira_builtin_shell_t *shell, int argc, char **argv) {
    if (argc < 2) {
        mira_builtin_append_cstr_locked(shell, "cat: missing file\r\n");
        return;
    }
    char path[PATH_MAX];
    if (mira_builtin_resolve_path(shell, argv[1], path, sizeof(path), 1) != 0) {
        mira_builtin_printf_locked(shell, "cat: %s: %s\r\n", argv[1], strerror(errno));
        return;
    }
    FILE *file = fopen(path, "rb");
    if (file == NULL) {
        mira_builtin_printf_locked(shell, "cat: %s: %s\r\n", path, strerror(errno));
        return;
    }
    char buffer[4096];
    size_t read_count;
    while ((read_count = fread(buffer, 1, sizeof(buffer), file)) > 0) {
        mira_builtin_append_locked(shell, buffer, read_count);
    }
    if (ferror(file)) {
        mira_builtin_printf_locked(shell, "\r\ncat: %s: read failed\r\n", path);
    }
    fclose(file);
    mira_builtin_append_cstr_locked(shell, "\r\n");
}

static const char *mira_builtin_rest_after_command(const char *line) {
    if (line == NULL) {
        return "";
    }
    while (*line && !isspace((unsigned char) *line)) {
        ++line;
    }
    while (*line && isspace((unsigned char) *line)) {
        ++line;
    }
    return line;
}

static void mira_builtin_write_text_file(mira_builtin_shell_t *shell, const char *command_line, int argc, char **argv, const char *mode) {
    if (argc < 3) {
        mira_builtin_printf_locked(shell, "%s: missing file or text\r\n", argv[0]);
        return;
    }
    char path[PATH_MAX];
    if (mira_builtin_resolve_path(shell, argv[1], path, sizeof(path), 0) != 0) {
        mira_builtin_printf_locked(shell, "%s: %s: %s\r\n", argv[0], argv[1], strerror(errno));
        return;
    }
    FILE *file = fopen(path, mode);
    if (file == NULL) {
        mira_builtin_printf_locked(shell, "%s: %s: %s\r\n", argv[0], path, strerror(errno));
        return;
    }
    const char *text = strstr(command_line, argv[1]);
    if (text != NULL) {
        text += strlen(argv[1]);
        while (*text && isspace((unsigned char) *text)) {
            ++text;
        }
    } else {
        text = "";
    }
    fputs(text, file);
    fputc('\n', file);
    fclose(file);
}

static void mira_builtin_execute_line(mira_builtin_shell_t *shell, const char *raw_line) {
    char line[MIRA_BUILTIN_INPUT_LIMIT + 1U];
    snprintf(line, sizeof(line), "%s", raw_line == NULL ? "" : raw_line);
    mira_builtin_strip_line(line);
    if (line[0] == '\0') {
        mira_builtin_prompt_locked(shell);
        return;
    }

    char parse_line[MIRA_BUILTIN_INPUT_LIMIT + 1U];
    snprintf(parse_line, sizeof(parse_line), "%s", line);
    char *argv[64];
    int argc = mira_builtin_tokenize(parse_line, argv, 64);
    if (argc == 0) {
        mira_builtin_prompt_locked(shell);
        return;
    }

    if (strcmp(argv[0], "help") == 0) {
        mira_builtin_cmd_help(shell);
    } else if (strcmp(argv[0], "pwd") == 0) {
        mira_builtin_printf_locked(shell, "%s\r\n", shell->cwd);
    } else if (strcmp(argv[0], "cd") == 0) {
        char path[PATH_MAX];
        if (mira_builtin_resolve_path(shell, argc > 1 ? argv[1] : NULL, path, sizeof(path), 1) != 0) {
            mira_builtin_printf_locked(shell, "cd: %s: %s\r\n", argc > 1 ? argv[1] : shell->home, strerror(errno));
        } else {
            struct stat st;
            if (stat(path, &st) != 0 || !S_ISDIR(st.st_mode)) {
                mira_builtin_printf_locked(shell, "cd: %s: not a directory\r\n", path);
            } else {
                snprintf(shell->cwd, sizeof(shell->cwd), "%s", path);
            }
        }
    } else if (strcmp(argv[0], "ls") == 0) {
        mira_builtin_cmd_ls(shell, argc, argv);
    } else if (strcmp(argv[0], "cat") == 0) {
        mira_builtin_cmd_cat(shell, argc, argv);
    } else if (strcmp(argv[0], "echo") == 0) {
        mira_builtin_printf_locked(shell, "%s\r\n", mira_builtin_rest_after_command(line));
    } else if (strcmp(argv[0], "mkdir") == 0) {
        if (argc < 2) {
            mira_builtin_append_cstr_locked(shell, "mkdir: missing directory\r\n");
        } else {
            char path[PATH_MAX];
            if (mira_builtin_resolve_path(shell, argv[1], path, sizeof(path), 0) != 0 || mkdir(path, 0755) != 0) {
                mira_builtin_printf_locked(shell, "mkdir: %s: %s\r\n", argv[1], strerror(errno));
            }
        }
    } else if (strcmp(argv[0], "touch") == 0) {
        if (argc < 2) {
            mira_builtin_append_cstr_locked(shell, "touch: missing file\r\n");
        } else {
            char path[PATH_MAX];
            if (mira_builtin_resolve_path(shell, argv[1], path, sizeof(path), 0) != 0) {
                mira_builtin_printf_locked(shell, "touch: %s: %s\r\n", argv[1], strerror(errno));
            } else {
                int fd = open(path, O_CREAT | O_WRONLY, 0644);
                if (fd < 0) mira_builtin_printf_locked(shell, "touch: %s: %s\r\n", path, strerror(errno));
                else close(fd);
            }
        }
    } else if (strcmp(argv[0], "rm") == 0) {
        if (argc < 2) {
            mira_builtin_append_cstr_locked(shell, "rm: missing file\r\n");
        } else {
            char path[PATH_MAX];
            if (mira_builtin_resolve_path(shell, argv[1], path, sizeof(path), 0) != 0 || unlink(path) != 0) {
                mira_builtin_printf_locked(shell, "rm: %s: %s\r\n", argv[1], strerror(errno));
            }
        }
    } else if (strcmp(argv[0], "rmdir") == 0) {
        if (argc < 2) {
            mira_builtin_append_cstr_locked(shell, "rmdir: missing directory\r\n");
        } else {
            char path[PATH_MAX];
            if (mira_builtin_resolve_path(shell, argv[1], path, sizeof(path), 0) != 0 || rmdir(path) != 0) {
                mira_builtin_printf_locked(shell, "rmdir: %s: %s\r\n", argv[1], strerror(errno));
            }
        }
    } else if (strcmp(argv[0], "write") == 0) {
        mira_builtin_write_text_file(shell, line, argc, argv, "wb");
    } else if (strcmp(argv[0], "append") == 0) {
        mira_builtin_write_text_file(shell, line, argc, argv, "ab");
    } else if (strcmp(argv[0], "stat") == 0) {
        if (argc < 2) {
            mira_builtin_append_cstr_locked(shell, "stat: missing path\r\n");
        } else {
            char path[PATH_MAX];
            struct stat st;
            if (mira_builtin_resolve_path(shell, argv[1], path, sizeof(path), 1) != 0 || stat(path, &st) != 0) {
                mira_builtin_printf_locked(shell, "stat: %s: %s\r\n", argv[1], strerror(errno));
            } else {
                mira_builtin_printf_locked(shell, "path: %s\r\nsize: %lld\r\nmode: %o\r\nuid: %u\r\ngid: %u\r\n", path, (long long) st.st_size, (unsigned) st.st_mode, (unsigned) st.st_uid, (unsigned) st.st_gid);
            }
        }
    } else if (strcmp(argv[0], "env") == 0) {
        mira_builtin_printf_locked(shell,
            "HOME=%s\r\nPWD=%s\r\nSHELL=/mira/builtin-sh\r\nTERM=xterm-256color\r\nMIRA_SANDBOX=1\r\n",
            shell->home,
            shell->cwd);
    } else if (strcmp(argv[0], "uname") == 0) {
        struct utsname name;
        if (uname(&name) == 0) {
            mira_builtin_printf_locked(shell, "%s %s %s %s %s\r\n", name.sysname, name.nodename, name.release, name.version, name.machine);
        } else {
            mira_builtin_printf_locked(shell, "uname: %s\r\n", strerror(errno));
        }
    } else if (strcmp(argv[0], "id") == 0) {
        mira_builtin_printf_locked(shell, "uid=%u gid=%u euid=%u egid=%u\r\n", (unsigned) getuid(), (unsigned) getgid(), (unsigned) geteuid(), (unsigned) getegid());
    } else if (strcmp(argv[0], "whoami") == 0) {
        struct passwd *pw = getpwuid(getuid());
        mira_builtin_printf_locked(shell, "%s\r\n", pw != NULL && pw->pw_name != NULL ? pw->pw_name : "mobile");
    } else if (strcmp(argv[0], "date") == 0) {
        time_t now = time(NULL);
        char text[128];
        struct tm tm_value;
        localtime_r(&now, &tm_value);
        strftime(text, sizeof(text), "%Y-%m-%d %H:%M:%S %z", &tm_value);
        mira_builtin_printf_locked(shell, "%s\r\n", text);
    } else if (strcmp(argv[0], "clear") == 0) {
        mira_builtin_append_cstr_locked(shell, "\033[2J\033[H");
    } else if (strcmp(argv[0], "exit") == 0 || strcmp(argv[0], "logout") == 0) {
        shell->exit_status = argc > 1 ? atoi(argv[1]) : 0;
        shell->closed = 1;
        pthread_cond_broadcast(&shell->cond);
        return;
    } else {
        mira_builtin_printf_locked(shell, "mira: command not found: %s\r\n", argv[0]);
    }

    if (!shell->closed) {
        mira_builtin_prompt_locked(shell);
    }
}

static void mira_builtin_initialize_paths(mira_builtin_shell_t *shell, const mira_shell_options_t *options) {
    const char *home = getenv("HOME");
    if (options != NULL && options->cwd != NULL && options->cwd[0] != '\0') {
        home = options->cwd;
    }
    if (home == NULL || home[0] == '\0') {
        home = "/tmp";
    }
    char resolved[PATH_MAX];
    if (realpath(home, resolved) == NULL) {
        snprintf(resolved, sizeof(resolved), "%s", home);
    }
    snprintf(shell->home, sizeof(shell->home), "%s", resolved);
    snprintf(shell->cwd, sizeof(shell->cwd), "%s", resolved);
}

mira_builtin_shell_t *mira_builtin_shell_open(const mira_shell_options_t *options) {
    mira_builtin_shell_t *shell = (mira_builtin_shell_t *) calloc(1, sizeof(*shell));
    if (shell == NULL) {
        return NULL;
    }
    if (pthread_mutex_init(&shell->mutex, NULL) != 0) {
        free(shell);
        errno = EINVAL;
        return NULL;
    }
    if (pthread_cond_init(&shell->cond, NULL) != 0) {
        pthread_mutex_destroy(&shell->mutex);
        free(shell);
        errno = EINVAL;
        return NULL;
    }
    shell->columns = options != NULL && options->columns > 0 ? options->columns : 80;
    shell->rows = options != NULL && options->rows > 0 ? options->rows : 24;
    mira_builtin_initialize_paths(shell, options);

    pthread_mutex_lock(&shell->mutex);
    mira_builtin_append_cstr_locked(shell, "Mira iOS builtin shell ready. Type `help`.\r\n");
    mira_builtin_prompt_locked(shell);
    pthread_mutex_unlock(&shell->mutex);
    return shell;
}

ssize_t mira_builtin_shell_read(mira_builtin_shell_t *shell, void *buffer, size_t length) {
    if (shell == NULL || buffer == NULL || length == 0) {
        errno = EINVAL;
        return -1;
    }
    pthread_mutex_lock(&shell->mutex);
    while (shell->output_length == 0 && !shell->closed) {
        pthread_cond_wait(&shell->cond, &shell->mutex);
    }
    if (shell->output_length == 0 && shell->closed) {
        pthread_mutex_unlock(&shell->mutex);
        return 0;
    }
    size_t count = shell->output_length < length ? shell->output_length : length;
    memcpy(buffer, shell->output, count);
    memmove(shell->output, shell->output + count, shell->output_length - count);
    shell->output_length -= count;
    if (shell->output != NULL) {
        shell->output[shell->output_length] = '\0';
    }
    pthread_mutex_unlock(&shell->mutex);
    return (ssize_t) count;
}

ssize_t mira_builtin_shell_write(mira_builtin_shell_t *shell, const void *buffer, size_t length) {
    if (shell == NULL || buffer == NULL) {
        errno = EINVAL;
        return -1;
    }
    const unsigned char *bytes = (const unsigned char *) buffer;
    pthread_mutex_lock(&shell->mutex);
    if (shell->closed) {
        pthread_mutex_unlock(&shell->mutex);
        return 0;
    }
    for (size_t i = 0; i < length; ++i) {
        unsigned char ch = bytes[i];
        if (ch == '\r' || ch == '\n') {
            mira_builtin_append_cstr_locked(shell, "\r\n");
            shell->input[shell->input_length] = '\0';
            char line[MIRA_BUILTIN_INPUT_LIMIT + 1U];
            snprintf(line, sizeof(line), "%s", shell->input);
            shell->input_length = 0;
            shell->input[0] = '\0';
            mira_builtin_execute_line(shell, line);
        } else if (ch == 0x7f || ch == '\b') {
            if (shell->input_length > 0) {
                --shell->input_length;
                shell->input[shell->input_length] = '\0';
                mira_builtin_append_cstr_locked(shell, "\b \b");
            }
        } else if (isprint(ch) || ch == '\t') {
            if (shell->input_length + 1U < sizeof(shell->input)) {
                shell->input[shell->input_length++] = (char) ch;
                shell->input[shell->input_length] = '\0';
                mira_builtin_append_locked(shell, (const char *) &ch, 1);
            }
        }
    }
    pthread_mutex_unlock(&shell->mutex);
    return (ssize_t) length;
}

int mira_builtin_shell_resize(mira_builtin_shell_t *shell, int columns, int rows, int cell_width, int cell_height) {
    (void) cell_width;
    (void) cell_height;
    if (shell == NULL) {
        errno = EINVAL;
        return -1;
    }
    pthread_mutex_lock(&shell->mutex);
    if (columns > 0) shell->columns = columns;
    if (rows > 0) shell->rows = rows;
    pthread_mutex_unlock(&shell->mutex);
    return 0;
}

int mira_builtin_shell_wait_for(mira_builtin_shell_t *shell) {
    if (shell == NULL) {
        errno = EINVAL;
        return -EINVAL;
    }
    pthread_mutex_lock(&shell->mutex);
    while (!shell->closed) {
        pthread_cond_wait(&shell->cond, &shell->mutex);
    }
    int status = shell->exit_status;
    pthread_mutex_unlock(&shell->mutex);
    return status;
}

int mira_builtin_shell_close(mira_builtin_shell_t *shell) {
    if (shell == NULL) {
        errno = EINVAL;
        return -1;
    }
    pthread_mutex_lock(&shell->mutex);
    shell->closed = 1;
    pthread_cond_broadcast(&shell->cond);
    pthread_mutex_unlock(&shell->mutex);
    return 0;
}

void mira_builtin_shell_destroy(mira_builtin_shell_t *shell) {
    if (shell == NULL) {
        return;
    }
    pthread_cond_destroy(&shell->cond);
    pthread_mutex_destroy(&shell->mutex);
    free(shell->output);
    free(shell);
}
