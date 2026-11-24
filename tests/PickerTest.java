import com.example.nusukhelper.EndpointPicker;
import java.util.Arrays;
public class PickerTest {
    static int f;
    static void check(boolean ok, String n) { System.out.println((ok ? "PASS " : "FAIL ") + n); if (!ok) f++; }
    static String o(EndpointPicker p, long t) { return Arrays.toString(p.order(t)); }
    public static void main(String[] a) {
        EndpointPicker p = new EndpointPicker(2, true);
        check(o(p, 0).equals("[0, 1]"), "first question: 1 then 2");
        p.success(0);
        check(o(p, 0).equals("[1, 0]"), "after 1 answered: 2 goes first (alternate)");
        p.success(1);
        check(o(p, 0).equals("[0, 1]"), "then 1 again");
        // endpoint 1 rate limited for 10 s: it is skipped, 2 answers every question
        p.failed(0, 1000, 10000);
        check(o(p, 2000).equals("[1]"), "1 cooling down -> only 2");
        p.success(1);
        check(o(p, 5000).equals("[1]"), "still only 2 while 1 cools down");
        check(o(p, 11000).equals("[0, 1]") || o(p, 11000).equals("[1, 0]"), "1 is back after the cool-down");
        // both cooling
        p.failed(0, 20000, 8000); p.failed(1, 20000, 3000);
        check(p.order(21000).length == 0 && p.soonestReady(21000) == 2000, "both cooling: nothing to call, soonest ready in 2 s");
        // priority mode
        EndpointPicker q = new EndpointPicker(2, false);
        q.success(0); q.success(1);
        check(o(q, 0).equals("[0, 1]"), "priority mode: always 1 first");
        q.failed(0, 0, 5000);
        check(o(q, 1000).equals("[1]") && o(q, 6000).equals("[0, 1]"), "priority mode: skips 1 while cooling, returns after");
        EndpointPicker one = new EndpointPicker(1, true);
        one.success(0);
        check(o(one, 0).equals("[0]"), "single endpoint works");
        System.exit(f == 0 ? 0 : 1);
    }
}
