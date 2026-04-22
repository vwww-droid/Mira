#pragma once

#include "mira/shell.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct mira_ish_shell mira_ish_shell_t;

int mira_ish_shell_available(void);
const char *mira_ish_shell_last_error(void);

mira_ish_shell_t *mira_ish_shell_open(const mira_shell_options_t *options);
ssize_t mira_ish_shell_read(mira_ish_shell_t *shell, void *buffer, size_t length);
ssize_t mira_ish_shell_write(mira_ish_shell_t *shell, const void *buffer, size_t length);
int mira_ish_shell_resize(mira_ish_shell_t *shell, int columns, int rows, int cell_width, int cell_height);
int mira_ish_shell_wait_for(mira_ish_shell_t *shell);
int mira_ish_shell_close(mira_ish_shell_t *shell);
void mira_ish_shell_destroy(mira_ish_shell_t *shell);

#ifdef __cplusplus
}
#endif
