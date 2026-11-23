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
 * when the question is known; otherwise follows Gemini Live's caption and learns from the result.
 */
public class QuizAccessService extends NusukAccessService {
    public static volatile QuizAccessService instance;

    /** Press "Check your answer" after choosing an option (the door macro did the same). */
    private static final boolean PRESS_CHECK = true;
    /** Doors to play per Start press (each door costs one key). */
    private static final int MAX_DOORS = 0;   // 0 = keep going until you press Stop
    private static final String NUSUK = "com.moh.nusukapp";
    private static final long RESULT_TIMEOUT_MS = 20000;
    /** No usable answer from memory or Gemini after this long: pick an option so the run keeps going. */
    private static final String BRIEFING =
            "From now on, each time I say answer, look at the screen and reply only with the option number and the answer. Answer.";
    private static final long ANSWER_WAIT_MS = 14000;

    private static class Item {
        String text;
        AccessibilityNodeInfo click;
        final Rect bounds = new Rect();
    }

    private static class Snap {
        List<Item> options = new ArrayList<>();     // empty unless exactly 4 found
        String question = "";
        String caption = "";
        int candidates;
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private LinearLayout box;
    private TextView statusTv;
    private WindowManager.LayoutParams lp;
    private Button autoBtn;
    private boolean auto;
    private boolean ask = true;
    private Button askBtn;
    private android.speech.tts.TextToSpeech tts;
    private boolean ttsReady;
    private boolean briefed;
    private boolean locked;
    private LinearLayout btnRow, btnRow2;
    private PocketLock pocket;
    private Button lockBtn;
    private int askCount;
    private long lastAskAt;
    private boolean guess = false;   // never click blindly unless you switch Guess on
    private Button guessBtn;

    private String lastKey = "", baseline = "", prevCap = "";
    private int stable;
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

    private volatile long lastGeminiEvent;

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        CharSequence p = e.getPackageName();
        if (p != null && isGemini(p.toString())) lastGeminiEvent = System.currentTimeMillis();
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
        if (tts == null) tts = new android.speech.tts.TextToSpeech(this, st -> {
            ttsReady = st == android.speech.tts.TextToSpeech.SUCCESS;
            if (ttsReady) tts.setLanguage(Locale.US);
        });
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
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        guessBtn = button("Guess: off", v -> { guess = !guess; guessBtn.setText(guess ? "Guess: on" : "Guess: off"); });
        row2.addView(guessBtn);
        askBtn = button("Ask: on", v -> { ask = !ask; askBtn.setText(ask ? "Ask: on" : "Ask: off"); });
        row2.addView(askBtn);
        btnRow = row;
        btnRow2 = row2;
        box.addView(row);
        box.addView(row2);

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
    }

