import com.example.nusukhelper.ResultDetector;
import java.util.Arrays;
import java.util.List;
public class ResultTest {
    static int f;
    static void check(boolean ok, String n) { System.out.println((ok ? "PASS " : "FAIL ") + n); if (!ok) f++; }
    public static void main(String[] a) {
        List<String> o = Arrays.asList("Mecca", "Taif", "Jeddah", "Medina");
        check("Mecca".equals(ResultDetector.correctAnswer("Wrong answer | The correct answer is: Mecca | Play again", o)), "'The correct answer is: Mecca'");
        check("Taif".equals(ResultDetector.correctAnswer("Incorrect | Correct answer | Taif | Play again", o)), "'Correct answer | Taif' (separate rows)");
        check(ResultDetector.correctAnswer("Wrong | Better luck | Play again", o) == null, "no correct answer shown -> null");
        check(ResultDetector.correctAnswer("The correct answer is Mecca or Medina", o) == null, "two options in text -> null (no guessing)");
        check(ResultDetector.correctAnswer("The correct answer is Riyadh", o) == null, "text matches no option -> null");
        System.exit(f == 0 ? 0 : 1);
    }
}
