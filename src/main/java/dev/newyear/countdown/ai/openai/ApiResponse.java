package dev.newyear.countdown.ai.openai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/** Responses API 응답에서 필요한 부분만 꺼낸 것. 원본 output 항목은 다음 요청 입력으로 되돌려 보낸다. */
public final class ApiResponse {
    public record FunctionCall(String callId, String name, String arguments) {}

    public record Usage(long input, long cachedInput, long output, long reasoning) {
        public static final Usage ZERO = new Usage(0, 0, 0, 0);

        public Usage plus(Usage o) {
            return new Usage(input + o.input, cachedInput + o.cachedInput, output + o.output, reasoning + o.reasoning);
        }
    }

    public final String id;
    public final String status;
    public final String incompleteReason;
    public final List<JsonObject> outputItems = new ArrayList<>();
    public final List<FunctionCall> functionCalls = new ArrayList<>();
    public final String outputText;
    public final String refusal;
    public final Usage usage;
    public final String model;

    private ApiResponse(String id, String status, String incompleteReason, String outputText, String refusal, Usage usage, String model) {
        this.id = id;
        this.status = status;
        this.incompleteReason = incompleteReason;
        this.outputText = outputText;
        this.refusal = refusal;
        this.usage = usage;
        this.model = model;
    }

    static String str(JsonObject o, String k) {
        JsonElement e = o == null ? null : o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    static long num(JsonObject o, String k) {
        JsonElement e = o == null ? null : o.get(k);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsLong() : 0;
    }

    static JsonObject obj(JsonObject o, String k) {
        JsonElement e = o == null ? null : o.get(k);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    public static ApiResponse parse(JsonObject root) {
        StringBuilder text = new StringBuilder();
        StringBuilder refusal = new StringBuilder();
        JsonArray output = root.has("output") && root.get("output").isJsonArray() ? root.getAsJsonArray("output") : new JsonArray();
        JsonObject u = obj(root, "usage");
        Usage usage = new Usage(num(u, "input_tokens"), num(obj(u, "input_tokens_details"), "cached_tokens"),
                num(u, "output_tokens"), num(obj(u, "output_tokens_details"), "reasoning_tokens"));
        List<FunctionCall> calls = new ArrayList<>();
        List<JsonObject> items = new ArrayList<>();
        for (JsonElement el : output) {
            if (!el.isJsonObject()) continue;
            JsonObject item = el.getAsJsonObject();
            items.add(item);
            String type = str(item, "type");
            if ("message".equals(type) && item.has("content") && item.get("content").isJsonArray()) {
                for (JsonElement c : item.getAsJsonArray("content")) {
                    if (!c.isJsonObject()) continue;
                    JsonObject co = c.getAsJsonObject();
                    String ct = str(co, "type");
                    if ("output_text".equals(ct) && str(co, "text") != null) text.append(str(co, "text"));
                    if ("refusal".equals(ct) && str(co, "refusal") != null) refusal.append(str(co, "refusal"));
                }
            } else if ("function_call".equals(type)) {
                String args = str(item, "arguments");
                calls.add(new FunctionCall(str(item, "call_id"), str(item, "name"), args == null ? "{}" : args));
            }
        }
        JsonObject inc = obj(root, "incomplete_details");
        ApiResponse r = new ApiResponse(str(root, "id"), str(root, "status"), str(inc, "reason"),
                text.toString(), refusal.isEmpty() ? null : refusal.toString(), usage, str(root, "model"));
        r.outputItems.addAll(items);
        r.functionCalls.addAll(calls);
        return r;
    }
}
