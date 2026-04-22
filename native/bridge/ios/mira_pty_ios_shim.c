#include "mira_pty_ios_shim.h"

#include "mira/shell.h"

const char *mira_pty_ios_backend_name(void) {
    return mira_shell_backend_available(MIRA_SHELL_BACKEND_ISH) ? "ios-ish" : "ios-native";
}
