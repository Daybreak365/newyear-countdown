package dev.newyear.countdown.ai.openai;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * OpenAI Responses API 클라이언트 (POST {apiBase}/responses) + 기억 검색용 임베딩 (POST {apiBase}/embeddings).
 * 호출하는 스레드를 블로킹하므로 반드시 AI 작업 스레드에서 부른다 (서버 메인 스레드 금지).
 * 일시적 오류(429/5xx/네트워크/시간 초과)는 지수 백오프로 재시도하고 Retry-After 를 따른다.
 */
public final class OpenAiClient {
    public record Settings(String apiBase, String apiKey, Duration timeout, int maxRetries) {}

    /** 요청 취소 표시. */
    public static final class CancelToken {
        private volatile boolean cancelled;
        private volatile CompletableFuture<?> inFlight;

        public void cancel() {
            cancelled = true;
            CompletableFuture<?> f = inFlight;
            if (f != null) f.cancel(true);
        }

        public boolean isCancelled() {
            return cancelled;
        }
    }

    /** 재시도 사이 대기 (테스트에서 바꿀 수 있다). */
    public interface Sleeper {
        void sleep(long ms) throws InterruptedException;
    }

    private final HttpClient http;
    private final Supplier<Settings> settings;
    private Sleeper sleeper = Thread::sleep;

    public OpenAiClient(Supplier<Settings> settings) {
        this.settings = settings;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .proxy(ProxySelector.getDefault())
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    public void setSleeper(Sleeper s) {
        this.sleeper = s;
    }

    public ApiResponse create(JsonObject body, CancelToken cancel) throws ApiException {
        return withRetries(body, cancel, (s, payload) -> {
            JsonObject root = send(s, "/responses", payload, cancel);
            ApiResponse r = ApiResponse.parse(root);
            if ("failed".equals(r.status)) {
                JsonObject err = ApiResponse.obj(root, "error");
                throw new RetryableHttp(new ApiException(ApiException.Kind.SERVER, 200, "response failed: " + ApiResponse.str(err, "message")), -1);
            }
            return r;
        });
    }

    /** 임베딩 결과: 입력 순서대로의 벡터와 사용 토큰. */
    public record Embeddings(List<float[]> vectors, long tokens, String model) {}

    /**
     * POST {apiBase}/embeddings. 같은 재시도·오류 분류를 쓴다. 벡터는 L2 정규화해서 돌려준다.
     * dimensions 가 0 이하면 모델 기본 차원.
     */
    public Embeddings embed(String model, List<String> inputs, int dimensions, CancelToken cancel) throws ApiException {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        com.google.gson.JsonArray in = new com.google.gson.JsonArray();
        for (String t : inputs) in.add(t);
        body.add("input", in);
        if (dimensions > 0) body.addProperty("dimensions", dimensions);
        body.addProperty("encoding_format", "float");
        return withRetries(body, cancel, (s, payload) -> {
            JsonObject root = send(s, "/embeddings", payload, cancel);
            List<float[]> out = new java.util.ArrayList<>(java.util.Collections.nCopies(inputs.size(), (float[]) null));
            JsonElement data = root.get("data");
            if (data == null || !data.isJsonArray()) throw new RetryableHttp(new ApiException(ApiException.Kind.SERVER, 200, "embeddings response has no data"), -1);
            for (JsonElement el : data.getAsJsonArray()) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                int idx = o.has("index") ? o.get("index").getAsInt() : -1;
                JsonElement emb = o.get("embedding");
                if (idx < 0 || idx >= out.size() || emb == null || !emb.isJsonArray()) continue;
                com.google.gson.JsonArray arr = emb.getAsJsonArray();
                float[] v = new float[arr.size()];
                double norm = 0;
                for (int i = 0; i < v.length; i++) {
                    v[i] = arr.get(i).getAsFloat();
                    norm += v[i] * v[i];
                }
                norm = Math.sqrt(norm);
                if (norm > 0) for (int i = 0; i < v.length; i++) v[i] /= (float) norm;
                out.set(idx, v);
            }
            for (float[] v : out) {
                if (v == null) throw new ApiException(ApiException.Kind.INVALID_OUTPUT, "embeddings response is missing an entry");
            }
            JsonObject u = ApiResponse.obj(root, "usage");
            long tokens = Math.max(ApiResponse.num(u, "total_tokens"), ApiResponse.num(u, "prompt_tokens"));
            return new Embeddings(out, tokens, ApiResponse.str(root, "model"));
        });
    }

    private interface Attempt<T> {
        T run(Settings s, String payload) throws ApiException, RetryableHttp;
    }

