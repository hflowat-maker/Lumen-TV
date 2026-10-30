package io.freemytv;

import android.content.Context;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * The TV's own ADB for the iPhone remote and the headphones button, in the :home process: one connection kept open
 * and one thread, so commands run in the order they came (a swipe's arrows never overtake each other). The key is
 * the app's (already accepted when the home screen was switched on).
 */
final class AdbRunner {
    private static AdbRunner instance;

    private final Context context;
    private final ExecutorService thread = Executors.newSingleThreadExecutor();
    private Adb adb;    // only touched on `thread`

    private AdbRunner(Context c) {
        context = c.getApplicationContext();
    }

    static synchronized AdbRunner get(Context c) {
        if (instance == null) instance = new AdbRunner(c);
        return instance;
    }

    /** Runs a shell command and waits for its output (at most `timeoutMs`). */
    String run(final String command, long timeoutMs) throws IOException {
        Future<String> f = thread.submit(() -> runHere(command));
        try {
            return f.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException) throw (IOException) cause;
            throw new IOException(String.valueOf(cause));
        } catch (java.util.concurrent.TimeoutException e) {
            throw new IOException("ADB: no answer in " + timeoutMs + " ms");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
    }

    /** Queues a command without waiting (errors only go to the log). */
    void post(final String command) {
        thread.execute(() -> {
            try {
                runHere(command);
            } catch (IOException e) {
                AppLog.w("remote command failed: " + e.getMessage());
            }
        });
    }

    private String runHere(String command) throws IOException {
        for (int attempt = 0; ; attempt++) {
            try {
                if (adb == null) adb = Adb.connect(context, 20_000, null);
                return adb.shell(command);
            } catch (IOException e) {
                if (adb != null) adb.close();
                adb = null;
                if (attempt >= 1) throw e;     // the connection may just have gone stale: one fresh try
            }
        }
    }
}
