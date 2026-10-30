package io.freemytv;

import android.app.Application;
import android.os.Build;

/**
 * Gives Prefs a context in every process; in the home screen's (:home), takes your keys and starts the iPhone remote's
 * server. Standalone it is the Application; inside Lumen, LumenApp calls App.init().
 */
public class App extends Application {
    @Override public void onCreate() {
        super.onCreate();
        init(this);
    }

    public static void init(Application a) {
        Prefs.init(a);
        if (isHome()) {
            Secrets.importFile(a);
            Remote.start(a);
        }
    }

    /** True in Salon's own processes (:home, :keys), where Lumen's player setup isn't needed. */
    public static boolean isSalonProcess() {
        String name = processName();
        return name.endsWith(":home") || name.endsWith(":keys");
    }

    private static boolean isHome() {
        return processName().endsWith(":home");
    }

    private static String processName() {
        if (Build.VERSION.SDK_INT >= 28) return Named.name();
        try (java.io.FileInputStream in = new java.io.FileInputStream("/proc/self/cmdline")) {
            byte[] b = new byte[256];
            int n = in.read(b);
            int end = 0;
            while (end < n && b[end] != 0) end++;
            return new String(b, 0, end, java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            return "";
        }
    }

    /** Only this class touches the Android 9 call (App itself still verifies on Android 8). */
    private static final class Named {
        static String name() {
            return Application.getProcessName();
        }
    }
}
