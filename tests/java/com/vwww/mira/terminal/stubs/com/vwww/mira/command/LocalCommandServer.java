package com.vwww.mira.command;

import android.content.Context;
import java.io.File;

public final class LocalCommandServer {
    public static File socketFile(Context context) {
        return new File(new File(context.getFilesDir(), "run"), "mira-command.sock");
    }
}
