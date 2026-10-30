package io.freemytv;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Salon's side of Lumen, the IPTV player in the same app: its catalog (lumen.db, written by Lumen in the main process)
 * and its account, read from here (the :home process), and its player, opened with an intent. Only reads: Lumen stays
 * the one that syncs the playlist and writes.
 */
final class Iptv {
    private Iptv() {}

    /** Lumen's screen; it understands the extras below (salon_open = movie, series or live; salon_id; salon_ids). */
    static final String PLAYER = "app.lumen.tv.MainActivity";

    static final class Channel {
        int id, num;
        String name, icon;
        // What's on now (EPG), when known
        String now;
        long start, end;
    }

    static final class Match {
        String type;    // "movie" or "series"
        int id;
    }

    /** Lumen's settings file (its account, active profile, last sync), re-read when Lumen changed it. */
    @SuppressWarnings("deprecation")
    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("lumen", Context.MODE_MULTI_PROCESS);
    }

    /** A playlist is set up and synced at least once in Lumen. */
    static boolean ready(Context c) {
        SharedPreferences p = prefs(c);
        return p.getString("server", null) != null && p.getLong("last_sync", 0) > 0
                && c.getDatabasePath("lumen.db").exists();
    }

    static long syncedAt(Context c) {
        return prefs(c).getLong("last_sync", 0);
    }

    // MARK: - The catalog

    private static SQLiteDatabase db;

    private static synchronized SQLiteDatabase db(Context c) {
        if (db != null && db.isOpen()) return db;
        File f = c.getDatabasePath("lumen.db");
        if (!f.exists()) return null;
        try {
            // Read-only: Lumen keeps the database in WAL mode, and a read-only connection leaves that alone.
            db = SQLiteDatabase.openDatabase(f.getPath(), null, SQLiteDatabase.OPEN_READONLY);
        } catch (RuntimeException e) {
            AppLog.w("iptv: can't open Lumen's catalog: " + e.getMessage());
            db = null;
        }
        return db;
    }

    /** Lumen's own lowercase, accent-free form (Text.normalize), then without spaces or punctuation. */
    static String key(String title) {
        if (title == null) return "";
        String s = Normalizer.normalize(title.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{Mn}+", "");
        s = s.replace('&', ' ').replaceAll("\\s+", " ").trim();
        // Quality and language tags some providers leave after the title ("dune multi", "dune 4k")
        String[] words = s.split(" ");
        int end = words.length;
        while (end > 1 && TAGS.contains(words[end - 1])) end--;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < end; i++) b.append(words[i]);
        return b.toString().replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    private static final Set<String> TAGS = new HashSet<>(Arrays.asList(
            "fr", "vf", "vff", "vfq", "vo", "vost", "vostfr", "multi", "4k", "uhd", "fhd", "hd", "sd", "hdr", "en", "truefrench",
            "french", "1080p", "720p", "2160p", "hevc", "x265", "x264"));

    private static final class Index {
        long builtFor;
        final Map<String, List<int[]>> movies = new HashMap<>(), series = new HashMap<>();
    }

    private static Index index;

    /** Title → the catalog's entries, rebuilt after each playlist sync (a few seconds for tens of thousands of titles). */
    private static synchronized Index index(Context c) {
        long synced = syncedAt(c);
        if (index != null && index.builtFor == synced) return index;
        SQLiteDatabase d = db(c);
        if (d == null) return null;
        Index ix = new Index();
        ix.builtFor = synced;
        try {
            fill(d, "movie", ix.movies);
            fill(d, "series", ix.series);
        } catch (RuntimeException e) {
            AppLog.w("iptv: catalog unreadable: " + e.getMessage());
            closeDb();
            return null;
        }
        AppLog.w("iptv: catalog of " + ix.movies.size() + " movie titles and " + ix.series.size() + " series titles");
        index = ix;
        return ix;
    }

    private static void fill(SQLiteDatabase d, String table, Map<String, List<int[]>> out) {
        try (Cursor cur = d.rawQuery("SELECT id, norm, year FROM " + table, null)) {
            while (cur.moveToNext()) {
                String k = key(cur.getString(1));
                if (k.isEmpty()) continue;
                List<int[]> list = out.get(k);
                if (list == null) out.put(k, list = new ArrayList<>(1));
                list.add(new int[] {cur.getInt(0), year(cur.getString(2))});
            }
        }
    }

    private static synchronized void closeDb() {
        if (db != null) db.close();
        db = null;
    }

    private static final Pattern YEAR = Pattern.compile("(19|20)\\d{2}");

    static int year(String s) {
        if (s == null) return 0;
        java.util.regex.Matcher m = YEAR.matcher(s);
        return m.find() ? Integer.parseInt(m.group()) : 0;
    }

    /**
     * The catalog's movie or series for a TMDB title ("movie" or "tv"), by its French or original title; when the
     * catalog has several with that name, the one from the same year (±1). Null when the playlist doesn't have it.
     */
    static Match find(Context c, String kind, String title, String original, int year) {
        Index ix = index(c);
        if (ix == null) return null;
        Map<String, List<int[]>> map = "movie".equals(kind) ? ix.movies : ix.series;
        for (String t : new String[] {title, original}) {
            List<int[]> hits = map.get(key(t));
            if (hits == null) continue;
            int[] best = null;
            for (int[] h : hits) {
                boolean sameYear = year == 0 || h[1] == 0 || Math.abs(h[1] - year) <= 1;
                if (sameYear && (best == null || (h[1] == year && best[1] != year))) best = h;
            }
            if (best == null) continue;     // a namesake from another year: not this one
            Match m = new Match();
            m.type = "movie".equals(kind) ? "movie" : "series";
            m.id = best[0];
            return m;
        }
        return null;
    }

    // MARK: - Live TV

    /** Your channels: Lumen's favorites ("Ma liste") of the active profile, then the last ones watched. */
    static List<Channel> channels(Context c, int max) {
        List<Channel> out = new ArrayList<>();
        SQLiteDatabase d = db(c);
        if (d == null) return out;
        String profile = String.valueOf(prefs(c).getLong("profile", -1));
        Set<Integer> ids = new LinkedHashSet<>();
        try {
            try (Cursor cur = d.rawQuery("SELECT item FROM favorite WHERE profile=? AND type='live' ORDER BY added DESC", new String[] {profile})) {
                while (cur.moveToNext()) ids.add(cur.getInt(0));
            }
            try (Cursor cur = d.rawQuery("SELECT item FROM recent_live WHERE profile=? ORDER BY watched DESC LIMIT 30", new String[] {profile})) {
                while (cur.moveToNext()) ids.add(cur.getInt(0));
            }
            for (int id : ids) {
                if (out.size() >= max) break;
                try (Cursor cur = d.rawQuery("SELECT id, num, name, icon FROM live WHERE id=? AND (adult IS NULL OR adult=0)", new String[] {String.valueOf(id)})) {
                    if (cur.moveToNext()) out.add(channel(cur));
                }
            }
            if (out.isEmpty()) {
                // Nothing in "Ma liste" yet, nothing watched: the first channels of the playlist.
                try (Cursor cur = d.rawQuery("SELECT id, num, name, icon FROM live WHERE (adult IS NULL OR adult=0) ORDER BY pos LIMIT " + max, null)) {
                    while (cur.moveToNext()) out.add(channel(cur));
                }
            }
        } catch (RuntimeException e) {
            AppLog.w("iptv: channels unreadable: " + e.getMessage());
            closeDb();
        }
        return out;
    }

    private static Channel channel(Cursor cur) {
        Channel ch = new Channel();
        ch.id = cur.getInt(0);
        ch.num = cur.getInt(1);
        ch.name = cur.getString(2);
        ch.icon = cur.getString(3);
        return ch;
    }

    /** What's on now on a channel (the provider's short EPG); leaves the channel as is when unknown. */
    static void fillNow(Context c, Channel ch) {
        SharedPreferences p = prefs(c);
        String server = p.getString("server", null), user = p.getString("user", null), pass = p.getString("pass", null);
        if (server == null || user == null || pass == null) return;
        try {
            String url = server + "/player_api.php?username=" + URLEncoder.encode(user, "UTF-8") + "&password="
                    + URLEncoder.encode(pass, "UTF-8") + "&action=get_short_epg&stream_id=" + ch.id + "&limit=3";
            JSONArray list = new JSONObject(get(url, p.getString("ua", "LumenTV/1.0 (Linux; Android TV)"))).optJSONArray("epg_listings");
            long now = System.currentTimeMillis();
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject e = list.getJSONObject(i);
                long start = e.optLong("start_timestamp") * 1000, end = e.optLong("stop_timestamp", e.optLong("end_timestamp")) * 1000;
                if (start <= now && now < end) {
                    ch.now = decode(e.optString("title"));
                    ch.start = start;
                    ch.end = end;
                    return;
                }
            }
        } catch (Exception e) {
            // no guide for this channel: its name alone
        }
    }

    private static String decode(String s) {
        if (s == null || s.isEmpty()) return null;
        if (!s.matches("[A-Za-z0-9+/=\\r\\n]+")) return s;
        try {
            return new String(Base64.decode(s, Base64.DEFAULT), "UTF-8").trim();
        } catch (Exception e) {
            return s;
        }
    }

    private static String get(String url, String userAgent) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(12000);
        c.setRequestProperty("User-Agent", userAgent);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    /** A channel's logo, small, from the provider (cached in memory). */
    private static final Map<String, android.graphics.Bitmap> LOGOS = new HashMap<>();

    static android.graphics.Bitmap logo(String url, int maxSide) {
        if (url == null || url.isEmpty()) return null;
        synchronized (LOGOS) {
            if (LOGOS.containsKey(url)) return LOGOS.get(url);
        }
        android.graphics.Bitmap b = null;
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(8000);
            try (InputStream in = c.getInputStream()) {
                android.graphics.Bitmap full = android.graphics.BitmapFactory.decodeStream(in);
                if (full != null) {
                    float s = Math.min(1f, (float) maxSide / Math.max(full.getWidth(), full.getHeight()));
                    b = s >= 1f ? full : android.graphics.Bitmap.createScaledBitmap(full, Math.max(1, Math.round(full.getWidth() * s)),
                            Math.max(1, Math.round(full.getHeight() * s)), true);
                    if (b != full) full.recycle();
                }
            } finally {
                c.disconnect();
            }
        } catch (Exception ignored) {
        }
        synchronized (LOGOS) {
            if (LOGOS.size() > 120) LOGOS.clear();
            LOGOS.put(url, b);
        }
        return b;
    }

    // MARK: - Opening Lumen's player

    /** "iptv:movie:123" / "iptv:series:45": the title's page in Lumen (Back comes home). */
    static boolean openTitle(Context c, String link) {
        if (link == null || !link.startsWith("iptv:")) return false;
        String[] f = link.split(":");
        if (f.length < 3) return false;
        return start(c, new Intent().putExtra("salon_open", f[1]).putExtra("salon_id", parse(f[2])));
    }

    /** A live channel in Lumen's player; ↑/↓ there zap through `list` (the row it was picked from). */
    static boolean openChannel(Context c, int id, int[] list) {
        return start(c, new Intent().putExtra("salon_open", "live").putExtra("salon_id", id).putExtra("salon_ids", list));
    }

    private static boolean start(Context c, Intent i) {
        i.setClassName(c.getPackageName(), PLAYER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(i);
            return true;
        } catch (RuntimeException e) {
            AppLog.w("iptv: can't open Lumen: " + e.getMessage());
            return false;
        }
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
