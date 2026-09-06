package com.vwww.mira.runtime;

import java.io.File;

final class RuntimeScripts {
    static final String MANAGED_MARKER = "# Managed by MiraBootstrap";

    private final File prefixDir;
    private final File homeDir;
    private final File tmpDir;

    RuntimeScripts(File prefixDir, File homeDir, File tmpDir) {
        this.prefixDir = prefixDir;
        this.homeDir = homeDir;
        this.tmpDir = tmpDir;
    }

    String shellWrapper() {
        String prefix = quote(prefixDir.getAbsolutePath());
        String home = quote(homeDir.getAbsolutePath());
        String tmp = quote(tmpDir.getAbsolutePath());
        return "#!/system/bin/sh\n" +
            MANAGED_MARKER + "\n" +
            "export PREFIX=" + prefix + "\n" +
            "export TERMUX_PREFIX=\"$PREFIX\"\n" +
            "export HOME=" + home + "\n" +
            "export TERMUX_HOME=\"$HOME\"\n" +
            "export TMPDIR=" + tmp + "\n" +
            "export LD_LIBRARY_PATH=\"$PREFIX/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}\"\n" +
            "MIRA_BASE_PATH=\"$PREFIX/bin:/system/bin:/system/xbin\"\n" +
            "if [ -n \"$MIRA_PATH_PREFIX\" ]; then\n" +
            "  export PATH=\"$MIRA_PATH_PREFIX:$MIRA_BASE_PATH\"\n" +
            "else\n" +
            "  export PATH=\"$MIRA_BASE_PATH\"\n" +
            "fi\n" +
            "export TERM=\"${TERM:-xterm-256color}\"\n" +
            "export COLORTERM=\"${COLORTERM:-truecolor}\"\n" +
            "export MIRA_SANDBOX=1\n" +
            "export MIRA_PREFIX=\"$PREFIX\"\n" +
            "cd \"$HOME\" 2>/dev/null || cd \"$TMPDIR\" 2>/dev/null || cd /\n" +
            "export PWD=\"$(pwd)\"\n" +
            "export SHELL=\"$PREFIX/bin/sh\"\n" +
            "export ENV=\"$HOME/.profile\"\n" +
            "exec /system/bin/sh \"$@\"\n";
    }

    String profileScript() {
        return MANAGED_MARKER + "\n" +
            "# Mira minimal profile\n" +
            "if [ -d \"$PREFIX/etc/profile.d\" ]; then\n" +
            "  for mira_profile in \"$PREFIX\"/etc/profile.d/*.sh; do\n" +
            "    [ -f \"$mira_profile\" ] && . \"$mira_profile\"\n" +
            "  done\n" +
            "fi\n";
    }

    String profileHookScript() {
        return MANAGED_MARKER + "\n" +
            "MIRA_BASE_PATH=\"$PREFIX/bin:/system/bin:/system/xbin\"\n" +
            "if [ -n \"$MIRA_PATH_PREFIX\" ]; then\n" +
            "  export PATH=\"$MIRA_PATH_PREFIX:$MIRA_BASE_PATH\"\n" +
            "else\n" +
            "  export PATH=\"$MIRA_BASE_PATH\"\n" +
            "fi\n" +
            "export TERMUX_PREFIX=\"$PREFIX\"\n" +
            "export TERMUX_HOME=\"$HOME\"\n" +
            "export LD_LIBRARY_PATH=\"$PREFIX/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}\"\n" +
            "export MIRA_SANDBOX=1\n";
    }

    String homeProfileScript() {
        return MANAGED_MARKER + "\n" +
            "# Mira shell profile\n" +
            "[ -n \"$PREFIX\" ] && [ -f \"$PREFIX/etc/profile\" ] && . \"$PREFIX/etc/profile\"\n" +
            "cd \"$HOME\" 2>/dev/null || cd \"$TMPDIR\" 2>/dev/null || cd /\n" +
            "export PWD=\"$(pwd)\"\n" +
            "mira_ls() {\n" +
            "  if [ -n \"$MIRA_BUSYBOX\" ] && [ -x \"$MIRA_BUSYBOX\" ]; then \"$MIRA_BUSYBOX\" ls \"$@\"; else command ls \"$@\"; fi\n" +
            "}\n" +
            "mira_path() { printf '%s\\n' \"$PATH\" | tr ':' '\\n'; }\n" +
            "mira_tools() { command -v sh ls cat grep sed awk ps top logcat python3 frida mira-info mira-logcat mira-getprop mira-dumpsys mira-settings 2>/dev/null; }\n" +
            "alias am='mira-am'\n" +
            "alias ls='mira_ls'\n" +
            "alias l='mira_ls -CF'\n" +
            "alias ll='mira_ls -alF'\n" +
            "alias la='mira_ls -A'\n" +
            "alias ..='cd ..'\n" +
            "alias ...='cd ../..'\n" +
            "alias up='cd ..'\n" +
            "alias c='clear'\n" +
            "alias cls='clear'\n" +
            "alias md='mkdir -p'\n" +
            "alias p='cd -'\n" +
            "alias t='cd \"$TMPDIR\"'\n" +
            "alias py='python3'\n" +
            "alias path='mira_path'\n" +
            "alias tools='mira_tools'\n" +
            "alias props='mira-getprop'\n" +
            "alias logs='mira-logcat'\n" +
            "export PS1='$PWD $ '\n";
    }

