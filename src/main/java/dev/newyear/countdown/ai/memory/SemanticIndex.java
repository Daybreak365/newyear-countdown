package dev.newyear.countdown.ai.memory;

import com.google.gson.JsonObject;
import dev.newyear.countdown.ai.openai.ApiException;
import dev.newyear.countdown.ai.openai.OpenAiClient;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/**
 * 임베딩 기반 의미 검색 색인. 기억 문장을 OpenAI 임베딩 벡터로 바꿔 두고, 검색 때 질의 벡터와의 코사인 유사도를
 * 키워드 점수와 함께 쓴다 (MemoryStore.search). Minecraft 와 무관하다.
 * <ul>
 *   <li>벡터 표: 서버 스레드에서만 읽고 쓴다. 임베딩 요청은 작업 스레드에서 하고 결과는 main 으로 되돌려 적용한다.</li>
 *   <li>새 기억·바뀐 기억은 주기적인 동기화(sync)가 찾아 묶음으로 임베딩한다 — 기억을 쓰는 코드는 이 색인을 몰라도 된다.</li>
 *   <li>질의 임베딩은 작업 스레드에서 막고 기다리며(embedQuery), 서버 스레드는 캐시만 본다(cachedQuery).</li>
 *   <li>저장은 int8 양자화 + base64 (256 차원이면 기억 하나당 약 350 바이트).</li>
 * </ul>
 */
public final class SemanticIndex {
    /** 실제 임베딩 호출 (테스트에서는 가짜). 작업 스레드에서 불린다. */
    public interface Embedder {
        OpenAiClient.Embeddings embed(List<String> texts) throws ApiException;
    }

    private record Entry(String hash, float[] vec) {}

