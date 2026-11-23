package com.example.nusukhelper;

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
}
