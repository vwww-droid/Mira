#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#ifndef _XOPEN_SOURCE
#define _XOPEN_SOURCE 600
#endif

#include "mira/pty.h"
#include "posix/pty_posix_platform.h"
#include "pty/pty_platform.h"
#include "pty/pty_trace.h"

#include <errno.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

static unsigned short mira_pty_clamp_size(int value, unsigned short fallback) {
    if (value <= 0) {
        return fallback;
    }
    if (value > 65535) {
        return 65535;
    }
    return (unsigned short) value;
}

static unsigned short mira_pty_pixel_size(int cells, int cell_size) {
    if (cells <= 0 || cell_size <= 0) {
        return 0;
    }
    long pixels = (long) cells * (long) cell_size;
    if (pixels > 65535L) {
        return 65535;
    }
    return (unsigned short) pixels;
}

static int mira_pty_set_window_size(int fd, int rows, int columns, int cell_width, int cell_height) {
    struct winsize size;
    memset(&size, 0, sizeof(size));
    size.ws_row = mira_pty_clamp_size(rows, 1);
    size.ws_col = mira_pty_clamp_size(columns, 1);
    size.ws_xpixel = mira_pty_pixel_size(columns, cell_width);
    size.ws_ypixel = mira_pty_pixel_size(rows, cell_height);
    return ioctl(fd, TIOCSWINSZ, &size);
}

static int mira_pty_set_utf8_mode_fd(int fd) {
#ifdef IUTF8
    struct termios tios;
    if (tcgetattr(fd, &tios) != 0) {
        return -1;
    }
    if ((tios.c_iflag & IUTF8) == 0) {
        tios.c_iflag |= IUTF8;
        return tcsetattr(fd, TCSANOW, &tios);
    }
#else
    (void) fd;
#endif
    return 0;
}

static void mira_pty_configure_termios(int fd) {
    struct termios tios;
    if (tcgetattr(fd, &tios) != 0) {
        return;
    }
#ifdef IUTF8
    tios.c_iflag |= IUTF8;
#endif
    tios.c_iflag &= (tcflag_t) ~(IXON | IXOFF);
    (void) tcsetattr(fd, TCSANOW, &tios);
}

static void mira_pty_make_controlling_terminal(int slave_fd) {
    if (slave_fd < 0) {
        return;
    }
#ifdef TIOCSCTTY
    (void) ioctl(slave_fd, TIOCSCTTY, 0);
#endif
    (void) tcsetpgrp(slave_fd, getpgrp());
}

static void mira_pty_clear_environment(void) {
    extern char **environ;
#if !defined(__APPLE__)
    if (clearenv() == 0) {
        return;
    }
#endif
    if (environ != NULL) {
        size_t count = 0;
        for (char **cursor = environ; *cursor != NULL; ++cursor) {
            ++count;
        }

        char **names = (char **) calloc(count, sizeof(char *));
        if (names != NULL) {
            size_t name_count = 0;
            for (char **cursor = environ; *cursor != NULL; ++cursor) {
                char *equals = strchr(*cursor, '=');
                if (equals == NULL) {
                    continue;
                }
                size_t name_length = (size_t) (equals - *cursor);
                names[name_count] = (char *) malloc(name_length + 1U);
                if (names[name_count] == NULL) {
                    continue;
                }
                memcpy(names[name_count], *cursor, name_length);
                names[name_count][name_length] = '\0';
                ++name_count;
            }
            for (size_t i = 0; i < name_count; ++i) {
                if (names[i] != NULL) {
                    unsetenv(names[i]);
                    free(names[i]);
                }
            }
            free(names);
        }
    }
}

static void mira_pty_apply_environment(char *const envp[]) {
    mira_pty_clear_environment();
    if (envp == NULL) {
        return;
    }
    for (char *const *cursor = envp; *cursor != NULL; ++cursor) {
        putenv(*cursor);
    }
}