    public void hideBox() {
        if (locked) setLocked(false);
        auto = false;
        ui.removeCallbacks(tick);
        Awake.release();
        if (tts != null) { try { tts.shutdown(); } catch (Exception ignored) { } tts = null; ttsReady = false; }
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
            btnRow.setVisibility(View.GONE); btnRow2.setVisibility(View.GONE);
            status("Locked: double-tap this text to unlock");
        } else {
            if (pocket != null) pocket.unlock();
            pocket = null;
            btnRow.setVisibility(View.VISIBLE); btnRow2.setVisibility(View.VISIBLE);
            status("Unlocked");
        }
    }

    private Button button(String t, View.OnClickListener c) { return PocketLock.button(this, t, c); }

    private void toggleAuto() {
        auto = !auto;
        autoBtn.setText(auto ? "Stop" : "Auto");
        if (auto) {
            lastKey = ""; pendingQ = null; doorsDone = 0; doorTick = 0; briefed = false;
            Awake.hold(this);
            if (ask && ttsReady) {          // full instruction once, right when Auto starts
                briefed = true;
                tts.speak(BRIEFING, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "brief");
            }
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

    private static boolean isGemini(String pkg) {
        return pkg.contains("googlequicksearchbox") || pkg.contains("gemini") || pkg.contains("bard")
                || pkg.equals("com.google.android.apps.assistant");
    }

    private Snap snapshot() {
        Snap s = new Snap();
        StringBuilder cap = new StringBuilder();
        List<Item> items = new ArrayList<>();
        boolean gotApp = false;
        for (AccessibilityWindowInfo w : getWindows()) {
            AccessibilityNodeInfo r = w.getRoot();
            if (r == null || r.getPackageName() == null) continue;
            String pkg = r.getPackageName().toString();
            if (pkg.equals(getPackageName())) continue;
            if (isGemini(pkg)) {
                gather(r, null, cap, 0);                       // caption: real text only, not descriptions
            } else if (!gotApp && pkg.equals(NUSUK) && w.getType() == AccessibilityWindowInfo.TYPE_APPLICATION) {
                gotApp = true;
                gather(r, items, null, 0);
            }
        }
        s.caption = cap.toString().trim();

        List<QuizLogic.Row> rows = new ArrayList<>();
        for (Item it : items) rows.add(new QuizLogic.Row(it.text, it.bounds.top, it.bounds.bottom, it.click != null));
        QuizLogic.Result res = QuizLogic.analyze(rows);
        s.candidates = res.candidates;
        s.question = res.question;
        for (int i : res.options) s.options.add(items.get(i));
        return s;
    }

    private void gather(AccessibilityNodeInfo n, List<Item> items, StringBuilder cap, int depth) {
        if (n == null || depth > 40) return;
        CharSequence t = n.getText();
        if (t == null && items != null) t = n.getContentDescription();
        if (t != null && t.toString().trim().length() > 0) {
            if (cap != null) cap.append(t).append('\n');
            if (items != null) {
                Item it = new Item();
                it.text = t.toString();
                it.click = clickable(n);
                n.getBoundsInScreen(it.bounds);
                items.add(it);
            }
        }
        for (int i = 0; i < n.getChildCount(); i++) gather(n.getChild(i), items, cap, depth + 1);
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
            else if (pendingQ != null && now - pendingAt > RESULT_TIMEOUT_MS) giveUpPending();
            return;
        }
        StringBuilder k = new StringBuilder(s.question);
        for (Item o : s.options) k.append('|').append(o.text);
        String key = k.toString();
        boolean isNew = !key.equals(lastKey);
        if (isNew) {
            lastKey = key; baseline = s.caption; prevCap = s.caption; stable = 0; handled = false;
            questionSince = now; askCount = 0; lastAskAt = 0;
        }
        if (handled && !manual) {
            // answered but the result page never came: press Check again once, then give up
            if (pendingQ != null && now - pendingAt > 8000 && now - lastClickAt > 5000) {
                if (now - pendingAt > RESULT_TIMEOUT_MS) giveUpPending();
                else { pressCheck(); lastClickAt = now; }
            }
            return;
        }

        // 1) known from memory (works offline, no Gemini needed)
        List<String> texts = new ArrayList<>();
        for (Item o : s.options) texts.add(o.text);
        int known = store.find(s.question, texts);
        if (known >= 0) { act(s, known, "memory"); return; }

        // 2) otherwise follow Gemini's caption
        if (!manual) {
            if (isNew) { status("New question: " + shorten(s.question) + (s.caption.isEmpty() ? " (no Gemini caption: turn captions on)" : "")); return; }
            // fresh = the caption differs from the one seen when the question appeared, OR Gemini's window
            // updated after the question appeared (an identical answer such as "Option 2" twice in a row)
            boolean fresh = !s.caption.isEmpty()
                    && (!s.caption.equals(baseline) || lastGeminiEvent > questionSince + 400);   // ignore the previous question's caption
            int idx = -1;
            if (fresh) {
                if (s.caption.equals(prevCap)) stable++; else stable = 0;
                prevCap = s.caption;
                idx = AnswerMatcher.pick(s.caption, texts, false);            // clear answer: take it at once
                if (idx < 0 && stable >= 1) idx = AnswerMatcher.pick(s.caption, texts, true);   // half answer / keyword
            }
            if (idx >= 0) { act(s, idx, "gemini"); return; }
            if (guess && now - questionSince > ANSWER_WAIT_MS) {
                // nothing usable: guess an option that is not known to be wrong, learn from the result
                int g = 0;
                for (int i = 0; i < 4; i++) if (!store.isWrong(s.question, texts.get(i))) { g = i; break; }
                status("No answer, guessing " + (g + 1));
                act(s, g, "guess");
                return;
            }
            boolean gemQuiet = now - lastGeminiEvent > 1500 || now - questionSince > 6000;   // do not talk over Gemini
            if (ask && ttsReady && askCount < 3 && now - questionSince > 1500 && now - lastAskAt > 10000 && gemQuiet) {
                askCount++; lastAskAt = now;
                String say;
                if (!briefed) {             // Auto was started before the voice engine was ready
                    briefed = true;
                    say = BRIEFING;
                } else {
                    say = "Answer.";
                }
                tts.speak(say, android.speech.tts.TextToSpeech.QUEUE_ADD, null, "ask");
                status("Asked Gemini by voice (" + askCount + "/3)");
                return;
            }
            status(fresh ? "Unclear (not guessing): " + shorten(tail(s.caption)) : "Waiting for Gemini…");
            return;
        }
        if (s.caption.isEmpty()) { status("No Gemini caption found"); return; }
        int idx = AnswerMatcher.pick(s.caption, texts, true);
        if (idx < 0) { status("Gemini's answer not understood yet"); return; }
        act(s, idx, "gemini");
    }

    private void act(Snap s, int idx, String source) {
        final Item it = s.options.get(idx);
        final String question = s.question, answer = it.text;
        boolean ok = clickItem(it);
        handled = true;
        if (source.equals("gemini")) {
            store.record(question, answer, false);
            saveStore();
        }
        log(s, idx, source);
        status((ok ? "Selected " : "Tap failed for ") + (idx + 1) + " (" + source + "): " + shorten(answer));
        if (ok) {
            pendingQ = question; pendingA = answer; pendingAt = System.currentTimeMillis();
            if (PRESS_CHECK) ui.postDelayed(this::pressCheck, 700);
        }
    }

    /** Result page (right or wrong, both have "Play again"): learn, then press Play again. */
    private void onResult(List<Item> app, String text) {
        long now = System.currentTimeMillis();
        if (now - lastClickAt < 1500) return;
        if (pendingQ != null) {
            int r = ResultDetector.classify(text);
            String label = r == ResultDetector.RIGHT ? "RIGHT" : r == ResultDetector.WRONG ? "WRONG" : "UNKNOWN";
            if (r == ResultDetector.RIGHT) store.markRight(pendingQ, pendingA);
            else if (r == ResultDetector.WRONG) store.markWrong(pendingQ, pendingA);
            saveStore();
            String t = text.length() > 500 ? text.substring(0, 500) : text;
            appendLine("quiz_result_log.txt", now() + " [" + label + "] " + pendingQ + " => " + pendingA + "\n    screen: " + t);
            status(label + " | " + store.size() + " answers known");
            pendingQ = null;
        }
        lastKey = ""; handled = false; stable = 0;
        doorsDone++;
        clickText(app, "play again");
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
            gather(r, items, null, 0);
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

    private static String tail(String c) {
        c = c.replace("\n", " / ");
        return c.length() > 50 ? "…" + c.substring(c.length() - 50) : c;
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
        String cap = s.caption.length() > 300 ? s.caption.substring(s.caption.length() - 300) : s.caption;
        try (FileWriter w = new FileWriter(f, true)) {
            if (f.length() == 0) w.write("time,question,options,chosen_number,chosen_text,source,gemini_caption\n");
            w.write(q(now()) + "," + q(s.question) + "," + q(opts.toString()) + "," + (idx + 1) + ","
                    + q(s.options.get(idx).text) + "," + q(source) + "," + q(cap) + "\n");
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
