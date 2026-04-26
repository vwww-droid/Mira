package com.vwww.mira;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bridge between Mira command system and frida-gadget (script mode).
 *
 * Gadget is configured with "on_change": "reload", so writing a new JS file
 * to the script path causes gadget to unload the old script and load the new one.
 *
 * Result collection uses file-based IPC: the wrapper script writes a JSON result
 * file that the bridge polls for.
 */
final class MiraFridaBridge {
    private static final String TAG = "MiraFridaBridge";

    /** Where gadget reads the active script (matches libdynamic.config.so path). */
    private static final String GADGET_SCRIPT_PATH = "/data/local/tmp/mira-frida-active.js";

    /** Directory for result files written by frida scripts. */
    private static final String RESULT_DIR = "/data/local/tmp/mira-frida-results";

    /** Directory for audit logs. */
    private static final String AUDIT_DIR = "/data/local/tmp/mira-frida-audit";

    private static final long DEFAULT_TIMEOUT_MS = 30_000L;
    private static final long POLL_INTERVAL_MS = 200L;
    private static final int MAX_AUDIT_FILES = 200;

    private static volatile MiraFridaBridge instance;

    private final Context context;
    private final AtomicBoolean gadgetLoaded = new AtomicBoolean(false);
    private final AtomicInteger executionCount = new AtomicInteger(0);
    private final AtomicInteger activeHookCount = new AtomicInteger(0);

    /** Persistent hook registry: hookId -> JS source fragment. */
    private final Map<String, String> hookRegistry = new LinkedHashMap<>();

    private MiraFridaBridge(Context context) {
        this.context = context.getApplicationContext();
        ensureDirectories();
        detectGadgetLoaded();
        installIdleScript();
    }

    static synchronized MiraFridaBridge getInstance(Context context) {
        MiraApplication.ensureDynamicLibraryLoaded();
        if (instance == null) {
            instance = new MiraFridaBridge(context);
        }
        return instance;
    }

    // ---- Public API ----

    /**
     * Execute arbitrary Frida JS code and return the result.
     */
    MiraCommandResult exec(String jsCode, long timeoutMs) {
        if (!gadgetLoaded.get()) {
            detectGadgetLoaded();
            if (!gadgetLoaded.get()) {
                return MiraCommandResult.error("frida-gadget not loaded (libdynamic.so missing or failed to load)\n");
            }
        }
        if (jsCode == null || jsCode.trim().isEmpty()) {
            return MiraCommandResult.error("frida-exec: empty script\n");
        }
        String execId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String resultPath = RESULT_DIR + "/result-" + execId + ".json";
        String wrappedScript = wrapScript(execId, jsCode, resultPath);

        auditLog(execId, "exec", jsCode);
        executionCount.incrementAndGet();

        try {
            writeFile(GADGET_SCRIPT_PATH, wrappedScript);
        } catch (IOException e) {
            return MiraCommandResult.error("frida-exec: failed to write script: " + e.getMessage() + "\n");
        }

        long effectiveTimeout = timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;
        JSONObject result = pollForResult(resultPath, effectiveTimeout);
        if (result == null) {
            return MiraCommandResult.error("frida-exec: timeout after " + effectiveTimeout + "ms waiting for script result\n");
        }

        // Restore hooks script after one-shot execution
        restoreHooksScript();

        String status = result.optString("status", "unknown");
        StringBuilder stdout = new StringBuilder();
        JSONArray results = result.optJSONArray("results");
        if (results != null) {
            for (int i = 0; i < results.length(); i++) {
                stdout.append(results.optString(i, "")).append("\n");
            }
        }
        JSONArray logs = result.optJSONArray("logs");
        if (logs != null) {
            for (int i = 0; i < logs.length(); i++) {
                stdout.append("[log] ").append(logs.optString(i, "")).append("\n");
            }
        }

        String error = result.optString("error", "");
        if ("error".equals(status)) {
            return new MiraCommandResult(1, stdout.toString(), "frida script error: " + error + "\n");
        }
        return MiraCommandResult.ok(stdout.toString());
    }

    MiraCommandResult exec(String jsCode) {
        return exec(jsCode, DEFAULT_TIMEOUT_MS);
    }

