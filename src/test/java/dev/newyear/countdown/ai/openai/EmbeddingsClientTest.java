package dev.newyear.countdown.ai.openai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import dev.newyear.countdown.ai.memory.MemoryRecord;
import dev.newyear.countdown.ai.memory.MemoryStore;
import dev.newyear.countdown.ai.memory.SemanticIndex;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class EmbeddingsClientTest {
    private HttpServer server;
    private final List<JsonObject> requests = new CopyOnWriteArrayList<>();

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    /** 입력 문장마다 [길이, 1, 0] 벡터를 거꾸로 된 순서로 돌려주는 가짜 /embeddings (index 로 순서를 맞추는지 확인). */
    private String start(int failFirst) throws Exception {
        AtomicInteger n = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", ex -> {
            JsonObject body = JsonParser.parseString(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            requests.add(body);
            String out;
            int code;
            if (n.incrementAndGet() <= failFirst) {
                code = 503;
                out = "{\"error\":{\"message\":\"busy\"}}";
            } else {
                code = 200;
                StringBuilder sb = new StringBuilder("{\"object\":\"list\",\"model\":\"text-embedding-3-small\",\"data\":[");
                var in = body.getAsJsonArray("input");
                for (int i = in.size() - 1; i >= 0; i--) {
                    sb.append("{\"object\":\"embedding\",\"index\":").append(i).append(",\"embedding\":[")
                            .append(in.get(i).getAsString().length()).append(",1,0]}");
                    if (i > 0) sb.append(',');
                }
                sb.append("],\"usage\":{\"prompt_tokens\":7,\"total_tokens\":7}}");
                out = sb.toString();
            }
            byte[] b = out.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(code, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    private OpenAiClient client(String base) {
        OpenAiClient c = new OpenAiClient(() -> new OpenAiClient.Settings(base, "sk-test", Duration.ofSeconds(5), 2));
        c.setSleeper(ms -> { });
        return c;
    }

    @Test
    void embedsInInputOrderNormalisedWithRetry() throws Exception {
        OpenAiClient c = client(start(1));
        OpenAiClient.Embeddings e = c.embed("text-embedding-3-small", List.of("가", "가나다라"), 3, null);
        assertEquals(2, requests.size(), "503 must be retried");
        JsonObject req = requests.get(1);
        assertEquals(3, req.get("dimensions").getAsInt());
        assertEquals("float", req.get("encoding_format").getAsString());
        assertFalse(req.toString().contains("sk-test"), "API key must never be in the body");
        assertEquals(7, e.tokens());
        // "가" → [1,1,0]/√2,  "가나다라" → [4,1,0]/√17
        assertEquals(1 / Math.sqrt(2), e.vectors().get(0)[0], 1e-5);
        assertEquals(4 / Math.sqrt(17), e.vectors().get(1)[0], 1e-5);
    }

    @Test
    void noKeyFailsFast() {
        OpenAiClient c = new OpenAiClient(() -> new OpenAiClient.Settings("http://127.0.0.1:1", "", Duration.ofSeconds(1), 0));
        ApiException ex = assertThrows(ApiException.class, () -> c.embed("m", List.of("x"), 0, null));
        assertEquals(ApiException.Kind.NO_API_KEY, ex.kind);
    }

    @Test
    void indexSyncEmbedsMissingMemoriesAndCachesQueries() throws Exception {
        OpenAiClient c = client(start(0));
        MemoryStore store = new MemoryStore();
        SemanticIndex idx = new SemanticIndex();
        idx.configure("text-embedding-3-small", 3);
        idx.attach(store);
        MemoryRecord r = new MemoryRecord();
        r.owner = "A";
        r.text = "고양이 나비";
        store.add(r, 1);
        AtomicLong tokens = new AtomicLong();
        SemanticIndex.Embedder emb = texts -> c.embed("text-embedding-3-small", texts, 3, null);
        // 같은 스레드에서 바로 실행하는 executor 로 동기화
        assertTrue(idx.sync(store.all(), 8, Runnable::run, Runnable::run, emb, tokens::addAndGet, System.currentTimeMillis()));
        assertNotNull(idx.vectorOf(r));
        assertEquals(7, tokens.get());
        assertFalse(idx.sync(store.all(), 8, Runnable::run, Runnable::run, emb, tokens::addAndGet, System.currentTimeMillis()), "nothing left to embed");

        float[] q1 = idx.embedQuery("고양이", emb, tokens::addAndGet);
        float[] q2 = idx.embedQuery("  고양이 ", emb, tokens::addAndGet);
        assertSame(q1, q2, "normalised query text hits the cache");
        assertEquals(2, requests.size());

        // 저장 → 불러오기
        SemanticIndex back = new SemanticIndex();
        back.configure("text-embedding-3-small", 3);
        back.load(idx.toJson(), null);
        assertEquals(1, back.size());
        assertTrue(MemoryStore.cosine(idx.vectorOf(r), back.vectorOf(r)) > 0.999);
    }
}