    String miraInfoScript() {
        return "#!/system/bin/sh\n" +
            MANAGED_MARKER + "\n" +
            "echo \"Mira sandbox\"\n" +
            "echo \"PREFIX=$PREFIX\"\n" +
            "echo \"HOME=$HOME\"\n" +
            "echo \"TMPDIR=$TMPDIR\"\n" +
            "echo \"SHELL=$SHELL\"\n";
    }

    String fridaWrapperScript() {
        return "#!/system/bin/sh\n" +
            MANAGED_MARKER + "\n" +
            "if [ -n \"$PREFIX\" ] && [ -x \"$PREFIX/bin/frida-official\" ] && [ -x \"$PREFIX/bin/python3\" ]; then\n" +
            "  export LD_LIBRARY_PATH=\"$PREFIX/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}\"\n" +
            "  mira_needs_default_target=1\n" +
            "  for mira_arg in \"$@\"; do\n" +
            "    case \"$mira_arg\" in\n" +
            "      --status)\n" +
            "        exec \"$PREFIX/bin/python3\" -c \"import frida, json; dev=frida.get_device_manager().add_remote_device('127.0.0.1:27042'); ps=dev.enumerate_processes(); first=ps[0] if ps else None; print(json.dumps({'frida': frida.__version__, 'connected': True, 'processCount': len(ps), 'pid': getattr(first, 'pid', None), 'target': getattr(first, 'name', None)}, separators=(',', ':')))\"\n" +
            "        ;;\n" +
            "      -h|--help|--version)\n" +
            "        mira_needs_default_target=0\n" +
            "        ;;\n" +
            "      -D|--device|-U|--usb|-R|--remote|-H|--host|-f|--file|-F|--attach-frontmost|-n|--attach-name|-N|--attach-identifier|-p|--attach-pid|-W|--await)\n" +
            "        mira_needs_default_target=0\n" +
            "        ;;\n" +
            "    esac\n" +
            "  done\n" +
            "  if [ \"$mira_needs_default_target\" = \"1\" ]; then\n" +
            "    exec \"$PREFIX/bin/frida-official\" -H 127.0.0.1 -n Gadget \"$@\"\n" +
            "  fi\n" +
            "  exec \"$PREFIX/bin/frida-official\" \"$@\"\n" +
            "fi\n" +
            "echo \"frida: official runtime is not available\" >&2\n" +
            "exit 127\n";
    }

    String miraCommandScript(String command) {
        return "#!/system/bin/sh\n" +
            "if [ -z \"$MIRA_COMMAND_SOCKET\" ]; then\n" +
            "  echo \"" + command + ": MIRA_COMMAND_SOCKET is not set\" >&2\n" +
            "  exit 1\n" +
            "fi\n" +
            "mira_b64() {\n" +
            "  if [ -n \"$MIRA_BUSYBOX\" ] && [ -x \"$MIRA_BUSYBOX\" ]; then \"$MIRA_BUSYBOX\" base64 \"$@\"; else /system/bin/toybox base64 \"$@\"; fi\n" +
            "}\n" +
            "request=\"MIRA/1 " + command + "\"\n" +
            "for arg in \"$@\"; do\n" +
            "  encoded=$(printf '%s' \"$arg\" | mira_b64 | tr -d '\\n')\n" +
            "  request=\"$request $encoded\"\n" +
            "done\n" +
            "response=$(printf '%s\\n' \"$request\" | /system/bin/toybox nc -U -w 10 \"$MIRA_COMMAND_SOCKET\")\n" +
            "status=$?\n" +
            "if [ \"$status\" -ne 0 ]; then\n" +
            "  echo \"" + command + ": command bridge unavailable\" >&2\n" +
            "  exit \"$status\"\n" +
            "fi\n" +
            "exit_code=$(printf '%s\\n' \"$response\" | sed -n 's/^MIRA\\/1 EXIT //p' | head -1)\n" +
            "stdout_b64=$(printf '%s\\n' \"$response\" | sed -n 's/^STDOUT //p' | head -1)\n" +
            "stderr_b64=$(printf '%s\\n' \"$response\" | sed -n 's/^STDERR //p' | head -1)\n" +
            "[ -n \"$stdout_b64\" ] && printf '%s' \"$stdout_b64\" | mira_b64 -d\n" +
            "[ -n \"$stderr_b64\" ] && printf '%s' \"$stderr_b64\" | mira_b64 -d >&2\n" +
            "case \"$exit_code\" in ''|*[!0-9]*) exit 1 ;; *) exit \"$exit_code\" ;; esac\n";
    }

    private String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