    /**
     * Quick-hook a Java method: prints arguments and return value.
     */
    MiraCommandResult hookJavaMethod(String classAndMethod) {
        if (classAndMethod == null || !classAndMethod.contains(".")) {
            return MiraCommandResult.error("frida-hook: usage: frida-hook <fully.qualified.Class.methodName>\n");
        }
        int lastDot = classAndMethod.lastIndexOf('.');
        String className = classAndMethod.substring(0, lastDot);
        String methodName = classAndMethod.substring(lastDot + 1);

        String hookId = "jhook_" + className.replace('.', '_') + "_" + methodName;
        String hookJs = buildJavaHookScript(className, methodName, hookId);

        synchronized (hookRegistry) {
            hookRegistry.put(hookId, hookJs);
            activeHookCount.set(hookRegistry.size());
        }

        auditLog(hookId, "hook-java", classAndMethod);
        String combinedScript = buildCombinedHookScript();
        try {
            writeFile(GADGET_SCRIPT_PATH, combinedScript);
        } catch (IOException e) {
            return MiraCommandResult.error("frida-hook: failed to write hook script: " + e.getMessage() + "\n");
        }
        return MiraCommandResult.ok("hook installed: " + className + "." + methodName + " (id=" + hookId + ")\n");
    }

    /**
     * Quick-hook a native function in a shared library.
     */
    MiraCommandResult hookNativeFunction(String soName, String funcName) {
        if (soName == null || funcName == null || soName.isEmpty() || funcName.isEmpty()) {
            return MiraCommandResult.error("frida-native-hook: usage: frida-native-hook <libname.so> <function_name>\n");
        }

        String hookId = "nhook_" + soName.replace('.', '_') + "_" + funcName;
        String hookJs = buildNativeHookScript(soName, funcName, hookId);

        synchronized (hookRegistry) {
            hookRegistry.put(hookId, hookJs);
            activeHookCount.set(hookRegistry.size());
        }

        auditLog(hookId, "hook-native", soName + "!" + funcName);
        String combinedScript = buildCombinedHookScript();
        try {
            writeFile(GADGET_SCRIPT_PATH, combinedScript);
        } catch (IOException e) {
            return MiraCommandResult.error("frida-native-hook: failed to write hook script: " + e.getMessage() + "\n");
        }
        return MiraCommandResult.ok("native hook installed: " + soName + "!" + funcName + " (id=" + hookId + ")\n");
    }

    /**
     * Actively call a Java method via Frida.
     */
    MiraCommandResult callJavaMethod(String classAndMethod, List<String> args) {
        if (classAndMethod == null || !classAndMethod.contains(".")) {
            return MiraCommandResult.error("frida-call: usage: frida-call <Class.method> [arg1 arg2 ...]\n");
        }
        int lastDot = classAndMethod.lastIndexOf('.');
        String className = classAndMethod.substring(0, lastDot);
        String methodName = classAndMethod.substring(lastDot + 1);

        StringBuilder argsJs = new StringBuilder("[");
        if (args != null) {
            for (int i = 0; i < args.size(); i++) {
                if (i > 0) argsJs.append(",");
                argsJs.append(escapeJsString(args.get(i)));
            }
        }
        argsJs.append("]");

        String jsCode = "Java.perform(function(){\n" +
            "  var clz = Java.use('" + escapeJsIdentifier(className) + "');\n" +
            "  var args = " + argsJs + ";\n" +
            "  try {\n" +
            "    var result = clz." + escapeJsIdentifier(methodName) + ".apply(clz, args);\n" +
            "    send(JSON.stringify({method:'" + escapeJsIdentifier(className) + "." + escapeJsIdentifier(methodName) + "',result:String(result)}));\n" +
            "  } catch(e) {\n" +
            "    send(JSON.stringify({method:'" + escapeJsIdentifier(className) + "." + escapeJsIdentifier(methodName) + "',error:e.toString()}));\n" +
            "  }\n" +
            "});";
        return exec(jsCode);
    }