    private <T> T withRetries(JsonObject body, CancelToken cancel, Attempt<T> attemptFn) throws ApiException {
        Settings s = settings.get();
        if (s.apiKey() == null || s.apiKey().isEmpty()) {
            throw new ApiException(ApiException.Kind.NO_API_KEY, "No session API key or OPENAI_API_KEY is set on the server");
        }
        String payload = body.toString();
        ApiException last = null;
        for (int attempt = 0; attempt <= s.maxRetries(); attempt++) {
            if (cancel != null && cancel.isCancelled()) throw new ApiException(ApiException.Kind.CANCELLED, "cancelled");
            long waitMs;
            try {
                return attemptFn.run(s, payload);
            } catch (RetryableHttp r) {
                last = r.error;
                waitMs = r.retryAfterMs;
            } catch (ApiException e) {
                if (!e.retryable()) throw e;
                last = e;
                waitMs = -1;
            }
            if (attempt == s.maxRetries()) break;
            if (waitMs < 0) waitMs = (long) (800 * Math.pow(2, attempt)) + ThreadLocalRandom.current().nextLong(400);
            waitMs = Math.min(waitMs, 30_000);
            try {
                sleeper.sleep(waitMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new ApiException(ApiException.Kind.CANCELLED, "interrupted");
            }
        }
        throw last == null ? new ApiException(ApiException.Kind.NETWORK, "request failed") : last;
    }

    private static final class RetryableHttp extends Exception {
        final ApiException error;
        final long retryAfterMs;

        RetryableHttp(ApiException error, long retryAfterMs) {
            super(error.getMessage(), null, false, false);
            this.error = error;
            this.retryAfterMs = retryAfterMs;
        }
    }

    /** 한 번 보내고 2xx 본문(JSON 객체)을 돌려준다. 재시도할 오류는 RetryableHttp. */
    private JsonObject send(Settings s, String path, String payload, CancelToken cancel) throws ApiException, RetryableHttp {
        HttpRequest req = HttpRequest.newBuilder(URI.create(s.apiBase() + path))
                .timeout(s.timeout())
                .header("Authorization", "Bearer " + s.apiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
        CompletableFuture<HttpResponse<String>> f = http.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (cancel != null) cancel.inFlight = f;
        HttpResponse<String> resp;
        try {
            resp = f.get(s.timeout().toMillis() + 5_000, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.CancellationException e) {
            throw new ApiException(ApiException.Kind.CANCELLED, "cancelled");
        } catch (InterruptedException e) {
            f.cancel(true);
            Thread.currentThread().interrupt();
            throw new ApiException(ApiException.Kind.CANCELLED, "interrupted");
        } catch (TimeoutException e) {
            f.cancel(true);
            throw new ApiException(ApiException.Kind.TIMEOUT, "request timed out");
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof HttpTimeoutException) throw new ApiException(ApiException.Kind.TIMEOUT, "request timed out");
            if (c instanceof IOException) throw new ApiException(ApiException.Kind.NETWORK, "network error: " + c.getClass().getSimpleName());
            throw new ApiException(ApiException.Kind.NETWORK, "request failed: " + (c == null ? e : c).getClass().getSimpleName());
        } finally {
            if (cancel != null) cancel.inFlight = null;
        }
        int code = resp.statusCode();
        String bodyText = resp.body() == null ? "" : resp.body();
        if (code >= 200 && code < 300) {
            JsonObject root;
            try {
                JsonElement el = JsonParser.parseString(bodyText);
                if (!el.isJsonObject()) throw new IllegalStateException("not an object");
                root = el.getAsJsonObject();
            } catch (RuntimeException e) {
                throw new RetryableHttp(new ApiException(ApiException.Kind.SERVER, code, "malformed response body"), -1);
            }
            return root;
        }
        String msg = errorMessage(bodyText, s.apiKey());
        String errCode = errorCode(bodyText);
        long retryAfter = retryAfterMs(resp);
        switch (code) {
            case 401, 403 -> throw new ApiException(ApiException.Kind.AUTH, code, "authentication failed: check the API key and model access");
            case 429 -> {
                if ("insufficient_quota".equals(errCode)) throw new ApiException(ApiException.Kind.QUOTA, code, "quota exceeded: " + msg);
                throw new RetryableHttp(new ApiException(ApiException.Kind.RATE_LIMIT, code, "rate limited: " + msg), retryAfter);
            }
            case 408, 409, 500, 502, 503, 504 -> throw new RetryableHttp(new ApiException(ApiException.Kind.SERVER, code, "server error " + code + ": " + msg), retryAfter);
            default -> throw new ApiException(ApiException.Kind.BAD_REQUEST, code, "request rejected (" + code + "): " + msg);
        }
    }

    private static long retryAfterMs(HttpResponse<?> resp) {
        Optional<String> ms = resp.headers().firstValue("retry-after-ms");
        if (ms.isPresent()) {
            try { return (long) Double.parseDouble(ms.get()); } catch (NumberFormatException ignored) { }
        }
        Optional<String> sec = resp.headers().firstValue("retry-after");
        if (sec.isPresent()) {
            try { return (long) (Double.parseDouble(sec.get()) * 1000); } catch (NumberFormatException ignored) { }
        }
        return -1;
    }

    private static String errorMessage(String body, String key) {
        try {
            JsonObject o = JsonParser.parseString(body).getAsJsonObject();
            JsonObject err = ApiResponse.obj(o, "error");
            String m = ApiResponse.str(err, "message");
            if (m != null) return redact(m, key, 300);
        } catch (RuntimeException ignored) {
            // 본문이 JSON 이 아니면 앞부분만
        }
        return redact(body, key, 200);
    }

    private static String redact(String message, String key, int limit) {
        String safe = key == null || key.isEmpty() ? message : message.replace(key, "[redacted]");
        safe = safe.replaceAll("sk-[A-Za-z0-9_.*-]+", "[redacted]");
        return safe.length() > limit ? safe.substring(0, limit) : safe;
    }

    private static String errorCode(String body) {
        try {
            JsonObject o = JsonParser.parseString(body).getAsJsonObject();
            return ApiResponse.str(ApiResponse.obj(o, "error"), "code");
        } catch (RuntimeException e) {
            return null;
        }
    }
}
