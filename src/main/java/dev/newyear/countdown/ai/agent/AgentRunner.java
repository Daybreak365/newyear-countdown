package dev.newyear.countdown.ai.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.newyear.countdown.ai.openai.ApiException;
import dev.newyear.countdown.ai.openai.ApiResponse;
import dev.newyear.countdown.ai.openai.OpenAiClient;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 도구 호출 루프. 모델이 함수를 부르면 실행해 결과를 돌려주고, 최종 구조화 응답이 나올 때까지 반복한다.
 * Minecraft 와 무관하다 — 도구 실행은 {@link ToolExecutor} 가 맡는다 (서버 스레드 전환은 그쪽 책임).
 */
public final class AgentRunner {
    public interface ToolExecutor {
        /** 결과는 모델에 그대로 전달할 문자열 (보통 JSON). 예외를 던지지 말고 오류도 문자열로 돌려준다. */
        String execute(String name, String argumentsJson);
    }

    public record Request(String model, String reasoningEffort, String instructions, List<JsonObject> input,
                          JsonArray tools, JsonObject outputSchema, String schemaName, int maxRounds, int maxCalls,
                          int maxOutputTokens, boolean store, String cacheKey, String safetyId) {}

    public record ToolTrace(String name, String arguments, String result, long millis) {}

    public static final class Result {
        public String text = "";
        public JsonObject json;
        public ApiResponse.Usage usage = ApiResponse.Usage.ZERO;
        public int rounds;
        public int apiCalls;
        public final List<ToolTrace> trace = new ArrayList<>();
        public boolean truncated;
    }

    private final OpenAiClient client;

    public AgentRunner(OpenAiClient client) {
        this.client = client;
    }

    public Result run(Request req, ToolExecutor tools, OpenAiClient.CancelToken cancel, Consumer<ApiResponse.Usage> onUsage,
                      Consumer<String> onStatus) throws ApiException {
        Result result = new Result();
        List<JsonObject> context = new ArrayList<>(req.input());
        List<JsonObject> pending = new ArrayList<>(req.input());
        String previousId = null;
        int totalCalls = 0;
        int repairs = 0;
        boolean toolsAllowed = true;

        while (true) {
            if (cancel != null && cancel.isCancelled()) throw new ApiException(ApiException.Kind.CANCELLED, "cancelled");
            JsonObject body = buildBody(req, req.store() && previousId != null ? pending : context, previousId, toolsAllowed);
            ApiResponse r = client.create(body, cancel);
            result.apiCalls++;
            result.usage = result.usage.plus(r.usage);
            if (onUsage != null) onUsage.accept(r.usage);
            previousId = r.id;
            pending = new ArrayList<>();
            context.addAll(r.outputItems);

            if (!r.functionCalls.isEmpty() && toolsAllowed) {
                result.rounds++;
                for (ApiResponse.FunctionCall call : r.functionCalls) {
                    String out;
                    long t0 = System.nanoTime();
                    if (++totalCalls > req.maxCalls()) {
                        out = "{\"error\":\"Tool call limit for this turn reached. Stop calling tools and give your final answer now.\"}";
                    } else {
                        if (onStatus != null) onStatus.accept(call.name());
                        out = tools.execute(call.name(), call.arguments());
                    }
                    long ms = (System.nanoTime() - t0) / 1_000_000;
                    result.trace.add(new ToolTrace(call.name(), call.arguments(), out, ms));
                    JsonObject item = new JsonObject();
                    item.addProperty("type", "function_call_output");
                    item.addProperty("call_id", call.callId());
                    item.addProperty("output", out);
                    context.add(item);
                    pending.add(item);
                }
                if (result.rounds >= req.maxRounds()) {
                    // 라운드 한도: 도구 없이 마무리하게 한다
                    toolsAllowed = false;
                    JsonObject nudge = userMessage("[system] Tool round limit reached. Do not call tools. Reply now with the final JSON.");
                    context.add(nudge);
                    pending.add(nudge);
                }
                continue;
            }

            // 최종 응답
            String text = r.outputText == null ? "" : r.outputText.trim();
            if (text.isEmpty() && r.refusal != null) {
                result.text = r.refusal;
                result.json = fallbackJson(r.refusal);
                return result;
            }
            JsonObject parsed = tryParse(text);
            if (parsed != null && (req.outputSchema() == null || hasRequired(parsed, req.outputSchema()))) {
                result.text = text;
                result.json = parsed;
                result.truncated = "incomplete".equals(r.status);
                return result;
            }
            if (req.outputSchema() == null && !text.isEmpty()) {
                result.text = text;
                return result;
            }
            // 출력 복구: 한두 번까지 다시 요청
            if (repairs++ < 2) {
                String why = "incomplete".equals(r.status) ? "Your answer was cut off (" + r.incompleteReason + "). Be brief." :
                        "Your last message was not a valid JSON object matching the required schema.";
                JsonObject nudge = userMessage("[system] " + why + " Reply again with ONLY the final JSON object.");
                context.add(nudge);
                pending.add(nudge);
                toolsAllowed = toolsAllowed && !"incomplete".equals(r.status);
                continue;
            }
            if (!text.isEmpty()) {
                // 마지막 수단: 평문을 대사로 사용
                result.text = text;
                result.json = fallbackJson(text);
                return result;
            }
            throw new ApiException(ApiException.Kind.INVALID_OUTPUT, "model returned no usable output");
        }
    }

