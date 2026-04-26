#include "frida-core.h"

#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

typedef struct {
  GMainLoop * loop;
  gboolean had_error;
  gboolean shutting_down;
} MiraFridaRunContext;

static GMainLoop * g_active_loop = NULL;

static void print_usage(const char * program_name);
static gboolean parse_common_option(int argc, char ** argv, int * index, const char ** address);
static gboolean parse_timeout_option(int argc, char ** argv, int * index, guint * timeout_ms);
static FridaDevice * open_remote_device(const char * address, FridaDeviceManager ** manager_out, GError ** error);
static guint resolve_target_pid(FridaDevice * device, GError ** error);
static int command_probe(const char * address);
static int command_eval(const char * address, const char * source, guint timeout_ms);
static void on_session_detached(FridaSession * session, FridaSessionDetachReason reason, FridaCrash * crash, gpointer user_data);
static void on_script_message(FridaScript * script, const gchar * message, GBytes * data, gpointer user_data);
static void on_signal(int signo);
static gboolean stop_loop(gpointer user_data);

int
main(int argc, char * argv[])
{
  const char * address = "127.0.0.1:27042";

  if (argc < 2) {
    print_usage(argv[0]);
    return 1;
  }

  frida_init();

  if (strcmp(argv[1], "probe") == 0) {
    int i;
    for (i = 2; i < argc; i++) {
      if (!parse_common_option(argc, argv, &i, &address)) {
        print_usage(argv[0]);
        return 1;
      }
    }
    return command_probe(address);
  }

  if (strcmp(argv[1], "version") == 0) {
    static const char * version_script =
        "send({"
        "frida: Frida.version,"
        "pid: Process.id,"
        "arch: Process.arch,"
        "platform: Process.platform"
        "});";
    guint timeout_ms = 3000;
    int i;
    for (i = 2; i < argc; i++) {
      if (parse_common_option(argc, argv, &i, &address))
        continue;
      if (parse_timeout_option(argc, argv, &i, &timeout_ms))
        continue;
      print_usage(argv[0]);
      return 1;
    }
    return command_eval(address, version_script, timeout_ms);
  }

  if (strcmp(argv[1], "eval") == 0) {
    guint timeout_ms = 3000;
    const char * source = NULL;
    int i;
    for (i = 2; i < argc; i++) {
      if (parse_common_option(argc, argv, &i, &address))
        continue;
      if (parse_timeout_option(argc, argv, &i, &timeout_ms))
        continue;
      source = argv[i];
      break;
    }
    if (source == NULL || *source == '\0') {
      g_printerr("frida eval: missing JavaScript source\n");
      print_usage(argv[0]);
      return 1;
    }
    return command_eval(address, source, timeout_ms);
  }

  print_usage(argv[0]);
  return 1;
}

static void
print_usage(const char * program_name)
{
  g_printerr(
      "Usage:\n"
      "  %s probe [--address HOST:PORT]\n"
      "  %s version [--address HOST:PORT] [--timeout MS]\n"
      "  %s eval [--address HOST:PORT] [--timeout MS] <javascript>\n",
      program_name, program_name, program_name);
}

static gboolean
parse_common_option(int argc, char ** argv, int * index, const char ** address)
{
  if (strcmp(argv[*index], "--address") == 0) {
    if (*index + 1 >= argc) {
      g_printerr("--address requires a value\n");
      return FALSE;
    }
    *address = argv[++(*index)];
    return TRUE;
  }
  return FALSE;
}

static gboolean
parse_timeout_option(int argc, char ** argv, int * index, guint * timeout_ms)
{
  if (strcmp(argv[*index], "--timeout") == 0) {
    gchar * end = NULL;
    unsigned long value;
    if (*index + 1 >= argc) {
      g_printerr("--timeout requires a value\n");
      return FALSE;
    }
    value = strtoul(argv[++(*index)], &end, 10);
    if (end == NULL || *end != '\0' || value == 0) {
      g_printerr("invalid timeout: %s\n", argv[*index]);
      return FALSE;
    }
    *timeout_ms = (guint) value;
    return TRUE;
  }
  return FALSE;
}

