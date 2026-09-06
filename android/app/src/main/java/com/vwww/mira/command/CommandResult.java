package com.vwww.mira.command;

final class CommandResult {
    final int exitCode;
    final String stdout;
    final String stderr;

    CommandResult(int exitCode, String stdout, String stderr) {
        this.exitCode = sanitizeExitCode(exitCode);
        this.stdout = stdout == null ? "" : stdout;
        this.stderr = stderr == null ? "" : stderr;
    }

    static CommandResult ok(String stdout) {
        return new CommandResult(0, stdout, "");
    }

    static CommandResult error(String stderr) {
        return new CommandResult(1, "", stderr == null ? "" : stderr);
    }

    private static int sanitizeExitCode(int exitCode) {
        if (exitCode < 0 || exitCode > 255) return 1;
        return exitCode;
    }
}
