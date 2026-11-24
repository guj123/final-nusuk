package com.example.nusukhelper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The app's answer memory (quiz_answers.json). Learns from play, works offline, easy to edit or share.
 * File format (keys are lower-case questions without punctuation):
 *   {
 *     "right":      {"how many verses are in surat al fatiha": "7 verses"},    <- confirmed right by Nusuk (or trusted)
 *     "unverified": {"some question": "Some answer"},                          <- from Groq / imports, not confirmed yet
 *     "wrong":      {"some question": ["Bad answer 1", "Bad answer 2"]}
 *   }
 * Also understood when importing: a plain {"question": "answer"} file, and the older {"answers": [...]} format.
 */
public final class AnswerStore {
    private static final class Entry { String q, a; boolean verified; }

    private final File file;
    private final Map<String, Entry> answers = new LinkedHashMap<>();
    private final Map<String, Map<String, String>> wrong = new LinkedHashMap<>();   // normQ -> normA -> answer text
    /** Use answers that Nusuk has not confirmed yet (from Groq or an import). */
    public boolean useUnverified = true;

    public AnswerStore(File file) { this.file = file; }

    // ---- lookup ----------------------------------------------------------

    /** Index (0..3) of the option to tap from memory, or -1 when we do not know. */
    public synchronized int find(String question, List<String> options) {
        String nq = norm(question);
        if (nq.isEmpty() || options.size() != 4) return -1;
        Entry e = answers.get(nq);
        if (e != null && (e.verified || useUnverified)) {
            int hit = -1, count = 0;
            String na = norm(e.a);
            for (int i = 0; i < 4; i++) if (norm(options.get(i)).equals(na)) { hit = i; count++; }
            if (count == 1) return hit;
        }
        Map<String, String> w = wrong.get(nq);
        if (w != null && !w.isEmpty()) {                       // eliminate answers known to be wrong
            int left = -1, count = 0;
            for (int i = 0; i < 4; i++) if (!w.containsKey(norm(options.get(i)))) { left = i; count++; }
            if (count == 1) return left;
        }
        return -1;
    }

    /** True when this answer was already seen to be wrong for this question. */
    public synchronized boolean isWrong(String question, String answer) {
        Map<String, String> w = wrong.get(norm(question));
        return w != null && w.containsKey(norm(answer));
    }

    public synchronized int size() { return answers.size(); }

    // ---- learning --------------------------------------------------------

    public synchronized void record(String question, String answer, boolean verified) {
        String nq = norm(question);
        if (nq.isEmpty() || norm(answer).isEmpty()) return;
        Entry old = answers.get(nq);
        if (old != null && old.verified && !verified) return;   // never replace a confirmed answer
        Entry e = new Entry();
        e.q = nq; e.a = answer.trim(); e.verified = verified;
        answers.put(nq, e);
        Map<String, String> w = wrong.get(nq);                   // a right answer is never "wrong"
        if (w != null) w.remove(norm(answer));
    }

    public synchronized void markRight(String question, String answer) { record(question, answer, true); }

    public synchronized void markWrong(String question, String answer) {
        String nq = norm(question), na = norm(answer);
        if (nq.isEmpty() || na.isEmpty()) return;
        Entry e = answers.get(nq);
        if (e != null && e.verified && norm(e.a).equals(na)) return;   // keep confirmed answers
        Map<String, String> w = wrong.get(nq);
        if (w == null) { w = new LinkedHashMap<>(); wrong.put(nq, w); }
        w.put(na, answer.trim());
        if (e != null && norm(e.a).equals(na)) answers.remove(nq);
    }

    // ---- files -----------------------------------------------------------

    /** Loads this app's own file; its "right" entries count as confirmed. */
    public synchronized void load() throws IOException {
        answers.clear(); wrong.clear();
        if (file.exists()) merge(MiniJson.parse(read(file)), true);
    }

