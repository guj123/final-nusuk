package com.example.nusukhelper;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Quiz mode (separate from code-entry mode). Answers from its own memory (quiz_answers.json, offline)
 * when the question is known; otherwise asks your AI endpoint (any OpenAI-compatible API) and learns from the result.
 * It also learns when Auto is off: it notes the option you tap yourself and reads Nusuk's right/wrong page.
 * If the AI cannot answer (network, rate limit, bad reply) it taps NOTHING and tries again; it never guesses.
 */
public class QuizAccessService extends NusukAccessService {
    public static volatile QuizAccessService instance;

    /** Press "Check your answer" after choosing an option (the door macro did the same). */
    private static final boolean PRESS_CHECK = true;
    /** Doors to play per Start press (each door costs one key). */
    private static final int MAX_DOORS = 0;   // 0 = keep going until you press Stop
    private static final String NUSUK = "com.moh.nusukapp";
    private static final long RESULT_TIMEOUT_MS = 20000;
    /** After this many failed AI calls in a row on one question, Auto stops (nothing is ever tapped blindly). */
    private static final int MAX_API_FAILS = 6;

    private static class Item {
        String text;
        AccessibilityNodeInfo click;
        boolean selected;
        final Rect bounds = new Rect();
    }

    private static class Snap {
        List<Item> options = new ArrayList<>();     // empty unless exactly 4 found
        String question = "";
        int candidates;
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private LinearLayout box;
    private TextView statusTv;
    private WindowManager.LayoutParams lp;
    private Button autoBtn;
    private boolean auto;
    private boolean locked;
    private LinearLayout btnRow;
    private PocketLock pocket;
    private Button lockBtn;

    // AI-call state (one request at a time; the answer is only used if it is for the question still on screen)
    private volatile boolean apiBusy;
    private volatile String apiForKey = "";
    private volatile String apiSource = "api";
    private volatile int apiIdx = -1;
    private volatile String apiErr;
    private volatile boolean apiFatal;
    private volatile long apiWaitUntil;
    private int apiFails;
    private String apiLastErr = "";