    /**
     * Detach all hooks and restore idle script.
     */
    MiraCommandResult detachAll() {
        int count;
        synchronized (hookRegistry) {
            count = hookRegistry.size();
            hookRegistry.clear();
            activeHookCount.set(0);
        }
        installIdleScript();
        auditLog("detach-all", "detach", "removed " + count + " hooks");
        return MiraCommandResult.ok("detached " + count + " hooks, gadget restored to idle\n");
    }

    /**
     * Report gadget status.
     */
    MiraCommandResult status() {
        detectGadgetLoaded();
        StringBuilder sb = new StringBuilder();
        sb.append("gadget_loaded: ").append(gadgetLoaded.get()).append("\n");
        sb.append("gadget_library: libdynamic.so\n");
        sb.append("script_path: ").append(GADGET_SCRIPT_PATH).append("\n");
        sb.append("script_mode: on_change=reload\n");
        sb.append("total_executions: ").append(executionCount.get()).append("\n");

        synchronized (hookRegistry) {
            sb.append("active_hooks: ").append(hookRegistry.size()).append("\n");
            if (!hookRegistry.isEmpty()) {
                sb.append("hook_ids:\n");
                for (String hookId : hookRegistry.keySet()) {
                    sb.append("  - ").append(hookId).append("\n");
                }
            }
        }

        File scriptFile = new File(GADGET_SCRIPT_PATH);
        sb.append("script_file_exists: ").append(scriptFile.exists()).append("\n");
        if (scriptFile.exists()) {
            sb.append("script_file_size: ").append(scriptFile.length()).append(" bytes\n");
            sb.append("script_file_modified: ").append(new Date(scriptFile.lastModified())).append("\n");
        }

        File resultDir = new File(RESULT_DIR);
        if (resultDir.exists()) {
            File[] files = resultDir.listFiles();
            sb.append("pending_results: ").append(files == null ? 0 : files.length).append("\n");
        }

        return MiraCommandResult.ok(sb.toString());
    }

    // ---- Script generation ----

    /**
     * Wrap user JS code in a template that captures send()/console.log output
     * and writes results to a JSON file for the bridge to read.
     */
    private String wrapScript(String execId, String userScript, String resultPath) {
        // Escape paths for JS string literals
        String safeResultPath = resultPath.replace("\\", "\\\\").replace("'", "\\'");

        return "(function(){\n" +
            "  'use strict';\n" +
            "  var __id = '" + execId + "';\n" +
            "  var __results = [];\n" +
            "  var __logs = [];\n" +
            "  var __flushed = false;\n" +
            "\n" +
            "  function __flush(status, error) {\n" +
            "    if (__flushed) return;\n" +
            "    __flushed = true;\n" +
            "    var payload = JSON.stringify({\n" +
            "      id: __id,\n" +
            "      status: status || 'ok',\n" +
            "      error: error || '',\n" +
            "      results: __results,\n" +
            "      logs: __logs,\n" +
            "      ts: Date.now()\n" +
            "    });\n" +
            "    try {\n" +
            "      Java.performNow(function(){\n" +
            "        var FW = Java.use('java.io.FileWriter');\n" +
            "        var f = FW.$new('" + safeResultPath + "');\n" +
            "        f.write(payload);\n" +
            "        f.flush();\n" +
            "        f.close();\n" +
            "      });\n" +
            "    } catch(writeErr) {\n" +
            "      // fallback: try native file write\n" +
            "      var fp = new File('" + safeResultPath + "', 'w');\n" +
            "      if (fp) { fp.write(payload); fp.flush(); fp.close(); }\n" +
            "    }\n" +
            "  }\n" +
            "\n" +
            "  // Override send() to capture results\n" +
            "  var _origSend = typeof send === 'function' ? send : function(){};\n" +
            "  send = function(payload, data) {\n" +
            "    var s = (typeof payload === 'string') ? payload : JSON.stringify(payload);\n" +
            "    __results.push(s);\n" +
            "    _origSend(payload, data);\n" +
            "  };\n" +
            "\n" +
            "  // Override console.log to capture logs\n" +
            "  var _origLog = console.log;\n" +
            "  console.log = function() {\n" +
            "    var msg = Array.prototype.slice.call(arguments).map(String).join(' ');\n" +
            "    __logs.push(msg);\n" +
            "    _origLog.apply(console, arguments);\n" +
            "  };\n" +
            "\n" +
            "  try {\n" +
            "    // ===== USER SCRIPT START =====\n" +
            userScript + "\n" +
            "    // ===== USER SCRIPT END =====\n" +
            "\n" +
            "    // Flush after delay to allow Java.perform callbacks\n" +
            "    setTimeout(function(){ __flush('ok', ''); }, 3000);\n" +
            "  } catch(e) {\n" +
            "    __flush('error', e.toString() + '\\n' + (e.stack || ''));\n" +
            "  }\n" +
            "})();\n";
    }

