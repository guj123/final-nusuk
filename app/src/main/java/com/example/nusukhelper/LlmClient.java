package com.example.nusukhelper;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Pure Java (no Android): asks ANY OpenAI-compatible chat endpoint (Groq, Cerebras, Mistral, Gemini's
 * OpenAI mode, OpenRouter, your own server...) which option is right and returns 0..n-1.
 * It NEVER guesses: any problem (no network, rate limit, bad key, odd reply) is a failed Result
 * with idx = -1, and the caller must not click anything.
 */
public final class LlmClient {
    private LlmClient() { }

    public static final String DEFAULT_URL = "";
    public static final String DEFAULT_MODEL = "";
    /** Tests only: lets the unit test talk to a local http:// mock server. */
    public static boolean allowHttpForTests;

    public static final class Result {
        public final int idx;            // 0..n-1, or -1 when there is no answer
        public final String error;       // null when idx >= 0
        public final boolean fatal;      // true: retrying will not help (bad key, bad model, daily quota)
        public final long retryAfterMs;  // suggested wait before the next try
        Result(int idx, String error, boolean fatal, long retryAfterMs) {
            this.idx = idx; this.error = error; this.fatal = fatal; this.retryAfterMs = retryAfterMs;
        }
        static Result ok(int idx) { return new Result(idx, null, false, 0); }
        static Result fail(String e, boolean fatal, long wait) { return new Result(-1, e, fatal, wait); }
    }

    /** Longest wait we sit out for a rate limit; beyond this we treat it as the daily quota. */
    static final long MAX_WAIT_MS = 120_000;

    static String system(int n) {
        return "Multiple-choice quiz about Islam, Hajj/Umrah and Saudi Arabia. Reply only with the single digit (1-"
                + n + ") of the correct option.";
    }

    /** Accepts a base URL too: ".../v1" becomes ".../v1/chat/completions". */
    public static String normalizeUrl(String u) {
        u = u == null ? "" : u.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        if (u.isEmpty() || u.endsWith("/chat/completions")) return u;
        return u + "/chat/completions";
    }

    public static String userPrompt(String question, List<String> options) {
        StringBuilder sb = new StringBuilder(question == null ? "" : question.trim());
        for (int i = 0; i < options.size(); i++) {
            sb.append('\n').append(i + 1).append(". ").append(options.get(i).trim().replace("\n", " "));
        }
        return sb.toString();
    }

    /** Reasoning models (gpt-oss, qwen3...) think before answering, so a tiny token limit would give an empty reply. */
    static boolean isReasoning(String model) {
        String m = model.toLowerCase();
        return m.contains("gpt-oss") || m.contains("qwen") || m.contains("deepseek");
    }

    /** swap = use the other name for the token limit (some providers accept only one of the two). */
    static String requestBody(String model, String question, List<String> options, boolean swap) {
        boolean reasoning = isReasoning(model);
        boolean completionParam = reasoning != swap;
        StringBuilder b = new StringBuilder();
        b.append("{\"model\":").append(MiniJson.quote(model));
        b.append(",\"temperature\":0");
        b.append(completionParam ? ",\"max_completion_tokens\":" : ",\"max_tokens\":").append(reasoning ? 512 : 8);
        if (model.toLowerCase().contains("gpt-oss")) b.append(",\"reasoning_effort\":\"low\"");
        b.append(",\"messages\":[{\"role\":\"system\",\"content\":").append(MiniJson.quote(system(options.size()))).append("},");
        b.append("{\"role\":\"user\",\"content\":").append(MiniJson.quote(userPrompt(question, options))).append("}]}");
        return b.toString();
    }

