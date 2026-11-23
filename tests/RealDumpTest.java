import com.example.nusukhelper.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public class RealDumpTest {
    static final Pattern LINE = Pattern.compile("^(\\s*)\\S+ text=\"?(.*?)\"? desc=.*? click=(true|false) edit=\\w+ bounds=\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]$");
    public static void main(String[] a) throws Exception {
        for (String f : a) {
            System.out.println("=== " + new File(f).getName());
            String pkg = ""; List<String> lines = Files.readAllLines(Paths.get(f));
            List<int[]> clickBoxes = new ArrayList<>(); List<Object[]> texts = new ArrayList<>(); StringBuilder cap = new StringBuilder();
            for (String ln : lines) {
                if (ln.startsWith("===")) { Matcher p = Pattern.compile("pkg=(\\S+)").matcher(ln); pkg = p.find() ? p.group(1) : ""; continue; }
                Matcher m = LINE.matcher(ln); if (!m.find()) continue;
                String text = m.group(2); boolean click = m.group(3).equals("true");
                int[] b = {Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)), Integer.parseInt(m.group(6)), Integer.parseInt(m.group(7))};
                if (pkg.contains("googlequicksearchbox")) { if (!text.isEmpty()) cap.append(text).append('\n'); }
                else if (pkg.equals("com.moh.nusukapp")) { if (click) clickBoxes.add(b); if (!text.isEmpty()) texts.add(new Object[]{text, b}); }
            }
            List<QuizLogic.Row> rows = new ArrayList<>();
            for (Object[] t : texts) {
                int[] b = (int[]) t[1]; boolean click = false;
                for (int[] c : clickBoxes) if (c[0] <= b[0] && c[1] <= b[1] && c[2] >= b[2] && c[3] >= b[3]) click = true;
                rows.add(new QuizLogic.Row((String) t[0], b[1], b[3], click));
            }
            QuizLogic.Result r = QuizLogic.analyze(rows);
            System.out.println("candidates=" + r.candidates + " question=" + r.question);
            List<String> opts = new ArrayList<>();
            for (int i : r.options) opts.add(rows.get(i).text);
            System.out.println("options=" + opts);
            System.out.println("caption=" + cap.toString().trim().replace('\n', ' '));
            int pick = AnswerMatcher.pick(cap.toString(), opts);
            System.out.println("PICK=" + (pick < 0 ? "none" : (pick + 1) + " -> " + opts.get(pick)));
        }
    }
}