static FridaDevice *
open_remote_device(const char * address, FridaDeviceManager ** manager_out, GError ** error)
{
  FridaDeviceManager * manager;
  FridaRemoteDeviceOptions * options;
  FridaDevice * device;

  manager = frida_device_manager_new();
  options = frida_remote_device_options_new();
  frida_remote_device_options_set_keepalive_interval(options, 0);

  device = frida_device_manager_add_remote_device_sync(manager, address, options, NULL, error);

  g_object_unref(options);

  if (device == NULL) {
    frida_unref(manager);
    return NULL;
  }

  *manager_out = manager;
  return device;
}

static guint
resolve_target_pid(FridaDevice * device, GError ** error)
{
  FridaProcessList * processes;
  gint count;
  guint pid = 0;

  processes = frida_device_enumerate_processes_sync(device, NULL, NULL, error);
  if (processes == NULL)
    return 0;

  count = frida_process_list_size(processes);
  if (count > 0) {
    FridaProcess * process = frida_process_list_get(processes, 0);
    pid = frida_process_get_pid(process);
    g_object_unref(process);
  } else {
    g_set_error(error, FRIDA_ERROR, FRIDA_ERROR_PROCESS_NOT_FOUND, "remote Gadget reported no processes");
  }

  frida_unref(processes);
  return pid;
}

static int
command_probe(const char * address)
{
  FridaDeviceManager * manager = NULL;
  FridaDevice * device = NULL;
  FridaProcessList * processes = NULL;
  GError * error = NULL;
  gint count, i;
  int exit_code = 0;

  device = open_remote_device(address, &manager, &error);
  if (device == NULL)
    goto failure;

  g_print("address: %s\n", address);
  g_print("device: %s\n", frida_device_get_name(device));
  g_print("dtype: %d\n", frida_device_get_dtype(device));

  processes = frida_device_enumerate_processes_sync(device, NULL, NULL, &error);
  if (processes == NULL)
    goto failure;

  count = frida_process_list_size(processes);
  g_print("process_count: %d\n", count);
  for (i = 0; i < count; i++) {
    FridaProcess * process = frida_process_list_get(processes, i);
    g_print("  pid=%u name=%s\n", frida_process_get_pid(process), frida_process_get_name(process));
    g_object_unref(process);
  }

  goto cleanup;

failure:
  exit_code = 1;
  g_printerr("probe failed: %s\n", error != NULL ? error->message : "unknown error");

cleanup:
  if (error != NULL)
    g_error_free(error);
  if (processes != NULL)
    frida_unref(processes);
  if (device != NULL)
    frida_unref(device);
  if (manager != NULL) {
    frida_device_manager_close_sync(manager, NULL, NULL);
    frida_unref(manager);
  }
  return exit_code;
}

