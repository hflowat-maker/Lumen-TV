package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;

import io.nayuki.qrcodegen.QrCode;

/**
 * "iPhone": pairs an iPhone with the remote. Shows a QR code with the remote's address and a 4-digit code (valid
 * while this screen is open, 5 minutes at most). Once paired, the iPhone keeps working without it. ↓ then OK forgets
 * every paired iPhone.
 */
public class PairActivity extends Activity implements Remote.PairListener {
    private static final int ACCENT = 0xFFF2B84B;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private PairView view;
    private String url, pin;
    private Bitmap qr;
    private boolean done, forgetFocused;
    private int paired;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        view = new PairView(this);
        setContentView(view);
    }

    @Override protected void onResume() {
        super.onResume();
        Remote remote = Remote.get(this);
        url = Remote.url();
        pin = url == null ? null : remote.openPairing(this);
        paired = remote.pairedCount();
        done = false;
        qr = url == null ? null : qrBitmap(url);
        handler.postDelayed(expire, 5 * 60_000L);
        view.invalidate();
    }

    @Override protected void onPause() {
        Remote.get(this).closePairing();
        handler.removeCallbacksAndMessages(null);
        super.onPause();
    }

    private final Runnable expire = this::finish;

    @Override public void onPaired() {
        done = true;
        paired = Remote.get(this).pairedCount();
        view.invalidate();
        handler.postDelayed(this::finish, 3500);
    }

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (paired > 0 && !forgetFocused) {
                    forgetFocused = true;
                    view.invalidate();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (forgetFocused) {
                    forgetFocused = false;
                    view.invalidate();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (forgetFocused) {
                    Remote.get(this).forgetAll();
                    paired = 0;
                    forgetFocused = false;
                    view.invalidate();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return true;
            default:
                return super.onKeyDown(code, e);
        }
    }

    /** The QR code, black on white with a 2-module quiet zone, one pixel per module (scaled up without filtering). */
    private static Bitmap qrBitmap(String text) {
        QrCode code = QrCode.encodeText(text, QrCode.Ecc.MEDIUM);
        int n = code.size, border = 2, size = n + border * 2;
        int[] px = new int[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean dark = code.getModule(x - border, y - border);
                px[y * size + x] = dark ? 0xFF0B0A10 : 0xFFFFFFFF;
            }
        }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888);
    }

    private final class PairView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint qrPaint = new Paint();     // no filtering: crisp modules
        private Bitmap qrScaled;
        private Bitmap qrSource;
        private final RectF r = new RectF();
        private Paint kicker, title, step, small, digit, number, button;

        PairView(Context c) {
            super(c);
            setFocusable(true);
            stroke.setStyle(Paint.Style.STROKE);
            qrPaint.setFilterBitmap(false);
            qrPaint.setAntiAlias(false);
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            float k = w / 1920f;
            kicker = Ui.text(Ui.semibold, 20 * k, ACCENT);
            kicker.setLetterSpacing(0.18f);
            title = Ui.text(Ui.displaySemibold, 52 * k, 0xFFFFFFFF);
            step = Ui.text(Ui.regular, 27 * k, 0xFFFFFFFF);
            small = Ui.text(Ui.regular, 21 * k, 0xB3FFFFFF);
            digit = Ui.text(Ui.displaySemibold, 40 * k, 0xFF0B0A10);
            number = Ui.text(Ui.semibold, 24 * k, 0xFFFFFFFF);
            button = Ui.text(Ui.semibold, 22 * k, 0xFFFFFFFF);
        }

        @Override protected void onDraw(Canvas c) {
            if (kicker == null) return;
            float k = getWidth() / 1920f;
            // The card
            r.set(330 * k, 190 * k, 1590 * k, 890 * k);
            fill.setColor(0xF2100E16);
            c.drawRoundRect(r, 44 * k, 44 * k, fill);
            stroke.setColor(0x1AFFFFFF);
            stroke.setStrokeWidth(1.5f * k);
            c.drawRoundRect(r, 44 * k, 44 * k, stroke);

            float left = 402 * k, top = 262 * k;
            // QR code (or why there is none)
            r.set(left, top + 60 * k, left + 380 * k, top + 440 * k);
            fill.setColor(0xFFFFFFFF);
            c.drawRoundRect(r, 28 * k, 28 * k, fill);
            if (qr != null) {
                // Each module a whole number of pixels: a scaled-up QR code gets blurred edges
                int room = Math.round(r.width() - 44 * k), scale = Math.max(1, room / qr.getWidth()), size = scale * qr.getWidth();
                if (qrSource != qr || qrScaled == null || qrScaled.getWidth() != size) {
                    qrSource = qr;
                    qrScaled = Bitmap.createScaledBitmap(qr, size, size, false);
                }
                c.drawBitmap(qrScaled, Math.round(r.centerX() - size / 2f), Math.round(r.centerY() - size / 2f), qrPaint);
            }
            String caption = url == null ? Ui.tr("The TV isn't on the home Wi-Fi.") : url.replace("http://", "").replace("/", "");
            c.drawText(caption, r.centerX() - small.measureText(caption) / 2, r.bottom + 46 * k, small);

            float x = 854 * k, y = top + 28 * k;
            c.drawText(Ui.tr("IPHONE REMOTE"), x, y, kicker);
            y += 72 * k;
            c.drawText(Ui.tr("Your iPhone becomes the remote"), x, y, title);
            y += 80 * k;
            if (done) {
                fill.setColor(0xFF4ADE80);
                c.drawCircle(x + 28 * k, y + 10 * k, 28 * k, fill);
                Symbols.draw(getContext(), c, "check", x + 28 * k, y + 10 * k, 30 * k, 0xFF0B0A10);
                c.drawText(Ui.tr("iPhone paired!"), x + 76 * k, y + 20 * k, title);
                y += 80 * k;
                c.drawText(Ui.tr("Next: Share › Add to Home Screen, in Safari."), x, y, step);
            } else if (pin != null) {
                y = step(c, x, y, "1", Ui.tr("Point the iPhone's camera at this code."), k);
                y = step(c, x, y, "2", Ui.tr("On the iPhone, type this code:"), k);
                float bx = x + 70 * k;
                for (int i = 0; i < pin.length(); i++) {
                    r.set(bx, y - 20 * k, bx + 64 * k, y + 56 * k);
                    fill.setColor(0xFFFFFFFF);
                    c.drawRoundRect(r, 16 * k, 16 * k, fill);
                    String d = pin.substring(i, i + 1);
                    c.drawText(d, r.centerX() - digit.measureText(d) / 2, r.centerY() + Ui.halfCap(digit), digit);
                    bx += 76 * k;
                }
                y += 112 * k;
                y = step(c, x, y, "3", Ui.tr("In Safari: Share › Add to Home Screen."), k);
            }
            c.drawText(Ui.tr("Only on your Wi-Fi. Each new iPhone is accepted here once."), x, 760 * k, small);

            if (paired > 0) {
                String label = Ui.trf("Forget paired iPhones ({n})", "n", paired);
                float bw = button.measureText(label) + 56 * k;
                r.set(x, 790 * k, x + bw, 846 * k);
                fill.setColor(forgetFocused ? 0xFFFFFFFF : 0x1FFFFFFF);
                c.drawRoundRect(r, 28 * k, 28 * k, fill);
                button.setColor(forgetFocused ? 0xFF0B0A10 : 0xFFFFFFFF);
                c.drawText(label, r.left + 28 * k, r.centerY() + Ui.halfCap(button), button);
            }
        }

        private float step(Canvas c, float x, float y, String n, String text, float k) {
            fill.setColor(0x1FFFFFFF);
            c.drawCircle(x + 24 * k, y - 9 * k, 24 * k, fill);
            c.drawText(n, x + 24 * k - number.measureText(n) / 2, y - 9 * k + Ui.halfCap(number), number);
            c.drawText(text, x + 70 * k, y, step);
            return y + 74 * k;
        }
    }
}
