package com.vwww.mira.command;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

final class CommandDispatcher {
    static CommandResult next = CommandResult.ok("");
    static RuntimeException failure;
    static String lastTool;
    static List<String> lastArgv;

    private CommandDispatcher() {}

    static CommandResult dispatch(Context context, String tool, List<String> argv) {
        lastTool = tool;
        lastArgv = argv == null ? null : new ArrayList<>(argv);
        if (failure != null) {
            RuntimeException current = failure;
            failure = null;
            throw current;
        }
        return next;
    }
}