static int
command_eval(const char * address, const char * source, guint timeout_ms)
{
  FridaDeviceManager * manager = NULL;
  FridaDevice * device = NULL;
  FridaSession * session = NULL;
  FridaScript * script = NULL;
  FridaScriptOptions * options = NULL;
  MiraFridaRunContext context;
  GError * error = NULL;
  guint pid;
  int exit_code = 0;

  memset(&context, 0, sizeof(context));

  device = open_remote_device(address, &manager, &error);
  if (device == NULL)
    goto failure;

  pid = resolve_target_pid(device, &error);
  if (pid == 0)
    goto failure;

  session = frida_device_attach_sync(device, pid, NULL, NULL, &error);
  if (session == NULL)
    goto failure;

  g_signal_connect(session, "detached", G_CALLBACK(on_session_detached), &context);

  options = frida_script_options_new();
  frida_script_options_set_name(options, "mira-frida-cli");
  frida_script_options_set_runtime(options, FRIDA_SCRIPT_RUNTIME_DEFAULT);

  script = frida_session_create_script_sync(session, source, options, NULL, &error);
  if (script == NULL)
    goto failure;

  g_signal_connect(script, "message", G_CALLBACK(on_script_message), &context);

  frida_script_load_sync(script, NULL, &error);
  if (error != NULL)
    goto failure;

  context.loop = g_main_loop_new(NULL, FALSE);
  g_active_loop = context.loop;

  signal(SIGINT, on_signal);
  signal(SIGTERM, on_signal);

  g_timeout_add(timeout_ms, stop_loop, &context);
  g_main_loop_run(context.loop);

  goto cleanup;

failure:
  exit_code = 1;
  g_printerr("eval failed: %s\n", error != NULL ? error->message : "unknown error");

cleanup:
  context.shutting_down = TRUE;
  if (script != NULL) {
    g_signal_handlers_disconnect_by_func(script, G_CALLBACK(on_script_message), &context);
    frida_script_unload_sync(script, NULL, NULL);
    frida_unref(script);
  }
  if (options != NULL)
    g_object_unref(options);
  if (session != NULL) {
    g_signal_handlers_disconnect_by_func(session, G_CALLBACK(on_session_detached), &context);
    frida_session_detach_sync(session, NULL, NULL);
    frida_unref(session);
  }
  if (g_active_loop == context.loop)
    g_active_loop = NULL;
  if (context.loop != NULL)
    g_main_loop_unref(context.loop);
  if (device != NULL)
    frida_unref(device);
  if (manager != NULL) {
    frida_device_manager_close_sync(manager, NULL, NULL);
    frida_unref(manager);
  }
  if (error != NULL)
    g_error_free(error);
  if (context.had_error)
    return 1;
  return exit_code;
}

static void
on_session_detached(FridaSession * session,
                    FridaSessionDetachReason reason,
                    FridaCrash * crash,
                    gpointer user_data)
{
  MiraFridaRunContext * context = user_data;
  if (context != NULL && context->shutting_down)
    return;
  gchar * reason_str = g_enum_to_string(FRIDA_TYPE_SESSION_DETACH_REASON, reason);
  g_printerr("session detached: reason=%s crash=%p\n", reason_str, crash);
  g_free(reason_str);
  if (context != NULL)
    context->had_error = TRUE;
  g_idle_add(stop_loop, context);
}

static void
on_script_message(FridaScript * script,
                  const gchar * message,
                  GBytes * data,
                  gpointer user_data)
{
  MiraFridaRunContext * context = user_data;
  JsonParser * parser;
  JsonObject * root;
  const gchar * type;

  parser = json_parser_new();
  if (!json_parser_load_from_data(parser, message, -1, NULL)) {
    g_printerr("%s\n", message);
    g_object_unref(parser);
    return;
  }

  root = json_node_get_object(json_parser_get_root(parser));
  type = json_object_get_string_member(root, "type");

  if (strcmp(type, "log") == 0) {
    g_print("%s\n", json_object_get_string_member(root, "payload"));
  } else if (strcmp(type, "send") == 0 && json_object_has_member(root, "payload")) {
    JsonNode * payload = json_object_get_member(root, "payload");
    if (JSON_NODE_HOLDS_VALUE(payload) && json_node_get_value_type(payload) == G_TYPE_STRING) {
      g_print("%s\n", json_node_get_string(payload));
    } else {
      gchar * payload_str = json_to_string(payload, FALSE);
      g_print("%s\n", payload_str);
      g_free(payload_str);
    }
  } else if (strcmp(type, "error") == 0) {
    context->had_error = TRUE;
    g_printerr("%s\n", message);
  } else {
    g_print("%s\n", message);
  }

  g_object_unref(parser);
}

static void
on_signal(int signo)
{
  if (g_active_loop != NULL)
    g_idle_add(stop_loop, NULL);
}

static gboolean
stop_loop(gpointer user_data)
{
  MiraFridaRunContext * context = user_data;
  GMainLoop * loop = context != NULL ? context->loop : g_active_loop;
  if (loop != NULL && g_main_loop_is_running(loop))
    g_main_loop_quit(loop);
  return G_SOURCE_REMOVE;
}
