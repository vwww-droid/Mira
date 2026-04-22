#pragma once

#include <stddef.h>
#include <sys/types.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef enum mira_shell_backend {
    MIRA_SHELL_BACKEND_AUTO = 0,
    MIRA_SHELL_BACKEND_POSIX_PTY = 1,
    MIRA_SHELL_BACKEND_ISH = 2,
    MIRA_SHELL_BACKEND_BUILTIN = 3
} mira_shell_backend_t;

typedef struct mira_shell_session mira_shell_session_t;

typedef struct mira_shell_options {
    mira_shell_backend_t backend;
    const char *shell_path;
    const char *cwd;
    char *const *argv;
    char *const *envp;
    int rows;
    int columns;
    int cell_width;
    int cell_height;
} mira_shell_options_t;

/*
 * Mira shell(命令解释器) 抽象层。
 *
 * 目标:
 * - Android(安卓系统) 继续复用真实 POSIX PTY(伪终端) backend(后端)。
 * - iOS Simulator(iOS 模拟器) 可以暂时复用 POSIX PTY backend 做验证。
 * - 普通 iOS App(应用) 后续接 iSH backend, 对 relay(中继) 协议保持同一组字节流 API(C 语言接口)。
 */
mira_shell_session_t *mira_shell_open(const mira_shell_options_t *options);
ssize_t mira_shell_read(mira_shell_session_t *session, void *buffer, size_t length);
ssize_t mira_shell_write(mira_shell_session_t *session, const void *buffer, size_t length);
int mira_shell_resize(mira_shell_session_t *session, int columns, int rows, int cell_width, int cell_height);
int mira_shell_wait_for(mira_shell_session_t *session);
int mira_shell_close(mira_shell_session_t *session);

const char *mira_shell_backend_name(const mira_shell_session_t *session);
int mira_shell_backend_available(mira_shell_backend_t backend);
const char *mira_shell_last_error(void);

#ifdef __cplusplus
}
#endif