    /** Merges a file you supply or downloaded. Everything imported is "unverified" until Nusuk confirms it. */
    public synchronized int importFile(File f) throws IOException {
        return merge(MiniJson.parse(read(f)), false);
    }

    private int merge(Object root, boolean trustRight) {
        if (!(root instanceof Map)) return 0;
        Map<?, ?> m = (Map<?, ?>) root;
        int n = 0;
        if (!(m.get("answers") instanceof List)
                && (m.containsKey("right") || m.containsKey("unverified") || m.containsKey("wrong"))) {
            n += addMap(m.get("right"), trustRight);
            n += addMap(m.get("unverified"), false);
            if (m.get("wrong") instanceof Map) {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) m.get("wrong")).entrySet()) {
                    Object v = e.getValue();
                    List<?> list = v instanceof List ? (List<?>) v : new ArrayList<Object>();
                    for (Object a : list) if (a instanceof String) markWrong(String.valueOf(e.getKey()), (String) a);
                }
            }
        } else if (m.get("answers") instanceof List) {                    // older format of this app
            for (Object o : (List<?>) m.get("answers")) if (o instanceof Map) {
                Map<?, ?> x = (Map<?, ?>) o;
                Object q = x.get("q"), a = x.get("a");
                if (q instanceof String && a instanceof String) {
                    record((String) q, (String) a, trustRight && Boolean.TRUE.equals(x.get("verified"))); n++;
                }
            }
            if (m.get("wrong") instanceof List) for (Object o : (List<?>) m.get("wrong")) if (o instanceof Map) {
                Map<?, ?> x = (Map<?, ?>) o;
                if (x.get("q") instanceof String && x.get("a") instanceof String) markWrong((String) x.get("q"), (String) x.get("a"));
            }
        } else {
            n += addMap(m, false);                                         // plain {"question": "answer"}
        }
        return n;
    }

    private int addMap(Object o, boolean verified) {
        int n = 0;
        if (!(o instanceof Map)) return 0;
        for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
            if (e.getValue() instanceof String && !norm(String.valueOf(e.getKey())).isEmpty()) {
                record(String.valueOf(e.getKey()), (String) e.getValue(), verified); n++;
            }
        }
        return n;
    }

    public synchronized void save() throws IOException {
        StringBuilder b = new StringBuilder("{\n \"right\": {");
        boolean first = true;
        for (Entry e : answers.values()) if (e.verified) {
            b.append(first ? "\n" : ",\n").append("  ").append(MiniJson.quote(e.q)).append(": ").append(MiniJson.quote(e.a));
            first = false;
        }
        b.append(first ? "},\n" : "\n },\n").append(" \"unverified\": {");
        first = true;
        for (Entry e : answers.values()) if (!e.verified) {
            b.append(first ? "\n" : ",\n").append("  ").append(MiniJson.quote(e.q)).append(": ").append(MiniJson.quote(e.a));
            first = false;
        }
        b.append(first ? "},\n" : "\n },\n").append(" \"wrong\": {");
        first = true;
        for (Map.Entry<String, Map<String, String>> e : wrong.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            b.append(first ? "\n" : ",\n").append("  ").append(MiniJson.quote(e.getKey())).append(": [");
            boolean f2 = true;
            for (String a : e.getValue().values()) { b.append(f2 ? "" : ", ").append(MiniJson.quote(a)); f2 = false; }
            b.append("]");
            first = false;
        }
        b.append(first ? "}\n}\n" : "\n }\n}\n");
        File tmp = new File(file.getPath() + ".tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) { w.write(b.toString()); }
        if (!tmp.renameTo(file)) {                              // some file systems refuse to overwrite on rename
            try (Writer w = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) { w.write(b.toString()); }
            tmp.delete();
        }
    }

    private static String read(File f) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            char[] buf = new char[8192]; int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
        }
        return sb.toString();
    }

    static String norm(String s) {
        return s == null ? "" : s.toLowerCase().replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }
}
