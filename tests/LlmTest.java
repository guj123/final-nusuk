import com.example.nusukhelper.LlmClient;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Run: javac -d out app/src/main/java/com/example/nusukhelper/{MiniJson,LlmClient}.java tests/LlmTest.java && java -cp out LlmTest */
public class LlmTest {
    static int fails;
    static void check(boolean ok, String name) { System.out.println((ok ? "PASS " : "FAIL ") + name); if (!ok) fails++; }

    static final AtomicReference<int[]> code = new AtomicReference<>(new int[]{200});
    static final AtomicReference<String> body = new AtomicReference<>("");
    static final AtomicReference<String> sentBody = new AtomicReference<>(""), sentAuth = new AtomicReference<>("");
    static final AtomicReference<String> retryHdr = new AtomicReference<>(null);

    static volatile boolean swapMode;
    static int GroqLikeAsk(String url, List<String> o) { return LlmClient.ask(url, "k", "llama-3.1-8b-instant", "q", o).idx; }
    static void reply(String content) { code.set(new int[]{200}); body.set("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":" + (content == null ? "null" : "\"" + content + "\"") + "}}]}"); }

    public static void main(String[] a) throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", ex -> {
            sentBody.set(new String(ex.getRequestBody().readAllBytes(), "UTF-8"));
            if (swapMode && sentBody.get().contains("\"max_tokens\"")) {
                byte[] e = "{\"error\":{\"message\":\"Unsupported parameter: 'max_tokens'. Use 'max_completion_tokens' instead.\"}}".getBytes("UTF-8");
                ex.sendResponseHeaders(400, e.length); ex.getResponseBody().write(e); ex.close(); return;
            }
            sentAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            byte[] b = body.get().getBytes("UTF-8");
            if (retryHdr.get() != null) ex.getResponseHeaders().add("retry-after", retryHdr.get());
            ex.sendResponseHeaders(code.get()[0], b.length);
            ex.getResponseBody().write(b); ex.close();
        });
        s.start();
        String url = "http://127.0.0.1:" + s.getAddress().getPort() + "/x";
        LlmClient.allowHttpForTests = true;
        List<String> opts = Arrays.asList("Mecca", "Taif", "Jeddah", "Medina");

        reply("3");
        LlmClient.Result r = LlmClient.ask(url, "gsk_test", "llama-3.1-8b-instant", "Where is the Kaaba?", opts);
        check(r.idx == 2 && r.error == null, "reply '3' -> index 2");
        check("Bearer gsk_test".equals(sentAuth.get()), "Authorization header sent");
        check(sentBody.get().contains("\"max_tokens\":8") && sentBody.get().contains("Where is the Kaaba?\\n1. Mecca\\n2. Taif\\n3. Jeddah\\n4. Medina"), "body: max_tokens + numbered options");
        check(sentBody.get().contains("Reply only with the single digit (1-4)"), "terse system prompt");

        reply("Option 4."); check(LlmClient.ask(url, "k", "m", "q", opts).idx == 3, "'Option 4.' -> index 3");
        reply("2\\n"); check(LlmClient.ask(url, "k", "m", "q", opts).idx == 1, "'2\\n' -> index 1");
        reply("1 or 2"); r = LlmClient.ask(url, "k", "m", "q", opts); check(r.idx == -1 && r.error != null && !r.fatal, "'1 or 2' -> no answer (no guess)");
        reply("I don't know"); check(LlmClient.ask(url, "k", "m", "q", opts).idx == -1, "text without digit -> no answer");
        reply("5"); check(LlmClient.ask(url, "k", "m", "q", opts).idx == -1, "'5' -> no answer");
        reply(""); check(LlmClient.ask(url, "k", "m", "q", opts).idx == -1, "empty content -> no answer");
        reply(null); check(LlmClient.ask(url, "k", "m", "q", opts).idx == -1, "null content -> no answer");
        body.set("{\"choices\":[]}"); code.set(new int[]{200}); check(LlmClient.ask(url, "k", "m", "q", opts).idx == -1, "no choices -> no answer");
        body.set("not json"); check(LlmClient.ask(url, "k", "m", "q", opts).idx == -1, "garbage body -> no answer");

        reply("2"); LlmClient.ask(url, "k", "openai/gpt-oss-120b", "q", opts);
        check(sentBody.get().contains("max_completion_tokens") && sentBody.get().contains("reasoning_effort") && !sentBody.get().contains("\"max_tokens\""), "reasoning model gets room to think");

        code.set(new int[]{429}); body.set("{\"error\":{\"message\":\"rate limit\"}}"); retryHdr.set("7");
        r = LlmClient.ask(url, "k", "m", "q", opts);
        check(r.idx == -1 && !r.fatal && r.retryAfterMs == 7000, "429 retry-after 7s -> wait 7s, not fatal");
        retryHdr.set("3600");
        r = LlmClient.ask(url, "k", "m", "q", opts);
        check(r.idx == -1 && r.fatal, "429 retry-after 1h (daily quota) -> fatal");
        retryHdr.set(null);
        code.set(new int[]{401}); body.set("{\"error\":{\"message\":\"Invalid API Key\"}}");
        r = LlmClient.ask(url, "k", "m", "q", opts); check(r.idx == -1 && r.fatal && r.error.contains("Invalid API Key"), "401 -> fatal with message");
        code.set(new int[]{503}); body.set("{}");
        r = LlmClient.ask(url, "k", "m", "q", opts); check(r.idx == -1 && !r.fatal, "503 -> retry, no answer");
        check(LlmClient.ask(url, "", "m", "q", opts).fatal, "empty key -> fatal, no request");

        // custom-provider behaviour
        check(LlmClient.normalizeUrl("https://api.cerebras.ai/v1/").equals("https://api.cerebras.ai/v1/chat/completions"), "base URL gets /chat/completions");
        check(LlmClient.normalizeUrl(" https://x/v1/chat/completions ").equals("https://x/v1/chat/completions"), "full URL kept");
        check(LlmClient.parseDigit("2", 3) == 1 && LlmClient.parseDigit("4", 3) == -1, "digit must be within the option count");
        List<String> two = Arrays.asList("A", "B");
        reply("2"); check(GroqLikeAsk(url, two) == 1, "two remaining options -> '2' is index 1");
        check(sentBody.get().contains("(1-2)") && sentBody.get().contains("\\n1. A\\n2. B") && !sentBody.get().contains("\\n3. "), "prompt only lists remaining options");
        LlmClient.allowHttpForTests = false;
        r = LlmClient.ask(url, "k", "m", "q", opts); check(r.idx == -1 && r.fatal && r.error.contains("https"), "plain http refused");
        LlmClient.allowHttpForTests = true;
        check(LlmClient.ask(url, "k", "", "q", opts).fatal, "empty model -> fatal");
        // provider that rejects max_tokens and wants max_completion_tokens -> retried once with the other name
        swapMode = true; reply("3");
        r = LlmClient.ask(url, "k", "gpt-4o-mini", "q", opts);
        check(r.idx == 2 && sentBody.get().contains("max_completion_tokens"), "400 'use max_completion_tokens' -> retried with it, answer taken");
        swapMode = false;
        s.stop(0);
        r = LlmClient.ask(url, "k", "m", "q", opts); check(r.idx == -1 && !r.fatal && r.error != null, "server down -> no answer, retry");
        System.out.println(fails == 0 ? "ALL PASSED" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
