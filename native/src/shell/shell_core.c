#include "mira/shell.h"

#include "mira/pty.h"
#include "shell/builtin_shell.h"
#include "shell/ish_shell.h"

#include <errno.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

#if defined(__APPLE__)
#include <TargetConditionals.h>
#endif

struct mira_shell_session {
    mira_shell_backend_t backend;
    mira_pty_process_t *pty;
    mira_builtin_shell_t *builtin;
#if defined(MIRA_HAS_ISH_BACKEND) && MIRA_HAS_ISH_BACKEND
    mira_ish_shell_t *ish;
#endif
};

static int mira_shell_posix_pty_available(void) {
#if defined(__APPLE__) && defined(TARGET_OS_IPHONE) && TARGET_OS_IPHONE && !TARGET_OS_SIMULATOR
    return 0;
#else
    return 1;
#endif
}

static __thread char g_mira_shell_last_error[512];

static void mira_shell_set_last_error(const char *message) {
    if (message == NULL) {
        g_mira_shell_last_error[0] = '\0';
        return;
    }
    snprintf(g_mira_shell_last_error, sizeof(g_mira_shell_last_error), "%s", message);
}

const char *mira_shell_last_error(void) {
    return g_mira_shell_last_error[0] != '\0' ? g_mira_shell_last_error : NULL;
}

static int mira_shell_ish_available(void) {
#if defined(MIRA_HAS_ISH_BACKEND) && MIRA_HAS_ISH_BACKEND
    return mira_ish_shell_available();
#else
    return 0;
#endif
}

static int mira_shell_builtin_available(void) {
    return 1;
}

static mira_shell_backend_t mira_shell_resolve_backend(mira_shell_backend_t backend) {
    if (backend == MIRA_SHELL_BACKEND_AUTO) {
#if defined(__APPLE__) && defined(TARGET_OS_IPHONE) && TARGET_OS_IPHONE && !TARGET_OS_SIMULATOR
        return MIRA_SHELL_BACKEND_ISH;
#endif
        if (mira_shell_ish_available()) {
            return MIRA_SHELL_BACKEND_ISH;
        }
        if (mira_shell_posix_pty_available()) {
            return MIRA_SHELL_BACKEND_POSIX_PTY;
        }
        return MIRA_SHELL_BACKEND_BUILTIN;
    }
    return backend;
}

int mira_shell_backend_available(mira_shell_backend_t backend) {
    backend = mira_shell_resolve_backend(backend);
    switch (backend) {
        case MIRA_SHELL_BACKEND_POSIX_PTY:
            return mira_shell_posix_pty_available();
        case MIRA_SHELL_BACKEND_ISH:
            return mira_shell_ish_available();
        case MIRA_SHELL_BACKEND_BUILTIN:
            return mira_shell_builtin_available();
        case MIRA_SHELL_BACKEND_AUTO:
        default:
            return 0;
    }
}

mira_shell_session_t *mira_shell_open(const mira_shell_options_t *options) {
    if (options == NULL) {
        errno = EINVAL;
        return NULL;
    }

    mira_shell_backend_t backend = mira_shell_resolve_backend(options->backend);
    if (backend == MIRA_SHELL_BACKEND_BUILTIN) {
        mira_builtin_shell_t *builtin = mira_builtin_shell_open(options);
        if (builtin == NULL) {
            return NULL;
        }
        mira_shell_session_t *session = (mira_shell_session_t *) calloc(1, sizeof(*session));
        if (session == NULL) {
            int saved_errno = errno;
            mira_builtin_shell_close(builtin);
            mira_builtin_shell_destroy(builtin);
            errno = saved_errno;
            return NULL;
        }
        session->backend = backend;
        session->builtin = builtin;
        return session;
    }
    if (backend == MIRA_SHELL_BACKEND_ISH) {
#if defined(MIRA_HAS_ISH_BACKEND) && MIRA_HAS_ISH_BACKEND
        if (!mira_shell_ish_available()) {
            mira_shell_set_last_error("iSH backend is not available in this build");
            errno = ENOSYS;
            return NULL;
        }
        mira_ish_shell_t *ish = mira_ish_shell_open(options);
        if (ish == NULL) {
            mira_shell_set_last_error(mira_ish_shell_last_error());
            return NULL;
        }
        mira_shell_session_t *session = (mira_shell_session_t *) calloc(1, sizeof(*session));
        if (session == NULL) {
            int saved_errno = errno;
            mira_ish_shell_close(ish);
            mira_ish_shell_destroy(ish);
            errno = saved_errno;
            return NULL;
        }
        session->backend = backend;
        session->ish = ish;
        return session;
#else
        mira_shell_set_last_error("iSH backend is not compiled into Mira");
        errno = ENOSYS;
        return NULL;
#endif
    }
    if (backend != MIRA_SHELL_BACKEND_POSIX_PTY) {
        errno = EINVAL;
        return NULL;
    }
    if (!mira_shell_posix_pty_available()) {
        errno = ENOSYS;
        return NULL;
    }

    const char *shell_path = options->shell_path;
    if (shell_path == NULL || shell_path[0] == '\0') {
        shell_path = "/bin/sh";
    }

    mira_pty_process_t *pty = mira_pty_open(
        shell_path,
        options->cwd,
        options->argv,
        options->envp,
        options->rows,
        options->columns,
        options->cell_width,
        options->cell_height
    );
    if (pty == NULL) {
        return NULL;
    }

    mira_shell_session_t *session = (mira_shell_session_t *) calloc(1, sizeof(*session));
    if (session == NULL) {
        int saved_errno = errno;
        (void) mira_pty_close(pty);
        mira_pty_destroy(pty);
        errno = saved_errno;
        return NULL;
    }

    session->backend = backend;
    session->pty = pty;
    return session;
}

