#pragma once

#include "mira/pty.h"

#ifdef __cplusplus
extern "C" {
#endif

/*
 * iOS 平台桥接薄层。
 *
 * Swift 通过 bridging header(桥接头文件) 直接使用这里的 C API。
 * 当前保持为 pty.h 的稳定转发入口, 后续 iOS App 接入时只在这里增加
 * Swift 需要的轻量包装, 不把 UIKit 或 WebKit 依赖放进 native core(原生核心层)。
 */
const char *mira_pty_ios_backend_name(void);

typedef void (*mira_ios_screen_input_callback_t)(const char *json, void *context);

int mira_ios_relay_start(const char *relay_url, const char *device_name, const char *home_dir);
int mira_ios_relay_start_with_device_info(
    const char *relay_url,
    const char *device_name,
    const char *home_dir,
    const char *device_model,
    const char *hardware_model,
    const char *os_version
);
void mira_ios_relay_stop(void);
const char *mira_ios_relay_status(void);
const char *mira_ios_relay_install_id(void);
int mira_ios_relay_send_control_json(const char *json);
void mira_ios_relay_set_screen_input_callback(mira_ios_screen_input_callback_t callback, void *context);

int mira_ios_frida_loader_ensure_loaded(void);
const char *mira_ios_frida_loader_status(void);
int mira_ios_frida_loader_is_loaded(void);

#ifdef __cplusplus
}
#endif
