package io.freemytv;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Base64;

import app.lumen.tv.data.CatInfo;
import app.lumen.tv.data.Category;
import app.lumen.tv.data.Classifier;
import app.lumen.tv.data.ContentType;
import app.lumen.tv.data.Group;
import app.lumen.tv.data.Lang;

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
        // What's on now (EPG), when known, and when it was asked (0: not yet)
        String now;
        long start, end, askedAt;
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

    /** A TMDB title in the catalog's form (Lumen's Text.normalize: lowercase, no accents), as keyHash() sees it. */
    static String fold(String title) {
        if (title == null) return "";
        return Normalizer.normalize(title.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{Mn}+", "");
    }

    private static final String[] TAGS = {
            "fr", "vf", "vff", "vfq", "vo", "vost", "vostfr", "multi", "4k", "uhd", "fhd", "hd", "sd", "hdr", "en", "truefrench",
            "french", "1080p", "720p", "2160p", "hevc", "x265", "x264"};
    private static final long FNV = 0xcbf29ce484222325L, PRIME = 0x100000001b3L;

    /**
     * A normalized title (the catalog's `norm` column, or fold() of a TMDB title) reduced to its letters and digits,
     * without the quality or language tags some providers leave at the end ("dune multi", "dune 4k"; never the first
     * word), as a 44-bit hash (0 = no letters). One pass, no string built: it runs on every title of the playlist.
     */
    static long keyHash(String s) {
        if (s == null) return 0;
        int len = s.length(), words = 0;
        int[] start = new int[8], end = new int[8];
        long[] before = new long[8];
        long h = FNV;
        boolean inWord = false;
        for (int i = 0; i < len; i++) {
            char ch = s.charAt(i);
            if (!Character.isLetterOrDigit(ch)) {
                inWord = false;
                continue;
            }
            if (!inWord) {
                if (words == start.length) {
                    start = Arrays.copyOf(start, words * 2);
                    end = Arrays.copyOf(end, words * 2);
                    before = Arrays.copyOf(before, words * 2);
                }
                start[words] = i;
                before[words] = h;
                words++;
                inWord = true;
            }
            h = (h ^ ch) * PRIME;
            end[words - 1] = i + 1;
        }
        if (words == 0) return 0;
        while (words > 1 && isTag(s, start[words - 1], end[words - 1])) h = before[--words];
        long k = h >>> Table.ROW_BITS;
        return k == 0 ? 1 : k;
    }

    private static boolean isTag(String s, int from, int to) {
        int n = to - from;
        for (String t : TAGS) if (t.length() == n && s.regionMatches(from, t, 0, n)) return true;
        return false;
    }

    /**
     * One table of the catalog (movies or series) as sorted numbers: (title hash << 20 | row), with each row's id and
     * year beside it. About 16 bytes per title (a map of strings would take ten times that in the home screen's memory).
     */
    private static final class Table {
        static final int ROW_BITS = 20, MAX_ROWS = 1 << ROW_BITS;
        long[] keys = new long[4096];
        int[] ids = new int[4096];
        short[] years = new short[4096];
        int size;

        void add(long hash, int id, int year) {
            if (hash == 0 || size >= MAX_ROWS) return;
            if (size == keys.length) {
                int n = Math.min(MAX_ROWS, size * 2);
                keys = Arrays.copyOf(keys, n);
                ids = Arrays.copyOf(ids, n);
                years = Arrays.copyOf(years, n);
            }
            keys[size] = hash << ROW_BITS | size;
            ids[size] = id;
            years[size] = (short) year;
            size++;
        }

        void seal() {
            keys = Arrays.copyOf(keys, size);
            ids = Arrays.copyOf(ids, size);
            years = Arrays.copyOf(years, size);
            Arrays.sort(keys);
        }

        /** Index of the first entry with this hash (or where it would be). */
        int first(long hash) {
            long want = hash << ROW_BITS;
            int lo = 0, hi = size;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (keys[mid] < want) lo = mid + 1; else hi = mid;
            }
            return lo;
        }
    }

    private static final class Index {
        long builtFor;
        final Table movies = new Table(), series = new Table();
    }

    private static Index index;

    /** Title → the catalog's entries, rebuilt after each playlist sync (a few seconds for tens of thousands of titles). */
    private static synchronized Index index(Context c) {
        long synced = syncedAt(c);
        if (index != null && index.builtFor == synced) return index;
        SQLiteDatabase d = db(c);
        if (d == null) return null;
        long t0 = System.currentTimeMillis();
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
        AppLog.w("iptv: catalog of " + ix.movies.size + " movies and " + ix.series.size + " series, read in "
                + (System.currentTimeMillis() - t0) + " ms");
        index = ix;
        return ix;
    }

    private static void fill(SQLiteDatabase d, String table, Table out) {
        try (Cursor cur = d.rawQuery("SELECT id, norm, year FROM " + table, null)) {
            while (cur.moveToNext()) out.add(keyHash(cur.getString(1)), cur.getInt(0), year(cur.getString(2)));
        }
        out.seal();
    }

    private static synchronized void closeDb() {
        if (db != null) db.close();
        db = null;
    }

    /** The first 19xx or 20xx in the text ("2023", "2023-05-01"), else 0. Without a regex: once per title. */
    static int year(String s) {
        if (s == null) return 0;
        for (int i = 0; i + 4 <= s.length(); i++) {
            char a = s.charAt(i), b = s.charAt(i + 1);
            if (!((a == '1' && b == '9') || (a == '2' && b == '0'))) continue;
            char c = s.charAt(i + 2), d = s.charAt(i + 3);
            if (c < '0' || c > '9' || d < '0' || d > '9') continue;
            boolean before = i > 0 && Character.isDigit(s.charAt(i - 1)), after = i + 4 < s.length() && Character.isDigit(s.charAt(i + 4));
            if (!before && !after) return (a - '0') * 1000 + (b - '0') * 100 + (c - '0') * 10 + (d - '0');
        }
        return 0;
    }

    /**
     * The catalog's movie or series for a TMDB title ("movie" or "tv"), by its French or original title; when the
     * catalog has several with that name, the one from the same year (±1). Null when the playlist doesn't have it.
     */
    static Match find(Context c, String kind, String title, String original, int year) {
        Index ix = index(c);
        if (ix == null) return null;
        Table t = "movie".equals(kind) ? ix.movies : ix.series;
        for (String name : new String[] {title, original}) {
            long h = keyHash(fold(name));
            if (h == 0) continue;
            int bestId = 0, bestYear = 0;
            boolean found = false;
            for (int i = t.first(h); i < t.size && (t.keys[i] >>> Table.ROW_BITS) == h; i++) {
                int row = (int) (t.keys[i] & (Table.MAX_ROWS - 1)), y = t.years[row];
                boolean sameYear = year == 0 || y == 0 || Math.abs(y - year) <= 1;
                if (sameYear && (!found || (y == year && bestYear != year))) {
                    bestId = t.ids[row];
                    bestYear = y;
                    found = true;
                }
            }
            if (!found) continue;     // a namesake from another year: not this one
            Match m = new Match();
            m.type = "movie".equals(kind) ? "movie" : "series";
            m.id = bestId;
            return m;
        }
        return null;
    }

    // MARK: - Live TV

    /**
     * A row of the Live TV tab: your list (favorites, then the last ones watched) or one of the themes Lumen shows in
     * its own Live TV tab (Sport, Info, Cinéma & séries…: its Classifier, for the profile's languages), each made of the
     * provider's categories.
     */
    static final class LiveGroup {
        String key, label;
        List<String> cats;      // the provider's categories; null for your list
        List<Integer> ids;      // your list's channels, in order
    }

    /** The rows, in Lumen's order; empty when the catalog can't be read. */
    static List<LiveGroup> liveGroups(Context c) {
        List<LiveGroup> out = new ArrayList<>();
        SQLiteDatabase d = db(c);
        if (d == null) return out;
        try {
            SharedPreferences p = prefs(c);
            long profile = p.getLong("profile", -1);
            boolean kid = isKid(d, profile);
            List<Integer> mine = myList(d, profile);
            if (!mine.isEmpty()) {
                LiveGroup g = new LiveGroup();
                g.key = "mine";
                g.label = Ui.tr("My list");
                g.ids = mine;
                out.add(g);
            }
            List<Category> cats = new ArrayList<>();
            String sql = "SELECT c.id, c.name, (SELECT COUNT(*) FROM live x WHERE x.cat = c.id), c.adult FROM categories c "
                    + "WHERE c.type = 'live' AND " + catClause(p, kid, "c.id", false) + " ORDER BY c.pos";
            try (Cursor cur = d.rawQuery(sql, null)) {
                while (cur.moveToNext()) {
                    int n = cur.getInt(2);
                    if (n > 0) cats.add(new Category(ContentType.LIVE, cur.getString(0), cur.getString(1), n, cur.getInt(3) == 1));
                }
            }
            List<CatInfo> infos = Classifier.INSTANCE.classify(ContentType.LIVE, cats);
            Set<Lang> langs = Lang.Companion.decode(p.getString("lang_" + profile, null));
            if (langs == null) langs = kid ? Lang.Companion.getKIDS() : Lang.Companion.getDEFAULT();
            for (Group g : Classifier.INSTANCE.genreGroups(ContentType.LIVE, infos, langs)) add(out, g.getKey(), g.getLabel(), g.getCatIds());
            if (langs.contains(Lang.TR)) add(out, "tr", "Chaînes turques", Classifier.INSTANCE.idsFor(infos, Lang.TR));
            if (langs.contains(Lang.OTHER)) {
                for (Group g : Classifier.INSTANCE.otherLanguageGroups(infos)) add(out, g.getKey(), g.getLabel(), g.getCatIds());
            }
        } catch (RuntimeException e) {
            AppLog.w("iptv: live categories unreadable: " + e.getMessage());
            closeDb();
        }
        return out;
    }

    private static void add(List<LiveGroup> out, String key, String label, List<String> cats) {
        if (cats == null || cats.isEmpty()) return;
        LiveGroup g = new LiveGroup();
        g.key = key;
        g.label = label;
        g.cats = cats;
        out.add(g);
    }

    /** A row's channels (all of them; the guide and logos are fetched for the ones on screen). */
    static List<Channel> channels(Context c, LiveGroup g) {
        List<Channel> out = new ArrayList<>();
        SQLiteDatabase d = db(c);
        if (d == null || g == null) return out;
        try {
            SharedPreferences p = prefs(c);
            String clause = catClause(p, isKid(d, p.getLong("profile", -1)), "cat", true);
            if (g.ids != null) {
                Map<Integer, Channel> byId = new HashMap<>();
                StringBuilder in = new StringBuilder();
                for (int id : g.ids) in.append(in.length() == 0 ? "" : ",").append(id);
                if (in.length() == 0) return out;
                try (Cursor cur = d.rawQuery("SELECT id, num, name, icon FROM live WHERE id IN (" + in + ") AND " + clause, null)) {
                    while (cur.moveToNext()) {
                        Channel ch = channel(cur);
                        byId.put(ch.id, ch);
                    }
                }
                for (int id : g.ids) if (byId.containsKey(id)) out.add(byId.get(id));
            } else {
                StringBuilder in = new StringBuilder();
                for (String cat : g.cats) in.append(in.length() == 0 ? "" : ",").append('\'').append(cat.replace("'", "''")).append('\'');
                try (Cursor cur = d.rawQuery("SELECT id, num, name, icon FROM live WHERE cat IN (" + in + ") AND " + clause + " ORDER BY pos", null)) {
                    while (cur.moveToNext()) out.add(channel(cur));
                }
            }
        } catch (RuntimeException e) {
            AppLog.w("iptv: channels unreadable: " + e.getMessage());
            closeDb();
        }
        return out;
    }

    /** "Ma liste": the profile's favorite channels (latest first), then the last ones watched. */
    private static List<Integer> myList(SQLiteDatabase d, long profile) {
        String pid = String.valueOf(profile);
        Set<Integer> ids = new LinkedHashSet<>();
        try (Cursor cur = d.rawQuery("SELECT item FROM favorite WHERE profile=? AND type='live' ORDER BY added DESC", new String[] {pid})) {
            while (cur.moveToNext()) ids.add(cur.getInt(0));
        }
        try (Cursor cur = d.rawQuery("SELECT item FROM recent_live WHERE profile=? ORDER BY watched DESC LIMIT 30", new String[] {pid})) {
            while (cur.moveToNext()) ids.add(cur.getInt(0));
        }
        return new ArrayList<>(ids);
    }

    private static boolean isKid(SQLiteDatabase d, long profile) {
        try (Cursor cur = d.rawQuery("SELECT kid FROM profile WHERE id=?", new String[] {String.valueOf(profile)})) {
            return cur.moveToNext() && cur.getInt(0) == 1;
        }
    }

    /** Lumen's filter (Db.catClause): a kid profile sees only its allowed categories; adult ones are hidden. */
    private static String catClause(SharedPreferences p, boolean kid, String column, boolean channelRows) {
        if (kid) return column + " IN (SELECT cat FROM kid_cat WHERE type='live' AND allowed=1)";
        if (p.getBoolean("hide_adult", true)) {
            return column + " NOT IN (SELECT id FROM categories WHERE type='live' AND adult=1)"
                    + (channelRows ? " AND (adult IS NULL OR adult=0)" : "");
        }
        return "1=1";
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