    private String buildJavaHookScript(String className, String methodName, String hookId) {
        return "// Hook: " + hookId + "\n" +
            "(function(){\n" +
            "  Java.perform(function(){\n" +
            "    try {\n" +
            "      var clz = Java.use('" + escapeJsIdentifier(className) + "');\n" +
            "      var overloads = clz." + escapeJsIdentifier(methodName) + ".overloads;\n" +
            "      overloads.forEach(function(overload){\n" +
            "        overload.implementation = function(){\n" +
            "          var args = Array.prototype.slice.call(arguments);\n" +
            "          var argStr = args.map(function(a){ try{return String(a);}catch(e){return '<err>';} }).join(', ');\n" +
            "          console.log('[hook:" + hookId + "] " + className + "." + methodName + "(' + argStr + ')');\n" +
            "          var ret = this." + escapeJsIdentifier(methodName) + ".apply(this, arguments);\n" +
            "          console.log('[hook:" + hookId + "] => ' + String(ret));\n" +
            "          return ret;\n" +
            "        };\n" +
            "      });\n" +
            "      console.log('[hook:" + hookId + "] installed ' + overloads.length + ' overload(s)');\n" +
            "    } catch(e) {\n" +
            "      console.log('[hook:" + hookId + "] FAILED: ' + e);\n" +
            "    }\n" +
            "  });\n" +
            "})();\n";
    }

    private String buildNativeHookScript(String soName, String funcName, String hookId) {
        return "// Hook: " + hookId + "\n" +
            "(function(){\n" +
            "  var mod = Process.findModuleByName('" + escapeJsIdentifier(soName) + "');\n" +
            "  if (!mod) { console.log('[hook:" + hookId + "] module not found: " + soName + "'); return; }\n" +
            "  var addr = mod.findExportByName('" + escapeJsIdentifier(funcName) + "');\n" +
            "  if (!addr) { console.log('[hook:" + hookId + "] export not found: " + funcName + "'); return; }\n" +
            "  Interceptor.attach(addr, {\n" +
            "    onEnter: function(args) {\n" +
            "      console.log('[hook:" + hookId + "] " + soName + "!" + funcName + " called, arg0=' + args[0] + ' arg1=' + args[1] + ' arg2=' + args[2]);\n" +
            "      this._ts = Date.now();\n" +
            "    },\n" +
            "    onLeave: function(retval) {\n" +
            "      var elapsed = Date.now() - (this._ts || 0);\n" +
            "      console.log('[hook:" + hookId + "] " + soName + "!" + funcName + " returned ' + retval + ' (' + elapsed + 'ms)');\n" +
            "    }\n" +
            "  });\n" +
            "  console.log('[hook:" + hookId + "] native hook installed at ' + addr);\n" +
            "})();\n";
    }

    private String buildCombinedHookScript() {
        StringBuilder sb = new StringBuilder();
        sb.append("// Mira Frida Hook Registry - auto-generated\n");
        sb.append("// Active hooks: ").append(hookRegistry.size()).append("\n");
        sb.append("// Generated: ").append(new Date()).append("\n\n");
        synchronized (hookRegistry) {
            for (Map.Entry<String, String> entry : hookRegistry.entrySet()) {
                sb.append(entry.getValue()).append("\n");
            }
        }
        return sb.toString();
    }

