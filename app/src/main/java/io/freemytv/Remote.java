package io.freemytv;

import app.lumen.tv.R;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The iPhone remote: a small web server on the home Wi-Fi (port 7878), in the :home process. It serves the remote's
 * page (assets/remote) and its API. Only iPhones paired once with the 4-digit code shown on the TV (PairActivity)
 * can use the API: each gets a random key, sent back with every request. Keys and arrows are pressed through the
 * TV's own ADB (like the real remote, so they work in every app); the drawing goes to the Board.
 * Nothing goes over the Internet.
 */
final class Remote {
    static final int PORT = 7878;
    private static final int MAX_BODY = 512 * 1024, MAX_ATTEMPTS = 5;
    private static final long PIN_MS = 5 * 60_000L;
    private static final Map<String, Integer> KEYS = new HashMap<>();
    static {
        KEYS.put("up", 19);
        KEYS.put("down", 20);
        KEYS.put("left", 21);
        KEYS.put("right", 22);
        KEYS.put("ok", 23);
        KEYS.put("back", 4);
        KEYS.put("home", 3);
        KEYS.put("playpause", 85);
        KEYS.put("volup", 24);
        KEYS.put("voldown", 25);
        KEYS.put("mute", 164);
        KEYS.put("sleep", 223);
        KEYS.put("del", 67);
    }

    /** Told (main thread) when an iPhone was just paired. */
    interface PairListener { void onPaired(); }

    private static Remote instance;

    private final Context context;
    private final SharedPreferences prefs;
    private final SecureRandom random = new SecureRandom();
    private final ExecutorService clients = Executors.newCachedThreadPool();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile String pin;
    private volatile long pinUntil;
    private int attempts;
    private PairListener pairListener;

    private Remote(Context c) {
        context = c.getApplicationContext();
        prefs = context.getSharedPreferences("remote", Context.MODE_PRIVATE);
    }

    /** Starts the server once (from the :home process). */
    static synchronized void start(Context c) {
        if (instance != null) return;
        instance = new Remote(c);
        Thread t = new Thread(instance::serve, "remote-http");
        t.setDaemon(true);
        t.start();
    }

    static synchronized Remote get(Context c) {
        if (instance == null) start(c);
        return instance;
    }

    // MARK: - Pairing (PairActivity)

    /** Opens pairing: a new 4-digit code, valid 5 minutes or until closePairing(). */
    synchronized String openPairing(PairListener l) {
        pin = String.format(Locale.ROOT, "%04d", random.nextInt(10000));
        pinUntil = System.currentTimeMillis() + PIN_MS;
        attempts = 0;
        pairListener = l;
        return pin;
    }

    synchronized void closePairing() {
        pin = null;
        pairListener = null;
    }

    int pairedCount() {
        return prefs.getStringSet("keys", Collections.<String>emptySet()).size();
    }

    void forgetAll() {
        prefs.edit().remove("keys").apply();
    }

    /** The address the iPhone opens (the TV's IPv4 on the home network), or null when not connected. */
    static String url() {
        String ip = localIp();
        return ip == null ? null : "http://" + ip + ":" + PORT + "/";
    }