    private String lastKey = "";
    private String watchKey = "";
    private List<String> pendingOpts = new ArrayList<>();
    private volatile String clickedText = "";
    private volatile long clickedAt;
    private boolean handled;
    private String pendingQ, pendingA;        // answer given, waiting for the result page
    private long pendingAt;
    private int doorsDone, doorTick;
    private long lastClickAt, questionSince;
    private AnswerStore store;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!auto) return;
            try { step(false); } catch (Exception e) { status("Error: " + e.getMessage()); }
            ui.postDelayed(this, 700);
        }
    };

    /** Runs whenever the box is shown. With Auto off it only watches and learns (never taps). */
    private final Runnable watchTick = new Runnable() {
        @Override public void run() {
            if (box == null) return;
            try { watch(); } catch (Exception e) { status("Error: " + e.getMessage()); }
            ui.postDelayed(this, 700);
        }
    };

    private long lastScrollAt;
    private int scrollTries;

    /** Remembers the text of the last tap inside Nusuk, so a manual answer can be matched to an option. */
    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_VIEW_CLICKED) return;
        CharSequence pk = e.getPackageName();
        if (pk == null || !pk.toString().equals(NUSUK)) return;
        StringBuilder sb = new StringBuilder();
        if (e.getText() != null) for (CharSequence c : e.getText()) if (c != null) sb.append(c).append('\n');
        if (e.getContentDescription() != null) sb.append(e.getContentDescription()).append('\n');
        AccessibilityNodeInfo src = e.getSource();
        if (src != null) {
            List<Item> tmp = new ArrayList<>();
            gather(src, tmp, 0);
            for (Item it : tmp) sb.append(it.text).append('\n');
        }
        clickedText = sb.toString();
        clickedAt = System.currentTimeMillis();
    }
    @Override public void onInterrupt() { }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();          // codes mode (NusukAccessService) is set up as before
        instance = this;
    }

    @Override
    public boolean onUnbind(Intent i) {
        hideBox();
        instance = null;
        return super.onUnbind(i);
    }

    // ---- floating box ----------------------------------------------------

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    public void showBox() {
        if (box != null) return;
        // Quiz mode and code-entry mode are separate: switch the code-entry box off first.
        stopService(new Intent(this, OverlayService.class));

        openStore();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(0xE6202020);
        box.setPadding(dp(8), dp(6), dp(8), dp(6));

        statusTv = new TextView(this);
        statusTv.setTextColor(0xFFFFFFFF);
        statusTv.setTextSize(12);
        statusTv.setTypeface(Typeface.MONOSPACE);
        statusTv.setMaxWidth(dp(260));
        statusTv.setText("Quiz mode ready (" + store.size() + " answers known)");
        box.addView(statusTv);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        autoBtn = button("Auto", v -> toggleAuto());
        row.addView(autoBtn);
        lockBtn = button("Lock", v -> setLocked(true));
        row.addView(lockBtn);
        row.addView(button("X", v -> hideBox()));
        btnRow = row;
        box.addView(row);

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = dp(8);
        lp.y = dp(220);
        statusTv.setOnTouchListener(new View.OnTouchListener() {
            float dx, dy; int sx, sy; long down, lastTap; boolean moved;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN: dx = e.getRawX(); dy = e.getRawY(); sx = lp.x; sy = lp.y; down = e.getEventTime(); moved = false; return true;
                    case MotionEvent.ACTION_UP:
                        if (locked) {                                           // double tap to unlock
                            if (!moved && e.getEventTime() - down < 500) {
                                if (e.getEventTime() - lastTap < 600) { lastTap = 0; setLocked(false); }
                                else lastTap = e.getEventTime();
                            }
                        }
                        else if (!moved && e.getEventTime() - down < 600) dump();   // tap the text = save a screen dump
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (Math.abs(e.getRawX() - dx) + Math.abs(e.getRawY() - dy) > 20) moved = true;
                        lp.x = sx + (int) (e.getRawX() - dx); lp.y = sy + (int) (e.getRawY() - dy);
                        wm.updateViewLayout(box, lp); return true;
                }
                return false;
            }
        });
        wm.addView(box, lp);
        ui.removeCallbacks(watchTick);
        ui.post(watchTick);
    }

    public void hideBox() {
        if (locked) setLocked(false);
        auto = false;
        ui.removeCallbacks(tick);
        ui.removeCallbacks(watchTick);
        Awake.release();
        if (box != null && wm != null) {
            try { wm.removeView(box); } catch (Exception ignored) { }
        }
        box = null;
    }

    private void setLocked(boolean on) {
        if (on == locked || wm == null || box == null) return;
        locked = on;
        if (on) {
            pocket = new PocketLock(wm, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY);
            pocket.lock(this, box, lp);
            btnRow.setVisibility(View.GONE);
            status("Locked: double-tap this text to unlock");
        } else {
            if (pocket != null) pocket.unlock();
            pocket = null;
            btnRow.setVisibility(View.VISIBLE);
            status("Unlocked");
        }
    }

    private Button button(String t, View.OnClickListener c) { return PocketLock.button(this, t, c); }

    private void toggleAuto() {
        auto = !auto;
        autoBtn.setText(auto ? "Stop" : "Auto");
        if (auto) {
            lastKey = ""; pendingQ = null; doorsDone = 0; doorTick = 0;
            apiFails = 0; apiLastErr = ""; apiFatal = false; apiErr = null; apiIdx = -1; apiForKey = ""; apiWaitUntil = 0;
            Awake.hold(this);
            ui.post(tick);
            status("Auto on: waiting for a question");
        } else {
            ui.removeCallbacks(tick);
            Awake.release();
            status("Auto off");
        }
    }

    private void status(String s) {
        ui.post(() -> { if (statusTv != null) statusTv.setText(s); });
    }

    // ---- reading the screen ----------------------------------------------

    private Snap snapshot() { return snapshotOf(appItems()); }

    private Snap snapshotOf(List<Item> items) {
        Snap s = new Snap();

        List<QuizLogic.Row> rows = new ArrayList<>();
        for (Item it : items) rows.add(new QuizLogic.Row(it.text, it.bounds.top, it.bounds.bottom, it.click != null));
        QuizLogic.Result res = QuizLogic.analyze(rows);
        s.candidates = res.candidates;
        s.question = res.question;
        for (int i : res.options) s.options.add(items.get(i));
        return s;
    }

    private void gather(AccessibilityNodeInfo n, List<Item> items, int depth) {
        if (n == null || depth > 40) return;
        CharSequence t = n.getText();
        if (t == null) t = n.getContentDescription();
        if (t != null && t.toString().trim().length() > 0) {
            Item it = new Item();
            it.text = t.toString();
            it.click = clickable(n);
            it.selected = n.isSelected() || n.isChecked()
                    || (it.click != null && (it.click.isSelected() || it.click.isChecked()));
            n.getBoundsInScreen(it.bounds);
            items.add(it);
        }
        for (int i = 0; i < n.getChildCount(); i++) gather(n.getChild(i), items, depth + 1);
    }

    private AccessibilityNodeInfo clickable(AccessibilityNodeInfo n) {
        for (int i = 0; n != null && i < 8; i++) {
            if (n.isClickable()) return n;
            n = n.getParent();
        }
        return null;
    }

    // ---- main step -------------------------------------------------------

    private boolean clickText(List<Item> items, String start) {
        for (Item it : items) {
            if (it.text.trim().toLowerCase().startsWith(start)) { clickItem(it); lastClickAt = System.currentTimeMillis(); return true; }
        }
        return false;
    }

    private static String allText(List<Item> items) {
        StringBuilder sb = new StringBuilder();
        for (Item it : items) sb.append(it.text.trim().replace("\n", " ")).append(" | ");
        return sb.toString();
    }

    private void step(boolean manual) {
        List<Item> app = appItems();
        String text = allText(app);
        int kind = QuizLogic.kind(text);
        long now = System.currentTimeMillis();

        if (!manual && kind != QuizLogic.OTHER) {
            if (kind == QuizLogic.RESULT) { onResult(app, text); return; }
            if (kind == QuizLogic.CONFIRM) {
                if (now - lastClickAt > 1500) { clickText(app, "open the door"); status("Opening the door"); }
                return;
            }
            if (kind == QuizLogic.DOORS) {
                int keys = keyCount(app);
                if (keys == 0) {
                    auto = false; ui.removeCallbacks(tick); Awake.release();
                    ui.post(() -> { if (autoBtn != null) autoBtn.setText("Auto"); });
                    status("Stopped: 0 keys left (" + doorsDone + " doors played)");
                    return;
                }
                if (MAX_DOORS > 0 && doorsDone >= MAX_DOORS) {
                    auto = false; ui.removeCallbacks(tick); Awake.release();
                    ui.post(() -> { if (autoBtn != null) autoBtn.setText("Auto"); });
                    status("Done: " + doorsDone + " doors played");
                    return;
                }
                if (now - lastClickAt < 2500) return;
                List<QuizLogic.Row> rows = new ArrayList<>();
                for (Item it : app) rows.add(new QuizLogic.Row(it.text, it.bounds.top, it.bounds.bottom, it.click != null));
                List<Integer> doors = QuizLogic.doors(rows);
                if (doors.isEmpty()) { status("No doors found"); return; }
                Item d = app.get(doors.get(doorTick++ % doors.size()));
                clickItem(d); lastClickAt = now;
                status("Door " + (doorsDone + 1) + ": " + shorten(d.text));
                return;
            }
        }

        // question screen
        Snap s = snapshot();
        if (s.options.size() != 4) {
            if (manual) status("No 4-option question found (" + s.candidates + " clickable texts). Press Dump.");
            else if (kind == QuizLogic.OTHER && s.candidates >= 2 && s.candidates < 4 && scrollTries < 3 && now - lastScrollAt > 2500) {
                scrollTries++; lastScrollAt = now;      // the 4th option is below the screen: scroll
                scrollScreen();
                status("Scrolling to reach all 4 options");
                return;
            }
            else if (pendingQ != null && now - pendingAt > RESULT_TIMEOUT_MS) giveUpPending();
            return;
        }
        StringBuilder k = new StringBuilder(s.question);
        for (Item o : s.options) k.append('|').append(o.text);
        String key = k.toString();
        boolean isNew = !key.equals(lastKey);
        if (isNew) {
            lastKey = key; handled = false;
            questionSince = now;
            apiIdx = -1; apiErr = null; apiFails = 0; apiWaitUntil = 0;     // forget the previous question's answer
        }
        if (handled && !manual) {
            // answered but the result page never came: press Check again once, then give up
            if (pendingQ != null && now - pendingAt > 8000 && now - lastClickAt > 5000) {
                if (now - pendingAt > RESULT_TIMEOUT_MS) giveUpPending();
                else { pressCheck(); lastClickAt = now; }
            }
            return;
        }

        // 1) known from memory (works offline, no API call needed)
        List<String> texts = new ArrayList<>();
        for (Item o : s.options) texts.add(o.text);
        int known = store.find(s.question, texts);
        if (known >= 0) { act(s, known, "memory"); return; }

        // 2) otherwise ask the AI endpoint. Only a clear option number from it is ever tapped; any failure = wait and retry.
        if (apiFatal) { return; }                       // Auto was already stopped with a message
        if (apiIdx >= 0 && key.equals(apiForKey)) { act(s, apiIdx, apiSource); return; }
        if (apiBusy) { status("Asking AI…"); return; }
        if (apiErr != null && key.equals(apiForKey)) {
            apiFails++;
            String err = apiErr;
            apiLastErr = err;
            apiErr = null;
            if (apiFatal || apiFails >= MAX_API_FAILS) {
                stopAuto("Stopped, nothing tapped: " + err);
                return;
            }
            status("AI failed (" + apiFails + "/" + MAX_API_FAILS + "), will retry: " + shorten(err));
            return;
        }
        if (now < apiWaitUntil) { status("Retry in " + ((apiWaitUntil - now) / 1000 + 1) + " s (" + apiFails + "/" + MAX_API_FAILS + "): " + shorten(apiLastErr)); return; }
        askLlm(key, s.question, texts);
        status("Asking AI: " + shorten(s.question));
    }

    private void stopAuto(String msg) {
        auto = false;
        ui.removeCallbacks(tick);
        Awake.release();
        ui.post(() -> { if (autoBtn != null) autoBtn.setText("Auto"); });
        status(msg);
    }

    private static final class Ep { String url, key, model; }

    /** Endpoint 1 is always used (so its problem is shown); endpoint 2 is a backup, used only when it has a key. */
    private List<Ep> endpoints() {
        android.content.SharedPreferences sp = getSharedPreferences("nh", MODE_PRIVATE);
        List<Ep> l = new ArrayList<>();
        Ep a = new Ep();
        a.url = sp.getString("ep1_url", LlmClient.DEFAULT_URL);
        a.key = sp.getString("ep1_key", sp.getString("groq_key", ""));
        a.model = sp.getString("ep1_model", sp.getString("groq_model", LlmClient.DEFAULT_MODEL));
        l.add(a);
        Ep b = new Ep();
        b.url = sp.getString("ep2_url", "");
        b.key = sp.getString("ep2_key", "");
        b.model = sp.getString("ep2_model", "");
        if (!b.key.trim().isEmpty()) l.add(b);
        return l;
    }

    /** One request at a time, off the main thread. The result is kept only for the question it was asked for. */
    private void askLlm(final String key, final String question, final List<String> options) {
        // Leave out options that Nusuk already told us are wrong for this question.
        final List<Integer> map = new ArrayList<>();
        final List<String> remaining = new ArrayList<>();
        for (int i = 0; i < options.size(); i++) {
            if (!store.isWrong(question, options.get(i))) { map.add(i); remaining.add(options.get(i)); }
        }
        if (remaining.size() < 2) {
            map.clear(); remaining.clear();
            for (int i = 0; i < options.size(); i++) { map.add(i); remaining.add(options.get(i)); }
        }
        final List<Ep> eps = endpoints();
        apiBusy = true;
        new Thread(() -> {
            int idx = -1;
            String source = "api";
            StringBuilder errs = new StringBuilder();
            boolean allFatal = true;
            long wait = 0;
            try {
                for (int n = 0; n < eps.size(); n++) {
                    Ep e = eps.get(n);
                    LlmClient.Result r = LlmClient.ask(e.url, e.key, e.model, question, remaining);
                    if (r.idx >= 0) { idx = map.get(r.idx); source = "api" + (n + 1); break; }
                    errs.append(errs.length() > 0 ? " | " : "").append(eps.size() > 1 ? (n + 1) + ": " : "").append(r.error);
                    if (!r.fatal) { allFatal = false; wait = Math.max(wait, r.retryAfterMs); }
                }
            } catch (Exception e) {
                errs.append("AI error: ").append(e.getMessage());
                allFatal = false; wait = 4000;
            }
            apiForKey = key;
            apiSource = source;
            apiIdx = idx;
            apiErr = idx >= 0 ? null : errs.toString();
            apiFatal = idx < 0 && allFatal;
            apiWaitUntil = System.currentTimeMillis() + wait;
            final String fatalMsg = errs.toString();
            if (apiFatal) ui.post(() -> stopAuto("Stopped, nothing tapped: " + fatalMsg));
            apiBusy = false;
        }).start();
    }

    private void act(Snap s, int idx, String source) {
        final Item it = s.options.get(idx);
        final String question = s.question, answer = it.text;
        handled = true;
        if (source.startsWith("api")) {
            store.record(question, answer, false);
            saveStore();
        }
        log(s, idx, source);
        pendingQ = question; pendingA = answer; pendingAt = System.currentTimeMillis();
        pendingOpts = new ArrayList<>();
        for (Item o : s.options) pendingOpts.add(o.text);
        clickOption(it, answer, idx, source, 0);
    }

    /** True when the option is off the screen. */
    private boolean covered(Item it) {
        int h = getResources().getDisplayMetrics().heightPixels;
        return it.bounds.bottom > h || it.bounds.top < 0;
    }

    private void clickOption(Item it, final String answer, final int idx, final String source, final int tries) {
        if (tries < 3 && covered(it)) {
            scrollScreen();
            status("Scrolling: option " + (idx + 1) + " is covered");
            ui.postDelayed(() -> {
                Item again = null;
                for (Item x : appItems()) if (x.text.trim().equalsIgnoreCase(answer.trim()) && x.click != null) { again = x; break; }
                clickOption(again != null ? again : it, answer, idx, source, tries + 1);
            }, 900);
            return;
        }
        boolean ok = clickItem(it);
        status((ok ? "Selected " : "Tap failed for ") + (idx + 1) + " (" + source + "): " + shorten(answer));
        if (ok && PRESS_CHECK) ui.postDelayed(this::pressCheck, 700);
    }

    /** Scrolls the Nusuk screen up (reveals options hidden below the screen). */
    private void scrollScreen() {
        AccessibilityNodeInfo root = null;
        for (AccessibilityWindowInfo w : getWindows()) {
            AccessibilityNodeInfo r = w.getRoot();
            if (r != null && r.getPackageName() != null && r.getPackageName().toString().equals(NUSUK)
                    && w.getType() == AccessibilityWindowInfo.TYPE_APPLICATION) { root = r; break; }
        }
        if (root != null && scrollForward(root, 0)) return;
        int w = getResources().getDisplayMetrics().widthPixels, h = getResources().getDisplayMetrics().heightPixels;
        Path p = new Path();
        p.moveTo(w * 0.5f, h * 0.70f);
        p.lineTo(w * 0.5f, h * 0.35f);
        final PocketLock pl = PocketLock.current;
        if (pl != null) { pl.pass(true); ui.postDelayed(() -> pl.pass(false), 700); }
        dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 350)).build(), null, null);
    }

    private boolean scrollForward(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 30) return false;
        if (n.isScrollable() && n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true;
        for (int i = 0; i < n.getChildCount(); i++) if (scrollForward(n.getChild(i), depth + 1)) return true;
        return false;
    }

    /** Result page (right or wrong, both have "Play again"): learn, then press Play again. */
    private void onResult(List<Item> app, String text) {
        long now = System.currentTimeMillis();
        if (now - lastClickAt < 1500) return;
        if (pendingQ != null) learnFromResult(text, now);
        lastKey = ""; handled = false; scrollTries = 0;
        doorsDone++;
        clickText(app, "play again");
    }

    /** Learns from Nusuk's result page, for the answer Auto tapped OR the one you tapped yourself. */
    private void learnFromResult(String text, long now) {
        int r = ResultDetector.classify(text);
        String label = r == ResultDetector.RIGHT ? "RIGHT" : r == ResultDetector.WRONG ? "WRONG" : "UNKNOWN";
        String extra = "";
        if (r == ResultDetector.RIGHT) store.markRight(pendingQ, pendingA);
        else if (r == ResultDetector.WRONG) {
            store.markWrong(pendingQ, pendingA);
            String good = ResultDetector.correctAnswer(text, pendingOpts);     // only if the page itself names the right option
            if (good != null && !AnswerStore.norm(good).equals(AnswerStore.norm(pendingA))) {
                store.markRight(pendingQ, good);
                extra = " (page showed right answer: " + shorten(good) + ")";
            }
        }
        saveStore();
        String t = text.length() > 500 ? text.substring(0, 500) : text;
        appendLine("quiz_result_log.txt", now() + " [" + label + "] " + pendingQ + " => " + pendingA + extra + "\n    screen: " + t);
        status(label + extra + " | " + store.size() + " answers known");
        pendingQ = null;
    }

    // ---- learning while Auto is off ----------------------------------------

    /** Index of the option you tapped: the last tap that matches exactly one option, else the one option shown as selected. */
    private int pickedIndex(List<Item> opts) {
        List<String> lines = new ArrayList<>();
        for (String l : clickedText.split("\n")) { String n = AnswerStore.norm(l); if (!n.isEmpty()) lines.add(n); }
        if (!lines.isEmpty()) {
            int hit = -1, count = 0;
            for (int i = 0; i < opts.size(); i++) {
                if (lines.contains(AnswerStore.norm(opts.get(i).text))) { hit = i; count++; }
            }
            if (count == 1) return hit;
        }
        int sel = -1, cnt = 0;
        for (int i = 0; i < opts.size(); i++) if (opts.get(i).selected) { sel = i; cnt++; }
        return cnt == 1 ? sel : -1;
    }

    /** Auto is off: note which option you answered, and when Nusuk shows the result, learn from it. Never taps. */
    private void watch() {
        if (auto || store == null) return;
        List<Item> app = appItems();
        int kind = QuizLogic.kind(allText(app));
        long now = System.currentTimeMillis();
        if (kind == QuizLogic.RESULT) {
            if (pendingQ != null) learnFromResult(allText(app), now);
            watchKey = "";
            return;
        }
        if (kind != QuizLogic.OTHER) return;
        Snap s = snapshotOf(app);
        if (s.options.size() != 4) return;
        StringBuilder k = new StringBuilder(s.question);
        for (Item o : s.options) k.append('|').append(o.text);
        if (!k.toString().equals(watchKey)) {
            watchKey = k.toString();
            clickedText = "";
            if (pendingQ != null && !pendingQ.equals(s.question)) pendingQ = null;
        }
        int p = pickedIndex(s.options);
        if (p < 0) return;
        String a = s.options.get(p).text;
        if (pendingQ == null || !s.question.equals(pendingQ) || !a.equals(pendingA)) {
            pendingQ = s.question; pendingA = a; pendingAt = now;
            pendingOpts = new ArrayList<>();
            for (Item o : s.options) pendingOpts.add(o.text);
            status("Noted your answer: " + shorten(a) + " (learns from the result)");
        }
    }

    /** Key count shown at the top of the doors screen (digits only text near the top), or -1 if not found. */
    private int keyCount(List<Item> app) {
        int limit = getResources().getDisplayMetrics().heightPixels / 6;
        for (Item it : app) {
            String t = it.text.trim();
            if (it.bounds.top < limit && t.matches("\\d{1,6}")) return Integer.parseInt(t);
        }
        return -1;
    }

    private void giveUpPending() {
        appendLine("quiz_result_log.txt", now() + " [UNKNOWN] " + pendingQ + " => " + pendingA + "\n    screen: no result page seen");
        status("No result page seen, continuing");
        pendingQ = null; lastKey = ""; handled = false;
    }

    /** Text items of the Nusuk app window. */
    private List<Item> appItems() {
        List<Item> items = new ArrayList<>();
        for (AccessibilityWindowInfo w : getWindows()) {
            AccessibilityNodeInfo r = w.getRoot();
            if (r == null || r.getPackageName() == null) continue;
            String pkg = r.getPackageName().toString();
            if (!pkg.equals(NUSUK) || w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
            gather(r, items, 0);
            break;
        }
        return items;
    }

    private void pressCheck() {
        for (Item it : appItems()) {
            if (it.text.trim().toLowerCase().startsWith("check") && it.click != null) {
                clickItem(it);
                status("Pressed: " + it.text.trim());
                return;
            }
        }
    }

    private static String shorten(String s) { return s.length() > 60 ? s.substring(0, 57) + "..." : s; }

    private boolean clickItem(Item it) {
        if (it.click != null && it.click.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        Path p = new Path();
        p.moveTo(it.bounds.centerX(), it.bounds.centerY());
        final PocketLock pl = PocketLock.current;
        if (pl != null) { pl.pass(true); ui.postDelayed(() -> pl.pass(false), 600); }
        return dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 60)).build(), null, null);
    }

    // ---- files -----------------------------------------------------------

    private File dir() {
        return new File(getSharedPreferences("nh", MODE_PRIVATE).getString("dir", Codes.DEFAULT_DIR));
    }

    private static String q(String s) { return "\"" + s.replace("\"", "\"\"").replace("\n", " ") + "\""; }

    private static String now() { return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()); }

    private void openStore() {
        store = new AnswerStore(new File(dir(), "quiz_answers.json"));
        try { store.load(); } catch (Exception e) { status("quiz_answers.json unreadable: " + e.getMessage()); }
        importNow();
    }

    /** Merges quiz_import.json (your own file or one downloaded in the app) and renames it. */
    public void importNow() {
        if (store == null) { openStore(); return; }
        File imp = new File(dir(), "quiz_import.json");
        if (!imp.exists()) return;
        try {
            int n = store.importFile(imp);
            saveStore();
            imp.renameTo(new File(dir(), "quiz_import.done.json"));
            status("Imported " + n + " answers (" + store.size() + " known)");
        } catch (Exception e) {
            status("Import failed: " + e.getMessage());
        }
    }

    private void saveStore() {
        try { store.save(); } catch (IOException e) { status("Could not save answers: " + e.getMessage()); }
    }

    private void appendLine(String name, String line) {
        try (FileWriter w = new FileWriter(new File(dir(), name), true)) { w.write(line + "\n"); }
        catch (IOException ignored) { }
    }

    private void log(Snap s, int idx, String source) {
        File f = new File(dir(), "quiz_history.csv");
        StringBuilder opts = new StringBuilder();
        for (Item o : s.options) opts.append(opts.length() > 0 ? " | " : "").append(o.text);
        try (FileWriter w = new FileWriter(f, true)) {
            if (f.length() == 0) w.write("time,question,options,chosen_number,chosen_text,source\n");
            w.write(q(now()) + "," + q(s.question) + "," + q(opts.toString()) + "," + (idx + 1) + ","
                    + q(s.options.get(idx).text) + "," + q(source) + "\n");
        } catch (IOException e) {
            status("Could not write quiz_history.csv: " + e.getMessage());
        }
    }

    private void dump() {
        String name = "dump_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".txt";
        StringBuilder sb = new StringBuilder();
        for (AccessibilityWindowInfo w : getWindows()) {
            AccessibilityNodeInfo r = w.getRoot();
            sb.append("=== window type=").append(w.getType()).append(" title=").append(w.getTitle())
                    .append(" pkg=").append(r == null ? "null" : r.getPackageName()).append('\n');
            if (r != null) dumpNode(r, 0, sb);
        }
        try (FileWriter fw = new FileWriter(new File(dir(), name))) {
            fw.write(sb.toString());
            status("Dump saved: " + name);
        } catch (IOException e) {
            status("Dump failed: " + e.getMessage());
        }
    }

    private void dumpNode(AccessibilityNodeInfo n, int depth, StringBuilder sb) {
        if (n == null || depth > 40) return;
        CharSequence t = n.getText(), d = n.getContentDescription();
        if (t != null || d != null || n.isClickable() || n.isEditable()) {
            Rect b = new Rect();
            n.getBoundsInScreen(b);
            for (int i = 0; i < depth; i++) sb.append(' ');
            sb.append(n.getClassName()).append(" text=").append(t == null ? "" : q(t.toString()))
                    .append(" desc=").append(d == null ? "" : q(d.toString()))
                    .append(" click=").append(n.isClickable()).append(" edit=").append(n.isEditable())
                    .append(" bounds=").append(b.toShortString()).append('\n');
        }
        for (int i = 0; i < n.getChildCount(); i++) dumpNode(n.getChild(i), depth + 1, sb);
    }
}
