package com.example.nusukhelper;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Path;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Replays the macro: scroll, focus field, enter code, press Check, read result, log, Add another. */
public class NusukAccessService extends AccessibilityService {
    public interface Listener { void onStatus(String s); }

    public static volatile NusukAccessService instance;
    public static volatile Listener listener;

    // reference screen of the original macro (720 x 1600)
    private static final float REF_W = 720f, REF_H = 1600f;
    private volatile boolean running;
    private Thread worker;
    private int sw, sh;

    @Override public void onAccessibilityEvent(AccessibilityEvent e) { }
    @Override public void onInterrupt() { }

    @Override
    protected void onServiceConnected() {
        instance = this;
        DisplayMetrics m = new DisplayMetrics();
        ((WindowManager) getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(m);
        sw = m.widthPixels; sh = m.heightPixels;
    }

    @Override
    public boolean onUnbind(android.content.Intent i) {
        running = false;
        instance = null;
        return super.onUnbind(i);
    }

    public boolean isRunning() { return running; }

    public synchronized void startAuto() {
        if (running) return;
        running = true;
        Awake.hold(this);
        hideKeyboard(true);
        worker = new Thread(this::loop, "nusuk-auto");
        worker.start();
    }

    public void stopAuto() {
        running = false;
        if (worker != null) worker.interrupt();
    }

    private void status(String s) {
        Listener l = listener;
        if (l != null) l.onStatus(s);
    }

    // ---- main loop -------------------------------------------------------

    private void loop() {
        try {
            int fails = 0, lost = 0;
            while (running) {
                String code = Codes.current();
                if (code == null) { status("All codes done"); break; }
                if (!goToEntry()) {
                    // lost: press Back a few times and try again instead of stopping at once
                    if (++lost > 3) { status("Stopped: entry form not found"); break; }
                    status("Entry form not found, pressing Back (" + lost + "/3)");
                    performGlobalAction(GLOBAL_ACTION_BACK);
                    sleep(1500);
                    continue;
                }
                lost = 0;
                status("Entering " + code + " (" + Codes.size() + " left)");

                swipeUp(); sleep(300);
                swipeUp(); sleep(300);
                enterCode(code);
                sleep(300);
                swipeUp(); sleep(300);
                clickCheck();
                sleep(1500);

                String st = null;
                for (int i = 0; i < 6 && st == null && running; i++) {   // poll up to ~9 s for the result
                    st = result();
                    if (st == null) sleep(1500);
                }
                if (st == null) {
                    // unknown result: keep going. The code stays in final.txt and goes to the end of the queue.
                    fails++;
                    status("Result not recognised for " + code + " (skipped, " + fails + "/5 in a row)");
                    if (fails >= 5) { status("Stopped: 5 unrecognised results in a row"); break; }
                    Codes.skip();
                    continue;
                }
                fails = 0;
                if (!Codes.markUsed(st)) { status("Stopped: could not write used.txt"); break; }
                status(st + ": " + code);
            }
        } catch (InterruptedException ie) {
            status("Stopped");
        } finally {
            running = false;
            Awake.release();
            hideKeyboard(false);
        }
    }

    /** Keeps the phone keyboard from popping up while codes are typed in by the service. */
    private void hideKeyboard(boolean hide) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 24) {
                getSoftKeyboardController().setShowMode(hide
                        ? android.accessibilityservice.AccessibilityService.SHOW_MODE_HIDDEN
                        : android.accessibilityservice.AccessibilityService.SHOW_MODE_AUTO);
            }
        } catch (Throwable ignored) { }
    }

    /** Returns SUCCESS / ALREADY_USED / INVALID or null when the screen is not recognised. */
    private String result() {
        String t = screenText().toLowerCase();
        if (t.contains("already has been used")) return "ALREADY_USED";
        if (t.contains("entered code is not correct")) return "INVALID";
        if (t.contains("congratulations") || t.contains("you got a new key")) return "SUCCESS";
        return null;
    }

    private boolean goToEntry() throws InterruptedException {
        for (int i = 0; i < 15 && running; i++) {
            String t = screenText().toLowerCase();
            if (t.contains("add code to get new key") || t.contains("check the code")) return true;
            if (t.contains("congratulations") || t.contains("you got a new key")) {
                AccessibilityNodeInfo n = findByText("add another");
                if (n == null || !clickUp(n)) tapRef(360, 1473);
            }
            sleep(1000);
        }
        return false;
    }

    private void enterCode(String code) throws InterruptedException {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        AccessibilityNodeInfo ed = root == null ? null : findEditable(root);
        if (ed != null) {
            ed.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            ed.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            Bundle b = new Bundle();
            b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, code);
            if (ed.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)) {
                sleep(300);
                if (screenText().contains(code)) return;
            }
        }
        // fallback: tap the field like the macro did, then paste from clipboard
        ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE))
                .setPrimaryClip(ClipData.newPlainText("code", code));
        tapRef(221, 1040); sleep(300);
        tapRef(221, 1040); sleep(300);
        root = getRootInActiveWindow();
        AccessibilityNodeInfo f = root == null ? null : root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (f != null) {
            Bundle b = new Bundle();
            b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "");
            f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
            f.performAction(AccessibilityNodeInfo.ACTION_PASTE);
        }
    }

    private void clickCheck() throws InterruptedException {
        AccessibilityNodeInfo n = findByText("check the code");
        if (n == null || !clickUp(n)) tapRef(221, 1040);
    }

    // ---- screen helpers --------------------------------------------------

    private String screenText() {
        AccessibilityNodeInfo r = getRootInActiveWindow();
        if (r == null) return "";
        StringBuilder sb = new StringBuilder();
        collect(r, sb);
        return sb.toString();
    }

    private void collect(AccessibilityNodeInfo n, StringBuilder sb) {
        if (n == null) return;
        if (n.getText() != null) sb.append(n.getText()).append('\n');
        if (n.getContentDescription() != null) sb.append(n.getContentDescription()).append('\n');
        for (int i = 0; i < n.getChildCount(); i++) collect(n.getChild(i), sb);
    }

    private AccessibilityNodeInfo findByText(String needle) {
        AccessibilityNodeInfo r = getRootInActiveWindow();
        return r == null ? null : findByText(r, needle.toLowerCase());
    }

    private AccessibilityNodeInfo findByText(AccessibilityNodeInfo n, String needle) {
        if (n == null) return null;
        CharSequence t = n.getText(), d = n.getContentDescription();
        if ((t != null && t.toString().toLowerCase().contains(needle))
                || (d != null && d.toString().toLowerCase().contains(needle))) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo f = findByText(n.getChild(i), needle);
            if (f != null) return f;
        }
        return null;
    }

    private AccessibilityNodeInfo findEditable(AccessibilityNodeInfo n) {
        if (n == null) return null;
        if (n.isEditable()) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo f = findEditable(n.getChild(i));
            if (f != null) return f;
        }
        return null;
    }

    private boolean clickUp(AccessibilityNodeInfo n) {
        while (n != null) {
            if (n.isClickable() && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
            n = n.getParent();
        }
        return false;
    }

    // ---- gestures (coordinates scaled from the 720x1600 macro) -----------

    private void swipeUp() throws InterruptedException {
        gesture(360 * sw / REF_W, 1250 * sh / REF_H, 360 * sw / REF_W, 600 * sh / REF_H, 350);
    }

    private void tapRef(float x, float y) throws InterruptedException {
        gesture(x * sw / REF_W, y * sh / REF_H, x * sw / REF_W, y * sh / REF_H, 60);
    }

    private void gesture(float x1, float y1, float x2, float y2, long dur) throws InterruptedException {
        Path p = new Path();
        p.moveTo(x1, y1);
        if (x1 != x2 || y1 != y2) p.lineTo(x2, y2);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, dur)).build();
        CountDownLatch done = new CountDownLatch(1);
        boolean ok = dispatchGesture(g, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription d) { done.countDown(); }
            @Override public void onCancelled(GestureDescription d) { done.countDown(); }
        }, null);
        if (ok) done.await(dur + 2000, TimeUnit.MILLISECONDS);
    }

    private void sleep(long ms) throws InterruptedException {
        if (!running) throw new InterruptedException();
        Thread.sleep(ms);
    }
}
