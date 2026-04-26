#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#ifndef _XOPEN_SOURCE
#define _XOPEN_SOURCE 600
#endif

#include "posix/pty_posix_platform.h"
#include "pty/pty_trace.h"

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

int mira_pty_platform_open_pair(mira_pty_platform_pair_t *pair) {
    MIRA_PTY_LOGI("open_pair enter");
    if (pair == NULL) {
        errno = EINVAL;
        MIRA_PTY_PERROR("open_pair invalid pair");
        return -1;
    }

    memset(pair, 0, sizeof(*pair));
    pair->master_fd = -1;
    pair->slave_fd = -1;

    int ptm = posix_openpt(O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (ptm < 0) {
        MIRA_PTY_PERROR("posix_openpt");
        return -1;
    }
    MIRA_PTY_LOGI("posix_openpt ok master_fd=%d", ptm);
    if (grantpt(ptm) != 0) {
        int saved_errno = errno;
        close(ptm);
        errno = saved_errno;
        MIRA_PTY_PERROR("grantpt");
        return -1;
    }
    MIRA_PTY_LOGI("grantpt ok master_fd=%d", ptm);
    if (unlockpt(ptm) != 0) {
        int saved_errno = errno;
        close(ptm);
        errno = saved_errno;
        MIRA_PTY_PERROR("unlockpt");
        return -1;
    }
    MIRA_PTY_LOGI("unlockpt ok master_fd=%d", ptm);
    if (ptsname_r(ptm, pair->slave_name, sizeof(pair->slave_name)) != 0) {
        int saved_errno = errno;
        close(ptm);
        errno = saved_errno;
        MIRA_PTY_PERROR("ptsname_r");
        return -1;
    }

    pair->master_fd = ptm;
    MIRA_PTY_LOGI("open_pair ok master_fd=%d slave_name=%s", ptm, pair->slave_name);
    return 0;
}

int mira_pty_platform_open_slave(mira_pty_platform_pair_t *pair) {
    if (pair == NULL || pair->slave_name[0] == '\0') {
        errno = EINVAL;
        MIRA_PTY_PERROR("open_slave invalid pair");
        return -1;
    }
    MIRA_PTY_LOGI("open_slave enter slave_name=%s", pair->slave_name);
    int fd = open(pair->slave_name, O_RDWR);
    if (fd < 0) {
        MIRA_PTY_PERROR("open slave");
    } else {
        MIRA_PTY_LOGI("open_slave ok slave_fd=%d", fd);
    }
    return fd;
}

void mira_pty_platform_close_extra_fds(void) {
    MIRA_PTY_LOGI("close_extra_fds enter");
    DIR *dir = opendir("/proc/self/fd");
    if (dir == NULL) {
        MIRA_PTY_PERROR("opendir /proc/self/fd");
        return;
    }

    int dir_fd = dirfd(dir);
    struct dirent *entry;
    while ((entry = readdir(dir)) != NULL) {
        char *end = NULL;
        long fd = strtol(entry->d_name, &end, 10);
        if (end == entry->d_name || *end != '\0') {
            continue;
        }
        if (fd > 2 && fd != dir_fd) {
            close((int) fd);
        }
    }

    closedir(dir);
    MIRA_PTY_LOGI("close_extra_fds done");
}