    /** The one digit 1..n in the reply (e.g. "3", "3.", "Option 3"); -1 if none or ambiguous. */
    public static int parseDigit(String content, int n) {
        if (content == null) return -1;
        int found = -1;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c >= '1' && c <= '0' + n) {
                int d = c - '1';
                if (found >= 0 && found != d) return -1;      // two different digits: do not guess
                found = d;
            } else if (Character.isDigit(c)) {
                return -1;                                    // out of range or part of a bigger number
            }
        }
        return found;
    }

    /** Pulls choices[0].message.content out of the response JSON. */
    @SuppressWarnings("unchecked")
    static String content(String json) {
        Object o = MiniJson.parse(json);
        if (!(o instanceof Map)) return null;
        Object choices = ((Map<String, Object>) o).get("choices");
        if (!(choices instanceof List) || ((List<Object>) choices).isEmpty()) return null;
        Object c0 = ((List<Object>) choices).get(0);
        if (!(c0 instanceof Map)) return null;
        Object msg = ((Map<String, Object>) c0).get("message");
        if (!(msg instanceof Map)) return null;
        Object content = ((Map<String, Object>) msg).get("content");
        return content == null ? null : content.toString();
    }

    private static String errorMessage(String json) {
        try {
            Object o = MiniJson.parse(json);
            if (o instanceof Map) {
                Object e = ((Map<?, ?>) o).get("error");
                if (e instanceof Map) {
                    Object m = ((Map<?, ?>) e).get("message");
                    if (m != null) return m.toString();
                } else if (e instanceof String) return (String) e;
            }
        } catch (Exception ignored) { }
        return null;
    }

    private static String read(InputStream in) throws java.io.IOException {
        if (in == null) return "";
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) { bo.write(buf, 0, n); if (bo.size() > 200_000) break; }
        return bo.toString("UTF-8");
    }

    public static Result ask(String url, String apiKey, String model, String question, List<String> options) {
        url = normalizeUrl(url);
        if (!url.startsWith("https://") && !(allowHttpForTests && url.startsWith("http://"))) return Result.fail("Endpoint URL must start with https://", true, 0);
        if (apiKey == null || apiKey.trim().isEmpty()) return Result.fail("No API key entered", true, 0);
        if (model == null || model.trim().isEmpty()) return Result.fail("No model name entered", true, 0);
        Result r = askOnce(url, apiKey, model.trim(), question, options, false);
        if (r.error != null && r.error.contains("#swap")) r = askOnce(url, apiKey, model.trim(), question, options, true);
        return r.error == null ? r : Result.fail(r.error.replace("#swap", ""), r.fatal, r.retryAfterMs);
    }

    private static Result askOnce(String url, String apiKey, String model, String question, List<String> options, boolean swap) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(8000);
            c.setReadTimeout(25000);
            c.setDoOutput(true);
            c.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
            c.setRequestProperty("Content-Type", "application/json");
            byte[] body = requestBody(model, question, options, swap).getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = c.getOutputStream()) { os.write(body); }

            int code = c.getResponseCode();
            if (code == 200) {
                String text;
                try (InputStream in = c.getInputStream()) { text = read(in); }
                String content = content(text);
                int idx = parseDigit(content, options.size());
                if (idx < 0) return Result.fail("Reply not a clear option number: '" + trim(content) + "'", false, 2000);
                return Result.ok(idx);
            }
            String err;
            try (InputStream in = c.getErrorStream()) { err = read(in); }
            String msg = errorMessage(err);
            String why = "HTTP " + code + (msg != null ? ": " + trim(msg) : "");
            if (code == 429) {
                long wait = retryAfter(c.getHeaderField("retry-after"));
                if (wait > MAX_WAIT_MS) return Result.fail("Limit reached (" + why + ")", true, wait);
                return Result.fail("Rate limited (" + why + ")", false, Math.max(wait, 3000));
            }
            if (code == 400 && !swap && msg != null && (msg.contains("max_tokens") || msg.contains("max_completion_tokens")))
                return Result.fail(why + "#swap", false, 0);
            if (code == 400 || code == 401 || code == 403 || code == 404) return Result.fail(why, true, 0);
            return Result.fail(why, false, 4000);
        } catch (Exception e) {
            return Result.fail("No answer: " + e.getClass().getSimpleName()
                    + (e.getMessage() != null ? " " + trim(e.getMessage()) : ""), false, 4000);
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static long retryAfter(String h) {
        if (h == null) return 0;
        try { return (long) (Double.parseDouble(h.trim()) * 1000); } catch (NumberFormatException e) { return 0; }
    }

    private static String trim(String s) {
        if (s == null) return "null";
        s = s.replace("\n", " ").trim();
        return s.length() > 80 ? s.substring(0, 77) + "..." : s;
    }
}