    static String localIp() {
        try {
            String best = null;
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress() || !a.isSiteLocalAddress()) continue;
                    String name = ni.getName();
                    if (name.startsWith("wlan") || name.startsWith("eth")) return a.getHostAddress();
                    if (best == null) best = a.getHostAddress();
                }
            }
            return best;
        } catch (Exception e) {
            return null;
        }
    }

    // MARK: - HTTP

    private void serve() {
        while (true) {
            try (ServerSocket server = new ServerSocket()) {
                server.setReuseAddress(true);
                server.bind(new InetSocketAddress(PORT));
                AppLog.w("remote: listening on port " + PORT);
                while (true) {
                    final Socket s = server.accept();
                    clients.execute(() -> handle(s));
                }
            } catch (IOException e) {
                AppLog.w("remote server: " + e.getMessage());
                try {
                    Thread.sleep(10_000);     // port busy or network down: try again later
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    private static final class Request {
        String method, path, query = "";
        final Map<String, String> headers = new HashMap<>();
        byte[] body = new byte[0];
    }

    private void handle(Socket s) {
        try (Socket socket = s) {
            socket.setSoTimeout(30_000);
            socket.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            while (true) {
                Request r = read(in);
                if (r == null) return;
                boolean keepAlive = !"close".equalsIgnoreCase(r.headers.get("connection"));
                respond(r, out, keepAlive);
                if (!keepAlive) return;
            }
        } catch (IOException | RuntimeException e) {
            // the iPhone went away, or a malformed request: just close
        }
    }

    private static Request read(InputStream in) throws IOException {
        String line = line(in);
        if (line == null) return null;
        String[] parts = line.split(" ");
        if (parts.length < 2) throw new IOException("bad request");
        Request r = new Request();
        r.method = parts[0];
        String target = parts[1];
        int q = target.indexOf('?');
        r.path = q < 0 ? target : target.substring(0, q);
        if (q >= 0) r.query = target.substring(q + 1);
        String h;
        int count = 0;
        while ((h = line(in)) != null && !h.isEmpty()) {
            if (++count > 100) throw new IOException("too many headers");
            int colon = h.indexOf(':');
            if (colon > 0) r.headers.put(h.substring(0, colon).trim().toLowerCase(Locale.ROOT), h.substring(colon + 1).trim());
        }
        int length = 0;
        try {
            length = Integer.parseInt(r.headers.getOrDefault("content-length", "0"));
        } catch (NumberFormatException ignored) {
        }
        if (length < 0 || length > MAX_BODY) throw new IOException("body too large");
        r.body = new byte[length];
        int read = 0;
        while (read < length) {
            int n = in.read(r.body, read, length - read);
            if (n < 0) throw new IOException("truncated body");
            read += n;
        }
        return r;
    }

    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 8192) throw new IOException("line too long");
        }
        if (c < 0 && b.size() == 0) return null;
        return b.toString("ISO-8859-1");
    }

    private void respond(Request r, OutputStream out, boolean keepAlive) throws IOException {
        int status = 200;
        String type = "application/json; charset=utf-8";
        byte[] body;
        try {
            if (r.method.equals("GET") && (r.path.equals("/") || r.path.equals("/index.html"))) {
                body = asset("remote/index.html");
                type = "text/html; charset=utf-8";
            } else if (r.method.equals("GET") && r.path.equals("/icon.png")) {
                body = asset("remote/icon.png");
                type = "image/png";
            } else if (r.path.startsWith("/api/")) {
                JSONObject reply = api(r);
                status = reply.optInt("_status", 200);
                reply.remove("_status");
                body = reply.toString().getBytes(StandardCharsets.UTF_8);
            } else {
                status = 404;
                body = "{\"error\":\"not found\"}".getBytes(StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            AppLog.w("remote " + r.path + ": " + e);
            status = 500;
            body = ("{\"ok\":false,\"error\":" + JSONObject.quote(String.valueOf(e.getMessage())) + "}").getBytes(StandardCharsets.UTF_8);
        }
        String head = "HTTP/1.1 " + status + " " + reason(status) + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "X-Content-Type-Options: nosniff\r\n"
                + "Connection: " + (keepAlive ? "keep-alive" : "close") + "\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    private static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 429: return "Too Many Requests";
            default: return "Error";
        }
    }

    private byte[] asset(String name) throws IOException {
        try (InputStream in = context.getAssets().open(name)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toByteArray();
        }
    }

    // MARK: - API

    private JSONObject api(Request r) throws Exception {
        JSONObject in = r.body.length == 0 ? new JSONObject() : new JSONObject(new String(r.body, StandardCharsets.UTF_8));
        boolean post = r.method.equals("POST");
        if (r.path.equals("/api/info")) return new JSONObject().put("name", context.getString(R.string.salon_name));
        if (r.path.equals("/api/pair") && post) return pair(in);
        if (!authorized(r.headers.get("x-salon-key"))) return error(401, "not paired");
        switch (r.path) {
            case "/api/key": return keys(in);
            case "/api/text": return text(in);
            case "/api/apps": return apps();
            case "/api/open": return open(in);
            case "/api/audio": return post ? audioAction(in) : audio();
            case "/api/draw":
                Board.get().apply(in.optJSONArray("ops"), in.optJSONArray("end"));
                return ok().put("full", Board.get().isFull());
            case "/api/board": return post ? board(in) : Board.get().toJson().put("ok", true);
            default: return error(404, "unknown");
        }
    }

    private static JSONObject ok() throws Exception {
        return new JSONObject().put("ok", true);
    }

    private static JSONObject error(int status, String why) throws Exception {
        return new JSONObject().put("ok", false).put("error", why).put("_status", status);
    }

    private boolean authorized(String key) {
        if (key == null || key.length() < 16) return false;
        return prefs.getStringSet("keys", Collections.<String>emptySet()).contains(key);
    }

    private JSONObject pair(JSONObject in) throws Exception {
        String key;
        final PairListener l;
        synchronized (this) {
            if (pin == null || System.currentTimeMillis() > pinUntil) return error(403, "pairing closed");
            if (attempts >= MAX_ATTEMPTS) return error(429, "too many attempts");
            if (!pin.equals(in.optString("pin"))) {
                if (++attempts >= MAX_ATTEMPTS) pin = null;     // a new code is needed (open "iPhone" again)
                return error(attempts >= MAX_ATTEMPTS ? 429 : 403, "wrong code");
            }
            byte[] b = new byte[18];
            random.nextBytes(b);
            key = android.util.Base64.encodeToString(b, android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
            Set<String> keys = new HashSet<>(prefs.getStringSet("keys", Collections.<String>emptySet()));
            keys.add(key);
            prefs.edit().putStringSet("keys", keys).apply();
            pin = null;
            l = pairListener;
        }
        if (l != null) main.post(l::onPaired);
        return ok().put("key", key);
    }

    private JSONObject keys(JSONObject in) throws Exception {
        JSONArray list = in.optJSONArray("keys");
        if (list == null && in.has("key")) list = new JSONArray().put(in.optString("key"));
        StringBuilder codes = new StringBuilder();
        for (int i = 0; list != null && i < list.length() && i < 40; i++) {
            Integer code = KEYS.get(list.optString(i));
            if (code != null) codes.append(' ').append(code);
        }
        if (codes.length() == 0) return error(400, "no key");
        AdbRunner.get(context).run("input keyevent" + codes, 8000);
        return ok();
    }

    /**
     * Typed text: `input text` only takes plain ASCII, so accents are dropped (é → e; searches in the streaming apps
     * don't mind) and other characters left out.
     */
    private JSONObject text(JSONObject in) throws Exception {
        String t = Normalizer.normalize(in.optString("text", ""), Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        StringBuilder s = new StringBuilder();
        for (char ch : t.toCharArray()) {
            if (ch == ' ') s.append("%s");
            else if (ch >= 33 && ch < 127 && ch != '%') s.append(ch);
        }
        if (s.length() == 0) return ok();
        String quoted = "'" + s.toString().replace("'", "'\\''") + "'";
        AdbRunner.get(context).run("input text " + quoted, 8000);
        return ok();
    }

    private JSONObject apps() throws Exception {
        JSONArray list = new JSONArray();
        PackageManager pm = context.getPackageManager();
        for (HomeApps.Item it : HomeApps.shelf(context, false)) {
            if (it.kind != HomeApps.APP || it.pkg == null || HomeApps.launchIntent(pm, it.pkg) == null) continue;
            list.put(new JSONObject().put("pkg", it.pkg).put("label", it.label));
            if (list.length() >= 8) break;
        }
        return ok().put("apps", list);
    }

    private JSONObject open(JSONObject in) throws Exception {
        String pkg = in.optString("pkg", "");
        android.content.Intent intent = pkg.matches("[A-Za-z0-9_.]+") ? HomeApps.launchIntent(context.getPackageManager(), pkg) : null;
        if (intent == null || intent.getComponent() == null) return error(404, "app not found");
        String component = intent.getComponent().flattenToShortString();
        if (!component.matches("[A-Za-z0-9_.$/]+")) return error(400, "bad app");
        // As the shell user: an app in the background may not start activities, the shell may.
        AdbRunner.get(context).run("am start -n '" + component + "' -f 0x10200000", 8000);
        return ok();
    }

    private JSONObject audio() throws Exception {
        Bt.Result r = Bt.audio(context, false);
        JSONArray list = new JSONArray();
        for (Bt.Device d : r.devices) {
            list.put(new JSONObject().put("name", d.name).put("address", d.address).put("connected", d.connected));
        }
        JSONObject o = new JSONObject().put("ok", r.error == null).put("devices", list).put("off", r.off);
        if (r.error != null) o.put("error", r.error);
        return o;
    }

    private JSONObject audioAction(JSONObject in) throws Exception {
        String why = Bt.connect(context, in.optString("address"), !"disconnect".equals(in.optString("action")));
        return why == null ? ok() : new JSONObject().put("ok", false).put("error", why);
    }

    private JSONObject board(JSONObject in) throws Exception {
        Board b = Board.get();
        switch (in.optString("action")) {
            case "undo":
                b.undo();
                return b.toJson().put("ok", true);
            case "clear":
                b.clear();
                return ok().put("full", false);
            case "full":
                boolean full = b.toggleFull();
                if (full) AdbRunner.get(context).post("am start -n " + context.getPackageName() + "/" + BoardActivity.class.getName() + " -f 0x10000000");
                return ok().put("full", full);
            default:
                return error(400, "unknown action");
        }
    }
}