    private JsonObject buildBody(Request req, List<JsonObject> input, String previousId, boolean toolsAllowed) {
        JsonObject body = new JsonObject();
        body.addProperty("model", req.model());
        if (req.instructions() != null) body.addProperty("instructions", req.instructions());
        JsonArray in = new JsonArray();
        for (JsonObject o : input) in.add(o);
        body.add("input", in);
        if (req.store() && previousId != null) body.addProperty("previous_response_id", previousId);
        if (req.tools() != null && !req.tools().isEmpty()) {
            body.add("tools", req.tools());
            body.addProperty("tool_choice", toolsAllowed ? "auto" : "none");
            body.addProperty("parallel_tool_calls", true);
        }
        if (req.reasoningEffort() != null && !req.reasoningEffort().isEmpty() && !req.reasoningEffort().equals("none")) {
            JsonObject reasoning = new JsonObject();
            reasoning.addProperty("effort", req.reasoningEffort());
            body.add("reasoning", reasoning);
        }
        if (req.outputSchema() != null) {
            JsonObject format = new JsonObject();
            format.addProperty("type", "json_schema");
            format.addProperty("name", req.schemaName() == null ? "reply" : req.schemaName());
            format.addProperty("strict", true);
            format.add("schema", req.outputSchema());
            JsonObject text = new JsonObject();
            text.add("format", format);
            body.add("text", text);
        }
        if (req.maxOutputTokens() > 0) body.addProperty("max_output_tokens", req.maxOutputTokens());
        body.addProperty("store", req.store());
        if (!req.store()) {
            JsonArray include = new JsonArray();
            include.add("reasoning.encrypted_content");
            body.add("include", include);
        }
        if (req.cacheKey() != null) body.addProperty("prompt_cache_key", req.cacheKey());
        if (req.safetyId() != null) body.addProperty("safety_identifier", req.safetyId());
        return body;
    }

    public static JsonObject userMessage(String text) {
        JsonObject m = new JsonObject();
        m.addProperty("role", "user");
        m.addProperty("content", text);
        return m;
    }

    public static JsonObject developerMessage(String text) {
        JsonObject m = new JsonObject();
        m.addProperty("role", "developer");
        m.addProperty("content", text);
        return m;
    }

    public static JsonObject assistantMessage(String text) {
        JsonObject m = new JsonObject();
        m.addProperty("role", "assistant");
        m.addProperty("content", text);
        return m;
    }

    static JsonObject tryParse(String text) {
        if (text == null || text.isEmpty()) return null;
        String t = text.trim();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            int end = t.lastIndexOf("```");
            if (nl > 0 && end > nl) t = t.substring(nl + 1, end).trim();
        }
        int s = t.indexOf('{'), e = t.lastIndexOf('}');
        if (s < 0 || e <= s) return null;
        try {
            JsonElement el = JsonParser.parseString(t.substring(s, e + 1));
            return el.isJsonObject() ? el.getAsJsonObject() : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static boolean hasRequired(JsonObject obj, JsonObject schema) {
        if (!schema.has("required")) return true;
        for (JsonElement k : schema.getAsJsonArray("required")) if (!obj.has(k.getAsString())) return false;
        return true;
    }

    private static JsonObject fallbackJson(String text) {
        JsonObject o = new JsonObject();
        o.addProperty("say", text.length() > 600 ? text.substring(0, 600) : text);
        o.addProperty("expect_reply", false);
        o.add("quick_replies", new JsonArray());
        o.addProperty("mood", "neutral");
        return o;
    }
}
