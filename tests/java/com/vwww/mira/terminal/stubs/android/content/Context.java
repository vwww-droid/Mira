package android.content;

import java.io.File;

public class Context {
    private final File filesDir;
    private final String packageName;

    public Context(File filesDir, String packageName) {
        this.filesDir = filesDir;
        this.packageName = packageName;
    }

    public File getFilesDir() {
        return filesDir;
    }

    public String getPackageName() {
        return packageName;
    }
}
