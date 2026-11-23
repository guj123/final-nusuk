package com.example.nusukhelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Pure Java (no Android): finds the question and the four options in a list of screen rows. */
public final class QuizLogic {
    private QuizLogic() { }

    public static final class Row {
        public final String text;
        public final int top, bottom;
        public final boolean clickable;
        public Row(String text, int top, int bottom, boolean clickable) {
            this.text = text; this.top = top; this.bottom = bottom; this.clickable = clickable;
        }
    }

    public static final class Result {
        public final List<Integer> options = new ArrayList<>();   // row indexes, top to bottom
        public String question = "";
        public int candidates;
    }

    /** Button labels that are clickable but are not answers. */
    private static final Pattern BUTTON = Pattern.compile(
            "(?i)^(check|submit|next|continue|confirm|close|back|cancel|ok|done)\\b.*");

    public static final int OTHER = 0, DOORS = 1, CONFIRM = 2, RESULT = 3;

    /** Which Nusuk screen is this, judged from all its text (lower-cased inside). */
    public static int kind(String allText) {
        String t = allText == null ? "" : allText.toLowerCase();
        if (t.contains("play again")) return RESULT;               // right and wrong pages both have it
        if (t.contains("do you want to use your key")) return CONFIRM;
        if (t.contains("choose a door")) return DOORS;
        return OTHER;
    }

    private static final Set<String> NAV = new HashSet<String>(java.util.Arrays.asList(
            "play", "winners", "keys", "share", "help", "close", "choose a door", "start the challenge"));

    /** Row indexes of the doors on the "Choose a door" screen, in screen order. */
    public static List<Integer> doors(List<Row> rows) {
        List<Integer> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            Row w = rows.get(i);
            String t = w.text == null ? "" : w.text.trim();
            if (!w.clickable || t.isEmpty() || NAV.contains(t.toLowerCase()) || t.matches("\\d+")) continue;
            if (seen.add(t.toLowerCase())) out.add(i);
        }
        return out;
    }

    public static Result analyze(final List<Row> rows) {
        Result r = new Result();
        List<Integer> idx = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            Row w = rows.get(i);
            String t = w.text == null ? "" : w.text.trim();
            if (!w.clickable || t.isEmpty() || BUTTON.matcher(t).matches()) continue;
            if (seen.add(t.toLowerCase())) idx.add(i);
        }
        r.candidates = idx.size();
        if (idx.size() != 4) return r;
        Collections.sort(idx, new Comparator<Integer>() {
            @Override public int compare(Integer a, Integer b) {
                int c = Integer.compare(rows.get(a).top, rows.get(b).top);
                return c != 0 ? c : Integer.compare(a, b);
            }
        });
        r.options.addAll(idx);
        int firstTop = rows.get(idx.get(0)).top;
        int best = -1;
        for (int i = 0; i < rows.size(); i++) {
            Row w = rows.get(i);
            String t = w.text == null ? "" : w.text.trim();
            if (w.clickable || t.length() < 10 || w.bottom > firstTop + 5) continue;
            if (best < 0 || w.bottom > rows.get(best).bottom
                    || (w.bottom == rows.get(best).bottom && t.length() > rows.get(best).text.trim().length())) {
                best = i;
            }
        }
        if (best >= 0) r.question = rows.get(best).text.trim();
        return r;
    }
}
