import com.example.nusukhelper.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public class FlowTest {
    static int fails = 0;
    static void check(boolean ok, String m) { System.out.println((ok ? "PASS " : "FAIL ") + m); if (!ok) fails++; }

    static List<QuizLogic.Row> rows(String file) throws Exception {
        List<QuizLogic.Row> out = new ArrayList<>();
        Pattern p = Pattern.compile("^\\s*\\S+ text=\"(.*?)\" desc=(?:\"(.*?)\")? click=(\\w+) edit=\\w+ bounds=\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]");
        boolean nusuk = false;
        List<int[]> boxes = new ArrayList<>();
        Pattern em = Pattern.compile("text= desc= click=true edit=\\w+ bounds=\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]");
        for (String l : Files.readAllLines(Paths.get(file))) {
            if (l.startsWith("===")) { nusuk = l.contains("pkg=com.moh.nusukapp"); continue; }
            if (!nusuk) continue;
            Matcher e = em.matcher(l);
            if (e.find()) boxes.add(new int[]{Integer.parseInt(e.group(1)), Integer.parseInt(e.group(2)), Integer.parseInt(e.group(3)), Integer.parseInt(e.group(4))});
            Matcher m = p.matcher(l);
            if (!m.find()) {
                Matcher m2 = Pattern.compile("^\\s*\\S+ text= desc=\"(.*?)\" click=(\\w+) edit=\\w+ bounds=\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]").matcher(l);
                if (m2.find()) out.add(new QuizLogic.Row(m2.group(1), Integer.parseInt(m2.group(4)), Integer.parseInt(m2.group(6)), m2.group(2).equals("true")));
                continue;
            }
            out.add(new QuizLogic.Row(m.group(1), Integer.parseInt(m.group(5)), Integer.parseInt(m.group(7)), m.group(3).equals("true")));
        }
        // like the service: a text inside a clickable box counts as clickable
        List<QuizLogic.Row> fixed = new ArrayList<>();
        for (QuizLogic.Row r : out) {
            boolean c = r.clickable;
            for (int[] b : boxes) if (r.top >= b[1] && r.bottom <= b[3]) c = true;
            fixed.add(new QuizLogic.Row(r.text, r.top, r.bottom, c));
        }
        return fixed;
    }
    static String all(List<QuizLogic.Row> r) { StringBuilder sb = new StringBuilder(); for (QuizLogic.Row x : r) sb.append(x.text).append(" | "); return sb.toString(); }

    public static void main(String[] a) throws Exception {
        String d = a[0];
        List<QuizLogic.Row> doors = rows(d + "/10f4b554-dump_20261122_201604.txt");
        check(QuizLogic.kind(all(doors)) == QuizLogic.DOORS, "doors screen detected");
        List<Integer> di = QuizLogic.doors(doors);
        System.out.println("doors: " + di.size());
        for (int i : di) System.out.println("  " + doors.get(i).text);
        check(di.size() == 4, "4 doors found");
        List<QuizLogic.Row> conf = rows(d + "/e7cd0e62-dump_20261122_201611.txt");
        check(QuizLogic.kind(all(conf)) == QuizLogic.CONFIRM, "confirm sheet detected");
        List<QuizLogic.Row> res = rows(d + "/a8b3ef18-dump_20261123_063233.txt");
        check(QuizLogic.kind(all(res)) == QuizLogic.RESULT, "result page detected");
        check(ResultDetector.classify(all(res)) == ResultDetector.RIGHT, "Correct! page = RIGHT");
        check(ResultDetector.classify("Wrong answer. Play again | Share") == ResultDetector.WRONG, "wrong page = WRONG");
        check(AnswerMatcher.pick("Both Surah Al-Mujadila", Arrays.asList("Al-Mujadila", "Maryam", "Al-Nisa", "Al-Mumtahana")) == -1, "'Both ...' caption refused");
        check(AnswerMatcher.pick("The answer is Maryam", Arrays.asList("Al-Mujadila", "Maryam", "Al-Nisa", "Al-Mumtahana")) == 1, "plain answer still works");
        List<String> o = Arrays.asList("Al-Mujadila", "Maryam", "Al-Nisa", "Al-Mumtahana");
        check(AnswerMatcher.pick("The answer is Mary", o, true) == 1, "half word 'Mary' -> Maryam (partial)");
        check(AnswerMatcher.pick("The answer is Mary", o, false) == -1, "half word not taken in strict mode");
        check(AnswerMatcher.pick("I think it's Mumtah", o, true) == 3, "keyword start Mumtah -> Al-Mumtahana");
        check(AnswerMatcher.pick("the second option", o, true) == 1, "second option");
        check(AnswerMatcher.pick("Option 3", o, false) == 2, "option 3");
        check(AnswerMatcher.pick("Surah Al", o, true) == -1, "ambiguous 'Al' not taken");
        List<String> mk = Arrays.asList("Madinah", "Taif", "Jeddah", "Mecca");
        check(AnswerMatcher.pick("4. Mecca", mk, false) == 3, "'4. Mecca' -> option 4");
        check(AnswerMatcher.pick("Answer.\n2) Taif", mk, false) == 1, "'2) Taif' -> option 2");
        check(AnswerMatcher.pick("From now on, each time I say answer, look at the screen and reply only with the option number and the answer. Answer.", mk, true) == -1, "own prompt ignored");
        check(AnswerMatcher.pick("In Surah 2: Mecca", mk, false) == 3, "mid-line number not mistaken");
        check(AnswerMatcher.pick("Option 1, Mecca", Arrays.asList("Makkah", "Taif", "Jeddah", "Madinah"), false) == 0, "'Option 1, Mecca'");
        check(AnswerMatcher.pick("Mecca", Arrays.asList("Makkah and the Grand Mosque", "Madinah and the Prophet's Mosque", "The Quran", "The Prophet's Biography"), true) == 0, "'Mecca' = Makkah option");
        check(AnswerMatcher.pick("Option 1", Arrays.asList("Makkah", "Taif", "Jeddah", "Madinah"), false) == 0, "'Option 1'");
        List<String> mk2 = Arrays.asList("Madinah", "Taif", "Jeddah", "Mecca");
        check(AnswerMatcher.pick("Live\n1\nSharing screen", mk2, true) == -1, "stray '1' on its own line is NOT option 1");
        check(AnswerMatcher.pick("Something unrelated", mk2, true) == -1, "unrelated text -> no pick");
        check(AnswerMatcher.stripOwn("Option 1, Mecca\nFrom now on, each time I say answer, just reply with the option number only\nAnswer.").trim().equals("Option 1, Mecca"), "own prompt lines stripped");
        System.exit(fails == 0 ? 0 : 1);
    }
}
