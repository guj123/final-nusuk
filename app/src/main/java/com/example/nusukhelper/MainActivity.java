package com.example.nusukhelper;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** One app, two modes: CODES (enter keys) and QUIZ (answer doors). Each has its own floating box. */
public class MainActivity extends Activity {
    private SharedPreferences sp;
    private EditText dir;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences("nh", MODE_PRIVATE);

        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (16 * getResources().getDisplayMetrics().density);
        l.setPadding(p, p, p, p);

        l.addView(title("Nusuk Helper"));
        l.addView(text("Folder with final.txt / used.txt (answers are saved here too):"));
        dir = new EditText(this);
        dir.setSingleLine(true);
        dir.setText(sp.getString("dir", Codes.DEFAULT_DIR));
        l.addView(dir);

        l.addView(title("Setup (once)"));
        l.addView(btn("1. Allow display over other apps", v -> startActivity(new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())))));
        l.addView(btn("2. Allow all files access", v -> startActivity(new Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName())))));
        l.addView(btn("3. Turn on the accessibility service", v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));
        l.addView(text("If a switch is greyed out: Settings > Apps > Nusuk Helper > menu > Allow restricted settings."));

        l.addView(title("CODES mode"));
        l.addView(text("Floating box: Start / Stop. Enters every code from final.txt and moves it to used.txt."));
        l.addView(btn("Show CODES box", v -> showCodes()));

        l.addView(title("QUIZ mode"));
        l.addView(text("Floating box: Auto / Lock / X. Answers from memory (quiz_answers.json), otherwise asks your AI endpoint. "
                + "If the AI cannot answer, nothing is tapped. It learns from Nusuk's right/wrong page even when Auto is off "
                + "(answer by hand while the box is shown)."));

        l.addView(title("AI endpoint 1"));
        l.addView(text("Any OpenAI-compatible provider. Full URL ending in /chat/completions (a base URL ending in /v1 also works), https only."));
        final EditText u1 = field("https://api.groq.com/openai/v1/chat/completions", sp.getString("ep1_url", LlmClient.DEFAULT_URL), false);
        final EditText k1 = field("API key", sp.getString("ep1_key", sp.getString("groq_key", "")), true);
        final EditText m1 = field("model name", sp.getString("ep1_model", sp.getString("groq_model", LlmClient.DEFAULT_MODEL)), false);
        l.addView(u1); l.addView(k1); l.addView(m1);

        l.addView(title("AI endpoint 2 (backup, optional)"));
        l.addView(text("Used only when endpoint 1 fails (rate limit, error). Leave the key empty to disable."));
        final EditText u2 = field("https://api.cerebras.ai/v1/chat/completions", sp.getString("ep2_url", ""), false);
        final EditText k2 = field("API key", sp.getString("ep2_key", ""), true);
        final EditText m2 = field("model name", sp.getString("ep2_model", ""), false);
        l.addView(u2); l.addView(k2); l.addView(m2);
        l.addView(text("Examples: Groq https://api.groq.com/openai/v1/chat/completions | Cerebras https://api.cerebras.ai/v1/chat/completions | "
                + "Mistral https://api.mistral.ai/v1/chat/completions | Gemini https://generativelanguage.googleapis.com/v1beta/openai/chat/completions | "
                + "OpenRouter https://openrouter.ai/api/v1/chat/completions. Use a model your account is allowed to use (wrong name = HTTP 404)."));
        final android.widget.CheckBox alt = new android.widget.CheckBox(this);
        alt.setText("Alternate: question 1 -> endpoint 1, question 2 -> endpoint 2, then 1, 2, 1, 2... (spreads the rate limit). Off = endpoint 1 first, 2 only as backup.");
        alt.setChecked(sp.getBoolean("ep_alternate", true));
        l.addView(alt);
        final Runnable save = () -> sp.edit().putBoolean("ep_alternate", alt.isChecked())
                .putString("ep1_url", u1.getText().toString().trim()).putString("ep1_key", k1.getText().toString().trim())
                .putString("ep1_model", m1.getText().toString().trim())
                .putString("ep2_url", u2.getText().toString().trim()).putString("ep2_key", k2.getText().toString().trim())
                .putString("ep2_model", m2.getText().toString().trim()).apply();
        l.addView(btn("Save endpoints", v -> { save.run(); toast("Saved"); }));
        l.addView(btn("Save and test endpoints", v -> { save.run(); testEndpoints(); }));
        l.addView(btn("Show QUIZ box", v -> showQuiz()));
        l.addView(btn("Hide QUIZ box", v -> {
            QuizAccessService q = QuizAccessService.instance;
            if (q != null) q.hideBox();
        }));
        l.addView(text("Answers file to download (https link to a JSON file you trust, e.g. a raw GitHub link):"));
        final EditText url = new EditText(this);
        url.setSingleLine(true);
        url.setText(sp.getString("quiz_url", ""));
        l.addView(url);
        l.addView(btn("Download and merge answers", v -> {
            String u = url.getText().toString().trim();
            sp.edit().putString("quiz_url", u).apply();
            download(u, folder());
        }));

        ScrollView sv = new ScrollView(this);
        sv.addView(l);
        setContentView(sv);
    }

    private String folder() {
        String d = dir.getText().toString().trim();
        return d.isEmpty() ? Codes.DEFAULT_DIR : d;
    }

    private void showCodes() {
        if (!Settings.canDrawOverlays(this)) { toast("Step 1 (display over other apps) not done"); return; }
        if (!Environment.isExternalStorageManager()) { toast("Step 2 (all files access) not done"); return; }
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }
        String d = folder();
        Codes.setDir(d);
        if (!Codes.hasFinal()) { toast("final.txt not found in " + d); return; }
        sp.edit().putString("dir", d).apply();
        QuizAccessService qz = QuizAccessService.instance;
        if (qz != null) qz.hideBox();                       // the two modes never run together
        startForegroundService(new Intent(this, OverlayService.class));
        toast("Codes box started. Open Nusuk.");
    }

    private void showQuiz() {
        if (!Environment.isExternalStorageManager()) { toast("Step 2 (all files access) not done"); return; }
        QuizAccessService q = QuizAccessService.instance;
        if (q == null) { toast("Step 3 not done: turn on the Nusuk Helper accessibility service"); return; }
        sp.edit().putString("dir", folder()).apply();
        if (sp.getString("ep1_key", sp.getString("groq_key", "")).trim().isEmpty()) { toast("Enter the endpoint 1 API key and press 'Save endpoints' first"); return; }
        q.showBox();                                        // also switches the codes box off
        toast("Quiz box shown. Open Nusuk.");
    }

    /** Runs only when you press the button. https only, max 5 MB; saved as quiz_import.json, merged as "unverified". */
    private void download(final String link, final String folder) {
        if (!link.startsWith("https://")) { toast("Use an https:// link"); return; }
        toast("Downloading...");
        new Thread(() -> {
            String msg;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(link).openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(20000);
                if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
                File out = new File(folder, "quiz_import.json");
                long total = 0;
                try (InputStream in = c.getInputStream(); FileOutputStream os = new FileOutputStream(out)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        total += n;
                        if (total > 5_000_000) throw new Exception("file too big");
                        os.write(buf, 0, n);
                    }
                }
                msg = "Downloaded " + (total / 1024) + " KB";
            } catch (Exception e) {
                msg = "Download failed: " + e.getMessage();
            }
            final String m = msg;
            runOnUiThread(() -> {
                toast(m);
                QuizAccessService s = QuizAccessService.instance;
                if (s != null) s.importNow();               // otherwise merged next time the quiz box is shown
            });
        }).start();
    }

    /** Sends one easy test question to each configured endpoint and shows what happened. */
    private void testEndpoints() {
        final String[] names = {"ep1", "ep2"};
        toast("Testing...");
        new Thread(() -> {
            StringBuilder out = new StringBuilder();
            for (int n = 0; n < 2; n++) {
                String key = sp.getString(names[n] + "_key", n == 0 ? sp.getString("groq_key", "") : "");
                if (n == 1 && key.trim().isEmpty()) { out.append("Endpoint 2: not set\n"); continue; }
                String url = sp.getString(names[n] + "_url", n == 0 ? LlmClient.DEFAULT_URL : "");
                String model = sp.getString(names[n] + "_model", n == 0 ? LlmClient.DEFAULT_MODEL : "");
                long t0 = System.currentTimeMillis();
                LlmClient.Result r = LlmClient.ask(url, key, model, "Which city is the Kaaba in?",
                        java.util.Arrays.asList("Taif", "Mecca", "Jeddah", "Medina"));
                long ms = System.currentTimeMillis() - t0;
                out.append("Endpoint ").append(n + 1).append(": ");
                if (r.idx == 1) out.append("OK, correct answer (").append(ms).append(" ms)");
                else if (r.idx >= 0) out.append("works but answered option ").append(r.idx + 1).append(" (wrong, the model is weak)");
                else out.append("FAILED: ").append(r.error);
                out.append("\n");
            }
            final String msg = out.toString();
            runOnUiThread(() -> new android.app.AlertDialog.Builder(this).setTitle("Endpoint test")
                    .setMessage(msg).setPositiveButton("OK", null).show());
        }).start();
    }

    private EditText field(String hint, String value, boolean secret) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setHint(hint);
        e.setText(value);
        if (secret) e.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return e;
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    private TextView title(String t) {
        TextView v = new TextView(this);
        v.setText("\n" + t);
        v.setTextSize(18);
        v.setTypeface(null, android.graphics.Typeface.BOLD);
        return v;
    }

    private TextView text(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(14);
        return v;
    }

    private Button btn(String text, View.OnClickListener c) {
        Button b = new Button(this);
        b.setText(text);
        b.setOnClickListener(c);
        return b;
    }
}
