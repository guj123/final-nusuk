package com.example.nusukhelper;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure Java: decides from the screen text after "Check your answer" whether the answer was right.
 * The wording below is a first guess. The app writes the real screen text to quiz_result_log.txt
 * so the patterns can be tuned to what Nusuk really shows.
 */
public final class ResultDetector {
    private ResultDetector() { }

    public static final int RIGHT = 1, WRONG = -1, UNKNOWN = 0;

    private static final Pattern WRONG_P = Pattern.compile(
            "(?i)\\b(incorrect|wrong|not correct|not the right|try again|unfortunately|better luck)\\b");
    private static final Pattern RIGHT_P = Pattern.compile(
            "(?i)\\b(congratulations|well done|great job|excellent|that'?s right|you are right|you got it|your answer is correct|correct answer!|answered correctly|you won)\\b|\\bcorrect\\s*!");

    public static int classify(String screenText) {
        if (screenText == null) return UNKNOWN;
        if (WRONG_P.matcher(screenText).find()) return WRONG;       // wrong wins: a wrong screen may also say "correct answer is ..."
        if (RIGHT_P.matcher(screenText).find()) return RIGHT;
        return UNKNOWN;
    }

    private static final Pattern CORRECT_IS = Pattern.compile(
            "(?i)correct answer(?:\\s+is)?\\s*[:\\-|]?\\s*([^|\\n]{2,120})");

    /**
     * If a wrong-answer screen also tells the right answer ("The correct answer is X"), returns that option's text,
     * but only when it matches exactly one of the options. Otherwise null (nothing is learned from guesses).
     */
    public static String correctAnswer(String screenText, List<String> options) {
        if (screenText == null) return null;
        Matcher m = CORRECT_IS.matcher(screenText);
        while (m.find()) {
            String got = AnswerStore.norm(m.group(1));
            if (got.isEmpty()) continue;
            String hit = null; int count = 0;
            for (String o : options) {
                String no = AnswerStore.norm(o);
                if (!no.isEmpty() && (no.equals(got) || got.startsWith(no + " ") || got.endsWith(" " + no))) { hit = o; count++; }
            }
            if (count == 1) return hit;
        }
        return null;
    }
}
