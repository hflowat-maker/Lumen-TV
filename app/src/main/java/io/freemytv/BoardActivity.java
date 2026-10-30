package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;

/** The drawing board over the whole screen ("Plein écran" on the iPhone). Back, or "Réduire" on the iPhone, closes it. */
public class BoardActivity extends Activity implements Board.Listener {
    private final BoardRenderer renderer = new BoardRenderer();
    private FullView view;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        view = new FullView(this);
        setContentView(view);
    }

    @Override protected void onResume() {
        super.onResume();
        Board.get().addListener(this);
        Board.get().setFull(true);
        view.invalidate();
    }

    @Override protected void onPause() {
        Board.get().removeListener(this);
        super.onPause();
    }

    @Override protected void onStop() {
        super.onStop();
        Board.get().setFull(false);     // left (Home, another app): the iPhone shows "Plein écran" again
        finish();
    }

    @Override protected void onDestroy() {
        renderer.release();
        super.onDestroy();
    }

    @Override public void onBoard(boolean reset) {
        if (!Board.get().isFull()) {
            finish();
            return;
        }
        view.invalidate();
    }

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_BACK) {
            Board.get().setFull(false);
            finish();
            return true;
        }
        return super.onKeyDown(code, e);
    }

    private final class FullView extends View {
        private final Paint image = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF area = new RectF();
        private Paint caption;

        FullView(Context c) {
            super(c);
            setFocusable(true);
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            caption = Ui.text(Ui.medium, 22 * (w / 1920f), 0x99FFFFFF);
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), k = w / 1920f;
            c.drawColor(0xFF0E0D12);
            // The largest 16:9 area, a little inside the edges
            float aw = Math.min(w - 96 * k, (h - 120 * k) * Board.RATIO), ah = aw / Board.RATIO;
            area.set((w - aw) / 2, (h - ah) / 2 + 20 * k, (w + aw) / 2, (h + ah) / 2 + 20 * k);
            fill.setColor(0xFF15131C);
            c.drawRoundRect(area, 28 * k, 28 * k, fill);
            Bitmap b = renderer.update(Math.round(area.width()), Math.round(area.height()));
            if (b != null) c.drawBitmap(b, area.left, area.top, image);
            if (caption != null) {
                Symbols.draw(getContext(), c, "draw", area.left + 18 * k, 44 * k - Ui.halfCap(caption), 22 * k, 0x99FFFFFF);
                c.drawText(Ui.tr("Board · draw from your iPhone · Back to close"), area.left + 44 * k, 44 * k, caption);
            }
        }
    }
}