    /** Install a no-op script so gadget doesn't block on missing file. */
    private void installIdleScript() {
        try {
            writeFile(GADGET_SCRIPT_PATH, "// Mira Frida Bridge - idle\nconsole.log('[mira] gadget idle, awaiting commands');\n");
        } catch (IOException e) {
            Log.w(TAG, "Failed to write idle script", e);
        }
    }

    /** Re-install hooks script after a one-shot exec, or idle if no hooks. */
    private void restoreHooksScript() {
        synchronized (hookRegistry) {
            if (hookRegistry.isEmpty()) {
                installIdleScript();
            } else {
                try {
                    writeFile(GADGET_SCRIPT_PATH, buildCombinedHookScript());
                } catch (IOException e) {
                    Log.w(TAG, "Failed to restore hooks script", e);
                }
            }
        }
    }

    // ---- Result polling ----

    private JSONObject pollForResult(String resultPath, long timeoutMs) {
        File resultFile = new File(resultPath);
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        while (SystemClock.uptimeMillis() < deadline) {
            if (resultFile.exists() && resultFile.length() > 0) {
                try {
                    String content = readFile(resultFile);
                    resultFile.delete();
                    return new JSONObject(content);
                } catch (Exception e) {
                    Log.w(TAG, "Failed to read result file", e);
                    return null;
                }
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        // Cleanup on timeout
        resultFile.delete();
        return null;
    }

    // ---- Utilities ----

    private void detectGadgetLoaded() {
        // Check if libdynamic.so appears in /proc/self/maps
        try {
            File maps = new File("/proc/self/maps");
            if (maps.exists()) {
                String content = readFile(maps);
                gadgetLoaded.set(content.contains("libdynamic.so"));
                return;
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to read /proc/self/maps", e);
        }
        // Fallback: check if the script path is writable (gadget may have created it)
        File scriptFile = new File(GADGET_SCRIPT_PATH);
        gadgetLoaded.set(scriptFile.getParentFile() != null && scriptFile.getParentFile().canWrite());
    }

    private void ensureDirectories() {
        new File(RESULT_DIR).mkdirs();
        new File(AUDIT_DIR).mkdirs();
    }

    private void auditLog(String id, String action, String detail) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US);
            String filename = sdf.format(new Date()) + "_" + action + "_" + id + ".log";
            File auditFile = new File(AUDIT_DIR, filename);
            String content = "timestamp: " + new Date() + "\n" +
                "action: " + action + "\n" +
                "id: " + id + "\n" +
                "detail:\n" + detail + "\n";
            writeFile(auditFile.getAbsolutePath(), content);
            pruneAuditLogs();
        } catch (IOException e) {
            Log.w(TAG, "Audit log failed", e);
        }
    }

    private void pruneAuditLogs() {
        File dir = new File(AUDIT_DIR);
        File[] files = dir.listFiles();
        if (files == null || files.length <= MAX_AUDIT_FILES) return;
        // Sort by name (timestamp-based), delete oldest
        java.util.Arrays.sort(files);
        int toDelete = files.length - MAX_AUDIT_FILES;
        for (int i = 0; i < toDelete; i++) {
            files[i].delete();
        }
    }

    private static void writeFile(String path, String content) throws IOException {
        File file = new File(path);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        FileWriter writer = new FileWriter(file);
        try {
            writer.write(content);
            writer.flush();
        } finally {
            writer.close();
        }
    }

    private static String readFile(File file) throws IOException {
        StringBuilder sb = new StringBuilder((int) Math.min(file.length(), 1024 * 1024));
        BufferedReader reader = new BufferedReader(new FileReader(file));
        try {
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                sb.append(buffer, 0, read);
            }
        } finally {
            reader.close();
        }
        return sb.toString();
    }

    private static String escapeJsIdentifier(String s) {
        if (s == null) return "";
        // Allow only safe chars for class/method names
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '$') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String escapeJsString(String s) {
        if (s == null) return "null";
        // Try to parse as number
        try {
            Double.parseDouble(s);
            return s;
        } catch (NumberFormatException ignored) {
        }
        // Try to parse as boolean
        if ("true".equals(s) || "false".equals(s)) return s;
        if ("null".equals(s)) return "null";
        // String literal
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r") + "'";
    }
}
