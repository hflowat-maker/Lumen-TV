package io.freemytv;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The drawing board ("Ardoise"): the strokes drawn from the iPhone, kept in memory in the :home process. The home
 * screen shows it in a frame while it holds a drawing; "Plein écran" shows it over the whole screen (BoardActivity).
 * Points are fractions of the board (0 to 1, 16:9), so any size draws the same picture. Written from the remote's
 * threads, read on the main thread: everything is synchronized on the board, and listeners hear about changes on the
 * main thread.
 */
final class Board {
    /** The board's shape (the iPhone's drawing area has the same one). */
    static final float RATIO = 16f / 9f;
    private static final int MAX_STROKES = 2000, MAX_POINTS = 120_000;

    static final class Stroke {
        final String id;
        final int color, width;     // width: 1 thin, 2 medium, 3 thick
        final boolean eraser;
        float[] points = new float[32];
        int count;                  // floats used (x, y pairs)
        boolean ended;

        Stroke(String id, int color, int width, boolean eraser) {
            this.id = id;
            this.color = color;
            this.width = width;
            this.eraser = eraser;
        }

        void add(float x, float y) {
            if (count + 2 > points.length) points = java.util.Arrays.copyOf(points, points.length * 2);
            points[count++] = x;
            points[count++] = y;
        }
    }

    interface Listener {
        /** `reset`: strokes were removed (undo, clear): draw everything again; otherwise only new points came. */
        void onBoard(boolean reset);
    }

    private static final Board INSTANCE = new Board();

    static Board get() {
        return INSTANCE;
    }

    private final List<Stroke> strokes = new ArrayList<>();
    private final Map<String, Stroke> byId = new HashMap<>();
    private final List<Listener> listeners = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private int totalPoints;
    private int epoch;              // bumped when strokes are removed
    private boolean full;
    private boolean notifyQueued, resetQueued;

    private Board() {}

    // MARK: - Reading (main thread)

    synchronized int epoch() {
        return epoch;
    }

    synchronized boolean isEmpty() {
        return strokes.isEmpty();
    }

    synchronized boolean isFull() {
        return full;
    }

    /** The strokes themselves: only while holding the board's lock (synchronized (Board.get())). */
    List<Stroke> strokesLocked() {
        return strokes;
    }

    // MARK: - Changes (from the remote)

    /** New points: `ops` = [{id, c, w, e, p:[x, y, …]}], `end` = ids of finished strokes. */
    void apply(JSONArray ops, JSONArray end) throws JSONException {
        synchronized (this) {
            for (int i = 0; ops != null && i < ops.length(); i++) {
                JSONObject op = ops.getJSONObject(i);
                String id = op.optString("id", "");
                if (id.isEmpty() || id.length() > 40) continue;
                Stroke s = byId.get(id);
                if (s == null) {
                    if (strokes.size() >= MAX_STROKES) continue;
                    s = new Stroke(id, color(op.optString("c", "#FFFFFF")), Math.max(1, Math.min(3, op.optInt("w", 2))),
                            op.optBoolean("e", false));
                    strokes.add(s);
                    byId.put(id, s);
                }
                JSONArray p = op.optJSONArray("p");
                if (p == null) continue;
                for (int j = 0; j + 1 < p.length() && totalPoints < MAX_POINTS; j += 2) {
                    s.add(clamp((float) p.optDouble(j, 0)), clamp((float) p.optDouble(j + 1, 0)));
                    totalPoints++;
                }
            }
            for (int i = 0; end != null && i < end.length(); i++) {
                Stroke s = byId.get(end.optString(i));
                if (s != null) s.ended = true;
            }
        }
        changed(false);
    }

    void undo() {
        synchronized (this) {
            if (strokes.isEmpty()) return;
            Stroke s = strokes.remove(strokes.size() - 1);
            byId.remove(s.id);
            totalPoints -= s.count / 2;
            epoch++;
        }
        changed(true);
    }

    void clear() {
        synchronized (this) {
            strokes.clear();
            byId.clear();
            totalPoints = 0;
            full = false;
            epoch++;
        }
        changed(true);
    }

    /** Toggles full screen; returns the new value. */
    boolean toggleFull() {
        boolean now;
        synchronized (this) {
            full = !full;
            now = full;
        }
        changed(false);
        return now;
    }

    void setFull(boolean value) {
        synchronized (this) {
            if (full == value) return;
            full = value;
        }
        changed(false);
    }

    /** The whole drawing for the iPhone ({strokes, full}). */
    synchronized JSONObject toJson() throws JSONException {
        JSONArray list = new JSONArray();
        for (Stroke s : strokes) {
            JSONObject o = new JSONObject();
            o.put("id", s.id).put("c", String.format(java.util.Locale.ROOT, "#%06X", s.color & 0xFFFFFF))
                    .put("w", s.width).put("e", s.eraser);
            JSONArray p = new JSONArray();
            for (int i = 0; i < s.count; i++) p.put(Math.round(s.points[i] * 10000) / 10000.0);
            o.put("p", p);
            list.put(o);
        }
        return new JSONObject().put("strokes", list).put("full", full);
    }

    // MARK: - Listeners (main thread)

    void addListener(Listener l) {
        synchronized (listeners) {
            if (!listeners.contains(l)) listeners.add(l);
        }
    }

    void removeListener(Listener l) {
        synchronized (listeners) {
            listeners.remove(l);
        }
    }

    /** Coalesced: many small updates in a row reach the screen as one, on the next turn of the main thread. */
    private void changed(boolean reset) {
        synchronized (listeners) {
            resetQueued |= reset;
            if (notifyQueued) return;
            notifyQueued = true;
        }
        main.post(() -> {
            boolean r;
            List<Listener> copy;
            synchronized (listeners) {
                r = resetQueued;
                resetQueued = false;
                notifyQueued = false;
                copy = new ArrayList<>(listeners);
            }
            for (Listener l : copy) l.onBoard(r);
        });
    }

    private static float clamp(float v) {
        return Float.isNaN(v) ? 0 : Math.max(0, Math.min(1, v));
    }

    private static int color(String hex) {
        try {
            if (hex.matches("#[0-9A-Fa-f]{6}")) return 0xFF000000 | Integer.parseInt(hex.substring(1), 16);
        } catch (NumberFormatException ignored) {
        }
        return 0xFFFFFFFF;
    }
}
