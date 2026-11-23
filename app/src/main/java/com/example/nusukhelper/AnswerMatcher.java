package com.example.nusukhelper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure Java: turns Gemini's caption into 0..3 (the option) or -1 (not sure). */
public final class AnswerMatcher {
    private AnswerMatcher() { }

    private static final Pattern NUM = Pattern.compile(
            "(?i)\\b(?:option|choice|answer|number)\\s*(?:number\\s*|is\\s*|:)?\\s*(1|2|3|4|one|two|three|four)\\b");
    private static final Set<String> STOP = new HashSet<>(Arrays.asList(
            "the", "of", "a", "an", "al", "as", "is", "and", "or", "to", "in", "on", "by", "for", "was", "ibn", "bin"));

    /** Captions like "4. Mecca", "4) Mecca", "4 Mecca" or a lone "4" at the start of a line. */
    private static final Pattern LEAD = Pattern.compile("(?m)^\\s*([1-4])(?:\\s*[.):,\\-]\\s*|\\s+)\\p{L}|^\\s*([1-4])\\s*$");
    /** The app's own spoken prompt can be transcribed into the caption: remove it before matching. */
    private static final Pattern OWN = Pattern.compile(
            "(?i)from now on,? each time i say answer,? look at the screen and reply only with the option number and the answer\\.?|(?m)^\\s*answer\\.?\\s*$");

    private static final Pattern ORD = Pattern.compile(
            "(?i)\\b(first|second|third|fourth|last)\\s+(?:option|choice|answer|one)\\b");

    /** Strict match: option number, whole option text, or strongly matching words. */
    public static int pick(String caption, List<String> options) { return pick(caption, options, false); }

    /**
     * partial = true also accepts a half-spoken answer: one distinctive keyword (or the start of a word)
     * that points to exactly one option.
     */
    public static int pick(String caption, List<String> options, boolean partial) {
        if (caption == null || options.size() != 4) return -1;
        caption = OWN.matcher(caption).replaceAll("");
        if (caption.trim().isEmpty()) return -1;

        Matcher m = NUM.matcher(caption);
        int idx = -1;
        while (m.find()) {
            int v = num(m.group(1));
            if (v >= 1 && v <= 4) idx = v - 1;                 // last spoken number wins
        }
        if (idx >= 0) return idx;
        Matcher lm = LEAD.matcher(caption);
        while (lm.find()) idx = Integer.parseInt(lm.group(1) != null ? lm.group(1) : lm.group(2)) - 1;
        if (idx >= 0) return idx;
        Matcher om = ORD.matcher(caption);
        while (om.find()) {
            switch (om.group(1).toLowerCase()) {
                case "first": idx = 0; break;
                case "second": idx = 1; break;
                case "third": idx = 2; break;
                default: idx = 3;
            }
        }
        if (idx >= 0) return idx;

        if (Pattern.compile("(?i)\\b(both|either|neither)\\b").matcher(caption).find()) return -1;   // not a final answer yet

        String nc = " " + norm(caption) + " ";                 // 1) whole option text inside the caption
        int found = -1, count = 0;
        for (int i = 0; i < 4; i++) {
            String no = norm(options.get(i));
            if (no.length() >= 2 && nc.contains(" " + no + " ")) { found = i; count++; }
        }
        if (count == 1) return found;

        Set<String> cap = new HashSet<>(Arrays.asList(norm(caption).split(" ")));   // 2) fuzzy word match
        List<Set<String>> toks = new ArrayList<>();
        for (String o : options) toks.add(tokens(o));
        double best = -1, second = -1;
        int bi = -1;
        for (int i = 0; i < 4; i++) {
            Set<String> mine = new HashSet<>(toks.get(i));
            Set<String> distinct = new HashSet<>(mine);
            for (int j = 0; j < 4; j++) if (j != i) distinct.removeAll(toks.get(j));
            if (distinct.isEmpty()) distinct = mine;
            if (distinct.isEmpty()) continue;
            int hit = 0;
            for (String t : distinct) if (cap.contains(t)) hit++;
            double score = (double) hit / distinct.size();
            if (score > best) { second = best; best = score; bi = i; }
            else if (score > second) { second = score; }
        }
        if (best >= 0.6 && best - Math.max(second, 0) >= 0.25) return bi;
        return partial ? partialPick(norm(caption), toks) : -1;
    }

    /** Keyword / word-start match: exactly one option must have the best number of keyword hits. */
    private static int partialPick(String ncap, List<Set<String>> toks) {
        String[] words = ncap.isEmpty() ? new String[0] : ncap.split(" ");
        int best = 0, second = 0, bi = -1;
        for (int i = 0; i < 4; i++) {
            Set<String> distinct = new HashSet<>(toks.get(i));
            for (int j = 0; j < 4; j++) if (j != i) distinct.removeAll(toks.get(j));
            int hit = 0;
            for (String t : distinct) {
                for (String w : words) {
                    if (w.length() >= 3 && !STOP.contains(w)
                            && (t.equals(w) || (t.startsWith(w)) || (t.length() >= 4 && w.startsWith(t)))) { hit++; break; }
                }
            }
            if (hit > best) { second = best; best = hit; bi = i; }
            else if (hit > second) second = hit;
        }
        return (best >= 1 && best > second) ? bi : -1;
    }

    private static Set<String> tokens(String s) {
        Set<String> out = new HashSet<>();
        for (String t : norm(s).split(" ")) if (t.length() >= 2 && !STOP.contains(t)) out.add(t);
        return out;
    }

    private static int num(String s) {
        switch (s.toLowerCase()) {
            case "1": case "one": return 1;
            case "2": case "two": return 2;
            case "3": case "three": return 3;
            case "4": case "four": return 4;
        }
        return -1;
    }

    static String norm(String s) { return s.toLowerCase().replaceAll("[^\\p{L}\\p{N}]+", " ").trim(); }
}
