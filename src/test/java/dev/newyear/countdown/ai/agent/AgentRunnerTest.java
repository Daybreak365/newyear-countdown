package dev.newyear.countdown.ai.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import dev.newyear.countdown.ai.openai.ApiException;
import dev.newyear.countdown.ai.openai.OpenAiClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class AgentRunnerTest {
    private HttpServer server;
    private final List<JsonObject> requests = new CopyOnWriteArrayList<>();
    private final List<String> authHeaders = new CopyOnWriteArrayList<>();

    record Reply(int code, String body, String retryAfterMs) {}

    private String start(Function<Integer, Reply> handler) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(JsonParser.parseString(body).getAsJsonObject());
            authHeaders.add(ex.getRequestHeaders().getFirst("Authorization"));
            Reply r = handler.apply(requests.size());
            if (r.retryAfterMs() != null) ex.getResponseHeaders().add("retry-after-ms", r.retryAfterMs());
            byte[] out = r.body().getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(r.code(), out.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(out); }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
        dev.newyear.countdown.ai.AiConfig.clearSessionApiKey();
    }

    static String functionCall(String name, String args) {
        return "{\"id\":\"resp_1\",\"status\":\"completed\",\"output\":[{\"type\":\"reasoning\",\"id\":\"rs_1\",\"encrypted_content\":\"x\"},"
                + "{\"type\":\"function_call\",\"id\":\"fc_1\",\"call_id\":\"call_1\",\"name\":\"" + name + "\",\"arguments\":" + new com.google.gson.JsonPrimitive(args) + "}],"
                + "\"usage\":{\"input_tokens\":100,\"input_tokens_details\":{\"cached_tokens\":40},\"output_tokens\":20,\"output_tokens_details\":{\"reasoning_tokens\":5}}}";
    }

    static String finalText(String text) {
        return "{\"id\":\"resp_2\",\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":"
                + new com.google.gson.JsonPrimitive(text) + "}]}],\"usage\":{\"input_tokens\":150,\"output_tokens\":30}}";
    }

    private AgentRunner.Request request(int maxRounds) {
        return request(maxRounds, "gpt-6.1-terra");
    }

    private AgentRunner.Request request(int maxRounds, String model) {
        JsonObject schema = JsonParser.parseString("{\"type\":\"object\",\"properties\":{\"say\":{\"type\":\"string\"}},\"required\":[\"say\"],\"additionalProperties\":false}").getAsJsonObject();
        JsonArray tools = JsonParser.parseString("[{\"type\":\"function\",\"name\":\"give\",\"parameters\":{\"type\":\"object\",\"properties\":{}}}]").getAsJsonArray();
        return new AgentRunner.Request(model, "medium", "be nice", new ArrayList<>(List.of(AgentRunner.userMessage("빵 하나 줘"))),
                tools, schema, "reply", maxRounds, 10, 2000, false, "k", "sid");
    }

    private OpenAiClient client(String base) {
        OpenAiClient c = new OpenAiClient(() -> new OpenAiClient.Settings(base, "sk-test", Duration.ofSeconds(5), 3));
        c.setSleeper(ms -> { });
        return c;
    }

    @Test
    void unfinishedQuestMakesTheModelImplementBeforeItsFinalReply() throws Exception {
        var pendingQuest = new java.util.concurrent.atomic.AtomicBoolean(true);
        String base = start(n -> n == 1 ? new Reply(200, finalText("{\"say\":\"과제를 시작해라\"}"), null)
                : n == 2 ? new Reply(200, functionCall("give", "{}"), null)
                : new Reply(200, finalText("{\"say\":\"준비가 끝났다\"}"), null));
        var result = new AgentRunner(client(base)).run(request(5), (name, args) -> {
            pendingQuest.set(false); return "{\"tracker_ready\":true}";
        }, null, null, null, () -> pendingQuest.get() ? "Implement the hidden quest condition tracker before replying." : "");
        assertEquals(3, requests.size());
        assertEquals("준비가 끝났다", result.json.get("say").getAsString());
        assertTrue(requests.get(1).getAsJsonArray("input").toString().contains("condition tracker"));
        assertEquals("auto", requests.get(1).get("tool_choice").getAsString());
    }

    @Test
    void missingImplementationRetriesAreBounded() throws Exception {
        String base = start(n -> new Reply(200, finalText("{\"say\":\"준비 중\"}"), null));
        new AgentRunner(client(base)).run(request(5), (a, b) -> "{}", null, null, null, () -> "Tracker missing.");
        assertEquals(3, requests.size());
    }

    @Test
    void implementationRetryDoesNotBypassToolRoundLimit() throws Exception {
        String base = start(n -> n == 1 ? new Reply(200, functionCall("give", "{}"), null)
                : new Reply(200, finalText("{\"say\":\"아직 준비 중\"}"), null));
        new AgentRunner(client(base)).run(request(1), (a, b) -> "{}", null, null, null, () -> "Tracker missing.");
        assertEquals(2, requests.size());
        assertEquals("none", requests.get(1).get("tool_choice").getAsString());
    }

    @Test
    void temporaryKeyIsUsedOnlyInAuthorizationAndCanBeReplaced() throws Exception {
        String base = start(n -> new Reply(200, finalText("{\"say\":\"ok\"}"), null));
        var player = java.util.UUID.randomUUID();
        var config = dev.newyear.countdown.ai.AiConfig.class;
        String first = "sk-session-first-test-only", second = "sk-session-second-test-only";
        OpenAiClient liveSettings = new OpenAiClient(() -> new OpenAiClient.Settings(base,
                dev.newyear.countdown.ai.AiConfig.apiKey(), Duration.ofSeconds(5), 0));
        dev.newyear.countdown.ai.AiConfig.setSessionApiKey(player, first);
        new AgentRunner(liveSettings).run(request(1), (a, b) -> "{}", null, null, null);
        dev.newyear.countdown.ai.AiConfig.setSessionApiKey(player, second);
        new AgentRunner(liveSettings).run(request(1), (a, b) -> "{}", null, null, null);
        assertEquals(List.of("Bearer " + first, "Bearer " + second), authHeaders);
        for (JsonObject body : requests) {
            assertFalse(body.toString().contains(first));
            assertFalse(body.toString().contains(second));
        }
    }

    @Test
    void authenticationErrorsDoNotEchoCredentials() throws Exception {
        String base = start(n -> new Reply(401, "{\"error\":{\"message\":\"Incorrect key sk-test\"}}", null));
        ApiException error = assertThrows(ApiException.class, () -> new AgentRunner(client(base)).run(request(1), (a, b) -> "{}", null, null, null));
        assertEquals(ApiException.Kind.AUTH, error.kind);
        assertFalse(error.getMessage().contains("sk-test"));
    }

    @Test
    void rejectedRequestRedactsKeyBeforeTruncatingErrorText() throws Exception {
        String message = "x".repeat(296) + "sk-test";
        String body = "{\"error\":{\"message\":" + new com.google.gson.JsonPrimitive(message) + "}}";
        String base = start(n -> new Reply(400, body, null));
        ApiException error = assertThrows(ApiException.class, () -> new AgentRunner(client(base)).run(request(1), (a, b) -> "{}", null, null, null));
        assertFalse(error.getMessage().contains("sk-"));
    }

    @Test
    void solModelIsSentThroughoutToolRoundTrip() throws Exception {
        String base = start(n -> n == 1 ? new Reply(200, functionCall("give", "{\"item\":\"bread\"}"), null)
                : new Reply(200, finalText("{\"say\":\"완료\"}"), null));
        String model = new dev.newyear.countdown.ai.AiConfig().model;
        assertEquals("gpt-6.1-sol", model);
        var result = new AgentRunner(client(base)).run(request(3, model), (name, args) -> "{\"ok\":true}", null, null, null);
        assertEquals("완료", result.json.get("say").getAsString());
        assertEquals(2, requests.size());
        for (JsonObject body : requests) {
            assertEquals("gpt-6.1-sol", body.get("model").getAsString());
            assertEquals("medium", body.getAsJsonObject("reasoning").get("effort").getAsString());
        }
    }

    @Test
    void toolLoopRoundTrip() throws Exception {
        String base = start(n -> n == 1 ? new Reply(200, functionCall("give", "{\"item\":\"bread\"}"), null)
                : new Reply(200, finalText("{\"say\":\"옜다, 빵이다.\"}"), null));
        List<String> executed = new ArrayList<>();
        AgentRunner.Result r = new AgentRunner(client(base)).run(request(5), (name, args) -> {
            executed.add(name + args);
            return "{\"ok\":true}";
        }, new OpenAiClient.CancelToken(), u -> { }, s -> { });
        assertEquals(List.of("give{\"item\":\"bread\"}"), executed);
        assertEquals("옜다, 빵이다.", r.json.get("say").getAsString());
        assertEquals(2, r.apiCalls);
        assertEquals(250, r.usage.input());
        assertEquals(40, r.usage.cachedInput());
        // 두 번째 요청에는 원래 출력 항목과 함수 결과가 함께 들어가야 한다
        JsonArray input2 = requests.get(1).getAsJsonArray("input");
        boolean hasOutput = false, hasCall = false, hasReasoning = false;
        for (var e : input2) {
            String type = e.getAsJsonObject().has("type") ? e.getAsJsonObject().get("type").getAsString() : "";
            if (type.equals("function_call_output")) { hasOutput = true; assertEquals("call_1", e.getAsJsonObject().get("call_id").getAsString()); }
            if (type.equals("function_call")) hasCall = true;
            if (type.equals("reasoning")) hasReasoning = true;
        }
        assertTrue(hasOutput && hasCall && hasReasoning);
        assertEquals("medium", requests.get(0).getAsJsonObject("reasoning").get("effort").getAsString());
        assertEquals("json_schema", requests.get(0).getAsJsonObject("text").getAsJsonObject("format").get("type").getAsString());
        assertEquals("Bearer sk-test", authHeaders.get(0));
        assertFalse(requests.get(0).toString().contains("sk-test"), "API key must never be in the body");
    }

    @Test
    void retriesRateLimitThenSucceeds() throws Exception {
        String base = start(n -> n == 1 ? new Reply(429, "{\"error\":{\"message\":\"slow down\",\"code\":\"rate_limit_exceeded\"}}", "10")
                : n == 2 ? new Reply(503, "{\"error\":{\"message\":\"overloaded\"}}", null)
                : new Reply(200, finalText("{\"say\":\"ok\"}"), null));
        AgentRunner.Result r = new AgentRunner(client(base)).run(request(5), (a, b) -> "{}", null, null, null);
        assertEquals("ok", r.json.get("say").getAsString());
        assertEquals(3, requests.size());
    }

    @Test
    void quotaIsNotRetried() throws Exception {
        String base = start(n -> new Reply(429, "{\"error\":{\"message\":\"no money\",\"code\":\"insufficient_quota\"}}", null));
        ApiException e = assertThrows(ApiException.class, () -> new AgentRunner(client(base)).run(request(5), (a, b) -> "{}", null, null, null));
        assertEquals(ApiException.Kind.QUOTA, e.kind);
        assertEquals(1, requests.size());
    }

    @Test
    void repairsInvalidOutput() throws Exception {
        String base = start(n -> n == 1 ? new Reply(200, finalText("I will give you bread!"), null)
                : new Reply(200, finalText("{\"say\":\"fixed\"}"), null));
        AgentRunner.Result r = new AgentRunner(client(base)).run(request(5), (a, b) -> "{}", null, null, null);
        assertEquals("fixed", r.json.get("say").getAsString());
        assertEquals(2, requests.size());
    }

    @Test
    void roundLimitForcesFinalAnswer() throws Exception {
        String base = start(n -> n <= 2 ? new Reply(200, functionCall("give", "{}"), null)
                : new Reply(200, finalText("{\"say\":\"done\"}"), null));
        AgentRunner.Result r = new AgentRunner(client(base)).run(request(2), (a, b) -> "{}", null, null, null);
        assertEquals("done", r.json.get("say").getAsString());
        assertEquals("none", requests.get(2).get("tool_choice").getAsString());
    }

    @Test
    void missingKeyFailsFast() {
        OpenAiClient c = new OpenAiClient(() -> new OpenAiClient.Settings("http://127.0.0.1:1/v1", "", Duration.ofSeconds(1), 0));
        ApiException e = assertThrows(ApiException.class, () -> new AgentRunner(c).run(request(1), (a, b) -> "{}", null, null, null));
        assertEquals(ApiException.Kind.NO_API_KEY, e.kind);
    }
}