/* Spawn a PTY child while keeping parent-side descriptor ownership explicit. */
int mira_pty_platform_spawn(const char *shell_path,
                            const char *cwd,
                            char *const argv[],
                            char *const envp[],
                            int rows,
                            int columns,
                            int cell_width,
                            int cell_height,
                            int *master_fd,
                            pid_t *pid) {
    MIRA_PTY_LOGI("spawn enter shell=%s cwd=%s rows=%d cols=%d cell=%dx%d argv0=%s",
                  shell_path == NULL ? "(null)" : shell_path,
                  cwd == NULL ? "(null)" : cwd,
                  rows,
                  columns,
                  cell_width,
                  cell_height,
                  (argv != NULL && argv[0] != NULL) ? argv[0] : "(null)");
    if (master_fd == NULL || pid == NULL || shell_path == NULL || shell_path[0] == '\0') {
        errno = EINVAL;
        MIRA_PTY_PERROR("spawn invalid args");
        return -1;
    }

    /* Allocate the platform PTY pair before forking so failures stay in parent. */
    mira_pty_platform_pair_t pair;
    if (mira_pty_platform_open_pair(&pair) != 0) {
        MIRA_PTY_PERROR("open_pair");
        return -1;
    }
    MIRA_PTY_LOGI("open_pair returned master_fd=%d slave_name=%s", pair.master_fd, pair.slave_name);

    mira_pty_configure_termios(pair.master_fd);
    MIRA_PTY_LOGI("configure_termios done master_fd=%d", pair.master_fd);

    if (mira_pty_set_window_size(pair.master_fd, rows, columns, cell_width, cell_height) != 0) {
        int saved_errno = errno;
        if (pair.slave_fd >= 0) {
            close(pair.slave_fd);
        }
        close(pair.master_fd);
        errno = saved_errno;
        MIRA_PTY_PERROR("set_window_size");
        return -1;
    }
    MIRA_PTY_LOGI("set_window_size done master_fd=%d", pair.master_fd);

    MIRA_PTY_LOGI("fork begin");
    pid_t child = fork();
    if (child < 0) {
        int saved_errno = errno;
        if (pair.slave_fd >= 0) {
            close(pair.slave_fd);
        }
        close(pair.master_fd);
        errno = saved_errno;
        MIRA_PTY_PERROR("fork");
        return -1;
    }

    /* Parent owns only the master side and returns the child pid to callers. */
    if (child > 0) {
        *master_fd = pair.master_fd;
        *pid = child;
        if (pair.slave_fd >= 0) {
            close(pair.slave_fd);
        }
        MIRA_PTY_LOGI("fork parent return child_pid=%d master_fd=%d", child, pair.master_fd);
        return 0;
    }

    /* Child becomes a session leader and wires the slave side to stdio. */
    MIRA_PTY_LOGI("fork child enter");
    sigset_t signals_to_unblock;
    sigfillset(&signals_to_unblock);
    sigprocmask(SIG_UNBLOCK, &signals_to_unblock, NULL);
    MIRA_PTY_LOGI("child signals unblocked");

    close(pair.master_fd);
    MIRA_PTY_LOGI("child closed master fd");
    if (setsid() < 0) {
        MIRA_PTY_PERROR("setsid");
        _exit(1);
    }
    MIRA_PTY_LOGI("setsid ok");
    int pts = mira_pty_platform_open_slave(&pair);
    if (pts < 0) {
        MIRA_PTY_PERROR("open_slave");
        _exit(1);
    }
    mira_pty_make_controlling_terminal(pts);
    MIRA_PTY_LOGI("controlling terminal ok slave_fd=%d", pts);

    if (dup2(pts, STDIN_FILENO) < 0 || dup2(pts, STDOUT_FILENO) < 0 || dup2(pts, STDERR_FILENO) < 0) {
        MIRA_PTY_PERROR("dup2");
        _exit(1);
    }
    if (pts > STDERR_FILENO) {
        close(pts);
    }
    MIRA_PTY_LOGI("stdio wired");

    mira_pty_platform_close_extra_fds();
    MIRA_PTY_LOGI("child extra fds closed");
    mira_pty_apply_environment(envp);
    MIRA_PTY_LOGI("child environment applied");

    if (cwd != NULL && cwd[0] != '\0' && chdir(cwd) != 0) {
        perror("chdir");
        fflush(stderr);
        MIRA_PTY_PERROR("chdir");
    }
    MIRA_PTY_LOGI("child cwd ready cwd=%s", cwd == NULL ? "(null)" : cwd);

    /* Exec is the final step: every failure after this point exits the child. */
    char *const *exec_argv = argv;
    if (exec_argv == NULL || exec_argv[0] == NULL) {
        char *default_argv[] = { (char *) shell_path, NULL };
        MIRA_PTY_LOGI("execvp default shell=%s", shell_path);
        execvp(shell_path, default_argv);
    } else {
        MIRA_PTY_LOGI("execvp argv0=%s shell=%s", exec_argv[0], shell_path);
        execvp(shell_path, exec_argv);
    }

    perror("execvp");
    fflush(stderr);
    MIRA_PTY_PERROR("execvp");
    _exit(1);
}

ssize_t mira_pty_platform_read(int master_fd, void *buffer, size_t length) {
    if (master_fd < 0) {
        errno = EBADF;
        return -1;
    }
    while (1) {
        ssize_t result = read(master_fd, buffer, length);
        if (result < 0 && errno == EINTR) {
            continue;
        }
        if (result < 0 && errno == EIO) {
            return 0;
        }
        return result;
    }
}

ssize_t mira_pty_platform_write(int master_fd, const void *buffer, size_t length) {
    if (master_fd < 0) {
        errno = EBADF;
        return -1;
    }
    const unsigned char *cursor = (const unsigned char *) buffer;
    size_t remaining = length;
    while (remaining > 0) {
        ssize_t written = write(master_fd, cursor, remaining);
        if (written < 0 && errno == EINTR) {
            continue;
        }
        if (written < 0) {
            return -1;
        }
        if (written == 0) {
            errno = EIO;
            return -1;
        }
        cursor += written;
        remaining -= (size_t) written;
    }
    return (ssize_t) length;
}

int mira_pty_platform_resize(int master_fd, int columns, int rows, int cell_width, int cell_height) {
    if (master_fd < 0) {
        errno = EBADF;
        return -1;
    }
    return mira_pty_set_window_size(master_fd, rows, columns, cell_width, cell_height);
}

int mira_pty_platform_set_utf8_mode(int master_fd) {
    if (master_fd < 0) {
        errno = EBADF;
        return -1;
    }
    return mira_pty_set_utf8_mode_fd(master_fd);
}

int mira_pty_platform_wait_for(pid_t pid, int *status) {
    if (pid <= 0 || status == NULL) {
        errno = EINVAL;
        return -EINVAL;
    }

    while (waitpid(pid, status, 0) < 0) {
        if (errno == EINTR) {
            continue;
        }
        return -errno;
    }
    return 0;
}

int mira_pty_platform_kill(pid_t pid, int signal_number) {
    if (pid == 0) {
        errno = EINVAL;
        return -EINVAL;
    }
    if (kill(pid, signal_number) < 0) {
        if (errno == ESRCH) {
            return 0;
        }
        return -errno;
    }
    return 0;
}

int mira_pty_platform_close(int fd) {
    if (fd < 0) {
        return 0;
    }
    if (close(fd) < 0) {
        if (errno == EBADF) {
            return 0;
        }
        return -errno;
    }
    return 0;
}
