package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Your own keys (TMDB, Watchmode), kept out of the source code: config.properties when the app is built at home, or,
 * for an app built on GitHub (where the code is public), a salon-config.txt pushed once by ADB into the app's own
 * folder (Android/data/<app>/files; only the app and ADB can write there). It is read once, kept in private storage
 * and deleted.
 */
final class Secrets {
    private Secrets() {}

    private static final String FILE = "salon-config.txt";

    @SuppressWarnings("deprecation")
    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("salon-secrets", Context.MODE_MULTI_PROCESS);
    }

    static String tmdbKey(Context c) {
        String built = Config.TMDB_KEY.trim();
        if (!built.isEmpty() || c == null) return built;
        return prefs(c).getString("tmdb_key", "").trim();
    }

    /** Takes the keys from salon-config.txt if ADB left one (lines key=value), then deletes it. */
    static void importFile(Context c) {
        File dir = c.getExternalFilesDir(null);
        File f = dir == null ? null : new File(dir, FILE);
        if (f == null || !f.exists()) return;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) Math.min(f.length(), 64 * 1024)];
            int n = 0, r;
            while (n < b.length && (r = in.read(b, n, b.length - n)) > 0) n += r;
            SharedPreferences.Editor e = prefs(c).edit();
            for (String line : new String(b, 0, n, StandardCharsets.UTF_8).split("\n")) {
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String k = line.substring(0, eq).trim().toLowerCase(java.util.Locale.ROOT), v = line.substring(eq + 1).trim();
                if (k.equals("tmdb_key") && !v.isEmpty()) e.putString("tmdb_key", v);
            }
            e.commit();
            AppLog.w("keys imported from " + FILE);
        } catch (IOException e) {
            AppLog.w("keys file unreadable: " + e.getMessage());
        } finally {
            if (!f.delete()) AppLog.w("couldn't delete " + FILE);
        }
    }
}
