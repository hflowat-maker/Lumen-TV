package io.freemytv;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.TextPaint;
import android.view.KeyEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Écouteurs": the sound output panel, on the right over the home screen. The paired headphones are listed and one
 * press of OK connects them (or disconnects them: "TV speakers" brings the sound back). Pairing new ones happens once
 * in the TV's Bluetooth settings.
 */
public class AudioActivity extends Activity {
    private static final int ACCENT = 0xFFF2B84B;
    private static final int TV = 0, DEVICE = 1, PAIR = 2;
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();

    private static final class Row {
        int kind;
        Bt.Device device;
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Row> rows = new ArrayList<>();
    private Bt.Result result;
    private boolean loading = true;
    private String busy;         // address being connected or disconnected
    private boolean busyOn;
    private String message;
    private int focus;
    private PanelView view;
    private boolean alive;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        view = new PanelView(this);
        setContentView(view);
        rebuild();
    }

    @Override protected void onResume() {
        super.onResume();
        alive = true;
        refresh(true, true);
    }

    @Override protected void onPause() {
        alive = false;
        handler.removeCallbacksAndMessages(null);
        super.onPause();
    }

    private void refresh(final boolean fresh, final boolean pickFocus) {
        final Context c = getApplicationContext();
        WORK.execute(() -> {
            final Bt.Result r = Bt.audio(c, fresh);
            handler.post(() -> {
                result = r;
                loading = false;
                rebuild();
                if (pickFocus) {
                    // One press: the focus starts on the headphones (the first ones, connected ones first).
                    focus = rows.size() - 1;    // none paired yet: "Pair other headphones"
                    for (int i = 0; i < rows.size(); i++) if (rows.get(i).kind == DEVICE) { focus = i; break; }
                }
                view.invalidate();
            });
        });
    }

    private void rebuild() {
        rows.clear();
        Row tv = new Row();
        tv.kind = TV;
        rows.add(tv);
        if (result != null) {
            for (Bt.Device d : result.devices) {
                Row r = new Row();
                r.kind = DEVICE;
                r.device = d;
                rows.add(r);
            }
        }
        Row pair = new Row();
        pair.kind = PAIR;
        rows.add(pair);
        focus = Math.max(0, Math.min(focus, rows.size() - 1));
    }

