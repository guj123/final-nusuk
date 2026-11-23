package com.example.nusukhelper;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Pocket mode: an invisible full-screen layer that swallows accidental touches, plus a dimmed screen.
 * It is not black, so screen sharing (Gemini) still sees the real screen.
 * While the app itself sends a gesture, the layer lets touches through for a moment (pass).
 */
public final class PocketLock {
    public static volatile PocketLock current;

    private final WindowManager wm;
    private final int type;
    private final Handler main = new Handler(Looper.getMainLooper());
    private View layer;
    private WindowManager.LayoutParams lp2;
    private volatile boolean locked;

    public PocketLock(WindowManager wm, int windowType) { this.wm = wm; this.type = windowType; }

    public boolean isLocked() { return locked; }

    /** Adds the layer, then puts the control box back on top of it. */
    public void lock(Context c, View box, WindowManager.LayoutParams boxLp) {
        if (locked) return;
        layer = new View(c);
        layer.setOnTouchListener((v, e) -> true);
        lp2 = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT);
        lp2.screenBrightness = 0.02f;
        wm.addView(layer, lp2);
        try { wm.removeView(box); wm.addView(box, boxLp); } catch (Exception ignored) { }
        locked = true;
        current = this;
    }

    public void unlock() {
        locked = false;
        if (current == this) current = null;
        if (layer != null) { try { wm.removeView(layer); } catch (Exception ignored) { } layer = null; }
    }

    /** true: touches pass through (the app is about to send a gesture); false: block again. */
    public void pass(final boolean on) {
        if (!locked || layer == null) return;
        final Runnable r = new Runnable() {
            @Override public void run() {
                if (layer == null) return;
                if (on) lp2.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                else lp2.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                try { wm.updateViewLayout(layer, lp2); } catch (Exception ignored) { }
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) { r.run(); return; }
        final CountDownLatch l = new CountDownLatch(1);
        main.post(() -> { r.run(); l.countDown(); });
        try { l.await(500, TimeUnit.MILLISECONDS); if (on) Thread.sleep(80); } catch (InterruptedException ignored) { }
    }

    /** Compact single-line button: no more letters wrapping onto separate lines. */
    public static Button button(Context c, String t, View.OnClickListener l) {
        float d = c.getResources().getDisplayMetrics().density;
        Button b = new Button(c);
        b.setText(t);
        b.setAllCaps(false);
        b.setSingleLine(true);
        b.setTextSize(12);
        b.setMinWidth(0); b.setMinimumWidth(0); b.setMinHeight(0); b.setMinimumHeight((int) (36 * d));
        b.setPadding((int) (10 * d), (int) (4 * d), (int) (10 * d), (int) (4 * d));
        b.setOnClickListener(l);
        return b;
    }
}