ssize_t mira_shell_read(mira_shell_session_t *session, void *buffer, size_t length) {
    if (session == NULL) {
        errno = EINVAL;
        return -1;
    }
    if (session->backend == MIRA_SHELL_BACKEND_BUILTIN) {
        return mira_builtin_shell_read(session->builtin, buffer, length);
    }
#if defined(MIRA_HAS_ISH_BACKEND) && MIRA_HAS_ISH_BACKEND
    if (session->backend == MIRA_SHELL_BACKEND_ISH) {
        return mira_ish_shell_read(session->ish, buffer, length);
    }
#endif
    if (session->pty == NULL) {
        errno = EINVAL;
        return -1;
    }
    return mira_pty_read(session->pty, buffer, length);
}

ssize_t mira_shell_write(mira_shell_session_t *session, const void *buffer, size_t length) {
    if (session == NULL) {
        errno = EINVAL;
        return -1;
    }
    if (session->backend == MIRA_SHELL_BACKEND_BUILTIN) {
        return mira_builtin_shell_write(session->builtin, buffer, length);
    }
#if defined(MIRA_HAS_ISH_BACKEND) && MIRA_HAS_ISH_BACKEND
    if (session->backend == MIRA_SHELL_BACKEND_ISH) {
        return mira_ish_shell_write(session->ish, buffer, length);
    }
#endif
    if (session->pty == NULL) {
        errno = EINVAL;
        return -1;
    }
    return mira_pty_write(session->pty, buffer, length);
}

int mira_shell_resize(mira_shell_session_t *session, int columns, int rows, int cell_width, int cell_height) {
    if (session == NULL) {
        errno = EINVAL;
        return -1;
    }
    if (session->backend == MIRA_SHELL_BACKEND_BUILTIN) {
        return mira_builtin_shell_resize(session->builtin, columns, rows, cell_width, cell_height);
    }
#if defined(MIRA_HAS_ISH_BACKEND) && MIRA_HAS_ISH_BACKEND
    if (session->backend == MIRA_SHELL_BACKEND_ISH) {
        return mira_ish_shell_resize(session->ish, columns, rows, cell_width, cell_height);
    }
#endif
    if (session->pty == NULL) {
        errno = EINVAL;
        return -1;
    }
    return mira_pty_resize(session->pty, columns, rows, cell_width, cell_height);
}

int mira_shell_wait_for(mira_shell_session_t *session) {
    if (session == NULL) {
        errno = EINVAL;
        return -EINVAL;
    }
    if (session->backend == MIRA_SHELL_BACKEND_BUILTIN) {
        return mira_builtin_shell_wait_for(session->builtin);
    }
#if defined(MIRA_HAS_ISH_BACKEND) && MIRA_HAS_ISH_BACKEND
    if (session->backend == MIRA_SHELL_BACKEND_ISH) {
        return mira_ish_shell_wait_for(session->ish);
    }
#endif
    if (session->pty == NULL) {
        errno = EINVAL;
        return -EINVAL;
    }
    return mira_pty_wait_for(session->pty);
}

int mira_shell_close(mira_shell_session_t *session) {
    if (session == NULL) {
        errno = EINVAL;
        return -1;
    }

    int rc = 0;
    if (session->builtin != NULL) {
        rc = mira_builtin_shell_close(session->builtin);
        mira_builtin_shell_destroy(session->builtin);
        session->builtin = NULL;
    }
#if defined(MIRA_HAS_ISH_BACKEND) && MIRA_HAS_ISH_BACKEND
    if (session->ish != NULL) {
        rc = mira_ish_shell_close(session->ish);
        mira_ish_shell_destroy(session->ish);
        session->ish = NULL;
    }
#endif
    if (session->pty != NULL) {
        rc = mira_pty_close(session->pty);
        mira_pty_destroy(session->pty);
        session->pty = NULL;
    }
    free(session);
    return rc;
}

const char *mira_shell_backend_name(const mira_shell_session_t *session) {
    if (session == NULL) {
        return "none";
    }
    switch (session->backend) {
        case MIRA_SHELL_BACKEND_POSIX_PTY:
            return "posix-pty";
        case MIRA_SHELL_BACKEND_ISH:
            return "ish";
        case MIRA_SHELL_BACKEND_BUILTIN:
            return "builtin";
        case MIRA_SHELL_BACKEND_AUTO:
        default:
            return "auto";
    }
}