    private boolean anyConnected() {
        if (result == null) return false;
        for (Bt.Device d : result.devices) if (d.connected) return true;
        return false;
    }

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (focus > 0) focus--;
                view.invalidate();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (focus < rows.size() - 1) focus++;
                view.invalidate();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (e.getRepeatCount() == 0) activate(rows.get(focus));
                return true;
            default:
                return super.onKeyDown(code, e);
        }
    }

    private void activate(Row row) {
        if (busy != null) return;
        if (row.kind == PAIR) {
            openBluetoothSettings();
            return;
        }
        if (row.kind == TV) {
            // Back to the TV's speakers: disconnect whatever headphones are on.
            if (result != null) for (Bt.Device d : result.devices) if (d.connected) { connect(d, false); return; }
            return;
        }
        connect(row.device, !row.device.connected);
    }

    private void connect(final Bt.Device d, final boolean on) {
        busy = d.address;
        busyOn = on;
        message = null;
        view.invalidate();
        final Context c = getApplicationContext();
        WORK.execute(() -> {
            String why = Bt.connect(c, d.address, on);
            // The headphones take a moment: check again until they show the new state (up to ~8 s).
            Bt.Result r = null;
            boolean reached = false;
            for (int i = 0; why == null && i < 8 && !reached; i++) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {
                }
                r = Bt.audio(c, true);
                for (Bt.Device x : r.devices) if (x.address.equals(d.address)) reached = x.connected == on;
            }
            final Bt.Result fr = r;
            final String fwhy = why != null ? why : reached ? null : (on ? Ui.tr("The headphones didn't answer. Are they on and close by?") : null);
            handler.post(() -> {
                busy = null;
                if (fr != null) result = fr;
                message = fwhy;
                rebuild();
                view.invalidate();
                if (fwhy == null && on && alive) handler.postDelayed(this::finish, 1200);   // done: back to what you were watching
            });
        });
    }

    private void openBluetoothSettings() {
        Intent[] tries = {
            new Intent(Settings.ACTION_BLUETOOTH_SETTINGS),
            new Intent("com.google.android.intent.action.CONNECT_INPUT"),
            new Intent(Settings.ACTION_SETTINGS),
        };
        for (Intent i : tries) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ComponentName target = i.resolveActivity(getPackageManager());
            if (target == null) continue;
            try {
                startActivity(i);
                finish();
                return;
            } catch (RuntimeException ignored) {
            }
        }
        message = Ui.tr("Couldn't open the Bluetooth settings.");
        view.invalidate();
    }

    private final class PanelView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private Paint kicker, title, text, rowTitle, rowText, pill;
        private final TextPaint fit = new TextPaint();

        PanelView(Context c) {
            super(c);
            setFocusable(true);
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            float k = w / 1920f;
            kicker = Ui.text(Ui.semibold, 20 * k, ACCENT);
            kicker.setLetterSpacing(0.18f);
            title = Ui.text(Ui.displaySemibold, 60 * k, 0xFFFFFFFF);
            text = Ui.text(Ui.regular, 25 * k, 0xBFFFFFFF);
            rowTitle = Ui.text(Ui.semibold, 28 * k, 0xFFFFFFFF);
            rowText = Ui.text(Ui.regular, 22 * k, 0xB3FFFFFF);
            pill = Ui.text(Ui.semibold, 22 * k, 0xFF0B0A10);
        }

        @Override protected void onDraw(Canvas c) {
            if (kicker == null) return;
            float k = getWidth() / 1920f, h = getHeight();
            float left = 1240 * k;
            fill.setColor(0xF0100E16);
            c.drawRect(left, 0, getWidth(), h, fill);
            fill.setColor(0x1AFFFFFF);
            c.drawRect(left, 0, left + 1.5f * k, h, fill);

            float x = left + 64 * k, right = getWidth() - 64 * k, y = 120 * k;
            c.drawText(Ui.tr("SOUND OUTPUT"), x, y, kicker);
            y += 76 * k;
            c.drawText(Ui.tr("Headphones"), x, y, title);
            y += 52 * k;
            c.drawText(loading ? Ui.tr("Looking for your headphones…") : Ui.tr("Choose where the sound goes. One press is enough."), x, y, text);
            y += 52 * k;

            boolean connected = anyConnected();
            for (int i = 0; i < rows.size(); i++) {
                Row row = rows.get(i);
                boolean f = i == focus;
                r.set(x, y, right, y + 118 * k);
                fill.setColor(f ? 0xFFFFFFFF : 0x0FFFFFFF);
                c.drawRoundRect(r, 24 * k, 24 * k, fill);
                int ink = f ? 0xFF0B0A10 : 0xFFFFFFFF, dim = f ? 0xFF4A4552 : 0xB3FFFFFF;
                String symbol, t, sub, action = null;
                if (row.kind == TV) {
                    symbol = "tv";
                    t = Ui.tr("TV speakers");
                    sub = connected ? Ui.tr("Press to bring the sound back here") : Ui.tr("The sound plays here right now");
                } else if (row.kind == DEVICE) {
                    symbol = "headphones";
                    t = row.device.name;
                    boolean isBusy = row.device.address.equals(busy);
                    sub = isBusy ? (busyOn ? Ui.tr("Connecting…") : Ui.tr("Disconnecting…"))
                            : row.device.connected ? Ui.tr("Connected") : Ui.tr("Paired · ready to connect");
                    if (!isBusy) action = row.device.connected ? Ui.tr("Disconnect") : Ui.tr("Connect");
                } else {
                    symbol = "settings";
                    t = Ui.tr("Pair other headphones");
                    sub = Ui.tr("Opens the TV's Bluetooth settings");
                }
                Symbols.draw(getContext(), c, symbol, x + 56 * k, r.centerY(), 30 * k, ink);
                float tx = x + 104 * k, maxW = right - tx - (action != null ? 210 * k : 24 * k);
                rowTitle.setColor(ink);
                fit.set(rowTitle);
                c.drawText(TextUtils.ellipsize(t, fit, maxW, TextUtils.TruncateAt.END).toString(), tx, r.centerY() - 6 * k, rowTitle);
                rowText.setColor(dim);
                fit.set(rowText);
                c.drawText(TextUtils.ellipsize(sub, fit, maxW, TextUtils.TruncateAt.END).toString(), tx, r.centerY() + 32 * k, rowText);
                if (row.kind == TV && !connected) Symbols.draw(getContext(), c, "check", right - 52 * k, r.centerY(), 30 * k, ink);
                if (action != null) {
                    float pw = pill.measureText(action) + 52 * k;
                    RectF p = new RectF(right - 28 * k - pw, r.centerY() - 26 * k, right - 28 * k, r.centerY() + 26 * k);
                    fill.setColor(row.device.connected ? (f ? 0xFFE5E5EA : 0x33FFFFFF) : ACCENT);
                    c.drawRoundRect(p, 26 * k, 26 * k, fill);
                    pill.setColor(row.device.connected && !f ? 0xFFFFFFFF : 0xFF0B0A10);
                    c.drawText(action, p.left + 26 * k, p.centerY() + Ui.halfCap(pill), pill);
                }
                y += 134 * k;
            }

            String bottom = message;
            if (bottom == null && result != null && result.off) bottom = Ui.tr("Bluetooth is off on the TV.");
            if (bottom == null && result != null && result.error != null && result.devices.isEmpty()) {
                bottom = Ui.trf("Couldn't read the Bluetooth: {why}", "why", result.error);
            }
            if (bottom == null && result != null && result.devices.isEmpty()) {
                bottom = Ui.tr("Pair your headphones once in the TV's Bluetooth settings; after that, one press here connects them.");
            }
            if (bottom == null) bottom = Ui.tr("When you turn your headphones off, the sound comes back to the TV by itself.");
            fit.set(text);
            android.text.StaticLayout lines = android.text.StaticLayout.Builder.obtain(bottom, 0, bottom.length(), fit,
                    Math.max(1, Math.round(right - x))).setMaxLines(3).setEllipsize(TextUtils.TruncateAt.END).build();
            c.save();
            c.translate(x, h - 64 * k - lines.getHeight());
            lines.draw(c);
            c.restore();
        }
    }
}