    private final Map<String, Entry> vectors = new HashMap<>();
    private final Map<String, float[]> queryCache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, float[]> e) {
            return size() > 128;
        }
    };
    private String model = "";
    private int dims;
    private volatile boolean busy;
    private long backoffUntil;
    private boolean dirty;
    /** 최근 오류 (관리자 상태 표시용). */
    public volatile String lastError = "";
    public volatile long embeddedTotal;

    // ---------------------------------------------------------------- 설정

    /** 모델이나 차원이 바뀌면 기존 벡터는 쓸 수 없으므로 버린다. */
    public void configure(String model, int dims) {
        if (!model.equals(this.model) || dims != this.dims) {
            vectors.clear();
            synchronized (queryCache) {
                queryCache.clear();
            }
            this.model = model;
            this.dims = dims;
            dirty = true;
        }
    }

    public String model() {
        return model;
    }

    public int size() {
        return vectors.size();
    }

    public boolean busy() {
        return busy;
    }

    public boolean isDirty() {
        return dirty;
    }

    public void clearDirty() {
        dirty = false;
    }

    // ---------------------------------------------------------------- 조회 (서버 스레드)

    /** 그 기억의 현재 내용에 맞는 벡터 (없거나 낡았으면 null). */
    public float[] vectorOf(MemoryRecord r) {
        Entry e = vectors.get(r.id);
        return e != null && e.hash.equals(r.hash) ? e.vec : null;
    }

    /** 이 색인을 기억 저장소의 벡터 공급원으로 꽂는다. */
    public void attach(MemoryStore store) {
        store.setVectors(this::vectorOf);
    }

    /** 아직 벡터가 없거나 내용이 바뀐 기억 (보관된 요약 원본은 건너뛴다). */
    public List<MemoryRecord> missing(Collection<MemoryRecord> all, int max) {
        List<MemoryRecord> out = new ArrayList<>();
        for (MemoryRecord r : all) {
            if (r.archived || r.text == null || r.text.isBlank()) continue;
            if (vectorOf(r) != null) continue;
            out.add(r);
            if (out.size() >= max) break;
        }
        return out;
    }

    /** 지워진 기억의 벡터를 버린다. */
    public int dropRemoved(Collection<MemoryRecord> all) {
        Set<String> ids = new HashSet<>();
        for (MemoryRecord r : all) ids.add(r.id);
        int before = vectors.size();
        vectors.keySet().removeIf(id -> !ids.contains(id));
        int n = before - vectors.size();
        if (n > 0) dirty = true;
        return n;
    }

    // ---------------------------------------------------------------- 갱신

    /**
     * 서버 스레드에서 주기적으로 부른다: 빠진 기억을 한 묶음 골라 작업 스레드에서 임베딩하고, 결과는 main 에서 적용한다.
     * @return 요청을 보냈으면 true
     */
    public boolean sync(Collection<MemoryRecord> all, int batch, Executor worker, Executor main, Embedder embedder, LongConsumer onTokens, long nowMs) {
        if (busy || nowMs < backoffUntil) return false;
        dropRemoved(all);
        List<MemoryRecord> todo = missing(all, Math.max(1, batch));
        if (todo.isEmpty()) return false;
        List<String> ids = new ArrayList<>(), hashes = new ArrayList<>(), texts = new ArrayList<>();
        for (MemoryRecord r : todo) {
            ids.add(r.id);
            hashes.add(r.hash);
            texts.add(embedText(r));
        }
        busy = true;
        try {
            worker.execute(() -> {
                try {
                    OpenAiClient.Embeddings res = embedder.embed(texts);
                    if (onTokens != null) onTokens.accept(res.tokens());
                    main.execute(() -> {
                        for (int i = 0; i < ids.size(); i++) put(ids.get(i), hashes.get(i), res.vectors().get(i));
                        embeddedTotal += ids.size();
                        lastError = "";
                        busy = false;
                    });
                } catch (ApiException | RuntimeException e) {
                    lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    main.execute(() -> {
                        backoffUntil = System.currentTimeMillis() + 60_000;
                        busy = false;
                    });
                }
            });
        } catch (RuntimeException rejected) {
            busy = false;
            return false;
        }
        return true;
    }

    void put(String id, String hash, float[] vec) {
        if (vec == null || (dims > 0 && vec.length != dims)) return;
        vectors.put(id, new Entry(hash, vec));
        dirty = true;
    }

    /** 임베딩할 문장: 본문 + 태그 + 이야기 줄기 (검색 질의와 같은 말로 이어지도록). */
    public static String embedText(MemoryRecord r) {
        StringBuilder sb = new StringBuilder(r.text);
        if (r.thread != null && !r.thread.isBlank()) sb.append(" | ").append(r.thread);
        if (r.tags != null && !r.tags.isEmpty()) sb.append(" | ").append(String.join(", ", r.tags));
        return sb.toString();
    }

    // ---------------------------------------------------------------- 질의

    private static String queryKey(String q) {
        String k = q == null ? "" : q.strip().replaceAll("\\s+", " ");
        return k.length() > 600 ? k.substring(0, 600) : k;
    }

    /** 작업 스레드 전용: 캐시에 없으면 바로 임베딩해서 기다린다. 실패하면 null (키워드 검색만 쓴다). */
    public float[] embedQuery(String query, Embedder embedder, LongConsumer onTokens) {
        String key = queryKey(query);
        if (key.isEmpty()) return null;
        synchronized (queryCache) {
            float[] hit = queryCache.get(key);
            if (hit != null) return hit;
        }
        try {
            OpenAiClient.Embeddings res = embedder.embed(List.of(key));
            if (onTokens != null) onTokens.accept(res.tokens());
            float[] v = res.vectors().get(0);
            synchronized (queryCache) {
                queryCache.put(key, v);
            }
            return v;
        } catch (ApiException | RuntimeException e) {
            lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return null;
        }
    }

    /** 서버 스레드용: 캐시에 있을 때만 돌려준다. 없으면 다음을 위해 작업 스레드에 미리 계산을 맡긴다. */
    public float[] cachedQuery(String query, Executor worker, Embedder embedder, LongConsumer onTokens) {
        String key = queryKey(query);
        if (key.isEmpty()) return null;
        synchronized (queryCache) {
            float[] hit = queryCache.get(key);
            if (hit != null) return hit;
        }
        if (worker != null) {
            try {
                worker.execute(() -> embedQuery(key, embedder, onTokens));
            } catch (RuntimeException ignored) {
                // 바쁘면 이번엔 키워드 검색만
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- 저장

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("model", model);
        o.addProperty("dims", dims);
        JsonObject entries = new JsonObject();
        for (Map.Entry<String, Entry> e : vectors.entrySet()) {
            JsonObject j = new JsonObject();
            j.addProperty("h", e.getValue().hash);
            j.addProperty("v", encode(e.getValue().vec));
            entries.add(e.getKey(), j);
        }
        o.add("entries", entries);
        return o;
    }

    public void load(JsonObject o, Consumer<String> warn) {
        vectors.clear();
        if (o == null) return;
        try {
            String m = o.has("model") ? o.get("model").getAsString() : "";
            int d = o.has("dims") ? o.get("dims").getAsInt() : 0;
            if (!m.equals(model) || d != dims) return;      // 설정이 바뀌었으면 다시 만든다
            JsonObject entries = o.getAsJsonObject("entries");
            if (entries == null) return;
            for (String id : entries.keySet()) {
                JsonObject j = entries.getAsJsonObject(id);
                float[] v = decode(j.get("v").getAsString());
                if (v != null && (dims <= 0 || v.length == dims)) vectors.put(id, new Entry(j.get("h").getAsString(), v));
            }
        } catch (RuntimeException e) {
            vectors.clear();
            if (warn != null) warn.accept("embedding index unreadable, rebuilding: " + e);
        }
    }

    /** int8 양자화 (정규화 벡터라 성분은 -1..1). */
    static String encode(float[] v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) b[i] = (byte) Math.max(-127, Math.min(127, Math.round(v[i] * 127f)));
        return Base64.getEncoder().encodeToString(b);
    }

    static float[] decode(String s) {
        byte[] b = Base64.getDecoder().decode(s);
        float[] v = new float[b.length];
        double norm = 0;
        for (int i = 0; i < b.length; i++) {
            v[i] = b[i] / 127f;
            norm += v[i] * v[i];
        }
        norm = Math.sqrt(norm);
        if (norm > 0) for (int i = 0; i < v.length; i++) v[i] /= (float) norm;
        return v;
    }
}
