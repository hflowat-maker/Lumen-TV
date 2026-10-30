package io.freemytv;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Draws the board's strokes into a bitmap of the size it is shown at, adding only the new points on each update
 * (everything again after an undo or a clear). One per screen showing the board.
 */
final class BoardRenderer {
    /** Line width as a fraction of the board's width, for thin, medium and thick (the iPhone uses the same). */
    private static final float[] WIDTH = {0.005f, 0.010f, 0.020f};
    private static final float ERASER = 3;

    private Bitmap bitmap;
    private Canvas canvas;
    private int epoch = -1;
    private final Map<Board.Stroke, Integer> drawn = new IdentityHashMap<>();
    private final Paint pen = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint eraser = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    BoardRenderer() {
        for (Paint p : new Paint[] {pen, eraser}) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
        }
        eraser.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
    }

    /** Brings the drawing up to date at w × h and returns it (null for an empty size). Main thread. */
    Bitmap update(int w, int h) {
        if (w <= 0 || h <= 0) return null;
        if (bitmap == null || bitmap.getWidth() != w || bitmap.getHeight() != h) {
            if (bitmap != null) bitmap.recycle();
            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            canvas = new Canvas(bitmap);
            epoch = -1;
        }
        Board board = Board.get();
        synchronized (board) {
            if (board.epoch() != epoch) {
                bitmap.eraseColor(0);
                drawn.clear();
                epoch = board.epoch();
            }
            for (Board.Stroke s : board.strokesLocked()) {
                Integer from = drawn.get(s);
                int start = from == null ? 0 : from;
                if (start >= s.count) continue;
                draw(s, start, w);
                drawn.put(s, s.count);
            }
        }
        return bitmap;
    }

    void release() {
        if (bitmap != null) bitmap.recycle();
        bitmap = null;
        canvas = null;
        drawn.clear();
    }

    private void draw(Board.Stroke s, int start, int w) {
        Paint p = s.eraser ? eraser : pen;
        float width = WIDTH[Math.max(0, Math.min(2, s.width - 1))] * w * (s.eraser ? ERASER : 1);
        p.setStrokeWidth(Math.max(1, width));
        if (!s.eraser) p.setColor(s.color);
        float bw = bitmap.getWidth(), bh = bitmap.getHeight();
        if (s.count == 2) {     // a single tap: a dot
            canvas.drawPoint(s.points[0] * bw, s.points[1] * bh, p);
            return;
        }
        int from = Math.max(0, start - 2);     // continue from the last point already drawn
        path.reset();
        path.moveTo(s.points[from] * bw, s.points[from + 1] * bh);
        for (int i = from + 2; i + 1 < s.count; i += 2) path.lineTo(s.points[i] * bw, s.points[i + 1] * bh);
        canvas.drawPath(path, p);
    }
}
